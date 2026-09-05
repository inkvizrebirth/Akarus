package com.dreamcast.client.util;

import com.dreamcast.client.DreamcastClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Автоустановка Baritone: найти версию под текущую игру, скачать в {@code mods/},
 * попросить перезапуск.
 *
 * <p>Почему скачиванием, а не «вшитым» jar. Baritone не публикуется в maven, его
 * сборки привязаны к конкретной версии игры, а лицензия — GPL-3.0: распространять
 * его внутри нашего CC0-мода нельзя (в отличие от Sodium/Lithium, которые
 * вшиваются как nested jar). Поэтому мы оставляем только <b>инструкцию</b>
 * установить мод: на старте клиент смотрит, загружен ли Baritone, и если нет —
 * берёт подходящий jar с Modrinth (там есть метаданные {@code game_versions},
 * в отличие от GitHub-релизов, где имя файла версии игры не несёт).</p>
 *
 * <p>Всё происходится на отдельном демоно-потоке и молча проваливается при любой
 * ошибке: отсутствие сети, Modrinth 503, «нет сборки под 26.2» — это не повод
 * не запускать игру. Правила выбора (что качать, когда не качать, как проверить
 * файл) — в {@link ModInstallLogic}, они покрыты тестами.</p>
 */
public final class ModInstaller {

	/** Проект на Modrinth. */
	private static final String PROJECT = "baritone";
	private static final String API = "https://api.modrinth.com/v2/project/" + PROJECT + "/version";
	/** Разумный предел: мод Baritone — единицы мегабайт; больше — точно не он. */
	private static final long MAX_BYTES = 32L * 1024 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private static volatile boolean running;
	private static volatile String lastError = "";

	private ModInstaller() {
	}

	/** Загружен ли Baritone в этой сессии игры (класс API виден загрузчику). */
	public static boolean isLoaded() {
		try {
			Class.forName("baritone.api.BaritoneAPI", false, ModInstaller.class.getClassLoader());
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * Проверяет состояние и, если нужно, запускает скачивание в фоне.
	 *
	 * @param autoInstall разрешена ли автоустановка настройкой модуля
	 * @return что решено — для сообщения в чат/уведомление
	 */
	public static ModInstallLogic.Decision checkAndInstall(boolean autoInstall) {
		if (running) {
			return ModInstallLogic.Decision.LOADED; // уже качаем — не плошим решения
		}
		if (running) {
			return ModInstallLogic.Decision.BUSY; // уже качаем — не принимаем решений повторно
		}
		ModInstallLogic.Decision decision = ModInstallLogic.decide(isLoaded(), hasOnDisk(), autoInstall);
		switch (decision) {
			case LOADED -> {
				lastError = "";
			}
			case NEEDS_RESTART -> Notifications.ok("Baritone",
					"Мод уже лежит в mods/ — перезапусти игру, чтобы он подхватился");
			case DISABLED -> {
				// пользователь сам решил ставить руками: молча выходим
			}
			case DOWNLOAD -> start();
		}
		return decision;
	}

	/** То же, но по кнопке из меню: качаем всегда, если мода нет. */
	public static void installNow() {
		if (isLoaded()) {
			Notifications.info("Baritone", "Уже установлен и работает — ничего делать не нужно");
			return;
		}
		if (hasOnDisk()) {
			Notifications.warn("Baritone", "Файл есть в mods/ — нужен перезапуск игры");
			return;
		}
		start();
	}

	public static String lastError() {
		return lastError;
	}

	// ------------------------------------------------------------------
	// Диск
	// ------------------------------------------------------------------

	private static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	private static boolean hasOnDisk() {
		return firstModFile() != null;
	}

	/** Имя уже скачанного jar (если он есть) — чтобы не плодить копии. */
	private static String firstModFile() {
		Path dir = modsDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			List<String> names = new ArrayList<>();
			for (Path path : stream) {
				names.add(path.getFileName().toString());
			}
			for (String name : names) {
				if (ModInstallLogic.hasModFile(List.of(name), PROJECT)) {
					return name;
				}
			}
		} catch (IOException error) {
			lastError = "mods/ не читается: " + error;
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Сеть
	// ------------------------------------------------------------------

	private static void start() {
		running = true;
		Thread worker = new Thread(ModInstaller::download, "dreamcast-mod-installer");
		worker.setDaemon(true);
		worker.start();
	}

	private static void download() {
		try {
			String gameVersion = gameVersion();
			String url = findJarUrl(gameVersion);
			if (url == null) {
				lastError = "сборки Baritone под Minecraft " + gameVersion + " на Modrinth нет";
				Notifications.warn("Baritone",
						"Нет сборки под Minecraft " + gameVersion + " — проверь mods вручную (Modrinth/GitHub)");
				return;
			}
			Path target = install(url);
			Notifications.ok("Baritone", "Скачан " + target.getFileName() + " — перезапусти игру, чтобы мод подхватился");
			DreamcastClient.LOGGER.info("Baritone установлен: {}", target);
		} catch (Exception error) {
			lastError = error.toString();
			DreamcastClient.LOGGER.warn("Baritone автоустановка не удалась: {}", error.toString());
			Notifications.error("Baritone", "Скачать не удалось: " + shortError(error));
		} finally {
			running = false;
		}
	}

	private static String shortError(Exception error) {
		String message = error.getMessage();
		if (message == null || message.isBlank()) {
			return error.getClass().getSimpleName();
		}
		return message.length() > 120 ? message.substring(0, 120) : message;
	}

	private static String gameVersion() {
		try {
			String normalized = FabricLoader.getInstance().getGameProvider().getNormalizedGameVersion();
			return normalized == null || normalized.isBlank() ? "26.2" : normalized;
		} catch (Throwable ignored) {
			return "26.2";
		}
	}

	private static HttpClient client() {
		return HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(TIMEOUT)
				.build();
	}

	private static HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.header("User-Agent", "Dreamcast/" + DreamcastClient.MOD_VERSION + " (mod installer)");
	}

	/**
	 * Ищет ссылку на jar у первой подходящей версии мода.
	 *
	 * <p>Запрос отдаёт список версий, отсортированный Modrinth по актуальности, —
	 * берём первую, у которой есть файл-кандидат (Fabric-вариант).</p>
	 */
	private static String findJarUrl(String gameVersion) throws IOException, InterruptedException {
		String url = API + "?" + ModInstallLogic.modrinthQuery(gameVersion);
		HttpResponse<String> response = client().send(request(url)
				.header("Accept", "application/json")
				.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() != 200) {
			lastError = "Modrinth ответил " + response.statusCode();
			return null;
		}
		JsonElement root = JsonParser.parseString(response.body());
		if (!root.isJsonArray()) {
			return null;
		}
		JsonArray versions = root.getAsJsonArray();
		for (JsonElement entry : versions) {
			if (!entry.isJsonObject()) {
				continue;
			}
			JsonObject version = entry.getAsJsonObject();
			JsonArray files = version.has("files") ? version.getAsJsonArray("files") : new JsonArray();
			List<String> names = new ArrayList<>();
			for (JsonElement file : files) {
				if (file.isJsonObject() && file.getAsJsonObject().has("filename")) {
					names.add(file.getAsJsonObject().get("filename").getAsString());
				}
			}
			String pick = ModInstallLogic.pickFile(names);
			if (pick == null) {
				continue;
			}
			for (JsonElement file : files) {
				JsonObject object = file.getAsJsonObject();
				if (pick.equals(object.get("filename").getAsString()) && object.has("url")) {
					return object.get("url").getAsString();
				}
			}
		}
		return null;
	}

	/** Качает в {@code mods/<имя>.part}, проверяет заголовок zip и атомарно переименовывает. */
	private static Path install(String downloadUrl) throws IOException, InterruptedException {
		String fileName = downloadUrl.substring(downloadUrl.lastIndexOf('/') + 1);
		if (fileName.isBlank() || fileName.contains("..") || fileName.contains("/")) {
			throw new IOException("непонятное имя файла: " + fileName);
		}
		Path dir = modsDir();
		Files.createDirectories(dir);
		Path temp = dir.resolve(fileName + ".part");
		Path target = dir.resolve(fileName);

		HttpResponse<Path> response = client().send(request(downloadUrl).GET().build(),
				HttpResponse.BodyHandlers.ofFile(temp));
		if (response.statusCode() != 200) {
			Files.deleteIfExists(temp);
			throw new IOException("Modrinth отдал " + response.statusCode());
		}
		long size = Files.size(temp);
		if (size < ModInstallLogic.MIN_JAR_BYTES || size > MAX_BYTES || !startsWithZip(temp)) {
			Files.deleteIfExists(temp);
			throw new IOException("скачанный файл не похож на мод (" + size + " байт)");
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	private static boolean startsWithZip(Path path) throws IOException {
		try (InputStream stream = Files.newInputStream(path, StandardOpenOption.READ)) {
			byte[] head = new byte[4];
			if (stream.read(head) != head.length) {
				return false;
			}
			return ModInstallLogic.looksLikeJar(head[0] & 0xFF, head[1] & 0xFF, head[2] & 0xFF, head[3] & 0xFF);
		}
	}

	/** Оставляет след в логе при старте клиента: есть ли мод и что с автоустановкой. */
	public static void reportAtStartup() {
		String onDisk = firstModFile();
		DreamcastClient.LOGGER.info("Baritone: {}{} {}", isLoaded() ? "загружен" : "не загружен",
				onDisk == null ? "" : (", файл " + onDisk + " в mods/"),
				lastError.isEmpty() ? "" : (", последняя ошибка: " + lastError));
	}

	/** Каталог, куда кладём мод — используется экраном «Прочее» и отладкой. */
	public static Path modsDirectory() {
		return modsDir();
	}
}

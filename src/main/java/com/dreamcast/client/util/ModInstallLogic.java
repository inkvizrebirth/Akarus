package com.dreamcast.client.util;

import java.util.List;
import java.util.Locale;

/**
 * Чистая логика автоустановки сторонних модов (пока — Baritone).
 *
 * <p>Решения «ставить ли», «что качать» и «похож ли файл на jar» вынесены из
 * {@link ModInstaller}, потому что сеть и диск в юнит-тесте не проверить, а
 * эти три правила — ровно то, на чём можно наломать дров: поставить «не тот»
 * файл (Forge вместо Fabric), перезалить мод каждый запуск или записать в
 * {@code mods/} HTML-страницу ошибки вместо jar.</p>
 */
public final class ModInstallLogic {

	/** Что решили по итогам проверки. */
	public enum Decision {
		/** Мод уже загружен в игру — ничего делать не надо. */
		LOADED,
		/** Файл лежит в {@code mods/}, но игра его ещё не подхватила — нужен перезапуск. */
		NEEDS_RESTART,
		/** Можно скачивать. */
		DOWNLOAD,
		/** Ставить нельзя: скачивание отключено настройкой. */
		DISABLED,
		/** Скачивание уже идёт — повторный запуск не нужен. */
		BUSY
	}

	/** Минимальный правдоподобный размер мода: меньше — это страница ошибки, а не jar. */
	public static final long MIN_JAR_BYTES = 64L * 1024L;

	private ModInstallLogic() {
	}

	/**
	 * Выбор файла из списка вариантов версии: нужен Fabric-вариант (мы ставим его
	 * как мод), а не {@code -forge}/{@code -neoforge}/{@code -api}.
	 */
	public static String pickFile(List<String> filenames) {
		if (filenames == null || filenames.isEmpty()) {
			return null;
		}
		String fallback = null;
		for (String name : filenames) {
			if (name == null || name.isBlank()) {
				continue;
			}
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".jar") && !lower.contains("sources") && !lower.contains("javadoc")) {
				if (fallback == null) {
					fallback = name;
				}
				if (lower.contains("fabric")) {
					return name;
				}
			}
		}
		return fallback;
	}

	/** Ставить, ждать перезапуска или не трогать. */
	public static Decision decide(boolean loadedInGame, boolean jarOnDisk, boolean autoInstallEnabled) {
		if (loadedInGame) {
			return Decision.LOADED;
		}
		if (jarOnDisk) {
			return Decision.NEEDS_RESTART;
		}
		return autoInstallEnabled ? Decision.DOWNLOAD : Decision.DISABLED;
	}

	/** Первые байты zip-архива («PK\\x03\\x04») — быстрая проверка, что скачался jar. */
	public static boolean looksLikeJar(int b0, int b1, int b2, int b3) {
		return b0 == 'P' && b1 == 'K' && (b2 == 3 || b2 == 5 || b2 == 7) && (b3 == 4 || b3 == 6 || b3 == 8);
	}

	/** Файл в {@code mods/} с таким модом уже есть? */
	public static boolean hasModFile(List<String> fileNames, String modId) {
		if (fileNames == null || modId == null) {
			return false;
		}
		String needle = modId.toLowerCase(Locale.ROOT);
		for (String name : fileNames) {
			if (name == null) {
				continue;
			}
			String lower = name.toLowerCase(Locale.ROOT);
			if ((lower.endsWith(".jar") || lower.endsWith(".litemod")) && lower.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	/** Строка запроса к Modrinth: версии игры и загрузчик (значения URL-кодируются вручную). */
	public static String modrinthQuery(String gameVersion) {
		String version = gameVersion == null ? "" : gameVersion.trim();
		StringBuilder query = new StringBuilder("loaders=[\"fabric\"]");
		if (!version.isEmpty()) {
			query.append("&game_versions=%5B%22")
					.append(version.replaceAll("[\"\[\]\s]", ""))
					.append("%22%5D");
		}
		return query.toString();
	}
}

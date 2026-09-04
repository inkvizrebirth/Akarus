package com.dreamcast.client.gui.hud;

import com.dreamcast.client.DreamcastClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;

import java.util.HashMap;
import java.util.Map;

/**
 * Раскладка HUD: позиция каждого элемента на экране (в логических пикселях
 * от левого верхнего угла). Хранится отдельным файлом hud.json рядом с конфигом.
 *
 * Позиции по умолчанию задаёт сам элемент ({@link #defaultX}/{@link #defaultY}
 * вызываются с дефолтом), а перетаскивание — в редакторе HUD (клавиша модуля HUD)
 * или прямо с открытым чатом: клик по элементу — и тащишь.
 */
public final class HudLayout {

	/** Описание одного элемента: id + позиция по умолчанию. */
	public record ElementSpec(String id, int defaultX, int defaultY) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir()
			.resolve(DreamcastClient.MOD_ID + "-hud.json");

	private static final Map<String, int[]> POSITIONS = new HashMap<>();

	/** Границы элемента в момент перетаскивания (обновляются при отрисовке). */
	private static final Map<String, int[]> LAST_BOUNDS = new HashMap<>();

	/** Строку («id:x:y;...») пишем в один файл — правится руками без боли. */
	private static String encoded = "";

	private HudLayout() {
	}

	public static void load() {
		POSITIONS.clear();
		if (!Files.exists(PATH)) {
			return;
		}
		try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonObject parsed = GSON.fromJson(reader, JsonObject.class);
			if (parsed == null || parsed.get("elements") == null) {
				return;
			}
			for (String entry : parsed.get("elements").getAsString().split(";")) {
				String[] parts = entry.split(":");
				if (parts.length != 3) {
					continue;
				}
				try {
					POSITIONS.put(parts[0], new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
				} catch (NumberFormatException ignored) {
					// битая запись — пропускаем, элемент встанет на дефолт
				}
			}
		} catch (IOException | RuntimeException exception) {
			DreamcastClient.LOGGER.warn("Не удалось прочитать раскладку HUD {}", PATH, exception);
		}
	}

	public static synchronized void save() {
		StringBuilder builder = new StringBuilder();
		// Стабильный порядок делает файл воспроизводимым и не создаёт случайные diff.
		for (Map.Entry<String, int[]> entry : new java.util.TreeMap<>(POSITIONS).entrySet()) {
			if (!builder.isEmpty()) {
				builder.append(';');
			}
			builder.append(entry.getKey()).append(':')
					.append(entry.getValue()[0]).append(':')
					.append(entry.getValue()[1]);
		}
		encoded = builder.toString();

		JsonObject root = new JsonObject();
		root.addProperty("elements", encoded);
		try {
			Files.createDirectories(PATH.getParent());
			Path temp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
			Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
			try {
				Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			DreamcastClient.LOGGER.error("Не удалось сохранить раскладку HUD {}", PATH, exception);
		}
	}

	/** Позиция элемента: сохранённая или дефолтная. */
	public static int[] position(ElementSpec spec) {
		int[] saved = POSITIONS.get(spec.id());
		return saved != null ? saved : new int[]{spec.defaultX(), spec.defaultY()};
	}

	/** Позиция по id (для перетаскивания, где спецификация уже не нужна). */
	public static int[] position(String id, int defaultX, int defaultY) {
		int[] saved = POSITIONS.get(id);
		return saved != null ? saved : new int[]{defaultX, defaultY};
	}

	public static void setPosition(String id, int x, int y) {
		POSITIONS.put(id, new int[]{x, y});
	}

	public static void reset(String id) {
		POSITIONS.remove(id);
		save();
	}

	public static void resetAll() {
		POSITIONS.clear();
		save();
	}

	// ------------------------------------------------------------------
	// Перетаскивание (общее для редактора HUD и «с открытым чатом»)
	// ------------------------------------------------------------------

	private static String draggingId;
	private static int dragOffsetX;
	private static int dragOffsetY;

	/** Начать перетаскивание элемента в точке (mouseX, mouseY). */
	public static boolean startDrag(double mouseX, double mouseY) {
		for (Map.Entry<String, int[]> entry : LAST_BOUNDS.entrySet()) {
			int[] bounds = entry.getValue();
			if (bounds != null && mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
					&& mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3]) {
				draggingId = entry.getKey();
				dragOffsetX = (int) mouseX - bounds[0];
				dragOffsetY = (int) mouseY - bounds[1];
				return true;
			}
		}
		return false;
	}

	public static boolean isDragging() {
		return draggingId != null;
	}

	/** Двигает перетаскиваемый элемент к курсору. */
	public static void dragTo(double mouseX, double mouseY, int screenW, int screenH) {
		if (draggingId == null) {
			return;
		}
		int[] bounds = LAST_BOUNDS.get(draggingId);
		int width = bounds == null ? 10 : bounds[2];
		int height = bounds == null ? 10 : bounds[3];
		int x = (int) Math.max(0, Math.min(screenW - width, mouseX - dragOffsetX));
		int y = (int) Math.max(0, Math.min(screenH - height, mouseY - dragOffsetY));
		setPosition(draggingId, x, y);
	}

	public static void endDrag() {
		if (draggingId != null) {
			draggingId = null;
			save();
		}
	}

	/** Помнит границы элемента этого кадра — по ним ловится клик при перетаскивании. */
	public static void publishBounds(String id, int x, int y, int width, int height) {
		LAST_BOUNDS.put(id, new int[]{x, y, width, height});
	}

	/** Удаляет геометрию прошлого кадра, чтобы скрытый элемент нельзя было тащить. */
	public static void beginFrame() {
		LAST_BOUNDS.clear();
	}

	public static Map<String, int[]> boundsSnapshot() {
		return new HashMap<>(LAST_BOUNDS);
	}
}

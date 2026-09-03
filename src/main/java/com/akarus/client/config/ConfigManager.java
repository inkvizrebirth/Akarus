package com.akarus.client.config;

import com.akarus.client.AkarusClient;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.Setting;
import com.akarus.client.settings.StringSetting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Простой конфиг в формате JSON: config/akarus.json
 *
 * Структура:
 * <pre>
 * {
 *   "modules": {
 *     "hud_info": { "enabled": true, "settings": { "fps": true, "ping": false } }
 *   }
 * }
 * </pre>
 */
public final class ConfigManager {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(AkarusClient.MOD_ID + ".json");

	/** Содержимое файла до момента создания модулей. */
	private static JsonObject pending;

	private ConfigManager() {
	}

	/** Читает файл конфига в память. Значения применяются в {@link #applyTo(Module)}. */
	public static void load() {
		pending = null;

		if (!Files.exists(PATH)) {
			return;
		}

		try (BufferedReader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
			JsonObject parsed = GSON.fromJson(reader, JsonObject.class);
			pending = parsed == null ? null : parsed;
		} catch (IOException | com.google.gson.JsonParseException exception) {
			AkarusClient.LOGGER.error("Не удалось прочитать конфиг {}", PATH, exception);
		}
	}

	/** Применяет сохранённые значения к конкретному модулю. */
	public static void applyTo(Module module) {
		if (pending == null) {
			return;
		}

		JsonObject modules = getObject(pending, "modules");
		if (modules == null) {
			return;
		}

		JsonObject data = getObject(modules, module.getId());
		if (data == null) {
			return;
		}

		if (data.has("enabled")) {
			module.setEnabledSilently(data.get("enabled").getAsBoolean());
		}

		JsonObject settings = getObject(data, "settings");
		if (settings == null) {
			return;
		}

		for (Setting<?> setting : module.getSettings()) {
			JsonElement value = settings.get(setting.getId());
			if (value == null || !value.isJsonPrimitive()) {
				continue;
			}

			// Конфиг редактируется руками, поэтому значения применяем «мягко»
			try {
				if (setting instanceof BooleanSetting) {
					setRaw(setting, value.getAsBoolean());
				} else if (setting instanceof IntSetting intSetting) {
					intSetting.set((int) Math.round(value.getAsDouble()));
				} else if (setting instanceof StringSetting stringSetting) {
					stringSetting.set(value.getAsString());
				}
			} catch (RuntimeException exception) {
				AkarusClient.LOGGER.warn("Не удалось применить настройку {}.{}", module.getId(), setting.getId(), exception);
			}
		}
	}

	/** Сохраняет состояние всех модулей на диск. */
	public static void save() {
		JsonObject root = new JsonObject();
		JsonObject modules = new JsonObject();

		for (Module module : ModuleManager.getAll()) {
			JsonObject data = new JsonObject();
			data.addProperty("enabled", module.isEnabled());

			if (!module.getSettings().isEmpty()) {
				JsonObject settings = new JsonObject();
				for (Setting<?> setting : module.getSettings()) {
					Object value = setting.getValue();
					if (value instanceof Boolean booleanValue) {
						settings.addProperty(setting.getId(), booleanValue);
					} else if (value instanceof Number numberValue) {
						settings.addProperty(setting.getId(), numberValue);
					} else if (value instanceof String stringValue) {
						settings.addProperty(setting.getId(), stringValue);
					}
				}
				data.add("settings", settings);
			}

			modules.add(module.getId(), data);
		}

		root.add("modules", modules);

		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			AkarusClient.LOGGER.error("Не удалось сохранить конфиг {}", PATH, exception);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setRaw(Setting setting, Object value) {
		setting.setValue(value);
	}

	private static JsonObject getObject(JsonObject parent, String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}
}

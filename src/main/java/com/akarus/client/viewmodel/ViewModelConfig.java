package com.akarus.client.viewmodel;

import com.akarus.client.AkarusClient;
import com.akarus.client.viewmodel.ViewModelProfile.Parameter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Сохранение раскладки рук в {@code config/akarus_viewmodel.json}.
 *
 * Отдельный файл намеренно: раскладку правят часто и прямо во время игры,
 * поэтому её удобнее держать рядом с конфигом, но не смешивать с настройками модулей.
 */
public final class ViewModelConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve(AkarusClient.MOD_ID + "_viewmodel.json");

	private ViewModelConfig() {
	}

	/** Читает раскладку с диска. Если файла нет или он битый — возвращает значения по умолчанию. */
	public static ViewModelProfile load() {
		ViewModelProfile profile = ViewModelProfile.createDefault();

		if (!Files.exists(PATH)) {
			return profile;
		}

		try {
			JsonObject root = GSON.fromJson(Files.readString(PATH, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null) {
				return profile;
			}
			for (Parameter parameter : Parameter.values()) {
				if (root.has(parameter.name()) && root.get(parameter.name()).isJsonPrimitive()) {
					profile.set(parameter, root.get(parameter.name()).getAsFloat());
				}
			}
		} catch (IOException | RuntimeException exception) {
			AkarusClient.LOGGER.error("Не удалось прочитать раскладку рук {}", PATH, exception);
		}

		return profile;
	}

	/** Пишет раскладку на диск. */
	public static void save(ViewModelProfile profile) {
		JsonObject root = new JsonObject();
		for (Parameter parameter : Parameter.values()) {
			root.addProperty(parameter.name(), profile.get(parameter));
		}

		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			AkarusClient.LOGGER.error("Не удалось сохранить раскладку рук {}", PATH, exception);
		}
	}
}

package com.dreamcast.client.util;

import com.dreamcast.client.DreamcastClient;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Alt Manager: список оффлайн-аккаунтов и мгновенное переключение.
 *
 * <p>Аккаунты хранятся в {@code config/dreamcast-alts.json}. Переключение —
 * подмена {@code Minecraft#user} через reflection (поле final, но класс не
 * модульный): новый ник и offline-UUID применяются к следующему подключению.
 * Смена ника на лету не трогает текущую сессию — так безопаснее.</p>
 *
 * <p>Также помнит последний сервер: если игрока кикнуло, экран отключения
 * предложит переподключиться — в том числе сразу со случайным ником.</p>
 */
public final class AltsManager {

	/** Один альт: просто ник (оффлайн-аккаунт). */
	public record Alt(String name) {
	}

	private static final List<Alt> alts = new ArrayList<>();
	private static String activeName = "";
	private static volatile ServerData lastServer;
	private static Field userField;

	private AltsManager() {
	}

	private static Path path() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("dreamcast-alts.json");
	}

	// ------------------------------------------------------------------
	// Хранение
	// ------------------------------------------------------------------

	public static synchronized void load() {
		alts.clear();
		Path path = path();
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject root = new GsonBuilder().create().fromJson(Files.readString(path), JsonObject.class);
			if (root == null) {
				return;
			}
			if (root.has("active")) {
				activeName = root.get("active").getAsString();
			}
			if (root.has("alts")) {
				for (var element : root.getAsJsonArray("alts")) {
					alts.add(new Alt(element.getAsJsonObject().get("name").getAsString()));
				}
			}
		} catch (Exception error) {
			DreamcastClient.LOGGER.warn("Не удалось прочитать альты: {}", error.toString());
		}
	}

	public static synchronized void save() {
		JsonObject root = new JsonObject();
		root.addProperty("active", activeName);
		JsonArray array = new JsonArray();
		for (Alt alt : alts) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", alt.name());
			array.add(entry);
		}
		root.add("alts", array);
		try {
			Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (IOException error) {
			DreamcastClient.LOGGER.warn("Не удалось сохранить альты: {}", error.toString());
		}
	}

	// ------------------------------------------------------------------
	// Список
	// ------------------------------------------------------------------

	public static synchronized List<Alt> list() {
		return List.copyOf(alts);
	}

	public static synchronized String activeName() {
		return activeName;
	}

	public static synchronized void add(String name) {
		name = name.trim();
		if (name.isEmpty() || name.length() > 16 || !name.matches("[A-Za-z0-9_]+")) {
			return;
		}
		for (Alt alt : alts) {
			if (alt.name().equals(name)) {
				return;
			}
		}
		alts.add(new Alt(name));
		save();
	}

	public static synchronized void remove(int index) {
		if (index >= 0 && index < alts.size()) {
			alts.remove(index);
			save();
		}
	}

	// ------------------------------------------------------------------
	// Переключение аккаунта
	// ------------------------------------------------------------------

	/** Применяет ник к клиенту (следующее подключение — уже под ним). */
	public static synchronized boolean loginAs(String name) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return false;
		}
		try {
			if (userField == null) {
				userField = Minecraft.class.getDeclaredField("user");
				userField.setAccessible(true);
			}
			UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
			net.minecraft.client.User user = new net.minecraft.client.User(
					name, offlineId, "0", Optional.empty(), Optional.empty());
			userField.set(client, user);
			activeName = name;
			save();
			DreamcastClient.LOGGER.info("Аккаунт переключён: {}", name);
			return true;
		} catch (Throwable error) {
			DreamcastClient.LOGGER.warn("Не удалось переключить аккаунт: {}", error.toString());
			return false;
		}
	}

	/** Случайный ник «ПрилагательноеСуществующееNN». */
	public static String randomNick() {
		String[] adjectives = {"Swift", "Dark", "Iron", "Silent", "Neon", "Frost", "Shadow", "Wild", "Lucky", "Cyber"};
		String[] nouns = {"Wolf", "Falcon", "Rider", "Ghost", "Blade", "Storm", "Hunter", "Raven", "Fox", "Knight"};
		return adjectives[RANDOM.nextInt(adjectives.length)]
				+ nouns[RANDOM.nextInt(nouns.length)]
				+ (10 + RANDOM.nextInt(90));
	}

	private static final java.util.Random RANDOM = new java.util.Random();

	// ------------------------------------------------------------------
	// Последний сервер (для реконнекта после кика)
	// ------------------------------------------------------------------

	public static void rememberServer(@Nullable ServerData data) {
		lastServer = data;
	}

	@Nullable
	public static ServerData lastServer() {
		return lastServer;
	}
}

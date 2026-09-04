package com.dreamcast.client.module.impl;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.ButtonSetting;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Macros — свои команды на горячих клавишах.
 *
 * <p>Число команд не ограничено. У каждой своя клавиша (GLFW-код);
 * нажатие мгновенно отправляет команду в чат сервера (или в клиент, если
 * начинается с {@code /}). Работает и в GUI (кроме чата и наших экранов
 * ввода), и в игре.</p>
 *
 * <p>Список хранится отдельно от общего конфига — {@code config/dreamcast-macros.json},
 * чтобы кнопка «редактировать» не мешала остальным настройкам.</p>
 */
public class MacroModule extends Module {

	/** Технический предохранитель от переполнения списка, а не пользовательский лимит. */
	public static final int MAX_MACROS = Integer.MAX_VALUE;

	/** Один макрос: команда + клавиша (GLFW-код или -1 = не задана). */
	public record Macro(String command, int key) {
	}

	private final List<Macro> macros = new ArrayList<>();
	/** Анти-повтор: когда макрос последний раз срабатывал. */
	/** Анти-повтор: команда → время последнего срабатывания (мс). */
	private final java.util.HashMap<String, Long> cooldowns = new java.util.HashMap<>();
	private final Path store;

	public MacroModule() {
		super("macros", "Macros", "Команды на горячих клавишах без лимита",
				ModuleCategory.MISC, GLFW.GLFW_KEY_UNKNOWN);
		this.store = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("dreamcast-macros.json");

		load();
		addSetting(new ButtonSetting("edit", "Макросы", "Редактировать…", this::openEditor));
	}

	@Override
	protected boolean defaultEnabled() {
		return true;
	}

	private void openEditor() {
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			client.gui.setScreen(new com.dreamcast.client.gui.screens.DreamcastMacrosScreen(client.gui.screen(), this));
		}
	}

	// ------------------------------------------------------------------
	// Хранение
	// ------------------------------------------------------------------

	public synchronized void load() {
		macros.clear();
		cooldowns.clear(); // иначе после перечитывания остаются записи удалённых команд
		if (!Files.exists(store)) {
			return;
		}
		try {
			JsonObject root = new GsonBuilder().setPrettyPrinting().create().fromJson(
					Files.readString(store), JsonObject.class);
			if (root == null || !root.has("macros") || !root.get("macros").isJsonArray()) {
				return;
			}
			for (var element : root.getAsJsonArray("macros")) {
				if (macros.size() >= MAX_MACROS || !element.isJsonObject()) {
					break;
				}
				JsonObject entry = element.getAsJsonObject();
				if (!entry.has("command") || !entry.get("command").isJsonPrimitive()) {
					continue;
				}
				String command = entry.get("command").getAsString().trim();
				if (command.isEmpty()) {
					continue;
				}
				macros.add(new Macro(command,
						entry.has("key") && entry.get("key").isJsonPrimitive()
								? entry.get("key").getAsInt() : -1));
			}
		} catch (Exception error) {
			DreamcastClient.LOGGER.warn("Не удалось прочитать макросы: {}", error.toString());
		}
	}

	public synchronized void save() {
		JsonObject root = new JsonObject();
		JsonArray array = new JsonArray();
		for (Macro macro : macros) {
			JsonObject entry = new JsonObject();
			entry.addProperty("command", macro.command());
			entry.addProperty("key", macro.key());
			array.add(entry);
		}
		root.add("macros", array);
		try {
			Files.createDirectories(store.getParent());
			Path temp = store.resolveSibling(store.getFileName() + ".tmp");
			Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root),
					StandardCharsets.UTF_8);
			try {
				Files.move(temp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.move(temp, store, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException error) {
			DreamcastClient.LOGGER.warn("Не удалось сохранить макросы: {}", error.toString());
		}
	}

	// ------------------------------------------------------------------
	// Управление списком
	// ------------------------------------------------------------------

	public synchronized List<Macro> macros() {
		return List.copyOf(macros);
	}

	public synchronized boolean canAdd() {
		return macros.size() < MAX_MACROS;
	}

	public synchronized void add(String command) {
		command = command.trim();
		if (command.isEmpty() || !canAdd()) {
			return;
		}
		macros.add(new Macro(command, -1));
		save();
	}

	public synchronized void remove(int index) {
		if (index >= 0 && index < macros.size()) {
			cooldowns.remove(macros.get(index).command());
			macros.remove(index);
			save();
		}
	}

	public synchronized void setKey(int index, int key) {
		if (index < 0 || index >= macros.size()) {
			return;
		}
		Macro old = macros.get(index);
		macros.set(index, new Macro(old.command(), key));
		save();
	}

	// ------------------------------------------------------------------
	// Исполнение
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || client.getConnection() == null) {
			return;
		}
		// В чате и на экранах ввода клавиши принадлежат полю, не макросам
		if (client.gui.screen() != null) {
			return;
		}

		long window = client.getWindow().handle();
		long now = Util.getMillis();
		for (Macro macro : macros()) {
			int key = macro.key();
			if (key < 0 || GLFW.glfwGetKey(window, key) != GLFW.GLFW_PRESS) {
				continue;
			}
			// Повтор: не чаще раза в 500 мс на команду (анти-спам при зажатии)
			if (now - lastFired(macro.command()) < 500) {
				continue;
			}
			run(client, macro.command());
			touch(macro.command(), now);
		}
	}

	private long lastFired(String command) {
		return cooldowns.getOrDefault(command, 0L);
	}

	private void touch(String command, long timestamp) {
		cooldowns.put(command, timestamp);
	}

	private void run(Minecraft client, String command) {
		String text = command.startsWith("/") ? command.substring(1) : command;
		if (command.startsWith("/")) {
			client.getConnection().sendCommand(text);
		} else {
			client.getConnection().sendChat(command);
		}
	}
}

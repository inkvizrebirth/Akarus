package com.dreamcast.client.module.impl;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Macros — свои команды на горячих клавишах.
 *
 * <p>Лимит — {@value #MAX_MACROS} команд. У каждой своя клавиша (GLFW-код);
 * нажатие мгновенно отправляет команду в чат сервера (или в клиент, если
 * начинается с {@code /}). Работает и в GUI (кроме чата и наших экранов
 * ввода), и в игре.</p>
 *
 * <p>Список хранится отдельно от общего конфига — {@code config/dreamcast-macros.json},
 * чтобы кнопка «редактировать» не мешала остальным настройкам.</p>
 */
public class MacroModule extends Module {

	public static final int MAX_MACROS = 10;

	/** Один макрос: команда + клавиша (GLFW-код или -1 = не задана). */
	public record Macro(String command, int key) {
	}

	private final List<Macro> macros = new ArrayList<>();
	/** Анти-повтор: когда макрос последний раз срабатывал. */
	private final List<String> cooldowns = new ArrayList<>();
	private final Path store;

	public MacroModule() {
		super("macros", "Macros", "Команды на горячих клавишах (лимит " + MAX_MACROS + ")",
				ModuleCategory.MISC, GLFW.GLFW_KEY_UNKNOWN);
		this.store = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("dreamcast-macros.json");

		load();
	 addButton:
		addSetting(new ButtonSetting("edit", "Редактировать макросы…", this::openEditor));
	}

	@Override
	protected boolean defaultEnabled() {
		return true;
	}

	private void openEditor() {
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			client.gui.setScreen(new com.dreamcast.client.gui.screens.DreamcastMacrosScreen(null, this));
		}
	}

	// ------------------------------------------------------------------
	// Хранение
	// ------------------------------------------------------------------

	public synchronized void load() {
		macros.clear();
		if (!Files.exists(store)) {
			return;
		}
		try {
			JsonObject root = new GsonBuilder().setPrettyPrinting().create().fromJson(
					Files.readString(store), JsonObject.class);
			if (root == null || !root.has("macros")) {
				return;
			}
			for (var element : root.getAsJsonArray("macros")) {
				JsonObject entry = element.getAsJsonObject();
				String command = entry.get("command").getAsString();
				macros.add(new Macro(command,
						entry.has("key") ? entry.get("key").getAsInt() : -1));
				cooldowns.add(command + "=0");
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
		cooldowns.clear();
		root.add("macros", array);
		try {
			Files.writeString(store, new GsonBuilder().setPrettyPrinting().create().toJson(root));
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
		cooldowns.add(command + "=0");
		save();
	}

	public synchronized void remove(int index) {
		if (index >= 0 && index < macros.size()) {
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
			if (key < 0 || !GLFW.glfwGetKey(window, key)) {
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
		for (String entry : cooldowns) {
			int eq = entry.lastIndexOf('=');
			if (entry.substring(0, eq).equals(command)) {
				return Long.parseLong(entry.substring(eq + 1));
			}
		}
		return 0L;
	}

	private void touch(String command, long timestamp) {
		for (int i = 0; i < cooldowns.size(); i++) {
			String entry = cooldowns.get(i);
			int eq = entry.lastIndexOf('=');
			if (entry.substring(0, eq).equals(command)) {
				cooldowns.set(i, command + "=" + timestamp);
				return;
			}
		}
		cooldowns.add(command + "=" + timestamp);
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

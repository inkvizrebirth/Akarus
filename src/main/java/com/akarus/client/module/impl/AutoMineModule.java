package com.akarus.client.module.impl;

import com.akarus.client.AkarusClient;
import com.akarus.client.baritone.BaritoneBridge;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.StringSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * AutoMine — автоматическая добыча блоков через Baritone.
 *
 * Нужен установленный мод Baritone (Fabric, версия для 26.2).
 * Что и сколько добывать задаётся в настройках модуля.
 */
public class AutoMineModule extends Module {

	private final StringSetting block = textSetting("block", "Блок", "diamond_ore");
	private final IntSetting amount = intSetting("amount", "Сколько", 0, 0, 512);
	private final BooleanSetting chatCommands = bool("chat_commands", "Командами чата", false);

	public AutoMineModule() {
		super("auto_mine", "AutoMine", "Автоматическая добыча блоков через Baritone",
				ModuleCategory.MISC, GLFW.GLFW_KEY_B);
	}

	@Override
	protected void onEnable() {
		if (!BaritoneBridge.isAvailable()) {
			notify("§cBaritone не установлен — AutoMine недоступен");
			AkarusClient.LOGGER.warn("Baritone не найден, AutoMine отключается");
			// Выключаем модуль изнутри, чтобы не оставлять его в «включённом» состоянии
			setEnabledSilently(false);
			return;
		}

		start();
	}

	@Override
	public void tick() {
		// Baritone сам управляет процессом, здесь можно добавить контроль прогресса
	}

	@Override
	protected void onDisable() {
		BaritoneBridge.stop();
	}

	@Override
	public void onSettingsChanged() {
		// Изменили блок или количество — перезапускаем задачу
		if (isEnabled()) {
			start();
		}
	}

	private void start() {
		String target = block.get().trim();
		if (target.isEmpty()) {
			notify("§cУкажи блок для добычи в настройках AutoMine");
			return;
		}

		int quantity = amount.get();
		if (BaritoneBridge.mine(target, quantity, chatCommands.isEnabled())) {
			notify("§7[Akarus] Добываю: §f" + target + (quantity > 0 ? " §7x§f" + quantity : " §7(без лимита)"));
		} else {
			notify("§c[Akarus] Не удалось запустить добычу");
		}
	}

	private static void notify(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.gui == null) {
			return;
		}
		client.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}
}

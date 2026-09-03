package com.akarus.client;

import com.akarus.client.config.ConfigManager;
import com.akarus.client.gui.ClickGuiScreen;
import com.akarus.client.gui.hud.HudRenderer;
import com.akarus.client.gui.screens.AkarusScreens;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.AutoWalkModule;
import com.akarus.client.module.impl.FreeCamModule;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Главный класс мода. Точка входа объявлена в fabric.mod.json (секция "client"),
 * поэтому весь код выполняется только на стороне клиента.
 */
public class AkarusClient implements ClientModInitializer {

	public static final String MOD_ID = "akarus";
	public static final String MOD_NAME = "Akarus";
	public static final String MOD_VERSION = "0.7.1";

	public static final Logger LOGGER = LogUtils.getLogger();

	/** Своя категория в настройках управления ("Akarus"). */
	public static final KeyMapping.Category KEY_CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "modules"));

	private static KeyMapping clickGuiKey;

	@Override
	public void onInitializeClient() {
		LOGGER.info("{} {} — инициализация", MOD_NAME, MOD_VERSION);

		// Порядок важен: сначала читаем конфиг, потом создаём модули (они подхватят сохранённые значения)
		ConfigManager.load();
		ModuleManager.init();
		HudRenderer.register();
		AkarusScreens.register();

		// Клавиша открытия меню — правый Shift
		clickGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + MOD_ID + ".gui",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				KEY_CATEGORY));

		// Перехват ПКМ делаем в начале тика: игра обрабатывает «использовать» позже,
		// внутри того же тика, поэтому успеваем нажатие погасить
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			AutoWalkModule.handleInput(client);
			// FreeCam: снимаем нажатия передвижения, чтобы игрок не пошёл за камерой
			FreeCamModule.handleInput(client);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (clickGuiKey.consumeClick()) {
				// Открываем GUI только если сейчас нет другого экрана
				if (client.gui.screen() == null) {
					client.gui.setScreen(new ClickGuiScreen());
				}
			}

			// Обработка клавиш модулей и их тиков
			ModuleManager.tick();
		});

		// Сохраняем настройки при закрытии игры
		Runtime.getRuntime().addShutdownHook(new Thread(ConfigManager::save, "akarus-config-save"));

		LOGGER.info("{} {} готов к работе. Меню — правый Shift.", MOD_NAME, MOD_VERSION);
	}

	public static KeyMapping getClickGuiKey() {
		return clickGuiKey;
	}
}

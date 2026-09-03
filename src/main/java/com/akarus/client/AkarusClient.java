package com.akarus.client;

import com.akarus.client.config.ConfigManager;
import com.akarus.client.gui.hud.HudRenderer;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.render.WorldRenderHook;
import com.akarus.client.util.Notifications;
import com.akarus.client.module.impl.AutoWalkModule;
import com.akarus.client.module.impl.FreeCamModule;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Главный класс мода. Точка входа объявлена в fabric.mod.json (секция "client"),
 * поэтому весь код выполняется только на стороне клиента.
 */
public class AkarusClient implements ClientModInitializer {

	public static final String MOD_ID = "akarus";
	/** Пользовательское имя клиента. Id мода остаётся akarus — ради совместимости конфига и ресурсов. */
	public static final String MOD_NAME = "Dreamcast DLC";
	public static final String MOD_VERSION = "0.8.0";
	/** Короткое имя для логотипа в меню и HUD. */
	public static final String LOGO_TEXT = "DREAMCAST";

	public static final Logger LOGGER = LogUtils.getLogger();

	/** Своя категория в настройках управления ("Akarus"). */
	public static final KeyMapping.Category KEY_CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "modules"));

	@Override
	public void onInitializeClient() {
		LOGGER.info("{} {} — инициализация", MOD_NAME, MOD_VERSION);

		// Порядок важен: сначала читаем конфиг, потом создаём модули (они подхватят сохранённые значения)
		ConfigManager.load();
		ModuleManager.init();
		HudRenderer.register();
		WorldRenderHook.register();

		// Перехват ПКМ делаем в начале тика: игра обрабатывает «использовать» позже,
		// внутри того же тика, поэтому успеваем нажатие погасить
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			AutoWalkModule.handleInput(client);
			// FreeCam: снимаем нажатия передвижения, чтобы игрок не пошёл за камерой
			FreeCamModule.handleInput(client);
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Обработка клавиш модулей (бинд ClickGUI открывает меню) и их тиков
			ModuleManager.tick();
			// Уведомления живут на своих таймерах
			Notifications.tick();
		});

		// Сохраняем настройки при закрытии игры
		Runtime.getRuntime().addShutdownHook(new Thread(ConfigManager::save, "akarus-config-save"));

		LOGGER.info("{} {} готов к работе. Меню — правый Shift.", MOD_NAME, MOD_VERSION);
	}
}


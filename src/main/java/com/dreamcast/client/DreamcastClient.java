package com.dreamcast.client;

import com.dreamcast.client.config.ConfigManager;
import com.dreamcast.client.gui.hud.HudRenderer;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.render.WorldRenderHook;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.module.impl.AutoWalkModule;
import com.dreamcast.client.module.impl.FreeCamModule;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Главный класс мода. Точка входа объявлена в fabric.mod.json (секция "client"),
 * поэтому весь код выполняется только на стороне клиента.
 */
public class DreamcastClient implements ClientModInitializer {

	public static final String MOD_ID = "dreamcast";
	/** Пользовательское имя клиента: на экранах — короткое «Dreamcast», без «DLC». */
	public static final String MOD_NAME = "Dreamcast";
	public static final String MOD_VERSION = "1.1.4";
	/** Короткое имя для логотипа в меню и HUD. */
	public static final String LOGO_TEXT = "DREAMCAST";

	public static final Logger LOGGER = LogUtils.getLogger();

	/** Своя категория в настройках управления («Dreamcast»). */
	public static final KeyMapping.Category KEY_CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "modules"));

	@Override
	public void onInitializeClient() {
		LOGGER.info("{} {} — инициализация", MOD_NAME, MOD_VERSION);

		// Порядок важен: сначала читаем конфиг, потом создаём модули (они подхватят сохранённые значения)
		ConfigManager.load();
		com.dreamcast.client.util.AltsManager.load();
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
			// После всех модулей сводим удержания/подавления общих клавиш.
			com.dreamcast.client.util.KeyOwnership.refresh(client);
			// Отложенные восстановления инвентаря (после закрытия контейнера)
			com.dreamcast.client.util.PendingRestores.tick(client);
			// Уведомления живут на своих таймерах
			Notifications.tick();
		});

		// Выход из мира: отложенные восстановления теряют смысл — очередь чистим
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			com.dreamcast.client.util.PendingRestores.clear();
			com.dreamcast.client.util.KeyOwnership.clear(client);
			// Повороты живут внутри одной игровой сессии: в новом мире чужой
			// «боевой угол» и память о нём недопустимы
			com.dreamcast.client.rotation.RotationManager.reset();
		});

		// Сохраняем настройки при закрытии игры
		Runtime.getRuntime().addShutdownHook(new Thread(ConfigManager::save, "dreamcast-config-save"));

		LOGGER.info("{} {} готов к работе. Меню — правый Shift.", MOD_NAME, MOD_VERSION);
	}
}

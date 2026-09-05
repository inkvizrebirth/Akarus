package com.dreamcast.client.gui;

import com.dreamcast.client.gui.screens.DreamcastMenuScreen;
import com.dreamcast.client.gui.screens.DreamcastPauseScreen;
import com.dreamcast.client.gui.screens.DreamcastServersScreen;
import com.dreamcast.client.gui.screens.DreamcastSettingsScreen;
import com.dreamcast.client.gui.screens.DreamcastWorldsScreen;
import com.dreamcast.client.module.impl.CustomGuiModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Маршрутизация «ванильное меню → наше меню».
 *
 * <p>Подменяем только те экраны, где у клиента есть своя полноценная замена:
 * главное меню, пауза, выбор мира, серверы и настройки. Экраны контейнеров
 * (сундук, печь, житель, книга) не подменяем намеренно: их логика живёт в
 * subclass-ах с собственными виджетами, и при замене пользователь потерял бы
 * стрелку плавильни или ползунок торговца. Их видом занимается
 * {@code ContainerStyleMixin}.</p>
 *
 * <p>Имена классов сравниваем строками, а не {@code instanceof}: в 26.2 часть
 * экранов переехала по пакетам, и импорт ради каждой проверки сделал бы мод
 * хрупким. Имя {@code getSimpleName()} — единственное, что держится между
 * переименованиями пакетов.</p>
 */
public final class ClientScreens {

	private ClientScreens() {
	}

	/** Возвращает экран, который нужно открыть вместо {@code screen}. */
	public static Screen remap(Screen screen) {
		if (screen == null || !CustomGuiModule.wantsSwap() || CustomGuiModule.isOwnScreen(screen)) {
			return screen;
		}
		if (screen instanceof AbstractContainerScreen) {
			return screen;
		}
		Minecraft client = Minecraft.getInstance();
		Screen current = client == null ? null : client.gui.screen();
		// «Родителем» ставим тот экран, который открыт сейчас: из него и пришли.
		Screen parent = CustomGuiModule.isOwnScreen(current) ? current : null;
		String name = screen.getClass().getSimpleName();
		return switch (name) {
			case "TitleScreen" -> new DreamcastMenuScreen();
			case "PauseScreen" -> new DreamcastPauseScreen();
			case "SelectWorldScreen" -> new DreamcastWorldsScreen(parent == null ? new DreamcastMenuScreen() : parent);
			case "ServerListScreen", "JoinMultiplayerScreen", "ModifyServerScreen",
					"AddServerScreen", "DirectJoinScreen" -> new DreamcastServersScreen(parent);
			case "OptionsScreen" -> new DreamcastSettingsScreen(parent == null ? new DreamcastMenuScreen() : parent);
			default -> screen;
		};
	}
}

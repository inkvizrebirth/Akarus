package com.akarus.client.gui.screens;

import com.akarus.client.util.ViaIntegration;
import com.akarus.client.mixin.AkarusScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * Мелкие правки чужих экранов через стабильные точки Fabric API.
 *
 * Пилюля версии в правом верхнем углу списка серверов: по клику открывается наш
 * AkarusVersionSelectScreen (протоколы ViaFabricPlus). Кнопка — ванильный виджет:
 * у него гарантированное поведение фокуса/тултипов, а на ванильном экране он и не
 * чужеродный.
 */
public final class AkarusScreens {

	private AkarusScreens() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof JoinMultiplayerScreen)) {
				return;
			}

			String label = "Версия: " + ViaIntegration.currentVersionLabel();
			Button versionPill = Button.builder(Component.literal(label), button ->
							client.setScreen(new AkarusVersionSelectScreen(screen)))
					.width(Math.min(150, Math.max(110, client.font.width(label) + 18)))
					.height(14)
					.pos(scaledWidth - Math.min(150, Math.max(110, client.font.width(label) + 18)) - 4, 4)
					.build();
			versionPill.setTooltip(Tooltip.create(Component.literal(
					ViaIntegration.available()
							? "Выбрать версию Minecraft для подключения\n(через ViaFabricPlus)"
							: "ViaFabricPlus не установлен — метка для справки")));
			((AkarusScreenAccessor) (Object) screen).akarus$addRenderableWidget(versionPill);
		});
	}

	/** Точка входа «экран настроек поверх любого экрана». */
	public static void openSettingsFrom(Screen parent) {
		if (parent != null) {
			net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
			if (client != null) {
				client.setScreen(new AkarusSettingsScreen(parent));
			}
		}
	}
}

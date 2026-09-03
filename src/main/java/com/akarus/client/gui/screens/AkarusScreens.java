package com.akarus.client.gui.screens;

import com.akarus.client.util.RenderUtils;
import com.akarus.client.util.ViaIntegration;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.util.Util;

/**
 * Мелкие правки чужих экранов через Fabric Screen API — без миксинов в методы,
 * которых у класса может и не быть.
 *
 * Пока что это пилюля версии в правом верхнем углу списка серверов: по клику
 * открывается выбор протокола (ViaFabricPlus). Рисуем поверх ванильного экрана
 * после его отрисовки, клики перехватываем до ванильной обработки.
 */
public final class AkarusScreens {

	private static final int HEIGHT = 14;
	private static final int ACCENT = 0xFF5CE1E6;

	private static int pillX;
	private static int pillWidth;
	private static float hover;
	private static String label = "";
	private static long labelNextUpdate;

	private AkarusScreens() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof JoinMultiplayerScreen)) {
				return;
			}

			ScreenMouseEvents.allowMouseClick(screen).register((scr, mouseX, mouseY, mouseButton) -> {
				if (mouseButton == 0 && inPill(mouseX, mouseY)) {
					Minecraft instance = Minecraft.getInstance();
					if (instance != null) {
						instance.setScreen(new AkarusVersionSelectScreen(scr));
					}
					return false; // ванильные списки клик под пилюлей не увидят
				}
				return true;
			});

			ScreenEvents.afterRender(screen).register((scr, graphics, mouseX, mouseY, delta) ->
					drawPill(graphics, scr.width, scr.height, mouseX, mouseY));
		});
	}

	private static void drawPill(GuiGraphicsExtractor graphics, int width, int height, int mouseX, int mouseY) {
		long now = Util.getMillis();
		if (now > labelNextUpdate) {
			labelNextUpdate = now + 900L;
			label = "Версия: " + ViaIntegration.currentVersionLabel();
		}

		pillWidth = Math.max(104, graphicsFontWidth(label) + 26);
		pillX = width - pillWidth - 4;
		int pillY = 4;

		boolean inside = mouseX >= pillX && mouseX < pillX + pillWidth && mouseY >= pillY && mouseY < pillY + HEIGHT;
		hover = hover + ((inside ? 1.0f : 0.0f) - hover) * 0.25f;
		if (Math.abs(hover - (inside ? 1.0f : 0.0f)) < 0.01f) {
			hover = inside ? 1.0f : 0.0f;
		}

		RenderUtils.fillRounded(graphics, pillX, pillY, pillWidth, HEIGHT, 7,
				RenderUtils.mix(0xE80B0B0E, 0xF21D1D24, hover));
		RenderUtils.fillRounded(graphics, pillX, pillY, pillWidth, 1, 0,
				RenderUtils.withAlpha(ACCENT, 0.35f + 0.5f * hover));
		graphics.text(Minecraft.getInstance().font, label,
				pillX + 13, pillY + (HEIGHT - Minecraft.getInstance().font.lineHeight) / 2 + 1,
				RenderUtils.mix(0xFFB9B9C6, 0xFFFFFFFF, hover), true);
	}

	private static int graphicsFontWidth(String text) {
		Minecraft client = Minecraft.getInstance();
		return client == null ? text.length() * 6 : client.font.width(text);
	}

	private static boolean inPill(double mouseX, double mouseY) {
		return mouseX >= pillX && mouseX < pillX + pillWidth && mouseY >= 4 && mouseY < 4 + HEIGHT;
	}

	/** Публичная точка входа для «экрана настроек из меню». */
	public static void openSettingsFrom(Screen parent) {
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			client.setScreen(new AkarusSettingsScreen(parent));
		}
	}
}

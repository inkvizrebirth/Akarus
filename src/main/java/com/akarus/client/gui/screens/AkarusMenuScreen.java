package com.akarus.client.gui.screens;

import com.akarus.client.AkarusClient;
import com.akarus.client.gui.ClickGuiScreen;
import com.akarus.client.util.FileOpener;
import com.akarus.client.util.RenderUtils;
import com.akarus.client.util.ViaIntegration;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Наш главный экран вместо ванильного TitleScreen.
 *
 * Фон — кастомный «шейдерный» кадр Minecraft (ресурс {@code textures/gui/
 * main_menu_background.png}) с медленным дрейфом камеры поверх; затемнение и
 * кнопки — наш чёрный стиль. Если текстура почему-то не прогрузилась, остаётся
 * тёмный градиент — интерфейс не разваливается.
 */
public class AkarusMenuScreen extends AkarusScreen {

	private static final Identifier BACKGROUND =
			Identifier.fromNamespaceAndPath(AkarusClient.MOD_ID, "textures/gui/main_menu_background.png");

	private static final int ACCENT = 0xFF5CE1E6;
	private static final int BUTTON_WIDTH = 192;
	private static final int BUTTON_HEIGHT = 22;
	private static final int BUTTON_GAP = 6;

	public AkarusMenuScreen() {
		super("Akarus");

		items.add(item("Одиночная игра", "миры и сохранения",
				() -> this.minecraft.gui.setScreen(new SelectWorldScreen(this))));
		items.add(item("Сетевая игра", "серверы и версии",
				() -> this.minecraft.gui.setScreen(new JoinMultiplayerScreen(this))));
		items.add(item("Настройки", "игра · видео · клавиши",
				() -> this.minecraft.gui.setScreen(new AkarusSettingsScreen(this))));
		items.add(item("ClickGUI", "модули",
				() -> this.minecraft.gui.setScreen(new ClickGuiScreen())));
		items.add(item("Telegram", "@inkviz01",
				() -> FileOpener.openUrl("https://t.me/inkviz01")));
		items.add(item("Выход", "",
				() -> this.minecraft.stop()));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		long time = Util.getMillis();

		// Фон: картинка с почти незаметным дрейфом (±7 px) — «живая» заставка
		float panX = (float) Math.sin(time / 9000.0) * 7.0f;
		float panY = (float) Math.cos(time / 11000.0) * 4.0f;
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND,
				-6, -6, 12 + panX, 8 + panY,
				width + 12, height + 12, 1920, 1080);

		drawDarkBackdrop(graphics);

		// Логотип: AKARUS с разрядкой и «дышащим» подчёркиванием
		String logo = "AKARUS";
		int logoY = height / 4 - 22;
		RenderUtils.drawTracked(graphics, font, logo,
				width / 2 - RenderUtils.trackedWidth(font, logo, 6) / 2, logoY, 0xFFF4F4FA, 6);

		int underlineWidth = RenderUtils.trackedWidth(font, logo, 6);
		float breathe = 0.5f + 0.5f * (float) Math.sin(time / 900.0);
		int accentBarWidth = Math.round(underlineWidth * (0.72f + 0.28f * breathe));
		graphics.fill(width / 2 - accentBarWidth / 2, logoY + font.lineHeight + 4,
				width / 2 + accentBarWidth / 2, logoY + font.lineHeight + 5,
				RenderUtils.withAlpha(ACCENT, 0.45f + 0.45f * breathe));

		String tagline = "клиент для Minecraft 26.2 · Fabric";
		graphics.text(font, tagline, width / 2 - font.width(tagline) / 2,
				logoY + font.lineHeight + 10, 0xFF9E9EAE, false);

		// Колонка кнопок — как в ваниле, но наша
		int total = items.size() * BUTTON_HEIGHT + (items.size() - 1) * BUTTON_GAP;
		int startY = height - total - 34;
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = startY;
		for (Item entry : items) {
			drawItem(graphics, entry, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, ACCENT, mouseX, mouseY);
			y += BUTTON_HEIGHT + BUTTON_GAP;
		}

		// Подвал: версия и строка о виа
		String version = "Akarus " + AkarusClient.MOD_VERSION + "   ·   Minecraft " + ViaIntegration.currentVersionLabel();
		graphics.text(font, version, 6, height - 10, 0xFF80808C, true);
		String build = net.minecraft.SharedConstants.getCurrentVersion().name();
		graphics.text(font, "build " + build, width - 6 - font.width(build), height - 10, 0xFF80808C, true);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickItems(event)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}

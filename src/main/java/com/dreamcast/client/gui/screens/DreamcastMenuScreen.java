package com.dreamcast.client.gui.screens;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.gui.ClickGuiScreen;
import com.dreamcast.client.util.FileOpener;
import com.dreamcast.client.util.RenderUtils;
import com.dreamcast.client.util.ViaIntegration;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
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
public class DreamcastMenuScreen extends DreamcastScreen {

	private static final Identifier BACKGROUND =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "textures/gui/main_menu_background.png");

	/** Фирменная пара Dreamcast: фиолетовый → циан. */
	private static final int ACCENT = 0xFF7C6CFF;
	private static final int ACCENT_2 = 0xFF45E3FF;
	private static final int BUTTON_WIDTH = 192;
	private static final int BUTTON_HEIGHT = 22;
	private static final int BUTTON_GAP = 6;

	public DreamcastMenuScreen() {
		super(DreamcastClient.MOD_NAME);

		items.add(item("Одиночная игра", "",
				() -> this.minecraft.gui.setScreen(new DreamcastWorldsScreen(this))));
		items.add(item("Сетевая игра", "",
				() -> this.minecraft.gui.setScreen(new DreamcastServersScreen(this))));
		items.add(item("Настройки", "",
				() -> this.minecraft.gui.setScreen(new DreamcastSettingsScreen(this))));
		items.add(item("ClickGUI", "",
				() -> this.minecraft.gui.setScreen(new ClickGuiScreen())));
		items.add(item("Telegram", "",
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

		// Фон: картинка встаёт сразу целиком — по принципу «cover» (заполняет
		// экран без искажений, лишние края обрезаются). Никакой прокрутки.
		float scale = Math.max(width / 1920.0f, height / 1080.0f);
		int srcW = Math.round(width / scale);
		int srcH = Math.round(height / scale);
		float u = (1920 - srcW) / 2.0f;
		float v = (1080 - srcH) / 2.0f;
		graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND,
				0, 0, u, v, width, height, srcW, srcH, 1920, 1080);

		drawDarkBackdrop(graphics);

		// Логотип: DREAMCAST с разрядкой, строго по центру — без лишнего рядом
		String logo = DreamcastClient.LOGO_TEXT;
		int logoY = height / 4 - 22;
		int tracked = RenderUtils.trackedWidthBold(font, logo, 6);
		int logoX = width / 2 - tracked / 2;
		RenderUtils.drawTrackedBold(graphics, font, logo, logoX, logoY, 0xFFF4F4FA, 6);

		// «Дышащая» линия под логотипом: градиент фиолетовый → циан
		float breathe = 0.5f + 0.5f * (float) Math.sin(time / 900.0);
		int accentBarWidth = Math.round(tracked * (0.72f + 0.28f * breathe));
		for (int i = 0; i < accentBarWidth; i++) {
			float t = i / (float) Math.max(1, accentBarWidth - 1);
			int color = RenderUtils.mix(ACCENT, ACCENT_2, t);
			graphics.fill(logoX + i, logoY + font.lineHeight + 4,
					logoX + i + 1, logoY + font.lineHeight + 5,
					RenderUtils.withAlpha(color, 0.45f + 0.45f * breathe));
		}

		// Колонка кнопок — как в ваниле, но наша
		int total = items.size() * BUTTON_HEIGHT + (items.size() - 1) * BUTTON_GAP;
		int startY = height - total - 34;
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = startY;
		int index = 0;
		for (Item entry : items) {
			drawItem(graphics, entry, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, accentFor(index), mouseX, mouseY);
			y += BUTTON_HEIGHT + BUTTON_GAP;
			index++;
		}

		// Подвал: версия клиента и версия протокола
		String version = DreamcastClient.MOD_NAME + " " + DreamcastClient.MOD_VERSION
				+ "   ·   Minecraft " + ViaIntegration.currentVersionLabel();
		RenderUtils.text(graphics, font, version, 6, height - 10, 0xFF80808C);
		String build = net.minecraft.SharedConstants.getCurrentVersion().name();
		String buildLabel = "build " + build;
		RenderUtils.text(graphics, font, buildLabel, width - 6 - RenderUtils.width(font, buildLabel), height - 10, 0xFF80808C);
	}

	/** Каждой кнопке — свой акцент: меню переливается сверху вниз. */
	private static int accentFor(int index) {
		float t = index / 5.0f;
		return RenderUtils.mix(ACCENT, ACCENT_2, t);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickItems(event)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}

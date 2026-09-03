package com.akarus.client.gui.screens;

import com.akarus.client.gui.ClickGuiScreen;
import com.akarus.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;

/**
 * Наш экран паузы вместо ванильного PauseScreen.
 *
 * Игра ставится на паузу так же, как это делает ванильный экран: {@code pauseGame(true)}
 * при открытии и {@code pauseGame(false)} при возврате. Кнопка выхода умная:
 * одиночный мир сохраняется, сервер — просто отключается.
 */
public class AkarusPauseScreen extends AkarusScreen {

	private static final int ACCENT = 0xFFFFB86C;
	private static final int BUTTON_WIDTH = 220;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 5;

	public AkarusPauseScreen() {
		super("Пауза");

		items.add(item("Продолжить игру", "Esc", () -> {
			if (this.minecraft != null) {
				this.minecraft.pauseGame(false);
				this.minecraft.setScreen(null);
			}
		}));
		items.add(item("Настройки", "", () -> openNext(new AkarusSettingsScreen(this))));
		items.add(item("ClickGUI", "модули", () -> openNext(new ClickGuiScreen())));

		Minecraft client = this.minecraft;
		boolean singleplayer = client != null && client.isLocalServer();
		items.add(item(singleplayer ? "Сохранить и выйти в меню" : "Отключиться от сервера",
				"", () -> {
					if (this.minecraft != null) {
						if (singleplayer) {
							this.minecraft.disconnectWithSavingScreen();
						} else {
							this.minecraft.disconnectWithProgressScreen();
						}
					}
				}));
		items.add(item("Выйти из игры", "",
				() -> {
					if (this.minecraft != null) {
						this.minecraft.stop();
					}
				}));
	}

	private void openNext(AkarusScreen next) {
		if (this.minecraft != null) {
			this.minecraft.setScreen(next);
		}
	}

	/** Пауза должна оставаться паузой: игра замирает, пока открыт этот экран. */
	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public void added() {
		super.added();
		if (this.minecraft != null) {
			this.minecraft.pauseGame(true);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.pauseGame(false);
		}
		super.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Полупрозрачность: мир за стеклом остаётся виден — как в ваниле, но темнее
		graphics.fill(0, 0, width, height, 0xB0050506);

		long time = Util.getMillis();
		String title = "ПАУЗА";
		int titleY = height / 2 - (items.size() * (BUTTON_HEIGHT + BUTTON_GAP)) / 2 - 34;
		RenderUtils.drawTracked(graphics, font, title,
				width / 2 - RenderUtils.trackedWidth(font, title, 4) / 2, titleY, 0xFFF4F4FA, 4);
		float pulse = 0.5f + 0.5f * (float) Math.sin(time / 700.0);
		int lineW = 60;
		graphics.fill(width / 2 - lineW / 2, titleY + font.lineHeight + 3,
				width / 2 + lineW / 2, titleY + font.lineHeight + 4,
				RenderUtils.withAlpha(ACCENT, 0.30f + 0.5f * pulse));

		int total = items.size() * BUTTON_HEIGHT + (items.size() - 1) * BUTTON_GAP;
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = height / 2 - total / 2 + 8;
		for (Item entry : items) {
			drawItem(graphics, entry, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, ACCENT, mouseX, mouseY);
			y += BUTTON_HEIGHT + BUTTON_GAP;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickItems(event)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}

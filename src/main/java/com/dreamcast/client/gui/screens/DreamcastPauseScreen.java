package com.dreamcast.client.gui.screens;

import com.dreamcast.client.gui.ClickGuiScreen;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;

/**
 * Наш экран паузы вместо ванильного PauseScreen.
 *
 * Игра ставится на паузу так же, как это делает ванильный экран: {@code pauseGame(true)}
 * при открытии и {@code pauseGame(false)} при возврате. Кнопка выхода умная:
 * одиночный мир сохраняется, сервер — просто отключается.
 */
public class DreamcastPauseScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFFFFB86C;
	private static final int BUTTON_WIDTH = 220;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 5;

	public DreamcastPauseScreen() {
		super("Пауза");

		items.add(item("Продолжить игру", "Esc", () -> {
			if (this.minecraft != null) {
				this.minecraft.pauseGame(false);
				this.minecraft.gui.setScreen(null);
			}
		}));
		items.add(item("Настройки", "", () -> openNext(new DreamcastSettingsScreen(this))));
		items.add(item("ClickGUI", "модули", () -> openNext(new ClickGuiScreen(this))));

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

	private void openNext(Screen next) {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(next);
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

	/** Короткая подпись о том, где стоит игра: локальный мир или сервер. */
	private String pauseContext() {
		Minecraft client = this.minecraft;
		if (client == null) {
			return "";
		}
		if (client.getCurrentServer() != null) {
			return "сервер · " + client.getCurrentServer().ip;
		}
		if (client.isLocalServer() && client.getSingleplayerServer() != null) {
			String world = client.getSingleplayerServer().getWorldData().getLevelName();
			return "одиночная игра" + (world == null || world.isEmpty() ? "" : " · " + world);
		}
		return "";
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

		// Контекст: где именно мы остановились
		String context = pauseContext();
		if (!context.isEmpty()) {
			RenderUtils.textFlat(graphics, font, context,
					width / 2 - RenderUtils.width(font, context) / 2,
					titleY + font.lineHeight + 7, 0xFF8A8A96);
		}

		int total = items.size() * BUTTON_HEIGHT + (items.size() - 1) * BUTTON_GAP;
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = height / 2 - total / 2 + 8;
		for (Item entry : items) {
			drawItem(graphics, entry, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, ACCENT, mouseX, mouseY);
			y += BUTTON_HEIGHT + BUTTON_GAP;
		}
	
		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		if (clickItems(event)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}

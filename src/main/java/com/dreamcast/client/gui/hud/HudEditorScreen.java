package com.dreamcast.client.gui.hud;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Редактор раскладки HUD: все элементы на своих местах, обведены рамками,
 * любой можно схватить мышью и перетащить. ESC или та же клавиша модуля — выход.
 *
 * Экран прозрачный: мир и HUD видны как есть, поверх — лёгкое затемнение
 * и подписи элементов. Позиции сохраняются сразу при отпускании кнопки.
 */
public class HudEditorScreen extends Screen {

	private long openedAt;
	private String draggingId;

	public HudEditorScreen() {
		super(Component.literal("HUD"));
	}

	public static void open() {
		HudEditorScreen screen = new HudEditorScreen();
		var client = net.minecraft.client.Minecraft.getInstance();
		if (client != null) {
			client.gui.setScreen(screen);
		}
	}

	@Override
	protected void init() {
		super.init();
		openedAt = Util.getMillis();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Только лёгкое затемнение — мир остаётся видимым, блюр не нужен
		graphics.fill(0, 0, this.width, this.height, 0x50000000);
		this.minecraft.gui.hud.extractDeferredSubtitles();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		// Элементы HUD на их местах — как в игре, но с рамками
		HudRenderer.renderElements(graphics, this.minecraft, true);

		// Рамки и подписи поверх
		var font = this.font;
		long now = Util.getMillis();
		float appear = Math.min(1.0f, (now - openedAt) / 220.0f);
		int accent = com.dreamcast.client.gui.theme.ClientTheme.accent(now);

		for (var entry : HudLayout.boundsSnapshot().entrySet()) {
			int[] bounds = entry.getValue();
			if (bounds == null) {
				continue;
			}
			boolean dragged = entry.getKey().equals(draggingId);
			int color = RenderUtils.withAlpha(dragged ? accent : 0xFFFFFFFF, dragged ? 0.95f : 0.55f * appear);
			drawDashedFrame(graphics, bounds[0] - 3, bounds[1] - 3, bounds[2] + 6, bounds[3] + 6, color);

			String label = HudRenderer.elementLabel(entry.getKey());
			int labelY = bounds[1] - 3 - font.lineHeight - 2;
			if (labelY < 2) {
				labelY = bounds[1] + bounds[3] + 5;
			}
			RenderUtils.textFlat(graphics, font, label, bounds[0] - 3, labelY, color);
		}

		// Подсказка снизу по центру
		String hint = "Тащи элементы мышью   •   ESC — готово";
		int hintWidth = RenderUtils.width(font, hint);
		int hintX = (this.width - hintWidth) / 2;
		int hintY = this.height - 18;
		RenderUtils.fillRounded(graphics, hintX - 8, hintY - 4, hintWidth + 16, font.lineHeight + 8, 6, 0xC80A0A0D);
		RenderUtils.text(graphics, font, hint, hintX, hintY, 0xFFEDEDF5);
	}

	/** Пунктирная рамка: коротые штрихи по периметру. */
	private static void drawDashedFrame(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		int step = 5;
		int on = 3;
		for (int cx = x; cx < x + width; cx += step) {
			int end = Math.min(cx + on, x + width);
			graphics.fill(cx, y, end, y + 1, color);
			graphics.fill(cx, y + height - 1, end, y + height, color);
		}
		for (int cy = y; cy < y + height; cy += step) {
			int end = Math.min(cy + on, y + height);
			graphics.fill(x, cy, x + 1, end, color);
			graphics.fill(x + width - 1, cy, x + width, end, color);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && HudLayout.startDrag(event.x(), event.y())) {
			// Подсветим тащимый элемент: id смотрим по границам этого кадра
			for (var entry : HudLayout.boundsSnapshot().entrySet()) {
				int[] box = entry.getValue();
				if (box != null && event.x() >= box[0] && event.x() < box[0] + box[2]
						&& event.y() >= box[1] && event.y() < box[1] + box[3]) {
					draggingId = entry.getKey();
					break;
				}
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double deltaX, double deltaY) {
		if (draggingId != null) {
			HudLayout.dragTo(event.x(), event.y(), this.width, this.height);
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		if (draggingId != null) {
			draggingId = null;
			HudLayout.endDrag();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// ESC закрывает редактор — handled onClose сохраняет позиции
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		HudLayout.endDrag();
		HudLayout.save();
		DreamcastClient.LOGGER.info("Раскладка HUD сохранена");
		super.onClose();
	}
}

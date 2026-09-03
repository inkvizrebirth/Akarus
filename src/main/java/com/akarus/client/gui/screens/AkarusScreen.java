package com.akarus.client.gui.screens;

import com.akarus.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Общая база наших экранов: чёрное «стекло», плавные ховеры, клики по прямоугольникам.
 *
 * Никаких ванильных виджетов — меню выглядит как ClickGUI: тёмные панели, мягкие
 * скругления, акцентная линия. Пункты живут в {@link #items}; границы пунктов
 * записываются при отрисовке и там же используются кликом (один поток — гонки нет).
 */
public abstract class AkarusScreen extends Screen {

	/** Пункт меню: подпись, подсказка справа, действие + анимация ховера. */
	protected static final class Item {
		public final String label;
		public final String hint;
		public final Runnable action;

		public float hover;
		public int x;
		public int y;
		public int width;
		public int height;

		Item(String label, String hint, Runnable action) {
			this.label = label;
			this.hint = hint;
			this.action = action;
		}
	}

	protected final List<Item> items = new ArrayList<>();

	/** Плавное появление экрана: 0 → 1 за ~260 мс после открытия. */
	private float openProgress;
	private long openedAt;

	protected AkarusScreen(String title) {
		super(Component.literal(title));
	}

	@Override
	public void added() {
		super.added();
		this.openedAt = System.nanoTime();
		this.openProgress = 0.0f;
	}

	protected float openProgress() {
		if (openProgress < 1.0f) {
			float target = Math.min(1.0f, (System.nanoTime() - openedAt) / 260_000_000.0f);
			openProgress = Math.max(openProgress, target);
		}
		return openProgress;
	}

	/** Затемнение фона: «чёрное стекло» поверх того, что игра нарисовала сама. */
	protected void drawDarkBackdrop(GuiGraphicsExtractor graphics) {
		float t = openProgress();
		graphics.fill(0, 0, width, height, RenderUtils.withAlpha(0xFF050506, 0.62f + 0.26f * t));
		graphics.fillGradient(0, 0, width, height / 2,
				RenderUtils.withAlpha(0xFF000000, 0.10f),
				RenderUtils.withAlpha(0xFF000000, 0.34f));
		graphics.fillGradient(0, height * 2 / 3, width, height,
				RenderUtils.withAlpha(0xFF000000, 0.38f),
				RenderUtils.withAlpha(0xFF000000, 0.02f));
	}

	/** Кадровый easing (как в ClickGUI): плавно, но конечное число шагов. */
	protected static float ease(float current, float target, double speed) {
		float next = current + (target - current) * (float) speed;
		return Math.abs(next - target) < 0.004f ? target : next;
	}

	/** Панель-«стекло»: тень, скругление с градиентом, светлая рамка, акцент сверху. */
	protected static void drawGlassPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
	                                     int radius, float hover, int accent) {
		RenderUtils.drawSoftShadow(graphics, x, y, w, h, radius, 4);
		int top = RenderUtils.mix(0xF4121215, 0xF61C1C22, hover);
		int bottom = RenderUtils.mix(0xF60A0A0C, 0xF8121217, hover);
		int border = RenderUtils.mix(0x26FFFFFF, 0x66FFFFFF, hover);
		RenderUtils.fillRoundedBorder(graphics, x, y, w, h, radius, border, top, bottom, 1);
		if (accent != 0) {
			graphics.fill(x + 8, y, x + w - 8, y + 1, RenderUtils.withAlpha(accent, 0.30f + 0.60f * hover));
		}
	}

	/** Отрисовка пункта меню; границы сохраняются в сам пункт для клика. */
	protected void drawItem(GuiGraphicsExtractor graphics, Item item, int x, int y, int w, int h,
	                        int accent, int mouseX, int mouseY) {
		item.x = x;
		item.y = y;
		item.width = w;
		item.height = h;
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		item.hover = ease(item.hover, inside ? 1.0f : 0.0f, 0.22);

		drawGlassPanel(graphics, x, y, w, h, 8, item.hover, accent);

		int textY = y + (h - font.lineHeight) / 2;
		int textColor = RenderUtils.mix(0xFFE8E8F0, 0xFFFFFFFF, item.hover);
		graphics.text(font, Component.literal(item.label), x + 12, textY, textColor, true);

		if (item.hint != null && !item.hint.isEmpty()) {
			int hintColor = RenderUtils.withAlpha(0xFFA6A6B2, 0.55f + 0.45f * item.hover);
			graphics.text(font, Component.literal(item.hint),
					x + w - 12 - font.width(item.hint), textY, hintColor, true);
		}

		if (item.hover > 0.02f) {
			graphics.fill(x, y + 3, x + 2, y + h - 3, RenderUtils.withAlpha(accent, item.hover));
		}
	}

	/** Клик по пунктам меню; true — если попали в пункт (действие уже выполнено). */
	protected boolean clickItems(MouseButtonEvent event) {
		double mx = event.x();
		double my = event.y();
		for (Item item : items) {
			if (item.action != null
					&& mx >= item.x && mx < item.x + item.width
					&& my >= item.y && my < item.y + item.height) {
				playClick();
				item.action.run();
				return true;
			}
		}
		return false;
	}

	protected void playClick() {
		Minecraft client = this.minecraft;
		if (client != null) {
			client.getSoundManager().play(
					net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
							net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
		}
	}

	protected static Item item(String label, String hint, Runnable action) {
		return new Item(label, hint, action);
	}
}

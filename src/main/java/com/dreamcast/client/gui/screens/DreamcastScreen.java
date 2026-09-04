package com.dreamcast.client.gui.screens;

import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Общая база наших экранов: чёрное «стекло», плавные ховеры, клики по прямоугольникам.
 *
 * Никаких ванильных виджетов — меню выглядит как ClickGUI: тёмные панели, мягкие
 * скругления, акцентная линия. Пункты живут в {@link #items}; границы пунктов
 * записываются при отрисовке и там же используются кликом (один поток — гонки нет).
 */
public abstract class DreamcastScreen extends Screen {

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

	/** Кнопка-чип: маленькая пилюля в ряду действий. */
	protected static final class Chip {
		public final String label;
		public final Runnable action;
		public boolean enabled = true;
		public boolean danger;

		public float hover;
		public int x;
		public int y;
		public int width;
		public int height;

		Chip(String label, Runnable action, boolean danger) {
			this.label = label;
			this.action = action;
			this.danger = danger;
		}
	}

	/** Лёгкое собственное текстовое поле: каретка, стирание, фокус. */
	protected final class TextField {
		public String value = "";
		public String hint = "";
		public int maxLength = 64;
		public boolean focused;
		public int x;
		public int y;
		public int width;
		public int height = 18;
		public float hover;

		public void type(String text) {
			for (int i = 0; i < text.length() && value.length() < maxLength; i++) {
				value += text.charAt(i);
			}
		}

		public void backspace() {
			if (!value.isEmpty()) {
				value = value.substring(0, value.length() - 1);
			}
		}

		public boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}

		public void draw(GuiGraphicsExtractor graphics, int accent, int mouseX, int mouseY) {
			boolean inside = contains(mouseX, mouseY);
			hover = ease(hover, inside || focused ? 1.0f : 0.0f, 0.2f);
			int border = focused ? accent : RenderUtils.mix(0x1AFFFFFF, accent, hover * 0.3f);
			RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 5, border,
					RenderUtils.mix(0x82000000, 0x16FFFFFF, hover * 0.5f));
			int textY = y + (height - font.lineHeight) / 2;
			boolean blink = (Util.getMillis() / 500L) % 2 == 0;
			if (value.isEmpty() && !focused) {
				RenderUtils.textFlat(graphics, font, hint, x + 8, textY, 0x6680808C);
			} else {
				String shown = RenderUtils.clamp(font, value, width - 16);
				RenderUtils.textFlat(graphics, font, shown, x + 8, textY, focused ? 0xFFF6F6F8 : 0xFFC9C9D4);
				if (focused && blink && shown.equals(value)) {
					int cursorX = x + 8 + RenderUtils.width(font, shown) + 1;
					graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight - 1, accent);
				}
			}
		}
	}

	protected final List<Item> items = new ArrayList<>();
	protected final List<Chip> chips = new ArrayList<>();

	/** Плавное появление экрана: 0 → 1 за ~260 мс после открытия. */
	private float openProgress;
	private long openedAt;

	protected DreamcastScreen(String title) {
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
		// Один непрерывный градиент: прежние два перекрывающихся слоя давали
		// заметную горизонтальную полосу на главном экране и прятали фон.
		graphics.fill(0, 0, width, height, RenderUtils.withAlpha(0xFF050506, 0.34f + 0.13f * t));
		graphics.fillGradient(0, 0, width, height,
				RenderUtils.withAlpha(0xFF000000, 0.08f),
				RenderUtils.withAlpha(0xFF000000, 0.30f));
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
		// Подсветка начинается немного до границы кнопки и плавно затухает: так
		// соты не «мигают» при уходе курсора. В каждый момент активны максимум
		// одна-две кнопки, поэтому меню остаётся лёгким.
		float dx = Math.max(x - mouseX, Math.max(0, mouseX - (x + w)));
		float dy = Math.max(y - mouseY, Math.max(0, mouseY - (y + h)));
		float near = Math.max(0.0f, 1.0f - (float) Math.sqrt(dx * dx + dy * dy) / 38.0f);
		item.hover = ease(item.hover, inside ? 1.0f : near, 0.18);

		// Полная четырёхслойная тень на каждой кнопке была главным расходом
		// главного меню. Здесь остаётся одна короткая тень только у подсветки.
		if (item.hover > 0.01f) {
			RenderUtils.drawSoftShadow(graphics, x, y, w, h, 8, 1);
		}
		int top = RenderUtils.mix(0xF4121215, 0xF61C1C22, item.hover);
		int bottom = RenderUtils.mix(0xF60A0A0C, 0xF8121217, item.hover);
		int border = RenderUtils.mix(0x26FFFFFF, 0x66FFFFFF, item.hover);
		RenderUtils.fillRoundedBorder(graphics, x, y, w, h, 8, border, top, bottom, 1);
		graphics.fill(x + 8, y, x + w - 8, y + 1, RenderUtils.withAlpha(accent, 0.30f + 0.60f * item.hover));
		// Сотовая текстура: шестиугольники расступаются у курсора и подсвечиваются
		RenderUtils.drawHexPattern(graphics, x + 2, y + 2, w - 4, h - 4, accent,
				mouseX, mouseY, item.hover);

		int textY = y + (h - font.lineHeight) / 2;
		int textColor = RenderUtils.mix(0xFFE8E8F0, 0xFFFFFFFF, item.hover);
		String label = RenderUtils.clamp(font, item.label, w - 24);
		RenderUtils.text(graphics, font, label, x + 12, textY, textColor);

		if (item.hint != null && !item.hint.isEmpty()) {
			int hintColor = RenderUtils.withAlpha(0xFFA6A6B2, 0.55f + 0.45f * item.hover);
			String hint = RenderUtils.clamp(font, item.hint, w - 24 - RenderUtils.width(font, label) - 12);
			if (!hint.isEmpty()) {
				RenderUtils.text(graphics, font, hint,
						x + w - 12 - RenderUtils.width(font, hint), textY, hintColor);
			}
		}

		if (item.hover > 0.01f) {
			graphics.fill(x, y + 3, x + 2, y + h - 3, RenderUtils.withAlpha(accent, item.hover));
		}
	}

	/** Кнопка-чип: компактная пилюля для рядов действий. */
	protected void drawChip(GuiGraphicsExtractor graphics, Chip chip, int x, int y, int w, int h,
	                        int accent, int mouseX, int mouseY) {
		chip.x = x;
		chip.y = y;
		chip.width = w;
		chip.height = h;
		boolean inside = chip.enabled
				&& mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		chip.hover = ease(chip.hover, inside ? 1.0f : 0.0f, 0.25);

		int color = chip.danger ? 0xFFFF5C7A : accent;
		int base = chip.enabled
				? RenderUtils.mix(0xD90F0F13, RenderUtils.withAlpha(color, 0.85f), chip.hover * 0.30f)
				: 0xB80C0C10;
		RenderUtils.fillRoundedBorder(graphics, x, y, w, h, 6,
				chip.enabled ? RenderUtils.mix(0x20FFFFFF, color, chip.hover * 0.65f) : 0x14FFFFFF,
				base);
		if (chip.enabled) {
			RenderUtils.drawHexPattern(graphics, x + 2, y + 2, w - 4, h - 4,
					chip.danger ? 0xFFFF5C7A : accent, mouseX, mouseY, chip.hover * 0.8f);
		}

		int textY = y + (h - font.lineHeight) / 2;
		int textColor = chip.enabled
				? RenderUtils.mix(0xFFC9C9D4, 0xFFFFFFFF, chip.hover)
				: 0xFF54545E;
		String label = RenderUtils.clamp(font, chip.label, w - 8);
		RenderUtils.textFlat(graphics, font, label,
				x + (w - RenderUtils.width(font, label)) / 2, textY, textColor);
	}

	/** Строка чипов одинаковой высоты; возвращает итоговую ширину ряда. */
	protected int drawChipRow(GuiGraphicsExtractor graphics, int centerX, int y, int h, int gap,
	                          int accent, int mouseX, int mouseY) {
		int total = 0;
		for (Chip chip : chips) {
			chip.width = Math.max(46, RenderUtils.width(font, chip.label) + 18);
			total += chip.width;
		}
		total += gap * (chips.size() - 1);

		int x = centerX - total / 2;
		for (Chip chip : chips) {
			drawChip(graphics, chip, x, y, chip.width, h, accent, mouseX, mouseY);
			x += chip.width + gap;
		}
		return total;
	}

	/** Полоса прокрутки для наших списков; возвращает индекс первого видимого элемента. */
	protected int drawScrollbar(GuiGraphicsExtractor graphics, int trackX, int trackY, int trackH,
	                            int scroll, int visible, int total, int accent) {
		if (total <= visible) {
			return scroll;
		}
		RenderUtils.fillRounded(graphics, trackX, trackY, 2, trackH, 1, 0x26FFFFFF);
		int thumb = Math.max(12, trackH * visible / total);
		int maxScroll = total - visible;
		int thumbY = trackY + (trackH - thumb) * scroll / Math.max(1, maxScroll);
		RenderUtils.fillRounded(graphics, trackX, thumbY, 2, thumb, 1, RenderUtils.withAlpha(accent, 0.85f));
		return scroll;
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

	/** Клик по чипам действий. */
	protected boolean clickChips(MouseButtonEvent event) {
		double mx = event.x();
		double my = event.y();
		for (Chip chip : chips) {
			if (chip.enabled && chip.action != null
					&& mx >= chip.x && mx < chip.x + chip.width
					&& my >= chip.y && my < chip.y + chip.height) {
				playClick();
				chip.action.run();
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

	protected static Chip chip(String label, Runnable action) {
		return new Chip(label, action, false);
	}

	protected static Chip chip(String label, Runnable action, boolean danger) {
		return new Chip(label, action, danger);
	}
}

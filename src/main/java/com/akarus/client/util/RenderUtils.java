package com.akarus.client.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;

/**
 * Мелкие помощники для рисования интерфейса.
 *
 * В 26.2 вся отрисовка GUI идёт через {@link GuiGraphicsExtractor}: он сам
 * складывает элементы в render state, поэтому «вручную» мы только считаем геометрию.
 */
public final class RenderUtils {

	private RenderUtils() {
	}

	/** Линейное смешивание двух ARGB-цветов. t = 0 — первый цвет, t = 1 — второй. */
	public static int mix(int first, int second, float t) {
		return ARGB.color(
				(int) (ARGB.alpha(first) + (ARGB.alpha(second) - ARGB.alpha(first)) * t),
				(int) (ARGB.red(first) + (ARGB.red(second) - ARGB.red(first)) * t),
				(int) (ARGB.green(first) + (ARGB.green(second) - ARGB.green(first)) * t),
				(int) (ARGB.blue(first) + (ARGB.blue(second) - ARGB.blue(first)) * t)
		);
	}

	/** Меняет прозрачность цвета: alpha01 — от 0 (полностью прозрачный) до 1. */
	public static int withAlpha(int color, float alpha01) {
		return ARGB.color((int) (ARGB.alpha(color) * alpha01), ARGB.red(color), ARGB.green(color), ARGB.blue(color));
	}

	/** HSB → ARGB. hue — от 0 до 1. Собственная реализация, чтобы не тянуть java.awt. */
	public static int hsb(float hue, float saturation, float brightness, int alpha) {
		hue = hue - (float) Math.floor(hue);
		float h = hue * 6.0f;
		int sector = (int) h;
		float f = h - sector;

		float p = brightness * (1.0f - saturation);
		float q = brightness * (1.0f - f * saturation);
		float t = brightness * (1.0f - (1.0f - f) * saturation);

		float r;
		float g;
		float b;
		switch (sector % 6) {
			case 0 -> { r = brightness; g = t; b = p; }
			case 1 -> { r = q; g = brightness; b = p; }
			case 2 -> { r = p; g = brightness; b = t; }
			case 3 -> { r = p; g = q; b = brightness; }
			case 4 -> { r = t; g = p; b = brightness; }
			default -> { r = brightness; g = p; b = q; }
		}

		return ARGB.color(alpha, (int) (r * 255.0f), (int) (g * 255.0f), (int) (b * 255.0f));
	}

	/** Радужный цвет, плавно меняющийся со временем. */
	public static int rainbow(long timeMillis, float offset) {
		return hsb(((timeMillis % 4000L) / 4000.0f) + offset, 0.75f, 1.0f, 0xFF);
	}

	/** Прямоугольник со скруглёнными углами. */
	public static void fillRounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		fillRounded(graphics, x, y, width, height, radius, color, true, true, true, true);
	}

	/** Прямоугольник, у которого скруглён только верх (для «шапок» панелей). */
	public static void fillRoundedTop(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		fillRounded(graphics, x, y, width, height, radius, color, true, true, false, false);
	}

	/** Прямоугольник, у которого скруглён только низ. */
	public static void fillRoundedBottom(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		fillRounded(graphics, x, y, width, height, radius, color, false, false, true, true);
	}

	/**
	 * Прямоугольник с выборочно скруглёнными углами.
	 * Рисуется построчно: середина — целиком, верхняя и нижняя полосы — с учётом дуг.
	 */
	public static void fillRounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color,
			boolean topLeft, boolean topRight, boolean bottomLeft, boolean bottomRight) {
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		int x1 = x + width;
		int y1 = y + height;

		int topInset = topLeft || topRight ? r : 0;
		int bottomInset = bottomLeft || bottomRight ? r : 0;

		// Средняя часть — на всю ширину
		graphics.fill(x, y + topInset, x1, y1 - bottomInset, color);

		// Верхняя полоса
		for (int i = 0; i < topInset; i++) {
			int rowY = y + i;
			int chord = chord(r, topInset - i);
			int left = topLeft ? x + r - chord : x;
			int right = topRight ? x1 - r + chord : x1;
			graphics.fill(left, rowY, right, rowY + 1, color);
		}

		// Нижняя полоса
		for (int i = 0; i < bottomInset; i++) {
			int rowY = y1 - 1 - i;
			int chord = chord(r, bottomInset - i);
			int left = bottomLeft ? x + r - chord : x;
			int right = bottomRight ? x1 - r + chord : x1;
			graphics.fill(left, rowY, right, rowY + 1, color);
		}
	}

	/** Длина полухорды круга радиуса r на расстоянии dy от края. */
	private static int chord(int r, int dy) {
		return (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
	}

	/** Залитый круг — используется для эффекта волны по клику. */
	public static void fillCircle(GuiGraphicsExtractor graphics, float centerX, float centerY, float radius, int color) {
		if (radius <= 0.5f) {
			return;
		}
		int r = (int) Math.ceil(radius);
		float r2 = radius * radius;
		for (int dy = -r; dy <= r; dy++) {
			float chord = (float) Math.sqrt(Math.max(0.0f, r2 - dy * dy));
			int y = (int) Math.floor(centerY + dy);
			graphics.fill((int) (centerX - chord), y, (int) (centerX + chord), y + 1, color);
		}
	}

	/**
	 * Мягкая многослойная тень: несколько скруглённых прямоугольников,
	 * каждый чуть больше предыдущего и с меньшей прозрачностью.
	 */
	public static void drawSoftShadow(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int layers) {
		for (int i = layers; i >= 1; i--) {
			float t = i / (float) layers;
			int grow = (int) (t * 10.0f);
			int alpha = (int) (7.0f + 26.0f * (1.0f - t));
			// тень немного смещена вниз — так панель «приподнимается» над миром
			fillRounded(graphics, x - grow, y - grow / 2 + 2, width + grow * 2, height + grow + 2,
					radius + grow / 2, withAlpha(0xFF000000, alpha / 255.0f));
		}
	}

	/** Скруглённая «рамка»: рисуется внешний прямоугольник, а поверх него — внутренний. */
	public static void fillRoundedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius,
			int borderColor, int fillColor) {
		fillRounded(graphics, x, y, width, height, radius, borderColor);
		fillRounded(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fillColor);
	}

	/** Обрезает строку многоточием, если она не влезает в заданную ширину. */
	public static String clamp(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		String cut = text;
		while (cut.length() > 1 && font.width(cut + ellipsis) > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + ellipsis;
	}

	/**
	 * Тумблер включения/выключения.
	 *
	 * @param progress 0 — выключен, 1 — включен (между значениями — плавная анимация)
	 */
	public static void drawToggle(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float progress, int accent) {
		int track = mix(0xFF3A3A46, accent, progress);
		fillRounded(graphics, x, y, width, height, height / 2, track);

		int knob = height - 4;
		int knobX = (int) (x + 2 + (width - knob - 4) * progress);
		fillRounded(graphics, knobX, y + 2, knob, knob, knob / 2, 0xFFF2F2F7);
	}
}

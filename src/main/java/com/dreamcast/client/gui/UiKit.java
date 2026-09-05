package com.dreamcast.client.gui;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.gui.theme.ClientTheme;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Набор «стеклянных» виджетов клиента: панель, строка значения, бар, чип, заголовок.
 *
 * <p>Зачем он нужен: раньше каждый элемент HUD и каждое меню рисовали свою
 * «тёмную пилюлю» руками, и в сумме это выглядело набором прямоугольников.
 * Здесь вся глубина собирается в одном месте — тень, стекло с лёгким
 * акцентным подкрашиванием, внутренний блик сверху, тёмное кольцо снаружи,
 * акцентная волосяная линия снизу, — и все экраны клиента берут её отсюда.
 * Меняется вкус — меняется сразу всё: HUD, ClickGUI, меню, Target HUD.</p>
 *
 * <p>Значения никогда не прыгают: {@link #tween} держит собственное состояние
 * на ключ и тянет число к цели с экспоненциальным сглаживанием, поэтому бары,
 * ширини пилюль и появление элементов анимированы, а не «щёлкают».</p>
 */
public final class UiKit {

	/** Наружное тёмное кольцо: без него стекло на светлом фоне растворяется. */
	private static final int OUTER_RING = 0x66000000;
	/** Верхний блик — 1 пиксель света, даёт ощущение стекла. */
	private static final int SPECULAR = 0x1FFFFFFF;

	private static final Map<String, Float> TWEENS = new HashMap<>();
	private static final Map<String, Long> STAMPS = new HashMap<>();

	private UiKit() {
	}

	// ------------------------------------------------------------------
	// Движение
	// ------------------------------------------------------------------

	/**
	 * Экспоненциальное сглаживание к цели. Ключ — имя виджета; состояние живёт
	 * пока не истечёт {@code ttlMs} без обращений (иначе карта росла бы вечно).
	 */
	public static float tween(String key, float target, float speed, long now) {
		Long stamp = STAMPS.get(key);
		if (stamp != null && now - stamp > 5_000L) {
			TWEENS.remove(key);
		}
		STAMPS.put(key, now);
		float current = TWEENS.getOrDefault(key, target);
		float next = current + (target - current) * Math.min(1.0F, speed);
		if (Math.abs(next - target) < 0.0025F) {
			next = target;
		}
		TWEENS.put(key, next);
		return next;
	}

	/** Появление элемента: 0 → 1 с «выдохом». Считается от первого обращения. */
	public static float appear(String key, long now, int millis) {
		Long first = STAMPS.get("appear:" + key);
		if (first == null) {
			STAMPS.put("appear:" + key, now);
			first = now;
		}
		return Math.min(1.0F, (now - first) / (float) Math.max(1, millis));
	}

	public static void forget(String key) {
		TWEENS.remove(key);
		STAMPS.remove("appear:" + key);
	}

	// ------------------------------------------------------------------
	// Стекло
	// ------------------------------------------------------------------

	/**
	 * Основная панель элемента. {@code accentMix} — насколько сильно в стекло
	 * подмешан текущий цвет темы (0 — чёрное стекло, 1 — сплошной акцент).
	 */
	public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
	                         float alpha, float accentMix, long now) {
		panel(graphics, x, y, width, height, 6.0F, alpha, accentMix, now, true);
	}

	public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, double radius,
	                         float alpha, float accentMix, long now, boolean shadow) {
		if (width <= 0 || height <= 0 || alpha <= 0.002F) {
			return;
		}
		int accent = ClientTheme.accent(now);
		int top = RenderUtils.mix(0xF215151A, RenderUtils.withAlpha(accent, 0xFF), 0.07F + accentMix * 0.16F);
		int bottom = RenderUtils.mix(0xF50B0B0F, RenderUtils.withAlpha(accent, 0xFF), 0.03F + accentMix * 0.09F);
		top = RenderUtils.withAlpha(top, Math.min(1.0F, alpha));
		bottom = RenderUtils.withAlpha(bottom, Math.min(1.0F, alpha));

		if (shadow) {
			RenderUtils.drawSoftShadow(graphics, x, y, width, height, (int) radius, 4);
		}
		// Наружное кольцо чуть темнее стекла — читается как толщина стекла
		RenderUtils.fillRounded(graphics, x - 1, y - 1, width + 2, height + 2, radius + 1.0,
				RenderUtils.withAlpha(OUTER_RING, 0.55F * alpha));
		RenderUtils.fillRounded(graphics, x, y, width, height, radius, top, bottom);

		// Граница: сверху светлее, снизу — текущий цвет темы (перелив по краю)
		int border = RenderUtils.mix(0x3AFFFFFF, accent, 0.28F + accentMix * 0.5F);
		RenderUtils.fillRounded(graphics, x, y, width, height, radius,
				RenderUtils.withAlpha(border, 0.55F * alpha), RenderUtils.withAlpha(border, 0.22F * alpha));
		RenderUtils.fillRounded(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(0.0, radius - 1.0),
				top, bottom);

		// Блик сверху и волосяная акцентная линия снизу — то, что делает панель «объёмной»
		graphics.fill(x + (int) radius, y + 1, x + width - (int) radius, y + 2,
				RenderUtils.withAlpha(SPECULAR, 0.7F * alpha));
		for (int i = 0; i < Math.max(1, width - 2 * (int) radius); i++) {
			float t = i / (float) Math.max(1, width - 2 * (int) radius);
			graphics.fill(x + (int) radius + i, y + height - 1, x + (int) radius + i + 1, y + height,
					RenderUtils.withAlpha(ClientTheme.gradientAt(t, now), 0.5F * alpha * (0.4F + accentMix)));
		}
	}

	/** Заголовок: иконка-чип, разрядка в kerning, волосяная линия под ним. */
	public static void header(GuiGraphicsExtractor graphics, Font font, String title, String iconName,
	                         int x, int y, int width, float alpha, long now) {
		int accent = ClientTheme.accent(now);
		int cursor = x;
		if (iconName != null) {
			int chip = 11;
			RenderUtils.fillRounded(graphics, cursor, y - 1, chip, chip, 3.0F,
					RenderUtils.withAlpha(accent, 0.22F * alpha));
			icon(graphics, iconName, cursor + 2, y + 1, 7, RenderUtils.withAlpha(accent, 0.95F * alpha));
			cursor += chip + 4;
		}
		tracked(graphics, font, title.toUpperCase(Locale.ROOT), cursor, y, alpha, 1);
		int lineY = y + Math.max(font.lineHeight, 10) + 3;
		graphics.fill(x, lineY, x + width, lineY + 1, RenderUtils.withAlpha(0x2EFFFFFF, alpha));
		graphics.fill(x, lineY, x + Math.round(width * 0.32F), lineY + 1,
				RenderUtils.withAlpha(accent, 0.75F * alpha));
	}

	/** Текст с разрядкой: заголовки читаются «дизайном», а не подписью. */
	public static void tracked(GuiGraphicsExtractor graphics, Font font, String text, int x, int y,
	                           float alpha, int spacing) {
		int step = Math.max(0, spacing);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			String s = String.valueOf(c);
			RenderUtils.textFlat(graphics, font, s, x, y, RenderUtils.withAlpha(0xFFF2F4FA, 0.92F * alpha));
			x += RenderUtils.width(font, s) + step;
		}
	}

	/** Иконка из наших спрайтов (assets/dreamcast/textures/gui/icons). */
	public static void icon(GuiGraphicsExtractor graphics, String name, int x, int y, int size, int color) {
		if (name == null || name.isEmpty()) {
			return;
		}
	graphics.blit(RenderPipelines.GUI_TEXTURED,
				Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "textures/gui/icons/" + name + ".png"),
				x, y, 0.0F, 0.0F, size, size, 40, 40, 40, 40, color);
	}

	// ------------------------------------------------------------------
	// Значения
	// ------------------------------------------------------------------

	/** Строка «метка — значение»: подписи слева тусклые, числа справа яркие. */
	public static int row(GuiGraphicsExtractor graphics, Font font, String label, String value,
	                       int x, int y, int width, float alpha) {
		RenderUtils.textFlat(graphics, font, label, x, y, RenderUtils.withAlpha(0xFF9A9DAE, 0.85F * alpha));
		String shown = RenderUtils.clamp(font, value, width - RenderUtils.width(font, label) - 8);
		RenderUtils.textBold(graphics, font, shown, x + width - RenderUtils.width(font, shown), y,
				RenderUtils.withAlpha(0xFFF4F6FF, alpha));
		return y + font.lineHeight + 3;
	}

	/** Бар со скруглёнными концами, глянцем и ярким «сегментом» на конце. */
	public static void bar(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
	                        float fraction, long now, float alpha) {
		fraction = Math.max(0.0F, Math.min(1.0F, fraction));
		float radius = height / 2.0F;
		RenderUtils.fillRounded(graphics, x, y, width, height, radius, RenderUtils.withAlpha(0x3AFFFFFF, alpha));
		if (fraction <= 0.002F) {
			return;
		}
		int filled = Math.max(height, Math.round(width * fraction));
		int left = ClientTheme.gradientAt(0.0F, now);
		int right = ClientTheme.gradientAt(1.0F, now);
		RenderUtils.fillRounded(graphics, x, y, filled, height, radius,
				RenderUtils.withAlpha(left, 0.95F * alpha), RenderUtils.withAlpha(right, 0.95F * alpha));
		// глянец по верхней половине — тот же приём, что у настоящих виджетов
		RenderUtils.fillRounded(graphics, x + 1, y + 1, filled - 2, Math.max(1, height / 2 - 1),
				Math.max(0.0F, radius - 1.0F), RenderUtils.withAlpha(0x3DFFFFFF, alpha));
		graphics.fill(x + filled - 1, y - 1, x + filled, y + height + 1,
				RenderUtils.withAlpha(0xFFFFFFFF, 0.8F * alpha));
	}

	/** Тонкая вертикальная шкала (здоровье, задержка) с «каплей» на конце. */
	public static void column(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
	                           float fraction, int color, float alpha) {
		fraction = Math.max(0.0F, Math.min(1.0F, fraction));
		RenderUtils.fillRounded(graphics, x, y, width, height, width / 2.0F,
				RenderUtils.withAlpha(0x33FFFFFF, 0.7F * alpha));
		int used = Math.max(width, Math.round(height * fraction));
		int top = y + height - used;
		RenderUtils.fillRounded(graphics, x, top, width, used, width / 2.0F,
				RenderUtils.withAlpha(color, alpha));
		RenderUtils.fillRounded(graphics, x, top, width, Math.min(used, width), width / 2.0F,
				RenderUtils.withAlpha(0xFFFFFFFF, 0.55F * alpha));
	}

	/** Чип: маленькая пилюля со значением (пинг, FPS, дистанция). */
	public static int chip(GuiGraphicsExtractor graphics, Font font, String text, int x, int y,
	                        int color, float alpha, long now) {
		int pad = 4;
		int width = RenderUtils.width(font, text) + pad * 2;
		int height = font.lineHeight + 4;
		RenderUtils.fillRounded(graphics, x, y, width, height, 4.0F,
				RenderUtils.mix(RenderUtils.withAlpha(0xE60D0D12, alpha), color, 0.16F));
		RenderUtils.fillRounded(graphics, x, y, width, height, 4.0F,
				RenderUtils.withAlpha(RenderUtils.mix(color, ClientTheme.accent(now), 0.35F), 0.5F * alpha),
				RenderUtils.withAlpha(0x00000000, 0.0F));
		RenderUtils.textFlat(graphics, font, text, x + pad, y + 2,
				RenderUtils.withAlpha(0xFFF2F4FA, alpha));
		return x + width + 3;
	}

	/** Значение с подписью сверху — крупная цифра и мелкое имя под ней. */
	public static void stat(GuiGraphicsExtractor graphics, Font font, String name, String value,
	                         int x, int y, float alpha, long now) {
		RenderUtils.textFlat(graphics, font, name, x, y, RenderUtils.withAlpha(0xFF8F93A6, 0.9F * alpha));
		RenderUtils.textBold(graphics, font, value, x, y + font.lineHeight + 1,
				RenderUtils.withAlpha(0xFFF6F8FF, alpha));
	}

	/** Разделитель «·» в одну строку, чтобы не плодить подписи. */
	public static void dot(GuiGraphicsExtractor graphics, int x, int y, float alpha, long now) {
		RenderUtils.fillCircle(graphics, x, y, 1.6F, RenderUtils.withAlpha(ClientTheme.accent(now), 0.8F * alpha));
	}

	/** Клиентский «холст»: тёмная вуаль на весь экран + блюр, если он доступен. */
	public static void backdrop(GuiGraphicsExtractor graphics, Minecraft client, int width, int height, float alpha) {
		graphics.fill(0, 0, width, height, RenderUtils.withAlpha(0xC405050A, alpha));
	}
}

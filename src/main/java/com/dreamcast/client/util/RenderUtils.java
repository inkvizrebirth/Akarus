package com.dreamcast.client.util;

import com.dreamcast.client.DreamcastClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Помощники для рисования интерфейса.
 *
 * В 26.2 вся отрисовка GUI идёт через {@link GuiGraphicsExtractor}: он сам складывает
 * элементы в render state, а координаты у него целочисленные. Поэтому весь «сглаженный»
 * вид (без лесенок на скруглениях) считается здесь: крайние пиксели дуги дорисовываются
 * с частичной прозрачностью — это и есть антиалиасинг на целочисленной сетке.
 *
 * Скруглённые прямоугольники рисуются полосками: средняя часть — одним {@code fill},
 * полосы углов — построчно. Так панель любого размера стоит ~2*radius вызовов, а не height.
 */
public final class RenderUtils {

	/** Ниже этого порога прикрытие не рисуется — иначе видны серые «шумовые» пиксели. */
	private static final float COVERAGE_EPSILON = 0.03f;

	private RenderUtils() {
	}

	// ------------------------------------------------------------------
	// Фирменный шрифт (Manrope): в 26.2 шрифт задаётся стилем строки,
	// а не отдельным экземпляром Font — оборачиваем строки в компоненты
	// ------------------------------------------------------------------

	/** Описание фирменного шрифта: assets/dreamcast/font/dreamcast.json. */
	public static final FontDescription FONT = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "dreamcast"));
	/** Жирное начертание — для логотипа и заголовков. */
	public static final FontDescription FONT_BOLD = new FontDescription.Resource(
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "dreamcast_bold"));

	/** Оборачивает строку в компонент с фирменным шрифтом. */
	public static MutableComponent styled(String text) {
		return Component.literal(text).setStyle(Style.EMPTY.withFont(FONT));
	}

	/** То же, но жирным начертанием. */
	public static MutableComponent styledBold(String text) {
		return Component.literal(text).setStyle(Style.EMPTY.withFont(FONT_BOLD));
	}

	/** Ширина строки фирменным шрифтом. */
	public static int width(Font font, String text) {
		return font.width(styled(text));
	}

	/** Ширина строки жирным фирменным шрифтом. */
	public static int widthBold(Font font, String text) {
		return font.width(styledBold(text));
	}

	/** Ширина строки с разрядкой жирным шрифтом. */
	public static int trackedWidthBold(Font font, String text, int tracking) {
		if (text.isEmpty()) {
			return 0;
		}
		return widthBold(font, text) + tracking * (text.length() - 1);
	}

	/** Текст с разрядкой жирным шрифтом — логотип и заголовки. */
	public static void drawTrackedBold(GuiGraphicsExtractor graphics, Font font, String text, int x, int y,
			int color, int tracking) {
		if (text.isEmpty()) {
			return;
		}
		int cursor = x;
		for (int i = 0; i < text.length(); i++) {
			String symbol = String.valueOf(text.charAt(i));
			graphics.text(font, styledBold(symbol), cursor, y, color, false);
			cursor += widthBold(font, symbol) + tracking;
		}
	}

	/** Рисует строку фирменным шрифтом с тенью. */
	public static void text(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		graphics.text(font, styled(text), x, y, color, true);
	}

	/** Рисует строку фирменным шрифтом без тени. */
	public static void textFlat(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		graphics.text(font, styled(text), x, y, color, false);
	}

	/** Рисует строку жирным фирменным шрифтом без тени. */
	public static void textBold(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		graphics.text(font, styledBold(text), x, y, color, false);
	}

	/** Рисует строку по центру указанной координаты фирменным шрифтом. */
	public static void textCentered(GuiGraphicsExtractor graphics, Font font, String text,
			int centerX, int y, int color, boolean shadow) {
		graphics.text(font, styled(text), centerX - width(font, text) / 2, y, color, shadow);
	}

	// ------------------------------------------------------------------
	// Гексагональная текстура кнопок
	// ------------------------------------------------------------------

	/**
	 * Сотовая текстура для кнопок меню: маленькие шестиугольники с зазорами.
	 *
	 * Возле курсора соты «расступаются» — радиальный сдвиг с гауссовым
	 * затуханием — и подсвечиваются акцентом. {@code glow} — общий уровень
	 * подсветки кнопки (наведение), 0 — только базовая тёмная сетка.
	 */
	public static void drawHexPattern(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
	                                  int accent, float mouseX, float mouseY, float glow) {
		// Соты с расчётом расстояния для каждой ячейки были главным источником
		// просадок FPS в меню: семь кнопок обсчитывались на каждом кадре.
		// Текстура нужна только при наведении, поэтому в обычном состоянии её
		// не рисуем вовсе.
		if (w <= 4 || h <= 4 || glow <= 0.003f) {
			return;
		}
		final float r = 6.0f;
		final float stepX = 1.7320508f * r;   // √3·r — шаг по горизонтали
		final float stepY = 1.5f * r;         // шаг по вертикали
		final float pushRadius = 30.0f;       // работает и немного за границей кнопки
		final float pushStrength = 3.4f;      // насколько соты отодвигаются
		final float radiusSq = pushRadius * pushRadius;

		graphics.enableScissor(x, y, x + w, y + h);
		int totalRows = (int) Math.ceil(h / stepY) + 2;
		// Раньше каждый кадр обходил всю сетку каждой затухающей кнопки. Теперь
		// берём только несколько строк/столбцов вокруг курсора: визуально эффект
		// тот же, а число отрисовываемых сот падает в несколько раз.
		int firstRow = Math.max(0, (int) Math.floor((mouseY - y - pushRadius - r) / stepY));
		int lastRow = Math.min(totalRows - 1, (int) Math.ceil((mouseY - y + pushRadius + r) / stepY));
		for (int row = firstRow; row <= lastRow; row++) {
			float cy = y + r + row * stepY;
			float shift = (row & 1) == 0 ? 0.0f : stepX * 0.5f;
			int firstCol = Math.max(0,
					(int) Math.floor((mouseX - x - shift - pushRadius) / stepX));
			int lastCol = Math.min((int) Math.ceil(w / stepX) + 1,
					(int) Math.ceil((mouseX - x - shift + pushRadius) / stepX));
			for (int col = firstCol; col <= lastCol; col++) {
				float cx = x + r + col * stepX + shift - stepX;

				// Квадрат расстояния позволяет сразу отсеять далёкие соты без sqrt/exp.
				float dx = cx - mouseX;
				float dy = cy - mouseY;
				float distanceSq = dx * dx + dy * dy;
				if (distanceSq >= radiusSq) {
					continue;
				}
				float influence = 1.0F - distanceSq / radiusSq;
				influence *= influence;
				float ox = 0.0f;
				float oy = 0.0f;
				if (distanceSq > 0.001F && influence > 0.02F) {
					float dist = (float) Math.sqrt(distanceSq);
					float push = pushStrength * influence;
					ox = dx / dist * push;
					oy = dy / dist * push;
				}

				// Подсветка: у курсора соты наливаются акцентом
				float near = influence;
				int hexColor = mix(0x1AFFFFFF, accent, Math.min(1.0f, near * 0.85f + glow * 0.25f));
				float alpha = 0.30f + 0.55f * near + 0.25f * glow;
				drawHex(graphics, cx + ox, cy + oy, r - 0.9f, withAlpha(hexColor, Math.min(1.0f, alpha)));
			}
		}
		graphics.disableScissor();
	}

	/** Один шестиугольник (вершина сверху), построчно с антиалиасингом. */
	private static void drawHex(GuiGraphicsExtractor graphics, float cx, float cy, float r, int color) {
		for (int dy = 0; dy <= (int) Math.floor(r); dy++) {
			// Верхняя половина шире к середине, нижняя симметрична
			float halfUpper = hexHalfWidth(r, dy);
			if (halfUpper <= 0.0f) {
				continue;
			}
			fillRow(graphics, (int) Math.floor(cy - dy), cx - halfUpper, cx + halfUpper, color);
			fillRow(graphics, (int) Math.floor(cy + dy), cx - halfUpper, cx + halfUpper, color);
		}
	}

	/** Полуширина соты на вертикальном смещении dy: трапеция → прямоугольник. */
	private static float hexHalfWidth(float r, float dy) {
		if (dy > r) {
			return 0.0f;
		}
		if (dy <= r * 0.5f) {
			return 0.8660254f * r;
		}
		return Math.max(0.0f, (r - dy) * 1.7320508f);
	}

	// ------------------------------------------------------------------
	// Волна клика: одна на все экраны, рисуется поверх содержимого
	// ------------------------------------------------------------------

	/** Длительность жизни волны, мс. */
	private static final long CLICK_WAVE_DURATION = 360;

	private record ClickWave(float x, float y, long born) {
	}

	private static final java.util.List<ClickWave> CLICK_WAVES = new java.util.ArrayList<>();

	/** Регистрирует волну клика (экранные координаты) — рисуется всеми экранами. */
	public static void addClickWave(double x, double y) {
		CLICK_WAVES.add(new ClickWave((float) x, (float) y, net.minecraft.util.Util.getMillis()));
		while (CLICK_WAVES.size() > 3) {
			CLICK_WAVES.remove(0);
		}
	}

	/** Рисует активные волны поверх содержимого; вызывать в конце extractRenderState. */
	public static void drawClickWaves(GuiGraphicsExtractor graphics, int accent) {
		if (CLICK_WAVES.isEmpty()) {
			return;
		}
		long now = net.minecraft.util.Util.getMillis();
		CLICK_WAVES.removeIf(wave -> now - wave.born() >= CLICK_WAVE_DURATION);
		for (ClickWave wave : CLICK_WAVES) {
			float progress = Math.min((now - wave.born()) / (float) CLICK_WAVE_DURATION, 1.0f);
			drawClickWave(graphics, wave.x(), wave.y(), progress, accent, now);
		}
	}

	/** Одна волна: гауссов ореол («блюр»), ядро, мерцающее точечное кольцо, эхо. */
	private static void drawClickWave(GuiGraphicsExtractor graphics, float cx, float cy,
	                                  float progress, int accent, long now) {
		float eased = 1.0f - progress * progress * progress;
		float radius = 6.0f + 28.0f * eased;
		float fade = (1.0f - progress) * (1.0f - progress);

		// 1. «Блюр»-ореол: слои с гауссовым затуханием
		for (int i = 2; i >= 1; i--) {
			float spread = 1.0f + i * 0.06f;
			float layerFade = (float) Math.exp(-i * i * 0.45f);
			fillCircle(graphics, cx, cy, radius * spread,
					withAlpha(accent, 0.075f * layerFade * fade));
		}

		// 2. Ядро волны
		fillCircle(graphics, cx, cy, radius, withAlpha(accent, 0.16f * fade));
		fillCircle(graphics, cx, cy, radius * 0.22f, withAlpha(accent, 0.20f * fade));

		// 3. Мерцающее точечное кольцо
		int dots = 18;
		for (int k = 0; k < dots; k++) {
			float angle = (k / (float) dots) * 6.2831855f;
			float shimmer = 0.5f + 0.5f * (float) Math.sin(angle * 3.0f + now * 0.012f);
			float dotR = radius * (1.0f - 0.035f * shimmer);
			float dx = cx + (float) Math.cos(angle) * dotR;
			float dy = cy + (float) Math.sin(angle) * dotR;
			int size = shimmer > 0.6f ? 3 : 2;
			int dotColor = mix(accent, 0xFFF6F6F8, 0.35f * shimmer);
			graphics.fill((int) dx - size / 2, (int) dy - size / 2,
					(int) dx - size / 2 + size, (int) dy - size / 2 + size,
					withAlpha(dotColor, (0.35f + 0.55f * shimmer) * fade));
		}

	}

	// ------------------------------------------------------------------
	// Цвета
	// ------------------------------------------------------------------

	/** Линейное смешивание двух ARGB-цветов. t = 0 — первый цвет, t = 1 — второй. */
	public static int mix(int first, int second, float t) {
		float k = clamp01(t);
		return ARGB.color(
				(int) (ARGB.alpha(first) + (ARGB.alpha(second) - ARGB.alpha(first)) * k),
				(int) (ARGB.red(first) + (ARGB.red(second) - ARGB.red(first)) * k),
				(int) (ARGB.green(first) + (ARGB.green(second) - ARGB.green(first)) * k),
				(int) (ARGB.blue(first) + (ARGB.blue(second) - ARGB.blue(first)) * k)
		);
	}

	/** Меняет прозрачность цвета: alpha01 — от 0 (полностью прозрачный) до 1. */
	public static int withAlpha(int color, float alpha01) {
		return ARGB.color((int) (ARGB.alpha(color) * clamp01(alpha01)), ARGB.red(color), ARGB.green(color), ARGB.blue(color));
	}

	/** Умножает альфу на коэффициент — нужно для антиалиасинга края. */
	public static int fade(int color, float factor) {
		return withAlpha(color, factor);
	}

	/** Цвет строки градиента от top до bottom по позиции line/total. */
	public static int gradientColor(int top, int bottom, float line, float total) {
		if (total <= 1.0f) {
			return top;
		}
		return mix(top, bottom, line / total);
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
	/**
	 * Фаза радуги 0..1 по времени и «скорости» (1..10).
	 *
	 * Порядок вычисления важен: время надо брать по модулю ПЕРЕВ превращением в
	 * float. {@code currentTimeMillis() * (speed / 2000f)} даёт ~3e9 — у float там
	 * мантиссы не хватает на долю цикла, hue квантуется в целое, и «радуга»
	 * замирает на одном цвете.
	 */
	public static float rainbowPhase(long timeMillis, int speed) {
		long period = Math.max(200L, 20000L / Math.max(1, speed));
		return (timeMillis % period) / (float) period;
	}

	public static int rainbow(long timeMillis, float offset) {
		return hsb(((timeMillis % 4000L) / 4000.0f) + offset, 0.72f, 1.0f, 0xFF);
	}

	/** Гаусс-подобный спад: 1 в центре, ~0 на краях. Используется для теней и свечения. */
	public static float falloff(float t) {
		float x = clamp01(t);
		return (1.0f - x) * (1.0f - x);
	}

	// ------------------------------------------------------------------
	// Фигуры
	// ------------------------------------------------------------------

	/** Прямоугольник со скруглёнными углами. */
	public static void fillRounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		fillRounded(graphics, x, y, width, height, radius, color, color, true, true, true, true);
	}

	/** Прямоугольник со скруглёнными углами и вертикальным градиентом. */
	public static void fillRounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius,
			int topColor, int bottomColor) {
		fillRounded(graphics, x, y, width, height, radius, topColor, bottomColor, true, true, true, true);
	}

	/** Прямоугольник, у которого скруглён только верх (для «шапок» панелей). */
	public static void fillRoundedTop(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		fillRounded(graphics, x, y, width, height, radius, color, color, true, true, false, false);
	}

	/** Прямоугольник, у которого скруглён только низ. */
	public static void fillRoundedBottom(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
		fillRounded(graphics, x, y, width, height, radius, color, color, false, false, true, true);
	}

	/**
	 * Скруглённый прямоугольник с выборочными углами и, при желании, градиентом.
	 *
	 * Средняя часть заливается одним прямоугольником с градиентом, угловые полосы —
	 * построчно, где край пикселя прикрывается пропорционально тому, насколько он
	 * попал внутрь дуги. За счёт этого угол выглядит круглым, а не «ступеньками».
	 */
	public static void fillRounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius,
			int topColor, int bottomColor, boolean topLeft, boolean topRight, boolean bottomLeft, boolean bottomRight) {
		if (width <= 0 || height <= 0) {
			return;
		}

		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		int x1 = x + width;
		int y1 = y + height;

		int topBand = topLeft || topRight ? r : 0;
		int bottomBand = bottomLeft || bottomRight ? r : 0;

		// Средняя часть — одним градиентом; цвета по краям полосы считаем честно,
		// чтобы на стыке с угловыми полосами не было «ступеньки»
		int middleTop = y + topBand;
		int middleBottom = y1 - bottomBand;
		if (middleBottom > middleTop) {
			graphics.fillGradient(x, middleTop, x1, middleBottom,
					RenderUtils.gradientColor(topColor, bottomColor, topBand, height),
					RenderUtils.gradientColor(topColor, bottomColor, height - bottomBand - 1, height));
		}

		for (int i = 0; i < topBand; i++) {
			int rowY = y + i;
			float inset = arcInset(r, r - 0.5f - i);
			drawRow(graphics, rowY, x, x1, inset, topLeft, topRight,
					RenderUtils.gradientColor(topColor, bottomColor, i, height));
		}

		for (int i = 0; i < bottomBand; i++) {
			int rowY = y1 - 1 - i;
			float inset = arcInset(r, r - 0.5f - i);
			drawRow(graphics, rowY, x, x1, inset, bottomLeft, bottomRight,
					RenderUtils.gradientColor(topColor, bottomColor, height - 1 - i, height));
		}
	}

	/** Одна полоса скруглённого края: середина целиком, края — с прикрытием. */
	private static void drawRow(GuiGraphicsExtractor graphics, int rowY, int x, int x1, float inset,
			boolean roundLeft, boolean roundRight, int color) {
		float left = roundLeft ? x + inset : x;
		float right = roundRight ? x1 - inset : x1;
		fillRow(graphics, rowY, left, right, color);
	}

	/** Заливает строку от left до right, сглаживая края дробным прикрытием пикселей. */
	private static void fillRow(GuiGraphicsExtractor graphics, int rowY, float left, float right, int color) {
		if (right - left <= 0.0f) {
			return;
		}

		int first = (int) Math.ceil(left);
		int last = (int) Math.floor(right);

		if (last <= first) {
			// Вся строка влезает в один пиксель — рисуем его частично
			graphics.fill(Math.round(left), rowY, Math.round(left) + 1, rowY + 1,
					withAlpha(color, clamp01(right - left)));
			return;
		}

		graphics.fill(first, rowY, last, rowY + 1, color);

		float leftCoverage = first - left;
		float rightCoverage = right - last;
		if (leftCoverage > COVERAGE_EPSILON) {
			graphics.fill(first - 1, rowY, first, rowY + 1, withAlpha(color, leftCoverage));
		}
		if (rightCoverage > COVERAGE_EPSILON) {
			graphics.fill(last, rowY, last + 1, rowY + 1, withAlpha(color, rightCoverage));
		}
	}

	/** Насколько отступить от края строки, чтобы попасть в дугу радиуса r на расстоянии d от её центра. */
	private static float arcInset(int r, float distance) {
		if (r <= 0 || distance >= r) {
			return 0.0f;
		}
		float d = Math.max(0.0f, distance);
		return r - (float) Math.sqrt(Math.max(0.0, r * r - d * d));
	}

	/** Скруглённая «рамка»: цветной контур нужной толщины и заливка внутри. */
	public static void fillRoundedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius,
			int borderColor, int fillColor) {
		fillRoundedBorder(graphics, x, y, width, height, radius, borderColor, fillColor, fillColor, 1);
	}

	/** Рамка с внутренним градиентом и настраиваемой толщиной линии. */
	public static void fillRoundedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius,
			int borderColor, int fillTop, int fillBottom, int thickness) {
		int t = Math.max(1, thickness);
		fillRounded(graphics, x, y, width, height, radius, borderColor);
		if (width > t * 2 && height > t * 2) {
			fillRounded(graphics, x + t, y + t, width - t * 2, height - t * 2, Math.max(0, radius - t), fillTop, fillBottom);
		}
	}

	/** Залитый круг с мягким краем — используется для волны по клику и свечения. */
	public static void fillCircle(GuiGraphicsExtractor graphics, float centerX, float centerY, float radius, int color) {
		if (radius <= 0.5f) {
			return;
		}
		int r = (int) Math.ceil(radius);
		float r2 = radius * radius;
		for (int dy = -r; dy <= r; dy++) {
			float chord = (float) Math.sqrt(Math.max(0.0f, r2 - dy * dy));
			int y = (int) Math.floor(centerY + dy);
			fillRow(graphics, y, centerX - chord, centerX + chord, color);
		}
	}

	/**
	 * Мягкая многослойная тень: концентрические скруглённые прямоугольники, у которых
	 * прозрачность падает по гауссу, а не линейно — так край тени не полосатый.
	 */
	public static void drawSoftShadow(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int layers) {
		int count = Math.max(1, layers);
		for (int i = count; i >= 1; i--) {
			float t = i / (float) count;
			int grow = Math.round(t * 12.0f);
			float alpha = 1.0f - t;
			alpha = alpha * alpha * 0.34f;
			fillRounded(graphics, x - grow, y - grow / 2 + 3, width + grow * 2, height + grow + 3,
					radius + grow / 2 + 1, withAlpha(0xFF000000, alpha));
		}
	}

	// ------------------------------------------------------------------
	// Виджеты
	// ------------------------------------------------------------------

	/**
	 * Тумблер включения/выключения.
	 *
	 * @param progress 0 — выключен, 1 — включен (между значениями — плавная анимация)
	 */
	public static void drawToggle(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float progress, int accent) {
		float p = clamp01(progress);

		int track = mix(0xFF2A2A33, accent, 0.20f + 0.80f * p);
		fillRounded(graphics, x, y, width, height, height / 2, withAlpha(track, 0.55f + 0.45f * p));

		// Тонкая светящаяся кромка по верху дорожки — «стекло»
		if (height >= 8) {
			int inner = height / 2;
			fillRounded(graphics, x + 1, y + 1, width - 2, height - 2, inner, 0x14FFFFFF, 0x00FFFFFF, true, true, false, false);
		}

		int knobSize = height - 4;
		int knobX = (int) Math.round(x + 2 + (width - knobSize - 4) * p);
		int knobY = y + 2;
		fillRounded(graphics, knobX, knobY + 1, knobSize, knobSize, knobSize / 2, withAlpha(0xFF000000, 0.35f));
		fillRounded(graphics, knobX, knobY, knobSize, knobSize, knobSize / 2, mix(0xFFEDEDF5, 0xFFFFFFFF, p));
	}

	/**
	 * Слайдер: дорожка, заполненная часть и бегунок.
	 *
	 * @param progress 0..1 — положение значения на шкале
	 */
	public static void drawSlider(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float progress, int accent) {
		float p = clamp01(progress);
		int radius = height / 2;

		fillRounded(graphics, x, y, width, height, radius, 0x30FFFFFF);
		int filled = Math.max(height, (int) Math.round(width * p));
		fillRounded(graphics, x, y, filled, height, radius, withAlpha(accent, 0.55f), withAlpha(accent, 0.95f));

		int knob = height + 4;
		int knobX = (int) Math.round(x + (width - height) * p - (knob - height) / 2.0f);
		fillRounded(graphics, knobX, y - 2, knob, knob, knob / 2, withAlpha(0xFF000000, 0.4f));
		fillRounded(graphics, knobX, y - 3, knob, knob, knob / 2, 0xFFF6F6FA);
	}

	// ------------------------------------------------------------------
	// Текст
	// ------------------------------------------------------------------

	/** Обрезает строку многоточием, если она не влезает в заданную ширину. */
	public static String clamp(Font font, String text, int maxWidth) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		if (maxWidth <= 0) {
			return "";
		}
		if (width(font, text) <= maxWidth) {
			return text;
		}

		String ellipsis = "…";
		String cut = text;
		while (cut.length() > 1 && width(font, cut + ellipsis) > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + ellipsis;
	}

	/** Ширина строки с дополнительным межбуквенным интервалом. */
	public static int trackedWidth(Font font, String text, int tracking) {
		if (text.isEmpty()) {
			return 0;
		}
		return width(font, text) + tracking * (text.length() - 1);
	}

	/**
	 * Текст с межбуквенным интервалом — так подписи и заголовки читаются «взрослее»,
	 * чем плотная пиксельная строка.
	 */
	public static void drawTracked(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, int tracking) {
		if (text.isEmpty()) {
			return;
		}
		int cursor = x;
		for (int i = 0; i < text.length(); i++) {
			String symbol = String.valueOf(text.charAt(i));
			graphics.text(font, styled(symbol), cursor, y, color, false);
			cursor += width(font, symbol) + tracking;
		}
	}

	/** Рисует текст, обрезанный по ширине, и возвращает позицию, после которой он закончился. */
	public static int drawClamped(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int maxWidth, int color) {
		String shown = clamp(font, text, maxWidth);
		graphics.text(font, styled(shown), x, y, color, false);
		return x + width(font, shown);
	}

	private static float clamp01(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}
}

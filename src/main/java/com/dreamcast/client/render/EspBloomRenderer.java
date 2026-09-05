package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.EspModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.List;

/**
 * «Свечение» ESP: многослойный ореол вокруг силуэта, вместо одной линии бокса.
 *
 * <p>Настоящий bloom (пост-проход по яркости кадра) в 26.2 нам недоступен — это
 * часть пайплайна, а не то, что можно дорисовать из мода, поэтому свечение
 * рисуется как это делает рисованная графика: <b>набор концентрических
 * полупрозрачных контуров</b>. Каждый следующий шире, тусклее и смещён наружу,
 * за счёт аддитивного смешивания наш слой и выглядит как мягкое свечение, а не
 * как «обводка в три пикселя».</p>
 *
 * <p>Состав одного кадра на цель (снизу вверх по яркости):</p>
 * <ol>
 *   <li>ореол — {@code halo} копий контура, раздутых на {@code spread·i} пикселей,
 *       с квадратичным затуханием альфы;</li>
 *   <li>«дыхание» — амплитуда и прозрачность ореола плавно пульсируют, фаза
 *       зависит от id сущности, поэтому толпа не мигает синхронно;</li>
 *   <li>ядро — тонкий яркий контур с градиентом по высоте (верх — основной цвет,
 *       низ — второй) health-подсветкой: чем меньше HP, тем сильнее уход в красный;</li>
 *   <li>уголки — короткие утолщённые скобы на 8 вершинах вторым цветом: силуэт
 *       читается даже когда цель наполовину в блоке;</li>
 *   <li>объём — процедурный glow-спрайт в центре и у головы (мягкая дымка,
 *       у головы ярче: взгляд цепляется за «лицо» цели);</li>
 *   <li>«пятак» — два кольца под ногами, расходящиеся от земли: видно, где цель
 *       стоит, даже когда её саму закрыли рельефом.</li>
 * </ol>
 *
 * <p>Все координаты — уже относительно камеры; ширина линий задаётся в пикселях
 * и пересчитывается через {@code unitsPerPixel}, поэтому ореол одинаково
 * выглядит на 720p и 4K (см. {@link WorldGeometryRenderer}).</p>
 */
public final class EspBloomRenderer {

	/** 12 рёбер AABB: индексы углов как (min=0 / max=1) по каждой оси. */
	private static final int[][] EDGES = {
			{0, 0, 0, 1, 0, 0}, {2, 0, 0, 3, 0, 0}, {2, 0, 2, 3, 0, 2}, {0, 0, 2, 1, 0, 2},
			{0, 1, 0, 1, 1, 0}, {2, 1, 0, 3, 1, 0}, {2, 1, 2, 3, 1, 2}, {0, 1, 2, 1, 1, 2},
			{0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {1, 0, 2, 1, 1, 2}, {0, 0, 2, 0, 1, 2},
	};

	/** Один цикл «расхождения» пятак-колец, мс. */
	private static final long POOL_PERIOD_MS = 1600L;

	private EspBloomRenderer() {
	}

	/**
	 * @param boxes     боксы целей, уже собранные на извлечении кадра
	 * @param now       время кадра ({@code Util.getMillis()}) для пульсации
	 * @param camX/camY/camZ позиция камеры — вычитается здесь, а не в модуле
	 */
	public static void draw(EspModule esp, List<EspModule.EspBox> boxes, PoseStack.Pose pose,
	                        VertexConsumer buffer, double camX, double camY, double camZ,
	                        float unitsPerPixel, long now) {
		if (boxes.isEmpty()) {
			return;
		}
		int halo = esp.haloLayers();
		float spread = esp.haloSpread();
		boolean breath = esp.breath();
		float coreWidth = Math.max(1.0F, esp.boxWidth() * 0.65F);

		for (EspModule.EspBox box : boxes) {
			double centerX = (box.minX() + box.maxX()) / 2.0 - camX;
			double centerY = (box.minY() + box.maxY()) / 2.0 - camY;
			double centerZ = (box.minZ() + box.maxZ()) / 2.0 - camZ;
			double height = box.maxY() - box.minY();

			// Пульсация: ±6 % по раздуву и ±12 % по яркости, фаза — от id цели
			float pulse = breath
					? (float) Math.sin((now + box.entityId() * 211L) / 620.0)
					: 0.0F;
			double inflate = 1.0 + 0.06 * pulse;

			// 1) Ореол: от самого широкого и тусклого к ядру — так альфы складываются
			//    аддитивно и край не «полосит»
			for (int layer = halo; layer >= 1; layer--) {
				float t = layer / (float) Math.max(1, halo);
				double pad = (0.5 + spread * t) * inflate * unitsPerPixel;
				float alpha = (1.0F - t) * (1.0F - t) * 0.55F * (1.0F + 0.12F * pulse);
				int color = RenderUtils.withAlpha(secondColor(esp, box), alpha);
				drawEdges(esp, box, pose, buffer, camX, camY, camZ, pad, color,
						(0.8F + 3.2F * t) * Math.max(1.0F, esp.boxWidth() * 0.5F), false);
			}

			// 2) Ядро контура — яркое, с градиентом по высоте и подкраской по здоровью
			drawEdges(esp, box, pose, buffer, camX, camY, camZ, 0.0, 0, coreWidth, true);

			// 3) Уголки: 8 вершин, короткие скобы вторым цветом
			if (esp.cornerBrackets()) {
				drawBrackets(esp, box, pose, buffer, camX, camY, camZ, unitsPerPixel, height);
			}

			// 4) Объём: дымка по центру и яркое пятно у головы
			if (halo > 0) {
				int haze = RenderUtils.withAlpha(baseColor(esp, box), 0.20F + 0.05F * pulse);
				WorldGeometryRenderer.glow(buffer, pose, centerX, centerY, centerZ,
						Math.max(0.35, height * 0.62), haze, Math.min(4, halo));
				int head = RenderUtils.withAlpha(baseColor(esp, box), 0.34F);
				WorldGeometryRenderer.glow(buffer, pose, centerX, box.maxY() - camY, centerZ,
						Math.max(0.12, height * 0.18), head, 2);
			}

			// 5) «Пятак» под ногами: два расходящихся кольца
			if (esp.groundPool()) {
				drawPool(esp, box, pose, buffer, camX, camY, camZ, unitsPerPixel, now, centerX, centerZ);
			}
		}
	}

	/** Контур бокса, раздутый на {@code pad} мировых единиц (нуль — ровно по габаритам). */
	private static void drawEdges(EspModule esp, EspModule.EspBox box, PoseStack.Pose pose,
	                              VertexConsumer buffer, double camX, double camY, double camZ,
	                              double pad, int flatColor, float width, boolean gradientCore) {
		double minX = box.minX() - camX - pad;
		double minY = box.minY() - camY - pad;
		double minZ = box.minZ() - camZ - pad;
		double maxX = box.maxX() - camX + pad;
		double maxY = box.maxY() - camY + pad;
		double maxZ = box.maxZ() - camZ + pad;

		for (int[] edge : EDGES) {
			double x0 = corner(edge[0], minX, maxX);
			double y0 = corner(edge[1], minY, maxY);
			double z0 = corner(edge[2], minZ, maxZ);
			double x1 = corner(edge[3], minX, maxX);
			double y1 = corner(edge[4], minY, maxY);
			double z1 = corner(edge[5], minZ, maxZ);

			int color0 = flatColor;
			int color1 = flatColor;
			if (gradientCore) {
				// Верх — основной цвет, низ — второй; health-сдвиг уже внутри boxColor
				color0 = RenderUtils.withAlpha(esp.boxColorWithHealth(box, y0), 0.94F);
				color1 = RenderUtils.withAlpha(esp.boxColorWithHealth(box, y1), 0.94F);
			}
			WorldGeometryRenderer.line(buffer, pose, x0, y0, z0, color0, x1, y1, z1, color1,
					width, unitsPerPixel);
		}
	}

	/** Короткие скобы на вершинах: читают силуэт, когда цель наполовину в блоке. */
	private static void drawBrackets(EspModule esp, EspModule.EspBox box, PoseStack.Pose pose,
	                                 VertexConsumer buffer, double camX, double camY, double camZ,
	                                 float unitsPerPixel, double height) {
		// Длина плеча скобы — 22 % высоты, но не меньше 0.35 блока
		double arm = Math.min(0.85, Math.max(0.35, 0.22 * height));
		double minX = box.minX() - camX;
		double minY = box.minY() - camY;
		double minZ = box.minZ() - camZ;
		double maxX = box.maxX() - camX;
		double maxY = box.maxY() - camY;
		double maxZ = box.maxZ() - camZ;
		int color = RenderUtils.withAlpha(secondColor(esp, box), 0.92F);
		float width = Math.max(1.4F, esp.boxWidth() * 0.9F);

		for (int cornerIndex = 0; cornerIndex < 8; cornerIndex++) {
			int sx = (cornerIndex & 1) == 0 ? 0 : 1;
			int sy = (cornerIndex & 2) == 0 ? 0 : 1;
			int sz = (cornerIndex & 4) == 0 ? 0 : 1;
			double x = corner(sx, minX, maxX);
			double y = corner(sy, minY, maxY);
			double z = corner(sz, minZ, maxZ);
			double dx = sx == 0 ? arm : -arm;
			double dy = sy == 0 ? arm : -arm;
			double dz = sz == 0 ? arm : -arm;
			WorldGeometryRenderer.line(buffer, pose, x, y, z, color, x + dx, y, z, color, width, unitsPerPixel);
			WorldGeometryRenderer.line(buffer, pose, x, y, z, color, x, y + dy, z, color, width, unitsPerPixel);
			WorldGeometryRenderer.line(buffer, pose, x, y, z, color, x, y, z + dz, color, width, unitsPerPixel);
		}
	}

	/** Два кольца под ногами: расходятся и гаснут, цикл — {@value #POOL_PERIOD_MS} мс. */
	private static void drawPool(EspModule esp, EspModule.EspBox box, PoseStack.Pose pose,
	                             VertexConsumer buffer, double camX, double camY, double camZ,
	                             float unitsPerPixel, long now, double centerX, double centerZ) {
		double radius = Math.max(0.35, (box.maxX() - box.minX() + box.maxZ() - box.minZ()) * 0.55);
		double groundY = box.minY() - camY + 0.03;
		int color = baseColor(esp, box);
		for (int ringIndex = 0; ringIndex < 2; ringIndex++) {
			float phase = (float) (((now + box.entityId() * 211L) / (double) POOL_PERIOD_MS)
					+ ringIndex * 0.5F) % 1.0F;
			double grown = radius * (0.55 + 0.95 * phase);
			float alpha = (1.0F - phase) * 0.5F;
			if (alpha <= 0.02F) {
				continue;
			}
			WorldGeometryRenderer.ring(buffer, pose, centerX, groundY, centerZ, grown,
					Math.max(1.0F, 2.0F * (1.0F - phase)), 40,
					RenderUtils.withAlpha(color, alpha), unitsPerPixel);
		}
	}

	/** Основной цвет цели (радуга/обычный) + подкраска по здоровью. */
	private static int baseColor(EspModule esp, EspModule.EspBox box) {
		return esp.boxColorWithHealth(box, box.maxY());
	}

	/** Второй цвет цели — то, чем красятся ореол и уголки (+ здоровье). */
	private static int secondColor(EspModule esp, EspModule.EspBox box) {
		return esp.boxColorWithHealth(box, box.minY());
	}

	private static double corner(int flag, double min, double max) {
		return flag == 0 ? min : max;
	}
}

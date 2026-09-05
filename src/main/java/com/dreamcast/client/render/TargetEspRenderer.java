package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.TargetEspModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Отрисовка стилей Target ESP.
 *
 * <p>Рендер читает только снапшот {@link TargetEspModule.Frame}: ни сущностей, ни
 * мира, ни настроек он не трогает. Всё, что живёт между тиками, берётся как
 * линейная интерполяция пары prev/cur по {@code partialTick} — на 300 fps кольцо
 * и орбы движутся плавно, а не прыгают двадцать раз в секунду.</p>
 *
 * <p>Каждый стиль собирается из четырёх примитивов {@link WorldGeometryRenderer}:
 * мягкий диск, лента, треугольник и окружность на земле. Текстур нет намеренно:
 * glow-спрайт из референса даёт ровно то же самое, если нарисовать его тремя
 * концентрическими слоями с падающей альфой, зато не нужен ни PNG, ни свой
 * рендер-пайплайн.</p>
 */
public final class TargetEspRenderer {

	private static final float TAU = (float) (Math.PI * 2.0);
	private static final float QUARTER = (float) (Math.PI * 0.5);

	/** Подслоев мягкого диска: ореол, середина, ядро. */
	private static final int GLOW_LAYERS = 3;
	/** «Перьев» у юбки кольца. */
	private static final int SKIRT_SPOKES = 14;

	private TargetEspRenderer() {
	}

	public static void draw(TargetEspModule module, TargetEspModule.Frame frame,
	                         PoseStack.Pose pose, VertexConsumer buffer,
	                         double camX, double camY, double camZ,
                         float unitsPerPixel, long now, float partialTick) {
		float show = Mth.lerp(partialTick, frame.prevShow(), frame.show());
		if (show <= 0.01F) {
			return;
		}
		float size = Mth.lerp(partialTick, frame.prevSizeFactor(), frame.sizeFactor())
				* module.elementScale();
		if (size <= 0.01F) {
			return;
		}
		float alpha = Math.min(1.0F, show * module.opacityScale());
		float ringPhase = Mth.lerp(partialTick, frame.prevRingPhase(), frame.ringPhase());
		float spin = Mth.lerp(partialTick, frame.prevFrameSpin(), frame.frameSpin());
		float centerX = Mth.lerp(partialTick, frame.prevCenterX(), frame.centerX()) - (float) camX;
		float centerY = Mth.lerp(partialTick, frame.prevCenterY(), frame.centerY()) - (float) camY;
		float centerZ = Mth.lerp(partialTick, frame.prevCenterZ(), frame.centerZ()) - (float) camZ;

		switch (frame.style()) {
			case TargetEspModule.STYLE_CIRCLE -> drawCircle(module, pose, buffer, centerX, centerY,
					centerZ, alpha, size, frame, ringPhase, unitsPerPixel, now);
			case TargetEspModule.STYLE_MARKER -> drawMarker(module, pose, buffer, centerX, centerY,
					centerZ, alpha, size, frame, spin, unitsPerPixel, now);
			default -> drawElements(module, pose, buffer, camX, camY, camZ, centerX, centerY, centerZ,
					alpha, size, frame, unitsPerPixel, now, partialTick);
		}
	}

	// ------------------------------------------------------------------
	// Кольцо с «юбкой»
	// ------------------------------------------------------------------

	private static void drawCircle(TargetEspModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                              float x, float y, float z, float alpha, float size,
	                              TargetEspModule.Frame frame, float phase, float unitsPerPixel, long now) {
		float height = frame.height();
		float radius = Math.max(0.1F, (frame.width() + 0.2F) * 0.75F * size * 1.7F);
		// кольцо идёт вверх-вниз по цели, юбка тянется за ним
		float floating = (Mth.sin(phase) + 1.0F) * 0.5F * height;
		float ringY = y - height * 0.5F + floating;
		float skirt = -Mth.cos(phase) * 0.5F * size;
		if (floating + skirt < 0.0F) {
			skirt = -floating;
		}

		int color = module.effectColor(phase * 0.05F, now);
		WorldGeometryRenderer.ring(buffer, pose, x, ringY, z, radius, 2.4F, 48,
				RenderUtils.withAlpha(color, alpha * 0.95F), unitsPerPixel);
		WorldGeometryRenderer.ring(buffer, pose, x, ringY, z, radius * 1.35F, 6.0F, 36,
				RenderUtils.withAlpha(color, alpha * 0.18F), unitsPerPixel);
		if (skirt >= -0.02F) {
			return;
		}
		int clear = RenderUtils.withAlpha(color, 0.0F);
		for (int i = 0; i < SKIRT_SPOKES; i++) {
			float angle = i / (float) SKIRT_SPOKES * TAU;
			float px = x + Mth.cos(angle) * radius;
			float pz = z + Mth.sin(angle) * radius;
			WorldGeometryRenderer.line(buffer, pose,
					px, ringY, pz, RenderUtils.withAlpha(color, alpha * 0.75F),
					px, ringY + skirt, pz, clear, 1.4F, unitsPerPixel);
		}
	}

	// ------------------------------------------------------------------
	// Маркер: повёрнутая рамка в плоскости экрана
	// ------------------------------------------------------------------

	private static void drawMarker(TargetEspModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                              float x, float y, float z, float alpha, float size,
	                              TargetEspModule.Frame frame, float spinDegrees,
	                              float unitsPerPixel, long now) {
		float half = Math.max(frame.width() * 1.35F, frame.height() * 0.62F) * size;
		float[] axes = new float[6];
		WorldGeometryRenderer.screenAxes(x, y, z, axes);
		float rightX = axes[0], rightY = axes[1], rightZ = axes[2];
		float upX = axes[3], upY = axes[4], upZ = axes[5];

		// квадрат крутится вокруг оси взгляда: right/up поворачиваются вместе
		float rad = (float) Math.toRadians(spinDegrees * 4.0F);
		float cs = Mth.cos(rad), sn = Mth.sin(rad);
		float axX = rightX * cs + upX * sn, axY = rightY * cs + upY * sn, axZ = rightZ * cs + upZ * sn;
		float ayX = upX * cs - rightX * sn, ayY = upY * cs - rightY * sn, ayZ = upZ * cs - rightZ * sn;

		int color = module.effectColor(spinDegrees * 0.01F, now);
		int solid = RenderUtils.withAlpha(color, alpha);
		int halo = RenderUtils.withAlpha(color, alpha * 0.25F);

		float[] previous = null;
		for (int i = 0; i <= 4; i++) {
			float[] corner = CORNERS[i % 4];
			float vx = x + (axX * corner[0] + ayX * corner[1]) * half;
			float vy = y + (axY * corner[0] + ayY * corner[1]) * half;
			float vz = z + (axZ * corner[0] + ayZ * corner[1]) * half;
			if (previous != null) {
				WorldGeometryRenderer.line(buffer, pose, previous[0], previous[1], previous[2], halo,
						vx, vy, vz, halo, 5.5F, unitsPerPixel);
				WorldGeometryRenderer.line(buffer, pose, previous[0], previous[1], previous[2], solid,
						vx, vy, vz, solid, 1.6F, unitsPerPixel);
			}
			previous = new float[]{vx, vy, vz};
		}
		// четыре угловые точки: рамка читается и на светлом, и на тёмном фоне
		for (float[] corner : CORNERS) {
			WorldGeometryRenderer.glow(buffer, pose,
					x + (axX * corner[0] + ayX * corner[1]) * half,
					y + (axY * corner[0] + ayY * corner[1]) * half,
					z + (axZ * corner[0] + ayZ * corner[1]) * half,
					0.06F * size, color, GLOW_LAYERS);
		}
	}

	/** Углы рамки в осях «вправо/вверх» экрана (до поворота). */
	private static final float[][] CORNERS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

	// ------------------------------------------------------------------
	// Орбы / кометы / рой / кристаллы
	// ------------------------------------------------------------------

	private static void drawElements(TargetEspModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                                  double camX, double camY, double camZ,
	                                  float centerX, float centerY, float centerZ,
	                                  float alpha, float size, TargetEspModule.Frame frame,
	                                  float unitsPerPixel, long now, float partialTick) {
		List<TargetEspModule.Element> elements = frame.elements();
		List<TargetEspModule.Trail> trails = frame.trails();
		boolean crystals = frame.style().equals(TargetEspModule.STYLE_CRYSTALS);
		boolean swarm = frame.style().equals(TargetEspModule.STYLE_GHOSTS);

		for (int i = 0; i < elements.size(); i++) {
			TargetEspModule.Element e = elements.get(i);
			int color = module.effectColor(e.hue(), now);
			float elementSize = 0.13F * size * e.scale();
			// элемент живёт между тиками так же, как центр кольца: prev→cur по
			// partialTick, иначе орбы на 300 fps шли бы «ступеньками» по 20 Гц
			float ex = Mth.lerp(partialTick, e.prevX(), e.x()) - (float) camX;
			float ey = Mth.lerp(partialTick, e.prevY(), e.y()) - (float) camY;
			float ez = Mth.lerp(partialTick, e.prevZ(), e.z()) - (float) camZ;

			if (crystals) {
				drawCrystal(pose, buffer, ex, ey, ez, e, elementSize, color, alpha);
				WorldGeometryRenderer.glow(buffer, pose, ex, ey, ez, elementSize * 2.6F,
						RenderUtils.withAlpha(color, alpha * 0.45F), GLOW_LAYERS);
				continue;
			}

			if (swarm) {
				// рой: из каждого элемента растёт веер мелких точек (в референсе
				// это были 3 слоя по 12 «перьев», собранные одним проходом)
				for (int j = 0; j < 12; j++) {
					float phase = now * 0.005F + j * 0.1F + i * 0.7F;
					float spread = (0.8F + j * 0.012F) * size;
					float spin2 = phase + j * j * 0.02F;
					WorldGeometryRenderer.glow(buffer, pose,
							ex + Mth.cos(spin2) * spread,
							ey + 0.5F * size + Mth.sin(phase * 1.3F) * 0.3F + i * 0.2F,
							ez + Mth.sin(spin2 - j * 0.04F) * spread,
							(0.02F + j * 0.004F) * size * 6.0F,
							RenderUtils.withAlpha(module.effectColor(j / 12.0F, now), alpha * 0.7F),
							GLOW_LAYERS);
				}
				continue;
			}

			// орбы и кометы: ядро в два слоя света + хвост из затухающих дисков
			WorldGeometryRenderer.glow(buffer, pose, ex, ey, ez, elementSize * 3.4F,
					RenderUtils.withAlpha(color, alpha * 0.28F), GLOW_LAYERS);
			WorldGeometryRenderer.glow(buffer, pose, ex, ey, ez, elementSize * 1.9F,
					RenderUtils.withAlpha(color, alpha * 0.55F), GLOW_LAYERS);
			WorldGeometryRenderer.glow(buffer, pose, ex, ey, ez, elementSize, color, GLOW_LAYERS);

			if (i >= trails.size() || alpha < 0.05F) {
				continue;
			}
			List<float[]> points = trails.get(i).points();
			float lastX = ex, lastY = ey, lastZ = ez;
			for (int t = 0; t < points.size(); t++) {
				float fade = 1.0F - t / (float) Math.max(1, points.size());
				float px = (float) (points.get(t)[0] - camX);
				float py = (float) (points.get(t)[1] - camY);
				float pz = (float) (points.get(t)[2] - camZ);
				WorldGeometryRenderer.glow(buffer, pose, px, py, pz,
						Math.max(elementSize * 0.35F, elementSize * fade),
						RenderUtils.withAlpha(color, alpha * fade * 0.55F), GLOW_LAYERS);
				WorldGeometryRenderer.line(buffer, pose,
						lastX, lastY, lastZ, RenderUtils.withAlpha(color, alpha * fade * 0.7F),
						px, py, pz, RenderUtils.withAlpha(color, alpha * fade * 0.35F),
						Math.max(0.6F, 2.2F * fade), unitsPerPixel);
				lastX = px;
				lastY = py;
				lastZ = pz;
			}
		}
	}

	/** Кристалл = октаэдр: две пирамиды, повёрнутые по фазе элемента. */
	private static void drawCrystal(PoseStack.Pose pose, VertexConsumer buffer,
	                                 float x, float y, float z, TargetEspModule.Element element,
	                                 float size, int color, float alpha) {
		float w = size * 0.85F;
		float h = size * 1.9F;
		int solid = RenderUtils.withAlpha(color, alpha * 0.85F);
		int side = RenderUtils.withAlpha(color, alpha * 0.45F);

		float yawRad = (float) Math.toRadians(element.spinY());
		float cy = Mth.cos(yawRad), sy = Mth.sin(yawRad);
		float pitchRad = (float) Math.toRadians(element.spinZ() * 0.35F);
		float cp = Mth.cos(pitchRad), sp = Mth.sin(pitchRad);

		float[] px = new float[4];
		float[] py = new float[4];
		float[] pz = new float[4];
		for (int i = 0; i < 4; i++) {
			float angle = i * QUARTER;
			float rx = Mth.cos(angle) * w;
			float rz = Mth.sin(angle) * w;
			// поворот вокруг вертикали, затем наклон вокруг «вправо»
			float wx = rx * cy + rz * sy;
			float wz = -rx * sy + rz * cy;
			px[i] = x + wx;
			py[i] = y - wz * sp;
			pz[i] = z + wz * cp;
		}
		float topY = y + h * cp, topZ = z + h * sp;
		float botY = y - h * cp, botZ = z - h * sp;
		for (int i = 0; i < 4; i++) {
			int next = (i + 1) % 4;
			WorldGeometryRenderer.triangle(buffer, pose,
					x, topY, z, solid, px[i], py[i], pz[i], side, px[next], py[next], pz[next], side);
			WorldGeometryRenderer.triangle(buffer, pose,
					x, botY, z, solid, px[next], py[next], pz[next], side, px[i], py[i], pz[i], side);
		}
	}
}

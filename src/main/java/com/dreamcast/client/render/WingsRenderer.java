package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.WingsModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Геометрия крыльев: контур из 13 точек, залитый веером от «плеча» в три слоя
 * (ореол → ядро → тело) плюс обводка и рёбра-перегородки.
 *
 * <p>Почему веер, а не триангуляция: контур построен звезды («перья») вокруг
 * точки крепления, то есть каждый сектор контура видно из anchor без самоперекрытий — веер из anchor закрывает форму целиком, без расчёта нормалей и
 * без дыр на вогнутых участках.</p>
 *
 * <p>Оси считаются вручную, без поворотов {@code PoseStack}: наш world-рендер
 * пишет вершины в пространстве камеры, и матрица только спрятала бы, что
 * происходит. При двух углах на крыло (захлоп + подъём кончика) формулы короче,
 * чем борьба с композицией поворотов, и их можно читать глазами.</p>
 *
 * <p>Слои — это тот же приём, что у следа в {@code TrailsModule}: широкая тусклая
 * копия, яркая узкая копия и тело между ними. Так крыло выглядит объёмным без
 * текстур, без своего рендер-пайплайна и без аддитивного блендинга.</p>
 */
public final class WingsRenderer {

	/**
	 * Контур крыла в долях размаха: x — вдоль крыла (0 у корпуса, 1.24 — кончик),
	 * y — профиль (вверх «горб», вниз — перья), третий — множитель прозрачности
	 * точки: кончик растворяется, основание плотное.
	 */
	private static final float[][] SHAPE = {
			{0.08F, 0.10F, 0.88F},
			{0.28F, 0.34F, 0.78F},
			{0.56F, 0.82F, 0.62F},
			{0.86F, 0.30F, 0.52F},
			{1.14F, 0.46F, 0.40F},
			{1.24F, 0.04F, 0.30F},
			{1.02F, -0.18F, 0.28F},
			{1.18F, -0.64F, 0.22F},
			{0.86F, -0.46F, 0.20F},
			{0.80F, -0.98F, 0.14F},
			{0.54F, -0.74F, 0.16F},
			{0.30F, -1.16F, 0.12F},
			{0.10F, -0.54F, 0.18F},
	};

	/** Индексы точек, в которые идут «рёбра» — перегородки между перьями. */
	private static final int[] RIBS = {2, 4, 7, 9, 11};

	/** Блок мира на единицу «размаха» контура при scale = 1. */
	private static final float SPAN_BLOCKS = 1.05F;
	/** Блок мира на единицу профиля. */
	private static final float PROFILE_BLOCKS = 0.92F;

	private WingsRenderer() {
	}

	public static void draw(WingsModule module, List<WingsModule.Rig> rigs, PoseStack.Pose pose,
	                        VertexConsumer buffer, double camX, double camY, double camZ,
	                        float unitsPerPixel, long now) {
		for (int i = 0; i < rigs.size(); i++) {
			drawRig(module, rigs.get(i), i, pose, buffer, camX, camY, camZ, unitsPerPixel, now);
		}
	}

	private static void drawRig(WingsModule module, WingsModule.Rig rig, int index,
	                            PoseStack.Pose pose, VertexConsumer buffer,
	                            double camX, double camY, double camZ,
	                            float unitsPerPixel, long now) {
		WingsModule.WingPose w = WingsModule.poseFor(rig.pose());
		float flap = Mth.sin(rig.flapPhase()) * w.flapAmplitude();
		// На бегу крыло сильнее отводится назад и меньше поднимается — иначе
		// «ветер» выглядел бы как взмах на месте
		float sweep = (float) Math.toRadians(w.sweepBase() + rig.move() * w.sweepPerMove() - flap * 0.35F);
		float elev = (float) Math.toRadians(w.elevBase() + flap);

		float yaw = (float) Math.toRadians(rig.bodyYaw());
		float fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
		float rx = -fz, rz = fx;
		// группа наклоняется вместе со взглядом: нырок — крылья уходят «в гору»
		float pitch = (float) Math.toRadians(-rig.bodyPitch() * 0.55F);
		float cp = Mth.cos(pitch), sp = Mth.sin(pitch);
		// F' = F·cos + U·sin, U' = U·cos − F·sin (R не меняется: вращаем вокруг него)
		float fwdX = fx * cp, fwdY = sp, fwdZ = fz * cp;
		float upX = -fx * sp, upY = cp, upZ = -fz * sp;

		float scale = rig.size() * w.scale();
		float anchorX = rig.x() - fwdX * w.anchorBack();
		float anchorY = rig.y() + 0.86F + w.anchorUp();
		float anchorZ = rig.z() - fwdZ * w.anchorBack();
		float opacity = module.opacityScale();
		int baseColor = module.wingColor(index * 0.17F, now);

		for (int side = -1; side <= 1; side += 2) {
			drawWing(module, pose, buffer, side, w, sweep, elev,
					rx, rz, fwdX, fwdY, fwdZ, upX, upY, upZ,
					anchorX, anchorY, anchorZ, scale, opacity, baseColor,
					camX, camY, camZ, unitsPerPixel, now);
		}
	}

	private static void drawWing(WingsModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                             int side, WingsModule.WingPose w, float sweep, float elev,
	                             float rx, float rz,
	                             float fwdX, float fwdY, float fwdZ,
	                             float upX, float upY, float upZ,
	                             float anchorX, float anchorY, float anchorZ,
	                             float scale, float opacity, int baseColor,
	                             double camX, double camY, double camZ,
	                             float unitsPerPixel, long now) {
		float cs = Mth.cos(sweep), ss = Mth.sin(sweep);
		// направление к кончику: вбок (rx,rz)·cos и назад (−F)·sin
		float spanX = rx * cs * side - fwdX * ss;
		float spanY = -fwdY * ss;
		float spanZ = rz * cs * side - fwdZ * ss;
		float ce = Mth.cos(elev), se = Mth.sin(elev);
		// подъём кончика: span поворачиваем к up, профиль — перпендикуляр
		float tipX = spanX * ce + upX * se, tipY = spanY * ce + upY * se, tipZ = spanZ * ce + upZ * se;
		float profX = upX * ce - spanX * se, profY = upY * ce - spanY * se, profZ = upZ * ce - spanZ * se;

		float gapX = rx * side * w.sideGap(), gapZ = rz * side * w.sideGap();
		float rootX = anchorX + gapX, rootY = anchorY, rootZ = anchorZ + gapZ;

		// вершины контура для трёх слоёв считаем один раз на слой: их всего 13
		float[] pts = new float[SHAPE.length * 3];
		float[] alphas = new float[SHAPE.length];
		for (int layer = 0; layer < 3; layer++) {
			float layerScale = switch (layer) {
				case 0 -> scale * 1.22F;   // ореол: шире и тусклее
				case 1 -> scale * 0.84F;   // ядро: плотное, выбеленное
				default -> scale;          // тело
			};
			int color = switch (layer) {
				case 0 -> WingsModule.lighten(baseColor, 0.28F);
				case 1 -> WingsModule.lighten(baseColor, 0.55F);
				default -> baseColor;
			};
			int alpha = switch (layer) {
				case 0 -> (int) (48 * opacity);
				case 1 -> (int) (57 * opacity);
				default -> (int) (220 * opacity);
			};
			if (alpha <= 1) {
				continue;
			}
			for (int i = 0; i < SHAPE.length; i++) {
				float[] p = SHAPE[i];
				pts[i * 3] = rootX + (tipX * p[0] + profX * p[1]) * layerScale * SPAN_BLOCKS;
				pts[i * 3 + 1] = rootY + (tipY * p[0] + profY * p[1]) * layerScale * PROFILE_BLOCKS
						+ (layer == 0 ? 0.02F : 0.0F);
				pts[i * 3 + 2] = rootZ + (tipZ * p[0] + profZ * p[1]) * layerScale * SPAN_BLOCKS;
				alphas[i] = p[2];
			}
			for (int i = 0; i < SHAPE.length; i++) {
				int next = (i + 1) % SHAPE.length;
				// прозрачность края растёт от кончика к основанию — крыло
				// «растворяется» на перьях, а не обрывается линией
				float aFrom = layer == 2 ? alpha / 255.0F * lerpAlpha(alphas[i]) : alpha / 255.0F * 0.85F;
				float aTo = layer == 2 ? alpha / 255.0F * lerpAlpha(alphas[next]) : alpha / 255.0F * 0.85F;
				WorldGeometryRenderer.triangle(buffer, pose,
						pts[i * 3] - camX, pts[i * 3 + 1] - camY, pts[i * 3 + 2] - camZ,
						 RenderUtils.withAlpha(color, aFrom),
						pts[next * 3] - camX, pts[next * 3 + 1] - camY, pts[next * 3 + 2] - camZ,
						RenderUtils.withAlpha(color, aTo),
						rootX - camX, rootY - camY, rootZ - camZ,
						RenderUtils.withAlpha(color, Math.min(1.0F, aFrom * 1.35F)));
			}
			if (layer == 2 && module.drawsOutline()) {
				int edge = RenderUtils.withAlpha(color, 0.62F * opacity);
				for (int i = 0; i < SHAPE.length; i++) {
					int next = (i + 1) % SHAPE.length;
					WorldGeometryRenderer.line(buffer, pose,
							pts[i * 3] - camX, pts[i * 3 + 1] - camY, pts[i * 3 + 2] - camZ, edge,
							pts[next * 3] - camX, pts[next * 3 + 1] - camY, pts[next * 3 + 2] - camZ, edge,
							1.2F, unitsPerPixel);
				}
				for (int rib : RIBS) {
					WorldGeometryRenderer.line(buffer, pose,
							rootX - camX, rootY - camY, rootZ - camZ, edge,
							pts[rib * 3] - camX, pts[rib * 3 + 1] - camY, pts[rib * 3 + 2] - camZ, edge,
							0.8F, unitsPerPixel);
				}
			}
		}
	}

	/** Край контура прозрачнее основания: подтягиваем множитель к единице. */
	private static float lerpAlpha(float pointAlpha) {
		return 0.35F + pointAlpha * 0.65F;
	}
}

package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.ChinaHatModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Геометрия «китайской шляпы»: конус из {@code segments} граней, основание —
 * на уровне макушки, вершина — над ней.
 *
 * <p>Позиции уже приведены к камере (модуль отдаёт мировые координаты основания,
 * рендер один раз вычитает камеру), а поворот шляпы считается вручную через
 * {@code cos/sin} — матрицы поворота тут только запутали бы: граней много, они
 * пересобираются каждый кадр.</p>
 *
 * <p>Светотень: для каждой грани берём |dot(нормаль, направление на камеру)|.
 * Грани, повёрнутые к нам ребром, затемняются — именно это и даёт ощущение
 * объёма вместо плоского «веера». Нормаль при этом не используем как освещение
 * сцены, только как множитель яркости, чтобы конус читался на любом фоне.</p>
 */
public final class ChinaHatRenderer {

	/** Полностью чёрный — с ним смешиваем затемнённые грани. */
	private static final int BLACK = 0xFF000000;
	/** Полный оборот в радианах (в Mth 26.2 своей константы нет). */
	private static final float TAU = (float) (Math.PI * 2.0);

	private ChinaHatRenderer() {
	}

	public static void draw(ChinaHatModule module, List<ChinaHatModule.Hat> hats,
	                        PoseStack.Pose pose, VertexConsumer buffer,
	                        double camX, double camY, double camZ,
	                        float unitsPerPixel, long now) {
		float height = module.heightBlocks();
		float radius = module.radiusBlocks();
		float yOffset = module.yOffsetBlocks();
		float spin = module.spinDegrees(now);
		int segments = module.segmentCount();
		float alpha = module.opacityValue() / 255.0F;
		boolean shade = module.shadesFaces();
		boolean rim = module.drawsRim();

		for (ChinaHatModule.Hat hat : hats) {
			// основание конуса: макушка + ручная поправка настройки
			float x = hat.x() - (float) camX;
			float y = hat.y() - (float) camY + yOffset;
			float z = hat.z() - (float) camZ;
			float base = (float) Math.toRadians(hat.yaw() + 90.0F + spin);
			float apexY = y + height;

			for (int i = 0; i < segments; i++) {
				float a0 = base + i / (float) segments * TAU;
				float a1 = base + (i + 1) / (float) segments * TAU;
				float x0 = x + Mth.cos(a0) * radius;
				float z0 = z + Mth.sin(a0) * radius;
				float x1 = x + Mth.cos(a1) * radius;
				float z1 = z + Mth.sin(a1) * radius;

				int color = module.sectorColor(i / (float) segments, now);
				if (shade) {
					color = RenderUtils.mix(color, BLACK, 1.0F - faceLight(x, y, z, x0, y, z0, x1, y, z1));
				}
				int side = RenderUtils.withAlpha(color, alpha);
				// вершине даём чуть больше плотности: кончик читается как грань,
				// а не как точка, где все сектора схлопнулись
				int tip = RenderUtils.withAlpha(color, Math.min(1.0F, alpha * 1.15F));
				WorldGeometryRenderer.triangle(buffer, pose,
						x0, y, z0, side,
						x1, y, z1, side,
						x, apexY, z, tip);
			}

			if (rim) {
				int edge = RenderUtils.withAlpha(module.sectorColor(0.5F, now), alpha * 0.85F);
				WorldGeometryRenderer.ring(buffer, pose, x, y, z, radius, 1.8F, Math.max(16, segments),
						edge, unitsPerPixel);
				// маленькое «колечко» на макушке: без него вершина — просто точка,
				// и на расстоянии конус выглядит обрезанным
				WorldGeometryRenderer.ring(buffer, pose, x, apexY, z, radius * 0.16F, 1.4F, 12,
						RenderUtils.withAlpha(module.sectorColor(0.0F, now), alpha), unitsPerPixel);
			}
		}
	}

	/**
	 * Освещённость грани: 1 — смотрим ровно в плоскость, 0 — грань повёрнута
	 * ребром. Считаем по нормали треугольника и направлению на камеру (она у нас
	 * в начале координат, поэтому «на камеру» = минус центр грани).
	 */
	private static float faceLight(float ax, float ay, float az,
	                               float bx, float by, float bz,
	                               float cx, float cy, float cz) {
		float ux = bx - ax, uy = by - ay, uz = bz - az;
		float vx = cx - ax, vy = cy - ay, vz = cz - az;
		float nx = uy * vz - uz * vy;
		float ny = uz * vx - ux * vz;
		float nz = ux * vy - uy * vx;
		float nlen = Mth.sqrt(nx * nx + ny * ny + nz * nz);
		if (nlen < 1.0e-4F) {
			return 1.0F;
		}
		float mx = (ax + bx + cx) / 3.0F, my = (ay + by + cy) / 3.0F, mz = (az + bz + cz) / 3.0F;
		float mlen = Mth.sqrt(mx * mx + my * my + mz * mz);
		if (mlen < 1.0e-4F) {
			return 1.0F;
		}
		float dot = (nx * -mx + ny * -my + nz * -mz) / (nlen * mlen);
		return 0.35F + 0.65F * Mth.abs(dot);
	}
}

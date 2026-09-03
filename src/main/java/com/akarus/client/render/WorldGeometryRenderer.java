package com.akarus.client.render;

import com.akarus.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/**
 * Рисование «линий» и «лент» в мире: светящиеся полосы, повёрнутые к камере.
 *
 * В 26.2 у рендера нет линии как примитива «из коробки» для наших задач, поэтому
 * каждый отрезок — это два треугольника (квад), повернутый к камеру ребром:
 * перпендикуляр считается через векторное произведение направления отрезка и
 * направления взгляда. Ширина задаётся в <b>пикселях интерфейса</b> и переводится
 * в мировые единицы с учётом расстояния до камеры — линия одинакова на экране
 * и вблизи, и вдали, как в классических клиентах.
 *
 * Пайплайн — emissive-translucent на белой текстуре 1×1 (как у обводки руки):
 * цвет идёт из вершин «как есть», поэтому свечение ровно того оттенка, который
 * выбран в меню, а мягкость даёт альфа-градиент по полосам.
 */
public final class WorldGeometryRenderer {

	private static final int FULL_LIGHT = 0xF000F0;
	private static final float HUD_TAN_HALF_FOV = 0.70021F;

	private static volatile RenderType cachedType;

	private WorldGeometryRenderer() {
	}

	/** Тип рендера: emissive-полупрозрачный на белой текстуре. */
	public static RenderType type() {
		RenderType type = cachedType;
		if (type == null) {
			type = HandOutlineRenderer.outlineType();
			cachedType = type;
		}
		return type;
	}

	/** Одна светящаяся линия от (x0,y0,z0) до (x1,y1,z1), уже в координатах камеры. */
	public static void line(VertexConsumer buffer, PoseStack.Pose pose,
	                        double x0, double y0, double z0, int color0,
	                        double x1, double y1, double z1, int color1,
	                        double widthPx, double unitsPerPixel) {
		quad(buffer, pose,
				x0, y0, z0, color0, color0,
				x1, y1, z1, color1, color1,
				widthPx, unitsPerPixel);
	}

	/**
	 * Квад «на ребро» между двумя точками с раздельным цветом левого/правого края.
	 * Используется лентой следа: цвет меняется поперёк (градиент/радуга).
	 */
	public static void quad(VertexConsumer buffer, PoseStack.Pose pose,
	                        double x0, double y0, double z0, int left0, int right0,
	                        double x1, double y1, double z1, int left1, int right1,
	                        double widthPx, double unitsPerPixel) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double dz = z1 - z0;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1.0e-4) {
			return;
		}
		dx /= length;
		dy /= length;
		dz /= length;

		// Камера в этом пространстве — в нуле: вектор на камеру = -позиция точки
		double viewLen0 = Math.sqrt(x0 * x0 + y0 * y0 + z0 * z0);
		double viewLen1 = Math.sqrt(x1 * x1 + y1 * y1 + z1 * z1);
		if (viewLen0 < 1.0e-4 || viewLen1 < 1.0e-4) {
			return;
		}

		// Перпендикуляры «отрезок × взгляд» — нормаль бильборда у каждого конца
		double sx0 = dy * (-z0 / viewLen0) - dz * (-y0 / viewLen0);
		double sy0 = dz * (-x0 / viewLen0) - dx * (-z0 / viewLen0);
		double sz0 = dx * (-y0 / viewLen0) - dy * (-x0 / viewLen0);
		double sx1 = dy * (-z1 / viewLen1) - dz * (-y1 / viewLen1);
		double sy1 = dz * (-x1 / viewLen1) - dx * (-z1 / viewLen1);
		double sz1 = dx * (-y1 / viewLen1) - dy * (-x1 / viewLen1);

		double sideLen0 = Math.sqrt(sx0 * sx0 + sy0 * sy0 + sz0 * sz0);
		double sideLen1 = Math.sqrt(sx1 * sx1 + sy1 * sy1 + sz1 * sz1);
		if (sideLen0 < 1.0e-4 || sideLen1 < 1.0e-4) {
			return;
		}

		double w0 = Math.max(0.002, widthPx * unitsPerPixel * viewLen0) / sideLen0;
		double w1 = Math.max(0.002, widthPx * unitsPerPixel * viewLen1) / sideLen1;
		double ax0 = x0 - sx0 * w0;
		double ay0 = y0 - sy0 * w0;
		double az0 = z0 - sz0 * w0;
		double bx0 = x0 + sx0 * w0;
		double by0 = y0 + sy0 * w0;
		double bz0 = z0 + sz0 * w0;
		double ax1 = x1 - sx1 * w1;
		double ay1 = y1 - sy1 * w1;
		double az1 = z1 - sz1 * w1;
		double bx1 = x1 + sx1 * w1;
		double by1 = y1 + sy1 * w1;
		double bz1 = z1 + sz1 * w1;

		float nx0 = (float) (sx0 / sideLen0);
		float ny0 = (float) (sy0 / sideLen0);
		float nz0 = (float) (sz0 / sideLen0);
		float nx1 = (float) (sx1 / sideLen1);
		float ny1 = (float) (sy1 / sideLen1);
		float nz1 = (float) (sz1 / sideLen1);

		// Два треугольника: a0→b0→b1 и a0→b1→a1
		vertex(buffer, pose, ax0, ay0, az0, left0, nx0, ny0, nz0);
		vertex(buffer, pose, bx0, by0, bz0, right0, nx0, ny0, nz0);
		vertex(buffer, pose, bx1, by1, bz1, right1, nx1, ny1, nz1);

		vertex(buffer, pose, ax0, ay0, az0, left0, nx0, ny0, nz0);
		vertex(buffer, pose, bx1, by1, bz1, right1, nx1, ny1, nz1);
		vertex(buffer, pose, ax1, ay1, az1, left1, nx1, ny1, nz1);
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, double x, double y, double z,
	                           int color, float nx, float ny, float nz) {
		buffer.addVertex(pose, (float) x, (float) y, (float) z)
				.setColor(color)
				.setUv(0.0F, 0.0F)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_LIGHT)
				.setNormal(pose, nx, ny, nz);
	}

	/** tan(fov/2) из матрицы проекции кадра; для вырожденной матрицы — 70°. */
	public static float tanHalfFov(org.joml.Matrix4fc projection) {
		float m11 = projection.m11();
		if (m11 > 0.01F && m11 < 100.0F) {
			return 1.0F / m11;
		}
		return HUD_TAN_HALF_FOV;
	}

	/** Сколько мировых единиц в одном пикселе GUI на расстоянии единица от камеры. */
	public static float unitsPerPixel(float tanHalfFov, int guiScaledHeight) {
		if (guiScaledHeight <= 0) {
			return 0.01F;
		}
		return 2.0F * tanHalfFov / guiScaledHeight;
	}

	/** Смешивание цветов для градиента по вертикали бокса. */
	public static int verticalColor(int top, int bottom, double y, double minY, double maxY) {
		double span = maxY - minY;
		float t = span <= 1.0e-4 ? 0.0f : (float) ((y - minY) / span);
		return RenderUtils.mix(top, bottom, t);
	}
}

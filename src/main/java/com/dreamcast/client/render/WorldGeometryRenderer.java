package com.dreamcast.client.render;

import com.dreamcast.client.util.RenderUtils;
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

	// ------------------------------------------------------------------
	// Полигоны и биллборды (шляпа, крылья, кристаллы, «свечение»)
	// ------------------------------------------------------------------

	/**
	 * Один залитый треугольник в том же пространстве, что и {@link #quad}:
	 * координаты уже относительно камеры, нормаль считается сама.
	 *
	 * Из треугольников собирается всё «объёмное»: конус шляпы (веер от вершины к
	 * окружности основания), полигоны крыла (веер от anchor к контуру), грани
	 * кристалла (две пирамиды).
	 */
	public static void triangle(VertexConsumer buffer, PoseStack.Pose pose,
	                           double ax, double ay, double az, int colorA,
	                           double bx, double by, double bz, int colorB,
	                           double cx, double cy, double cz, int colorC) {
		// Нормаль — по векторному произведению рёбер, развёрнутая «на камеру»
		// (камера у нас в нуле, поэтому «на камеру» = против вектора к точке)
		double ux = bx - ax, uy = by - ay, uz = bz - az;
		double vx = cx - ax, vy = cy - ay, vz = cz - az;
		double nx = uy * vz - uz * vy;
		double ny = uz * vx - ux * vz;
		double nz = ux * vy - uy * vx;
		double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1.0e-6) {
			return;
		}
		nx /= len;
		ny /= len;
		nz /= len;
		double gx = (ax + bx + cx) / 3.0, gy = (ay + by + cy) / 3.0, gz = (az + bz + cz) / 3.0;
		// Вырожденный треугольник всё равно нарисуется (cull у нас выключен), но
		// нормаль, обращённая к камере, держит стабильный оттенок на гранях
		if (nx * gx + ny * gy + nz * gz > 0.0) {
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		vertex(buffer, pose, ax, ay, az, colorA, (float) nx, (float) ny, (float) nz);
		vertex(buffer, pose, bx, by, bz, colorB, (float) nx, (float) ny, (float) nz);
		vertex(buffer, pose, cx, cy, cz, colorC, (float) nx, (float) ny, (float) nz);
	}

	/**
	 * Квад, всегда повёрнутый лицом к камере (бильборд), вокруг точки (x,y,z).
	 *
	 * Оси считаются из направления «камера → точка»: right = view × up,
	 * up' = right × view. При взгляде строго вверх/вниз up вырождается — там
	 * берём мировую ось Z, иначе у «свечения» начал бы заваливаться размер.
	 */
	public static void billboard(VertexConsumer buffer, PoseStack.Pose pose,
	                             double x, double y, double z, double halfSize, int color) {
		double dist = Math.sqrt(x * x + y * y + z * z);
		if (dist < 1.0e-4 || halfSize <= 0.0) {
			return;
		}
		double vx = x / dist, vy = y / dist, vz = z / dist;
		// right = normalize(view × worldUp) = normalize(-vz, 0, vx)
		double rx = -vz;
		double ry = 0.0;
		double rz = vx;
		double rlen = Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (rlen < 1.0e-4) {
			rx = 0.0;
			ry = 0.0;
			rz = -1.0;
			rlen = 1.0;
		}
		rx /= rlen;
		ry /= rlen;
		rz /= rlen;
		// up = normalize(right × view)
		double ux = ry * vz - rz * vy;
		double uy = rz * vx - rx * vz;
		double uz = rx * vy - ry * vx;
		double ulen = Math.sqrt(ux * ux + uy * uy + uz * uz);
		if (ulen < 1.0e-4) {
			return;
		}
		ux /= ulen;
		uy /= ulen;
		uz /= ulen;

		double h = halfSize;
		// a = -r-h, -u-h ; b = +r-h... обход против часовой, два треугольника
		double axX = x - rx * h - ux * h, axY = y - ry * h - uy * h, axZ = z - rz * h - uz * h;
		double bxX = x + rx * h - ux * h, bxY = y + ry * h - uy * h, bxZ = z + rz * h - uz * h;
		double cxX = x + rx * h + ux * h, cxY = y + ry * h + uy * h, cxZ = z + rz * h + uz * h;
		double dxX = x - rx * h + ux * h, dxY = y - ry * h + uy * h, dxZ = z - rz * h + uz * h;
		float fnx = (float) -vx, fny = (float) -vy, fnz = (float) -vz;
		vertex(buffer, pose, axX, axY, axZ, color, fnx, fny, fnz);
		vertex(buffer, pose, bxX, bxY, bxZ, color, fnx, fny, fnz);
		vertex(buffer, pose, cxX, cxY, cxZ, color, fnx, fny, fnz);
		vertex(buffer, pose, axX, axY, axZ, color, fnx, fny, fnz);
		vertex(buffer, pose, cxX, cxY, cxZ, color, fnx, fny, fnz);
		vertex(buffer, pose, dxX, dxY, dxZ, color, fnx, fny, fnz);
	}

	/**
	 * Мягкое «свечение» без текстуры: несколько концентрических бильбордов,
	 * каждый шире и прозрачнее предыдущего. Так выглядит glow-спрайт, если
	 * рисовать его процедурно — и не требует ни PNG, ни своего пайплайна.
	 *
	 * @param layers количество слоёв (3 — как в референсе, где ядро/среда/ореол)
	 */
	public static void glow(VertexConsumer buffer, PoseStack.Pose pose,
	                        double x, double y, double z, double radius, int color, int layers) {
		if (radius <= 0.002 || layers <= 0) {
			return;
		}
		int n = Math.min(6, Math.max(1, layers));
		int alpha = color >>> 24;
		for (int i = n - 1; i >= 0; i--) {
			// i = n-1 — самый широкий и тусклый слой, i = 0 — яркое ядро
			float t = n == 1 ? 1.0F : i / (float) (n - 1);
			double size = radius * (1.0 + t * 2.4);
			float fade = 1.0F - t * 0.86F;
			int a = (int) (alpha * fade * fade);
			if (a <= 1) {
				continue;
			}
			billboard(buffer, pose, x, y, z, size, RenderUtils.withAlpha(color, a / 255.0F));
		}
	}

	/** Окружность в горизонтальной плоскости (кольцо под целью, «пятак» шляпы). */
	public static void ring(VertexConsumer buffer, PoseStack.Pose pose,
	                        double x, double y, double z, double radius,
	                        double widthPx, int segments, int color, float unitsPerPixel) {
		if (radius < 0.02 || segments < 3) {
			return;
		}
		int n = Math.min(128, segments);
		for (int i = 0; i < n; i++) {
			double a0 = i / (double) n * Math.PI * 2.0;
			double a1 = (i + 1) / (double) n * Math.PI * 2.0;
			line(buffer, pose,
					x + Math.cos(a0) * radius, y, z + Math.sin(a0) * radius, color,
					x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius, color,
					widthPx, unitsPerPixel);
		}
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

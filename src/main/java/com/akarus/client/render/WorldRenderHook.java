package com.akarus.client.render;

import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.EspModule;
import com.akarus.client.module.impl.TrailsModule;
import com.akarus.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Мост между модулями и новым world-рендером 26.2.
 *
 * Fabric API в 26.2 заменяет старые WorldRenderEvents парой событий:
 * <ul>
 *   <li>{@code LevelExtractionEvents.END_EXTRACTION} — извлечение данных кадра
 *       (поток игры): здесь безопасно читать мир — собираем боксы ESP;</li>
 *   <li>{@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} — сбор геометрии
 *       кадра: здесь рисуем ленту следа и боксы, уже по данным из рендер-стейта.</li>
 * </ul>
 *
 * Правило потоков простое: всё, что читает мир, — на извлечении; рендер получает
 * готовые неизменяемые данные (или читает только собственный буфер модуля).
 */
public final class WorldRenderHook {

	/** Боксы ESP, собранные на извлечении; атомарно меняются целиком. */
	private static volatile List<EspModule.EspBox> espBoxes = List.of();

	private WorldRenderHook() {
	}

	public static void register() {
		LevelExtractionEvents.END_EXTRACTION.register(WorldRenderHook::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(WorldRenderHook::render);
	}

	private static void extract(LevelExtractionContext context) {
		try {
			EspModule esp = ModuleManager.find(EspModule.class);
			if (esp == null || !esp.wantsBoxes()) {
				espBoxes = List.of();
				targetBar = null;
				return;
			}
			var camera = context.camera().position();
			espBoxes = List.copyOf(esp.collectBoxes(
					context.level().entitiesForRendering(),
					camera.x, camera.y, camera.z));

			// TargetESP: полоска здоровья над целью
			net.minecraft.world.entity.Entity target = esp.targetForRender();
			if (target != null) {
				var box = target.getBoundingBox();
				targetBar = new TargetBar(
						new EspModule.EspBox(
								(float) box.minX, (float) box.minY, (float) box.minZ,
								(float) box.maxX, (float) box.maxY, (float) box.maxZ,
								target.getId()),
						EspModule.healthFraction(target));
			} else {
				targetBar = null;
			}
		} catch (Exception error) {
			espBoxes = List.of();
			targetBar = null;
		}
	}

	/** Полоска HP цели TargetESP: бокс + доля здоровья. */
	private record TargetBar(EspModule.EspBox box, float health) {
	}

	private static TargetBar targetBar;

	private static void render(LevelRenderContext context) {
		try {
			TrailsModule trails = ModuleManager.find(TrailsModule.class);
			List<EspModule.EspBox> boxes = espBoxes;
			boolean hasTrail = trails != null && trails.wantsLine();
			if (!hasTrail && boxes.isEmpty()) {
				return;
			}

			var camera = context.levelState().cameraRenderState;
			double camX = camera.pos.x;
			double camY = camera.pos.y;
			double camZ = camera.pos.z;

			Minecraft client = Minecraft.getInstance();
			int guiHeight = client == null || client.getWindow() == null ? 0 : client.getWindow().getGuiScaledHeight();
			float unitsPerPixel = WorldGeometryRenderer.unitsPerPixel(
					WorldGeometryRenderer.tanHalfFov(camera.projectionMatrix), guiHeight);

			float partialTick = client != null
					? client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
					: 1.0F;
			long now = Util.getMillis();

			context.submitNodeCollector().submitCustomGeometry(
					context.poseStack(),
					WorldGeometryRenderer.type(),
					(pose, buffer) -> {
						if (hasTrail) {
							drawTrail(trails, pose, buffer, camX, camY, camZ, unitsPerPixel, partialTick, now);
						}
						if (!boxes.isEmpty()) {
							drawBoxes(boxes, pose, buffer, camX, camY, camZ, unitsPerPixel);
						}
						if (targetBar != null) {
							drawTargetBar(targetBar, pose, buffer, camX, camY, camZ, unitsPerPixel);
						}
					});
		} catch (Exception ignored) {
			// Рендер не должен падать: любая ошибка в наших линиях — просто пропуск кадра
		}
	}

	// ------------------------------------------------------------------
	// Лента следа
	// ------------------------------------------------------------------

	private static void drawTrail(TrailsModule trails, PoseStack.Pose pose, VertexConsumer buffer,
	                              double camX, double camY, double camZ, float unitsPerPixel,
	                              float partialTick, long now) {
		var points = trails.trailPoints().toArray(new float[0][]);
		if (points.length < 1) {
			return;
		}
		float[] head = trails.headPoint(partialTick);
		if (head == null) {
			return;
		}

		// points[0] — самая старая точка (голова очереди), последний элемент — свежая.
		// Рисуем от головы (у игрока) к хвосту: t = 0 у игрока, 1 в хвосте.
		int segments = points.length; // head→newest + внутренние
		if (segments == 0) {
			return;
		}

		int width = trails.lineWidth();
		// Два прохода: широкое мягкое свечение, затем плотная сердцевина
		drawTrailPass(trails, pose, buffer, points, head, camX, camY, camZ,
				unitsPerPixel, width * 2.8F, 0.22F, now);
		drawTrailPass(trails, pose, buffer, points, head, camX, camY, camZ,
				unitsPerPixel, width, 0.85F, now);
	}

	private static void drawTrailPass(TrailsModule trails, PoseStack.Pose pose, VertexConsumer buffer,
	                                  float[][] points, float[] head,
	                                  double camX, double camY, double camZ,
	                                  float unitsPerPixel, float widthPx, float alphaScale,
	                                  long now) {
		// Первый сегмент: от интерполированной позиции игрока к свежей точке
		float[] from = head;
		for (int i = points.length - 1; i >= 0; i--) {
			float[] to = points[i];
			float t = (points.length - 1 - i + 1) / (float) (points.length + 1);
			float tNext = (points.length - 1 - i) / (float) (points.length + 1);

			int colorFrom = RenderUtils.withAlpha(trails.trailColor(tNext, now), alphaScale);
			int colorTo = RenderUtils.withAlpha(trails.trailColor(t, now), alphaScale * (1.0f - t));

			WorldGeometryRenderer.line(buffer, pose,
					from[0] - camX, from[1] - camY, from[2] - camZ, colorFrom,
					to[0] - camX, to[1] - camY, to[2] - camZ, colorTo,
					widthPx, unitsPerPixel);
			from = to;
		}
	}

	// ------------------------------------------------------------------
	// Боксы ESP
	// ------------------------------------------------------------------

	private static final int[][] BOX_EDGES = {
			// нижнее кольцо
			{0, 0, 0, 1, 0, 0}, {1, 0, 0, 1, 0, 1}, {1, 0, 1, 0, 0, 1}, {0, 0, 1, 0, 0, 0},
			// верхнее кольцо
			{0, 1, 0, 1, 1, 0}, {1, 1, 0, 1, 1, 1}, {1, 1, 1, 0, 1, 1}, {0, 1, 1, 0, 1, 0},
			// стойки
			{0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {1, 0, 1, 1, 1, 1}, {0, 0, 1, 0, 1, 1},
	};

	private static void drawBoxes(List<EspModule.EspBox> boxes, PoseStack.Pose pose, VertexConsumer buffer,
	                              double camX, double camY, double camZ, float unitsPerPixel) {
		EspModule esp = ModuleManager.find(EspModule.class);
		if (esp == null) {
			return;
		}

		float width = esp.boxWidth();
		boolean corners = esp.cornersOnly();

		for (EspModule.EspBox box : boxes) {
			double minX = box.minX() - camX;
			double minY = box.minY() - camY;
			double minZ = box.minZ() - camZ;
			double maxX = box.maxX() - camX;
			double maxY = box.maxY() - camY;
			double maxZ = box.maxZ() - camZ;

			// Цвет по высоте: у градиента верх — основной цвет, низ — второй
			int colorBottom = esp.boxColor(box.entityId(), minY, minY, maxY);
			int colorTop = esp.boxColor(box.entityId(), maxY, minY, maxY);
			int alpha = 0xE6;

			if (corners) {
				drawCornerBrackets(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ,
						withAlpha(colorTop, alpha), withAlpha(colorBottom, alpha), width, unitsPerPixel);
			} else {
				for (int[] edge : BOX_EDGES) {
					double x0 = edge[0] == 0 ? minX : maxX;
					double y0 = edge[1] == 0 ? minY : maxY;
					double z0 = edge[2] == 0 ? minZ : maxZ;
					double x1 = edge[3] == 0 ? minX : maxX;
					double y1 = edge[4] == 0 ? minY : maxY;
					double z1 = edge[5] == 0 ? minZ : maxZ;

					int c0 = withAlpha(edge[1] == 0 ? colorBottom : colorTop, alpha);
					int c1 = withAlpha(edge[4] == 0 ? colorBottom : colorTop, alpha);
					WorldGeometryRenderer.line(buffer, pose, x0, y0, z0, c0, x1, y1, z1, c1, width, unitsPerPixel);
				}
			}
		}
	}

	/**
	 * Полоска здоровья цели TargetESP: «биллборд» из двух линий над боксом,
	 * поворачивается перпендикулярно взгляду — читается с любой стороны.
	 */
	private static void drawTargetBar(TargetBar bar, PoseStack.Pose pose, VertexConsumer buffer,
			double camX, double camY, double camZ, float unitsPerPixel) {
		EspModule.EspBox box = bar.box();
		double centerX = (box.minX() + box.maxX()) / 2.0 - camX;
		double centerY = box.maxY() - camY + 0.42;
		double centerZ = (box.minZ() + box.maxZ()) / 2.0 - camZ;

		// Перпендикуляр к направлению «камера → цель» в горизонтальной плоскости
		double vx = camX - (box.minX() + box.maxX()) / 2.0;
		double vz = camZ - (box.minZ() + box.maxZ()) / 2.0;
		double length = Math.sqrt(vx * vx + vz * vz);
		if (length < 1.0e-4) {
			vx = 1.0;
			vz = 0.0;
		} else {
			double px = -vz / length;
			double pz = vx / length;
			vx = px;
			vz = pz;
		}

		float halfWidth = 36.0F * unitsPerPixel * 0.5F;
		double ax = centerX - vx * halfWidth;
		double az = centerZ - vz * halfWidth;
		double bx = centerX + vx * halfWidth;
		double bz = centerZ + vz * halfWidth;

		// Фон полоски
		WorldGeometryRenderer.line(buffer, pose, ax, centerY, az, 0xE6101014, bx, centerY, bz, 0xE6101014,
				3.5F, unitsPerPixel);
		// Здоровье: зелёный при полном, красный в опасности
		float health = Math.max(0.0f, Math.min(1.0f, bar.health()));
		int healthColor = health > 0.5f
				? RenderUtils.mix(0xFFFF5C5C, 0xFF7BE08A, (health - 0.5f) * 2.0f)
				: 0xFFFF5C5C;
		double hx = ax + (bx - ax) * health;
		double hz = az + (bz - az) * health;
		if (health > 0.01f) {
			WorldGeometryRenderer.line(buffer, pose, ax, centerY, az, healthColor, hx, centerY, hz, healthColor,
					3.5F, unitsPerPixel);
		}
	}

	private static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (alpha << 24);
	}

	/** Режим «только углы»: короткие скобки в восьми углах бокса. */
	private static void drawCornerBrackets(PoseStack.Pose pose, VertexConsumer buffer,
	                                       double minX, double minY, double minZ,
	                                       double maxX, double maxY, double maxZ,
	                                       int colorTop, int colorBottom,
	                                       float width, float unitsPerPixel) {
		double sizeX = Math.max(0.18F, (maxX - minX) * 0.3);
		double sizeY = Math.max(0.18F, (maxY - minY) * 0.3);
		double sizeZ = Math.max(0.18F, (maxZ - minZ) * 0.3);

		for (int corner = 0; corner < 8; corner++) {
			double cx = (corner & 1) == 0 ? minX : maxX;
			double cy = (corner & 2) == 0 ? minY : maxY;
			double cz = (corner & 4) == 0 ? minZ : maxZ;
			int color = (corner & 2) == 0 ? colorBottom : colorTop;

			// Скобка из трёх коротких линий по направлениям граней
			WorldGeometryRenderer.line(buffer, pose,
					cx, cy, cz, color,
					cx == minX ? cx + sizeX : cx - sizeX, cy, cz, color,
					width, unitsPerPixel);
			WorldGeometryRenderer.line(buffer, pose,
					cx, cy, cz, color,
					cx, cy == minY ? cy + sizeY : cy - sizeY, cz, color,
					width, unitsPerPixel);
			WorldGeometryRenderer.line(buffer, pose,
					cx, cy, cz, color,
					cx, cy, cz == minZ ? cz + sizeZ : cz - sizeZ, color,
					width, unitsPerPixel);
		}
	}
}

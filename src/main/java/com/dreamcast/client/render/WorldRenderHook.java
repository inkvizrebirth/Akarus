package com.dreamcast.client.render;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.EspModule;
import com.dreamcast.client.module.impl.WingsModule;
import com.dreamcast.client.module.impl.ChinaHatModule;
import com.dreamcast.client.module.impl.CosmosModule;
import com.dreamcast.client.module.impl.TargetEspModule;
import com.dreamcast.client.module.impl.TrailsModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Util;

import java.util.Iterator;
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
	private static volatile List<CosmosModule.Target> cosmosTargets = List.of();

	private WorldRenderHook() {
	}

	public static void register() {
		LevelExtractionEvents.END_EXTRACTION.register(WorldRenderHook::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(WorldRenderHook::render);
	}

	private static void extract(LevelExtractionContext context) {
		try {
			// BlockESP: боксы собраны самим модулем в тике — берем снапшот
			com.dreamcast.client.module.impl.BlockEspModule blockEsp =
					ModuleManager.find(com.dreamcast.client.module.impl.BlockEspModule.class);
			blockBoxes = blockEsp != null && blockEsp.wantsBoxes()
					? List.copyOf(blockEsp.blockBoxes())
					: List.of();

			// Scaffold: превью следующей установки (одна позиция)
			com.dreamcast.client.module.impl.ScaffoldModule scaffold =
					ModuleManager.find(com.dreamcast.client.module.impl.ScaffoldModule.class);
			scaffoldPreview = scaffold != null ? scaffold.previewPos() : null;

			// ChinaHat / Wings / TargetESP: их снапшоты не зависят от ESP, поэтому
			// собираются ДО раннего возврата «боксов рисовать нечего»
			Minecraft extractClient = Minecraft.getInstance();
			float partialTick = extractClient == null ? 1.0F
					: extractClient.getDeltaTracker().getGameTimeDeltaPartialTick(true);

			ChinaHatModule hat = ModuleManager.find(ChinaHatModule.class);
			if (hat != null && hat.isEnabled()) {
				hat.collect(partialTick);
				hats = hat.hats();
			} else {
				hats = List.of();
			}

			WingsModule wingsModule = ModuleManager.find(WingsModule.class);
			if (wingsModule != null && wingsModule.isEnabled()) {
				wingsModule.collect(partialTick);
				wings = wingsModule.rigs();
			} else {
				wings = List.of();
			}

			CosmosModule cosmos = ModuleManager.find(CosmosModule.class);
			if (cosmos != null && cosmos.isEnabled()) {
				var cosmosCamera = context.camera().position();
				cosmos.collect(context.level().entitiesForRendering(), cosmosCamera.x, cosmosCamera.y,
						cosmosCamera.z, partialTick, net.minecraft.util.Util.getMillis());
				cosmosTargets = cosmos.targets();
			} else {
				cosmosTargets = List.of();
			}

			TargetEspModule targetEsp = ModuleManager.find(TargetEspModule.class);
			targetFrame = targetEsp != null && targetEsp.wantsEffect() ? targetEsp.frame() : null;

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
								target.getId(), EspModule.healthFraction(target)),
						EspModule.healthFraction(target));
			} else {
				targetBar = null;
			}
		} catch (Exception error) {
			espBoxes = List.of();
			targetBar = null;
			blockBoxes = List.of();
			hats = List.of();
			wings = List.of();
			targetFrame = null;
		}
	}

	/** Полоска HP цели TargetESP: бокс + доля здоровья. */
	private record TargetBar(EspModule.EspBox box, float health) {
	}

	private static TargetBar targetBar;

	/** Контур сдвигаем внутрь блока — против z-fighting с его же гранью. */
	private static final double BLOCK_INSET = 0.0045;
	/** Призрак установки, наоборот, слегка расширен: грань клетки совпадает с блоком под ней. */
	private static final double GHOST_EXPAND = 0.0035;
	/** Больше этого числа боксов заливку не рисуем: 12 треугольников на каждый — дорого. */
	private static final int MAX_FILLED_BOXES = 220;

	/** Боксы BlockESP (целые координаты). */
	private static List<com.dreamcast.client.module.impl.BlockEspModule.BlockBox> blockBoxes = List.of();

	/** Превью установки Scaffold. */
	private static volatile net.minecraft.core.BlockPos scaffoldPreview;

	/** Шляпы ChinaHat (основания уже на уровне макушки). */
	private static volatile List<ChinaHatModule.Hat> hats = List.of();

	/** Крылья: готовые позы на кадр. */
	private static volatile List<WingsModule.Rig> wings = List.of();

	/** Кадр TargetESP: публикуется модулем в тике, здесь только читается. */
	private static volatile TargetEspModule.Frame targetFrame;

	private static void render(LevelRenderContext context) {
		try {
			TrailsModule trails = ModuleManager.find(TrailsModule.class);
			com.dreamcast.client.module.impl.JumpEffectModule jumpEffect =
					ModuleManager.find(com.dreamcast.client.module.impl.JumpEffectModule.class);
			com.dreamcast.client.module.impl.HitParticlesModule hitParticles =
					ModuleManager.find(com.dreamcast.client.module.impl.HitParticlesModule.class);
			com.dreamcast.client.module.impl.NametagsModule nametags =
					ModuleManager.find(com.dreamcast.client.module.impl.NametagsModule.class);
			ChinaHatModule hatModule = ModuleManager.find(ChinaHatModule.class);
			WingsModule wingsModule = ModuleManager.find(WingsModule.class);
			TargetEspModule targetEspModule = ModuleManager.find(TargetEspModule.class);
			com.dreamcast.client.module.impl.RainModule rain =
					ModuleManager.find(com.dreamcast.client.module.impl.RainModule.class);
			boolean hasRain = rain != null && rain.isEnabled();
			CosmosModule cosmos = ModuleManager.find(CosmosModule.class);
			List<CosmosModule.Target> cosmosList = cosmosTargets;
			boolean hasCosmos = cosmos != null && cosmos.wantsCosmos() && !cosmosList.isEmpty();
			List<ChinaHatModule.Hat> hatList = hats;
			List<WingsModule.Rig> wingRigs = wings;
			TargetEspModule.Frame frame = targetFrame;
			List<EspModule.EspBox> boxes = espBoxes;
			boolean hasTrail = trails != null && trails.wantsLine();
			boolean hasRings = jumpEffect != null && jumpEffect.wantsRings();
			boolean hasHits = hitParticles != null && hitParticles.wantsWaves();
			boolean hasTags = nametags != null && nametags.wantsTags();
			net.minecraft.core.BlockPos preview = scaffoldPreview;
			if (!hasTrail && boxes.isEmpty() && !hasRings && !hasHits && !hasTags
					&& targetBar == null && blockBoxes.isEmpty() && preview == null
					&& hatList.isEmpty() && wingRigs.isEmpty() && frame == null && !hasRain
					&& !hasCosmos) {
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
							drawBoxes(boxes, pose, buffer, camX, camY, camZ, unitsPerPixel, now);
						}
						if (targetBar != null) {
							drawTargetBar(targetBar, pose, buffer, camX, camY, camZ, unitsPerPixel);
						}
						if (!blockBoxes.isEmpty()) {
							drawBlockBoxes(blockBoxes, pose, buffer, camX, camY, camZ, unitsPerPixel);
						}
						if (preview != null) {
							drawScaffoldPreview(preview, pose, buffer, camX, camY, camZ, unitsPerPixel);
						}
						if (hasRings) {
							drawJumpRings(jumpEffect, pose, buffer, camX, camY, camZ, unitsPerPixel, now);
						}
						if (hasHits) {
							drawHitWaves(hitParticles, pose, buffer, camX, camY, camZ, unitsPerPixel, now);
						}
						if (hatModule != null && !hatList.isEmpty()) {
							ChinaHatRenderer.draw(hatModule, hatList, pose, buffer,
									camX, camY, camZ, unitsPerPixel, now);
						}
						if (wingsModule != null && !wingRigs.isEmpty()) {
							WingsRenderer.draw(wingsModule, wingRigs, pose, buffer,
									camX, camY, camZ, unitsPerPixel, now);
						}
						if (targetEspModule != null && frame != null) {
							TargetEspRenderer.draw(targetEspModule, frame, pose, buffer,
									camX, camY, camZ, unitsPerPixel, now, partialTick);
						}
						if (hasRain) {
							RainRenderer.render(rain, pose, buffer, camX, camY, camZ,
									unitsPerPixel, now);
						}
						if (hasCosmos) {
							CosmosRenderer.render(cosmos, cosmosList, pose, buffer,
									camX, camY, camZ, unitsPerPixel, now);
						}
						// Эффекты смерти живут и без включённого космоса: цель могла
						// умереть в тот кадр, когда обёртку уже некому рисовать.
						else if (cosmos != null && cosmos.isEnabled()) {
							CosmosRenderer.render(cosmos, List.of(), pose, buffer,
									camX, camY, camZ, unitsPerPixel, now);
						}
					});
					// Nametags: текстовые биллборды — той же трубой, что ванильные ники
					if (hasTags) {
						drawNametags(context, nametags, camX, camY, camZ, unitsPerPixel);
					}
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
		var points = trails.trailPoints().toArray(new TrailsModule.TrailPoint[0]);
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
		// Три прохода: широкий ореол, цветная лента и тонкая яркая сердцевина.
		// Это даёт объёмный шлейф вместо одиночных "треугольников" за игроком.
		drawTrailPass(trails, pose, buffer, points, head, camX, camY, camZ,
				unitsPerPixel, width * 3.8F, 0.14F, now);
		drawTrailPass(trails, pose, buffer, points, head, camX, camY, camZ,
				unitsPerPixel, width * 1.8F, 0.42F, now);
		drawTrailPass(trails, pose, buffer, points, head, camX, camY, camZ,
				unitsPerPixel, Math.max(1.0F, width * 0.70F), 0.95F, now);
	}

	private static void drawTrailPass(TrailsModule trails, PoseStack.Pose pose, VertexConsumer buffer,
	                                  TrailsModule.TrailPoint[] points, float[] head,
	                                  double camX, double camY, double camZ,
	                                  float unitsPerPixel, float widthPx, float alphaScale,
	                                  long now) {
		// Первый сегмент: от интерполированной позиции игрока к свежей точке
		float[] from = head;
		float fromAlpha = 1.0F;
		for (int i = points.length - 1; i >= 0; i--) {
			TrailsModule.TrailPoint to = points[i];
			float t = (points.length - 1 - i + 1) / (float) (points.length + 1);
			float tNext = (points.length - 1 - i) / (float) (points.length + 1);
			float toAlpha = trails.pointAlpha(to, now);

			int colorFrom = RenderUtils.withAlpha(trails.trailColor(tNext, now), alphaScale * fromAlpha);
			int colorTo = RenderUtils.withAlpha(trails.trailColor(t, now), alphaScale * toAlpha * (1.0F - t * 0.20F));

			WorldGeometryRenderer.line(buffer, pose,
					from[0] - camX, from[1] - camY, from[2] - camZ, colorFrom,
					to.x() - camX, to.y() - camY, to.z() - camZ, colorTo,
					widthPx, unitsPerPixel);
			from = new float[]{to.x(), to.y(), to.z()};
			fromAlpha = toAlpha;
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
	                              double camX, double camY, double camZ, float unitsPerPixel, long now) {
		EspModule esp = ModuleManager.find(EspModule.class);
		if (esp == null) {
			return;
		}
		if (esp.bloomMode()) {
			// «Свечение»: ореол + ядро + уголки + дымка + кольца (см. EspBloomRenderer)
			EspBloomRenderer.draw(esp, boxes, pose, buffer, camX, camY, camZ, unitsPerPixel, now);
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

			// Тёмная подложка на 2.4px шире основной линии. Это единственный приём,
			// который честно решает «ESP не видно на снегу/небе/лаве»: контур остаётся
			// читаемым на любом фоне, потому что контраст рисуется самим контуром, а не
			// цветом. Рисуем её первой, сверху — цветной.
			int underlay = RenderUtils.withAlpha(0xFF000000, 0.62F);
			if (corners) {
				drawCornerBrackets(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ,
						underlay, underlay, width + 2.4F, unitsPerPixel);
				drawCornerBrackets(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ,
						withAlpha(colorTop, alpha), withAlpha(colorBottom, alpha), width, unitsPerPixel);
			} else {
				for (int pass = 0; pass < 2; pass++) {
					boolean back = pass == 0;
					float passWidth = back ? width + 2.4F : width;
					for (int[] edge : BOX_EDGES) {
						double x0 = edge[0] == 0 ? minX : maxX;
						double y0 = edge[1] == 0 ? minY : maxY;
						double z0 = edge[2] == 0 ? minZ : maxZ;
						double x1 = edge[3] == 0 ? minX : maxX;
						double y1 = edge[4] == 0 ? minY : maxY;
						double z1 = edge[5] == 0 ? minZ : maxZ;

						int c0 = back ? underlay : withAlpha(edge[1] == 0 ? colorBottom : colorTop, alpha);
						int c1 = back ? underlay : withAlpha(edge[4] == 0 ? colorBottom : colorTop, alpha);
						WorldGeometryRenderer.line(buffer, pose, x0, y0, z0, c0, x1, y1, z1, c1,
								passWidth, unitsPerPixel);
					}
				}
			}

			// Колонка здоровья справа от бокса: видно «сколько осталось», не глядя на HP
			float health = box.health();
			if (health > 0.0F) {
				double columnX = maxX + 0.16;
				double columnBottom = minY + 0.05;
				double columnTop = maxY - 0.05;
				double filled = columnBottom + (columnTop - columnBottom) * health;
				int hpColor = health < 0.3F ? 0xFFFF5C7A : health < 0.6F ? 0xFFFFC66C : 0xFF7CE58C;
				WorldGeometryRenderer.line(buffer, pose, columnX, columnBottom, maxZ, underlay,
						columnX, columnTop, maxZ, underlay, width + 3.2F, unitsPerPixel);
				WorldGeometryRenderer.line(buffer, pose, columnX, columnBottom, maxZ, hpColor,
						columnX, filled, maxZ, hpColor, width + 0.6F, unitsPerPixel);
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

	/** Боксы BlockESP: рамка или «уголки» вокруг каждого выбранного блока. */
	private static void drawBlockBoxes(List<com.dreamcast.client.module.impl.BlockEspModule.BlockBox> boxes,
			PoseStack.Pose pose, VertexConsumer buffer,
			double camX, double camY, double camZ, float unitsPerPixel) {
		com.dreamcast.client.module.impl.BlockEspModule blockEsp =
				ModuleManager.find(com.dreamcast.client.module.impl.BlockEspModule.class);
		if (blockEsp == null) {
			return;
		}
		float width = blockEsp.lineWidth();
		boolean corners = blockEsp.cornersOnly();
		// Заливка только когда боксов немного: 12 треугольников на каждый — это
		// приятно глазу до сотен целей и уже дорого на них
		boolean fill = blockEsp.fill() && boxes.size() <= MAX_FILLED_BOXES;
		double fadeRadius = blockEsp.radiusBlocks();

		for (com.dreamcast.client.module.impl.BlockEspModule.BlockBox box : boxes) {
			// Контур сдвигаем ВНУТРЬ блока: ровно по границе линия лежит в той же
			// плоскости, что грань мира, и начинается z-fighting — «мерцающая рамка»
			// на каждом кадре. 0.0045 блока хватает, чтобы мерцание пропало, и
			// недостаточно, чтобы рамка visibly «отползла» от блока.
			double minX = box.x() - camX + BLOCK_INSET;
			double minY = box.y() - camY + BLOCK_INSET;
			double minZ = box.z() - camZ + BLOCK_INSET;
			double maxX = box.x() - camX + 1.0 - BLOCK_INSET;
			double maxY = box.y() - camY + 1.0 - BLOCK_INSET;
			double maxZ = box.z() - camZ + 1.0 - BLOCK_INSET;

			// Плавное затухание у границы радиуса: иначе блоки на пределе то
			// появляются, то исчезают целыми пачками при малейшем движении
			float fade = distanceFade(minX, minY, minZ, maxX, maxY, maxZ, fadeRadius);
			int color = withAlpha(blockEsp.lineColor(box.phase()), (int) (0xE0 * fade));

			if (fill) {
				int soft = withAlpha(blockEsp.lineColor(box.phase()), (int) (0x26 * fade));
				drawBoxFaces(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ, soft);
			}

			if (corners) {
				drawCornerBrackets(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ, color, color, width, unitsPerPixel);
			} else {
				for (int[] edge : BOX_EDGES) {
					double x0 = edge[0] == 0 ? minX : maxX;
					double y0 = edge[1] == 0 ? minY : maxY;
					double z0 = edge[2] == 0 ? minZ : maxZ;
					double x1 = edge[3] == 0 ? minX : maxX;
					double y1 = edge[4] == 0 ? minY : maxY;
					double z1 = edge[5] == 0 ? minZ : maxZ;
					WorldGeometryRenderer.line(buffer, pose, x0, y0, z0, color, x1, y1, z1, color, width, unitsPerPixel);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Scaffold: превью следующей установки
	// ------------------------------------------------------------------

	private static void drawScaffoldPreview(net.minecraft.core.BlockPos pos, PoseStack.Pose pose,
	                                        VertexConsumer buffer,
	                                        double camX, double camY, double camZ, float unitsPerPixel) {
		// Призрак ставится в пустую клетку, но её нижняя грань совпадает с гранью
		// блока под ней — поэтому рамку слегка РАСШИРЯЕМ (не сдвигаем внутрь, как
		// у BlockESP): призрак должен читаться как «сюда встанет», а не как «здесь
		// уже стоит».
		double minX = pos.getX() - camX - GHOST_EXPAND;
		double minY = pos.getY() - camY - GHOST_EXPAND;
		double minZ = pos.getZ() - camZ - GHOST_EXPAND;
		double maxX = pos.getX() - camX + 1.0 + GHOST_EXPAND;
		double maxY = pos.getY() - camY + 1.0 + GHOST_EXPAND;
		double maxZ = pos.getZ() - camZ + 1.0 + GHOST_EXPAND;
		// Мягкий пульс: «сюда» видно даже боковым зрением, и оно не выглядит статичной рамкой
		float pulse = 0.80F + 0.20F * (float) Math.sin(Util.getMillis() / 320.0);
		int color = withAlpha(0x55FF55, (int) (0xC0 * pulse));
		drawBoxFaces(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ,
				withAlpha(0x55FF55, (int) (0x2E * pulse)));
		for (int[] edge : BOX_EDGES) {
			double x0 = edge[0] == 0 ? minX : maxX;
			double y0 = edge[1] == 0 ? minY : maxY;
			double z0 = edge[2] == 0 ? minZ : maxZ;
			double x1 = edge[3] == 0 ? minX : maxX;
			double y1 = edge[4] == 0 ? minY : maxY;
			double z1 = edge[5] == 0 ? minZ : maxZ;
			WorldGeometryRenderer.line(buffer, pose, x0, y0, z0, color, x1, y1, z1, color, 1.5F, unitsPerPixel);
		}
	}

	/** Шесть граней бокса полупрозрачным цветом: блок читается объёмом. */
	private static void drawBoxFaces(PoseStack.Pose pose, VertexConsumer buffer,
	                                 double minX, double minY, double minZ,
	                                 double maxX, double maxY, double maxZ, int color) {
		// низ / верх
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, minZ, color, maxX, minY, minZ, color, maxX, minY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, minZ, color, maxX, minY, maxZ, color, minX, minY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, maxY, minZ, color, maxX, maxY, maxZ, color, maxX, maxY, minZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, maxY, minZ, color, minX, maxY, maxZ, color, maxX, maxY, maxZ, color);
		// четыре боковых
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, minZ, color, maxX, minY, minZ, color, maxX, maxY, minZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, minZ, color, maxX, maxY, minZ, color, minX, maxY, minZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, maxZ, color, maxX, maxY, maxZ, color, maxX, minY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, maxZ, color, minX, maxY, maxZ, color, maxX, maxY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, minZ, color, minX, maxY, minZ, color, minX, maxY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, minX, minY, minZ, color, minX, maxY, maxZ, color, minX, minY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, maxX, minY, minZ, color, maxX, minY, maxZ, color, maxX, maxY, maxZ, color);
		WorldGeometryRenderer.triangle(buffer, pose, maxX, minY, minZ, color, maxX, maxY, maxZ, color, maxX, maxY, minZ, color);
	}

	/**
	 * Затухание у границы радиуса: последний «хвост» радиуса (30 %) плавно гаснет.
	 * Без него блоки на пределе дальности вспыхивают и гаснут пачками при шаге игрока.
	 */
	private static float distanceFade(double minX, double minY, double minZ,
	                                  double maxX, double maxY, double maxZ, double radius) {
		double dx = (minX + maxX) * 0.5;
		double dy = (minY + maxY) * 0.5;
		double dz = (minZ + maxZ) * 0.5;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (radius <= 1.0 || dist < radius * 0.7) {
			return 1.0F;
		}
		float t = (float) Math.max(0.0, Math.min(1.0, (radius - dist) / (radius * 0.3)));
		return 0.15F + 0.85F * t;
	}

	// ------------------------------------------------------------------
	// HitParticles: волна в точке удара
	// ------------------------------------------------------------------

	/**
	 * Волны попаданий. Режим «Волна» — то же кольцо, что у Jump Effect
	 * (мерцание, эхо, ореол), режим «Искры» — три мелких кольца в стороны.
	 */
	private static void drawHitWaves(com.dreamcast.client.module.impl.HitParticlesModule effect,
	                                 PoseStack.Pose pose, VertexConsumer buffer,
	                                 double camX, double camY, double camZ,
	                                 float unitsPerPixel, long now) {
		float maxRadius = effect.radiusBlocks();
		long duration = effect.durationMs();
		float intensity = effect.intensityScale();
		float width = 2.6F;

		effect.gc(now);
		for (com.dreamcast.client.module.impl.HitParticlesModule.HitWave wave : effect.waves()) {
			float age = now - wave.bornMs();
			float progress = age / (float) duration;
			if (progress >= 1.0f) {
				continue;
			}
			float x = (float) (wave.x() - camX);
			float y = (float) (wave.y() - camY);
			float z = (float) (wave.z() - camZ);
			float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
			float fade = (1.0f - progress) * (1.0f - progress);
			float seed = (wave.seed() % 1000) / 1000.0f * 6.28f;

			if (wave.spark()) {
				// «Искры»: три мелких волны со сдвигом фаз — дробная отдача
				for (int k = 0; k < 3; k++) {
					float phase = progress - k * 0.12f;
					if (phase <= 0.0f) {
						continue;
					}
					float easedK = 1.0f - (1.0f - phase) * (1.0f - phase);
					drawRingCircle(effect::waveColor, pose, buffer, x, y, z,
							maxRadius * (0.5f + k * 0.25f) * easedK, width * 0.8f, 18,
							0.55f * fade * intensity, seed + k, now, 2.0f, unitsPerPixel);
				}
			} else {
				// «Волна»: фирменное кольцо — ореол, кромка с мерцанием, эхо
				com.dreamcast.client.render.WorldRenderHook.RingColor color = effect::waveColor;
				drawRingCircle(color, pose, buffer, x, y, z, maxRadius * eased * 1.06f,
						width * 3.2f, 32, 0.10f * fade * intensity, seed, now, 0.0f, unitsPerPixel);
				drawRingCircle(color, pose, buffer, x, y, z, maxRadius * eased,
						width, 32, 0.85f * fade * intensity, seed, now, 1.0f, unitsPerPixel);
				float echoProgress = Math.max(0.0f, progress - 0.16f);
				float echoEased = 1.0f - (1.0f - echoProgress) * (1.0f - echoProgress);
				drawRingCircle(color, pose, buffer, x, y, z, maxRadius * echoEased * 0.7f,
						width * 0.7f, 20, 0.35f * fade * intensity, seed, now, 2.0f, unitsPerPixel);
			}
		}
	}

	// ------------------------------------------------------------------
	// Nametags: расширенные таблички игроков
	// ------------------------------------------------------------------

	/**
	 * Текстовые биллборды через {@code submitNameTag} — та же труба, что у
	 * ванильных ников: масштаб по дистанции, фон и подсветка берутся из игры.
	 * Строки идут вниз от точки над головой с шагом 0.3 блока.
	 */
	private static void drawNametags(LevelRenderContext context,
	                                 com.dreamcast.client.module.impl.NametagsModule nametags,
	                                 double camX, double camY, double camZ, float unitsPerPixel) {
		var collector = context.submitNodeCollector();
		var camera = context.levelState().cameraRenderState;
		PoseStack poseStack = context.poseStack();

		// Подложка рисуется нашим же примитивом, тремя концентрическими биллбордами:
		// три слоя с падающей альфой дают мягкий край (тот же эффект, что у blur-спрайта),
		// и текст перестаёт теряться на светлых блоках. Размер считаем по длине строки.
		List<com.dreamcast.client.module.impl.NametagsModule.TagEntry> entries = nametags.entries();
		collector.submitCustomGeometry(poseStack, WorldGeometryRenderer.type(), (pose, buffer) -> {
			for (com.dreamcast.client.module.impl.NametagsModule.TagEntry tag : entries) {
				double ax = tag.x() - camX;
				double ay = tag.y() - camY;
				double az = tag.z() - camZ;
				int chars = 0;
				for (net.minecraft.network.chat.Component text : tag.lines()) {
					chars = Math.max(chars, text.getString().length());
				}
				double half = Math.max(0.34, 0.062 * chars + 0.2) + tag.lines().size() * 0.06;
				double rise = Math.max(0.18, unitsPerPixel * 3.2);
				for (int layer = 2; layer >= 0; layer--) {
					double size = half * (1.0 + layer * 0.42);
					int alpha = layer == 0 ? 0x8C : (layer == 1 ? 0x4A : 0x24);
					WorldGeometryRenderer.billboard(buffer, pose, ax, ay + rise * 0.35, az, size,
							RenderUtils.withAlpha(0xFF06060A, alpha / 255.0F));
				}
			}
		});

		for (com.dreamcast.client.module.impl.NametagsModule.TagEntry tag : nametags.entries()) {
			double ax = tag.x() - camX;
			double ay = tag.y() - camY;
			double az = tag.z() - camZ;
			int line = 0;
			for (net.minecraft.network.chat.Component text : tag.lines()) {
				collector.submitNameTag(poseStack,
						new net.minecraft.world.phys.Vec3(ax, ay - line * 0.30, az),
						8, text, true, 0xF000F0, camera);
				line++;
			}
		}
	}

	// ------------------------------------------------------------------
	// Jump Effect: ударная волна при прыжке
	// ------------------------------------------------------------------

	/**
	 * Кольцо из сегментов-линий: окружность делится на дуги, каждая дуга —
	 * линия со своим цветом и альфой. Мерцание по окружности даёт живую
	 * «энергетическую» волну вместо плоской геометрии.
	 */
	private static void drawJumpRings(com.dreamcast.client.module.impl.JumpEffectModule effect,
	                                  PoseStack.Pose pose, VertexConsumer buffer,
	                                  double camX, double camY, double camZ,
	                                  float unitsPerPixel, long now) {
		float maxRadius = effect.radiusBlocks();
		long duration = effect.durationMs();
		float intensity = effect.intensityScale();
		int segments = effect.segmentCount();
		// Ширины заданы в пикселях: WorldGeometryRenderer.line сам переводит их
		// в мировые единицы через unitsPerPixel
		float width = 3.0F;

		Iterator<com.dreamcast.client.module.impl.JumpEffectModule.JumpRing> iterator =
				effect.rings().iterator();
		while (iterator.hasNext()) {
			com.dreamcast.client.module.impl.JumpEffectModule.JumpRing ring = iterator.next();
			float age = now - ring.bornMs();
			float progress = age / (float) duration;
			if (progress >= 1.0f) {
				continue;
			}
			float x = (float) (ring.x() - camX);
			float y = (float) (ring.y() - camY);
			float z = (float) (ring.z() - camZ);

			// Разворот с торможением: волна быстро рвётся наружу и «догасает»
			float eased = 1.0f - (1.0f - progress) * (1.0f - progress) * (1.0f - progress);
			float fade = (1.0f - progress) * (1.0f - progress);
			float seed = (ring.seed() % 1000) / 1000.0f * 6.28f;

			com.dreamcast.client.render.WorldRenderHook.RingColor color =
					t -> effect.ringColor(t, progress, now);
			// 1. Широкое мягкое свечение позади волны — «блюр»-ореол
			drawRingCircle(color, pose, buffer, x, y, z, maxRadius * eased * 1.06f, width * 3.2f,
					segments, 0.10f * fade * intensity, seed, now, 0.0f, unitsPerPixel);
			// 2. Основная волна: мерцающие дуги
			drawRingCircle(color, pose, buffer, x, y, z, maxRadius * eased, width,
					segments, 0.85f * fade * intensity, seed, now, 1.0f, unitsPerPixel);
			// 3. Эхо: задержанная волна поменьше
			float echoProgress = Math.max(0.0f, progress - 0.16f);
			float echoEased = 1.0f - (1.0f - echoProgress) * (1.0f - echoProgress);
			drawRingCircle(color, pose, buffer, x, y, z, maxRadius * echoEased * 0.78f, width * 0.7f,
					Math.max(16, segments / 2), 0.38f * fade * intensity, seed, now, 2.0f, unitsPerPixel);
			// 4. Поднимающееся кольцо-«подъём» — отмечает сам отрыв
			float riseY = y + eased * 0.55f;
			drawRingCircle(color, pose, buffer, x, riseY, z, maxRadius * eased * 0.5f, width * 0.6f,
					Math.max(16, segments / 2), 0.30f * fade * intensity, seed, now, 3.0f, unitsPerPixel);
		}
	}

	/** Круг из сегментов; яркость дуги модулируется бегущей синусоидой (shimmer). */
	/** Источник цвета кольца: t — доля окружности. */
	private interface RingColor {
		int at(float t);
	}

	private static void drawRingCircle(RingColor color,
	                                   PoseStack.Pose pose, VertexConsumer buffer,
	                                   float x, float y, float z, float radius, float width, int segments,
	                                   float alpha, float seed, long now, float shimmerSpeed,
	                                   float unitsPerPixel) {
		if (radius < 0.02f || alpha <= 0.01f) {
			return;
		}
		for (int i = 0; i < segments; i++) {
			float t0 = (float) i / segments;
			float t1 = (float) (i + 1) / segments;
			float a0 = t0 * 6.2831855f;
			float a1 = t1 * 6.2831855f;
			// Мерцание: три «луча» яркости бегут по окружности
			float shimmer0 = shimmerSpeed <= 0.0f ? 1.0f
					: 0.55f + 0.45f * (float) Math.sin(a0 * 3.0f + now * 0.008f * shimmerSpeed + seed);
			float shimmer1 = shimmerSpeed <= 0.0f ? 1.0f
					: 0.55f + 0.45f * (float) Math.sin(a1 * 3.0f + now * 0.008f * shimmerSpeed + seed);

			int c0 = withAlpha(color.at(t0), (int) (0xFF * alpha * shimmer0));
			int c1 = withAlpha(color.at(t1), (int) (0xFF * alpha * shimmer1));
			WorldGeometryRenderer.line(buffer, pose,
					x + (float) Math.cos(a0) * radius, y, z + (float) Math.sin(a0) * radius, c0,
					x + (float) Math.cos(a1) * radius, y, z + (float) Math.sin(a1) * radius, c1,
					width, unitsPerPixel);
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

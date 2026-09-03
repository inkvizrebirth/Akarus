package com.akarus.client.render;

import com.akarus.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Обводка и свечение вокруг руки и предмета от первого лица.
 *
 * Как это устроено в 26.2
 * -----------------------
 * Руки от первого лица рисуются в собственный submit-контейнер, чей диспетчер
 * выполняет только фазы solid, translucent, afterTerrain и alwaysOnTop — ванильная
 * фаза {@code outline}, в которую игра складывает свечение, для рук не исполняется
 * никогда. Поэтому контур собирается здесь руками.
 *
 * Прежняя версия просто «раздувала» силуэт масштабом. Это выглядело сломанно по двум
 * причинам:
 * <ul>
 *   <li>масштаб считается от начала системы координат, а оно у руки — в углу экрана,
 *       поэтому раздутая копия уезжала от руки в сторону;</li>
 *   <li>копия лежит ровно на той же глубине, что и сама рука, и при алфа-смешивании
 *       закрашивает руку целиком вместо тонкого ободка.</li>
 * </ul>
 *
 * Теперь геометрия не масштабируется, а рисуется несколько раз со сдвигом в плоскости
 * экрана: копия смещается на N пикселей вверх/вниз/влево/вправо и по диагоналям и
 * одновременно уходит чуть-чуть «от камеры» — там, где рука есть, она перекрывает
 * копию по глубине, а наружу торчит только ровное кольцо одинаковой толщины.
 * Толщина измеряется в пикселях и не зависит от того, насколько крупно рука
 * нарисована на экране.
 *
 * Сдвиг считается честно: текущая матрица позы переносит вершины руки в пространство
 * камеры, где +X — «вправо по экрану», +Y — «вверх», -Z — «от камеры». Значит нужный
 * экранный сдвиг достаточно перенести обратно обратной матрицей, и он попадёт в
 * локальные координаты модели при любом масштабе и повороте руки.
 */
public final class HandOutlineRenderer {

	/** Однотонная белая текстура 1×1 из ресурсов мода: цвет берётся из вершин. */
	public static final Identifier WHITE_TEXTURE =
			Identifier.fromNamespaceAndPath("akarus", "textures/hand_outline/white.png");

	/** Полный свет: обводка не должна темнеть вместе с рукой. */
	private static final int FULL_LIGHT = 0xF000F0;

	/**
	 * Насколько блоков копия уходит от камеры. Хватает, чтобы рука гарантированно
	 * выиграла тест глубины на совпадающих пикселях, и слишком мало, чтобы силуэт
	 * заметно «съехал» из-за перспективы.
	 */
	private static final float DEPTH_BIAS = 0.0022F;

	/**
	 * Тангенс половины угла hud-проекции. Руки в 26.2 всегда рисуются с fov 70°
	 * (см. {@code Camera#calculateHudFov}), поэтому лезть в настоящую матрицу
	 * проекции за переводом пикселей в единицы камеры не нужно.
	 */
	private static final float HUD_TAN_HALF_FOV = 0.70021F;

	/** Лучей у кольца: 12 дают ровный круг вместо «квадрата с фаской». */
	private static final int DIRECTIONS = 12;
	private static final float[] DIRECTION_X = new float[DIRECTIONS];
	private static final float[] DIRECTION_Y = new float[DIRECTIONS];

	/** Больше шести полос свечения рисунок уже не становится мягче, а стоимость растёт. */
	private static final int MAX_GLOW_BANDS = 5;

	static {
		for (int i = 0; i < DIRECTIONS; i++) {
			double angle = i * (Math.PI * 2.0 / DIRECTIONS);
			DIRECTION_X[i] = (float) Math.cos(angle);
			DIRECTION_Y[i] = (float) Math.sin(angle);
		}
	}

	private static volatile RenderType cachedType;

	/**Scratch для немедленного прохода руки — в кадре их много, аллокации не нужны.*/
	private static final Matrix4f SCRATCH_INVERSE = new Matrix4f();
	private static final Vector3f SCRATCH_OFFSET = new Vector3f();

	private HandOutlineRenderer() {
	}

	/**
	 * Что рисуем. Собирается один раз за кадр в {@link HandRenderHook} из настроек
	 * модуля — цвета, фаза радуги и прозрачность уже посчитаны, рендеру остаётся
	 * только разложить их по направлениям.
	 *
	 * @param primary      основной цвет (ARGB, альфа уже умножена на «Плотность»)
	 * @param secondary    второй цвет — для градиента поперёк кольца
	 * @param gradient     смешивать primary и secondary по направлению кольца
	 * @param rainbow      переливаться по кругу вместо смешивания цветов
	 * @param rainbowPhase сдвиг оттенка радуги, 0..1 (считается модулем по времени)
	 * @param alpha        альфа 0..255 — нужна только радуге, остальные цвета уже с альфой
	 * @param thickness    толщина обводки в пикселях
	 * @param softness     ширина мягкого свечения вокруг обводки, в пикселях
	 * @param ring         рисовать кольцо (плотный цветной контур)
	 * @param glow         рисовать свечение (оно слегка подкрашивает и саму руку — так
	 *                     выглядит «шейдер», и именно эта часть не зависит от глубины)
	 */
	public record Spec(int primary, int secondary, boolean gradient, boolean rainbow, float rainbowPhase,
			int alpha, int thickness, int softness, boolean ring, boolean glow) {

		public boolean empty() {
			return !ring && !glow;
		}

		public boolean needsGlow() {
			return glow && softness > 0;
		}
	}

	/** Render type обводки: без диффузного освещения и без отсечения задних граней. */
	public static RenderType outlineType() {
		RenderType type = cachedType;
		if (type == null) {
			// emissive — вершинный цвет идёт «как есть»: обводка ровно того цвета,
			// который выбран в меню, а не затемняется по нормалям модели
			type = RenderTypes.entityTranslucentEmissive(WHITE_TEXTURE);
			cachedType = type;
		}
		return type;
	}

	/**
	 * Обводка руки.
	 *
	 * @param arm       часть модели руки — та же, что рисует игра
	 * @param collector тот же контейнер, в который игра кладёт руку
	 */
	public static void outlineArm(ModelPart arm, PoseStack poseStack, OrderedSubmitNodeCollector collector, Spec spec) {
		if (arm == null || collector == null || spec == null || spec.empty()) {
			return;
		}

		float unitsPerPixel = unitsPerPixel(poseStack.last().pose());
		if (unitsPerPixel <= 0.0f) {
			return;
		}

		// Кольцо рисуем первым: свечение должно лечь поверх него, а не под него
		if (spec.ring()) {
			for (int direction = 0; direction < DIRECTIONS; direction++) {
				submitArmCopy(arm, poseStack, collector, spec, unitsPerPixel,
						DIRECTION_X[direction] * spec.thickness(), DIRECTION_Y[direction] * spec.thickness(),
						colorFor(spec, direction, 1.0f));
			}
		}

		if (spec.glow()) {
			int bands = glowBands(spec);
			for (int band = bands - 1; band >= 0; band--) {
				float spread = bandSpread(spec, band, bands);
				float fade = bandAlpha(spec, band, bands);
				if (fade <= 0.01f) {
					continue;
				}
				// Наружным полосам хватает половины направлений: под мягким спадом
				// прозрачности «шестиугольник» не отличить от круга, а вдвое дешевле
				for (int direction = 0; direction < DIRECTIONS; direction += 2) {
					submitArmCopy(arm, poseStack, collector, spec, unitsPerPixel,
							DIRECTION_X[direction] * spread, DIRECTION_Y[direction] * spread,
							colorFor(spec, direction, fade));
				}
			}
		}
	}

	/**
	 * Одна копия руки, сдвинутая на экранный offset (в пикселях интерфейса).
	 *
	 * @param unitsPerPixel пересчёт пикселей в единицы камеры — считается один раз на
	 *                      проход, а не на каждое кольцо (копий за кадр больше сотни)
	 */
	private static void submitArmCopy(ModelPart arm, PoseStack poseStack, OrderedSubmitNodeCollector collector,
			Spec spec, float unitsPerPixel, float screenXPx, float screenYPx, int color) {
		poseStack.pushPose();
		applyScreenOffset(poseStack, screenXPx, screenYPx, unitsPerPixel);
		collector.submitModelPart(arm, poseStack, outlineType(), FULL_LIGHT, OverlayTexture.NO_OVERLAY,
				null, color, null);
		poseStack.popPose();
	}

	/**
	 * Обводка предмета в руке.
	 *
	 * Предмет — это список запечённых квадов, а не часть модели, поэтому проходы
	 * считает сам {@link QuadWriter}: у него вершины попадают в буфер напрямую.
	 */
	public static void outlineItem(List<BakedQuad> quads, PoseStack poseStack, OrderedSubmitNodeCollector collector, Spec spec) {
		if (collector == null || spec == null || spec.empty() || quads == null || quads.isEmpty()) {
			return;
		}

		float unitsPerPixel = unitsPerPixel(poseStack.last().pose());
		if (unitsPerPixel <= 0.0f) {
			return;
		}

		poseStack.pushPose();
		collector.submitCustomGeometry(poseStack, outlineType(), new QuadWriter(quads, spec, unitsPerPixel));
		poseStack.popPose();
	}

	// ------------------------------------------------------------------
	// Общая математика колец
	// ------------------------------------------------------------------

	private static int glowBands(Spec spec) {
		if (!spec.needsGlow()) {
			return 1;
		}
		return Math.max(2, Math.min(MAX_GLOW_BANDS, 2 + spec.softness() / 2));
	}

	/** Радиус i-й полосы свечения в пикселях — от кромки обводки наружу. */
	private static float bandSpread(Spec spec, int band, int bands) {
		float t = bands <= 1 ? 0.0f : band / (float) (bands - 1);
		return spec.thickness() + spec.softness() * (0.2f + 0.8f * t);
	}

	/** Прозрачность i-й полосы: чем дальше, тем прозрачнее — отсюда мягкий край. */
	private static float bandAlpha(Spec spec, int band, int bands) {
		float t = bands <= 1 ? 0.0f : band / (float) (bands - 1);
		return RenderUtils.falloff(t) * 0.45f;
	}

	/**
	 * Цвет луча кольца.
	 *
	 * variation — положение луча поперёк экрана (0 — «влево», 1 — «вправо»): у
	 * градиента это точка перехода primary → secondary, у радуги — сдвиг оттенка,
	 * поэтому радуга переливается вокруг руки, а не мигает целиком.
	 */
	private static int colorFor(Spec spec, int direction, float fade) {
		float variation = (DIRECTION_X[direction] + 1.0f) * 0.5f;

		int color;
		if (spec.rainbow()) {
			color = RenderUtils.hsb(spec.rainbowPhase() + variation * 0.34f, 0.80f, 1.0f, spec.alpha());
		} else if (spec.gradient()) {
			color = RenderUtils.mix(spec.primary(), spec.secondary(), variation);
		} else {
			color = spec.primary();
		}

		return fade >= 1.0f ? color : RenderUtils.fade(color, fade);
	}

	/**
	 * Толкает текущую позу на экранный сдвиг: (screenX, screenY) в пикселях плюс
	 * маленький уход от камеры, чтобы рука перекрывала копию.
	 */
	private static void applyScreenOffset(PoseStack poseStack, float screenXPx, float screenYPx, float unitsPerPixel) {
		Matrix4f matrix = poseStack.last().pose();
		SCRATCH_INVERSE.set(matrix);
		// Вырожденную матрицу JOML инвертирует исключением, а исключение в рендере
		// роняет игру — такую копию просто рисуем без сдвига
		if (Math.abs(SCRATCH_INVERSE.determinant()) < 1.0e-12f) {
			return;
		}
		SCRATCH_INVERSE.invert();
		SCRATCH_INVERSE.transformDirection(screenXPx * unitsPerPixel, screenYPx * unitsPerPixel, -DEPTH_BIAS, SCRATCH_OFFSET);
		poseStack.translate(SCRATCH_OFFSET.x, SCRATCH_OFFSET.y, SCRATCH_OFFSET.z);
	}

	/**
	 * Сколько единиц пространства камеры приходится на один пиксель на глубине руки.
	 *
	 * «Пиксель» тут — экранный пиксель интерфейса (то есть с учётом GUI scale), а не
	 * физический пиксель окна: иначе на масштабе интерфейса 2 обводка в 3 пикселя
	 * превращалась бы в полтора, и настройка вела бы себя по-разному на разных
	 * настройках Minecraft.
	 */
	private static float unitsPerPixel(Matrix4fc poseMatrix) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return 0.0f;
		}

		int windowHeight = client.getWindow().getGuiScaledHeight();
		if (windowHeight <= 0) {
			return 0.0f;
		}

		// Глубина начала координат руки в пространстве камеры: m32 — translation Z
		float depth = Math.abs(poseMatrix.m32());
		if (depth < 0.05f) {
			depth = 0.5F;
		}

		return 2.0f * HUD_TAN_HALF_FOV * depth / windowHeight;
	}

	/**
	 * Пишет квады предмета, разложенные по кольцам, одним цветом.
	 *
	 * Отдельный класс нужен потому, что рисование происходит позже сбора геометрии:
	 * лямбда захватывала бы {@code quads} и цвет, а поза к моменту отрисовки уже своя.
	 */
	private record QuadWriter(List<BakedQuad> quads, Spec spec, float unitsPerPixel) implements SubmitNodeCollector.CustomGeometryRenderer {

		@Override
		public void render(PoseStack.Pose pose, VertexConsumer buffer) {
			Matrix4f inverse = new Matrix4f(pose.pose());
			// См. applyScreenOffset: вырожденная матрица здесь — пропускаем обводку,
			// а не роняем кадр исключением из JOML
			if (Math.abs(inverse.determinant()) < 1.0e-12f) {
				return;
			}
			inverse.invert();

			if (spec.glow()) {
				int bands = glowBands(spec);
				for (int band = bands - 1; band >= 0; band--) {
					float spread = bandSpread(spec, band, bands);
					float fade = bandAlpha(spec, band, bands);
					if (fade > 0.01f) {
						writeRing(pose, buffer, inverse, spread, fade);
					}
				}
			}

			if (spec.ring()) {
				writeRing(pose, buffer, inverse, Math.max(1, spec.thickness()), 1.0f);
			}
		}

		private void writeRing(PoseStack.Pose pose, VertexConsumer buffer, Matrix4f inverse, float radiusPx, float fade) {
			// Шаг 2 для мягких полос и шаг 1 для плотного кольца — см. outlineArm
			int step = fade >= 1.0f ? 1 : 2;
			for (int direction = 0; direction < DIRECTIONS; direction += step) {
				Vector3f offset = localOffset(inverse, DIRECTION_X[direction] * radiusPx, DIRECTION_Y[direction] * radiusPx);
				int color = colorFor(spec, direction, fade);

				for (BakedQuad quad : quads) {
					int stepX = quad.direction().getStepX();
					int stepY = quad.direction().getStepY();
					int stepZ = quad.direction().getStepZ();

					for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
						Vector3fc position = quad.position(vertex);
						buffer.addVertex(pose,
								position.x() + offset.x,
								position.y() + offset.y,
								position.z() + offset.z)
								.setColor(color)
								.setUv(0.0f, 0.0f)
								.setOverlay(OverlayTexture.NO_OVERLAY)
								.setLight(FULL_LIGHT)
								// Нормаль грани: у emissive-пайплайна на неё не смотрим,
								// но формат вершин требует, чтобы она была настоящая
								.setNormal(pose, stepX, stepY, stepZ);
					}
				}
			}
		}

		/** Экранный сдвиг (пиксели) + уход от камеры — в локальные координаты квадов. */
		private Vector3f localOffset(Matrix4f inverse, float screenXPx, float screenYPx) {
			Vector3f dest = new Vector3f();
			inverse.transformDirection(screenXPx * unitsPerPixel, screenYPx * unitsPerPixel, -DEPTH_BIAS, dest);
			// Сдвиг в локальном масштабе модели: unitsPerPixel посчитан для пространств
			// камеры, а квады предмета лежат в юнитах модели (1/16 блока) — обратная
			// матрица это и учитывает, поэтому дополнительно масштаб не применяем
			return dest;
		}
	}
}

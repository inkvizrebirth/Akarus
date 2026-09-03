package com.akarus.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Отрисовка контура вокруг руки и предмета от первого лица.
 *
 * Как это работает в 26.2
 * -----------------------
 * Руки от первого лица рисуются в собственный {@code SubmitNodeStorage}
 * (поле {@code handAndScreenSubmitNodeStorage} в {@code GameRenderer}), а его
 * диспетчер выполняет только фазы solid, translucent, afterTerrain и alwaysOnTop.
 * Фаза {@code outline} — та, куда игра складывает свечение от
 * {@code RenderTypes.outline(...)} — для рук не выполняется никогда: её исполняет
 * только {@code LevelRenderer} для сущностей мира.
 *
 * Поэтому контур здесь рисуется «в лоб»: та же геометрия отправляется ещё раз,
 * но с плоской белой текстурой и нужным цветом в вершинах. Она чуть больше самой
 * руки/предмета (масштаб из настройки «Толщина»), поэтому из-под модели торчит
 * только цветной ободок.
 *
 * Render type выбран {@code entityTranslucent}: у него включено смешивание, значит
 * геометрия попадёт в фазу {@code translucentModels} (для модели руки) и
 * {@code translucentCustomGeometry} (для предмета) — обе выполняются.
 */
public final class HandOutlineRenderer {

	/** Однотонная белая текстура 1×1 из ресурсов мода: цвет берётся из вершин. */
	public static final Identifier WHITE_TEXTURE =
			Identifier.fromNamespaceAndPath("akarus", "textures/hand_outline/white.png");

	/** Полный свет: контур не должен темнеть вместе с рукой. */
	private static final int FULL_LIGHT = 0xF000F0;

	private static volatile RenderType cachedType;

	private HandOutlineRenderer() {
	}

	/** Render type контура. Игра сама кеширует его по текстуре. */
	public static RenderType outlineType() {
		RenderType type = cachedType;
		if (type == null) {
			type = RenderTypes.entityTranslucent(WHITE_TEXTURE);
			cachedType = type;
		}
		return type;
	}

	/**
	 * Контур вокруг руки.
	 *
	 * @param arm   часть модели руки (правая или левая) — та же, что рисует игра
	 * @param scale во сколько раз силуэт больше руки: из этого получается толщина
	 */
	public static void outlineArm(ModelPart arm, PoseStack poseStack, OrderedSubmitNodeCollector collector, int color, float scale) {
		if (color == 0) {
			return;
		}
		poseStack.pushPose();
		if (scale != 1.0f) {
			poseStack.scale(scale, scale, scale);
		}
		// Тот же вызов, что делает ваниль, но с плоской текстурой и нашим цветом
		collector.submitModelPart(arm, poseStack, outlineType(), FULL_LIGHT, OverlayTexture.NO_OVERLAY, null, color, null);
		poseStack.popPose();
	}

	/**
	 * Контур вокруг предмета в руке.
	 *
	 * Предмет — это список запечённых квадов, а не часть модели, поэтому рисуем
	 * их вручную через {@code submitCustomGeometry}: вершины уже готовы, остаётся
	 * только передать цвет.
	 */
	public static void outlineItem(List<BakedQuad> quads, PoseStack poseStack, OrderedSubmitNodeCollector collector,
			int color, float scale) {
		if (color == 0 || quads == null || quads.isEmpty()) {
			return;
		}
		poseStack.pushPose();
		if (scale != 1.0f) {
			poseStack.scale(scale, scale, scale);
		}
		collector.submitCustomGeometry(poseStack, outlineType(), new QuadWriter(quads, color));
		poseStack.popPose();
	}

	/**
	 * Пишет квады предмета в буфер одним цветом.
	 *
	 * Вынесен в отдельный класс, потому что lambda здесь захватывала бы {@code quads}
	 * и {@code color}, а рендер происходит позже, в другом потоке кадра.
	 */
	private record QuadWriter(List<BakedQuad> quads, int color) implements SubmitNodeCollector.CustomGeometryRenderer {

		@Override
		public void render(PoseStack.Pose pose, VertexConsumer buffer) {
			for (BakedQuad quad : quads) {
				for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
					Vector3fc position = quad.position(vertex);
					buffer.addVertex(pose, position.x(), position.y(), position.z())
							.setColor(color)
							.setUv(0.0f, 0.0f)
							.setOverlay(OverlayTexture.NO_OVERLAY)
							.setLight(FULL_LIGHT)
							.setNormal(pose, 0.0f, 1.0f, 0.0f);
				}
			}
		}
	}
}

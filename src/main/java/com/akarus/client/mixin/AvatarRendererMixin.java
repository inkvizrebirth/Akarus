package com.akarus.client.mixin;

import com.akarus.client.render.HandOutlineRenderer;
import com.akarus.client.render.HandRenderHook;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Обводка руки от первого лица.
 *
 * {@code renderHand} вызывается ровно один раз на кадр на каждую руку —
 * и только из {@code ItemInHandRenderer.renderPlayerArm}, то есть только
 * от первого лица. Мы встаём в конец метода: рука уже нарисована, стек матриц
 * всё ещё в её системе координат, а нужная часть модели лежит прямо в аргументах,
 * поэтому остаётся отправить её в рендер ещё раз — чуть крупнее и одним цветом.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

	@Inject(method = "renderHand", at = @At("TAIL"))
	private void akarus$outlineHand(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			Identifier skinTexture, ModelPart arm, boolean hasSleeve, CallbackInfo ci) {

		int color = HandRenderHook.armOutlineColor();
		if (color == 0) {
			return;
		}
		HandOutlineRenderer.outlineArm(arm, poseStack, submitNodeCollector, color, HandRenderHook.outlineScale());
	}
}

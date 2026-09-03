package com.dreamcast.client.mixin;

import com.dreamcast.client.render.HandOutlineRenderer;
import com.dreamcast.client.render.HandOutlineRenderer.Spec;
import com.dreamcast.client.render.HandRenderHook;
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
 * {@code renderHand} вызывается ровно один раз на кадр на каждую руку — и только
 * из {@code ItemInHandRenderer#renderPlayerArm}, то есть только от первого лица.
 * Встаём в конец метода: рука уже нарисована, стек матриц всё ещё лежит в системе
 * координат руки (сама {@code renderHand} ничего в стеке не оставляет), поэтому
 * оттуда удобно брать направление «вправо/вверх по экрану» для копий обводки.
 *
 * Модели при этом не масштабируются — см. {@link HandOutlineRenderer}: copies
 * сдвигаются в плоскости экрана и чуть-чуть от камеры, и там, где есть рука,
 * она перекрывает копию по глубине.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

	@Inject(method = "renderHand", at = @At("TAIL"))
	private void dreamcast$outlineHand(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			Identifier skinTexture, ModelPart arm, boolean hasSleeve, CallbackInfo ci) {

		Spec spec = HandRenderHook.armSpec();
		if (spec == null) {
			return;
		}
		HandOutlineRenderer.outlineArm(arm, poseStack, submitNodeCollector, spec);
	}
}

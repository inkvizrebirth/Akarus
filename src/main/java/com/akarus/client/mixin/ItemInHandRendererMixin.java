package com.akarus.client.mixin;

import com.akarus.client.render.HandRenderHook;
import com.akarus.client.viewmodel.ViewModelProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Раскладка рук от первого лица (модуль «ViewModel»).
 *
 * {@code submitArmWithItem} рисуёт одну руку: она сама кладёт в стек матриц
 * взмах, поворот и подъём, а в конце снимает их. Мы встаём до этого и добавляем
 * свои сдвиг, поворот и масштаб — тогда они применяются к руке целиком, вместе
 * с предметом в ней.
 *
 * Нюанс, из-за которого здесь два инджекта, а не один: в начале метода есть
 * ранний выход {@code if (player.isScoping()) return;}. Если бы мы просто
 * толкали матрицу в стек, она осталась бы там навсегда. Поэтому состояние
 * запоминаем в поле и проверяем тот же самый {@code isScoping()}.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

	@Unique
	private boolean akarus$transformApplied;

	@Inject(method = "submitArmWithItem", at = @At("HEAD"))
	private void akarus$applyViewModel(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
			float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {

		this.akarus$transformApplied = false;

		// Сообщаем модулям, что сейчас рисуется рука: по этому флагу включается обводка предмета
		HandRenderHook.begin(hand == InteractionHand.MAIN_HAND, attack);

		// Прицеливание игра обрабатывает сама и сразу выходит из метода — не мешаем
		if (player.isScoping()) {
			return;
		}

		ViewModelProfile profile = HandRenderHook.profile();
		if (profile == null) {
			return;
		}

		poseStack.pushPose();
		poseStack.translate(profile.getOffsetX(), profile.getOffsetY(), profile.getOffsetZ());
		poseStack.mulPose(Axis.XP.rotationDegrees(profile.getRotationX()));
		poseStack.mulPose(Axis.YP.rotationDegrees(profile.getRotationY()));
		poseStack.mulPose(Axis.ZP.rotationDegrees(profile.getRotationZ()));
		poseStack.scale(profile.getScale(), profile.getScale(), profile.getScale());
		this.akarus$transformApplied = true;
	}

	@Inject(method = "submitArmWithItem", at = @At("RETURN"))
	private void akarus$restoreViewModel(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
			float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {

		if (this.akarus$transformApplied) {
			poseStack.popPose();
			this.akarus$transformApplied = false;
		}
		HandRenderHook.end();
	}
}

package com.akarus.client.mixin;

import com.akarus.client.render.HandRenderHook;
import com.akarus.client.viewmodel.ViewModelProfile;
import com.mojang.blaze3d.vertex.PoseStack;
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
 * Раскладка рук от первого лица (модуль «ViewModel») и начало прохода обводки.
 *
 * {@code ItemInHandRenderer#renderArmWithItem} — единственная точка, где рисуются
 * обе руки от первого лица: и голая рука, и рука с предметом (а для карты — обе
 * сразу). Метод сам кладёт в стек матриц взмах, поворот и подъём, а в конце снимает
 * их. Мы встаём до этого и добавляем свои сдвиг, поворот и масштаб — тогда они
 * применяются к руке целиком, вместе с предметом в ней, и в системе координат руки.
 *
 * Нюанс, из-за которого здесь два инджекта, а не один: внутри метода есть ранний
 * выход на прицеливании ({@code if (!player.isScoping()) { ... }}). Если бы мы просто
 * толкали матрицу в начале, она осталась бы в стеке навсегда и унесла все последующие
 * кадры. Поэтому сами проверяем {@code isScoping()}, заводим флаг и по нему же
 * снимаем матрицу в {@code RETURN}.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

	@Unique
	private boolean akarus$transformApplied;

	@Inject(method = "renderArmWithItem", at = @At("HEAD"))
	private void akarus$applyViewModel(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
			float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {

		this.akarus$transformApplied = false;

		// Сообщаем модулям, что сейчас рисуется рука: по этому флагу включается обводка
		HandRenderHook.begin();

		// Прицеливание игра обрабатывает сама и сразу выходит из метода — не мешаем
		if (player.isScoping()) {
			return;
		}

		ViewModelProfile profile = HandRenderHook.profile();
		if (profile == null) {
			return;
		}

		profile.apply(poseStack, HandRenderHook.mirrored(player, hand == InteractionHand.MAIN_HAND));
		this.akarus$transformApplied = true;
	}

	@Inject(method = "renderArmWithItem", at = @At("RETURN"))
	private void akarus$restoreViewModel(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
			float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {

		if (this.akarus$transformApplied) {
			// removePose соответствует pushPose внутри ViewModelProfile#apply
			poseStack.popPose();
			this.akarus$transformApplied = false;
		}
		HandRenderHook.end();
	}
}

package com.akarus.client.mixin;

import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.FreeCamModule;
import com.akarus.client.module.impl.FreeLookModule;
import com.akarus.client.module.impl.NoFovModule;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перехват камеры — FreeCam и FreeLook.
 *
 * {@code Camera#update()} сначала выравнивает камеру по сущности (поворот и
 * интерполированная позиция), потом считает углы обзора, фрустум отсечения и
 * перспективу. Встаём в самый конец {@code alignWithEntity}: к моменту, когда всё
 * это считается, камера уже стоит там, где захотел пользователь. Дальше игра сама
 * корректно отсекает чанки, расставляет звук и рисует мир — с нашей точки.
 *
 * Поворот при этом остаётся «игровым»: мышь двигает голову игрока, поэтому направление
 * взгляда совпадает у камеры, у прицела и у того, что видит сервер. Отсюда и корректное
 * поведение на серверах: клиент ни в одном пакете не врёт.
 *
 * {@code require = 0} — если в следующей версии метод переименуют, игра просто перестанет
 * поддерживать свободную камеру, а не упадёт на старте.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"), require = 0)
	private void akarus$overrideCamera(float partialTicks, CallbackInfo ci) {
		// FreeCam важнее: это «полёт» камеры, орбита поверх него не имеет смысла
		Vec3 freeCam = FreeCamModule.cameraPosition(partialTicks);
		if (freeCam != null) {
			this.setPosition(freeCam);
			return;
		}

		Vec3 freeLook = FreeLookModule.cameraPosition(partialTicks);
		if (freeLook != null) {
			this.setPosition(freeLook);
			// FreeLook-камера всегда смотрит на игрока: её поворот — это наши углы
			this.setRotation(FreeLookModule.cameraYaw(), FreeLookModule.cameraPitch());
		}
	}

	/**
	 * Пока работает FreeLook, камера «отцеплена» от игрока — иначе игра считала бы
	 * нас от первого лица: рука висела бы в воздухе без тела, а сам игрок не
	 * рисовался бы вовсе.
	 */
	@Inject(method = "isDetached", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$showPlayerBody(CallbackInfoReturnable<Boolean> cir) {
		if (FreeLookModule.active()) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * NoFOV: фиксированный угол обзора. {@code Camera#getFov} — последнее звено
	 * цепочки «опции → модификаторы (спринт, лук, вода, смерть) → проекция»,
	 * поэтому здесь достаточно вернуть наше значение, и картинка перестаёт
	 * «дышать» независимо от состояния игрока.
	 */
	@Inject(method = "getFov", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$staticFov(CallbackInfoReturnable<Float> cir) {
		NoFovModule module = ModuleManager.find(NoFovModule.class);
		if (module != null && module.isEnabled()) {
			cir.setReturnValue((float) module.getFov());
		}
	}
}

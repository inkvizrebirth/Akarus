package com.akarus.client.mixin;

import com.akarus.client.module.impl.FreeCamModule;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехват позиции камеры — то, ради чего существует FreeCam.
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

	@Inject(method = "alignWithEntity", at = @At("TAIL"), require = 0)
	private void akarus$useFreeCamPosition(float partialTicks, CallbackInfo ci) {
		Vec3 target = FreeCamModule.cameraPosition(partialTicks);
		if (target != null) {
			this.setPosition(target);
		}
	}
}

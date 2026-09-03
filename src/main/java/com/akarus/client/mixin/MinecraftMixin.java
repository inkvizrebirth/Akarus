package com.akarus.client.mixin;

import com.akarus.client.module.impl.AutoWalkModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехват правой кнопки мыши.
 *
 * Пока включён AutoWalk, ПКМ принадлежит модулю: первым нажатием игрок ставит
 * точку назначения, вторым — отменяет маршрут. Чтобы в этот момент игра не
 * ставила блоки и не использовала предмет из руки, ванильный вызов
 * {@code Minecraft#startUseItem()} отменяется.
 *
 * Отменяется именно использование: сам факт нажатия модуль видит через
 * {@code options.keyUse.isDown()}, а атака (ЛКМ) не трогается.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void akarus$onStartUseItem(CallbackInfo callbackInfo) {
		if (AutoWalkModule.isInterceptingUse()) {
			callbackInfo.cancel();
		}
	}
}

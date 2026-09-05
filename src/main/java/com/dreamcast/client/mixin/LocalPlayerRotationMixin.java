package com.dreamcast.client.mixin;

import com.dreamcast.client.rotation.RotationManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * «Сайлент»-повороты: подмена угла ровно на время пакета движения.
 *
 * <p>{@code LocalPlayer#sendPosition()} — единственное место, где клиент собирает
 * {@code ServerboundMovePlayerPacket} (Pos / Rot / PosRot / StatusOnly) и решает,
 * надо ли вообще слать поворот (ваниль сравнивает {@code yRotLast/xRotLast}).
 * Всё остальное — движение, камера, тряска, точка прицела — считается ДО или ПОСЛЕ,
 * поэтому если подменить углы игрока между HEAD и TAIL этого метода, то:</p>
 * <ul>
 *   <li>сервер увидит прицел ауры (значит reach и angle-проверки Grim/Matrix
 *       проходят честно — удар летит туда, куда «смотрит» сервер);</li>
 *   <li>камера игрока не дёрнется ни на градус: {@code player.setYRot} вызывается
 *       и тут же откатывается обратно, между кадрами никто этот угол не видит;</li>
 *   <li>движение не ломается: игрок идёт туда, куда смотрит сам, а не туда, куда
 *       навела аура (вот почему больше не нужен «доворот ввода»);</li>
 *   <li>нет и «мигания» углов: {@code yRotLast} обновляется уже нашим значением, и
 *       в следующем тике аура выдаёт тот же угол, поэтому сравнение «изменился ли
 *       поворот» не дёргает сервер туда-сюда каждый тик.</li>
 * </ul>
 *
 * <p>{@code require = 0}: если в следующем обновлении игры метод переименуют,
 * миксин просто не применится — а {@code RotationManager} это заметит (он ждёт
 * вызова {@code beginPacket}) и начнёт сам слать пакет поворота перед действиями.
 * Молча «немного врущая камера» хуже, чем честный fallback.</p>
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerRotationMixin {

	@Inject(method = "sendPosition", at = @At("HEAD"), require = 0)
	private void dreamcast$applySilentRotation(CallbackInfo ci) {
		RotationManager.beginPacket();
	}

	@Inject(method = "sendPosition", at = @At("TAIL"), require = 0)
	private void dreamcast$restoreCameraRotation(CallbackInfo ci) {
		RotationManager.endPacket();
	}
}

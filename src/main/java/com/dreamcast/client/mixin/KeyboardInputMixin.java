package com.dreamcast.client.mixin;

import com.dreamcast.client.module.impl.FreeCamModule;
import com.dreamcast.client.module.impl.KillAuraModule;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Заморозка ввода игрока во время свободной камеры.
 *
 * <p><b>Почему миксин наследует {@code ClientInput}.</b> В 26.2 поля {@code keyPresses}
 * и {@code moveVector} объявлены не в {@code KeyboardInput}, а в его родителе
 * {@code ClientInput}. {@code @Shadow} по чужому родителю не работает («was not
 * located in the target class»), поэтому вместо теней миксин просто расширяет
 * родительский класс цели — поля доступны напрямую, а Mixin корректно проверяет
 * иерархию: родитель миксина обязан быть суперклассом цели или им самим.
 *
 * <p>Из этих двух полей игрок узнаёт, куда идти, бежать, прыгать и красться.
 * Обнуляем их сразу после того, как игра их посчитала:
 *
 * <ul>
 *   <li>игрок стоит на месте, не спринтит и не топочет ногами;</li>
 *   <li>сами клавиши остаются «нажатыми» для игры — на них читает FreeCam, поэтому
 *       камера продолжает лететь, пока кнопка удерживается;</li>
 *   <li>пакеты о состоянии игрока (бег, приседание, прыжок) не отправляются —
 *       сервер видит человека, который спокойно стоит там, где его оставили.</li>
 * </ul>
 *
 * <p>Ни {@code mayfly}, ни режим полёта, ни noClip игроку не выдаются — именно поэтому
 * такой фрикам живёт на серверах, а «полетай игрока» — нет.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void dreamcast$standStill(CallbackInfo ci) {
		if (FreeCamModule.freezesInput()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
			return;
		}

		// «Свободная» коррекция движений киллауры: ввод разворачивается так,
		// будто камера не наводилась аурой — W ведёт игрока по его взгляду
		Vec2 corrected = KillAuraModule.correctedMovement(this.moveVector);
		if (corrected != null) {
			this.moveVector = corrected;
		}
	}
}

package com.akarus.client.mixin;

import com.akarus.client.module.impl.FreeCamModule;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Заморозка ввода игрока во время свободной камеры.
 *
 * {@code KeyboardInput#tick()} перекладывает нажатые клавиши в {@code keyPresses}
 * и {@code moveVector} — из этих двух полей игрок узнаёт, куда идти, бежать,
 * прыгать и красться. Обнуляем их сразу после того, как игра их посчитала:
 *
 * <ul>
 *   <li>игрок стоит на месте, не спринтит и не топочет ногами;</li>
 *   <li>сами клавиши остаются «нажатыми» для игры — на них читает FreeCam, поэтому
 *       камера продолжает лететь, пока кнопка удерживается;</li>
 *   <li>пакеты о состоянии игрока (бег, приседание, прыжок) не отправляются —
 *       сервер видит человека, который спокойно стоит там, где его оставили.</li>
 * </ul>
 *
 * Ни {@code mayfly}, ни режим полёта, ни noClip игроку не выдаются — именно поэтому
 * такой фрикам живёт на серверах, а «полетай игрока» — нет.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

	@Shadow
	public Input keyPresses;

	@Shadow
	protected Vec2 moveVector;

	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void akarus$standStill(CallbackInfo ci) {
		if (FreeCamModule.freezesInput()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
		}
	}
}

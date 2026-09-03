package com.dreamcast.client.mixin;

import com.dreamcast.client.module.impl.EspModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ESP (Glow): говорим игре, что цель подсвечена.
 *
 * {@code Minecraft#shouldEntityAppearGlowing} — единственная точка, из которой
 * ванильный контур-обводка узнаёт, кого обводить (сюда же попадает ванильное
 * свечение от эффекта и аутлайн наблюдателя). Пока у сущности сохраняется
 * собственное свечение, не отбираем его — добавляем своё сверху.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$espGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (entity == null) {
			return;
		}
		if (entity.isCurrentlyGlowing()) {
			return;
		}
		if (EspModule.wantsGlow(entity)) {
			cir.setReturnValue(true);
		}
	}
}

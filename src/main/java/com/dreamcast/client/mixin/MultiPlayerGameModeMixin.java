package com.dreamcast.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хук атаки игрока: точка, где засчитывается удар по сущности.
 *
 * Здесь стартуют HitSounds и HitParticles (в т.ч. для атак KillAura —
 * модуль бьёт тем же gameMode#attack). Слушатели сами фильтруют цели.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

	@Inject(method = "attack", at = @At("TAIL"), require = 0)
	private void dreamcast$onAttack(LocalPlayer player, Entity target, CallbackInfo ci) {
		com.dreamcast.client.module.impl.HitParticlesModule.onAttack(player, target);
		com.dreamcast.client.module.impl.HitSoundsModule.onAttack(player, target);
	}
}

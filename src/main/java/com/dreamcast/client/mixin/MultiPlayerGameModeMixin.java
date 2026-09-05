package com.dreamcast.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
	private void dreamcast$onAttack(Player player, Entity target, CallbackInfo ci) {
		// В 26.2 сигнатура MultiPlayerGameMode#attack принимает базовый Player,
		// хотя в клиенте сюда приходит LocalPlayer. LocalPlayer в дескрипторе
		// ломал трансформацию класса при логине и давал Network Protocol Error.
		if (player instanceof LocalPlayer localPlayer) {
			com.dreamcast.client.module.impl.HitParticlesModule.onAttack(localPlayer, target);
			com.dreamcast.client.module.impl.HitSoundsModule.onAttack(localPlayer, target);
		}
	}
}

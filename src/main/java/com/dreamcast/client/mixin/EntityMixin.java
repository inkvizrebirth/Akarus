package com.dreamcast.client.mixin;

import com.dreamcast.client.module.impl.EspModule;
import com.dreamcast.client.util.RotationHumanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * «Человечные» довороты для легитных режимов AutoMine и KillAura.
 *
 * {@code Entity#setRot(float, float)} — точка, через которую Baritone выставляет
 * игроку абсолютные углы поворота. Пока работает легитный AutoMine, «скачковые»
 * запросы (сразу на десятки градусов — Baritone мгновенно наводится на блок)
 * заменяются плавным доворотом с промахом — вся математика в
 * {@link RotationHumanizer}. Обычная мышь и мелкие доводки проходят насквозь.
 *
 * Миксин висит на всех сущностях, но работает только с нашим игроком — для остальных
 * это один дешёвый if.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

	@Shadow
	protected abstract void setRot(float yRot, float xRot);

	/** Защита от рекурсии: наш собственный вызов setRot должен пройти как есть. */
	@Unique
	private boolean dreamcast$writingHumanized;

	/**
	 * NoWeb: паутина «приклеивает» игрока через makeStuckInBlock. Пока модуль
	 * включён — просто не даём ей выставить замедление; для всех остальных
	 * блоков и сущностей вызов проходит как есть.
	 */
	@Inject(method = "makeStuckInBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$noWeb(net.minecraft.world.level.block.state.BlockState state,
	                              net.minecraft.world.phys.Vec3 speedMultiplier, CallbackInfo ci) {
		if ((Object) this instanceof net.minecraft.client.player.LocalPlayer player) {
			com.dreamcast.client.module.impl.NoSlowModule noSlow =
					com.dreamcast.client.module.ModuleManager.find(com.dreamcast.client.module.impl.NoSlowModule.class);
			if (noSlow != null && noSlow.isEnabled() && noSlow.noWebEnabled()
					&& state.is(net.minecraft.world.level.block.Blocks.COBWEB)) {
				ci.cancel();
			}
		}
	}

	@Inject(method = "setRot", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$humanizeRotation(float yRot, float xRot, CallbackInfo ci) {
		if (this.dreamcast$writingHumanized) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player != (Object) this) {
			return;
		}
		LocalPlayer player = client.player;

		// Реальный поворот передаём слою: на первом вызове после включения доворот
		// должен стартовать от того, куда игрок смотрит сейчас
		float[] adjusted = RotationHumanizer.adjust(yRot, xRot, player.getYRot(), player.getXRot());
		if (adjusted == null) {
			return;
		}

		this.dreamcast$writingHumanized = true;
		try {
			this.setRot(adjusted[0], adjusted[1]);
		} finally {
			this.dreamcast$writingHumanized = false;
		}
		ci.cancel();
	}

	/**
	 * ESP (Glow): цвет обводки подсвеченных сущностей.
	 *
	 * Контур свечения красится в цвет команды сущности — это единственный вход,
	 * через который ваниль узнаёт цвет обводки. Для наших целей из {@link EspModule}
	 * прилетает свой цвет (в том числе радуга и градиент), у всех остальных
	 * сущностей цвет остаётся игровым. Вызывается на потоке игры при извлечении
	 * рендер-стейта сущности — то есть ровно один раз на сущность в кадр.
	 */
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$espGlowColor(CallbackInfoReturnable<Integer> cir) {
		Entity self = (Entity) (Object) this;
		int color = EspModule.glowColor(self);
		if (color != 0) {
			cir.setReturnValue(color);
		}
	}
}

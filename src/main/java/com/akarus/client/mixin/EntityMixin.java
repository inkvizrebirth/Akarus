package com.akarus.client.mixin;

import com.akarus.client.util.RotationHumanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
	private boolean akarus$writingHumanized;

	@Inject(method = "setRot", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$humanizeRotation(float yRot, float xRot, CallbackInfo ci) {
		if (this.akarus$writingHumanized) {
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

		this.akarus$writingHumanized = true;
		try {
			this.setRot(adjusted[0], adjusted[1]);
		} finally {
			this.akarus$writingHumanized = false;
		}
		ci.cancel();
	}
}

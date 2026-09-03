package com.akarus.client.mixin;

import com.akarus.client.module.impl.FreeLookModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Мышь во время FreeLook крутит камеру, а не игрока.
 *
 * {@code MouseHandler#turnPlayer} — место, где игра перекладывает движение мыши в
 * поворот игрока (с учётом чувствительности из настроек). Мы запоминаем поворот до
 * вызова, смотрим, на сколько игрок повернулся после, и если FreeLook активен —
 * откатываем игроку его поворот, а дельту отдаём камере:
 * <ul>
 *   <li>чувствительность и плавность — ровно игровые, дельту посчитала сама игра;</li>
 *   <li>игрок не поворачивается ни на градус — Baritone, который во время добычи
 *       управляет поворотом сам, ничего не замечает и с мышью не конфликтует;</li>
 *   <li>FreeLook — чистый рендер: ни одного лишнего пакета не уходит.</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

	@Unique
	private float akarus$yawBeforeTurn;
	@Unique
	private float akarus$pitchBeforeTurn;

	@Inject(method = "turnPlayer", at = @At("HEAD"), require = 0)
	private void akarus$rememberPlayerRotation(CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null) {
			return;
		}
		this.akarus$yawBeforeTurn = player.getYRot();
		this.akarus$pitchBeforeTurn = player.getXRot();
	}

	@Inject(method = "turnPlayer", at = @At("TAIL"), require = 0)
	private void akarus$turnFreeLookCamera(CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null) {
			return;
		}

		float deltaYaw = player.getYRot() - this.akarus$yawBeforeTurn;
		float deltaPitch = player.getXRot() - this.akarus$pitchBeforeTurn;
		if (!FreeLookModule.absorbMouseLook(deltaYaw, deltaPitch)) {
			return;
		}

		// Камера забрала дельту — возвращаем игроку его прежний поворот
		player.setYRot(this.akarus$yawBeforeTurn);
		player.setXRot(this.akarus$pitchBeforeTurn);
	}
}

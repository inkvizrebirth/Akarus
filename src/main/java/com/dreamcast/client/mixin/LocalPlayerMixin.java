package com.dreamcast.client.mixin;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.NoSlowModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoSlow: подмена множителя скорости в {@code LocalPlayer#modifyInput(Vec2)}.
 *
 * Ваниль урезает ввод при использовании предмета. Мы воспроизводим весь метод,
 * но с нашим множителем: keepSlow = 0% → полное движение, 100% → ваниль.
 * Остальная логика (0.98-коэффициент, приседание, нормализация «квадрата»)
 * скопирована дословно, чтобы не менять ощущение ходьбы.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

	@Inject(method = "modifyInput", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$noSlow(Vec2 input, CallbackInfoReturnable<Vec2> cir) {
		NoSlowModule noSlow = ModuleManager.find(NoSlowModule.class);
		if (noSlow == null || !noSlow.isEnabled() || noSlow.keepSlowFraction() >= 1.0f) {
			return; // ванильный путь
		}
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (!self.isUsingItem() || self.isPassenger()) {
			return;
		}
		if (!noSlow.appliesTo(self.getUseItem())) {
			return;
		}

		float mult = self.getUseItem()
				.getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS,
						net.minecraft.world.item.component.UseEffects.DEFAULT)
				.speedMultiplier();
		// Ослабляем ванильный множитель до выбранной доли: 1.0 = без замедления
		float modified = 1.0f + (mult - 1.0f) * noSlow.keepSlowFraction();

		Vec2 newInput = input.scale(0.98F).scale(modified);
		if (self.isMovingSlowly()) {
			float sneaking = (float) self.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED);
			newInput = newInput.scale(sneaking);
		}
		cir.setReturnValue(dreamcast$normalizeSquare(newInput));
	}

	/** Копия приватного {@code modifyInputSpeedForSquareMovement} из LocalPlayer. */
	@Unique
	private static Vec2 dreamcast$normalizeSquare(Vec2 input) {
		float length = input.length();
		if (length <= 0.0F) {
			return input;
		}
		Vec2 direction = input.scale(1.0F / length);
		float distanceToUnitSquare = dreamcast$distanceToUnitSquare(direction);
		float modifiedLength = Math.min(length * distanceToUnitSquare, 1.0F);
		return direction.scale(modifiedLength);
	}

	/** Копия приватного {@code distanceToUnitSquare} из LocalPlayer. */
	@Unique
	private static float dreamcast$distanceToUnitSquare(Vec2 direction) {
		float directionX = Math.abs(direction.x);
		float directionY = Math.abs(direction.y);
		float tan = directionY > directionX ? directionX / directionY : directionY / directionX;
		return Mth.sqrt(1.0F + Mth.square(tan));
	}
}

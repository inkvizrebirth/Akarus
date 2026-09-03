package com.akarus.client.mixin;

import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.NoBlindModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoBlind: оверлеи HUD, которые можно выключить.
 *
 * В 26.2 оверлеи экрана собирает {@code Hud}: {@code extractCameraOverlays}
 * рисует в том числе огонь при горении, {@code extractConfusionOverlay} —
 * наложение тошноты. Пока включён соответствующий флаг NoBlind, эти вызовы
 * просто отменяются (остальное — хотбар, сердца, чат — не трогается).
 */
@Mixin(Hud.class)
public abstract class HudMixin {

	@Inject(method = "extractCameraOverlays", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$hideFireOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		NoBlindModule module = ModuleManager.find(NoBlindModule.class);
		if (module == null || !module.isEnabled() || !module.hidesFire()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null && client.player.isOnFire()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$hideConfusionOverlay(GuiGraphicsExtractor graphics, float partialTicks, CallbackInfo ci) {
		NoBlindModule module = ModuleManager.find(NoBlindModule.class);
		if (module != null && module.isEnabled() && module.hidesNausea()) {
			ci.cancel();
		}
	}
}

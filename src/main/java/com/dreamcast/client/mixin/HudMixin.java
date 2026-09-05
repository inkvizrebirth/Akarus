package com.dreamcast.client.mixin;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.NoBlindModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoBlind: наложение тошноты.
 *
 * <p>{@code Hud#extractConfusionOverlay} рисует «кашу» от Nausea прямо поверх
 * экрана — отменяем её, когда включён соответствующий флаг. Второй слой защиты —
 * {@code GameRendererMixin}, который гасит сам постэффект: какой-то из двух
 * обязательно сработает, а двойная отмена безвредна.</p>
 *
 * <p>Огонь на экране тут не трогается: в 26.2 он рисуется через
 * {@code ScreenEffectRenderer#submitFire} (см. {@code ScreenEffectRendererMixin}).
 * Раньше его «выключали» отменой всего {@code extractCameraOverlays} — это убивало
 * виньетку, портал и spyglass, а пелену огня оставляло.</p>
 */
@Mixin(Hud.class)
public abstract class HudMixin {

	@Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$hideConfusionOverlay(GuiGraphicsExtractor graphics, float partialTicks, CallbackInfo ci) {
		NoBlindModule module = ModuleManager.find(NoBlindModule.class);
		if (module != null && module.isEnabled() && module.hidesNausea()) {
			ci.cancel();
		}
	}
}

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
 * NoBlind: оверлей тошноты, который собирает HUD.
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

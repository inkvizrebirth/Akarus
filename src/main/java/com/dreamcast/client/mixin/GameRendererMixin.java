package com.dreamcast.client.mixin;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.NoBlindModule;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoBlind: отключение «тошноты».
 *
 * В 26.2 эффект тошноты рисуется постэффектом: {@code GameRenderer} держит
 * идентификатор активного постэффекта и применяет его при отрисовке. Пока
 * включён NoBlind с опцией «Тошнота», активный постэффект-наusea подменяется
 * на «нет» — экран не крутит и не размывает. Остальные постэффекты
 * (например, от espectator-мобов) не трогаются.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Shadow
	private Identifier postEffectId;

	@Inject(method = "currentPostEffect", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$hideNausea(CallbackInfoReturnable<Identifier> cir) {
		NoBlindModule module = ModuleManager.find(NoBlindModule.class);
		if (module == null || !module.isEnabled() || !module.hidesNausea()) {
			return;
		}
		if (this.postEffectId != null && this.postEffectId.getPath().contains("nausea")) {
			cir.setReturnValue(null);
		}
	}
}

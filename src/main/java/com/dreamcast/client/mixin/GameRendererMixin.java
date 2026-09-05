package com.dreamcast.client.mixin;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.MotionBlurModule;
import com.dreamcast.client.module.impl.NoBlindModule;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Пост-эффекты: «тошнота» (NoBlind) и размытие в движении (MotionBlur).
 *
 * <p>В 26.2 экранные искажения — это пост-цепочки: {@code GameRenderer} держит
 * идентификатор активного эффекта ({@code postEffectId} + флаг
 * {@code effectActive}), а {@code render} каждый кадр прогоняет через
 * {@code ShaderManager#getPostChain} основной таргет. Отсюда обе наши правки:</p>
 *
 * <ul>
 *   <li>NoBlind: пока включён флаг «Тошнота», {@link #currentPostEffect()}
 *       отдаёт {@code null} — эффект не применяется, остальные цепочки
 *       (creeper/spider/invert от мобов) не трогаются;</li>
 *   <li>MotionBlur: навязываем свою цепочку в конце
 *       {@code checkEntityPostEffect} — ваниль вызывает его каждый кадр и в
 *       противном случае сама же обнуляет {@code postEffectId} («под камеру нет
 *       моба с постэффектом»), так что «применить один раз при включении» не
 *       работает.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Shadow
	private Identifier postEffectId;

	/** Ванильный флаг «есть активный эффект»: без него {@code render} цепочку пропустит. */
	@Shadow
	private boolean effectActive;

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

	@Inject(method = "checkEntityPostEffect", at = @At("RETURN"), require = 0)
	private void dreamcast$applyMotionBlur(Entity viewed, CallbackInfo ci) {
		MotionBlurModule module = ModuleManager.find(MotionBlurModule.class);
		if (module == null) {
			return;
		}
		Identifier chain = module.chainId();
		if (chain != null && !chain.equals(this.postEffectId)) {
			this.postEffectId = chain;
			this.effectActive = true;
		}
	}
}

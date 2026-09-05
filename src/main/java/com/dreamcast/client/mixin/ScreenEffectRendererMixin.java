package com.dreamcast.client.mixin;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.NoBlindModule;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoBlind: огонь на экране.
 *
 * <p>В 26.2 «что под глазами» (вода, блок, пламя) собирается не в HUD, а в
 * пайплайн отрисовки мира: {@link net.minecraft.client.renderer.ScreenEffectRenderer}
 * кладёт полноэкранный квад текста {@code misc/fire} через {@code submitFire}.
 * {@code Hud#extractCameraOverlays} к огню отношения уже не имеет — там виньетка,
 * портал,spyglass и powder snow, их заглушать нельзя.</p>
 *
 * <p>Отменяем ровно один вызов — {@code submitFire}. Огонь на самих моделях
 * (горящий моб/игрок) остаётся: мы убираем только пелену перед камерой, как и
 * обещает описание модуля.</p>
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true, require = 0)
	private static void dreamcast$hideFire(PoseStack poseStack, SubmitNodeCollector collector,
	                                       TextureAtlasSprite sprite, CallbackInfo ci) {
		NoBlindModule module = ModuleManager.find(NoBlindModule.class);
		if (module != null && module.isEnabled() && module.hidesFire()) {
			ci.cancel();
		}
	}
}

package com.dreamcast.client.mixin;

import com.dreamcast.client.shader.PostFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Точка входа нашего рантайм-«Сатина» в ванильный конвейер пост-обработки.
 *
 * <p>{@code GameRenderer} каждый кадр спрашивает у {@code ShaderManager} цепочку
 * по {@code postEffectId} и, если получил не-null, сам вызывает
 * {@code PostChain#process(mainRenderTarget, resourcePool)}. Значит нам не нужно
 * ни управлять целями, ни трогать кадр-граф: достаточно на этот вопрос ответить
 * своей собранной цепочкой — и ваниль отрендерит её как свой же «nausea»-эффект.</p>
 *
 * <p>Заодно миксин отдаёт {@link PostFx} контекст ({@code Projection} и
 * {@code ProjectionMatrixBuffer} пост-цепочек — приватные поля менеджера, без них
 * {@code PostChain.load} не собрать) и сбрасывает наши цепочки при перезагрузке
 * ресурсов и на выключении шейдеров, где ваниль закрывает буферы.</p>
 *
 * <p>Все {@code require = 0}: если в следующей версии поля переименуют, игра
 * должна остаться играбельной — модули в этом случае просто откатятся на
 * JSON-цепочки из {@code assets/dreamcast/post_effect/}.</p>
 */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {

	@Shadow
	@Final
	private Projection postChainProjection;

	@Shadow
	@Final
	private ProjectionMatrixBuffer postChainProjectionMatrixBuffer;

	@Inject(method = "getPostChain", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$runtimeChain(Identifier id, Set<Identifier> externalTargets,
										CallbackInfoReturnable<PostChain> cir) {
		if (!PostFx.owns(id)) {
			return;
		}
		PostFx.setContext(new PostFx.Context() {
			@Override
			public TextureManager textures() {
				return Minecraft.getInstance().getTextureManager();
			}

			@Override
			public Projection projection() {
				return postChainProjection;
			}

			@Override
			public ProjectionMatrixBuffer projectionBuffer() {
				return postChainProjectionMatrixBuffer;
			}
		});
		// Возвращаем и в случае неудачи тоже: пусть ваниль не пытается загрузить
		// несуществующий dreamcast-конфиг (иначе она зря зовёт recovery на каждый кадр).
		cir.setReturnValue(PostFx.resolve(id));
	}

	/**
	 * Перезагрузка ресурсов (F3+T, смена ресурспака): ваниль пересоздаёт свои
	 * цепочки, наши — тоже должны пересобраться, иначе останутся с шейдерами
	 * и целями из уже закрытого набора.
	 */
	@Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;"
			+ "Lnet/minecraft/server/packs/resources/ResourceManager;"
			+ "Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("TAIL"), require = 0)
	private void dreamcast$rebuildAfterReload(CallbackInfo ci) {
		PostFx.invalidateAll();
	}

	/** На выключении клиента ваниль закрывает свои буферы — наши цепочки к тому моменту уже не нужны. */
	@Inject(method = "close", at = @At("HEAD"), require = 0)
	private void dreamcast$release(CallbackInfo ci) {
		PostFx.invalidateAll();
	}
}

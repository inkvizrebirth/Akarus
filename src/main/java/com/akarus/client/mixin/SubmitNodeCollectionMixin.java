package com.akarus.client.mixin;

import com.akarus.client.render.HandOutlineRenderer;
import com.akarus.client.render.HandOutlineRenderer.Spec;
import com.akarus.client.render.HandRenderHook;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Обводка предмета в руке от первого лица.
 *
 * Ванильный канал обводки здесь не годится: {@code submitItem} кладёт её в фазу
 * {@code outline}, а руки от первого лица рисуются в отдельном контейнере, чей
 * диспетчер эту фазу не выполняет. Поэтому контур предмета дорисовываем сами —
 * теми же квадами, сдвинутыми по кругу (см. {@link HandOutlineRenderer}).
 *
 * Метод вызывается для всех предметов вообще, поэтому работаем только когда
 * {@link HandRenderHook} сообщает, что прямо сейчас идёт отрисовка рук
 * от первого лица.
 */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin implements OrderedSubmitNodeCollector {

	@Inject(method = "submitItem", at = @At("TAIL"), require = 0)
	private void akarus$outlineItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
			int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
			ItemStackRenderState.FoilType foilType, CallbackInfo ci) {

		Spec spec = HandRenderHook.itemSpec();
		if (spec == null) {
			return;
		}
		HandOutlineRenderer.outlineItem(quads, poseStack, this, spec);
	}
}

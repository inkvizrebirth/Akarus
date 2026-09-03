package com.akarus.client.mixin;

import com.akarus.client.gui.hud.HudLayout;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Перетаскивание элементов HUD с открытым чатом.
 *
 * Клик по элементу HUD (рамки публикуются каждый кадр рендером HUD) начинает
 * перетаскивание и «съедается», чтобы чат не подумал, что это выделение текста.
 * Дальше рендер HUD сам ведёт элемент за курсором и отпустит его, когда
 * кнопка мыши отожмётся.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$startHudDrag(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (event.button() == 0 && HudLayout.startDrag(event.x(), event.y())) {
			cir.setReturnValue(true);
		}
	}
}

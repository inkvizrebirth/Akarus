package com.dreamcast.client.mixin;

import com.dreamcast.client.module.impl.CustomGuiModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Оформление окон контейнеров: свои плитки слотов, акцентная рамка и заголовок.
 *
 * <p>Логику не трогаем вообще — только отрисовку. Ванильные {@code renderSlots}
 * (точнее, их современный эквивалент {@code extractSlots}) продолжают рисовать
 * предметы, а мы подменяем «косметику»: подсветку слота (back — под предметом,
 * front — поверх) и подпись окна. Так плавильня остаётся плавильней со своей
 * стрелкой, а житель — с ползунком.</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerStyleMixin {

	@Shadow
	protected int leftPos;

	@Shadow
	protected int topPos;

	@Shadow
	protected int imageWidth;

	@Shadow
	protected int imageHeight;

	@Shadow
	protected Slot hoveredSlot;

	/** Окно, из которого mixin читает список слотов: поле protected, геттера нет. */
	@Shadow
	protected net.minecraft.world.inventory.AbstractContainerMenu menu;

	@Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$slots(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (!CustomGuiModule.wantsSlots() || CustomGuiModule.isOwnScreen(this)) {
			return;
		}
		for (Slot slot : this.menu.slots()) {
			if (!slot.isActive()) {
				continue;
			}
			CustomGuiModule.drawSlot(graphics, this.leftPos + slot.x, this.topPos + slot.y,
					slot == this.hoveredSlot);
		}
		ci.cancel();
	}

	@Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$slotFront(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (!CustomGuiModule.wantsSlots() || CustomGuiModule.isOwnScreen(this)) {
			return;
		}
		// передний контур рисует сам drawSlot (он учитывает hovered), а ванильный
		// спрайт поверх предмета только пачкает картинку
		ci.cancel();
	}

	@Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$title(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!CustomGuiModule.wantsTitles() || CustomGuiModule.isOwnScreen(this)) {
			return;
		}
		CustomGuiModule.drawTitle(graphics,
				CustomGuiModule.readableName((Object) this), this.leftPos, this.topPos, this.imageWidth);
		ci.cancel();
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
	private void dreamcast$frame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
	                              CallbackInfo ci) {
		if (CustomGuiModule.isOwnScreen(this)) {
			return;
		}
		CustomGuiModule.drawFrame(graphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);
	}
}

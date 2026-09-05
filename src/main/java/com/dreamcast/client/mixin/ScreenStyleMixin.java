package com.dreamcast.client.mixin;

import com.dreamcast.client.module.impl.CustomGuiModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Стиль для ЛЮБЫХ чужих экранов: ванильная серая подложка заменяется нашей
 * вуалью (затемнение + виньетка + блюр, если игра это разрешает).
 *
 * Целимся в {@code Screen#extractBackground} — этот метод вызывают все экраны
 * (и контейнеры, и опции, и advancements, и книги), а наши собственные экраны
 * его либо переопределяют, либо отсюда же выходят по проверке пакета.
 */
@Mixin(Screen.class)
public abstract class ScreenStyleMixin {

	@Shadow
	protected int width;

	@Shadow
	protected int height;

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void dreamcast$veil(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
	                             CallbackInfo ci) {
		Object self = this;
		Screen screen = (Screen) (Object) self;
		if (CustomGuiModule.isOwnScreen(screen) || !CustomGuiModule.styling()) {
			return;
		}
		CustomGuiModule.drawVeil(graphics, this.width, this.height);
		ci.cancel();
	}
}

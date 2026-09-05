package com.dreamcast.client.mixin;

import com.dreamcast.client.gui.ClientScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Замена ванильных меню на свои — в единственной точке, через которую игра их
 * открывает: {@code Minecraft#setScreenAndShow}.
 *
 * <p>Меняем сам аргумент, а не отменяем вызов: поле экрана заполнится уже нашей
 * заменой, поэтому Esc, {@code onClose} и «родительский» экран ведут себя честно.
 * Контейнеры здесь НЕ подменяются — их поведение целиком ванильное, а вид даёт
 * {@link ContainerStyleMixin}.</p>
 */
@Mixin(Minecraft.class)
public abstract class ScreenSwapMixin {

	@ModifyVariable(method = "setScreenAndShow", at = @At("HEAD"), argsOnly = true, require = 0)
	private static Screen dreamcast$swap(Screen screen) {
		return ClientScreens.remap(screen);
	}
}

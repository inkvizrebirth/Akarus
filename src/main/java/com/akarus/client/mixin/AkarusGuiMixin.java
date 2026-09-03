package com.akarus.client.mixin;

import com.akarus.client.gui.screens.AkarusMenuScreen;
import com.akarus.client.gui.screens.AkarusPauseScreen;
import com.akarus.client.gui.screens.AkarusServersScreen;
import com.akarus.client.gui.screens.AkarusWorldsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Полноценная замена ванильной «оболочки» игры.
 *
 * Все пути, которыми игра показывает TitleScreen/PauseScreen (старт, Esc,
 * {@code Gui#setPauseScreen}, возврат с подключений), проходят через
 * {@code Gui#setScreen} — подменяем аргумент ровно там и только там, где это
 * безопасно. Тот же крючок ловит и списки миров/серверов: куда бы игра ни
 * собиралась показать SelectWorldScreen или JoinMultiplayerScreen (после
 * отключения, из настроек, при возврате), игрок видит наши экраны в стиле
 * клиента. Точечная замена вместо точечных патчей десятка мест: в 26.2
 * начальный экран собирается в {@code Gui#buildInitialScreens} лямбдой, которая
 * тоже вызывает setScreen — мы её покрываем.
 *
 * Рекурсия исключена: наш повторный setScreen идёт с флагом «уже подменяем».
 */
@Mixin(Gui.class)
public final class AkarusGuiMixin {

	@Unique
	private static final AtomicBoolean AKARUS$REPLACING = new AtomicBoolean();

	@Unique
	private void akarus$replaceScreen(Screen incoming, CallbackInfo ci) {
		if (incoming == null || AKARUS$REPLACING.get()) {
			return;
		}

		Screen replacement = null;
		if (incoming instanceof TitleScreen) {
			replacement = new AkarusMenuScreen();
		} else if (incoming instanceof PauseScreen) {
			replacement = new AkarusPauseScreen();
		} else if (incoming instanceof SelectWorldScreen) {
			replacement = new AkarusWorldsScreen(new AkarusMenuScreen());
		} else if (incoming instanceof JoinMultiplayerScreen) {
			replacement = new AkarusServersScreen(new AkarusMenuScreen());
		}
		if (replacement == null) {
			return;
		}

		ci.cancel();
		AKARUS$REPLACING.set(true);
		try {
			((Gui) (Object) this).setScreen(replacement);
		} finally {
			AKARUS$REPLACING.set(false);
		}
	}

	// Обязательно cancellable=true: replaceScreen вызывает ci.cancel(). Без этого Mixin
	// бросает CancellationException именно при первом показе TitleScreen, а загрузочный
	// оверлей принимает её за ошибку ресурсов и остаётся на «Minecraft Loading».
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
	private void akarus$onSetScreen(Screen screen, CallbackInfo ci) {
		akarus$replaceScreen(screen, ci);
	}
}

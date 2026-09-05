package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.util.ModInstaller;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Baritone (автоустановка) — следит, чтобы мод Baritone был в игре.
 *
 * <p>Сам Baritone в нас не вшит: он распространяется под GPL-3.0 и собирается
 * отдельно под каждую версию игры, поэтому правильное поведение клиента —
 * не таскать чужой jar, а доставить его в {@code mods/} при первом запуске и
 * попросить перезапустить игру. Дальше работают AutoMine/AutoWalk: они ходят в
 * Baritone через рефлексивный мост {@code BaritoneBridge} и деградируют в чат-команды,
 * если мода нет.</p>
 *
 * <p>Кнопка «Скачать сейчас» нужна, когда автоустановка была выключена или сеть
 * в тот момент не ответила. Мод не пытается ни патчить, ни обновлять Baritone:
 * обновление — это удалить файл и нажать кнопку снова.</p>
 */
public class BaritoneModule extends Module {

	private final BooleanSetting autoInstall = bool("auto_install", "Ставить автоматически", true);
	private final BooleanSetting notify = bool("notify", "Писать в чат", true);

	public BaritoneModule() {
		super("baritone", "Baritone",
				"Автоустановка Baritone в mods/ (нужен перезапуск игры), нужен AutoMine/AutoWalk",
				ModuleCategory.MISC, GLFW.GLFW_KEY_UNKNOWN);
		addSetting(buttonSetting("install", "Скачать сейчас", "Скачать Baritone", ModInstaller::installNow));
	}

	@Override
	protected boolean defaultEnabled() {
		return true;
	}

	@Override
	protected void onEnable() {
		check(false);
	}

	/** Проверка при входе в мир: возможно, Baritone поставили руками между запусками. */
	public static void onWorldJoin(Minecraft client) {
		BaritoneModule module = com.dreamcast.client.module.ModuleManager.find(BaritoneModule.class);
		if (module != null && module.isEnabled()) {
			module.check(true);
		}
	}

	/**
	 * @param quiet true — ничего не писать, если мод уже на месте (обычный запуск
	 *              игры не должен дёргать пользователя рассказом о его же модах)
	 */
	private void check(boolean quiet) {
		if (ModInstaller.isLoaded()) {
			return;
		}
		var decision = ModInstaller.checkAndInstall(autoInstall.isEnabled());
		if (!notify.isEnabled() || quiet && decision == com.dreamcast.client.util.ModInstallLogic.Decision.LOADED) {
			return;
		}
		switch (decision) {
			case NEEDS_RESTART -> message("Baritone уже лежит в mods/ — перезапусти игру, чтобы он подхватился");
			case DOWNLOAD -> message("Скачиваю Baritone с Modrinth…");
			case DISABLED -> message("Baritone не установлен, автоустановка выключена — нажми «Скачать сейчас»");
			case BUSY -> message("Baritone уже скачивается…");
			default -> {
			}
		}
	}

	private void message(String text) {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.gui != null && client.gui.hud != null) {
			client.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
		}
	}
}

package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.glfw.GLFW;

/**
 * NoBlind — убирает мешающие визуальные эффекты с экрана.
 *
 * Каждый эффект отключается отдельной настройкой:
 * <ul>
 *   <li><b>Тошнота</b> — в 26.2 тошнота рисуется постэффектом (nausea), который
 *       крутит и размывает экран. {@code GameRendererMixin} подменяет активный
 *       постэффект на «ничего», пока включена эта опция;</li>
 *   <li><b>Тьма и слепота</b> — эффекты, сужающие обзор до пугающего тумана.
 *       Пока опция включена, соответствующие эффекты снимаются с нашего игрока
 *       на клиенте каждый тик: сервер продолжает их считать, но экран остаётся
 *       чистым;</li>
 *   <li><b>Огонь на экране</b> — пламя, застилающее вид при горении. В 26.2 эта
 *       пелена собирается в {@code ScreenEffectRenderer} вместе с водой и
 *       «головой в блоке», поэтому {@code ScreenEffectRendererMixin} отменяет
 *       ровно её (огонь на самих моделях остаётся).</li>
 * </ul>
 *
 * Всё это чисто клиентская косметика: ни одного пакета не меняется.
 */
public class NoBlindModule extends Module {

	private final BooleanSetting nausea = bool("nausea", "Тошнота", true);
	private final BooleanSetting darkness = bool("darkness", "Тьма и слепота", true);
	private final BooleanSetting fire = bool("fire", "Огонь на экране", true);

	public NoBlindModule() {
		super("no_blind", "NoBlind", "Убирает визуальные помехи: тошноту, тьму, слепоту и огонь на экране",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void tick() {
		if (!hidesDarkness()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) {
			return;
		}
		// Снимаем на клиенте: сервер продолжает считать эффекты, но экран чист
		client.player.removeEffect(MobEffects.DARKNESS);
		client.player.removeEffect(MobEffects.BLINDNESS);
	}

	public boolean hidesNausea() {
		return nausea.isEnabled();
	}

	public boolean hidesDarkness() {
		return darkness.isEnabled();
	}

	public boolean hidesFire() {
		return fire.isEnabled();
	}
}

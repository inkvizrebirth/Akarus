package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

/**
 * Sprint — всегда держит спринт.
 *
 * Держим клавишу спринта нажатой и подтягиваем флаг вручную, как только игрок
 * действительно идёт вперёд: ваниль иногда «теряет» спринт после удара, в воде
 * или при короткой заминке — с этим модулем бег возобновляется сам.
 *
 * С другими модулями не конфликтует: FreeCam замораживает ввод по-своему (тогда
 * игрок всё равно стоит), Baritone управляет движением своими флагами поверх
 * клавиши, а киллаура на пару тиков снимает спринт для сброса (см.
 * {@link #suppress(int)}) — в это время модуль сознательно молчит.
 */
public class SprintModule extends Module {

	/** Сколько тиков не трогать спринт — их считает сброс спринта киллауры. */
	private static int suppressedTicks;

	public SprintModule() {
		super("sprint", "Спринт", "Всегда держит спринт: бежит, как только игрок идёт вперёд",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_M);
	}

	@Override
	protected void onEnable() {
		suppressedTicks = 0;
	}

	@Override
	protected void onDisable() {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.options != null) {
			client.options.keySprint.setDown(false);
		}
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.options == null) {
			return;
		}

		// Киллаура на тик-два снимает спринт (сброс для нокбэка) — не мешаем ей
		if (suppressedTicks > 0) {
			suppressedTicks--;
			client.options.keySprint.setDown(false);
			return;
		}

		// Во время еды/натягивания лука спринт не форсируем — это выглядело бы
		// подозрительно и конфликтовало бы с самим использованием предмета
		if (player.isUsingItem() && !player.isBlocking()) {
			return;
		}

		client.options.keySprint.setDown(true);

		// zza — вертикальная (вперёд/назад) составляющая того, куда игрок идёт
		if (player.zza > 0.0F && !player.isSprinting()) {
			player.setSprinting(true);
		}
	}

	/**
	 * Просит модуль не трогать спринт следующие {@code ticks} тиков.
	 * Нужно киллауре: короткий сброс спринта после удара (w-tap) даёт полный
	 * нокбэк каждым ударом, а зажатый намертво спринт его «съедает».
	 */
	public static void suppress(int ticks) {
		suppressedTicks = Math.max(suppressedTicks, ticks);
	}
}

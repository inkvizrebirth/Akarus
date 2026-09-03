package com.akarus.client.render;

import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.HandShaderModule;
import com.akarus.client.module.impl.ViewModelModule;
import com.akarus.client.viewmodel.ViewModelProfile;

/**
 * Мост между миксинами рендера рук и модулями клиента.
 *
 * Миксины выполняются каждый кадр и не должны ничего знать про жизненный цикл
 * модулей, поэтому вся логика «включено ли сейчас» собрана здесь, а сами миксины
 * только вызывают эти методы и ничего не решают.
 *
 * Все методы спроектированы так, чтобы до инициализации клиента (или если модуль
 * по какой-то причине не зарегистрирован) возвращать «ничего не делать».
 */
public final class HandRenderHook {

	/** Идёт ли прямо сейчас отрисовка рук от первого лица. */
	private static boolean rendering;
	/** Прогресс 0..1 для градиента — берётся из анимации взмаха руки. */
	private static float progress;
	/** Какая рука рисуется сейчас: true — основная, false — вторая. */
	private static boolean mainHand = true;

	private HandRenderHook() {
	}

	public static void begin(boolean main, float animationProgress) {
		rendering = true;
		mainHand = main;
		progress = Math.max(0.0f, Math.min(1.0f, animationProgress));
	}

	public static void end() {
		rendering = false;
	}

	public static boolean isRendering() {
		return rendering;
	}

	public static boolean isMainHand() {
		return mainHand;
	}

	public static float getProgress() {
		return progress;
	}

	private static HandShaderModule shader() {
		return ModuleManager.find(HandShaderModule.class);
	}

	private static ViewModelModule viewModel() {
		return ModuleManager.find(ViewModelModule.class);
	}

	/**
	 * Цвет обводки руки или 0, если обводить не нужно.
	 * Ноль здесь важен: именно по нулю ванильный рендер понимает, что обводки нет.
	 */
	public static int armOutlineColor() {
		HandShaderModule module = shader();
		if (module == null || !module.isEnabled() || !module.isArmOutline()) {
			return 0;
		}
		return module.getColor(progress, System.currentTimeMillis());
	}

	/** Цвет обводки предмета или 0. */
	public static int itemOutlineColor() {
		if (!rendering) {
			return 0;
		}
		HandShaderModule module = shader();
		if (module == null || !module.isEnabled() || !module.isItemOutline()) {
			return 0;
		}
		return module.getColor(progress, System.currentTimeMillis());
	}

	/** Насколько «раздувать» силуэт руки, чтобы получилась обводка нужной толщины. */
	public static float outlineScale() {
		HandShaderModule module = shader();
		return module == null ? 1.0f : module.getOutlineScale();
	}

	/** Активная раскладка рук или null, если модуль выключен и менять ничего не нужно. */
	public static ViewModelProfile profile() {
		ViewModelModule module = viewModel();
		if (module == null || !module.isEnabled()) {
			return null;
		}
		ViewModelProfile profile = module.getProfile();
		return profile == null || profile.isDefault() ? null : profile;
	}
}

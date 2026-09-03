package com.akarus.client.render;

import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.HandShaderModule;
import com.akarus.client.module.impl.ViewModelModule;
import com.akarus.client.render.HandOutlineRenderer.Spec;
import com.akarus.client.viewmodel.ViewModelProfile;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Мост между миксинами рендера рук и модулями клиента.
 *
 * Миксины выполняются каждый кадр и не должны ничего знать про жизненный цикл
 * модулей, поэтому вся логика «включено ли сейчас и как именно» собрана здесь,
 * а сами миксины только вызывают эти методы и ничего не решают.
 *
 * Все методы спроектированы так, чтобы до инициализации клиента (или если модуль
 * по какой-то причине не зарегистрирован) возвращать «ничего не делать».
 */
public final class HandRenderHook {

	/** Идёт ли прямо сейчас отрисовка рук от первого лица. */
	private static boolean rendering;

	/**
	 * Параметры обводки на этот кадр. Считаются один раз при входе в рисование руки,
	 * а не на каждое кольцо: колец может быть больше сотни за кадр.
	 */
	private static Spec armSpec;
	private static Spec itemSpec;

	private HandRenderHook() {
	}

	/**
	 * Начало прохода руки. Вызывается из миксина один раз на каждую руку за кадр,
	 * поэтому настройки модуля читаются тут, а не в цикле по кольцам.
	 */
	public static void begin() {
		rendering = true;

		HandShaderModule module = shader();
		if (module == null || !module.isEnabled()) {
			armSpec = null;
			itemSpec = null;
			return;
		}

		long time = Util.getMillis();
		armSpec = module.spec(time, true);
		itemSpec = module.spec(time, false);
	}

	public static void end() {
		rendering = false;
		armSpec = null;
		itemSpec = null;
	}

	public static boolean isRendering() {
		return rendering;
	}

	private static HandShaderModule shader() {
		return ModuleManager.find(HandShaderModule.class);
	}

	private static ViewModelModule viewModel() {
		return ModuleManager.find(ViewModelModule.class);
	}

	/** Параметры обводки руки для текущего кадра или null, если рисовать нечего. */
	public static Spec armSpec() {
		return rendering ? armSpec : null;
	}

	/** Параметры обводки предмета в руке или null. */
	public static Spec itemSpec() {
		return rendering ? itemSpec : null;
	}

	/**
	 * Отзеркалить ли сдвиг по X для руки, которая рисуется сейчас.
	 *
	 * Ваниль левую руку рисует зеркально правой (см. {@code invert} в
	 * {@code ItemInHandRenderer}), поэтому и пользовательский сдвиг «вправо» для
	 * второй руки надо развернуть — тогда обе руки уезжают к краю экрана одинаково.
	 */
	public static boolean mirrored(AbstractClientPlayer player, boolean mainHand) {
		if (player == null) {
			return false;
		}
		HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
		if (arm != HumanoidArm.LEFT) {
			return false;
		}
		ViewModelModule module = viewModel();
		return module == null || module.mirrorsOffHand();
	}

	/**
	 * Раскладка рук. Возвращает null, если менять ничего не нужно.
	 *
	 * Модуль ViewModel тут только хранит значения и включает-выключает их одним
	 * тумблером «Применять раскладку»: редактор раскладки правит те же самые поля,
	 * поэтому работает в любом случае — иначе получалось, что «рука двигается
	 * только перетаскиванием», а остальные параметры молчат.
	 */
	public static ViewModelProfile profile() {
		ViewModelModule module = viewModel();
		if (module == null || !module.appliesLayout()) {
			return null;
		}
		ViewModelProfile profile = module.getProfile();
		return profile == null || profile.isDefault() ? null : profile;
	}
}

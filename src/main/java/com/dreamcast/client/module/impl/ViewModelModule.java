package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ButtonSetting;
import com.dreamcast.client.viewmodel.ViewModelConfig;
import com.dreamcast.client.viewmodel.ViewModelProfile;
import com.dreamcast.client.viewmodel.ViewModelProfile.Parameter;
import org.lwjgl.glfw.GLFW;

/**
 * ViewModel — положение рук от первого лица.
 *
 * Сама математика живёт в {@link ViewModelProfile}, а применяет её миксин
 * {@code ItemInHandRendererMixin}: он перехватывает позу руки до начала отрисовки
 * и накладывает на неё масштаб, сдвиг и поворот отсюда.
 *
 * Модуль включён по умолчанию: раскладка — это настройка внешности, а не то,
 * что хочется каждый раз тумблерить. Выключить можно, если нужно сравнить
 * «как в ванили» и «как у меня».
 */
public class ViewModelModule extends Module {

	private final BooleanSetting mirrorOffHand = bool("mirror_off_hand", "Зеркалить левую руку", true);

	@SuppressWarnings("unused")
	private final ButtonSetting editor = buttonSetting("editor", "Раскладка рук", "Настроить",
			HandShaderModule::openEditor);

	/** Текущая раскладка. Живёт в модуле, а не в статике, чтобы её можно было сохранить в конфиг. */
	private final ViewModelProfile profile = ViewModelConfig.load();

	public ViewModelModule() {
		super("view_model", "ViewModel", "Масштаб, сдвиг и поворот рук от первого лица",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_V);
	}

	/** Раскладка применяется, пока модуль включён — он включён по умолчанию. */
	@Override
	protected boolean defaultEnabled() {
		return true;
	}

	public ViewModelProfile getProfile() {
		return profile;
	}

	/** Накладывать ли раскладку вообще (см. {@code HandRenderHook#profile()}). */
	public boolean appliesLayout() {
		return isEnabled();
	}

	/**
	 * Левая рука в ванили — зеркало правой, поэтому и пользовательский сдвиг по X
	 * для неё отзеркаливается. Кто хочет «одинаково для обеих рук» — выключает.
	 */
	public boolean mirrorsOffHand() {
		return mirrorOffHand.isEnabled();
	}

	/** Удобная обёртка для колеса мыши в редакторе раскладки. */
	public void change(Parameter parameter, float amount) {
		profile.change(parameter, amount);
		// Правим раскладку при выключенном модуле — значит её надо включить,
		// иначе «ничего не меняется» остаётся главным впечатлением от редактора
		if (!isEnabled()) {
			setEnabled(true);
		}
	}

	/**
	 * То же, что {@link #change(Parameter, float)}, но с точным значением — нужно
	 * для перетаскивания руки мышью.
	 */
	public void set(Parameter parameter, float value) {
		profile.set(parameter, value);
		if (!isEnabled()) {
			setEnabled(true);
		}
	}

	/** Сохраняет раскладку на диск. */
	public void saveProfile() {
		ViewModelConfig.save(profile);
	}

	/** Возвращает раскладку к ванильной. */
	public void resetProfile() {
		profile.copyFrom(ViewModelProfile.createDefault());
		ViewModelConfig.save(profile);
	}
}

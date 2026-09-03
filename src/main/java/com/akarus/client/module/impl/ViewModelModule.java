package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.ButtonSetting;
import com.akarus.client.viewmodel.ViewModelConfig;
import com.akarus.client.viewmodel.ViewModelProfile;
import com.akarus.client.viewmodel.ViewModelProfile.Parameter;
import org.lwjgl.glfw.GLFW;

/**
 * ViewModel — положение рук от первого лица.
 *
 * Сама математика живёт в {@link ViewModelProfile}, а применяет её миксин
 * {@code ItemInHandRendererMixin}: он перехватывает матрицу руки и до начала
 * отрисовки накладывает на неё масштаб, сдвиг и поворот отсюда.
 */
public class ViewModelModule extends Module {

	private final BooleanSetting separateItems = bool("apply_to_items", "Двигать и предмет", true);

	@SuppressWarnings("unused")
	private final ButtonSetting editor = buttonSetting("editor", "Раскладка рук", "Настроить",
			HandShaderModule::openEditor);

	/** Текущая раскладка. Живёт в модуле, а не в статике, чтобы её можно было сохранить в конфиг. */
	private final ViewModelProfile profile = ViewModelConfig.load();

	public ViewModelModule() {
		super("view_model", "ViewModel", "Масштаб, сдвиг и поворот рук от первого лица",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_V);
	}

	public ViewModelProfile getProfile() {
		return profile;
	}

	/** Двигать ли предмет вместе с рукой (некоторые любят оставлять его на месте). */
	public boolean appliesToItems() {
		return separateItems.isEnabled();
	}

	/** Удобная обёртка для колеса мыши в редакторе раскладки. */
	public void change(Parameter parameter, float amount) {
		profile.change(parameter, amount);
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

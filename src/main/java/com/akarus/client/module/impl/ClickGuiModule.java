package com.akarus.client.module.impl;

import com.akarus.client.gui.ClickGuiScreen;
import com.akarus.client.gui.theme.ClientTheme;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.ColorSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.ModeSetting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Модуль-настройка ClickGUI.
 *
 * Бинд модуля — это и есть клавиша открытия меню (по умолчанию правый Shift):
 * нажатие не «включает» модуль, а открывает окно. Сам модуль выключателя
 * не имеет — меню всегда доступно.
 *
 * Настройки: «стекло» (полупрозрачное окно с размытием мира за ним), тема
 * (пресеты градиентов или свои два цвета) и скорость перелива градиента.
 */
public class ClickGuiModule extends Module {

	private final ModeSetting theme = mode("theme", "Тема", "dreamcast", themeOptions());
	private final ColorSetting customFirst = colorSetting("custom_color_1", "Свой цвет 1", 0xFF7C6CFF);
	private final ColorSetting customSecond = colorSetting("custom_color_2", "Свой цвет 2", 0xFF45E3FF);
	private final IntSetting flowSpeed = intSetting("flow_speed", "Скорость перелива", 3, 0, 10);
	private final IntSetting glassStrength = intSetting("glass", "Стекло: плотность", 6, 0, 10);

	public ClickGuiModule() {
		super("click_gui", "ClickGUI", "Меню модулей: клавиша открытия, стекло и тема",
				ModuleCategory.MISC, GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static ModeSetting.Option[] themeOptions() {
		List<ModeSetting.Option> options = new ArrayList<>();
		for (ClientTheme.Preset preset : ClientTheme.PRESETS) {
			options.add(ModeSetting.option(preset.id(), preset.label()));
		}
		options.add(ModeSetting.option("custom", "Свои цвета"));
		return options.toArray(new ModeSetting.Option[0]);
	}

	/** Меню нельзя «выключить» — оно всегда доступно. */
	@Override
	protected boolean alwaysEnabled() {
		return true;
	}

	/** Клавиша модуля открывает меню, а не переключает состояние. */
	@Override
	protected void onBindPressed() {
		ClickGuiScreen.open();
	}

	// ------------------------------------------------------------------
	// Значения для темы и стекла
	// ------------------------------------------------------------------

	private boolean customTheme() {
		return theme.is("custom");
	}

	public int themeFirstColor() {
		return customTheme() ? customFirst.get() : ClientTheme.preset(theme.getValue()).first();
	}

	public int themeSecondColor() {
		return customTheme() ? customSecond.get() : ClientTheme.preset(theme.getValue()).second();
	}

	/** Скорость перелива, приведённая к 0..1 (0 — статичный градиент). */
	public float flowSpeed01() {
		return flowSpeed.get() / 10.0f;
	}

	/** Включено ли «стекло»: полупрозрачное окно + размытие мира за ним. */
	public boolean glassEnabled() {
		return glassStrength.get() > 0;
	}

	/** Плотность стекла 0..1: 0 — непрозрачное окно, 1 — почти призрачное. */
	public float glassAlpha() {
		return glassStrength.get() / 10.0f;
	}

	public String themeId() {
		return theme.getValue();
	}
}

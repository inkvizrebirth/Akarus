package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import net.minecraft.client.Minecraft;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import net.minecraft.client.OptionInstance;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;

/**
 * Цвет блика зачарования.
 *
 * <p>Как это работает. В 26.2 игра сама решает, каким будет блик: пиксельный
 * шейдер {@code glint.fsh} берёт один скаляр {@code GlintAlpha}, а он
 * складывается из ванильной настройки «Сила блика». Наш мод кладёт в этот же
 * канал <em>тон</em> — свой {@code assets/minecraft/shaders/core/glint.fsh}
 * расшифровывает его обратно в HSV. Отсюда две вещи:</p>
 *
 * <ul>
 *   <li>цвет меняется в тот же кадр — никакой перезагрузки текстур;</li>
 *   <li>«Сила блика» в настройках игры становится рукояткой Dreamcast:
 *   меняем её, когда модуль выключен, — возвращается ванильное поведение.</li>
 * </ul>
 *
 * <p>Скорость «текучести» блика — тоже ванильная настройка ({@code glintSpeed}):
 * она управляет тем, как быстро по предмету бежит полоса. Радуга — наш счётчик:
 * тон каждый тик сдвигается, поэтому перелив не зависит от частоты кадров
 * (тик = 50 мс всегда).</p>
 *
 * <p>Ограничение, о котором честно знаем: канал один, поэтому «второй цвет»
 * или отдельная регулировка прозрачности в этом режиме невозможны — яркость
 * блика задаётся маской предмета. Включённый мод перехватывает обе ванильные
 * настройки и возвращает их на место при выключении.</p>
 */
public class GlintColorModule extends Module {

	/** Диапазон ванильной ползунки «Сила блика»; тон укладываем в него целиком. */
	private static final double MIN_STRENGTH = 0.0;
	private static final double MAX_STRENGTH = 1.0;
	/** Ванильный диапазон скорости блика: 0 — стоит на месте, 5 — бежит быстро. */
	private static final double MIN_SCROLL = 0.0;
	private static final double MAX_SCROLL = 5.0;

	private final ColorSetting color = colorSetting("color", "Цвет блика", 0xFF45E3FF);
	private final BooleanSetting rainbow = bool("rainbow", "Переливать", true);
	private final IntSetting flowSpeed = intSetting("flow", "Скорость перелива", 4, 0, 10);
	private final IntSetting scrollSpeed = intSetting("scroll", "Скорость полосы", 5, 0, 10);

	/** Тон, который поставили мы (нужен, чтобы вернуть на место при выключении). */
	private double storedStrength = -1.0;
	private double storedScroll = -1.0;
	/** Копилка фазы радуги: 0..1 по кругу HSV. */
	private double phase;

	public GlintColorModule() {
		super("glint_color", "Glint Color", "Свой цвет и скорость блика зачарований",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
		addSetting(color);
		addSetting(rainbow);
		addSetting(flowSpeed);
		addSetting(scrollSpeed);
	}

	@Override
	protected void onEnable() {
		OptionInstance<Double> strength = strengthOption();
		OptionInstance<Double> scroll = scrollOption();
		if (strength != null && strength.get() != null) {
			storedStrength = strength.get();
		}
		if (scroll != null && scroll.get() != null) {
			storedScroll = scroll.get();
		}
		apply();
	}

	@Override
	protected void onDisable() {
		write(strengthOption(), storedStrength, MIN_STRENGTH, MAX_STRENGTH);
		write(scrollOption(), storedScroll, MIN_SCROLL, MAX_SCROLL);
		storedStrength = -1.0;
		storedScroll = -1.0;
	}

	@Override
	public void onSettingsChanged() {
		apply();
	}

	@Override
	public void tick() {
		if (!isEnabled()) {
			return;
		}
		if (rainbow.isEnabled()) {
			// 20 тиков в секунду, «скорость» 10 — полный круг примерно за 4 секунды
			phase = (phase + flowSpeed.get() * 0.0012) % 1.0;
		}
		apply();
	}

	private void apply() {
		if (!isEnabled()) {
			return;
		}
		write(strengthOption(), rainbow.isEnabled() ? phase : hueOf(color.get()), MIN_STRENGTH, MAX_STRENGTH);
		write(scrollOption(), MIN_SCROLL + (MAX_SCROLL - MIN_SCROLL) * (scrollSpeed.get() / 10.0),
				MIN_SCROLL, MAX_SCROLL);
	}

	/**
	 * Тон цвета в HSV. AWT здесь только математика: {@code RGBtoHSB} не трогает
	 * дисплей и не создаёт окон, поэтому работает и в headless, и на клиенте.
	 */
	private static double hueOf(int argb) {
		float[] hsb = Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, null);
		return hsb[0];
	}

	private static OptionInstance<Double> strengthOption() {
		Minecraft client = Minecraft.getInstance();
		return client == null || client.options == null ? null : client.options.glintStrength();
	}

	private static OptionInstance<Double> scrollOption() {
		Minecraft client = Minecraft.getInstance();
		return client == null || client.options == null ? null : client.options.glintSpeed();
	}

	/** Пишем значение только если оно действительно отличается — иначе игра будет сохранять конфиг каждый тик. */
	private static void write(OptionInstance<Double> option, double value, double min, double max) {
		if (option == null || value < 0.0) {
			return;
		}
		double clamped = Math.max(min, Math.min(max, value));
		Double current = option.get();
		if (current == null || Math.abs(current - clamped) > 1.0e-4) {
			option.set(clamped);
		}
	}
}

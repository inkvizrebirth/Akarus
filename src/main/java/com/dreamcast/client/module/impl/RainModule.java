package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.render.RainRenderer;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import org.lwjgl.glfw.GLFW;

/**
 * Дождь по-нашему: капли, брызги, гроза и звук.
 *
 * <p>Настоящие шейдеры (PBR, тени, SSAO) в 26.2 без Iris недоступны ни модулю,
 * ни клиенту — поэтому погода рисуется нами же, теми же примитивами, что и остальной
 * мир: полосы капель — светящиеся линии, брызги — expanding-кольца, молния — ломаная
 * со вспышкой. Такое не зависит от версии графического пайплайна и не ломается на
 * другом железе.</p>
 *
 * <p>Погода здесь своя: серверный {@code rain} включать не нужно. Капли живут в
 * box вокруг камеры и перерабатываются по кругу, поэтому не «появляются» и не
 * «исчезают» на глазах; симуляция идёт по настоящему dt, а не по кадрам.</p>
 */
public class RainModule extends Module {

	/** Максимум капель: больше — уже заметно по FPS, а глазу разницы нет. */
	public static final int MAX_DROPS = 1600;

	private final IntSetting density = intSetting("density", "Плотность, %", 55, 0, 100);
	private final IntSetting radius = intSetting("radius", "Радиус, блоков", 48, 16, 96);
	private final IntSetting height = intSetting("height", "Высота слоя, блоков", 36, 12, 72);
	private final IntSetting wind = intSetting("wind", "Ветер, %", 22, -100, 100);
	private final IntSetting fallSpeed = intSetting("fall_speed", "Скорость падения, %", 100, 20, 260);
	private final IntSetting streak = intSetting("streak", "Длина капли, см", 60, 8, 220);
	private final IntSetting thickness = intSetting("thickness", "Толщина, px", 2, 1, 5);
	private final BooleanSetting snow = bool("snow", "Снег", false);
	private final BooleanSetting splashes = bool("splashes", "Брызги о землю", true);
	private final BooleanSetting stormSky = bool("storm_sky", "Низкое небо", true);
	private final IntSetting clouds = intSetting("clouds", "Плотность облаков", 26, 0, 64);
	private final IntSetting lightning = intSetting("lightning", "Молнии, сек", 9, 0, 60);
	private final IntSetting volume = intSetting("volume", "Громкость, %", 60, 0, 100);
	private final IntSetting pitch = intSetting("pitch", "Тон звука, %", 100, 50, 180);
	private final ColorSetting color = colorSetting("color", "Цвет капель", 0xFF9FC7FF);

	public RainModule() {
		super("rain", "Rain", "Дождь, снег, гроза и их звук — рисуем сами",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
		addSetting(density);
		addSetting(radius);
		addSetting(height);
		addSetting(wind);
		addSetting(fallSpeed);
		addSetting(streak);
		addSetting(thickness);
		addSetting(snow);
		addSetting(splashes);
		addSetting(stormSky);
		addSetting(clouds);
		addSetting(lightning);
		addSetting(volume);
		addSetting(pitch);
		addSetting(color);
	}

	@Override
	protected void onDisable() {
		RainRenderer.reset();
	}

	public int drops() {
		return (int) Math.round(MAX_DROPS * (density.get() / 100.0));
	}

	public int radiusBlocks() {
		return radius.get();
	}

	public int layerHeight() {
		return height.get();
	}

	/** -1..1: направление и сила сноса. */
	public float windFactor() {
		return wind.get() / 100.0F;
	}

	public float fallMultiplier() {
		return fallSpeed.get() / 100.0F;
	}

	public double streakBlocks() {
		return streak.get() / 100.0;
	}

	public int lineThickness() {
		return thickness.get();
	}

	public boolean snowing() {
		return snow.isEnabled();
	}

	public boolean wantsSplashes() {
		return splashes.isEnabled();
	}

	public boolean wantsStormSky() {
		return stormSky.isEnabled() && clouds.get() > 0;
	}

	public int cloudCount() {
		return clouds.get() / 2;
	}

	/** Интервал грозы в секундах; 0 — молний нет. */
	public int lightningIntervalSeconds() {
		return lightning.get();
	}

	public float soundVolume() {
		return volume.get() / 100.0F;
	}

	public float soundPitch() {
		return pitch.get() / 100.0F;
	}

	public int dropColor() {
		return color.get();
	}
}

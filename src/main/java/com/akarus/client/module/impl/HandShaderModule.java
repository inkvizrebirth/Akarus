package com.akarus.client.module.impl;

import com.akarus.client.gui.HandEditorScreen;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.ButtonSetting;
import com.akarus.client.settings.ColorSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Обводка рук от первого лица.
 *
 * Работает вместе с миксинами {@code ItemInHandRendererMixin} и {@code AvatarRendererMixin}:
 * рука и предмет рисуются второй раз контурным render type'ом, а цвет берётся
 * из {@link #getColor(float, long)}.
 */
public class HandShaderModule extends Module {

	private final BooleanSetting outlineArm = bool("outline_arm", "Обводить руку", true);
	private final BooleanSetting outlineItem = bool("outline_item", "Обводить предмет", true);
	private final IntSetting thickness = intSetting("thickness", "Толщина", 3, 1, 8);
	private final ColorSetting color = colorSetting("color", "Цвет", 0xFF8A6CFF);
	private final ColorSetting secondaryColor = colorSetting("secondary_color", "Второй цвет", 0xFF5CE1E6);
	private final BooleanSetting gradient = bool("gradient", "Градиент", false);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", false);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 5, 1, 20);
	@SuppressWarnings("unused")
	private final ButtonSetting editor = buttonSetting("editor", "Раскладка рук", "Настроить", HandShaderModule::openEditor);

	public HandShaderModule() {
		super("hand_shader", "Обводка рук", "Цветной контур вокруг руки и предмета от первого лица",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_J);
	}

	/**
	 * Итоговый цвет обводки.
	 *
	 * Порядок простой: радуга важнее градиента, градиент важнее сплошного цвета.
	 *
	 * @param progress значение 0..1 — «позиция» внутри обводки; для градиента это
	 *                 точка перехода от основного цвета ко второму, для радуги —
	 *                 сдвиг оттенка. Берётся из анимации взмаха руки.
	 * @param time     время в миллисекундах — по нему двигается радуга
	 */
	public int getColor(float progress, long time) {
		int alpha = color.getAlpha();

		if (rainbow.isEnabled()) {
			// Полный круг радуги проходится за 4 секунды на пятой скорости
			float period = 8000.0f / rainbowSpeed.get();
			float hue = (time % (long) period) / period + progress * 0.35f;
			return RenderUtils.withAlpha(RenderUtils.hsb(hue, 0.80f, 1.0f, 0xFF), alpha / 255.0f);
		}

		if (gradient.isEnabled()) {
			return RenderUtils.mix(color.get(), secondaryColor.get(), Math.max(0.0f, Math.min(1.0f, progress)));
		}

		return color.get();
	}

	/** Во сколько раз силуэт руки больше самой руки: из этого и получается толщина контура. */
	public float getOutlineScale() {
		return 1.0f + (thickness.get() - 1) * 0.010f;
	}

	public boolean isArmOutline() {
		return outlineArm.isEnabled();
	}

	public boolean isItemOutline() {
		return outlineItem.isEnabled();
	}

	public ColorSetting getColorSetting() {
		return color;
	}

	public ColorSetting getSecondaryColorSetting() {
		return secondaryColor;
	}

	/** Открывает редактор раскладки рук. */
	public static void openEditor() {
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			client.gui.setScreen(new HandEditorScreen());
		}
	}
}

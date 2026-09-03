package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.IntSetting;
import org.lwjgl.glfw.GLFW;

/**
 * NoFOV — абсолютно статичный угол обзора.
 *
 * Ваниль плавно меняет FOV: спринт и скорость раздвигают картинку, лук/пища
 * сужают, вода и смерть добавляют свои модификаторы. Модуль фиксирует FOV
 * на выбранном значении: {@code CameraMixin} перехватывает {@code Camera#getFov}
 * и отдаёт наше число, какие бы модификаторы игра ни посчитала.
 *
 * Значение настраивается (30–110, по умолчанию 90) и применяется мгновенно,
 * без перезахода. На FOV рук (hudFov) модуль не влияет — руки рисуются
 * отдельной проекцией и остаются как в настройках игры.
 */
public class NoFovModule extends Module {

	private final IntSetting fov = intSetting("fov", "FOV", 90, 30, 110);

	public NoFovModule() {
		super("no_fov", "NoFOV", "Статичный угол обзора: FOV не меняется от спринта, эффектов и предметов",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	/** Зафиксированное значение FOV. */
	public int getFov() {
		return fov.get();
	}
}

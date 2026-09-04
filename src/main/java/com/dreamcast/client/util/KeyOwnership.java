package com.dreamcast.client.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.dreamcast.client.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * Владение клавишами: модуль, программно зажавший клавишу (прыжок, W, спринт,
 * «использовать»), при освобождении обязан вернуть её ФИЗИЧЕСКОЕ состояние,
 * а не просто setDown(false) — иначе игровой ввод, который держит сам игрок,
 * отключается до повторного нажатия.
 */
public final class KeyOwnership {

	private KeyOwnership() {
	}

	/** Отпустить клавишу с восстановлением реального состояния GLFW. */
	public static void release(Minecraft client, KeyMapping key) {
		if (client == null || key == null) {
			return;
		}
		key.setDown(isPhysicallyDown(client, key));
	}

	/** Нажата ли клавиша игроком физически (мышь или клавиатура). */
	public static boolean isPhysicallyDown(Minecraft client, KeyMapping key) {
		if (client == null || client.getWindow() == null) {
			return false;
		}
		InputConstants.Key bound = ((KeyMappingAccessor) key).dreamcast$boundKey();
		long window = client.getWindow().handle();
		return switch (bound.getType()) {
			case MOUSE -> GLFW.glfwGetMouseButton(window, bound.getValue()) == GLFW.GLFW_PRESS;
			case KEYSYM -> GLFW.glfwGetKey(window, bound.getValue()) == GLFW.GLFW_PRESS;
			default -> false;
		};
	}
}

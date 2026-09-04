package com.dreamcast.client.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.dreamcast.client.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Владение клавишами: модуль, программно зажавший клавишу (прыжок, W, спринт,
 * «использовать»), при освобождении обязан вернуть её ФИЗИЧЕСКОЕ состояние,
 * а не просто setDown(false) — иначе игровой ввод, который держит сам игрок,
 * отключается до повторного нажатия.
 */
public final class KeyOwnership {
	/** Кто программно держит клавишу нажатой. Сравнение владельцев — по identity. */
	private static final Map<KeyMapping, Set<Object>> HOLDERS = new IdentityHashMap<>();
	/** Кто временно гасит клавишу. Suppression имеет приоритет над hold. */
	private static final Map<KeyMapping, Set<Object>> SUPPRESSORS = new IdentityHashMap<>();

	private KeyOwnership() {
	}

	/** Зажать клавишу от имени конкретного модуля. */
	public static synchronized void hold(Minecraft client, KeyMapping key, Object owner) {
		if (client == null || key == null || owner == null) {
			return;
		}
		owners(HOLDERS, key).add(owner);
		apply(client, key);
	}

	/** Отпустить только своё программное удержание, не ломая другие модули. */
	public static synchronized void releaseHold(Minecraft client, KeyMapping key, Object owner) {
		releaseFrom(HOLDERS, key, owner);
		apply(client, key);
	}

	/** Временно погасить клавишу (например, настоящий W-tap или ввод FreeCam). */
	public static synchronized void suppress(Minecraft client, KeyMapping key, Object owner) {
		if (client == null || key == null || owner == null) {
			return;
		}
		owners(SUPPRESSORS, key).add(owner);
		apply(client, key);
	}

	/** Снять только своё подавление и восстановить hold/физическое состояние. */
	public static synchronized void releaseSuppression(Minecraft client, KeyMapping key, Object owner) {
		releaseFrom(SUPPRESSORS, key, owner);
		apply(client, key);
	}

	/** Снять все claims владельца при выключении/паузе модуля. */
	public static synchronized void releaseAll(Minecraft client, Object owner) {
		if (owner == null) {
			return;
		}
		Set<KeyMapping> changed = Collections.newSetFromMap(new IdentityHashMap<>());
		removeOwner(HOLDERS, owner, changed);
		removeOwner(SUPPRESSORS, owner, changed);
		for (KeyMapping key : changed) {
			apply(client, key);
		}
	}

	/** Повторно применить claims после ванильного обновления ввода. */
	public static synchronized void refresh(Minecraft client) {
		Set<KeyMapping> keys = Collections.newSetFromMap(new IdentityHashMap<>());
		keys.addAll(HOLDERS.keySet());
		keys.addAll(SUPPRESSORS.keySet());
		for (KeyMapping key : keys) {
			apply(client, key);
		}
	}

	/** Полный сброс на disconnect: ни один claim не переносится в следующий мир. */
	public static synchronized void clear(Minecraft client) {
		Set<KeyMapping> keys = Collections.newSetFromMap(new IdentityHashMap<>());
		keys.addAll(HOLDERS.keySet());
		keys.addAll(SUPPRESSORS.keySet());
		HOLDERS.clear();
		SUPPRESSORS.clear();
		for (KeyMapping key : keys) {
			apply(client, key);
		}
	}

	private static Set<Object> owners(Map<KeyMapping, Set<Object>> claims, KeyMapping key) {
		return claims.computeIfAbsent(key,
				ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private static void releaseFrom(Map<KeyMapping, Set<Object>> claims, KeyMapping key, Object owner) {
		if (key == null || owner == null) {
			return;
		}
		Set<Object> owners = claims.get(key);
		if (owners != null) {
			owners.remove(owner);
			if (owners.isEmpty()) {
				claims.remove(key);
			}
		}
	}

	private static void removeOwner(Map<KeyMapping, Set<Object>> claims, Object owner,
	                                Set<KeyMapping> changed) {
		for (KeyMapping key : new ArrayList<>(claims.keySet())) {
			Set<Object> owners = claims.get(key);
			if (owners != null && owners.remove(owner)) {
				changed.add(key);
				if (owners.isEmpty()) {
					claims.remove(key);
				}
			}
		}
	}

	private static void apply(Minecraft client, KeyMapping key) {
		if (client == null || key == null) {
			return;
		}
		Set<Object> suppressors = SUPPRESSORS.get(key);
		if (suppressors != null && !suppressors.isEmpty()) {
			key.setDown(false);
			return;
		}
		Set<Object> holders = HOLDERS.get(key);
		key.setDown(holders != null && !holders.isEmpty() || isPhysicallyDown(client, key));
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

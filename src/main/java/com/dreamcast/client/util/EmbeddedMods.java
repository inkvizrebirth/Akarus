package com.dreamcast.client.util;

import com.dreamcast.client.DreamcastClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Constructor;

/**
 * Мостик к встроенным модам (Sodium, Mod Menu и др.), вшитым в наш jar.
 *
 * <p>Мы не компилируемся против их классов — вызовы через reflection. Если
 * мод по какой-то причине не вшит (собрали без libs/) или его API сменился,
 * кнопка просто не появится: клиент работает и без него.</p>
 */
public final class EmbeddedMods {

	private EmbeddedMods() {
	}

	/** Есть ли на борту Mod Menu (кнопка «Моды» в настройках). */
	public static boolean modMenuPresent() {
		return findFirst(
				"com.terraformersmc.modmenu.gui.ModsScreen",
				"com.terraformersmc.modmenu.ModMenu") != null;
	}

	/** Есть ли на борту Sodium (кнопка «Графика»). */
	public static boolean sodiumPresent() {
		return findFirst(
				"me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI",
				"net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI") != null;
	}

	/** Открывает экран встроенного мода; {@code false} — мод не найден. */
	public static boolean open(String kind, Screen parent) {
		String[] candidates = switch (kind) {
			case "modmenu" -> new String[]{
					"com.terraformersmc.modmenu.gui.ModsScreen",
					"com.terraformersmc.modmenu.ModMenu"};
			case "sodium" -> new String[]{
					"me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI",
					"net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI"};
			default -> new String[0];
		};
		Class<?> screenClass = findFirst(candidates);
		if (screenClass == null) {
			return false;
		}
		try {
			Constructor<?> ctor = screenClass.getConstructor(Screen.class);
			Screen screen = (Screen) ctor.newInstance(parent);
			Minecraft.getInstance().gui.setScreen(screen);
			return true;
		} catch (NoSuchMethodException noCtor) {
			try {
				// У некоторых версий конструктор без аргументов
				Constructor<?> ctor = screenClass.getConstructor();
				Screen screen = (Screen) ctor.newInstance();
				Minecraft.getInstance().gui.setScreen(screen);
				return true;
			} catch (Exception ignored) {
				return false;
			}
		} catch (Exception error) {
			DreamcastClient.LOGGER.warn("Не удалось открыть экран встроенного мода {}: {}", kind, error.toString());
			return false;
		}
	}

	@Nullable
	private static Class<?> findFirst(String... names) {
		for (String name : names) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ignored) {
				// пробуем следующее имя
			}
		}
		return null;
	}
}

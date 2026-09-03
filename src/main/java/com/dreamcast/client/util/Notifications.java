package com.dreamcast.client.util;

import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Уведомления клиента: «модуль включился/выключился», сохранение конфига и т.п.
 *
 * Живут своей жизнью: {@link #tick()} считает время (вызывается из клиентского
 * тика), а отрисовка спрашивает {@link #snapshot()} каждый кадр и рисует
 * плашки с анимацией въезда/выезда (сдвиг + прозрачность, easing).
 */
public final class Notifications {

	public enum Type {
		INFO, OK, WARN, ERROR
	}

	/** Одно уведомление: неизменяемые данные + состояние анимации. */
	public static final class Notification {
		public final String title;
		public final String message;
		public final Type type;
		final long createdAt = Util.getMillis();
		long dismissedAt = Long.MAX_VALUE;
		/** 0..1 — прогресс появления, обновляется при отрисовке. */
		public float appear = 0.0f;

		Notification(String title, String message, Type type) {
			this.title = title;
			this.message = message;
			this.type = type;
		}

		public long ageMillis() {
			return Util.getMillis() - createdAt;
		}

		void dismiss() {
			if (dismissedAt == Long.MAX_VALUE) {
				dismissedAt = Util.getMillis();
			}
		}

		public boolean dismissed() {
			return dismissedAt != Long.MAX_VALUE;
		}

		/** Прогресс исчезновения 0..1 (1 — пора удалять). */
		public float dismissProgress() {
			if (!dismissed()) {
				return 0.0f;
			}
			return Math.min(1.0f, (Util.getMillis() - dismissedAt) / 260.0f);
		}
	}

	private static final int MAX_SHOWN = 5;
	private static final long LIFE_MILLIS = 2600;
	private static final List<Notification> ITEMS = new ArrayList<>();

	private Notifications() {
	}

	public static void push(String title, String message, Type type) {
		ITEMS.add(new Notification(title, message, type));
		while (ITEMS.size() > MAX_SHOWN + 3) {
			ITEMS.remove(0);
		}
	}

	public static void info(String title, String message) {
		push(title, message, Type.INFO);
	}

	public static void ok(String title, String message) {
		push(title, message, Type.OK);
	}

	public static void warn(String title, String message) {
		push(title, message, Type.WARN);
	}

	public static void error(String title, String message) {
		push(title, message, Type.ERROR);
	}

	/** Вызывается из клиентского тика: включает таймер исчезновения. */
	public static void tick() {
		for (Notification item : ITEMS) {
			if (item.ageMillis() > LIFE_MILLIS) {
				item.dismiss();
			}
		}
		ITEMS.removeIf(item -> item.dismissProgress() >= 1.0f);
	}

	/** Копия для отрисовки (новые — внизу списка, рисуются снизу вверх). */
	public static List<Notification> snapshot() {
		return new ArrayList<>(ITEMS);
	}

	public static boolean isEmpty() {
		return ITEMS.isEmpty();
	}

	/** Чтобы старые плашки не висели вечно при выходе из мира. */
	public static void clear() {
		for (Notification item : ITEMS) {
			item.dismiss();
		}
	}

	/** Только для тестов/отладки: заполнить примерами. */
	public static void debugSample() {
		ok("Модуль", "KillAura — включён");
		info("Модуль", "FreeCam — выключен");
		warn("Конфиг", "Настройки сохранены");
	}

	static Iterator<Notification> iterator() {
		return ITEMS.iterator();
	}
}

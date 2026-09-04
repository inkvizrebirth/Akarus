package com.dreamcast.client.util;

/**
 * Решения конечного автомата «пить до конца» (AutoBuff, но без Minecraft-типов —
 * покрывается юнит-тестами).
 *
 * <p>Инвариант: клавишу «использовать» нельзя отпускать раньше, чем за тик
 * до конца питья — иначе ваниль считает это отменой. И нельзя держать после
 * конца — следующий предмет попадёт под повторное использование.</p>
 */
public final class DrinkLogic {

	private DrinkLogic() {
	}

	/**
	 * Закончено ли питьё. Нормальное завершение — ТОЛЬКО переход
	 * {@code isUsingItem(): true → false}: досрочный отпуск по remainingTicks
	 * отменял последний тик использования, и предмет тратился без эффекта.
	 *
	 * @param sawUsing   мы видели, что использование началось (isUsingItem был true)
	 * @param usingNow   использование идёт прямо сейчас
	 * @param elapsedMs  сколько уже держим
	 * @param timeoutMs  общий таймаут (сервер не дал начать/закончить)
	 */
	public static boolean finished(boolean sawUsing, boolean usingNow,
	                               long elapsedMs, long timeoutMs) {
		if (elapsedMs >= timeoutMs) {
			return true; // отказ сервера/залипание — отпускаем и откатываем
		}
		if (!sawUsing) {
			return false; // ещё не начинали — держим дальше (или ждём старта)
		}
		return !usingNow;
	}

	/**
	 * Так и не начали использовать за отведённое время (кулдаун предмета,
	 * отказ сервера, не тот предмет в руке) — пора откатиться.
	 */
	public static boolean neverStarted(boolean sawUsing, long elapsedMs, long startTimeoutMs) {
		return !sawUsing && elapsedMs >= startTimeoutMs;
	}

	/**
	 * Человекочитаемая пауза легит-режима: устойчивая для конкретного модуля
	 * (не случайная на каждый вызов), но разная у разных экземпляров.
	 */
	public static int humanPause(int seed, int minMs, int maxMs) {
		int span = Math.max(0, maxMs - minMs);
		int mixed = seed * 0x9E3779B9;
		if (mixed == Integer.MIN_VALUE) {
			mixed = 0;
		}
		return minMs + Math.abs(mixed % (span + 1));
	}
}

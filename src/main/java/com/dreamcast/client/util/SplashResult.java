package com.dreamcast.client.util;

/**
 * Ожидание результата броска взрывного зелья (чистая логика).
 *
 * <p>Бросок подтверждается по изменению стак в руке (количество −1 или смена
 * предмета) — взрывное зелье применяется мгновенно, без удержания клавиши.
 * Эффект может появиться с задержкой, пока снаряд летит, — это НЕ ошибка:
 * подтверждение опирается только на предмет, эффект не проверяется.</p>
 */
public final class SplashResult {

	private SplashResult() {
	}

	public enum State {
		PENDING,   // летит/ждём изменения стака
		CONFIRMED, // стак изменился — бросок прошёл
		TIMEOUT    // ничего не изменилось за отведённое время — откатываемся
	}

	/**
	 * @param itemChanged      стак в руке отличается от снимка до броска
	 * @param ticksSinceThrow  тиков с момента useItem
	 * @param timeoutTicks     сколько тиков ждать подтверждения
	 */
	public static State evaluate(boolean itemChanged, long ticksSinceThrow, long timeoutTicks) {
		if (itemChanged) {
			return State.CONFIRMED;
		}
		return ticksSinceThrow >= timeoutTicks ? State.TIMEOUT : State.PENDING;
	}
}

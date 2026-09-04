package com.dreamcast.client.util;

/**
 * Чистые решения стейт-машины NoFallDamage (без типов Minecraft — тестируется юнит-тестами).
 *
 * <p>Покрывает три критичных решения: когда падение считать опасным, что делать
 * в фазе ожидания подтверждения воды (PLACING) и когда собирать воду (RETRACT).</p>
 */
public final class NoFallLogic {

	private NoFallLogic() {
	}

	/**
	 * Опасно ли падение прямо сейчас: порог урона достигнут и нет смягчающих
	 * условий (вода, лестница, элитры, пассажир, земля).
	 *
	 * @param threshold порог fallDistance, с которого начинается урон (обычно 3.0)
	 */
	public static boolean dangerousFall(float fallDistance, float threshold, boolean onGround,
	                                    boolean inWater, boolean onClimbable,
	                                    boolean fallFlying, boolean passenger) {
		return fallDistance >= threshold && !onGround && !inWater
				&& !onClimbable && !fallFlying && !passenger;
	}

	/** Действие фазы PLACING — ждём подтверждения, что вода реально появилась. */
	public enum PlacingAction {
		CONFIRM,   // источник на месте → PLACED
		ABORT,     // уже не падаем / таймаут → выйти
		WAIT,      // сервер клик принял (ведро пусто) — ждём синхронизацию жидкости
		RETRY      // клик, видимо, не прошёл → повторить (с паузой retryDelay)
	}

	/**
	 * Решение фазы PLACING на этот тик.
	 *
	 * @param waterPresent   наш источник воды уже стоит в мире
	 * @param stillFalling   игрок всё ещё в опасном падении
	 * @param bucketEmpty    в руке пустое ведро (сервер принял выливание)
	 */
	public static PlacingAction placingAction(boolean waterPresent, boolean stillFalling,
	                                          boolean bucketEmpty) {
		if (waterPresent) {
			return PlacingAction.CONFIRM;
		}
		if (!stillFalling) {
			return PlacingAction.ABORT;
		}
		if (bucketEmpty) {
			// Пустым ведром кликать нельзя — зачерпнём только что поставленную
			// воду; ждём появления источника до таймаута
			return PlacingAction.WAIT;
		}
		return PlacingAction.RETRY;
	}

	/** Действие фазы RETRACT — сбор воды после подтверждённой посадки. */
	public enum RetractAction {
		SIPHON,   // собрать источник
		ABORT     // источника нет или таймаут — выйти
	}

	public static RetractAction retractAction(boolean waterPresent, boolean timedOut) {
		return waterPresent && !timedOut ? RetractAction.SIPHON : RetractAction.ABORT;
	}
}

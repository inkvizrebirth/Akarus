package com.dreamcast.client.util;

/**
 * Чистое решение удержания цели KillAura (sticky/switch) — без типов Minecraft,
 * чтобы семантика переключения покрывалась юнит-тестами.
 *
 * <p>Оценка всегда «меньше — лучше» (дистанция, HP, угол). Sticky держит
 * текущую цель, пока новая не лучше хотя бы на 20 %; Switch меняет цель
 * свободно, но не чаще, чем раз в switchDelay тиков.</p>
 */
public final class TargetLockLogic {

	private TargetLockLogic() {
	}

	/** Итог: держим ли текущую цель и каким становится счётчик удержания. */
	public record Decision(boolean keepCurrent, int lockTicks) {
	}

	/**
	 * @param lockEnabled    режим удержания включён (sticky или switch)
	 * @param currentValid   текущая цель жива и проходит фильтры
	 * @param sameAsBest     лучшая цель совпадает с текущей
	 * @param lockTicksLeft  остаток счётчика удержания (тиков)
	 * @param switchDelay    задержка смены цели (тиков)
	 * @param sticky         режим sticky (иначе switch)
	 * @param bestScore      оценка лучшей кандидатки (меньше — лучше)
	 * @param currentScore   оценка текущей цели (меньше — лучше)
	 */
	public static Decision decide(boolean lockEnabled, boolean currentValid, boolean sameAsBest,
	                              int lockTicksLeft, int switchDelay, boolean sticky,
	                              double bestScore, double currentScore) {
		if (!lockEnabled) {
			return new Decision(false, lockTicksLeft);
		}
		if (!currentValid) {
			// цель умерла/вышла из радиуса — сразу берём лучшую
			return new Decision(false, switchDelay);
		}
		if (sameAsBest) {
			return new Decision(true, switchDelay);
		}
		if (lockTicksLeft > 0) {
			// задержка смены ещё не истекла — держимся
			return new Decision(true, lockTicksLeft - 1);
		}
		// sticky: меняем только если новая заметно лучше (≥20 % запаса)
		if (sticky && bestScore > currentScore * 0.8) {
			return new Decision(true, lockTicksLeft);
		}
		return new Decision(false, switchDelay);
	}
}

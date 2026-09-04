package com.dreamcast.client.util;

import com.dreamcast.client.util.SplashResult.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ожидание результата броска взрывного зелья: подтверждение по изменению
 * стака; задержка эффекта (снаряд летит) — не ошибка; таймаут — откат.
 */
class SplashResultTest {

	@Test
	void itemChangeConfirmsImmediately() {
		assertEquals(State.CONFIRMED, SplashResult.evaluate(true, 0, 30));
		assertEquals(State.CONFIRMED, SplashResult.evaluate(true, 25, 30));
	}

	@Test
	void flightDelayIsPendingNotError() {
		// эффект ещё не подействовал (снаряд в полёте), стак пока тот же
		assertEquals(State.PENDING, SplashResult.evaluate(false, 0, 30));
		assertEquals(State.PENDING, SplashResult.evaluate(false, 10, 30));
		assertEquals(State.PENDING, SplashResult.evaluate(false, 29, 30));
	}

	@Test
	void timeoutAfterAllottedTicks() {
		assertEquals(State.TIMEOUT, SplashResult.evaluate(false, 30, 30));
		assertEquals(State.TIMEOUT, SplashResult.evaluate(false, 60, 30));
	}
}

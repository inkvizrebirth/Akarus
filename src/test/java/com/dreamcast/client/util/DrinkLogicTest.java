package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полный цикл питья AutoBuff: клавишу «использовать» нельзя отпускать, пока
 * использование не завершится (иначе ваниль считает это отменой — зелье не
 * выпивается), но и держать после конца нельзя (следующий предмет попадёт
 * под повторное использование). Отдельно — отказ сервера по таймауту.
 */
class DrinkLogicTest {

	@Test
	void keepsHoldingWhileDrinking() {
		// начали, пьём — держим, сколько бы тиков ни оставалось
		assertFalse(DrinkLogic.finished(true, true, 1000, 8000));
	}

	@Test
	void holdsThroughTheLastTick() {
		// ДОСРОЧНЫЙ отпуск отменял последний тик использования — предмет
		// тратился без эффекта. Держим до перехода isUsingItem(): true → false
		assertFalse(DrinkLogic.finished(true, true, 1400, 8000));
	}

	@Test
	void finishesWhenUseEnds() {
		// использование само погасло — предмет допит/сорван
		assertTrue(DrinkLogic.finished(true, false, 2000, 8000));
	}

	@Test
	void waitsWhenUseHasNotStartedYet() {
		// только нажали — пакет ещё в полёте, isUsingItem ещё false
		assertFalse(DrinkLogic.finished(false, false, 200, 8000));
	}

	@Test
	void timeoutBreaksStuckDrink() {
		// сервер не даёт завершить (залипло использование) — таймаут решает
		assertTrue(DrinkLogic.finished(true, true, 9000, 8000));
	}

	@Test
	void neverStartedAbortsAfterStartTimeout() {
		assertTrue(DrinkLogic.neverStarted(false, 700, 600));
		assertFalse(DrinkLogic.neverStarted(false, 100, 600), "рано сдаваться");
		assertFalse(DrinkLogic.neverStarted(true, 10_000, 600),
				"если использование началось — это не «не начали»");
	}

	@Test
	void humanPauseStaysInRangeAndStable() {
		int seed = 42;
		int first = DrinkLogic.humanPause(seed, 200, 480);
		assertEqualsSeeds(first, DrinkLogic.humanPause(seed, 200, 480));
		for (int s = 0; s < 64; s++) {
			int pause = DrinkLogic.humanPause(s, 200, 480);
			assertTrue(pause >= 200 && pause <= 480, "пауза вне диапазона: " + pause);
		}
	}

	private static void assertEqualsSeeds(int expected, int actual) {
		assertTrue(expected == actual, "пауза должна быть стабильной для одного seed");
	}
}

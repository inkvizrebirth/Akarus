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
		// начали, пьём, осталось много тиков — держим
		assertFalse(DrinkLogic.finished(true, true, 20, 1000, 8000));
	}

	@Test
	void releasesOneTickBeforeCompletion() {
		// осталось ≤1 тика: отпускем ЗАРАНЕЕ, чтобы после завершения ваниль
		// не посчитала клавишу зажатой и не использовала следующий предмет
		assertTrue(DrinkLogic.finished(true, true, 1, 2000, 8000));
		assertTrue(DrinkLogic.finished(true, true, 0, 2000, 8000));
	}

	@Test
	void finishesWhenUseEnds() {
		// использование само погасло — предмет допит/сорван
		assertTrue(DrinkLogic.finished(true, false, Integer.MAX_VALUE, 2000, 8000));
	}

	@Test
	void waitsWhenUseHasNotStartedYet() {
		// только нажали — пакет ещё в полёте, isUsingItem ещё false
		assertFalse(DrinkLogic.finished(false, false, Integer.MAX_VALUE, 200, 8000));
	}

	@Test
	void timeoutBreaksStuckDrink() {
		// сервер не даёт завершить (залипло использование) — таймаут решает
		assertTrue(DrinkLogic.finished(true, true, 20, 9000, 8000));
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

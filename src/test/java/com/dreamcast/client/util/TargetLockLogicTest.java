package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static com.dreamcast.client.util.TargetLockLogic.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sticky/Switch удержание цели KillAura: без дёргания между равными целями,
 * смена только при заметном перевесе или по истечении задержки.
 */
class TargetLockLogicTest {

	@Test
	void offModeAlwaysTakesBest() {
		var d = decide(false, true, false, 5, 10, true, 1.0, 9.0);
		assertFalse(d.keepCurrent());
		assertEquals(5, d.lockTicks(), "счётчик не трогаем");
	}

	@Test
	void invalidCurrentSwitchesImmediately() {
		var d = decide(true, false, false, 7, 10, true, 2.0, 1.0);
		assertFalse(d.keepCurrent());
		assertEquals(10, d.lockTicks(), "счётчик перезапускается");
	}

	@Test
	void sameTargetRefreshesCounter() {
		var d = decide(true, true, true, 0, 12, false, 3.0, 3.0);
		assertTrue(d.keepCurrent());
		assertEquals(12, d.lockTicks());
	}

	@Test
	void switchWaitsOutDelayThenChanges() {
		// 3 тика задержки ещё идут — держим текущую
		var d = decide(true, true, false, 3, 3, false, 1.0, 9.0);
		assertTrue(d.keepCurrent());
		assertEquals(2, d.lockTicks());
		// задержка кончилась — новая цель лучше, меняем
		d = decide(true, true, false, 0, 3, false, 1.0, 9.0);
		assertFalse(d.keepCurrent());
	}

	@Test
	void stickyHoldsWhileNewTargetNotBetterBy20Percent() {
		// новая на 10 % лучше — sticky держит текущую (дистанции: меньше лучше)
		var d = decide(true, true, false, 0, 0, true, 9.0, 10.0);
		assertTrue(d.keepCurrent(), "перевес меньше 20 % — не переключаемся");
		// новая на 25 % лучше — переключаемся
		d = decide(true, true, false, 0, 0, true, 7.0, 10.0);
		assertFalse(d.keepCurrent());
		// новая хуже — конечно держим
		d = decide(true, true, false, 0, 0, true, 15.0, 10.0);
		assertTrue(d.keepCurrent());
	}

	@Test
	void switchModeIgnoresScore() {
		// switch (не sticky): без задержки меняемся даже при минимальном перевесе
		var d = decide(true, true, false, 0, 0, false, 9.9, 10.0);
		assertFalse(d.keepCurrent());
	}
}

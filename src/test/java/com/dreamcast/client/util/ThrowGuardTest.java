package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гварды старта броска взрывного зелья: каждое условие по отдельности
 * обязано блокировать бросок (анти-спам, GUI, контейнер, воздух, движение).
 */
class ThrowGuardTest {

	private static final boolean[] ALL_OK = {
			true,  // idle
			true,  // cooldown прошёл
			true,  // предыдущий подтверждён
			true,  // gui закрыт
			true,  // инвентарь игрока
			true,  // не использует предмет
			true,  // жив
			true,  // земля ок
			true,  // движение ок
			true,  // поверхность под ногами
	};

	private static boolean throwWith(int flagIndex, boolean value) {
		boolean[] flags = ALL_OK.clone();
		flags[flagIndex] = value;
		return ThrowGuard.canStartThrow(
				flags[0], flags[1], flags[2], flags[3], flags[4],
				flags[5], flags[6], flags[7], flags[8], flags[9]);
	}

	@Test
	void allClearAllowsThrow() {
		assertTrue(ThrowGuard.canStartThrow(
				true, true, true, true, true, true, true, true, true, true));
	}

	@Test
	void anyViolationBlocksThrow() {
		String[] names = {"не IDLE", "кулдаун", "предыдущий не подтверждён", "GUI открыт",
				"чужой контейнер", "использует предмет", "мёртв", "не на земле",
				"в движении", "пустота под ногами"};
		for (int i = 0; i < names.length; i++) {
			assertFalse(throwWith(i, false), names[i] + " должен блокировать бросок");
		}
	}

	@Test
	void activeEffectOrFlightBlocksRethrow() {
		// эффект активен дольше порога / зелье ещё летит → машина не в IDLE
		// или предыдущий бросок не подтверждён
		assertFalse(throwWith(0, false), "машина занята");
		assertFalse(throwWith(2, false), "предыдущий бросок не подтверждён");
	}

	@Test
	void voidBelowBlocksThrow() {
		assertFalse(ThrowGuard.groundBelow(false));
		assertTrue(ThrowGuard.groundBelow(true));
	}
}

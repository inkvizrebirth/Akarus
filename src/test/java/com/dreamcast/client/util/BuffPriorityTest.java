package com.dreamcast.client.util;

import com.dreamcast.client.util.BuffPriority.Target;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Приоритет действий AutoBuff: лечение → огнестойкость (горит/кончается) →
 * сила → скорость → регенерация → ночное зрение → золотое яблоко.
 */
class BuffPriorityTest {

	@Test
	void healComesFirstAtLowHp() {
		assertEquals(Target.HEAL, BuffPriority.pick(
				true, true, true,
				true, true,
				true, true, true, true, true));
	}

	@Test
	void noHealWithoutLowHpOrPotion() {
		assertEquals(Target.FIRE_RESISTANCE, BuffPriority.pick(
				false, true, true,
				true, true,
				true, true, true, true, true));
		assertEquals(Target.STRENGTH, BuffPriority.pick(
				false, true, false,
				false, true,
				true, true, true, true, true));
	}

	@Test
	void fireResBeatsStrengthOnlyWhenUrgent() {
		assertEquals(Target.FIRE_RESISTANCE, BuffPriority.pick(
				false, true, true, true, true,
				true, true, true, true, true));
		assertEquals(Target.STRENGTH, BuffPriority.pick(
				false, true, true, false, true,
				true, true, true, true, true));
	}

	@Test
	void strengthSpeedRegenNightVisionOrder() {
		assertEquals(Target.STRENGTH, BuffPriority.pick(
				false, false, false, false, false,
				true, true, true, true, true));
		assertEquals(Target.SPEED, BuffPriority.pick(
				false, false, false, false, false,
				false, true, true, true, true));
		assertEquals(Target.REGENERATION, BuffPriority.pick(
				false, false, false, false, false,
				false, false, true, true, true));
		assertEquals(Target.NIGHT_VISION, BuffPriority.pick(
				false, false, false, false, false,
				false, false, false, true, true));
	}

	@Test
	void gappleIsLastResortHeal() {
		// лечение выключено/зелий нет, прочих баффов не надо — яблоко
		assertEquals(Target.GOLDEN_APPLE, BuffPriority.pick(
				true, false, false,
				false, false,
				false, false, false, false, true));
	}

	@Test
	void gappleNotUsedWhenHealthy() {
		assertEquals(Target.NONE, BuffPriority.pick(
				false, false, false,
				false, false,
				false, false, false, false, true));
	}

	@Test
	void nothingToDoYieldsNone() {
		assertEquals(Target.NONE, BuffPriority.pick(
				true, true, false,
				true, false,
				false, false, false, false, false));
	}
}

package com.dreamcast.client.util;

import com.dreamcast.client.util.PotionLogic.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выбор способа применения зелья (пить/бросать) в каждом режиме использования
 * и фильтрация вредных зелий.
 */
class PotionLogicTest {

	@Test
	void autoPrefersSplashWhenTold() {
		assertEquals(Kind.SPLASH, PotionLogic.pick("auto", true, true, true));
		// взрывного нет — пьём
		assertEquals(Kind.DRINK, PotionLogic.pick("auto", true, false, true));
		// питьевого нет — бросаем
		assertEquals(Kind.SPLASH, PotionLogic.pick("auto", true, true, false));
	}

	@Test
	void autoWithoutSplashPriorityPrefersDrink() {
		assertEquals(Kind.DRINK, PotionLogic.pick("auto", false, true, true));
		assertEquals(Kind.SPLASH, PotionLogic.pick("auto", false, true, false));
	}

	@Test
	void drinkOnlyNeverThrows() {
		assertEquals(Kind.DRINK, PotionLogic.pick("drink_only", true, true, true));
		assertEquals(Kind.DRINK, PotionLogic.pick("drink_only", true, false, true));
		assertNull(PotionLogic.pick("drink_only", true, true, false), "нет питьевого — нет действия");
	}

	@Test
	void splashOnlyNeverDrinks() {
		assertEquals(Kind.SPLASH, PotionLogic.pick("splash_only", false, true, true));
		assertNull(PotionLogic.pick("splash_only", false, false, true), "нет взрывного — нет действия");
	}

	@Test
	void preferDrinkFallsBackToSplash() {
		assertEquals(Kind.DRINK, PotionLogic.pick("prefer_drink", true, true, true));
		assertEquals(Kind.SPLASH, PotionLogic.pick("prefer_drink", true, true, false));
		assertNull(PotionLogic.pick("prefer_drink", true, false, false));
	}

	@Test
	void nothingAvailableYieldsNull() {
		assertNull(PotionLogic.pick("auto", true, false, false));
		assertNull(PotionLogic.pick(null, true, false, false));
	}

	// ---- фильтрация вредных ----

	@Test
	void knownHarmfulIdsAreRejected() {
		assertTrue(PotionLogic.harmful("weakness", false));
		assertTrue(PotionLogic.harmful("slowness", false));
		assertTrue(PotionLogic.harmful("poison", false));
		assertTrue(PotionLogic.harmful("instant_damage", false));
		assertTrue(PotionLogic.harmful("harm", false), "старое имя мгновенного урона");
		assertTrue(PotionLogic.harmful("wither", false));
	}

	@Test
	void harmfulCategoryIsRejectedEvenIfIdUnknown() {
		// категория HARMFUL покрывает и будущие/модовые эффекты
		assertTrue(PotionLogic.harmful("whatever_custom", true));
	}

	@Test
	void beneficialEffectsPass() {
		assertFalse(PotionLogic.harmful("speed", false));
		assertFalse(PotionLogic.harmful("strength", false));
		assertFalse(PotionLogic.harmful("fire_resistance", false));
		assertFalse(PotionLogic.harmful("instant_health", false));
		assertFalse(PotionLogic.harmful(null, false));
	}
}

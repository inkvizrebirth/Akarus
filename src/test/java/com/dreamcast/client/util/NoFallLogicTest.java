package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static com.dreamcast.client.util.NoFallLogic.PlacingAction;
import static com.dreamcast.client.util.NoFallLogic.RetractAction;
import static com.dreamcast.client.util.NoFallLogic.dangerousFall;
import static com.dreamcast.client.util.NoFallLogic.placingAction;
import static com.dreamcast.client.util.NoFallLogic.retractAction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Стейт-машина ватердропа NoFallDamage: порог опасного падения, подтверждение
 * постановки воды (PLACING) и решение сбора (RETRACT).
 *
 * <p>Ключевой сценарий, который раньше ломался: клик «в воздух» считался
 * успехом. Теперь CONFIRM происходит только когда источник реально стоит, а
 * пустое ведро (сервер принял выливание) запрещает повторный клик — иначе
 * зачерпнули бы только что поставленную воду.</p>
 */
class NoFallLogicTest {

	// ---- опасность падения ----

	@Test
	void fallIsDangerousAtDamageThreshold() {
		assertTrue(dangerousFall(3.0, 3.0, false, false, false, false, false));
		assertTrue(dangerousFall(12.5, 3.0, false, false, false, false, false));
	}

	@Test
	void shortFallIsSafe() {
		assertFalse(dangerousFall(2.99, 3.0, false, false, false, false, false));
	}

	@Test
	void mitigationsCancelDanger() {
		assertFalse(dangerousFall(10.0, 3.0, true, false, false, false, false), "земля");
		assertFalse(dangerousFall(10.0, 3.0, false, true, false, false, false), "вода");
		assertFalse(dangerousFall(10.0, 3.0, false, false, true, false, false), "лестница");
		assertFalse(dangerousFall(10.0, 3.0, false, false, false, true, false), "элитры");
		assertFalse(dangerousFall(10.0, 3.0, false, false, false, false, true), "пассажир");
	}

	// ---- фаза PLACING: подтверждение постановки ----

	@Test
	void waterAppearingConfirmsPlacement() {
		assertEquals(PlacingAction.CONFIRM, placingAction(true, true, false));
		assertEquals(PlacingAction.CONFIRM, placingAction(true, false, true),
				"вода стоит — подтверждаем даже если уже не падаем");
	}

	@Test
	void landingOutsideWaterAborts() {
		assertEquals(PlacingAction.ABORT, placingAction(false, false, false));
		assertEquals(PlacingAction.ABORT, placingAction(false, false, true));
	}

	@Test
	void emptyBucketWaitsInsteadOfReclicking() {
		// сервер принял выливание — повторный клик пустым ведром зачерпнул бы
		// только что поставленную воду; единственно верное действие — ждать
		assertEquals(PlacingAction.WAIT, placingAction(false, true, true));
	}

	@Test
	void noWaterNoAcceptRetriesClick() {
		assertEquals(PlacingAction.RETRY, placingAction(false, true, false));
	}

	// ---- фаза RETRACT: сбор воды ----

	@Test
	void siphonOnlyWhileSourceExists() {
		assertEquals(RetractAction.SIPHON, retractAction(true, false));
	}

	@Test
	void retractAbortsOnMissingWaterOrTimeout() {
		assertEquals(RetractAction.ABORT, retractAction(false, false), "источник исчез");
		assertEquals(RetractAction.ABORT, retractAction(true, true), "таймаут");
		assertEquals(RetractAction.ABORT, retractAction(false, true));
	}
}

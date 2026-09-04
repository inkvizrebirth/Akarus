package com.dreamcast.client.util;

import com.dreamcast.client.util.RestorePlan.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Порядок восстановления после действия AutoBuff: предмет резервного слота →
 * выбранный слот → взгляд. Прерывание (выключение посреди действия) проходит
 * все шаги немедленно.
 */
class RestorePlanTest {

	@Test
	void orderIsSwapThenSlotThenRotation() {
		RestorePlan plan = new RestorePlan(true);
		assertEquals(Step.SWAP_BACK, plan.peek());
		plan.advance();
		assertEquals(Step.RESTORE_SLOT, plan.peek());
		plan.advance();
		assertEquals(Step.RESTORE_ROTATION, plan.peek());
		assertFalse(plan.finished());
		plan.advance();
		assertEquals(Step.DONE, plan.peek());
		assertTrue(plan.finished());
	}

	@Test
	void noSwapSkipsItemStep() {
		// предмет был в хотбаре — возвращать нечего, только слот и взгляд
		RestorePlan plan = new RestorePlan(false);
		assertEquals(Step.RESTORE_SLOT, plan.peek());
		plan.advance();
		plan.advance();
		assertTrue(plan.finished());
	}

	@Test
	void slotNeverRestoresBeforeItemIsBack() {
		// предмет резервного слота обязан вернуться ДО восстановления слота:
		// иначе выбранным окажется не тот предмет
		RestorePlan plan = new RestorePlan(true);
		plan.advance();
		assertEquals(Step.RESTORE_SLOT, plan.peek(), "после SWAP_BACK идёт слот");
	}

	@Test
	void interruptIsFlaggedAndIdempotent() {
		RestorePlan plan = new RestorePlan(true);
		plan.interrupt();
		assertTrue(plan.isInterrupted());
		plan.interrupt();
		assertTrue(plan.isInterrupted(), "повторное прерывание безопасно");
		// порядок шагов при прерывании сохранён
		assertEquals(Step.SWAP_BACK, plan.peek());
		plan.advance();
		assertEquals(Step.RESTORE_SLOT, plan.peek());
		plan.advance();
		assertEquals(Step.RESTORE_ROTATION, plan.peek());
	}

	@Test
	void advancePastEndIsSafe() {
		RestorePlan plan = new RestorePlan(false);
		plan.advance();
		plan.advance();
		plan.advance();
		plan.advance();
		assertEquals(Step.DONE, plan.peek());
		assertTrue(plan.finished());
	}
}

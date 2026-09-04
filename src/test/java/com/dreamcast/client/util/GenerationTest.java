package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Отмена фоновой загрузки экрана миров: пока экран жив — билет действует;
 * после removed() все выданные раньше билеты гасятся, повторное открытие не
 * реанимирует старые задачи.
 */
class GenerationTest {

	@Test
	void freshTicketIsValid() {
		Generation generation = new Generation();
		Generation.Ticket ticket = generation.start();
		assertTrue(generation.valid(ticket));
	}

	@Test
	void invalidateKillsOutstandingTickets() {
		Generation generation = new Generation();
		Generation.Ticket loading = generation.start();
		generation.invalidate(); // removed()
		assertFalse(generation.valid(loading), "задача, начатая до закрытия, должна игнорироваться");
	}

	@Test
	void reopenedScreenIssuesNewTickets() {
		Generation generation = new Generation();
		Generation.Ticket first = generation.start();
		generation.invalidate();
		Generation.Ticket second = generation.start();
		assertFalse(generation.valid(first));
		assertTrue(generation.valid(second));
	}

	@Test
	void multipleInvalidationsKeepAllOldTicketsDead() {
		Generation generation = new Generation();
		Generation.Ticket a = generation.start();
		generation.invalidate();
		Generation.Ticket b = generation.start();
		generation.invalidate();
		assertFalse(generation.valid(a));
		assertFalse(generation.valid(b));
	}
}

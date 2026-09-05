package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила AutoGG: «это моё убийство», подтверждение смерти и текст в чат.
 *
 * <p>Именно эти четыре функции решают, напишет мод GG или замолчит/напишет
 * лишнее. Клиенту не приходит «кто кого убил», поэтому всё держится на окне
 * после нашего удара — и окно, и санитайзинг строки надо проверять, а не
 * проверять глазами в игре.</p>
 */
class AutoGgLogicTest {

	@Test
	void freshHitCountsAsOurKill() {
		assertTrue(AutoGgLogic.ourKill(10_000L, 9_000L, 2_500L));
		assertFalse(AutoGgLogic.ourKill(10_000L, 5_000L, 2_500L), "удар был слишком давно");
		assertFalse(AutoGgLogic.ourKill(10_000L, 0L, 2_500L), "мы вообще не били");
		assertFalse(AutoGgLogic.ourKill(10_000L, 9_500L, 0L), "окно выключено — не пишем никому");
	}

	@Test
	void clockJumpDoesNotSilenceGg() {
		// часы могли прыгнуть (синхронизация): since < 0 — считаем свежим ударом
		assertTrue(AutoGgLogic.ourKill(9_000L, 10_000L, 2_500L));
	}

	@Test
	void deathNeedsConfirmation() {
		assertTrue(AutoGgLogic.dead(false, false, false), "сущности нет в мире");
		assertTrue(AutoGgLogic.dead(true, false, false), "мертв, но ещё в мире (анимация)");
		assertTrue(AutoGgLogic.dead(true, true, true), "удалён");
		assertFalse(AutoGgLogic.dead(true, true, false), "жив — GG писать рано");
	}

	@Test
	void messageWaitsForDelay() {
		assertFalse(AutoGgLogic.due(1_000L, 1_500L));
		assertTrue(AutoGgLogic.due(1_500L, 1_500L), "ровно в срок — пора");
		assertEquals(0L, AutoGgLogic.delayMillis(-5));
		assertEquals(500L, AutoGgLogic.delayMillis(500));
	}

	@Test
	void templatePlaceholdersAreReplaced() {
		assertEquals("*Steve* GG", AutoGgLogic.format("*%player%* GG", "Steve"));
		assertEquals("Steve GG", AutoGgLogic.format("%name% GG", "Steve"));
		assertEquals("gg Steve, gg", AutoGgLogic.format("gg %player%, %name%", "Steve"));
		assertEquals("EZ", AutoGgLogic.format("EZ", "Steve"), "шаблон без плейсхолдеров — как есть");
		assertEquals("GG", AutoGgLogic.format("%player% GG", null), "без ника — только текст");
	}

	@Test
	void messageIsSanitized() {
		assertEquals("a b", AutoGgLogic.format("a\nb", "x"));
		assertEquals("a b", AutoGgLogic.format("a\r\n\tb", "x"));
		assertEquals("a", AutoGgLogic.format("  a\n  ", "x"), "обрамляющие пробелы съедаются");
		String longText = "y".repeat(400);
		assertEquals(AutoGgLogic.MAX_MESSAGE_LENGTH, AutoGgLogic.format(longText, "x").length());
	}

	@Test
	void emptyMessageIsNotSent() {
		assertFalse(AutoGgLogic.sendable(null));
		assertFalse(AutoGgLogic.sendable(""));
		assertFalse(AutoGgLogic.sendable("   "));
		assertTrue(AutoGgLogic.sendable("GG"));
	}
}

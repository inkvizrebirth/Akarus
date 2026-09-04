package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static com.dreamcast.client.util.ScaffoldLogic.apex;
import static com.dreamcast.client.util.ScaffoldLogic.abortOnNoBlocks;
import static com.dreamcast.client.util.ScaffoldLogic.brakeAtEdge;
import static com.dreamcast.client.util.ScaffoldLogic.canJump;
import static com.dreamcast.client.util.ScaffoldLogic.canPlace;
import static com.dreamcast.client.util.ScaffoldLogic.placementAllowed;
import static com.dreamcast.client.util.ScaffoldLogic.predictFeet;
import static com.dreamcast.client.util.ScaffoldLogic.retryLeft;
import static com.dreamcast.client.util.ScaffoldLogic.supportUnsafe;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обязательные сценарии Scaffold/Telly: один прыжок на край, никаких блоков
 * при подъёме, установка только после апекса, диагональный прогноз, ненадёжные
 * опоры (полублоки/снег/жидкости/пустота), конец блоков, retry-бюджет.
 */
class ScaffoldLogicTest {

	// ---- один прыжок на один край ----

	@Test
	void jumpOnlyFromGroundAndOncePerEdge() {
		assertTrue(canJump(true, true, false), "край + земля + ещё не прыгали");
		assertFalse(canJump(false, true, false), "не с земли — ни в коем случае");
		assertFalse(canJump(true, true, true), "этот край уже прыгнут");
		assertFalse(canJump(true, false, false), "края нет");
	}

	// ---- никаких установок при подъёме ----

	@Test
	void neverPlaceWhileAscending() {
		// apex-режим: пока летим вверх, apexPassed ещё не случился
		assertFalse(placementAllowed("apex", false, 0.42, 0, 0, 0),
				"подъём — блоки не ставятся");
		// и falling-режим тоже не должен срабатывать при vy > 0
		assertFalse(placementAllowed("falling", false, 0.42, 0, 0, 0));
	}

	@Test
	void apexIsVelocitySignFlip() {
		assertTrue(apex(true, -0.01), "переход вверх→вниз");
		assertTrue(apex(true, 0.0), "нулевая скорость на макушке");
		assertFalse(apex(false, -0.5), "мы и так падали — апекса нет");
		assertFalse(apex(true, 0.3), "ещё подъём");
	}

	// ---- установка после апекса / по скорости / по задержке ----

	@Test
	void placingStartsOnlyAfterApex() {
		assertTrue(placementAllowed("apex", true, -0.05, 0, 0, 0));
	}

	@Test
	void fallingModeWaitsForSpeed() {
		assertFalse(placementAllowed("falling", false, -0.05, 2, 0, 0), "медленно");
		assertTrue(placementAllowed("falling", false, -0.25, 2, 0, 0), "быстрее порога 0.2");
	}

	@Test
	void customModeWaitsDelay() {
		assertFalse(placementAllowed("custom", false, 0.0, 0, 200, 120));
		assertTrue(placementAllowed("custom", false, 0.0, 0, 200, 200));
	}

	// ---- диагональное движение: прогноз по обеим осям ----

	@Test
	void diagonalPredictionCoversBothAxes() {
		double[] feet = predictFeet(0, 100, 0, 0.3, -0.1, -0.4, 2);
		assertArrayEquals(new double[]{0.6, feet[1], -0.8},
				new double[]{feet[0], feet[1], feet[2]}, 1.0e-9);
		// вертикаль: гравитация разгоняет падение сильнее линейного
		// (линейно было бы 99.8, с гравитацией опускаемся ниже 99.6)
		assertTrue(feet[1] < 99.6, "гравитация учтена");
	}

	@Test
	void predictionIsForwardOnly() {
		double[] feet = predictFeet(10, 64, 10, 1.0, 0.0, 0.0, 3);
		assertArrayEquals(new double[]{13.0, feet[1], 10.0},
				new double[]{feet[0], feet[1], feet[2]}, 1.0e-9);
	}

	// ---- полублоки, снег, жидкости, пустота ----

	@Test
	void unsafeSupportsAreRejected() {
		assertTrue(supportUnsafe(false, false, false, true), "воздух — не опора");
		assertTrue(supportUnsafe(true, true, false, false), "слой снега проминается");
		assertTrue(supportUnsafe(true, false, true, false), "жидкость — не опора");
		assertTrue(supportUnsafe(false, false, false, false), "полублок без полной коллизии");
		assertFalse(supportUnsafe(true, false, false, false), "полный куб — опора");
	}

	// ---- закончились блоки посреди прыжка ----

	@Test
	void noBlocksAbortsOrWaits() {
		assertTrue(abortOnNoBlocks(false, true), "stop without blocks — прервать");
		assertFalse(abortOnNoBlocks(false, false), "иначе просто ждать");
		assertFalse(abortOnNoBlocks(true, true), "с блоками не прерываем");
	}

	// ---- отклонение установки сервером: бюджет повторов ограничен ----

	@Test
	void retryBudgetIsBounded() {
		assertTrue(retryLeft(0, 3));
		assertTrue(retryLeft(2, 3));
		assertFalse(retryLeft(3, 3), "спамить пакетами нельзя");
	}

	// ---- полный набор условий установки ----

	@Test
	void placementNeedsEveryCondition() {
		assertTrue(canPlace(true, true, true, true, true, true, true));
		// каждое условие по отдельности блокирует
		assertFalse(canPlace(false, true, true, true, true, true, true));
		assertFalse(canPlace(true, false, true, true, true, true, true), "нет соседа — не по чему кликать");
		assertFalse(canPlace(true, true, false, true, true, true, true), "вне досягаемости");
		assertFalse(canPlace(true, true, true, false, true, true, true));
		assertFalse(canPlace(true, true, true, true, false, true, true), "за границей мира");
		assertFalse(canPlace(true, true, true, true, true, false, true), "нет блока в руке");
		assertFalse(canPlace(true, true, true, true, true, true, false), "кулдаун");
	}

	// ---- SafeWalk: тормоз у края ----

	@Test
	void safeWalkBrakesOnlyAtEdgeWhenNotPlacing() {
		assertTrue(brakeAtEdge(true, true, false));
		assertFalse(brakeAtEdge(true, true, true), "когда ставим — не тормозим");
		assertFalse(brakeAtEdge(false, true, false));
		assertFalse(brakeAtEdge(true, false, false));
	}

	// ---- отключение в любой фазе: RestorePlan уже покрыт своим тестом,
	//      здесь — что переход в IDLE после abort корректен по решению ----

	@Test
	void noBlocksMidAirWithStopAbortsCycle() {
		boolean hasBlocks = false;
		boolean stopWithoutBlocks = true;
		// в середине прыжка: цикл обязан прерваться и вернуть управление
		assertTrue(abortOnNoBlocks(hasBlocks, stopWithoutBlocks));
	}
}

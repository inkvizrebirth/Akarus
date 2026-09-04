package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * История позиций AutoTotem: скорость считается по дельте с ПРОШЛЫМ тиком.
 * Баг, который здесь зафиксирован: если историю перезаписать текущей позицией
 * до прогноза, velocityOf вычитает позицию из неё же — всегда ноль, и
 * предсказание сближения/смэша не работает.
 */
class MotionMathTest {

	@Test
	void oneTickDeltaGivesVelocity() {
		double[] previous = {100.0, 64.0, -50.0, 1000};
		double[] velocity = MotionMath.velocityPerTick(previous, 100.5, 63.0, -50.25, 1001);
		assertArrayEquals(new double[]{0.5, -1.0, -0.25}, velocity, 1.0e-9);
	}

	@Test
	void sameTickRecordGivesZeroNotInfinity() {
		double[] previous = {10.0, 20.0, 30.0, 500};
		double[] velocity = MotionMath.velocityPerTick(previous, 10.0, 20.0, 30.0, 500);
		assertArrayEquals(new double[]{0.0, 0.0, 0.0}, velocity, 1.0e-9,
				"нулевой возраст должен безопасно давать ноль, а не деление на ноль");
	}

	@Test
	void gapAveragesPerTick() {
		double[] previous = {0.0, 0.0, 0.0, 100};
		// 10 блоков за 5 тиков = 2 блока/тик
		double[] velocity = MotionMath.velocityPerTick(previous, 10.0, 0.0, 0.0, 105);
		assertEquals(2.0, velocity[0], 1.0e-9);
	}

	@Test
	void fallingPlayerLandsUnderItselfWithoutDrift() {
		double[] landing = MotionMath.landingPoint(100, 80, 100, 0, -1.0, 0, 60, 3.0);
		assertArrayEquals(new double[]{100, 60, 100}, landing, 1.0e-9);
	}

	@Test
	void horizontalDriftIsExtrapolatedButCapped() {
		// Падение с 20 блоков при vy=-1 и vx=1: без капа снесло бы на 20
		double[] landing = MotionMath.landingPoint(0, 80, 0, 1.0, -1.0, 0, 60, 3.0);
		assertEquals(3.0, Math.hypot(landing[0], landing[2]), 1.0e-9, "снос ограничен капом");
		assertEquals(60.0, landing[1], 1.0e-9);
	}

	@Test
	void noFallVelocityReturnsSamePoint() {
		double[] landing = MotionMath.landingPoint(5, 70, 5, 2.0, 0.5, -1.0, 60, 3.0);
		assertArrayEquals(new double[]{5, 70, 5}, landing, 1.0e-9);
	}

	@Test
	void belowGroundReturnsSamePoint() {
		double[] landing = MotionMath.landingPoint(5, 50, 5, 0, -1, 0, 60, 3.0);
		assertTrue(landing[1] == 50.0);
	}
}

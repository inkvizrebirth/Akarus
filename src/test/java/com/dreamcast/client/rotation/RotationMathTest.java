package com.dreamcast.client.rotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Математика слоя поворотов: зацикливание углов, ограничение скорости,
 * «навелись ли», питч и упреждение цели.
 *
 * Именно от этих функций зависит, бьёт ли аура и ставит ли Scaffold: их нельзя
 * «починить на глаз» — только тестами.
 */
class RotationMathTest {

	private static final float EPS = 1.0e-4F;

	// ---- wrap: угол всегда в (-180; 180] ----

	@Test
	void wrapKeepsAnglesInRange() {
		// диапазон [-180; 180) — как у Mth.wrapDegrees
		assertEquals(0.0F, RotationMath.wrap(0.0F), EPS);
		assertEquals(10.0F, RotationMath.wrap(370.0F), EPS);
		assertEquals(-170.0F, RotationMath.wrap(190.0F), EPS);
		assertEquals(170.0F, RotationMath.wrap(-190.0F), EPS);
		// ровно 180 ваниль тоже закручивает в −180 — держимся того же соглашения
		assertEquals(-180.0F, RotationMath.wrap(180.0F), EPS);
	}

	@Test
	void wrapIsSymmetricAcrossZero() {
		// короткая дорога через ноль: 350° → 10° это −20°, а не +340°
		assertEquals(-20.0F, RotationMath.wrap(10.0F - 350.0F), EPS);
	}

	// ---- шаг с ограничением скорости ----

	@Test
	void zeroSpeedSnapsInstantly() {
		assertEquals(90.0F, RotationMath.stepYaw(0.0F, 90.0F, 0.0F), EPS);
		assertEquals(45.0F, RotationMath.stepPitch(0.0F, 45.0F, 0.0F), EPS);
	}

	@Test
	void stepNeverOvershootsLimit() {
		assertEquals(30.0F, RotationMath.stepYaw(0.0F, 100.0F, 30.0F), EPS);
		assertEquals(-30.0F, RotationMath.stepYaw(0.0F, -100.0F, 30.0F), EPS);
		assertEquals(60.0F, RotationMath.stepPitch(30.0F, 100.0F, 30.0F), EPS);
	}

	@Test
	void stepArrivesExactlyOnTarget() {
		// остаток меньше шага — приходим ровно в цель, а не «мимо на 0.3°»
		assertEquals(12.0F, RotationMath.stepYaw(0.0F, 12.0F, 30.0F), EPS);
		assertEquals(12.0F, RotationMath.stepPitch(11.0F, 12.0F, 30.0F), EPS);
	}

	@Test
	void shortWayIsUsedForRotationAroundBack() {
		// с 350° в 10° едем через ноль (+20), а не назад через 180°
		assertEquals(10.0F, RotationMath.stepYaw(350.0F, 10.0F, 30.0F), EPS);
	}

	@Test
	void repeatedStepsConverge() {
		// ровно то, чего не хватало старому «silent» у Scaffold: шаг от текущего
		// взгляда игрока никуда не вёл, потому что состояние не сохранялось
		float current = 0.0F;
		for (int i = 0; i < 10; i++) {
			current = RotationMath.stepYaw(current, 100.0F, 20.0F);
		}
		assertEquals(100.0F, current, EPS);
	}

	// ---- pitch clamp ----

	@Test
	void pitchIsClampedToNinety() {
		assertEquals(90.0F, RotationMath.clampPitch(120.0F), EPS);
		assertEquals(-90.0F, RotationMath.clampPitch(-200.0F), EPS);
	}

	// ---- «навелись ли» ----

	@Test
	void aimedUsesToleranceAndWrapping() {
		assertTrue(RotationMath.aimed(359.0F, 0.0F, 1.0F, 0.0F, 6.0F));
		assertFalse(RotationMath.aimed(340.0F, 0.0F, 1.0F, 0.0F, 6.0F));
		assertFalse(RotationMath.aimed(0.0F, 20.0F, 0.0F, 0.0F, 6.0F), "питч мимо прицела");
	}

	// ---- упреждение ----

	@Test
	void leadIgnoresVerticalMotion() {
		double[] ahead = RotationMath.lead(0.0, 64.0, 0.0, 0.5, -0.9, 0.0, 2);
		assertEquals(1.0, ahead[0], EPS);
		assertEquals(64.0, ahead[1], EPS, "вертикаль не экстраполируем: там гравитация");
	}

	@Test
	void zeroLeadKeepsPosition() {
		double[] same = RotationMath.lead(1.5, 2.5, 3.5, 1.0, 1.0, 1.0, 0);
		assertEquals(1.5, same[0], EPS);
		assertEquals(2.5, same[1], EPS);
		assertEquals(3.5, same[2], EPS);
	}

	// ---- углы «в точку» ----

	@Test
	void yawToPointMatchesVanillaConvention() {
		// +Z (юг) — это yaw 0 в Minecraft
		assertEquals(0.0F, RotationMath.yawTo(0.0, 0.0, 0.0, 10.0), 1.0e-3F);
		// −X (запад) — yaw +90, +X (восток) — −90 (как у calculateViewVector)
		assertEquals(90.0F, RotationMath.yawTo(0.0, 0.0, -10.0, 0.0), 1.0e-3F);
		assertEquals(-90.0F, RotationMath.yawTo(0.0, 0.0, 10.0, 0.0), 1.0e-3F);
	}

	@Test
	void pitchToPointIsNegativeUp() {
		assertEquals(0.0F, RotationMath.pitchTo(0.0, 0.0, 0.0, 10.0, 0.0, 0.0), 1.0e-3F);
		assertEquals(-45.0F, RotationMath.pitchTo(0.0, 0.0, 0.0, 10.0, 10.0, 0.0), 1.0e-3F);
		assertEquals(45.0F, RotationMath.pitchTo(0.0, 0.0, 0.0, 10.0, -10.0, 0.0), 1.0e-3F);
	}
}

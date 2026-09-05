package com.dreamcast.client.rotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Математика слоя поворотов: зацикливание углов, ограничение скорости,
 * «навелись ли», питч и упреждение цели.
 *
 * <p>Именно от этих функций зависит, бьёт ли аура и ставит ли Scaffold — «на глаз»
 * такое не чинится. Сравнение углов идёт в double с дельтой: float-варианты
 * assertEquals в JUnit появляются в разных версиях по-разному, а дельта для
 * двойного точного есть всегда.</p>
 */
class RotationMathTest {

	private static final double EPS = 1.0e-3;

	private static void assertAngle(float expected, float actual) {
		assertEquals(expected, actual, EPS);
	}

	private static void assertAngle(float expected, float actual, String label) {
		assertEquals(expected, actual, EPS, label);
	}

	// ---- wrap: угол всегда в [-180; 180) ----

	@Test
	void wrapKeepsAnglesInRange() {
		assertAngle(0.0F, RotationMath.wrap(0.0F));
		assertAngle(10.0F, RotationMath.wrap(370.0F));
		assertAngle(-170.0F, RotationMath.wrap(190.0F), "190° — это −170°");
		assertAngle(170.0F, RotationMath.wrap(-190.0F), "−190° — это 170°");
		// ровно 180 ваниль тоже закручивает в −180 — держимся того же соглашения
		assertAngle(-180.0F, RotationMath.wrap(180.0F));
	}

	@Test
	void deltaThroughZeroIsTheShortWay() {
		// с 350° в 10° короткая дорога — +20° через ноль, а не −340° назад
		assertAngle(20.0F, RotationMath.wrap(10.0F - 350.0F));
		// и зеркально: из 10° в 350° — это −20°
		assertAngle(-20.0F, RotationMath.wrap(350.0F - 10.0F));
	}

	// ---- шаг с ограничением скорости ----

	@Test
	void zeroSpeedSnapsInstantly() {
		assertAngle(90.0F, RotationMath.stepYaw(0.0F, 90.0F, 0.0F), "скорость 0 = мгновенно");
		assertAngle(45.0F, RotationMath.stepPitch(0.0F, 45.0F, 0.0F));
	}

	@Test
	void stepNeverOvershootsLimit() {
		assertAngle(30.0F, RotationMath.stepYaw(0.0F, 100.0F, 30.0F));
		assertAngle(-30.0F, RotationMath.stepYaw(0.0F, -100.0F, 30.0F));
		assertAngle(60.0F, RotationMath.stepPitch(30.0F, 100.0F, 30.0F));
	}

	@Test
	void stepArrivesExactlyOnTarget() {
		// остаток меньше шага — приходим ровно в цель, а не «мимо на 0.3°»
		assertAngle(12.0F, RotationMath.stepYaw(0.0F, 12.0F, 30.0F));
		assertAngle(12.0F, RotationMath.stepPitch(11.0F, 12.0F, 30.0F));
	}

	@Test
	void shortWayIsUsedForRotationAroundBack() {
		// с 350° в 10° едем через ноль (+20), а не назад через 180°
		assertAngle(10.0F, RotationMath.stepYaw(350.0F, 10.0F, 30.0F));
	}

	@Test
	void repeatedStepsConverge() {
		// ровно то, чего не хватало старому «silent» у Scaffold: шаг считался от
		// текущего взгляда игрока и никуда не вёл, потому что состояние не жило
		float current = 0.0F;
		for (int i = 0; i < 10; i++) {
			current = RotationMath.stepYaw(current, 100.0F, 20.0F);
		}
		assertAngle(100.0F, current);
	}

	@Test
	void stepGoesThroughPlusMinus180WithoutJump() {
		// 170° -> −170°: это +20° через 180°. Если посчитать «по разнице без
		// закрутки», шаг вышел бы на −340°, и цель никогда не догонялась
		assertAngle(-170.0F, RotationMath.stepYaw(170.0F, -170.0F, 30.0F));
		// маленьким шагом идём постепенно и не перескакиваем
		assertAngle(-175.0F, RotationMath.stepYaw(175.0F, -170.0F, 10.0F));
	}

	// ---- clamp питча ----

	@Test
	void pitchIsClampedToNinety() {
		assertAngle(90.0F, RotationMath.clampPitch(120.0F));
		assertAngle(-90.0F, RotationMath.clampPitch(-200.0F));
	}

	// ---- «навелись ли» ----

	@Test
	void aimedUsesToleranceAndWrapping() {
		assertTrue(RotationMath.aimed(359.0F, 0.0F, 1.0F, 0.0F, 6.0F), "короткая дорога через ноль");
		assertFalse(RotationMath.aimed(340.0F, 0.0F, 1.0F, 0.0F, 6.0F), "20° мимо — не прицел");
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
		assertAngle(0.0F, RotationMath.yawTo(0.0, 0.0, 0.0, 10.0), "+Z — юг, ноль");
		// −X (запад) — +90, +X (восток) — −90 (как у calculateViewVector)
		assertAngle(90.0F, RotationMath.yawTo(0.0, 0.0, -10.0, 0.0), "−X — запад");
		assertAngle(-90.0F, RotationMath.yawTo(0.0, 0.0, 10.0, 0.0), "+X — восток");
	}

	@Test
	void pitchToPointIsNegativeUp() {
		assertAngle(0.0F, RotationMath.pitchTo(0.0, 0.0, 0.0, 10.0, 0.0, 0.0), "горизонт");
		assertAngle(-45.0F, RotationMath.pitchTo(0.0, 0.0, 0.0, 10.0, 10.0, 0.0), "вверх — минус");
		assertAngle(45.0F, RotationMath.pitchTo(0.0, 0.0, 0.0, 10.0, -10.0, 0.0), "вниз — плюс");
	}
}

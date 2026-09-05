package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.WingsModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Математика world-примитивов и поз крыльев.
 *
 * <p>Обе вещи не видно глазами в игре: базис экрана используется каждым
 * биллбордом (свечение, маркер TargetESP, кончик шляпы), и если он скошен или
 * схлопнут — геометрия «плывёт» только в некоторых направлениях взгляда.
 * Позы крыльев — из того же ряда: таблица {@code POSE_*} легко превращается в
 * набор несочетаемых чисел, когда кто-то подправляет один угол.</p>
 */
class WorldGeometryPrimitivesTest {

	private static final double EPS = 1.0e-3;

	/** Оси экрана: единичные, взаимно перпендикулярные и перпендикулярные взгляду. */
	private static void assertScreenBasis(double x, double y, double z) {
		float[] axes = new float[6];
		WorldGeometryRenderer.screenAxes(x, y, z, axes);
		double rx = axes[0], ry = axes[1], rz = axes[2];
		double ux = axes[3], uy = axes[4], uz = axes[5];

		assertEquals(1.0, Math.sqrt(rx * rx + ry * ry + rz * rz), EPS, "right — единичная");
		assertEquals(1.0, Math.sqrt(ux * ux + uy * uy + uz * uz), EPS, "up — единичная");
		assertEquals(0.0, rx * ux + ry * uy + rz * uz, EPS, "right ⟂ up");

		double vlen = Math.sqrt(x * x + y * y + z * z);
		if (vlen > 1.0e-3) {
			assertEquals(0.0, (rx * x + ry * y + rz * z) / vlen, EPS, "right ⟂ взгляду");
			assertEquals(0.0, (ux * x + uy * y + uz * z) / vlen, EPS, "up ⟂ взгляду");
		}
	}

	@Test
	void screenAxesStayOrthonormal() {
		assertScreenBasis(3.0, 1.5, -2.0);
		assertScreenBasis(-8.0, 0.25, 4.0);
		assertScreenBasis(0.5, -6.0, 0.5);
	}

	@Test
	void screenAxesSurviveStraightUpAndDown() {
		// взгляд строго в небо/под ноги — вырожденный случай: worldUp параллелен
		// взгляду, и без ветки «любая перпендикулярная ось» биллборд схлопнулся бы
		assertScreenBasis(0.0, 12.0, 0.0);
		assertScreenBasis(0.0, -12.0, 0.0);
	}

	@Test
	void screenAxesAtOriginFallBackToCardinalAxes() {
		float[] axes = new float[6];
		WorldGeometryRenderer.screenAxes(0.0, 0.0, 0.0, axes);
		assertEquals(1.0, axes[0], EPS);
		assertEquals(1.0, axes[4], EPS);
	}

	@Test
	void everyPoseHasUsableWingShape() {
		for (WingsModule.Pose pose : WingsModule.Pose.values()) {
			WingsModule.WingPose wing = WingsModule.poseFor(pose);
			assertNotNull(wing, pose.name());
			assertTrue(wing.scale() > 0.3F && wing.scale() <= 1.5F, pose.name() + ": масштаб вменяемый");
			assertTrue(wing.sideGap() > 0.0F, pose.name() + ": зазор от спины обязателен");
			assertTrue(wing.sweepBase() >= 0.0F && wing.sweepBase() < 90.0F, pose.name() + ": захлоп");
			assertTrue(wing.flapAmplitude() >= 0.0F && wing.flapAmplitude() < 45.0F, pose.name() + ": взмах");
			assertTrue(wing.flapSpeed() >= 0.0F && wing.flapSpeed() < 1.0F, pose.name() + ": частота");
			assertTrue(Math.abs(wing.elevBase()) < 45.0F, pose.name() + ": подъём кончика");
			assertTrue(Math.abs(wing.anchorUp()) < 1.0F && Math.abs(wing.anchorBack()) < 1.0F,
					pose.name() + ": точка крепления внутри корпуса");
		}
	}

	@Test
	void posesDifferInCharacter() {
		WingsModule.WingPose gliding = WingsModule.poseFor(WingsModule.Pose.GLIDING);
		WingsModule.WingPose walking = WingsModule.poseFor(WingsModule.Pose.WALKING);
		WingsModule.WingPose sprinting = WingsModule.poseFor(WingsModule.Pose.SPRINTING);
		// в полёте крылья прижаты и почти не машут, на бегу — активнее
		assertTrue(gliding.sweepBase() > walking.sweepBase(), "глайдинг закрыт сильнее");
		assertTrue(gliding.flapAmplitude() < walking.flapAmplitude(), "в планировании взмаха нет");
		assertTrue(sprinting.flapSpeed() > walking.flapSpeed(), "бег — чаще взмахи");
		assertTrue(sprinting.sweepBase() > walking.sweepBase(), "бег — сильнее отвод назад");
	}
}

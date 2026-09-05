package com.dreamcast.client.rotation;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Математика «человечного» доворота.
 *
 * <p>Главное свойство, которое здесь проверяется, — <b>не-линейность</b>: если
 * скорость шага постоянна, ротация выглядит машинной ровно так же, как мгновенный
 * телепорт угла. Пружина обязана разгоняться и тормозить, а недодемпфированная —
 * проскакивать цель и возвращаться. Это не «красивые слова в javadoc», а то, на
 * чём держится обход детектов scripted-ротаций, поэтому проверяем числами.</p>
 */
class RotationCurveTest {

	private static final float DT = 1.0F / 20.0F;

	@Test
	void springAcceleratesInsteadOfRunningConstant() {
		RotationCurve.State state = RotationCurve.State.at(0.0F);
		float[] deltas = new float[10];
		for (int i = 0; i < deltas.length; i++) {
			float before = state.position();
			state = RotationCurve.approach(state, 90.0F, 12.0F, 1.0F, DT, 0.0F);
			deltas[i] = state.position() - before;
		}
		float min = Float.MAX_VALUE;
		float max = 0.0F;
		for (float delta : deltas) {
			assertTrue(delta > 0.0F, "движение только в сторону цели: " + delta);
			min = Math.min(min, delta);
			max = Math.max(max, delta);
		}
		// Линейный шаг дал бы max/min == 1; пружина в начале разгоняется
		assertTrue(max > min * 2.0F, "скорость шага должна расти, а не быть постоянной");
	}

	@Test
	void springConvergesAndSnapsToTarget() {
		RotationCurve.State state = RotationCurve.State.at(-12.0F);
		for (int i = 0; i < 400; i++) {
			state = RotationCurve.approach(state, 37.0F, 45.0F, 1.0F, DT, 0.0F);
		}
		assertEquals(37.0F, state.position(), 0.0F, "доехав, пружина встаёт ровно в цель");
		assertEquals(0.0F, state.velocity(), 0.0F, "и гасит скорость, а не «гудит» на цели");
	}

	@Test
	void underDampedOvershootsAndRecovers() {
		boolean overshot = false;
		RotationCurve.State state = RotationCurve.State.at(0.0F);
		for (int i = 0; i < 200; i++) {
			state = RotationCurve.approach(state, 30.0F, 90.0F, 0.65F, DT, 0.0F);
			if (state.position() > 30.0F) {
				overshot = true;
			}
		}
		assertTrue(overshot, "недодемпфированная пружина обязана проскакивать цель — это и есть перелёт");
		assertTrue(state.position() <= 30.05F, "и возвращаться к ней: " + state.position());
	}

	@Test
	void speedCapIsRespected() {
		RotationCurve.State state = RotationCurve.State.at(0.0F);
		for (int i = 0; i < 12; i++) {
			RotationCurve.State next = RotationCurve.approach(state, 180.0F, 400.0F, 1.0F, DT, 90.0F);
			assertTrue(Math.abs(next.position() - state.position()) <= 90.0F * DT + 1e-3F,
					"потолок °/с из настройки модуля должен работать");
			state = next;
		}
	}

	@Test
	void hugeDeltaUsesOneFrameNotTeleport() {
		// «Прыжок» времени (просадка TPS) не должен превращаться в телепорт угла
		// dt обрезается сверху, поэтому кадр с dt=5 идентичен кадру с dt=0.12
		RotationCurve.State state = RotationCurve.State.at(0.0F);
		RotationCurve.State clamped = RotationCurve.approach(state, 120.0F, 80.0F, 1.0F, 0.12F, 0.0F);
		RotationCurve.State jumped = RotationCurve.approach(state, 120.0F, 80.0F, 1.0F, 5.0F, 0.0F);
		assertEquals(clamped.position(), jumped.position(), 1e-4F, "dt свыше 0.12 с не разгоняет пружину");
	}

	@Test
	void easingIsSmoothAtBothEnds() {
		assertEquals(0.0F, RotationCurve.ease(0.0F), 1e-6F);
		assertEquals(1.0F, RotationCurve.ease(1.0F), 1e-6F);
		assertEquals(0.5F, RotationCurve.ease(0.5F), 1e-6F, "середина — ровно половина пути");
		float previous = -1.0F;
		for (float t = 0.0F; t <= 1.0001F; t += 0.02F) {
			float value = RotationCurve.ease(t);
			assertTrue(value >= previous - 1e-6F, "кривая не убывает");
			assertTrue(value > previous || t > 0.99F || t < 0.02F, "нет плато в середине");
			previous = value;
		}
		// на концах производная smootherstep нулевая — старт/финиш без «щелчка»
		assertTrue(RotationCurve.ease(0.02F) < 0.002F);
		assertTrue(RotationCurve.ease(0.98F) > 0.998F);
	}

	@Test
	void handDriftIsCorrelatedButBounded() {
		Random random = new Random(7L);
		float[] state = new float[1];
		float max = 0.0F;
		for (int i = 0; i < 5000; i++) {
			RotationCurve.drift(random, state, 0.05F);
			max = Math.max(max, Math.abs(state[0]));
		}
		assertTrue(max < 1.0F, "дрейф не должен уезжать на градусы: " + max);
		assertTrue(max > 0.02F, "и не схлопываться в ноль: " + max);
	}

	@Test
	void dampingStaysInTheHumanRange() {
		Random random = new Random(3L);
		for (int i = 0; i < 1000; i++) {
			float damping = RotationCurve.underDamped(random, i / 1000.0F);
			assertFalse(damping <= 0.4F || damping > 1.1F, "за пределом «перелёт — без раскачки»: " + damping);
		}
		// больше рандомизации — в среднем сильнее проскок
		float soft = 0.0F;
		float wild = 0.0F;
		for (int i = 0; i < 400; i++) {
			soft += RotationCurve.underDamped(random, 0.0F);
			wild += RotationCurve.underDamped(random, 1.0F);
		}
		assertTrue(soft > wild, "при 0 % рандомизации пружина ближе к критической");
	}
}

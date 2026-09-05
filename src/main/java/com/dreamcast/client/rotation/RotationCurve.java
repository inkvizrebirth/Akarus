package com.dreamcast.client.rotation;

import java.util.Random;

/**
 * Кривые доворота: пружина вместо «шага постоянной скорости».
 *
 * <p>Линейность — это то, за что античиты (Grim, Matrix, Vulcan) режут ротации
 * быстрее всего: постоянный °/тик, идеальный вход в цель без перелёта, одинаковый
 * питч и рыскание. Здесь модель другая — <b>колебательная система</b> с
 * жёсткостью и демпфированием: скорость нарастает, потом гаснет, перелёт и
 * возврат происходят сами собой (недодемпфированная пружина), а не дописываются
 * вручную. Отсюда и «никакой линейности»: вторая производная nonzero всегда.</p>
 *
 * <p>Всё считается по реальному {@code dt} (секунды), поэтому поведение не
 * зависит от TPS/герцовки: на 300 Гц камера не «ускоряется в пять раз».</p>
 */
public final class RotationCurve {

	/** Порог, после которого считаем, что доворот закончен и пружину можно успокоить. */
	public static final float EPSILON = 0.02F;

	private RotationCurve() {
	}

	/** Состояние одной оси: положение и скорость (°/с). */
	public record State(float position, float velocity) {

		public static State at(float position) {
			return new State(position, 0.0F);
		}
	}

	/**
	 * Один шаг пружины к цели.
	 *
	 * @param stiffness  жёсткость (1/с²): насколько «злая» доводка
	 * @param damping    относительное демпфирование: &lt;1 — с перелётом, 1 — без
	 * @param dtSeconds  сколько реально прошло времени
	 * @param maxSpeed   потолок скорости, °/с; {@code <= 0} — без потолка
	 */
	public static State approach(State state, float target, float stiffness, float damping,
	                             float dtSeconds, float maxSpeed) {
		float dt = Math.max(0.001F, Math.min(dtSeconds, 0.12F)); // 8 FPS — уже «прыжок», не разгоняем
		float delta = target - state.position();
		// критическое демпфирование = 2·√k; относительный коэффициент задаёт «злость»
		float critical = 2.0F * (float) Math.sqrt(Math.max(0.01F, stiffness));
		float acc = stiffness * delta - critical * damping * state.velocity();
		float velocity = state.velocity() + acc * dt;
		if (maxSpeed > 0.0F) {
			velocity = Math.max(-maxSpeed, Math.min(maxSpeed, velocity));
		}
		float position = state.position() + velocity * dt;
		if (Math.abs(target - position) < EPSILON && Math.abs(velocity) < 6.0F) {
			// доехали: обнуляем скорость, иначе пружина будет «гудеть» на цели
			return new State(target, 0.0F);
		}
		return new State(position, velocity);
	}

	/**
	 * Сглаженная доля пути (без линейного участка): медленный вход, быстрый
	 * середина, мягкий выход. Используется там, где пружина не нужна — например,
	 * для «плавного» возврата camera-offset'а.
	 */
	public static float ease(float progress) {
		float t = Math.max(0.0F, Math.min(1.0F, progress));
		return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F); // smootherstep
	}

	/**
	 * Тряска «руки»: медленный дрейф плюс мелкий шум, а не белый шум — у живой
	 * руки смещение correlated, оно не меняется скачками каждый кадр.
	 */
	public static void drift(Random random, float[] state, float amount) {
		// state[0] — текущее смещение; держится и медленно утекает к новому
		state[0] = state[0] * 0.82F + (float) random.nextGaussian() * amount;
	}

	/** Демпфирование, при котором пружина ещё успевает проскочить цель (перелёт). */
	public static float underDamped(Random random, float intensity) {
		// 0.62 — заметный перелёт, 1.05 — лёгкое «недоедание» до цели
		return 1.06F - (0.20F + 0.24F * intensity) * (0.35F + random.nextFloat() * 0.65F);
	}
}

package com.dreamcast.client.rotation;

/**
 * Чистая математика поворотов для {@link RotationManager}.
 *
 * <p>Вынесена отдельно (и без единой ссылки на Minecraft) по двум причинам:
 * во-первых, ей пользуются сразу несколько модулей — аура, Scaffold, AutoBuff;
 * во-вторых, её можно покрыть юнит-тестами, чего не скажешь о коде, который
 * трогает игрока и пакеты.</p>
 */
public final class RotationMath {

	private RotationMath() {
	}

	/** Заматывает угол в диапазон [−180; 180) — ровно как {@code Mth.wrapDegrees}. */
	public static float wrap(float degrees) {
		float wrapped = degrees % 360.0F;
		if (wrapped >= 180.0F) {
			wrapped -= 360.0F;
		}
		if (wrapped < -180.0F) {
			wrapped += 360.0F;
		}
		return wrapped;
	}

	/** Питч всегда живёт в [−90; 90] — иначе игра ломает направление взгляда. */
	public static float clampPitch(float pitch) {
		return pitch < -90.0F ? -90.0F : Math.min(pitch, 90.0F);
	}

	/**
	 * Один шаг доворота к желаемому углу с ограничением скорости.
	 *
	 * @param current   текущий угол
	 * @param wanted    куда хотим прийти
	 * @param maxStep   максимум градусов за тик; {@code <= 0} — мгновенно
	 * @return новый угол (уже «закрученный» в обычный диапазон)
	 */
	public static float stepYaw(float current, float wanted, float maxStep) {
		float delta = wrap(wanted - current);
		if (maxStep <= 0.0F || Math.abs(delta) <= maxStep) {
			return wrap(current + delta);
		}
		return wrap(current + Math.signum(delta) * maxStep);
	}

	/** Тот же шаг, но для питча: без «закрутки», зато с жёстким клампом. */
	public static float stepPitch(float current, float wanted, float maxStep) {
		float delta = wanted - current;
		if (maxStep <= 0.0F || Math.abs(delta) <= maxStep) {
			return clampPitch(wanted);
		}
		return clampPitch(current + Math.signum(delta) * maxStep);
	}

	/** Прицел «наведён», если оба угла отличаются не больше чем на допуск. */
	public static boolean aimed(float currentYaw, float currentPitch,
	                           float wantedYaw, float wantedPitch, float tolerance) {
		return Math.abs(wrap(currentYaw - wantedYaw)) <= tolerance
				&& Math.abs(currentPitch - wantedPitch) <= tolerance;
	}

	/**
	 * Углы взгляда из точки глаза в точку цели.
	 *
	 * Расчёт сознательно повторяет игровой (atan2 по горизонтали и вертикали),
	 * а не опирается на {@code Entity#getViewVector}: нам нужны углы «как если бы
	 * игрок смотрел в эту точку», включая случай, когда камера у игрока своя.
	 */
	public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
		return wrap((float) (Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90.0));
	}

	public static float pitchTo(double fromX, double fromY, double fromZ,
	                             double toX, double toY, double toZ) {
		double dx = toX - fromX;
		double dz = toZ - fromZ;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return clampPitch((float) (-Math.toDegrees(Math.atan2(toY - fromY, horizontal))));
	}

	/**
	 * Упреждение цели: куда встанет цель через {@code ticks} тиков, если продолжит
	 * движение с текущей скоростью. Вертикаль гасим — гравитация делает прогноз
	 * «по deltaMovement» неверным уже через два тика, а прыжки отлавливаются
	 * отдельной логикой (см. Смарт Крит).
	 */
	public static double[] lead(double x, double y, double z,
	                           double dx, double dy, double dz, int ticks) {
		if (ticks <= 0) {
			return new double[]{x, y, z};
		}
		return new double[]{x + dx * ticks, y, z + dz * ticks};
	}
}

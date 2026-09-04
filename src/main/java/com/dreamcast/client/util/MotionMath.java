package com.dreamcast.client.util;

/**
 * Чистая математика движения для предсказаний (без типов Minecraft — тестируется юнит-тестами).
 */
public final class MotionMath {

	private MotionMath() {
	}

	/**
	 * Скорость в блоках/тик между записью истории и текущей позицией.
	 *
	 * @param previous  запись истории {x, y, z, tick}
	 * @param x,y,z     текущая позиция
	 * @param tickCount текущий тик сущности
	 * @return {vx, vy, vz} в блоках/тик; возраст всегда ≥1, деления на ноль нет
	 */
	public static double[] velocityPerTick(double[] previous, double x, double y, double z, int tickCount) {
		int ageTicks = Math.max(1, tickCount - (int) previous[3]);
		return new double[]{
				(x - previous[0]) / ageTicks,
				(y - previous[1]) / ageTicks,
				(z - previous[2]) / ageTicks
		};
	}

	/**
	 * Точка предполагаемого приземления: горизонталь сносится скоростью
	 * за время падения до поверхности. Если вертикальной скорости нет
	 * (стоит/летит вверх), возвращается сама точка.
	 *
	 * @param vy         вертикальная скорость, блоков/тик (отрицательная = вниз)
	 * @param groundY     Y поверхности под игроком
	 * @param maxShift    предел горизонтального сноса, блоков (не запускаем точку далеко)
	 */
	public static double[] landingPoint(double px, double py, double pz,
	                                    double vx, double vy, double vz,
	                                    double groundY, double maxShift) {
		if (vy >= -1.0e-4 || py <= groundY) {
			return new double[]{px, py, pz};
		}
		double ticksToGround = (py - groundY) / -vy;
		double shiftX = vx * ticksToGround;
		double shiftZ = vz * ticksToGround;
		double shift = Math.hypot(shiftX, shiftZ);
		if (shift > maxShift) {
			double scale = maxShift / shift;
			shiftX *= scale;
			shiftZ *= scale;
		}
		return new double[]{px + shiftX, groundY, pz + shiftZ};
	}
}

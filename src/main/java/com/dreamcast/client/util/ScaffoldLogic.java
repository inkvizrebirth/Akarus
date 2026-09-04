package com.dreamcast.client.util;

/**
 * Чистые решения Scaffold/Telly (без Minecraft-типов — покрывается юнит-тестами).
 *
 * <p>Инварианты режима Telly: прыжок ровно один на обнаруженный край; во время
 * подъёма блоки НЕ ставятся; установка начинается только после апекса (или по
 * условию «падаю быстрее порога» / кастомной задержке); один блок за тик с
 * подтверждением и ограниченным retry.</p>
 */
public final class ScaffoldLogic {

	private ScaffoldLogic() {
	}

	/** Гравитация и сопротивление воздуха, блоки/тик² (как в ванили). */
	public static final double GRAVITY = 0.08;
	public static final double DRAG = 0.98;

	/** Можно ли прыгать: только с земли и только если этот край ещё не прыгнут. */
	public static boolean canJump(boolean onGround, boolean edgeDetected,
	                              boolean alreadyJumpedThisEdge) {
		return edgeDetected && onGround && !alreadyJumpedThisEdge;
	}

	/** Фаза подъёма: строго вверх. */
	public static boolean ascending(double vy) {
		return vy > 0.0;
	}

	/** Апекс: вертикальная скорость перешла из положительной в нулевую/отрицательную. */
	public static boolean apex(boolean wasAscending, double vy) {
		return wasAscending && vy <= 0.0;
	}

	/**
	 * Разрешена ли установка блоков по настройке «Начало установки».
	 *
	 * @param startMode      apex / falling / custom
	 * @param apexPassed     апекс уже пройден
	 * @param vy             текущая вертикальная скорость
	 * @param minFallSpeed   порог скорости падения (0.1 бл/тик) для falling
	 * @param customDelayMs  прошедшая мс для custom (0 = сразу после прыжка)
	 */
	public static boolean placementAllowed(String startMode, boolean apexPassed,
	                                           double vy, int minFallSpeed,
	                                           long customDelayMs, long elapsedMs) {
		// инвариант Telly: при подъёме блоки не ставятся ни в каком режиме
		if (vy > 0.0) {
			return false;
		}
		if ("falling".equals(startMode)) {
			return vy < -minFallSpeed * 0.1;
		}
		if ("custom".equals(startMode)) {
			return elapsedMs >= customDelayMs;
		}
		return apexPassed; // apex (по умолчанию): только после перехода вниз
	}

	/**
	 * Прогноз позиции ног через ticks: горизонталь равномерно, вертикаль —
	 * с гравитацией и drag (как тики ванильного падения).
	 */
	public static double[] predictFeet(double x, double y, double z,
	                                   double vx, double vy, double vz, int ticks) {
		double px = x + vx * ticks;
		double pz = z + vz * ticks;
		double py = y;
		double velocity = vy;
		for (int t = 0; t < ticks; t++) {
			velocity = (velocity - GRAVITY) * DRAG;
			py += velocity;
		}
		return new double[]{px, py, pz};
	}

	/**
	 * Итоговое разрешение на установку блока в этот тик.
	 *
	 * @param targetAir        целевая позиция воздух/заменяема
	 * @param neighborSolid    есть твёрдый сосед для клика по грани
	 * @param reachOk          грань в пределах досягаемости
	 * @param replaceable      целевой блок можно заменить (не твёрдый)
	 * @param borderOk         позиция внутри границы мира
	 * @param hasBlock         в руке/placeable-слоте есть блок
	 * @param cooldownPassed   межустановочная задержка прошла
	 */
	public static boolean canPlace(boolean targetAir, boolean neighborSolid, boolean reachOk,
	                               boolean replaceable, boolean borderOk, boolean hasBlock,
	                               boolean cooldownPassed) {
		return targetAir && neighborSolid && reachOk && replaceable && borderOk
				&& hasBlock && cooldownPassed;
	}

	/** Опора ненадёжна: полублок без полной коллизии, снег, жидкость, пустота. */
	public static boolean supportUnsafe(boolean fullCube, boolean snowLayer,
	                                     boolean fluid, boolean air) {
		return air || snowLayer || fluid || !fullCube;
	}

	/** Остался ли бюджет повторов после отклонения установки сервером. */
	public static boolean retryLeft(int used, int max) {
		return used < max;
	}

	/**
	 * Блоки кончились посреди прыжка:@stopWithoutBlocks — прервать цикл,
	 * иначе просто пауза до появления блоков.
	 */
	public static boolean abortOnNoBlocks(boolean hasBlocks, boolean stopWithoutBlocks) {
		return !hasBlocks && stopWithoutBlocks;
	}

	/**
	 * SafeWalk-тормоз: край в пределах шага — гасим «вперёд» на этот тик,
	 * чтобы не шагнуть в пустоту (для режима без прыжка).
	 */
	public static boolean brakeAtEdge(boolean edgeAhead, boolean safeWalk, boolean placing) {
		return safeWalk && edgeAhead && !placing;
	}
}

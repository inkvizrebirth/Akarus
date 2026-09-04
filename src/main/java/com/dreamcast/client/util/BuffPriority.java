package com.dreamcast.client.util;

/**
 * Приоритет баффов AutoBuff (чистая логика, без Minecraft-типов).
 *
 * <p>Порядок: лечение при низком HP → огнестойкость (горит/кончается) →
 * сила → скорость → регенерация → ночное зрение → золотое яблоко как
 * запасное лечение. Следующий бафф не выбирается, пока не завершено
 * предыдущее действие — это обеспечивает сама стейт-машина модуля.</p>
 */
public final class BuffPriority {

	private BuffPriority() {
	}

	public enum Target {
		HEAL, FIRE_RESISTANCE, STRENGTH, SPEED, REGENERATION, NIGHT_VISION, GOLDEN_APPLE, NONE
	}

	/**
	 * Что применять прямо сейчас.
	 *
	 * @param lowHp             HP не выше порога лечения
	 * @param wantHeal          разрешено мгновенное лечение
	 * @param healPotionPresent есть зелье/средство лечения
	 * @param fireResUrgent     игрок горит или огнестойкость вот-вот кончится
	 * @param wantFireRes       разрешена огнестойкость
	 * @param wantStrength      разрешена сила (и она нужна)
	 * @param wantSpeed         разрешена скорость (и она нужна)
	 * @param wantRegen         разрешена регенерация (и она нужна)
	 * @param wantNightVision   разрешено ночное зрение (и оно нужно)
	 * @param gappleAllowed     яблоко доступно (нет поглощения, разрешено настройкой)
	 */
	public static Target pick(boolean lowHp, boolean wantHeal, boolean healPotionPresent,
	                          boolean fireResUrgent, boolean wantFireRes,
	                          boolean wantStrength, boolean wantSpeed,
	                          boolean wantRegen, boolean wantNightVision,
	                          boolean gappleAllowed) {
		if (lowHp && wantHeal && healPotionPresent) {
			return Target.HEAL;
		}
		if (fireResUrgent && wantFireRes) {
			return Target.FIRE_RESISTANCE;
		}
		if (wantStrength) {
			return Target.STRENGTH;
		}
		if (wantSpeed) {
			return Target.SPEED;
		}
		if (wantRegen) {
			return Target.REGENERATION;
		}
		if (wantNightVision) {
			return Target.NIGHT_VISION;
		}
		if (lowHp && gappleAllowed) {
			return Target.GOLDEN_APPLE;
		}
		return Target.NONE;
	}
}

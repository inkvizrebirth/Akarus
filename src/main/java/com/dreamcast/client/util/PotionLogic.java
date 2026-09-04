package com.dreamcast.client.util;

/**
 * Чистые решения о зельях AutoBuff (без типов Minecraft — покрывается юнит-тестами).
 */
public final class PotionLogic {

	private PotionLogic() {
	}

	/** Чем «применять» эффект. */
	public enum Kind {
		DRINK,   // питьевое зелье (Items.POTION)
		SPLASH   // взрывное зелье (Items.SPLASH_POTION), бросок под ноги
	}

	/**
	 * Выбирает способ применения по режиму использования и наличию зелий.
	 *
	 * @param usage     режим: auto (приоритет взрывных при preferSplash),
	 *                  drink_only, splash_only, prefer_drink
	 * @param preferSplash приоритет взрывных внутри режима auto
	 * @param hasSplash есть подходящее взрывное зелье
	 * @param hasDrink  есть подходящее питьевое зелье
	 */
	public static Kind pick(String usage, boolean preferSplash,
	                        boolean hasSplash, boolean hasDrink) {
		boolean auto = "auto".equals(usage) || usage == null;
		boolean drinkFirst = "prefer_drink".equals(usage) || (auto && !preferSplash);
		if ("drink_only".equals(usage)) {
			return hasDrink ? Kind.DRINK : null;
		}
		if ("splash_only".equals(usage)) {
			return hasSplash ? Kind.SPLASH : null;
		}
		// auto / prefer_drink: сначала предпочитаемый способ, потом запасной
		if (drinkFirst) {
			if (hasDrink) {
				return Kind.DRINK;
			}
			return hasSplash ? Kind.SPLASH : null;
		}
		if (hasSplash) {
			return Kind.SPLASH;
		}
		return hasDrink ? Kind.DRINK : null;
	}

	/**
	 * Вреден ли эффект зелья: явный чёрный список плюс категория HARMFUL
	 * (ванильная — из {@code MobEffect.getCategory()}).
	 *
	 * @param effectPath        путь id эффекта (например, «weakness»)
	 * @param categoryHarmful   эффект относится к категории HARMFUL
	 */
	public static boolean harmful(String effectPath, boolean categoryHarmful) {
		if (categoryHarmful) {
			return true;
		}
		if (effectPath == null) {
			return false;
		}
		return switch (effectPath) {
			case "weakness", "slowness", "poison", "instant_damage", "harm",
					"wither", "hunger", "mining_fatigue", "nausea", "blindness",
					"darkness", "levitation", "unluck", "bad_omen", "trial_omen",
					"raid_omen", "wind_charged", "weaving", "oozing", "infested" -> true;
			default -> false;
		};
	}
}

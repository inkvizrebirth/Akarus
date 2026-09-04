package com.dreamcast.client.util;

/**
 * Разрешён ли старт броска взрывного зелья (чистая логика — покрывается тестами).
 * Все проверки должны выполняться ДО перехода стейт-машины в фазы броска.
 */
public final class ThrowGuard {

	private ThrowGuard() {
	}

	/**
	 * @param idle              стейт-машина свободна (предыдущее действие завершено)
	 * @param cooldownPassed    общий кулдаун между бросками прошёл
	 * @param previousConfirmed предыдущий бросок подтверждён (ничего не летит)
	 * @param guiClear          GUI закрыт (если настройка «не бросать при GUI» включена)
	 * @param playerInventory   открыт именно инвентарь игрока (не сундук/верстак)
	 * @param notUsingItem      игрок не пьёт/не ест/не натягивает
	 * @param alive             игрок жив
	 * @param groundOk          требование «стоять на земле» выполнено
	 * @param movingOk          требование «не в движении» выполнено
	 * @param groundBelow       под игроком есть поверхность (не пустота)
	 */
	public static boolean canStartThrow(boolean idle, boolean cooldownPassed,
	                                    boolean previousConfirmed, boolean guiClear,
	                                    boolean playerInventory, boolean notUsingItem,
	                                    boolean alive, boolean groundOk, boolean movingOk,
	                                    boolean groundBelow) {
		return idle && cooldownPassed && previousConfirmed && guiClear
				&& playerInventory && notUsingItem && alive
				&& groundOk && movingOk && groundBelow;
	}

	/**
	 * «Не бросать в воздухе»: под ногами в пределах досягаемости есть хоть
	 * один не-воздушный блок — зелье не улетит в пустоту.
	 *
	 * @param solidBelow найден твёрдый блок в пределах {@code depth} вниз
	 */
	public static boolean groundBelow(boolean solidBelow) {
		return solidBelow;
	}
}

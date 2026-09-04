package com.dreamcast.client.util;

/**
 * Перевод индексов {@code Inventory} (0..8 хотбар, 9..35 рюкзак) в id слотов
 * {@code InventoryMenu} (контейнера игрока).
 *
 * <p>Раскладка InventoryMenu в 26.2: 0 — крафт-результат, 1–4 — крафт-сетка,
 * 5–8 — броня, 9–35 — рюкзак (совпадает с индексами Inventory один в один),
 * 36–44 — хотбар, 45 — щит/оффхенд. Поэтому индекс хотбара 0..8 нельзя
 * передавать в контейнерный клик как есть — он попадёт в крафт/броню.</p>
 */
public final class SlotMath {

	public static final int HOTBAR_SIZE = 9;
	public static final int INVENTORY_SIZE = 36;
	public static final int MENU_HOTBAR_START = 36;

	private SlotMath() {
	}

	/**
	 * Id слота контейнера для индекса Inventory. Хотбар 0..8 → 36..44,
	 * рюкзак 9..35 остаётся как есть. Некорректные индексы возвращаются
	 * без изменений — клик по ним сервер отклонит, а не «пойдёт не туда».
	 */
	public static int inventoryToMenuSlot(int inventorySlot) {
		if (inventorySlot >= 0 && inventorySlot < HOTBAR_SIZE) {
			return inventorySlot + MENU_HOTBAR_START;
		}
		return inventorySlot;
	}

	/** Обратное преобразование: id слота контейнера → индекс Inventory (-1 — не инвентарь). */
	public static int menuToInventorySlot(int menuSlot) {
		if (menuSlot >= MENU_HOTBAR_START && menuSlot < MENU_HOTBAR_START + HOTBAR_SIZE) {
			return menuSlot - MENU_HOTBAR_START;
		}
		if (menuSlot >= HOTBAR_SIZE && menuSlot < INVENTORY_SIZE) {
			return menuSlot;
		}
		return -1;
	}
}

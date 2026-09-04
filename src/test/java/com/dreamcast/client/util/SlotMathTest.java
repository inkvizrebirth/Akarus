package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Раскладка InventoryMenu (26.2): 0 — крафт-результат, 1–4 — крафт, 5–8 — броня,
 * 9–35 — рюкзак, 36–44 — хотбар, 45 — щит. Индексы Inventory: 0–8 хотбар,
 * 9–35 рюкзак. Раньше STOW в AutoTotem передавал индекс хотбара как есть и
 * кликал в крафт/броню — эти тесты держат пересчёт.
 */
class SlotMathTest {

	@Test
	void hotbarShiftsToUseRow() {
		for (int hotbar = 0; hotbar < 9; hotbar++) {
			assertEquals(36 + hotbar, SlotMath.inventoryToMenuSlot(hotbar),
					"хотбар " + hotbar + " должен уходить в слот 36..44");
		}
	}

	@Test
	void backpackSlotsAreIdentical() {
		for (int bag = 9; bag < 36; bag++) {
			assertEquals(bag, SlotMath.inventoryToMenuSlot(bag),
					"рюкзак " + bag + " в InventoryMenu имеет тот же id");
		}
	}

	@Test
	void roundTripKeepsInventoryIndex() {
		for (int inv = 0; inv < 36; inv++) {
			assertEquals(inv, SlotMath.menuToInventorySlot(SlotMath.inventoryToMenuSlot(inv)),
					"обратное преобразование должно возвращать исходный индекс");
		}
	}

	@Test
	void nonInventorySlotsMapToMinusOne() {
		assertEquals(-1, SlotMath.menuToInventorySlot(0));   // крафт-результат
		assertEquals(-1, SlotMath.menuToInventorySlot(3));   // крафт-сетка
		assertEquals(-1, SlotMath.menuToInventorySlot(7));   // броня
		assertEquals(-1, SlotMath.menuToInventorySlot(45));  // щит/оффхенд
	}
}

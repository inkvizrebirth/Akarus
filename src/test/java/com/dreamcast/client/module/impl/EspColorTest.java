package com.dreamcast.client.module.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подкраска ESP по здоровью — единственная «цветовая» математика режима
 * «Свечение», которую можно проверить без кадра.
 *
 * <p>Важно не только «краснеет ли», но и то, что альфа канала не уезжает
 * (иначе слои ореола начнут складываться иначе, чем задумано) и что цвет не
 * становится чисто красным: «1 хп» и «уже мёртв» должны различаться.</p>
 */
class EspColorTest {

	private static int red(int color) {
		return (color >> 16) & 0xFF;
	}

	private static int green(int color) {
		return (color >> 8) & 0xFF;
	}

	private static int blue(int color) {
		return color & 0xFF;
	}

	@Test
	void fullHealthKeepsColorAsIs() {
		int cyan = 0xFF45E3FF;
		assertEquals(cyan, EspModule.tintByHealth(cyan, 1.0F));
	}

	@Test
	void alphaChannelNeverMoves() {
		for (float health = -1.0F; health <= 1.5F; health += 0.25F) {
			int tinted = EspModule.tintByHealth(0x8045E3FF, health);
			assertEquals(0x80, tinted >>> 24, "альфа — то, на что опирается сложение слоёв");
		}
	}

	@Test
	void nearlyDeadTurnsRedButNotPurely() {
		int cyan = 0xFF45E3FF;
		int tinted = EspModule.tintByHealth(cyan, 0.0F);
		assertTrue(red(tinted) > red(cyan), "красный растёт");
		assertTrue(green(tinted) < green(cyan), "зелёный гаснет");
		assertTrue(blue(tinted) < blue(cyan), "синий гаснет");
		assertTrue(green(tinted) > 0 || blue(tinted) > 0,
				"полностью багровым цель не становится — иначе не отличить «умирает» от «умерла»");
	}

	@Test
	void tintIsMonotonicByHealth() {
		int cyan = 0xFF45E3FF;
		int previousRed = red(EspModule.tintByHealth(cyan, 0.0F));
		for (float health = 0.1F; health <= 1.0F; health += 0.1F) {
			int current = red(EspModule.tintByHealth(cyan, health));
			assertTrue(current <= previousRed, "чем больше здоровья, тем меньше красноты");
			previousRed = current;
		}
	}

	@Test
	void outOfRangeHealthIsClamped() {
		assertEquals(EspModule.tintByHealth(0xFF45E3FF, 0.0F), EspModule.tintByHealth(0xFF45E3FF, -3.0F));
		assertEquals(0xFF45E3FF, EspModule.tintByHealth(0xFF45E3FF, 4.0F));
	}
}

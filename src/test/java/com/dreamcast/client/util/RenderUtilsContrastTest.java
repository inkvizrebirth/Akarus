package com.dreamcast.client.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Контраст в GUI. Проверка не «красиво ли получилось», а того, что подпись
 * физически различима на любом цвете темы: пользователь вправе выбрать хоть
 * тёмно-синий акцент, и тогда жёстко заданные цвета текста (или «всегда чёрный
 * на выбранном сегменте») превращаются в нечитаемые.
 */
class RenderUtilsContrastTest {

	@Test
	void luminanceMatchesWcag() {
		assertEquals(0.0F, RenderUtils.luminance(0xFF000000), 1e-4F);
		assertEquals(1.0F, RenderUtils.luminance(0xFFFFFFFF), 1e-3F);
		assertTrue(RenderUtils.luminance(0xFF00FF00) > RenderUtils.luminance(0xFF0000FF),
				"зелёный ярче синего — на этом стоит вся палитра интерфейса");
	}

	@Test
	void contrastIsSymmetricAndBounded() {
		float ratio = RenderUtils.contrast(0xFF000000, 0xFFFFFFFF);
		assertTrue(ratio > 20.0F && ratio <= 21.0F, "чёрный по белому — максимум по WCAG: " + ratio);
		assertEquals(ratio, RenderUtils.contrast(0xFFFFFFFF, 0xFF000000), 1e-4F);
		assertEquals(1.0F, RenderUtils.contrast(0xFF7C6CFF, 0xFF7C6CFF), 1e-4F);
	}

	@Test
	void darkAccentGetsLightened() {
		int background = 0xFF16161A; // стекло панели
		int darkAccent = 0xFF1B2A6B; // почти невидим на тёмном
		int fixed = RenderUtils.readableOn(background, darkAccent, 4.5F);
		assertTrue(RenderUtils.contrast(background, fixed) >= 4.5F,
				"после правки контраст обязан пройти порог: " + RenderUtils.contrast(background, fixed));
		assertTrue(((fixed >> 16) & 0xFF) <= ((fixed >> 8) & 0xFF) || ((fixed >> 16) & 0xFF) > 120,
				"и синеватый тон сохраняется: текст остаётся «акцентным», а не серым");
	}

	@Test
	void readableColorOnLightBackgroundTurnsDark() {
		int lightPill = 0xFFDFFF9E; // светлый «токсик»
		int white = 0xFFF6F6F8;
		int fixed = RenderUtils.readableOn(lightPill, white, 4.2F);
		assertTrue(fixed != white, "белый на светлом не оставляем");
		assertTrue(RenderUtils.contrast(lightPill, fixed) >= 4.2F);
	}

	@Test
	void alreadyReadableColorIsUntouched() {
		int background = 0xFF16161A;
		assertEquals(0xFFF6F6F8, RenderUtils.readableOn(background, 0xFFF6F6F8, 4.5F),
				"хороший цвет не портим");
	}

	@Test
	void alphaIsCompositedBeforeContrast() {
		int over = RenderUtils.opaqueOver(0x80FF0000, 0xFF000000);
		assertEquals(0xFF, over >>> 24, "результат всегда непрозрачный");
		assertEquals(0x80, (over >> 16) & 0xFF, "50 % красного поверх чёрного — 0x80");
		assertEquals(0x00, (over >> 8) & 0xFF);
		int cleared = RenderUtils.opaqueOver(0x00123456, 0xFF404040);
		assertEquals(0xFF404040, cleared, "нулевая альфа — это фон");
	}
}

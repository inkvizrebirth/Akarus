package com.dreamcast.client.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Процедурное «небо» — единственное место космоса, которое проверяемо без игры:
 * это чистые функции от координат. Важно, чтобы поле не расходилось между
 * кадрами (иначе текстура «кипит») и не вылезало за 0..1 (иначе цвет рвётся).
 */
class CosmosSamplerTest {

	@Test
	void hashStableAndSpread() {
		assertEquals(CosmosRenderer.hash(3, 5, 7), CosmosRenderer.hash(3, 5, 7));
		Set<Integer> distinct = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			distinct.add(CosmosRenderer.hash(i, i * 31 + 7, 11));
		}
		assertTrue(distinct.size() > 900, "хэш слишком «сыпется»: " + distinct.size());
	}

	@Test
	void noiseStaysInRange() {
		for (int i = -40; i <= 40; i++) {
			for (int j = -20; j <= 20; j++) {
				float value = CosmosRenderer.noiseAt(i * 0.37F, j * 0.29F, 3);
				assertTrue(value >= 0.0F && value <= 1.0F, "шум вышел за диапазон: " + value);
			}
		}
	}

	@Test
	void fbmClampedAndDeterministic() {
		float a = CosmosRenderer.fbm(1.25F, -3.5F, 9);
		float b = CosmosRenderer.fbm(1.25F, -3.5F, 9);
		assertEquals(a, b, 0.0F, "одни и те же координаты дают разный газ");
		assertTrue(a >= 0.0F && a <= 1.0F, "fbm вне диапазона: " + a);
	}

	@Test
	void starDensityIsMonotonic() {
		int sparse = 0;
		int dense = 0;
		for (int i = -60; i <= 60; i++) {
			for (int j = -60; j <= 60; j++) {
				if (CosmosRenderer.starCell(i * 0.41F, j * 0.27F, 10) > 0) {
					sparse++;
				}
				if (CosmosRenderer.starCell(i * 0.41F, j * 0.27F, 100) > 0) {
					dense++;
				}
			}
		}
		assertTrue(dense > sparse, "ручка «звёзды» не ускоряет: " + sparse + " -> " + dense);
		assertEquals(0, CosmosRenderer.starCell(2.5F, 7.25F, 0), "0%% — звёзд быть не должно");
	}

	@Test
	void edgeMaskFadesToBorder() {
		assertEquals(0.0F, CosmosRenderer.edgeMask(0.0F), 1.0e-6F);
		assertEquals(1.0F, CosmosRenderer.edgeMask(0.5F), 1.0e-6F);
		assertTrue(CosmosRenderer.edgeMask(0.06F) < CosmosRenderer.edgeMask(0.3F),
				"у края должно быть прозрачнее");
	}
}

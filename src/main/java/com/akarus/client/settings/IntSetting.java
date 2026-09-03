package com.akarus.client.settings;

/** Числовая настройка — в меню рисуется слайдером. */
public class IntSetting extends Setting<Integer> {

	private final int min;
	private final int max;

	public IntSetting(String id, String name, int value, int min, int max) {
		super(id, name, clamp(value, min, max));
		this.min = min;
		this.max = max;
	}

	public int get() {
		return getValue();
	}

	public void set(int value) {
		setValue(clamp(value, min, max));
	}

	public int getMin() {
		return min;
	}

	public int getMax() {
		return max;
	}

	/** Положение ползунка: 0 — минимум, 1 — максимум. */
	public float getNormalized() {
		if (max == min) {
			return 0.0f;
		}
		return (get() - min) / (float) (max - min);
	}

	public void setNormalized(float normalized) {
		set(min + Math.round((max - min) * clamp01(normalized)));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp01(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}
}

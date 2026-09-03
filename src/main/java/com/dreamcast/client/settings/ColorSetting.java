package com.dreamcast.client.settings;

/**
 * Настройка цвета.
 *
 * Значение хранится как ARGB-число (альфа почти всегда 0xFF), а в меню
 * редактируется строкой в формате {@code #RRGGBB} или {@code RRGGBB}:
 * плашка с текущим цветом, поле с кодом и мигающий курсор.
 */
public class ColorSetting extends Setting<Integer> {

	/** Разбирает строку вида {@code #AARRGGBB}, {@code #RRGGBB} или {@code RRGGBB}. */
	public static Integer parse(String text) {
		if (text == null) {
			return null;
		}

		String hex = text.trim();
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		}
		if (hex.startsWith("0x") || hex.startsWith("0X")) {
			hex = hex.substring(2);
		}
		if (hex.isEmpty() || hex.length() > 8) {
			return null;
		}

		for (int i = 0; i < hex.length(); i++) {
			if (Character.digit(hex.charAt(i), 16) < 0) {
				return null;
			}
		}

		try {
			long value = Long.parseLong(hex, 16);
			if (hex.length() <= 6) {
				return (int) (0xFF000000L | value);
			}
			return (int) value;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	public ColorSetting(String id, String name, int color) {
		super(id, name, color | 0xFF000000);
	}

	public int get() {
		return getValue() == null ? 0xFFFFFFFF : getValue();
	}

	public void set(int color) {
		setValue(color);
	}

	/** Применяет строку из поля ввода; вернёт false, если строка ещё не похожа на цвет. */
	public boolean trySetHex(String hex) {
		Integer parsed = parse(hex);
		if (parsed == null) {
			return false;
		}
		set(parsed);
		return true;
	}

	/** Код цвета для поля ввода, без «#»: RRGGBB или AARRGGBB. */
	public String getHex() {
		int color = get();
		return ((color & 0xFF000000) == 0xFF000000
				? String.format("%06X", color & 0x00FFFFFF)
				: String.format("%08X", color));
	}

	public int getRed() {
		return (get() >> 16) & 0xFF;
	}

	public int getGreen() {
		return (get() >> 8) & 0xFF;
	}

	public int getBlue() {
		return get() & 0xFF;
	}

	public int getAlpha() {
		return (get() >> 24) & 0xFF;
	}
}

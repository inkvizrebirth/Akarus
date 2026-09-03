package com.akarus.client.module;

/** Категории модулей (вкладки в левой части ClickGUI). */
public enum ModuleCategory {

	HUD("HUD", 0xFF45E3FF, "◎"),
	RENDER("Рендер", 0xFF7C6CFF, "◆"),
	MOVEMENT("Движение", 0xFF8DE06C, "»"),
	COMBAT("Бой", 0xFFFF5C7A, "✖"),
	MISC("Прочее", 0xFFFFC66C, "≡");

	private final String displayName;
	private final int accent;
	/** Глиф категории для бокового меню ClickGUI. */
	private final String glyph;

	ModuleCategory(String displayName, int accent, String glyph) {
		this.displayName = displayName;
		this.accent = accent;
		this.glyph = glyph;
	}

	public String getDisplayName() {
		return displayName;
	}

	/** Акцентный цвет категории (используется в меню и в HUD). */
	public int getAccent() {
		return accent;
	}

	/** Односимвольная иконка категории. */
	public String getGlyph() {
		return glyph;
	}
}

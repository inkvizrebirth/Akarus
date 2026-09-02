package com.akarus.client.module;

/** Категории модулей (вкладки в левой части ClickGUI). */
public enum ModuleCategory {

	HUD("HUD", 0xFF5CE1E6),
	RENDER("Рендер", 0xFF8A6CFF),
	MOVEMENT("Движение", 0xFFFFB86C),
	COMBAT("Бой", 0xFFFF5C7A),
	MISC("Прочее", 0xFF8DE06C);

	private final String displayName;
	private final int accent;

	ModuleCategory(String displayName, int accent) {
		this.displayName = displayName;
		this.accent = accent;
	}

	public String getDisplayName() {
		return displayName;
	}

	/** Акцентный цвет категории (используется в меню и в HUD). */
	public int getAccent() {
		return accent;
	}
}

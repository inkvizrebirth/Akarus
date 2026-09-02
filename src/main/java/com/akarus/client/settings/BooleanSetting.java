package com.akarus.client.settings;

/** Настройка-переключатель («да/нет»). */
public class BooleanSetting extends Setting<Boolean> {

	public BooleanSetting(String id, String name, boolean value) {
		super(id, name, value);
	}

	public boolean isEnabled() {
		return Boolean.TRUE.equals(getValue());
	}

	public void toggle() {
		setValue(!isEnabled());
	}
}

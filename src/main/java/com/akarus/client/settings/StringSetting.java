package com.akarus.client.settings;

/** Текстовая настройка — в меню редактируется с клавиатуры. */
public class StringSetting extends Setting<String> {

	public StringSetting(String id, String name, String value) {
		super(id, name, value == null ? "" : value);
	}

	public String get() {
		return getValue() == null ? "" : getValue();
	}

	public void set(String value) {
		setValue(value == null ? "" : value);
	}
}

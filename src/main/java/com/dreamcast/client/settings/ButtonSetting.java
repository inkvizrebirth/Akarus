package com.dreamcast.client.settings;

/**
 * Настройка-кнопка: не хранит значение, а выполняет действие по клику.
 *
 * Используется там, где из списка настроек нужно открыть отдельный экран —
 * например, «Настроить» в модуле «Обводка рук» открывает редактор раскладки.
 */
public class ButtonSetting extends Setting<String> {

	/** Действие кнопки. */
	public interface Action {
		void run();
	}

	private final String label;
	private final Action action;

	public ButtonSetting(String id, String name, String label, Action action) {
		// Значение у кнопки всегда одно и то же — подпись; в конфиг она не пишется
		super(id, name, label);
		this.label = label;
		this.action = action;
	}

	/** Текст на самой кнопке. */
	public String getLabel() {
		return label;
	}

	public void run() {
		if (action != null) {
			action.run();
		}
	}
}

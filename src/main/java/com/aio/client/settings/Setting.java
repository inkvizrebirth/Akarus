package com.aio.client.settings;

/**
 * Базовая настройка модуля.
 *
 * Пока используется только {@link BooleanSetting}, но заготовка сделана так,
 * чтобы дальше можно было добавить слайдеры (числа), выбор из списка и цвет.
 *
 * @param <T> тип значения настройки
 */
public abstract class Setting<T> {

	private final String id;
	private final String name;
	private T value;

	protected Setting(String id, String name, T value) {
		this.id = id;
		this.name = name;
		this.value = value;
	}

	/** Идентификатор для конфига (только латиница). */
	public String getId() {
		return id;
	}

	/** Отображаемое имя в меню. */
	public String getName() {
		return name;
	}

	public T getValue() {
		return value;
	}

	public void setValue(T value) {
		this.value = value;
	}
}

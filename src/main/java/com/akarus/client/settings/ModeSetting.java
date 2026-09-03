package com.akarus.client.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройка-переключатель из нескольких вариантов (в меню — сегменты, которые
 * листаются кликом по правому краю строки или колесом мыши).
 *
 * Значение в конфиге хранится как id варианта, поэтому подписи можно менять
 * без потери сохранённых настроек.
 */
public class ModeSetting extends Setting<String> {

	/** Один вариант выбора: id — для конфига и кода, label — для меню. */
	public record Option(String id, String label) {
	}

	private final List<Option> options;

	public ModeSetting(String id, String name, List<Option> options, String defaultId) {
		super(id, name, findOrDefault(options, defaultId).id());
		this.options = List.copyOf(options);
	}

	public static Option option(String id, String label) {
		return new Option(id, label);
	}

	private static Option findOrDefault(List<Option> options, String id) {
		for (Option option : options) {
			if (option.id().equals(id)) {
				return option;
			}
		}
		return options.get(0);
	}

	public List<Option> getOptions() {
		return options;
	}

	/** Человекочитаемое имя текущего варианта. */
	public String getLabel() {
		return current().label();
	}

	public Option current() {
		return findOrDefault(options, getValue());
	}

	public int indexOfCurrent() {
		String value = getValue();
		for (int i = 0; i < options.size(); i++) {
			if (options.get(i).id().equals(value)) {
				return i;
			}
		}
		return 0;
	}

	/** Идти по вариантам; direction &gt; 0 — вперёд, &lt; 0 — назад. Замыкается по кругу. */
	public void shift(int direction) {
		if (options.size() < 2 || direction == 0) {
			return;
		}
		int size = options.size();
		int index = Math.floorMod(indexOfCurrent() + Integer.signum(direction), size);
		setValue(options.get(index).id());
	}

	public void set(int index) {
		if (index >= 0 && index < options.size()) {
			setValue(options.get(index).id());
		}
	}

	/** Принимает значение из конфига; неизвестный вариант игнорируется. */
	public boolean trySetId(String id) {
		for (Option option : options) {
			if (option.id().equals(id)) {
				setValue(id);
				return true;
			}
		}
		return false;
	}

	/** Текущий вариант — тот, что запрошен. */
	public boolean is(String id) {
		return getValue().equals(id);
	}

	/** Удобный список подписей для меню. */
	public List<String> labels() {
		List<String> labels = new ArrayList<>(options.size());
		for (Option option : options) {
			labels.add(option.label());
		}
		return labels;
	}
}

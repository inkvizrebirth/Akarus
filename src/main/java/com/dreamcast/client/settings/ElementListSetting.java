package com.dreamcast.client.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройка-список с множественным выбором: «что показывать на HUD».
 *
 * В меню рисуется не тумблерами, а списком строк с отметкой — то, что выбрано,
 * то и показывается (см. пункт «HUD без выключателя»). В конфиге хранится
 * строкой из id через запятую, поэтому правится руками без труда.
 */
public class ElementListSetting extends Setting<String> {

	/** Один элемент списка: id — для конфига, label — для меню. */
	public record Element(String id, String label) {
	}

	private final List<Element> elements;
	/** Выбранные id в порядке списка. */
	private final List<String> selected = new ArrayList<>();

	public ElementListSetting(String id, String name, List<Element> elements, String... defaultSelected) {
		super(id, name, String.join(",", defaultSelected));
		this.elements = List.copyOf(elements);
		for (String elementId : defaultSelected) {
			if (elementOf(elementId) != null && !this.selected.contains(elementId)) {
				this.selected.add(elementId);
			}
		}
	}

	public List<Element> getElements() {
		return elements;
	}

	public boolean isSelected(String elementId) {
		return selected.contains(elementId);
	}

	/** Переключает элемент и возвращает его новое состояние. */
	public boolean toggle(String elementId) {
		if (elementOf(elementId) == null) {
			return false;
		}
		if (selected.contains(elementId)) {
			selected.remove(elementId);
		} else {
			selected.add(elementId);
		}
		setValue(String.join(",", selected));
		return isSelected(elementId);
	}

	/** Клик по строке выбирает элемент одиночным значением (радио-поведение). */
	public void selectExclusive(String elementId) {
		if (elementOf(elementId) == null) {
			return;
		}
		selected.clear();
		selected.add(elementId);
		setValue(String.join(",", selected));
	}

	public boolean applySaved(String joined) {
		if (joined == null) {
			return false;
		}
		List<String> parsed = new ArrayList<>();
		for (String part : joined.split(",")) {
			String id = part.trim();
			if (elementOf(id) != null && !parsed.contains(id)) {
				parsed.add(id);
			}
		}
		// Пустой набор тоже сохраняем: HUD разрешено полностью очистить.
		selected.clear();
		selected.addAll(parsed);
		setValue(String.join(",", parsed));
		return true;
	}

	private Element elementOf(String elementId) {
		for (Element element : elements) {
			if (element.id().equals(elementId)) {
				return element;
			}
		}
		return null;
	}

	/** Подписи выбранных элементов — для всплывающих подсказок и статусов. */
	public Map<String, Boolean> selectionSnapshot() {
		Map<String, Boolean> snapshot = new LinkedHashMap<>();
		for (Element element : elements) {
			snapshot.put(element.id(), selected.contains(element.id()));
		}
		return snapshot;
	}
}

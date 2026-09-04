package com.dreamcast.client.module.impl;

import com.dreamcast.client.gui.hud.HudEditorScreen;
import com.dreamcast.client.gui.hud.HudLayout;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.ButtonSetting;
import com.dreamcast.client.settings.ElementListSetting;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * HUD — набор элементов экрана.
 *
 * Выключателя у модуля нет: HUD — это «что выбрано, то и показывается».
 * Список элементов — ватермарк, инфопанель, список модулей, бинды,
 * медиаплеер, уведомления — отмечается прямо в настройках модуля.
 *
 * Раскладка: клавиша модуля (по умолчанию H) открывает редактор HUD,
 * а с открытым чатом элементы можно тащить мышью прямо по экрану.
 */
public class HudInfoModule extends Module {

	public static final String ELEMENT_WATERMARK = "watermark";
	public static final String ELEMENT_INFO = "info";
	public static final String ELEMENT_MODULE_LIST = "module_list";
	public static final String ELEMENT_KEYBINDS = "keybinds";
	public static final String ELEMENT_MEDIA = "media";
	public static final String ELEMENT_NOTIFICATIONS = "notifications";
	public static final String ELEMENT_TARGET = "target";
	public static final String ELEMENT_EFFECTS = "effects";
	public static final String ELEMENT_ARMOR = "armor";
	public static final String ELEMENT_KEYSTROKES = "keystrokes";
	public static final String ELEMENT_SESSION = "session";

	private final ElementListSetting elements = new ElementListSetting("elements", "Элементы HUD",
			List.of(
					new ElementListSetting.Element(ELEMENT_WATERMARK, "Водяной знак"),
					new ElementListSetting.Element(ELEMENT_INFO, "FPS · координаты · пинг"),
					new ElementListSetting.Element(ELEMENT_MODULE_LIST, "Список модулей"),
						new ElementListSetting.Element(ELEMENT_KEYBINDS, "Бинды"),
						new ElementListSetting.Element(ELEMENT_MEDIA, "Медиаплеер"),
						new ElementListSetting.Element(ELEMENT_NOTIFICATIONS, "Уведомления"),
						new ElementListSetting.Element(ELEMENT_TARGET, "Target HUD"),
						new ElementListSetting.Element(ELEMENT_EFFECTS, "Активные эффекты"),
						new ElementListSetting.Element(ELEMENT_ARMOR, "Броня и оффхенд"),
						new ElementListSetting.Element(ELEMENT_KEYSTROKES, "Keystrokes и CPS"),
						new ElementListSetting.Element(ELEMENT_SESSION, "Статистика сессии")),
				ELEMENT_WATERMARK, ELEMENT_INFO, ELEMENT_MODULE_LIST, ELEMENT_KEYBINDS,
				ELEMENT_MEDIA, ELEMENT_NOTIFICATIONS, ELEMENT_TARGET, ELEMENT_EFFECTS,
				ELEMENT_ARMOR, ELEMENT_KEYSTROKES, ELEMENT_SESSION);

	public HudInfoModule() {
		super("hud_info", "HUD", "Элементы на экране: выбери, что показывать, и расставь их",
				ModuleCategory.HUD, GLFW.GLFW_KEY_H);
		addSetting(elements);
		addSetting(new ButtonSetting("editor", "Раскладка", "Открыть редактор", () -> {
			HudEditorScreen.open();
		}));
		addSetting(new ButtonSetting("reset_layout", "Сброс", "Сбросить позиции", () -> {
			HudLayout.resetAll();
		}));
	}

	/** HUD нельзя «выключить» целиком — только снять галочки с элементов. */
	@Override
	protected boolean alwaysEnabled() {
		return true;
	}

	/** Клавиша модуля открывает редактор раскладки, а не переключает состояние. */
	@Override
	protected void onBindPressed() {
		HudEditorScreen.open();
	}

	public boolean shows(String elementId) {
		return elements.isSelected(elementId);
	}

	public ElementListSetting elementList() {
		return elements;
	}
}

package com.akarus.client.module.impl;

import com.akarus.client.gui.hud.HudEditorScreen;
import com.akarus.client.gui.hud.HudLayout;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.ButtonSetting;
import com.akarus.client.settings.ElementListSetting;
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

	private final ElementListSetting elements = new ElementListSetting("elements", "Элементы HUD",
			List.of(
					new ElementListSetting.Element(ELEMENT_WATERMARK, "Водяной знак"),
					new ElementListSetting.Element(ELEMENT_INFO, "FPS · координаты · пинг"),
					new ElementListSetting.Element(ELEMENT_MODULE_LIST, "Список модулей"),
					new ElementListSetting.Element(ELEMENT_KEYBINDS, "Бинды"),
					new ElementListSetting.Element(ELEMENT_MEDIA, "Медиаплеер"),
					new ElementListSetting.Element(ELEMENT_NOTIFICATIONS, "Уведомления")),
			ELEMENT_WATERMARK, ELEMENT_INFO, ELEMENT_MODULE_LIST, ELEMENT_KEYBINDS,
			ELEMENT_MEDIA, ELEMENT_NOTIFICATIONS);

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

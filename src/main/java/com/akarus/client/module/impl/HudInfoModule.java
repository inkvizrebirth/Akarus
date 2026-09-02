package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import org.lwjgl.glfw.GLFW;

/**
 * Демонстрационный модуль: информационный HUD.
 *
 * Показывает FPS, координаты, направление, пинг, водяной знак
 * и список включённых модулей. Каждую строку можно отключить в меню.
 */
public class HudInfoModule extends Module {

	private final BooleanSetting fps = bool("fps", "FPS", true);
	private final BooleanSetting coordinates = bool("coordinates", "Координаты", true);
	private final BooleanSetting direction = bool("direction", "Направление", true);
	private final BooleanSetting ping = bool("ping", "Пинг", true);
	private final BooleanSetting watermark = bool("watermark", "Водяной знак", true);
	private final BooleanSetting moduleList = bool("module_list", "Список модулей", true);

	public HudInfoModule() {
		super("hud_info", "HUD-инфо", "Показывает FPS, координаты, пинг и активные модули",
				ModuleCategory.HUD, GLFW.GLFW_KEY_H);
	}

	public boolean showFps() {
		return fps.isEnabled();
	}

	public boolean showCoordinates() {
		return coordinates.isEnabled();
	}

	public boolean showDirection() {
		return direction.isEnabled();
	}

	public boolean showPing() {
		return ping.isEnabled();
	}

	public boolean showWatermark() {
		return watermark.isEnabled();
	}

	public boolean showModuleList() {
		return moduleList.isEnabled();
	}
}

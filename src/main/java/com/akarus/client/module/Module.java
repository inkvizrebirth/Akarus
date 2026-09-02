package com.akarus.client.module;

import com.akarus.client.AkarusClient;
import com.akarus.client.config.ConfigManager;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Базовый модуль клиента.
 *
 * Модуль — это отдельная функция, которую можно включить/выключить
 * в ClickGUI или своей клавишей. Новый модуль = наследник этого класса,
 * зарегистрированный в {@link ModuleManager#init()}.
 */
public abstract class Module {

	private final String id;
	private final String name;
	private final String description;
	private final ModuleCategory category;
	private final KeyMapping keyMapping;
	private final List<Setting<?>> settings = new ArrayList<>();

	private boolean enabled;

	protected Module(String id, String name, String description, ModuleCategory category, int defaultKey) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.category = category;

		// Регистрируем клавишу модуля в ванильных настройках управления
		this.keyMapping = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + AkarusClient.MOD_ID + "." + id,
				InputConstants.Type.KEYSYM,
				defaultKey,
				AkarusClient.KEY_CATEGORY));
	}

	/** Включает модуль, если он выключен — и наоборот. */
	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		setEnabledSilently(enabled);
		ConfigManager.save();
	}

	/**
	 * Включает/выключает модуль без записи в конфиг.
	 * Используется при загрузке сохранённых настроек.
	 */
	public void setEnabledSilently(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;

		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	/** Вызывается при включении модуля. */
	protected void onEnable() {
	}

	/** Вызывается при выключении модуля. */
	protected void onDisable() {
	}

	/** Вызывается каждый тик клиента, пока модуль включён. */
	public void tick() {
	}

	protected BooleanSetting bool(String id, String name, boolean defaultValue) {
		BooleanSetting setting = new BooleanSetting(id, name, defaultValue);
		settings.add(setting);
		return setting;
	}

	public List<Setting<?>> getSettings() {
		return settings;
	}

	public Optional<BooleanSetting> getBooleanSetting(String settingId) {
		return settings.stream()
				.filter(setting -> setting instanceof BooleanSetting && setting.getId().equals(settingId))
				.map(setting -> (BooleanSetting) setting)
				.findFirst();
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public ModuleCategory getCategory() {
		return category;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public KeyMapping getKeyMapping() {
		return keyMapping;
	}
}

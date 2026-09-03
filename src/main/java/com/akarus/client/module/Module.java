package com.akarus.client.module;

import com.akarus.client.AkarusClient;
import com.akarus.client.config.ConfigManager;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.Setting;
import com.akarus.client.settings.StringSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

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

	/** Текущий бинд модуля. Меняется прямо из ClickGUI. */
	private InputConstants.Key bind;

	private boolean enabled;

	protected Module(String id, String name, String description, ModuleCategory category, int defaultKey) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.category = category;

		// Регистрируем клавишу модуля в ванильных настройках управления
		this.bind = InputConstants.Type.KEYSYM.getOrCreate(defaultKey);
		this.keyMapping = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + AkarusClient.MOD_ID + "." + id,
				InputConstants.Type.KEYSYM,
				defaultKey,
				AkarusClient.KEY_CATEGORY));
	}

	// ------------------------------------------------------------------
	// Бинд
	// ------------------------------------------------------------------

	/** Клавиша (или кнопка мыши), на которую сейчас повешен модуль. */
	public InputConstants.Key getBind() {
		return bind;
	}

	/**
	 * Назначает модулю новую клавишу.
	 *
	 * Помимо нашего поля обновляется и ванильная таблица биндов, иначе игра
	 * продолжала бы реагировать на старую клавишу.
	 */
	public void setBind(InputConstants.Key key) {
		this.bind = key == null ? InputConstants.UNKNOWN : key;
		this.keyMapping.setKey(this.bind);
		// setKey() сам не перестраивает внутреннюю таблицу соответствий — делаем это вручную
		KeyMapping.resetMapping();
	}

	/** Имя бинда в том виде, в котором он хранится в конфиге. */
	public String getBindName() {
		return bind.getName();
	}

	public void setBindByName(String name) {
		InputConstants.Key key = InputConstants.getKey(name);
		if (key != InputConstants.UNKNOWN) {
			setBind(key);
		}
	}

	/** Человекочитаемое название клавиши для меню. */
	public String getBindLabel() {
		if (bind == InputConstants.UNKNOWN) {
			return "—";
		}
		Component label = bind.getDisplayName();
		return label == null ? "—" : label.getString();
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

	protected IntSetting intSetting(String id, String name, int value, int min, int max) {
		IntSetting setting = new IntSetting(id, name, value, min, max);
		settings.add(setting);
		return setting;
	}

	protected StringSetting textSetting(String id, String name, String value) {
		StringSetting setting = new StringSetting(id, name, value);
		settings.add(setting);
		return setting;
	}

	/** Вызывается после изменения любой настройки модуля в меню. */
	public void onSettingsChanged() {
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

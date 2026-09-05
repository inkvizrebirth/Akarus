package com.dreamcast.client.module;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.config.ConfigManager;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ButtonSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.settings.Setting;
import com.dreamcast.client.settings.StringSetting;
import com.dreamcast.client.util.Notifications;
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

	/**
	 * Состояние модуля при первом запуске (когда в конфиге про модуль ещё нет записи).
	 * По умолчанию выключен; включённым имеют смысл модули-«настройки внешности»,
	 * вроде раскладки рук.
	 */
	protected boolean defaultEnabled() {
		return false;
	}

	/**
	 * Модули-«настройки» (ClickGUI, HUD): их нельзя выключить — выключателя
	 * в меню у них нет, а setEnabled молча игнорирует попытки.
	 */
	protected boolean alwaysEnabled() {
		return false;
	}

	public boolean isAlwaysEnabled() {
		return alwaysEnabled();
	}

	/**
	 * Что делает нажатие клавиши модуля. Обычно — переключение, но у модулей-«экранов»
	 * (ClickGUI, HUD-редактор) клавиша открывает их окно.
	 */
	protected void onBindPressed() {
		toggle();
	}

	protected Module(String id, String name, String description, ModuleCategory category, int defaultKey) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.category = category;

		// При чистом первом запуске клиент не навязывает горячие клавиши: это
		// исключает случайное включение боевых/движенческих модулей во время игры.
		// Единственное исключение — ClickGUI, без него новый пользователь вообще
		// не сможет открыть настройки и назначить собственные бинды.
		int initialKey = "click_gui".equals(id) ? defaultKey : org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN;
		// Регистрируем клавишу модуля в ванильных настройках управления
		this.bind = InputConstants.Type.KEYSYM.getOrCreate(initialKey);
		this.keyMapping = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + DreamcastClient.MOD_ID + "." + id,
				InputConstants.Type.KEYSYM,
				initialKey,
				DreamcastClient.KEY_CATEGORY));

		this.enabled = defaultEnabled();
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
		// UNKNOWN тоже является валидным сохранённым значением: так снятый
		// пользователем бинд не превращается обратно в дефолтный после перезапуска.
		setBind(key);
	}

	/** Есть ли у модуля назначенная клавиша или кнопка мыши. */
	public boolean hasBind() {
		return !InputConstants.UNKNOWN.equals(bind);
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
		if (alwaysEnabled() || this.enabled == enabled) {
			return;
		}
		setEnabledSilently(enabled);
		ConfigManager.save();
		// Уведомление на HUD: «включилось/выключилось»
		Notifications.push("Модуль",
				getName() + (enabled ? " — включён" : " — выключен"),
				enabled ? Notifications.Type.OK : Notifications.Type.INFO);
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

	/** Настройка с выбором одного из нескольких вариантов. */
	protected ModeSetting mode(String id, String name, String defaultId, ModeSetting.Option... options) {
		ModeSetting setting = new ModeSetting(id, name, List.of(options), defaultId);
		settings.add(setting);
		return setting;
	}

	protected ColorSetting colorSetting(String id, String name, int color) {
		ColorSetting setting = new ColorSetting(id, name, color);
		settings.add(setting);
		return setting;
	}

	/**
	 * Добавляет уже созданную настройку — для типов, у которых нет своего хелпера
	 * (например, список элементов HUD).
	 */
	protected <T extends Setting<?>> T addSetting(T setting) {
		settings.add(setting);
		return setting;
	}

	protected ButtonSetting buttonSetting(String id, String name, String label, ButtonSetting.Action action) {
		ButtonSetting setting = new ButtonSetting(id, name, label, action);
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

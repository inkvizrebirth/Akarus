package com.akarus.client.module;

import com.akarus.client.AkarusClient;
import com.akarus.client.config.ConfigManager;
import com.akarus.client.module.impl.AutoMineModule;
import com.akarus.client.module.impl.AutoWalkModule;
import com.akarus.client.module.impl.FreeCamModule;
import com.akarus.client.module.impl.HandShaderModule;
import com.akarus.client.module.impl.HudInfoModule;
import com.akarus.client.module.impl.ViewModelModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Реестр всех модулей клиента. */
public final class ModuleManager {

	private static final List<Module> MODULES = new ArrayList<>();

	private ModuleManager() {
	}

	/** Регистрация модулей. Новые модули добавляются здесь одной строкой. */
	public static void init() {
		register(new HudInfoModule());
		register(new FreeCamModule());
		register(new AutoMineModule());
		register(new AutoWalkModule());
		register(new HandShaderModule());
		register(new ViewModelModule());
	}

	public static void register(Module module) {
		MODULES.add(module);
		// Подтягиваем сохранённые значения из конфига
		ConfigManager.applyTo(module);
		AkarusClient.LOGGER.info("Модуль зарегистрирован: {} ({})", module.getName(), module.getCategory().getDisplayName());
	}

	public static List<Module> getAll() {
		return Collections.unmodifiableList(MODULES);
	}

	public static List<Module> getByCategory(ModuleCategory category) {
		return MODULES.stream()
				.filter(module -> module.getCategory() == category)
				.toList();
	}

	@SuppressWarnings("unchecked")
	public static <T extends Module> T getModule(Class<T> type) {
		for (Module module : MODULES) {
			if (type.isInstance(module)) {
				return (T) module;
			}
		}
		throw new IllegalStateException("Модуль не найден: " + type.getName());
	}

	/**
	 * То же, что {@link #getModule(Class)}, но вместо исключения возвращает null.
	 *
	 * Нужно коду, который вызывается до инициализации модулей или вообще без них —
	 * например, миксинам рендера: падать из-за них игра не должна.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends Module> T find(Class<T> type) {
		for (Module module : MODULES) {
			if (type.isInstance(module)) {
				return (T) module;
			}
		}
		return null;
	}

	/** Вызывается каждый тик клиента: обрабатывает клавиши модулей и их логику. */
	public static void tick() {
		for (Module module : MODULES) {
			while (module.getKeyMapping().consumeClick()) {
				module.toggle();
			}

			if (module.isEnabled()) {
				module.tick();
			}
		}
	}
}

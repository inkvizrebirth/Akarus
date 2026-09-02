package com.aio.client.module;

import com.aio.client.AioClient;
import com.aio.client.config.ConfigManager;
import com.aio.client.module.impl.HudInfoModule;

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
	}

	public static void register(Module module) {
		MODULES.add(module);
		// Подтягиваем сохранённые значения из конфига
		ConfigManager.applyTo(module);
		AioClient.LOGGER.info("Модуль зарегистрирован: {} ({})", module.getName(), module.getCategory().getDisplayName());
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

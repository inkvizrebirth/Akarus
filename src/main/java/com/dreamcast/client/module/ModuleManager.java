package com.dreamcast.client.module;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.config.ConfigManager;
import com.dreamcast.client.module.impl.AutoMineModule;
import com.dreamcast.client.module.impl.AutoTotemModule;
import com.dreamcast.client.module.impl.AutoWalkModule;
import com.dreamcast.client.module.impl.BlockEspModule;
import com.dreamcast.client.module.impl.SpiderModule;
import com.dreamcast.client.module.impl.ClickGuiModule;
import com.dreamcast.client.module.impl.EspModule;
import com.dreamcast.client.module.impl.FreeCamModule;
import com.dreamcast.client.module.impl.FreeLookModule;
import com.dreamcast.client.module.impl.HandShaderModule;
import com.dreamcast.client.module.impl.HudInfoModule;
import com.dreamcast.client.module.impl.JumpEffectModule;
import com.dreamcast.client.module.impl.HitParticlesModule;
import com.dreamcast.client.module.impl.HitSoundsModule;
import com.dreamcast.client.module.impl.MacroModule;
import com.dreamcast.client.module.impl.AutoBuffModule;
import com.dreamcast.client.module.impl.NoSlowModule;
import com.dreamcast.client.module.impl.NametagsModule;
import com.dreamcast.client.module.impl.KillAuraModule;
import com.dreamcast.client.module.impl.MediaPlayerModule;
import com.dreamcast.client.module.impl.NoBlindModule;
import com.dreamcast.client.module.impl.NoFallDamageModule;
import com.dreamcast.client.module.impl.NoFovModule;
import com.dreamcast.client.module.impl.SprintModule;
import com.dreamcast.client.module.impl.TrailsModule;
import com.dreamcast.client.module.impl.ViewModelModule;

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
		register(new ClickGuiModule());
		register(new FreeCamModule());
		register(new FreeLookModule());
		register(new AutoMineModule());
		register(new AutoWalkModule());
		register(new KillAuraModule());
		register(new SprintModule());
		register(new NoFovModule());
		register(new NoBlindModule());
		register(new NoFallDamageModule());
		register(new HandShaderModule());
		register(new ViewModelModule());
		register(new AutoTotemModule());
		register(new MediaPlayerModule());
		register(new TrailsModule());
		register(new EspModule());
		register(new BlockEspModule());
		register(new JumpEffectModule());
		register(new SpiderModule());
		register(new NametagsModule());
		register(new NoSlowModule());
		register(new AutoBuffModule());
		register(new MacroModule());
		register(new HitSoundsModule());
		register(new HitParticlesModule());
	}

	public static void register(Module module) {
		MODULES.add(module);
		// Подтягиваем сохранённые значения из конфига
		ConfigManager.applyTo(module);
		DreamcastClient.LOGGER.info("Модуль зарегистрирован: {} ({})", module.getName(), module.getCategory().getDisplayName());
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
				module.onBindPressed();
			}

			if (module.isEnabled()) {
				module.tick();
			}
		}
	}
}

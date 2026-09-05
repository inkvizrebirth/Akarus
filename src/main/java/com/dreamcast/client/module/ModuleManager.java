package com.dreamcast.client.module;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.config.ConfigManager;
import com.dreamcast.client.module.impl.AutoGgModule;
import com.dreamcast.client.module.impl.AutoMineModule;
import com.dreamcast.client.module.impl.BaritoneModule;
import com.dreamcast.client.module.impl.RainModule;
import com.dreamcast.client.module.impl.GlintColorModule;
import com.dreamcast.client.module.impl.AutoTotemModule;
import com.dreamcast.client.module.impl.AutoWalkModule;
import com.dreamcast.client.module.impl.BlockEspModule;
import com.dreamcast.client.module.impl.ChinaHatModule;
import com.dreamcast.client.module.impl.MotionBlurModule;
import com.dreamcast.client.module.impl.TargetEspModule;
import com.dreamcast.client.module.impl.WingsModule;
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
import com.dreamcast.client.module.impl.ScaffoldModule;
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
		if (!MODULES.isEmpty()) {
			DreamcastClient.LOGGER.warn("Повторная инициализация модулей проигнорирована");
			return;
		}
		// Порядок регистрации = порядок тика = приоритет действий:
		// тотем важнее воды, вода важнее баффов, баффы важнее строения,
		// строение важнее удара — так модули не перетягивают инвентарь
		register(new AutoTotemModule());
		register(new NoFallDamageModule());
		register(new AutoBuffModule());
		register(new ScaffoldModule());
		register(new KillAuraModule());
		register(new HudInfoModule());
		register(new ClickGuiModule());
		register(new FreeCamModule());
		register(new FreeLookModule());
		register(new AutoMineModule());
		register(new AutoWalkModule());
		register(new SprintModule());
		register(new NoFovModule());
		register(new NoBlindModule());
		register(new HandShaderModule());
		register(new ViewModelModule());
		register(new MediaPlayerModule());
		register(new TrailsModule());
		register(new EspModule());
		register(new BlockEspModule());
		register(new JumpEffectModule());
		register(new SpiderModule());
		register(new NametagsModule());
		register(new NoSlowModule());
		register(new MacroModule());
		register(new HitSoundsModule());
		register(new HitParticlesModule());
		register(new ChinaHatModule());
		register(new WingsModule());
		register(new TargetEspModule());
		register(new MotionBlurModule());
		register(new AutoGgModule());
		register(new BaritoneModule());
		register(new GlintColorModule());
		register(new RainModule());
	}

	public static void register(Module module) {
		if (MODULES.stream().anyMatch(existing -> existing.getId().equals(module.getId()))) {
			throw new IllegalArgumentException("Модуль уже зарегистрирован: " + module.getId());
		}
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
			try {
				while (module.getKeyMapping().consumeClick()) {
					module.onBindPressed();
				}

				if (module.isEnabled()) {
					module.tick();
				}
			} catch (RuntimeException error) {
				DreamcastClient.LOGGER.error("Модуль {} аварийно остановлен", module.getId(), error);
				com.dreamcast.client.rotation.RotationManager.release(module);
				try {
					module.setEnabledSilently(false);
				} catch (RuntimeException cleanupError) {
					error.addSuppressed(cleanupError);
					DreamcastClient.LOGGER.error("Не удалось безопасно выключить модуль {}", module.getId(), cleanupError);
				}
				com.dreamcast.client.util.Notifications.error(
						module.getName(), "Остановлен из-за внутренней ошибки; подробности в latest.log");
			}
		}

		// Слой поворотов: если в этом тике никто не продлевал заявку, он сам
		// отпустит камеру и перестанет подменять поворот в пакетах движения
		com.dreamcast.client.rotation.RotationManager.tickEnd();
	}
}

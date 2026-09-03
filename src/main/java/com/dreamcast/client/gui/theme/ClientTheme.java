package com.dreamcast.client.gui.theme;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.ClickGuiModule;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.util.Util;

/**
 * Темы клиента: пара цветов градиента + плавный «перелив» между ними.
 *
 * Пресеты заданы в {@link ClickGuiModule} (настройка «Тема»), свои цвета —
 * двумя настройками-цветами там же. Всё, что здесь, — только чтение: тема
 * спрашивается каждый кадр, поэтому методы дешёвые и без аллокаций.
 *
 * Градиент «переливается»: фаза бегает по синусу, и в каждый момент цвета
 * чуть сдвинуты друг к другу — окно выглядит живым даже в статике.
 */
public final class ClientTheme {

	/** Пресет темы: имя + два цвета градиента. */
	public record Preset(String id, String label, int first, int second) {
	}

	public static final Preset[] PRESETS = {
			new Preset("dreamcast", "Dreamcast", 0xFF7C6CFF, 0xFF45E3FF),
			new Preset("sakura", "Сакура", 0xFFFF6EC7, 0xFFFE8CA8),
			new Preset("sunset", "Закат", 0xFFFF9966, 0xFFFF5E62),
			new Preset("toxic", "Токсик", 0xFFB6FF00, 0xFF00E5A8),
			new Preset("ice", "Лёд", 0xFF8CE8FF, 0xFF4A7CFF),
			new Preset("amethyst", "Аметист", 0xFFC56CFF, 0xFFFF6EA9),
			new Preset("gold", "Золото", 0xFFFFD45E, 0xFFFF7A45),
	};

	/** Найденный пресет по id (для подсветки текущего в меню). */
	public static Preset preset(String id) {
		for (Preset preset : PRESETS) {
			if (preset.id().equals(id)) {
				return preset;
			}
		}
		return PRESETS[0];
	}

	private ClientTheme() {
	}

	/** Первый цвет темы. */
	public static int first() {
		ClickGuiModule module = ModuleManager.find(ClickGuiModule.class);
		if (module == null) {
			return PRESETS[0].first();
		}
		return module.themeFirstColor();
	}

	/** Второй цвет темы. */
	public static int second() {
		ClickGuiModule module = ModuleManager.find(ClickGuiModule.class);
		if (module == null) {
			return PRESETS[0].second();
		}
		return module.themeSecondColor();
	}

	/**
	 * Текущий «акцент» — цвет в середине перелива. Подходит для тумблеров,
	 * ползунков и рамок: он всегда между цветами темы и никогда не «выпадает».
	 */
	public static int accent() {
		return accent(Util.getMillis());
	}

	public static int accent(long now) {
		float phase = flowPhase(now);
		return RenderUtils.mix(first(), second(), 0.5f + 0.5f * phase);
	}

	/**
	 * Цвет градиента в точке {@code t} (0..1 по ширине окна) с учётом перелива.
	 * Фаза двигает точку «встречи» цветов туда-сюда — градиент течёт.
	 */
	public static int gradientAt(float t, long now) {
		ClickGuiModule module = ModuleManager.find(ClickGuiModule.class);
		float speed = module == null ? 1.0f : module.flowSpeed01();
		float phase = (float) Math.sin(now / 1000.0 * speed * 2.4);
		float shift = 0.5f + 0.5f * phase;
		// Смешиваем с бэкингом: t уходит в «пружину» между цветами
		float k = clamp01(0.5f * shift + t * (1.0f - 0.5f * Math.abs(shift - 0.5f) * 2));
		k = clamp01(t + (shift - 0.5f) * (1.0f - Math.abs(t - 0.5f) * 2) * 0.65f);
		return RenderUtils.mix(first(), second(), k);
	}

	/** Фаза перелива −1..1 (для внешних анимаций, которые хотят синхронизации). */
	public static float flowPhase(long now) {
		ClickGuiModule module = ModuleManager.find(ClickGuiModule.class);
		float speed = module == null ? 1.0f : module.flowSpeed01();
		return (float) Math.sin(now / 1000.0 * speed * 2.4);
	}

	private static float clamp01(float value) {
		return Math.max(0.0f, Math.min(1.0f, value));
	}
}

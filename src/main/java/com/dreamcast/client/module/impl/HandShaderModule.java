package com.dreamcast.client.module.impl;

import com.dreamcast.client.gui.HandEditorScreen;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.render.HandOutlineRenderer.Spec;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ButtonSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Обводка и свечение рук от первого лица.
 *
 * Работает вместе с миксинами {@code ItemInHandRendererMixin}, {@code AvatarRendererMixin}
 * и {@code SubmitNodeCollectionMixin}; вся геометрия и смещения — в {@code HandOutlineRenderer}.
 *
 * Все настройки читаются на лету в момент отрисовки, поэтому менять их можно прямо в меню,
 * не перелогиниваясь и не перезапуская игру.
 */
public class HandShaderModule extends Module {

	/** Стиль: плотный контур, мягкое свечение или оба слоя сразу. */
	public static final String STYLE_OUTLINE = "outline";
	public static final String STYLE_GLOW = "glow";
	public static final String STYLE_BOTH = "both";

	private final BooleanSetting outlineArm = bool("outline_arm", "Обводить руку", true);
	private final BooleanSetting outlineItem = bool("outline_item", "Обводить предмет", true);
	private final ModeSetting style = mode("style", "Стиль", STYLE_BOTH,
			ModeSetting.option(STYLE_OUTLINE, "Контур"),
			ModeSetting.option(STYLE_GLOW, "Свечение"),
			ModeSetting.option(STYLE_BOTH, "Контур + свечение"));
	private final IntSetting thickness = intSetting("thickness", "Толщина (px)", 3, 1, 12);
	private final IntSetting softness = intSetting("softness", "Мягкость (px)", 4, 0, 12);
	private final ColorSetting color = colorSetting("color", "Цвет", 0xFF8A6CFF);
	private final ColorSetting secondaryColor = colorSetting("secondary_color", "Второй цвет", 0xFF5CE1E6);
	private final BooleanSetting gradient = bool("gradient", "Градиент", true);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", false);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 5, 1, 20);
	private final IntSetting opacity = intSetting("opacity", "Плотность, %", 100, 10, 100);

	@SuppressWarnings("unused")
	private final ButtonSetting editor = buttonSetting("editor", "Раскладка рук", "Настроить", HandShaderModule::openEditor);

	public HandShaderModule() {
		super("hand_shader", "Обводка рук", "Цветной контур и свечение вокруг руки и предмета от первого лица",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_J);
	}

	/**
	 * Готовый набор параметров для одного прохода обводки.
	 *
	 * Важный момент: цвета считаются НЕ по анимации взмаха. Раньше градиент «ехал»
	 * вместе с замахом руки, и выглядело это как мигающий баг, а не как настройка.
	 * Теперь переход цвета разложен по кругу обводки — рендер сам решает, какой луч
	 * какого цвета, — а от времени зависит только фаза радуги.
	 *
	 * @param time время в миллисекундах — по нему двигается радуга
	 * @param arm  true — рисуем руку, false — предмет в ней (у них отдельные тумблеры)
	 * @return null, если для этой части обводка выключена
	 */
	public Spec spec(long time, boolean arm) {
		if (arm && !outlineArm.isEnabled() || !arm && !outlineItem.isEnabled()) {
			return null;
		}

		boolean ring = !style.is(STYLE_GLOW);
		boolean glow = !style.is(STYLE_OUTLINE);
		if (!ring && !glow) {
			return null;
		}

		float density = opacity.get() / 100.0f;
		int alpha = Math.round(255.0f * density);

		// Полный круг радуги проходится за 8 секунд на пятой скорости
		float period = 8000.0f / Math.max(1, rainbowSpeed.get());
		float phase = (time % (long) period) / period;

		return new Spec(
				RenderUtils.withAlpha(color.get(), density),
				RenderUtils.withAlpha(secondaryColor.get(), density),
				gradient.isEnabled(),
				rainbow.isEnabled(),
				phase,
				alpha,
				thickness.get(),
				glow ? softness.get() : 0,
				ring,
				glow);
	}

	/** Открывает редактор раскладки рук. */
	public static void openEditor() {
		Minecraft client = Minecraft.getInstance();
		if (client != null) {
			client.gui.setScreen(new HandEditorScreen(client.gui.screen()));
		}
	}
}

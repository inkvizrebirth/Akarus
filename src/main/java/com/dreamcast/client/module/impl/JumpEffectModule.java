package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Jump Effect — детализированная ударная волна при прыжке.
 *
 * В момент отрыва от земли под ногами рождается кольцо энергии: основная
 * волна с мерцанием по окружности, эхо-волна чуть меньше и мягкое свечение.
 * Рисует наш world-рендер (см. {@link com.dreamcast.client.render.WorldRenderHook}):
 * модуль только фиксирует моменты прыжков и хранит активные кольца.
 *
 * Срабатывание — именно прыжок (отрыв с восходящей скоростью), а не падение
 * с уступа: обычное схождение с края даёт нисходящую скорость и игнорируется.
 */
public class JumpEffectModule extends Module {

	/** Активное кольцо: точка рождения (по земле) и время старта. */
	public record JumpRing(double x, double y, double z, long bornMs, int seed) {
	}

	private final ColorSetting color = colorSetting("color", "Цвет", 0xFF45E3FF);
	private final ColorSetting secondColor = colorSetting("color2", "Второй цвет", 0xFF7C6CFF);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", false);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 3, 1, 10);

	private final IntSetting radius = intSetting("radius", "Радиус (0.1 блока)", 26, 8, 60);
	private final IntSetting duration = intSetting("duration", "Длительность (0.1 с)", 6, 3, 14);
	private final IntSetting intensity = intSetting("intensity", "Яркость (0.1)", 8, 2, 10);
	/** Детализация: количество сегментов окружности основной волны. */
	private final IntSetting detail = intSetting("detail", "Сегментов в кольце", 40, 16, 72);

	private final List<JumpRing> rings = new ArrayList<>();
	private boolean wasOnGround = true;

	public JumpEffectModule() {
		super("jump_effect", "Jump Effect", "Ударная волна под ногами при прыжке",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return true;
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			rings.clear();
			wasOnGround = true;
			return;
		}

		boolean onGround = player.onGround();
		// Прыжок: кадр назад стояли на земле, теперь нет, и скорость направлена вверх.
		// Спрыг с края (скорость вниз/нулевая) волной не отмечается
		if (wasOnGround && !onGround && player.getDeltaMovement().y > 0.06) {
			long now = Util.getMillis();
			rings.add(new JumpRing(
					player.getX(),
					player.getY() + 0.05,
					player.getZ(),
					now,
					(int) (Util.getNanos() & 0xFFFF)));
			// Ограничиваем список: даже частые прыжки не копят мусор
			while (rings.size() > 12) {
				rings.remove(0);
			}
		}
		wasOnGround = onGround;

		// Убираем отжившие кольца прямо в тике — рендеру достаётся чистый список.
		// Часы те же, что в рендере (Util.getMillis): System.currentTimeMillis
		// имеет другую точку отсчёта и ломает возраст колец
		long now = Util.getMillis();
		Iterator<JumpRing> it = rings.iterator();
		while (it.hasNext()) {
			if (now - it.next().bornMs() > durationMs() + 200L) {
				it.remove();
			}
		}
	}

	public boolean wantsRings() {
		return isEnabled() && !rings.isEmpty();
	}

	public List<JumpRing> rings() {
		return rings;
	}

	public float radiusBlocks() {
		return radius.get() * 0.1f;
	}

	public long durationMs() {
		return duration.get() * 100L;
	}

	public float intensityScale() {
		return intensity.get() * 0.1f;
	}

	public int segmentCount() {
		return detail.get();
	}

	/**
	 * Цвет точки кольца: t — доля окружности (0..1), сдвигает радугу/градиент
	 * вдоль волны, phase — прогресс жизни кольца.
	 */
	public int ringColor(float t, float phase, long timeMillis) {
		if (rainbow.isEnabled()) {
			float speed = rainbowSpeed.get() / 2000.0F;
			return RenderUtils.hsb(timeMillis * speed + t * 0.35F + phase * 0.12F, 0.75F, 1.0F, 0xFF);
		}
		return RenderUtils.mix(color.get(), secondColor.get(), t);
	}
}

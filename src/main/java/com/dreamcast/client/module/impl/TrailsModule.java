package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.DustParticleOptions;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

/**
 * Trails — светящийся след за игроком.
 *
 * Два источника «света», можно по отдельности или вместе:
 * <ul>
 *   <li><b>Линия</b> — лента в мире, повернутая к камере ребром: рисуется нашим
 *       world-рендером (см. {@link com.dreamcast.client.render.WorldRenderHook}),
 *       с мягким свечением вокруг и градиентом/радугой вдоль следа;</li>
 *   <li><b>Партиклы</b> — цветная пыль (dust), которую клиент спавнит позади
 *       игрока: выглядит как искры и живёт своей жизнью даже после выключения.</li>
 * </ul>
 *
 * Точки следа пишутся в кольцевой буфер по ходу игрока — с равным шагом
 * (а не каждый тик), поэтому при спринте и при медленной ходьбе след одинаково
 * плотный. При выходе из мира буфер очищается.
 */
public class TrailsModule extends Module {

	private static final float STEP = 0.25F;

	private final ModeSetting style = mode("style", "Стиль", "both",
			ModeSetting.option("line", "Линия"),
			ModeSetting.option("particles", "Партиклы"),
			ModeSetting.option("both", "Вместе"));

	private final ColorSetting color = colorSetting("color", "Цвет", 0xFF45E3FF);
	private final ColorSetting secondColor = colorSetting("color2", "Второй цвет", 0xFF7C6CFF);
	private final BooleanSetting gradient = bool("gradient", "Градиент", true);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", false);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 3, 1, 10);

	private final IntSetting width = intSetting("width", "Толщина линии", 3, 1, 12);
	private final IntSetting length = intSetting("length", "Длина, блоков", 10, 2, 32);
	private final IntSetting heightOffset = intSetting("height", "Высота (0.1 блока)", 6, 0, 20);

	private final IntSetting density = intSetting("density", "Партиклов/шаг", 2, 1, 6);
	private final IntSetting particleSize = intSetting("particle_size", "Размер партиклов", 2, 1, 8);
	private final BooleanSetting onlyMoving = bool("only_moving", "Только в движении", true);

	/** Точки следа (голова буфера — самая свежая). x, y, z. */
	private final Queue<float[]> points = new ArrayDeque<>();
	private float lastX = Float.NaN;
	private float lastY = Float.NaN;
	private float lastZ = Float.NaN;

	public TrailsModule() {
		super("trails", "Trails", "Светящийся след из партиклов за игроком",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			clearTrail();
			return;
		}

		float x = (float) player.getX();
		float y = (float) player.getY() + heightOffset.get() / 10.0F;
		float z = (float) player.getZ();

		if (onlyMoving.isEnabled() && player.getDeltaMovement().horizontalDistanceSqr() < 1.0e-4
				&& (player.onGround() || player.isInWater())) {
			// Стоим на месте — след не растёт (но и не исчезает мгновенно)
			return;
		}

		if (Float.isNaN(lastX)) {
			lastX = x;
			lastY = y;
			lastZ = z;
			return;
		}

		float dx = x - lastX;
		float dy = y - lastY;
		float dz = z - lastZ;
		float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		while (distance >= STEP) {
			float t = STEP / distance;
			lastX += dx * t;
			lastY += dy * t;
			lastZ += dz * t;

			points.add(new float[]{lastX, lastY, lastZ});
			if (wantsParticles()) {
				spawnDust(lastX, lastY, lastZ);
			}

			dx = x - lastX;
			dy = y - lastY;
			dz = z - lastZ;
			distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
		pruneTrail();
	}

	@Override
	protected void onDisable() {
		clearTrail();
	}

	private void clearTrail() {
		points.clear();
		lastX = Float.NaN;
		lastY = Float.NaN;
		lastZ = Float.NaN;
	}

	/** Держим в буфере не больше length блоков пути. */
	private void pruneTrail() {
		double limit = length.get();
		double total = 0.0;
		Iterator<float[]> iterator = points.iterator();
		float[] previous = null;
		while (iterator.hasNext()) {
			float[] point = iterator.next();
			if (previous != null) {
				total += Math.sqrt(
						(point[0] - previous[0]) * (point[0] - previous[0])
								+ (point[1] - previous[1]) * (point[1] - previous[1])
								+ (point[2] - previous[2]) * (point[2] - previous[2]));
			}
			previous = point;
		}
		// Хвост (самые старые) удаляем по одному, пока след не влезет в лимит
		while (total > limit && points.size() > 2) {
			float[] head = points.poll();
			float[] next = points.peek();
			if (next != null) {
				total -= Math.sqrt(
						(next[0] - head[0]) * (next[0] - head[0])
								+ (next[1] - head[1]) * (next[1] - head[1])
								+ (next[2] - head[2]) * (next[2] - head[2]));
			}
		}
	}

	private boolean wantsParticles() {
		return style.is("particles") || style.is("both");
	}

	/** Нужно ли рисовать ленту (вызывается рендером каждый кадр). */
	public boolean wantsLine() {
		return isEnabled() && (style.is("line") || style.is("both")) && points.size() >= 2;
	}

	private void spawnDust(float x, float y, float z) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		for (int i = 0; i < density.get(); i++) {
			int dustColor = trailColor(0.5f) | 0xFF000000;
			client.level.addParticle(
					new DustParticleOptions(dustColor, particleSize.get() / 4.0F),
					x + (client.level.getRandom().nextDouble() - 0.5) * 0.3,
					y + (client.level.getRandom().nextDouble() - 0.5) * 0.3,
					z + (client.level.getRandom().nextDouble() - 0.5) * 0.3,
					0.0, 0.02, 0.0);
		}
	}

	/**
	 * Цвет следа в точке t (0 — у игрока, 1 — в хвосте) с учётом настроек.
	 * time — текущее время для радуги.
	 */
	public int trailColor(float t) {
		return trailColor(t, System.currentTimeMillis());
	}

	public int trailColor(float t, long timeMillis) {
		if (rainbow.isEnabled()) {
			float speed = rainbowSpeed.get() / 2000.0F;
			return RenderUtils.hsb(timeMillis * speed + t * 0.45F, 0.75F, 1.0F, 0xFF);
		}
		if (gradient.isEnabled()) {
			return RenderUtils.mix(color.get(), secondColor.get(), t);
		}
		return color.get();
	}

	public int lineWidth() {
		return width.get();
	}

	/** Точки следа для рендера (свежие — в конце очереди). */
	public Queue<float[]> trailPoints() {
		return points;
	}

	/** Точка «прямо сейчас» — интерполированная позиция головы следа. */
	public float[] headPoint(float partialTick) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		return new float[]{
				(float) player.getX(partialTick),
				(float) player.getY(partialTick) + heightOffset.get() / 10.0F,
				(float) player.getZ(partialTick)
		};
	}
}

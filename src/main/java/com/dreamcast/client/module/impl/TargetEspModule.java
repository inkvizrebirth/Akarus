package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Target ESP — объёмная подсветка ТЕКУЩЕЙ ЦЕЛИ ауры вокруг неё.
 *
 * <p>Цель берётся у {@link KillAuraModule} (а если аура выключена — у того, на кого
 * наведён прицел), поэтому ESP показывает ровно то, что аура собирается бить.
 * Это главное отличие от обычного ESP: не «всех видно», а «видно выбор».</p>
 *
 * <p>Шесть стилей: {@code marker} (вращающаяся рамка в плоскости экрана),
 * {@code circle} (кольцо + «юбка» лучей вниз), {@code ghosts} (рой мягких
 * светящихся точек), {@code orbs} (три орба с хвостами по вольту),
 * {@code comets} (три кометы по орбите с длинным хвостом) и {@code crystals}
 * (орбитальные кристаллы-октаэдры со свечением).</p>
 *
 * <p>Всё состояние считается в {@link #tick()} и наружу отдаётся неизменяемым
 * снапшотом {@link Frame}: в 26.2 извлечение кадра и отрисовка могут быть на
 * разных потоках, поэтому живой список с историей в рендер отдавать нельзя.
 * Плавность между тиками даёт пара prev/cur + partialTick — тот же приём, что у
 * интерполяции позиций сущностей.</p>
 *
 * <p>Эффект переживает потерю цели: гаснет по анимации (in/out/none) и
 * {@code duration}, а позиция держится последней ({@code freezeLast}). Без этого
 * ESP мигал бы на каждом ретаргете и обрывал хвосты.</p>
 */
public class TargetEspModule extends Module {

	public static final String STYLE_MARKER = "marker";
	public static final String STYLE_CIRCLE = "circle";
	public static final String STYLE_GHOSTS = "ghosts";
	public static final String STYLE_ORBS = "orbs";
	public static final String STYLE_COMETS = "comets";
	public static final String STYLE_CRYSTALS = "crystals";

	/**
	 * Один элемент орбиты. {@code prev*} — положение прошлым тиком, из них рендер
	 * интерполирует текущий кадр.
	 */
	public record Element(float x, float y, float z, float prevX, float prevY, float prevZ,
	                       float spinY, float spinZ, float scale, float hue) {
	}

	/** Хвост элемента: мировые точки, от свежей к старой. */
	public record Trail(List<float[]> points) {
	}

	/**
	 * Кадр эффекта для world-рендера: стиль, анимации, центр, габариты цели,
	 * фаза кольца/рамки и элементы с хвостами.
	 */
	public record Frame(String style, float show, float prevShow,
	                     float sizeFactor, float prevSizeFactor,
	                     float centerX, float centerY, float centerZ,
	                     float prevCenterX, float prevCenterY, float prevCenterZ,
	                     float width, float height,
	                     float ringPhase, float prevRingPhase,
	                     float frameSpin, float prevFrameSpin,
	                     float orbitRadius, List<Element> elements, List<Trail> trails) {
	}

	private final ModeSetting style = mode("style", "Стиль", STYLE_ORBS,
			ModeSetting.option("orbs", "Орбы"),
			ModeSetting.option("comets", "Кометы"),
			ModeSetting.option("crystals", "Кристаллы"),
			ModeSetting.option("ghosts", "Рой точек"),
			ModeSetting.option("circle", "Кольцо"),
			ModeSetting.option("marker", "Маркер"));
	private final ModeSetting animation = mode("animation", "Анимация", "in",
			ModeSetting.option("in", "Расти"),
			ModeSetting.option("out", "Угасать"),
			ModeSetting.option("none", "Нет"));
	private final IntSetting duration = intSetting("duration", "Длительность (0.05 с)", 6, 1, 20);
	private final IntSetting count = intSetting("count", "Элементов", 12, 1, 36);
	private final IntSetting trailLength = intSetting("trail", "Длина хвоста", 18, 0, 40);
	private final IntSetting speed = intSetting("speed", "Скорость (%)", 100, 0, 500);
	private final IntSetting size = intSetting("size", "Размер (%)", 100, 40, 220);
	private final IntSetting orbitRadius = intSetting("radius", "Радиус орбиты (0.1 блока)", 9, 2, 30);
	private final IntSetting opacity = intSetting("alpha", "Плотность (%)", 100, 20, 100);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 4, 1, 10);
	private final ColorSetting color = colorSetting("color", "Цвет", 0xFFFF4FA0);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", true);
	private final BooleanSetting freezeLast = bool("freeze_last", "Держать последнюю позицию", true);
	private final BooleanSetting healthTint = bool("health_tint", "Цвет по здоровью цели", false);

	private volatile Frame frame;

	/** Живое состояние — только из тика. */
	private Entity target;
	private final List<ElementState> elements = new ArrayList<>();
	/** Сид берётся от id цели: набор кристаллов стабилен на цель, но разный на разных. */
	private RandomSource random = RandomSource.create(0x5EEDL);
	private float show;
	private float prevShow;
	private float sizeFactor = 1.0F;
	private float prevSizeFactor = 1.0F;
	private float centerX;
	private float centerY;
	private float centerZ;
	private float prevCenterX;
	private float prevCenterY;
	private float prevCenterZ;
	private double lastX = Double.NaN;
	private double lastY;
	private double lastZ;
	private float width = 0.6F;
	private float height = 1.8F;
	private float ringPhase;
	private float prevRingPhase;
	private float frameSpin;
	private float prevFrameSpin;
	private float frameDir = 1.0F;
	private long lastTickMs;

	public TargetEspModule() {
		super("target_esp", "Target ESP",
				"Объёмная подсветка цели ауры: орбы, кометы, кристаллы, рой, кольцо или маркер",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void onDisable() {
		frame = null;
		elements.clear();
		target = null;
		show = 0.0F;
		prevShow = 0.0F;
		lastX = Double.NaN;
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.level == null || client.player == null) {
			onDisable();
			return;
		}

		prevShow = show;
		prevSizeFactor = sizeFactor;
		prevCenterX = centerX;
		prevCenterY = centerY;
		prevCenterZ = centerZ;
		prevRingPhase = ringPhase;
		prevFrameSpin = frameSpin;

		Entity next = resolveTarget(client);
		if (next != null && next != target) {
			target = next;
			random = RandomSource.create(next.getId() * 0x9E3779B97F4A7C15L);
			rebuildElements();
		}
		boolean active = target != null && target.isAlive() && !target.isRemoved();
		if (!active && !freezeLast.isEnabled()) {
			target = null;
		}

		if (active) {
			lastX = target.getX();
			lastY = target.getY() + target.getBbHeight() * 0.5F;
			lastZ = target.getZ();
			width = Math.max(0.35F, target.getBbWidth());
			height = Math.max(0.5F, target.getBbHeight());
		}
		if (Double.isNaN(lastX)) {
			show = 0.0F;
			frame = null;
			return;
		}
		centerX = (float) lastX;
		centerY = (float) lastY;
		centerZ = (float) lastZ;

		long now = Util.getMillis();
		float delta = Math.min(250.0F, Math.max(0.0F, now - lastTickMs));
		lastTickMs = now;

		// Подгон show к 1 или к 0 за duration; шаг нормирован на 20 tps, поэтому
		// на 300 fps анимация идёт столько же по времени, сколько на 30
		float step = delta / Math.max(1.0F, duration.get() * 50.0F);
		show = active ? Math.min(1.0F, show + step) : Math.max(0.0F, show - step);
		float wantedSize = switch (animation.getValue()) {
			case "in" -> active ? 1.0F : 0.0F;
			case "out" -> active ? 0.0F : 2.0F;
			default -> 1.0F;
		};
		sizeFactor = Mth.clamp(sizeFactor + (wantedSize - sizeFactor) * Math.min(1.0F, step), 0.0F, 2.0F);

		float speedScale = Mth.clamp(speed.get() / 100.0F, 0.0F, 5.0F);
		ringPhase += delta * 0.0025F * speedScale;
		frameDir = frameSpin > 25.0F ? -1.0F : (frameSpin < -25.0F ? 1.0F : frameDir);
		frameSpin += 0.5F * frameDir * show * speedScale;

		updateElements(active, delta, speedScale);

		if (show <= 0.001F && !active) {
			frame = null;
			target = null;
			elements.clear();
			return;
		}
		publish();
	}

	/** Цель: сначала у ауры, иначе — то, на что наведён прицел. */
	private static Entity resolveTarget(Minecraft client) {
		KillAuraModule aura = ModuleManager.find(KillAuraModule.class);
		if (aura != null && aura.isEnabled()) {
			Entity auraTarget = aura.currentTarget();
			if (auraTarget != null && auraTarget.isAlive()) {
				return auraTarget;
			}
		}
		Entity picked = client.crosshairPickEntity;
		return picked instanceof LivingEntity living && living.isAlive() ? living : null;
	}

	private int elementCount() {
		String current = style.getValue();
		return switch (current) {
			case STYLE_CRYSTALS -> count.get();
			case STYLE_GHOSTS -> Math.max(3, count.get() / 2);
			case STYLE_COMETS, STYLE_ORBS -> 3;
			default -> 0;
		};
	}

	/** Пересобираем элементы орбиты под текущий стиль. */
	private void rebuildElements() {
		elements.clear();
		int n = elementCount();
		String current = style.getValue();
		for (int i = 0; i < n; i++) {
			ElementState state = new ElementState();
			state.angle = (float) (i / (double) Math.max(1, n) * Math.PI * 2.0);
			state.hue = i / (float) Math.max(1, n);
			state.yOffset = 0.15F + Math.max(0.1F, height - 0.3F) * (i % 3) / 2.0F;
			state.scale = current.equals(STYLE_CRYSTALS) ? 0.75F + random.nextFloat() * 0.35F : 1.0F;
			state.spinY = random.nextFloat() * 360.0F;
			state.spinZ = random.nextFloat() * 360.0F;
			state.radiusJitter = (random.nextFloat() - 0.5F) * 0.18F;
			elements.add(state);
		}
	}

	private void updateElements(boolean active, float delta, float speedScale) {
		String current = style.getValue();
		boolean orbits = !current.equals(STYLE_CRYSTALS);
		float spin = (current.equals(STYLE_CRYSTALS) ? 0.02F : 0.06F) * speedScale;
		float deltaTicks = Math.max(1.0F, delta / 50.0F);
		float orbit = Math.max(0.2F, orbitRadius.get() * 0.1F);
		int maxTrail = trailLength.get();

		for (int index = 0; index < elements.size(); index++) {
			ElementState e = elements.get(index);
			e.prevX = e.x;
			e.prevY = e.y;
			e.prevZ = e.z;
			e.angle += spin * (current.equals(STYLE_COMETS) ? 0.26F : 1.0F) * deltaTicks;
			e.spinY += deltaTicks * 1.5F * speedScale;
			e.spinZ += deltaTicks * 0.7F * speedScale;

			float radius = orbit + e.radiusJitter;
			float floatY = Mth.sin(e.angle * 2.0F) * Math.min(0.4F, height * 0.2F);
			e.x = centerX + Mth.cos(e.angle) * radius;
			e.y = centerY - height * 0.5F + e.yOffset + floatY;
			e.z = centerZ + Mth.sin(e.angle) * radius;
			if (current.equals(STYLE_COMETS) && (index == 0 || index == 2)) {
				// крайние кометы идут «восьмёркой»: одна выше, другая ниже и
				// с обратным знаком по X — иначе три кометы сливаются в одну дугу
				float wave = Mth.sin(e.angle) * height * 0.4F;
				e.y = centerY + (index == 0 ? wave : -wave);
				if (index == 2) {
					e.x = centerX - Mth.cos(e.angle) * radius;
				}
			}

			if (!orbits) {
				e.history.clear();
				continue;
			}
			if (active && maxTrail > 0) {
				e.history.addFirst(new float[]{e.x, e.y, e.z});
				while (e.history.size() > maxTrail) {
					e.history.removeLast();
				}
			}
		}
	}

	private void publish() {
		String current = style.getValue();
		List<Element> snapshot = new ArrayList<>(elements.size());
		List<Trail> trails = new ArrayList<>(elements.size());
		for (ElementState e : elements) {
			snapshot.add(new Element(e.x, e.y, e.z, e.prevX, e.prevY, e.prevZ,
					e.spinY, e.spinZ, e.scale, e.hue));
			List<float[]> history = new ArrayList<>(e.history.size());
			for (float[] point : e.history) {
				history.add(point.clone());
			}
			trails.add(new Trail(List.copyOf(history)));
		}
		frame = new Frame(current, show, prevShow, sizeFactor, prevSizeFactor,
				centerX, centerY, centerZ, prevCenterX, prevCenterY, prevCenterZ,
				width, height, ringPhase, prevRingPhase, frameSpin, prevFrameSpin,
				orbitRadius.get() * 0.1F, List.copyOf(snapshot), List.copyOf(trails));
	}

	/** Есть ли что рисовать на этом кадре. */
	public boolean wantsEffect() {
		return isEnabled() && frame != null;
	}

	public Frame frame() {
		return frame;
	}

	public float opacityScale() {
		return opacity.get() / 100.0F;
	}

	/** Размер элемента (настройки × анимация), множителем к мировому размеру. */
	public float elementScale() {
		return size.get() / 100.0F;
	}

	/**
	 * Цвет элемента: радуга с фазой {@code t} или выбранный цвет; «цвет по
	 * здоровью» перекрывает радугу — это про информацию, а не про красоту.
	 */
	public int effectColor(float t, long nowMs) {
		if (healthTint.isEnabled()) {
			int hpColor = healthColor();
			if (hpColor != 0) {
				return hpColor;
			}
		}
		if (rainbow.isEnabled()) {
			return RenderUtils.hsb(RenderUtils.rainbowPhase(nowMs, rainbowSpeed.get()) + t * 0.4F,
					0.78F, 1.0F, 0xFF);
		}
		return color.get();
	}

	private int healthColor() {
		Entity entity = target;
		if (!(entity instanceof LivingEntity living) || living.getMaxHealth() <= 0.0F) {
			return 0;
		}
		float health = Mth.clamp(living.getHealth() / living.getMaxHealth(), 0.0F, 1.0F);
		return RenderUtils.mix(0xFFFF5C5C, 0xFF7BE08A, health);
	}

	/** Живое состояние одного элемента орбиты. */
	private static final class ElementState {
		float angle;
		float hue;
		float yOffset;
		float scale;
		float spinY;
		float spinZ;
		float radiusJitter;
		float x;
		float y;
		float z;
		float prevX;
		float prevY;
		float prevZ;
		final Deque<float[]> history = new ArrayDeque<>();
	}
}

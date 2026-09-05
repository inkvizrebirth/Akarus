package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.render.CosmosRenderer;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Космос на игроках + эффекты смерти.
 *
 * <p>Мод не подменяет скин и не трогает модель игрока — на 26.2 для этого у
 * мода нет ни входа, прав: вместо этого {@link CosmosRenderer} оборачивает
 * силуэт цели процедурным полем «глубокого космоса» (газ по value-noise,
 * звёзды по хэшу клетки), которое течёт вместе со временем. Со стороны это
 * читается как живая текстура на человеке, но не зависит ни от скинов, ни от
 * чужих плагинов рендера.</p>
 *
 * <p>Эффекты смерти ловятся по здоровью: пока тело в зоне видимости, момент
 * {@code health -> 0} надёжен. Отдельный выключатель «при исчезновении» ловит и
 * те смерти, где тело успело уйти из списка отрисовки, — зато он может
 * сработать и на выход за дальность прорисовки, поэтому по умолчанию выключен.</p>
 */
public class CosmosModule extends Module {

	/** Одна цель на кадр: центр основания бокса, размеры, здоровье, идентификатор. */
	public record Target(int id, double x, double y, double z, double width, double height, float health) {
	}

	private final IntSetting detail = intSetting("detail", "Плотность текстуры", 7, 3, 11);
	private final IntSetting pad = intSetting("pad", "Отступ космоса, %", 18, 0, 60);
	private final IntSetting flow = intSetting("flow", "Скорость потока", 5, 0, 20);
	private final IntSetting coverage = intSetting("coverage", "Плотность покрытия, %", 66, 10, 100);
	private final IntSetting stars = intSetting("stars", "Звёзды, %", 45, 0, 100);
	private final IntSetting reach = intSetting("reach", "Дальность, блоков", 48, 8, 128);
	private final ColorSetting firstColor = colorSetting("color", "Первый цвет", 0xFF3B1E7A);
	private final ColorSetting secondColor = colorSetting("color2", "Второй цвет", 0xFF1E6FA8);
	private final BooleanSetting mobs = bool("all_mobs", "Не только игроки", false);
	private final BooleanSetting aura = bool("aura", "Ореол вокруг тела", true);
	private final IntSetting deathEffect = intSetting("death", "Эффект смерти", 0, 0, 3);
	private final BooleanSetting deathSounds = bool("death_sounds", "Звук эффекта", true);
	private final BooleanSetting onVanish = bool("on_vanish", "Эффект при исчезновении", false);

	/** Что увидит рендер этого кадра. */
	private volatile List<Target> targets = List.of();
	/** id -> {здоровье, x, y, z}: по нему и ловим смерть. */
	private final Map<Integer, float[]> seen = new HashMap<>();
	private final Map<Integer, float[]> nextSeen = new HashMap<>();
	/** Уже сработавшие id: пока цель мертва, эффект не повторяем каждый кадр. */
	private final Map<Integer, Long> triggered = new HashMap<>();

	public CosmosModule() {
		super("cosmos", "Cosmos", "Анимированный космос на игроках и эффекты смерти",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
		addSetting(detail);
		addSetting(pad);
		addSetting(flow);
		addSetting(coverage);
		addSetting(stars);
		addSetting(reach);
		addSetting(firstColor);
		addSetting(secondColor);
		addSetting(mobs);
		addSetting(aura);
		addSetting(deathEffect);
		addSetting(deathSounds);
		addSetting(onVanish);
	}

	@Override
	protected void onDisable() {
		targets = List.of();
		seen.clear();
		nextSeen.clear();
		triggered.clear();
		CosmosRenderer.reset();
	}

	/** Собирает цели и заводит эффекты смерти — вызывается на этапе извлечения кадра. */
	public void collect(Iterable<Entity> entities, double camX, double camY, double camZ,
	                    float partialTick, long now) {
		double maxSqr = (double) reach.get() * reach.get();
		List<Target> list = new ArrayList<>();
		nextSeen.clear();
		for (Entity entity : entities) {
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}
			if (!mobs.isEnabled() && !(entity instanceof Player)) {
				continue;
			}
			// Интерполяция по partialTick: без неё обёртка дёргается 20 раз в секунду,
			// а «текстура» на бегущем игроке начинает жить своей жизнью.
			double x = entity.getX(partialTick);
			double y = entity.getY(partialTick);
			double z = entity.getZ(partialTick);
			double dx = x - camX;
			double dy = y - camY;
			double dz = z - camZ;
			double distanceSqr = dx * dx + dy * dy + dz * dz;
			if (distanceSqr > maxSqr) {
				continue;
			}
			float health = living.getHealth();
			int id = entity.getId();
			nextSeen.put(id, new float[]{health, (float) x, (float) y, (float) z});
			float[] previous = seen.get(id);
			if (health <= 0.0F) {
				// Эффект — только на самом переходе «жил -> умер»: труп висит в
				// списке несколько тиков, и без этого условия он бы сыпался каждый кадр.
				boolean justDied = previous != null && previous[0] > 0.0F;
				Long firedAt = triggered.get(id);
				if (justDied && (firedAt == null || now - firedAt > 4000L)) {
					triggered.put(id, now);
					CosmosRenderer.spawn(deathEffectFor(id), previous[1], previous[2], previous[3],
							id, deathSounds.isEnabled(), now);
				}
				continue; // мёртвому космос на теле не нужен
			}
			triggered.remove(id); // возродился — можно снова ловить смерть
			list.add(new Target(id, x, y, z, entity.getBbWidth() * 1.35, entity.getBbHeight(), health));
		}
		// Тело исчезло из списка отрисовки, а было живым: либо умерло, либо ушло
		// за дальность — поэтому режим выключен по умолчанию.
		if (onVanish.isEnabled()) {
			for (Map.Entry<Integer, float[]> entry : seen.entrySet()) {
				float[] was = entry.getValue();
				if (was[0] > 0.0F && !nextSeen.containsKey(entry.getKey())) {
					CosmosRenderer.spawn(deathEffectFor(entry.getKey()), was[1], was[2], was[3],
							entry.getKey(), deathSounds.isEnabled(), now);
				}
			}
		}
		seen.clear();
		seen.putAll(nextSeen);
		targets = List.copyOf(list);
	}

	/** 0 — наугад по идентификатору цели, дальше — молния / ангел / растворение. */
	private int deathEffectFor(int id) {
		int chosen = deathEffect.get();
		return chosen == 0 ? Math.floorMod(id, 3) : chosen - 1;
	}

	public List<Target> targets() {
		return targets;
	}

	public int detail() {
		return detail.get();
	}

	public int pad() {
		return pad.get();
	}

	public float flowSpeed() {
		return flow.get();
	}

	public int coverage() {
		return coverage.get();
	}

	public int starDensity() {
		return stars.get();
	}

	public int reach() {
		return reach.get();
	}

	public int firstColor() {
		return firstColor.get();
	}

	public int secondColor() {
		return secondColor.get();
	}

	public boolean wantsAura() {
		return aura.isEnabled();
	}

	public boolean wantsCosmos() {
		return isEnabled() && coverage.get() > 0;
	}
}

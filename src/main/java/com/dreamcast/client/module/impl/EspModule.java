package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.RenderUtils;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * ESP — подсветка сущностей.
 *
 * <ul>
 *   <li><b>Glow</b> — ванильное свечение-обводка: мы лишь говорим игре «этот
 *       объект светится» и подменяем цвет обводки. Работает через стены всегда
 *       и стоит ноль кадров, пока целей рядом нет;</li>
 *   <li><b>Box</b> — светящийся 3D-бокс по габаритам сущности, рисуется нашим
 *       world-рендером. Есть режим «только углы» и градиент по высоте.</li>
 * </ul>
 *
 * Отбор целей и цвета считает сам модуль (быстрые проверки без обращений к
 * настройкам в горячем цикле), а миксины и рендер только спрашивают его.
 */
public class EspModule extends Module {

	private final ModeSetting mode = mode("mode", "Режим", "both",
			ModeSetting.option("glow", "Glow"),
			ModeSetting.option("box", "Box"),
			ModeSetting.option("both", "Вместе"),
			ModeSetting.option("target", "TargetESP"));

	private final BooleanSetting players = bool("players", "Игроки", true);
	private final BooleanSetting monsters = bool("monsters", "Мобы", true);
	private final BooleanSetting creatures = bool("creatures", "Животные", false);
	private final BooleanSetting items = bool("items", "Предметы", false);

	private final ColorSetting color = colorSetting("color", "Цвет", 0xFF45E3FF);
	private final ColorSetting secondColor = colorSetting("color2", "Второй цвет", 0xFF7C6CFF);
	private final BooleanSetting gradient = bool("gradient", "Градиент по высоте", true);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", false);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 3, 1, 10);

	private final IntSetting distance = intSetting("distance", "Радиус, блоков", 64, 8, 256);
	private final IntSetting boxWidth = intSetting("box_width", "Толщина линий", 2, 1, 10);
	private final BooleanSetting cornersOnly = bool("corners", "Только углы", false);

	public EspModule() {
		super("esp", "ESP", "Подсветка сущностей: свечение и боксы",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	// ------------------------------------------------------------------
	// Glow (вызывается из миксинов на игре)
	// ------------------------------------------------------------------

	/** Просит ли игра нарисовать обводку вокруг этой сущности. */
	public static boolean wantsGlow(Entity entity) {
		EspModule module = com.dreamcast.client.module.ModuleManager.find(EspModule.class);
		return module != null && module.glowFor(entity);
	}

	private boolean glowFor(Entity entity) {
		if (!isEnabled() || mode.is("box")) {
			return false;
		}
		if (mode.is("target")) {
			return entity.equals(resolveTarget());
		}
		return isTarget(entity);
	}

	/** Текущая цель для TargetESP: цель KillAura, иначе то, что под прицелом. */
	private Entity resolveTarget() {
		com.dreamcast.client.module.impl.KillAuraModule killAura =
				com.dreamcast.client.module.ModuleManager.find(com.dreamcast.client.module.impl.KillAuraModule.class);
		if (killAura != null && killAura.isEnabled() && killAura.currentTarget() != null) {
			return killAura.currentTarget();
		}
		Minecraft client = Minecraft.getInstance();
		return client != null ? client.crosshairPickEntity : null;
	}

	/** Цель для world-рендера (в режиме TargetESP). Может быть null. */
	public Entity targetForRender() {
		if (!isEnabled() || !mode.is("target")) {
			return null;
		}
		Entity target = resolveTarget();
		if (target == null || !target.isAlive()) {
			return null;
		}
		return target;
	}

	/** Доля здоровья цели 0..1 (для полоски). */
	public static float healthFraction(Entity entity) {
		if (entity instanceof LivingEntity living) {
			return Math.max(0.0f, Math.min(1.0f, living.getHealth() / Math.max(1.0f, living.getMaxHealth())));
		}
		return 1.0f;
	}

	/** Цвет обводки для сущности (миксин getTeamColor). 0 — не подменять. */
	public static int glowColor(Entity entity) {
		EspModule module = com.dreamcast.client.module.ModuleManager.find(EspModule.class);
		if (module == null || !module.glowFor(entity)) {
			return 0;
		}
		return module.entityColorById(entity.getId());
	}

	// ------------------------------------------------------------------
	// Box (данные для world-рендера)
	// ------------------------------------------------------------------

	/** Один бокс: координаты AABB + id сущности (для сдвига радуги). */
	public record EspBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int entityId) {
	}

	public boolean wantsBoxes() {
		return isEnabled() && !mode.is("glow");
	}

	/** Считает цели как обычно, но в режиме TargetESP — только сама цель. */
	public boolean isRenderTarget(Entity entity) {
		if (mode.is("target")) {
			return entity.equals(resolveTarget());
		}
		return isTarget(entity);
	}

	/** Собирает боксы целей — вызывается на этапе извлечения кадра. */
	public List<EspBox> collectBoxes(Iterable<Entity> entities, double camX, double camY, double camZ) {
		double maxSqr = (double) distance.get() * distance.get();
		List<EspBox> result = new ArrayList<>();
		for (Entity entity : entities) {
			if (!isRenderTarget(entity)) {
				continue;
			}
			AABB box = entity.getBoundingBox();
			Vec3 center = box.getCenter();
			double dx = center.x - camX;
			double dy = center.y - camY;
			double dz = center.z - camZ;
			if (dx * dx + dy * dy + dz * dz > maxSqr) {
				continue;
			}
			result.add(new EspBox(
					(float) box.minX, (float) box.minY, (float) box.minZ,
					(float) box.maxX, (float) box.maxY, (float) box.maxZ,
					entity.getId()));
		}
		return result;
	}

	public int boxWidth() {
		return boxWidth.get();
	}

	public boolean cornersOnly() {
		return cornersOnly.isEnabled();
	}

	/** Цвет линии бокса на высоте y (для градиента по высоте). */
	public int boxColor(int entityId, double y, double minY, double maxY) {
		int base = entityColorById(entityId);
		if (gradient.isEnabled()) {
			return WorldGeometry_verticalColor(base, secondColor.get(), y, minY, maxY);
		}
		return base;
	}

	// ------------------------------------------------------------------
	// Общее
	// ------------------------------------------------------------------

	private boolean isTarget(Entity entity) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || entity == client.player) {
			return false;
		}
		if (entity.isSpectator() || !entity.isAlive()) {
			return false;
		}
		if (entity instanceof Player) {
			return players.isEnabled();
		}
		if (entity instanceof Monster) {
			return monsters.isEnabled();
		}
		if (entity instanceof ItemEntity) {
			return items.isEnabled();
		}
		if (entity instanceof LivingEntity) {
			return creatures.isEnabled();
		}
		return false;
	}

	private int entityColorById(int entityId) {
		if (rainbow.isEnabled()) {
			float speed = rainbowSpeed.get() / 2000.0F;
			float offset = (entityId % 16) / 16.0F;
			return RenderUtils.hsb(System.currentTimeMillis() * speed + offset, 0.75F, 1.0F, 0xFF);
		}
		return color.get();
	}

	private static int WorldGeometry_verticalColor(int top, int bottom, double y, double minY, double maxY) {
		double span = maxY - minY;
		float t = span <= 1.0e-4 ? 0.0f : (float) ((y - minY) / span);
		return RenderUtils.mix(top, bottom, t);
	}
}

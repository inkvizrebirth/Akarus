package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.ModeSetting;
import com.akarus.client.util.RotationHumanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import java.util.UUID;

/**
 * KillAura — автоматическая атака целей вокруг игрока.
 *
 * Два режима:
 * <ul>
 *   <li><b>Быстрый</b> — доворот мгновенный и точный, удар сразу, как только
 *       восстановилась сила удара. Максимум эффективности, никакого камуфляжа;</li>
 *   <li><b>Легитный</b> — повороты идут через {@link RotationHumanizer}: камера
 *       доворачивается к цели плавно, с промахом и переменной скоростью, удар — только
 *       когда прицел уже наведён, и с человеческой паузой между целями и ударами.</li>
 * </ul>
 *
 * Выбор цели — настроенный приоритет: ближайший, слабейший или тот, что ближе всего
 * к прицелу. Можно отдельно разрешить игроков и враждебных мобов, решение о невидимых
 * и стрельбе через стены — тоже за вами.
 */
public class KillAuraModule extends Module {

	public static final String MODE_FAST = "fast";
	public static final String MODE_LEGIT = "legit";

	public static final String PRIORITY_NEAREST = "nearest";
	public static final String PRIORITY_HEALTH = "health";
	public static final String PRIORITY_ANGLE = "angle";

	private final ModeSetting mode = mode("mode", "Режим", MODE_FAST,
			ModeSetting.option(MODE_FAST, "Быстрый"),
			ModeSetting.option(MODE_LEGIT, "Легитный"));

	private final IntSetting range = intSetting("range", "Дальность, блоков", 4, 1, 6);

	private final ModeSetting priority = mode("priority", "Приоритет цели", PRIORITY_NEAREST,
			ModeSetting.option(PRIORITY_NEAREST, "Ближний"),
			ModeSetting.option(PRIORITY_HEALTH, "Слабый (мало HP)"),
			ModeSetting.option(PRIORITY_ANGLE, "Под прицелом"));

	private final BooleanSetting attackPlayers = bool("players", "Игроки", true);
	private final BooleanSetting attackMobs = bool("mobs", "Враждебные мобы", true);
	private final BooleanSetting attackInvisible = bool("invisible", "Невидимые", false);
	private final BooleanSetting throughWalls = bool("walls", "Через стены", false);

	private static final Random RANDOM = new Random();

	/** До скольки градусов считаем, что прицел наведён (легитный режим). */
	private static final float AIM_TOLERANCE = 6.0F;

	private UUID targetId;
	private int attackDelay;

	public KillAuraModule() {
		super("kill_aura", "KillAura", "Автоматическая атака: Быстрый — мгновенные довороты, Легитный — повороты и удары как у человека",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_X);
	}

	@Override
	protected void onEnable() {
		this.targetId = null;
		this.attackDelay = 3;
	}

	@Override
	protected void onDisable() {
		this.targetId = null;
	}

	public boolean isLegit() {
		return mode.is(MODE_LEGIT);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			return;
		}

		// В меню, чате и во время использования предметов (еда, лук) не воюем
		if ((client.gui != null && client.gui.screen() != null) || player.isUsingItem()) {
			return;
		}

		Entity target = selectTarget(client, player);
		if (target == null) {
			this.targetId = null;
			return;
		}

		// Смена цели — человеческая пауза на «перевести взгляд»
		UUID id = target.getUUID();
		if (!id.equals(this.targetId)) {
			this.targetId = id;
			this.attackDelay = Math.max(this.attackDelay, isLegit() ? 4 + RANDOM.nextInt(7) : 2);
		}

		float[] aim = aimAt(player, target);
		if (isLegit()) {
			// Доворот через RotationHumanizer: плавно, с промахом и переменной скоростью.
			// Пишем публичными setYRot/setXRot — они не перехватываются миксином
			float[] rotation = RotationHumanizer.aimTowards(player, aim[0], aim[1]);
			if (rotation != null) {
				player.setYRot(rotation[0]);
				player.setXRot(rotation[1]);
			} else {
				player.setYRot(aim[0]);
				player.setXRot(aim[1]);
			}

			// Бьём только наведённым прицелом: удар мимо взгляда — читерский почерк
			if (!RotationHumanizer.arrived()
					|| Math.abs(Mth.wrapDegrees(player.getYRot() - aim[0])) > AIM_TOLERANCE
					|| Math.abs(player.getXRot() - aim[1]) > AIM_TOLERANCE) {
				return;
			}
		} else {
			player.setYRot(aim[0]);
			player.setXRot(aim[1]);
		}

		if (this.attackDelay > 0) {
			this.attackDelay--;
			return;
		}

		// Ждём восстановления силы удара: спам ослабленными ударами — потеря dps
		// и лишний повод для античита
		if (player.getAttackStrengthScale(0.0F) < 0.93F) {
			return;
		}

		if (client.gameMode != null) {
			client.gameMode.attack(player, target);
		}
		player.swing(InteractionHand.MAIN_HAND);

		this.attackDelay = isLegit() ? 2 + RANDOM.nextInt(4) : 0;
	}

	// ------------------------------------------------------------------
	// Выбор цели
	// ------------------------------------------------------------------

	/** Ищет лучшую цель по выбранному приоритету. */
	private Entity selectTarget(Minecraft client, LocalPlayer player) {
		Entity best = null;
		double bestScore = Double.MAX_VALUE;

		for (Entity entity : client.level.entitiesForRendering()) {
			if (!isValidTarget(player, entity)) {
				continue;
			}

			double score;
			if (priority.is(PRIORITY_NEAREST)) {
				score = player.distanceToSqr(entity);
			} else if (priority.is(PRIORITY_HEALTH)) {
				score = entity instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE;
			} else {
				score = angleTo(player, entity);
			}

			if (score < bestScore) {
				bestScore = score;
				best = entity;
			}
		}

		return best;
	}

	/** Подходит ли сущность под роль цели. */
	private boolean isValidTarget(LocalPlayer player, Entity entity) {
		if (entity == player || !entity.isAlive() || entity.isSpectator() || entity instanceof ArmorStand) {
			return false;
		}

		if (entity instanceof Player) {
			if (!attackPlayers.isEnabled()) {
				return false;
			}
		} else if (entity.getType().getCategory() == MobCategory.MONSTER) {
			if (!attackMobs.isEnabled()) {
				return false;
			}
		} else {
			// Живность, которая не воюет (коровы, жители), и прочие сущности не трогаются
			return false;
		}

		if (entity.isInvisible() && !attackInvisible.isEnabled()) {
			return false;
		}
		if (player.distanceTo(entity) > range.get()) {
			return false;
		}
		return throughWalls.isEnabled() || player.hasLineOfSight(entity);
	}

	/** Угол между взглядом игрока и направлением на сущность, в градусах. */
	private static double angleTo(LocalPlayer player, Entity target) {
		Vec3 look = player.getViewVector(1.0F);
		Vec3 direction = target.getEyePosition().subtract(player.getEyePosition()).normalize();
		double dot = Mth.clamp(look.dot(direction), -1.0, 1.0);
		return Math.toDegrees(Math.acos(dot));
	}

	/** Углы, по которым игрок смотрит в глаза цели. */
	private static float[] aimAt(LocalPlayer player, Entity target) {
		Vec3 eye = player.getEyePosition();
		Vec3 at = target.getEyePosition();

		double dx = at.x - eye.x;
		double dy = at.y - eye.y;
		double dz = at.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
		return new float[]{yaw, Mth.clamp(pitch, -90.0F, 90.0F)};
	}
}

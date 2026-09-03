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
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import java.util.UUID;

/**
 * KillAura — автоматическая атака целей вокруг игрока.
 *
 * Режимы:
 * <ul>
 *   <li><b>Быстрый</b> — доворот мгновенный и точный, удар сразу по готовности;</li>
 *   <li><b>Легитный</b> — человечные повороты через {@link RotationHumanizer}, игрок
 *       двигается туда, куда фактически смотрит камера, удары и паузы — с шумом.</li>
 * </ul>
 *
 * Что обязательно в любом режиме:
 * <ul>
 *   <li><b>RayTrace</b> — удар уходит только если луч из глаз по взгляду реально
 *       пересекает хитбокс цели. Практически любой античит проверяет это на сервере:
 *       «удар в то, что прицелом не видишь» — самый частый детект ауры;</li>
 *   <li>удар ждёт восстановления силы атаки — спам ослабленными ударами это потеря
 *       урона и читерский тайминг.</li>
 * </ul>
 *
 * Авто-Блок: пока враг смотрит на нас и стоит в зоне досягаемости удара — держим щит.
 * Как только враг замахнулся (ударил по щиту) — сбрасываем щит и бьём сами; после
 * удара (и Сброса спринта) щит поднимается снова. Смарт Крит вместо немедленного
 * удара прыгает и критует в падении, после чего щит возвращается сразу.
 */
public class KillAuraModule extends Module {

	public static final String MODE_FAST = "fast";
	public static final String MODE_LEGIT = "legit";

	public static final String PRIORITY_NEAREST = "nearest";
	public static final String PRIORITY_HEALTH = "health";
	public static final String PRIORITY_ANGLE = "angle";

	public static final String SPRINT_OFF = "off";
	public static final String SPRINT_FAST = "fast";
	public static final String SPRINT_LEGIT = "legit";

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

	/**
	 * Степень «человечности» легитного режима: 0 — почти без шума, максимум буста,
	 * 100 — максимум рандомизации. Влияет на промахи, перелёты, дрожь, задержки.
	 */
	private final IntSetting randomization = intSetting("randomization", "Рандомизация, %", 70, 0, 100);

	/** Держать щит, пока враг смотрит на нас и стоит в зоне удара; на его замах — контратака. */
	private final BooleanSetting autoBlock = bool("auto_block", "Авто-Блок (щит)", true);

	/** Вместо удара с земли — прыгнуть и критовать в падении, затем сразу поднять щит. */
	private final BooleanSetting smartCrit = bool("smart_crit", "Смарт Крит", false);

	/** Сброс спринта после удара: полный нокбэк каждым ударом (w-tap). */
	private final ModeSetting sprintReset = mode("sprint_reset", "Сброс спринта", SPRINT_FAST,
			ModeSetting.option(SPRINT_OFF, "Выкл"),
			ModeSetting.option(SPRINT_FAST, "Быстрый"),
			ModeSetting.option(SPRINT_LEGIT, "Легитный"));

	private static final Random RANDOM = new Random();

	/** До скольки градусов считаем, что прицел наведён (легитный режим). */
	private static final float AIM_TOLERANCE = 6.0F;

	/** Дальняя граница «враг может достать ударом» — здесь щит ещё имеет смысл. */
	private static final float BLOCK_REACH = 3.2F;

	/** Косинус угла, в пределах которого считаем «враг смотрит на нас». */
	private static final double FACING_DOT = 0.60;

	/** Фаза контратаки: что именно мы делаем после того, как решили бить. */
	private enum Sequence {
		NONE, LOWERING, JUMPING
	}

	private UUID targetId;

	/** Куда по вертикали целимся по текущей цели: преимущественно корпус, иногда голова. */
	private float aimOffsetY;

	private int attackDelay;

	/** Текущая фаза контратаки и её счётчики. */
	private Sequence sequence = Sequence.NONE;
	private int lowerTicks;
	private int critTimeout;
	private int jumpHoldTicks;

	/** Кулдаун повторного поднятия щита после удара — человек не машет щитом как веером. */
	private int blockCooldown;

	/** Сколько тиков держится сброс спринта после удара. */
	private int sprintResetTicks;

	/** Замах врага: фронт замаха ловим по переходу swinging false → true. */
	private boolean wasTargetSwinging;

	/** Держим ли мы сейчас W (движение за камерой) и прыжок (Смарт Крит). */
	private boolean holdingMove;
	private boolean holdingJump;

	public KillAuraModule() {
		super("kill_aura", "KillAura", "Автоматическая атака: режимы, Авто-Блок щитом, Смарт Крит, сброс спринта и обязательный RayTrace",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_X);
	}

	@Override
	protected void onEnable() {
		this.targetId = null;
		this.aimOffsetY = drawAimOffset();
		this.attackDelay = 3;
		resetSequence();
	}

	@Override
	protected void onDisable() {
		this.targetId = null;
		resetSequence();
		releaseMovement();
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null) {
			stopBlocking(client.player);
		}
	}

	public boolean isLegit() {
		return mode.is(MODE_LEGIT);
	}

	/** Степень рандомизации для RotationHumanizer (0..100). */
	public int getRandomization() {
		return randomization.get();
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			return;
		}

		// В меню и чате не воюем. Использование предмета — тоже пауза, КРОМЕ щита:
		// пока держим щит Авто-Блоком, isUsingItem() истинен, и прерывать себя нельзя
		if (client.gui != null && client.gui.screen() != null
				|| player.isUsingItem() && !player.isBlocking()) {
			releaseMovement();
			return;
		}

		Entity target = selectTarget(client, player);
		if (target == null) {
			this.targetId = null;
			this.wasTargetSwinging = false;
			resetSequence();
			releaseMovement();
			// Врага больше нет — щит опускаем, если держим его мы
			stopBlocking(player);
			return;
		}

		// Смена цели — новый «захват»: свежая точка прицеливания и человеческая пауза
		UUID id = target.getUUID();
		if (!id.equals(this.targetId)) {
			this.targetId = id;
			this.aimOffsetY = drawAimOffset();
			this.attackDelay = Math.max(this.attackDelay, 3 + RANDOM.nextInt(6));
			this.wasTargetSwinging = target instanceof LivingEntity living && living.swinging;
			resetSequence();
		}

		// Легитный режим: игрок двигается туда, куда фактически смотрит камера киллауры
		if (isLegit()) {
			moveWithCamera(client, player, target);
		} else {
			releaseMovement();
		}

		// Замах врага — фронт: swinging только что стал true
		boolean targetSwinging = target instanceof LivingEntity living && living.swinging;
		boolean enemyJustSwung = targetSwinging && !this.wasTargetSwinging;
		this.wasTargetSwinging = targetSwinging;

		// Прицеливание
		float[] aim = aimAt(player, target);
		boolean aimReady;
		if (isLegit()) {
			// Доворот через RotationHumanizer: плавно, с промахом, перелётом и дрожью.
			// Пишем публичными setYRot/setXRot — они не перехватываются миксином
			float[] rotation = RotationHumanizer.aimTowards(player, aim[0], aim[1]);
			if (rotation != null) {
				player.setYRot(rotation[0]);
				player.setXRot(rotation[1]);
			} else {
				player.setYRot(aim[0]);
				player.setXRot(aim[1]);
			}
			// Бьём только прицелом, который «успел» за целью (перелёт скорректирован)
			aimReady = RotationHumanizer.settled()
					&& Math.abs(Mth.wrapDegrees(player.getYRot() - aim[0])) <= AIM_TOLERANCE
					&& Math.abs(player.getXRot() - aim[1]) <= AIM_TOLERANCE;
		} else {
			player.setYRot(aim[0]);
			player.setXRot(aim[1]);
			aimReady = true;
		}

		// Обязательный RayTrace: луч из глаз по взгляду должен пересекать хитбокс —
		// иначе удар «мимо прицела» и античит получает классический детект ауры
		boolean rayHits = rayIntersectsHitbox(player, target, range.get());

		// ---- Авто-Блок: держим щит, пока враг может ударить ----
		InteractionHand shieldHand = shieldHand(player);
		boolean enemyInReach = player.distanceTo(target) <= BLOCK_REACH;
		boolean enemyFacingUs = isFacingUs(target, player);
		// После удара топором щит «отключён» на 100 тиков — поднимать бессмысленно
		boolean shieldDisabled = shieldHand != null
				&& player.getCooldowns().isOnCooldown(player.getItemInHand(shieldHand));
		boolean wantBlock = autoBlock.isEnabled() && shieldHand != null && !shieldDisabled
				&& enemyInReach && enemyFacingUs;

		if (blockCooldown > 0) {
			blockCooldown--;
		}

		switch (sequence) {
		case NONE -> {
			// Спокойное состояние: поднять щит, если надо; бьём, когда всё готово
			if (wantBlock && !player.isBlocking() && blockCooldown == 0) {
				raiseShield(client, player, shieldHand);
			}

			boolean basicReady = attackDelay == 0 && aimReady && rayHits
					&& player.getAttackStrengthScale(0.0F) >= 0.93F;
			// С Авто-Блоком первый удар — только реакция на замах врага (или враг сам
			// вне зоны блока); без него — как только прицел и сила готовы
			boolean provoked = !wantBlock || !player.isBlocking() || enemyJustSwung;
			if (basicReady && provoked) {
				beginAttackSequence(client, player, target);
			} else if (attackDelay > 0) {
				attackDelay--;
			}
		}
		case LOWERING -> {
			// Щит опущен, ждём человеческой задержки — и бьём (или прыгаем)
			if (--lowerTicks <= 0) {
				if (smartCrit.isEnabled() && canCrit(player)) {
					startJump(client);
				} else {
					executeAttack(client, player, target);
				}
			}
		}
		case JUMPING -> {
			// Смарт Крит: ждём окна крита (падение), таймаут — бьём с воздуха как есть
			if (jumpHoldTicks > 0) {
				jumpHoldTicks--;
				client.options.keyJump.setDown(true);
				holdingJump = true;
			} else if (holdingJump) {
				client.options.keyJump.setDown(false);
				holdingJump = false;
			}
			if (player.fallDistance > 0.0F || --critTimeout <= 0) {
				executeAttack(client, player, target);
			}
		}
		}

		// Сброс спринта после удара: быстрый — снять спринт на тик; легитный — w-tap
		if (sprintResetTicks > 0) {
			sprintResetTicks--;
			if (sprintReset.is(SPRINT_FAST)) {
				client.options.keySprint.setDown(false);
				player.setSprinting(false);
			} else if (sprintReset.is(SPRINT_LEGIT)) {
				// w-tap: W отпускается в moveWithCamera по moveSuppressTicks
				client.options.keySprint.setDown(false);
			}
		}
	}

	// ------------------------------------------------------------------
	// Последовательность удара
	// ------------------------------------------------------------------

	/** Решение бить принято: если держим щит — сначала опускаем его. */
	private void beginAttackSequence(Minecraft client, LocalPlayer player, Entity target) {
		if (player.isBlocking()) {
			player.stopUsingItem();
			this.sequence = Sequence.LOWERING;
			this.lowerTicks = isLegit() ? 2 + RANDOM.nextInt(2) : 1;
			this.blockCooldown = 5 + RANDOM.nextInt(5);
		} else if (smartCrit.isEnabled() && canCrit(player)) {
			startJump(client);
		} else {
			executeAttack(client, player, target);
		}
	}

	private void startJump(Minecraft client) {
		this.sequence = Sequence.JUMPING;
		this.critTimeout = 14;
		this.jumpHoldTicks = 2;
		client.options.keyJump.setDown(true);
		this.holdingJump = true;
	}

	/** Сам удар: атака, взмах, пауза, сброс спринта. */
	private void executeAttack(Minecraft client, LocalPlayer player, Entity target) {
		this.sequence = Sequence.NONE;
		releaseJump(client);

		if (target == null || client.gameMode == null || !target.isAlive()) {
			return;
		}

		// Последняя проверка прямо перед ударом: прицел мог уйти за хитбокс
		if (!rayIntersectsHitbox(player, target, range.get())) {
			return;
		}

		client.gameMode.attack(player, target);
		player.swing(InteractionHand.MAIN_HAND);
		this.attackDelay = nextAttackDelay();
		this.blockCooldown = Math.max(this.blockCooldown, 3 + RANDOM.nextInt(3));

		// Сброс спринта: полный нокбэк следующим ударом
		if (!sprintReset.is(SPRINT_OFF)) {
			sprintResetTicks = sprintReset.is(SPRINT_FAST) ? 1 : 1 + RANDOM.nextInt(2);
			SprintModule.suppress(sprintResetTicks + 1);
			if (sprintReset.is(SPRINT_LEGIT)) {
				moveSuppressTicks = sprintResetTicks;
			}
		}
	}

	private void resetSequence() {
		this.sequence = Sequence.NONE;
		this.lowerTicks = 0;
		this.critTimeout = 0;
		this.jumpHoldTicks = 0;
		this.sprintResetTicks = 0;
	}

	// ------------------------------------------------------------------
	// Щит
	// ------------------------------------------------------------------

	/** В какой руке щит (предпочитаем вторую), или null, если щита нет. */
	private static InteractionHand shieldHand(LocalPlayer player) {
		if (player.getItemInHand(InteractionHand.OFF_HAND).getItem() == Items.SHIELD) {
			return InteractionHand.OFF_HAND;
		}
		if (player.getItemInHand(InteractionHand.MAIN_HAND).getItem() == Items.SHIELD) {
			return InteractionHand.MAIN_HAND;
		}
		return null;
	}

	private static void raiseShield(Minecraft client, LocalPlayer player, InteractionHand hand) {
		if (client.gameMode != null && !player.isBlocking() && !player.isUsingItem()) {
			client.gameMode.useItem(player, hand);
		}
	}

	/** Опускаем щит, только если мы его держим (не трогаем чужое использование). */
	private static void stopBlocking(LocalPlayer player) {
		if (player.isBlocking()) {
			player.stopUsingItem();
		}
	}

	// ------------------------------------------------------------------
	// Движение (легитный режим) и клавиши
	// ------------------------------------------------------------------

	/** Сколько тиков не жать W — w-tap после удара. */
	private int moveSuppressTicks;

	/**
	 * Легитный режим: игрок идёт туда, куда фактически смотрит камера киллауры.
	 * Камера наведена на цель — значит, жмём W и сокращаем дистанцию; вблизи —
	 * отпускаем, чтобы не толкать цель. После удара на пару тиков W отпускается
	 * (легитный сброс спринта), как это делает человек.
	 */
	private void moveWithCamera(Minecraft client, LocalPlayer player, Entity target) {
		if (moveSuppressTicks > 0) {
			moveSuppressTicks--;
			client.options.keyUp.setDown(false);
			holdingMove = false;
			return;
		}

		if (player.distanceTo(target) > 1.4F) {
			client.options.keyUp.setDown(true);
			holdingMove = true;
		} else if (holdingMove) {
			client.options.keyUp.setDown(false);
			holdingMove = false;
		}
	}

	private void releaseMovement() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return;
		}
		if (holdingMove) {
			client.options.keyUp.setDown(false);
			holdingMove = false;
		}
		releaseJump(client);
		moveSuppressTicks = 0;
	}

	private void releaseJump(Minecraft client) {
		if (holdingJump) {
			client.options.keyJump.setDown(false);
			holdingJump = false;
		}
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

	// ------------------------------------------------------------------
	// Геометрия: RayTrace, взгляд врага, прицел
	// ------------------------------------------------------------------

	/**
	 * Обязательный RayTrace: отрезок «глаза → взгляд × дальность» пересекает хитбокс
	 * цели (с маленьким допуском). Именно эту проверку делает сервер и античиты —
	 * бить можно только то, что прицел реально видит.
	 */
	private static boolean rayIntersectsHitbox(LocalPlayer player, Entity target, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		AABB box = target.getBoundingBox().inflate(0.15);

		double[] origin = {eye.x, eye.y, eye.z};
		double[] direction = {look.x, look.y, look.z};
		double[] min = {box.minX, box.minY, box.minZ};
		double[] max = {box.maxX, box.maxY, box.maxZ};

		double tMin = 0.0;
		double tMax = range;
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(direction[axis]) < 1.0e-9) {
				if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
					return false;
				}
				continue;
			}
			double t1 = (min[axis] - origin[axis]) / direction[axis];
			double t2 = (max[axis] - origin[axis]) / direction[axis];
			if (t1 > t2) {
				double swap = t1;
				t1 = t2;
				t2 = swap;
			}
			tMin = Math.max(tMin, t1);
			tMax = Math.min(tMax, t2);
			if (tMin > tMax) {
				return false;
			}
		}
		return true;
	}

	/** Смотрит ли враг на нас: угол между его взглядом и направлением на нас. */
	private static boolean isFacingUs(Entity enemy, LocalPlayer player) {
		Vec3 look = enemy.getViewVector(1.0F);
		Vec3 toPlayer = player.getEyePosition().subtract(enemy.getEyePosition()).normalize();
		return look.dot(toPlayer) >= FACING_DOT;
	}

	/** Угол между взглядом игрока и направлением на сущность, в градусах. */
	private static double angleTo(LocalPlayer player, Entity target) {
		Vec3 look = player.getViewVector(1.0F);
		Vec3 direction = target.getEyePosition().subtract(player.getEyePosition()).normalize();
		double dot = Mth.clamp(look.dot(direction), -1.0, 1.0);
		return Math.toDegrees(Math.acos(dot));
	}

	/** Углы, по которым игрок смотрит в точку прицеливания на теле цели. */
	private float[] aimAt(LocalPlayer player, Entity target) {
		Vec3 eye = player.getEyePosition();
		Vec3 at = target.getEyePosition();
		// Смещение по вертикали: корпус/ноги/голова — свой выбор на каждую цель
		at = new Vec3(at.x, at.y + this.aimOffsetY, at.z);

		double dx = at.x - eye.x;
		double dy = at.y - eye.y;
		double dz = at.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
		return new float[]{yaw, Mth.clamp(pitch, -90.0F, 90.0F)};
	}

	// ------------------------------------------------------------------
	// Человеческие случайности
	// ------------------------------------------------------------------

	/**
	 * Пауза после удара: обычно крошечная (тайминг диктует восстановление силы удара,
	 * а не наш счётчик), изредка человек «задумывается» на пару тиков.
	 */
	private static int nextAttackDelay() {
		int delay = RANDOM.nextInt(3);
		if (RANDOM.nextInt(100) < 16) {
			delay += 2 + RANDOM.nextInt(4);
		}
		return delay;
	}

	/**
	 * Точка прицеливания по вертикали: преимущественно корпус (вплоть до ног),
	 * примерно каждый пятый раз — голова. На урон не влияет, а вот почерк меняет.
	 */
	private static float drawAimOffset() {
		if (RANDOM.nextFloat() < 0.2F) {
			return 0.05F + RANDOM.nextFloat() * 0.25F;
		}
		return 0.05F - 0.4F * RANDOM.nextFloat();
	}

	/** Можно ли критовать прямо сейчас: с земли, не в воде, не на лестнице. */
	private static boolean canCrit(LocalPlayer player) {
		return player.onGround()
				&& !player.isInWater()
				&& !player.isInLava()
				&& !player.onClimbable()
				&& !player.isFallFlying()
				&& !player.isPassenger();
	}
}

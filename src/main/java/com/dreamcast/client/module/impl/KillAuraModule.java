package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.util.KeyOwnership;
import com.dreamcast.client.util.TargetLockLogic;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.RotationHumanizer;
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

	public static final String MOVEMENT_FREE = "free";
	public static final String MOVEMENT_FOCUSED = "focused";
	public static final String MOVEMENT_LEGIT = "legit";

	private final ModeSetting mode = mode("mode", "Режим", MODE_FAST,
			ModeSetting.option(MODE_FAST, "Быстрый"),
			ModeSetting.option(MODE_LEGIT, "Легитный"));

	private final IntSetting range = intSetting("range", "Дальность, блоков", 4, 1, 6);

	private final ModeSetting targetLock = mode("target_lock", "Удержание цели", "sticky",
			ModeSetting.option("off", "Выкл"),
			ModeSetting.option("sticky", "Sticky"),
			ModeSetting.option("switch", "Switch"));
	private final IntSetting switchDelay = intSetting("switch_delay", "Задержка смены цели, тиков", 10, 0, 40);

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

	/**
	 * Коррекция движений: как игрок двигается, пока киллаура целится.
	 * Свободный — ввод поворачивается к «своему» взгляду (W ведёт туда, куда смотрел
	 * игрок, а не куда навела аура). Фокусированный — аура сама ведёт игрока в центр
	 * цели и на близкой дистанции кружит вокруг неё. Легитный — аура клавиши не
	 * трогает: игрок идёт сам, и только пока держит W.
	 */
	private final ModeSetting movement = mode("movement", "Коррекция движений", MOVEMENT_LEGIT,
			ModeSetting.option(MOVEMENT_FREE, "Свободный"),
			ModeSetting.option(MOVEMENT_FOCUSED, "Фокусированный"),
			ModeSetting.option(MOVEMENT_LEGIT, "Легитный"));

	/** Сброс спринта после удара: полный нокбэк каждым ударом (w-tap). */
	private final ModeSetting sprintReset = mode("sprint_reset", "Сброс спринта", SPRINT_FAST,
			ModeSetting.option(SPRINT_OFF, "Выкл"),
			ModeSetting.option(SPRINT_FAST, "Быстрый"),
			ModeSetting.option(SPRINT_LEGIT, "Легитный"));

	// ------------------------------------------------------------------
	// Лимиты: когда аура обязана держать паузу
	// ------------------------------------------------------------------

	/** Не прерывать еду: аура ждёт, пока игрок закончит есть/пить. */
	private final BooleanSetting limitEating = bool("limit_eating", "Лимит: не бить, когда ешь", true);

	/** Не прерывать тотем: удар снимает анимацию тотема — смерть. Пауза до конца использования. */
	private final BooleanSetting limitTotem = bool("limit_totem", "Лимит: не прерывать тотем", true);

	/** Бить только когда в основной руке реальное оружие. */
	private final BooleanSetting limitWeapon = bool("limit_weapon", "Лимит: только с оружием в руке", false);

	/** Не атаковать, пока находимся под водой (нулевой урон там почти всегда). */
	private final BooleanSetting limitWater = bool("limit_water", "Лимит: не бить под водой", false);

	/** Не атаковать в прыжке/падении — удар без опоры слабее, а античит любит это проверять. */
	private final BooleanSetting limitFalling = bool("limit_falling", "Лимит: не бить в прыжке", false);

	/** При критическом HP аура отступает вместо боя (порог — «HP для отступления»). */
	private final BooleanSetting limitLowHealth = bool("limit_low_health", "Лимит: отступать при малом HP", false);
	private final IntSetting lowHealthLine = intSetting("low_health_line", "HP для отступления", 4, 1, 19);

	/** Не опускать щит ради удара: чисто защитный режим, бьём только не под блоком. */
	private final BooleanSetting limitKeepBlock = bool("limit_keep_block", "Лимит: не опускать щит", false);


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
	/** Текущая цель ауры — читает TargetESP. Обновляется каждый тик. */
	private Entity currentTarget;
	/** Sticky: сколько тиков ещё держимся за текущую цель. */
	private int lockTicks;

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

	/** Держим ли мы сейчас клавиши движения (Фокусированный режим) и прыжок. */
	private boolean holdingForward;
	private int holdingStrafe; // -1 — влево, 0 — нет, 1 — вправо
	private boolean holdingJump;

	/** Фокусированный режим: накопленный «ультра-быстрый» доворот круга. */
	private float spinYaw;
	private int orbitDirection = 1;
	private int orbitTicks;

	/**
	 * Куда игрок смотрел бы «сам» — без камеры киллауры. Считается из мышиных
	 * дельт (см. tick): нужно «Свободной» коррекции, чтобы W вёл игрока по его
	 * собственному взгляду, а не по наведённой камере.
	 */
	private static float userYaw;
	private static float lastWrittenYaw;
	private static boolean trackingUserYaw;

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
		this.currentTarget = null;
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

		// В меню и чате не воюем
		if (client.gui != null && client.gui.screen() != null) {
			releaseMovement();
			return;
		}

		// Использование предмета — пауза, КРОМЕ щита: пока держим щит Авто-Блоком,
		// isUsingItem() истинен, и прерывать себя нельзя. Еда и тотем — по лимитам.
		if (itemUsePausesAttack(player)) {
			releaseMovement();
			return;
		}

		Entity target = selectTarget(client, player);
		this.currentTarget = target;
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

		// «Свободная» коррекция: запоминаем, куда игрок смотрел бы без ауры —
		// ввод потом разворачивается к этому взгляду в KeyboardInputMixin
		if (movement.is(MOVEMENT_FREE)) {
			float currentYaw = player.getYRot();
			if (!trackingUserYaw) {
				userYaw = currentYaw;
				trackingUserYaw = true;
			} else {
				float mouse = Mth.wrapDegrees(currentYaw - lastWrittenYaw);
				userYaw = Math.abs(mouse) < 40.0F ? Mth.wrapDegrees(userYaw + mouse) : currentYaw;
			}
		} else {
			trackingUserYaw = false;
		}

		// «Фокусированный» режим сам ведёт игрока; остальные — ввод не трогают
		if (!movement.is(MOVEMENT_FOCUSED)) {
			releaseHeldKeys();
		}

		// «Фокусированный» режим: ведём игрока к центру цели и кружим вблизи
		if (movement.is(MOVEMENT_FOCUSED)) {
			moveFocused(client, player, target);
		}

		// Замах врага — фронт: swinging только что стал true
		boolean targetSwinging = target instanceof LivingEntity living && living.swinging;
		boolean enemyJustSwung = targetSwinging && !this.wasTargetSwinging;
		this.wasTargetSwinging = targetSwinging;

		// Прицеливание
		float[] aim = aimAt(player, target);
		boolean fastRotation = !isLegit() || movement.is(MOVEMENT_FOCUSED);
		boolean aimReady;
		if (!fastRotation) {
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
			if (movement.is(MOVEMENT_FOCUSED) && player.distanceTo(target) < 2.2F) {
				// Дошли до центра — «ультра-быстрое кружение»: камера крутится
				// вокруг цели, удары проходят по RayTrace на каждом обороте
				this.spinYaw += 38.0F + RANDOM.nextFloat() * 14.0F;
				player.setYRot(aim[0] + this.spinYaw);
				player.setXRot(aim[1]);
			} else {
				this.spinYaw = 0.0F;
				player.setYRot(aim[0]);
				player.setXRot(aim[1]);
			}
			aimReady = true;
		}
		lastWrittenYaw = player.getYRot();

		// Обязательный RayTrace: луч из глаз по взгляду должен пересекать хитбокс
		// И не упираться раньше в блок (block clip): иначе бьём «сквозь стену»
		boolean rayHits = rayIntersectsHitbox(player, target, range.get())
				&& !blockBlocksRay(player, target);

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
					&& player.getAttackStrengthScale(0.0F) >= 0.93F
					// «Никогда»-лимиты: оружие в руке, вода, прыжок, малое HP
					&& limitsAllow(player);
			// С Авто-Блоком первый удар — только реакция на замах врага (или враг сам
			// вне зоны блока); без него — как только прицел и сила готовы.
			// Лимит «не опускать щит» отключает контратаку из-под блока совсем.
			boolean provoked = !wantBlock || !player.isBlocking() || enemyJustSwung;
			if (limitKeepBlock.isEnabled() && player.isBlocking()) {
				provoked = false;
			}
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
				KeyOwnership.release(client, client.options.keyJump);
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
				KeyOwnership.release(client, client.options.keySprint);
				player.setSprinting(false);
			} else if (sprintReset.is(SPRINT_LEGIT)) {
				// w-tap: на тик отпускаем спринт и — в легитной коррекции — W,
				// который в этом режиме держит сам игрок
				KeyOwnership.release(client, client.options.keySprint);
				if (movement.is(MOVEMENT_LEGIT)) {
					KeyOwnership.release(client, client.options.keyUp);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Лимиты
	// ------------------------------------------------------------------

	/**
	 * Прерывает ли текущее использование предмета атаку по нашим лимитам.
	 *
	 * Щит — нет (с ним живёт Авто-Блок). Тотем — только с «не прерывать тотем»:
	 * удар обрывает анимацию, и возрождающий предмет не сработает. Еду «не бить,
	 * когда ешь» можно отключить — тогда аура будет есть и воевать одновременно,
	 * как это делают speed-билды, но каждое прерывание съедает прогресс приёма пищи.
	 */
	private boolean itemUsePausesAttack(LocalPlayer player) {
		if (!player.isUsingItem() || player.isBlocking()) {
			return false;
		}
		boolean totem = player.getItemInHand(InteractionHand.MAIN_HAND).getItem() == Items.TOTEM_OF_UNDYING
				|| player.getItemInHand(InteractionHand.OFF_HAND).getItem() == Items.TOTEM_OF_UNDYING;
		if (totem) {
			return limitTotem.isEnabled();
		}
		return limitEating.isEnabled();
	}

	/** «Никогда»-лимиты удара: что в руке, где мы и сколько у нас HP. */
	private boolean limitsAllow(LocalPlayer player) {
		if (limitWeapon.isEnabled() && !isWeaponInHand(player)) {
			return false;
		}
		if (limitWater.isEnabled() && player.isInWater()) {
			return false;
		}
		// onGround, а не fallDistance: при подъёме fallDistance == 0, и первая
		// часть прыжка раньше пропускала удар
		if (limitFalling.isEnabled() && !player.onGround()) {
			return false;
		}
		if (limitLowHealth.isEnabled() && player.getHealth() <= lowHealthLine.get()) {
			return false;
		}
		return true;
	}

	/** Оружие МИЛИ: мечи, топоры, булава, трезубец. HEAVY_CORE — ингредиент,
	 * лук/арбалет — дальнобой: мили-удар с ними не имеет смысла (для ranged
	 * нужна отдельная аура со своей логикой зарядки). */
	private static boolean isWeaponInHand(LocalPlayer player) {
		var item = player.getItemInHand(InteractionHand.MAIN_HAND).getItem();
		return item == Items.WOODEN_SWORD || item == Items.STONE_SWORD || item == Items.IRON_SWORD
				|| item == Items.GOLDEN_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD
				|| item == Items.WOODEN_AXE || item == Items.STONE_AXE || item == Items.IRON_AXE
				|| item == Items.GOLDEN_AXE || item == Items.DIAMOND_AXE || item == Items.NETHERITE_AXE
				|| item == Items.MACE || item == Items.TRIDENT;
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
	 * «Фокусированный» режим: пока далеко — жмём W (игрок бежит в центр цели),
	 * у центра — кружим: strafe влево/вправо со сменой направления раз в
	 * 8–14 тиков плюс «ультра-быстрый» доворот камеры (см. tick).
	 */
	private void moveFocused(Minecraft client, LocalPlayer player, Entity target) {
		if (moveSuppressTicks > 0) {
			// w-tap после удара: на тик отпускаем всё
			moveSuppressTicks--;
			holdForward(client, false);
			holdStrafe(client, 0);
			return;
		}

		if (player.distanceTo(target) > 2.2F) {
			holdForward(client, true);
			holdStrafe(client, 0);
			return;
		}

		if (--this.orbitTicks <= 0) {
			this.orbitDirection = RANDOM.nextBoolean() ? 1 : -1;
			this.orbitTicks = 8 + RANDOM.nextInt(7);
		}
		holdForward(client, false);
		holdStrafe(client, this.orbitDirection);
	}

	private void holdForward(Minecraft client, boolean down) {
		if (holdingForward != down) {
			if (down) {
				client.options.keyUp.setDown(true);
			} else {
				KeyOwnership.release(client, client.options.keyUp);
			}
			holdingForward = down;
		}
	}

	private void holdStrafe(Minecraft client, int direction) {
		if (holdingStrafe == direction) {
			return;
		}
		if (holdingStrafe == -1) {
			KeyOwnership.release(client, client.options.keyLeft);
		} else if (holdingStrafe == 1) {
			KeyOwnership.release(client, client.options.keyRight);
		}
		if (direction == -1) {
			client.options.keyLeft.setDown(true);
		} else if (direction == 1) {
			client.options.keyRight.setDown(true);
		}
		holdingStrafe = direction;
	}

	/** Отпускает всё, что мы держали (клавиши игрока не трогает). */
	private void releaseHeldKeys() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.options == null) {
			return;
		}
		holdForward(client, false);
		holdStrafe(client, 0);
		releaseJump(client);
	}

	private void releaseMovement() {
		releaseHeldKeys();
		moveSuppressTicks = 0;
	}

	/**
	 * «Свободная» коррекция для KeyboardInputMixin: пересчитывает ввод так, будто
	 * камера не наводилась аурой — W ведёт игрока по его собственному взгляду.
	 */
	public static net.minecraft.world.phys.Vec2 correctedMovement(net.minecraft.world.phys.Vec2 moveVector) {
		KillAuraModule module = ModuleManager.find(KillAuraModule.class);
		if (module == null || !module.isEnabled() || !module.movement.is(MOVEMENT_FREE) || !trackingUserYaw) {
			return null;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || moveVector == null
				|| moveVector.x == 0.0F && moveVector.y == 0.0F) {
			return null;
		}

		float aimYaw = client.player.getYRot();
		if (Math.abs(Mth.wrapDegrees(userYaw - aimYaw)) < 1.0F) {
			return null;
		}

		// Мировое направление текущего ввода (относительно наведённой камеры)…
		double aimRad = Math.toRadians(aimYaw);
		double aimForwardX = -Math.sin(aimRad), aimForwardZ = Math.cos(aimRad);
		double aimRightX = -aimForwardZ, aimRightZ = aimForwardX;
		double worldX = aimForwardX * moveVector.y + aimRightX * moveVector.x;
		double worldZ = aimForwardZ * moveVector.y + aimRightZ * moveVector.x;

		// …и то же направление в осях «своего» взгляда игрока
		double userRad = Math.toRadians(userYaw);
		double userForwardX = -Math.sin(userRad), userForwardZ = Math.cos(userRad);
		double userRightX = -userForwardZ, userRightZ = userForwardX;
		return new net.minecraft.world.phys.Vec2(
				(float) (worldX * userRightX + worldZ * userRightZ),
				(float) (worldX * userForwardX + worldZ * userForwardZ));
	}

	private void releaseJump(Minecraft client) {
		if (holdingJump) {
			KeyOwnership.release(client, client.options.keyJump);
			holdingJump = false;
		}
	}

	// ------------------------------------------------------------------
	// Выбор цели
	// ------------------------------------------------------------------

	/** Ищет лучшую цель по выбранному приоритету. */
	/** Текущая цель KillAura (или null) — для TargetESP и HUD. */
	public Entity currentTarget() {
		return currentTarget;
	}

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

		return applyTargetLock(player, currentTarget, best, bestScore);
	}

	/**
	 * Sticky-цель: без этого аура прыгала между двумя равными целями каждый
	 * тик, каждый раз сбрасывая захват и паузу — и не била вовсе. Держимся за
	 * текущую цель, пока она валидна; меняем только если новая заметно лучше
	 * (≥20 %) либо прошла задержка switchDelay.
	 */
	private Entity applyTargetLock(LocalPlayer player, Entity current, Entity best, double bestScore) {
		if (best == null) {
			return best;
		}
		boolean currentValid = current != null && current.isAlive() && isValidTarget(player, current);
		// само решение — чистая логика, покрытая TargetLockLogicTest
		TargetLockLogic.Decision decision = TargetLockLogic.decide(
				!targetLock.is("off"), currentValid, current == best,
				lockTicks, switchDelay.get(), targetLock.is("sticky"),
				bestScore, current == null ? Double.MAX_VALUE : scoreOf(player, current));
		lockTicks = decision.lockTicks();
		return decision.keepCurrent() ? current : best;
	}

	private double scoreOf(LocalPlayer player, Entity entity) {
		if (priority.is(PRIORITY_NEAREST)) {
			return player.distanceToSqr(entity);
		}
		if (priority.is(PRIORITY_HEALTH)) {
			return entity instanceof LivingEntity living ? living.getHealth() : Double.MAX_VALUE;
		}
		return angleTo(player, entity);
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

	/** Стоит ли блок между глазами и хитбоксом цели (по реальному clip'у мира). */
	private static boolean blockBlocksRay(LocalPlayer player, Entity target) {
		net.minecraft.world.phys.Vec3 eyes = player.getEyePosition();
		net.minecraft.world.phys.Vec3 center = positionCenter(target);
		double distance = Math.min(eyes.distanceTo(center), player.blockInteractionRange() + 1.0);
		net.minecraft.world.phys.Vec3 end = eyes.add(player.getViewVector(1.0F).scale(distance));
		var hit = player.level().clip(new net.minecraft.world.level.ClipContext(eyes, end,
				net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
				&& eyes.distanceTo(hit.getLocation()) + 0.4 < eyes.distanceTo(center);
	}

	private static net.minecraft.world.phys.Vec3 positionCenter(Entity target) {
		return target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
	}

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

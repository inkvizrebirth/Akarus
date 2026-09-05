package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.util.KeyOwnership;
import com.dreamcast.client.util.TargetLockLogic;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ElementListSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.rotation.RotationManager;
import com.dreamcast.client.rotation.RotationMath;
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
import java.util.List;
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

	public static final String ROTATION_SILENT = "silent";
	public static final String ROTATION_VISIBLE = "visible";
	public static final String ROTATION_NONE = "none";

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

	/** Один раскрываемый список целей вместо трёх разрозненных тумблеров. */
	private final ElementListSetting targets = addSetting(new ElementListSetting("targets", "Цели",
			List.of(
					new ElementListSetting.Element("players", "Игроки"),
					new ElementListSetting.Element("mobs", "Враждебные мобы"),
					new ElementListSetting.Element("invisible", "Невидимые")
			), "players", "mobs"));
	private final BooleanSetting throughWalls = bool("walls", "Через стены", false);
	private final BooleanSetting ignoreTeams = bool("ignore_teams", "Не бить союзников/команду", true);
	private final BooleanSetting ignoreCreative = bool("ignore_creative", "Не бить Creative/Spectator", true);
	private final IntSetting fieldOfView = intSetting("fov", "Угол обзора, градусов", 360, 30, 360);
	private final IntSetting attackStrength = intSetting("attack_strength", "Сила удара, %", 93, 50, 100);
	private final ModeSetting aimPoint = mode("aim_point", "Точка прицела", "adaptive",
			ModeSetting.option("adaptive", "Адаптивно"),
			ModeSetting.option("head", "Голова"),
			ModeSetting.option("chest", "Корпус"),
			ModeSetting.option("legs", "Ноги"));

	/**
	 * Как аура наводится. «Сайлент» — у ауры СВОЯ камера: доворот уходит только в
	 * пакет движения, взгляд игрока не двигается вообще (см. {@link RotationManager}).
	 * «Видимый» — как раньше: игрок разворачивается на цель. «Без доворота» — аура
	 * бьёт только то, во что уже смотрит игрок (для самых строгих античитов).
	 */
	private final ModeSetting rotation = mode("rotation", "Поворот", ROTATION_SILENT,
			ModeSetting.option(ROTATION_SILENT, "Сайлент (своя камера)"),
			ModeSetting.option(ROTATION_VISIBLE, "Видимый"),
			ModeSetting.option(ROTATION_NONE, "Без доворота"));

	/** Ограничение скорости доворота, °/тик. 0 — мгновенно (как раньше в «Быстром»). */
	private final IntSetting rotationSpeed = intSetting("rotation_speed", "Скорость доворота, °/тик", 0, 0, 90);

	/** Ждать ли, пока сервер получил наш поворот, перед ударом (защита от angle-флагов). */
	private final BooleanSetting waitSync = bool("wait_sync", "Бить только после синхронизации", true);

	/** Насколько расширить хитбокс цели при проверке лучом (0.1 блока за единицу). */
	private final IntSetting hitboxExpand = intSetting("hitbox_expand", "Расширение хитбокса (0.1)", 2, 0, 8);

	/** Упреждение: на сколько тиков вперёд берём скорость цели (0 — не упреждать). */
	private final IntSetting leadTicks = intSetting("lead_ticks", "Упреждение цели, тиков", 0, 0, 5);

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
	private final ModeSetting movement = mode("movement", "Коррекция движений (видимый поворот)", MOVEMENT_LEGIT,
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

	/** Допуск «Быстрого» режима: мгновенный доворот обязан быть точным. */
	private static final float AIM_SNAP_TOLERANCE = 1.0F;

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
	private float aimHeightFraction;

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
	/** Щит подняла именно аура — ручное блокирование игрока не снимаем при off/GUI. */
	private boolean blockingByAura;

	/** Фокусированный режим: накопленный «ультра-быстрый» доворот круга. */
	private float spinYaw;
	private int orbitDirection = 1;
	private int orbitTicks;

	public KillAuraModule() {
		super("kill_aura", "KillAura", "Автоматическая атака: режимы, Авто-Блок щитом, Смарт Крит, сброс спринта и обязательный RayTrace",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_X);
	}

	@Override
	protected void onEnable() {
		this.targetId = null;
		this.aimHeightFraction = drawAimHeight();
		this.attackDelay = 3;
		this.blockCooldown = 0;
		this.moveSuppressTicks = 0;
		this.spinYaw = 0.0F;
		this.orbitTicks = 0;
		this.blockingByAura = false;
		resetSequence();
	}

	@Override
	protected void onDisable() {
		this.targetId = null;
		this.currentTarget = null;
		RotationManager.release(this);
		resetSequence();
		releaseMovement();
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null) {
			stopBlocking(client.player);
		}
		KeyOwnership.releaseAll(client, this);
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
			RotationManager.release(this);
			releaseMovement();
			stopBlocking(player);
			return;
		}

		// Использование предмета — пауза, КРОМЕ щита: пока держим щит Авто-Блоком,
		// isUsingItem() истинен, и прерывать себя нельзя. Еда и тотем — по лимитам.
		if (itemUsePausesAttack(player)) {
			RotationManager.release(this);
			releaseMovement();
			stopBlocking(player);
			return;
		}

		Entity target = selectTarget(client, player);
		this.currentTarget = target;
		if (target == null) {
			this.targetId = null;
			this.wasTargetSwinging = false;
			RotationManager.release(this);
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
			this.aimHeightFraction = drawAimHeight();
			this.attackDelay = Math.max(this.attackDelay, 3 + RANDOM.nextInt(6));
			this.wasTargetSwinging = target instanceof LivingEntity living && living.swinging;
			resetSequence();
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

		// ---- Прицеливание: СВОИМИ углами, через RotationManager ------------------
		// Модуль больше не пишет player.setYRot/setXRot. В «сайленте» доворот уходит
		// только в пакет движения (см. LocalPlayerRotationMixin), поэтому камера
		// игрока остаётся там, где её оставил игрок, а сервер видит прицел ауры.
		float[] aim = aimAt(player, target);
		if (movement.is(MOVEMENT_FOCUSED) && player.distanceTo(target) < 2.2F) {
			// Дошли до центра — «ультра-быстрое кружение»: аура обходит цель по дуге,
			// удары проходят по RayTrace на каждом обороте
			this.spinYaw += 38.0F + RANDOM.nextFloat() * 14.0F;
			aim[0] = RotationMath.wrap(aim[0] + this.spinYaw);
		} else {
			this.spinYaw = 0.0F;
		}

		// «Легитный» доворот очеловечивается; в «Быстром» скорость не ограничиваем
		boolean humanize = isLegit() && !movement.is(MOVEMENT_FOCUSED);
		float speed = isLegit() ? rotationSpeed.get() : 0.0F;
		if (!RotationManager.request(this, RotationManager.PRIORITY_AURA, rotationMode(),
				movementMode(), speed, humanize, aim[0], aim[1])) {
			// Прямо сейчас слоем владеет более важный модуль (AutoBuff бросает зелье) —
			// не дерём камеру/пакеты: просто ждём свой следующий тик
			return;
		}

		// Бьём, только НАВЕДШИСЬ и синхронизировав поворот с сервером: удар под
		// старым углом — это «rotation mismatch», самый частый флаг на аурах.
		boolean aimReady;
		if (rotation.is(ROTATION_NONE)) {
			aimReady = RotationMath.aimed(player.getYRot(), player.getXRot(),
					aim[0], aim[1], AIM_TOLERANCE);
		} else {
			aimReady = RotationManager.aimed(isLegit() ? AIM_TOLERANCE : AIM_SNAP_TOLERANCE)
					&& (!waitSync.isEnabled() || RotationManager.inSync());
		}

		// Обязательный RayTrace: луч из глаз по НАШЕМУ прицелу должен пересекать
		// хитбокс и не упираться раньше в блок (то есть бьём то, что видит сервер)
		boolean rayHits = rayIntersectsHitbox(player, target, range.get())
				&& (throughWalls.isEnabled() || !blockBlocksRay(player, target));

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
			} else if (!wantBlock && blockingByAura) {
				stopBlocking(player);
			}

			boolean basicReady = attackDelay == 0 && aimReady && rayHits
					&& player.getAttackStrengthScale(0.0F) >= attackStrength.get() / 100.0F
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
				KeyOwnership.hold(client, client.options.keyJump, this);
				holdingJump = true;
			} else if (holdingJump) {
				KeyOwnership.releaseHold(client, client.options.keyJump, this);
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
			KeyOwnership.suppress(client, client.options.keySprint, this);
			if (sprintReset.is(SPRINT_FAST)) {
				player.setSprinting(false);
			} else if (sprintReset.is(SPRINT_LEGIT)) {
				// w-tap: на тик отпускаем спринт и — в легитной коррекции — W,
				// который в этом режиме держит сам игрок
				if (movement.is(MOVEMENT_LEGIT)) {
					KeyOwnership.suppress(client, client.options.keyUp, this);
				}
			}
		} else {
			KeyOwnership.releaseSuppression(client, client.options.keySprint, this);
			KeyOwnership.releaseSuppression(client, client.options.keyUp, this);
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
			blockingByAura = false;
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
		KeyOwnership.hold(client, client.options.keyJump, this);
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
		if (!rayIntersectsHitbox(player, target, range.get())
				|| !throughWalls.isEnabled() && blockBlocksRay(player, target)) {
			return;
		}

		// Сервер должен видеть наш прицел ДО удара: обычно поворот уже ушёл в
		// пакете движения этого тика, но если миксина нет (или угол сменился
		// позже) — докупаем коротким Rot-пакетом прямо здесь.
		RotationManager.syncBeforeAction(player);
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
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.options != null) {
			releaseJump(client);
			KeyOwnership.releaseSuppression(client, client.options.keySprint, this);
			KeyOwnership.releaseSuppression(client, client.options.keyUp, this);
		}
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

	private void raiseShield(Minecraft client, LocalPlayer player, InteractionHand hand) {
		if (client.gameMode != null && !player.isBlocking() && !player.isUsingItem()) {
			client.gameMode.useItem(player, hand);
			blockingByAura = true;
		}
	}

	/** Опускаем щит, только если мы его держим (не трогаем чужое использование). */
	private void stopBlocking(LocalPlayer player) {
		if (blockingByAura && player.isBlocking()) {
			player.stopUsingItem();
		}
		blockingByAura = false;
	}

	// ------------------------------------------------------------------
	// Движение (легитный режим) и клавиши
	// ------------------------------------------------------------------

	/** Сколько тиков не жать W — w-tap после удара. */
	private int moveSuppressTicks;

	/**
	 * «Фокусированный» режим: аура сама ведёт игрока в центр цели, а вблизи —
	 * кружит вокруг неё.
	 *
	 * Направление считается честно: вектор «в цель» (или по касательной вблизи)
	 * перекладывается в оси того взгляда, которым игрок управляет вводом, и уже
	 * из него нажимаются W/S/A/D. Поэтому режим работает и в «видимом» повороте
	 * (там ввод идёт от камеры ауры), и в «сайленте» (камера игрока не тронута,
	 * но игрок всё равно доезжает туда, куда целится аура).
	 */
	private void moveFocused(Minecraft client, LocalPlayer player, Entity target) {
		if (moveSuppressTicks > 0) {
			// w-tap после удара: на тик отпускаем всё
			moveSuppressTicks--;
			holdForward(client, false);
			holdStrafe(client, 0);
			return;
		}

		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);
		if (flat < 1.0e-3) {
			holdForward(client, false);
			holdStrafe(client, 0);
			return;
		}

		// Далеко — идём в цель; вблизи — по касательной (кругом)
		double dirX;
		double dirZ;
		if (flat < 2.2) {
			if (--this.orbitTicks <= 0) {
				this.orbitDirection = RANDOM.nextBoolean() ? 1 : -1;
				this.orbitTicks = 8 + RANDOM.nextInt(7);
			}
			dirX = -dz / flat * this.orbitDirection;
			dirZ = dx / flat * this.orbitDirection;
		} else {
			this.orbitTicks = 0;
			dirX = dx / flat;
			dirZ = dz / flat;
		}

		// Оси ввода — фактический разворот игрока: в «видимом» режиме его уже
		// развернула аура, в «сайте» там же и остался взгляд игрока
		float axisYaw = player.getYRot();
		double rad = Math.toRadians(axisYaw);
		double fwdX = -Math.sin(rad), fwdZ = Math.cos(rad);
		double rightX = -fwdZ, rightZ = fwdX;
		float forward = (float) (dirX * fwdX + dirZ * fwdZ);
		float strafe = (float) (dirX * rightX + dirZ * rightZ);

		holdForward(client, forward > 0.35F);
		holdStrafe(client, strafe > 0.35F ? 1 : strafe < -0.35F ? -1 : 0);
	}

	private void holdForward(Minecraft client, boolean down) {
		if (holdingForward != down) {
			if (down) {
				KeyOwnership.hold(client, client.options.keyUp, this);
			} else {
				KeyOwnership.releaseHold(client, client.options.keyUp, this);
			}
			holdingForward = down;
		}
	}

	private void holdStrafe(Minecraft client, int direction) {
		if (holdingStrafe == direction) {
			return;
		}
		if (holdingStrafe == -1) {
			KeyOwnership.releaseHold(client, client.options.keyLeft, this);
		} else if (holdingStrafe == 1) {
			KeyOwnership.releaseHold(client, client.options.keyRight, this);
		}
		if (direction == -1) {
			KeyOwnership.hold(client, client.options.keyLeft, this);
		} else if (direction == 1) {
			KeyOwnership.hold(client, client.options.keyRight, this);
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
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.options != null) {
			KeyOwnership.releaseSuppression(client, client.options.keySprint, this);
			KeyOwnership.releaseSuppression(client, client.options.keyUp, this);
		}
		moveSuppressTicks = 0;
	}

	/**
	 * Коррекция ввода для {@code KeyboardInputMixin} — делегат к слою поворотов.
	 * Метод оставлен, чтобы у миксина был понятный «ауровский» вход: вся логика
	 * (когда она вообще нужна) живёт в {@link RotationManager}.
	 */
	public static net.minecraft.world.phys.Vec2 correctedMovement(net.minecraft.world.phys.Vec2 moveVector) {
		return RotationManager.correctedInput(moveVector);
	}

	/** Режим поворота из настройки — в терминах слоя. */
	private RotationManager.Mode rotationMode() {
		if (rotation.is(ROTATION_VISIBLE)) {
			return RotationManager.Mode.VISIBLE;
		}
		if (rotation.is(ROTATION_NONE)) {
			return RotationManager.Mode.NONE;
		}
		return RotationManager.Mode.SILENT;
	}

	/** Коррекция движения из настройки — в терминах слоя. */
	private RotationManager.Movement movementMode() {
		if (movement.is(MOVEMENT_FOCUSED)) {
			return RotationManager.Movement.FOCUSED;
		}
		if (movement.is(MOVEMENT_FREE)) {
			return RotationManager.Movement.FREE;
		}
		return RotationManager.Movement.LEGIT;
	}

	private void releaseJump(Minecraft client) {
		if (holdingJump) {
			KeyOwnership.releaseHold(client, client.options.keyJump, this);
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
			if (!targets.isSelected("players")) {
				return false;
			}
			if (ignoreCreative.isEnabled() && ((Player) entity).isCreative()) {
				return false;
			}
		} else if (entity.getType().getCategory() == MobCategory.MONSTER) {
			if (!targets.isSelected("mobs")) {
				return false;
			}
		} else {
			// Живность, которая не воюет (коровы, жители), и прочие сущности не трогаются
			return false;
		}

		if (entity.isInvisible() && !targets.isSelected("invisible")) {
			return false;
		}
		if (ignoreTeams.isEnabled() && player.isAlliedTo(entity)) {
			return false;
		}
		if (player.distanceTo(entity) > range.get()) {
			return false;
		}
		if (fieldOfView.get() < 360 && angleTo(player, entity) > fieldOfView.get() * 0.5) {
			return false;
		}
		return throughWalls.isEnabled() || player.hasLineOfSight(entity);
	}

	// ------------------------------------------------------------------
	// Геометрия: RayTrace, взгляд врага, прицел
	// ------------------------------------------------------------------

	/** Стоит ли блок между глазами и хитбоксом цели (по реальному clip'у мира). */
	private static boolean blockBlocksRay(LocalPlayer player, Entity target) {
		Vec3 eyes = player.getEyePosition();
		Vec3 center = positionCenter(target);
		double distance = Math.min(eyes.distanceTo(center), player.blockInteractionRange() + 1.0);
		// Луч ведём по ПРИЦЕЛУ АУРЫ, а не по камере: именно так его увидит сервер
		Vec3 end = eyes.add(RotationManager.lookVector().scale(distance));
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
	private boolean rayIntersectsHitbox(LocalPlayer player, Entity target, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = RotationManager.lookVector();
		// Небольшой допуск (настройка) — «край хитбокса» сервер тоже считает щедро
		double expand = 0.15 + hitboxExpand.get() * 0.1;
		AABB box = target.getBoundingBox().inflate(expand);

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

	/**
	 * Угол между ВЗГЛЯДОМ ИГРОКА (не ауры!) и направлением на сущность, в градусах.
	 *
	 * Важно именно так: в «сайленте» прицел слоя всегда смотрит в цель, и фильтр
	 * «угол обзора»/приоритет «под прицелом» на углах ауры были бы бессмысленны
	 * (всегда ноль). Считаем по тому, куда смотрит человек.
	 */
	private static double angleTo(LocalPlayer player, Entity target) {
		Vec3 direction = target.getEyePosition().subtract(player.getEyePosition()).normalize();
		double yawRad = Math.toRadians(RotationManager.userYaw(player));
		double pitchRad = Math.toRadians(RotationManager.userPitch(player));
		double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
		double lookY = -Math.sin(pitchRad);
		double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);
		double dot = Mth.clamp(lookX * direction.x + lookY * direction.y + lookZ * direction.z, -1.0, 1.0);
		return Math.toDegrees(Math.acos(dot));
	}

	/**
	 * Углы, в которые должна «смотреть» аура: точка прицела на теле цели
	 * (с упреждением по её скорости) из глаз игрока.
	 */
	private float[] aimAt(LocalPlayer player, Entity target) {
		Vec3 eye = player.getEyePosition();
		AABB box = target.getBoundingBox();
		float fraction = switch (aimPoint.getValue()) {
			case "head" -> 0.86F;
			case "chest" -> 0.62F;
			case "legs" -> 0.28F;
			default -> this.aimHeightFraction;
		};
		// Точка всегда внутри реального AABB. Старый расчёт прибавлял смещение
		// к позиции глаз и иногда целился выше головы маленьких сущностей.
		double centerX = (box.minX + box.maxX) * 0.5;
		double centerY = box.minY + (box.maxY - box.minY) * fraction;
		double centerZ = (box.minZ + box.maxZ) * 0.5;

		// Упреждение: берём горизонтальную скорость цели (без вертикали — прыжок
		// и гравитация ломают прогноз уже через тик), и целимся в «куда она придёт»
		int lead = leadTicks.get();
		if (lead > 0) {
			Vec3 velocity = target.getDeltaMovement();
			double[] ahead = RotationMath.lead(centerX, centerY, centerZ,
					velocity.x, velocity.y, velocity.z, lead);
			centerX = ahead[0];
			centerY = ahead[1];
			centerZ = ahead[2];
		}

		float yaw = RotationMath.yawTo(eye.x, eye.z, centerX, centerZ);
		float pitch = RotationMath.pitchTo(eye.x, eye.y, eye.z, centerX, centerY, centerZ);
		return new float[]{yaw, pitch};
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
	private static float drawAimHeight() {
		if (RANDOM.nextFloat() < 0.2F) {
			return 0.76F + RANDOM.nextFloat() * 0.12F;
		}
		return 0.42F + 0.26F * RANDOM.nextFloat();
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

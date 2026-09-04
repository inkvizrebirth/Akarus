package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.util.PendingRestores;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * AutoTotem — держит тотем бессмертия в левой руке и, что важнее, <b>предсказывает</b>
 * входящий урон, а не реагирует на уже нанесённый.
 *
 * Обычные авто-тотемы ставят тотем, когда HP уже упало. Это бесполезно ровно там, где
 * больнее всего: смэш булавой с высоты, трезубец в упор и добивание «с одного удара»
 * снимают всё здоровье одним движением — реагировать после некуда. Здесь тотем
 * надевается <i>до</i> урона по трём признакам:
 * <ul>
 *   <li>враг в шаге и замахивается (флаг замаха) — удар через ноль-два тика;</li>
 *   <li>враг смотрит на нас и сближается так, что входит в радиус мили раньше, чем
 *       закроется «окно предсказания»;</li>
 *   <li>сверху пикирует игрок с булавой — до момента приземления (смэш получает
 *       бонус по высоте падения) меньше окна тиков;</li>
 *   <li>в наш хитбокс приходит траектория летящего снаряда (трезубец/стрела).</li>
 * </ul>
 *
 * Скорости считаются по дельте {@code position()} между тиками клиента — без опоры на
 * внутренние velocity-геттеры версии. Механика свопов легитна на уровне пакетов:
 * выделение хотбара + кнопка-своп оффхенда (ровно то, что шлёт F) и shift-перенос,
 * по одному действию за тик.
 *
 * Режимы: «Легитный» — случайные задержки перед каждым действием; «Обычный» — действие
 * раз в тик; «Турбо» — тот же ритм, но не ждёт окончания еды.
 */
public class AutoTotemModule extends Module {

	public static final String MODE_LEGIT = "legit";
	public static final String MODE_NORMAL = "normal";
	public static final String MODE_TURBO = "turbo";

	public static final String RETURN_KEEP = "keep";
	public static final String RETURN_HOTBAR = "hotbar";
	public static final String RETURN_INVENTORY = "inventory";

	/** База молаут-смэша с полной высоты (≈15, плюс ~1.1 за блок падения). */
	private static final float MACE_BASE_DAMAGE = 15.0f;

	/** Минимальная вертикальная скорость пикирования, blocks/тик, чтобы считать «атакует сверху». */
	private static final double FALLING_SPEED_MIN = 0.35;

	private final ModeSetting mode = mode("mode", "Режим", MODE_LEGIT,
			ModeSetting.option(MODE_LEGIT, "Легитный"),
			ModeSetting.option(MODE_NORMAL, "Обычный"),
			ModeSetting.option(MODE_TURBO, "Турбо"));

	/** При HP не выше порога тотем держим всегда, независимо от предсказания. */
	private final IntSetting healthLine = intSetting("health_line", "Ставить при HP ≤", 6, 1, 20);

	private final BooleanSetting prediction = bool("prediction", "Предсказывать урон", true);

	/** Сколько тиков вперёд заглядывает предсказание. */
	private final IntSetting window = intSetting("window", "Окно предсказания, тиков", 8, 2, 20);

	/** Радиус, в котором противники вообще рассматриваются как угроза. */
	private final IntSetting threatRange = intSetting("threat_range", "Дальность угрозы, блоков", 16, 4, 32);

	private final BooleanSetting antiMelee = bool("melee", "Милли: сближение и замах", true);
	private final BooleanSetting antiFallingSmash = bool("falling_smash", "Краш: пикирующая булава", true);
	private final BooleanSetting antiProjectiles = bool("projectiles", "Снайпер: летящие снаряды", true);

	/** Не снимать тотем, пока рядом есть живые противники (релевантно без предсказания). */
	private final BooleanSetting alwaysHold = bool("always_hold", "Держать, пока враг рядом", false);

	/** Ждать окончания еды перед свопом (турбо игнорирует). */
	private final BooleanSetting respectEating = bool("respect_eating", "Не прерывать еду", true);

	private final BooleanSetting alertSound = bool("alert_sound", "Сигнал при постановке", true);

	/** Куда девать тотем, когда угроза прошла. */
	private final ModeSetting returnMode = mode("return_mode", "После боя тотем в", RETURN_HOTBAR,
			ModeSetting.option(RETURN_KEEP, "Левой руке (не снимать)"),
			ModeSetting.option(RETURN_HOTBAR, "Хотбар"),
			ModeSetting.option(RETURN_INVENTORY, "Рюкзак"));

	/** Сколько тиков спокойности нужно, чтобы снять тотем. */
	private final IntSetting safeTicks = intSetting("safe_ticks", "Снимать после (тихих тиков)", 60, 20, 600);

	private static final Random RANDOM = new Random();

	/** Тики покоя без угрозы. */
	private int quiet;

	private int phaseTimer;
	private Phase phase = Phase.IDLE;

	/** Слот тотема в хотбаре на время удержания. */
	private int totemHotbarSlot = -1;

	/** Активная ячейка до манипуляций — вернуть один в один. */
	private int restoreHotbar = -1;
	/** Оффхенд заполнили мы (SWAP) — при выключении вернуть как было. */
	private boolean offhandWasOurs;

	/** Последняя threat-подпись для HUD: кто и сколько. */
	private String lastThreat = "";

	/** Был ли в прошлом сканировании хотя бы один живой противник в радиусе. */
	private boolean hostileNearby;
	/** Экземпляр мира, к которому относится текущая транзакция и история скоростей. */
	private ClientLevel activeLevel;

	/** История позиций для дельт: id → {x, y, z, tick}. */
	private final Map<UUID, double[]> positionHistory = new HashMap<>();

	private enum Phase {
		IDLE,       // тотем не нужен, стоим
		SEARCH,     // ищем тотем (хотбар → рюкзак)
		SHIFT_UP,   // после shift-переноса перечитать хотбар
		SELECT,     // выделить слот хотбара
		SWAP,       // кнопка-своп в левую руку
		HELD,       // держим
		UNSELECT,   // снятие: выделить слот тотема
		UNSWAP,     // своп обратно в хотбар
		STOW,       // shift-перенос тотема в рюкзак
	}

	public AutoTotemModule() {
		super("auto_totem", "AutoTotem",
				"Тотем в левой руке с ПРЕДСКАЗАНИЕМ: смэш булавой сверху, трезубцы и сближение — тотем надевается до удара",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_R);
	}

	@Override
	protected void onEnable() {
		quiet = 0;
		phaseTimer = 0;
		phase = Phase.IDLE;
		totemHotbarSlot = -1;
		restoreHotbar = -1;
		offhandWasOurs = false;
		lastThreat = "";
		hostileNearby = false;
		Minecraft client = Minecraft.getInstance();
		activeLevel = client == null ? null : client.level;
		positionHistory.clear();
	}

	@Override
	protected void onDisable() {
		phase = Phase.IDLE;
		positionHistory.clear();
		// Транзакционный возврат: выключение посреди операции (например, из
		// ClickGUI) не должно оставлять тотем в оффхенде навсегда. ClickGUI
		// не мешает — containerMenu остаётся inventoryMenu; открытый сундук
		// → отложенный возврат через PendingRestores.
		if (offhandWasOurs) {
			Minecraft client = Minecraft.getInstance();
			LocalPlayer player = client == null ? null : client.player;
			if (player != null && isHoldingTotem(player)) {
				int backSlot = totemHotbarSlot >= 0 ? totemHotbarSlot
						: player.getInventory().getSelectedSlot();
					final int wantedSlot = restoreHotbar;
					if (player.containerMenu == player.inventoryMenu) {
						restoreTotem(client, player, backSlot, wantedSlot);
					} else {
						PendingRestores.add(c -> {
							if (c.player == null || c.player.containerMenu != c.player.inventoryMenu) {
								return false;
							}
							// Не используем захваченный LocalPlayer старого мира: после
							// reconnect это другой объект и другая инвентарная сессия.
							if (isHoldingTotem(c.player)) {
								restoreTotem(c, c.player, backSlot, wantedSlot);
							}
							return true;
					});
				}
			}
			offhandWasOurs = false;
			totemHotbarSlot = -1;
			restoreHotbar = -1;
		}
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null || client.gameMode == null) {
			clearTransientState();
			return;
		}
		if (activeLevel != client.level) {
			// Слоты/сущности из прошлого мира нельзя продолжать в новой сессии.
			clearTransientState();
			activeLevel = client.level;
		}

		Screen screen = client.gui == null ? null : client.gui.screen();
		if (screen != null) {
			return; // в меню инвентарь не трогаем
		}

		boolean holding = isHoldingTotem(player);

		// ── нужна ли защита прямо сейчас ──────────────────────────────
		// ВАЖНО: прогноз читает историю ПОЗАПРОШЛОГО тика, поэтому трек
		// позиций обновляем строго ПОСЛЕ predictIncomingDamage — иначе
		// velocityOf вычитает текущую позицию из неё же и скорость всегда 0.
		float incoming;
		if (prediction.isEnabled()) {
			incoming = predictIncomingDamage(client, player);
		} else {
			incoming = 0.0f;
			hostileNearby = hasHostileNearby(client, player);
			lastThreat = "";
		}
		trackVelocities(client);
		float effectiveHealth = player.getHealth() + player.getAbsorptionAmount();
		boolean critical = effectiveHealth <= healthLine.get();
		boolean burst = incoming >= Math.max(4.0f, player.getHealth());
		boolean predictedDeath = incoming > 0.0f && effectiveHealth - incoming <= healthLine.get();
		boolean holdWhileHostile = alwaysHold.isEnabled() && hostileNearby;
		boolean danger = critical || burst || predictedDeath || holdWhileHostile;

		if (danger) {
			quiet = 0;
		} else {
			quiet = Math.min(safeTicks.get() + 1, quiet + 1);
		}

		// ── переходы состояний ─────────────────────────────────────────
		if (phase == Phase.IDLE && danger && !holding) {
			phase = Phase.SEARCH;
			phaseTimer = actionDelay();
		}
		if (phase == Phase.HELD) {
			boolean wantReturn = !danger && holding && quiet >= safeTicks.get() && !returnMode.is(RETURN_KEEP);
			if (!holding) {
				// тотем сам ушёл из руки (смерть, бартер) — сброситься в IDLE
				finishUnarm(player.getInventory());
				phase = Phase.IDLE;
			} else if (wantReturn) {
				phase = Phase.UNSELECT;
				phaseTimer = actionDelay();
			}
		}

		if (phase == Phase.IDLE || phase == Phase.HELD) {
			return;
		}

		if (phaseTimer > 0) {
			phaseTimer--;
			return;
		}
		if (shouldWaitForUse(player)) {
			return;
		}

		runPhase(client, player);
	}

	/** Стоит ли подождать окончания анимации использования. Турбо не ждёт. */
	private boolean shouldWaitForUse(LocalPlayer player) {
		return respectEating.isEnabled() && !mode.is(MODE_TURBO) && player.isUsingItem();
	}

	private void runPhase(Minecraft client, LocalPlayer player) {
		Inventory inventory = player.getInventory();

		switch (phase) {
			case SEARCH -> {
				int hotbarTotem = findTotemInHotbar(inventory);
				if (hotbarTotem >= 0) {
					totemHotbarSlot = hotbarTotem;
					phase = Phase.SELECT;
					phaseTimer = actionDelay();
					return;
				}
				int bagTotem = findTotemInBag(inventory);
				if (bagTotem < 0) {
					lastThreat = "тотем не найден";
					phase = Phase.IDLE;
					return;
				}
				if (firstFreeHotbar(inventory) < 0) {
					lastThreat = "хотбар забит";
					phase = Phase.IDLE;
					return;
				}
				quickMove(client, player, bagTotem);
				phase = Phase.SHIFT_UP;
				phaseTimer = 1 + Math.min(2, actionDelay());
			}
			case SHIFT_UP -> {
				phase = Phase.SEARCH; // перечитаем: перенос применится на сервере со следующим пакетом
				phaseTimer = 1;
			}
			case SELECT -> {
				int slot = totemHotbarSlot >= 0 && isTotem(inventory.getItem(totemHotbarSlot))
						? totemHotbarSlot
						: findTotemInHotbar(inventory);
				if (slot < 0) {
					phase = Phase.SEARCH;
					phaseTimer = actionDelay();
					return;
				}
				totemHotbarSlot = slot;
				if (restoreHotbar < 0) {
					restoreHotbar = inventory.getSelectedSlot();
				}
				if (inventory.getSelectedSlot() != slot) {
					inventory.setSelectedSlot(slot);
					phaseTimer = actionDelay();
				}
				phase = Phase.SWAP;
			}
			case SWAP -> {
				swapOffhand(client, player);
				offhandWasOurs = true;
				if (alertSound.isEnabled()) {
					client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.1F));
				}
				phase = Phase.HELD;
			}
			case UNSELECT -> {
				if (restoreHotbar < 0) {
					restoreHotbar = inventory.getSelectedSlot();
				}
				int slot = totemHotbarSlot >= 0 ? totemHotbarSlot : 0;
				inventory.setSelectedSlot(slot);
				phase = Phase.UNSWAP;
				phaseTimer = actionDelay();
			}
			case UNSWAP -> {
				swapOffhand(client, player);
				offhandWasOurs = false; // сами вернули — восстанавливать при off не нужно
				if (returnMode.is(RETURN_INVENTORY)) {
					phase = Phase.STOW;
					phaseTimer = 1 + Math.min(2, actionDelay());
				} else {
					finishUnarm(inventory);
					phase = Phase.IDLE;
				}
			}
			case STOW -> {
				if (totemHotbarSlot >= 0 && isTotem(inventory.getItem(totemHotbarSlot))) {
					quickMove(client, player, totemHotbarSlot);
				}
				finishUnarm(inventory);
				phase = Phase.IDLE;
			}
			default -> phase = Phase.IDLE;
		}
	}

	private void finishUnarm(Inventory inventory) {
		if (restoreHotbar >= 0 && inventory.getSelectedSlot() != restoreHotbar) {
			inventory.setSelectedSlot(restoreHotbar);
		}
		restoreHotbar = -1;
		totemHotbarSlot = -1;
		offhandWasOurs = false;
	}

	// ------------------------------------------------------------------
	// Инвентарные действия
	// ------------------------------------------------------------------

	/** Ровно то, что шлёт ванильная клавиша F: кнопка-своп оффхенда игрового меню. */
	private static void swapOffhand(Minecraft client, LocalPlayer player) {
		MultiPlayerGameMode gameMode = client.gameMode;
		if (gameMode != null) {
			gameMode.handleInventoryButtonClick(player.inventoryMenu.containerId, Inventory.SLOT_OFFHAND);
		}
	}

	private static void restoreTotem(Minecraft client, LocalPlayer player, int backSlot, int wantedSlot) {
		if (client == null || player == null || client.gameMode == null || !isHoldingTotem(player)) {
			return;
		}
		if (backSlot >= 0 && backSlot < Inventory.getSelectionSize()) {
			player.getInventory().setSelectedSlot(backSlot);
		}
		swapOffhand(client, player);
		if (wantedSlot >= 0 && wantedSlot < Inventory.getSelectionSize()
				&& player.getInventory().getSelectedSlot() != wantedSlot) {
			player.getInventory().setSelectedSlot(wantedSlot);
		}
	}

	/** Сброс незавершённой транзакции без действий со слотами старого мира. */
	private void clearTransientState() {
		phase = Phase.IDLE;
		phaseTimer = 0;
		totemHotbarSlot = -1;
		restoreHotbar = -1;
		offhandWasOurs = false;
		quiet = 0;
		hostileNearby = false;
		lastThreat = "";
		positionHistory.clear();
	}

	/**
	 * Shift-клик переноса (рюкзак ↔ хотбар). Принимает индекс Inventory
	 * (0..8 хотбар, 9..35 рюкзак) и переводит в id слота InventoryMenu:
	 * хотбар в контейнере живёт на 36..44, без пересчёта клик уходил бы
	 * в крафт-сетку/броню.
	 */
	private static void quickMove(Minecraft client, LocalPlayer player, int inventorySlot) {
		MultiPlayerGameMode gameMode = client.gameMode;
		if (gameMode != null) {
			int menuSlot = com.dreamcast.client.util.SlotMath.inventoryToMenuSlot(inventorySlot);
			gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, 0,
					ContainerInput.QUICK_MOVE, player);
		}
	}

	private static boolean isHoldingTotem(LocalPlayer player) {
		return player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
	}

	private static boolean isTotem(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == Items.TOTEM_OF_UNDYING;
	}

	/** Слот 0..8 хотбара с тотемом или -1. */
	private static int findTotemInHotbar(Inventory inventory) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (isTotem(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/** Слот 9..35 рюкзака с тотемом или -1. */
	private static int findTotemInBag(Inventory inventory) {
		for (int slot = Inventory.getSelectionSize(); slot < 36; slot++) {
			if (isTotem(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	private static int firstFreeHotbar(Inventory inventory) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	/** Задержка действия: турбо 0, обычный 1, легит 2..5 с редкими «подтупливаниями». */
	private int actionDelay() {
		if (mode.is(MODE_TURBO)) {
			return 0;
		}
		if (mode.is(MODE_LEGIT)) {
			int base = 2 + RANDOM.nextInt(4);
			return RANDOM.nextInt(100) < 25 ? base + 3 + RANDOM.nextInt(3) : base;
		}
		return 1;
	}

	// ------------------------------------------------------------------
	// Предсказание урона
	// ------------------------------------------------------------------

	/**
	 * Максимальный урон, который реально прилетит в пределах окна предсказания.
	 * Именно максимум (не сумма): задача — решить, надевать ли тотем сейчас.
	 */
	private float predictIncomingDamage(Minecraft client, LocalPlayer player) {
		float worst = 0.0f;
		String threatName = "";
		hostileNearby = false;

		double range = threatRange.get();
		var box = player.getBoundingBox().inflate(range);

		for (Entity entity : client.level.getEntities(player, box, candidate -> candidate != player)) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
				continue;
			}
			boolean playerEnemy = living instanceof Player;
			boolean mobEnemy = living.getType().getCategory() == MobCategory.MONSTER;
			if (!playerEnemy && !mobEnemy) {
				continue;
			}
			hostileNearby = true;

			float damage = threatDamage(client, player, living);
			if (damage > worst) {
				worst = damage;
				threatName = living.getName().getString();
			}
		}

		if (antiProjectiles.isEnabled()) {
			float projectile = incomingProjectileDamage(client, player);
			if (projectile > worst) {
				worst = projectile;
				threatName = "снаряд";
			}
		}

		lastThreat = worst > 0.0f ? threatName + " ≈ " + Math.round(worst) + " HP" : "";
		return worst;
	}

	/** Лёгкий скан для Always Hold, когда тяжёлое предсказание отключено. */
	private boolean hasHostileNearby(Minecraft client, LocalPlayer player) {
		double range = threatRange.get();
		var box = player.getBoundingBox().inflate(range);
		for (Entity entity : client.level.getEntities(player, box, candidate -> candidate != player)) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isSpectator()) {
				continue;
			}
			boolean hostilePlayer = living instanceof Player && !player.isAlliedTo(living);
			boolean hostileMob = living.getType().getCategory() == MobCategory.MONSTER;
			if (hostilePlayer || hostileMob) {
				return true;
			}
		}
		return false;
	}

	/** Угроза от живого противника: замах, сближение, пикирующий смэш. */
	private float threatDamage(Minecraft client, LocalPlayer player, LivingEntity enemy) {
		double distance = enemy.distanceTo(player);
		double[] velocity = velocityOf(enemy);

		// 1) замах + почти вплотную — удар через 0-2 тика, это уже не «может быть»
		if (antiMelee.isEnabled() && distance < 4.6 && enemy.swinging) {
			return estimatedMeleeDamage(enemy, true);
		}

		// 2) сближается под прицелом: входит в радиус мили раньше конца окна.
			// closingSpeed — проекция скорости на направление «на нас» (блоков/тик):
			// делим на длину вектора к нам; прежняя формула с |v|² давала ВРЕМЯ,
			// умноженное затем на |v| — далёкий медленный враг считался мгновенной угрозой
		if (antiMelee.isEnabled() && distance < 9.0 && velocity != null) {
			Vec3 toUs = player.getEyePosition().subtract(enemy.getEyePosition());
			double closeSpeed = com.dreamcast.client.util.MotionMath.closingSpeed(
					toUs.x, toUs.y, toUs.z, velocity[0], velocity[1], velocity[2]);
			boolean closing = closeSpeed > 0.02;
			// время до входа в радиус удара при текущей скорости сближения
			double eta = closing ? (distance - 3.4) / closeSpeed : Double.MAX_VALUE;
			boolean facingUs = dotTo(enemy, player) > 0.55;
			if (closing && facingUs && eta <= window.get()) {
				return estimatedMeleeDamage(enemy, eta <= 2.5);
			}
		}

		// 3) молаут-смэш: сверху, быстро вниз, горизонталь близко, булава в руке
		if (antiFallingSmash.isEnabled()) {
			boolean holdingMace = enemy.getItemInHand(InteractionHand.MAIN_HAND).getItem() == Items.MACE
					|| enemy.getOffhandItem().getItem() == Items.MACE;
			if (holdingMace) {
				double dy = enemy.getY() - player.getY();
				double horizontal = Math.hypot(enemy.getX() - player.getX(), enemy.getZ() - player.getZ());
				if (dy > 1.2 && horizontal < 2.4 && velocity != null && velocity[1] < -FALLING_SPEED_MIN) {
					double fallTicks = dy / -velocity[1];
					if (fallTicks <= window.get()) {
						return MACE_BASE_DAMAGE + (float) (dy - 1.0) * 1.1f;
					}
				}
				// смэш уже исполняется (игрок в удар-анимации с падением) — полный урон
				if (enemy.fallDistance > 1.5 && distance < 3.5) {
					return MACE_BASE_DAMAGE + (float) enemy.fallDistance * 1.1f;
				}
			}
		}

		return 0.0f;
	}

	/**
	 * Прикидка силы мили-удара по оружию и силе (эффекту). Держимся на порядок ниже
	 * теоретического максимума: недооценка здесь безопаснее переоценки — тотем лишний
	 * раз просто полежит в руке, а ложная тревога в бою стоит внимания.
	 */
	private static float estimatedMeleeDamage(LivingEntity enemy, boolean imminent) {
		ItemStack hand = enemy.getItemInHand(InteractionHand.MAIN_HAND);
		var item = hand.getItem();
		float base;
		if (item == Items.MACE) {
			base = 8.0f; // без падения
		} else if (item == Items.TRIDENT) {
			base = 9.5f;
		} else if (item == Items.NETHERITE_AXE || item == Items.DIAMOND_AXE || item == Items.IRON_AXE) {
			base = 9.0f;
		} else if (item == Items.NETHERITE_SWORD) {
			base = 8.0f;
		} else if (item == Items.DIAMOND_SWORD || item == Items.IRON_SWORD) {
			base = 7.0f;
		} else if (item == Items.STONE_SWORD || item == Items.GOLDEN_SWORD) {
			base = 5.0f;
		} else {
			base = 5.0f; // голые руки: 1, но с силой уже больно — возьмём с запасом
		}
		if (enemy.hasEffect(MobEffects.STRENGTH)) {
			base += 3.0f;
		}
		return imminent ? base : base * 0.6f;
	}

	/** Летящие снаряды: ближайший подход траектории к нашему корпусу в пределах окна. */
	private float incomingProjectileDamage(Minecraft client, LocalPlayer player) {
		float worst = 0.0f;
		var box = player.getBoundingBox().inflate(threatRange.get() + 8.0);

		for (Entity entity : client.level.getEntities(player, box,
				e -> e instanceof net.minecraft.world.entity.projectile.Projectile)) {
			net.minecraft.world.entity.projectile.Projectile projectile =
					(net.minecraft.world.entity.projectile.Projectile) entity;
			if (projectile.getOwner() == player) {
				continue; // свой выстрел не считаем
			}

			double[] velocity = velocityOf(entity);
			if (velocity == null) {
				continue;
			}

			// точка прицела снаряда — примерно корпус; решаем min ||P₀ + v·t − H||, t∈[0, окно]
			Vec3 torso = new Vec3(player.getX(), player.getY() + 0.9, player.getZ());
			double px = entity.getX() - torso.x;
			double py = entity.getY() - torso.y;
			double pz = entity.getZ() - torso.z;
			double vx = velocity[0];
			double vy = velocity[1];
			double vz = velocity[2];
			double speedSq = vx * vx + vy * vy + vz * vz;
			if (speedSq < 1.0e-8) {
				continue;
			}
			double t = -(px * vx + py * vy + pz * vz) / speedSq;
			if (t < 0.0 || t > window.get()) {
				continue; // летит мимо уже или уйдёт за окно
			}
			double nearX = px + vx * t;
			double nearY = py + vy * t;
			double nearZ = pz + vz * t;
			// вертикаль штрафует сильнее: по ней снаряд почти не «мажет» телом
			double miss = Math.sqrt(nearX * nearX + nearY * nearY * 2.2 + nearZ * nearZ);

			if (miss < 1.35) {
				boolean trident = entity.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("trident");
				worst = Math.max(worst, trident ? 9.0f : 5.5f);
			}
		}
		return worst;
	}

	// ------------------------------------------------------------------
	// Трекер скоростей (дельты position() по тикам)
	// ------------------------------------------------------------------

	/** Записать текущие позиции всех потенциальных источников в окно наблюдения. */
	private void trackVelocities(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			return;
		}
		double range = threatRange.get() + 10.0;
		var box = player.getBoundingBox().inflate(range);

		if (player.tickCount % 20 == 0 && !positionHistory.isEmpty()) {
			positionHistory.entrySet().removeIf(entry -> player.tickCount - entry.getValue()[3] > 40);
		}

		for (Entity entity : client.level.getEntities(player, box, candidate -> candidate != player)) {
			boolean interesting = entity instanceof Player
					|| entity instanceof net.minecraft.world.entity.projectile.Projectile
					|| (entity instanceof LivingEntity living
						&& living.getType().getCategory() == MobCategory.MONSTER);
			if (interesting) {
				Vec3 pos = entity.position();
					positionHistory.put(entity.getUUID(), new double[]{pos.x, pos.y, pos.z, entity.tickCount});
			}
		}
	}

	/** Скорость в блоках/тик по последней дельте; null — пока наблюдение только началось. */
	private double[] velocityOf(Entity entity) {
		double[] previous = positionHistory.get(entity.getUUID());
		if (previous == null) {
			return null;
		}
		Vec3 current = entity.position();
		return com.dreamcast.client.util.MotionMath.velocityPerTick(
				previous, current.x, current.y, current.z, entity.tickCount);
	}

	/** Косинус угла: смотрит ли «viewer» на «target». */
	private static double dotTo(Entity viewer, Entity target) {
		Vec3 look = viewer.getViewVector(1.0F);
		Vec3 direction = target.getEyePosition().subtract(viewer.getEyePosition()).normalize();
		return look.dot(direction);
	}

	// ------------------------------------------------------------------
	// Статус для HUD и подсказок
	// ------------------------------------------------------------------

	/** Короткая строка состояния: держит/ставит/ждёт + последняя угроза. */
	public String statusText() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null) {
			return "нет игрока";
		}
		if (isHoldingTotem(player)) {
			return "держу" + (lastThreat.isEmpty() ? "" : " · " + lastThreat);
		}
		if (phase != Phase.IDLE) {
			return "ставлю…";
		}
		return lastThreat.isEmpty() ? "ждёт угрозы" : "угроза: " + lastThreat;
	}
}

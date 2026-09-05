package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.rotation.RotationManager;
import com.dreamcast.client.util.BuffPriority;
import com.dreamcast.client.util.DrinkLogic;
import com.dreamcast.client.util.KeyOwnership;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.util.PendingRestores;
import com.dreamcast.client.util.PotionLogic;
import com.dreamcast.client.util.RestorePlan;
import com.dreamcast.client.util.SlotMath;
import com.dreamcast.client.util.SplashResult;
import com.dreamcast.client.util.ThrowGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.lwjgl.glfw.GLFW;

/**
 * AutoBuff — сам применяет зелья-баффы: <b>пьёт обычные и бросает взрывные</b>
 * (строго под ноги), лечится при низком HP.
 *
 * <p>Единая стейт-машина: {@code IDLE → FIND_ITEM → [MOVE_TO_HOTBAR →]
 * SELECT_SLOT → [AIM_DOWN → WAIT_ROTATION →] USE_ITEM → WAIT_RESULT →
 * RESTORE_SLOT → RESTORE_ROTATION → COOLDOWN}. Завершение раздельное:</p>
 * <ul>
 *   <li><b>питьевое</b> — удержание клавиши использования до естественного
 *       гашения {@code isUsingItem()} (досрочный отпуск по остатку тиков
 *       отменял последний тик — предмет тратился без эффекта);</li>
 *   <li><b>взрывное</b> — один {@code useItem} с взглядом вниз (сервер получает
 *       поворот до пакета использования — WAIT_ROTATION), без удержания
 *       клавиши; подтверждение по изменению стака. Эффект может опоздать,
 *       пока снаряд летит, — это не ошибка.</li>
 * </ul>
 *
 * <p>Приоритет: лечение → огнестойкость (горит/кончается) → сила → скорость →
 * регенерация → ночное зрение → золотое яблоко. Следующее действие не
 * начинается, пока не завершено предыдущее.</p>
 *
 * <p>Безопасность: никаких действий при открытом GUI/чужом контейнере; слоты
 * только 0..35; обратный SWAP возвращает предмет резервного слота; анти-спам
 * — общий кулдаун и пауза после лечащего броска (HP успевает обновиться).</p>
 */
public class AutoBuffModule extends Module {

	/** Общий таймаут питья: 32 тика предмета + сетевые задержки, с запасом. */
	private static final long DRINK_TIMEOUT_MS = 8000L;
	/** Если за это время использование так и не началось — откат. */
	private static final long START_TIMEOUT_MS = 600L;
	/** Таймаут подтверждения броска, тиков (снаряд летит — эффект не проверяем). */
	private static final long SPLASH_TIMEOUT_TICKS = 30L;
	/** Пауза после лечащего броска: HP обновляется после попадания снаряда. */
	private static final long HEAL_SETTLE_MS = 1000L;
	/** Резервный слот хотбара для обмена с рюкзаком. */
	private static final int RESERVE_SLOT = 8;
	/** Целевой pitch броска: почти вертикально вниз, но не 90 в точности. */
	private static final float SPLASH_PITCH = 89.0f;
	/** Целевой pitch питья в легит-режиме (как раньше). */
	private static final float DRINK_PITCH = 72.0f;
	/** Глубина проверки поверхности под ногами для броска. */
	private static final int GROUND_CHECK_DEPTH = 4;

	// ------------------------------------------------------------------
	// Настройки
	// ------------------------------------------------------------------

	/** Стиль питья (существующие режимы — не ломаем). */
	private final ModeSetting mode = mode("mode", "Режим питья", "legit",
			ModeSetting.option("fast", "Быстрый"),
			ModeSetting.option("legit", "Легит"));

	/** Что использовать: питьевые и/или взрывные зелья. */
	private final ModeSetting usage = mode("usage", "Использование", "auto",
			ModeSetting.option("auto", "Авто"),
			ModeSetting.option("drink_only", "Только пить"),
			ModeSetting.option("splash_only", "Только бросать"),
			ModeSetting.option("prefer_drink", "Предпочитать питьевое"));

	/** Приоритет взрывных внутри режима «Авто». */
	private final BooleanSetting preferSplash = bool("splash_priority", "Приоритет взрывных", true);

	/** Стиль броска: легитный (плавно, с паузой на синхронизацию) или быстрый. */
	private final ModeSetting throwStyle = mode("throw_style", "Стиль броска", "legit",
			ModeSetting.option("legit", "Легитный"),
			ModeSetting.option("fast", "Быстрый"));

	/**
	 * Как опускать взгляд под бросок. «Сайлент» (по умолчанию) — сервер видит
	 * взгляд вниз, а камера игрока остаётся где была: поворот живёт в слое
	 * {@link RotationManager} и подменяет только пакет движения. «Видимая» —
	 * прежнее поведение (камера реально смотрит под ноги и возвращается обратно).
	 */
	private final ModeSetting rotation = mode("rotation", "Наводка вниз", "silent",
			ModeSetting.option("silent", "Сайлент (своя камера)"),
			ModeSetting.option("visible", "Видимая"),
			ModeSetting.option("none", "Без наводки"));

	private final BooleanSetting wantSpeed = bool("speed", "Скорость", true);
	private final BooleanSetting wantStrength = bool("strength", "Сила", true);
	private final BooleanSetting wantFireRes = bool("fire_res", "Огнестойкость", true);
	private final BooleanSetting wantRegen = bool("regen", "Регенерация", false);
	private final BooleanSetting wantNightVision = bool("night_vision", "Ночное зрение", false);
	private final BooleanSetting wantHeal = bool("heal", "Мгновенное лечение", true);
	private final BooleanSetting wantGapple = bool("gapple", "Золотое яблоко", true);

	private final IntSetting healBelow = intSetting("heal_below", "Лечиться при HP ≤", 12, 1, 20);
	private final IntSetting refreshSeconds = intSetting("refresh", "Пить за N сек до конца", 8, 1, 30);
	/** Минимальная задержка между бросками, тиков. */
	private final IntSetting splashCooldown = intSetting("splash_cooldown", "Задержка между бросками, тиков", 15, 10, 40);
	private final BooleanSetting throwOnlyOnGround = bool("throw_on_ground", "Бросать только с земли", true);
	private final BooleanSetting allowThrowMoving = bool("throw_moving", "Разрешить бросок в движении", true);
	/** Пауза всех новых действий при открытом экране (бросок и так не стартует в GUI). */
	private final BooleanSetting pauseInGui = bool("pause_gui", "Пауза в меню", true);
	private final BooleanSetting notify = bool("notify", "Уведомления", false);

	// ------------------------------------------------------------------
	// Стейт-машина
	// ------------------------------------------------------------------

	private enum Phase {
		IDLE, FIND_ITEM, MOVE_TO_HOTBAR, SELECT_SLOT, AIM_DOWN, WAIT_ROTATION,
		USE_ITEM, WAIT_RESULT, RESTORE_SLOT, RESTORE_ROTATION, COOLDOWN
	}

	private Phase phase = Phase.IDLE;
	private long phaseSince;
	/** Общий кулдаун: не раньше этого времени — новое действие. */
	private long cooldownUntil;
	/** Пауза после лечащего броска: HP обновляется с задержкой попадания. */
	private long healSettleUntil;

	private BuffPriority.Target target = BuffPriority.Target.NONE;
	private Holder<MobEffect> targetEffect;
	private PotionLogic.Kind kind = PotionLogic.Kind.DRINK;
	/** Слот хотбара с предметом (после возможного обмена). */
	private int itemSlot = -1;
	/** Слот рюкзака, откуда обменяли (−1 — предмет был в хотбаре). */
	private int bagSource = -1;
	/** Выбранный слот до наших действий. */
	private int previousSlot = -1;

	private float returnYaw;
	private float returnPitch;
	private boolean cameraTouched;

	private boolean sawUsing;
	/** Снимок стака до броска: предмет и количество. */
	private net.minecraft.world.item.Item snapshotItem;
	private int snapshotCount;
	/** Снимок зелья из рюкзака: ждём именно его в резервном слоте после SWAP. */
	private ItemStack expectedStack;

	private RestorePlan restorePlan = new RestorePlan(false);
	private int pauseSeed;

	public AutoBuffModule() {
		super("auto_buff", "AutoBuff", "Автоматически пьёт и бросает бафф-зелья, лечится при низком HP",
				ModuleCategory.MISC, GLFW.GLFW_KEY_UNKNOWN);
		pauseSeed = System.identityHashCode(this);
	}

	// ------------------------------------------------------------------
	// Прерывания
	// ------------------------------------------------------------------

	@Override
	protected void onDisable() {
		hardInterrupt();
	}

	/** Полный откат: клавиша, предмет резервного слота, слот, взгляд, состояние. */
	private void hardInterrupt() {
		releaseUseKey();
		RotationManager.release(this);
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player != null) {
			// Обратный SWAP: достаточное условие — открыт именно инвентарь игрока
			// (containerMenu == inventoryMenu). ClickGUI этому НЕ мешает — модуль
			// чаще всего выключают именно через него, и запрет терял предмет.
			// Открытый сундук/верстак → containerMenu чужой → SWAP откладывается.
			if (bagSource >= 0) {
				if (player.containerMenu == player.inventoryMenu) {
					swapReserve(player);
				} else {
					final int bagSlot = bagSource;
					final int wantedSlot = previousSlot;
					PendingRestores.add(c -> {
						if (c.player == null || c.player.containerMenu != c.player.inventoryMenu) {
							return false;
						}
						c.gameMode.handleContainerInput(c.player.inventoryMenu.containerId,
								SlotMath.inventoryToMenuSlot(bagSlot), RESERVE_SLOT,
								net.minecraft.world.inventory.ContainerInput.SWAP, c.player);
						if (wantedSlot >= 0) {
							c.player.getInventory().setSelectedSlot(wantedSlot);
						}
						return true;
					});
				}
			}
			if (previousSlot >= 0 && player.getInventory().getSelectedSlot() != previousSlot) {
				player.getInventory().setSelectedSlot(previousSlot);
			}
			if (cameraTouched) {
				player.setXRot(returnPitch);
				player.setYRot(returnYaw);
				cameraTouched = false;
			}
		}
		resetState();
	}

	private void resetState() {
		phase = Phase.IDLE;
		target = BuffPriority.Target.NONE;
		targetEffect = null;
		itemSlot = -1;
		bagSource = -1;
		previousSlot = -1;
		cameraTouched = false;
		sawUsing = false;
		snapshotItem = null;
		snapshotCount = -1;
		expectedStack = null;
		restorePlan = new RestorePlan(false);
	}

	// ------------------------------------------------------------------
	// Тик
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null || client.gameMode == null) {
			hardInterrupt();
			return;
		}

		boolean screenOpen = client.gui != null && client.gui.screen() != null;
		boolean foreignContainer = player.containerMenu != player.inventoryMenu;

		// Чужой контейнер — стоп всегда; экран — стоп новым действиям, если
		// включена «Пауза в меню» (питьё/бросок в GUI всё равно не стартуют)
		if ((foreignContainer || (screenOpen && pauseInGui.isEnabled()))
				&& !survivesScreen(screenOpen, foreignContainer)) {
			return;
		}

		switch (phase) {
			case IDLE, COOLDOWN -> tickIdleOrCooldown(player);
			case FIND_ITEM -> tickFindItem(player);
			case MOVE_TO_HOTBAR -> tickMoveToHotbar();
			case SELECT_SLOT -> tickSelectSlot(player);
			case AIM_DOWN -> tickAimDown(player);
			case WAIT_ROTATION -> tickWaitRotation(player);
			case USE_ITEM -> tickUseItem(client, player);
			case WAIT_RESULT -> tickWaitResult(client, player);
			case RESTORE_SLOT -> tickRestoreSlot(player);
			case RESTORE_ROTATION -> tickRestoreRotation(player);
		}
	}

	/** Что делать при открытом экране/чужом контейнере в середине действия. */
	private boolean survivesScreen(boolean screenOpen, boolean foreignContainer) {
		// До использования — откат (ничего существенного не сделано)
		switch (phase) {
			case IDLE, COOLDOWN, FIND_ITEM:
				return false;
			case MOVE_TO_HOTBAR, SELECT_SLOT, AIM_DOWN, WAIT_ROTATION, USE_ITEM:
				if (screenOpen || foreignContainer) {
					// использовать предмет ещё не поздно — тихо откатываемся
					phase = Phase.RESTORE_SLOT;
					restorePlan = new RestorePlan(bagSource >= 0);
				}
				return false;
			default:
				// WAIT_RESULT/RESTORE_* — продолжаем: питьё само корректно
				// завершится (или по таймауту), SWAP подождёт свой гвард
				return true;
		}
	}

	// ------------------------------------------------------------------
	// IDLE/COOLDOWN: выбор цели
	// ------------------------------------------------------------------

	private void tickIdleOrCooldown(LocalPlayer player) {
		long now = net.minecraft.util.Util.getMillis();
		if (phase == Phase.COOLDOWN) {
			if (now < cooldownUntil || now < healSettleUntil) {
				return;
			}
			phase = Phase.IDLE;
		} else if (now < cooldownUntil || now < healSettleUntil) {
			return;
		}

		BuffPriority.Target picked = chooseTarget(player);
		if (picked == BuffPriority.Target.NONE) {
			return;
		}
		target = picked;
		phase = Phase.FIND_ITEM;
		phaseSince = now;
	}

	/** Приоритет: лечение → огнестойкость (горит/кончается) → сила → скорость → регенерация → зрение → яблоко. */
	private BuffPriority.Target chooseTarget(LocalPlayer player) {
		boolean lowHp = player.getHealth() <= healBelow.get();
		boolean healPresent = potionKindFor(MobEffects.INSTANT_HEALTH) != null;
		boolean fireResUrgent = player.isOnFire() || needs(player, MobEffects.FIRE_RESISTANCE);
		boolean gappleAllowed = wantGapple.isEnabled()
				&& !player.hasEffect(MobEffects.ABSORPTION)
				&& findGapple() >= 0;
		return BuffPriority.pick(lowHp, wantHeal.isEnabled(), healPresent,
				fireResUrgent, wantFireRes.isEnabled() && needs(player, MobEffects.FIRE_RESISTANCE),
				wantStrength.isEnabled() && needs(player, MobEffects.STRENGTH),
				wantSpeed.isEnabled() && needs(player, MobEffects.SPEED),
				wantRegen.isEnabled() && needs(player, MobEffects.REGENERATION),
				wantNightVision.isEnabled() && needs(player, MobEffects.NIGHT_VISION),
				gappleAllowed);
	}

	private boolean needs(LocalPlayer player, Holder<MobEffect> effect) {
		MobEffectInstance active = player.getEffect(effect);
		if (active == null) {
			return true;
		}
		int left = active.getDuration();
		return !active.isInfiniteDuration() && left <= refreshSeconds.get() * 20;
	}

	// ------------------------------------------------------------------
	// Поиск предмета
	// ------------------------------------------------------------------

	private void tickFindItem(LocalPlayer player) {
		if (target == BuffPriority.Target.GOLDEN_APPLE) {
			int slot = findGapple();
			if (slot < 0) {
				restartIdle();
				return;
			}
			kind = PotionLogic.Kind.DRINK; // едим, не бросаем
			targetEffect = null;
			takeItem(slot);
			return;
		}

		Holder<MobEffect> effect = effectFor(target);
		PotionLogic.Kind picked = potionKindFor(effect);
		if (picked == null) {
			restartIdle();
			return;
		}
		kind = picked;
		targetEffect = effect;

		// Взрывное — гварды безопасного броска (только для SPLASH)
		if (kind == PotionLogic.Kind.SPLASH && !throwGuardsPass()) {
			// Бросать нельзя — пробуем питьевое, если режим позволяет
			if (hasPotion(effect, PotionLogic.Kind.DRINK)
					&& PotionLogic.pick(usage.current().id(), false, false, true) != null) {
				kind = PotionLogic.Kind.DRINK;
			} else {
				restartIdle();
				return;
			}
		}

		int slot = findPotion(effect, kind);
		if (slot < 0) {
			restartIdle();
			return;
		}
		takeItem(slot);
	}

	/** Все ли условия безопасного броска выполнены. */
	private boolean throwGuardsPass() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		boolean guiClear = client.gui == null || client.gui.screen() == null;
		boolean groundOk = !throwOnlyOnGround.isEnabled() || player.onGround();
		boolean movingOk = allowThrowMoving.isEnabled()
				|| player.getDeltaMovement().horizontalDistanceSqr() < 0.02;
		return ThrowGuard.canStartThrow(true, true, true, guiClear,
				player.containerMenu == player.inventoryMenu,
				!player.isUsingItem(), player.isAlive(), groundOk, movingOk,
				ThrowGuard.groundBelow(solidBelow(player)));
	}

	/** Не пустота ли под ногами: в пределах нескольких блоков есть поверхность. */
	private boolean solidBelow(LocalPlayer player) {
		BlockPos feet = player.blockPosition();
		for (int depth = 1; depth <= GROUND_CHECK_DEPTH; depth++) {
			if (!player.level().getBlockState(feet.below(depth)).isAir()) {
				return true;
			}
		}
		return false;
	}

	/** Захватить предмет: слот хотбара напрямую или обмен с резервным слотом. */
	private void takeItem(int slot) {
		long now = net.minecraft.util.Util.getMillis();
		if (slot < 9) {
			itemSlot = slot;
			bagSource = -1;
			phase = Phase.SELECT_SLOT;
			phaseSince = now;
			return;
		}
		// Рюкзак: временный SWAP с резервным слотом хотбара (обратный — в RESTORE).
		// Снимок: дальше идём ТОЛЬКО когда в резервном слоте реально появился
		// именно этот предмет (SWAP мог не дойти/быть отклонён — иначе рискуем
		// использовать чужой предмет из слота 8)
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		bagSource = slot;
		expectedStack = player.getInventory().getItem(slot).copy();
		swapReserve(player);
		itemSlot = RESERVE_SLOT;
		phase = Phase.MOVE_TO_HOTBAR;
		phaseSince = now;
	}

	private void tickMoveToHotbar() {
		// Дальше — только когда в резервном слоте реально появился наш предмет
		// (совпадение предмета и компонентов), либо таймаут с откатом
		ItemStack reserve = Minecraft.getInstance().player.getInventory().getItem(RESERVE_SLOT);
		if (ItemStack.matches(expectedStack, reserve)) {
			phase = Phase.SELECT_SLOT;
			return;
		}
		if (net.minecraft.util.Util.getMillis() - phaseSince >= 1500L) {
			if (notify.isEnabled()) {
				Notifications.warn("AutoBuff", "Зелье не переложилось в хотбар");
			}
			phase = Phase.RESTORE_SLOT;
			restorePlan = new RestorePlan(true);
		}
	}

	// ------------------------------------------------------------------
	// Слот и прицел
	// ------------------------------------------------------------------

	private void tickSelectSlot(LocalPlayer player) {
		previousSlot = player.getInventory().getSelectedSlot();
		if (player.getInventory().getSelectedSlot() != itemSlot) {
			player.getInventory().setSelectedSlot(itemSlot);
		}
		returnYaw = player.getYRot();
		returnPitch = player.getXRot();
		long now = net.minecraft.util.Util.getMillis();

		boolean needAim = !rotation.is("none")
				&& (kind == PotionLogic.Kind.SPLASH
						|| (kind == PotionLogic.Kind.DRINK && mode.is("legit")));
		if (needAim) {
			// Камеру пачкаем только в видимом режиме: в сайленте игрок не заметит
			// ничего, и «возвращать» её некуда
			cameraTouched = rotation.is("visible");
			phase = Phase.AIM_DOWN;
		} else {
			phase = Phase.USE_ITEM; // быстрое питьё — без камеры, как раньше
		}
		phaseSince = now;
	}

	private void tickAimDown(LocalPlayer player) {
		float aimTarget = kind == PotionLogic.Kind.SPLASH ? SPLASH_PITCH : DRINK_PITCH;
		boolean fastThrow = kind == PotionLogic.Kind.SPLASH && throwStyle.is("fast");
		float rate = fastThrow ? 30.0f : 6.0f;
		if (!holdAim(player, player.getYRot(), aimTarget, rate)) {
			return; // слоем владеет более важный модуль — подождём свой тик
		}
		if (Math.abs(RotationManager.pitch() - aimTarget) < 1.0f) {
			phase = kind == PotionLogic.Kind.SPLASH ? Phase.WAIT_ROTATION : Phase.USE_ITEM;
			phaseSince = net.minecraft.util.Util.getMillis();
		}
	}

	/**
	 * Держим прицел слоя: заявка обновляется каждый тик, иначе слой «протухнет»
	 * и перестанет подменять поворот в пакетах.
	 *
	 * @return false, если в этом тике слоем владеет другой (более важный) модуль
	 */
	private boolean holdAim(LocalPlayer player, float wantYaw, float wantPitch, float speed) {
		RotationManager.Mode wanted = rotation.is("visible")
				? RotationManager.Mode.VISIBLE
				: RotationManager.Mode.SILENT;
		return RotationManager.request(this, RotationManager.PRIORITY_BUFF, wanted,
				RotationManager.Movement.NONE, speed, false, wantYaw, wantPitch);
	}

	private void tickWaitRotation(LocalPlayer player) {
		// Сервер должен получить направленный вниз взгляд ДО пакета
		// использования: держим прицел и ждём минимум тик (быстрый) или
		// человеческую паузу (легит)
		holdAim(player, player.getYRot(),
				kind == PotionLogic.Kind.SPLASH ? SPLASH_PITCH : DRINK_PITCH, 30.0f);
		long waitMs = throwStyle.is("legit")
				? DrinkLogic.humanPause(pauseSeed, 200, 480)
				: 60L;
		if (net.minecraft.util.Util.getMillis() - phaseSince >= waitMs) {
			phase = Phase.USE_ITEM;
		}
	}

	// ------------------------------------------------------------------
	// Использование
	// ------------------------------------------------------------------

	private void tickUseItem(Minecraft client, LocalPlayer player) {
		phaseSince = net.minecraft.util.Util.getMillis();
		if (kind == PotionLogic.Kind.SPLASH) {
			// Один вызов, без удержания: снимок стака для подтверждения
			ItemStack held = player.getInventory().getItem(itemSlot);
			snapshotItem = held.getItem();
			snapshotCount = held.getCount();
			// Бросок считается по взгляду: сервер обязан получить «вниз» ДО
			// пакета использования (иначе снаряд летит по старому углу)
			RotationManager.syncBeforeAction(player);
			client.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
			player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		} else {
			sawUsing = false;
			client.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
			KeyOwnership.hold(client, client.options.keyUse, this);
		}
		phase = Phase.WAIT_RESULT;
	}

	private void tickWaitResult(Minecraft client, LocalPlayer player) {
		long now = net.minecraft.util.Util.getMillis();

		if (kind == PotionLogic.Kind.SPLASH) {
			ItemStack held = player.getInventory().getItem(itemSlot);
			boolean changed = held.getItem() != snapshotItem || held.getCount() != snapshotCount;
			long ticks = (now - phaseSince) / 50L;
			switch (SplashResult.evaluate(changed, ticks, SPLASH_TIMEOUT_TICKS)) {
				case CONFIRMED -> {
					if (target == BuffPriority.Target.HEAL) {
						healSettleUntil = now + HEAL_SETTLE_MS; // HP придёт с попаданием снаряда
					}
					// уведомление — единая точка в finishAction (без дублей)
					beginRestore();
				}
				case TIMEOUT -> {
					if (notify.isEnabled()) {
						Notifications.warn("AutoBuff", "Бросок не подтверждён");
					}
					beginRestore();
				}
				case PENDING -> {
					// летит — ждём
				}
			}
			return;
		}

		// Питьевое/яблоко: завершение ТОЛЬКО по переходу isUsingItem(): true → false
		int humanPause = DrinkLogic.humanPause(pauseSeed, 200, 480);
		if (now - phaseSince < humanPause && sawUsing) {
			return;
		}
		boolean usingNow = player.isUsingItem();
		if (usingNow) {
			sawUsing = true;
		}
		long elapsed = now - phaseSince;
		if (DrinkLogic.neverStarted(sawUsing, elapsed, START_TIMEOUT_MS)
				|| DrinkLogic.finished(sawUsing, usingNow, elapsed, DRINK_TIMEOUT_MS)) {
			releaseUseKey();
			beginRestore();
		} else if (!usingNow) {
			// сорвалось (урон, движение) — перезапускаем использование
			client.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
			KeyOwnership.hold(client, client.options.keyUse, this);
		}
	}

	// ------------------------------------------------------------------
	// Восстановление
	// ------------------------------------------------------------------

	private void beginRestore() {
		restorePlan = new RestorePlan(bagSource >= 0);
		phase = Phase.RESTORE_SLOT;
		phaseSince = net.minecraft.util.Util.getMillis();
	}

	private void tickRestoreSlot(LocalPlayer player) {
		Minecraft client = Minecraft.getInstance();
		boolean immediate = restorePlan.isInterrupted();

		if (restorePlan.peek() == RestorePlan.Step.SWAP_BACK) {
			// Обратный SWAP — только со своим инвентарём и закрытым экраном
			boolean free = player.containerMenu == player.inventoryMenu
					&& (client.gui == null || client.gui.screen() == null);
			if (!free) {
				if (net.minecraft.util.Util.getMillis() - phaseSince > 3000L) {
					if (notify.isEnabled()) {
						Notifications.warn("AutoBuff", "Жду закрытия контейнера, чтобы вернуть предмет");
					}
					// SWAP_BACK нельзя пропускать: иначе резервный предмет и зелье
					// навсегда останутся обменянными. Ждём безопасного inventoryMenu.
					phaseSince = net.minecraft.util.Util.getMillis();
				}
				return;
			}
			swapReserve(player);
			restorePlan.advance();
			if (!immediate) {
				return; // по одному действию за тик
			}
		}

		if (restorePlan.peek() == RestorePlan.Step.RESTORE_SLOT) {
			if (previousSlot >= 0 && player.getInventory().getSelectedSlot() != previousSlot) {
				player.getInventory().setSelectedSlot(previousSlot);
			}
			restorePlan.advance();
			if (!immediate) {
				return;
			}
		}

		phase = Phase.RESTORE_ROTATION;
		phaseSince = net.minecraft.util.Util.getMillis();
	}

	private void tickRestoreRotation(LocalPlayer player) {
		// Слой отпускаем всегда: в сайленте камера и не двигалась, а в видимом
		// режиме дальше возвращаем угол руками
		RotationManager.release(this);
		if (!cameraTouched) {
			finishAction();
			return;
		}
		if (RotationManager.active()) {
			// Поворот уже перехватила аура (или ещё один модуль) — лезть в углы
			// двумя руками нельзя: вернём взгляд на тик позже, когда освободится
			return;
		}
		boolean instant = restorePlan.isInterrupted() || mode.is("fast")
				|| (kind == PotionLogic.Kind.SPLASH && throwStyle.is("fast"));
		if (instant) {
			player.setXRot(returnPitch);
			player.setYRot(returnYaw);
			cameraTouched = false;
			finishAction();
			return;
		}
		float pitch = player.getXRot();
		float delta = Math.signum(returnPitch - pitch) * Math.min(8.0f, Math.abs(returnPitch - pitch));
		player.setXRot(pitch + delta);
		if (Math.abs(player.getXRot() - returnPitch) < 8.0f) {
			player.setXRot(returnPitch);
			player.setYRot(returnYaw);
			cameraTouched = false;
			finishAction();
		}
	}

	private void finishAction() {
		long now = net.minecraft.util.Util.getMillis();
		cooldownUntil = now + (kind == PotionLogic.Kind.SPLASH
				? splashCooldown.get() * 50L
				: 250L);
		// Сначала сохраняем итог (resetState() стирает target/kind), уведомление —
		// ровно одно: у броска и питья разные тексты
		BuffPriority.Target done = target;
		PotionLogic.Kind doneKind = kind;
		String doneName = targetName();
		boolean succeeded = done != BuffPriority.Target.NONE;
		resetState();
		target = BuffPriority.Target.NONE;
		if (notify.isEnabled() && succeeded) {
			if (doneKind == PotionLogic.Kind.SPLASH) {
				Notifications.ok("AutoBuff", "Брошено: " + doneName);
			} else {
				Notifications.ok("AutoBuff", "Применено: " + doneName);
			}
		}
		phase = Phase.COOLDOWN;
		cooldownUntil = Math.max(cooldownUntil, now);
	}

	// ------------------------------------------------------------------
	// Помощники: поиск и обмен
	// ------------------------------------------------------------------

	/** SWAP между слотом рюкзака и резервным слотом хотбара (двойной SWAP = исходное). */
	private void swapReserve(LocalPlayer player) {
		Minecraft client = Minecraft.getInstance();
		if (client.gameMode == null || bagSource < 0) {
			return;
		}
		client.gameMode.handleContainerInput(player.inventoryMenu.containerId,
				SlotMath.inventoryToMenuSlot(bagSource), RESERVE_SLOT,
				net.minecraft.world.inventory.ContainerInput.SWAP, player);
	}

	private void restartIdle() {
		resetState();
		cooldownUntil = net.minecraft.util.Util.getMillis() + 250L; // не сканируем вхолостую каждый тик
	}

	private Holder<MobEffect> effectFor(BuffPriority.Target picked) {
		return switch (picked) {
			case HEAL -> MobEffects.INSTANT_HEALTH;
			case FIRE_RESISTANCE -> MobEffects.FIRE_RESISTANCE;
			case STRENGTH -> MobEffects.STRENGTH;
			case SPEED -> MobEffects.SPEED;
			case REGENERATION -> MobEffects.REGENERATION;
			case NIGHT_VISION -> MobEffects.NIGHT_VISION;
			default -> null;
		};
	}

	private String targetName() {
		return switch (target) {
			case HEAL -> "лечение";
			case FIRE_RESISTANCE -> "огнестойкость";
			case STRENGTH -> "сила";
			case SPEED -> "скорость";
			case REGENERATION -> "регенерация";
			case NIGHT_VISION -> "ночное зрение";
			case GOLDEN_APPLE -> "золотое яблоко";
			default -> "эффект";
		};
	}

	/** Какой способ применения доступен для эффекта (с учётом режима), или null. */
	private PotionLogic.Kind potionKindFor(Holder<MobEffect> effect) {
		boolean splash = hasPotion(effect, PotionLogic.Kind.SPLASH);
		boolean drink = hasPotion(effect, PotionLogic.Kind.DRINK);
		return PotionLogic.pick(usage.current().id(), preferSplash.isEnabled(), splash, drink);
	}

	private boolean hasPotion(Holder<MobEffect> effect, PotionLogic.Kind wantedKind) {
		return findPotion(effect, wantedKind) >= 0;
	}

	/**
	 * Ищет зелье нужного вида: слоты строго 0..35 (броня и оффхенд не трогаем).
	 * Возвращает слот хотбара (0..8) или рюкзака (9..35), −1 — нет.
	 */
	private int findPotion(Holder<MobEffect> wanted, PotionLogic.Kind wantedKind) {
		var inventory = Minecraft.getInstance().player.getInventory();
		for (int i = 0; i < SlotMath.INVENTORY_SIZE; i++) {
			if (isBuffPotion(inventory.getItem(i), wanted, wantedKind)) {
				return i;
			}
		}
		return -1;
	}

	private int findGapple() {
		var inventory = Minecraft.getInstance().player.getInventory();
		for (int i = 0; i < SlotMath.INVENTORY_SIZE; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
				return i;
			}
		}
		return -1;
	}

	/** Зелье нужного вида (POTION/SPLASH_POTION) с нужным эффектом и без вредных. */
	private boolean isBuffPotion(ItemStack stack, Holder<MobEffect> wanted, PotionLogic.Kind wantedKind) {
		boolean rightItem = wantedKind == PotionLogic.Kind.SPLASH
				? stack.is(Items.SPLASH_POTION)
				: stack.is(Items.POTION);
		if (!rightItem) {
			return false;
		}
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) {
			return false;
		}
		boolean hasWanted = false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			if (effect.getEffect() == wanted) {
				hasWanted = true;
			}
			if (isBad(effect)) {
				return false;
			}
		}
		return hasWanted;
	}

	/** Вредный эффект: чёрный список + вся категория HARMFUL ванили. */
	private static boolean isBad(MobEffectInstance effect) {
		MobEffect mobEffect = effect.getEffect().value();
		String path = BuiltInRegistries.MOB_EFFECT.getKey(mobEffect) == null
				? "" : BuiltInRegistries.MOB_EFFECT.getKey(mobEffect).getPath();
		return PotionLogic.harmful(path, mobEffect.getCategory() == MobEffectCategory.HARMFUL);
	}

	// ------------------------------------------------------------------
	// Прочее
	// ------------------------------------------------------------------

	/** Отпускаем программно зажатую клавишу — безопасно и для физического нажатия. */
	private void releaseUseKey() {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.options != null) {
			KeyOwnership.releaseHold(client, client.options.keyUse, this);
		}
	}
}

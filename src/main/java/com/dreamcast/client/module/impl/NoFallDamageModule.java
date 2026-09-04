package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * NoFallDamage — в данный момент режим один: «Ватердроп» (MLG-ведро).
 *
 * Что делает: как только падение становится опасным (fallDistance ≥ 3), модуль
 * находит ведро воды, держит его в руке и ставит воду под ноги. Урон обнуляется,
 * игрок падает в воду.
 *
 * «Автоматически убирать воду»: ровно после того, как игрок упал в эту воду
 * (isInWater и fallDistance сброшен) и прошло ещё несколько тиков подтверждения,
 * вода собирается обратно в ведро — тем же ведром, по тому же блоку. Вода НЕ
 * убирается сразу после установки и не убирается, если игрок в неё так и не
 * попал: сначала ждём подтверждённой посадки (или таймаута с чисткой уже
 * стоящей на земле воды).
 *
 * Поиск ведра: вторая рука → хотбар (простое переключение слота) → основной
 * инвентарь (shift-клик в хотбар через контейнерный клик, на следующем тике —
 * переключение). Прежний выбранный слот запоминается и восстанавливается.
 */
public class NoFallDamageModule extends Module {

	public static final String MODE_WATERDROP = "waterdrop";

	private final ModeSetting mode = mode("mode", "Режим", MODE_WATERDROP,
			ModeSetting.option(MODE_WATERDROP, "Ватердроп"));

	private final BooleanSetting autoRemove = bool("auto_remove", "Автоматически убирать воду", true);

	/** Порог падения, с которого начинается урон (блоки). */
	private static final float DAMAGE_FALL_DISTANCE = 3.0F;

	/** Сколько тиков держим воду после подтверждённой посадки, прежде чем собрать. */
	private static final int RETRACT_DELAY = 6;

	/** Общий таймаут операции: пока вода не поставлена/не собрана. */
	private static final int TIMEOUT = 100;

	/** Дальняя точка raycast вниз от ног: примерно длина руки + запас. */
	private static final double PLACE_REACH = 5.0;

	private enum Phase {
		IDLE, SWITCHING, FALLING, PLACING, PLACED, RETRACT
	}

	private Phase phase = Phase.IDLE;
	private int timer;
	/** Сколько тиков осталось до сбора воды после посадки. */
	private int retractCountdown = -1;
	private BlockPos waterPos;
	private InteractionHand bucketHand;
	private int previousSlot = -1;
	private boolean landedInWater;
	/** Тиков до повторной попытки постановки (анти-спам кликов). */
	private int retryDelay;
	/** Наше ли это ведро вылилось (сервер принял клик) — только такую воду собираем. */
	private boolean placedByUs;
	/** Видели ли переход водяного ведра в пустое. */
	private boolean sawBucketEmpty;
	private ClientLevel activeLevel;

	public NoFallDamageModule() {
		super("no_fall_damage", "NoFallDamage", "Ватердроп: ставит воду под себя при опасном падении и убирает её после посадки",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected void onDisable() {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.level == activeLevel) {
			restoreSelection();
		} else {
			previousSlot = -1;
		}
		resetState();
	}

	private void resetState() {
		this.phase = Phase.IDLE;
		this.timer = 0;
		this.retractCountdown = -1;
		this.waterPos = null;
		this.bucketHand = null;
		this.landedInWater = false;
		this.retryDelay = 0;
		this.placedByUs = false;
		this.sawBucketEmpty = false;
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null || client.gameMode == null) {
			resetState();
			previousSlot = -1;
			activeLevel = null;
			return;
		}
		if (activeLevel != client.level) {
			resetState();
			previousSlot = -1;
			activeLevel = client.level;
		}

		// GUI/чужой контейнер: новых действий нет; начатое — безопасный откат
		// (слот восстанавливается всегда — это клиентская операция; клики не шлём)
		boolean guiOrContainer = (client.gui != null && client.gui.screen() != null)
				|| player.containerMenu != player.inventoryMenu;
		if (guiOrContainer) {
			if (this.phase != Phase.IDLE) {
				restoreSelection();
				resetState();
			}
			return;
		}

		switch (this.phase) {
		case IDLE -> tickIdle(client, player);
		case SWITCHING -> tickSwitching(client, player);
		case FALLING -> tickFalling(client, player);
		case PLACING -> tickPlacing(client, player);
		case PLACED -> tickPlaced(client, player);
		case RETRACT -> tickRetract(client, player);
		}
	}

	// ------------------------------------------------------------------
	// Фазы
	// ------------------------------------------------------------------

	private void tickIdle(Minecraft client, LocalPlayer player) {
		if (!mode.is(MODE_WATERDROP)) {
			return;
		}

		// Падение уже опасно: вода, лестница, элитры и полёт пассажиром — не наш случай
		if (!com.dreamcast.client.util.NoFallLogic.dangerousFall(player.fallDistance,
				DAMAGE_FALL_DISTANCE, player.onGround(), player.isInWater(),
				player.onClimbable(), player.isFallFlying(), player.isPassenger())) {
			return;
		}

		// 1) Ведро во второй руке — просто пользуемся ей
		if (player.getItemInHand(InteractionHand.OFF_HAND).getItem() == Items.WATER_BUCKET) {
			this.bucketHand = InteractionHand.OFF_HAND;
			this.phase = Phase.FALLING;
			this.timer = TIMEOUT;
			return;
		}

		// 2) Ведро в хотбаре — переключаемся на слот (запомнив прежний)
		Inventory inventory = player.getInventory();
		int slot = findBucketInHotbar(inventory);
		if (slot >= 0) {
			this.previousSlot = inventory.getSelectedSlot();
			this.bucketHand = InteractionHand.MAIN_HAND;
			inventory.setSelectedSlot(slot);
			this.phase = Phase.FALLING;
			this.timer = TIMEOUT;
			return;
		}

		// 3) Ведро в основном инвентаре — переносим в хотбар и ждём тик
		if (moveBucketFromInventory(client, player)) {
			this.phase = Phase.SWITCHING;
			this.timer = 10;
		}
	}

	private void tickSwitching(Minecraft client, LocalPlayer player) {
		int slot = findBucketInHotbar(player.getInventory());
		if (slot >= 0) {
			this.previousSlot = player.getInventory().getSelectedSlot();
			this.bucketHand = InteractionHand.MAIN_HAND;
			player.getInventory().setSelectedSlot(slot);
			this.phase = Phase.FALLING;
			this.timer = TIMEOUT;
			return;
		}
		if (--this.timer <= 0) {
			resetState();
		}
	}

	private void tickFalling(Minecraft client, LocalPlayer player) {
		// Пока летим: могли мягко приземлиться или уже упасть в чью-то воду
		if (player.onGround() || player.isInWater() || player.fallDistance < 1.0F) {
			restoreSelection();
			resetState();
			return;
		}

		if (--this.timer <= 0) {
			restoreSelection();
			resetState();
			return;
		}

		// Точка предполагаемого приземления: горизонталь сносится скоростью
		// падения (с пределом, чтобы не «закидывать» воду далеко)
		var motion = player.getDeltaMovement();
		double[] landing = com.dreamcast.client.util.MotionMath.landingPoint(
				player.getX(), player.getY(), player.getZ(),
				motion.x, motion.y, motion.z,
				player.getY() - PLACE_REACH, 3.0);

		// Raycast вниз до поверхности: кликнуть надо по ВЕРХНЕЙ ГРАНИ твёрдого
		// блока под точкой приземления, а не по воздуху у текущих ног
		Vec3 from = new Vec3(landing[0], player.getY() + 0.5, landing[2]);
		Vec3 to = new Vec3(landing[0], player.getY() - PLACE_REACH, landing[2]);
		BlockHitResult hit = client.level.clip(new ClipContext(from, to,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return; // поверхности в пределах досягаемости нет — ждём следующий тик
		}

		BlockPos solidPos = hit.getBlockPos();
		BlockPos placePos = solidPos.above();
		if (!client.level.getBlockState(placePos).isAir()
				&& client.level.getFluidState(placePos).isEmpty()) {
			return; // занято твёрдым блоком — лить некуда, ждём
		}
		// (если там уже чья-то вода — просто перейдём в PLACING, он подтвердит её)

		client.gameMode.useItemOn(player, this.bucketHand, hit);
		player.swing(this.bucketHand);

		// В PLACED сразу нельзя: сервер мог отклонить клик — ждём реальную воду.
		// Если в placePos уже стоял ЧУЖОЙ источник, сервер наш клик отклонит
		// (ведро останется полным) — воду тогда не собираем, это не наша
		this.waterPos = placePos;
		this.landedInWater = false;
		this.retractCountdown = -1;
		this.placedByUs = false;
		this.sawBucketEmpty = false;
		this.retryDelay = 3;
		this.phase = Phase.PLACING;
		this.timer = TIMEOUT;
	}

	/** Ставили: подтверждаем, что вода реально появилась; иначе повторяем клик. */
	private void tickPlacing(Minecraft client, LocalPlayer player) {
		if (--this.timer <= 0) {
			// Не вышло: безопасно выйти (падение ещё опасно — IDLE заново запустит)
			restoreSelection();
			resetState();
			return;
		}

		boolean stillFalling = !(player.onGround() || player.isInWater() || player.fallDistance < 1.0F);
		if (heldBucketIsEmpty(player)) {
			this.sawBucketEmpty = true; // сервер принял выливание — вода наша
		}
		switch (com.dreamcast.client.util.NoFallLogic.placingAction(
				isOurWaterStillThere(client), stillFalling, heldBucketIsEmpty(player))) {
			case CONFIRM -> {
				// placedByUs НЕ фиксируем: источник мог появиться раньше, чем
				// обновился стак ведра — итоговое решение примет PLACED по
				// фактическому переходу WATER_BUCKET → BUCKET
				this.phase = Phase.PLACED;
			}
			case ABORT -> {
				restoreSelection();
				resetState();
			}
			case WAIT -> {
				// сервер клик принял (ведро пусто) — даём жидкости синхронизироваться
			}
			case RETRY -> {
				// Повторная попытка: не чаще, чем раз в retryDelay тиков
				if (--this.retryDelay > 0) {
					return;
				}
				this.retryDelay = 3;

				BlockPos solidPos = this.waterPos == null ? null : this.waterPos.below();
				if (solidPos == null) {
					restoreSelection();
					resetState();
					return;
				}
				Vec3 location = new Vec3(solidPos.getX() + 0.5, solidPos.getY() + 1.0, solidPos.getZ() + 0.5);
				BlockHitResult hit = new BlockHitResult(location, Direction.UP, solidPos, false);
				client.gameMode.useItemOn(player, this.bucketHand, hit);
				player.swing(this.bucketHand);
			}
		}
	}

	/** true, если в руке, где было ведро, теперь пустое ведро (воду вылили). */
	private boolean heldBucketIsEmpty(LocalPlayer player) {
		return this.bucketHand != null
				&& player.getItemInHand(this.bucketHand).getItem() == Items.BUCKET;
	}

	private void tickPlaced(Minecraft client, LocalPlayer player) {
		// Гонка подтверждения: вода пришла раньше обновления ведра — продолжаем
		// ждать переход WATER_BUCKET → BUCKET, чтобы получить право на сбор
		if (!this.placedByUs && heldBucketIsEmpty(player)) {
			this.sawBucketEmpty = true;
			this.placedByUs = true;
		}
		if (--this.timer <= 0) {
			// Вода не дождалась посадки: если игрок уже стоит на земле и источник
			// ещё там — приберём за собой (только нашу!), иначе просто выходим
			if (player.onGround() && isOurWaterStillThere(client)
					&& com.dreamcast.client.util.NoFallLogic.collectAllowed(
							this.placedByUs, autoRemove.isEnabled())) {
				startRetract();
			} else {
				restoreSelection();
				resetState();
			}
			return;
		}

		// Подтверждение посадки: игрок в воде и fallDistance сброшен.
		// Только после этого (и ещё после задержки) воду можно собирать
		if (!this.landedInWater && player.isInWater() && player.fallDistance <= 0.01F) {
			this.landedInWater = true;
			this.retractCountdown = RETRACT_DELAY;
		}

		if (this.landedInWater) {
			if (--this.retractCountdown <= 0) {
				if (com.dreamcast.client.util.NoFallLogic.collectAllowed(this.placedByUs, autoRemove.isEnabled())) {
					startRetract();
				} else {
					// Чужую воду не трогаем — просто выходим
					restoreSelection();
					resetState();
				}
			}
			return;
		}

		// Воды под ногами больше нет (сместились/собрали вручную) — выходим тихо
		if (!isOurWaterStillThere(client)) {
			restoreSelection();
			resetState();
		}
	}

	private void tickRetract(Minecraft client, LocalPlayer player) {
		if (com.dreamcast.client.util.NoFallLogic.retractAction(
				isOurWaterStillThere(client), --this.timer <= 0)
				== com.dreamcast.client.util.NoFallLogic.RetractAction.ABORT) {
			restoreSelection();
			resetState();
			return;
		}

		// Рука после установки уже держит пустое ведро — собираем источник.
		// Кликаем по самому источнику: raycast с SOURCE_ONLY даёт честный хит
		Vec3 from = new Vec3(this.waterPos.getX() + 0.5, this.waterPos.getY() + 1.4, this.waterPos.getZ() + 0.5);
		Vec3 to = new Vec3(this.waterPos.getX() + 0.5, this.waterPos.getY() - 0.5, this.waterPos.getZ() + 0.5);
		BlockHitResult hit = client.level.clip(new ClipContext(from, to,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			return; // источник «ушёл» между проверкой и кликом — снимемся на следующем тике
		}
		client.gameMode.useItemOn(player, this.bucketHand, hit);
		player.swing(this.bucketHand);

		restoreSelection();
		resetState();
	}

	private void startRetract() {
		this.phase = Phase.RETRACT;
		this.timer = 40;
	}

	// ------------------------------------------------------------------
	// Ведро и слоты
	// ------------------------------------------------------------------

	private static int findBucketInHotbar(Inventory inventory) {
		for (int slot = 0; slot < 9; slot++) {
			if (inventory.getItem(slot).getItem() == Items.WATER_BUCKET) {
				return slot;
			}
		}
		return -1;
	}

	/** Shift-клик ведра из основного инвентаря — оно переедет в свободный слот хотбара. */
	private static boolean moveBucketFromInventory(Minecraft client, Player player) {
		if (client.gameMode == null) {
			return false;
		}
		Inventory inventory = player.getInventory();
		for (int slot = 9; slot < 36; slot++) {
			if (inventory.getItem(slot).getItem() == Items.WATER_BUCKET) {
				client.gameMode.handleContainerInput(
						player.inventoryMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, player);
				return true;
			}
		}
		return false;
	}

	private boolean isOurWaterStillThere(Minecraft client) {
		return this.waterPos != null && client.level.getFluidState(this.waterPos).isSource();
	}

	private void restoreSelection() {
		if (this.previousSlot >= 0) {
			Minecraft client = Minecraft.getInstance();
			if (client != null && client.player != null) {
				client.player.getInventory().setSelectedSlot(this.previousSlot);
			}
			this.previousSlot = -1;
		}
	}
}

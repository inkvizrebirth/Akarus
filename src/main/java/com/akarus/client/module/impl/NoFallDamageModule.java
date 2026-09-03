package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
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

	private enum Phase {
		IDLE, SWITCHING, FALLING, PLACED, RETRACT
	}

	private Phase phase = Phase.IDLE;
	private int timer;
	/** Сколько тиков осталось до сбора воды после посадки. */
	private int retractCountdown = -1;
	private BlockPos waterPos;
	private InteractionHand bucketHand;
	private int previousSlot = -1;
	private boolean landedInWater;

	public NoFallDamageModule() {
		super("no_fall_damage", "NoFallDamage", "Ватердроп: ставит воду под себя при опасном падении и убирает её после посадки",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected void onDisable() {
		restoreSelection();
		resetState();
	}

	private void resetState() {
		this.phase = Phase.IDLE;
		this.timer = 0;
		this.retractCountdown = -1;
		this.waterPos = null;
		this.bucketHand = null;
		this.landedInWater = false;
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null || client.gameMode == null) {
			resetState();
			return;
		}

		switch (this.phase) {
		case IDLE -> tickIdle(client, player);
		case SWITCHING -> tickSwitching(client, player);
		case FALLING -> tickFalling(client, player);
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
		boolean dangerousFall = player.fallDistance >= DAMAGE_FALL_DISTANCE
				&& !player.onGround()
				&& !player.isInWater()
				&& !player.onClimbable()
				&& !player.isFallFlying()
				&& !player.isPassenger();
		if (!dangerousFall) {
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

		// Блок, в котором сейчас ноги — вода появится прямо под падением
		BlockPos target = BlockPos.containing(player.getX(), player.getY(), player.getZ());
		if (!client.level.getBlockState(target).isAir()) {
			target = target.below();
			if (!client.level.getBlockState(target).isAir()) {
				return;
			}
		}

		Vec3 hitLocation = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
		BlockHitResult hit = new BlockHitResult(hitLocation, Direction.UP, target, false);
		client.gameMode.useItemOn(player, this.bucketHand, hit);
		player.swing(this.bucketHand);

		this.waterPos = target;
		this.landedInWater = false;
		this.retractCountdown = -1;
		this.phase = Phase.PLACED;
		this.timer = TIMEOUT;
	}

	private void tickPlaced(Minecraft client, LocalPlayer player) {
		if (--this.timer <= 0) {
			// Вода не дождалась посадки: если игрок уже стоит на земле и источник
			// ещё там — приберём за собой, иначе просто выходим
			if (player.onGround() && isOurWaterStillThere(client)) {
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
				if (autoRemove.isEnabled()) {
					startRetract();
				} else {
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
		if (!isOurWaterStillThere(client) || --this.timer <= 0) {
			restoreSelection();
			resetState();
			return;
		}

		// Рука после установки уже держит пустое ведро — собираем источник
		Vec3 hitLocation = new Vec3(this.waterPos.getX() + 0.5, this.waterPos.getY() + 1.0, this.waterPos.getZ() + 0.5);
		BlockHitResult hit = new BlockHitResult(hitLocation, Direction.UP, this.waterPos, false);
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

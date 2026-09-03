package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.ModeSetting;
import com.akarus.client.util.Notifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Spider — подъём по стенам.
 *
 * Режим <b>WaterBucket</b>: подойдя к стене и зажав пробел, клиент ставит
 * ведро воды в блок над головой (у стены), вода обтекает игрока и поднимает
 * его; когда игрок оказался выше источника — ведро забирает воду обратно
 * и ставит её выше. Цикл повторяется, пока наверху не появится блок, на
 * который можно встать.
 *
 * Режим <b>Прыжок</b> — простой спайдер: у стены добавляется вертикальная
 * скорость, игрок «залипает» вверх по блокам.
 */
public class SpiderModule extends Module {

	private final ModeSetting mode = mode("mode", "Режим", "water_bucket",
			ModeSetting.option("water_bucket", "WaterBucket"),
			ModeSetting.option("jump", "Прыжок"));

	/** Кулдаун между действиями с ведром (тиков) — пакеты должны успеть. */
	private static final int ACTION_COOLDOWN = 4;
	/** Как часто напоминаем, что ведра нет. */
	private static final int NO_BUCKET_NOTIFY = 60;

	private int cooldown;
	private int noBucketTimer;
	/** Слот, в котором живёт ведро (запоминаем между циклами). */
	private int bucketSlot = -1;
	/** Позиция поставленного источника воды; null — воды сейчас нет. */
	private BlockPos waterSource;

	public SpiderModule() {
		super("spider", "Spider", "Подъём по стенам: водой из ведра или прыжками",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			return;
		}
		if (cooldown > 0) {
			cooldown--;
		}
		if (noBucketTimer > 0) {
			noBucketTimer--;
		}

		if (mode.is("jump")) {
			tickJump(player);
			return;
		}
		tickWaterBucket(client, player);
	}

	// ------------------------------------------------------------------
	// Режим «Прыжок»
	// ------------------------------------------------------------------

	private void tickJump(LocalPlayer player) {
		if (!player.horizontalCollision) {
			return;
		}
		// Небольшой постоянный «подскок»: скорость как у прыжка, но гасимая
		Vec3 motion = player.getDeltaMovement();
		if (motion.y < 0.25) {
			player.setDeltaMovement(motion.x * 0.85, 0.32, motion.z * 0.85);
		}
	}

	// ------------------------------------------------------------------
	// Режим «WaterBucket»
	// ------------------------------------------------------------------

	private void tickWaterBucket(Minecraft client, LocalPlayer player) {
		// Работаем только по зажатому прыжку — как обычный подъём
		if (!client.options.keyJump.isDown() || client.gui.screen() != null) {
			return;
		}

		BlockPos feet = player.blockPosition();
		Direction facing = player.getDirection();
		BlockPos ahead = feet.relative(facing);

		// Вершину видно — выше подниматься не нужно
		int feetY = feet.getY();
		if (standableAt(client, ahead, feetY + 1)) {
			if (waterSource != null) {
				pickWater(client, player, waterSource);
			}
			waterSource = null;
			return;
		}

		// Стена начинается на уровне ног или головы — иначе это не подъём
		if (!solid(client, ahead) && !solid(client, ahead.above())) {
			waterSource = null;
			return;
		}

		// Уже поднимаемся на нашей воде: ждём, пока ноги окажутся выше источника
		if (waterSource != null) {
			boolean sourceAlive = client.level.getFluidState(waterSource).is(FluidTags.WATER)
					&& client.level.getFluidState(waterSource).isSource();
			if (!sourceAlive) {
				waterSource = null; // воду забрали/она утекла — поставим заново
				return;
			}
			if (feetY >= waterSource.getY()) {
				// Забираем воду и сразу ставим выше — продолжаем подъём
				if (pickWater(client, player, waterSource)) {
					waterSource = null;
					placeHigher(client, player, feet);
				}
			}
			return;
		}

		// Воды нет: начинаем цикл — ставим над головой
		placeHigher(client, player, feet);
	}

	/** Ставит воду в колонне игрока на высоте головы+1, кликая по блоку стены. */
	private void placeHigher(Minecraft client, LocalPlayer player, BlockPos feet) {
		if (cooldown > 0) {
			return;
		}
		int slot = findBucketSlot(player, Items.WATER_BUCKET);
		if (slot < 0) {
			if (noBucketTimer <= 0) {
				Notifications.warn("Spider", "В хотбаре нет ведра воды");
				noBucketTimer = NO_BUCKET_NOTIFY;
			}
			return;
		}

		Direction facing = player.getDirection();
		Direction toPlayer = facing.getOpposite();

		// Блок стены на уровне головы+1: у его грани, обращённой к игроку,
		// и появится вода — ровно над головой, в колонне игрока
		BlockPos wallBlock = feet.relative(facing).above(2);
		if (!solid(client, wallBlock)) {
			// Стена ниже: клик по её вершине сверху — вода встанет у стены
			wallBlock = topOfWall(client, feet.relative(facing), feet);
			if (wallBlock == null) {
				return;
			}
			toPlayer = Direction.UP;
		}
		if (!canPlaceAt(client, feet.above(2))) {
			return; // над головой что-то стоит — воду некуда ставить
		}

		Vec3 hitLocation = Vec3.atCenterOf(wallBlock).add(
				Vec3.atLowerCornerOf(toPlayer.getUnitVec3i()).scale(0.5));
		BlockHitResult hit = new BlockHitResult(hitLocation, toPlayer, wallBlock, false);

		selectSlot(player, slot);
		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		bucketSlot = slot;
		waterSource = feet.above(2);
		cooldown = ACTION_COOLDOWN;
	}

	/** Забирает поставленную воду пустым ведром (после постановки оно и есть пустое). */
	private boolean pickWater(Minecraft client, LocalPlayer player, BlockPos source) {
		if (cooldown > 0) {
			return false;
		}
		int slot = bucketSlot >= 0 && isBucket(player.getInventory().getItem(bucketSlot), Items.BUCKET)
				? bucketSlot
				: findBucketSlot(player, Items.BUCKET);
		if (slot < 0) {
			return false;
		}
		Vec3 hitLocation = Vec3.atCenterOf(source);
		BlockHitResult hit = new BlockHitResult(hitLocation, Direction.UP, source, false);

		selectSlot(player, slot);
		client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		bucketSlot = slot;
		cooldown = ACTION_COOLDOWN;
		return true;
	}

	// ------------------------------------------------------------------
	// Помощники
	// ------------------------------------------------------------------

	private static boolean isBucket(ItemStack stack, Item target) {
		return stack.is(target);
	}

	private int findBucketSlot(LocalPlayer player, Item item) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).is(item)) {
				return i;
			}
		}
		return -1;
	}

	private void selectSlot(LocalPlayer player, int slot) {
		if (player.getInventory().getSelectedSlot() != slot) {
			player.getInventory().setSelectedSlot(slot);
		}
	}

	private static boolean solid(Minecraft client, BlockPos pos) {
		return client.level.getBlockState(pos).isSolidRender();
	}

	private static boolean canPlaceAt(Minecraft client, BlockPos pos) {
		return client.level.getBlockState(pos).canBeReplaced();
	}

	/** Вершина стены: первый непустой-снизу блок; null — стена кончилась далеко. */
	private static BlockPos topOfWall(Minecraft client, BlockPos column, BlockPos feet) {
		for (int y = feet.getY() + 1; y <= feet.getY() + 12; y++) {
			BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
			if (solid(client, pos)) {
				continue;
			}
			return pos.above(-1); // верхний сплошной
		}
		return null;
	}

	/** Есть ли на колонне блока, на котором можно стоять (на высоте not выше прыжка)? */
	private static boolean standableAt(Minecraft client, BlockPos column, int y) {
		BlockPos stand = new BlockPos(column.getX(), y, column.getZ());
		BlockPos above1 = stand.above();
		BlockPos above2 = stand.above(2);
		return solid(client, stand) && !solid(client, above1) && !solid(client, above2);
	}
}

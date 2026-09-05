package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BlockListSetting;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.KeyOwnership;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.rotation.RotationManager;
import com.dreamcast.client.rotation.RotationMath;
import com.dreamcast.client.util.ScaffoldLogic;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Scaffold — автоматическая подстройка блоков под ноги.
 *
 * <p>Режимы:</p>
 * <ul>
 *   <li><b>Normal</b> — блок ставится под игрока при движении (обычный мост);</li>
 *   <li><b>Legit</b> — перед установкой клиент плавно наводится на грань
 *       (видимой или silent-ротацией) и кликает только «прицелившись»;</li>
 *   <li><b>Telly</b> — мост прыжками: на краю совершается ванильный прыжок
 *       вперёд, во время подъёма блоки НЕ ставятся, после апекса — установка
 *       под траекторию падения. Скорость игрока не трогаем вовсе
 *       (никаких deltaMovement.y из модуля).</li>
 * </ul>
 *
 * <p>Инварианты Telly: прыжок ровно один на край и только с земли; апекс —
 * переход вертикальной скорости из «+» в «0/−»; не больше одной установки за
 * тик; каждая установка ждёт подтверждения мира с ограниченным retry;
 * при выключении/смерти/смене мира/GUI/чужом контейнере возвращаются слот,
 * ротация и физическое состояние клавиш.</p>
 */
public class ScaffoldModule extends Module {

	/** Фазы цикла Telly. */
	private enum Phase {
		IDLE, RUNNING, EDGE_DETECTED, JUMP, ASCENDING, APEX, DESCENDING, PLACE, LANDING, COOLDOWN
	}

	private static final int OFFHAND = -2;
	/** Тиков ждём подтверждения установки, прежде чем считать её отклонённой. */
	private static final int PENDING_TIMEOUT = 4;
	/** Максимум повторов одной установки (без спама пакетами). */
	private static final int MAX_RETRIES = 3;
	/** Антиспам уведомления «нет блоков» (тиков). */
	private static final int NO_BLOCK_NOTIFY = 100;
	/** Допуск «навелись ли мы на грань», градусы. */
	private static final float AIM_TOLERANCE = 3.0F;

	// ---- настройки ----

	private final ModeSetting mode = mode("mode", "Режим", "normal",
			ModeSetting.option("normal", "Normal"),
			ModeSetting.option("legit", "Legit"),
			ModeSetting.option("telly", "Telly"));
	private final BooleanSetting autoJump = bool("auto_jump", "Auto Jump", true);
	private final BooleanSetting autoForward = bool("auto_forward", "Auto Forward", false);
	private final IntSetting edgeDistance = intSetting("edge_distance", "Edge Distance", 1, 0, 3);
	private final BooleanSetting keepY = bool("keep_y", "Keep Y", false);
	private final IntSetting placeDelay = intSetting("place_delay", "Place Delay", 2, 0, 10);
	private final IntSetting blocksPerJump = intSetting("blocks_per_jump", "Blocks Per Jump", 2, 1, 8);
	private final ModeSetting startPlacing = mode("start_placing", "Start Placing", "apex",
			ModeSetting.option("apex", "Apex"),
			ModeSetting.option("falling", "Falling"),
			ModeSetting.option("custom", "Custom Delay"));
	private final IntSetting customDelay = intSetting("custom_delay", "Custom Delay (мс)", 300, 0, 1000);
	private final IntSetting minFallSpeed = intSetting("min_fall_speed", "Minimum Fall Speed (0.1 б/тик)", 2, 0, 10);
	private final IntSetting predictionTicks = intSetting("prediction_ticks", "Prediction Ticks", 2, 1, 3);
	private final IntSetting expand = intSetting("expand", "Expand", 0, 0, 2);
	private final ModeSetting rotation = mode("rotation", "Rotation", "silent",
			ModeSetting.option("visible", "Visible"),
			ModeSetting.option("silent", "Silent"),
			ModeSetting.option("none", "None"));
	private final IntSetting rotationSpeed = intSetting("rotation_speed", "Rotation Speed", 10, 1, 20);
	private final BooleanSetting rayTrace = bool("ray_trace", "RayTrace", true);
	private final ModeSetting swing = mode("swing", "Swing", "client",
			ModeSetting.option("client", "Client"),
			ModeSetting.option("packet", "Packet"),
			ModeSetting.option("none", "None"));
	private final ModeSetting sprint = mode("sprint", "Sprint", "smart",
			ModeSetting.option("keep", "Keep"),
			ModeSetting.option("disable", "Disable"),
			ModeSetting.option("smart", "Smart"));
	private final BooleanSetting safeWalk = bool("safe_walk", "SafeWalk", true);
	private final BooleanSetting tower = bool("tower", "Tower", false);
	private final IntSetting towerDelay = intSetting("tower_delay", "Tower Delay", 2, 0, 10);
	private final ModeSetting hand = mode("hand", "Hand", "main",
			ModeSetting.option("main", "Main"),
			ModeSetting.option("offhand", "Offhand"),
			ModeSetting.option("auto", "Auto"));
	private final ModeSetting slotSwitch = mode("slot_switch", "Slot Switch", "silent",
			ModeSetting.option("visible", "Visible"),
			ModeSetting.option("silent", "Silent"));
	private final BooleanSetting restoreSlot = bool("restore_slot", "Restore Slot", true);
	private final BlockListSetting whitelist = new BlockListSetting("whitelist", "Whitelist (только эти)");
	private final BlockListSetting blacklist = new BlockListSetting("blacklist", "Blacklist (кроме этих)");
	private final BooleanSetting stopWithoutBlocks = bool("stop_without_blocks", "Stop Without Blocks", false);
	private final BooleanSetting pauseInGui = bool("pause_gui", "Pause In GUI", true);
	private final BooleanSetting pauseWhileEating = bool("pause_eating", "Pause While Eating", true);
	private final BooleanSetting renderPreview = bool("render_preview", "Render Placement Preview", true);

	// ---- состояние ----

	private Phase phase = Phase.IDLE;
	/** Мир текущего цикла: сменился — полный сброс. */
	private ClientLevel activeLevel;
	/** Y ног на момент включения — для Keep Y. */
	private double startFeetY;
	/** Прыжок на этот край уже сделан — второго не будет. */
	private boolean jumpedThisEdge;
	/** Прошлый тик летели вверх — для фиксации апекса. */
	private boolean wasAscending;
	/** Мс старта прыжка — для custom-задержки начала установки. */
	private long jumpStartMs;
	/** Апекс пройден. */
	private boolean apexPassed;
	/** Блоков поставлено с начала текущего прыжка. */
	private int placedThisJump;
	/** Кулдаун между установками (тиков). */
	private int placeCooldown;
	/** Кулдаун Tower. */
	private int towerCooldown;
	/** Ожидаем подтверждения установки в этой позиции. */
	private BlockPos pendingPos;
	private int pendingTicks;
	private int pendingRetries;
	/** Наша последняя цель — для превью. */
	private BlockPos lastTarget;
	/** Слот до наших видимых переключений. */
	private int previousSlot = -1;
	/** Делали видимую ротацию — вернуть угол. */
	private boolean rotatedVisibly;
	private float prevYaw;
	private float prevPitch;
	/** Клавиши, которые зажали мы. */
	private final List<KeyMapping> ownedKeys = new ArrayList<>();
	/** Антиспам уведомления «нет блоков». */
	private int noBlockNotifyCooldown;

	public ScaffoldModule() {
		super("scaffold", "Scaffold", "Мосты и башни: Normal, Legit и прыжковый Telly",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
		// списки блоков не имеют своего хелпера — добавляем вручную,
		// иначе они не появятся в ClickGUI и в конфиге
		addSetting(whitelist);
		addSetting(blacklist);
	}

	// =================================================================
	// Жизненный цикл
	// =================================================================

	@Override
	protected void onEnable() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		startFeetY = player != null ? player.getY() : 0;
		activeLevel = player != null && client.level != null ? client.level : null;
	}

	@Override
	protected void onDisable() {
		RotationManager.release(this);
		rollback(Minecraft.getInstance());
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (client == null || player == null || client.level == null || client.gameMode == null) {
			rollback(client);
			return;
		}
		// смерть/выход — полный откат
		if (!player.isAlive()) {
			rollback(client);
			return;
		}
		// смена мира — полный сброс цикла
		if (client.level != activeLevel) {
			rollback(client);
			activeLevel = client.level;
			return;
		}
		// чужой контейнер (сундук посреди операции) — стоп всегда
		if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
			pauseWithRollback(client, player);
			return;
		}
		// экран — стоп только по настройке
		if (client.gui != null && client.gui.screen() != null && pauseInGui.isEnabled()) {
			pauseWithRollback(client, player);
			return;
		}
		// еда/зелье в руках — стоп по настройке
		if (pauseWhileEating.isEnabled() && player.isUsingItem()) {
			pauseWithRollback(client, player);
			return;
		}

		tickCooldowns();

		if (mode.is("telly")) {
			tickTelly(client, player);
		} else if (mode.is("legit")) {
			tickLegit(client, player);
		} else {
			tickNormal(client, player);
		}
	}

	private void tickCooldowns() {
		if (placeCooldown > 0) {
			placeCooldown--;
		}
		if (towerCooldown > 0) {
			towerCooldown--;
		}
		if (noBlockNotifyCooldown > 0) {
			noBlockNotifyCooldown--;
		}
	}

	// =================================================================
	// Общий гвард блоков
	// =================================================================

	/** Слот с годным блоком: 0–8 хотбар, {@link #OFFHAND} — оффхенд, −1 — нет. */
	private int findBlockSlot(LocalPlayer player) {
		int selected = player.getInventory().getSelectedSlot();
		if (!hand.is("offhand") && isPlaceable(player.getMainHandItem())) {
			return selected;
		}
		if (!hand.is("main") && isPlaceable(player.getOffhandItem())) {
			return OFFHAND;
		}
		if (hand.is("offhand")) {
			return -1;
		}
		for (int slot = 0; slot < 9; slot++) {
			if (isPlaceable(player.getInventory().getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/** Блок можно ставить нами: полный куб, не TNT/сыпучий/интерактивный, списки разрешают. */
	private boolean isPlaceable(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		String blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).getPath();
		if (whitelist.count() > 0) {
			if (!whitelist.isSelected(blockId)) {
				return false;
			}
		} else if (blacklist.isSelected(blockId)) {
			return false;
		}
		// запрет TNT, сыпучих (песок/гравий/бетонный порошок) и интерактивных
		// (EntityBlock: сундуки/печки/двери/шалкеры и т.п.), неполные блоки
		// отсекаются требованием полного куба коллизии (слэбы/панели/факелы)
		if (blockItem.getBlock() instanceof TntBlock
				|| blockItem.getBlock() instanceof FallingBlock
				|| blockItem.getBlock() instanceof EntityBlock) {
			return false;
		}
		return blockItem.getBlock().defaultBlockState()
				.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	/** Нет блоков: уведомление с антиспамом, опционально — выключение модуля. */
	private boolean outOfBlocks(Minecraft client, int slot) {
		if (slot != -1) {
			return false;
		}
		if (noBlockNotifyCooldown == 0) {
			Notifications.warn("Scaffold", "Нет блоков для строительства");
			noBlockNotifyCooldown = NO_BLOCK_NOTIFY;
		}
		if (stopWithoutBlocks.isEnabled()) {
			Notifications.warn("Scaffold", "Блоки закончились — выключение");
			rollback(client);
			setEnabled(false);
			return true;
		}
		// блоки кончились посреди прыжка Telly: без stop — просто ждём появления
		return false;
	}

	// =================================================================
	// Normal
	// =================================================================

	private void tickNormal(Minecraft client, LocalPlayer player) {
		// AutoJump — импульс на один тик, а не вечное удержание пробела.
		releaseJump(client);
		int slot = findBlockSlot(player);
		if (outOfBlocks(client, slot)) {
			return;
		}
		handleSprint(player);
		BlockPos target = findTargetBelow(client, player);
		if (target != null) {
			tryPlace(client, player, target, slot);
		} else {
			lastTarget = null;
			tickTower(client, player, slot);
		}
		// авто-прыжок через разрывы
		if (autoJump.isEnabled() && player.onGround()
				&& edgeAhead(client, player, Math.max(1, edgeDistance.get()))) {
			pressJump(client);
		}
		applySafeWalk(client, player);
	}

	// =================================================================
	// Legit: сначала наводимся на грань, потом ставим
	// =================================================================

	private void tickLegit(Minecraft client, LocalPlayer player) {
		// Отпускаем импульс прошлого тика до проверки следующего края.
		releaseJump(client);
		int slot = findBlockSlot(player);
		if (outOfBlocks(client, slot)) {
			return;
		}
		handleSprint(player);
		BlockPos target = findTargetBelow(client, player);
		if (target == null) {
			lastTarget = null;
			tickTower(client, player, slot);
			applySafeWalk(client, player);
			return;
		}
		lastTarget = target;
		Vec3 hitPoint = hitPointFor(client.level, target);
		if (hitPoint == null) {
			applySafeWalk(client, player);
			return;
		}
		// плавно наводимся; кликаем только когда «прицелились»
		boolean aimed = rotateTowards(client, player, hitPoint);
		if (aimed) {
			tryPlace(client, player, target, slot);
		}
		if (autoJump.isEnabled() && player.onGround()
				&& edgeAhead(client, player, Math.max(1, edgeDistance.get()))) {
			pressJump(client);
		}
		applySafeWalk(client, player);
	}

	// =================================================================
	// Telly: конечный автомат по ТЗ
	// =================================================================

	private void tickTelly(Minecraft client, LocalPlayer player) {
		int slot = findBlockSlot(player);
		double vy = player.getDeltaMovement().y;

		switch (phase) {
			case IDLE, RUNNING, EDGE_DETECTED -> onGroundPhase(client, player, slot);
			case JUMP -> {
				// клавишу уже нажали — отпускаем, дальше физика ванили
				releaseJump(client);
				if (ScaffoldLogic.ascending(vy)) {
					phase = Phase.ASCENDING;
					wasAscending = true;
				} else {
					phase = Phase.LANDING;
				}
			}
			case ASCENDING -> {
				// подъём: блоки НЕ ставим ни при каких настройках
				forwardOwnership(client, autoForward.isEnabled());
				if (ScaffoldLogic.apex(wasAscending, vy)) {
					phase = Phase.APEX;
					apexPassed = true;
					wasAscending = false;
				}
			}
			case APEX, DESCENDING, PLACE -> {
				if (phase != Phase.PLACE && vy < 0) {
					phase = Phase.DESCENDING;
				}
				forwardOwnership(client, autoForward.isEnabled());
				if (player.onGround()) {
					phase = Phase.LANDING;
				} else if (ScaffoldLogic.placementAllowed(startPlacing.current().id(),
						apexPassed, vy, minFallSpeed.get(), customDelay.get(),
						System.currentTimeMillis() - jumpStartMs)
						&& !outOfBlocks(client, slot)
						&& placedThisJump < blocksPerJump.get()) {
					BlockPos target = findTargetUnderTrajectory(client, player);
					if (target != null) {
						phase = Phase.PLACE;
						if (tryPlace(client, player, target, slot)) {
							placedThisJump++;
						}
						phase = Phase.DESCENDING;
					}
				}
			}
			case LANDING -> {
				if (player.onGround()) {
					phase = Phase.COOLDOWN;
					forwardOwnership(client, false);
					jumpedThisEdge = false;
					apexPassed = false;
					wasAscending = false;
					placedThisJump = 0;
					pendingRetries = 0;
				} else {
					phase = Phase.DESCENDING;
				}
			}
			case COOLDOWN -> {
				if (placeCooldown == 0) {
					phase = Phase.RUNNING;
				}
			}
			default -> phase = Phase.RUNNING;
		}
		applySafeWalk(client, player);
	}

	/** Наземная часть Telly: ищем край, прыгаем ровно один раз, мостим по земле. */
	private void onGroundPhase(Minecraft client, LocalPlayer player, int slot) {
		phase = Phase.RUNNING;
		if (!player.onGround()) {
			// сошли с края без прыжка — падаем; установки по правилам фазы
			phase = Phase.DESCENDING;
			apexPassed = true; // прыжка не было, апекс не нужен
			return;
		}
		if (outOfBlocks(client, slot)) {
			return;
		}
		handleSprint(player);
		if (edgeAhead(client, player, Math.max(1, edgeDistance.get()))) {
			phase = Phase.EDGE_DETECTED;
			if (ScaffoldLogic.canJump(player.onGround(), autoJump.isEnabled(), jumpedThisEdge)) {
				phase = Phase.JUMP;
				jumpedThisEdge = true;
				jumpStartMs = System.currentTimeMillis();
				apexPassed = false;
				placedThisJump = 0;
				wasAscending = false;
				// ванильный прыжок: только клавиша, deltaMovement.y не трогаем
				forwardOwnership(client, autoForward.isEnabled());
				pressJump(client);
			}
			return;
		}
		BlockPos target = findTargetBelow(client, player);
		if (target != null) {
			tryPlace(client, player, target, slot);
		} else {
			tickTower(client, player, slot);
		}
	}

	// =================================================================
	// Поиск цели
	// =================================================================

	/** Блок под ногами (или под прогнозом при Expand) для Normal/Legit/Tower. */
	private BlockPos findTargetBelow(Minecraft client, LocalPlayer player) {
		int baseY = keepY.isEnabled()
				? (int) Math.floor(startFeetY)
				: (int) Math.floor(player.getY() - 0.05);
		Vec3 motion = player.getDeltaMovement();
		Vec3 dir = new Vec3(motion.x, 0, motion.z);
		if (dir.lengthSqr() < 1.0e-4) {
			dir = Vec3.directionFromRotation(0, player.getYRot());
		} else {
			dir = dir.normalize();
		}
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(BlockPos.containing(player.getX(), baseY, player.getZ()));
		for (int step = 1; step <= expand.get(); step++) {
			candidates.add(BlockPos.containing(
					player.getX() + dir.x * step, baseY, player.getZ() + dir.z * step));
		}
		// рядом с целевой позицией — прогноз ног на predictionTicks
		double[] feetPredict = ScaffoldLogic.predictFeet(player.getX(), player.getY(), player.getZ(),
				motion.x, motion.y, motion.z, predictionTicks.get());
		candidates.add(BlockPos.containing(feetPredict[0], baseY, feetPredict[2]));
		for (BlockPos candidate : candidates) {
			if (isValidTarget(client, player, candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** Воздух под траекторией падения + соседний твёрдый блок (Telly). */
	private BlockPos findTargetUnderTrajectory(Minecraft client, LocalPlayer player) {
		Vec3 motion = player.getDeltaMovement();
		int ticks = predictionTicks.get();
		double[] predicted = ScaffoldLogic.predictFeet(player.getX(), player.getY(), player.getZ(),
				motion.x, motion.y, motion.z, ticks);
		// ищем воздух по прогнозу ног на 1–3 тика вперёд
		for (int step = 1; step <= 3; step++) {
			double factor = step / (double) Math.max(1, ticks);
			int x = (int) Math.floor(player.getX() + (predicted[0] - player.getX()) * factor);
			int z = (int) Math.floor(player.getZ() + (predicted[2] - player.getZ()) * factor);
			int y = (int) Math.floor(predicted[1]);
			BlockPos candidate = new BlockPos(x, y, z);
			if (isValidTarget(client, player, candidate)) {
				return candidate;
			}
			BlockPos lower = candidate.below();
			if (isValidTarget(client, player, lower)) {
				return lower;
			}
		}
		return null;
	}

	/** Цель валидна: воздух/заменяема, без жидкости, в границах мира, есть грань для клика. */
	private boolean isValidTarget(Minecraft client, LocalPlayer player, BlockPos pos) {
		BlockState state = client.level.getBlockState(pos);
		if (!state.canBeReplaced()) {
			return false;
		}
		if (!state.getFluidState().isEmpty()) {
			return false;
		}
		if (!client.level.getWorldBorder().isWithinBounds(pos)) {
			return false;
		}
		return faceFor(client.level, pos) != null;
	}

	/** Грань соседа для клика: сосед с полной коллизией (надёжная опора). */
	private record Face(BlockPos neighbor, Direction direction, Vec3 hitPoint) {
	}

	private Face faceFor(ClientLevel level, BlockPos target) {
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = target.relative(direction);
			BlockState neighborState = level.getBlockState(neighbor);
			// полный куб: не полублок/снег/жидкость (supportUnsafe-семантика)
			if (!neighborState.isCollisionShapeFullBlock(level, neighbor)) {
				continue;
			}
			// neighbor лежит в направлении direction ОТ target. Кликать нужно по
			// обращённой К target грани соседа — то есть по opposite. Прежний код
			// указывал внешнюю грань и сервер пытался ставить блок через одну клетку.
			Direction clickFace = direction.getOpposite();
			Vec3 hit = Vec3.atCenterOf(neighbor).add(
					clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
			return new Face(neighbor, clickFace, hit);
		}
		return null;
	}

	private Vec3 hitPointFor(ClientLevel level, BlockPos target) {
		Face face = faceFor(level, target);
		return face == null ? null : face.hitPoint();
	}

	// =================================================================
	// Край и опора: по collision shape под будущим AABB ног
	// =================================================================

	/** Есть ли опора под AABB ног через ticks вперёд. */
	private boolean hasSupportAhead(Minecraft client, LocalPlayer player, int ticks) {
		Vec3 motion = player.getDeltaMovement();
		double[] feet = ScaffoldLogic.predictFeet(player.getX(), player.getY(), player.getZ(),
				motion.x, motion.y, motion.z, ticks);
		double half = player.getBbWidth() / 2.0;
		int y = (int) Math.floor(feet[1] - 0.05);
		int minX = (int) Math.floor(feet[0] - half);
		int maxX = (int) Math.floor(feet[0] + half);
		int minZ = (int) Math.floor(feet[2] - half);
		int maxZ = (int) Math.floor(feet[2] + half);
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				BlockPos pos = new BlockPos(x, y, z);
				if (!client.level.getBlockState(pos)
						.getCollisionShape(client.level, pos).isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Край: сейчас опора есть, через ticks — уже нет (по collision shape, не isAir). */
	private boolean edgeAhead(Minecraft client, LocalPlayer player, int ticks) {
		if (!player.onGround()) {
			return false;
		}
		return !hasSupportAhead(client, player, ticks);
	}

	// =================================================================
	// Установка: все проверки, одна за тик, подтверждение + retry
	// =================================================================

	private boolean tryPlace(Minecraft client, LocalPlayer player, BlockPos target, int slot) {
		// ждём подтверждения прошлой установки — новой в этом тике не будет
		if (pendingPos != null) {
			BlockState now = client.level.getBlockState(pendingPos);
			if (!now.canBeReplaced()) {
				// мир подтвердил: блок появился
				pendingPos = null;
				pendingTicks = 0;
				pendingRetries = 0;
				placeCooldown = placeDelay.get();
			} else if (++pendingTicks > PENDING_TIMEOUT) {
				// сервер отклонил установку — бюджет повторов ограничен
				pendingPos = null;
				pendingTicks = 0;
				pendingRetries++;
				if (pendingRetries > MAX_RETRIES) {
					pendingRetries = 0;
					placeCooldown = placeDelay.get();
					return false; // сдаёмся без спама, возьмём новую цель
				}
			} else {
				return false;
			}
		}
		if (placeCooldown > 0) {
			return false;
		}
		lastTarget = target;
		Face face = faceFor(client.level, target);
		if (face == null) {
			return false;
		}
		Vec3 eyes = player.getEyePosition();
		double reach = player.blockInteractionRange();
		if (eyes.distanceToSqr(face.hitPoint()) > reach * reach) {
			return false;
		}
		// raytrace: между глазами и гранью не должно быть чужого блока
		if (rayTrace.isEnabled() && blockBlocksRay(client, player, face)) {
			return false;
		}
		if (!rotation.is("none")) {
			rotateTowards(client, player, face.hitPoint());
		}
		BlockHitResult hit = new BlockHitResult(face.hitPoint(), face.direction(), face.neighbor(), false);
		// рука/слот: visible — обычное переключение с восстановлением по Restore Slot,
		// silent — короткая аренда слота на время useItemOn (клиентский выбор
		// возвращается в тот же тик, сервер видит: выбрать слот → клик → вернуть)
		boolean borrowed = false;
		int heldBefore = -1;
		InteractionHand useHand = resolveHand(player);
		if (useHand == null) {
			if (slot < 0 || slot > 8) {
				return false;
			}
			heldBefore = player.getInventory().getSelectedSlot();
			if (heldBefore == slot) {
				useHand = InteractionHand.MAIN_HAND;
			} else if (slotSwitch.is("visible")) {
				if (previousSlot < 0) {
					previousSlot = heldBefore;
				}
				player.getInventory().setSelectedSlot(slot);
				useHand = InteractionHand.MAIN_HAND;
			} else {
				player.getInventory().setSelectedSlot(slot);
				borrowed = true;
				useHand = InteractionHand.MAIN_HAND;
			}
		}
		// Сервер обязан увидеть поворот на грани ДО пакета установки
		syncRotation(player);
		client.gameMode.useItemOn(player, useHand, hit);
		swingHand(client, player, useHand);
		if (borrowed) {
			// вернуть клиентский слот; пакет возврата уйдёт сам через
			// ensureHasSentCarriedItem в следующем gameMode.tick()
			player.getInventory().setSelectedSlot(heldBefore);
		}
		// ждём подтверждения мира (ставится блок или нет)
		pendingPos = target.immutable();
		pendingTicks = 0;
		return true;
	}

	/** Рука по настройке: main/offhand/auto, без переключения слотов. */
	private InteractionHand resolveHand(LocalPlayer player) {
		boolean mainPlaceable = isPlaceable(player.getMainHandItem());
		boolean offPlaceable = isPlaceable(player.getOffhandItem());
		if (hand.is("offhand")) {
			return offPlaceable ? InteractionHand.OFF_HAND : null;
		}
		if (mainPlaceable) {
			return InteractionHand.MAIN_HAND;
		}
		if (offPlaceable && hand.is("auto")) {
			return InteractionHand.OFF_HAND;
		}
		return null;
	}

	/** Не даёт лучу «сквозь стену»: клик легитимно видит свою грань. */
	private boolean blockBlocksRay(Minecraft client, LocalPlayer player, Face face) {
		Vec3 eyes = player.getEyePosition();
		BlockHitResult clip = client.level.clip(new ClipContext(
				eyes, face.hitPoint(),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return !clip.getBlockPos().equals(face.neighbor());
	}

	// =================================================================
	// Ротация: visible/silent/none + скорость
	// =================================================================

	/**
	 * Поворот к грани блока — через общий слой {@link RotationManager}.
	 *
	 * <p>Раньше «silent» здесь сам слал пакет поворота, каждый тик отсчитывая шаг
	 * от взгляда игрока: камера не менялась, значит и «шаг» каждый тик начинался
	 * заново — доводок никогда не доходил до цели (конвергенции не было). Теперь
	 * состояние прицела живёт в слое, а модуль только говорит, куда он хочет.</p>
	 *
	 * @return true, когда прицел на грани (для Legit-тайминга установки)
	 */
	private boolean rotateTowards(Minecraft client, LocalPlayer player, Vec3 point) {
		Vec3 eye = player.getEyePosition();
		float targetYaw = RotationMath.yawTo(eye.x, eye.z, point.x, point.z);
		float targetPitch = RotationMath.pitchTo(eye.x, eye.y, eye.z, point.x, point.y, point.z);

		RotationManager.Mode wanted = switch (rotation.current().id()) {
			case "none" -> RotationManager.Mode.NONE;
			case "silent" -> RotationManager.Mode.SILENT;
			default -> RotationManager.Mode.VISIBLE;
		};
		if (!RotationManager.request(this, RotationManager.PRIORITY_BUILD, wanted,
				RotationManager.Movement.NONE, rotationSpeed.get(), false, targetYaw, targetPitch)) {
			// Слоем владеет более важный модуль (аура) — в этом тике не наводимся
			return false;
		}

		if (wanted == RotationManager.Mode.VISIBLE) {
			if (!rotatedVisibly) {
				rotatedVisibly = true;
				prevYaw = player.getYRot();
				prevPitch = player.getXRot();
			}
		}

		if (wanted == RotationManager.Mode.NONE) {
			return RotationMath.aimed(player.getYRot(), player.getXRot(),
					targetYaw, targetPitch, AIM_TOLERANCE);
		}
		return RotationManager.aimed(AIM_TOLERANCE);
	}

	/**
	 * Перед самой установкой: убеждаемся, что сервер видит наш прицел на грани
	 * (иначе «placed block outside view» — типичный флаг на silent-ротациях).
	 */
	private void syncRotation(LocalPlayer player) {
		if (!rotation.is("none")) {
			RotationManager.syncBeforeAction(player);
		}
	}

	private static float wrapDegrees(float degrees) {
		return RotationMath.wrap(degrees);
	}

	/** Возврат видимой ротации при паузе/выключении. */
	private void restoreRotation(LocalPlayer player) {
		if (rotatedVisibly) {
			player.setYRot(prevYaw);
			player.setXRot(prevPitch);
			rotatedVisibly = false;
		}
	}

	// =================================================================
	// Свинг
	// =================================================================

	private void swingHand(Minecraft client, LocalPlayer player, InteractionHand useHand) {
		if (swing.is("client")) {
			player.swing(useHand);
		} else if (swing.is("packet") && client.getConnection() != null) {
			client.getConnection().send(new ServerboundSwingPacket(useHand));
		}
	}

	// =================================================================
	// Клавиши: прыжок/вперёд через владение, SafeWalk, спринт
	// =================================================================

	private void pressJump(Minecraft client) {
		own(client.options.keyJump);
		KeyOwnership.hold(client, client.options.keyJump, this);
	}

	private void releaseJump(Minecraft client) {
		if (ownedKeys.contains(client.options.keyJump)) {
			KeyOwnership.releaseHold(client, client.options.keyJump, this);
			ownedKeys.remove(client.options.keyJump);
		}
	}

	/** Авто-вперёд в прыжке (опция): владение клавишей W. */
	private void forwardOwnership(Minecraft client, boolean press) {
		if (press) {
			own(client.options.keyUp);
			KeyOwnership.hold(client, client.options.keyUp, this);
		} else if (ownedKeys.contains(client.options.keyUp)) {
			KeyOwnership.releaseHold(client, client.options.keyUp, this);
			ownedKeys.remove(client.options.keyUp);
		}
	}

	private void own(KeyMapping key) {
		if (!ownedKeys.contains(key)) {
			ownedKeys.add(key);
		}
	}

	/**
	 * SafeWalk: у края гасим наш авто-«вперёд», пока опора не поставлена.
	 * Физическую клавишу игрока не трогаем — только нашу симуляцию.
	 */
	private void applySafeWalk(Minecraft client, LocalPlayer player) {
		if (!safeWalk.isEnabled() || !player.onGround()
				|| mode.is("telly") && phase != Phase.IDLE && phase != Phase.RUNNING && phase != Phase.EDGE_DETECTED) {
			return;
		}
		boolean edge = edgeAhead(client, player, 1);
		if (ScaffoldLogic.brakeAtEdge(edge, true, placeCooldown == 0 && pendingPos == null)) {
			forwardOwnership(client, false);
		}
	}

	/** Спринт: Keep — не трогаем, Disable — гасим всегда, Smart — гасим у края/в полёте. */
	private void handleSprint(LocalPlayer player) {
		if (sprint.is("keep")) {
			return;
		}
		boolean suppress = sprint.is("disable") || !player.onGround();
		if (suppress) {
			player.setSprinting(false);
		}
	}

	// =================================================================
	// Tower: башня под ногами при зажатой игроком пробеле
	// =================================================================

	private void tickTower(Minecraft client, LocalPlayer player, int slot) {
		if (!tower.isEnabled() || towerCooldown > 0 || !client.options.keyJump.isDown()) {
			return;
		}
		BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.05, player.getZ());
		if (isValidTarget(client, player, below) && tryPlace(client, player, below, slot)) {
			towerCooldown = towerDelay.get();
		}
	}

	// =================================================================
	// Откат: слот, ротация, клавиши — при off/паузе/смене мира
	// =================================================================

	private void pauseWithRollback(Minecraft client, LocalPlayer player) {
		RotationManager.release(this);
		restoreRotation(player);
		restoreVisibleSlot(player);
		releaseOwnedKeys(client);
		resetCycleState();
	}

	private void rollback(Minecraft client) {
		RotationManager.release(this);
		LocalPlayer player = client == null ? null : client.player;
		if (player != null) {
			restoreRotation(player);
			restoreVisibleSlot(player);
		}
		releaseOwnedKeys(client);
		resetCycleState();
	}

	/** Сбрасывает только краткоживущий цикл; activeLevel и Keep Y остаются валидными. */
	private void resetCycleState() {
		phase = Phase.IDLE;
		jumpedThisEdge = false;
		wasAscending = false;
		apexPassed = false;
		placedThisJump = 0;
		pendingPos = null;
		pendingTicks = 0;
		pendingRetries = 0;
		placeCooldown = 0;
		towerCooldown = 0;
		lastTarget = null;
	}

	/** Возврат слота после видимого переключения (по настройке Restore Slot). */
	private void restoreVisibleSlot(LocalPlayer player) {
		if (restoreSlot.isEnabled() && previousSlot >= 0
				&& player.getInventory().getSelectedSlot() != previousSlot) {
			player.getInventory().setSelectedSlot(previousSlot);
		}
		previousSlot = -1;
	}

	private void releaseOwnedKeys(Minecraft client) {
		if (client == null) {
			ownedKeys.clear();
			return;
		}
		for (KeyMapping key : new ArrayList<>(ownedKeys)) {
			KeyOwnership.releaseHold(client, key, this);
		}
		ownedKeys.clear();
	}

	// =================================================================
	// Превью для рендера
	// =================================================================

	/** Позиция последней цели — превью установки (читает WorldRenderHook). */
	public BlockPos previewPos() {
		return renderPreview.isEnabled() && isEnabled() ? lastTarget : null;
	}
}

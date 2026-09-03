package com.akarus.client.module.impl;

import com.akarus.client.AkarusClient;
import com.akarus.client.baritone.BaritoneBridge;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * AutoWalk — «посмотри и иди».
 *
 * Как это работает:
 * <ol>
 *   <li>включаешь модуль — включается FreeCam, чтобы осмотреться: камера летит куда
 *       угодно, игрок при этом стоит на месте;</li>
 *   <li>наводишь прицел на место, куда хочешь попасть (или просто смотришь «под себя»
 *       в точку, куда хочешь встать), и жмёшь правую кнопку мыши;</li>
 *   <li>фрикам выключается, а Baritone строит путь к выбранному блоку и ведёт
 *       игрока туда;</li>
 *   <li>пришёл — модуль сам выключается. ПКМ во время пути — отменить и снова
 *       выбирать точку.</li>
 * </ol>
 *
 * Нужен установленный Baritone.
 */
public class AutoWalkModule extends Module {

	private final IntSetting raycastRange = intSetting("raycast_range", "Дальность выбора, блоков", 96, 16, 256);
	private final BooleanSetting chatCommands = bool("chat_commands", "Командами чата", false);
	private final BooleanSetting autoDisable = bool("auto_disable", "Выключиться по приходу", true);
	private final BooleanSetting useFreeCam = bool("free_cam", "Осматриваться фрикамом", true);

	/** В каком состоянии сейчас модуль: выбираем точку или уже идём. */
	private Phase phase = Phase.CHOOSING;
	private BlockPos target;

	/** Был ли фрикам включён до нас — чтобы не выключать чужой. */
	private boolean freeCamWasEnabled;
	private boolean useWasDown;

	/** Сколько тиков Baritone ещё не начинал путь — даём ему время стартовать. */
	private int walkingTicks;

	public AutoWalkModule() {
		super("auto_walk", "AutoWalk", "Смотришь на блок, жмёшь ПКМ — Baritone ведёт туда",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_G);
	}

	@Override
	protected void onEnable() {
		this.target = null;
		this.phase = Phase.CHOOSING;
		this.walkingTicks = 0;
		// Если клавиша «использовать» уже зажата — не считаем это выбором точки
		this.useWasDown = true;

		if (!BaritoneBridge.isAvailable()) {
			notify("§c[Akarus] Baritone не найден — AutoWalk нечему отдавать команду");
			AkarusClient.LOGGER.warn("Baritone не найден, AutoWalk отключается");
			setEnabledSilently(false);
			return;
		}

		if (Minecraft.getInstance().player == null) {
			setEnabledSilently(false);
			return;
		}

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		this.freeCamWasEnabled = freeCam != null && freeCam.isEnabled();
		if (useFreeCam.isEnabled() && freeCam != null && !freeCam.isEnabled()) {
			freeCam.setEnabled(true);
		}

		notify("§7[Akarus] Смотри на точку и нажми §fПКМ§7 — Baritone пойдёт туда");
	}

	@Override
	public void tick() {
		if (phase != Phase.WALKING || target == null) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		this.walkingTicks++;

		// Сами проверяем, дошёл ли игрок: Baritone может молчать о конце пути
		double distance = player.position().distanceTo(Vec3.atCenterOf(target));
		if (distance <= 1.5) {
			notify("§a[Akarus] Пришёл: §f" + format(target));
			setEnabled(false);
			return;
		}

		// Путь оборвался (блока нет, далеко, помешали) — возвращаемся к выбору точки,
		// иначе модуль будет висеть включённым «в никуда»
		if (autoDisable.isEnabled() && walkingTicks > 20 && !BaritoneBridge.isPathing() && distance > 2.5) {
			notify("§e[Akarus] Baritone прекратил путь — выбираю точку заново");
			cancelRoute();
		}
	}

	@Override
	protected void onDisable() {
		BaritoneBridge.stop();
		this.phase = Phase.CHOOSING;
		this.target = null;
		this.walkingTicks = 0;

		// Возвращаем фрикам в то состояние, в котором он был до нас
		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		if (freeCam != null && !this.freeCamWasEnabled && freeCam.isEnabled()) {
			freeCam.setEnabled(false);
		}
	}

	@Override
	public void onSettingsChanged() {
		// Поменяли способ общения с Baritone — перезадаём цель
		if (isEnabled() && phase == Phase.WALKING && target != null) {
			BaritoneBridge.goal(target.getX(), target.getY(), target.getZ(), chatCommands.isEnabled());
		}
	}

	/** Правая кнопка мыши в мире: ставим точку или отменяем маршрут. */
	private void onRightClick() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		if (phase == Phase.WALKING) {
			// Передумали: ПКМ возвращает в режим полёта, чтобы выбрать другую точку
			cancelRoute();
			return;
		}

		BlockPos position = pickTarget(client);
		if (position == null) {
			notify("§c[Akarus] Ни во что не смотрю — наведи камеру на место, куда нужно дойти");
			return;
		}
		startWalking(client, position);
	}

	private void cancelRoute() {
		BaritoneBridge.stop();
		this.target = null;
		this.phase = Phase.CHOOSING;
		this.walkingTicks = 0;

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		if (useFreeCam.isEnabled() && freeCam != null && !freeCam.isEnabled()) {
			freeCam.setEnabled(true);
		}
		notify("§7[Akarus] Маршрут отменён — §fПКМ§7 снова задаёт цель");
	}

	/** Правая кнопка мыши: запоминаем точку и запускаем Baritone. */
	private void startWalking(Minecraft client, BlockPos position) {
		// Идти нужно уже в обычном режиме: камера возвращается игроку
		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		if (freeCam != null && freeCam.isEnabled()) {
			freeCam.setEnabled(false);
		}

		if (BaritoneBridge.goal(position.getX(), position.getY(), position.getZ(), chatCommands.isEnabled())) {
			this.target = position;
			this.phase = Phase.WALKING;
			this.walkingTicks = 0;
			notify("§a[Akarus] Иду на §f" + format(position));
		} else {
			notify("§c[Akarus] Не удалось задать цель Baritone");
			setEnabled(false);
		}
	}

	// ------------------------------------------------------------------
	// Выбор точки
	// ------------------------------------------------------------------

	/**
	 * Блок, на который смотрит камера (или игрок, если фрикам не активен).
	 *
	 * Обычный {@code Minecraft#hitResult} тут не годится: он считается от глаз
	 * игрока, а игрок во время осмотров стоит на месте и далеко от камеры —
	 * прицел на экране и реальная цель разошлись бы. Поэтому луч ведём сами,
	 * шагом, по состоянию блоков: дёшево и без зависимости от внутренностей игры.
	 */
	private BlockPos pickTarget(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			return null;
		}

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		boolean fromCamera = freeCam != null && freeCam.isEnabled();
		Vec3 origin = fromCamera ? freeCam.position() : player.getEyePosition();
		Vec3 direction = player.getViewVector(1.0F);

		double reach = raycastRange.get();
		// Шаг меньше блока, чтобы не «проскакивать» тонкие блоки (стекло, заборы)
		for (double travelled = 0.25; travelled <= reach; travelled += 0.25) {
			BlockPos pos = BlockPos.containing(
					origin.x + direction.x * travelled,
					origin.y + direction.y * travelled,
					origin.z + direction.z * travelled
			);
			if (isWalkableTarget(client, pos)) {
				return pos;
			}
		}

		// Ни во что не попали — целимся в землю: идём вниз от точки, где висит камера
		BlockPos below = BlockPos.containing(origin);
		for (int depth = 0; depth < 64; depth++) {
			BlockPos candidate = below.below(depth);
			if (isWalkableTarget(client, candidate)) {
				// Идти имеет смысл наверх блока, а не внутрь него
				return candidate;
			}
		}

		return null;
	}

	/** Solid ли блок и не жидкость ли: цель должна быть тем, на что можно встать рядом. */
	private static boolean isWalkableTarget(Minecraft client, BlockPos pos) {
		BlockState state = client.level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		return state.getFluidState().isEmpty();
	}

	// ------------------------------------------------------------------
	// Данные для HUD
	// ------------------------------------------------------------------

	/** true, когда модуль уже ведёт игрока к цели (а не ждёт выбора точки). */
	public boolean isWalking() {
		return phase == Phase.WALKING && target != null;
	}

	public BlockPos getTarget() {
		return target;
	}

	/**
	 * Вызывается в самом начале клиентского тика — раньше, чем игра сама
	 * обработает правую кнопку мыши ({@code Minecraft#handleKeybinds()}).
	 *
	 * Пока модуль включён, ПКМ принадлежит ему: мы запоминаем нажатие и тут же
	 * гасим его для игры, поэтому блоки не ставятся, а предметы из руки не
	 * используются. Левая кнопка (атака) не трогается.
	 */
	public static void handleInput(Minecraft client) {
		AutoWalkModule module = ModuleManager.find(AutoWalkModule.class);
		if (module == null || !module.isEnabled() || client.player == null || client.mouseHandler == null) {
			return;
		}

		// В меню и инвентаре правая кнопка мыши работает как обычно
		if (client.gui != null && client.gui.screen() != null) {
			return;
		}

		// Физическое состояние кнопки: не зависит от того, что мы дальше погасим
		boolean pressed = client.mouseHandler.isRightPressed();
		if (pressed && !module.useWasDown) {
			module.onRightClick();
		}
		module.useWasDown = pressed;

		// Гасим ПКМ до того, как его увидит игра
		KeyMapping use = client.options.keyUse;
		use.setDown(false);
		while (use.consumeClick()) {
			// клик съеден, игра его не заметит
		}
	}

	/** Расстояние от игрока до цели в блоках. */
	public double getDistance() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || target == null) {
			return 0.0;
		}
		return client.player.position().distanceTo(Vec3.atCenterOf(target));
	}

	public static String format(BlockPos position) {
		return position.getX() + " " + position.getY() + " " + position.getZ();
	}

	private static void notify(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui == null) {
			return;
		}
		client.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}

	private enum Phase {
		CHOOSING,
		WALKING
	}
}

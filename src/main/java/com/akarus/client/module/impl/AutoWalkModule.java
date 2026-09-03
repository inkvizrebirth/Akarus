package com.akarus.client.module.impl;

import com.akarus.client.AkarusClient;
import com.akarus.client.baritone.BaritoneBridge;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.settings.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * AutoWalk — «полёт, точка, маршрут».
 *
 * Как это работает:
 * <ol>
 *   <li>включаешь модуль — игрок сразу переходит в FreeCam и может лететь сквозь блоки;</li>
 *   <li>снизу всё время видны координаты точки, в которой ты сейчас находишься;</li>
 *   <li>находишь нужное место, жмёшь правую кнопку мыши — фрикам выключается,
 *       а Baritone сам идёт на эти координаты.</li>
 * </ol>
 *
 * Нужен установленный Baritone.
 */
public class AutoWalkModule extends Module {

	private final BooleanSetting chatCommands = bool("chat_commands", "Командами чата", false);
	private final BooleanSetting autoDisable = bool("auto_disable", "Выключиться по приходу", true);

	/** В каком состоянии сейчас модуль: выбираем точку или уже идём. */
	private Phase phase = Phase.CHOOSING;
	private BlockPos target;

	/** Был ли фрикам включён до нас — чтобы не выключать чужой. */
	private boolean freeCamWasEnabled;
	private boolean useWasDown;

	public AutoWalkModule() {
		super("auto_walk", "AutoWalk", "Летишь фрикамом, ПКМ — и Baritone идёт на эти координаты",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_G);
	}

	@Override
	protected void onEnable() {
		target = null;
		phase = Phase.CHOOSING;
		// Если клавиша «использовать» уже зажата — не считаем это выбором точки
		useWasDown = true;

		if (!BaritoneBridge.isAvailable()) {
			notify("§cBaritone не установлен — AutoWalk недоступен");
			AkarusClient.LOGGER.warn("Baritone не найден, AutoWalk отключается");
			setEnabledSilently(false);
			return;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			setEnabledSilently(false);
			return;
		}

		// Включаем фрикам: в нём игрок физически летит, поэтому «долететь» можно куда угодно
		FreeCamModule freeCam = ModuleManager.getModule(FreeCamModule.class);
		freeCamWasEnabled = freeCam.isEnabled();
		if (!freeCamWasEnabled) {
			freeCam.setEnabled(true);
		}

		notify("§7[Akarus] Лети куда нужно и нажми §fПКМ§7 — Baritone пойдёт туда сам");
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		// Ловим именно момент нажатия правой кнопки, а не её удержание
		boolean useDown = client.options.keyUse.isDown();
		boolean pressed = useDown && !useWasDown;
		useWasDown = useDown;

		if (phase == Phase.CHOOSING) {
			if (pressed) {
				startWalking(player.blockPosition());
			}
			return;
		}

		if (target == null) {
			return;
		}

		// Передумали: ПКМ возвращает в режим полёта, чтобы выбрать другую точку
		if (pressed) {
			BaritoneBridge.stop();
			target = null;
			phase = Phase.CHOOSING;

			FreeCamModule freeCam = ModuleManager.getModule(FreeCamModule.class);
			if (!freeCam.isEnabled()) {
				freeCam.setEnabled(true);
			}
			notify("§7[Akarus] Снова выбираю точку — §fПКМ§7, чтобы задать цель");
			return;
		}

		// Сами проверяем, дошёл ли игрок: Baritone может молчать о конце пути
		double distance = player.position().distanceTo(Vec3.atCenterOf(target));
		if (distance <= 1.5) {
			if (autoDisable.isEnabled()) {
				notify("§a[Akarus] Пришёл: §f" + format(target));
				setEnabled(false);
			}
		}
	}

	@Override
	protected void onDisable() {
		BaritoneBridge.stop();
		phase = Phase.CHOOSING;
		target = null;

		// Возвращаем фрикам в то состояние, в котором он был до нас
		FreeCamModule freeCam = ModuleManager.getModule(FreeCamModule.class);
		if (!freeCamWasEnabled && freeCam.isEnabled()) {
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

	/** Правая кнопка мыши: запоминаем точку и запускаем Baritone. */
	private void startWalking(BlockPos position) {
		// Идти нужно уже в обычном режиме, поэтому фрикам выключаем
		FreeCamModule freeCam = ModuleManager.getModule(FreeCamModule.class);
		if (freeCam.isEnabled()) {
			freeCam.setEnabled(false);
		}

		if (BaritoneBridge.goal(position.getX(), position.getY(), position.getZ(), chatCommands.isEnabled())) {
			target = position;
			phase = Phase.WALKING;
			notify("§a[Akarus] Иду на §f" + format(position));
		} else {
			notify("§c[Akarus] Не удалось задать цель Baritone");
			setEnabled(false);
		}
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

	/** Расстояние от игрока до цели в блоках. */
	public double getDistance() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || target == null) {
			return 0.0;
		}
		return player.position().distanceTo(Vec3.atCenterOf(target));
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

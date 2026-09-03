package com.akarus.client.module.impl;

import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

/**
 * FreeCam, в котором камера двигается «по-настоящему».
 *
 * В отличие от классического фрикама, где летает только «глаз», здесь сам игрок
 * получает noPhysics и режим полёта: он физически перемещается, поэтому можно
 * ломать и ставить блоки именно там, куда смотришь.
 *
 * Нюансы:
 * <ul>
 *   <li>в одиночной игре состояние синхронизируется и с серверной сущностью игрока,
 *       поэтому рывков нет;</li>
 *   <li>на сервере сервер может «откатывать» позицию — это нормально для клиентского ноклипа;</li>
 *   <li>при выключении игрок аккуратно поднимается вверх, если застрял в блоке.</li>
 * </ul>
 */
public class FreeCamModule extends Module {

	private final IntSetting speed = intSetting("speed", "Скорость", 6, 1, 20);
	private final BooleanSetting sprintBoost = bool("sprint_boost", "Ускорение на спринт", true);

	private boolean previousMayFly;
	private boolean previousFlying;
	private float previousFlyingSpeed;

	public FreeCamModule() {
		super("free_cam", "FreeCam", "Полёт сквозь блоки: игрок двигается по-настоящему, можно ломать и ставить",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_N);
	}

	@Override
	protected void onEnable() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}

		Abilities abilities = player.getAbilities();
		previousMayFly = abilities.mayfly;
		previousFlying = abilities.flying;
		previousFlyingSpeed = abilities.getFlyingSpeed();

		setNoClip(player, true);
	}

	@Override
	public void tick() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}

		// Держим состояние каждый тик: после респавна или смены мира способности сбрасываются
		setNoClip(player, true);

		float value = 0.025f * speed.get();
		if (sprintBoost.isEnabled() && player.isSprinting()) {
			value *= 2.0f;
		}
		player.getAbilities().setFlyingSpeed(value);
	}

	@Override
	protected void onDisable() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}

		setNoClip(player, false);

		Abilities abilities = player.getAbilities();
		abilities.mayfly = previousMayFly;
		abilities.flying = previousFlying;
		abilities.setFlyingSpeed(previousFlyingSpeed);

		unstick(player);
	}

	private static void setNoClip(LocalPlayer player, boolean enabled) {
		player.noPhysics = enabled;

		Abilities abilities = player.getAbilities();
		abilities.mayfly = enabled;
		abilities.flying = enabled;

		// В одиночной игре сервер живёт в том же процессе — снимаем коллизии и у него,
		// иначе сервер будет откатывать игрока обратно в стену
		setServerNoClip(player, enabled);
	}

	private static void setServerNoClip(LocalPlayer player, boolean enabled) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}

		ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
		if (serverPlayer != null) {
			serverPlayer.noPhysics = enabled;
		}
	}

	/** Поднимает игрока вверх, если он выключил режим внутри блока. */
	private static void unstick(LocalPlayer player) {
		if (player.level() == null) {
			return;
		}

		double y = player.getY();
		for (int i = 0; i < 64 && isInsideSolidBlock(player); i++) {
			y += 1.0;
			player.setPos(player.getX(), y, player.getZ());
		}
	}

	private static boolean isInsideSolidBlock(LocalPlayer player) {
		BlockPos position = BlockPos.containing(player.getEyePosition());
		BlockState state = player.level().getBlockState(position);
		return state.isSuffocating(player.level(), position);
	}
}

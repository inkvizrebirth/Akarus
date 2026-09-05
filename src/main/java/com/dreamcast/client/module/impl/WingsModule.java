package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Wings — крылья за спиной: плоский веер из трёх слоёв (ореол, ядро, тело)
 * с обводкой и «рёбрами», который машет в такт движению.
 *
 * <p>Поза зависит от состояния игрока: планирование на элитрах, плавание,
 * воздух, спринт и шаг — у каждой свои точка крепления, раскрытие, амплитуда
 * и частота взмаха. Если элитры НАДЕТЫ, свои крылья не рисуем: два набора
 * крыльев за одной спиной выглядят как баг, а не как кастомизация.</p>
 *
 * <p>Разворот корпуса своего игрока сглаживается (14°/тик). Камера у нас
 * вращается мгновенно мышью, и без сглаживания крылья дёргались бы на каждый
 * флик; у чужих игроков {@code getPreciseBodyRotation} уже интерполирован
 * серверными пакетами, поэтому им правка не нужна.</p>
 */
public class WingsModule extends Module {

	/** Что игрок делает — из этого выбирается поза крыльев. */
	public enum Pose {
		GLIDING,
		SWIMMING,
		AIRBORNE,
		SPRINTING,
		WALKING
	}

	/**
	 * Один набор крыльев на кадр: точка крепления в мире, оси корпуса и параметры
	 * взмаха. Собирается на извлечении кадра, чтобы рендер не читал сущности.
	 */
	public record Rig(float x, float y, float z, float bodyYaw, float bodyPitch,
	                 Pose pose, float flapPhase, float move, float size) {
	}

	/**
	 * Параметры позы крыльев. Углы — в градусах, смещения — в блоках,
	 * {@code flapSpeed} — радианы на тик.
	 *
	 * @param anchorUp     насколько выше середины корпуса крепится крыло
	 * @param anchorBack   смещение точки крепления назад (вдоль оси взгляда)
	 * @param sweepBase    базовый «захлоп»: 0 — крыло вбок, 90 — прижато назад
	 * @param sweepPerMove  насколько сильнее отводится назад на скорости, °
	 * @param elevBase     базовый подъём кончика (минус — кончик ниже плеча)
	 * @param flapAmplitude амплитуда взмаха по подъёму, °
	 * @param flapSpeed    частота взмаха, рад/тик
	 * @param scale        масштаб крыла
	 * @param sideGap      зазор от оси корпуса, чтобы крыло не шло сквозь спину
	 */
	public record WingPose(float anchorUp, float anchorBack, float sweepBase, float sweepPerMove,
	                       float elevBase, float flapAmplitude, float flapSpeed, float scale,
	                       float sideGap) {
	}

	private static final WingPose POSE_GLIDING = new WingPose(0.30F, 0.16F, 74.0F, 6.0F,
			-16.0F, 4.0F, 0.05F, 0.78F, 0.10F);
	private static final WingPose POSE_SWIMMING = new WingPose(0.16F, 0.06F, 40.0F, 8.0F,
			14.0F, 22.0F, 0.16F, 0.88F, 0.10F);
	private static final WingPose POSE_AIRBORNE = new WingPose(0.24F, 0.04F, 30.0F, 12.0F,
			18.0F, 26.0F, 0.13F, 0.96F, 0.11F);
	private static final WingPose POSE_SPRINTING = new WingPose(0.30F, 0.06F, 42.0F, 14.0F,
			4.0F, 18.0F, 0.16F, 1.0F, 0.12F);
	private static final WingPose POSE_WALKING = new WingPose(0.32F, 0.03F, 26.0F, 6.0F,
			6.0F, 10.0F, 0.09F, 1.0F, 0.12F);

	private final ModeSetting when = mode("when", "Показывать",
			ModeSetting.option("always", "Всегда"),
			ModeSetting.option("moving", "В движении"),
			ModeSetting.option("air", "В воздухе и в полёте"));
	private final BooleanSetting self = bool("self", "На себе", true);
	private final BooleanSetting players = bool("players", "На игроках", false);
	private final BooleanSetting seeThrough = bool("see_through", "Сквозь стены", false);
	private final BooleanSetting outline = bool("outline", "Обводка и рёбра", true);
	private final IntSetting size = intSetting("size", "Размер (%)", 100, 70, 140);
	private final IntSetting flapSpeed = intSetting("flap_speed", "Частота взмахов (%)", 100, 20, 300);
	private final IntSetting opacity = intSetting("alpha", "Плотность (%)", 100, 20, 100);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 4, 1, 10);
	private final ColorSetting color = colorSetting("color", "Цвет", 0xFF7C6CFF);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", true);

	private volatile List<Rig> rigs = List.of();

	/** Сглаженный разворот корпуса своего игрока (см. javadoc класса). */
	private float selfBodyYaw;
	private boolean selfBodyYawReady;

	public WingsModule() {
		super("wings", "Wings", "Анимированные крылья за спиной: махают в такт движению и позе",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void onDisable() {
		rigs = List.of();
		selfBodyYawReady = false;
	}

	/** Сбор поз на этот кадр — вызывается из {@code END_EXTRACTION}. */
	public void collect(float partialTick) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			rigs = List.of();
			return;
		}
		List<Rig> out = new ArrayList<>(4);
		if (self.isEnabled() && visibleFor(player)) {
			out.add(rigFor(player, smoothedSelfBodyYaw(player, partialTick), partialTick));
		}
		if (players.isEnabled()) {
			for (AbstractClientPlayer other : client.level.getEntitiesOfClass(AbstractClientPlayer.class,
					player.getBoundingBox().inflate(64.0))) {
				if (other == player || !visibleFor(other)) {
					continue;
				}
				out.add(rigFor(other, other.getPreciseBodyRotation(partialTick), partialTick));
			}
		}
		rigs = List.copyOf(out);
	}

	private Rig rigFor(LivingEntity entity, float bodyYaw, float partialTick) {
		WingPose wing = poseFor(poseOf(entity));
		// Фазу передаём как есть: синус считается в рендере с учётом частичного
		// тика, иначе взмах «квантовался» бы по тикам игры (60 fps и 20 tps)
		float phase = (entity.tickCount + partialTick) * wing.flapSpeed() * (flapSpeed.get() / 100.0F);
		float move = Mth.clamp((float) entity.getDeltaMovement().horizontalDistance(), 0.0F, 1.0F);
		return new Rig((float) entity.getX(partialTick), (float) entity.getY(partialTick),
				(float) entity.getZ(partialTick), bodyYaw, entity.getViewXRot(partialTick),
				poseOf(entity), phase, move, size.get() / 100.0F);
	}

	/** Поза крыльев по состоянию игрока. */
	public static Pose poseOf(LivingEntity entity) {
		if (entity.isFallFlying()) {
			return Pose.GLIDING;
		}
		if (entity.isSwimming()) {
			return Pose.SWIMMING;
		}
		if (!entity.onGround()) {
			return Pose.AIRBORNE;
		}
		if (entity.isSprinting()) {
			return Pose.SPRINTING;
		}
		return Pose.WALKING;
	}

	public static WingPose poseFor(Pose pose) {
		return switch (pose) {
			case GLIDING -> POSE_GLIDING;
			case SWIMMING -> POSE_SWIMMING;
			case AIRBORNE -> POSE_AIRBORNE;
			case SPRINTING -> POSE_SPRINTING;
			case WALKING -> POSE_WALKING;
		};
	}

	/** На игроке в элитрах крылья не рисуем: они уже есть у модели. */
	private static boolean hasRealElytra(LivingEntity entity) {
		return entity.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
	}

	private boolean visibleFor(LivingEntity entity) {
		if (hasRealElytra(entity)) {
			return false;
		}
		return switch (when.getValue()) {
			case "moving" -> entity.getDeltaMovement().horizontalDistanceSqr() > 1.0e-3
					|| !entity.onGround();
			case "air" -> !entity.onGround();
			default -> true;
		};
	}

	private float smoothedSelfBodyYaw(LocalPlayer player, float partialTick) {
		float wanted = player.getViewYRot(partialTick);
		if (!selfBodyYawReady || player.tickCount < 2) {
			selfBodyYaw = wanted;
			selfBodyYawReady = true;
			return wanted;
		}
		selfBodyYaw += Mth.clamp(Mth.wrapDegrees(wanted - selfBodyYaw), -14.0F, 14.0F);
		return selfBodyYaw;
	}

	public boolean wantsWings() {
		return isEnabled() && !rigs.isEmpty();
	}

	public List<Rig> rigs() {
		return rigs;
	}

	public boolean drawsOutline() {
		return outline.isEnabled();
	}

	public boolean seeThroughWalls() {
		return seeThrough.isEnabled();
	}

	public float opacityScale() {
		return opacity.get() / 100.0F;
	}

	/** Цвет крыла: {@code t} — доля размаха крыла (для перелива от корпуса к краю). */
	public int wingColor(float t, long nowMs) {
		if (rainbow.isEnabled()) {
			return RenderUtils.hsb(RenderUtils.rainbowPhase(nowMs, rainbowSpeed.get()) + t * 0.35F,
					0.75F, 1.0F, 0xFF);
		}
		return color.get();
	}

	/** Ядро и ореол — тот же цвет, выбеленный к центру крыла. */
	public static int lighten(int color, float amount) {
		return RenderUtils.mix(color, 0xFFFFFFFF, amount);
	}
}

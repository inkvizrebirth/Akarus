package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.Notifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.lwjgl.glfw.GLFW;


/**
 * AutoBuff — сам пьёт зелья-баффы, когда они кончаются.
 *
 * <p>Следит за эффектами из чек-листа (скорость, сила, огнестойкость,
 * регенерация, ночное зрение). Если эффекта нет или он вот-вот кончится —
 * находит зелье в хотбаре (или инвентаре) и выпивает.</p>
 *
 * <p>Режимы:</p>
 * <ul>
 *   <li><b>fast</b> — мгновенно, как в классических клиентах: подмена слота,
 *       использование и возврат слота за один тик;</li>
 *   <li><b>legit</b> — по-человечески: случайные паузы 0.2–0.5 с между
 *       действиями, плавный «взгляд вниз» на время питья (камера сама
 *       возвращается), смена слота через инвентарь с задержкой.</li>
 * </ul>
 *
 * <p>Зелья выбираются только «чистые»: нужный эффект без вредных
 * (слабость/медлительность/яд). Молоко и нега-зелья игнорируются.</p>
 */
public class AutoBuffModule extends Module {

	private final ModeSetting mode = mode("mode", "Режим", "legit",
			ModeSetting.option("fast", "Быстрый"),
			ModeSetting.option("legit", "Легит"));

	private final BooleanSetting wantSpeed = bool("speed", "Скорость", true);
	private final BooleanSetting wantStrength = bool("strength", "Сила", true);
	private final BooleanSetting wantFireRes = bool("fire_res", "Огнестойкость", true);
	private final BooleanSetting wantRegen = bool("regen", "Регенерация", false);
	private final BooleanSetting wantNightVision = bool("night_vision", "Ночное зрение", false);

	private final IntSetting refreshSeconds = intSetting("refresh", "Пить за N сек до конца", 8, 1, 30);
	private final BooleanSetting notify = bool("notify", "Уведомления", false);

	/** Состояние конечного автомата легит-режима. */
	private enum Phase {
		IDLE, TURNING, SWAPPING, DRINKING, RETURNING
	}

	private Phase phase = Phase.IDLE;
	private long phaseSince;
	private int previousSlot = -1;
	private int pendingSlot = -1;
	/** Куда вернуть взгляд после питья. */
	private float returnYaw, returnPitch;
	private boolean wasLookingDown;

	public AutoBuffModule() {
		super("auto_buff", "AutoBuff", "Автоматически пьёт бафф-зелья, когда они кончаются",
				ModuleCategory.MISC, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null || client.gameMode == null) {
			reset();
			return;
		}

		if ("fast".equals(mode.get())) {
			tickFast(player);
		} else {
			tickLegit(client, player);
		}
	}

	// ------------------------------------------------------------------
	// Быстрый режим
	// ------------------------------------------------------------------

	private void tickFast(LocalPlayer player) {
		Holder<MobEffect> missing = missingBuff(player);
		if (missing == null) {
			return;
		}
		int slot = findPotionSlot(player, missing, false);
		if (slot < 0) {
			slot = findPotionSlot(player, missing, true);
		}
		if (slot < 0) {
			return; // нет нужного зелья — ждём
		}
		int current = player.getInventory().getSelectedSlot();
		selectSlot(player, slot);
		useHeld(player);
		if (slot != current) {
			selectSlot(player, current);
		}
		if (notify.isEnabled()) {
			Notifications.info("AutoBuff", "Выпито: " + buffName(missing));
		}
	}

	// ------------------------------------------------------------------
	// Легит-режим: человечные задержки и повороты
	// ------------------------------------------------------------------

	private void tickLegit(Minecraft client, LocalPlayer player) {
		long now = net.minecraft.util.Util.getMillis();
		long humanPause = 200 + Math.abs(hashCode() % 7) * 40;

		switch (phase) {
			case IDLE -> {
				Holder<MobEffect> missing = missingBuff(player);
				if (missing == null) {
					return;
				}
				int slot = findPotionSlot(player, missing, false);
				if (slot < 0) {
					slot = findPotionSlot(player, missing, true);
				}
				if (slot < 0) {
					return;
				}
				pendingSlot = slot;
				previousSlot = player.getInventory().getSelectedSlot();
				returnYaw = player.getYRot();
				returnPitch = player.getXRot();
				wasLookingDown = returnPitch > 55.0f;
				phase = Phase.TURNING;
				phaseSince = now;
			}
			case TURNING -> {
				// Плавно опускаем взгляд, как делает игрок перед питьём
				float target = wasLookingDown ? returnPitch : 72.0f;
				float pitch = player.getXRot();
				float delta = Math.signum(target - pitch) * Math.min(6.0f, Math.abs(target - pitch));
				player.setXRot(pitch + delta);
				if (Math.abs(player.getXRot() - target) < 7.0f && now - phaseSince > humanPause) {
					phase = Phase.SWAPPING;
					phaseSince = now;
				}
			}
			case SWAPPING -> {
				if (now - phaseSince < humanPause) {
					return;
				}
				if (pendingSlot >= 0) {
					selectSlot(player, pendingSlot);
				}
				phase = Phase.DRINKING;
				phaseSince = now;
			}
			case DRINKING -> {
				if (now - phaseSince < humanPause) {
					return;
				}
				useHeld(player);
				phase = Phase.RETURNING;
				phaseSince = now;
			}
			case RETURNING -> {
				if (now - phaseSince < 150) {
					return;
				}
				if (previousSlot >= 0) {
					selectSlot(player, previousSlot);
				}
				// Возвращаем взгляд тем же плавным темпом
				float pitch = player.getXRot();
				float delta = Math.signum(returnPitch - pitch) * Math.min(8.0f, Math.abs(returnPitch - pitch));
				player.setXRot(pitch + delta);
				if (Math.abs(player.getXRot() - returnPitch) < 8.0f) {
					if (notify.isEnabled()) {
						Notifications.ok("AutoBuff", "Бафф обновлён");
					}
					reset();
				}
			}
		}
	}

	private void reset() {
		phase = Phase.IDLE;
		previousSlot = -1;
		pendingSlot = -1;
		wasLookingDown = false;
	}

	// ------------------------------------------------------------------
	// Помощники
	// ------------------------------------------------------------------

	private net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> missingBuff(LocalPlayer player) {
		if (wantSpeed.isEnabled() && needs(player, MobEffects.SPEED)) {
			return MobEffects.SPEED;
		}
		if (wantStrength.isEnabled() && needs(player, MobEffects.STRENGTH)) {
			return MobEffects.STRENGTH;
		}
		if (wantFireRes.isEnabled() && needs(player, MobEffects.FIRE_RESISTANCE)) {
			return MobEffects.FIRE_RESISTANCE;
		}
		if (wantRegen.isEnabled() && needs(player, MobEffects.REGENERATION)) {
			return MobEffects.REGENERATION;
		}
		if (wantNightVision.isEnabled() && needs(player, MobEffects.NIGHT_VISION)) {
			return MobEffects.NIGHT_VISION;
		}
		return null;
	}

	private boolean needs(LocalPlayer player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
		MobEffectInstance active = player.getEffect(effect);
		if (active == null) {
			return true;
		}
		int left = active.getDuration();
		return !active.isInfiniteDuration() && left <= refreshSeconds.get() * 20;
	}

	/**
	 * Ищет питьевое зелье с нужным эффектом. Сначала хотбар, потом (если
	 * {@code searchInventory}) весь инвентарь — тогда вернётся слот, куда
	 * нужно свапнуть предмет из хотбара.
	 */
	private int findPotionSlot(LocalPlayer player,
	                           net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> wanted,
	                           boolean searchInventory) {
		var inventory = player.getInventory();
		int last = searchInventory ? inventory.getContainerSize() : 9;
		for (int i = 0; i < last; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!isBuffPotion(stack, wanted)) {
				continue;
			}
			if (i < 9) {
				return i;
			}
			// Вне хотбара: легитно перекладываем — вернём слот хотбара,
			// с которого заберём предмет (обычно самый «мусорный» — последний)
			return swapIntoHotbar(player, i);
		}
		return -1;
	}

	private int swapIntoHotbar(LocalPlayer player, int fromSlot) {
		var inventory = player.getInventory();
		int hotbarTarget = 8;
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gameMode == null) {
			return -1;
		}
		client.gameMode.handleContainerInput(player.containerMenu.containerId, fromSlot, hotbarTarget,
				net.minecraft.world.inventory.ContainerInput.SWAP, player);
		return hotbarTarget;
	}

	/** Питьевое зелье (не splash) с нужным эффектом и без вредных. */
	private boolean isBuffPotion(ItemStack stack,
	                             net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> wanted) {
		if (!stack.is(Items.POTION)) {
			return false;
		}
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) {
			return false;
		}
		boolean hasWanted = false;
		for (MobEffectInstance effect : contents.allEffects()) {
			if (effect.getEffect() == wanted) {
				hasWanted = true;
			}
			if (isBad(effect)) {
				return false;
			}
		}
		return hasWanted;
	}

	private static boolean isBad(MobEffectInstance effect) {
		return effect.getEffect() == MobEffects.WEAKNESS
				|| effect.getEffect() == MobEffects.SLOWNESS
				|| effect.getEffect() == MobEffects.POISON
				|| effect.getEffect() == MobEffects.INSTANT_DAMAGE;
	}

	private static void selectSlot(LocalPlayer player, int slot) {
		player.getInventory().setSelectedSlot(slot);
	}

	private static void useHeld(LocalPlayer player) {
		Minecraft client = Minecraft.getInstance();
		if (client.gameMode != null) {
			client.gameMode.useItem(player,
					net.minecraft.world.InteractionHand.MAIN_HAND);
		}
	}

	private static String buffName(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
		if (effect == MobEffects.SPEED) {
			return "скорость";
		}
		if (effect == MobEffects.STRENGTH) {
			return "сила";
		}
		if (effect == MobEffects.FIRE_RESISTANCE) {
			return "огнестойкость";
		}
		if (effect == MobEffects.REGENERATION) {
			return "регенерация";
		}
		return "эффект";
	}
}

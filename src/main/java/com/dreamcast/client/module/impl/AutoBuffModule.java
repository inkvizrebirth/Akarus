package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.util.DrinkLogic;
import com.dreamcast.client.util.Notifications;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import org.lwjgl.glfw.GLFW;

/**
 * AutoBuff — сам пьёт зелья-баффы (и лечится), когда они кончаются.
 *
 * <p>Следит за эффектами из чек-листа (скорость, сила, огнестойкость,
 * регенерация, ночное зрение). Если эффекта нет или он вот-вот кончится —
 * находит зелье в хотбаре (или инвентаре) и <b>выпивает до конца</b>:
 * клавиша «использовать» держится программно, пока питьё реально не
 * завершится ({@code isUsingItem()} гаснет / остаются ≤1 тика), и только
 * затем возвращаются слот и взгляд. На случай отказа сервера есть общий
 * таймаут.</p>
 *
 * <p>Лечение: при HP ниже порога пьётся мгновенное зелье лечения, а если
 * его нет — съедается золотое яблоко (пока нет поглощения). У мгновенных
 * зелий нет длительности, поэтому они выбираются только по порогу HP.</p>
 *
 * <p>Режимы:</p>
 * <ul>
 *   <li><b>fast</b> — без пауз и поворотов камеры: слот → питьё до конца → слот назад;</li>
 *   <li><b>legit</b> — по-человечески: пауза перед действием, плавный «взгляд вниз»
 *       на время питья, смена слота через инвентарь с задержкой.</li>
 * </ul>
 *
 * <p>Зелья выбираются только «чистые»: нужный эффект без вредных
 * (слабость/медлительность/яд/мгновенный урон).</p>
 */
public class AutoBuffModule extends Module {

	/** Общий таймаут питья: 32 тика предмета + сетевые задержки, с запасом. */
	private static final long DRINK_TIMEOUT_MS = 8000L;
	/** Если за это время использование так и не началось — откат. */
	private static final long START_TIMEOUT_MS = 600L;

	private final ModeSetting mode = mode("mode", "Режим", "legit",
			ModeSetting.option("fast", "Быстрый"),
			ModeSetting.option("legit", "Легит"));

	private final BooleanSetting wantSpeed = bool("speed", "Скорость", true);
	private final BooleanSetting wantStrength = bool("strength", "Сила", true);
	private final BooleanSetting wantFireRes = bool("fire_res", "Огнестойкость", true);
	private final BooleanSetting wantRegen = bool("regen", "Регенерация", false);
	private final BooleanSetting wantNightVision = bool("night_vision", "Ночное зрение", false);

	private final BooleanSetting wantHeal = bool("heal", "Лечение при низком HP", true);
	private final BooleanSetting wantGapple = bool("gapple", "Золотое яблоко при низком HP", true);
	private final IntSetting healBelow = intSetting("heal_below", "Считать HP низким при ≤", 12, 1, 20);

	private final IntSetting refreshSeconds = intSetting("refresh", "Пить за N сек до конца", 8, 1, 30);
	private final BooleanSetting notify = bool("notify", "Уведомления", false);

	/** Состояние конечного автомата (общий для обоих режимов). */
	private enum Phase {
		IDLE, TURNING, SWAPPING, DRINKING, RETURNING
	}

	private Phase phase = Phase.IDLE;
	private long phaseSince;
	private int previousSlot = -1;
	private int pendingSlot = -1;
	/** Что держим: зелье с эффектом или золотое яблоко. */
	private Holder<MobEffect> pendingEffect;
	private boolean pendingGapple;
	/** Куда вернуть взгляд после питья. */
	private float returnYaw, returnPitch;
	private boolean wasLookingDown;
	/** Питьё: видели ли начало, когда начали держать. */
	private boolean sawUsing;
	private int pauseSeed;

	public AutoBuffModule() {
		super("auto_buff", "AutoBuff", "Автоматически пьёт бафф-зелья и лечится при низком HP",
				ModuleCategory.MISC, GLFW.GLFW_KEY_UNKNOWN);
		pauseSeed = System.identityHashCode(this);
	}

	@Override
	protected void onDisable() {
		releaseUseKey();
		reset();
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null || client.gameMode == null) {
			releaseUseKey();
			reset();
			return;
		}

		if (mode.is("fast")) {
			tickFast(client, player);
		} else {
			tickLegit(client, player);
		}
	}

	// ------------------------------------------------------------------
	// Быстрый режим: те же фазы, но без камеры и пауз
	// ------------------------------------------------------------------

	private void tickFast(Minecraft client, LocalPlayer player) {
		long now = net.minecraft.util.Util.getMillis();

		switch (phase) {
			case IDLE -> {
				Target target = findTarget(player);
				if (target == null) {
					return;
				}
				previousSlot = player.getInventory().getSelectedSlot();
				beginSwap(player, target);
				phase = Phase.DRINKING; // fast: TURNING/SWAPPING без пауз
				phaseSince = now;
				sawUsing = false;
				startDrinking(client, player);
			}
			case DRINKING -> tickDrinking(client, player, now, 0);
			case RETURNING -> {
				// последний тик питья ещё идёт — сменой слота можно сорвать использование
				if (player.isUsingItem()) {
					return;
				}
				if (previousSlot >= 0) {
					selectSlot(player, previousSlot);
				}
				if (notify.isEnabled()) {
					Notifications.ok("AutoBuff", "Готово: " + lastName());
				}
				reset();
			}
			default -> phase = Phase.IDLE; // TURNING/SWAPPING — легит-фазы
		}
	}

	// ------------------------------------------------------------------
	// Легит-режим: человечные задержки и повороты
	// ------------------------------------------------------------------

	private void tickLegit(Minecraft client, LocalPlayer player) {
		long now = net.minecraft.util.Util.getMillis();
		int humanPause = DrinkLogic.humanPause(pauseSeed, 200, 480);

		switch (phase) {
			case IDLE -> {
				Target target = findTarget(player);
				if (target == null) {
					return;
				}
				previousSlot = player.getInventory().getSelectedSlot();
				returnYaw = player.getYRot();
				returnPitch = player.getXRot();
				wasLookingDown = returnPitch > 55.0f;
				beginSwap(player, target);
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
					phase = Phase.DRINKING;
					phaseSince = now;
					sawUsing = false;
					startDrinking(client, player);
				}
			}
			case DRINKING -> tickDrinking(client, player, now, humanPause);
			case RETURNING -> {
				// последний тик питья ещё идёт — сменой слота можно сорвать использование
				if (player.isUsingItem()) {
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
						Notifications.ok("AutoBuff", "Бафф обновлён: " + lastName());
					}
					reset();
				}
			}
			default -> phase = Phase.IDLE;
		}
	}

	/** Общий для режимов HOLD: держим «использовать», пока питьё не завершится. */
	private void tickDrinking(Minecraft client, LocalPlayer player, long now, int humanPause) {
		if (now - phaseSince < humanPause && sawUsing) {
			return;
		}
		boolean usingNow = player.isUsingItem();
		if (usingNow) {
			sawUsing = true;
		}
		int remaining = usingNow ? player.getUseItemRemainingTicks() : Integer.MAX_VALUE;
		long elapsed = now - phaseSince;

		if (DrinkLogic.neverStarted(sawUsing, elapsed, START_TIMEOUT_MS)
				|| DrinkLogic.finished(sawUsing, usingNow, remaining, elapsed, DRINK_TIMEOUT_MS)) {
			// finished() срабатывает и на «≤1 тик до конца» — клавишу отпускаем
			// заранее, за тик, чтобы ваниль не начала использовать следующий предмет
			releaseUseKey();
			phase = Phase.RETURNING;
			phaseSince = now;
			return;
		}

		if (!usingNow) {
			// Использование сорвалось (смена слота, экран, урон) — перезапускаем
			startDrinking(client, player);
		}
	}

	// ------------------------------------------------------------------
	// Выбор цели: лечение → баффы по списку
	// ------------------------------------------------------------------

	/** Что пить прямо сейчас: {эффект, слот, яблоко?} или null. */
	private Target findTarget(LocalPlayer player) {
		boolean lowHp = player.getHealth() <= healBelow.get();
		if (lowHp && wantHeal.isEnabled()) {
			int slot = findPotionSlot(player, MobEffects.INSTANT_HEALTH);
			if (slot >= 0) {
				return new Target(MobEffects.INSTANT_HEALTH, slot, false);
			}
		}
		if (lowHp && wantGapple.isEnabled() && !player.hasEffect(MobEffects.ABSORPTION)) {
			int slot = findGappleSlot(player);
			if (slot >= 0) {
				return new Target(null, slot, true);
			}
		}
		Holder<MobEffect> missing = missingBuff(player);
		if (missing == null) {
			return null;
		}
		int slot = findPotionSlot(player, missing);
		return slot < 0 ? null : new Target(missing, slot, false);
	}

	private record Target(Holder<MobEffect> effect, int slot, boolean gapple) {
	}

	private Holder<MobEffect> missingBuff(LocalPlayer player) {
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

	private boolean needs(LocalPlayer player, Holder<MobEffect> effect) {
		MobEffectInstance active = player.getEffect(effect);
		if (active == null) {
			return true;
		}
		int left = active.getDuration();
		return !active.isInfiniteDuration() && left <= refreshSeconds.get() * 20;
	}

	// ------------------------------------------------------------------
	// Действия
	// ------------------------------------------------------------------

	/** Выбирает слот цели (переложив из рюкзака в хотбар, если нужно). */
	private void beginSwap(LocalPlayer player, Target target) {
		pendingSlot = target.slot();
		pendingEffect = target.effect();
		pendingGapple = target.gapple();
		selectSlot(player, pendingSlot);
	}

	/** Начинает использование: пакет «use» + программно зажимаем клавишу использования. */
	private void startDrinking(Minecraft client, LocalPlayer player) {
		useHeld(client, player);
		client.options.keyUse.setDown(true);
	}

	/** Отпускаем программно зажатую клавишу — безопасно и для физического нажатия. */
	private void releaseUseKey() {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.options != null) {
			client.options.keyUse.setDown(false);
		}
	}

	private String lastName() {
		if (pendingGapple) {
			return "золотое яблоко";
		}
		return pendingEffect == null ? "эффект" : buffName(pendingEffect);
	}

	/**
	 * Ищет питьевое зелье с нужным эффектом: сначала хотбар, потом весь
	 * инвентарь (тогда предмет легитно перекладывается в хотбар).
	 */
	private int findPotionSlot(LocalPlayer player, Holder<MobEffect> wanted) {
		int slot = findPotionSlot(player, wanted, false);
		if (slot < 0) {
			slot = findPotionSlot(player, wanted, true);
		}
		return slot;
	}

	private int findPotionSlot(LocalPlayer player,
	                           Holder<MobEffect> wanted,
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

	private int findGappleSlot(LocalPlayer player) {
		var inventory = player.getInventory();
		for (int pass = 0; pass < 2; pass++) {
			int last = pass == 0 ? 9 : inventory.getContainerSize();
			for (int i = 0; i < last; i++) {
				ItemStack stack = inventory.getItem(i);
				if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
					if (i < 9) {
						return i;
					}
					return swapIntoHotbar(player, i);
				}
			}
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
		// fromSlot 9..35 в InventoryMenu совпадает с индексом Inventory — пересчёт не нужен
		client.gameMode.handleContainerInput(player.containerMenu.containerId,
				com.dreamcast.client.util.SlotMath.inventoryToMenuSlot(fromSlot), hotbarTarget,
				net.minecraft.world.inventory.ContainerInput.SWAP, player);
		return hotbarTarget;
	}

	/** Питьевое зелье (не splash) с нужным эффектом и без вредных. */
	private boolean isBuffPotion(ItemStack stack, Holder<MobEffect> wanted) {
		if (!stack.is(Items.POTION)) {
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

	private static boolean isBad(MobEffectInstance effect) {
		return effect.getEffect() == MobEffects.WEAKNESS
				|| effect.getEffect() == MobEffects.SLOWNESS
				|| effect.getEffect() == MobEffects.POISON
				|| effect.getEffect() == MobEffects.INSTANT_DAMAGE;
	}

	private static void selectSlot(LocalPlayer player, int slot) {
		player.getInventory().setSelectedSlot(slot);
	}

	private static void useHeld(Minecraft client, LocalPlayer player) {
		if (client.gameMode != null) {
			client.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
		}
	}

	private static String buffName(Holder<MobEffect> effect) {
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
		if (effect == MobEffects.NIGHT_VISION) {
			return "ночное зрение";
		}
		if (effect == MobEffects.INSTANT_HEALTH) {
			return "лечение";
		}
		return "эффект";
	}

	private void reset() {
		phase = Phase.IDLE;
		previousSlot = -1;
		pendingSlot = -1;
		pendingEffect = null;
		pendingGapple = false;
		wasLookingDown = false;
		sawUsing = false;
	}
}

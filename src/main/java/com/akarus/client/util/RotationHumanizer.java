package com.akarus.client.util;

import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.AutoMineModule;
import com.akarus.client.module.impl.KillAuraModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.Random;

/**
 * «Человечные» довороты — общий слой для легитных режимов AutoMine и KillAura.
 *
 * Зачем это нужно: Baritone и киллаура в быстром режиме ставят поворот игрока
 * мгновенно и точно в центр блока. Со стороны это выглядит ботом: углы телепортируются,
 * прицел никогда не промахивается, скорость доворота всё время одинаковая. Античиты
 * ловят такое в первую очередь.
 *
 * Два входа:
 * <ul>
 *   <li>{@link #adjust(float, float, float, float)} — перехват чужих установок поворота
 *       (Baritone пишет в {@code Entity#setRot}, миксин {@code EntityMixin} отдаёт запрос
 *       сюда). Большой скачок заменяется плавным доворотом;</li>
 *   <li>{@link #aimTowards(LocalPlayer, float, float)} — собственное прицеливание
 *       киллауры: делает шаг к «человеческой» цели и возвращает углы, которые модуль
 *       пишет игроку уже публичными {@code setYRot}/{@code setXRot}.</li>
 * </ul>
 *
 * Что именно «человечного» в довороте:
 * <ul>
 *   <li><b>не всегда в центр</b> — у каждой новой цели свой «промах» до пары градусов,
 *       который держится, пока цель та же: человек не перезаново промахивается каждый тик;</li>
 *   <li><b>разная скорость</b> — от ленивых ~10°/тик до резких ~48°/тик: иногда быстро,
 *       иногда вразвалочку;</li>
 *   <li><b>шаг «дышит»</b> — скорость каждого шага чуть плавает, траектория не по идеальной
 *       прямой;</li>
 *   <li><b>обычная мышь не трогается</b> — её дельты маленькие и проходят насквозь
 *       (порог {@link #CONTINUATION_DELTA}).</li>
 * </ul>
 */
public final class RotationHumanizer {

	/** Изменение меньше этого считается «продолжением» (мышь, доводка) и проходит как есть. */
	private static final float CONTINUATION_DELTA = 4.0F;

	/** Новый запрос настолько отличается от текущей цели — перебрасываем «промах» и скорость. */
	private static final float NEW_TARGET_DELTA = 22.0F;

	/** Считаем доворот закончившимся, когда до цели осталось меньше этого. */
	private static final float ARRIVE_DELTA = 1.0F;

	/** Промах живёт не дольше этого: даже по той же цели человек время от времени «перехватывается». */
	private static final long TARGET_LIFETIME_MS = 1200L;

	/** Доворот двигается не чаще раза за тик. */
	private static final long STEP_INTERVAL_MS = 40L;

	private static final Random RANDOM = new Random();

	/** Последние реальные углы игрока. */
	private static float currentYaw;
	private static float currentPitch;

	/** «Человеческая» цель доворота (запрошенные углы плюс промах). */
	private static float targetYaw;
	private static float targetPitch;

	/** Что точно просили Baritone или киллаура (без промаха). */
	private static float pendingYaw;
	private static float pendingPitch;

	/** Параметры «человечности» текущей цели: промах и скорость доворота. */
	private static float missYaw;
	private static float missPitch;
	private static float degreesPerStep;

	/** Отслеживаем ли большую цель. */
	private static boolean pending;

	/** Первый вызов после включения: углы ещё не синхронизированы с игроком. */
	private static boolean stale = true;

	private static long lastNewTarget;
	private static long lastStep;

	private RotationHumanizer() {
	}

	/** Работает ли сейчас хотя бы один «легитный» модуль. */
	public static boolean active() {
		AutoMineModule autoMine = ModuleManager.find(AutoMineModule.class);
		if (autoMine != null && autoMine.isEnabled() && autoMine.isLegit()) {
			return true;
		}
		KillAuraModule killAura = ModuleManager.find(KillAuraModule.class);
		return killAura != null && killAura.isEnabled() && killAura.isLegit();
	}

	/**
	 * Перехват чужой установки поворота (Baritone → {@code Entity#setRot} → миксин → сюда).
	 *
	 * @param yaw         запрошенные углы (что хотят поставить)
	 * @param pitch       — // —
	 * @param actualYaw   где игрок реально смотрит прямо сейчас (до установки)
	 * @param actualPitch — // —
	 * @return углы, которыми заменить запрошенные, или null — пропустить запрошенные
	 * как есть (мышь, мелкие доводки, слой выключен)
	 */
	public static float[] adjust(float yaw, float pitch, float actualYaw, float actualPitch) {
		if (!active()) {
			stale = true;
			pending = false;
			return null;
		}

		if (stale) {
			// Первый вызов после включения: стартуем от реального поворота игрока,
			// иначе доворот начинался бы с нуля градусов и крутил камеру по кругу.
			// Дальше идём по общей логике: если запрос и так большой — он очеловечится
			stale = false;
			currentYaw = actualYaw;
			currentPitch = actualPitch;
			pending = false;
		}

		// Тот же большой запрос, что и раньше (Baritone повторяет цель каждый тик) —
		// продолжаем доворот, не пропуская «точный» угол насквозь
		if (pending && Math.abs(Mth.wrapDegrees(yaw - pendingYaw)) < CONTINUATION_DELTA
				&& Math.abs(pitch - pendingPitch) < CONTINUATION_DELTA) {
			return continueTarget(yaw, pitch);
		}

		float deltaYaw = Mth.wrapDegrees(yaw - currentYaw);
		float deltaPitch = pitch - currentPitch;
		if (Math.abs(deltaYaw) < CONTINUATION_DELTA && Math.abs(deltaPitch) < CONTINUATION_DELTA) {
			// Мышиная доводка или крошечная поправка — проходим насквозь и синхронизируемся.
			// Пользователь взял мышь — доворот отменяется до следующего большого запроса
			currentYaw = yaw;
			currentPitch = pitch;
			pending = false;
			return null;
		}

		// Большой скачок: Baritone наводится на очередной блок
		return beginOrContinue(yaw, pitch);
	}

	/**
	 * Собственное прицеливание легитной киллауры: шаг к «человеческой» цели.
	 *
	 * @return углы, которые надо записать игроку через {@code setYRot}/{@code setXRot},
	 * или null, если слой сейчас не активен (тогда киллаура целится точно)
	 */
	public static float[] aimTowards(LocalPlayer player, float yaw, float pitch) {
		if (!active()) {
			pending = false;
			return null;
		}

		if (stale) {
			stale = false;
			pending = false;
			if (player != null) {
				currentYaw = player.getYRot();
				currentPitch = player.getXRot();
			} else {
				currentYaw = yaw;
				currentPitch = pitch;
			}
		}

		if (pending && Math.abs(Mth.wrapDegrees(yaw - pendingYaw)) < CONTINUATION_DELTA
				&& Math.abs(pitch - pendingPitch) < CONTINUATION_DELTA) {
			return continueTarget(yaw, pitch);
		}

		if (Math.abs(Mth.wrapDegrees(yaw - currentYaw)) < CONTINUATION_DELTA
				&& Math.abs(pitch - currentPitch) < CONTINUATION_DELTA) {
			// Прицел и так на цели — мгновенной доводки не требуется
			currentYaw = yaw;
			currentPitch = pitch;
			pending = false;
			return null;
		}

		return beginOrContinue(yaw, pitch);
	}

	/**
	 * Подталкивает незаконченный доворот Baritone между его установками поворота:
	 * цель выдаётся не каждый тик, а камера должна двигаться каждый.
	 */
	public static void tick(LocalPlayer player) {
		if (!pending || player == null) {
			return;
		}
		float[] moved = step();
		if (moved != null) {
			player.setYRot(moved[0]);
			player.setXRot(moved[1]);
		}
	}

	/**
	 * Закончен ли доворот до «человеческой» цели. Киллаура ждёт этого, чтобы бить:
	 * удар мимо прицела — первый шаг к бану.
	 */
	public static boolean arrived() {
		return !pending
				|| (Math.abs(Mth.wrapDegrees(targetYaw - currentYaw)) < 2.0F
						&& Math.abs(targetPitch - currentPitch) < 2.0F);
	}

	// ------------------------------------------------------------------
	// Внутреннее
	// ------------------------------------------------------------------

	/** Та же цель (или её плавное смещение) — держим промах и темп, шаг вперёд. */
	private static float[] continueTarget(float yaw, float pitch) {
		pendingYaw = yaw;
		pendingPitch = pitch;
		targetYaw = pendingYaw + missYaw;
		targetPitch = clampPitch(pendingPitch + missPitch);
		return step();
	}

	/** Большой скачок: новая цель, если она действительно новая — со своим промахом и темпом. */
	private static float[] beginOrContinue(float yaw, float pitch) {
		long now = Util.getMillis();
		if (!pending
				|| Math.abs(Mth.wrapDegrees(yaw - pendingYaw)) > NEW_TARGET_DELTA
				|| now - lastNewTarget > TARGET_LIFETIME_MS) {
			startNewTarget(yaw, pitch, now);
		} else {
			// Цель та же, но игрок успел уйти (например, мышью) — догоняем прежним темпом
			pendingYaw = yaw;
			pendingPitch = pitch;
			targetYaw = pendingYaw + missYaw;
			targetPitch = clampPitch(pendingPitch + missPitch);
		}
		return step();
	}

	/**
	 * Один шаг доворота. Пока цель не достигнута, возвращает текущие углы, которыми
	 * нужно заменить запрошенные (в том числе «подержать» уже найденный промах,
	 * чтобы точный угол Baritone не проходил насквозь); null — если большой цели нет.
	 */
	private static float[] step() {
		if (!pending) {
			return null;
		}

		float deltaYaw = Mth.wrapDegrees(targetYaw - currentYaw);
		float deltaPitch = targetPitch - currentPitch;
		if (Math.abs(deltaYaw) < ARRIVE_DELTA && Math.abs(deltaPitch) < ARRIVE_DELTA) {
			currentYaw = targetYaw;
			currentPitch = targetPitch;
			return new float[]{currentYaw, currentPitch};
		}

		long now = Util.getMillis();
		if (now - lastStep < STEP_INTERVAL_MS) {
			// Рано двигаться — просто держим текущие углы вместо запрошенных
			return new float[]{currentYaw, currentPitch};
		}
		lastStep = now;

		// Скорость шага «дышит»: человек не крутит голову равномерно
		float jitter = 0.85F + RANDOM.nextFloat() * 0.30F;
		currentYaw = Mth.wrapDegrees(currentYaw
				+ Math.signum(deltaYaw) * Math.min(Math.abs(deltaYaw), degreesPerStep * jitter));
		currentPitch = clampPitch(currentPitch
				+ Math.signum(deltaPitch) * Math.min(Math.abs(deltaPitch), degreesPerStep * 0.9F));
		return new float[]{currentYaw, currentPitch};
	}

	/** Новая большая цель: свой промах и своя скорость доворота. */
	private static void startNewTarget(float yaw, float pitch, long now) {
		pending = true;
		pendingYaw = yaw;
		pendingPitch = pitch;
		lastNewTarget = now;

		// Промах: не в центр блока. Гаусс — чтобы «иногда точно, иногда мимо»,
		// а не равномерно размазано
		missYaw = Mth.clamp((float) RANDOM.nextGaussian() * 1.4F, -3.5F, 3.5F);
		missPitch = Mth.clamp((float) RANDOM.nextGaussian() * 0.9F, -2.5F, 2.5F);

		// Скорость доворота: от ленивой до снайперской — «иногда быстро»
		degreesPerStep = 10.0F + RANDOM.nextFloat() * 38.0F;

		targetYaw = pendingYaw + missYaw;
		targetPitch = clampPitch(pendingPitch + missPitch);
	}

	private static float clampPitch(float pitch) {
		return Mth.clamp(pitch, -90.0F, 90.0F);
	}
}

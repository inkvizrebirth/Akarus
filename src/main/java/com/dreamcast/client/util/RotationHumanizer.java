package com.dreamcast.client.util;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.AutoBuffModule;
import com.dreamcast.client.module.impl.AutoMineModule;
import com.dreamcast.client.module.impl.KillAuraModule;
import com.dreamcast.client.module.impl.ScaffoldModule;
import com.dreamcast.client.rotation.RotationCurve;
import com.dreamcast.client.rotation.RotationMath;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.Random;

/**
 * «Человечные» довороты — общий слой для легитных режимов KillAura, Scaffold,
 * AutoBuff и AutoMine (Baritone).
 *
 * <p>Что именно здесь «человеческое»</p>
 * <ul>
 *   <li><b>пружина вместо шага</b> ({@link RotationCurve}): разгон, торможение и
 *       перелёт с возвратом следуют из динамики, а не из константы «°/тик».
 *       Ровного участка скорости нет вообще — ровно то, за что Grim/Matrix
 *       детектят «scripted rotation»;</li>
 *   <li><b>уход взгляда в сторону</b> — иногда (шанс растёт с рандомизацией)
 *       камера на 0.1–0.35 с уходит на 9–35° в сторону от цели и возвращается с
 *       новой микро-реакцией: «глянул мимо, снова взял цель»;
 *       на время отведения прицел считается нестабильным — аура в этот момент не бьёт;</li>
 *   <li><b>перепрыг цели</b> — когда мы в воздухе и цель уходит вниз/под ноги,
 *       слой НЕ начинает прицеливание заново и не «дёргается»: он переходит в
 *       режим сопровождения (демпфированная пружина, без перелёта) и доворачивает
 *       ровно, пока цель не окажется сзади;</li>
 *   <li><b>промах</b> — гауссов и постоянный на время захвата (человек не
 *       перевыбирает точку каждый тик), но новый на каждый новый захват;</li>
 *   <li><b>задержка реакции, спотыкания, дрейф руки</b> — мелкий correlated-шум
 *       вместо белого, ритм шагов не метрономный, «перезанятие» цели раз в
 *       0.7–1.6 с.</li>
 * </ul>
 *
 * <p>Все параметры разыгрываются заново на каждый {@link Engagement}, поэтому
 * два одинаковых доворота подряд не встречаются. Настройки «Скорость доворота»
 * модуля при этом остаётся смыслом: она превращается в потолок скорости пружины
 * (°/с), а не в линейную ступеньку.</p>
 *
 * <p>Два входа: {@link #adjust(float, float, float, float)} — перехват чужих
 * установок поворота (Baritone пишет в {@code Entity#setRot}, миксин
 * {@code EntityMixin} отдаёт запрос сюда); {@link #aimTowards} — собственное
 * прицеливание модулей через {@link com.dreamcast.client.rotation.RotationManager}.</p>
 */
public final class RotationHumanizer {

	/** Изменение меньше этого считается «продолжением» (мышь, доводка) и проходит как есть. */
	private static final float CONTINUATION_DELTA = 4.0F;

	/** Новый запрос настолько отличается от текущей цели — это новая цель, новый «захват». */
	private static final float NEW_TARGET_DELTA = 22.0F;

	/** Считаем шаги завершёнными, когда до цели осталось меньше этого. */
	private static final float ARRIVE_DELTA = 0.45F;

	/** Прыжок питча больше этого в воздухе — цель «уходит под ноги», не новая цель. */
	private static final float PASS_PITCH_JUMP = 18.0F;

	/** И в этом радиусе по рысканию сопровождаем прежним захватом. */
	private static final float PASS_YAW_RADIUS = 70.0F;

	private static final Random RANDOM = new Random();

	/** Последние реальные углы игрока. */
	private static float currentYaw;
	private static float currentPitch;

	/** Текущий «захват» цели со своими параметрами рандомизации. */
	private static Engagement engagement;

	/** Первый вызов после включения: углы ещё не синхронизированы с игроком. */
	private static boolean stale = true;

	private static long lastStep;

	/** Дрейф «руки»: коррелированный шум, а не белый. */
	private static final float[] DRIFT_YAW = new float[1];
	private static final float[] DRIFT_PITCH = new float[1];

	private RotationHumanizer() {
	}

	/** Один «захват» цели: все параметры разыграны заново при создании. */
	private static final class Engagement {
		/** Кто целился: киллаура ведёт прицел сама, Baritone — через adjust(). */
		boolean byKillAura;

		/** Что точно просят (без промаха). */
		float requestYaw;
		float requestPitch;

		/** «Человеческая» цель: запрос плюс промах. */
		float missYaw;
		float missPitch;
		float targetYaw;
		float targetPitch;

		/** Пружина: жёсткость, относительное демпфирование, скорости по осям. */
		float stiffness;
		float damping;
		float velocityYaw;
		float velocityPitch;

		/** Потолок скорости, °/с (0 — без потолка), и множитель для питча. */
		float maxSpeed;
		float pitchRatio;

		/** Дрожь прицела, °, и шанс «рука споткнулась» на шаг. */
		float wobble;
		float hesitation;

		/** Задержка реакции перед началом движения. */
		long reactUntil;

		/** Шанс отвести взгляд в сторону за шаг, пока прицел уже «успел». */
		float glanceChance;
		boolean glancing;
		long glanceUntil;
		float glanceYaw;
		float glancePitch;

		/** Сопровождение цели при перепрыге: ровно, без перелёта и перезанятий. */
		boolean passing;
		long passUntil;

		/** Сколько живёт цель, мс: после этого человек «перезанимается прицеливанием». */
		long bornAt;
		long lifetime;

		/** Доворот дошёл до финальной точки (прицел на цели, можно бить). */
		boolean settled;

		/** Степень рандомизации, 0..1 — снимается с модуля в момент захвата. */
		float intensity;
	}

	/** Работает ли сейчас хотя бы один «легитный» модуль. */
	public static boolean active() {
		return strongestIntensity() > 0.0F;
	}

	/**
	 * Степень рандомизации активного модуля (0..1). Слой один, модулей несколько,
	 * поэтому берём максимум: если у ауры рандомизация 80 %, а у Scaffold 20 %,
	 * «человечность» не должна проседать до 20 % на время установки блоков.
	 */
	public static float intensity() {
		return strongestIntensity();
	}

	private static float strongestIntensity() {
		float value = 0.0F;
		AutoMineModule autoMine = ModuleManager.find(AutoMineModule.class);
		if (autoMine != null && autoMine.isEnabled() && autoMine.isLegit()) {
			value = Math.max(value, autoMine.getRandomization() / 100.0F);
		}
		KillAuraModule killAura = ModuleManager.find(KillAuraModule.class);
		if (killAura != null && killAura.isEnabled() && killAura.isLegit()) {
			value = Math.max(value, killAura.getRandomization() / 100.0F);
		}
		ScaffoldModule scaffold = ModuleManager.find(ScaffoldModule.class);
		if (scaffold != null && scaffold.isEnabled() && scaffold.isLegit()) {
			value = Math.max(value, scaffold.getRandomization() / 100.0F);
		}
		AutoBuffModule buff = ModuleManager.find(AutoBuffModule.class);
		if (buff != null && buff.isEnabled() && buff.isHumanizedThrow()) {
			value = Math.max(value, buff.getRandomization() / 100.0F);
		}
		return Mth.clamp(value, 0.0F, 1.0F);
	}

	/**
	 * Перехват чужой установки поворота (Baritone → {@code Entity#setRot} → миксин → сюда).
	 *
	 * @return углы, которыми заменить запрошенные, или null — пропустить запрошенные
	 * как есть (мышь, мелкие доводки, слой выключен)
	 */
	public static float[] adjust(float yaw, float pitch, float actualYaw, float actualPitch) {
		if (!active()) {
			stale = true;
			engagement = null;
			return null;
		}

		if (stale) {
			// Первый вызов после включения: стартуем от реального поворота игрока
			stale = false;
			currentYaw = actualYaw;
			currentPitch = actualPitch;
			engagement = null;
		}

		return route(yaw, pitch, false, null, 0.0F);
	}

	/**
	 * Собственное прицеливание легитных модулей: шаг к «человеческой» цели.
	 *
	 * @return углы, которые надо записать игроку через {@code setYRot}/{@code setXRot},
	 * или null, если слой сейчас не активен (тогда модуль целится точно)
	 */
	public static float[] aimTowards(LocalPlayer player, float yaw, float pitch) {
		return aimTowards(player, yaw, pitch,
				player == null ? yaw : player.getYRot(),
				player == null ? pitch : player.getXRot(), 0.0F);
	}

	/**
	 * Тот же доворот для {@link com.dreamcast.client.rotation.RotationManager}:
	 * стартовые углы передаются отдельно, потому что в «сайлент»-режиме реальные
	 * углы игрока — это взгляд человека, а не то, куда уже наведён слой.
	 *
	 * @param baseYaw   угол, от которого стартуем (обычно текущий боевой угол слоя)
	 * @param basePitch то же для питча
	 * @param degreesPerTick потолок скорости из настройки модуля (°/тик, 0 — без потолка)
	 */
	public static float[] aimTowards(LocalPlayer player, float yaw, float pitch,
	                                 float baseYaw, float basePitch, float degreesPerTick) {
		if (!active()) {
			engagement = null;
			return null;
		}

		if (stale) {
			stale = false;
			engagement = null;
			currentYaw = baseYaw;
			currentPitch = basePitch;
		}

		return route(yaw, pitch, true, player, degreesPerTick);
	}

	/**
	 * Подталкивает незаконченный доворот Baritone между его установками поворота:
	 * цель выдаётся не каждый тик, а камера должна двигаться каждый.
	 */
	public static void tick(LocalPlayer player) {
		if (player == null || engagement == null || engagement.byKillAura) {
			return;
		}
		float[] moved = advance(Util.getMillis());
		if (moved != null) {
			player.setYRot(moved[0]);
			player.setXRot(moved[1]);
		}
	}

	/**
	 * Дошёл ли доворот до финальной точки (прицел на цели, перелёт выбран).
	 * Киллаура ждёт этого, чтобы бить: удар мимо прицела — первый шаг к бану.
	 * Во время отведения взгляда ответ всегда «нет» — бить вслепую мы не будем.
	 */
	public static boolean settled() {
		return engagement == null || (engagement.settled && !engagement.glancing);
	}

	/** Смотрим ли мы сейчас «мимо» цели (отведение взгляда)? Полезно для отладки и HUD. */
	public static boolean glancing() {
		return engagement != null && engagement.glancing;
	}

	/** Сброс между мирами/включениями модуля: иначе пружина стартует с чужими углами. */
	public static void reset() {
		engagement = null;
		stale = true;
		lastStep = 0L;
		DRIFT_YAW[0] = 0.0F;
		DRIFT_PITCH[0] = 0.0F;
	}

	// ------------------------------------------------------------------
	// Маршрут: та же цель, новая цель, сопровождение или «прицел уже там»
	// ------------------------------------------------------------------

	private static float[] route(float yaw, float pitch, boolean byKillAura,
	                             LocalPlayer player, float degreesPerTick) {
		long now = Util.getMillis();
		Engagement current = engagement;

		if (current != null && looksLikeVerticalPass(current, yaw, pitch, player)) {
			// Цель уходит вниз (мы её перепрыгиваем): не новый захват, а ровное
			// сопровождение прежним — без рывка и без перелёта через игрока
			current.requestYaw = yaw;
			current.requestPitch = pitch;
			current.targetYaw = yaw + current.missYaw;
			current.targetPitch = clampPitch(pitch + current.missPitch);
			current.passing = true;
			current.passUntil = now + 280L + RANDOM.nextInt(240);
			current.bornAt = now;
			current.settled = false;
			return advance(now);
		}

		if (current != null && Math.abs(Mth.wrapDegrees(yaw - current.requestYaw)) < NEW_TARGET_DELTA
				&& Math.abs(pitch - current.requestPitch) < NEW_TARGET_DELTA) {
			// Та же цель (пусть и уплывшая на десяток градусов): тянем точную точку
			// за ней, а промах, темп и «отвлечения» остаются своими
			current.requestYaw = yaw;
			current.requestPitch = pitch;
			current.targetYaw = yaw + current.missYaw;
			current.targetPitch = clampPitch(pitch + current.missPitch);

			if (now - current.bornAt > current.lifetime) {
				// Цель «протухла»: даже по той же цели человек время от времени
				// перезанимается прицеливанием — с новым промахом и темпом
				engagement = current = newEngagement(yaw, pitch, now, byKillAura, degreesPerTick);
			}
			return advance(now);
		}

		float deltaYaw = Mth.wrapDegrees(yaw - currentYaw);
		float deltaPitch = pitch - currentPitch;
		if (Math.abs(deltaYaw) < CONTINUATION_DELTA && Math.abs(deltaPitch) < CONTINUATION_DELTA) {
			// Прицел уже там (мышь довела, цель в паре градусов) — синхронизируемся
			// и не мешаем: мгновенной доводки на пару градусов человеку не занимать
			currentYaw = yaw;
			currentPitch = pitch;
			engagement = null;
			return null;
		}

		// Большой скачок: новая цель (или цель сильно увела) — новый захват
		engagement = newEngagement(yaw, pitch, now, byKillAura, degreesPerTick);
		return advance(now);
	}

	/** Прыжок вниз по питчу, пока мы в воздухе, — это перепрыг цели, а не новая цель. */
	private static boolean looksLikeVerticalPass(Engagement current, float yaw, float pitch, LocalPlayer player) {
		if (player == null || Math.abs(Mth.wrapDegrees(yaw - current.requestYaw)) > PASS_YAW_RADIUS) {
			return false;
		}
		boolean airborne = !player.onGround() || player.fallDistance > 0.4F || player.getDeltaMovement().y > 0.06;
		return airborne && Math.abs(pitch - current.requestPitch) > PASS_PITCH_JUMP;
	}

	// ------------------------------------------------------------------
	// Движение
	// ------------------------------------------------------------------

	/** Один шаг: реакция, пружина, дрожь, отведение взгляда, «спотыкания». */
	private static float[] advance(long now) {
		Engagement engagement = RotationHumanizer.engagement;
		if (engagement == null) {
			return null;
		}

		// Задержка реакции: между «увидел» и «поехал» у человека есть пауза
		if (now < engagement.reactUntil) {
			return hold();
		}

		// Рука «споткнулась»: крошечная пауза посреди доворота
		if (!engagement.glancing && RANDOM.nextFloat() < engagement.hesitation) {
			return hold();
		}

		if (engagement.glancing && now >= engagement.glanceUntil) {
			endGlance(engagement, now);
		}
		if (engagement.passing && now >= engagement.passUntil) {
			engagement.passing = false;
		}

		float dt = lastStep == 0L ? 0.05F : (now - lastStep) / 1000.0F;
		lastStep = now;

		float aimYaw = engagement.targetYaw + (engagement.glancing ? engagement.glanceYaw : 0.0F);
		float aimPitch = clampPitch(engagement.targetPitch + (engagement.glancing ? engagement.glancePitch : 0.0F));

		// В сопровождении — критическое демпфирование (едет ровно, без перелёта);
		// в обычном режиме пружина слегка недодемпфирована: перелёт и возврат сами
		float stiffness = engagement.passing ? engagement.stiffness * 0.45F : engagement.stiffness;
		float damping = engagement.passing ? 1.0F : engagement.damping;

		RotationCurve.State yawState = RotationCurve.approach(
				new RotationCurve.State(currentYaw, engagement.velocityYaw),
				aimYaw, stiffness, damping, dt, engagement.maxSpeed);
		RotationCurve.State pitchState = RotationCurve.approach(
				new RotationCurve.State(currentPitch, engagement.velocityPitch),
				aimPitch, stiffness * 0.85F, damping, dt,
				engagement.maxSpeed > 0.0F ? engagement.maxSpeed * engagement.pitchRatio : 0.0F);

		engagement.velocityYaw = yawState.velocity();
		engagement.velocityPitch = pitchState.velocity();
		currentYaw = RotationMath.wrap(yawState.position());
		currentPitch = clampPitch(pitchState.position());

		// Дрейф руки: correlated-шум, тем сильнее, чем больше рандомизации
		RotationCurve.drift(RANDOM, DRIFT_YAW, engagement.wobble * 0.16F);
		RotationCurve.drift(RANDOM, DRIFT_PITCH, engagement.wobble * 0.1F);
		currentYaw = RotationMath.wrap(currentYaw + DRIFT_YAW[0]);
		currentPitch = clampPitch(currentPitch + DRIFT_PITCH[0]);

		boolean arrived = Math.abs(Mth.wrapDegrees(aimYaw - currentYaw)) < ARRIVE_DELTA
				&& Math.abs(aimPitch - currentPitch) < ARRIVE_DELTA;
		engagement.settled = arrived && !engagement.glancing;

		// Отведение взгляда — только когда уже прицелились, не в сопровождении и
		// не на нулевой рандомизации: иначе «человечность» превращается в потерянный DPS
		if (engagement.settled && !engagement.passing && engagement.intensity > 0.05F
				&& RANDOM.nextFloat() < engagement.glanceChance) {
			startGlance(engagement, now);
		}
		return new float[]{currentYaw, currentPitch};
	}

	private static void startGlance(Engagement engagement, long now) {
		engagement.glancing = true;
		engagement.glanceUntil = now + 90L + RANDOM.nextInt(240);
		float spread = 9.0F + 26.0F * engagement.intensity;
		engagement.glanceYaw = (RANDOM.nextBoolean() ? 1.0F : -1.0F)
				* spread * (0.35F + RANDOM.nextFloat() * 0.65F);
		engagement.glancePitch = (RANDOM.nextFloat() * 2.0F - 1.0F) * (4.0F + 12.0F * engagement.intensity);
		engagement.settled = false;
	}

	/** Возврат из отведения: человек «снова берёт» цель — с микро-реакцией и новым промахом. */
	private static void endGlance(Engagement engagement, long now) {
		engagement.glancing = false;
		engagement.glanceYaw = 0.0F;
		engagement.glancePitch = 0.0F;
		engagement.reactUntil = now + 40L + RANDOM.nextInt(90);
		engagement.velocityYaw *= 0.35F;
		engagement.velocityPitch *= 0.35F;
		engagement.missYaw = Mth.clamp((float) RANDOM.nextGaussian() * (0.5F + 2.1F * engagement.intensity), -3.6F, 3.6F);
		engagement.missPitch = Mth.clamp((float) RANDOM.nextGaussian() * (0.35F + 1.15F * engagement.intensity), -2.2F, 2.2F);
		engagement.targetYaw = engagement.requestYaw + engagement.missYaw;
		engagement.targetPitch = clampPitch(engagement.requestPitch + engagement.missPitch);
		engagement.bornAt = now;
		engagement.lifetime = 700L + RANDOM.nextInt(900);
	}

	private static float[] hold() {
		return new float[]{currentYaw, currentPitch};
	}

	// ------------------------------------------------------------------
	// Новый захват: вся рандомизация разыгрывается заново
	// ------------------------------------------------------------------

	private static Engagement newEngagement(float yaw, float pitch, long now,
	                                        boolean byKillAura, float degreesPerTick) {
		Engagement engagement = new Engagement();
		engagement.byKillAura = byKillAura;
		engagement.intensity = intensity();

		engagement.bornAt = now;
		// Цель живёт 0.7–1.6 с: по той же цели человек перезанимается прицеливанием
		engagement.lifetime = 700L + RANDOM.nextInt(900);

		// Задержка реакции: 30–170 мс по степени рандомизации, изредка «задумчивость»
		long react = 30L + (long) (140.0F * engagement.intensity);
		if (RANDOM.nextInt(100) < 12) {
			react += 60L + RANDOM.nextInt(140);
		}
		engagement.reactUntil = now + react;

		// Промах: гауссов, тем шире, чем выше рандомизация. При 0 — прицельно, но не в центр
		engagement.missYaw = Mth.clamp((float) RANDOM.nextGaussian() * (0.5F + 2.1F * engagement.intensity), -3.6F, 3.6F);
		engagement.missPitch = Mth.clamp((float) RANDOM.nextGaussian() * (0.35F + 1.15F * engagement.intensity), -2.2F, 2.2F);
		engagement.requestYaw = yaw;
		engagement.requestPitch = pitch;
		engagement.targetYaw = yaw + engagement.missYaw;
		engagement.targetPitch = clampPitch(pitch + engagement.missPitch);

		// Пружина: жёсткость — «злость» доводки, демпфирование — есть ли перелёт.
		// Оба разыгрываются заново на каждый захват, поэтому и разгон, и торможение
		// каждый раз своей формы
		engagement.stiffness = 26.0F + RANDOM.nextFloat() * 58.0F + 30.0F * engagement.intensity;
		engagement.damping = RotationCurve.underDamped(RANDOM, engagement.intensity);
		engagement.pitchRatio = 0.55F + RANDOM.nextFloat() * 0.5F;

		// Потолок скорости — из настройки модуля: °/тик → °/с (20 тиков в секунду)
		engagement.maxSpeed = degreesPerTick > 0.0F ? degreesPerTick * 20.0F : 0.0F;

		// Дрожь, «спотыкания» и шанс отвести взгляд растут с рандомизацией
		engagement.wobble = (0.05F + 0.22F * engagement.intensity) * (0.5F + RANDOM.nextFloat());
		engagement.hesitation = 0.02F + 0.04F * engagement.intensity;
		engagement.glanceChance = 0.004F + 0.02F * engagement.intensity;

		DRIFT_YAW[0] = 0.0F;
		DRIFT_PITCH[0] = 0.0F;
		engagement.settled = false;
		return engagement;
	}

	private static float clampPitch(float pitch) {
		return Mth.clamp(pitch, -90.0F, 90.0F);
	}
}

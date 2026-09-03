package com.dreamcast.client.util;

import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.AutoMineModule;
import com.dreamcast.client.module.impl.KillAuraModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.Random;

/**
 * «Человечные» довороты — общий слой для легитных режимов AutoMine и KillAura.
 *
 * Baritone и киллаура в быстром режиме ставят поворот мгновенно и точно в центр.
 * Со стороны это выглядит ботом: углы телепортируются, прицел никогда не промахивается,
 * скорость доворота одинаковая. Античиты ловят такое в первую очередь.
 *
 * Как сделан «человек» здесь (и почему это не повторяющийся паттерн)
 * ------------------------------------------------------------------
 * На каждую новую цель собирается свежий «захват» ({@link Engagement}), и <b>все</b>
 * его параметры разыгрываются заново — двух одинаковых доворотов не бывает:
 * <ul>
 *   <li><b>промах</b> — гауссов, до пары градусов: не в центр, и у каждой цели свой,
 *       пока цель та же (человек не перезаново промахивается каждый тик);</li>
 *   <li><b>профиль скорости</b> — «снайпер» (34–50°/шаг), «обычный» (16–36) или
 *       «ленивый» (8–18): иногда резко, иногда вразвалочку;</li>
 *   <li><b>перелёт и коррекция</b> — человек чаще всего проскакивает цель на градус-другой
 *       и коротким доездом возвращается: направление и величина случайны;</li>
 *   <li><b>торможение</b> — скорость падает с остатком расстояния (экспоненциальный
 *       доезд), а не отсекается ступенькой;</li>
 *   <li><b>дрожь</b> — микроскопический шум на каждом шаге и «дыхание» прицела
 *       на уже достигнутой цели: прицел никогда не стоит идеально неподвижно;</li>
 *   <li><b>задержка реакции</b> — перед началом доворота 30–170 мс (иногда с «задумчивостью»);</li>
 *   <li><b>шаг «дышит»</b> — интервал и величина каждого шага плавают, изредка рука
 *       «спотыкается» (крошечная пауза в движении).</li>
 * </ul>
 *
 * Степень шума задаёт настройка «Рандомизация, %» в модуле (0 — почти без шума,
 * максимум буста; 100 — максимум человечности). Обычная мышь не трогается:
 * её дельты маленькие и проходят насквозь.
 *
 * Два входа:
 * <ul>
 *   <li>{@link #adjust(float, float, float, float)} — перехват чужих установок поворота
 *       (Baritone пишет в {@code Entity#setRot}, миксин {@code EntityMixin} отдаёт запрос сюда);</li>
 *   <li>{@link #aimTowards(LocalPlayer, float, float)} — собственное прицеливание
 *       киллауры: шаг к «человеческой» цели, углы модуль пишет игроку публичными
 *       {@code setYRot}/{@code setXRot}.</li>
 * </ul>
 */
public final class RotationHumanizer {

	/** Изменение меньше этого считается «продолжением» (мышь, доводка) и проходит как есть. */
	private static final float CONTINUATION_DELTA = 4.0F;

	/** Новый запрос настолько отличается от текущей цели — это новая цель, новый «захват». */
	private static final float NEW_TARGET_DELTA = 22.0F;

	/** Считаем шаги завершёнными, когда до цели осталось меньше этого. */
	private static final float ARRIVE_DELTA = 0.8F;

	private static final Random RANDOM = new Random();

	/** Последние реальные углы игрока. */
	private static float currentYaw;
	private static float currentPitch;

	/** Текущий «захват» цели со своими параметрами рандомизации. */
	private static Engagement engagement;

	/** Первый вызов после включения: углы ещё не синхронизированы с игроком. */
	private static boolean stale = true;

	private static long lastStep;

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

		/** Перелёт мимо цели, который потом корректируется коротким доездом. */
		float overshootYaw;
		float overshootPitch;
		boolean correcting;
		float correctSpeed;

		/** Пик скорости доворота, °/шаг, и параметры торможения. */
		float speedYaw;
		float pitchRatio;
		float brake;

		/** Дрожь прицела, °, и шанс «рука споткнулась» на шаг. */
		float wobble;
		float hesitation;

		/** Задержка реакции перед началом движения и пауза между шагами. */
		long reactUntil;
		int stepInterval;

		/** Сколько живёт цель, мс: после этого человек «перезанимается прицеливанием». */
		long bornAt;
		long lifetime;

		/** Доворот дошёл до финальной точки (с учётом коррекции перелёта). */
		boolean settled;

		/** Степень рандомизации, 0..1 — снимается с модуля в момент захвата. */
		float intensity;
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

	/** Степень рандомизации активного модуля (0..1); если активны оба — берём максимум. */
	private static float intensity() {
		float value = 0.0F;
		AutoMineModule autoMine = ModuleManager.find(AutoMineModule.class);
		if (autoMine != null && autoMine.isEnabled() && autoMine.isLegit()) {
			value = Math.max(value, autoMine.getRandomization() / 100.0F);
		}
		KillAuraModule killAura = ModuleManager.find(KillAuraModule.class);
		if (killAura != null && killAura.isEnabled() && killAura.isLegit()) {
			value = Math.max(value, killAura.getRandomization() / 100.0F);
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

		return route(yaw, pitch, false);
	}

	/**
	 * Собственное прицеливание легитной киллауры: шаг к «человеческой» цели.
	 *
	 * @return углы, которые надо записать игроку через {@code setYRot}/{@code setXRot},
	 * или null, если слой сейчас не активен (тогда киллаура целится точно)
	 */
	public static float[] aimTowards(LocalPlayer player, float yaw, float pitch) {
		if (!active()) {
			engagement = null;
			return null;
		}

		if (stale) {
			stale = false;
			engagement = null;
			if (player != null) {
				currentYaw = player.getYRot();
				currentPitch = player.getXRot();
			} else {
				currentYaw = yaw;
				currentPitch = pitch;
			}
		}

		return route(yaw, pitch, true);
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
	 * Дошёл ли доворот до финальной точки (перелёт скорректирован, прицел «успел»).
	 * Киллаура ждёт этого, чтобы бить: удар мимо прицела — первый шаг к бану.
	 */
	public static boolean settled() {
		return engagement == null || engagement.settled;
	}

	// ------------------------------------------------------------------
	// Маршрут: та же цель, новая цель или «прицел уже там»
	// ------------------------------------------------------------------

	private static float[] route(float yaw, float pitch, boolean byKillAura) {
		long now = Util.getMillis();
		Engagement current = engagement;

		if (current != null
				&& Math.abs(Mth.wrapDegrees(yaw - current.requestYaw)) < NEW_TARGET_DELTA
				&& Math.abs(pitch - current.requestPitch) < NEW_TARGET_DELTA) {
			// Та же цель (пусть и уплывшая на десяток градусов): тянем точную точку
			// за ней, а промах и темп захвата остаются своими
			current.requestYaw = yaw;
			current.requestPitch = pitch;
			current.targetYaw = yaw + current.missYaw;
			current.targetPitch = clampPitch(pitch + current.missPitch);

			if (now - current.bornAt > current.lifetime) {
				// Цель «протухла»: даже по той же цели человек время от времени
				// перезанимается прицеливанием — с новым промахом и темпом
				engagement = current = newEngagement(yaw, pitch, now, byKillAura);
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

		// Большой скачок: новая цель (или цель сильно увела) — новый захват.
		// Отдаём его тому, кто спросил: у киллауры и Baritone свои сценарии
		engagement = newEngagement(yaw, pitch, now, byKillAura);
		return advance(now);
	}

	// ------------------------------------------------------------------
	// Движение
	// ------------------------------------------------------------------

	/** Один шаг доворота: реакция, разгон, торможение, перелёт, коррекция, дрожь. */
	private static float[] advance(long now) {
		Engagement engagement = RotationHumanizer.engagement;
		if (engagement == null) {
			return null;
		}

		// Задержка реакции: между «увидел» и «поехал» у человека есть пауза
		if (now < engagement.reactUntil) {
			return hold();
		}

		// Пауза между шагами плавает — движение не метрономное
		if (now - lastStep < engagement.stepInterval) {
			return hold();
		}

		// Куда сейчас едем: до перелёта — в точку за целью, после — обратно в цель
		float aimYaw = engagement.correcting ? engagement.targetYaw : engagement.targetYaw + engagement.overshootYaw;
		float aimPitch = engagement.correcting ? engagement.targetPitch : engagement.targetPitch + engagement.overshootPitch;

		float deltaYaw = Mth.wrapDegrees(aimYaw - currentYaw);
		float deltaPitch = aimPitch - currentPitch;

		if (Math.abs(deltaYaw) < ARRIVE_DELTA && Math.abs(deltaPitch) < ARRIVE_DELTA) {
			if (!engagement.correcting && (Math.abs(engagement.overshootYaw) > 0.35F || Math.abs(engagement.overshootPitch) > 0.35F)) {
				// Доехали до точки перелёта — разворачиваемся на коррекцию, медленно
				engagement.correcting = true;
				engagement.correctSpeed = Math.max(1.4F, engagement.speedYaw * 0.35F);
				return hold();
			}

			// Финал: прицел на цели, но не идеально неподвижный — «дышит»
			engagement.settled = true;
			currentYaw = Mth.wrapDegrees(engagement.targetYaw + (float) RANDOM.nextGaussian() * engagement.wobble * 0.3F);
			currentPitch = clampPitch(engagement.targetPitch + (float) RANDOM.nextGaussian() * engagement.wobble * 0.2F);
			redrawStepInterval(engagement);
			return new float[]{currentYaw, currentPitch};
		}

		engagement.settled = false;

		// Рука «споткнулась»: крошечная пауза посреди доворота
		if (RANDOM.nextFloat() < engagement.hesitation) {
			return hold();
		}

		lastStep = now;
		redrawStepInterval(engagement);

		float speed = engagement.correcting ? engagement.correctSpeed : engagement.speedYaw;
		// Скорость шага «дышит»: человек не крутит голову равномерно
		float jitter = 0.80F + RANDOM.nextFloat() * 0.45F;

		// Торможение: пока далеко — пик скорости, у цели — доля остатка (плавный доезд)
		float stepYaw = stepSize(deltaYaw, speed * jitter, engagement.brake);
		float stepPitch = stepSize(deltaPitch, speed * jitter * engagement.pitchRatio, engagement.brake);

		currentYaw = Mth.wrapDegrees(currentYaw + Math.signum(deltaYaw) * stepYaw
				+ (float) RANDOM.nextGaussian() * engagement.wobble);
		currentPitch = clampPitch(currentPitch + Math.signum(deltaPitch) * stepPitch
				+ (float) RANDOM.nextGaussian() * engagement.wobble * 0.6F);
		return new float[]{currentYaw, currentPitch};
	}

	/** Шаг к цели: пик скорости вдали, торможение пропорционально остатку — и никогда не ноль. */
	private static float stepSize(float delta, float peak, float brake) {
		float distance = Math.abs(delta);
		return Math.min(distance, Math.max(0.12F, Math.min(peak, distance * brake + 0.05F)));
	}

	private static void redrawStepInterval(Engagement engagement) {
		// Больше рандомизации — более вальяжный ритм; и всегда с плавающим окном
		int base = 30 + (int) (18.0F * engagement.intensity);
		engagement.stepInterval = base + RANDOM.nextInt(11);
	}

	private static float[] hold() {
		return new float[]{currentYaw, currentPitch};
	}

	// ------------------------------------------------------------------
	// Новый захват: вся рандомизация разыгрывается заново
	// ------------------------------------------------------------------

	private static Engagement newEngagement(float yaw, float pitch, long now, boolean byKillAura) {
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

		// Перелёт: чаще с ростом рандомизации; величина случайна, направление — туда, куда ехали
		if (RANDOM.nextFloat() < 0.35F + 0.35F * engagement.intensity) {
			float magnitude = Math.min((0.3F + 2.0F * engagement.intensity) * (0.3F + RANDOM.nextFloat() * 0.7F), 2.6F);
			float sign = Math.signum(Mth.wrapDegrees(yaw - currentYaw));
			if (sign == 0.0F) {
				sign = RANDOM.nextBoolean() ? 1.0F : -1.0F;
			}
			engagement.overshootYaw = sign * magnitude;
			engagement.overshootPitch = RANDOM.nextBoolean()
					? Math.signum(pitch - currentPitch) * magnitude * (0.3F + RANDOM.nextFloat() * 0.5F)
					: 0.0F;
		} else {
			engagement.overshootYaw = 0.0F;
			engagement.overshootPitch = 0.0F;
		}

		// Профиль скорости: снайпер / обычный / ленивый — вероятности фиксированы,
		// но сами величины и всё остальное случайны, так что паттерн не повторяется
		float roll = RANDOM.nextFloat();
		engagement.speedYaw = roll < 0.18F
				? 34.0F + RANDOM.nextFloat() * 16.0F
				: roll < 0.75F ? 16.0F + RANDOM.nextFloat() * 20.0F
						: 8.0F + RANDOM.nextFloat() * 10.0F;

		// Питч обычно медленнее рыскания — на сколько именно, разыгрываем каждый раз
		engagement.pitchRatio = 0.55F + RANDOM.nextFloat() * 0.5F;
		engagement.brake = 0.22F + RANDOM.nextFloat() * 0.2F;

		// Дрожь и «спотыкания» растут с рандомизацией
		engagement.wobble = (0.05F + 0.22F * engagement.intensity) * (0.5F + RANDOM.nextFloat());
		engagement.hesitation = 0.02F + 0.04F * engagement.intensity;

		redrawStepInterval(engagement);
		engagement.settled = false;
		return engagement;
	}

	private static float clampPitch(float pitch) {
		return Mth.clamp(pitch, -90.0F, 90.0F);
	}
}

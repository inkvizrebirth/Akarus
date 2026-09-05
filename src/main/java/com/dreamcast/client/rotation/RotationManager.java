package com.dreamcast.client.rotation;

import com.dreamcast.client.util.RotationHumanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Единый слой поворотов клиента — «своя камера» у атакующих модулей.
 *
 * <p>Почему это вообще нужно. Раньше KillAura (и AutoBuff при броске, и Scaffold
 * в «видимом» режиме) писала углы прямо игроку: {@code player.setYRot(...)}.
 * А игрок — это одновременно камера, движение и пакеты. В итоге аура разворачивала
 * экран игрока: человек бьёт того, кто за спиной, сам глядя в стену, а W уводит
 * не туда, куда он смотрит. Правильное поведение ауры — целиться СВОИМ поворотом,
 * не трогая камеру игрока.</p>
 *
 * <p>Как устроено здесь (схема, которую используют Meteor и LiquidBounce):</p>
 * <ul>
 *   <li><b>Silent</b> (по умолчанию) — у игрока НЕ трогаются углы вообще. Слой
 *       хранит <i>своё</i> прицеливание и подменяет им поворот ровно на время
 *       формирования пакета движения (миксин {@code LocalPlayerRotationMixin}
 *       вокруг {@code LocalPlayer#sendPosition()}). Сервер получает взгляд ауры,
 *       все его проверки reach/angle проходят, а камера и движение остаются
 *       игрока.</li>
 *   <li><b>Visible</b> — прежнее поведение: углы пишутся игроку, камера едет за
 *       целью. Нужен тем, кто хочет «как ваниль, только автоматически».</li>
 *   <li><b>None</b> — слой не целится вовсе: бить можно только то, во что и так
 *       смотрит игрок (чистый aim-режим для строгих античитов).</li>
 * </ul>
 *
 * <p>Каналы и приоритеты. Поворот просит один владелец за раз: аура важнее
 * AutoBuff, AutoBuff важнее Scaffold (см. {@code PRIORITY_*}). Претендент с
 * меньшим приоритетом в этом тике не получает ничего и должен сам решить, что
 * делать (аура просто ждёт, Scaffold — пропускает установку).</p>
 *
 * <p>Синхронизация. {@link #inSync()} отвечает «сервер уже знает наш текущий
 * поворот». Бить раньше — значит отправить удар под старым углом: ровно то, за
 * что Grim и Matrix выдают флаги «rotation mismatch». Если миксин по какой-то
 * причине не применился (переименованный метод в обновлении игры), слой сам
 * шлёт пакет поворота перед действием — {@link #syncBeforeAction}.</p>
 */
public final class RotationManager {

	/** Куда слой целится и кому это разрешено показывать. */
	public enum Mode {
		/** Только пакеты: камера игрока не двигается. */
		SILENT,
		/** Видимый поворот: углы пишутся игроку (камера едет за целью). */
		VISIBLE,
		/** Не доворачиваемся: работаем тем, куда смотрит игрок. */
		NONE
	}

	/** Коррекция движения — имеет смысл только в {@link Mode#VISIBLE}. */
	public enum Movement {
		/** Ввод не трогаем. */
		NONE,
		/** W ведёт туда, куда смотрел игрок, а не туда, куда навела аура. */
		FREE,
		/** Аура сама ведёт игрока в центр цели (клавиши жмёт модуль). */
		FOCUSED,
		/** Как FREE, но аура клавиши не трогает вовсе. */
		LEGIT
	}

	public static final int PRIORITY_AURA = 100;
	public static final int PRIORITY_BUFF = 80;
	public static final int PRIORITY_BUILD = 60;

	/** Сколько тиков владельца считается «живым», если он не продлевал заявку. */
	private static final int GRACE_TICKS = 2;

	/** Дельта мыши больше этой — считаем, что игрока телепортинули/развернули. */
	private static final float USER_JUMP = 40.0F;

	private static Object owner;
	private static int ownerPriority;
	private static Mode mode = Mode.NONE;
	private static Movement movement = Movement.NONE;
	private static boolean humanizing;

	/** Боевые углы слоя: именно они уходят на сервер. */
	private static float yaw;
	private static float pitch;
	/** Куда просят прийти (для проверки «навелись ли»). */
	private static float wantedYaw;
	private static float wantedPitch;

	/** Взгляд самого игрока — то, что должно остаться на экране. */
	private static float userYaw;
	private static float userPitch;
	/** Что мы в последний раз записали игроку (чтобы отделить мышь от ауры). */
	private static float lastWrittenYaw;
	private static float lastWrittenPitch;
	private static boolean trackingUser;

	/** Значения, которые реально ушли на сервер (для {@link #inSync()}). */
	private static float sentYaw = Float.NaN;
	private static float sentPitch = Float.NaN;
	/** В этом тике пакет поворота уже отправлен (миксином или вручную). */
	private static boolean synced;
	/** Сервер хотя бы раз получал наш поворот (то есть «тишина» в пакете — не аргумент). */
	private static boolean applied;
	/** Миксин {@code LocalPlayer#sendPosition} хотя бы раз отработал. */
	private static boolean movementPacketHook;

	private static int staleTicks;
	/** Мир, к которому относится состояние слоя; сменился — полный сброс. */
	private static net.minecraft.client.multiplayer.ClientLevel activeLevel;

	/** Временное сохранение углов игрока на время формирования пакета. */
	private static boolean packetApplied;
	private static float playerYawBefore;
	private static float playerPitchBefore;

	private RotationManager() {
	}

	// ------------------------------------------------------------------
	// Заявка на поворот
	// ------------------------------------------------------------------

	/**
	 * Модуль заявляет, куда он хочет целиться в этом тике.
	 *
	 * @param requester модуль-владелец (сравнение по identity)
	 * @param priority  {@code PRIORITY_*}
	 * @param wanted   желаемый режим поворота
	 * @param speed     ограничение скорости доворота, °/тик (0 — мгновенно)
	 * @param humanize  очеловечивать ли доворот (см. {@link RotationHumanizer})
	 * @return {@code true}, если заявка выиграла и углы слоя — это углы модуля
	 */
	public static boolean request(Object requester, int priority, Mode wanted, Movement wantedMovement,
	                              float speed, boolean humanize, float targetYaw, float targetPitch) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			return false;
		}
		if (activeLevel != client.level) {
			forget();
			activeLevel = client.level;
		}

		if (owner != null && owner != requester && priority < ownerPriority) {
			// Более важный владелец уже целится в этом тике — мы не мешаем.
			// Счётчик «протухания» при этом НЕ обнуляем: иначе проигравший
			// заявкой держал бы слой занятым, даже когда победитель исчез.
			return false;
		}
		staleTicks = 0;
		if (owner != requester) {
			// Новый слой стартует от того, куда смотрит игрок, — без рывка
			// через всю карту; предыдущего владельца отпускаем молча (VISIBLE-поворот
			// никто не «отматывает» назад: игрок сам доведёт камеру мышью)
			humanizing = humanize;
			yaw = player.getYRot();
			pitch = player.getXRot();
			trackingUser = false;
		}
		owner = requester;
		ownerPriority = priority;
		mode = wanted;
		movement = wantedMovement;
		humanizing = humanize;
		wantedYaw = targetYaw;
		wantedPitch = RotationMath.clampPitch(targetPitch);

		observeUser(player);

		float step = Math.max(0.0F, speed);
		float baseYaw = mode == Mode.SILENT || mode == Mode.NONE ? yaw : player.getYRot();
		float basePitch = mode == Mode.SILENT || mode == Mode.NONE ? pitch : player.getXRot();

		float nextYaw;
		float nextPitch;
		if (mode == Mode.NONE) {
			// Целимся ровно тем, что смотрит игрок
			nextYaw = player.getYRot();
			nextPitch = player.getXRot();
		} else if (humanize) {
			// Человеческий доворот: пружина, промахи, отведения взгляда — математика
			// в RotationHumanizer. Важно: сюда НЕ прикладывается обычный
			// «stepYaw(base, x, speed)» — он бы обрезал кривую до ровной ступеньки,
			// то есть вернул ровно ту линейность, из-за которой ротации и режут.
			// Настройка «Скорость доворота» уходит в слой как потолок скорости, а
			// страховка от сумасшедшего скачка — 3 заявленных шага за тик.
			float[] smoothed = RotationHumanizer.aimTowards(player, wantedYaw, wantedPitch,
					baseYaw, basePitch, step);
			if (smoothed != null) {
				float rail = step > 0.0F ? step * 3.0F : Float.MAX_VALUE;
				nextYaw = RotationMath.stepYaw(baseYaw, smoothed[0], rail);
				nextPitch = RotationMath.stepPitch(basePitch, smoothed[1], rail);
			} else {
				nextYaw = RotationMath.stepYaw(baseYaw, wantedYaw, step);
				nextPitch = RotationMath.stepPitch(basePitch, wantedPitch, step);
			}
		} else {
			nextYaw = RotationMath.stepYaw(baseYaw, wantedYaw, step);
			nextPitch = RotationMath.stepPitch(basePitch, wantedPitch, step);
		}

		yaw = RotationMath.wrap(nextYaw);
		pitch = RotationMath.clampPitch(nextPitch);
		apply(player);
		synced = Float.compare(sentYaw, yaw) == 0 && Float.compare(sentPitch, pitch) == 0;
		return true;
	}

	/** Модуль больше не целится: освобождаем слой (и возвращаем камеру, если двигали её). */
	public static void release(Object requester) {
		if (owner == null || owner != requester) {
			return;
		}
		forget();
	}

	/** Вызывается в конце тика клиента: протухшие заявки снимаем. */
	public static void tickEnd() {
		if (owner == null) {
			return;
		}
		if (++staleTicks <= GRACE_TICKS) {
			return;
		}
		forget();
	}

	private static void forget() {
		// Слой освобождён: человеческая пружина человеческая пружина не должна помнить старые
		// углы/скорости — иначе следующий владелец стартует с чужой инерцией
		RotationHumanizer.reset();
		owner = null;
		ownerPriority = 0;
		mode = Mode.NONE;
		movement = Movement.NONE;
		humanizing = false;
		staleTicks = 0;
		synced = false;
		applied = false;
		sentYaw = Float.NaN;
		sentPitch = Float.NaN;
		trackingUser = false;
		packetApplied = false;
	}

	// ------------------------------------------------------------------
	// Чтение состояния
	// ------------------------------------------------------------------

	public static boolean active() {
		return owner != null;
	}

	/** Владеет ли слоем запрошенный модуль. */
	public static boolean ownedBy(Object requester) {
		return owner != null && owner == requester;
	}

	public static Mode mode() {
		return mode;
	}

	public static Movement movement() {
		return movement;
	}

	/** Боевой поворот: то, что видит сервер (или то, куда смотрит игрок в NONE). */
	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	/** Направление взгляда ауры (для raytrace'а, а не для камеры). */
	public static Vec3 lookVector() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null) {
			return new Vec3(0.0, 0.0, 1.0);
		}
		if (owner == null || mode == Mode.NONE) {
			return player.getViewVector(1.0F);
		}
		return Vec3.directionFromRotation(pitch, yaw);
	}

	/** Куда смотрит игрок сам — для FOV-фильтра целей и «свободного» движения. */
	public static float userYaw(LocalPlayer player) {
		if (player == null) {
			return 0.0F;
		}
		return mode == Mode.VISIBLE && trackingUser ? userYaw : player.getYRot();
	}

	public static float userPitch(LocalPlayer player) {
		if (player == null) {
			return 0.0F;
		}
		return mode == Mode.VISIBLE && trackingUser ? userPitch : player.getXRot();
	}

	/** Навелись ли мы туда, куда просит модуль (с допуском, в градусах). */
	public static boolean aimed(float tolerance) {
		if (owner == null) {
			return false;
		}
		if (humanizing && !RotationHumanizer.settled()) {
			return false;
		}
		return RotationMath.aimed(yaw, pitch, wantedYaw, wantedPitch, tolerance);
	}

	/**
	 * Можно ли действовать: сервер уже получает наши повороты.
	 *
	 * Точное совпадение угла с последним отправленным не требуется — перед самим
	 * действием модуль вызывает {@link #syncBeforeAction(LocalPlayer)}, и там
	 * недостающий пакет поворота уходит строго перед ударом. Требовать «полный
	 * sync» здесь означало бы, что по moving-цели аура не бьёт никогда.
	 */
	public static boolean inSync() {
		return owner == null || mode != Mode.SILENT || synced || applied;
	}

	/** Миксин пакета движения жив (нужен модулям, чтобы не дублировать пакеты). */
	public static boolean hasMovementPacketHook() {
		return movementPacketHook;
	}

	// ------------------------------------------------------------------
	// Пакеты: точка врезки миксина
	// ------------------------------------------------------------------

	/**
	 * Вызывается из миксина перед {@code LocalPlayer#sendPosition()}: на время
	 * формирования пакета движения отдаём слою его поворот.
	 */
	public static void beginPacket() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || owner == null || mode != Mode.SILENT || packetApplied) {
			return;
		}
		movementPacketHook = true;
		packetApplied = true;
		playerYawBefore = player.getYRot();
		playerPitchBefore = player.getXRot();
		player.setYRot(yaw);
		player.setXRot(pitch);
		// Значения, которые ваниль сейчас отправит, — это наш текущий прицел
		applied = true;
	}

	/** Вызывается из миксина после {@code LocalPlayer#sendPosition()}: камеру — обратно. */
	public static void endPacket() {
		if (!packetApplied) {
			return;
		}
		packetApplied = false;
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player != null) {
			player.setYRot(playerYawBefore);
			player.setXRot(playerPitchBefore);
		}
		markSent();
	}

	/**
	 * Перед ударом/установкой/броском убеждаемся, что сервер видит наш прицел.
	 *
	 * Обычно поворот уже ушёл в {@code sendPosition} этого же тика; сюда
	 * заходим только когда миксина нет (метод переименовали в обновлении игры)
	 * либо когда угол сменился уже после отправки пакета.
	 */
	public static void syncBeforeAction(LocalPlayer player) {
		if (player == null || owner == null || mode != Mode.SILENT) {
			return;
		}
		if (synced && Float.compare(sentYaw, yaw) == 0 && Float.compare(sentPitch, pitch) == 0) {
			return; // сервер уже видит ровно этот угол — лишний пакет не нужен
		}
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return;
		}
		client.getConnection().send(new ServerboundMovePlayerPacket.Rot(
				yaw, pitch, player.onGround(), player.horizontalCollision));
		markSent();
	}

	private static void markSent() {
		sentYaw = yaw;
		sentPitch = pitch;
		synced = true;
		applied = true;
	}

	// ------------------------------------------------------------------
	// Внутриигровая механика слоя
	// ------------------------------------------------------------------

	/**
	 * Отделяем «мышь игрока» от «поворота ауры» в видимом режиме: всё, на что
	 * углы игрока ушли сверх нашей последней записи, считаем движением мыши.
	 */
	private static void observeUser(LocalPlayer player) {
		float currentYaw = player.getYRot();
		float currentPitch = player.getXRot();
		if (!trackingUser) {
			userYaw = currentYaw;
			userPitch = currentPitch;
			trackingUser = true;
			return;
		}
		if (mode != Mode.VISIBLE) {
			// Silent/None: игрок сам хозяин своих углов, ничего не отделяем
			userYaw = currentYaw;
			userPitch = currentPitch;
			return;
		}
		float mouseYaw = RotationMath.wrap(currentYaw - lastWrittenYaw);
		float mousePitch = currentPitch - lastWrittenPitch;
		userYaw = Math.abs(mouseYaw) < USER_JUMP ? RotationMath.wrap(userYaw + mouseYaw) : currentYaw;
		userPitch = Math.abs(mousePitch) < USER_JUMP ? Mth.clamp(userPitch + mousePitch, -90.0F, 90.0F) : currentPitch;
	}

	/** Записываем углы игроку только в видимом режиме; silent ничего не трогает. */
	private static void apply(LocalPlayer player) {
		if (mode == Mode.VISIBLE) {
			if (Float.compare(player.getYRot(), yaw) != 0) {
				player.setYRot(yaw);
			}
			if (Float.compare(player.getXRot(), pitch) != 0) {
				player.setXRot(pitch);
			}
		}
		lastWrittenYaw = player.getYRot();
		lastWrittenPitch = player.getXRot();
	}

	/**
	 * Коррекция ввода для {@code KeyboardInputMixin}: в видимом режиме аура
	 * развернула игрока, и без этого W вёл бы туда, куда навела аура. Возвращаем
	 * ввод в систему координат «своего» взгляда игрока.
	 *
	 * @return новый вектор ввода или {@code null}, если ничего менять не нужно
	 */
	public static Vec2 correctedInput(Vec2 input) {
		if (owner == null || mode != Mode.VISIBLE || movement != Movement.FREE || !trackingUser) {
			return null;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || input == null || input.x == 0.0F && input.y == 0.0F) {
			return null;
		}
		float aimYaw = player.getYRot();
		if (Math.abs(RotationMath.wrap(userYaw - aimYaw)) < 1.0F) {
			return null;
		}

		// Мировое направление текущего ввода (относительно наведённой камеры)…
		double aimRad = Math.toRadians(aimYaw);
		double aimForwardX = -Math.sin(aimRad), aimForwardZ = Math.cos(aimRad);
		double aimRightX = -aimForwardZ, aimRightZ = aimForwardX;
		double worldX = aimForwardX * input.y + aimRightX * input.x;
		double worldZ = aimForwardZ * input.y + aimRightZ * input.x;

		// …и то же направление в осях «своего» взгляда игрока
		double userRad = Math.toRadians(userYaw);
		double userForwardX = -Math.sin(userRad), userForwardZ = Math.cos(userRad);
		double userRightX = -userForwardZ, userRightZ = userForwardX;
		return new Vec2(
				(float) (worldX * userRightX + worldZ * userRightZ),
				(float) (worldX * userForwardX + worldZ * userForwardZ));
	}

	/** Полный сброс (выход из мира, death, смена мира). */
	public static void reset() {
		forget();
		activeLevel = null;
	}
}

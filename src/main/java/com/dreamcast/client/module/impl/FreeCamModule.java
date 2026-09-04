package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.util.KeyOwnership;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * FreeCam — настоящая свободная камера.
 *
 * Чем это отличается от «полёта игрока»
 * -------------------------------------
 * Прежняя версия включала игроку режим полёта и noClip <b>по-настоящему</b>: менялись
 * способности, игрок физически летел сквозь блоки. На сервере так нельзя — античит
 * снимает это за пару секунд, а сервер откатывает позицию телепортом.
 *
 * Здесь игрока не трогаем вообще: он стоит там, где стоял, и отправляет на сервер ровно
 * то же, что и стоя. Отвязывается только <b>камера</b>: её позицию подменяет миксин
 * {@code CameraMixin}, а движение считает этот модуль по тем же игровым клавишам.
 * Ни одного пакета на позицию не уходит, ни одного отката нет.
 *
 * Клавиши движения перехватываются: {@link #handleInput(Minecraft)} вызывается в начале
 * клиентского тика, снимает состояние «куда нажато», отдаёт его камере и гасит для игры —
 * иначе игрок пошёл бы пешком вслед за камерой, спринтом наслав бы лишних пакетов
 * и топанул ногами.
 */
public class FreeCamModule extends Module {

	private final IntSetting speed = intSetting("speed", "Скорость", 6, 1, 20);
	private final BooleanSetting sprintBoost = bool("sprint_boost", "Ctrl — ускорение ×2", true);
	private final BooleanSetting freezePlayer = bool("freeze_player", "Не давать игроку ходить", true);
	private final BooleanSetting muteActions = bool("mute_actions", "Гасить ЛКМ/ПКМ", true);
	private final BooleanSetting showHud = bool("hud", "Показывать координаты камеры", true);

	/** Позиция камеры — наша, отдельная от игрока. */
	private Vec3 position = Vec3.ZERO;
	/** Предыдущая позиция — для плавной интерполяции между тиками. */
	private Vec3 previous = Vec3.ZERO;

	private boolean initialised;

	public FreeCamModule() {
		super("free_cam", "FreeCam", "Свободная камера: игрок стоит на месте, летает только вид",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_N);
	}

	@Override
	protected void onEnable() {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null) {
			setEnabledSilently(false);
			return;
		}

		this.position = player.getEyePosition();
		this.previous = this.position;
		this.initialised = true;
	}

	@Override
	protected void onDisable() {
		this.initialised = false;
		KeyOwnership.releaseAll(Minecraft.getInstance(), this);
	}

	/**
	 * Вызывается в начале клиентского тика — раньше, чем игра обработает клавиши
	 * передвижения в {@code KeyboardInput#tick()}. Поэтому мы успеваем посмотреть,
	 * что нажато, сдвинуть камеру и погасить эти нажатия для игры.
	 */
	public static void handleInput(Minecraft client) {
		FreeCamModule module = ModuleManager.find(FreeCamModule.class);
		if (module == null) {
			return;
		}

		if (!module.isEnabled() || client.player == null || client.level == null) {
			return;
		}
		// В меню мышь и клавиатура принадлежат экрану: иначе FreeCam гасил
		// ЛКМ/ПКМ и делал инвентарь фактически некликабельным.
		if (client.gui != null && client.gui.screen() != null) {
			KeyOwnership.releaseSuppression(client, client.options.keyAttack, module);
			KeyOwnership.releaseSuppression(client, client.options.keyUse, module);
			return;
		}

		module.moveCamera(client);
		module.suppressActions(client);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		// Вышли из мира или умерли — камеру возвращаем игроку сразу
		if (client.player == null || client.level == null) {
			setEnabled(false);
		}
	}

	// ------------------------------------------------------------------
	// Движение камеры
	// ------------------------------------------------------------------

	private void moveCamera(Minecraft client) {
		LocalPlayer player = client.player;

		float yaw = player.getViewYRot(1.0F) * Mth.DEG_TO_RAD;
		float pitch = player.getViewXRot(1.0F) * Mth.DEG_TO_RAD;

		// Горизонтальная проекция направления взгляда и вектор «вправо» от него
		double forwardX = -Mth.sin(yaw) * Mth.cos(pitch);
		double forwardZ = Mth.cos(yaw) * Mth.cos(pitch);
		double length = Math.sqrt(forwardX * forwardX + forwardZ * forwardZ);
		if (length > 1.0e-5) {
			forwardX /= length;
			forwardZ /= length;
		}
		double rightX = -forwardZ;
		double rightZ = forwardX;

		double step = 0.12 * speed.get();
		if (sprintBoost.isEnabled() && isDown(client.options.keySprint)) {
			step *= 2.0;
		}

		boolean forward = isDown(client.options.keyUp);
		boolean backward = isDown(client.options.keyDown);
		boolean left = isDown(client.options.keyLeft);
		boolean right = isDown(client.options.keyRight);

		double dx = 0.0;
		double dy = 0.0;
		double dz = 0.0;

		if (forward) {
			dx += forwardX * step;
			dz += forwardZ * step;
		}
		if (backward) {
			dx -= forwardX * step;
			dz -= forwardZ * step;
		}
		if (right) {
			dx += rightX * step;
			dz += rightZ * step;
		}
		if (left) {
			dx -= rightX * step;
			dz -= rightZ * step;
		}
		// По диагонали едем с той же скоростью, что и по прямой
		if ((forward || backward) && (left || right)) {
			double diagonal = 1.0 / Math.sqrt(2.0);
			dx *= diagonal;
			dz *= diagonal;
		}
		if (isDown(client.options.keyJump)) {
			dy += step;
		}
		if (isDown(client.options.keyShift)) {
			dy -= step;
		}

		if (dx != 0.0 || dy != 0.0 || dz != 0.0) {
			this.previous = this.position;
			this.position = this.position.add(dx, dy, dz);
		} else {
			// Стоим — интерполяция не должна «догонять» саму себя
			this.previous = this.position;
		}
	}

	private static boolean isDown(KeyMapping key) {
		return key != null && key.isDown();
	}

	/**
	 * Гасим ЛКМ/ПКМ. Смысл: во время полёта камеры прицел на экране показывает блок
	 * на пятьдесят блоков левее и выше того, что реально видит игрок, — и обычный
	 * клик сломал бы именно его. Молчание мыши здесь честнее, чем «случайная
	 * дыра в стене за спиной».
	 *
	 * Клавиши передвижения НЕ трогаем: их читает {@link #moveCamera(Minecraft)},
	 * а игрока от них отрезает миксин {@code KeyboardInputMixin}.
	 */
	private void suppressActions(Minecraft client) {
		if (!muteActions.isEnabled()) {
			KeyOwnership.releaseSuppression(client, client.options.keyAttack, this);
			KeyOwnership.releaseSuppression(client, client.options.keyUse, this);
			return;
		}
		suppressKey(client, client.options.keyAttack);
		suppressKey(client, client.options.keyUse);
	}

	/** «Клавиша не нажата» плюс съедание накопленных кликов, чтобы игра их не увидела. */
	private void suppressKey(Minecraft client, KeyMapping key) {
		if (key == null) {
			return;
		}
		KeyOwnership.suppress(client, key, this);
		while (key.consumeClick()) {
			// клик съеден
		}
	}

	// ------------------------------------------------------------------
	// Данные для рендера и HUD
	// ------------------------------------------------------------------

	/** Позиция камеры с интерполяцией между тиками. Null, если свободной камеры нет. */
	public static Vec3 cameraPosition(float partialTicks) {
		FreeCamModule module = ModuleManager.find(FreeCamModule.class);
		if (module == null || !module.isEnabled() || !module.initialised) {
			return null;
		}
		return module.previous.lerp(module.position, partialTicks);
	}

	/**
	 * Should the player be pinned in place? Читается миксином {@code KeyboardInputMixin}
	 * каждый тик — поэтому проверка дешёвая и без обращений к Minecraft.
	 */
	public static boolean freezesInput() {
		FreeCamModule module = ModuleManager.find(FreeCamModule.class);
		return module != null && module.isEnabled() && module.freezePlayer.isEnabled();
	}

	/** Куда сейчас «смотрят глаза» камеры. Нужно AutoWalk, чтобы ставить цель под камерой. */
	public Vec3 position() {
		return this.position;
	}

	/** Расстояние от камеры до игрока в блоках. */
	public double distanceToPlayer() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return 0.0;
		}
		return client.player.getEyePosition().distanceTo(this.position);
	}

	public boolean showsHudInfo() {
		return showHud.isEnabled();
	}
}

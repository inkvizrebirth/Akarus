package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * FreeLook — камера от третьего лица, которая вращается мышью вокруг игрока.
 *
 * Чем это отличается от FreeCam
 * -----------------------------
 * FreeCam уводит «глаз»: камера летит куда угодно, а игрок остаётся стоять — и клики
 * приходится гасить, чтобы не ломать блоки у ног. FreeLook не отрывается от игрока:
 * камера всегда смотрит в него (игрок в центре экрана), облетает его мышью с любой
 * стороны и с любой дистанции, а сам игрок продолжает жить своей жизнью — идти,
 * копать, сражаться. Baritone при этом не страдает: мышью во время FreeLook крутится
 * только камера, поворот игрока не меняется ни на градус (его по-прежнему выставляет
 * Baritone, когда ведёт путь или добывает блоки).
 *
 * Как это устроено:
 * <ul>
 *   <li>{@code MouseHandlerMixin} смотрит, на сколько мышь повернула игрока за кадр,
 *       откатывает игроку его поворот и отдаёт дельту камере — поэтому
 *       чувствительность ровно игровая, и ни один пакет не меняется;</li>
 *   <li>{@code CameraMixin} в конце {@code Camera#alignWithEntity} ставит камере
 *       наши углы и позицию перед игроком; по пути камера «ужимается», если упирается
 *       в блок, — как ванильный вид от третьего лица;</li>
 *   <li>{@code Camera#isDetached} на время FreeLook говорит «камера отцеплена»:
 *       игра сама рисует тело игрока и прячет руку от первого лица;</li>
 *   <li>FreeCam важнее: если включены оба, летит именно FreeCam, а орбита ждёт.</li>
 * </ul>
 */
public class FreeLookModule extends Module {

	/** Как далеко камера висит от игрока, в блоках. */
	private final IntSetting distance = intSetting("distance", "Дистанция, блоков", 4, 1, 8);

	/** Куда камера смотрит: углы поворота в тех же единицах, что у игрока (градусы). */
	private float yaw;
	private float pitch;
	private boolean initialised;
	private ClientLevel activeLevel;

	public FreeLookModule() {
		super("free_look", "FreeLook", "Камера от третьего лица: вращается мышью вокруг игрока, игрок всегда в центре и живёт своей жизнью",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_K);
	}

	@Override
	protected void onEnable() {
		initialiseIfReady(Minecraft.getInstance());
	}

	@Override
	protected void onDisable() {
		initialised = false;
		activeLevel = null;
	}

	@Override
	public void tick() {
		initialiseIfReady(Minecraft.getInstance());
	}

	private static FreeLookModule module() {
		return ModuleManager.find(FreeLookModule.class);
	}

	/** Работает ли сейчас орбитальная камера. */
	public static boolean active() {
		FreeLookModule module = module();
		if (module == null || !module.isEnabled()) {
			return false;
		}
		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		if (freeCam != null && freeCam.isEnabled()) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		return module.initialiseIfReady(client);
	}

	/** Отложенный старт сохраняет включённое состояние из конфига до входа в мир. */
	private boolean initialiseIfReady(Minecraft client) {
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			initialised = false;
			activeLevel = null;
			return false;
		}
		if (!initialised || activeLevel != client.level) {
			// Начинаем с текущего взгляда: камера появляется за спиной без рывка.
			yaw = player.getYRot();
			pitch = player.getXRot();
			activeLevel = client.level;
			initialised = true;
		}
		return true;
	}

	/**
	 * Забрать себе доворот мыши. Вызывается из {@code MouseHandlerMixin} после того,
	 * как игра повернула игрока: дельту присоединяем к камере, а игроку вернут его
	 * прежний поворот сам миксин.
	 *
	 * @return false, если камерой сейчас управлять нельзя — тогда мышь работает как обычно
	 */
	public static boolean absorbMouseLook(float deltaYaw, float deltaPitch) {
		FreeLookModule module = module();
		if (!active()) {
			return false;
		}

		Minecraft client = Minecraft.getInstance();
		// В меню мышь принадлежит экрану (на практике turnPlayer в это время и не зовётся,
		// но подстрахуемся)
		if (client.gui != null && client.gui.screen() != null) {
			return false;
		}

		module.yaw = Mth.wrapDegrees(module.yaw + deltaYaw);
		module.pitch = Mth.clamp(module.pitch + deltaPitch, -90.0F, 90.0F);
		return true;
	}

	/** Углы, которые камера должна получить (ставит CameraMixin). */
	public static float cameraYaw() {
		FreeLookModule module = module();
		return module == null ? 0.0F : module.yaw;
	}

	public static float cameraPitch() {
		FreeLookModule module = module();
		return module == null ? 0.0F : module.pitch;
	}

	/**
	 * Точка, в которой висит камера: глаз игрока минус направление взгляда, умноженное
	 * на дистанцию. Null, если FreeLook не активен.
	 */
	public static Vec3 cameraPosition(float partialTicks) {
		FreeLookModule module = module();
		if (!active()) {
			return null;
		}

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Vec3 eye = player.getEyePosition(partialTicks);

		float yawRad = module.yaw * Mth.DEG_TO_RAD;
		float pitchRad = module.pitch * Mth.DEG_TO_RAD;
		double lookX = -Mth.sin(yawRad) * Mth.cos(pitchRad);
		double lookY = -Mth.sin(pitchRad);
		double lookZ = Mth.cos(yawRad) * Mth.cos(pitchRad);

		double wanted = module.distance.get();
		double allowed = wanted;
		// Камера не должна залезать в блоки: идём от глаз наружу и останавливаемся
		// перед первым плотным. Шаг меньше блока — чтобы не проскочить тонкие стены
		for (double travelled = 0.3; travelled <= wanted; travelled += 0.2) {
			BlockPos pos = BlockPos.containing(
					eye.x - lookX * travelled,
					eye.y - lookY * travelled,
					eye.z - lookZ * travelled);
			if (isBlocked(client, pos)) {
				allowed = Math.max(0.3, travelled - 0.3);
				break;
			}
		}

		return new Vec3(eye.x - lookX * allowed, eye.y - lookY * allowed, eye.z - lookZ * allowed);
	}

	/** Плотный ли блок (жидкости не считаются — сквозь воду камера проходит). */
	private static boolean isBlocked(Minecraft client, BlockPos pos) {
		BlockState state = client.level.getBlockState(pos);
		return !state.isAir() && state.getFluidState().isEmpty();
	}

	/** Текущая дистанция камеры — для плашки в HUD. */
	public int getDistance() {
		return distance.get();
	}
}

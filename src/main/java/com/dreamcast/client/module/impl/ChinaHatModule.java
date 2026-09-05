package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * ChinaHat — «китайская шляпа» над головой: конус из секторов, закрученный по
 * взгляду и переливающийся по кругу.
 *
 * <p>Шляпа рисуется нашим world-рендером (см. {@link
 * com.dreamcast.client.render.WorldRenderHook}), а не партиклами, и вот почему:
 * партикл живёт своё время и остаётся висеть в мире после выключения модуля,
 * а шляпа обязана исчезать в тот же кадр, в который выключили опцию. Плюс
 * геометрия даёт честный конус с гранями, которые можно затенять по
 * направлению к камере — плоский «веер» из спрайтов так не умеет.</p>
 *
 * <p>Гранинок ({@code segments}) много, и каждая вершина считается на месте:
 * это не буферизуется между кадрами, поэтому поворот головы не «догоняется»
 * с задержкой в тик — шляпа сидит на голове, а не плавает позади.</p>
 */
public class ChinaHatModule extends Module {

	/** Одна шляпа: позиция основания (уже с интерполяцией) и угол взгляда владельца. */
	public record Hat(float x, float y, float z, float yaw) {
	}

	private final IntSetting height = intSetting("height", "Высота (0.1 блока)", 4, 1, 12);
	private final IntSetting radius = intSetting("radius", "Радиус (0.1 блока)", 6, 2, 16);
	private final IntSetting yOffset = intSetting("y_offset", "Сдвиг по Y (0.1 блока)", 0, -5, 5);
	private final IntSetting segments = intSetting("segments", "Сегментов", 30, 8, 60);
	private final IntSetting opacity = intSetting("alpha", "Непрозрачность", 190, 20, 255);
	private final IntSetting spin = intSetting("spin", "Вращение (°/с)", 0, 0, 720);
	private final IntSetting rainbowSpeed = intSetting("rainbow_speed", "Скорость радуги", 4, 1, 10);

	private final BooleanSetting firstPerson = bool("first_person", "От первого лица", false);
	private final BooleanSetting onTarget = bool("on_target", "На цели ауры", false);
	private final BooleanSetting rim = bool("rim", "Ободок основания", true);
	private final BooleanSetting shade = bool("shade", "Объём (светотень)", true);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", true);
	private final ColorSetting color = colorSetting("color", "Цвет", 0xFFFF4FA0);

	/** Снапшот на кадр: собирается на извлечении, читается в рендере. */
	private volatile List<Hat> hats = List.of();

	public ChinaHatModule() {
		super("china_hat", "ChinaHat", "Вращающаяся шляпа-конус над головой (своя и/или над целью ауры)",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	/**
	 * Собирает шляпы на этом кадре. Вызывается из {@code END_EXTRACTION} — то есть
	 * в потоке игры и ровно один раз на кадр, поэтому {@code getX(partialTick)}
	 * здесь даёт то же самое сглаживание, что и у модели игрока.
	 */
	public void collect(float partialTick) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client == null ? null : client.player;
		if (player == null || client.level == null) {
			hats = List.of();
			return;
		}
		boolean cameraFirstPerson = client.options != null
				&& client.options.getCameraType().isFirstPerson();
		if (!firstPerson.isEnabled() && cameraFirstPerson) {
			// От своего лица шляпа всё равно видна только в упор: в референсе она
			// там и не рисуется. Оставляем привычное поведение — 3-е лицо.
			hats = List.of();
			return;
		}

		if (onTarget.isEnabled()) {
			Entity target = targetOf(client);
			if (target != null) {
				// Над целью: угол берём её собственный разворот, иначе у моба
				// шляпа «приклеена» к нашему взгляду и ползёт по голове
				hats = List.of(hatAt(target.getX(partialTick), target.getY(partialTick),
						target.getZ(partialTick), target.getYRot()),
						hatAt(player.getX(partialTick), player.getY(partialTick),
								player.getZ(partialTick), viewYaw(player, partialTick)));
				return;
			}
		}
		hats = List.of(hatAt(player.getX(partialTick), player.getY(partialTick),
				player.getZ(partialTick), viewYaw(player, partialTick)));
	}

	private Hat hatAt(double x, double y, double z, float yaw) {
		return new Hat((float) x, (float) y, (float) z, yaw);
	}

	/**
	 * Угол, вокруг которого закручена шляпа. Для игрока — это ВЗГЛЯД, а не
	 * {@code getYRot()} ауры:silent-поворот слоя не должен проворачивать шляпу
	 * вместе с прицелом (иначе она дёргается, когда аура целится за спину).
	 */
	private static float viewYaw(LocalPlayer player, float partialTick) {
		return player.getViewYRot(partialTick);
	}

	private static Entity targetOf(Minecraft client) {
		KillAuraModule aura = com.dreamcast.client.module.ModuleManager.find(KillAuraModule.class);
		if (aura != null && aura.isEnabled()) {
			Entity target = aura.currentTarget();
			if (target != null && target.isAlive()) {
				return target;
			}
		}
		return client.crosshairPickEntity instanceof AbstractClientPlayer ? client.crosshairPickEntity : null;
	}

	/** Нужно ли вообще тащить шляпу в кадр. */
	public boolean wantsHats() {
		return isEnabled() && !hats.isEmpty();
	}

	public List<Hat> hats() {
		return hats;
	}

	@Override
	public void onDisable() {
		hats = List.of();
	}

	// ------------------------------------------------------------------
	// Значения для рендера (пересчёт настроек в мирские единицы)
	// ------------------------------------------------------------------

	public float heightBlocks() {
		return height.get() * 0.1F;
	}

	public float radiusBlocks() {
		return radius.get() * 0.1F;
	}

	public float yOffsetBlocks() {
		return yOffset.get() * 0.1F;
	}

	public int segmentCount() {
		return segments.get();
	}

	public int opacityValue() {
		return opacity.get();
	}

	/** Насколько шляпа провернулась сама (градусы; 0 — только за взглядом). */
	public float spinDegrees(long nowMs) {
		int perSecond = spin.get();
		return perSecond <= 0 ? 0.0F : (nowMs % 3600000L) / 1000.0F * perSecond;
	}

	public boolean shadesFaces() {
		return shade.isEnabled();
	}

	public boolean drawsRim() {
		return rim.isEnabled();
	}

	/** Цвет сектора {@code t} (0..1 по кругу): радуга или выбранный цвет. */
	public int sectorColor(float t, long nowMs) {
		if (rainbow.isEnabled()) {
			return com.dreamcast.client.util.RenderUtils.hsb(
					com.dreamcast.client.util.RenderUtils.rainbowPhase(nowMs, rainbowSpeed.get()) + t,
					0.78F, 1.0F, 0xFF);
		}
		return color.get();
	}
}

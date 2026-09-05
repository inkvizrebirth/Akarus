package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * HitParticles — эффект в точке попадания удара.
 *
 * <p>Режим <b>«Волна»</b> — фирменная ударная волна клиента: ровно в точке
 * удара расходится детализированное кольцо (мерцающая кромка, эхо, ореол) —
 * та же отрисовка, что у Jump Effect, только в точке попадания.</p>
 *
 * <p>Режим <b>«Искры»</b> — короткие расходящиеся кольца-вспышки (три мелких
 * волны в стороны), когда нужна не круговая, а «дробная» отдача.</p>
 *
 * <p>Точка удара: луч взгляда до пересечения с хитбоксом цели — как
 * ServerboundInteractPacket «видит» попадание.</p>
 */
public class HitParticlesModule extends Module {

	/** Активная волна в мировой точке. */
	public record HitWave(double x, double y, double z, long bornMs, int seed, boolean spark) {
	}

	private final ModeSetting style = mode("style", "Стиль", "wave",
			ModeSetting.option("wave", "Волна"),
			ModeSetting.option("sparks", "Искры"));

	private final ColorSetting color = colorSetting("color", "Цвет", 0xFFFF5C7A);
	private final ColorSetting secondColor = colorSetting("color2", "Второй цвет", 0xFFFFC66C);
	private final BooleanSetting onlyPlayers = bool("only_players", "Только по игрокам", false);
	private final IntSetting radius = intSetting("radius", "Радиус (0.1 блока)", 14, 4, 40);
	private final IntSetting duration = intSetting("duration", "Длительность (0.1 с)", 4, 2, 10);
	private final IntSetting intensity = intSetting("intensity", "Яркость (0.1)", 9, 2, 10);

	private final List<HitWave> waves = new ArrayList<>();

	public HitParticlesModule() {
		super("hit_particles", "HitParticles", "Волна в точке попадания удара",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	public boolean wantsWaves() {
		return isEnabled() && !waves.isEmpty();
	}

	public List<HitWave> waves() {
		// Снапшот: отложенный рендер не должен итерировать живой список,
		// который параллельно чистит tick() (CME/пропуски кадров)
		return java.util.List.copyOf(waves);
	}

	/** true, если стиль — «искры» (иначе фирменная волна). */
	public boolean sparks() {
		return style.is("sparks");
	}

	public float radiusBlocks() {
		return radius.get() * 0.1f;
	}

	public long durationMs() {
		return duration.get() * 100L;
	}

	public float intensityScale() {
		return intensity.get() * 0.1f;
	}

	public int waveColor(float t) {
		return com.dreamcast.client.util.RenderUtils.mix(color.get(), secondColor.get(), t);
	}

	/** Убрать отжившие волны; вызывается из рендер-крючка. */
	public void gc(long now) {
		waves.removeIf(wave -> now - wave.bornMs() > durationMs() + 150L);
	}

	/** Точка входа миксина атаки: удар засчитан. */
	public static void onAttack(net.minecraft.client.player.LocalPlayer player, Entity target) {
		HitParticlesModule module = com.dreamcast.client.module.ModuleManager.find(HitParticlesModule.class);
		if (module == null || !module.isEnabled()) {
			return;
		}
		if (module.onlyPlayers.isEnabled() && !(target instanceof Player)) {
			return;
		}

		Vec3 eye = player.getEyePosition();
		// Точка удара считается по прицелу ауры (слою поворотов), а не по камере:
		// на «сайте» они разошлись, и волна иначе рисовалась бы мимо цели
		Vec3 look = com.dreamcast.client.rotation.RotationManager.lookVector();
		Vec3 end = eye.add(look.scale(6.0));
		var box = target.getBoundingBox().inflate(0.05);
		var hit = box.clip(eye, end);
		Vec3 point = hit.orElse(target.position().add(0, target.getBbHeight() * 0.6, 0));

		long now = Util.getMillis();
		int seed = (int) (Util.getNanos() & 0xFFFF);
		module.waves.add(new HitWave(point.x, point.y, point.z, now, seed,
				module.sparks()));
		if (module.waves.size() > 24) {
			module.waves.remove(0);
		}
	}
}

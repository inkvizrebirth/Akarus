package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * HitSounds — звук при ударе по сущности.
 *
 * Хук атаки общий с HitParticles (миксин на {@code MultiPlayerGameMode#attack}):
 * сюда прилетает каждое попадание — автовыстрел KillAura тоже озвучивается.
 *
 * Звуки: «плонг» (Note Block Pling), «лазер» (AMETHYST), «орб» (опыт),
 * «бас» и «колокол». Тон можно поднять/опустить питчем, громкость — отдельно.
 */
public class HitSoundsModule extends Module {

	private final ModeSetting sound = mode("sound", "Звук", "pling",
			ModeSetting.option("pling", "Плонг"),
			ModeSetting.option("amethyst", "Лазер"),
			ModeSetting.option("orb", "Орб"),
			ModeSetting.option("bass", "Бас"),
			ModeSetting.option("bell", "Колокол"));

	private final BooleanSetting onlyPlayers = bool("only_players", "Только по игрокам", false);
	private final BooleanSetting pitchByEnemyHp = bool("pitch_hp", "Тон по здоровью цели", false);
	/** Тон и громкость — в десятых (12 = 1.2), как остальные настройки клиента. */
	private final IntSetting pitch = intSetting("pitch", "Тон (0.1)", 10, 5, 20);
	private final IntSetting volume = intSetting("volume", "Громкость (0.1)", 6, 1, 10);

	public HitSoundsModule() {
		super("hit_sounds", "HitSounds", "Звук при попадании по цели",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	public boolean onlyPlayers() {
		return onlyPlayers.isEnabled();
	}

	/** Проиграть звук удара (вызов из миксина атаки). */
	public void play(net.minecraft.world.entity.Entity target) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}
		float pitch = this.pitch.get() * 0.1f;
		if (pitchByEnemyHp.isEnabled() && target instanceof net.minecraft.world.entity.LivingEntity living) {
			float healthFraction = living.getHealth() / Math.max(1.0f, living.getMaxHealth());
			// Чем меньше здоровья у цели — тем выше тон: «дожимаем» звучит иначе
			pitch *= 1.0f + (1.0f - healthFraction) * 0.5f;
		}

		float volume = this.volume.get() * 0.1f;
		switch (sound.get()) {
			case "amethyst" -> client.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, pitch, volume));
			case "orb" -> client.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch, volume));
			case "bass" -> client.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), pitch, volume));
			case "bell" -> client.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), pitch, volume));
			default -> client.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, volume));
		}
	}

	/** Точка входа миксина: удар засчитан. */
	public static void onAttack(LocalPlayer player, net.minecraft.world.entity.Entity target) {
		HitSoundsModule sounds = com.dreamcast.client.module.ModuleManager.find(HitSoundsModule.class);
		if (sounds != null && sounds.isEnabled()
				&& (!sounds.onlyPlayers() || target instanceof net.minecraft.world.entity.player.Player)) {
			sounds.play(target);
		}
	}
}

package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Nametags — расширенные таблички над игроками.
 *
 * Ваниль показывает только имя. Мы собираем на тике список «визиток» (что
 * показывать — решают настройки) и рисуем их нашим world-рендером через
 * {@code submitNameTag} — той же трубой, что и ванильные ники: правильное
 * масштабирование по дистанции, затемнение, отсечение сквозь стены.
 *
 * Строки: имя, предмет в руке, пинг (мс + цветовая маркировка), очки брони,
 * активные эффекты (короткие имена + уровень), здоровье.
 */
public class NametagsModule extends Module {

	/** Одна визитка: всё пересчитано на тике, рендеру остаются только компоненты. */
	public record TagEntry(
			int entityId,
			double x, double y, double z,
			List<net.minecraft.network.chat.Component> lines,
			double distanceSq) {
	}

	private final BooleanSetting showHeld = bool("held", "Предмет в руке", true);
	private final BooleanSetting showPing = bool("ping", "Пинг", true);
	private final BooleanSetting showArmor = bool("armor", "Броня", true);
	private final BooleanSetting showHealth = bool("health", "Здоровье", true);
	private final BooleanSetting showEffects = bool("effects", "Эффекты", true);
	private final BooleanSetting hideOwn = bool("hide_own", "Скрыть свой ник", false);
	private final IntSetting maxDistance = intSetting("distance", "Дистанция, блоков", 48, 8, 128);
	private final IntSetting maxEffects = intSetting("max_effects", "Эффектов максимум", 4, 1, 8);

	private final List<TagEntry> entries = new ArrayList<>();

	public NametagsModule() {
		super("nametags", "Nametags", "Расширенные таблички: предмет, пинг, броня, эффекты",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	public boolean wantsTags() {
		return isEnabled() && !entries.isEmpty();
	}

	public List<TagEntry> entries() {
		return entries;
	}

	@Override
	public void tick() {
		entries.clear();
		Minecraft client = Minecraft.getInstance();
		LocalPlayer self = client == null ? null : client.player;
		if (self == null || client.level == null) {
			return;
		}

		double maxDist = maxDistance.get();
		double maxDistSq = maxDist * maxDist;

		for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof Player player) || player.isRemoved()) {
				continue;
			}
			if (player == self && hideOwn.isEnabled()) {
				continue;
			}
			double distSq = player.distanceToSqr(self);
			if (distSq > maxDistSq) {
				continue;
			}

			List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
			net.minecraft.network.chat.MutableComponent name =
					net.minecraft.network.chat.Component.literal(player.getGameProfile().name());
			if (showHealth.isEnabled()) {
				name = name.append(net.minecraft.network.chat.Component.literal(
						String.format("  %.0f❤", Math.ceil(player.getHealth()))));
			}
			lines.add(name);

			if (showHeld.isEnabled()) {
				ItemStack held = player.getMainHandItem();
				if (!held.isEmpty()) {
					lines.add(net.minecraft.network.chat.Component.literal("⛏ ")
							.append(held.getHoverName()));
				}
			}

			if (showPing.isEnabled()) {
				PlayerInfo info = client.getConnection() == null
						? null : client.getConnection().getPlayerInfo(player.getUUID());
				if (info != null) {
					int ping = info.getLatency();
					String mark = ping < 80 ? "●" : ping < 160 ? "◐" : ping < 300 ? "○" : "✕";
					lines.add(net.minecraft.network.chat.Component.literal(
							mark + " " + ping + " мс"));
				}
			}

			if (showArmor.isEnabled()) {
				int armor = player.getArmorValue();
				if (armor > 0) {
					lines.add(net.minecraft.network.chat.Component.literal("🛡 броня " + armor));
				}
			}

			if (showEffects.isEnabled()) {
				int shown = 0;
				StringBuilder builder = new StringBuilder();
				for (MobEffectInstance effect : player.getActiveEffects()) {
					if (shown >= maxEffects.get()) {
						builder.append("…");
						break;
					}
					if (!builder.isEmpty()) {
						builder.append(' ');
					}
					builder.append(effectName(effect));
					shown++;
				}
				if (!builder.isEmpty()) {
					lines.add(net.minecraft.network.chat.Component.literal(builder.toString()));
				}
			}

			if (lines.size() > 1 || !(player == self && hideOwn.isEnabled())) {
				entries.add(new TagEntry(player.getId(),
						player.getX(), player.getEyeY() + 0.45, player.getZ(),
						lines, distSq));
			}
		}
	}

	private static String effectName(MobEffectInstance effect) {
		String key = effect.getEffect().value().getDescriptionId();
		int slash = key.lastIndexOf('.');
		String shortName = slash >= 0 ? key.substring(slash + 1) : key;
		int amp = effect.getAmplifier();
		return shortName + (amp > 0 ? Roman.to(amp + 1) : "");
	}

	/** Римские цифры для уровней эффектов (II, III…). */
	private static final class Roman {
		private static final int[] VALUES = {10, 9, 5, 4, 1};
		private static final String[] KEYS = {"X", "IX", "V", "IV", "I"};

		static String to(int number) {
			StringBuilder builder = new StringBuilder();
			for (int i = 0; i < VALUES.length; i++) {
				while (number >= VALUES[i]) {
					builder.append(KEYS[i]);
					number -= VALUES[i];
				}
			}
			return builder.toString();
		}
	}
}

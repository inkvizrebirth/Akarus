package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.RainModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

/**
 * Дождь, снег, брызги и гроза. Всё рисуется в координатах камеры, как и остальная
 * геометрия мода: капля «живёт» в box вокруг игрока и перерабатывается по кругу,
 * поэтому погода следует за камерой без видимых границ.
 *
 * <p>Симуляция идёт по настоящему {@code dt} (с потолком 100 мс: после сворачивания
 * окна капли не должны «перепрыгивать» землю). Звук — ванильные семплы дождя и грома,
 * мы только решаем, когда и с какой громкостью их играть: переписывать звуки погоды
 * права не даёт лицензия, а играть их — можно.</p>
 */
public final class RainRenderer {

	/** Потолок капель — столько же, сколько позволяет модуль. */
	private static final int CAP = RainModule.MAX_DROPS;
	private static final int SPLASHES = 128;
	private static final int BOLT_POINTS = 12;
	/** Сколько живёт вспышка и гром после удара. */
	private static final long FLASH_MS = 620;
	private static final double SOUND_SPEED_BLOCKS_PER_MS = 0.34; // ~340 м/с

	private static float[] x = new float[CAP];
	private static float[] y = new float[CAP];
	private static float[] z = new float[CAP];
	private static float[] speed = new float[CAP];
	private static boolean seeded;

	private static final float[] splashX = new float[SPLASHES];
	private static final float[] splashY = new float[SPLASHES];
	private static final float[] splashZ = new float[SPLASHES];
	private static final long[] splashAt = new long[SPLASHES];
	private static int splashHead;

	private static final float[] boltX = new float[BOLT_POINTS];
	private static final float[] boltY = new float[BOLT_POINTS];
	private static final float[] boltZ = new float[BOLT_POINTS];
	private static final float[] boltX2 = new float[BOLT_POINTS];
	private static final float[] boltZ2 = new float[BOLT_POINTS];
	private static long boltAt = Long.MIN_VALUE;
	private static float boltBaseX;
	private static float boltBaseZ;
	private static long thunderAt;
	private static long lastAmbience;
	private static long lastCloudShift;
	private static final float[] cloudX = new float[64];
	private static final float[] cloudZ = new float[64];
	private static final float[] cloudSize = new float[64];

	private RainRenderer() {
	}

	/** Сброс при выключении мода: иначе включение «в другой точке мира» начнёт с чужих капель. */
	public static void reset() {
		seeded = false;
		boltAt = Long.MIN_VALUE;
		thunderAt = 0;
		splashHead = 0;
		for (int i = 0; i < SPLASHES; i++) {
			splashAt[i] = 0;
		}
	}

	public static void render(RainModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                          double camX, double camY, double camZ, float unitsPerPixel, long now) {
		Minecraft client = Minecraft.getInstance();
		float drops = module.drops();
		if (client == null || client.level == null || drops <= 0) {
			return;
		}
		int count = (int) drops;
		int radius = module.radiusBlocks();
		int layer = module.layerHeight();
		float wind = module.windFactor();
		float fall = module.fallMultiplier();
		boolean snow = module.snowing();
		double groundY = client.player == null ? -1.0 : client.player.getY() - camY - 0.05;

		float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
		lastFrame = now;
		if (!seeded) {
			seed(module, count, radius, layer);
		}

		int dropColor = module.dropColor();
		double streakLen = module.streakBlocks();
		int thickness = module.lineThickness();

		for (int i = 0; i < count; i++) {
			float px = x[i];
			float py = y[i];
			float pz = z[i];
			float velocity = speed[i] * fall * (snow ? 0.16F : 1.0F);

			py -= velocity * dt;
			px += wind * velocity * dt * 0.45F;
			if (snow) {
				// Снег «гуляет»: синус по индексу и по времени — без общего такта
				px += (float) Math.sin(now / 900.0 + i * 0.7) * 0.5F * dt * 6.0F;
				pz += (float) Math.cos(now / 1100.0 + i * 1.3) * 0.5F * dt * 6.0F;
			}

			// Земля: брызг и переработка. При flight-режиме земли нет — просто recycle.
			if (py < groundY && client.player != null) {
				if (module.wantsSplashes() && !snow) {
					addSplash(px, (float) groundY, pz, now);
				}
				py = layer;
				px = (float) (Math.random() * 2.0 - 1.0) * radius;
				pz = (float) (Math.random() * 2.0 - 1.0) * radius;
			}
			// Стены box: перенос на другую сторону, а не спавн заново
			if (px > radius) {
				px -= 2 * radius;
			} else if (px < -radius) {
				px += 2 * radius;
			}
			if (pz > radius) {
				pz -= 2 * radius;
			} else if (pz < -radius) {
				pz += 2 * radius;
			}
			if (py > layer) {
				py = layer;
			}
			x[i] = px;
			y[i] = py;
			z[i] = pz;

			// Дальше — прозрачнее: дождь не должен выглядеть плоским «стеклом»
			float distance = (float) Math.sqrt(px * px + py * py + pz * pz);
			float fade = Math.max(0.15F, 1.0F - distance / (radius * 1.35F));
			int color = RenderUtils.withAlpha(dropColor, 0.55F * fade);
			if (snow) {
				float size = Math.max(0.02F, unitsPerPixel * (1.1F + (i % 3) * 0.35F));
				WorldGeometryRenderer.billboard(buffer, pose, px, py, pz, size,
						RenderUtils.withAlpha(0xFFF4FAFF, 0.8F * fade));
			} else {
				double tail = Math.max(0.05, streakLen * (0.6 + speed[i] * 0.05));
				WorldGeometryRenderer.line(buffer, pose,
						px, py, pz, RenderUtils.withAlpha(0xFFFFFFFF, 0.12F * fade),
						px - wind * tail * 0.5, py + tail, pz, color,
						thickness, unitsPerPixel);
			}
		}

		drawSplashes(module, pose, buffer, unitsPerPixel, now, dropColor);
		if (module.wantsStormSky()) {
			drawClouds(module, pose, buffer, unitsPerPixel, radius, layer, dt, now);
		}
		drawBolt(module, pose, buffer, unitsPerPixel, now, radius, layer);
		playAmbience(module, now, radius);
		maybeStrike(module, now, radius, layer);
	}

	private static long lastFrame;

	// ------------------------------------------------------------------
	// Капли
	// ------------------------------------------------------------------

	private static void seed(RainModule module, int count, int radius, int layer) {
		seeded = true;
		RandomSource random = RandomSource.create();
		for (int i = 0; i < CAP; i++) {
			x[i] = random.nextFloat() * 2.0F * radius - radius;
			y[i] = random.nextFloat() * layer;
			z[i] = random.nextFloat() * 2.0F * radius - radius;
			// 24..40 блоков/с:разброс, чтобы поле не выглядело одним фронтом
			speed[i] = 24.0F + random.nextFloat() * 16.0F;
		}
	}

	// ------------------------------------------------------------------
	// Брызги
	// ------------------------------------------------------------------

	private static void addSplash(float px, float py, float pz, long now) {
		if ((splashHead & 7) != 0) {
			return; // 1 из 8 капель: глаз считает это «дождём», а FPS остаётся
		}
		int slot = splashHead++ % SPLASHES;
		splashX[slot] = px;
		splashY[slot] = py;
		splashZ[slot] = pz;
		splashAt[slot] = now;
	}

	private static void drawSplashes(RainModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                                 float unitsPerPixel, long now, int dropColor) {
		for (int i = 0; i < SPLASHES; i++) {
			long start = splashAt[i];
			if (start == 0) {
				continue;
			}
			float t = (now - start) / 260.0F;
			if (t >= 1.0F) {
				splashAt[i] = 0;
				continue;
			}
			float radius = 0.06F + t * 0.34F;
			WorldGeometryRenderer.ring(buffer, pose, splashX[i], splashY[i] + 0.02F, splashZ[i],
					radius, 1.0F, 10, RenderUtils.withAlpha(dropColor, (1.0F - t) * 0.5F), unitsPerPixel);
		}
	}

	// ------------------------------------------------------------------
	// Низкое небо
	// ------------------------------------------------------------------

	private static void drawClouds(RainModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                               float unitsPerPixel, int radius, int layer, float dt, long now) {
		int count = Math.min(64, module.cloudCount());
		float drift = module.windFactor() * 2.4F;
		if (now - lastCloudShift > 50L) {
			lastCloudShift = now;
			for (int i = 0; i < count; i++) {
				if (cloudSize[i] <= 0.0F) {
					cloudSize[i] = radius * (0.45F + (i % 5) * 0.11F);
					cloudX[i] = ((i * 977) % (2 * radius * 2)) - radius * 2.0F;
					cloudZ[i] = ((i * 1699) % (2 * radius * 2)) - radius * 2.0F;
				}
				cloudX[i] += drift * 0.05F;
				if (cloudX[i] > radius * 2.0F) {
					cloudX[i] -= radius * 4.0F;
				} else if (cloudX[i] < -radius * 2.0F) {
					cloudX[i] += radius * 4.0F;
				}
			}
		}
		int span = radius * 2;
		for (int i = 0; i < count; i++) {
			float cx = ((cloudX[i] + span) % (2 * span)) - span;
			float cz = ((cloudZ[i] + span) % (2 * span)) - span;
			float size = cloudSize[i] * (1.0F + (i % 3) * 0.12F);
			int shade = 0xFF2C3238 + ((i % 4) * 0x000608);
			WorldGeometryRenderer.billboard(buffer, pose, cx, layer + 4.0F + (i % 6) * 1.2F, cz,
					size, RenderUtils.withAlpha(shade, 0.42F));
		}
	}

	// ------------------------------------------------------------------
	// Гроза
	// ------------------------------------------------------------------

	private static void maybeStrike(RainModule module, long now, int radius, int layer) {
		int interval = module.lightningIntervalSeconds();
		if (interval <= 0) {
			return;
		}
		boolean idle = boltAt == Long.MIN_VALUE || now - boltAt > FLASH_MS;
		if (!idle || now - lastStrike < interval * 1000L * (0.55 + (now % 97) / 190.0)) {
			return;
		}
		lastStrike = now;
		boltAt = now;
		RandomSource random = RandomSource.create(now);
		boltBaseX = random.nextFloat() * 2.0F * radius - radius;
		boltBaseZ = random.nextFloat() * 2.0F * radius - radius;
		float jitterX = 0.0F;
		float jitterZ = 0.0F;
		for (int i = 0; i < BOLT_POINTS; i++) {
			jitterX += random.nextFloat() * 3.0F - 1.5F;
			jitterZ += random.nextFloat() * 3.0F - 1.5F;
			boltX[i] = boltBaseX + jitterX * 0.5F;
			boltZ[i] = boltBaseZ + jitterZ * 0.5F;
			boltY[i] = layer + 8.0F - i * ((layer + 10.0F) / (BOLT_POINTS - 1));
			// второй, более тусклый «рукав» — так разряд читается объёмным
			boltX2[i] = boltX[i] + (i > 3 ? random.nextFloat() * 2.2F - 1.1F : 0.0F);
			boltZ2[i] = boltZ[i] + (i > 3 ? random.nextFloat() * 2.2F - 1.1F : 0.0F);
		}
		double distance = Math.sqrt(boltBaseX * boltBaseX + boltBaseZ * boltBaseZ);
		// Гром приходит с задержкой: скорость звука известна, расстояние — наше
		thunderAt = now + (long) (distance / SOUND_SPEED_BLOCKS_PER_MS);
	}

	private static long lastStrike;

	private static void drawBolt(RainModule module, PoseStack.Pose pose, VertexConsumer buffer,
	                             float unitsPerPixel, long now, int radius, int layer) {
		if (boltAt == Long.MIN_VALUE) {
			return;
		}
		float t = (now - boltAt) / (float) FLASH_MS;
		if (t >= 1.0F) {
			boltAt = Long.MIN_VALUE;
			return;
		}
		// Три вспышки за разряд: 0.9 / 0.35 / 0.7 — так бьёт настоящий гром
		float flicker = t < 0.18F ? 0.95F : (t < 0.3F ? 0.3F : (t < 0.45F ? 0.8F : (1.0F - t) * 0.6F));
		int core = RenderUtils.withAlpha(0xFFEFF6FF, flicker);
		int halo = RenderUtils.withAlpha(0xFF9FC7FF, flicker * 0.45F);
		for (int i = 0; i < BOLT_POINTS - 1; i++) {
			WorldGeometryRenderer.line(buffer, pose, boltX[i], boltY[i], boltZ[i], halo,
					boltX[i + 1], boltY[i + 1], boltZ[i + 1], halo, 9.0F, unitsPerPixel);
			WorldGeometryRenderer.line(buffer, pose, boltX[i], boltY[i], boltZ[i], core,
					boltX[i + 1], boltY[i + 1], boltZ[i + 1], core, 2.2F, unitsPerPixel);
			if (i > 3) {
				WorldGeometryRenderer.line(buffer, pose, boltX2[i], boltY[i], boltZ2[i], halo,
						boltX2[i + 1], boltY[i + 1], boltZ2[i + 1], core, 1.2F, unitsPerPixel);
			}
		}
		// Удар о землю: свет и ударная волна
		float baseY = boltY[BOLT_POINTS - 1];
		WorldGeometryRenderer.glow(buffer, pose, boltBaseX, baseY, boltBaseZ, 1.4F,
				RenderUtils.withAlpha(0xFFCFE4FF, flicker * 0.75F), 4);
		WorldGeometryRenderer.ring(buffer, pose, boltBaseX, baseY + 0.05F, boltBaseZ,
				1.0F + (1.0F - t) * 6.0F, 2.0F, 28,
				RenderUtils.withAlpha(0xFFCFE4FF, (1.0F - t) * 0.35F), unitsPerPixel);
	}

	// ------------------------------------------------------------------
	// Звук
	// ------------------------------------------------------------------

	/**
	 * Шум дождя — ванильный луп {@code weather.rain}: он бесшовный и длится
	 ~15 секунд, поэтому запускаем его заново чуть раньше конца. Гром —
	 * {@code entity.lightning_bolt.thunder} с задержкой по расстоянию.
	 */
	private static void playAmbience(RainModule module, long now, int radius) {
		float volume = module.soundVolume();
		if (volume <= 0.0F) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getSoundManager() == null) {
			return;
		}
		if (now - lastAmbience > 13_000L) {
			lastAmbience = now;
			play(client, "weather.rain", volume * 0.9F, module.soundPitch());
			play(client, "weather.rain.splash", volume * 0.55F, module.soundPitch() * 1.1F);
		}
		if (thunderAt != 0 && now >= thunderAt) {
			thunderAt = 0;
			play(client, "entity.lightning_bolt.thunder", Math.min(1.0F, volume * 1.3F),
					0.9F + (radius % 5) * 0.03F);
		}
	}

	private static void play(Minecraft client, String path, float volume, float pitch) {
		try {
			SoundEvent event = SoundEvent.createVariableRangeEvent(
					Identifier.fromNamespaceAndPath("minecraft", path));
			client.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.WEATHER,
					Math.max(0.0F, Math.min(1.0F, volume)), Math.max(0.5F, Math.min(2.0F, pitch)),
					RandomSource.create(), client.player == null ? 0.0 : client.player.getX(),
					client.player == null ? 0.0 : client.player.getY(),
					client.player == null ? 0.0 : client.player.getZ()));
		} catch (Throwable ignored) {
			// Звук — украшение: он не имеет права ронять кадр
		}
	}
}

package com.dreamcast.client.render;

import com.dreamcast.client.module.impl.CosmosModule;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Космос на игроках и эффекты смерти.
 *
 * <p>«Текстура космоса» рисуется процедурно, тайлами по силуэту цели: поле цвета
 * — трёхоктавный value-noise, звёзды — хэш по клетке сетки. Тайлы — биллборды,
 * поэтому «обёртка» всегда смотрит на камеру и не требует ни своей текстуры, ни
 * правки модели игрока (ни того, ни другого игра модулю не даёт). Картинка
 * течёт: координата выборки сдвигается временем, звёзды мерцают по своей фазе.</p>
 *
 * <p>Эффекты смерти живут в кольцевом буфере: завёл — прожил жизнь — Freed. Их
 * заводит модуль на игровом потоке, а рисует и озвучивает этот класс; между
 * потоками передаётся только очередь спавнов ({@link ArrayDeque} под замком),
 * никаких общих массивов на два потока.</p>
 */
public final class CosmosRenderer {

	/** Сколько эффектов может идти одновременно. */
	private static final int MAX_EFFECTS = 12;
	/** Время жизни эффектов, мс. */
	private static final int LIFE_LIGHTNING = 760;
	private static final int LIFE_ANGEL = 1700;
	private static final int LIFE_DISSOLVE = 1200;
	/** Потолок тайлов космоса на кадр: дальше начинает есть FPS. */
	private static final int MAX_TILES = 2600;
	private static final double SOUND_SPEED_BLOCKS_PER_MS = 0.34;

	// Кольцевой буфер эффектов. slot.start == 0 — слот свободен.
	private static final long[] startAt = new long[MAX_EFFECTS];
	private static final long[] soundAt = new long[MAX_EFFECTS];
	private static final int[] kind = new int[MAX_EFFECTS];
	private static final int[] seed = new int[MAX_EFFECTS];
	private static final double[] ex = new double[MAX_EFFECTS];
	private static final double[] ey = new double[MAX_EFFECTS];
	private static final double[] ez = new double[MAX_EFFECTS];
	private static final ArrayDeque<long[]> PENDING = new ArrayDeque<>();

	private CosmosRenderer() {
	}

	/** Вид эффекта — тот же код, что и в настройках модуля. */
	public static final int LIGHTNING = 0;
	public static final int ANGEL = 1;
	public static final int DISSOLVE = 2;

	public static void reset() {
		for (int i = 0; i < MAX_EFFECTS; i++) {
			startAt[i] = 0;
			soundAt[i] = 0;
		}
		synchronized (PENDING) {
			PENDING.clear();
		}
	}

	/** Ставит эффект в очередь (безопасно из любого потока). */
	public static void spawn(int effectKind, double x, double y, double z, int entitySeed, boolean withSound, long now) {
		synchronized (PENDING) {
			if (PENDING.size() > MAX_EFFECTS * 2) {
				return; // захлебнулись — значит, резня, тихо пропускаем
			}
			PENDING.add(new long[]{effectKind, Double.doubleToLongBits(x), Double.doubleToLongBits(y),
					Double.doubleToLongBits(z), entitySeed, withSound ? 1L : 0L, now});
		}
	}

	private static void drain(long now) {
		while (true) {
			long[] pending;
			synchronized (PENDING) {
				pending = PENDING.poll();
			}
			if (pending == null) {
				return;
			}
			int slot = freeSlot(now);
			if (slot < 0) {
				continue;
			}
			kind[slot] = (int) pending[0];
			ex[slot] = Double.longBitsToDouble(pending[1]);
			ey[slot] = Double.longBitsToDouble(pending[2]);
			ez[slot] = Double.longBitsToDouble(pending[3]);
			seed[slot] = (int) pending[4];
			startAt[slot] = pending[6];
			soundAt[slot] = pending[5] == 1L ? pending[6] + 40L : 0L;
		}
	}

	private static int freeSlot(long now) {
		int oldest = -1;
		long oldestStart = Long.MAX_VALUE;
		for (int i = 0; i < MAX_EFFECTS; i++) {
			if (startAt[i] == 0) {
				return i;
			}
			if (startAt[i] < oldestStart) {
				oldestStart = startAt[i];
				oldest = i;
			}
		}
		return oldest; // все заняты — подменяем самый старый
	}

	// ------------------------------------------------------------------
	// Космос на целях
	// ------------------------------------------------------------------

	public static void render(CosmosModule module, List<CosmosModule.Target> targets, PoseStack.Pose pose,
	                          VertexConsumer buffer, double camX, double camY, double camZ,
	                          float unitsPerPixel, long now) {
		int tiles = 0;
		float time = now / 1000.0F * (0.25F + module.flowSpeed() * 0.16F);
		int density = module.starDensity();
		int cover = module.coverage();
		int padPercent = module.pad();
		int first = module.firstColor();
		int second = module.secondColor();
		boolean aura = module.wantsAura();

		for (CosmosModule.Target target : targets) {
			double dx = target.x() - camX;
			double dy = target.y() - camY;
			double dz = target.z() - camZ;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance < 0.45 || distance > module.reach()) {
				continue;
			}
			// Оси «экрана» для этой цели: right — горизонталь перпендикулярно взгляду,
			// up — перпендикуляр обоим. Так тайлы лежат плоскостью к камере.
			double rightLength = Math.sqrt(dx * dx + dz * dz);
			if (rightLength < 1.0e-4) {
				continue;
			}
			float rx = (float) (-dz / rightLength);
			float rz = (float) (dx / rightLength);
			// up = normalize(cross(right, dir))
			// up = normalize(cross(right, dir))
			double cx = -rz * dy;
			double cy = rz * dx - rx * dz;
			double cz = rx * dy;
			double upLength = Math.max(1.0e-4, Math.sqrt(cx * cx + cy * cy + cz * cz));
			float ux = (float) (cx / upLength);
			float uy = (float) (cy / upLength);
			float uz = (float) (cz / upLength);

			double width = target.width() * (1.0 + padPercent / 100.0) + padPercent / 200.0;
			double height = target.height() * (1.0 + padPercent / 300.0);
			int cols = module.detail();
			int rows = Math.max(4, cols * 2);
			double tile = Math.max(0.045, Math.min(width / cols, height / rows) * 1.7);
			double centerY = dy + height * 0.5;

			if (aura) {
				int haloColor = RenderUtils.mix(first, second, 0.5F);
				WorldGeometryRenderer.glow(buffer, pose, dx, centerY, dz,
						height * 0.42, RenderUtils.withAlpha(haloColor, 0.14F), 3);
			}

			int slot = Math.abs(target.id());
			for (int v = 0; v < rows; v++) {
				double vv = rows == 1 ? 0.5 : v / (double) (rows - 1);
				double offsetY = (vv - 0.5) * height;
				for (int u = 0; u < cols; u++) {
					if (tiles++ > MAX_TILES) {
						return;
					}
					double uu = cols == 1 ? 0.5 : u / (double) (cols - 1);
					double offsetX = (uu - 0.5) * width;
					double px = dx + rx * offsetX + ux * offsetY;
					double py = centerY + uy * offsetY;
					double pz = dz + rz * offsetX + uz * offsetY;

					float sample = fbm((float) (uu * 2.6) + time * 0.11F + slot * 0.37F,
							(float) (vv * 4.2) - time, slot);
					// Края прямоугольника растворяются, иначе вместо «кожи» видно плашку
					float edge = edgeMask((float) uu) * edgeMask((float) vv);
					float glow = Math.max(0.0F, (sample - 0.42F) / 0.58F);
					int star = starCell((float) (uu * 9.0) + slot, (float) (vv * 14.0) - time * 1.6F, density);
					float intensity = (glow * 0.85F + (star > 0 ? 0.75F : 0.0F)) * edge * cover / 100.0F;
					if (intensity < 0.035F) {
						continue; // пустое небо не рисуем вообще — это и есть главный оптим.
					}
					int color;
					if (star > 0) {
						float twinkle = 0.65F + 0.35F * (float) Math.sin(now / 260.0 + star);
						color = RenderUtils.withAlpha(0xFFF2F8FF, Math.min(1.0F, 0.85F * twinkle * edge));
					} else {
						color = RenderUtils.withAlpha(RenderUtils.mix(first, second, sample),
								Math.min(0.92F, intensity));
					}
					WorldGeometryRenderer.billboard(buffer, pose, px, py, pz,
							star > 0 ? tile * 0.22 : tile * (0.5 + glow * 0.34), color);
				}
			}
		}

		drain(now);
		renderEffects(pose, buffer, camX, camY, camZ, unitsPerPixel, now);
	}

	/** Мягкое исчезновение к краям: 0 у границы, 1 в центре полосы. */
	static float edgeMask(float t) {
		float edge = Math.min(t, 1.0F - t) / 0.28F;
		return edge <= 0.0F ? 0.0F : Math.min(1.0F, edge);
	}

	// ------------------------------------------------------------------
	// Процедурное «небо»: value-noise + звёзды
	// ------------------------------------------------------------------

	/** 32-битный хэш от трёх целых — стабилен между кадрами и между запусками. */
	static int hash(int x, int y, int z) {
		int h = x * 374761393 + y * 668265263 + z * 1442695041;
		h = (h ^ (h >>> 13)) * 1274126177;
		return h ^ (h >>> 16);
	}

	/** Хэш от вещественных координат клетки (для шума и звёзд). */
	static float noiseAt(float u, float v, int layer) {
		int i0 = (int) Math.floor(u);
		int j0 = (int) Math.floor(v);
		float fu = u - i0;
		float fv = v - j0;
		// smootherstep вместо линейной интерполяции: без «грани» между клетками
		fu = fu * fu * fu * (fu * (fu * 6.0F - 15.0F) + 10.0F);
		fv = fv * fv * fv * (fv * (fv * 6.0F - 15.0F) + 10.0F);
		float a = lattice(i0, j0, layer);
		float b = lattice(i0 + 1, j0, layer);
		float c = lattice(i0, j0 + 1, layer);
		float d = lattice(i0 + 1, j0 + 1, layer);
		return (a + (b - a) * fu) * (1.0F - fv) + (c + (d - c) * fu) * fv;
	}

	private static float lattice(int i, int j, int layer) {
		return (hash(i, j, layer) & 0xFFFF) / 65535.0F;
	}

	/** Три октавы: крупные облака газа + средняя структура + мелкая пыдь. */
	static float fbm(float u, float v, int layer) {
		float sum = 0.0F;
		float weight = 0.55F;
		float frequency = 1.0F;
		for (int octave = 0; octave < 3; octave++) {
			sum += noiseAt(u * frequency, v * frequency, layer + octave * 71) * weight;
			weight *= 0.5F;
			frequency *= 2.1F;
		}
		return Math.min(1.0F, Math.max(0.0F, sum / 0.925F));
	}

	/**
	 * Есть ли звезда в клетке сетки: 0 — нет, иначе — фаза мерцания.
	 * Порог растёт с {@code density}, поэтому «Звёзды, %» — монотонная ручка.
	 */
	static int starCell(float u, float v, int density) {
		if (density <= 0) {
			return 0;
		}
		int i = (int) Math.floor(u * 3.0F);
		int j = (int) Math.floor(v * 3.0F);
		int h = hash(i, j, density);
		int roll = h & 0xFF;
		return roll < density * 255 / 100 ? (h >>> 8) & 0x3F : 0;
	}

	// ------------------------------------------------------------------
	// Эффекты смерти
	// ------------------------------------------------------------------

	private static void renderEffects(PoseStack.Pose pose, VertexConsumer buffer, double camX, double camY,
	                                  double camZ, float unitsPerPixel, long now) {
		for (int i = 0; i < MAX_EFFECTS; i++) {
			long start = startAt[i];
			if (start == 0) {
				continue;
			}
			int life = switch (kind[i]) {
				case ANGEL -> LIFE_ANGEL;
				case DISSOLVE -> LIFE_DISSOLVE;
				default -> LIFE_LIGHTNING;
			};
			float t = (now - start) / (float) life;
			if (t >= 1.0F) {
				startAt[i] = 0;
				soundAt[i] = 0;
				continue;
			}
			double x = ex[i] - camX;
			double y = ey[i] - camY;
			double z = ez[i] - camZ;
			switch (kind[i]) {
				case ANGEL -> drawAngel(pose, buffer, x, y, z, t, unitsPerPixel, now, seed[i]);
				case DISSOLVE -> drawDissolve(pose, buffer, x, y, z, t, unitsPerPixel, seed[i]);
				default -> drawLightning(pose, buffer, x, y, z, t, unitsPerPixel, now, seed[i]);
			}
			if (soundAt[i] != 0 && now >= soundAt[i]) {
				soundAt[i] = 0;
				play(i, x, y, z);
			}
		}
	}

	/** Разряд сверху: ломаная, три вспышки, ударная волна. */
	private static void drawLightning(PoseStack.Pose pose, VertexConsumer buffer, double x, double y, double z,
	                                  float t, float unitsPerPixel, long now, int salt) {
		float flicker = t < 0.16F ? 0.95F : (t < 0.3F ? 0.3F : (t < 0.44F ? 0.85F : (1.0F - t) * 0.7F));
		int core = RenderUtils.withAlpha(0xFFEFF6FF, flicker);
		int halo = RenderUtils.withAlpha(0xFF9FC7FF, flicker * 0.45F);
		int steps = 9;
		// Сегменты «перетекают» каждые ~90 мс — так разряд выглядит живым, а не палкой
		long step = now / 90L;
		double px = 0.0;
		double pz = 0.0;
		double top = y + 18.0;
		double prevX = x, prevY = top, prevZ = z;
		for (int i = 1; i <= steps; i++) {
			float f = i / (float) steps;
			int h = hash(salt, i, (int) step);
			px = ((h & 0xFF) / 255.0F - 0.5F) * 3.4F * (1.0F - f);
			pz = (((h >>> 8) & 0xFF) / 255.0F - 0.5F) * 3.4F * (1.0F - f);
			double ny = y + (top - y) * (1.0F - f);
			WorldGeometryRenderer.line(buffer, pose, prevX, prevY, prevZ, halo,
					x + px, ny, z + pz, halo, 10.0F, unitsPerPixel);
			WorldGeometryRenderer.line(buffer, pose, prevX, prevY, prevZ, core,
					x + px, ny, z + pz, core, 2.4F, unitsPerPixel);
			prevX = x + px;
			prevY = ny;
			prevZ = z + pz;
		}
		WorldGeometryRenderer.glow(buffer, pose, x + px, y + 0.3, z + pz, 1.1F,
				RenderUtils.withAlpha(0xFFCFE4FF, flicker * 0.8F), 4);
		WorldGeometryRenderer.ring(buffer, pose, x, y + 0.05, z, 0.6F + t * 5.4F, 2.0F, 26,
				RenderUtils.withAlpha(0xFFCFE4FF, (1.0F - t) * 0.4F), unitsPerPixel);
	}

	/** «Ангел вылетает»: восстающая фигура с крыльями, нимбом и столпом света. */
	private static void drawAngel(PoseStack.Pose pose, VertexConsumer buffer, double x, double y, double z,
	                              float t, float unitsPerPixel, long now, int salt) {
		float rise = 1.0F - (1.0F - t) * (1.0F - t); // easeOut: сначала быстро, потом планирует
		float fade = t < 0.7F ? 1.0F : (1.0F - t) / 0.3F;
		double bodyY = y + 0.3 + rise * 3.1;

		// Столп света: стопка биллбордов от земли к фигуре
		for (int i = 0; i < 7; i++) {
			float f = i / 6.0F;
			WorldGeometryRenderer.billboard(buffer, pose, x, y + 0.25 + f * (bodyY - y), z,
					0.30 + f * 0.22, RenderUtils.withAlpha(0xFFDFF0FF, 0.16F * fade * (1.0F - f * 0.5F)));
		}
		// Тело и голова
		WorldGeometryRenderer.glow(buffer, pose, x, bodyY + 0.35, z, 0.42F,
				RenderUtils.withAlpha(0xFFF8FBFF, 0.85F * fade), 4);
		WorldGeometryRenderer.billboard(buffer, pose, x, bodyY + 0.92, z, 0.13F,
				RenderUtils.withAlpha(0xFFFFFFFF, 0.95F * fade));
		// Крылья: две симметричные ломаные, размах «взмахивает»
		float beat = (float) Math.sin(t * 9.0F) * 0.22F;
		for (int side = -1; side <= 1; side += 2) {
			double lastX = x;
			double lastY = bodyY + 0.55;
			double lastZ = z;
			for (int i = 1; i <= 4; i++) {
				float f = i / 4.0F;
				double spread = f * (1.05 + beat) * side;
				double nx = x + spread;
				double ny = bodyY + 0.55 + f * (0.42 + beat * 0.6) - f * f * 0.34;
				double nz = z + f * 0.18 * side;
				WorldGeometryRenderer.line(buffer, pose, lastX, lastY, lastZ,
						RenderUtils.withAlpha(0xFFEFF6FF, 0.7F * fade * (1.0F - f * 0.35F)),
						nx, ny, nz, RenderUtils.withAlpha(0xFFDCEBFF, 0.45F * fade), 2.2F, unitsPerPixel);
				WorldGeometryRenderer.glow(buffer, pose, nx, ny, nz, 0.16,
						RenderUtils.withAlpha(0xFFEFF6FF, 0.32F * fade), 2);
				lastX = nx;
				lastY = ny;
				lastZ = nz;
			}
		}
		// Нимб и искры вокруг
		WorldGeometryRenderer.ring(buffer, pose, x, bodyY + 1.16, z, 0.22F + 0.03F * (float) Math.sin(t * 6.0F),
				1.6F, 20, RenderUtils.withAlpha(0xFFFFD98A, 0.8F * fade), unitsPerPixel);
		for (int i = 0; i < 9; i++) {
			double angle = (i / 9.0) * Math.PI * 2.0 + t * 2.2 + salt;
			double radius = 0.5 + 0.34 * Math.sin(t * 3.1 + i);
			WorldGeometryRenderer.billboard(buffer, pose, x + Math.cos(angle) * radius,
					bodyY + 0.3 + Math.sin(angle * 2.0) * 0.5, z + Math.sin(angle) * radius,
					Math.max(0.02, unitsPerPixel * 1.6), RenderUtils.withAlpha(0xFFFFF4C8, 0.5F * fade));
		}
	}

	/** Растворение: пепел разлетается вверх и в стороны, в конце схлопывается кольцо. */
	private static void drawDissolve(PoseStack.Pose pose, VertexConsumer buffer, double x, double y, double z,
	                                 float t, float unitsPerPixel, int salt) {
		float fade = 1.0F - t;
		int particles = 44;
		for (int i = 0; i < particles; i++) {
			int h = hash(salt, i, 7);
			float angle = ((h & 0xFF) / 255.0F) * (float) (Math.PI * 2.0);
			float outward = 0.35F + ((h >>> 8) & 0xFF) / 255.0F * 1.5F;
			float rise = 0.9F + ((h >>> 16) & 0xFF) / 255.0F * 2.4F;
			double f = t * (0.55 + ((h >>> 24) & 7) / 12.0);
			double px = x + Math.cos(angle) * outward * f;
			double pz = z + Math.sin(angle) * outward * f;
			double py = y + 0.15 + f * rise + Math.sin(f * 4.0 + i) * 0.12;
			int color = ((h >>> 12) & 1) == 0
					? RenderUtils.withAlpha(0xFFB48CFF, 0.7F * fade)
					: RenderUtils.withAlpha(0xFF8CD8FF, 0.65F * fade);
			WorldGeometryRenderer.billboard(buffer, pose, px, py, pz,
					Math.max(0.02, unitsPerPixel * (1.2F + (i % 4) * 0.4F) * (1.0F + f)), color);
		}
		WorldGeometryRenderer.glow(buffer, pose, x, y + 0.8, z, 0.9F * fade,
				RenderUtils.withAlpha(0xFF6A4FBF, 0.35F * fade), 3);
		WorldGeometryRenderer.ring(buffer, pose, x, y + 0.05, z, 1.7F * (1.0F - t), 1.8F, 24,
				RenderUtils.withAlpha(0xFFCFB8FF, 0.4F * fade), unitsPerPixel);
	}

	// ------------------------------------------------------------------
	// Звук: ванильные семплы, только решаем когда и как громко
	// ------------------------------------------------------------------

	private static void play(int slot, double x, double y, double z) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.getSoundManager() == null) {
			return;
		}
		String path = switch (kind[slot]) {
			case ANGEL -> "entity.player.levelup";
			case DISSOLVE -> "entity.enderman.teleport";
			default -> "entity.lightning_bolt.thunder";
		};
		float volume = kind[slot] == ANGEL ? 0.55F : 0.8F;
		try {
			SoundEvent event = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("minecraft", path));
			client.getSoundManager().play(new SimpleSoundInstance(event, SoundSource.PLAYERS, volume,
					0.95F + (seed[slot] % 7) * 0.03F, RandomSource.create(), ex[slot], ey[slot], ez[slot]));
		} catch (Throwable ignored) {
			// Эффект важнее звука: не падаем из-за чужого имени семпла
		}
	}
}

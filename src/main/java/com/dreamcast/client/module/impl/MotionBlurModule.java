package com.dreamcast.client.module.impl;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.shader.PostFx;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Motion Blur — размытие в движении поверх готового кадра.
 *
 * <p>В референсе это пост-эффект на Satin (свой FBO + velocity-буфер + N кадров
 * истории). В 26.2 такого пути нет, поэтому эффект делает наша прослойка
 * {@link PostFx}: она собирает ванильную {@code PostChain} из конфигурации,
 * построенной кодом, а {@code ShaderManagerMixin} подсовывает её игре вместо
 * загрузки {@code post_effect/*.json}. Результат — настоящий ползунок силы,
 * радиус смаза и выбор алгоритма, а не три заранее заготовленных пресета.</p>
 *
 * <p>История — одна persistent-цель (экспоненциальное сглаживание по кадрам),
 * а не кольцо из N кадров: ванильный конвейер не даёт переставлять цели между
 * кадрами, а N полноэкранных копий за кадр стоили бы слишком дорого. Размытие
 * применяется только там, где пиксель отличается от прошлого кадра, поэтому
 * статичная сцена остаётся резкой.</p>
 *
 * <p>Запасной путь — три JSON-цепочки ({@code motion_blur{,_soft,_strong}.json}):
 * он используется, пока рантайм-цепочка не собрана (первый кадр), если сборка
 * провалилась или если плавную силу выключить настройкой. Никакой из этого
 * потери качества нет — это те же шейдеры, просто с фиксированными весами.</p>
 *
 * <p>С Iris эффект несовместим: Iris подменяет пайплайн, и наши цели пост-цепочки
 * не существуют — модуль отказывается работать и пишет причину в чат. NoBlind с
 * ним не конфликтует: тот гасит только {@code nausea}-цепочку.</p>
 */
public class MotionBlurModule extends Module {

	/** Наш id: по нему {@code PostFx} понимает, что цепочку надо собрать самим. */
	private static final Identifier RUNTIME_CHAIN =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur_runtime");

	private static final String FRAGMENT = "dreamcast:post/motion_blur";
	private static final String HISTORY_TARGET = "accum";
	private static final String SCRATCH_TARGET = "mix";

	/** Цепочки-запасной вариант: один и тот же алгоритм, разный вес истории. */
	private static final Identifier CHAIN_SOFT =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur_soft");
	private static final Identifier CHAIN_MEDIUM =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur");
	private static final Identifier CHAIN_STRONG =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur_strong");

	/** Кадров в секунду, выше которых считаем «быстрый монитор». */
	private static final int FAST_FPS = 100;
	private static final int SLOW_FPS = 70;

	/** Шаг квантования веса: без него компенсация герцовки дергала бы сборку каждый кадр. */
	private static final float BLEND_STEP = 0.05f;

	private final IntSetting strength = intSetting("strength", "Сила", 45, 5, 95);
	private final IntSetting radius = intSetting("radius", "Радиус смаза", 2, 0, 6);
	private final ModeSetting algorithm = mode("algorithm", "Алгоритм", "backwards",
			ModeSetting.option("backwards", "Тянуть назад"),
			ModeSetting.option("centered", "По центру"));
	private final BooleanSetting smooth = bool("smooth", "Плавная сила (своя цепочка)", true);
	private final BooleanSetting refreshScale = bool("refresh_scale", "Компенсировать герцовку", true);

	/** Параметры прошлого кадра: по ним решаем, надо ли пересобирать цепочку. */
	private record Params(int strength, int radius, String algorithm, int fpsBand) {
	}

	private Params declared;
	private boolean fallbackAnnounced;

	/** Чтобы не долбить сообщением про Iris каждый кадр. */
	private long lastWarningMs;

	public MotionBlurModule() {
		super("motion_blur", "Motion Blur", "Размытие в движении: своя пост-цепочка 26.2, без Satin",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	/**
	 * Какую цепочку применить, либо {@code null}, если эффект применять нельзя.
	 *
	 * <p>Именно отсюда читает {@code GameRendererMixin}: поле {@code postEffectId}
	 * ваниль пересобирает каждый кадр в {@code checkEntityPostEffect}, поэтому
	 * «применить один раз при включении» не работает — надо отвечать на каждый кадр.
	 * Вызывается на render-потоке, поэтому здесь же и собираем цепочку.</p>
	 */
	public Identifier chainId() {
		if (!isEnabled() || blockedByIris()) {
			return null;
		}
		int band = fpsBand();
		Params params = new Params(strength.get(), radius.get(), algorithm.getValue(), band);
		if (smooth.isEnabled() && PostFx.available()) {
			if (!params.equals(declared)) {
				declared = params;
				PostFx.declare(RUNTIME_CHAIN, PostFx.motionBlur(FRAGMENT, HISTORY_TARGET, SCRATCH_TARGET,
						blend(params), sampleRadius(params), algorithmValue(params)));
				PostFx.prepare(RUNTIME_CHAIN);
			}
			if (PostFx.isReady(RUNTIME_CHAIN)) {
				fallbackAnnounced = false;
				return RUNTIME_CHAIN;
			}
			announceFallbackIfNeeded();
		}
		return chainFor(levelFor(strength.get()), band);
	}

	/**
	 * Вес истории. Квантуется по {@value #BLEND_STEP}, потому что uniform'ы
	 * пост-цепочки пишутся один раз при сборке: каждое изменение = пересборка
	 * {@code PostChain}, и без шага ползунок на 100 значений стоил бы 100 компиляций.
	 */
	private float blend(Params params) {
		float raw = params.strength() / 100f * 0.9f;
		if (refreshScale.isEnabled()) {
			// На 120–144 Гц кадры короче, история накапливается быстрее — чуть ослабляем;
			// на 60 Гц и ниже, наоборот, добавляем, иначе шлейфа не видно.
			raw += params.fpsBand() > 0 ? -BLEND_STEP : BLEND_STEP;
		}
		float quantized = Math.round(raw / BLEND_STEP) * BLEND_STEP;
		return Math.max(0.05f, Math.min(0.95f, quantized));
	}

	private static float sampleRadius(Params params) {
		return 0.5f + params.radius() * 0.6f;
	}

	private static int algorithmValue(Params params) {
		return "centered".equals(params.algorithm()) ? 1 : 0;
	}

	/** Полоса FPS: -1 медленный монитор, 0 обычный, 1 быстрый (пороги с запасом, чтобы не щёлкало). */
	private int fpsBand() {
		if (!refreshScale.isEnabled()) {
			return 0;
		}
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return 0;
		}
		int fps = client.getFps();
		if (fps <= 0 || fps >= 1000) {
			return 0;
		}
		if (fps >= FAST_FPS) {
			return 1;
		}
		return fps <= SLOW_FPS ? -1 : 0;
	}

	/** Запасной вариант: уровень силы из процента — это тот же пресет, что лежал в меню. */
	private static String levelFor(int strengthPercent) {
		if (strengthPercent <= 25) {
			return "soft";
		}
		return strengthPercent > 60 ? "strong" : "medium";
	}

	private static Identifier chainFor(String level, int band) {
		int index = switch (level) {
			case "soft" -> 0;
			case "strong" -> 2;
			default -> 1;
		};
		index = Math.max(0, Math.min(2, index + band));
		return switch (index) {
			case 0 -> CHAIN_SOFT;
			case 2 -> CHAIN_STRONG;
			default -> CHAIN_MEDIUM;
		};
	}

	/** Раз в сессию объясняем, почему сила снова стала пресетом. */
	private void announceFallbackIfNeeded() {
		if (fallbackAnnounced) {
			return;
		}
		fallbackAnnounced = true;
		String error = PostFx.lastError();
		warn(error.isEmpty()
				? "Motion Blur: свою цепочку собрать ещё не успели — пока работаем на пресетах"
				: "Motion Blur: свою цепочку собрать не вышло (" + error + "), работаем на пресетах");
	}

	private boolean blockedByIris() {
		if (!isIrisLoaded()) {
			return false;
		}
		long now = Util.getMillis();
		if (now - lastWarningMs > 15_000L) {
			lastWarningMs = now;
			warn("Motion Blur выключен: Iris меняет пайплайн рендера, ванильные пост-цепочки недоступны");
		}
		return true;
	}

	private static boolean isIrisLoaded() {
		try {
			return FabricLoader.getInstance().isModLoaded("iris");
		} catch (Throwable ignored) {
			// загрузчик недоступен (сборка без fabric-loader) — считаем, что Iris нет
			return false;
		}
	}

	private void warn(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.gui != null && client.gui.hud != null) {
			client.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
		}
	}

	@Override
	protected void onDisable() {
		// Цепочку чистить руками не нужно: ваниль сама обнулит postEffectId в
		// ближайшем checkEntityPostEffect. А вот свою — надо: она держит две цели.
		declared = null;
		lastWarningMs = 0L;
		fallbackAnnounced = false;
		PostFx.forget(RUNTIME_CHAIN);
	}
}

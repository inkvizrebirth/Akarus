package com.dreamcast.client.module.impl;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ModeSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Motion Blur — размытие в движении поверх готового кадра.
 *
 * <p>В референсе это пост-эффект на Satin (свой FBO + velocity-буфер). В 26.2
 * такого пути нет: свой FBO/GLSL из blaze3d не нарулить, а единственная
 * поддержанная цепочка пост-обработки — ванильные «пост-эффекты»
 * ({@code GameRenderer#postEffectId} + {@code assets/<ns>/post_effect/*.json}),
 * куда мы и подключаемся. Цепочка лежит у нас в ресурсах
 * ({@code dreamcast:post_effect/motion_blur*.json}), накопление истории идёт в
 * persistent-таргете, а «размазывается» только то, что изменилось между кадрами:
 * статичная сцена остаётся резкой.</p>
 *
 * <p><b>Почему сила — три варианта, а не ползунок.</b> В 26.2 uniform'ы пост-цепочки
 * читаются из JSON при загрузке, публичного {@code PostChain#setUniform} нет, поэтому
 * менять вес истории на лету нельзя. Вместо этого под каждый уровень лежит своя
 * цепочка (мягкое/среднее/сильное), а модуль выбирает её целиком — это и есть
 * «компенсация герцовки»: на 144 Гц кадры короче, история накапливается быстрее,
 * и тот же вес дал бы слишком длинный шлейф, поэтому при высоком FPS берётся
 * вариант на ступень сильнее.</p>
 *
 * <p>С Iris эффект несовместим: Iris подменяет пайплайн и наши цели пост-цепочки
 * не существуют — как и в референсе, модуль отказывается работать и пишет причину
 * в чат. Остальное (NoBlind) с ним не конфликтует: тот гасит только
 * {@code nausea}-цепочку, а мы пишем свою.</p>
 */
public class MotionBlurModule extends Module {

	/** Цепочки: один и тот же алгоритм, разный вес истории. */
	private static final Identifier CHAIN_SOFT =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur_soft");
	private static final Identifier CHAIN_MEDIUM =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur");
	private static final Identifier CHAIN_STRONG =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "motion_blur_strong");

	/** Кадров в секунду, выше которых считаем «быстрый монитор». */
	private static final int FAST_FPS = 100;
	private static final int SLOW_FPS = 70;

	private final ModeSetting strength = mode("strength", "Сила", "medium",
			ModeSetting.option("soft", "Мягкое"),
			ModeSetting.option("medium", "Среднее"),
			ModeSetting.option("strong", "Сильное"));
	private final BooleanSetting refreshScale = bool("refresh_scale", "Компенсировать герцовку", true);

	/** Чтобы не долбить сообщением про Iris каждый кадр. */
	private long lastWarningMs;

	public MotionBlurModule() {
		super("motion_blur", "Motion Blur", "Размытие в движении (пост-эффект 26.2, без своего FBO)",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	/**
	 * Какую цепочку применить, либо {@code null}, если эффект применять нельзя.
	 *
	 * <p>Именно отсюда читает {@code GameRendererMixin}: поле
	 * {@code postEffectId} ваниль пересобирает каждый кадр в
	 * {@code checkEntityPostEffect}, поэтому «применить один раз при включении»
	 * не работает — надо отвечать на каждый кадр.</p>
	 */
	public Identifier chainId() {
		if (!isEnabled() || blockedByIris()) {
			return null;
		}
		String base = strength.getValue();
		if (!refreshScale.isEnabled()) {
			return chainFor(base, 1);
		}
		int fps = measuredFps();
		// На мониторе 120–144 Гц тот же вес истории даёт в полтора раза более
		// длинный шлейф по времени, поэтому сдвигаем уровень вверх
		int bump = fps >= FAST_FPS ? 1 : (fps <= SLOW_FPS ? -1 : 0);
		return chainFor(base, bump);
	}

	private static Identifier chainFor(String level, int shift) {
		return switch (shift) {
			case -1 -> CHAIN_SOFT;
			case 1 -> CHAIN_STRONG;
			default -> switch (level) {
				case "soft" -> CHAIN_SOFT;
				case "strong" -> CHAIN_STRONG;
				default -> CHAIN_MEDIUM;
			};
		};
	}

	/**
	 * Частика кадров как прокси герцовки монитора: {@code glfwGetVideoMode} в
	 * 26.2 на Wayland и под некоторыми сборщиками возвращает null, а FPS при
	 * включённой вертикальной синхронизации ровно ей равна.
	 */
	private int measuredFps() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return 60;
		}
		int fps = client.getFps();
		return fps > 0 && fps < 1000 ? fps : 60;
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
		// ближайшем checkEntityPostEffect, и следующий кадр пойдёт без нас
		lastWarningMs = 0L;
	}
}

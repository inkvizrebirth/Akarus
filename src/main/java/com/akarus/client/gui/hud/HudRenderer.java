package com.akarus.client.gui.hud;

import com.akarus.client.AkarusClient;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.AutoWalkModule;
import com.akarus.client.module.impl.FreeCamModule;
import com.akarus.client.module.impl.FreeLookModule;
import com.akarus.client.module.impl.HudInfoModule;
import com.akarus.client.util.RenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Отрисовка своего HUD.
 *
 * В 26.2 для этого используется Fabric HUD API: мы регистрируем элемент,
 * который игра вызывает каждый кадр и передаёт нам {@link GuiGraphicsExtractor}.
 */
public final class HudRenderer {

	private static final int MARGIN = 6;
	private static final int PADDING = 6;
	private static final int LINE_GAP = 2;

	private static final int PANEL_BACKGROUND = 0xB80A0A0D;
	private static final int PANEL_BORDER = 0x2AFFFFFF;
	private static final int TEXT_COLOR = 0xFFEDEDF5;

	private HudRenderer() {
	}

	public static void register() {
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AkarusClient.MOD_ID, "overlay"),
				HudRenderer::render);

		// Координаты AutoWalk рисуются отдельным элементом: они нужны
		// и тогда, когда сам HUD-инфо выключен
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AkarusClient.MOD_ID, "auto_walk"),
				HudRenderer::renderAutoWalk);

		// Отдельная плашка свободной камеры: координаты взгляда и расстояние до игрока
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AkarusClient.MOD_ID, "free_cam"),
				HudRenderer::renderFreeCam);
	}

	/**
	 * Панель AutoWalk внизу по центру экрана.
	 *
	 * Пока игрок летает фрикамом — это его текущие координаты;
	 * как только цель задана — координаты цели и оставшееся расстояние.
	 */
	private static void renderAutoWalk(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		if (player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
			return;
		}

		// find, а не getModule: HUD рисуется каждый кадр, и исключение из-за
		// незарегистрированного модуля уронило бы рендер
		AutoWalkModule autoWalk = ModuleManager.find(AutoWalkModule.class);
		if (autoWalk == null || !autoWalk.isEnabled()) {
			return;
		}

		Font font = client.font;
		boolean walking = autoWalk.isWalking();

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		boolean flying = freeCam != null && freeCam.isEnabled();
		// Точку берём там, где сейчас «глаза»: во время осмотра это камера, а не игрок —
		// игрок стоит на месте, и его координаты для выбора точки бесполезны
		BlockPos position;
		if (walking && autoWalk.getTarget() != null) {
			position = autoWalk.getTarget();
		} else if (flying) {
			position = BlockPos.containing(freeCam.position());
		} else {
			position = player.blockPosition();
		}

		String title = (walking ? "Цель: " : flying ? "Смотрю на: " : "Иду к: ") + AutoWalkModule.format(position);
		String hint = walking
				? "Осталось " + Math.round(autoWalk.getDistance()) + " м   •   ПКМ — отменить маршрут"
				: flying
						? "Высота " + Math.round(freeCam.distanceToPlayer()) + " м от игрока   •   ПКМ — идти сюда"
						: "ПКМ — Baritone пойдёт на эту точку";

		int screenWidth = client.getWindow().getGuiScaledWidth();
		int screenHeight = client.getWindow().getGuiScaledHeight();

		int width = Math.max(font.width(title), font.width(hint)) + PADDING * 2 + 3;
		int height = font.lineHeight * 2 + LINE_GAP + PADDING * 2 + 2;
		int x = (screenWidth - width) / 2;
		int y = screenHeight - height - 46;

		int accent = ModuleCategory.MOVEMENT.getAccent();

		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 5, 4);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 5, PANEL_BORDER, PANEL_BACKGROUND);

		// Акцентная полоса сверху панели и «дышащая» точка рядом с заголовком
		graphics.fill(x + 7, y, x + width - 7, y + 1, RenderUtils.withAlpha(accent, 0.9f));

		graphics.text(font, title, x + PADDING + 3, y + PADDING + 1, TEXT_COLOR, true);
		graphics.text(font, hint, x + PADDING + 3, y + PADDING + font.lineHeight + LINE_GAP + 1, 0xFFA6A6B2, true);

		long time = Util.getMillis();
		float pulse = 0.55f + 0.45f * (float) Math.sin(time / 320.0);
		int dotX = x + width - PADDING - 3;
		int dotY = y + PADDING + (font.lineHeight - 4) / 2;
		graphics.fill(dotX, dotY, dotX + 4, dotY + 4, RenderUtils.withAlpha(accent, pulse));
	}

	/**
	 * Компактная плашка свободной камеры: где летим и как далеко от игрока.
	 *
	 * Пока AutoWalk включён, координаты показывает его панель — дублировать их
	 * двумя плашками незачем. FreeLook показывает свою короткую подсказку, когда
	 * FreeCam не активен.
	 */
	private static void renderFreeCam(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
			return;
		}

		AutoWalkModule autoWalk = ModuleManager.find(AutoWalkModule.class);
		boolean autoWalkBusy = autoWalk != null && autoWalk.isEnabled();

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		if (freeCam != null && freeCam.isEnabled() && freeCam.showsHudInfo() && !autoWalkBusy) {
			Vec3 position = freeCam.position();
			BlockPos block = BlockPos.containing(position);
			String title = "FreeCam " + block.getX() + " " + block.getY() + " " + block.getZ();
			String hint = "Игрок в " + String.format(java.util.Locale.ROOT, "%.1f", freeCam.distanceToPlayer()) + " м   •   N — выключить";

			Font font = client.font;
			int screenWidth = client.getWindow().getGuiScaledWidth();
			int x = (screenWidth - font.width(title)) / 2;
			int y = client.getWindow().getGuiScaledHeight() - 58;

			int accent = ModuleCategory.MOVEMENT.getAccent();

			graphics.text(font, title, x, y, TEXT_COLOR, true);
			graphics.text(font, hint, x, y + font.lineHeight + 1, RenderUtils.withAlpha(accent, 0.9f), true);
			return;
		}

		FreeLookModule freeLook = ModuleManager.find(FreeLookModule.class);
		if (freeLook == null || !freeLook.isEnabled() || autoWalkBusy) {
			return;
		}

		// FreeLook: камера крутится мышью, игрок всегда в центре и играет как обычно
		Font font = client.font;
		String title = "FreeLook";
		String hint = "Игрок в центре   •   выключить — " + freeLook.getBindLabel();
		int screenWidth = client.getWindow().getGuiScaledWidth();
		int x = (screenWidth - Math.max(font.width(title), font.width(hint))) / 2;
		int y = client.getWindow().getGuiScaledHeight() - 58;

		int accent = ModuleCategory.MOVEMENT.getAccent();

		graphics.text(font, title, x, y, TEXT_COLOR, true);
		graphics.text(font, hint, x, y + font.lineHeight + 1, RenderUtils.withAlpha(accent, 0.9f), true);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		// Рисуем только в мире, когда нет открытого экрана и HUD не скрыт клавишей F1
		if (player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
			return;
		}

		HudInfoModule hud = ModuleManager.find(HudInfoModule.class);
		if (hud == null || !hud.isEnabled()) {
			return;
		}

		Font font = client.font;
		long time = Util.getMillis();
		int x = MARGIN;
		int y = MARGIN;

		// Водяной знак: название и версия, переливающиеся по радуге
		if (hud.showWatermark()) {
			drawRainbow(graphics, font, AkarusClient.MOD_NAME + " " + AkarusClient.MOD_VERSION, x, y, time);
			y += font.lineHeight + LINE_GAP + 3;
		}

		// Информационные строки
		List<String> lines = new ArrayList<>();
		if (hud.showFps()) {
			lines.add("FPS: " + client.getFps());
		}
		if (hud.showCoordinates()) {
			BlockPos position = player.blockPosition();
			lines.add("XYZ: " + position.getX() + " " + position.getY() + " " + position.getZ());
		}
		if (hud.showDirection()) {
			lines.add("Направление: " + directionName(player.getDirection()));
		}
		if (hud.showPing()) {
			lines.add("Пинг: " + getPing(client) + " мс");
		}

		if (!lines.isEmpty()) {
			int width = 0;
			for (String line : lines) {
				width = Math.max(width, font.width(line));
			}
			width += PADDING * 2 + 3;
			int height = lines.size() * (font.lineHeight + LINE_GAP) - LINE_GAP + PADDING * 2;

			// Панелька с акцентной полосой слева
			RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 4, PANEL_BORDER, PANEL_BACKGROUND);
			graphics.fillGradient(x + 1, y + 3, x + 3, y + height - 3,
					RenderUtils.rainbow(time, 0.0f), RenderUtils.rainbow(time, 0.18f));

			int textY = y + PADDING;
			for (String line : lines) {
				graphics.text(font, line, x + PADDING + 3, textY, TEXT_COLOR, true);
				textY += font.lineHeight + LINE_GAP;
			}

			y += height + 5;
		}

		// Список включённых модулей: чем длиннее название, тем выше строка
		if (hud.showModuleList()) {
			List<Module> active = ModuleManager.getAll().stream()
					.filter(Module::isEnabled)
					.sorted(Comparator.comparingInt((Module module) -> -font.width(module.getName()))
							.thenComparing(Module::getName))
					.toList();

			int index = 0;
			for (Module module : active) {
				graphics.text(font, module.getName(), x, y, RenderUtils.rainbow(time, index * 0.08f), true);
				y += font.lineHeight + LINE_GAP;
				index++;
			}
		}
	}

	/** Текст, у которого каждый символ своего цвета — классический «радужный» водяной знак. */
	private static void drawRainbow(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, long time) {
		int cursor = x;
		for (int i = 0; i < text.length(); i++) {
			String symbol = String.valueOf(text.charAt(i));
			graphics.text(font, symbol, cursor, y, RenderUtils.rainbow(time, i * 0.035f), true);
			cursor += font.width(symbol);
		}
	}

	private static int getPing(Minecraft client) {
		if (client.getConnection() == null || client.player == null) {
			return 0;
		}
		PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
		return info == null ? 0 : info.getLatency();
	}

	private static String directionName(Direction direction) {
		return switch (direction) {
			case NORTH -> "Север (-Z)";
			case SOUTH -> "Юг (+Z)";
			case EAST -> "Восток (+X)";
			case WEST -> "Запад (-X)";
			default -> "-";
		};
	}
}

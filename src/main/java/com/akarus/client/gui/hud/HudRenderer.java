package com.akarus.client.gui.hud;

import com.akarus.client.AkarusClient;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleManager;
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

	private static final int PANEL_BACKGROUND = 0xB40D0D12;
	private static final int PANEL_BORDER = 0x33FFFFFF;
	private static final int TEXT_COLOR = 0xFFEDEDF5;

	private HudRenderer() {
	}

	public static void register() {
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(AkarusClient.MOD_ID, "overlay"),
				HudRenderer::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		// Рисуем только в мире, когда нет открытого экрана и HUD не скрыт клавишей F1
		if (player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
			return;
		}

		HudInfoModule hud = ModuleManager.getModule(HudInfoModule.class);
		if (!hud.isEnabled()) {
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

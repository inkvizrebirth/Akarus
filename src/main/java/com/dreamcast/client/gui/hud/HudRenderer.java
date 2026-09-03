package com.dreamcast.client.gui.hud;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.gui.theme.ClientTheme;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.AutoWalkModule;
import com.dreamcast.client.module.impl.FreeCamModule;
import com.dreamcast.client.module.impl.FreeLookModule;
import com.dreamcast.client.module.impl.HudInfoModule;
import com.dreamcast.client.module.impl.MediaPlayerModule;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.util.RenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Отрисовка HUD.
 *
 * HUD — набор независимых элементов (водяной знак, инфопанель, список модулей,
 * бинды, медиаплеер, уведомления). Какие показывать — решает модуль HUD
 * (галочки в его настройках), а где — раскладка {@link HudLayout}: элементы
 * таскаются мышью в редакторе (клавиша модуля HUD) или с открытым чатом.
 *
 * Всё рисуется фирменным шрифтом (Manrope) и в цветах темы клиента.
 */
public final class HudRenderer {

	private static final int PADDING = 6;
	private static final int LINE_GAP = 2;
	private static final int MARGIN = 6;

	private static final int PANEL_BACKGROUND = 0xB80A0A0D;
	private static final int PANEL_BORDER = 0x2AFFFFFF;
	private static final int TEXT_COLOR = 0xFFEDEDF5;
	private static final int TEXT_SECONDARY = 0xFFA6A6B2;
	private static final int TEXT_DIM = 0xFF6B6B78;
	private static final int ENABLED_GREEN = 0xFF7BE08A;

	/** Счётчики появления модулей в списке (0..10 шагов фейда). */
	private static final Map<String, Integer> MODULE_ALPHA = new HashMap<>();

	/** Кадровое время для анимаций (обновляется в начале отрисовки). */
	private static float frameDelta;

	private HudRenderer() {
	}

	public static void register() {
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "overlay"),
				HudRenderer::render);

		// Контекстные плашки: координаты AutoWalk и свободной камеры
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "auto_walk"),
				HudRenderer::renderAutoWalk);
		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "free_cam"),
				HudRenderer::renderFreeCam);

		HudLayout.load();
	}

	/** Человекочитаемое имя элемента раскладки — для редактора. */
	public static String elementLabel(String elementId) {
		return switch (elementId) {
			case HudInfoModule.ELEMENT_WATERMARK -> "Водяной знак";
			case HudInfoModule.ELEMENT_INFO -> "Инфопанель";
			case HudInfoModule.ELEMENT_MODULE_LIST -> "Список модулей";
			case HudInfoModule.ELEMENT_KEYBINDS -> "Бинды";
			case HudInfoModule.ELEMENT_MEDIA -> "Медиаплеер";
			case HudInfoModule.ELEMENT_NOTIFICATIONS -> "Уведомления";
			default -> elementId;
		};
	}

	// ------------------------------------------------------------------
	// Вход из Fabric HUD API
	// ------------------------------------------------------------------

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		// Рисуем в мире; с открытым чатом HUD тоже нужен — за элемент можно
		// тащить прямо из чата (см. ChatScreenMixin)
		Screen screen = client.gui.screen();
		boolean chatOpen = screen instanceof ChatScreen;
		if (player == null || client.level == null
				|| (screen != null && !chatOpen)
				|| client.gui.hud.isHidden()) {
			return;
		}

		renderElements(graphics, client, false);

		// Перетаскивание из чата: ведём элемент за курсором, пока зажата кнопка
		if (chatOpen && HudLayout.isDragging()) {
			double scale = client.getWindow().getGuiScaledWidth() / (double) client.getWindow().getScreenWidth();
			double mouseX = client.mouseHandler.xpos() * scale;
			double mouseY = client.mouseHandler.ypos() * scale;
			HudLayout.dragTo(mouseX, mouseY,
					client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
			if (!client.mouseHandler.isLeftPressed()) {
				HudLayout.endDrag();
			}
		}
	}

	/**
	 * Рисует все элементы HUD. Используется и обычным рендером, и редактором
	 * раскладки (там editorMode = true: показываем даже неотмеченные элементы,
	 * но приглушённо — чтобы их тоже можно было схватить и поставить).
	 */
	public static void renderElements(GuiGraphicsExtractor graphics, Minecraft client, boolean editorMode) {
		long now = Util.getMillis();
		frameDelta = Math.min((now - lastFrame) / 1000.0f, 0.05f);
		lastFrame = now;

		HudInfoModule hud = ModuleManager.find(HudInfoModule.class);
		if (hud == null) {
			return;
		}

		boolean watermark = hud.shows(HudInfoModule.ELEMENT_WATERMARK);
		boolean info = hud.shows(HudInfoModule.ELEMENT_INFO);
		boolean moduleList = hud.shows(HudInfoModule.ELEMENT_MODULE_LIST);
		boolean keybinds = hud.shows(HudInfoModule.ELEMENT_KEYBINDS);
		boolean media = hud.shows(HudInfoModule.ELEMENT_MEDIA);
		boolean notifications = hud.shows(HudInfoModule.ELEMENT_NOTIFICATIONS);

		if (watermark || editorMode) {
			drawWatermark(graphics, client, watermark ? 1.0f : 0.35f, now);
		}
		if (info || editorMode) {
			drawInfo(graphics, client, info ? 1.0f : 0.35f);
		}
		if (moduleList || editorMode) {
			drawModuleList(graphics, client, moduleList ? 1.0f : 0.35f, now);
		}
		if (keybinds || editorMode) {
			drawKeybinds(graphics, client, keybinds ? 1.0f : 0.35f);
		}
		if (media || editorMode) {
			drawMedia(graphics, client, media ? 1.0f : 0.35f, now);
		}
		if (notifications || editorMode) {
			drawNotifications(graphics, client, notifications ? 1.0f : 0.35f, now);
		}
	}

	private static long lastFrame;

	// ------------------------------------------------------------------
	// Элемент: водяной знак
	// ------------------------------------------------------------------

	private static void drawWatermark(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		Font font = client.font;
		String brand = DreamcastClient.MOD_NAME + " " + DreamcastClient.MOD_VERSION;

		int[] position = HudLayout.position(HudInfoModule.ELEMENT_WATERMARK, MARGIN, MARGIN);
		int pillW = RenderUtils.width(font, brand) + 28;
		int pillH = font.lineHeight + 8;

		int accent = ClientTheme.accent(now);
		RenderUtils.drawSoftShadow(graphics, position[0], position[1], pillW, pillH, 5, 3);
		RenderUtils.fillRoundedBorder(graphics, position[0], position[1], pillW, pillH, 5,
				RenderUtils.withAlpha(PANEL_BORDER, 0.9f * alpha), RenderUtils.withAlpha(0xE0070708, alpha));

		// Полоска темы сверху пилюли — «текущий» цвет перелива
		for (int i = 0; i < pillW - 10; i++) {
			float t = i / (float) (pillW - 10);
			graphics.fill(position[0] + 5 + i, position[1], position[0] + 6 + i, position[1] + 1,
					RenderUtils.withAlpha(ClientTheme.gradientAt(t, now), 0.85f * alpha));
		}

		float pulse = 0.4f + 0.6f * (float) Math.abs(Math.sin(now / 900.0));
		graphics.fill(position[0] + 6, position[1] + pillH / 2 - 2, position[0] + 10, position[1] + pillH / 2 + 2,
				RenderUtils.withAlpha(accent, pulse * alpha));

		// Текст — перелив темы по символам
		int cursor = position[0] + 15;
		for (int i = 0; i < brand.length(); i++) {
			String symbol = String.valueOf(brand.charAt(i));
			int color = ClientTheme.gradientAt(i / (float) brand.length(), now);
			RenderUtils.textFlat(graphics, font, symbol, cursor, position[1] + 4,
					RenderUtils.withAlpha(color, alpha));
			cursor += RenderUtils.width(font, symbol);
		}

		HudLayout.publishBounds(HudInfoModule.ELEMENT_WATERMARK, position[0], position[1], pillW, pillH);
	}

	// ------------------------------------------------------------------
	// Элемент: инфопанель (FPS · XYZ · направление · пинг)
	// ------------------------------------------------------------------

	private static void drawInfo(GuiGraphicsExtractor graphics, Minecraft client, float alpha) {
		LocalPlayer player = client.player;
		Font font = client.font;
		long now = Util.getMillis();

		List<String> lines = new ArrayList<>();
		lines.add("FPS: " + client.getFps());
		if (player != null) {
			BlockPos pos = player.blockPosition();
			lines.add("XYZ: " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
			lines.add("Направление: " + directionName(player.getDirection()));
			lines.add("Пинг: " + getPing(client) + " мс");
		}

		int width = 0;
		for (String line : lines) {
			width = Math.max(width, RenderUtils.width(font, line));
		}
		width += PADDING * 2 + 9;
		int height = lines.size() * (font.lineHeight + LINE_GAP) - LINE_GAP + PADDING * 2;

		int[] position = HudLayout.position(HudInfoModule.ELEMENT_INFO, MARGIN, MARGIN + font.lineHeight + 14);
		int x = position[0];
		int y = position[1];

		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 4, 3);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 4,
				RenderUtils.withAlpha(PANEL_BORDER, 0.9f * alpha), RenderUtils.withAlpha(PANEL_BACKGROUND, alpha));
		// Акцентная полоса слева — текущий цвет перелива
		graphics.fillGradient(x + 1, y + 3, x + 3, y + height - 3,
				RenderUtils.withAlpha(ClientTheme.gradientAt(0.0f, now), alpha),
				RenderUtils.withAlpha(ClientTheme.gradientAt(1.0f, now), alpha));

		int textY = y + PADDING;
		for (String line : lines) {
			RenderUtils.text(graphics, font, line, x + PADDING + 6, textY,
					RenderUtils.withAlpha(TEXT_COLOR, alpha));
			textY += font.lineHeight + LINE_GAP;
		}

		HudLayout.publishBounds(HudInfoModule.ELEMENT_INFO, x, y, width, height);
	}

	// ------------------------------------------------------------------
	// Элемент: список активных модулей
	// ------------------------------------------------------------------

	private static void drawModuleList(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		Font font = client.font;

		List<Module> active = ModuleManager.getAll().stream()
				.filter(Module::isEnabled)
				.sorted(Comparator.comparingInt((Module module) -> -RenderUtils.width(font, module.getName()))
						.thenComparing(Module::getName))
				.toList();

		// Прозрачности живут своей жизнью: включённые набирают 0..10,
		// выключенные доживают на месте и уходят с фейдом
		for (Module module : active) {
			MODULE_ALPHA.merge(module.getName(), 0, Math::max);
		}
		List<String> gone = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : MODULE_ALPHA.entrySet()) {
			boolean stillActive = active.stream().anyMatch(m -> m.getName().equals(entry.getKey()));
			int steps = Math.max(0, Math.min(10, entry.getValue() + (stillActive ? 1 : -1)));
			entry.setValue(steps);
			if (!stillActive && steps <= 0) {
				gone.add(entry.getKey());
			}
		}
		gone.forEach(MODULE_ALPHA::remove);

		if (active.isEmpty() && MODULE_ALPHA.isEmpty()) {
			return;
		}

		int[] position = HudLayout.position(HudInfoModule.ELEMENT_MODULE_LIST, MARGIN, 112);

		int y = position[1];
		int index = 0;
		for (Module module : active) {
			drawModuleLine(graphics, font, position[0], y, now, index, module.getName(), alpha);
			y += font.lineHeight + LINE_GAP;
			index++;
		}
		for (String name : List.copyOf(MODULE_ALPHA.keySet())) {
			if (active.stream().anyMatch(m -> m.getName().equals(name))) {
				continue;
			}
			drawModuleLine(graphics, font, position[0], y, now, index, name, alpha);
			y += font.lineHeight + LINE_GAP;
			index++;
		}
	}

	/** Строка модуля с easing: заезд слева + фейд. */
	private static void drawModuleLine(GuiGraphicsExtractor graphics, Font font, int x, int y,
			long time, int index, String name, float alpha) {
		int alphaSteps = MODULE_ALPHA.getOrDefault(name, 10);
		float a = Math.max(0.0f, Math.min(1.0f, alphaSteps / 10.0f));
		float eased = a * a * (3.0f - 2.0f * a);
		int shift = Math.round((1.0f - eased) * 6.0f);
		int baseColor = ClientTheme.gradientAt(index * 0.09f, time);
		int color = (baseColor & 0x00FFFFFF) | (Math.round(255 * eased * alpha) << 24);
		RenderUtils.textFlat(graphics, font, name, x + shift, y, color);
	}

	// ------------------------------------------------------------------
	// Элемент: бинды (модули с клавишами и их статус)
	// ------------------------------------------------------------------

	private static void drawKeybinds(GuiGraphicsExtractor graphics, Minecraft client, float alpha) {
		Font font = client.font;
		long now = Util.getMillis();

		List<Module> bound = ModuleManager.getAll().stream()
				.filter(module -> !"unknown".equals(module.getBindName()))
				.sorted(Comparator.comparing(Module::getName))
				.toList();
		if (bound.isEmpty()) {
			return;
		}

		int rowHeight = font.lineHeight + 3;
		int width = 0;
		for (Module module : bound) {
			width = Math.max(width, RenderUtils.width(font, module.getBindLabel()) + 8
					+ RenderUtils.width(font, module.getName()));
		}
		width += PADDING * 2 + 4;
		int height = bound.size() * rowHeight - 3 + PADDING * 2;

		int screenW = client.getWindow().getGuiScaledWidth();
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_KEYBINDS, screenW - width - MARGIN, 92);
		int x = position[0];
		int y = position[1];

		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 5, 3);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 5,
				RenderUtils.withAlpha(PANEL_BORDER, 0.9f * alpha), RenderUtils.withAlpha(0xCC09090C, alpha));

		// Заголовок-пилюля над списком: маленькая, с точкой-«записью»
		String title = "бинды";
		int titleW = RenderUtils.width(font, title) + 12;
		RenderUtils.fillRounded(graphics, x + 4, y - 5, titleW, font.lineHeight + 4, 4,
				RenderUtils.withAlpha(0xE60A0A0D, alpha));
		RenderUtils.textFlat(graphics, font, title, x + 10, y - 4,
				RenderUtils.withAlpha(ClientTheme.accent(now), alpha));

		int rowY = y + PADDING;
		for (Module module : bound) {
			boolean on = module.isEnabled();
			// Бейдж клавиши: тёмная плашка с рамкой цвета статуса
			String key = module.getBindLabel();
			int keyW = RenderUtils.width(font, key) + 8;
			int statusColor = on ? ENABLED_GREEN : RenderUtils.withAlpha(TEXT_SECONDARY, 0.8f);
			RenderUtils.fillRoundedBorder(graphics, x + PADDING, rowY - 1, keyW, font.lineHeight + 2, 3,
					RenderUtils.withAlpha(on ? ENABLED_GREEN : 0x30FFFFFF, 0.55f * alpha),
					RenderUtils.withAlpha(0x66000000, alpha));
			RenderUtils.textFlat(graphics, font, key, x + PADDING + 4, rowY,
					RenderUtils.withAlpha(TEXT_COLOR, alpha));
			// Имя: белое — выключен, зелёное — включён
			RenderUtils.text(graphics, font, module.getName(), x + PADDING + keyW + 6, rowY,
					RenderUtils.withAlpha(on ? ENABLED_GREEN : 0xFFF6F6F8, alpha));
			rowY += rowHeight;
		}

		HudLayout.publishBounds(HudInfoModule.ELEMENT_KEYBINDS, x, y - 6, width, height + 6);
	}

	// ------------------------------------------------------------------
	// Элемент: уведомления
	// ------------------------------------------------------------------

	private static void drawNotifications(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		Font font = client.font;
		List<Notifications.Notification> items = Notifications.snapshot();
		if (items.isEmpty()) {
			return;
		}

		int screenW = client.getWindow().getGuiScaledWidth();
		int[] anchor = HudLayout.position(HudInfoModule.ELEMENT_NOTIFICATIONS, screenW - 170, MARGIN);
		int x = anchor[0];
		int y = anchor[1];

		// Ограничиваем ширину плашек зоной элемента (плюс запас на длинный текст)
		int zoneWidth = Math.max(120, screenW - x - 6);

		int maxWidth = 110;
		for (Notifications.Notification item : items) {
			maxWidth = Math.max(maxWidth,
					RenderUtils.width(font, item.title) + 26 + RenderUtils.width(font, item.message));
		}
		maxWidth = Math.min(maxWidth, zoneWidth);

		int cursorY = y;
		int widest = 0;
		int heightTotal = 0;
		List<int[]> boxes = new ArrayList<>();

		for (Notifications.Notification item : items) {
			// Анимация появления: сдвиг справа + фейд; исчезновение — обратный ход
			item.appear = Math.min(1.0f, item.appear + frameDelta * 5.5f);
			float dismiss = item.dismissProgress();
			float slide = easeOut(item.appear) * (1.0f - easeIn(dismiss));
			float shown = slide;

			int boxWidth = Math.min(maxWidth,
					Math.max(96, RenderUtils.width(font, item.title) + 18
							+ Math.min(RenderUtils.width(font, item.message),
							maxWidth - RenderUtils.width(font, item.title) - 30)));
			int boxHeight = font.lineHeight + 10;
			int boxX = x + maxWidth - boxWidth + Math.round((1.0f - slide) * 26.0f);
			int color = switch (item.type) {
				case OK -> 0xFF7BE08A;
				case WARN -> 0xFFFFC66C;
				case ERROR -> 0xFFFF8095;
				case INFO -> ClientTheme.accent(now);
			};

			RenderUtils.drawSoftShadow(graphics, boxX, cursorY, boxWidth, boxHeight, 6, 3);
			RenderUtils.fillRoundedBorder(graphics, boxX, cursorY, boxWidth, boxHeight, 6,
					RenderUtils.withAlpha(color, (0.75f - 0.45f * (1.0f - shown)) * alpha),
					RenderUtils.withAlpha(0xE40A0A0D, alpha * (0.35f + 0.65f * shown)));
			// Цветная полоска слева — тип уведомления
			graphics.fill(boxX + 2, cursorY + 4, boxX + 3, cursorY + boxHeight - 4,
					RenderUtils.withAlpha(color, alpha * shown));

			RenderUtils.textFlat(graphics, font, item.title, boxX + 9, cursorY + 5,
					RenderUtils.withAlpha(color, alpha * shown));
			RenderUtils.drawClamped(graphics, font, item.message,
					boxX + 9 + RenderUtils.width(font, item.title) + 5, cursorY + 6,
					boxWidth - 14 - RenderUtils.width(font, item.title) - 5,
					RenderUtils.withAlpha(TEXT_SECONDARY, alpha * shown));

			boxes.add(new int[]{boxX, cursorY, boxWidth, boxHeight});
			widest = Math.max(widest, boxWidth);
			cursorY += boxHeight + 3;
			heightTotal += boxHeight + 3;
		}

		HudLayout.publishBounds(HudInfoModule.ELEMENT_NOTIFICATIONS, x, y, maxWidth,
				Math.max(heightTotal - 3, 10));
	}

	private static float easeOut(float t) {
		return 1.0f - (1.0f - t) * (1.0f - t);
	}

	private static float easeIn(float t) {
		return t * t;
	}

	// ------------------------------------------------------------------
	// Элемент: карточка медиаплеера
	// ------------------------------------------------------------------

	private static void drawMedia(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		MediaPlayerModule media = ModuleManager.find(MediaPlayerModule.class);
		Font font = client.font;

		int screenW = client.getWindow().getGuiScaledWidth();
		int screenH = client.getWindow().getGuiScaledHeight();

		int width = Math.min(150, screenW - 24);
		int height = 34 + font.lineHeight;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_MEDIA,
				screenW - width - MARGIN, screenH - height - MARGIN);
		int x = position[0];
		int y = position[1];

		// В редакторе карточка-фантом, даже если плеер выключен
		boolean playable = media != null && media.isEnabled() && media.showsHudCard();
		if (!playable && alpha >= 1.0f) {
			return;
		}

		HudLayout.publishBounds(HudInfoModule.ELEMENT_MEDIA, x, y, width, height);

		int accent = ClientTheme.gradientAt(0.35f, now);
		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 6, 4);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 6,
				RenderUtils.withAlpha(PANEL_BORDER, 0.9f * alpha), RenderUtils.withAlpha(0xD2080809, alpha));

		String title;
		String subtitle;
		boolean playing = media != null && media.isPlaying();

		if (media == null || !media.hasTrack()) {
			title = "Медиаплеер";
			subtitle = media != null && media.trackCount() > 0 ? "Трек не выбран" : "Папка dreamcast/media пуста";
		} else if (media.hasError()) {
			title = "Ошибка звука";
			subtitle = RenderUtils.clamp(font, media.errorText(), width - 16);
		} else {
			title = RenderUtils.clamp(font, stripExtension(media.currentName()),
					width - 24 - RenderUtils.width(font, playing ? "\u25B6" : "\u275A\u275A"));
			subtitle = formatTime(media.positionMillis()) + " / " + formatTime(Math.max(1L, media.durationMillis()));
		}

		RenderUtils.textFlat(graphics, font, playing ? "\u25B6" : "\u275A\u275A", x + 8, y + 7,
				RenderUtils.withAlpha(accent, (playing ? 0.95f : 0.5f) * alpha));
		RenderUtils.text(graphics, font, title, x + 20, y + 7, RenderUtils.withAlpha(TEXT_COLOR, alpha));

		int barX = x + 8;
		int barY = y + 7 + font.lineHeight + 4;
		int barWidth = width - 16;
		float progress = media != null && media.hasTrack() && media.durationMillis() > 0
				? Math.min(1.0f, (float) media.positionMillis() / (float) media.durationMillis())
				: 0.0f;
		RenderUtils.drawSlider(graphics, barX, barY, barWidth, 4, progress, RenderUtils.withAlpha(accent, alpha));

		RenderUtils.text(graphics, font, subtitle, x + 8, barY + 7,
				RenderUtils.withAlpha(TEXT_SECONDARY, alpha));

		// Мини-эквалайзер
		int barsX = x + width - 8 - (7 * 3 - 1);
		for (int bar = 0; bar < 7; bar++) {
			float wave = playing ? 0.5f + 0.5f * (float) Math.sin(now / (140.0 + bar * 47.0) + bar * 1.7) : 0.15f;
			int barHeight = 2 + Math.round(wave * 8.0f);
			graphics.fill(barsX + bar * 3, barY + 9 - barHeight, barsX + bar * 3 + 2, barY + 9,
					RenderUtils.withAlpha(accent, (0.25f + 0.7f * wave) * alpha));
		}
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static String formatTime(long millis) {
		long seconds = Math.max(0L, millis / 1000L);
		return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	// ------------------------------------------------------------------
	// Контекстные плашки (не входят в раскладку — всегда по своим местам)
	// ------------------------------------------------------------------

	private static void renderAutoWalk(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		if (player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
			return;
		}

		AutoWalkModule autoWalk = ModuleManager.find(AutoWalkModule.class);
		if (autoWalk == null || !autoWalk.isEnabled()) {
			return;
		}

		Font font = client.font;
		boolean walking = autoWalk.isWalking();

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		boolean flying = freeCam != null && freeCam.isEnabled();
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
		int width = Math.max(RenderUtils.width(font, title), RenderUtils.width(font, hint)) + PADDING * 2 + 3;
		int height = font.lineHeight * 2 + LINE_GAP + PADDING * 2 + 2;
		int x = (screenWidth - width) / 2;
		int y = screenHeight - height - 46;

		int accent = ClientTheme.gradientAt(0.4f, Util.getMillis());

		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 5, 4);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 5, PANEL_BORDER, PANEL_BACKGROUND);
		graphics.fill(x + 7, y, x + width - 7, y + 1, RenderUtils.withAlpha(accent, 0.9f));

		RenderUtils.text(graphics, font, title, x + PADDING + 3, y + PADDING + 1, TEXT_COLOR);
		RenderUtils.text(graphics, font, hint, x + PADDING + 3, y + PADDING + font.lineHeight + LINE_GAP + 1,
				TEXT_SECONDARY);

		float pulse = 0.55f + 0.45f * (float) Math.sin(Util.getMillis() / 320.0);
		int dotX = x + width - PADDING - 3;
		int dotY = y + PADDING + (font.lineHeight - 4) / 2;
		graphics.fill(dotX, dotY, dotX + 4, dotY + 4, RenderUtils.withAlpha(accent, pulse));
	}

	private static void renderFreeCam(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;

		if (player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) {
			return;
		}

		AutoWalkModule autoWalk = ModuleManager.find(AutoWalkModule.class);
		boolean autoWalkBusy = autoWalk != null && autoWalk.isEnabled();

		FreeCamModule freeCam = ModuleManager.find(FreeCamModule.class);
		if (freeCam != null && freeCam.isEnabled() && freeCam.showsHudInfo() && !autoWalkBusy) {
			Vec3 position = freeCam.position();
			BlockPos block = BlockPos.containing(position);
			String title = "FreeCam " + block.getX() + " " + block.getY() + " " + block.getZ();
			String hint = "Игрок в " + String.format(java.util.Locale.ROOT, "%.1f", freeCam.distanceToPlayer())
					+ " м   •   " + freeCam.getBindLabel() + " — выключить";

			Font font = client.font;
			int screenWidth = client.getWindow().getGuiScaledWidth();
			int x = (screenWidth - RenderUtils.width(font, title)) / 2;
			int y = client.getWindow().getGuiScaledHeight() - 58;

			int accent = ClientTheme.gradientAt(0.4f, Util.getMillis());
			RenderUtils.text(graphics, font, title, x, y, TEXT_COLOR);
			RenderUtils.text(graphics, font, hint, x, y + font.lineHeight + 1,
					RenderUtils.withAlpha(accent, 0.9f));
			return;
		}

		FreeLookModule freeLook = ModuleManager.find(FreeLookModule.class);
		if (freeLook == null || !freeLook.isEnabled() || autoWalkBusy) {
			return;
		}

		Font font = client.font;
		String title = "FreeLook";
		String hint = "Игрок в центре   •   выключить — " + freeLook.getBindLabel();
		int screenWidth = client.getWindow().getGuiScaledWidth();
		int x = (screenWidth - Math.max(RenderUtils.width(font, title), RenderUtils.width(font, hint))) / 2;
		int y = client.getWindow().getGuiScaledHeight() - 58;

		int accent = ClientTheme.gradientAt(0.4f, Util.getMillis());
		RenderUtils.text(graphics, font, title, x, y, TEXT_COLOR);
		RenderUtils.text(graphics, font, hint, x, y + font.lineHeight + 1, RenderUtils.withAlpha(accent, 0.9f));
	}

	// ------------------------------------------------------------------

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

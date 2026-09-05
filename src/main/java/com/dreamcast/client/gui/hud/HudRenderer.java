package com.dreamcast.client.gui.hud;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.gui.UiKit;
import com.dreamcast.client.gui.theme.ClientTheme;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.module.impl.AutoWalkModule;
import com.dreamcast.client.module.impl.FreeCamModule;
import com.dreamcast.client.module.impl.FreeLookModule;
import com.dreamcast.client.module.impl.HudInfoModule;
import com.dreamcast.client.module.impl.KillAuraModule;
import com.dreamcast.client.module.impl.MediaPlayerModule;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.util.RenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

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
	private static final Deque<Long> LEFT_CLICKS = new ArrayDeque<>();
	private static final Deque<Long> RIGHT_CLICKS = new ArrayDeque<>();
	private static boolean leftWasDown;
	private static boolean rightWasDown;
	// История скорости для элемента HUD: кольцевой буфер отсчётов, шаг 50 мс.
	// Считаем не по кадру, а по времени, чтобы график не «плыл» на 300 FPS.
	private static final int SPEED_SAMPLES = 72;
	private static final float[] speedHistory = new float[SPEED_SAMPLES];
	private static int speedHead;
	private static long speedLastSample;
	private static float speedScale = 6.0F; // авто-масштаб графика (сглаженный максимум)

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
				case HudInfoModule.ELEMENT_TARGET -> "Target HUD";
				case HudInfoModule.ELEMENT_EFFECTS -> "Активные эффекты";
				case HudInfoModule.ELEMENT_ARMOR -> "Броня и оффхенд";
				case HudInfoModule.ELEMENT_KEYSTROKES -> "Keystrokes и CPS";
								case HudInfoModule.ELEMENT_SPEED -> "Скорость (бар + график)";
				case HudInfoModule.ELEMENT_COORDS -> "Координаты";
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
		HudLayout.beginFrame();
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
		boolean target = hud.shows(HudInfoModule.ELEMENT_TARGET);
		boolean effects = hud.shows(HudInfoModule.ELEMENT_EFFECTS);
		boolean armor = hud.shows(HudInfoModule.ELEMENT_ARMOR);
		boolean keystrokes = hud.shows(HudInfoModule.ELEMENT_KEYSTROKES);
		boolean speed = hud.shows(HudInfoModule.ELEMENT_SPEED);
		boolean coords = hud.shows(HudInfoModule.ELEMENT_COORDS);

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
		if (target || editorMode) {
			drawTarget(graphics, client, target ? 1.0f : 0.35f, now);
		}
		if (effects || editorMode) {
			drawEffects(graphics, client, effects ? 1.0f : 0.35f, now);
		}
		if (armor || editorMode) {
			drawArmor(graphics, client, armor ? 1.0f : 0.35f, now);
		}
		if (keystrokes || editorMode) {
			drawKeystrokes(graphics, client, keystrokes ? 1.0f : 0.35f, now);
		}
		if (speed || editorMode) {
			drawSpeed(graphics, client, speed ? 1.0f : 0.35f, now);
		}
		if (coords || editorMode) {
			drawCoords(graphics, client, coords ? 1.0f : 0.35f);
		}
	}

	private static long lastFrame;

	// ------------------------------------------------------------------
	// Элемент: водяной знак
	// ------------------------------------------------------------------

	private static final Identifier EMBLEM =
			net.minecraft.resources.Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID,
					"textures/gui/icons/emblem.png");

	/**
	 * Водяной знак: эмблема, имя клиента с переливом по буквам и короткая
	 * сводка (ник · пинг · FPS · часы). Всё рисуется примитивами и одной
	 * текстурой, поэтому не зависит от шрифтов и модификаций интерфейса.
	 */
		private static void drawWatermark(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		Font font = client.font;
		String brand = DreamcastClient.MOD_NAME.toUpperCase(java.util.Locale.ROOT);
		String version = "v" + DreamcastClient.MOD_VERSION;

		List<String> facts = new ArrayList<>();
		if (client.getUser() != null) {
			facts.add(client.getUser().getName());
		}
		if (client.player != null) {
			facts.add(getPing(client) + " мс");
		}
		facts.add(client.getFps() + " FPS");
		facts.add(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

		int iconSize = 13;
		int brandWidth = RenderUtils.width(font, brand) + brand.length();
		int versionWidth = RenderUtils.width(font, version);
		int factsWidth = RenderUtils.width(font, String.join("   ", facts));
		int width = Math.round((9 + iconSize + 5 + brandWidth + 4 + versionWidth + 16 + factsWidth + 9) * 1.0F);
		int height = Math.max(font.lineHeight + 12, iconSize + 9);

		int[] position = HudLayout.position(HudInfoModule.ELEMENT_WATERMARK, MARGIN, MARGIN);
		int x = position[0];
		int y = position[1];
		float appear = UiKit.appear("watermark", now, 420);
		HudLayout.publishBounds(HudInfoModule.ELEMENT_WATERMARK, x, y, width, height);

		float pulse = 0.5F + 0.5F * (float) Math.sin(now / 1100.0);
		UiKit.panel(graphics, x, y, width, height, 6.5F, alpha * appear, 0.35F + 0.25F * pulse, now);

		int plate = height - 8;
		int accent = ClientTheme.accent(now);
		RenderUtils.fillRounded(graphics, x + 4, y + 4, plate, plate, 4.5F,
				RenderUtils.withAlpha(accent, (0.22F + 0.12F * pulse) * alpha));
		graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, EMBLEM,
				x + 6, y + 6, 0.0F, 0.0F, plate - 4, plate - 4, 64, 64, 64, 64,
				RenderUtils.withAlpha(0xFFFFFFFF, 0.95F * alpha));

		int cursor = x + 4 + plate + 6;
		int textY = y + (height - font.lineHeight) / 2;
		UiKit.tracked(graphics, font, brand, cursor, textY, alpha * appear, 1);
		cursor += brandWidth + 4;
		RenderUtils.textFlat(graphics, font, version, cursor, textY,
				RenderUtils.withAlpha(0xFF8F93A6, (0.65F + 0.35F * pulse) * alpha * appear));
		cursor += versionWidth + 8;

		graphics.fill(cursor, y + 6, cursor + 1, y + height - 6, RenderUtils.withAlpha(0x3AFFFFFF, alpha));
		cursor += 8;
		for (int i = 0; i < facts.size(); i++) {
			int factColor = i == facts.size() - 1 ? ClientTheme.gradientAt(1.0F, now) : 0xFFCBD0E2;
			RenderUtils.textFlat(graphics, font, facts.get(i), cursor, textY,
					RenderUtils.withAlpha(factColor, (i == 0 ? 0.95F : 0.78F) * alpha * appear));
			cursor += RenderUtils.width(font, facts.get(i)) + 3;
			if (i < facts.size() - 1) {
				UiKit.dot(graphics, cursor, textY + font.lineHeight / 2, 0.5F * alpha * appear, now);
				cursor += 5;
			}
		}
	}

	// ------------------------------------------------------------------
	// Элемент: инфопанель (FPS · XYZ · направление · пинг)
	// ------------------------------------------------------------------

		private static void drawInfo(GuiGraphicsExtractor graphics, Minecraft client, float alpha) {
		LocalPlayer player = client.player;
		Font font = client.font;
		long now = Util.getMillis();

		int width = 132;
		int rows = player == null ? 1 : 3;
		int height = 20 + rows * (font.lineHeight + 4) + 5;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_INFO, MARGIN, MARGIN + font.lineHeight + 14);
		int x = position[0];
		int y = position[1];
		HudLayout.publishBounds(HudInfoModule.ELEMENT_INFO, x, y, width, height);

		UiKit.panel(graphics, x, y, width, height, 6.0F, alpha, 0.06F, now);
		UiKit.header(graphics, font, "Сводка", "hud_info", x + 8, y + 6,
				width - 16, alpha, now);
		int cursor = y + 20 + font.lineHeight - 2;
		cursor = UiKit.row(graphics, font, "FPS", String.valueOf(client.getFps()), x + 8, cursor,
				width - 16, alpha);
		if (player != null) {
			cursor = UiKit.row(graphics, font, "Пинг", getPing(client) + " мс",
					x + 8, cursor, width - 16, alpha);
			UiKit.row(graphics, font, "Сторона",
					directionName(player.getDirection()), x + 8, cursor, width - 16, alpha);
		}
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
				// Скрываем действительно пустые бинды. Проверка строки имени была
				// ненадёжной: Minecraft хранит пустую клавишу как key.keyboard.unknown.
				.filter(Module::hasBind)
				.sorted(Comparator.comparing(Module::getName))
				.toList();
		if (bound.isEmpty()) {
			return;
		}

		int rowHeight = font.lineHeight + 5;
		int width = 0;
		for (Module module : bound) {
			width = Math.max(width, RenderUtils.width(font, module.getBindLabel()) + 18
					+ RenderUtils.width(font, module.getName()));
		}
		String title = "БИНДЫ";
		width = Math.max(width + PADDING * 2, RenderUtils.width(font, title) + 42);
		int height = bound.size() * rowHeight + PADDING * 2 + font.lineHeight + 4;

		int screenW = client.getWindow().getGuiScaledWidth();
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_KEYBINDS, screenW - width - MARGIN, 92);
		int x = position[0];
		int y = position[1];

		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 7, 3);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 7,
				RenderUtils.withAlpha(PANEL_BORDER, 0.9f * alpha), RenderUtils.withAlpha(0xCC09090C, alpha));

		// Спокойная шапка вместо выпирающей плашки: акцент, заголовок и счётчик.
		int accent = ClientTheme.accent(now);
		RenderUtils.fillRounded(graphics, x + PADDING, y + PADDING + 1, 2, font.lineHeight - 1, 1,
				RenderUtils.withAlpha(accent, alpha));
		RenderUtils.textFlat(graphics, font, title, x + PADDING + 7, y + PADDING,
				RenderUtils.withAlpha(TEXT_SECONDARY, alpha));
		String count = String.valueOf(bound.size());
		RenderUtils.textFlat(graphics, font, count, x + width - PADDING - RenderUtils.width(font, count), y + PADDING,
				RenderUtils.withAlpha(TEXT_DIM, alpha));
		graphics.fill(x + PADDING, y + PADDING + font.lineHeight + 4, x + width - PADDING,
				y + PADDING + font.lineHeight + 5, RenderUtils.withAlpha(PANEL_BORDER, alpha));

		int rowY = y + PADDING + font.lineHeight + 8;
		for (Module module : bound) {
			boolean on = module.isEnabled();
			// Минимальная точка статуса и аккуратный бейдж клавиши справа.
			String key = module.getBindLabel();
			int keyW = RenderUtils.width(font, key) + 8;
			int keyX = x + width - PADDING - keyW;
			int stateColor = on ? ENABLED_GREEN : TEXT_DIM;
			RenderUtils.fillRounded(graphics, x + PADDING, rowY + 3, 4, 4, 2,
					RenderUtils.withAlpha(stateColor, alpha));
			RenderUtils.textFlat(graphics, font, module.getName(), x + PADDING + 10, rowY,
					RenderUtils.withAlpha(on ? TEXT_COLOR : TEXT_SECONDARY, alpha));
			RenderUtils.fillRoundedBorder(graphics, keyX, rowY - 2, keyW, font.lineHeight + 4, 3,
					RenderUtils.withAlpha(on ? accent : PANEL_BORDER, 0.75f * alpha),
					RenderUtils.withAlpha(0x5C000000, alpha));
			RenderUtils.textFlat(graphics, font, key, keyX + 4, rowY,
					RenderUtils.withAlpha(on ? TEXT_COLOR : TEXT_SECONDARY, alpha));
			rowY += rowHeight;
		}

		HudLayout.publishBounds(HudInfoModule.ELEMENT_KEYBINDS, x, y, width, height);
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
	// Элемент: Target HUD
	// ------------------------------------------------------------------

		private static String lastTargetName;
	private static String lastTargetWeapon;
	private static float lastTargetHealth = 14.0F;
	private static float lastTargetAbsorption;
	private static float lastTargetMax = 20.0F;
	private static int lastTargetArmor;
	private static double lastTargetDistance;
	private static int lastTargetId = Integer.MIN_VALUE;
	private static long targetSwapAt;

	/**
	 * Target HUD. Держим последние значения цели, когда цели уже нет: карточка
	 * плавно гаснет, а не исчезает в тот же кадр (резкое мигание — половина
	 * «убогости» таких элементов).
	 */
	private static void drawTarget(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		KillAuraModule aura = ModuleManager.find(KillAuraModule.class);
		Entity raw = aura == null ? null : aura.currentTarget();
		LivingEntity target = raw instanceof LivingEntity living && living.isAlive() ? living : null;
		float presence = UiKit.tween("target.presence", target == null ? 0.0F : 1.0F, 0.2F, now);
		if (presence < 0.02F && alpha >= 1.0F) {
			return;
		}
		if (target != null) {
			if (lastTargetId != target.getId()) {
				lastTargetId = target.getId();
				targetSwapAt = now;
			}
			lastTargetName = target.getName().getString();
			lastTargetHealth = Math.max(0.0F, target.getHealth());
			lastTargetAbsorption = Math.max(0.0F, target.getAbsorptionAmount());
			lastTargetMax = Math.max(1.0F, target.getMaxHealth());
			lastTargetArmor = target.getArmorValue();
			lastTargetDistance = client.player == null ? 0.0 : client.player.distanceTo(target);
			lastTargetWeapon = target.getMainHandItem().isEmpty()
					? null : target.getMainHandItem().getHoverName().getString();
		}
		String name = lastTargetName == null ? "Цель" : lastTargetName;
		float health = lastTargetHealth;
		float absorption = lastTargetAbsorption;
		float maximum = lastTargetMax;
		int armor = lastTargetArmor;
		double distance = lastTargetDistance;
		String weapon = lastTargetWeapon;

		Font font = client.font;
		int width = Math.min(196, client.getWindow().getGuiScaledWidth() - 12);
		int height = 62;
		int defaultX = (client.getWindow().getGuiScaledWidth() - width) / 2;
		int defaultY = client.getWindow().getGuiScaledHeight() - height - 40;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_TARGET, defaultX, defaultY);
		int x = position[0];
		int y = position[1];
		HudLayout.publishBounds(HudInfoModule.ELEMENT_TARGET, x, y, width, height);

		float element = Math.min(1.0F, alpha) * (0.4F + 0.6F * presence);
		int accent = ClientTheme.gradientAt(0.2F, now);
		float progress = Math.max(0.0F, Math.min(1.0F, (health + absorption) / maximum));
		float animated = UiKit.tween("target.hp", progress, 0.16F, now);
		int healthColor = animated < 0.25F ? 0xFFFF667D : animated < 0.55F ? 0xFFFFC66C : accent;

		UiKit.panel(graphics, x, y, width, height, 8.0F, element, 0.42F, now);

		int plate = 34;
		int plateX = x + 8;
		int plateY = y + (height - plate) / 2;
		RenderUtils.fillRounded(graphics, plateX, plateY, plate, plate, 7.0F,
				RenderUtils.mix(0xFF141419, healthColor, 0.22F));
		RenderUtils.fillRounded(graphics, plateX, plateY, plate, plate, 7.0F,
				RenderUtils.withAlpha(healthColor, 0.55F * element), RenderUtils.withAlpha(0x00000000, 0.0F));
		String initial = name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
		RenderUtils.textBold(graphics, font, initial, plateX + plate / 2 - RenderUtils.width(font, initial) / 2,
				plateY + 10, RenderUtils.withAlpha(0xFFF6F8FF, element));
		int ticks = 34;
		double cx = plateX + plate / 2.0;
		double cy = plateY + plate / 2.0;
		for (int i = 0; i < ticks; i++) {
			float f = i / (float) ticks;
			if (f > animated) {
				continue;
			}
			double angle = f * Math.PI * 2.0 - Math.PI / 2.0;
			int tx = (int) Math.round(cx + Math.cos(angle) * plate * 0.68);
			int ty = (int) Math.round(cy + Math.sin(angle) * plate * 0.68);
			graphics.fill(tx, ty, tx + 1, ty + 1,
					RenderUtils.withAlpha(healthColor, (0.4F + 0.6F * f) * element));
		}

		int textX = plateX + plate + 9;
		int right = x + width - 8;
		RenderUtils.textBold(graphics, font, RenderUtils.clamp(font, name, right - textX - 30), textX, y + 7,
				RenderUtils.withAlpha(0xFFF6F8FF, element));
		String side = String.format(java.util.Locale.ROOT, "%.1f бл.", distance);
		RenderUtils.textFlat(graphics, font, side, right - RenderUtils.width(font, side), y + 9,
				RenderUtils.withAlpha(0xFF9A9DAE, element));

		String hp = String.format(java.util.Locale.ROOT, "%.1f", health + absorption);
		RenderUtils.textBold(graphics, font, hp, textX, y + 21, RenderUtils.withAlpha(healthColor, element));
		String ofMax = String.format(java.util.Locale.ROOT, " / %.0f", maximum);
		int afterHp = textX + RenderUtils.width(font, hp) + 1;
		RenderUtils.textFlat(graphics, font, ofMax, afterHp, y + 21,
				RenderUtils.withAlpha(0xFF8F93A6, 0.9F * element));
		if (weapon != null && !weapon.isBlank()) {
			int gunWidth = RenderUtils.width(font, weapon);
			if (right - gunWidth > afterHp + RenderUtils.width(font, ofMax) + 6) {
				RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, weapon, right - afterHp - 6),
						right - gunWidth, y + 21, RenderUtils.withAlpha(0xFFB9BFD4, element));
			}
		}

		int barX = textX;
		int barW = right - barX;
		int barY = y + 34;
		UiKit.bar(graphics, barX, barY, barW, 5, animated, now, element);
		if (absorption > 0.0F) {
			int shieldX = barX + Math.round(barW * animated);
			int shieldW = Math.max(2, Math.round(barW * (progress - animated)));
			RenderUtils.fillRounded(graphics, shieldX, barY, Math.min(shieldW, right - shieldX), 5, 2.5F,
					RenderUtils.withAlpha(0xFFFFD98A, 0.85F * element));
		}

		int pips = 10;
		int pipY = y + 45;
		int pipWidth = Math.max(5, Math.min(9, (barW - (pips - 1) * 2) / pips));
		for (int i = 0; i < pips; i++) {
			int px = barX + i * (pipWidth + 2);
			boolean on = armor >= (i + 1) * 2 || (armor % 2 == 1 && armor / 2 == i);
			RenderUtils.fillRounded(graphics, px, pipY, pipWidth, 6, 2.0F,
					RenderUtils.withAlpha(on ? 0xFFC9D6FF : 0x2AFFFFFF, element));
			if (on) {
				graphics.fill(px + 1, pipY + 1, px + pipWidth - 1, pipY + 2,
						RenderUtils.withAlpha(0xFFFFFFFF, 0.45F * element));
			}
		}
		String ping = getPing(client) + " мс";
		RenderUtils.textFlat(graphics, font, ping, right - RenderUtils.width(font, ping), pipY,
				RenderUtils.withAlpha(0xFF8F93A6, element));

		float flash = 1.0F - Math.min(1.0F, (now - targetSwapAt) / 320.0F);
		if (flash > 0.01F) {
			RenderUtils.fillRounded(graphics, x - 1, y - 1, width + 2, height + 2, 9.0F,
					RenderUtils.withAlpha(accent, flash * 0.85F * presence));
		}
	}

	// ------------------------------------------------------------------
	// Элемент: активные эффекты
	// ------------------------------------------------------------------

	private static void drawEffects(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		LocalPlayer player = client.player;
		List<MobEffectInstance> effects = player == null ? List.of() : player.getActiveEffects().stream()
				.filter(MobEffectInstance::showIcon)
				.sorted(Comparator.comparingInt(MobEffectInstance::getDuration))
				.limit(6)
				.toList();
		if (effects.isEmpty() && alpha >= 1.0f) {
			return;
		}
		Font font = client.font;
		int rows = Math.max(1, effects.size());
		int rowH = font.lineHeight + 5;
		int width = 150;
		int height = 19 + rows * rowH + 4;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_EFFECTS,
				client.getWindow().getGuiScaledWidth() - width - MARGIN, 170);
		int x = position[0];
		int y = position[1];
		HudLayout.publishBounds(HudInfoModule.ELEMENT_EFFECTS, x, y, width, height);

		UiKit.panel(graphics, x, y, width, height, 6.0F, alpha, 0.1F, now);
		RenderUtils.textBold(graphics, font, "ЭФФЕКТЫ", x + 8, y + 6,
				RenderUtils.withAlpha(ClientTheme.accent(now), alpha));

		int rowY = y + 18;
		if (effects.isEmpty()) {
			RenderUtils.textFlat(graphics, font, "Нет активных эффектов", x + 8, rowY,
					RenderUtils.withAlpha(TEXT_DIM, alpha));
		} else {
			for (MobEffectInstance effect : effects) {
				int effectColor = 0xFF000000 | effect.getEffect().value().getColor();
				RenderUtils.fillCircle(graphics, x + 9, rowY + font.lineHeight / 2.0f, 3.0f,
						RenderUtils.withAlpha(effectColor, alpha));
				String amplifier = effect.getAmplifier() > 0 ? " " + roman(effect.getAmplifier() + 1) : "";
				String name = effect.getEffect().value().getDisplayName().getString() + amplifier;
				String duration = effect.isInfiniteDuration() ? "∞" : formatDurationTicks(effect.getDuration());
				int durationW = RenderUtils.width(font, duration);
				RenderUtils.drawClamped(graphics, font, name, x + 17, rowY, width - 29 - durationW,
						RenderUtils.withAlpha(TEXT_COLOR, alpha));
				RenderUtils.textFlat(graphics, font, duration, x + width - 8 - durationW, rowY,
						RenderUtils.withAlpha(effect.getDuration() < 200 ? 0xFFFF8095 : TEXT_SECONDARY, alpha));
				rowY += rowH;
			}
		}
	}

	// ------------------------------------------------------------------
	// Элемент: броня и оффхенд
	// ------------------------------------------------------------------

	private static void drawArmor(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		LocalPlayer player = client.player;
		Font font = client.font;
		int width = 122;
		int height = 34;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_ARMOR, MARGIN,
				client.getWindow().getGuiScaledHeight() - height - MARGIN);
		int x = position[0];
		int y = position[1];
		HudLayout.publishBounds(HudInfoModule.ELEMENT_ARMOR, x, y, width, height);

		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 6, 3);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 6,
				RenderUtils.withAlpha(PANEL_BORDER, alpha), RenderUtils.withAlpha(0xD908080B, alpha));
		EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
		for (int index = 0; index < slots.length + 1; index++) {
			int itemX = x + 5 + index * 22 + (index == 4 ? 4 : 0);
			RenderUtils.fillRounded(graphics, itemX, y + 5, 20, 24, 4, RenderUtils.withAlpha(0x661C1C24, alpha));
			ItemStack stack = player == null ? ItemStack.EMPTY
					: index < slots.length ? player.getItemBySlot(slots[index]) : player.getOffhandItem();
			if (!stack.isEmpty()) {
				graphics.item(player, stack, itemX + 2, y + 6, 0);
				graphics.itemDecorations(font, stack, itemX + 2, y + 6);
				if (stack.isDamageableItem()) {
					int percent = Math.max(0, Math.round((stack.getMaxDamage() - stack.getDamageValue())
							* 100.0f / Math.max(1, stack.getMaxDamage())));
					int color = percent < 20 ? 0xFFFF667D : percent < 50 ? 0xFFFFC66C : 0xFF7BE08A;
					graphics.fill(itemX + 3, y + 27, itemX + 3 + Math.max(1, Math.round(14 * percent / 100.0f)), y + 29,
							RenderUtils.withAlpha(color, alpha));
				}
			}
		}
		graphics.fill(x + 94, y + 8, x + 95, y + 26, RenderUtils.withAlpha(ClientTheme.accent(now), 0.45f * alpha));
	}

	// ------------------------------------------------------------------
	// Элемент: Keystrokes + CPS
	// ------------------------------------------------------------------

	private static void drawKeystrokes(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		boolean left = client.mouseHandler != null && client.mouseHandler.isLeftPressed();
		boolean right = client.mouseHandler != null && client.mouseHandler.isRightPressed();
		if (left && !leftWasDown) LEFT_CLICKS.addLast(now);
		if (right && !rightWasDown) RIGHT_CLICKS.addLast(now);
		leftWasDown = left;
		rightWasDown = right;
		while (!LEFT_CLICKS.isEmpty() && now - LEFT_CLICKS.peekFirst() > 1000) LEFT_CLICKS.removeFirst();
		while (!RIGHT_CLICKS.isEmpty() && now - RIGHT_CLICKS.peekFirst() > 1000) RIGHT_CLICKS.removeFirst();

		int key = 19;
		int gap = 2;
		int width = key * 3 + gap * 2;
		int height = key * 3 + gap * 2;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_KEYSTROKES, MARGIN,
				client.getWindow().getGuiScaledHeight() - height - 46);
		int x = position[0];
		int y = position[1];
		HudLayout.publishBounds(HudInfoModule.ELEMENT_KEYSTROKES, x, y, width, height);

		drawInputKey(graphics, client.font, "W", x + key + gap, y, key, key,
				client.options.keyUp.isDown(), alpha, now, 0.0f);
		drawInputKey(graphics, client.font, "A", x, y + key + gap, key, key,
				client.options.keyLeft.isDown(), alpha, now, 0.13f);
		drawInputKey(graphics, client.font, "S", x + key + gap, y + key + gap, key, key,
				client.options.keyDown.isDown(), alpha, now, 0.26f);
		drawInputKey(graphics, client.font, "D", x + (key + gap) * 2, y + key + gap, key, key,
				client.options.keyRight.isDown(), alpha, now, 0.39f);
		int mouseW = (width - gap) / 2;
		drawInputKey(graphics, client.font, "L " + LEFT_CLICKS.size(), x, y + (key + gap) * 2, mouseW, key,
				left, alpha, now, 0.52f);
		drawInputKey(graphics, client.font, "R " + RIGHT_CLICKS.size(), x + mouseW + gap,
				y + (key + gap) * 2, width - mouseW - gap, key, right, alpha, now, 0.65f);
	}

	private static void drawInputKey(GuiGraphicsExtractor graphics, Font font, String label,
			int x, int y, int width, int height, boolean down, float alpha, long now, float gradientOffset) {
		int accent = ClientTheme.gradientAt(gradientOffset, now);
		RenderUtils.drawSoftShadow(graphics, x, y, width, height, 4, down ? 3 : 1);
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 4,
				RenderUtils.withAlpha(down ? accent : PANEL_BORDER, (down ? 0.9f : 0.55f) * alpha),
				RenderUtils.withAlpha(down ? accent : 0xC909090D, (down ? 0.24f : 1.0f) * alpha));
		RenderUtils.textCentered(graphics, font, label, x + width / 2,
				y + (height - font.lineHeight) / 2, RenderUtils.withAlpha(TEXT_COLOR, alpha), false);
	}

	// ------------------------------------------------------------------
	// Элемент: скорость — число, короткий бар и линейный график
	// ------------------------------------------------------------------

	/**
	 * Горизонтальная скорость игрока в блоках/с + график за последние ~3.6 с.
	 *
	 * <p>Бар нормируется по авто-масштабу (сглаженный максимум окна), а не по
	 * константе: на лошадии и на элитрах шкала остаётся читаемой. Масштаб
	 * растёт мгновенно и падает медленно — иначе график «прыгал» бы на каждом
	 * ускорении.</p>
	 */
	private static void drawSpeed(GuiGraphicsExtractor graphics, Minecraft client, float alpha, long now) {
		LocalPlayer player = client.player;
		float bps = 0.0F;
		if (player != null) {
			Vec3 motion = player.getDeltaMovement();
			bps = (float) Math.sqrt(motion.x * motion.x + motion.z * motion.z) * 20.0F;
		}
		// Отсчёт раз в 50 мс: на 400 FPS история забивалась бы одним и тем же
		// значением, и график превратился бы в прямую.
		if (now - speedLastSample >= 50L) {
			speedLastSample = now;
			speedHistory[speedHead] = bps;
			speedHead = (speedHead + 1) % SPEED_SAMPLES;
			float peak = bps;
			for (float sample : speedHistory) {
				peak = Math.max(peak, sample);
			}
			float target = Math.max(3.0F, peak * 1.15F);
			speedScale = target > speedScale ? target : speedScale + (target - speedScale) * 0.06F;
		}

		Font font = client.font;
		int width = 132;
		int height = 62;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_SPEED, MARGIN, 172);
		int x = position[0];
		int y = position[1];
		HudLayout.publishBounds(HudInfoModule.ELEMENT_SPEED, x, y, width, height);

		int accent = ClientTheme.accent(now);
		UiKit.panel(graphics, x, y, width, height, 6.0F, alpha, 0.1F, now);

		UiKit.tracked(graphics, font, "СКОРОСТЬ", x + 8, y + 6, alpha, 1);
		// Число крупно, единица — рядом мелко
		String value = String.format(java.util.Locale.ROOT, "%.1f", bps);
		RenderUtils.textBold(graphics, font, value, x + 8, y + 16,
				RenderUtils.withAlpha(0xFFF6F8FF, alpha));
		RenderUtils.textFlat(graphics, font, "б/с", x + 10 + RenderUtils.width(font, value), y + 18,
				RenderUtils.withAlpha(TEXT_SECONDARY, alpha));

		// Короткий бар: доля от авто-масштаба
		int barX = x + 8;
		int barW = width - 16;
		int barY = y + 32;
		float fraction = Math.min(1.0F, Math.max(0.0F, bps / Math.max(0.001F, speedScale)));
		UiKit.bar(graphics, barX, barY, barW, 3, fraction, now, alpha);
		// График: ломаная по кольцевому буферу (старое слева, свежее справа)
		int graphX = x + 8;
		int graphW = width - 16;
		int graphTop = y + 40;
		int graphBottom = y + height - 5;
		int graphH = Math.max(6, graphBottom - graphTop);
		graphics.fill(graphX, graphBottom, graphX + graphW, graphBottom + 1,
				RenderUtils.withAlpha(0x22FFFFFF, alpha));
		int step = Math.max(1, graphW / SPEED_SAMPLES);
		int used = Math.min(SPEED_SAMPLES, Math.max(2, graphW / step));
		int prevY = -1;
		for (int i = 0; i < used; i++) {
			float sample = speedHistory[(speedHead - used + i + SPEED_SAMPLES * 2) % SPEED_SAMPLES];
			int columnX = graphX + i * step;
			int valueH = Math.round(Math.min(1.0F, Math.max(0.0F,
					sample / Math.max(0.001F, speedScale))) * (graphH - 1));
			int sampleY = graphBottom - Math.max(1, valueH);
			if (prevY >= 0) {
				// вертикальный отрезок между соседними точками = звено ломаной
				int top = Math.min(prevY, sampleY);
				int bottom = Math.max(prevY, sampleY) + 1;
				graphics.fill(columnX - step, top, columnX, bottom,
						RenderUtils.withAlpha(accent, 0.55F * alpha));
			}
			graphics.fill(columnX - step, sampleY, columnX, sampleY + 1,
					RenderUtils.withAlpha(accent, 0.95F * alpha));
			// лёгкая «подложка» под линией — график читается и на светлом фоне
			graphics.fill(columnX - step, sampleY + 1, columnX, graphBottom,
					RenderUtils.withAlpha(accent, 0.12F * alpha));
			prevY = sampleY;
		}
		// Последняя точка — яркая точка-маркер
		if (prevY >= 0) {
			graphics.fill(graphX + (used - 1) * step - 1, prevY - 1,
					graphX + (used - 1) * step + 2, prevY + 2, RenderUtils.withAlpha(0xFFFFFFFF, 0.9F * alpha));
		}
	}

	// ------------------------------------------------------------------
	// Элемент: координаты
	// ------------------------------------------------------------------

	private static void drawCoords(GuiGraphicsExtractor graphics, Minecraft client, float alpha) {
		long now = Util.getMillis();
		LocalPlayer player = client.player;
		Font font = client.font;
		int[] position = HudLayout.position(HudInfoModule.ELEMENT_COORDS, MARGIN, 232);
		int x = position[0];
		int y = position[1];
		int width = 140;
		int height = 52;
		HudLayout.publishBounds(HudInfoModule.ELEMENT_COORDS, x, y, width, height);

		UiKit.panel(graphics, x, y, width, height, 6.0F, alpha, 0.1F, now);
		UiKit.header(graphics, font, "Координаты", "auto_walk",
				x + 8, y + 6, width - 16, alpha, now);
		if (player == null) {
			RenderUtils.textFlat(graphics, font, "нет мира", x + 8, y + 24,
					RenderUtils.withAlpha(TEXT_SECONDARY, alpha));
			return;
		}
		Vec3 eyes = player.getEyePosition();
		BlockPos pos = player.blockPosition();
		RenderUtils.textFlat(graphics, font,
				String.format(java.util.Locale.ROOT, "%.1f  %.1f  %.1f", eyes.x, eyes.y, eyes.z),
				x + 8, y + 24, RenderUtils.withAlpha(0xFFF6F8FF, alpha));
		RenderUtils.textFlat(graphics, font,
				String.format(java.util.Locale.ROOT, "блок %d %d %d · чанк %d:%d · %s",
						pos.getX(), pos.getY(), pos.getZ(), pos.getX() >> 4, pos.getZ() >> 4,
						directionName(player.getDirection())),
				x + 8, y + 37, RenderUtils.withAlpha(TEXT_SECONDARY, alpha));
	}

	private static String formatDurationTicks(int ticks) {
		long seconds = Math.max(0, ticks / 20L);
		return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	private static String roman(int value) {
		return switch (value) {
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> Integer.toString(value);
		};
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
					width - 24 - RenderUtils.width(font, playing ? "▶" : "❚❚"));
			subtitle = formatTime(media.positionMillis()) + " / " + formatTime(Math.max(1L, media.durationMillis()));
		}

		RenderUtils.textFlat(graphics, font, playing ? "▶" : "❚❚", x + 8, y + 7,
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

package com.dreamcast.client.module.impl;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.gui.UiKit;
import com.dreamcast.client.gui.theme.ClientTheme;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Своё оформление для ЧУЖИХ меню.
 *
 * <p>Идея простая: заменять экраны целиком — значит ломать их логику (плавильня
 * теряет стрелку, житель — ползунок, книга — поля). Поэтому меню игры остаются
 * ванильными по поведению, а ВНЕШНОСТЬ мы рисуем сами: затемнённая вуаль с блюром
 * вместо серой муки, акцентная рамка и угловые скобки вокруг окна контейнера,
 * свои плитки слотов вместо спрайтов, свои заголовки с разрядкой. Ниже это
 * разложено по переключателям.</p>
 *
 * <p>Экраны, которые у клиента свои (меню, пауза, серверы, миры, настройки,
 * макросы, аккаунты), остаются как есть — мод их не трогает, чтобы не рисовать
 * стекло дважды. Проверка идёт по имени пакета: чужой мод со своим экраном
 * получит ванильный стиль, а не наше стекло поверх своего.</p>
 */
public class CustomGuiModule extends Module {

	private final BooleanSetting veil = bool("veil", "Вуаль за меню", true);
	private final IntSetting veilDarkness = intSetting("veil_darkness", "Затемнение, %", 62, 0, 92);
	private final BooleanSetting blur = bool("blur", "Размытие фона", true);
	private final BooleanSetting frame = bool("frame", "Рамка окна контейнера", true);
	private final BooleanSetting slotStyle = bool("slots", "Свои плитки слотов", true);
	private final BooleanSetting titles = bool("titles", "Свои заголовки", true);
	private final BooleanSetting swap = bool("swap", "Свои экраны вместо ванильных", true);

	public CustomGuiModule() {
		super("custom_gui", "Custom GUI", "Наше оформление для любых меню игры",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
		addSetting(veil);
		addSetting(veilDarkness);
		addSetting(blur);
		addSetting(frame);
		addSetting(slotStyle);
		addSetting(titles);
		addSetting(swap);
	}

	@Override
	protected boolean defaultEnabled() {
		return true;
	}

	/** Включено ли оформление чужих экранов вообще. */
	public static boolean styling() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled();
	}

	public static boolean wantsVeil() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled() && module.veil.isEnabled();
	}

	public static int veilDarkness() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module == null ? 62 : module.veilDarkness.get();
	}

	public static boolean wantsBlur() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled() && module.blur.isEnabled();
	}

	public static boolean wantsFrame() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled() && module.frame.isEnabled();
	}

	public static boolean wantsSlots() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled() && module.slotStyle.isEnabled();
	}

	public static boolean wantsTitles() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled() && module.titles.isEnabled();
	}

	public static boolean wantsSwap() {
		CustomGuiModule module = com.dreamcast.client.module.ModuleManager.find(CustomGuiModule.class);
		return module != null && module.isEnabled() && module.swap.isEnabled();
	}

	/** Наши экраны (пакет клиента) не переоформляем: они и так наши. */
	public static boolean isOwnScreen(Object screen) {
		return screen != null && screen.getClass().getName().startsWith("com.dreamcast.");
	}

	/**
	 * Вуаль: ровный тёмный слой + виньетка по краям. Блюр — ровно один вызов на
	 * кадр, поэтому он обёрнут в попытку и не повторяется для второго экрана.
	 */
	public static void drawVeil(GuiGraphicsExtractor graphics, int width, int height) {
		if (!wantsVeil()) {
			return;
		}
		long now = Util.getMillis();
		int darkness = veilDarkness();
		float alpha = darkness / 100.0F;
		if (wantsBlur()) {
			try {
				graphics.blurBeforeThisStratum();
			} catch (Throwable ignored) {
				// игра не дала размыть — просто живём без блюра
			}
		}
		graphics.fillGradient(0, 0, width, height,
				RenderUtils.withAlpha(0xFF0A0A0E, 0.55F * alpha),
				RenderUtils.withAlpha(0xFF05050A, 0.72F * alpha));
		// виньетка: четыре полосы, чтобы взгляд собирался в центре
		int band = Math.max(8, Math.min(width, height) / 8);
		for (int i = 0; i < 5; i++) {
			float t = i / 5.0F;
			int a = (int) (0.16F * alpha * (1.0F - t));
			graphics.fill(0, i * band / 5, width, (i + 1) * band / 5, RenderUtils.withAlpha(0xFF000000, a));
			graphics.fill(0, height - (i + 1) * band / 5, width, height - i * band / 5,
					RenderUtils.withAlpha(0xFF000000, a));
			graphics.fill(i * band / 5, 0, (i + 1) * band / 5, height, RenderUtils.withAlpha(0xFF000000, a / 2));
			graphics.fill(width - (i + 1) * band / 5, 0, width - i * band / 5, height,
					RenderUtils.withAlpha(0xFF000000, a / 2));
		}
		// лёгкий акцентный «пол» под меню — то, чего не хватает ваниле
		int accent = ClientTheme.accent(now);
		graphics.fill(0, height - 1, width, height, RenderUtils.withAlpha(accent, 0.35F * alpha));
	}

	/** Акцентная рамка вокруг окна контейнера: волосяная линия + угловые скобки. */
	public static void drawFrame(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		if (!wantsFrame()) {
			return;
		}
		long now = Util.getMillis();
		int accent = ClientTheme.accent(now);
		int left = x - 5;
		int top = y - 5;
		int w = width + 10;
		int h = height + 10;
		RenderUtils.fillRounded(graphics, left, top, w, h, 6.0,
				RenderUtils.withAlpha(accent, 0.16F));
		RenderUtils.fillRounded(graphics, left + 1, top + 1, w - 2, h - 2, 5.0,
				RenderUtils.withAlpha(0x00000000, 0.0F));
		// угловые скобки — «уголки визора»
		int arm = Math.max(7, Math.min(16, Math.min(w, h) / 6));
		for (int i = 0; i < arm; i++) {
			int a = (int) (0.9F - 0.5F * (i / (float) arm));
			int color = RenderUtils.withAlpha(ClientTheme.gradientAt(i / (float) arm, now), a);
			graphics.fill(left + i, top, left + i + 1, top + 1, color);
			graphics.fill(left, top + i, left + 1, top + i + 1, color);
			graphics.fill(left + w - 1 - i, top, left + w - i, top + 1, color);
			graphics.fill(left + w - 1, top + i, left + w, top + i + 1, color);
			graphics.fill(left + i, top + h - 1, left + i + 1, top + h, color);
			graphics.fill(left, top + h - 1 - i, left + 1, top + h - i, color);
			graphics.fill(left + w - 1 - i, top + h - 1, left + w - i, top + h, color);
			graphics.fill(left + w - 1, top + h - 1 - i, left + w, top + h - i, color);
		}
	}

	/** Плитка слота: 18×18 со скруглением, внутренним затенением и бликом сверху. */
	public static void drawSlot(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
		long now = Util.getMillis();
		int accent = ClientTheme.accent(now);
		RenderUtils.fillRounded(graphics, x - 1, y - 1, 20, 20, 3.5,
				hovered ? RenderUtils.withAlpha(accent, 0.55F) : 0x2FFFFFFF);
		RenderUtils.fillRounded(graphics, x, y, 18, 18, 3.0,
				hovered ? RenderUtils.mix(0xFF1A1A22, accent, 0.3F) : 0xB00D0D12);
		graphics.fill(x + 3, y + 1, x + 15, y + 2, 0x1AFFFFFF);
		graphics.fill(x + 2, y + 17, x + 16, y + 18, 0x0FFFFFFF);
		if (hovered) {
			graphics.fill(x + 1, y - 2, x + 17, y - 1, RenderUtils.withAlpha(accent, 0.7F));
		}
	}

	/** Заголовок окна: имя контейнера с разрядкой над рамкой, справа — подпись клиента. */
	public static void drawTitle(GuiGraphicsExtractor graphics, String title, int x, int y, int width) {
		Minecraft client = Minecraft.getInstance();
		Font font = client == null ? null : client.font;
		if (font == null || title == null) {
			return;
		}
		long now = Util.getMillis();
		UiKit.tracked(graphics, font, title.toUpperCase(java.util.Locale.ROOT), x, y - 17, 1.0F, 1);
		String brand = DreamcastClient.MOD_NAME;
		RenderUtils.textFlat(graphics, font, brand, x + width - RenderUtils.width(font, brand), y - 16,
				RenderUtils.withAlpha(ClientTheme.gradientAt(0.6F, now), 0.75F));
	}

	/** Человекочитаемое имя экрана: {@code ChestScreen} → {@code Chest}. */
	public static String readableName(Object screen) {
		String name = screen.getClass().getSimpleName();
		if (name.endsWith("Screen")) {
			name = name.substring(0, name.length() - "Screen".length());
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (i > 0 && Character.isUpperCase(c)) {
				out.append(' ');
			}
			out.append(c);
		}
		return out.toString();
	}
}

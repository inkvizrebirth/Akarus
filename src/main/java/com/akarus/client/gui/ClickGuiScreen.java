package com.akarus.client.gui;

import com.akarus.client.AkarusClient;
import com.akarus.client.config.ConfigManager;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.ButtonSetting;
import com.akarus.client.settings.ColorSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.ModeSetting;
import com.akarus.client.settings.Setting;
import com.akarus.client.settings.StringSetting;
import com.akarus.client.util.RenderUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickGUI — собственное меню клиента Akarus.
 *
 * Всё рисуется вручную через {@link GuiGraphicsExtractor}: тёмная панель с мягкой тенью,
 * размытый фон, «волна» в месте клика и плавные анимации.
 * Поддерживаются переключатели, слайдеры (числа) и текстовые поля.
 */
public class ClickGuiScreen extends Screen {

	// --- Геометрия ---
	private static final int GUI_WIDTH = 480;
	/** Максимальная высота окна: дальше список уходит в прокрутку. */
	private static final int GUI_MAX_HEIGHT = 300;
	/** Минимальная высота — в ней целиком видно столбик категорий. */
	private static final int GUI_MIN_HEIGHT = 208;
	private static final int HEADER_HEIGHT = 34;
	private static final int CATEGORY_WIDTH = 124;
	private static final int CATEGORY_ROW_HEIGHT = 22;
	private static final int CATEGORY_GAP = 4;
	private static final int MODULE_ROW_HEIGHT = 34;
	private static final int TOGGLE_ROW_HEIGHT = 16;
	private static final int SLIDER_ROW_HEIGHT = 26;
	private static final int TEXT_ROW_HEIGHT = 20;
	private static final int PADDING = 7;
	private static final int FOOTER_HEIGHT = 14;
	private static final int PANEL_RADIUS = 10;

	private static final int TOGGLE_WIDTH = 30;
	private static final int TOGGLE_HEIGHT = 12;
	private static final int SETTING_TOGGLE_WIDTH = 24;
	private static final int SETTING_TOGGLE_HEIGHT = 10;

	private static final int RIPPLE_DURATION = 520;
	private static final int SHADOW_LAYERS = 5;

	// --- Цвета: всё в чёрных тонах, акцент берётся из категории ---
	private static final int BACKGROUND_DIM = 0xA6000000;
	private static final int PANEL_OUTLINE = 0xFF1C1C20;
	private static final int PANEL_TOP = 0xF6151518;
	private static final int PANEL_BOTTOM = 0xF809090B;
	private static final int LIST_BACKGROUND = 0x59000000;
	private static final int ROW_BACKGROUND = 0xB8101013;
	private static final int ROW_BORDER = 0x12FFFFFF;
	private static final int SHEEN = 0x0CFFFFFF;
	private static final int TEXT_PRIMARY = 0xFFF6F6F8;
	private static final int TEXT_SECONDARY = 0xFFA6A6B2;
	private static final int TEXT_DIM = 0xFF6B6B78;

	/** Размытие можно вызывать только один раз за кадр — если игра не даёт, отключаем его. */
	private static boolean blurSupported = true;

	// --- Состояние ---
	private ModuleCategory selected = ModuleCategory.HUD;
	private Module expanded = null;

	private float guiX;
	private float guiY;
	private boolean positioned;
	private boolean dragging;
	private double dragOffsetX;
	private double dragOffsetY;

	private float scroll;
	private float scrollTarget;
	/** Текущая (сглаженная) высота окна — см. {@link #panelHeight()}. */
	private float panelHeightAnim = GUI_MIN_HEIGHT;

	private Setting<?> focusedSetting;
	/** То, что сейчас напечатали в поле цвета (без «#»), пока поле в фокусе. */
	private String colorDraft = "";
	private Module focusedModule;
	/** Модуль, который сейчас ждёт новой клавиши для бинда. */
	private Module bindingModule;
	private boolean focusedDirty;
	private IntSetting draggingSetting;
	private Module draggingModule;

	private final Map<String, Float> hoverAnimations = new HashMap<>();
	private final Map<String, Float> toggleAnimations = new HashMap<>();
	private final List<Ripple> ripples = new ArrayList<>();
	private float openAnimation;

	private long lastFrameMillis;
	private float step;

	public ClickGuiScreen() {
		super(Component.literal(AkarusClient.MOD_NAME + " " + AkarusClient.MOD_VERSION));
	}

	@Override
	protected void init() {
		super.init();

		// При первом открытии ставим окно в центр экрана
		if (!positioned) {
			panelHeightAnim = targetPanelHeight();
			guiX = (this.width - GUI_WIDTH) / 2.0f;
			guiY = (this.height - panelHeight()) / 2.0f;
			positioned = true;
		}
		clampPanel();
	}

	/**
	 * Высота окна подгоняется под содержимое: на двух-трёх модулях панель на 300 пикселей
	 * выглядит пустой рамкой, а при раскрытых настройках список упирался бы в низ.
	 * Значение сглаживается, поэтому раскрытие модуля не «дёргает» окно.
	 */
	private int panelHeight() {
		return Math.round(panelHeightAnim);
	}

	private int targetPanelHeight() {
		int needed = HEADER_HEIGHT + PADDING * 2 + FOOTER_HEIGHT + contentHeight() + 4;
		return Math.max(GUI_MIN_HEIGHT, Math.min(GUI_MAX_HEIGHT, needed));
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		clampPanel();
	}

	// ------------------------------------------------------------------
	// Фон: затемнение + размытие
	// ------------------------------------------------------------------

	/**
	 * Переопределяем стандартный фон экрана.
	 *
	 * Важно: игру нельзя просить размыть кадр дважды — {@code blurBeforeThisStratum()}
	 * бросает IllegalStateException при повторном вызове. Поэтому затемняем и размываем
	 * мир именно здесь, а не в extractRenderState.
	 */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, RenderUtils.withAlpha(BACKGROUND_DIM, 0.35f + 0.65f * openAnimation));

		if (blurSupported) {
			try {
				graphics.blurBeforeThisStratum();
			} catch (IllegalStateException exception) {
				blurSupported = false;
				AkarusClient.LOGGER.warn("Размытие недоступно в этом кадре — отключаем блюр", exception);
			}
		}

		// Субтитры рисует ванильный HUD, не забываем про них
		this.minecraft.gui.hud.extractDeferredSubtitles();
	}

	// ------------------------------------------------------------------
	// Отрисовка
	// ------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		updateAnimations();

		int x = Math.round(guiX);
		int y = Math.round(guiY);
		int accent = selected.getAccent();

		// Никакого «выезжания» с масштабированием: матрица кадра влияет только на
		// отрисовку, а проверка попаданий мыши работает целыми числами — на первые
		// полсекунды курсор «не совпадал» бы с кнопками. Поэтому окно просто
		// проявляется вместе с затемнением фона (см. extractBackground).
		int panelHeight = panelHeight();
		RenderUtils.drawSoftShadow(graphics, x, y, GUI_WIDTH, panelHeight, PANEL_RADIUS, SHADOW_LAYERS);
		drawPanel(graphics, x, y, accent);
		drawCategories(graphics, x, y, mouseX, mouseY);
		drawModules(graphics, x, y, mouseX, mouseY, accent);
		drawHint(graphics, x, y);

		// Плашка «нажми кнопку» рисуется поверх всего
		if (bindingModule != null) {
			drawBindingOverlay(graphics, x, y, accent);
		}

		// Волна по клику в любом месте панели
		drawRipples(graphics, new Hitbox(x, y, GUI_WIDTH, panelHeight), accent);
	}

	private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int accent) {
		// Рамка и двухтоновый фон: сверху чуть светлее, снизу — почти чёрный
		int height = panelHeight();
		RenderUtils.fillRounded(graphics, x, y, GUI_WIDTH, height, PANEL_RADIUS, PANEL_OUTLINE);
		RenderUtils.fillRounded(graphics, x + 1, y + 1, GUI_WIDTH - 2, height - 2, PANEL_RADIUS - 1, PANEL_TOP);
		RenderUtils.fillRoundedBottom(graphics, x + 1, y + (height - 2) / 2, GUI_WIDTH - 2, (height - 2) / 2,
				PANEL_RADIUS - 1, PANEL_BOTTOM);

		// Тонкий блик по верхней кромке — эффект стекла
		graphics.fill(x + PANEL_RADIUS, y + 1, x + GUI_WIDTH - PANEL_RADIUS, y + 2, SHEEN);

		// Шапка: подложка с оттенком акцента + акцентная линия снизу
		int headerColor = RenderUtils.mix(PANEL_TOP, accent, 0.16f);
		RenderUtils.fillRoundedTop(graphics, x + 1, y + 1, GUI_WIDTH - 2, HEADER_HEIGHT - 1, PANEL_RADIUS - 1, headerColor);
		graphics.fillGradient(x + 1, y + HEADER_HEIGHT - 2, x + GUI_WIDTH - 1, y + HEADER_HEIGHT,
				RenderUtils.withAlpha(accent, 0.10f), RenderUtils.withAlpha(accent, 0.85f));

		Font font = this.font;
		int titleY = y + (HEADER_HEIGHT - font.lineHeight) / 2;

		graphics.text(font, AkarusClient.MOD_NAME, x + PADDING, titleY, TEXT_PRIMARY, true);
		graphics.text(font, "v" + AkarusClient.MOD_VERSION, x + PADDING + font.width(AkarusClient.MOD_NAME) + 5, titleY + 1, TEXT_DIM, false);

		String closeHint = "ESC — закрыть";
		graphics.text(font, closeHint, x + GUI_WIDTH - PADDING - font.width(closeHint), titleY, TEXT_DIM, false);
	}

	private void drawCategories(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
		Font font = this.font;
		int rowX = x + PADDING;
		int rowY = y + HEADER_HEIGHT + PADDING;

		for (ModuleCategory category : ModuleCategory.values()) {
			Hitbox box = new Hitbox(rowX, rowY, CATEGORY_WIDTH, CATEGORY_ROW_HEIGHT);
			boolean isSelected = category == selected;
			float hover = hoverProgress("category:" + category.name(), box.contains(mouseX, mouseY));
			int accent = category.getAccent();

			// Фон: чёрная «пилюля», у выбранной категории — с акцентной подсветкой
			int background = RenderUtils.mix(RenderUtils.withAlpha(0xFF000000, 0.55f), accent,
					isSelected ? 0.26f + 0.10f * hover : 0.06f * hover);
			RenderUtils.fillRounded(graphics, box.x(), box.y(), box.width(), box.height(), 6, background);

			if (isSelected) {
				// акцентная полоска слева
				graphics.fill(box.x(), box.y() + 5, box.x() + 2, box.y() + box.height() - 5, accent);
			}

			int textColor = isSelected ? TEXT_PRIMARY : RenderUtils.mix(TEXT_SECONDARY, TEXT_PRIMARY, hover);
			graphics.text(font, RenderUtils.clamp(font, category.getDisplayName(), box.width() - 34), box.x() + 11,
					box.y() + (box.height() - font.lineHeight) / 2 + 1, textColor, false);

			// Счётчик: «всего модулей», а если есть включённые — «сколько включено»
			int total = 0;
			int active = 0;
			for (Module module : ModuleManager.getByCategory(category)) {
				total++;
				if (module.isEnabled()) {
					active++;
				}
			}
			String badge = active == 0 ? String.valueOf(total) : active + "/" + total;
			int badgeWidth = font.width(badge);
			graphics.text(font, badge, box.x() + box.width() - 9 - badgeWidth,
					box.y() + (box.height() - font.lineHeight) / 2 + 1,
					active == 0 ? TEXT_DIM : RenderUtils.withAlpha(category.getAccent(), 0.95f), false);

			drawRipples(graphics, box, accent);

			rowY += CATEGORY_ROW_HEIGHT + CATEGORY_GAP;
		}
	}

	private void drawModules(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, int accent) {
		int listX = x + PADDING + CATEGORY_WIDTH + PADDING;
		int listY = y + HEADER_HEIGHT + PADDING;
		int listWidth = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3;
		int listHeight = panelHeight() - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;

		RenderUtils.fillRounded(graphics, listX, listY, listWidth, listHeight, 6, LIST_BACKGROUND);

		// Всё, что выходит за пределы списка, обрезается
		graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight);

		for (LayoutEntry entry : buildLayout()) {
			if (entry.kind() == Kind.CATEGORY) {
				continue;
			}
			if (entry.kind() == Kind.MODULE) {
				drawModuleRow(graphics, entry.module(), entry.box(), accent, mouseX, mouseY);
			} else {
				drawSettingRow(graphics, entry.setting(), entry.module(), entry.box(), accent, mouseX, mouseY);
			}
		}

		graphics.disableScissor();

		// Полоса прокрутки
		int content = contentHeight();
		if (content > listHeight) {
			int trackX = listX + listWidth - 5;
			RenderUtils.fillRounded(graphics, trackX, listY + 3, 3, listHeight - 6, 1, 0x1AFFFFFF);

			int barHeight = Math.max(18, (int) (listHeight * (listHeight / (float) content)));
			float progress = -scroll / (float) (content - listHeight);
			int barY = listY + (int) ((listHeight - barHeight) * progress);
			RenderUtils.fillRounded(graphics, trackX, barY, 3, barHeight, 1, RenderUtils.withAlpha(accent, 0.9f));
		}
	}

	/**
	 * Область стрелки «раскрыть настройки» в строке модуля. Один и тот же прямоугольник
	 * используется для подписи и для проверки клика — иначе «жмёшь на стрелку», а срабатывает
	 * тумблер.
	 */
	private static Hitbox expandMark(Hitbox box) {
		return new Hitbox(box.x() + box.width() - TOGGLE_WIDTH - 24, box.y() + 4, 14, 14);
	}

	private void drawModuleRow(GuiGraphicsExtractor graphics, Module module, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress("module:" + module.getId(), box.contains(mouseX, mouseY));
		float toggle = toggleProgress(module);

		// Карточка модуля: тёмная подложка, акцентная рамка, подсветка при наведении
		int background = RenderUtils.mix(ROW_BACKGROUND, RenderUtils.withAlpha(accent, 0.45f), toggle * 0.45f);
		int border = RenderUtils.mix(ROW_BORDER, accent, toggle * 0.55f);
		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height(), 6, border, background);

		if (hover > 0.01f) {
			RenderUtils.fillRounded(graphics, box.x() + 1, box.y() + 1, box.width() - 2, box.height() - 2, 5,
					RenderUtils.withAlpha(0xFFFFFFFF, 0.06f * hover));
		}

		// Волна от клика — рисуется под текстом
		drawRipples(graphics, box, accent);

		boolean binding = bindingModule == module;
		String bindLabel = binding ? "..." : module.getBindLabel();

		// Акцентная полоса у включённого модуля — «работает» видно издалека
		if (toggle > 0.02f) {
			RenderUtils.fillRounded(graphics, box.x() + 2, box.y() + 6, 2, box.height() - 12, 1,
					RenderUtils.withAlpha(accent, 0.9f * toggle));
		}

		int textX = box.x() + 9;
		// Имя + бинд должны влезть до тумблера и кнопки раскрытия
		int rightLimit = box.x() + box.width() - TOGGLE_WIDTH - 34;
		String name = module.getName();
		int nameLimit = rightLimit - textX - font.width(bindLabel) - 12;
		if (nameLimit < font.width("WW")) {
			name = "";
			nameLimit = rightLimit - textX;
		}
		name = RenderUtils.clamp(font, name, nameLimit);
		graphics.text(font, name, textX, box.y() + 6,
				module.isEnabled() ? TEXT_PRIMARY : TEXT_SECONDARY, false);
		String bind = RenderUtils.clamp(font, bindLabel, rightLimit - textX - font.width(name) - 8);
		graphics.text(font, bind, textX + font.width(name) + 6, box.y() + 7,
				binding ? accent : TEXT_DIM, false);
		graphics.text(font, RenderUtils.clamp(font, module.getDescription(), box.width() - 72), textX, box.y() + 20,
				TEXT_DIM, false);

		// Тумблер справа
		RenderUtils.drawToggle(graphics, box.x() + box.width() - TOGGLE_WIDTH - 9,
				box.y() + (box.height() - TOGGLE_HEIGHT) / 2, TOGGLE_WIDTH, TOGGLE_HEIGHT, toggle, accent);

		// Отметка, что у модуля есть настройки: ▾/▸ вместо плюса — она читается как «раскрыть».
		// Попадание по ней считается тем же expandBox(), что и клик, поэтому «стрелка»
		// всегда там, где она и работает.
		if (!module.getSettings().isEmpty()) {
			Hitbox markBox = expandMark(box);
			String mark = module == expanded ? "▾" : "▸";
			float markHover = hoverProgress("expand:" + module.getId(), markBox.contains(mouseX, mouseY));
			graphics.text(font, mark, markBox.x() + 2, markBox.y() + 2,
					RenderUtils.mix(TEXT_DIM, TEXT_SECONDARY, markHover), false);
		}
	}

	// ------------------------------------------------------------------
	// Настройки: переключатель / слайдер / текстовое поле
	// ------------------------------------------------------------------

	/**
	 * Строка одной настройки.
	 *
	 * Модуль здесь нужен не для логики, а для ключа анимаций: у разных модулей
	 * настройки могут называться одинаково («Плотность», «Градиент»), и без id модуля
	 * они делили бы между собой состояние наведения — подсветка «перетекала» бы
	 * между строками разных модулей.
	 */
	private void drawSettingRow(GuiGraphicsExtractor graphics, Setting<?> setting, Module owner, Hitbox box,
			int accent, int mouseX, int mouseY) {

		String key = (owner == null ? "" : owner.getId() + ":") + setting.getId();

		if (setting instanceof IntSetting intSetting) {
			drawSliderRow(graphics, intSetting, key, box, accent, mouseX, mouseY);
		} else if (setting instanceof StringSetting stringSetting) {
			drawTextRow(graphics, stringSetting, key, box, accent, mouseX, mouseY);
		} else if (setting instanceof ColorSetting colorSetting) {
			drawColorRow(graphics, colorSetting, key, box, accent, mouseX, mouseY);
		} else if (setting instanceof ButtonSetting buttonSetting) {
			drawButtonRow(graphics, buttonSetting, key, box, accent, mouseX, mouseY);
		} else if (setting instanceof ModeSetting modeSetting) {
			drawModeRow(graphics, modeSetting, key, box, accent, mouseX, mouseY);
		} else if (setting instanceof BooleanSetting booleanSetting) {
			drawToggleRow(graphics, booleanSetting, key, box, accent, mouseX, mouseY);
		}
	}

	/**
	 * Строка выбора варианта.
	 *
	 * Если все варианты влезают в строку — рисуем сегменты (как переключатель
	 * режима в ванильных настройках), и клик по сегменту выбирает именно его.
	 * Если не влезают — экономный режим: подпись и стрелки, левая половина строки
	 * листает назад, правая — вперёд. Геометрию в обоих случаях считает
	 * {@link #modeSegments}, поэтому картинка и попадание клика не разъезжаются.
	 */
	private void drawModeRow(GuiGraphicsExtractor graphics, ModeSetting setting, String key, Hitbox box,
			int accent, int mouseX, int mouseY) {

		Font font = this.font;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));
		int textY = box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1;

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				RenderUtils.mix(0x10FFFFFF, accent, hover * 0.35f),
				RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		List<Hitbox> segments = modeSegments(font, setting, box);
		if (segments.isEmpty()) {
			// Компактный режим: «название … ‹значение›»
			int right = box.x() + box.width() - 9;
			int arrowWidth = font.width("‹") + 5;
			String value = setting.getLabel();
			int valueWidth = font.width(value);
			int valueX = right - arrowWidth - valueWidth;

			graphics.text(font, RenderUtils.clamp(font, setting.getName(), valueX - box.x() - 18),
					box.x() + 9, textY, TEXT_SECONDARY, false);
			graphics.text(font, "‹", valueX - arrowWidth, textY,
					RenderUtils.mix(TEXT_DIM, TEXT_PRIMARY, hover), false);
			graphics.text(font, value, valueX, textY, TEXT_PRIMARY, false);
			graphics.text(font, "›", valueX + valueWidth + 4, textY,
					RenderUtils.mix(TEXT_DIM, TEXT_PRIMARY, hover), false);
		} else {
			int labelLimit = segments.get(0).x() - box.x() - 18;
			graphics.text(font, RenderUtils.clamp(font, setting.getName(), labelLimit),
					box.x() + 9, textY, TEXT_SECONDARY, false);

			int current = setting.indexOfCurrent();
			for (int i = 0; i < segments.size(); i++) {
				Hitbox segment = segments.get(i);
				boolean selected = i == current;
				boolean hovered = segment.contains(mouseX, mouseY);

				int background = selected
						? RenderUtils.mix(accent, 0xFFFFFFFF, 0.10f * hover)
						: RenderUtils.mix(0x33000000, accent, hovered ? 0.22f : 0.0f);
				RenderUtils.fillRounded(graphics, segment.x(), segment.y(), segment.width(), segment.height(),
						3, background);

				String label = setting.labels().get(i);
				graphics.text(font, RenderUtils.clamp(font, label, segment.width() - 8),
						segment.x() + (segment.width() - font.width(label)) / 2,
						segment.y() + (segment.height() - font.lineHeight) / 2 + 1,
						selected ? 0xFF0D0D10 : RenderUtils.mix(TEXT_SECONDARY, TEXT_PRIMARY, hover), false);
			}
		}

		drawRipples(graphics, box, accent);
	}

	/**
	 * Геометрия сегментов строки выбора: пустой список, если все варианты в строку
	 * не влезают (тогда рисуется компактный переключатель со стрелками).
	 */
	private static List<Hitbox> modeSegments(Font font, ModeSetting setting, Hitbox box) {
		List<String> labels = setting.labels();
		if (labels.isEmpty()) {
			return List.of();
		}

		int gap = 3;
		int sidePadding = 6;
		int total = gap * (labels.size() - 1);
		List<Integer> widths = new ArrayList<>(labels.size());
		for (String label : labels) {
			int width = font.width(label) + sidePadding * 2;
			widths.add(width);
			total += width;
		}

		// Нужно ещё оставить место подпись настройки — иначе текст упрётся в сегменты
		int available = box.width() - 18 - font.width(setting.getName()) - 10;
		if (total > available) {
			return List.of();
		}

		int height = Math.max(9, box.height() - 8);
		List<Hitbox> segments = new ArrayList<>(labels.size());
		int x = box.x() + box.width() - 9 - total;
		for (int width : widths) {
			segments.add(new Hitbox(x, box.y() + 3, width, height));
			x += width + gap;
		}
		return segments;
	}

	/**
	 * Строка цвета: подпись, плашка с текущим цветом и HEX-код.
	 * По клику поле попадает в фокус и код можно печатать прямо в меню.
	 */
	private void drawColorRow(GuiGraphicsExtractor graphics, ColorSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		boolean focused = setting == focusedSetting;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY) || focused);

		int border = focused ? accent : RenderUtils.mix(0x10FFFFFF, accent, hover * 0.35f);
		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4, border,
				RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		int textY = box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1;

		// Плашка цвета: чёрная подложка, сверху — сам цвет (так виден даже прозрачный)
		int swatchWidth = 26;
		int swatchHeight = Math.max(6, box.height() - 12);
		String shown = focused ? colorDraft : "#" + setting.getHex();
		int codeWidth = Math.max(font.width(shown), font.width("#AARRGGBB"));
		int swatchX = box.x() + box.width() - 9 - codeWidth - 6 - swatchWidth;
		int swatchY = box.y() + (box.height() - 2 - swatchHeight) / 2;
		graphics.text(font, RenderUtils.clamp(font, setting.getName(), swatchX - box.x() - 18), box.x() + 9,
				textY, TEXT_SECONDARY, false);

		RenderUtils.fillRounded(graphics, swatchX, swatchY, swatchWidth, swatchHeight, 3, 0xFF000000);
		RenderUtils.fillRounded(graphics, swatchX + 1, swatchY + 1, swatchWidth - 2, swatchHeight - 2, 2, setting.get());

		graphics.text(font, shown, swatchX + swatchWidth + 6, textY, focused ? TEXT_PRIMARY : TEXT_DIM, false);

		// Мигающий курсор за кодом цвета
		if (focused && (Util.getMillis() / 500L) % 2 == 0) {
			int cursorX = swatchX + swatchWidth + 6 + font.width(shown) + 1;
			graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight - 1, accent);
		}

		drawRipples(graphics, box, accent);
	}

	/** Строка-кнопка: действие, которое открывается прямо из списка настроек. */
	private void drawButtonRow(GuiGraphicsExtractor graphics, ButtonSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				0x10FFFFFF, RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		int textY = box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1;

		int width = Math.max(54, Math.min(78, font.width(setting.getLabel()) + 16));
		int height = Math.max(10, box.height() - 10);
		int buttonX = box.x() + box.width() - 8 - width;
		int buttonY = box.y() + (box.height() - 2 - height) / 2;

		graphics.text(font, RenderUtils.clamp(font, setting.getName(), buttonX - box.x() - 18), box.x() + 9,
				textY, TEXT_DIM, false);

		RenderUtils.fillRounded(graphics, buttonX, buttonY, width, height, 4,
				RenderUtils.mix(0xFF26262E, accent, 0.18f + 0.42f * hover));
		String label = RenderUtils.clamp(font, setting.getLabel(), width - 8);
		graphics.text(font, label, buttonX + (width - font.width(label)) / 2,
				buttonY + (height - font.lineHeight) / 2 + 1,
				RenderUtils.mix(TEXT_SECONDARY, TEXT_PRIMARY, hover), false);

		drawRipples(graphics, box, accent);
	}

	private void drawToggleRow(GuiGraphicsExtractor graphics, BooleanSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));
		float toggle = toggleProgress(key, setting);

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				0x10FFFFFF, RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		drawRipples(graphics, box, accent);

		graphics.text(font, RenderUtils.clamp(font, setting.getName(), box.width() - SETTING_TOGGLE_WIDTH - 26),
				box.x() + 9, box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1,
				setting.isEnabled() ? TEXT_PRIMARY : TEXT_DIM, false);

		RenderUtils.drawToggle(graphics, box.x() + box.width() - SETTING_TOGGLE_WIDTH - 8,
				box.y() + (box.height() - 2 - SETTING_TOGGLE_HEIGHT) / 2, SETTING_TOGGLE_WIDTH, SETTING_TOGGLE_HEIGHT,
				toggle, accent);
	}

	private void drawSliderRow(GuiGraphicsExtractor graphics, IntSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		boolean active = setting == draggingSetting;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY) || active);

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				RenderUtils.mix(0x10FFFFFF, accent, hover * 0.35f),
				RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		int textY = box.y() + 4;
		String value = String.valueOf(setting.get());
		// Подпись не должна наезжать на число: ограничиваем её свободным местом
		int valueLimit = box.width() - 18 - font.width(value) - 8;
		graphics.text(font, RenderUtils.clamp(font, setting.getName(), valueLimit), box.x() + 9, textY,
				TEXT_SECONDARY, false);
		graphics.text(font, value, box.x() + box.width() - 9 - font.width(value), textY, TEXT_PRIMARY, false);

		int trackX = box.x() + 9;
		int trackY = box.y() + box.height() - 10;
		int trackWidth = box.width() - 18;

		RenderUtils.fillRounded(graphics, trackX, trackY, trackWidth, 3, 1, 0x26FFFFFF);

		float progress = setting.getNormalized();
		RenderUtils.fillRounded(graphics, trackX, trackY, Math.max(2, (int) (trackWidth * progress)), 3, 1, accent);
		RenderUtils.fillRounded(graphics, (int) (trackX + (trackWidth - 6) * progress), trackY - 2, 6, 7, 3, 0xFFF2F2F7);

		drawRipples(graphics, box, accent);
	}

	private void drawTextRow(GuiGraphicsExtractor graphics, StringSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		boolean focused = setting == focusedSetting;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY) || focused);

		int border = focused ? accent : RenderUtils.mix(0x10FFFFFF, accent, hover * 0.35f);
		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4, border,
				RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		int textY = box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1;
		int textX = box.x() + 9;

		String value = setting.get();
		int labelSpace = 42;
		String shown = RenderUtils.clamp(font, value.isEmpty() && !focused ? "—" : value, box.width() - 18 - labelSpace);
		graphics.text(font, shown, textX, textY, focused ? TEXT_PRIMARY : TEXT_SECONDARY, false);

		String label = RenderUtils.clamp(font, setting.getName(), labelSpace);
		graphics.text(font, label, box.x() + box.width() - 9 - font.width(label), textY, TEXT_DIM, false);

		// Мигающий курсор в конце строки
		if (focused && (Util.getMillis() / 500L) % 2 == 0) {
			int cursorX = textX + font.width(shown) + 1;
			graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight - 1, accent);
		}

		drawRipples(graphics, box, accent);
	}

	private void drawHint(GuiGraphicsExtractor graphics, int x, int y) {
		String hint = "ЛКМ — вкл/выкл   •   ПКМ — настройки   •   СКМ — бинд   •   колесо — прокрутка   •   шапку можно таскать";
		graphics.text(this.font, RenderUtils.clamp(this.font, hint, GUI_WIDTH - PADDING * 2),
				x + PADDING, y + panelHeight() - PADDING - this.font.lineHeight + 1, TEXT_DIM, false);
	}

	/** Плашка «нажми кнопку для бинда» поверх панели. */
	private void drawBindingOverlay(GuiGraphicsExtractor graphics, int x, int y, int accent) {
		Font font = this.font;
		String title = "Нажми кнопку для бинда";
		String subtitle = bindingModule.getName() + "   •   ESC — отмена";

		int width = Math.max(font.width(title), font.width(subtitle)) + 26;
		int height = 48;
		int boxX = x + (GUI_WIDTH - width) / 2;
		int boxY = y + (panelHeight() - height) / 2;

		// Приглушаем список, чтобы плашка читалась
		graphics.fill(x + 1, y + 1, x + GUI_WIDTH - 1, y + panelHeight() - 1, 0xD205050A);

		RenderUtils.drawSoftShadow(graphics, boxX, boxY, width, height, 8, SHADOW_LAYERS);
		RenderUtils.fillRounded(graphics, boxX, boxY, width, height, 8, PANEL_OUTLINE);
		RenderUtils.fillRounded(graphics, boxX + 1, boxY + 1, width - 2, height - 2, 7, PANEL_TOP);

		graphics.text(font, title, boxX + (width - font.width(title)) / 2, boxY + 11, TEXT_PRIMARY, false);
		graphics.text(font, subtitle, boxX + (width - font.width(subtitle)) / 2, boxY + 25, TEXT_DIM, false);

		// Акцентная полоска внизу плашки
		graphics.fill(boxX + 12, boxY + height - 6, boxX + width - 12, boxY + height - 5,
				RenderUtils.withAlpha(accent, 0.85f));
	}

	// ------------------------------------------------------------------
	// Волна по клику
	// ------------------------------------------------------------------

	private void addRipple(double mouseX, double mouseY, Hitbox bounds) {
		float maxRadius = (float) Math.max(
				Math.hypot(mouseX - bounds.x(), mouseY - bounds.y()),
				Math.max(Math.hypot(mouseX - (bounds.x() + bounds.width()), mouseY - bounds.y()),
						Math.max(Math.hypot(mouseX - bounds.x(), mouseY - (bounds.y() + bounds.height())),
								Math.hypot(mouseX - (bounds.x() + bounds.width()), mouseY - (bounds.y() + bounds.height())))));
		ripples.add(new Ripple((float) mouseX, (float) mouseY, bounds, Util.getMillis(), maxRadius + 8.0f));
	}

	private void drawRipples(GuiGraphicsExtractor graphics, Hitbox box, int accent) {
		if (ripples.isEmpty()) {
			return;
		}

		long now = Util.getMillis();
		graphics.enableScissor(box.x(), box.y(), box.x() + box.width(), box.y() + box.height());

		for (Ripple ripple : ripples) {
			if (!ripple.bounds().equals(box)) {
				continue;
			}

			float progress = Math.min((now - ripple.startTime()) / (float) RIPPLE_DURATION, 1.0f);
			float eased = 1.0f - (float) Math.pow(1.0f - progress, 3.0f);
			float radius = ripple.maxRadius() * eased;
			float fade = 1.0f - progress;

			// Две волны разного размера — объёмный «всплеск»
			RenderUtils.fillCircle(graphics, ripple.x(), ripple.y(), radius,
					RenderUtils.withAlpha(accent, 0.22f * fade));
			RenderUtils.fillCircle(graphics, ripple.x(), ripple.y(), radius * 0.6f,
					RenderUtils.withAlpha(accent, 0.16f * fade));
		}

		graphics.disableScissor();
	}

	// ------------------------------------------------------------------
	// Анимации
	// ------------------------------------------------------------------

	private void updateAnimations() {
		long now = Util.getMillis();
		if (lastFrameMillis == 0L) {
			lastFrameMillis = now;
		}
		float deltaSeconds = Math.min((now - lastFrameMillis) / 1000.0f, 0.05f);
		lastFrameMillis = now;

		// Коэффициент приближения за кадр (не зависит от FPS)
		step = 1.0f - (float) Math.exp(-deltaSeconds * 22.0f);

		openAnimation = approach(openAnimation, 1.0f);
		panelHeightAnim = approach(panelHeightAnim, targetPanelHeight());
		scroll = approach(scroll, scrollTarget);
		ripples.removeIf(ripple -> now - ripple.startTime() >= RIPPLE_DURATION);
	}

	private float approach(float current, float target) {
		return current + (target - current) * step;
	}

	private float hoverProgress(String key, boolean hovered) {
		float current = hoverAnimations.getOrDefault(key, 0.0f);
		float next = approach(current, hovered ? 1.0f : 0.0f);
		hoverAnimations.put(key, next);
		return next;
	}

	private float toggleProgress(Module module) {
		String key = "module:" + module.getId();
		float current = toggleAnimations.getOrDefault(key, module.isEnabled() ? 1.0f : 0.0f);
		float next = approach(current, module.isEnabled() ? 1.0f : 0.0f);
		toggleAnimations.put(key, next);
		return next;
	}

	private float toggleProgress(String key, BooleanSetting setting) {
		float current = toggleAnimations.getOrDefault(key, setting.isEnabled() ? 1.0f : 0.0f);
		float next = approach(current, setting.isEnabled() ? 1.0f : 0.0f);
		toggleAnimations.put(key, next);
		return next;
	}

	// ------------------------------------------------------------------
	// Ввод
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		int panelHeight = panelHeight();
		boolean insidePanel = isInside(mouseX, mouseY, guiX, guiY, GUI_WIDTH, panelHeight);
		Hitbox rippleBounds = insidePanel ? new Hitbox(Math.round(guiX), Math.round(guiY), GUI_WIDTH, panelHeight) : null;

		// Перетаскивание за шапку
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
				&& isInside(mouseX, mouseY, guiX, guiY, GUI_WIDTH, HEADER_HEIGHT)) {
			dragging = true;
			dragOffsetX = mouseX - guiX;
			dragOffsetY = mouseY - guiY;
			clearSettingFocus();
			if (rippleBounds != null) {
				addRipple(mouseX, mouseY, rippleBounds);
			}
			return true;
		}

		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT
				&& event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT
				&& event.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			return super.mouseClicked(event, doubleClick);
		}

		int listX = Math.round(guiX) + PADDING + CATEGORY_WIDTH + PADDING;
		int listY = Math.round(guiY) + HEADER_HEIGHT + PADDING;
		int listWidth = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3;
		int listHeight = panelHeight() - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;

		// Ждём бинд: любая кнопка мыши становится новым биндом модуля
		if (bindingModule != null) {
			bindingModule.setBind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
			bindingModule = null;
			ConfigManager.save();
			playClick();
			return true;
		}

		// Клик в любом месте снимает фокус с текстового поля (и применяет ввод)
		clearSettingFocus();

		for (LayoutEntry entry : buildLayout()) {
			Hitbox box = entry.box();
			if (!box.contains(mouseX, mouseY)) {
				continue;
			}
			// За границами списка клики не считаются
			if (entry.kind() != Kind.CATEGORY && !box.intersects(listX, listY, listWidth, listHeight)) {
				continue;
			}

			addRipple(mouseX, mouseY, box);

			switch (entry.kind()) {
				case CATEGORY -> {
					selected = entry.category();
					expanded = null;
					bindingModule = null;
					scrollTarget = 0;
					scroll = 0;
					playClick();
				}
				case MODULE -> {
					boolean hasSettings = !entry.module().getSettings().isEmpty();
					boolean onExpandMark = hasSettings && expandMark(box).contains(mouseX, mouseY);

					if (onExpandMark) {
						// Стрелка — раскрыть/свернуть настройки, не трогая состояние модуля
						expanded = expanded == entry.module() ? null : entry.module();
					} else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						entry.module().toggle();
					} else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
						// Колесиком по модулю — переходим в режим ожидания клавиши
						bindingModule = entry.module();
						clearSettingFocus();
					} else {
						expanded = expanded == entry.module() ? null : entry.module();
					}
					playClick();
				}
				case SETTING -> handleSettingClick(entry, box, mouseX, event.button());
			}
			return true;
		}

		// Клик по пустому месту панели — просто волна
		if (rippleBounds != null) {
			addRipple(mouseX, mouseY, rippleBounds);
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void handleSettingClick(LayoutEntry entry, Hitbox box, double mouseX, int button) {
		Setting<?> setting = entry.setting();

		if (setting instanceof IntSetting intSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			draggingSetting = intSetting;
			draggingModule = entry.module();
			updateSlider(intSetting, box, mouseX);
			playClick();
			return;
		}

		if (setting instanceof StringSetting stringSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			if (focusedSetting != stringSetting) {
				focusedSetting = stringSetting;
				focusedModule = entry.module();
				focusedDirty = false;
			}
			playClick();
			return;
		}

		if (setting instanceof ColorSetting colorSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			if (focusedSetting != colorSetting) {
				focusedSetting = colorSetting;
				focusedModule = entry.module();
				colorDraft = colorSetting.getHex();
				focusedDirty = false;
			}
			playClick();
			return;
		}

		if (setting instanceof ButtonSetting buttonSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			// Кнопка может открыть другой экран, поэтому сначала гасим фокус и сохраняем
			clearSettingFocus();
			playClick();
			buttonSetting.run();
			return;
		}

		if (setting instanceof BooleanSetting booleanSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			booleanSetting.toggle();
			entry.module().onSettingsChanged();
			ConfigManager.save();
			playClick();
			return;
		}

		if (setting instanceof ModeSetting modeSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			List<Hitbox> segments = modeSegments(this.font, modeSetting, box);
			if (segments.isEmpty()) {
				// Компактный режим: левая половина строки — назад, правая — вперёд
				modeSetting.shift(mouseX < box.x() + box.width() / 2.0 ? -1 : 1);
			} else {
				for (int i = 0; i < segments.size(); i++) {
					if (segments.get(i).contains(mouseX, box.centerY())) {
						modeSetting.set(i);
						break;
					}
				}
			}
			if (entry.module() != null) {
				entry.module().onSettingsChanged();
			}
			ConfigManager.save();
			playClick();
		}
	}

	private void updateSlider(IntSetting setting, Hitbox box, double mouseX) {
		int trackX = box.x() + 9;
		int trackWidth = box.width() - 18;
		setting.setNormalized((float) ((mouseX - trackX) / trackWidth));
	}

	private void clearSettingFocus() {
		// Цвет применяем из черновика: пока поле в фокусе, там может быть неполный код
		if (focusedSetting instanceof ColorSetting colorSetting && !colorDraft.isEmpty()) {
			colorSetting.trySetHex(colorDraft);
			focusedDirty = true;
		}

		if (focusedModule != null && focusedDirty) {
			focusedModule.onSettingsChanged();
			ConfigManager.save();
		}
		focusedSetting = null;
		focusedModule = null;
		focusedDirty = false;
		colorDraft = "";
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (draggingSetting != null) {
			for (LayoutEntry entry : buildLayout()) {
				if (entry.kind() == Kind.SETTING && entry.setting() == draggingSetting) {
					updateSlider(draggingSetting, entry.box(), event.x());
					break;
				}
			}
			return true;
		}

		if (dragging) {
			guiX = (float) (event.x() - dragOffsetX);
			guiY = (float) (event.y() - dragOffsetY);
			clampPanel();
			return true;
		}

		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingSetting != null) {
			if (draggingModule != null) {
				draggingModule.onSettingsChanged();
				ConfigManager.save();
			}
			draggingSetting = null;
			draggingModule = null;
			return true;
		}

		if (dragging) {
			dragging = false;
			return true;
		}

		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int listHeight = panelHeight() - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;
		int maxScroll = Math.max(0, contentHeight() - listHeight);
		scrollTarget = clamp(scrollTarget - (float) scrollY * 16.0f, -maxScroll, 0);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (focusedSetting instanceof ColorSetting colorSetting) {
			String typed = event.codepointAsString();
			// В поле цвета принимают только шестнадцатеричные цифры
			if (typed.length() == 1 && isHexDigit(typed.charAt(0)) && colorDraft.length() < 8) {
				colorDraft += Character.toUpperCase(typed.charAt(0));
				colorSetting.trySetHex(colorDraft);
				focusedDirty = true;
			}
			return true;
		}

		if (focusedSetting instanceof StringSetting stringSetting && event.isAllowedChatCharacter()) {
			stringSetting.set(stringSetting.get() + event.codepointAsString());
			focusedDirty = true;
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Режим бинда: следующая нажатая клавиша и есть новый бинд
		if (bindingModule != null) {
			if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
				bindingModule.setBind(InputConstants.Type.KEYSYM.getOrCreate(event.key()));
				ConfigManager.save();
			}
			bindingModule = null;
			playClick();
			return true;
		}

		if (focusedSetting instanceof ColorSetting colorSetting) {
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
				if (!colorDraft.isEmpty()) {
					colorDraft = colorDraft.substring(0, colorDraft.length() - 1);
					if (!colorDraft.isEmpty()) {
						colorSetting.trySetHex(colorDraft);
					}
					focusedDirty = true;
				}
				return true;
			}

			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				clearSettingFocus();
				return true;
			}

			return true;
		}

		if (focusedSetting instanceof StringSetting stringSetting) {
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
				String text = stringSetting.get();
				if (!text.isEmpty()) {
					stringSetting.set(text.substring(0, text.length() - 1));
					focusedDirty = true;
				}
				return true;
			}

			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				clearSettingFocus();
				return true;
			}

			// Пока печатаем, остальные клавиши до игры не доходят
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		clearSettingFocus();
		bindingModule = null;
		ConfigManager.save();
		super.onClose();
	}

	private void playClick() {
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}

	// ------------------------------------------------------------------
	// Раскладка элементов
	// ------------------------------------------------------------------

	/** Позиции всех кликабельных элементов на текущем кадре. */
	private List<LayoutEntry> buildLayout() {
		List<LayoutEntry> layout = new ArrayList<>();

		int x = Math.round(guiX);
		int y = Math.round(guiY);

		// Категории
		int categoryY = y + HEADER_HEIGHT + PADDING;
		for (ModuleCategory category : ModuleCategory.values()) {
			layout.add(new LayoutEntry(new Hitbox(x + PADDING, categoryY, CATEGORY_WIDTH, CATEGORY_ROW_HEIGHT),
					Kind.CATEGORY, category, null, null));
			categoryY += CATEGORY_ROW_HEIGHT + CATEGORY_GAP;
		}

		// Модули выбранной категории (+ раскрытые настройки)
		int listX = x + PADDING + CATEGORY_WIDTH + PADDING;
		int listWidth = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3;
		int rowY = y + HEADER_HEIGHT + PADDING + Math.round(scroll);

		for (Module module : ModuleManager.getByCategory(selected)) {
			layout.add(new LayoutEntry(new Hitbox(listX, rowY, listWidth, MODULE_ROW_HEIGHT), Kind.MODULE, null, module, null));
			rowY += MODULE_ROW_HEIGHT;

			if (module == expanded) {
				for (Setting<?> setting : module.getSettings()) {
					int height = settingHeight(setting);
					layout.add(new LayoutEntry(new Hitbox(listX + 10, rowY, listWidth - 20, height),
							Kind.SETTING, null, module, setting));
					rowY += height;
				}
			}
		}

		return layout;
	}

	private static int settingHeight(Setting<?> setting) {
		if (setting instanceof IntSetting) {
			return SLIDER_ROW_HEIGHT;
		}
		if (setting instanceof StringSetting || setting instanceof ColorSetting || setting instanceof ButtonSetting
				|| setting instanceof ModeSetting) {
			return TEXT_ROW_HEIGHT;
		}
		return TOGGLE_ROW_HEIGHT;
	}

	private int contentHeight() {
		int height = 0;
		for (Module module : ModuleManager.getByCategory(selected)) {
			height += MODULE_ROW_HEIGHT;
			if (module == expanded) {
				for (Setting<?> setting : module.getSettings()) {
					height += settingHeight(setting);
				}
			}
		}
		return height;
	}

	/**
	 * Держим панель так, чтобы до неё всегда можно было дотянуться.
	 *
	 * Раньше нижняя граница пускала окно на пол-экрана за нижний край — панель
	 * «терялась», и до неё нельзя было дотащить курсор. Теперь по горизонтали окно
	 * вообще не выходит за границы (если экран уже панели — центрируется), а по
	 * вертикали оставляем видимой шапку: за неё и таскаем.
	 */
	private void clampPanel() {
		float margin = 6.0f;
		float minX = Math.min(margin, (this.width - GUI_WIDTH) / 2.0f);
		float maxX = Math.max(minX, this.width - GUI_WIDTH - margin);
		guiX = clamp(guiX, minX, maxX);

		float maxY = Math.max(margin, this.height - panelHeight() - margin);
		guiY = clamp(guiY, margin, maxY);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static boolean isInside(double px, double py, float x, float y, int width, int height) {
		return px >= x && px <= x + width && py >= y && py <= y + height;
	}

	private static boolean isHexDigit(char symbol) {
		return Character.digit(symbol, 16) >= 0;
	}

	// ------------------------------------------------------------------
	// Вспомогательные типы
	// ------------------------------------------------------------------

	private enum Kind {
		CATEGORY, MODULE, SETTING
	}

	private record Hitbox(int x, int y, int width, int height) {
		boolean contains(double px, double py) {
			return px >= x && px < x + width && py >= y && py < y + height;
		}

		double centerX() {
			return x + width / 2.0;
		}

		double centerY() {
			return y + height / 2.0;
		}

		boolean intersects(int otherX, int otherY, int otherWidth, int otherHeight) {
			return x < otherX + otherWidth && x + width > otherX && y < otherY + otherHeight && y + height > otherY;
		}
	}

	private record LayoutEntry(Hitbox box, Kind kind, ModuleCategory category, Module module, Setting<?> setting) {
	}

	/** Волна, расходящаяся от места клика. */
	private record Ripple(float x, float y, Hitbox bounds, long startTime, float maxRadius) {
	}
}

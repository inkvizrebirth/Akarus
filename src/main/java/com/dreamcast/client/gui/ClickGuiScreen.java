package com.dreamcast.client.gui;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.gui.screens.DreamcastMenuScreen;
import com.dreamcast.client.gui.theme.ClientTheme;
import com.dreamcast.client.module.impl.ClickGuiModule;
import com.dreamcast.client.settings.BlockListSetting;
import com.dreamcast.client.settings.ElementListSetting;
import net.minecraft.client.renderer.RenderPipelines;
import com.dreamcast.client.config.ConfigManager;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ButtonSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.settings.Setting;
import com.dreamcast.client.settings.StringSetting;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickGUI — собственное меню клиента Dreamcast.
 *
 * Всё рисуется вручную через {@link GuiGraphicsExtractor}: тёмная панель с мягкой тенью,
 * размытый фон, «волна» в месте клика и плавные анимации.
 * Поддерживаются переключатели, слайдеры (числа) и текстовые поля.
 */
public class ClickGuiScreen extends Screen {

	// --- Геометрия ---
	private static final int GUI_WIDTH = 500;
	/** Максимальная высота окна: дальше список уходит в прокрутку. */
	private static final int GUI_MAX_HEIGHT = 310;
	/** Минимальная высота — в ней целиком видно столбик категорий. */
	private static final int GUI_MIN_HEIGHT = 208;
	private static final int HEADER_HEIGHT = 36;
	private static final int CATEGORY_WIDTH = 128;
	private static final int CATEGORY_ROW_HEIGHT = 24;
	private static final int CATEGORY_GAP = 4;
	private static final int MODULE_ROW_HEIGHT = 36;
	private static final int TOGGLE_ROW_HEIGHT = 16;
	private static final int SLIDER_ROW_HEIGHT = 30;
	private static final int TEXT_ROW_HEIGHT = 20;
	private static final int PADDING = 8;
	private static final int FOOTER_HEIGHT = 15;
	private static final int PANEL_RADIUS = 12;

	/** Ширина поля поиска в шапке. */
	private static final int SEARCH_WIDTH = 112;

	private static final int TOGGLE_WIDTH = 30;
	private static final int TOGGLE_HEIGHT = 12;
	private static final int SETTING_TOGGLE_WIDTH = 24;
	private static final int SETTING_TOGGLE_HEIGHT = 10;

	private static final int SHADOW_LAYERS = 5;

	// --- Цвета: всё в чёрных тонах, акцент берётся из категории ---
	private static final int BACKGROUND_DIM = 0xA6000000;
	private static final int PANEL_OUTLINE = 0xFF232329;
	private static final int ROW_BORDER = 0x12FFFFFF;
	private static final int SHEEN = 0x0CFFFFFF;
	private static final int TEXT_PRIMARY = 0xFFF6F6F8;
	private static final int TEXT_SECONDARY = 0xFFA6A6B2;
	private static final int TEXT_DIM = 0xFF6B6B78;
	/** Имена старых ресурсов не всегда совпадают с id модулей. */
	private static final Map<String, String> MODULE_ICONS = Map.of(
			"scaffold", "auto_mine",
			"free_look", "freelook"
	);

	/** Размытие можно вызывать только один раз за кадр — если игра не даёт, отключаем его. */
	private static boolean blurSupported = true;

	// --- Состояние ---
	private ModuleCategory selected = ModuleCategory.HUD;
	private Module expanded = null;
	private Module closingExpanded = null;
	private final Screen parent;
	private static final Identifier MENU_BACKGROUND =
			Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "textures/gui/main_menu_background.png");

	/** Поиск по модулям выбранной категории; пустая строка — без фильтра. */
	private String searchQuery = "";
	private boolean searchFocused;

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
	/** Границы поля поиска последнего кадра — для клика и курсора. */
	private Hitbox searchBox;
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
	private float openAnimation;
	/** Закрытие — сначала доигрываем анимацию, потом реально уходим. */
	private boolean closing;
	private boolean closeDispatched;
	/** Момент смены категории/поиска: от него считается «лестница» появления строк. */
	private long contentSwitchAt = Util.getMillis();
	/** Нажатия: key → время. Короткий «вжим» контента. */
	private final Map<String, Long> pressAnimations = new HashMap<>();
	/** Раскрытие настроек: key moduleId → прогресс поворота стрелки. */
	private final Map<String, Float> expandAnimations = new HashMap<>();

	private long lastFrameMillis;
	private float step;

	public ClickGuiScreen() {
		this(null);
	}

	public ClickGuiScreen(Screen parent) {
		super(Component.literal(DreamcastClient.MOD_NAME + " " + DreamcastClient.MOD_VERSION));
		this.parent = parent;
	}

	/** Открывает меню (бинд модуля ClickGUI, по умолчанию правый Shift). */
	public static void open() {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}
		// Из чата меню тоже открывается — это удобно; поверх других экранов не лезем
		if (client.gui.screen() == null || client.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) {
			client.gui.setScreen(new ClickGuiScreen());
		}
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
	/**
	 * Модули выбранной категории с учётом строки поиска.
	 * Единая точка фильтрации: её используют и отрисовка, и раскладка, и клики,
	 * поэтому «что вижу» и «куда попадаю» не расходятся.
	 */
	private List<Module> modulesShown() {
		List<Module> modules = ModuleManager.getByCategory(selected);
		String query = searchQuery.trim().toLowerCase();
		if (query.isEmpty()) {
			return modules;
		}
		List<Module> filtered = new ArrayList<>();
		for (Module module : modules) {
			if (module.getName().toLowerCase().contains(query)
					|| module.getDescription().toLowerCase().contains(query)
					|| module.getId().toLowerCase().contains(query)) {
				filtered.add(module);
			}
		}
		return filtered;
	}

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
		// Screen не рисует предыдущий TitleScreen автоматически. Без этого при
		// открытии ClickGUI из меню сзади оставался чёрный кадр.
		if (parent instanceof DreamcastMenuScreen) {
			float scale = Math.max(width / 1920.0f, height / 1080.0f);
			int srcW = Math.round(width / scale);
			int srcH = Math.round(height / scale);
			graphics.blit(RenderPipelines.GUI_TEXTURED, MENU_BACKGROUND, 0, 0,
					(1920 - srcW) / 2.0f, (1080 - srcH) / 2.0f,
					width, height, srcW, srcH, 1920, 1080);
		}
		ClickGuiModule clickGui = ModuleManager.find(ClickGuiModule.class);
		boolean glass = clickGui == null || clickGui.glassEnabled();
		// «Стекло» — мир за окном чуть размывается и слегка притемняется;
		// без стекла фон темнее, зато картинка за окном остаётся резкой
		float dim = (glass ? 0.25f : 0.60f) + 0.40f * openAnimation;
		graphics.fill(0, 0, this.width, this.height, RenderUtils.withAlpha(BACKGROUND_DIM, Math.min(1.0f, dim)));

		if (glass && blurSupported) {
			try {
				graphics.blurBeforeThisStratum();
			} catch (IllegalStateException exception) {
				blurSupported = false;
				DreamcastClient.LOGGER.warn("Размытие недоступно в этом кадре — отключаем блюр", exception);
			}
		}

		// Субтитры рисует ванильный HUD, не забываем про них
		this.minecraft.gui.hud.extractDeferredSubtitles();
	}

	/** Стекло: альфа подложек окна. 0 — плотное окно, 1 — призрачное. */
	private float glassAlpha() {
		ClickGuiModule clickGui = ModuleManager.find(ClickGuiModule.class);
		return clickGui == null ? 0.6f : clickGui.glassAlpha();
	}

	private int panelTop() {
		return mulAlpha(0xF616161A, 1.0f - 0.55f * glassAlpha());
	}

	private int panelBottom() {
		return mulAlpha(0xF809090C, 1.0f - 0.50f * glassAlpha());
	}

	private int listBackground() {
		return mulAlpha(0x59000000, 1.0f - 0.45f * glassAlpha());
	}

	private int rowBackground() {
		return mulAlpha(0xB8101013, 1.0f - 0.45f * glassAlpha());
	}

	/** Умножает альфа-канал цвета (для «призрачных» подложек и появления строк). */
	private static int mulAlpha(int color, float factor) {
		int alpha = Math.round((color >>> 24) * Math.max(0.0f, Math.min(1.0f, factor)));
		return (color & 0x00FFFFFF) | (alpha << 24);
	}


	// ------------------------------------------------------------------
	// Отрисовка
	// ------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		updateAnimations();

		// Открытие/закрытие: окно мягко въезжает снизу с разгоном. Клик-зоны
		// считаются по финальным координатам — за ~150 мс анимации это не мешает.
		int x = Math.round(guiX);
		int y = Math.round(guiY + openSlide());
		int accent = ClientTheme.accent();

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

		// Фирменная волна клика — поверх всего GUI, без ножниц по кнопке
		RenderUtils.drawClickWaves(graphics, accent);

		// Волна по клику в любом месте панели
	}

	/** Сдвиг окна в анимации открытия/закрытия (пиксели, 0 в покое). */
	private float openSlide() {
		float eased = 1.0f - (1.0f - openAnimation) * (1.0f - openAnimation) * (1.0f - openAnimation);
		return (1.0f - eased) * 16.0f;
	}

	private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int accent) {
		// Рамка и двухтоновый фон: сверху чуть светлее, снизу — почти чёрный.
		// В режиме «стекла» фон полупрозрачный — сквозь него видно размытый мир.
		int height = panelHeight();
		RenderUtils.fillRounded(graphics, x, y, GUI_WIDTH, height, PANEL_RADIUS, PANEL_OUTLINE);
		RenderUtils.fillRounded(graphics, x + 1, y + 1, GUI_WIDTH - 2, height - 2,
				PANEL_RADIUS - 1, panelTop(), panelBottom());

		// Тонкий блик по верхней кромке — эффект стекла
		graphics.fill(x + PANEL_RADIUS, y + 1, x + GUI_WIDTH - PANEL_RADIUS, y + 2, SHEEN);

		// Шапка: подложка с оттенком акцента + «полярное» сияние под линией
		int headerColor = RenderUtils.mix(panelTop(), accent, 0.16f);
		RenderUtils.fillRoundedTop(graphics, x + 1, y + 1, GUI_WIDTH - 2, HEADER_HEIGHT - 1, PANEL_RADIUS - 1, headerColor);

		// Линия под шапкой: градиент темы, «течёт» и переливается
		long time = Util.getMillis();
		int lineY = y + HEADER_HEIGHT - 2;
		for (int i = 0; i < GUI_WIDTH - 2; i++) {
			float t = i / (float) (GUI_WIDTH - 3);
			float wave = 0.5f + 0.5f * (float) Math.sin((t * 2.2f - ClientTheme.flowPhase(time) * 0.6f) * Math.PI);
			int color = ClientTheme.gradientAt(t, time);
			graphics.fill(x + 1 + i, lineY, x + 2 + i, lineY + 1,
					RenderUtils.withAlpha(color, 0.35f + 0.60f * wave));
		}

		Font font = this.font;
		int titleY = y + (HEADER_HEIGHT - font.lineHeight) / 2;

		// Логотип DREAMCAST с разрядкой — фирменный вид клиента, без лишних бейджей
		String logo = DreamcastClient.LOGO_TEXT;
		int logoWidth = RenderUtils.trackedWidthBold(font, logo, 3);
		RenderUtils.drawTrackedBold(graphics, font, logo, x + PADDING + 1, titleY, TEXT_PRIMARY, 3);
		RenderUtils.textFlat(graphics, font, "v" + DreamcastClient.MOD_VERSION,
				x + PADDING + 1 + logoWidth + 8, titleY + 1, TEXT_DIM);

		// Поле поиска в шапке: живой фильтр по модулям категории
		int searchX = x + GUI_WIDTH - PADDING - SEARCH_WIDTH;
		int searchY = y + (HEADER_HEIGHT - 14) / 2;
		RenderUtils.fillRoundedBorder(graphics, searchX, searchY, SEARCH_WIDTH, 14, 7,
				searchFocused ? accent : 0x22FFFFFF,
				RenderUtils.mix(0x66000000, 0x14FFFFFF, searchFocused ? 0.6f : 0.2f));
		String shown = RenderUtils.clamp(font,
				searchQuery.isEmpty() && !searchFocused ? "" : searchQuery + (((time / 500L) % 2 == 0) ? "|" : ""),
				SEARCH_WIDTH - 18);
		if (searchQuery.isEmpty() && !searchFocused) {
			RenderUtils.textFlat(graphics, font, "поиск…", searchX + 8, searchY + (14 - font.lineHeight) / 2 + 1, TEXT_DIM);
		} else {
			RenderUtils.textFlat(graphics, font, shown, searchX + 8, searchY + (14 - font.lineHeight) / 2 + 1,
					TEXT_PRIMARY);
		}

		searchBox = new Hitbox(searchX, searchY, SEARCH_WIDTH, 14);
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
			int pressDy = Math.round(pressProgress("category:" + category.name()) * 1.5f);
			// Иконка категории: красится акцентом при выборе, приглушена — без
			RenderUtils.textFlat(graphics, font, category.getGlyph(), box.x() + 9,
					box.y() + (box.height() - font.lineHeight) / 2 + 1 + pressDy,
					isSelected ? accent : RenderUtils.mix(TEXT_DIM, accent, hover * 0.5f));
			RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, category.getDisplayName(), box.width() - 44), box.x() + 19,
					box.y() + (box.height() - font.lineHeight) / 2 + 1 + pressDy, textColor);

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
			int badgeWidth = RenderUtils.width(font, badge);
			int badgeX = box.x() + box.width() - 9 - badgeWidth;
			int badgeY = box.y() + (box.height() - font.lineHeight) / 2 + 1;
			if (active > 0) {
				// Пилюля-подложка под счётчик включённых — «горит» акцентом
				RenderUtils.fillRounded(graphics, badgeX - 4, badgeY - 2, RenderUtils.width(font, badge) + 8,
						font.lineHeight + 3, 4, RenderUtils.withAlpha(accent, 0.22f));
			}
			RenderUtils.textFlat(graphics, font, badge, badgeX, badgeY,
					active == 0 ? TEXT_DIM : RenderUtils.withAlpha(category.getAccent(), 0.95f));


			rowY += CATEGORY_ROW_HEIGHT + CATEGORY_GAP;
		}
	}

	private void drawModules(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, int accent) {
		int listX = x + PADDING + CATEGORY_WIDTH + PADDING;
		int listY = y + HEADER_HEIGHT + PADDING;
		int listWidth = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3;
		int listHeight = panelHeight() - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;

		RenderUtils.fillRounded(graphics, listX, listY, listWidth, listHeight, 6, listBackground());

		// Всё, что выходит за пределы списка, обрезается
		graphics.enableScissor(listX, listY, listX + listWidth, listY + listHeight);

		boolean anyDrawn = false;
		int moduleIndex = 0;
		for (LayoutEntry entry : buildLayout()) {
			if (entry.kind() == Kind.CATEGORY) {
				continue;
			}
			anyDrawn = true;
			if (entry.kind() == Kind.MODULE) {
				drawModuleRow(graphics, entry.module(), entry.box(), accent, mouseX, mouseY, moduleIndex);
				moduleIndex++;
			} else {
				drawSettingRow(graphics, entry.setting(), entry.module(), entry.box(), accent, mouseX, mouseY);
			}
		}

		graphics.disableScissor();

		if (!anyDrawn) {
			String message = searchQuery.isBlank() ? "В этой категории пока пусто" : "Ничего не найдено";
			RenderUtils.textFlat(graphics, font, message, listX + (listWidth - RenderUtils.width(font, message)) / 2,
					listY + listHeight / 2 - font.lineHeight / 2, TEXT_DIM);
		}

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

	// ------------------------------------------------------------------
	// Иконки (assets/dreamcast/textures/gui/icons) — белые, тонируются цветом
	// ------------------------------------------------------------------

	private static void drawIcon(GuiGraphicsExtractor graphics, String name, int x, int y, int size, int color) {
		Identifier icon = Identifier.fromNamespaceAndPath(DreamcastClient.MOD_ID, "textures/gui/icons/" + name + ".png");
		graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F, size, size, 40, 40, 40, 40, color);
	}

	/** Иконка модуля с тинтом под состояние. */
	private void drawModuleIcon(GuiGraphicsExtractor graphics, Module module, int x, int y, int accent) {
		int color = module.isAlwaysEnabled()
				? RenderUtils.mix(TEXT_SECONDARY, accent, 0.45f)
				: module.isEnabled() ? accent : RenderUtils.mix(TEXT_DIM, 0xFFFFFFFF, 0.18f);
		drawIcon(graphics, MODULE_ICONS.getOrDefault(module.getId(), module.getId()), x, y, 13, color);
	}

	private void drawModuleRow(GuiGraphicsExtractor graphics, Module module, Hitbox box, int accent,
			int mouseX, int mouseY, int index) {
		Font font = this.font;
		float hover = hoverProgress("module:" + module.getId(), box.contains(mouseX, mouseY));
		float toggle = toggleProgress(module);
		boolean permanent = module.isAlwaysEnabled();

		// «Лестница» появления строк после смены категории/поиска
		float appear = rowAppear(index);
		int slideX = Math.round((1.0f - appear) * -7.0f);

		// Карточка модуля: тёмная подложка, акцентная рамка, подсветка при наведении
		int background = RenderUtils.mix(rowBackground(), RenderUtils.withAlpha(accent, 0.45f), toggle * 0.45f);
		int border = RenderUtils.mix(ROW_BORDER, accent, toggle * 0.55f);
		RenderUtils.fillRoundedBorder(graphics, box.x() + slideX, box.y(), box.width(), box.height(), 6,
				mulAlpha(border, appear), mulAlpha(background, appear));

		if (hover > 0.01f) {
			RenderUtils.fillRounded(graphics, box.x() + slideX + 1, box.y() + 1, box.width() - 2, box.height() - 2, 5,
					RenderUtils.withAlpha(0xFFFFFFFF, 0.06f * hover * appear));
		}

		// «Вжимание» при клике: контент на пиксель вниз
		float press = pressProgress("module:" + module.getId());
		int contentDy = Math.round(press * 1.5f);

		// Волна от клика — рисуется под текстом

		boolean binding = bindingModule == module;
		String bindLabel = binding ? "..." : module.getBindLabel();

		// Акцентная полоса у включённого модуля — «работает» видно издалека
		if (toggle > 0.02f) {
			RenderUtils.fillRounded(graphics, box.x() + slideX + 2, box.y() + 6, 2, box.height() - 12, 1,
					RenderUtils.withAlpha(accent, 0.9f * toggle * appear));
		}

		int textX = box.x() + 9 + slideX;
		// Иконка модуля — тонируется темой у включённых
		drawModuleIcon(graphics, module, textX, box.y() + 5 + contentDy, accent);
		int nameX = textX + 17;
		// Имя + бинд должны влезть до тумблера и кнопки раскрытия
		int rightLimit = box.x() + box.width() - TOGGLE_WIDTH - 34;
		String name = module.getName();
		int nameLimit = rightLimit - nameX - RenderUtils.width(font, bindLabel) - 12;
		if (nameLimit < RenderUtils.width(font, "WW")) {
			name = "";
			nameLimit = rightLimit - nameX;
		}
		name = RenderUtils.clamp(font, name, nameLimit);
		int nameColor = module.isEnabled() || permanent ? TEXT_PRIMARY : TEXT_SECONDARY;
		RenderUtils.textFlat(graphics, font, name, nameX, box.y() + 6 + contentDy,
				mulAlpha(nameColor, appear));
		String bind = RenderUtils.clamp(font, bindLabel, rightLimit - nameX - RenderUtils.width(font, name) - 8);
		RenderUtils.textFlat(graphics, font, bind, nameX + RenderUtils.width(font, name) + 6,
				box.y() + 7 + contentDy, mulAlpha(binding ? accent : TEXT_DIM, appear));
		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, module.getDescription(), box.width() - 90),
				textX + 17, box.y() + 21 + contentDy,
				mulAlpha(RenderUtils.mix(TEXT_DIM, TEXT_SECONDARY, toggle * 0.75f), appear));

		// Тумблер справа; у модулей-«настроек» (ClickGUI, HUD) — мягкий индикатор:
		// они всегда активны, выключателя у них нет
		if (permanent) {
			int dotX = box.x() + box.width() - TOGGLE_WIDTH - 9 + (TOGGLE_WIDTH - 6) / 2;
			int dotY = box.y() + (box.height() - 6) / 2;
			RenderUtils.fillRounded(graphics, dotX, dotY, 6, 6, 3,
					RenderUtils.withAlpha(accent, (0.55f + 0.45f * hover) * appear));
		} else {
			RenderUtils.drawToggle(graphics, box.x() + box.width() - TOGGLE_WIDTH - 9,
					box.y() + (box.height() - TOGGLE_HEIGHT) / 2, TOGGLE_WIDTH, TOGGLE_HEIGHT, toggle, accent);
		}

		// Стрелка раскрытия настроек: свёрнуто — вниз, раскрыто — вверх,
		// между состояниями плавный кроссфейд двух картинок
		if (!module.getSettings().isEmpty()) {
			Hitbox markBox = expandMark(box);
			float expand = expandProgress(module);
			float markHover = hoverProgress("expand:" + module.getId(), markBox.contains(mouseX, mouseY));
			int markColor = RenderUtils.mix(TEXT_DIM, TEXT_SECONDARY, markHover);
			int markX = markBox.x() + 2 + slideX;
			int markY = markBox.y() + 2 + contentDy;
			if (expand < 1.0f) {
				drawIcon(graphics, "arrow_down", markX, markY, 9, mulAlpha(markColor, 1.0f - expand));
			}
			if (expand > 0.0f) {
				drawIcon(graphics, "arrow_up", markX, markY, 9, mulAlpha(markColor, expand));
			}
		}
	}

	/** Прогресс появления строки после смены категории: «лестница» по индексу. */
	private float rowAppear(int index) {
		long elapsed = Util.getMillis() - contentSwitchAt - index * 45L;
		float raw = Math.max(0.0f, Math.min(1.0f, elapsed / 170.0f));
		return raw * raw * (3.0f - 2.0f * raw);
	}

	/** Прогресс «вжимания» после клика (0..1, спадает за 150 мс). */
	private float pressProgress(String key) {
		Long pressedAt = pressAnimations.get(key);
		if (pressedAt == null) {
			return 0.0f;
		}
		float progress = (Util.getMillis() - pressedAt) / 150.0f;
		return Math.max(0.0f, 1.0f - progress);
	}

	/** Прогресс поворота стрелки раскрытия (0 — свёрнуто, 1 — раскрыто). */
	private float expandProgress(Module module) {
		return expandAnimations.getOrDefault(module.getId(), module == expanded ? 1.0f : 0.0f);
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
		} else if (setting instanceof ElementListSetting elementList) {
			drawElementListRow(graphics, elementList, key, box, accent, mouseX, mouseY);
		} else if (setting instanceof BlockListSetting blockList) {
			drawBlockListRow(graphics, blockList, key, box, accent, mouseX, mouseY);
		}
	}

	/**
	 * Строка-список с множественным выбором (элементы HUD): каждый вариант —
	 * своя строка с отметкой; клик переключает выбор. Никаких тумблеров —
	 * «что отметил, то и показывается».
	 */
	private void drawElementListRow(GuiGraphicsExtractor graphics, ElementListSetting setting, String key,
			Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 5,
				RenderUtils.mix(0x10FFFFFF, accent, hover * 0.25f),
				RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.5f));

		List<Hitbox> rows = elementRowBoxes(setting, box);
		for (int i = 0; i < rows.size(); i++) {
			Hitbox row = rows.get(i);
			ElementListSetting.Element element = setting.getElements().get(i);
			boolean selected = setting.isSelected(element.id());
			boolean rowHover = row.contains(mouseX, mouseY);

			if (rowHover) {
				RenderUtils.fillRounded(graphics, row.x(), row.y(), row.width(), row.height(), 3,
						RenderUtils.withAlpha(0xFFFFFFFF, 0.05f));
			}

			// Отметка: рамка-квадратик, у выбранного — заливка темой и галочка
			int boxSize = 9;
			int checkX = row.x() + 8;
			int checkY = row.y() + (row.height() - boxSize) / 2;
			float toggle = toggleProgress(key + ":" + element.id(), selected);
			RenderUtils.fillRounded(graphics, checkX, checkY, boxSize, boxSize, 2,
					RenderUtils.mix(0x33FFFFFF, accent, toggle));
			if (toggle > 0.5f) {
				RenderUtils.textFlat(graphics, font, "✓", checkX + 1, checkY - 1,
						RenderUtils.withAlpha(0xFF101014, (toggle - 0.5f) * 2.0f));
			}

			RenderUtils.textFlat(graphics, font, element.label(), checkX + boxSize + 6,
					row.y() + (row.height() - font.lineHeight) / 2 + 1,
					selected ? TEXT_PRIMARY : TEXT_DIM);
		}

	}

	/** Геометрия строк-вариантов внутри списка (12 px на вариант). */
	private static List<Hitbox> elementRowBoxes(ElementListSetting setting, Hitbox box) {
		List<Hitbox> rows = new ArrayList<>(setting.getElements().size());
		int rowHeight = 12;
		int y = box.y() + 3;
		for (int i = 0; i < setting.getElements().size(); i++) {
			rows.add(new Hitbox(box.x() + 3, y, box.width() - 6, rowHeight));
			y += rowHeight;
		}
		return rows;
	}

	// ------------------------------------------------------------------
	// Список блоков (BlockESP): раскрытие, поиск, множественный выбор
	// ------------------------------------------------------------------

	/** Состояние раскрытого списка блоков: поиск, скролл, время раскрытия. */
	private final Map<String, String> blockSearches = new HashMap<>();
	private final Map<String, Float> blockSearchAnimations = new HashMap<>();
	private final Map<String, Integer> blockScrolls = new HashMap<>();
	private final Map<String, Long> blockOpenedAt = new HashMap<>();
	private String focusedBlockList;

	private void drawBlockListRow(GuiGraphicsExtractor graphics, BlockListSetting setting, String key,
			Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		boolean open = key.equals(focusedBlockList);
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));

		// Анимация раскрытия: список «выезжает» из-под заголовка
		long openedAt = blockOpenedAt.getOrDefault(key, 0L);
		float expand = open ? Math.min(1.0f, (Util.getMillis() - openedAt) / 220.0f) : 0.0f;
		float eased = expand * expand * (3.0f - 2.0f * expand);

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height(), 5,
				RenderUtils.mix(0x10FFFFFF, accent, Math.max(hover, eased * 0.5f) * 0.35f),
				RenderUtils.mix(0x80000000, 0x14FFFFFF, Math.max(hover, eased * 0.5f) * 0.6f));

		// Заголовок: имя настройки и сколько блоков выбрано
		int textY = box.y() + 4;
		String summary = setting.count() == 0 ? "ничего не выбрано"
				: setting.count() == 1 ? "1 блок" : setting.count() + " блоков";
		RenderUtils.textFlat(graphics, font, setting.getName() + " · " + summary, box.x() + 9, textY,
				setting.count() == 0 ? TEXT_DIM : TEXT_SECONDARY);

		int markX = box.x() + box.width() - 14;
		drawIcon(graphics, open ? "arrow_up" : "arrow_down", markX, box.y() + 4, 9,
				RenderUtils.mix(TEXT_DIM, TEXT_SECONDARY, hover));

		if (eased <= 0.01f) {
			return;
		}

		int headerH = TEXT_ROW_HEIGHT - 2;

		// Поле поиска
		int searchY = box.y() + headerH;
		int searchH = 12;
		RenderUtils.fillRounded(graphics, box.x() + 4, searchY, box.width() - 8, searchH, 6,
				RenderUtils.mix(0x66000000, 0x14FFFFFF, 0.4f));
		String query = blockSearches.getOrDefault(key, "");
		if (query.isEmpty()) {
			RenderUtils.textFlat(graphics, font,
					RenderUtils.clamp(font, "поиск по " + BlockListSetting.allBlocks().size() + " блокам…", box.width() - 20),
					box.x() + 10, searchY + 2, TEXT_DIM);
		} else {
			String shown = query + ((Util.getMillis() / 500L) % 2 == 0 ? "|" : "");
			RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, shown, box.width() - 20),
					box.x() + 10, searchY + 2, TEXT_PRIMARY);
		}

		// Строки блоков
		int rowH = 12;
		int listTop = searchY + searchH + 2;
		int visibleRows = Math.min(7, BlockListSetting.search(query).size());
		int listPixels = Math.round((visibleRows * rowH) * eased);
		if (listPixels <= 0) {
			return;
		}

		graphics.enableScissor(box.x() + 2, listTop, box.x() + box.width() - 2, listTop + listPixels);
		List<com.dreamcast.client.settings.BlockListSetting.BlockEntry> found = BlockListSetting.search(query);
		int scroll = blockScrolls.getOrDefault(key, 0);
		int rowsShown = Math.min(7, found.size());
		int startIndex = Math.min(scroll, Math.max(0, found.size() - rowsShown));
		int y = listTop;
		for (int i = startIndex; i < found.size() && i < startIndex + rowsShown; i++) {
			var entry = found.get(i);
			boolean selectedBlock = setting.isSelected(entry.id());
			boolean rowHover = mouseY >= y && mouseY < y + rowH && mouseX >= box.x() && mouseX < box.x() + box.width();

			if (rowHover) {
				RenderUtils.fillRounded(graphics, box.x() + 4, y, box.width() - 8, rowH - 1, 3,
						RenderUtils.withAlpha(0xFFFFFFFF, 0.06f * eased));
			}
			int boxSize = 8;
			int checkX = box.x() + 9;
			int checkY = y + (rowH - 1 - boxSize) / 2;
			float toggle = toggleProgress(key + ":" + entry.id(), selectedBlock);
			RenderUtils.fillRounded(graphics, checkX, checkY, boxSize, boxSize, 2,
					RenderUtils.mix(0x33FFFFFF, accent, toggle));
			if (toggle > 0.5f) {
				RenderUtils.textFlat(graphics, font, "\u2713", checkX + 1, checkY - 1,
						RenderUtils.withAlpha(0xFF101014, (toggle - 0.5f) * 2.0f));
			}
			String label = RenderUtils.clamp(font, entry.id(), box.width() - 34);
			RenderUtils.textFlat(graphics, font, label, checkX + boxSize + 5, y + 1,
					selectedBlock ? TEXT_PRIMARY : TEXT_DIM);
			y += rowH;
		}
		graphics.disableScissor();

		// Счётчик найденных, если поиск что-то отфильтровал
		if (found.size() > 7) {
			String more = (found.size() - 7) + " ещё · колесо";
			RenderUtils.textFlat(graphics, font, more, box.x() + box.width() - 9 - RenderUtils.width(font, more),
					box.y() + box.height() - font.lineHeight - 2, RenderUtils.withAlpha(TEXT_DIM, eased));
		}
	}

	/** Геометрия строк блоков (совпадает с отрисовкой; список должен быть раскрыт). */
	private List<Hitbox> blockRowBoxes(BlockListSetting setting, String key, Hitbox box) {
		List<Hitbox> rows = new ArrayList<>();
		if (!key.equals(focusedBlockList)) {
			return rows;
		}
		List<com.dreamcast.client.settings.BlockListSetting.BlockEntry> found =
				BlockListSetting.search(blockSearches.getOrDefault(key, ""));
		int rowH = 12;
		int rowsShown = Math.min(7, found.size());
		int scroll = blockScrolls.getOrDefault(key, 0);
		int startIndex = Math.min(scroll, Math.max(0, found.size() - rowsShown));
		int y = box.y() + TEXT_ROW_HEIGHT - 2 + 12 + 2;
		for (int i = startIndex; i < found.size() && i < startIndex + rowsShown; i++) {
			rows.add(new Hitbox(box.x() + 4, y, box.width() - 8, rowH));
			y += rowH;
		}
		return rows;
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
			int arrowWidth = RenderUtils.width(font, "‹") + 5;
			String value = setting.getLabel();
			int valueWidth = RenderUtils.width(font, value);
			int valueX = right - arrowWidth - valueWidth;

			RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, setting.getName(), valueX - box.x() - 18),
					box.x() + 9, textY, TEXT_SECONDARY);
			RenderUtils.textFlat(graphics, font, "‹", valueX - arrowWidth, textY,
					RenderUtils.mix(TEXT_DIM, TEXT_PRIMARY, hover));
			RenderUtils.textFlat(graphics, font, value, valueX, textY, TEXT_PRIMARY);
			RenderUtils.textFlat(graphics, font, "›", valueX + valueWidth + 4, textY,
					RenderUtils.mix(TEXT_DIM, TEXT_PRIMARY, hover));
		} else {
			int labelLimit = segments.get(0).x() - box.x() - 18;
			RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, setting.getName(), labelLimit),
					box.x() + 9, textY, TEXT_SECONDARY);

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
				RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, label, segment.width() - 8),
						segment.x() + (segment.width() - RenderUtils.width(font, label)) / 2,
						segment.y() + (segment.height() - font.lineHeight) / 2 + 1,
						selected ? 0xFF0D0D10 : RenderUtils.mix(TEXT_SECONDARY, TEXT_PRIMARY, hover));
			}
		}

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
			int width = RenderUtils.width(font, label) + sidePadding * 2;
			widths.add(width);
			total += width;
		}

		// Нужно ещё оставить место подпись настройки — иначе текст упрётся в сегменты
		int available = box.width() - 18 - RenderUtils.width(font, setting.getName()) - 10;
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
		int codeWidth = Math.max(RenderUtils.width(font, shown), RenderUtils.width(font, "#AARRGGBB"));
		int swatchX = box.x() + box.width() - 9 - codeWidth - 6 - swatchWidth;
		int swatchY = box.y() + (box.height() - 2 - swatchHeight) / 2;
		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, setting.getName(), swatchX - box.x() - 18), box.x() + 9,
				textY, TEXT_SECONDARY);

		RenderUtils.fillRounded(graphics, swatchX, swatchY, swatchWidth, swatchHeight, 3, 0xFF000000);
		RenderUtils.fillRounded(graphics, swatchX + 1, swatchY + 1, swatchWidth - 2, swatchHeight - 2, 2, setting.get());

		RenderUtils.textFlat(graphics, font, shown, swatchX + swatchWidth + 6, textY, focused ? TEXT_PRIMARY : TEXT_DIM);

		// Мигающий курсор за кодом цвета
		if (focused && (Util.getMillis() / 500L) % 2 == 0) {
			int cursorX = swatchX + swatchWidth + 6 + RenderUtils.width(font, shown) + 1;
			graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight - 1, accent);
		}

	}

	/** Строка-кнопка: действие, которое открывается прямо из списка настроек. */
	private void drawButtonRow(GuiGraphicsExtractor graphics, ButtonSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				0x10FFFFFF, RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		int textY = box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1;

		int width = Math.max(54, Math.min(78, RenderUtils.width(font, setting.getLabel()) + 16));
		int height = Math.max(10, box.height() - 10);
		int buttonX = box.x() + box.width() - 8 - width;
		int buttonY = box.y() + (box.height() - 2 - height) / 2;

		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, setting.getName(), buttonX - box.x() - 18), box.x() + 9,
				textY, TEXT_DIM);

		RenderUtils.fillRounded(graphics, buttonX, buttonY, width, height, 4,
				RenderUtils.mix(0xFF26262E, accent, 0.18f + 0.42f * hover));
		String label = RenderUtils.clamp(font, setting.getLabel(), width - 8);
		RenderUtils.textFlat(graphics, font, label, buttonX + (width - RenderUtils.width(font, label)) / 2,
				buttonY + (height - font.lineHeight) / 2 + 1,
				RenderUtils.mix(TEXT_SECONDARY, TEXT_PRIMARY, hover));

	}

	private void drawToggleRow(GuiGraphicsExtractor graphics, BooleanSetting setting, String key, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress(key, box.contains(mouseX, mouseY));
		float toggle = toggleProgress(key, setting);

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				0x10FFFFFF, RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));


		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, setting.getName(), box.width() - SETTING_TOGGLE_WIDTH - 26),
				box.x() + 9, box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1,
				setting.isEnabled() ? TEXT_PRIMARY : TEXT_DIM);

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
		int valueLimit = box.width() - 18 - RenderUtils.width(font, value) - 8;
		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, setting.getName(), valueLimit), box.x() + 9, textY,
				TEXT_SECONDARY);
		RenderUtils.textFlat(graphics, font, value, box.x() + box.width() - 9 - RenderUtils.width(font, value), textY, TEXT_PRIMARY);

		// Дорожка и бегунок — общими виджетами RenderUtils: у них край считается
		// с частичным покрытием пикселей, поэтому бегунок круглый, а не «восьмиугольник»
		int trackX = box.x() + 9;
		int trackWidth = box.width() - 18;
		RenderUtils.drawSlider(graphics, trackX, box.y() + 19, trackWidth, 5,
				setting.getNormalized(), accent);

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
		RenderUtils.textFlat(graphics, font, shown, textX, textY, focused ? TEXT_PRIMARY : TEXT_SECONDARY);

		String label = RenderUtils.clamp(font, setting.getName(), labelSpace);
		RenderUtils.textFlat(graphics, font, label, box.x() + box.width() - 9 - RenderUtils.width(font, label), textY, TEXT_DIM);

		// Мигающий курсор в конце строки
		if (focused && (Util.getMillis() / 500L) % 2 == 0) {
			int cursorX = textX + RenderUtils.width(font, shown) + 1;
			graphics.fill(cursorX, textY - 1, cursorX + 1, textY + font.lineHeight - 1, accent);
		}

	}

	private void drawHint(GuiGraphicsExtractor graphics, int x, int y) {
		// Слева — подсказки о кнопках мыши, справа — сколько модулей включено
		String hint = "ЛКМ вкл · ПКМ настройки · СКМ бинд · колесо ±1";
		RenderUtils.textFlat(graphics, this.font, RenderUtils.clamp(this.font, hint, GUI_WIDTH - PADDING * 2 - 86),
				x + PADDING, y + panelHeight() - PADDING - this.font.lineHeight + 1, TEXT_DIM);

		int enabled = 0;
		int total = 0;
		for (Module module : ModuleManager.getAll()) {
			total++;
			if (module.isEnabled()) {
				enabled++;
			}
		}
		String status = enabled + " / " + total + " активно";
		RenderUtils.textFlat(graphics, this.font, status,
				x + GUI_WIDTH - PADDING - RenderUtils.width(this.font, status),
				y + panelHeight() - PADDING - this.font.lineHeight + 1,
				enabled == 0 ? TEXT_DIM : RenderUtils.withAlpha(selected.getAccent(), 0.95f));
	}

	/** Плашка «нажми кнопку для бинда» поверх панели. */
	private void drawBindingOverlay(GuiGraphicsExtractor graphics, int x, int y, int accent) {
		Font font = this.font;
		String title = "Нажми кнопку для бинда";
		String subtitle = bindingModule.getName() + "   •   ESC — отмена";

		int width = Math.max(RenderUtils.width(font, title), RenderUtils.width(font, subtitle)) + 26;
		int height = 48;
		int boxX = x + (GUI_WIDTH - width) / 2;
		int boxY = y + (panelHeight() - height) / 2;

		// Приглушаем список, чтобы плашка читалась
		graphics.fill(x + 1, y + 1, x + GUI_WIDTH - 1, y + panelHeight() - 1, 0xD205050A);

		RenderUtils.drawSoftShadow(graphics, boxX, boxY, width, height, 8, SHADOW_LAYERS);
		RenderUtils.fillRounded(graphics, boxX, boxY, width, height, 8, PANEL_OUTLINE);
		RenderUtils.fillRounded(graphics, boxX + 1, boxY + 1, width - 2, height - 2, 7, panelTop());

		RenderUtils.textFlat(graphics, font, title, boxX + (width - RenderUtils.width(font, title)) / 2, boxY + 11, TEXT_PRIMARY);
		RenderUtils.textFlat(graphics, font, subtitle, boxX + (width - RenderUtils.width(font, subtitle)) / 2, boxY + 25, TEXT_DIM);

		// Акцентная полоска внизу плашки
		graphics.fill(boxX + 12, boxY + height - 6, boxX + width - 12, boxY + height - 5,
				RenderUtils.withAlpha(accent, 0.85f));
	}

	// ------------------------------------------------------------------
	// Волна по клику
	// ------------------------------------------------------------------

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

		openAnimation = approach(openAnimation, closing ? 0.0f : 1.0f);
		for (Module module : ModuleManager.getAll()) {
			float current = expandAnimations.getOrDefault(module.getId(), 0.0f);
			expandAnimations.put(module.getId(), approach(current, module == expanded ? 1.0f : 0.0f));
		}
		if (closingExpanded != null && expandProgress(closingExpanded) < 0.01f) {
			closingExpanded = null;
		}
		panelHeightAnim = approach(panelHeightAnim, targetPanelHeight());
		scroll = approach(scroll, scrollTarget);
		pressAnimations.values().removeIf(pressedAt -> now - pressedAt >= 160L);

		// Закрытие доиграло — уходим по-настоящему (один раз)
		if (closing && !closeDispatched && openAnimation < 0.04f) {
			closeDispatched = true;
			closeNow();
		}
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
		return toggleProgress(key, setting.isEnabled());
	}

	private float toggleProgress(String key, boolean target) {
		float current = toggleAnimations.getOrDefault(key, target ? 1.0f : 0.0f);
		float next = approach(current, target ? 1.0f : 0.0f);
		toggleAnimations.put(key, next);
		return next;
	}

	// ------------------------------------------------------------------
	// Ввод
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		if (closing) {
			return true;
		}
		double mouseX = event.x();
		double mouseY = event.y();

		// Перетаскивание за шапку
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
				&& isInside(mouseX, mouseY, guiX, guiY, GUI_WIDTH, HEADER_HEIGHT)) {
			// Поле поиска живёт в шапке: клик по нему — фокус, а не перетаскивание
			if (searchBox != null && searchBox.contains(mouseX, mouseY)) {
				searchFocused = true;
				clearSettingFocus();
				return true;
			}
			searchFocused = false;
			dragging = true;
			dragOffsetX = mouseX - guiX;
			dragOffsetY = mouseY - guiY;
			clearSettingFocus();
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
		searchFocused = false;

		for (LayoutEntry entry : buildLayout()) {
			Hitbox box = entry.box();
			if (!box.contains(mouseX, mouseY)) {
				continue;
			}
			// За границами списка клики не считаются
			if (entry.kind() != Kind.CATEGORY && !box.intersects(listX, listY, listWidth, listHeight)) {
				continue;
			}


			switch (entry.kind()) {
				case CATEGORY -> {
					selected = entry.category();
					collapseSettings();
					bindingModule = null;
					focusedBlockList = null;
					scrollTarget = 0;
					scroll = 0;
					contentSwitchAt = Util.getMillis();
					pressAnimations.put("category:" + entry.category().name(), Util.getMillis());
					playClick();
				}
				case MODULE -> {
					focusedBlockList = null;
					boolean hasSettings = !entry.module().getSettings().isEmpty();
					boolean onExpandMark = hasSettings && expandMark(box).contains(mouseX, mouseY);
					pressAnimations.put("module:" + entry.module().getId(), Util.getMillis());

					if (onExpandMark) {
						// Стрелка — раскрыть/свернуть настройки, не трогая состояние модуля
						toggleSettings(entry.module());
					} else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
							&& !entry.module().isAlwaysEnabled()) {
						entry.module().toggle();
					} else if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
						// Колесиком по модулю — переходим в режим ожидания клавиши
						bindingModule = entry.module();
						clearSettingFocus();
					} else {
						// ПКМ или ЛКМ по модулю-«настройке»: раскрыть настройки
						toggleSettings(entry.module());
					}
					playClick();
				}
				case SETTING -> {
					mouseYOfSettingClick = mouseY;
					handleSettingClick(entry, box, mouseX, event.button());
				}
			}
			return true;
		}

		// Клик по пустому месту панели — просто волна

		return super.mouseClicked(event, doubleClick);
	}

	/** Y последнего клика мыши: строки-варианты списка элементов ищутся по вертикали. */
	private double mouseYOfSettingClick;

	private void handleSettingClick(LayoutEntry entry, Hitbox box, double mouseX, int button) {
		Setting<?> setting = entry.setting();
		if (!(setting instanceof BlockListSetting)) {
			focusedBlockList = null;
		}

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

		if (setting instanceof BlockListSetting blockList && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			// Тот же ключ, что в drawBlockListRow: id модуля + id настройки
			String key = (entry.module() == null ? "" : entry.module().getId() + ":") + setting.getId();
			boolean open = key.equals(focusedBlockList);

			if (open) {
				int headerH = TEXT_ROW_HEIGHT - 2;
				// Клик по строке блока — переключить выбор
				List<Hitbox> rows = blockRowBoxes(blockList, key, box);
				for (int i = 0; i < rows.size(); i++) {
					if (rows.get(i).contains(mouseX, mouseYOfSettingClick)) {
						List<com.dreamcast.client.settings.BlockListSetting.BlockEntry> found =
								BlockListSetting.search(blockSearches.getOrDefault(key, ""));
						int scroll = blockScrolls.getOrDefault(key, 0);
						int rowsShown = Math.min(7, found.size());
						int startIndex = Math.min(scroll, Math.max(0, found.size() - rowsShown));
						int entryIndex = startIndex + i;
						if (entryIndex >= 0 && entryIndex < found.size()) {
							blockList.toggle(found.get(entryIndex).id());
							if (entry.module() != null) {
								entry.module().onSettingsChanged();
							}
							ConfigManager.save();
							playClick();
						}
						return;
					}
				}
				// Клик по полю поиска — он и так принимает ввод, ничего не меняем
				Hitbox searchBox = new Hitbox(box.x() + 4, box.y() + headerH, box.width() - 8, 12);
				if (searchBox.contains(mouseX, mouseYOfSettingClick)) {
					return;
				}
				// Клик мимо строк (по заголовку) — свернуть
				focusedBlockList = null;
				playClick();
				return;
			}

			// Закрыт: заголовок раскрывает список
			focusedBlockList = key;
			blockOpenedAt.put(key, Util.getMillis());
			playClick();
			return;
		}

		if (setting instanceof ElementListSetting elementList && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			// Клик по строке варианта — переключить выбор этого элемента
			List<Hitbox> rows = elementRowBoxes(elementList, box);
			for (int i = 0; i < rows.size(); i++) {
				if (rows.get(i).contains(mouseX, mouseYOfSettingClick)) {
					elementList.toggle(elementList.getElements().get(i).id());
					if (entry.module() != null) {
						entry.module().onSettingsChanged();
					}
					ConfigManager.save();
					playClick();
					return;
				}
			}
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

	/**
	 * Настройка изменилась — сообщаем модулю и сохраняем конфиг.
	 * Звук не играем: этот путь используют и щелчки колесом, а их за секунду
	 * бывает десятки.
	 */
	private void applySettingChange(Module module) {
		if (module != null) {
			module.onSettingsChanged();
		}
		ConfigManager.save();
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
		int direction = (int) Math.signum(scrollY);

		// Колесо над строкой настройки меняет значение на шаг: слайдером в такое
		// поле попадать мышью неудобно, а «±1» нужен постоянно (например, чтобы
		// поймать толщину обводки в 3 пикселя, а не 2 или 4)
		if (direction != 0 && focusedBlockList != null) {
			// Раскрытый список блоков прокручивается колесом
			String key = focusedBlockList;
			LayoutEntry owner = null;
			for (LayoutEntry entry : buildLayout()) {
				if (entry.kind() == Kind.SETTING && (entry.module() == null ? "" : entry.module().getId() + ":")
						.concat(entry.setting().getId()).equals(key)) {
					owner = entry;
					break;
				}
			}
			if (owner != null && owner.box().contains(mouseX, mouseY)
					&& owner.setting() instanceof BlockListSetting) {
				List<com.dreamcast.client.settings.BlockListSetting.BlockEntry> found =
						BlockListSetting.search(blockSearches.getOrDefault(key, ""));
				int rowsShown = Math.min(7, found.size());
				int max = Math.max(0, found.size() - rowsShown);
				int scroll = blockScrolls.getOrDefault(key, 0);
				blockScrolls.put(key, Math.max(0, Math.min(max, scroll - direction)));
				return true;
			}
		}

		// Колесо всегда прокручивает список. Значения меняются только кликом и
		// перетаскиванием слайдера — прокрутка больше не портит настройку под мышью.
		int listHeight = panelHeight() - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;
		int maxScroll = Math.max(0, contentHeight() - listHeight);
		scrollTarget = clamp(scrollTarget - (float) scrollY * 16.0f, -maxScroll, 0);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// Поиск внутри раскрытого списка блоков
		if (focusedBlockList != null && focusedSetting == null) {
			if (event.isAllowedChatCharacter()) {
				String query = blockSearches.getOrDefault(focusedBlockList, "");
				if (query.length() < 40) {
					blockSearches.put(focusedBlockList, query + event.codepointAsString());
					blockScrolls.put(focusedBlockList, 0);
				}
			}
			return true;
		}

		if (searchFocused) {
			if (event.isAllowedChatCharacter() && searchQuery.length() < 40) {
				searchQuery += event.codepointAsString();
				scrollTarget = 0;
				scroll = 0;
				contentSwitchAt = Util.getMillis();
			}
			return true;
		}

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
		if (focusedBlockList != null && focusedSetting == null) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_BACKSPACE -> {
					String query = blockSearches.getOrDefault(focusedBlockList, "");
					if (!query.isEmpty()) {
						blockSearches.put(focusedBlockList, query.substring(0, query.length() - 1));
						blockScrolls.put(focusedBlockList, 0);
					}
				}
				case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> focusedBlockList = null;
				default -> {
				}
			}
			return true;
		}

		if (searchFocused) {
			switch (event.key()) {
				case GLFW.GLFW_KEY_BACKSPACE -> {
					if (!searchQuery.isEmpty()) {
						searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
						contentSwitchAt = Util.getMillis();
					}
				}
				case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> searchFocused = false;
				default -> {
				}
			}
			return true;
		}

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
		if (closing) {
			// Повторный вызов (после доигранной анимации) — закрываемся по-настоящему
			if (!closeDispatched) {
				closeDispatched = true;
				closeNow();
			}
			return;
		}
		clearSettingFocus();
		bindingModule = null;
		ConfigManager.save();
		// Доигрываем анимацию закрытия, реальный выход — в updateAnimations
		closing = true;
	}

	private void closeNow() {
		if (parent != null && this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		} else {
			super.onClose();
		}
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

		for (Module module : modulesShown()) {
			layout.add(new LayoutEntry(new Hitbox(listX, rowY, listWidth, MODULE_ROW_HEIGHT), Kind.MODULE, null, module, null));
			rowY += MODULE_ROW_HEIGHT;

			if (module == expanded || module == closingExpanded) {
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

	private int settingHeight(Setting<?> setting) {
		if (setting instanceof ElementListSetting elementList) {
			return elementList.getElements().size() * 12 + 8;
		}
		if (setting instanceof BlockListSetting) {
			// Раскрытый список занимает и высоту под поиск, и строки
			return TEXT_ROW_HEIGHT + (isBlockListOpen(setting) ? 14 + 7 * 12 : 0);
		}
		if (setting instanceof IntSetting) {
			return SLIDER_ROW_HEIGHT;
		}
		if (setting instanceof StringSetting || setting instanceof ColorSetting || setting instanceof ButtonSetting
				|| setting instanceof ModeSetting) {
			return TEXT_ROW_HEIGHT;
		}
		return TOGGLE_ROW_HEIGHT;
	}

	private boolean isBlockListOpen(Setting<?> setting) {
		if (focusedBlockList == null) {
			return false;
		}
		// Ключ фокуса — «ownerId:settingId»; сверяем хвост
		String suffix = ":" + setting.getId();
		return focusedBlockList.endsWith(suffix);
	}

	private int contentHeight() {
		int height = 0;
		for (Module module : modulesShown()) {
			height += MODULE_ROW_HEIGHT;
			if (module == expanded || module == closingExpanded) {
				for (Setting<?> setting : module.getSettings()) {
					height += settingHeight(setting);
				}
			}
		}
		return height;
	}

	private void toggleSettings(Module module) {
		if (expanded == module) {
			closingExpanded = module;
			expanded = null;
		} else {
			if (expanded != null) {
				closingExpanded = expanded;
			}
			expanded = module;
		}
	}

	private void collapseSettings() {
		if (expanded != null) {
			closingExpanded = expanded;
			expanded = null;
		}
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
}

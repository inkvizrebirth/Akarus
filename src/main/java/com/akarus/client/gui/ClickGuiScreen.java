package com.akarus.client.gui;

import com.akarus.client.AkarusClient;
import com.akarus.client.config.ConfigManager;
import com.akarus.client.module.Module;
import com.akarus.client.module.ModuleCategory;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.settings.BooleanSetting;
import com.akarus.client.settings.IntSetting;
import com.akarus.client.settings.Setting;
import com.akarus.client.settings.StringSetting;
import com.akarus.client.util.RenderUtils;
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
	private static final int GUI_HEIGHT = 300;
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

	private Setting<?> focusedSetting;
	private Module focusedModule;
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
			guiX = (this.width - GUI_WIDTH) / 2.0f;
			guiY = (this.height - GUI_HEIGHT) / 2.0f;
			positioned = true;
		}
		clampPanel();
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

		// Небольшое «выезжание» окна при открытии
		graphics.pose().pushMatrix();
		float scale = 0.96f + 0.04f * openAnimation;
		float centerX = x + GUI_WIDTH / 2.0f;
		float centerY = y + GUI_HEIGHT / 2.0f;
		graphics.pose().translate(centerX, centerY);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-centerX, -centerY);

		RenderUtils.drawSoftShadow(graphics, x, y, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, SHADOW_LAYERS);
		drawPanel(graphics, x, y, accent);
		drawCategories(graphics, x, y, mouseX, mouseY);
		drawModules(graphics, x, y, mouseX, mouseY, accent);
		drawHint(graphics, x, y);

		// Волна по клику в любом месте панели
		drawRipples(graphics, new Hitbox(x, y, GUI_WIDTH, GUI_HEIGHT), accent);

		graphics.pose().popMatrix();
	}

	private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int accent) {
		// Рамка и двухтоновый фон: сверху чуть светлее, снизу — почти чёрный
		RenderUtils.fillRounded(graphics, x, y, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, PANEL_OUTLINE);
		RenderUtils.fillRounded(graphics, x + 1, y + 1, GUI_WIDTH - 2, GUI_HEIGHT - 2, PANEL_RADIUS - 1, PANEL_TOP);
		RenderUtils.fillRoundedBottom(graphics, x + 1, y + (GUI_HEIGHT - 2) / 2, GUI_WIDTH - 2, (GUI_HEIGHT - 2) / 2,
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
			graphics.text(font, category.getDisplayName(), box.x() + 11,
					box.y() + (box.height() - font.lineHeight) / 2 + 1, textColor, false);

			drawRipples(graphics, box, accent);

			rowY += CATEGORY_ROW_HEIGHT + CATEGORY_GAP;
		}
	}

	private void drawModules(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, int accent) {
		int listX = x + PADDING + CATEGORY_WIDTH + PADDING;
		int listY = y + HEADER_HEIGHT + PADDING;
		int listWidth = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3;
		int listHeight = GUI_HEIGHT - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;

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
				drawSettingRow(graphics, entry.setting(), entry.box(), accent, mouseX, mouseY);
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

		int textX = box.x() + 9;
		graphics.text(font, module.getName(), textX, box.y() + 6,
				module.isEnabled() ? TEXT_PRIMARY : TEXT_SECONDARY, false);
		graphics.text(font, RenderUtils.clamp(font, module.getDescription(), box.width() - 72), textX, box.y() + 20, TEXT_DIM, false);

		// Тумблер справа
		RenderUtils.drawToggle(graphics, box.x() + box.width() - TOGGLE_WIDTH - 9,
				box.y() + (box.height() - TOGGLE_HEIGHT) / 2, TOGGLE_WIDTH, TOGGLE_HEIGHT, toggle, accent);

		// Отметка, что у модуля есть настройки
		if (!module.getSettings().isEmpty()) {
			String mark = module == expanded ? "-" : "+";
			graphics.text(font, mark, box.x() + box.width() - TOGGLE_WIDTH - 22, box.y() + 6, TEXT_DIM, false);
		}
	}

	// ------------------------------------------------------------------
	// Настройки: переключатель / слайдер / текстовое поле
	// ------------------------------------------------------------------

	private void drawSettingRow(GuiGraphicsExtractor graphics, Setting<?> setting, Hitbox box, int accent, int mouseX, int mouseY) {
		if (setting instanceof IntSetting intSetting) {
			drawSliderRow(graphics, intSetting, box, accent, mouseX, mouseY);
		} else if (setting instanceof StringSetting stringSetting) {
			drawTextRow(graphics, stringSetting, box, accent, mouseX, mouseY);
		} else if (setting instanceof BooleanSetting booleanSetting) {
			drawToggleRow(graphics, booleanSetting, box, accent, mouseX, mouseY);
		}
	}

	private void drawToggleRow(GuiGraphicsExtractor graphics, BooleanSetting setting, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		float hover = hoverProgress("setting:" + setting.getId(), box.contains(mouseX, mouseY));
		float toggle = toggleProgress(setting);

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				0x10FFFFFF, RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		drawRipples(graphics, box, accent);

		graphics.text(font, RenderUtils.clamp(font, setting.getName(), box.width() - 50), box.x() + 9,
				box.y() + (box.height() - 2 - font.lineHeight) / 2 + 1,
				setting.isEnabled() ? TEXT_SECONDARY : TEXT_DIM, false);

		RenderUtils.drawToggle(graphics, box.x() + box.width() - SETTING_TOGGLE_WIDTH - 8,
				box.y() + (box.height() - 2 - SETTING_TOGGLE_HEIGHT) / 2, SETTING_TOGGLE_WIDTH, SETTING_TOGGLE_HEIGHT,
				toggle, accent);
	}

	private void drawSliderRow(GuiGraphicsExtractor graphics, IntSetting setting, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		boolean active = setting == draggingSetting;
		float hover = hoverProgress("setting:" + setting.getId(), box.contains(mouseX, mouseY) || active);

		RenderUtils.fillRoundedBorder(graphics, box.x(), box.y(), box.width(), box.height() - 2, 4,
				RenderUtils.mix(0x10FFFFFF, accent, hover * 0.35f),
				RenderUtils.mix(0x80000000, 0x14FFFFFF, hover * 0.6f));

		int textY = box.y() + 4;
		graphics.text(font, setting.getName(), box.x() + 9, textY, TEXT_SECONDARY, false);

		String value = String.valueOf(setting.get());
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

	private void drawTextRow(GuiGraphicsExtractor graphics, StringSetting setting, Hitbox box, int accent, int mouseX, int mouseY) {
		Font font = this.font;
		boolean focused = setting == focusedSetting;
		float hover = hoverProgress("setting:" + setting.getId(), box.contains(mouseX, mouseY) || focused);

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
		String hint = "ЛКМ — включить модуль   •   ПКМ — настройки   •   колесо — прокрутка   •   шапку можно перетаскивать";
		graphics.text(this.font, RenderUtils.clamp(this.font, hint, GUI_WIDTH - PADDING * 2),
				x + PADDING, y + GUI_HEIGHT - PADDING - this.font.lineHeight + 1, TEXT_DIM, false);
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

	private float toggleProgress(BooleanSetting setting) {
		String key = "setting:" + setting.getId();
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

		boolean insidePanel = isInside(mouseX, mouseY, guiX, guiY, GUI_WIDTH, GUI_HEIGHT);
		Hitbox rippleBounds = insidePanel ? new Hitbox(Math.round(guiX), Math.round(guiY), GUI_WIDTH, GUI_HEIGHT) : null;

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

		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			return super.mouseClicked(event, doubleClick);
		}

		int listX = Math.round(guiX) + PADDING + CATEGORY_WIDTH + PADDING;
		int listY = Math.round(guiY) + HEADER_HEIGHT + PADDING;
		int listWidth = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3;
		int listHeight = GUI_HEIGHT - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;

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
					scrollTarget = 0;
					scroll = 0;
					playClick();
				}
				case MODULE -> {
					if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
						entry.module().toggle();
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

		if (setting instanceof BooleanSetting booleanSetting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			booleanSetting.toggle();
			entry.module().onSettingsChanged();
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
		if (focusedModule != null && focusedDirty) {
			focusedModule.onSettingsChanged();
			ConfigManager.save();
		}
		focusedSetting = null;
		focusedModule = null;
		focusedDirty = false;
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
		int listHeight = GUI_HEIGHT - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT;
		int maxScroll = Math.max(0, contentHeight() - listHeight);
		scrollTarget = clamp(scrollTarget - (float) scrollY * 16.0f, -maxScroll, 0);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (focusedSetting instanceof StringSetting stringSetting && event.isAllowedChatCharacter()) {
			stringSetting.set(stringSetting.get() + event.codepointAsString());
			focusedDirty = true;
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
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
		if (setting instanceof StringSetting) {
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

	private void clampPanel() {
		guiX = clamp(guiX, -GUI_WIDTH + 60, this.width - 60);
		guiY = clamp(guiY, 0, Math.max(0, this.height - 40));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static boolean isInside(double px, double py, float x, float y, int width, int height) {
		return px >= x && px <= x + width && py >= y && py <= y + height;
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

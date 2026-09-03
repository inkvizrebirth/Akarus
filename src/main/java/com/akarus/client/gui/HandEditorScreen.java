package com.akarus.client.gui;

import com.akarus.client.config.ConfigManager;
import com.akarus.client.module.ModuleManager;
import com.akarus.client.module.impl.ViewModelModule;
import com.akarus.client.util.RenderUtils;
import com.akarus.client.viewmodel.ViewModelProfile;
import com.akarus.client.viewmodel.ViewModelProfile.Parameter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;


/**
 * Редактор раскладки рук.
 *
 * Открывается кнопкой «Настроить» в настройках модулей «Обводка рук» и «ViewModel».
 * Рука остаётся на экране — её рисует сама игра, поэтому все изменения видно сразу.
 *
 * Управление:
 * <ul>
 *   <li>ЛКМ или ПКМ и потянуть — двигать руку;</li>
 *   <li>колесо над рукой — размер;</li>
 *   <li>колесо над списком — значение выбранного параметра;</li>
 *   <li>клик по строке — выбрать параметр;</li>
 *   <li>ESC — сохранить и вернуться в ClickGUI.</li>
 * </ul>
 *
 * Про скрытие интерфейса: в 26.2 флаг «скрыть HUD» (он же {@code isHudHidden}
 * в состоянии интерфейса) заодно отключает отрисовку рук от первого лица —
 * {@code GameRenderer.renderItemInHand} выходит из метода раньше времени.
 * Прячь мы интерфейс, рука бы пропала вместе с ним, так что редактор его не трогает.
 */
public class HandEditorScreen extends Screen {

	// --- Геометрия панели ---
	private static final int PANEL_WIDTH = 162;
	private static final int ROW_HEIGHT = 17;
	private static final int HEADER_HEIGHT = 22;
	private static final int BUTTON_HEIGHT = 15;
	private static final int BUTTON_WIDTH = 71;
	private static final int PADDING = 6;
	private static final int RADIUS = 8;

	// --- Цвета в том же духе, что и ClickGUI ---
	private static final int PANEL_OUTLINE = 0xFF1C1C20;
	private static final int PANEL_FILL = 0xF0101014;
	private static final int ROW_FILL = 0x8A000000;
	private static final int ROW_BORDER = 0x14FFFFFF;
	private static final int TEXT_PRIMARY = 0xFFF6F6F8;
	private static final int TEXT_SECONDARY = 0xFFA6A6B2;
	private static final int TEXT_DIM = 0xFF6B6B78;
	private static final int ACCENT = 0xFF8A6CFF;

	/** Сколько единиц модели приходится на один пиксель при перетаскивании. */
	private static final float DRAG_SCALE = 1.0f / 260.0f;

	private ViewModelModule module;
	private Parameter selected = Parameter.SCALE;

	private boolean dragging;
	private float dragStartX;
	private float dragStartY;
	private float dragStartOffsetX;
	private float dragStartOffsetY;

	private int panelX;
	private int panelY;
	private int panelHeight;

	public HandEditorScreen() {
		super(Component.literal("Раскладка рук"));
	}

	@Override
	protected void init() {
		super.init();

		this.module = ModuleManager.find(ViewModelModule.class);

		layout();
	}

	@Override
	public void resize(int width, int height) {
		super.resize(width, height);
		layout();
	}

	private void layout() {
		int rows = Parameter.values().length;
		this.panelHeight = HEADER_HEIGHT + BUTTON_HEIGHT + PADDING + rows * ROW_HEIGHT + PADDING;
		this.panelX = this.width - PANEL_WIDTH - 10;
		this.panelY = Math.max(10, (this.height - this.panelHeight) / 2);
	}

	/** Редактор не должен ставить игру на паузу: иначе не видно анимацию взмаха. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Фон не рисуем вовсе — нужен живой мир с рукой, а не размытая картинка. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.minecraft.gui.hud.extractDeferredSubtitles();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		Font font = this.font;

		drawPanel(graphics, mouseX, mouseY);

		String hint = "ЛКМ/ПКМ — тащить руку   •   колесо — размер или значение   •   ↑/↓ (с Ctrl ×5) — точно"
				+ "   •   ESC — сохранить и выйти";
		graphics.text(font, RenderUtils.clamp(font, hint, this.width - 20), 10, this.height - 14, TEXT_DIM, true);

		if (this.module == null) {
			graphics.text(font, "Модуль ViewModel не найден", 10, 10, 0xFFFF5C7A, true);
		}
	}

	private void drawPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Font font = this.font;
		int x = this.panelX;
		int y = this.panelY;

		RenderUtils.drawSoftShadow(graphics, x, y, PANEL_WIDTH, this.panelHeight, RADIUS, 4);
		RenderUtils.fillRounded(graphics, x, y, PANEL_WIDTH, this.panelHeight, RADIUS, PANEL_OUTLINE);
		RenderUtils.fillRounded(graphics, x + 1, y + 1, PANEL_WIDTH - 2, this.panelHeight - 2, RADIUS - 1, PANEL_FILL);

		graphics.text(font, "Раскладка рук", x + PADDING, y + (HEADER_HEIGHT - font.lineHeight) / 2, TEXT_PRIMARY, false);

		int buttonY = y + HEADER_HEIGHT;
		drawButton(graphics, x + PADDING, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "Сохранить",
				isInside(mouseX, mouseY, x + PADDING, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT));
		drawButton(graphics, x + PADDING + BUTTON_WIDTH + 4, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT, "Сбросить",
				isInside(mouseX, mouseY, x + PADDING + BUTTON_WIDTH + 4, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT));

		int rowY = buttonY + BUTTON_HEIGHT + PADDING;
		ViewModelProfile profile = currentProfile();
		if (profile == null) {
			// Модуль ViewModel не зарегистрирован (инициализация не прошла) — рисуем
			// значения по умолчанию, иначе первый же кадр редактора падал с NPE,
			// так и не успев показать сообщение об ошибке ниже
			profile = ViewModelProfile.createDefault();
		}
		for (Parameter parameter : Parameter.values()) {
			boolean isSelected = parameter == this.selected;
			boolean hovered = isInside(mouseX, mouseY, x + 4, rowY, PANEL_WIDTH - 8, ROW_HEIGHT - 2);

			int border = isSelected ? ACCENT : ROW_BORDER;
			int fill = isSelected ? RenderUtils.withAlpha(ACCENT, 0.16f) : RenderUtils.mix(ROW_FILL, 0x14FFFFFF, hovered ? 0.6f : 0.0f);
			RenderUtils.fillRoundedBorder(graphics, x + 4, rowY, PANEL_WIDTH - 8, ROW_HEIGHT - 2, 4, border, fill);

			if (isSelected) {
				graphics.fill(x + 4, rowY + 4, x + 6, rowY + ROW_HEIGHT - 6, ACCENT);
			}

			int textY = rowY + (ROW_HEIGHT - 2 - font.lineHeight) / 2 + 1;
			graphics.text(font, parameter.getDisplayName(), x + 12, textY,
					isSelected ? TEXT_PRIMARY : TEXT_SECONDARY, false);

			String value = parameter.format(profile.get(parameter));
			graphics.text(font, value, x + PANEL_WIDTH - 10 - font.width(value), textY,
					isSelected ? TEXT_PRIMARY : TEXT_DIM, false);

			rowY += ROW_HEIGHT;
		}
	}

	private void drawButton(GuiGraphicsExtractor graphics, int x, int y, int width, int height, String label, boolean hovered) {
		Font font = this.font;
		RenderUtils.fillRoundedBorder(graphics, x, y, width, height, 4,
				RenderUtils.mix(ROW_BORDER, ACCENT, hovered ? 0.6f : 0.0f),
				RenderUtils.mix(ROW_FILL, 0x1AFFFFFF, hovered ? 0.8f : 0.0f));
		graphics.text(font, label, x + (width - font.width(label)) / 2,
				y + (height - font.lineHeight) / 2 + 1, hovered ? TEXT_PRIMARY : TEXT_SECONDARY, false);
	}

	// ------------------------------------------------------------------
	// Ввод
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.button();

		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			return super.mouseClicked(event, doubleClick);
		}

		int buttonY = this.panelY + HEADER_HEIGHT;
		if (isInside(mouseX, mouseY, this.panelX + PADDING, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)) {
			save();
			playClick();
			return true;
		}
		if (isInside(mouseX, mouseY, this.panelX + PADDING + BUTTON_WIDTH + 4, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT)) {
			reset();
			playClick();
			return true;
		}

		// Клик по строке — выбираем параметр для колеса
		Parameter clicked = parameterAt(mouseX, mouseY);
		if (clicked != null) {
			this.selected = clicked;
			playClick();
			return true;
		}

		// Всё остальное пространство — перетаскивание руки
		if (this.module != null) {
			this.dragging = true;
			this.dragStartX = (float) mouseX;
			this.dragStartY = (float) mouseY;
			ViewModelProfile profile = currentProfile();
			this.dragStartOffsetX = profile.get(Parameter.X);
			this.dragStartOffsetY = profile.get(Parameter.Y);
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (this.dragging && this.module != null) {
			float movedX = (float) (event.x() - this.dragStartX) * DRAG_SCALE;
			float movedY = (float) (event.y() - this.dragStartY) * DRAG_SCALE;
			this.module.set(Parameter.X, this.dragStartOffsetX + movedX);
			// Вниз по экрану — это «ближе к игроку» по Y от первого лица, поэтому минус
			this.module.set(Parameter.Y, this.dragStartOffsetY - movedY);
			return true;
		}
		return super.mouseDragged(event, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.dragging) {
			this.dragging = false;
			save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.module == null) {
			return true;
		}

		float amount = (float) Math.signum(scrollY);
		if (amount == 0.0f) {
			return true;
		}

		// Колесо над панелью крутит выбранный параметр, над миром — размер руки
		if (isInside(mouseX, mouseY, this.panelX, this.panelY, PANEL_WIDTH, this.panelHeight)) {
			this.module.change(this.selected, amount);
		} else {
			this.module.change(Parameter.SCALE, amount);
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			save();
			this.minecraft.gui.setScreen(new ClickGuiScreen());
			return true;
		}
		// Стрелками удобно доводить значение, когда руками уже не поймать
		if (this.module != null && (event.key() == GLFW.GLFW_KEY_UP || event.key() == GLFW.GLFW_KEY_DOWN)) {
			float step = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 ? 5.0f : 1.0f;
			this.module.change(this.selected, (event.key() == GLFW.GLFW_KEY_UP ? step : -step));
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_R) {
			reset();
			return true;
		}
		return super.keyPressed(event);
	}

	/** ESC без нажатой клавиши (например, из меню) тоже должен сохранить раскладку. */
	@Override
	public void onClose() {
		save();
		super.onClose();
	}

	// ------------------------------------------------------------------
	// Действия
	// ------------------------------------------------------------------

	private void save() {
		if (this.module != null) {
			this.module.saveProfile();
			ConfigManager.save();
		}
	}

	private void reset() {
		if (this.module != null) {
			this.module.resetProfile();
		}
	}

	private ViewModelProfile currentProfile() {
		return this.module == null ? null : this.module.getProfile();
	}

	private Parameter parameterAt(double mouseX, double mouseY) {
		int rowY = this.panelY + HEADER_HEIGHT + BUTTON_HEIGHT + PADDING;
		for (Parameter parameter : Parameter.values()) {
			if (isInside(mouseX, mouseY, this.panelX + 4, rowY, PANEL_WIDTH - 8, ROW_HEIGHT - 2)) {
				return parameter;
			}
			rowY += ROW_HEIGHT;
		}
		return null;
	}

	private static boolean isInside(double px, double py, int x, int y, int width, int height) {
		return px >= x && px < x + width && py >= y && py < y + height;
	}

	private void playClick() {
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
	}
}

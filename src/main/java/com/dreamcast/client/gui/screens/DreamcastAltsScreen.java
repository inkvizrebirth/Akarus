package com.dreamcast.client.gui.screens;

import com.dreamcast.client.util.AltsManager;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Alt Manager: выбор оффлайн-аккаунта.
 *
 * Широкий список альтов без растянутой карточки. Поле ввода снизу: вписать
 * ник и «Добавить», либо «Рандом» — клиент сам придумает ник, добавит и
 * переключится.
 */
public class DreamcastAltsScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFF7C6CFF;
	private static final int PANEL_WIDTH = 620;
	private static final int ROW_HEIGHT = 26;
	private static final int ROW_GAP = 3;

	private static final class AltRow {
		final String name;
		float hover;
		float appear;
		int y;

		AltRow(String name) {
			this.name = name;
		}
	}

	private final Screen parent;
	private final List<AltRow> rows = new ArrayList<>();
	private final TextField nickField = new TextField();
	private int selected = -1;
	private boolean confirmDelete;
	private int listHeight = 1;
	private int scroll;

	public DreamcastAltsScreen(Screen parent) {
		super("Аккаунты");
		this.parent = parent;
		nickField.hint = "ник (a-z, 0-9, _)";
		nickField.maxLength = 16;
	}

	@Override
	protected void init() {
		super.init();
		reload();
	}

	private void reload() {
		rows.clear();
		selected = -1;
		scroll = 0;
		confirmDelete = false;
		for (AltsManager.Alt alt : AltsManager.list()) {
			rows.add(new AltRow(alt.name()));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);

		RenderUtils.textBold(graphics, font, "Аккаунты", 18, 16, 0xFFF4F4FA);
		RenderUtils.textFlat(graphics, font, "сейчас в игре: " + AltsManager.activeName(),
				18, 16 + font.lineHeight + 3, 0xFF80808C);

		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int panelY = 44;
		listHeight = Math.max(1, height - panelY - 66);
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		int listWidth = panelWidth - 20;
		int listX = 28;

		drawGlassPanel(graphics, 18, panelY, panelWidth, listHeight, 12, 1.0f, ACCENT);

		if (rows.isEmpty()) {
			RenderUtils.text(graphics, font, "Альтов пока нет", listX + 4, panelY + 12, 0xFFE8E8F0);
			RenderUtils.text(graphics, font, "добавь ниже или выбери «Рандом»", listX + 4, panelY + 24, 0xFF80808C);
		} else {
			graphics.enableScissor(listX - 2, panelY + 4, listX + listWidth + 4, panelY + listHeight - 4);
			int y = panelY + 8;
			int index = 0;
			for (AltRow row : rows) {
				row.y = y;
				if (index >= scroll && index < scroll + visible) {
					drawAltRow(graphics, row, index, listX, y, listWidth, mouseX, mouseY);
					y += ROW_HEIGHT + ROW_GAP;
				}
				index++;
			}
			graphics.disableScissor();
			drawScrollbar(graphics, listX + listWidth + 3, panelY + 8, listHeight - 16, scroll, visible, rows.size(), ACCENT);
		}

		// Поле ввода + чипы
		nickField.width = 160;
		nickField.x = 18;
		nickField.y = height - 58;
		nickField.draw(graphics, ACCENT, mouseX, mouseY);

		chips.clear();
		if (confirmDelete && selected >= 0) {
			chips.add(chip("удалить альт", this::deleteSelected, true));
			chips.add(chip("отмена", () -> confirmDelete = false));
		} else {
			chips.add(chip("Войти", () -> loginSelected()));
			chips.add(chip("Добавить", this::addFromField));
			chips.add(chip("Рандом", this::addRandom));
			Chip delete = chip("Удалить", () -> confirmDelete = true);
			delete.enabled = selected >= 0;
			delete.danger = true;
			chips.add(delete);
		}
		chips.add(chip("Назад", this::onClose));
		drawChipRow(graphics, width / 2 + 40, height - 36, 20, 5, ACCENT, mouseX, mouseY);

		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	private void drawCurrentCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		String name = AltsManager.activeName();
		boolean isActiveKnown = !name.isEmpty();

		// Аватар: крупная буква на градиентной плашке
		int avatarSize = 84;
		int avatarX = x + (w - avatarSize) / 2;
		int avatarY = y + 12;
		RenderUtils.drawSoftShadow(graphics, avatarX - 2, avatarY - 2, avatarSize + 4, avatarSize + 4, 8, 3);
		for (int i = 0; i < avatarSize; i++) {
			float t = i / (float) avatarSize;
			int top = RenderUtils.mix(0xFF7C6CFF, 0xFF45E3FF, t);
			int bottom = RenderUtils.mix(0xFF5A4DD0, 0xFF2FB8D8, t);
			graphics.fillGradient(avatarX, avatarY + i, avatarX + avatarSize, avatarY + i + 1, top, bottom);
		}
		String letter = isActiveKnown ? name.substring(0, 1).toUpperCase() : "?";
		RenderUtils.textBold(graphics, font, letter,
				avatarX + avatarSize / 2, avatarY + avatarSize / 2 - 14, 0xFFF4F4FA);

		String shown = RenderUtils.clamp(font, isActiveKnown ? name : "не выбран", w - 16);
		RenderUtils.textCentered(graphics, font, shown, x + w / 2, avatarY + avatarSize + 8, 0xFFF4F4FA, false);
		RenderUtils.textCentered(graphics, font, "offline-аккаунт", x + w / 2,
				avatarY + avatarSize + 10 + font.lineHeight, 0xFF6B6B78, false);

		// Подсказка снизу
		RenderUtils.textCentered(graphics, font, "переключение применится", x + w / 2, y + h - 26, 0xFF54545E, false);
		RenderUtils.textCentered(graphics, font, "к следующему подключению", x + w / 2, y + h - 16, 0xFF54545E, false);
	}

	private void drawAltRow(GuiGraphicsExtractor graphics, AltRow row, int index, int x, int y, int w,
	                        int mouseX, int mouseY) {
		boolean selectedRow = index == selected;
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;
		row.appear = ease(row.appear, 1.0f, 0.2f);
		float delay = Math.min(0.5f, index * 0.06f);
		float appear = Math.max(0.0f, Math.min(1.0f, (row.appear - delay) / Math.max(0.05f, 1.0f - delay)));
		int slideX = Math.round((1.0f - appear) * 8.0f);
		row.hover = ease(row.hover, inside ? 1.0f : 0.0f, 0.22);

		int background = selectedRow
				? RenderUtils.mix(0xCC101015, RenderUtils.withAlpha(ACCENT, 0xFF), 0.20f + 0.08f * row.hover)
				: RenderUtils.mix(0xA0101013, 0x16FFFFFF, row.hover * 0.5f);
		int border = selectedRow
				? RenderUtils.withAlpha(ACCENT, 0.55f + 0.35f * row.hover)
				: RenderUtils.mix(0x10FFFFFF, ACCENT, row.hover * 0.25f);
		RenderUtils.fillRoundedBorder(graphics, x + slideX, y, w, ROW_HEIGHT, 6,
				RenderUtils.withAlpha(border, appear), RenderUtils.withAlpha(background, appear));

		// Мини-аватар: буква
		int ax = x + slideX + 5;
		int ay = y + (ROW_HEIGHT - 16) / 2;
		RenderUtils.fillRounded(graphics, ax, ay, 16, 16, 4, RenderUtils.withAlpha(ACCENT, 0.35f));
		RenderUtils.textFlat(graphics, font, row.name.substring(0, 1).toUpperCase(), ax + 6, ay + 4, 0xFFF4F4FA);
		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, row.name, w - 40),
				ax + 22, y + (ROW_HEIGHT - font.lineHeight) / 2,
				RenderUtils.withAlpha(selectedRow ? 0xFFFFFFFF : 0xFFE8E8F0, appear));
	}

	// ------------------------------------------------------------------
	// Ввод
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		double mx = event.x();
		double my = event.y();

		if (nickField.contains(mx, my)) {
			nickField.focused = true;
			return true;
		}
		nickField.focused = false;

		if (clickChips(event)) {
			return true;
		}

		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int listWidth = panelWidth - 20;
		int listX = 28;
		int panelY = 44;
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		if (mx >= listX && mx < listX + listWidth && my >= panelY + 4 && my < panelY + listHeight - 4) {
			int index = scroll + (int) ((my - panelY - 8) / (ROW_HEIGHT + ROW_GAP));
			if (index >= scroll && index < Math.min(rows.size(), scroll + visible)) {
				selected = index;
				confirmDelete = false;
				playClick();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (nickField.focused) {
			if (event.isAllowedChatCharacter() && nickField.value.length() < nickField.maxLength) {
				nickField.value += event.codepointAsString();
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (nickField.focused) {
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
				nickField.backspace();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER) {
				addFromField();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				nickField.focused = false;
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible), scroll - (int) Math.signum(scrollY)));
		return true;
	}

	// ------------------------------------------------------------------
	// Действия
	// ------------------------------------------------------------------

	private void loginSelected() {
		if (selected < 0 || selected >= rows.size()) {
			return;
		}
		String name = rows.get(selected).name;
		if (AltsManager.loginAs(name)) {
			Notifications.ok("Аккаунты", "Игруем как " + name);
			playClick();
		} else {
			Notifications.error("Аккаунты", "Не удалось переключиться");
		}
	}

	private void addFromField() {
		String name = nickField.value.trim();
		if (!name.matches("[A-Za-z0-9_]{1,16}")) {
			Notifications.warn("Аккаунты", "Ник: латиница, цифры и _, до 16 символов");
			return;
		}
		AltsManager.add(name);
		nickField.value = "";
		reload();
		Notifications.ok("Аккаунты", "Добавлен " + name);
	}

	private void addRandom() {
		String name = AltsManager.randomNick();
		AltsManager.add(name);
		AltsManager.loginAs(name);
		reload();
		Notifications.ok("Аккаунты", "Мгновенный вход как " + name);
	}

	private void deleteSelected() {
		if (selected >= 0 && selected < rows.size()) {
			AltsManager.remove(selected);
		}
		confirmDelete = false;
		reload();
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}

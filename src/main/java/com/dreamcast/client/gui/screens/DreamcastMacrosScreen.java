package com.dreamcast.client.gui.screens;

import com.dreamcast.client.module.impl.MacroModule;
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
 * Редактор макросов: команда + свой бинд на каждую.
 *
 * Число команд не ограничено. Добавление: вписать команду
 * (с «/» или без) и нажать «Добавить». Бинд меняется кликом по строке —
 * следующий нажатый ключ становится биндом (Esc — сбросить).
 */
public class DreamcastMacrosScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFFFFC66C;
	private static final int PANEL_WIDTH = 460;
	private static final int ROW_HEIGHT = 26;
	private static final int ROW_GAP = 3;

	private static final class Row {
		final String command;
		final int key;
		float hover;
		float appear;
		int x;
		int y;

		Row(String command, int key) {
			this.command = command;
			this.key = key;
		}
	}

	private final Screen parent;
	private final MacroModule module;
	private final List<Row> rows = new ArrayList<>();
	private final TextField commandField = new TextField();
	private int selected = -1;
	private int scroll;
	private int listHeight = 1;
	/** Индекс строки, ждущей клавишу; -1 — не ждём. */
	private int awaitingKey = -1;

	public DreamcastMacrosScreen(Screen parent, MacroModule module) {
		super("Макросы");
		this.parent = parent;
		this.module = module;
		commandField.hint = "/команда или сообщение";
		commandField.maxLength = 100;
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
		rebuild();
	}

	/** Тот же список, но прокрутка и выделение остаются там же. */
	private void reloadKeepingPlace() {
		int keepSelected = selected;
		int keepScroll = scroll;
		rows.clear();
		rebuild();
		selected = keepSelected;
		scroll = keepScroll;
	}

	private void rebuild() {
		for (MacroModule.Macro macro : module.macros()) {
			rows.add(new Row(macro.command(), macro.key()));
		}
	}

	/**
	 * Индекс строки под курсором в видимой области, или -1. Общая логика для
	 * левого и правого клика — иначе легко разойтись с тем, что реально нарисовано.
	 */
	private int rowAtPointer(double mx, double my) {
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int panelX = width / 2 - panelWidth / 2;
		int firstRowY = 46 + 8;
		if (mx < panelX + 8 || mx >= panelX + panelWidth - 8
				|| my < firstRowY || my >= 46 + listHeight - 4) {
			return -1;
		}
		int relativeY = (int) my - firstRowY;
		if (relativeY % (ROW_HEIGHT + ROW_GAP) >= ROW_HEIGHT) {
			return -1; // зазор между строками
		}
		int index = scroll + relativeY / (ROW_HEIGHT + ROW_GAP);
		return index >= 0 && index < visible + scroll && index < rows.size() ? index : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);

		RenderUtils.textCentered(graphics, font, "Макросы", width / 2, 16, 0xFFF4F4FA, false);
		RenderUtils.textFlat(graphics, font, "команда на клавишу · без лимита",
				width / 2 - RenderUtils.width(font, "команда на клавишу · без лимита") / 2,
				16 + font.lineHeight + 3, 0xFF80808C);

		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int panelX = width / 2 - panelWidth / 2;
		int panelY = 46;
		listHeight = Math.max(60, height - panelY - 76);
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));

		drawGlassPanel(graphics, panelX, panelY, panelWidth, listHeight, 12, 1.0f, ACCENT);

		if (rows.isEmpty()) {
			RenderUtils.text(graphics, font, "Макросов нет", panelX + 14, panelY + 14, 0xFFE8E8F0);
			RenderUtils.text(graphics, font, "впиши команду ниже и нажми «Добавить»", panelX + 14, panelY + 26, 0xFF80808C);
		} else {
			graphics.enableScissor(panelX + 2, panelY + 4, panelX + panelWidth - 2, panelY + listHeight - 4);
			int y = panelY + 8;
			int index = 0;
			for (Row row : rows) {
				if (index >= scroll && index < scroll + visible) {
					row.y = y;
					drawMacroRow(graphics, row, index, panelX + 8, y, panelWidth - 16, mouseX, mouseY);
					y += ROW_HEIGHT + ROW_GAP;
				}
				index++;
			}
			graphics.disableScissor();
			drawScrollbar(graphics, panelX + panelWidth + 3, panelY + 8, listHeight - 16,
					scroll, visible, rows.size(), ACCENT);
		}

		// Поле ввода новой команды
		commandField.width = panelWidth - 24;
		commandField.x = panelX + 12;
		commandField.y = panelY + listHeight + 8;
		commandField.draw(graphics, ACCENT, mouseX, mouseY);

		chips.clear();
		chips.add(chip("Добавить", this::addFromField));
		Chip reset = chip("Сбросить бинд", () -> {
			if (selected >= 0) {
				module.setKey(selected, -1);
				reloadKeepingPlace();
				Notifications.ok("Макросы", "Клавиша снята");
			}
		});
		reset.enabled = selected >= 0;
		chips.add(reset);
		chips.add(chip("Удалить", () -> {
			if (selected >= 0) {
				module.remove(selected);
				// После удаления строки список короче: прокрутку и выделение
				// подгоняем, иначе курсор остаётся в «пустоте» за концом.
				selected = Math.min(selected, rows.size() - 2);
				reloadKeepingPlace();
			}
		}, true));
		chips.add(chip("Готово", this::onClose));
		drawChipRow(graphics, width / 2, height - 34, 20, 5, ACCENT, mouseX, mouseY);

		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	private void drawMacroRow(GuiGraphicsExtractor graphics, Row row, int index, int x, int y, int w,
	                          int mouseX, int mouseY) {
		row.x = x;
		boolean selectedRow = index == selected;
		boolean waiting = index == awaitingKey;
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;
		row.appear = ease(row.appear, 1.0f, 0.2f);
		row.hover = ease(row.hover, inside ? 1.0f : 0.0f, 0.22);

		int border = waiting ? 0xFFFF5C7A
				: selectedRow ? RenderUtils.withAlpha(ACCENT, 0.7f)
				: RenderUtils.mix(0x10FFFFFF, ACCENT, row.hover * 0.25f);
		int background = selectedRow
				? RenderUtils.mix(0xCC101015, RenderUtils.withAlpha(ACCENT, 0xFF), 0.18f)
				: 0xA0101013;
		RenderUtils.fillRoundedBorder(graphics, x, y, w, ROW_HEIGHT, 6,
				RenderUtils.withAlpha(border, row.appear), RenderUtils.withAlpha(background, row.appear));

		String command = RenderUtils.clamp(font, row.command, w - 110);
		RenderUtils.textFlat(graphics, font, command, x + 8, y + (ROW_HEIGHT - font.lineHeight) / 2,
				RenderUtils.withAlpha(0xFFE8E8F0, row.appear));

		// Правая часть строки — и есть кнопка бинда: подпись прямо говорит,
		// что от пользователя хотят (раньше там было молчаливое «не задан»,
		// и догадаться о клике можно было только случайно).
		String bindLabel = waiting ? "нажми клавишу…"
				: row.key < 0 ? "клик — задать клавишу" : keyName(row.key);
		String shown = RenderUtils.clamp(font, bindLabel, 86);
		int bindColor = waiting ? 0xFFFF5C7A : row.key < 0 ? 0xFF9A9AA8 : RenderUtils.withAlpha(ACCENT, 0.95f);
		String hint = waiting ? "Esc — отмена"
				: row.key < 0 ? "или правый клик — очистить" : "клик — сменить · правый — убрать";
		RenderUtils.textFlat(graphics, font, RenderUtils.clamp(font, hint, w - 100 - RenderUtils.width(font, shown)),
				x + 8, y + ROW_HEIGHT - 10, RenderUtils.withAlpha(0xFF6B6B78, row.appear));
		RenderUtils.textFlat(graphics, font, shown,
				x + w - 8 - RenderUtils.width(font, shown), y + (ROW_HEIGHT - font.lineHeight) / 2,
				RenderUtils.withAlpha(bindColor, row.appear));

		if (waiting) {
			// «Дыхание» строки в режиме ожидания клавиши
			float pulse = 0.5f + 0.5f * (float) Math.sin(net.minecraft.util.Util.getMillis() / 180.0);
			RenderUtils.fillRoundedBorder(graphics, x, y, w, ROW_HEIGHT, 6,
					RenderUtils.withAlpha(0xFFFF5C7A, 0.25f + 0.5f * pulse), 0x00000000);
		}
	}

	/** Читаемое имя клавиши по GLFW-коду (буквы, цифры, F1-F12, остальное — код). */
	private static String keyName(int key) {
		if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z
				|| key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9
				|| key == GLFW.GLFW_KEY_SPACE) {
			return String.valueOf((char) key).toUpperCase();
		}
		if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
			return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
		}
		if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) {
			return "Num" + (key - GLFW.GLFW_KEY_KP_0);
		}
		return switch (key) {
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift";
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl";
			case GLFW.GLFW_KEY_TAB -> "Tab";
			case GLFW.GLFW_KEY_ENTER -> "Enter";
			case GLFW.GLFW_KEY_GRAVE_ACCENT -> "Tilda";
			case GLFW.GLFW_KEY_MINUS -> "-";
			case GLFW.GLFW_KEY_EQUAL -> "=";
			case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
			case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
			case GLFW.GLFW_KEY_SEMICOLON -> ";";
			case GLFW.GLFW_KEY_APOSTROPHE -> "'";
			case GLFW.GLFW_KEY_COMMA -> ",";
			case GLFW.GLFW_KEY_PERIOD -> ".";
			case GLFW.GLFW_KEY_SLASH -> "/";
			case GLFW.GLFW_KEY_BACKSLASH -> "\\";
			default -> "#" + key;
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		double mx = event.x();
		double my = event.y();

		if (commandField.contains(mx, my)) {
			commandField.focused = true;
			awaitingKey = -1;
			return true;
		}
		commandField.focused = false;

		if (clickChips(event)) {
			return true;
		}

		// Правый клик по строке снимает клавишу — быстрый путь без кнопки внизу.
		if (event.button() == 1) {
			int rowAt = rowAtPointer(mx, my);
			if (rowAt >= 0) {
				if (rows.get(rowAt).key >= 0) {
					module.setKey(rowAt, -1);
					selected = rowAt;
					Notifications.ok("Макросы", "Клавиша снята");
					reloadKeepingPlace();
				}
				return true;
			}
		}

		// Левый клик по строке = «ждём клавишу». Зазор между строками не входит
		// ни в одну из них: иначе клик по зазору молча назначал бинд соседу.
		int index = rowAtPointer(mx, my);
		if (index >= 0) {
			selected = index;
			awaitingKey = index;
			playClick();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		int max = Math.max(0, rows.size() - visible);
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (awaitingKey >= 0) {
			int key = event.key();
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				awaitingKey = -1;
				Notifications.info("Макросы", "Бинд отменён");
				return true;
			}
			int index = awaitingKey;
			module.setKey(index, key);
			awaitingKey = -1;
			// Одна клавиша на две команды — не ошибка пользователя, а ловушка:
			// отдаём клавишу новой строке и говорим, у кого её забрали.
			for (int i = 0; i < rows.size(); i++) {
				if (i != index && rows.get(i).key == key) {
					module.setKey(i, -1);
					Notifications.warn("Макросы", "Клавиша " + keyName(key)
							+ " уже стояла на «" + rows.get(i).command + "» — снял с неё");
					break;
				}
			}
			Notifications.ok("Макросы", "Бинд: " + keyName(key));
			reloadKeepingPlace();
			return true;
		}
		if (commandField.focused) {
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
				commandField.backspace();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ENTER) {
				addFromField();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				commandField.focused = false;
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (awaitingKey >= 0) {
			return true; // клавиша ушла в бинд — в поле ей не место
		}
		if (commandField.focused) {
			if (event.isAllowedChatCharacter() && commandField.value.length() < commandField.maxLength) {
				commandField.value += event.codepointAsString();
			}
			return true;
		}
		return super.charTyped(event);
	}

	private void addFromField() {
		String command = commandField.value.trim();
		if (command.isEmpty()) {
			return;
		}
		if (!module.canAdd()) {
			Notifications.warn("Макросы", "Не удалось добавить макрос");
			return;
		}
		module.add(command);
		commandField.value = "";
		// Сразу подводим список к новой строке: дальше один клик по ней — и бинд готов.
		reload();
		rebuild();
		selected = rows.size() - 1;
		int visibleRows = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		scroll = Math.max(0, rows.size() - visibleRows);
		Notifications.ok("Макросы", "Добавлено; кликни строку, чтобы задать клавишу");
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}

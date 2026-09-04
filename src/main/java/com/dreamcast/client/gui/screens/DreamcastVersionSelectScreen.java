package com.dreamcast.client.gui.screens;

import com.dreamcast.client.util.FileOpener;
import com.dreamcast.client.util.RenderUtils;
import com.dreamcast.client.util.ViaIntegration;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Выбор версии протокола (для ViaFabricPlus) в нашем чёрном стиле.
 *
 * Открывается пилюлей «◆ версия» в правом верхнем углу списка серверов.
 * Версии берутся у самой VFP: объекты ProtocolVersion из getReversedProtocols(),
 * поэтому список всегда совпадает с тем, что показывает нативный экран VFP.
 * Здесь же — поиск по названию и честное объяснение, если VFP нет.
 */
public class DreamcastVersionSelectScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFF8DE06C;
	private static final int ROW_HEIGHT = 22;
	private static final int ROW_GAP = 3;
	private static final int PANEL_WIDTH = 300;
	private static final int LIST_TOP = 74;

	private static final class Row {
		final String label;
		final String hint;
		final boolean current;
		final Runnable action;

		float hover;

		Row(String label, String hint, boolean current, Runnable action) {
			this.label = label;
			this.hint = hint;
			this.current = current;
			this.action = action;
		}
	}

	private final Screen parent;
	private final TextField search = new TextField();
	private final List<Row> allRows = new ArrayList<>();
	private final List<Row> visibleRows = new ArrayList<>();
	private int scroll;
	private int listHeight = 1;

	public DreamcastVersionSelectScreen(Screen parent) {
		super("Версия для серверов");
		this.parent = parent;

		if (!ViaIntegration.available()) {
			allRows.add(new Row("ViaFabricPlus не найден", "можно установить отдельно", false, null));
			allRows.add(new Row("Открыть страницу VFP", "Modrinth", false,
					() -> FileOpener.openUrl("https://modrinth.com/mod/viafabricplus")));
		} else {
			allRows.add(new Row("Автоопределение", ViaIntegration.isAutoDetect() ? "\u2714 сейчас" : "1.7+ серверы",
					ViaIntegration.isAutoDetect(), () -> apply(ViaIntegration::setAutoDetect)));

			allRows.add(new Row("Нативная (" + ViaIntegration.nativeVersionLabel() + ")",
					"", !ViaIntegration.isAutoDetect() && isNativeCurrent(),
					() -> apply(ViaIntegration::setNative)));

			for (ViaIntegration.VersionEntry entry : ViaIntegration.collectVersions()) {
				allRows.add(new Row(entry.name(), entry.current() ? "\u2714 сейчас" : "",
						entry.current(), () -> apply(() -> ViaIntegration.setVersion(entry.protocol()))));
			}
		}
	}

	private boolean isNativeCurrent() {
		String current = ViaIntegration.currentVersionLabel();
		return current.equals(ViaIntegration.nativeVersionLabel());
	}

	private void apply(Runnable change) {
		playClick();
		change.run();
		// пересоздаём экран — галочка «сейчас» должна переехать
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(new DreamcastVersionSelectScreen(this.parent));
		}
	}

	private void refreshFilter() {
		String query = search.value.toLowerCase().trim();
		visibleRows.clear();
		for (Row row : allRows) {
			if (query.isEmpty() || row.label.toLowerCase().contains(query)) {
				visibleRows.add(row);
			}
		}
		scroll = 0;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);
		RenderUtils.textCentered(graphics, font, "Версия протокола", width / 2, 18, 0xFFF4F4FA, false);

		boolean via = ViaIntegration.available();
		String subtitle = !via
				? "ViaFabricPlus не установлен — показать нечего"
				: ViaIntegration.canChangeVersionNow()
						? "влияет на подключение к серверам от имени клиента"
						: "смена версии недоступна, пока открыт мир или сервер";
		graphics.text(font, RenderUtils.styled(subtitle), width / 2 - RenderUtils.width(font, subtitle) / 2, 30,
				ViaIntegration.canChangeVersionNow() || !via ? 0xFF9E9EAE : 0xFFFFC66C);

		int x = width / 2 - PANEL_WIDTH / 2;
		int searchWidth = Math.min(200, PANEL_WIDTH);
		search.x = width / 2 - searchWidth / 2;
		search.y = 46;
		search.width = searchWidth;
		search.hint = "поиск версии…";
		search.draw(graphics, ACCENT, mouseX, mouseY);

		if (search.value.isEmpty() && visibleRows.isEmpty()) {
			refreshFilter();
		}

		int listTop = LIST_TOP;
		listHeight = Math.max(1, height - listTop - 46);
		int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));

		drawGlassPanel(graphics, x - 8, listTop - 8, PANEL_WIDTH + 16, listHeight + 16, 10, 1.0f, ACCENT);

		graphics.enableScissor(x - 4, listTop - 4, x + PANEL_WIDTH + 4, listTop + listHeight + 4);
		int y = listTop;
		int drawn = 0;
		for (Row row : visibleRows) {
			if (drawn >= scroll && drawn < scroll + visible) {
				drawRow(graphics, row, x, y, PANEL_WIDTH, mouseX, mouseY);
				y += ROW_HEIGHT + ROW_GAP;
			}
			drawn++;
		}
		graphics.disableScissor();

		if (visibleRows.isEmpty()) {
			RenderUtils.textCentered(graphics, font, "ничего не найдено", width / 2, listTop + 10, 0xFF80808C, false);
		} else {
			drawScrollbar(graphics, x + PANEL_WIDTH + 3, listTop, listHeight, scroll, visible, visibleRows.size(), ACCENT);
		}
	
		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	private void drawRow(GuiGraphicsExtractor graphics, Row row, int x, int y, int w, int mouseX, int mouseY) {
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;
		boolean clickable = row.action != null && (ViaIntegration.canChangeVersionNow() || !ViaIntegration.available());
		row.hover = ease(row.hover, inside && clickable ? 1.0f : 0.0f, 0.22);

		int background = row.current
				? RenderUtils.mix(0xCC101015, RenderUtils.withAlpha(ACCENT, 0xFF), 0.20f)
				: RenderUtils.mix(0xB8101013, 0x16FFFFFF, row.hover * 0.5f);
		int border = row.current
				? RenderUtils.withAlpha(ACCENT, 0.6f)
				: RenderUtils.mix(0x12FFFFFF, ACCENT, row.hover * 0.25f);
		RenderUtils.fillRoundedBorder(graphics, x, y, w, ROW_HEIGHT, 6, border, background);

		int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
		int textColor = row.current ? 0xFFFFFFFF : RenderUtils.mix(0xFFC9C9D4, 0xFFF6F6F8, row.hover);
		graphics.text(font, RenderUtils.styled(RenderUtils.clamp(font, row.label, w - 16 - RenderUtils.width(font, row.hint) - 10)),
				x + 10, textY, textColor, false);
		if (!row.hint.isEmpty()) {
			graphics.text(font, RenderUtils.styled(row.hint), x + w - 10 - RenderUtils.width(font, row.hint), textY,
					row.current ? ACCENT : 0xFF6B6B78, false);
		}

		if (row.current) {
			graphics.fill(x, y + 4, x + 2, y + ROW_HEIGHT - 4, RenderUtils.withAlpha(ACCENT, 0.9f));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		if (search.contains(event.x(), event.y())) {
			search.focused = true;
			return true;
		}
		search.focused = false;

		double mx = event.x();
		double my = event.y();
		// Клик по строке: границы рисуются последовательно от LIST_TOP
		int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));
		if (mx >= width / 2 - PANEL_WIDTH / 2 && mx < width / 2 + PANEL_WIDTH / 2
				&& my >= LIST_TOP && my < LIST_TOP + listHeight) {
			int index = scroll + (int) ((my - LIST_TOP) / (ROW_HEIGHT + ROW_GAP));
			if (index >= scroll && index < Math.min(visibleRows.size(), scroll + visible)) {
				Row row = visibleRows.get(index);
				if (row.action != null && (ViaIntegration.canChangeVersionNow() || !ViaIntegration.available())) {
					row.action.run();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));
		int max = Math.max(0, visibleRows.size() - visible);
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (search.focused) {
			if (event.isAllowedChatCharacter()) {
				search.type(event.codepointAsString());
				refreshFilter();
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (search.focused) {
			switch (event.key()) {
				case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> {
					search.backspace();
					refreshFilter();
				}
				case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> search.focused = false;
				default -> {
				}
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}

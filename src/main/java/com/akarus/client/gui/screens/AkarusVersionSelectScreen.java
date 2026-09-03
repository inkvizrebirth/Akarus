package com.akarus.client.gui.screens;

import com.akarus.client.util.FileOpener;
import com.akarus.client.util.RenderUtils;
import com.akarus.client.util.ViaIntegration;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;

/**
 * Выбор версии протокола (для ViaFabricPlus) в нашем чёрном стиле.
 *
 * Открывается пилюлей в правом верхнем углу списка серверов. Без VFP экран не пустует:
 * объясняет, что виа не установлен, и даёт ссылку.
 */
public class AkarusVersionSelectScreen extends AkarusScreen {

	private static final int ACCENT = 0xFF8DE06C;
	private static final int ROW_HEIGHT = 24;
	private static final int ROW_GAP = 4;
	private static final int PANEL_WIDTH = 300;

	private final Screen parent;
	private int scroll;

	public AkarusVersionSelectScreen(Screen parent) {
		super("Версия для серверов");
		this.parent = parent;

		if (!ViaIntegration.available()) {
			items.add(item("ViaFabricPlus не найден", "иголка не перемонтируется", null));
			items.add(item("Открыть страницу VFP", "Modrinth",
					() -> FileOpener.openUrl("https://modrinth.com/mod/viafabricplus")));
			return;
		}

		items.add(item("Автоопределение", ViaIntegration.currentProtocolId() == -2 ? "\u2714 сейчас" : "для 1.7+ серверов",
				() -> apply(ViaIntegration::setAutoDetect)));

		String nativeName = ViaIntegration.nativeProtocolLabel();
		items.add(item("Нативная " + nativeName,
				ViaIntegration.currentProtocolId() == -1 ? "\u2714 сейчас" : "",
				() -> apply(ViaIntegration::setNative)));

		List<ViaIntegration.VersionEntry> versions = ViaIntegration.collectVersions();
		for (ViaIntegration.VersionEntry entry : versions) {
			String hint = entry.current() ? "\u2714 сейчас" : "#" + entry.id();
			items.add(item(entry.name(), hint, () -> apply(() -> ViaIntegration.setVersion(entry.id()))));
		}
	}

	private void apply(Runnable change) {
		playClick();
		change.run();
		// пересоздаём экран — галочка «сейчас» должна переехать
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(new AkarusVersionSelectScreen(this.parent));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);
		graphics.centeredText(font, "Версия протокола", width / 2, 18, 0xFFF4F4FA);

		String subtitle = ViaIntegration.available()
				? "влияет на подключение к серверам от имени клиента"
				: "ViaFabricPlus не установлен — меняем только метку";
		graphics.centeredText(font, subtitle, width / 2, 30, 0xFF9E9EAE);

		int visible = Math.min(items.size(), (height - 128) / (ROW_HEIGHT + ROW_GAP));
		int x = width / 2 - PANEL_WIDTH / 2;
		int y0 = 52;
		int listHeight = Math.max(1, visible) * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;

		drawGlassPanel(graphics, x - 8, y0 - 8, PANEL_WIDTH + 16, listHeight + 16, 10, 1.0f, ACCENT);

		int y = y0;
		for (int i = 0; i < items.size(); i++) {
			Item entry = items.get(i);
			if (i >= scroll && i < scroll + visible) {
				drawItem(graphics, entry, x, y, PANEL_WIDTH, ROW_HEIGHT, ACCENT, mouseX, mouseY);
			} else {
				// невидимые строки не должны ловить клики прошлыми границами
				entry.width = 0;
				entry.height = 0;
			}
			y += ROW_HEIGHT + ROW_GAP;
		}

		if (items.size() > visible) {
			int trackX = x + PANEL_WIDTH + 2;
			graphics.fill(trackX, y0, trackX + 2, y0 + listHeight, 0x33FFFFFF);
			int thumb = Math.max(12, listHeight * visible / items.size());
			int ty = y0 + (listHeight - thumb) * scroll / Math.max(1, items.size() - visible);
			graphics.fill(trackX, ty, trackX + 2, ty + thumb, RenderUtils.withAlpha(ACCENT, 0.8f));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickItems(event)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.min(items.size(), (height - 128) / (ROW_HEIGHT + ROW_GAP));
		int max = Math.max(0, items.size() - visible);
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}

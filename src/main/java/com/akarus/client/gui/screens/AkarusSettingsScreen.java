package com.akarus.client.gui.screens;

import com.akarus.client.util.RenderUtils;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.Options;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.HumanoidArm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Наш экран настроек: то же, что ванильные Options, но чёрным стеклом и по-нашему.
 *
 * Каждое значение пишется напрямую в {@code Options} через OptionInstance (игра сама
 * сохраняет options.txt), поэтому «применить» не нужно — изменение действует сразу.
 * Слайдер: клик — выставить по позиции, колесо — шаг. Циклические — клик по строке.
 */
public class AkarusSettingsScreen extends AkarusScreen {

	private static final int ACCENT = 0xFF8A6CFF;
	private static final int ROW_HEIGHT = 22;
	private static final int ROW_GAP = 3;
	private static final int PANEL_WIDTH = 340;

	/**kind*/
	private static final int TOGGLE = 0;
	private static final int SLIDER_INT = 1;
	private static final int SLIDER_DOUBLE = 2;
	private static final int CYCLE = 3;
	private static final int ACTION = 4;

	private static final class Row {
		final String label;
		final int kind;
		final Runnable onChange;

		boolean boolValue;
		java.util.function.BooleanSupplier boolGet;
		java.util.function.Consumer<Boolean> boolSet;

		int intValue;
		IntSupplier intGet;
		IntConsumer intSet;
		int min;
		int max;

		double dblValue;
		java.util.function.DoubleSupplier dblGet;
		java.util.function.DoubleConsumer dblSet;
		double dMin;
		double dMax;
		boolean percent;
		String valueSuffix = "";

		String[] cycleLabels;
		IntSupplier cycleGet;
		IntConsumer cycleSet;

		float hover;

		Row(String label, int kind, Runnable onChange) {
			this.label = label;
			this.kind = kind;
			this.onChange = onChange;
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private final Screen parent;

	private int scroll;

	public AkarusSettingsScreen(Screen parent) {
		super("Настройки");
		this.parent = parent;
		build();
	}

	private void build() {
		Options options = this.minecraft == null ? null : this.minecraft.options;
		if (options == null) {
			return;
		}

		Row render = new Row("Дальность прорисовки", SLIDER_INT, null);
		render.intGet = options.renderDistance()::get;
		render.intSet = options.renderDistance()::set;
		render.min = 2;
		render.max = 32;
		render.valueSuffix = " чанк.";
		rows.add(render);

		Row fps = new Row("Максимум FPS", SLIDER_INT, null);
		fps.intGet = options.framerateLimit()::get;
		fps.intSet = options.framerateLimit()::set;
		fps.min = 10;
		fps.max = 260;
		fps.valueSuffix = " (260 = без лимита)";
		rows.add(fps);

		Row mipmap = new Row("Mipmap", SLIDER_INT, null);
		mipmap.intGet = options.mipmapLevels()::get;
		mipmap.intSet = options.mipmapLevels()::set;
		mipmap.min = 0;
		mipmap.max = 4;
		mipmap.valueSuffix = " ур.";
		rows.add(mipmap);

		Row sensitivity = new Row("Чувствительность мыши", SLIDER_DOUBLE, null);
		sensitivity.dblGet = options.sensitivity()::get;
		sensitivity.dblSet = options.sensitivity()::set;
		sensitivity.percent = true;
		rows.add(sensitivity);

		Row wheel = new Row("Скорость колеса мыши", SLIDER_DOUBLE, null);
		wheel.dblGet = options.mouseWheelSensitivity()::get;
		wheel.dblSet = options.mouseWheelSensitivity()::set;
		wheel.dMin = 0.1;
		wheel.dMax = 1.0;
		wheel.percent = true;
		rows.add(wheel);

		Row shadows = new Row("Тени сущностей", TOGGLE, null);
		shadows.boolGet = () -> options.entityShadows().get();
		shadows.boolSet = v -> options.entityShadows().set(v);
		rows.add(shadows);

		Row vignette = new Row("Виньетка", TOGGLE, null);
		vignette.boolGet = () -> options.vignette().get();
		vignette.boolSet = v -> options.vignette().set(v);
		rows.add(vignette);

		Row ao = new Row("AO (затенение лица блоков)", TOGGLE, null);
		ao.boolGet = () -> options.ambientOcclusion().get();
		ao.boolSet = v -> options.ambientOcclusion().set(v);
		rows.add(ao);

		Row bob = new Row("Покачивание камеры", TOGGLE, null);
		bob.boolGet = () -> options.bobView().get();
		bob.boolSet = v -> options.bobView().set(v);
		rows.add(bob);

		Row autoJump = new Row("Автопрыжок", TOGGLE, null);
		autoJump.boolGet = () -> options.autoJump().get();
		autoJump.boolSet = v -> options.autoJump().set(v);
		rows.add(autoJump);

		Row vsync = new Row("VSync", TOGGLE, null);
		vsync.boolGet = () -> options.enableVsync().get();
		vsync.boolSet = v -> options.enableVsync().set(v);
		rows.add(vsync);

		Row invertY = new Row("Инверсия мыши по Y", TOGGLE, null);
		invertY.boolGet = () -> options.invertMouseY().get();
		invertY.boolSet = v -> options.invertMouseY().set(v);
		rows.add(invertY);

		Row smooth = new Row("Плавная камера (F8)", TOGGLE, null);
		smooth.boolGet = () -> options.smoothCamera;
		smooth.boolSet = v -> options.smoothCamera = v;
		rows.add(smooth);

		Row clouds = new Row("Облака", CYCLE, null);
		clouds.cycleLabels = new String[]{"Выкл", "Быстрые", "Облака"};
		clouds.cycleGet = () -> options.cloudStatus().get().ordinal();
		clouds.cycleSet = i -> options.cloudStatus().set(CloudStatus.values()[i % CloudStatus.values().length]);
		rows.add(clouds);

		Row preset = new Row("Качество графики", CYCLE, null);
		preset.cycleLabels = new String[]{"Быстро", "Детально", "Максимум"};
		preset.cycleGet = () -> options.graphicsPreset().get().ordinal();
		preset.cycleSet = i -> options.graphicsPreset().set(GraphicsPreset.values()[i % GraphicsPreset.values().length]);
		rows.add(preset);

		Row hand = new Row("Рука в руках", CYCLE, null);
		hand.cycleLabels = new String[]{"Левая", "Правая"};
		hand.cycleGet = () -> options.mainHand().get().ordinal();
		hand.cycleSet = i -> options.mainHand().set(HumanoidArm.values()[i % HumanoidArm.values().length]);
		rows.add(hand);

		Row keys = new Row("Назначить клавиши", ACTION,
				() -> {
					if (this.minecraft != null) {
						this.minecraft.gui.setScreen(new KeyBindsScreen(this, this.minecraft.options));
					}
				});
		rows.add(keys);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);

		int visible = Math.min(rows.size(), (height - 120) / (ROW_HEIGHT + ROW_GAP));
		int listHeight = visible * (ROW_HEIGHT + ROW_GAP) - ROW_GAP;
		int x = width / 2 - PANEL_WIDTH / 2;
		int y0 = 52;

		RenderUtils.textCentered(graphics, font, "Настройки", width / 2, 18, 0xFFF4F4FA, false);
		RenderUtils.textFlat(graphics, font, "клиент · управление · язык", width / 2, 31, 0xFF80808C);

		drawGlassPanel(graphics, x - 10, y0 - 12, PANEL_WIDTH + 20, listHeight + 24, 10, 1.0f, ACCENT);

		graphics.enableScissor(x - 6, y0 - 6, x + PANEL_WIDTH + 6, y0 + listHeight + 6);
		int y = y0;
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible + 1) && y - y0 <= listHeight; i++) {
			Row row = rows.get(i);
			drawRow(graphics, row, x, y, mouseX, mouseY);
			y += ROW_HEIGHT + ROW_GAP;
		}
		graphics.disableScissor();

		// Полоска прокрутки справа от списка
		if (rows.size() > visible) {
			int trackX = x + PANEL_WIDTH + 4;
			graphics.fill(trackX, y0, trackX + 2, y0 + listHeight, 0x33FFFFFF);
			int thumbHeight = Math.max(14, listHeight * visible / rows.size());
			int thumbY = y0 + (listHeight - thumbHeight) * scroll / Math.max(1, rows.size() - visible);
			graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, RenderUtils.withAlpha(ACCENT, 0.8f));
		}

		// Низ: назад
		int backY = height - 34;
		int backWidth = 150;
		{
			float hover = easeBack(mouseX, mouseY, x + (PANEL_WIDTH - backWidth) / 2, backY, backWidth, 20);
			drawGlassPanel(graphics, x + (PANEL_WIDTH - backWidth) / 2, backY, backWidth, 20, 8, hover, ACCENT);
			RenderUtils.textFlat(graphics, font, "Готово", x + PANEL_WIDTH / 2 - RenderUtils.width(font, "Готово") / 2,
					backY + (20 - font.lineHeight) / 2, 0xFFE8E8F0);
		}
	}

	private float backHover;

	private float easeBack(int mouseX, int mouseY, int bx, int by, int bw, int bh) {
		boolean inside = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
		backHover = ease(backHover, inside ? 1.0f : 0.0f, 0.25);
		return backHover;
	}

	private void drawRow(GuiGraphicsExtractor graphics, Row row, int x, int y, int mouseX, int mouseY) {
		boolean inside = mouseX >= x && mouseX < x + PANEL_WIDTH && mouseY >= y && mouseY < y + ROW_HEIGHT;
		row.hover = ease(row.hover, inside ? 1.0f : 0.0f, 0.25);

		if (row.hover > 0.02f) {
			graphics.fill(x - 6, y - 1, x + PANEL_WIDTH + 6, y + ROW_HEIGHT - 1,
					RenderUtils.withAlpha(0xFFFFFFFF, 0.035f * row.hover));
		}

		int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
		graphics.text(font, RenderUtils.styled(RenderUtils.clamp(font, row.label, PANEL_WIDTH - 90)), x + 6, textY, 0xFFE8E8F0, true);

		switch (row.kind) {
			case TOGGLE -> {
				row.boolValue = row.boolGet.getAsBoolean();
				int tw = 26;
				int th = 11;
				int tx = x + PANEL_WIDTH - 8 - tw;
				int ty = y + (ROW_HEIGHT - th) / 2;
				RenderUtils.drawToggle(graphics, tx, ty, tw, th, row.boolValue ? 1.0f : 0.0f, ACCENT);
			}
			case SLIDER_INT -> {
				row.intValue = row.intGet.getAsInt();
				double fraction = (row.intValue - row.min) / (double) Math.max(1, row.max - row.min);
				drawSliderValue(graphics, row, x, y, textY, fraction,
						row.intValue + row.valueSuffix);
			}
			case SLIDER_DOUBLE -> {
				row.dblValue = row.dblGet.getAsDouble();
				double dMin = row.dMin == 0.0 && !row.percent ? 0.0 : row.dMin;
				double dMax = row.dMax == 0.0 ? 1.0 : row.dMax;
				double fraction = (row.dblValue - dMin) / Math.max(1.0e-9, dMax - dMin);
				String shown = row.percent
						? Math.round(row.dblValue * 100.0) + " %"
						: String.format(java.util.Locale.ROOT, "%.2f", row.dblValue);
				drawSliderValue(graphics, row, x, y, textY, Math.max(0.0, Math.min(1.0, fraction)),
						shown + row.valueSuffix);
			}
			case CYCLE -> {
				int idx = row.cycleGet.getAsInt();
				String shown = idx >= 0 && idx < row.cycleLabels.length ? row.cycleLabels[idx] : "?";
				graphics.text(font, RenderUtils.styled(shown), x + PANEL_WIDTH - 10 - RenderUtils.width(font, shown), textY,
						RenderUtils.withAlpha(ACCENT, 0.95f), true);
			}
			case ACTION -> graphics.text(font, RenderUtils.styled("\u2192"), x + PANEL_WIDTH - 12, textY, 0xFFA6A6B2, true);
			default -> {
			}
		}
	}

	private void drawSliderValue(GuiGraphicsExtractor graphics, Row row, int x, int y, int textY,
	                             double fraction01, String valueLabel) {
		int sliderWidth = 96;
		int sx = x + PANEL_WIDTH - 12 - sliderWidth - font.width(valueLabel) - 8;
		int sy = y + (ROW_HEIGHT - 4) / 2;
		RenderUtils.drawSlider(graphics, sx, sy, sliderWidth, 4, (float) fraction01, ACCENT);
		graphics.text(font, RenderUtils.styled(valueLabel), sx + sliderWidth + 8, textY, 0xFFB9B9C6, true);
		row.intValue = sx; // левый край слайдера запоминаем: по нему кликом ставим значение
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		double mx = event.x();
		double my = event.y();
		int x = width / 2 - PANEL_WIDTH / 2;

		// «Готово»
		if (parent != null) {
			int backY = height - 34;
			int backWidth = 150;
			int bx = x + (PANEL_WIDTH - backWidth) / 2;
			if (mx >= bx && mx < bx + backWidth && my >= backY && my < backY + 20) {
				playClick();
				if (this.minecraft != null) {
					this.minecraft.gui.setScreen(parent);
				}
				return true;
			}
		}

		int visible = Math.min(rows.size(), (height - 120) / (ROW_HEIGHT + ROW_GAP));
		int y0 = 44;
		int index = 0;
		for (int i = scroll; i < Math.min(rows.size(), scroll + visible + 1); i++, index++) {
			int ry = y0 + index * (ROW_HEIGHT + ROW_GAP);
			if (my < ry || my >= ry + ROW_HEIGHT || mx < x - 6 || mx >= x + PANEL_WIDTH + 6) {
				continue;
			}
			Row row = rows.get(i);
			switch (row.kind) {
				case TOGGLE -> row.boolSet.accept(!row.boolGet.getAsBoolean());
				case SLIDER_INT -> {
					int sx = row.intValue; // левый край, записан в draw
					int sliderWidth = 96;
					double f = Math.max(0.0, Math.min(1.0, (mx - sx) / (double) sliderWidth));
					int value = (int) Math.round(row.min + f * (row.max - row.min));
					row.intSet.accept(Math.max(row.min, Math.min(row.max, value)));
				}
				case SLIDER_DOUBLE -> {
					int sx = row.intValue;
					int sliderWidth = 96;
					double f = Math.max(0.0, Math.min(1.0, (mx - sx) / (double) sliderWidth));
					double dMin = row.dMin == 0.0 && !row.percent ? 0.0 : row.dMin;
					double dMax = row.dMax == 0.0 ? 1.0 : row.dMax;
					row.dblSet.accept(Math.round((dMin + f * (dMax - dMin)) * 1000.0) / 1000.0);
				}
				case CYCLE -> row.cycleSet.accept(row.cycleGet.getAsInt() + 1);
				case ACTION -> {
					if (row.onChange != null) {
						playClick();
						row.onChange.run();
						return true;
					}
				}
				default -> {
				}
			}
			playClick();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.min(rows.size(), (height - 120) / (ROW_HEIGHT + ROW_GAP));
		int maxScroll = Math.max(0, rows.size() - visible);
		scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}
}

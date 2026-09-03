package com.akarus.client.gui.screens;

import com.akarus.client.AkarusClient;
import com.akarus.client.util.RenderUtils;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.NoticeWithLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.validation.ContentValidationException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Экран выбора миров в стиле клиента вместо ванильного SelectWorldScreen.
 *
 * Список миров грузится теми же API, что и в ваниле
 * ({@code findLevelCandidates()} → {@code loadLevelSummaries()}), но рисуется
 * наш: стеклянная панель, иконки миров, чипы действий, инлайн-подтверждение
 * удаления. Всё тяжёлое (чтение level.dat и иконок) уходит в фон — экран
 * открывается мгновенно и сначала показывает «Загрузка…».
 *
 * Создание и редактирование мира остаются за ванильными экранами
 * (CreateWorldScreen и EditWorldScreen) — это большие формы с генерацией,
 * датапаками и бэкапами, клонировать их себе дороже.
 */
public class AkarusWorldsScreen extends AkarusScreen {

	private static final int ACCENT = 0xFF7C6CFF;
	private static final int PANEL_WIDTH = 420;
	private static final int ROW_HEIGHT = 40;
	private static final int ROW_GAP = 4;
	private static final int LIST_TOP = 52;
	private static final int LIST_BOTTOM = 58;

	/** Одна строка списка: мир и его иконка. */
	private static final class WorldRow {
		final LevelSummary summary;
		final FaviconTexture icon;

		float hover;
		int y;

		WorldRow(LevelSummary summary, FaviconTexture icon) {
			this.summary = summary;
			this.icon = icon;
		}
	}

	private final Screen parent;
	private final List<WorldRow> rows = new ArrayList<>();
	private int selected = -1;
	private int scroll;
	private boolean loading = true;
	private boolean failure;
	private boolean confirmDelete;
	private int listHeight = 1;

	public AkarusWorldsScreen(Screen parent) {
		super("Одиночная игра");
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		if (loading && !failure && rows.isEmpty()) {
			loadWorlds();
		}
	}

	private void loadWorlds() {
		loading = true;
		CompletableFuture.supplyAsync(() -> {
			var candidates = this.minecraft.getLevelSource().findLevelCandidates();
			return this.minecraft.getLevelSource().loadLevelSummaries(candidates).join();
		}).whenComplete((summaries, error) -> this.minecraft.execute(() -> {
			closeIcons();
			rows.clear();
			selected = -1;
			scroll = 0;
			confirmDelete = false;

			if (error != null) {
				AkarusClient.LOGGER.error("Не удалось прочитать список миров", error);
				failure = true;
				loading = false;
				return;
			}

			List<LevelSummary> sorted = new ArrayList<>(summaries);
			sorted.sort(Comparator.comparingLong(LevelSummary::getLastPlayed).reversed());
			for (LevelSummary summary : sorted) {
				rows.add(new WorldRow(summary,
						FaviconTexture.forWorld(this.minecraft.getTextureManager(), summary.getLevelId())));
			}
			loading = false;
			uploadIcons();
		}));
	}

	/**
	 * Читает иконки миров в фоне, а заливает их в GPU на потоке игры — как ваниль.
	 * Файлы маленькие (64×64), но диск бывает медленным, и читать его в рендере
	 * не стоит.
	 */
	private void uploadIcons() {
		List<WorldRow> snapshot = new ArrayList<>(rows);
		List<WorldRow> ready = new ArrayList<>();
		List<NativeImage> images = new ArrayList<>();
		CompletableFuture.runAsync(() -> {
			for (WorldRow row : snapshot) {
				Path iconFile = row.summary.getIcon();
				if (iconFile == null || !Files.isRegularFile(iconFile)) {
					continue;
				}
				try (InputStream stream = Files.newInputStream(iconFile)) {
					NativeImage image = NativeImage.read(stream);
					if (image.getWidth() == 64 && image.getHeight() == 64) {
						ready.add(row);
						images.add(image);
					} else {
						image.close();
					}
				} catch (Throwable error) {
					AkarusClient.LOGGER.error("Некорректная иконка мира {}", row.summary.getLevelId(), error);
				}
			}
		}).whenComplete((unused, error) -> this.minecraft.execute(() -> {
			for (int i = 0; i < ready.size(); i++) {
				try {
					ready.get(i).icon.upload(images.get(i));
				} catch (Throwable uploadError) {
					images.get(i).close();
				}
			}
		}));
	}

	@Override
	public void removed() {
		closeIcons();
		super.removed();
	}

	private void closeIcons() {
		for (WorldRow row : rows) {
			if (!row.icon.isClosed()) {
				row.icon.close();
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);

		graphics.centeredText(font, "Одиночная игра", width / 2, 18, 0xFFF4F4FA);
		graphics.centeredText(font,
				loading ? "загрузка миров…" : rows.size() + " мир(ов) · двойной клик — играть",
				width / 2, 30, 0xFF9E9EAE);

		int panelWidth = Math.min(PANEL_WIDTH, width - 16);
		int x = width / 2 - panelWidth / 2;
		int listTop = LIST_TOP;
		listHeight = Math.max(1, height - listTop - LIST_BOTTOM);
		int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));

		drawGlassPanel(graphics, x - 8, listTop - 8, panelWidth + 16, listHeight + 16, 10, 1.0f, ACCENT);

		if (failure) {
			graphics.centeredText(font, "Не удалось прочитать список миров", width / 2, listTop + 14, 0xFFFF8095);
			graphics.centeredText(font, "подробности — в журнале игры", width / 2, listTop + 26, 0xFF80808C);
		} else if (loading && rows.isEmpty()) {
			graphics.centeredText(font, "Загрузка…", width / 2, listTop + 18, 0xFF9E9EAE);
		} else if (rows.isEmpty()) {
			graphics.centeredText(font, "Миров пока нет", width / 2, listTop + 12, 0xFFE8E8F0);
			graphics.centeredText(font, "создай первый — кнопка «Создать» внизу", width / 2, listTop + 24, 0xFF80808C);
		} else {
			graphics.enableScissor(x - 4, listTop - 4, x + panelWidth + 4, listTop + listHeight + 4);
			int y = listTop;
			for (int i = 0; i < rows.size(); i++) {
				WorldRow row = rows.get(i);
				row.y = y;
				if (i >= scroll && i < scroll + visible) {
					drawWorldRow(graphics, row, i, x, y, panelWidth, mouseX, mouseY);
					y += ROW_HEIGHT + ROW_GAP;
				}
			}
			graphics.disableScissor();
			drawScrollbar(graphics, x + panelWidth + 3, listTop, listHeight, scroll, visible, rows.size(), ACCENT);
		}

		// Ряд действий
		chips.clear();
		LevelSummary selection = selected >= 0 && selected < rows.size() ? rows.get(selected).summary : null;
		if (confirmDelete && selection != null) {
			Chip confirm = chip("удалить", this::deleteSelected, true);
			chips.add(confirm);
			chips.add(chip("отмена", () -> confirmDelete = false));
		} else {
			Chip play = chip("Играть", () -> joinWorld(selection));
			play.enabled = selection != null && selection.primaryActionActive();
			chips.add(play);
			chips.add(chip("Создать", this::createWorld));
			Chip edit = chip("Изменить", () -> editWorld(selection));
			edit.enabled = selection != null;
			chips.add(edit);
			Chip delete = chip("Удалить", () -> confirmDelete = true);
			delete.enabled = selection != null;
			delete.danger = true;
			chips.add(delete);
		}
		chips.add(chip("Назад", this::onClose));
		drawChipRow(graphics, width / 2, height - 44, 20, 5, ACCENT, mouseX, mouseY);
	}

	private void drawWorldRow(GuiGraphicsExtractor graphics, WorldRow row, int index, int x, int y, int w,
	                          int mouseX, int mouseY) {
		LevelSummary summary = row.summary;
		boolean selectedRow = index == selected;
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;
		row.hover = ease(row.hover, inside ? 1.0f : 0.0f, 0.22);

		int background = selectedRow
				? RenderUtils.mix(0xCC101015, RenderUtils.withAlpha(ACCENT, 0xFF), 0.18f + 0.08f * row.hover)
				: RenderUtils.mix(0xB8101013, 0x16FFFFFF, row.hover * 0.5f);
		int border = selectedRow
				? RenderUtils.withAlpha(ACCENT, 0.55f + 0.35f * row.hover)
				: RenderUtils.mix(0x12FFFFFF, ACCENT, row.hover * 0.25f);
		RenderUtils.fillRoundedBorder(graphics, x, y, w, ROW_HEIGHT, 6, border, background);

		// Иконка 32×32 с подложкой
		int iconX = x + 5;
		int iconY = y + (ROW_HEIGHT - 32) / 2;
		graphics.fill(iconX - 1, iconY - 1, iconX + 33, iconY + 33, 0x40000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, row.icon.textureLocation(),
				iconX, iconY, 0.0F, 0.0F, 32, 32, 32, 32);

		int textX = iconX + 36;
		int nameLimit = w - 36 - 8;
		boolean locked = summary.isLocked() || !summary.primaryActionActive();
		graphics.text(font, RenderUtils.clamp(font, summary.getLevelName(), nameLimit), textX, y + 5,
				locked ? 0xFF9E9EAE : selectedRow ? 0xFFFFFFFF : 0xFFE8E8F0, false);
		graphics.text(font, RenderUtils.clamp(font, summary.getInfo().getString(), nameLimit), textX, y + 16,
				0xFFA6A6B2, false);
		graphics.text(font, RenderUtils.clamp(font, formatDate(summary.getLastPlayed()), nameLimit),
				textX, y + 27, 0xFF6B6B78, false);

		// Ярлык состояния справа сверху
		String tag = null;
		int tagColor = 0xFF9E9EAE;
		if (summary.isExperimental()) {
			tag = "экспер.";
			tagColor = 0xFFFFC66C;
		} else if (!summary.isCompatible()) {
			tag = "другая версия";
			tagColor = 0xFFFF8095;
		} else if (summary.shouldBackup()) {
			tag = "бэкап";
			tagColor = 0xFF8DE06C;
		}
		if (tag != null) {
			graphics.text(font, tag, x + w - font.width(tag) - 8, y + 5, tagColor, false);
		}
	}

	private static String formatDate(long millis) {
		if (millis <= 0L) {
			return "дата неизвестна";
		}
		ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		return DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.forLanguageTag("ru")).format(time);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickChips(event)) {
			return true;
		}

		double mx = event.x();
		double my = event.y();
		int panelWidth = Math.min(PANEL_WIDTH, width - 16);
		int x = width / 2 - panelWidth / 2;
		if (mx >= x && mx < x + panelWidth && my >= LIST_TOP && my < LIST_TOP + listHeight) {
			int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));
			int index = scroll + (int) ((my - LIST_TOP) / (ROW_HEIGHT + ROW_GAP));
			if (index >= scroll && index < Math.min(rows.size(), scroll + visible)) {
				selected = index;
				confirmDelete = false;
				if (doubleClick) {
					joinWorld(rows.get(index).summary);
				} else {
					playClick();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));
		int max = Math.max(0, rows.size() - visible);
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent);
		}
	}

	// ------------------------------------------------------------------
	// Действия
	// ------------------------------------------------------------------

	private void reopen() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(new AkarusWorldsScreen(parent));
		}
	}

	private void joinWorld(@Nullable LevelSummary summary) {
		if (summary == null || !summary.primaryActionActive()) {
			return;
		}
		if (summary instanceof LevelSummary.SymlinkLevelSummary) {
			this.minecraft.gui.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen(this::reopen));
			return;
		}
		this.minecraft.createWorldOpenFlows().openWorld(summary.getLevelId(), this::reopen);
	}

	private void createWorld() {
		CreateWorldScreen.openFresh(this.minecraft, this::reopen);
	}

	private void editWorld(@Nullable LevelSummary summary) {
		if (summary == null) {
			return;
		}
		String levelId = summary.getLevelId();

		LevelStorageSource.LevelStorageAccess access;
		try {
			access = this.minecraft.getLevelSource().validateAndCreateAccess(levelId);
		} catch (IOException error) {
			SystemToast.onWorldAccessFailure(this.minecraft, levelId);
			AkarusClient.LOGGER.error("Не удалось открыть мир {}", levelId, error);
			return;
		} catch (ContentValidationException error) {
			AkarusClient.LOGGER.warn("{}", error.getMessage());
			this.minecraft.gui.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen(this::reopen));
			return;
		}

		try {
			EditWorldScreen editScreen = EditWorldScreen.create(this.minecraft, access, result -> {
				access.safeClose();
				reopen();
			});
			this.minecraft.gui.setScreen(editScreen);
		} catch (NbtException | ReportedNbtException | IOException error) {
			access.safeClose();
			SystemToast.onWorldAccessFailure(this.minecraft, levelId);
			AkarusClient.LOGGER.error("Не удалось прочитать данные мира {}", levelId, error);
			reopen();
		}
	}

	private void deleteSelected() {
		if (selected < 0 || selected >= rows.size()) {
			confirmDelete = false;
			return;
		}
		String levelId = rows.get(selected).summary.getLevelId();
		try (LevelStorageSource.LevelStorageAccess access = this.minecraft.getLevelSource().createAccess(levelId)) {
			access.deleteLevel();
		} catch (IOException error) {
			SystemToast.onWorldDeleteFailure(this.minecraft, levelId);
			AkarusClient.LOGGER.error("Не удалось удалить мир {}", levelId, error);
		}
		confirmDelete = false;
		reopen();
	}
}

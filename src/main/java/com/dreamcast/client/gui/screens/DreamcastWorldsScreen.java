package com.dreamcast.client.gui.screens;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.util.RenderUtils;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.RenderPipelines;
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
 * Экран выбора миров — «полка», а не список.
 *
 * Уникальная композиция: слева — крупная карточка выбранного мира (иконка
 * во весь рост, имя, подробности, метки), справа — узкая колонка строк
 * с мини-иконками для навигации. Строки въезжают «лесницей» при открытии,
 * новая выборка подсвечивается волной. Создание/редактирование — ванильные
 * экраны (большие формы клонировать себе дороже), всё остальное — наше.
 */
public class DreamcastWorldsScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFF7C6CFF;
	private static final int PANEL_WIDTH = 460;
	private static final int ROW_HEIGHT = 30;
	private static final int ROW_GAP = 3;
	private static final int LIST_TOP = 44;
	private static final int LIST_BOTTOM = 46;
	/** Ширина левой карточки-превью. */
	private static final int CARD_WIDTH = 196;

	/** Одна строка списка: мир и его иконка + анимации. */
	private static final class WorldRow {
		final LevelSummary summary;
		final FaviconTexture icon;

		float hover;
		/** Появление строки (0..1, «лестница» при загрузке списка). */
		float appear;
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
	/** Анимация подсветки карточки при смене выбора. */
	private float cardFlash;

	public DreamcastWorldsScreen(Screen parent) {
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

	/** Поколение экрана: растёт в removed() — устаревшие фоновые задачи игнорируются. */
	private final com.dreamcast.client.util.Generation generation = new com.dreamcast.client.util.Generation();

	private void loadWorlds() {
		loading = true;
		final com.dreamcast.client.util.Generation.Ticket gen = this.generation.start();
		CompletableFuture.supplyAsync(() -> {
			var candidates = this.minecraft.getLevelSource().findLevelCandidates();
			return this.minecraft.getLevelSource().loadLevelSummaries(candidates).join();
		}).whenComplete((summaries, error) -> this.minecraft.execute(() -> {
			if (!generation.valid(gen)) {
				return; // экран закрыли и открыли заново — эти строки уже не наши
			}
			closeIcons();
			rows.clear();
			selected = -1;
			scroll = 0;
			confirmDelete = false;

			if (error != null) {
				DreamcastClient.LOGGER.error("Не удалось прочитать список миров", error);
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
			if (!rows.isEmpty()) {
				selected = 0;
			}
			loading = false;
			uploadIcons();
		}));
	}

	/**
	 * Читает иконки миров в фоне, а заливает их в GPU на потоке игры — как ваниль.
	 */
	private void uploadIcons() {
		final com.dreamcast.client.util.Generation.Ticket gen = this.generation.start();
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
					DreamcastClient.LOGGER.error("Некорректная иконка мира {}", row.summary.getLevelId(), error);
				}
			}
		}).whenComplete((unused, error) -> this.minecraft.execute(() -> {
			if (!generation.valid(gen)) {
				// Экран закрылся, пока читались файлы: текстуры уже закрыты —
				// заливать нельзя, просто освобождаем память
				for (NativeImage image : images) {
					image.close();
				}
				return;
			}
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
		this.generation.invalidate(); // незавершённые фоновые задачи становятся устаревшими
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

		// Заголовок слева (композиция несимметричная — как обложка)
		RenderUtils.textBold(graphics, font, "Одиночная игра", 18, 16, 0xFFF4F4FA);
		String subtitle = loading ? "загрузка…" : rows.size() + " мир(ов)";
		RenderUtils.textFlat(graphics, font, subtitle, 18, 16 + font.lineHeight + 3, 0xFF80808C);

		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int panelX = 18;
		int panelY = LIST_TOP;
		listHeight = Math.max(1, height - panelY - LIST_BOTTOM);
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));

		// Строки справа: узкая колонка
		int listWidth = panelWidth - CARD_WIDTH - 12;
		int listX = panelX + CARD_WIDTH + 12;

		drawGlassPanel(graphics, panelX, panelY, panelWidth, listHeight, 12, 1.0f, ACCENT);

		if (failure) {
			RenderUtils.text(graphics, font, "Не удалось прочитать список миров", panelX + 14, panelY + 16, 0xFFFF8095);
			RenderUtils.text(graphics, font, "подробности — в журнале игры", panelX + 14, panelY + 28, 0xFF80808C);
		} else if (rows.isEmpty() && !loading) {
			RenderUtils.text(graphics, font, "Миров пока нет", panelX + 14, panelY + 14, 0xFFE8E8F0);
			RenderUtils.text(graphics, font, "«Создать» — первый мир", panelX + 14, panelY + 26, 0xFF80808C);
		} else if (!rows.isEmpty()) {
			// Левая карточка выбранного мира
			LevelSummary selection = selected >= 0 && selected < rows.size() ? rows.get(selected).summary : null;
			if (selection != null) {
				drawWorldCard(graphics, rows.get(selected), panelX + 10, panelY + 8, CARD_WIDTH, listHeight - 16);
			}

			// Правая колонка строк
			graphics.enableScissor(listX - 2, panelY + 4, listX + listWidth + 4, panelY + listHeight - 4);
			int y = panelY + 8;
			int index = 0;
			for (WorldRow row : rows) {
				row.y = y;
				if (index >= scroll && index < scroll + visible) {
					drawWorldRow(graphics, row, index, listX, y, listWidth, mouseX, mouseY);
					y += ROW_HEIGHT + ROW_GAP;
				}
				index++;
			}
			graphics.disableScissor();
			drawScrollbar(graphics, listX + listWidth + 3, panelY + 8, listHeight - 16, scroll, visible, rows.size(), ACCENT);
		}

		// Ряд действий
		chips.clear();
		if (confirmDelete && selected >= 0) {
			chips.add(chip("удалить мир", this::deleteSelected, true));
			chips.add(chip("отмена", () -> confirmDelete = false));
		} else {
			LevelSummary selection = selected >= 0 && selected < rows.size() ? rows.get(selected).summary : null;
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
		drawChipRow(graphics, width / 2, height - 36, 20, 5, ACCENT, mouseX, mouseY);
	
		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	/** Крупная карточка выбранного мира: иконка, имя, детали, метки состояния. */
	private void drawWorldCard(GuiGraphicsExtractor graphics, WorldRow row, int x, int y, int w, int h) {
		LevelSummary summary = row.summary;

		// Вспышка при смене выбора
		cardFlash = ease(cardFlash, 0.0f, 0.12f);
		if (cardFlash > 0.02f) {
			RenderUtils.fillRounded(graphics, x, y, w, h, 10, RenderUtils.withAlpha(ACCENT, 0.10f * cardFlash));
		}

		// Иконка крупно, со сглаженной подложкой
		int iconSize = Math.min(96, w - 24);
		int iconX = x + (w - iconSize) / 2;
		int iconY = y + 12;
		RenderUtils.drawSoftShadow(graphics, iconX - 2, iconY - 2, iconSize + 4, iconSize + 4, 8, 3);
		graphics.fill(iconX - 2, iconY - 2, iconX + iconSize + 2, iconY + iconSize + 2, 0x50000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, row.icon.textureLocation(),
				iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, 64, 64);

		// Имя крупно (жирным), под ним — режим и версия
		String name = RenderUtils.clamp(font, summary.getLevelName(), w - 16);
		RenderUtils.textCentered(graphics, font, name, x + w / 2, iconY + iconSize + 8, 0xFFF4F4FA, false);
		String info = RenderUtils.clamp(font, summary.getInfo().getString(), w - 16);
		RenderUtils.textCentered(graphics, font, info, x + w / 2, iconY + iconSize + 8 + font.lineHeight + 2,
				0xFFA6A6B2, false);

		// Дата игры
		String date = RenderUtils.clamp(font, "играли: " + formatDate(summary.getLastPlayed()), w - 16);
		RenderUtils.textCentered(graphics, font, date, x + w / 2, iconY + iconSize + 8 + font.lineHeight * 2 + 5,
				0xFF6B6B78, false);

		// Метки состояния — пилюли внизу карточки
		int pillY = y + h - 16;
		int pillX = x + 8;
		if (summary.isExperimental()) {
			pillX = drawPill(graphics, "эксперименты", pillX, pillY, 0xFFFFC66C);
		}
		if (!summary.isCompatible()) {
			pillX = drawPill(graphics, "другая версия", pillX, pillY, 0xFFFF8095);
		}
		if (summary.shouldBackup()) {
			drawPill(graphics, "бэкап", pillX, pillY, 0xFF8DE06C);
		}
	}

	/** Пилюля-метка; возвращает X следующей. */
	private int drawPill(GuiGraphicsExtractor graphics, String label, int x, int y, int color) {
		int w = RenderUtils.width(font, label) + 10;
		RenderUtils.fillRounded(graphics, x, y, w, font.lineHeight + 4, 4, RenderUtils.withAlpha(color, 0.20f));
		RenderUtils.textFlat(graphics, font, label, x + 5, y + 2, color);
		return x + w + 4;
	}

	private void drawWorldRow(GuiGraphicsExtractor graphics, WorldRow row, int index, int x, int y, int w,
	                          int mouseX, int mouseY) {
		LevelSummary summary = row.summary;
		boolean selectedRow = index == selected;
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;

		// «Лестница» появления: строка №index стартует с задержкой index*7%
		row.appear = ease(row.appear, 1.0f, 0.18f);
		float delay = Math.min(0.5f, index * 0.07f);
		float appear = Math.max(0.0f, Math.min(1.0f, (row.appear - delay) / Math.max(0.05f, 1.0f - delay)));
		int slideX = Math.round((1.0f - appear) * 10.0f);

		row.hover = ease(row.hover, inside ? 1.0f : 0.0f, 0.22);

		int background = selectedRow
				? RenderUtils.mix(0xCC101015, RenderUtils.withAlpha(ACCENT, 0xFF), 0.20f + 0.08f * row.hover)
				: RenderUtils.mix(0xA0101013, 0x16FFFFFF, row.hover * 0.5f);
		int border = selectedRow
				? RenderUtils.withAlpha(ACCENT, 0.55f + 0.35f * row.hover)
				: RenderUtils.mix(0x10FFFFFF, ACCENT, row.hover * 0.25f);
		background = RenderUtils.withAlpha(background, appear);
		border = RenderUtils.withAlpha(border, appear);
		RenderUtils.fillRoundedBorder(graphics, x + slideX, y, w, ROW_HEIGHT, 6, border, background);

		// Мини-иконка 18×18
		int iconX = x + slideX + 5;
		int iconY = y + (ROW_HEIGHT - 18) / 2;
		graphics.fill(iconX - 1, iconY - 1, iconX + 19, iconY + 19, 0x40000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, row.icon.textureLocation(),
				iconX, iconY, 0.0F, 0.0F, 18, 18, 64, 64);

		int textX = iconX + 22;
		int nameLimit = w - 22 - 8;
		boolean locked = summary.isLocked() || !summary.primaryActionActive();
		String name = RenderUtils.clamp(font, summary.getLevelName(), nameLimit);
		RenderUtils.textFlat(graphics, font, name, textX, y + 5,
				RenderUtils.withAlpha(locked ? 0xFF9E9EAE : selectedRow ? 0xFFFFFFFF : 0xFFE8E8F0, appear));
		String date = RenderUtils.clamp(font, shortDate(summary.getLastPlayed()), nameLimit);
		RenderUtils.textFlat(graphics, font, date, textX, y + 17,
				RenderUtils.withAlpha(0xFF6B6B78, appear));
	}

	private static String formatDate(long millis) {
		if (millis <= 0L) {
			return "дата неизвестна";
		}
		ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		return DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.forLanguageTag("ru")).format(time);
	}

	private static String shortDate(long millis) {
		if (millis <= 0L) {
			return "—";
		}
		ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru")).format(time);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		if (clickChips(event)) {
			return true;
		}

		double mx = event.x();
		double my = event.y();
		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int listWidth = panelWidth - CARD_WIDTH - 12;
		int listX = 18 + CARD_WIDTH + 12;
		int panelY = LIST_TOP;
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
		if (mx >= listX && mx < listX + listWidth && my >= panelY + 4 && my < panelY + listHeight - 4) {
			int index = scroll + (int) ((my - panelY - 8) / (ROW_HEIGHT + ROW_GAP));
			if (index >= scroll && index < Math.min(rows.size(), scroll + visible)) {
				selected = index;
				confirmDelete = false;
				cardFlash = 1.0f;
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
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));
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
			this.minecraft.gui.setScreen(new DreamcastWorldsScreen(parent));
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
			DreamcastClient.LOGGER.error("Не удалось открыть мир {}", levelId, error);
			return;
		} catch (ContentValidationException error) {
			DreamcastClient.LOGGER.warn("{}", error.getMessage());
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
			DreamcastClient.LOGGER.error("Не удалось прочитать данные мира {}", levelId, error);
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
			DreamcastClient.LOGGER.error("Не удалось удалить мир {}", levelId, error);
		}
		confirmDelete = false;
		reopen();
	}
}

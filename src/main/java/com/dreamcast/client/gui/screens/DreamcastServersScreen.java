package com.dreamcast.client.gui.screens;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.util.RenderUtils;
import com.dreamcast.client.util.ViaIntegration;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.ManageServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import org.jspecify.annotations.Nullable;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Экран мультиплеера — «монитор серверов», а не список.
 *
 * Уникальная композиция: слева — крупная панель выбранного сервера
 * (иконка, MOTD, игроки, пинг крупными цифрами), справа — колонка быстрых
 * строк. Новая строка (после «Добавить») въезжает со вспышкой и волной,
 * смена выбора подсвечивает карточку. Пилюля версии ViaFabricPlus —
 * сверху справа, открывает {@link DreamcastVersionSelectScreen}.
 */
public class DreamcastServersScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFF45E3FF;
	private static final int PANEL_WIDTH = 460;
	private static final int ROW_HEIGHT = 30;
	private static final int ROW_GAP = 3;
	private static final int LIST_TOP = 44;
	private static final int LIST_BOTTOM = 46;
	private static final int CARD_WIDTH = 200;

	/** Строка сервера: данные + иконка + анимации. */
	private static final class ServerRow {
		final ServerData data;
		final FaviconTexture icon;

		float hover;
		/** Появление строки (0..1; у только что добавленных — «вспышка»). */
		float appear;
		/** Дополнительная вспышка новой строки. */
		float flash;
		int y;
		byte @Nullable [] uploadedIcon;

		ServerRow(ServerData data, FaviconTexture icon) {
			this.data = data;
			this.icon = icon;
			this.appear = 0.0f;
			this.flash = 1.0f;
		}
	}

	private final Screen parent;
	private final ServerStatusPinger pinger = new ServerStatusPinger();
	private final List<ServerRow> rows = new ArrayList<>();
	private int selected = -1;
	private int scroll;
	private boolean confirmDelete;
	private int listHeight = 1;
	private float versionPillHover;
	/** Подсветка карточки при смене выбора. */
	private float cardFlash;
	/** ip серверера, добавленного последним (его строка «вспыхивает»). */
	private String lastAddedIp;

	public DreamcastServersScreen(Screen parent) {
		super("Сетевая игра");
		this.parent = parent;
	}

	public DreamcastServersScreen(Screen parent, String highlightIp) {
		this(parent);
		this.lastAddedIp = highlightIp;
	}

	@Override
	protected void init() {
		super.init();
		reloadServers();
	}

	private void reloadServers() {
		closeIcons();
		rows.clear();
		selected = -1;
		scroll = 0;
		confirmDelete = false;

		ServerList servers = new ServerList(this.minecraft);
		servers.load();
		for (int i = 0; i < servers.size(); i++) {
			ServerData data = servers.get(i);
			ServerRow row = new ServerRow(data,
					FaviconTexture.forServer(this.minecraft.getTextureManager(), data.ip));
			if (data.ip.equals(lastAddedIp)) {
				selected = i;
			}
			rows.add(row);
		}
		pingAll();
	}

	/** false после removed(): фоновые пинги не должны трогать данные и текстуры. */
	private volatile boolean alive = true;

	private void pingAll() {
		for (ServerRow row : rows) {
			pingRow(row);
		}
	}

	private void pingRow(ServerRow row) {
		ServerData data = row.data;
		data.setState(ServerData.State.PINGING);
		data.motd = Component.empty();
		data.status = Component.empty();
		CompletableFuture.runAsync(() -> {
			try {
				this.pinger.pingServer(data,
						() -> this.minecraft.execute(() -> {
							if (alive) {
								uploadChangedIcons();
							}
						}),
						() -> this.minecraft.execute(() -> {
							if (!alive) {
								return;
							}
							data.setState(data.protocol == net.minecraft.SharedConstants.getCurrentVersion().protocolVersion()
									? ServerData.State.SUCCESSFUL
									: ServerData.State.INCOMPATIBLE);
							uploadChangedIcons();
						}),
						EventLoopGroupHolder.remote(this.minecraft.options.useNativeTransport()));
			} catch (UnknownHostException error) {
				this.minecraft.execute(() -> applyPingFailure(row, "не удалось найти адрес"));
			} catch (Exception error) {
				this.minecraft.execute(() -> applyPingFailure(row, "не удалось подключиться"));
				DreamcastClient.LOGGER.warn("Пинг {} не удался", data.ip, error);
			}
		});
	}

	/** Ошибка пинга: применяемся строго на потоке игры и только у живого экрана. */
	private void applyPingFailure(ServerRow row, String message) {
		if (!alive) {
			return;
		}
		row.data.setState(ServerData.State.UNREACHABLE);
		row.data.motd = Component.literal(message);
	}

	/** Загружает иконки, чьи байты поменялись (после пинга они появляются). */
	private void uploadChangedIcons() {
		for (ServerRow row : rows) {
			byte[] bytes = row.data.getIconBytes();
			if (Arrays.equals(bytes, row.uploadedIcon)) {
				continue;
			}
			if (bytes != null) {
				try {
					row.icon.upload(NativeImage.read(bytes));
					row.uploadedIcon = bytes;
				} catch (Throwable error) {
					row.data.setIconBytes(null);
					row.uploadedIcon = null;
				}
			} else {
				row.icon.clear();
				row.uploadedIcon = null;
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		this.pinger.tick();
		uploadChangedIcons();
	}

	@Override
	public void removed() {
		alive = false; // фоновые пинги больше не применяют результаты
		this.pinger.removeAll();
		closeIcons();
		super.removed();
	}

	private void closeIcons() {
		for (ServerRow row : rows) {
			if (!row.icon.isClosed()) {
				row.icon.close();
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);

		// Заголовок слева — как у миров, единая композиция «обложки»
		RenderUtils.textBold(graphics, font, "Сетевая игра", 18, 16, 0xFFF4F4FA);
		String subtitle = switch (stateOfRows()) {
			case "empty" -> "серверов пока нет";
			case "pinging" -> "опрашиваем серверы…";
			default -> rows.size() + " сервер(ов)";
		};
		RenderUtils.textFlat(graphics, font, subtitle, 18, 16 + font.lineHeight + 3, 0xFF80808C);

		drawVersionPill(graphics, mouseX, mouseY);

		int panelWidth = Math.min(PANEL_WIDTH, width - 24);
		int panelX = 18;
		int panelY = LIST_TOP;
		listHeight = Math.max(1, height - panelY - LIST_BOTTOM);
		int visible = Math.max(1, (listHeight - 16) / (ROW_HEIGHT + ROW_GAP));

		int listWidth = panelWidth - CARD_WIDTH - 12;
		int listX = panelX + CARD_WIDTH + 12;

		drawGlassPanel(graphics, panelX, panelY, panelWidth, listHeight, 12, 1.0f, ACCENT);

		if (rows.isEmpty()) {
			RenderUtils.text(graphics, font, "Серверов пока нет", panelX + 14, panelY + 14, 0xFFE8E8F0);
			RenderUtils.text(graphics, font, "«Добавить» — адрес и название", panelX + 14, panelY + 26, 0xFF80808C);
		} else {
			ServerData selection = selected >= 0 && selected < rows.size() ? rows.get(selected).data : null;
			if (selection != null) {
				drawServerCard(graphics, rows.get(selected), panelX + 10, panelY + 8, CARD_WIDTH, listHeight - 16);
			}

			graphics.enableScissor(listX - 2, panelY + 4, listX + listWidth + 4, panelY + listHeight - 4);
			int y = panelY + 8;
			int index = 0;
			for (ServerRow row : rows) {
				row.y = y;
				if (index >= scroll && index < scroll + visible) {
					drawServerRow(graphics, row, index, listX, y, listWidth, mouseX, mouseY);
					y += ROW_HEIGHT + ROW_GAP;
				}
				index++;
			}
			graphics.disableScissor();
			drawScrollbar(graphics, listX + listWidth + 3, panelY + 8, listHeight - 16, scroll, visible, rows.size(), ACCENT);
		}

		chips.clear();
		ServerData selection = selected >= 0 && selected < rows.size() ? rows.get(selected).data : null;
		if (confirmDelete && selection != null) {
			chips.add(chip("удалить сервер", this::deleteSelected, true));
			chips.add(chip("отмена", () -> confirmDelete = false));
		} else {
			Chip join = chip("Играть", () -> joinServer(selection));
			join.enabled = selection != null;
			chips.add(join);
			chips.add(chip("Добавить", this::addServer));
			Chip edit = chip("Изменить", () -> editServer(selection));
			edit.enabled = selection != null;
			chips.add(edit);
			Chip delete = chip("Удалить", () -> confirmDelete = true);
			delete.enabled = selection != null;
			delete.danger = true;
			chips.add(delete);
			chips.add(chip("Обновить", this::reopen));
		}
		chips.add(chip("Назад", this::onClose));
		drawChipRow(graphics, width / 2, height - 36, 20, 5, ACCENT, mouseX, mouseY);
	
		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	private String stateOfRows() {
		if (rows.isEmpty()) {
			return "empty";
		}
		for (ServerRow row : rows) {
			if (row.data.state() == ServerData.State.PINGING) {
				return "pinging";
			}
		}
		return "ok";
	}

	/** Крупная карточка выбранного сервера: иконка, MOTD, игроки, пинг. */
	private void drawServerCard(GuiGraphicsExtractor graphics, ServerRow row, int x, int y, int w, int h) {
		ServerData data = row.data;

		cardFlash = ease(cardFlash, 0.0f, 0.12f);
		if (cardFlash > 0.02f) {
			RenderUtils.fillRounded(graphics, x, y, w, h, 10, RenderUtils.withAlpha(ACCENT, 0.10f * cardFlash));
		}

		// Иконка крупно; пока нет своей — «экран» с адресом
		int iconSize = Math.min(84, w - 24);
		int iconX = x + (w - iconSize) / 2;
		int iconY = y + 10;
		RenderUtils.drawSoftShadow(graphics, iconX - 2, iconY - 2, iconSize + 4, iconSize + 4, 8, 3);
		graphics.fill(iconX - 2, iconY - 2, iconX + iconSize + 2, iconY + iconSize + 2, 0x50000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, row.icon.textureLocation(),
				iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, 64, 64);

		String name = RenderUtils.clamp(font, data.name, w - 16);
		RenderUtils.textCentered(graphics, font, name, x + w / 2, iconY + iconSize + 6, 0xFFF4F4FA, false);
		String address = RenderUtils.clamp(font, data.ip, w - 16);
		RenderUtils.textCentered(graphics, font, address, x + w / 2, iconY + iconSize + 6 + font.lineHeight + 2,
				0xFF6B6B78, false);

		// MOTD или состояние
		int motdY = iconY + iconSize + 8 + font.lineHeight * 2 + 4;
		String info;
		int infoColor;
		switch (data.state()) {
			case PINGING -> {
				info = "опрос сервера…";
				infoColor = 0xFFFFC66C;
			}
			case UNREACHABLE -> {
				info = "не отвечает";
				infoColor = 0xFFFF8095;
			}
			case INCOMPATIBLE -> {
				info = RenderUtils.clamp(font, data.version.getString(), w - 16);
				infoColor = 0xFFFF8095;
			}
			case SUCCESSFUL -> {
				info = RenderUtils.clamp(font, data.motd.getString(), w - 16);
				infoColor = 0xFFA6A6B2;
			}
			default -> {
				info = RenderUtils.clamp(font, data.ip, w - 16);
				infoColor = 0xFFA6A6B2;
			}
		}
		RenderUtils.textCentered(graphics, font, info, x + w / 2, motdY, infoColor, false);

		// Низ карточки: игроки и пинг крупными
		int statsY = y + h - 16;
		if (data.state() == ServerData.State.SUCCESSFUL && data.players != null) {
			String players = data.players.online() + "/" + data.players.max();
			RenderUtils.textFlat(graphics, font, players, x + 8, statsY, 0xFF8DE06C);
		}
		if (data.ping > 0L) {
			String ping = data.ping + " мс";
			int color = data.ping < 100L ? 0xFF8DE06C : data.ping < 300L ? 0xFFFFC66C : 0xFFFF8095;
			RenderUtils.textFlat(graphics, font, ping, x + w - 8 - RenderUtils.width(font, ping), statsY, color);
		}
	}

	private void drawVersionPill(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		String label = "◆ " + ViaIntegration.currentVersionLabel();
		int pillWidth = Math.min(190, RenderUtils.width(font, label) + 20);
		int pillHeight = 16;
		int pillX = width - pillWidth - 6;
		int pillY = 6;
		boolean inside = mouseX >= pillX && mouseX < pillX + pillWidth
				&& mouseY >= pillY && mouseY < pillY + pillHeight;
		versionPillHover = ease(versionPillHover, inside ? 1.0f : 0.0f, 0.22);

		boolean via = ViaIntegration.available();
		int color = via ? 0xFF8DE06C : 0xFF9E9EAE;
		RenderUtils.fillRoundedBorder(graphics, pillX, pillY, pillWidth, pillHeight, pillHeight / 2,
				RenderUtils.mix(0x26FFFFFF, color, versionPillHover * 0.7f),
				RenderUtils.mix(0xD90C0C10, RenderUtils.withAlpha(color, 0xFF), 0.10f + 0.14f * versionPillHover));
		String shown = RenderUtils.clamp(font, label, pillWidth - 12);
		RenderUtils.textFlat(graphics, font, shown, pillX + (pillWidth - RenderUtils.width(font, shown)) / 2,
				pillY + (pillHeight - font.lineHeight) / 2,
				via ? RenderUtils.mix(0xFFD8E8C9, color, 0.4f + 0.4f * versionPillHover) : 0xFF9E9EAE);

		if (inside) {
			graphics.setTooltipForNextFrame(this.minecraft.font.split(Component.literal(via
							? "Версия для подключения — через ViaFabricPlus"
							: "ViaFabricPlus не найден: версия только для справки"),
					220), mouseX, mouseY);
		}

		versionPillBounds = new int[]{pillX, pillY, pillWidth, pillHeight};
	}

	private int @Nullable [] versionPillBounds;

	private void drawServerRow(GuiGraphicsExtractor graphics, ServerRow row, int index, int x, int y, int w,
	                           int mouseX, int mouseY) {
		ServerData data = row.data;
		boolean selectedRow = index == selected;
		boolean inside = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_HEIGHT;

		// «Лестница» появления + затухающая вспышка новой строки
		row.appear = ease(row.appear, 1.0f, 0.18f);
		float delay = Math.min(0.5f, index * 0.07f);
		float appear = Math.max(0.0f, Math.min(1.0f, (row.appear - delay) / Math.max(0.05f, 1.0f - delay)));
		row.flash = ease(row.flash, 0.0f, 0.05f);
		int slideX = Math.round((1.0f - appear) * 10.0f);

		row.hover = ease(row.hover, inside ? 1.0f : 0.0f, 0.22);

		int background = selectedRow
				? RenderUtils.mix(0xCC101015, RenderUtils.withAlpha(ACCENT, 0xFF), 0.20f + 0.08f * row.hover)
				: RenderUtils.mix(0xA0101013, 0x16FFFFFF, row.hover * 0.5f);
		int border = selectedRow
				? RenderUtils.withAlpha(ACCENT, 0.55f + 0.35f * row.hover)
				: RenderUtils.mix(0x10FFFFFF, ACCENT, row.hover * 0.25f);
		if (row.flash > 0.02f) {
			// Новая строка подсвечивается «волнами» — видно, куда добавился сервер
			float wave = 0.5f + 0.5f * (float) Math.sin(net.minecraft.util.Util.getMillis() / 160.0);
			border = RenderUtils.mix(border, 0xFF8DE06C, 0.4f * row.flash * (0.4f + 0.6f * wave));
		}
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
		int nameLimit = w - 22 - 44;
		String name = RenderUtils.clamp(font, data.name, nameLimit);
		RenderUtils.textFlat(graphics, font, name, textX, y + 5,
				RenderUtils.withAlpha(selectedRow ? 0xFFFFFFFF : 0xFFE8E8F0, appear));

		// Состояние — точкой цвета + короткой подписью
		int dotColor;
		String stateLabel;
		switch (data.state()) {
			case PINGING -> {
				dotColor = 0xFFFFC66C;
				stateLabel = "опрос";
			}
			case UNREACHABLE -> {
				dotColor = 0xFFFF8095;
				stateLabel = "нет связи";
			}
			case INCOMPATIBLE -> {
				dotColor = 0xFFFF8095;
				stateLabel = "версия";
			}
			case SUCCESSFUL -> {
				dotColor = 0xFF8DE06C;
				stateLabel = data.ping > 0 ? data.ping + " мс" : "онлайн";
			}
			default -> {
				dotColor = 0xFF6B6B78;
				stateLabel = "—";
			}
		}
		// «Дышащая» точка при опросе
		int dotAlpha = (int) (0xFF * appear * (data.state() == ServerData.State.PINGING
				? 0.5f + 0.5f * Math.abs(Math.sin(net.minecraft.util.Util.getMillis() / 300.0)) : 1.0f));
		graphics.fill(textX, y + 19, textX + 4, y + 23, (dotColor & 0x00FFFFFF) | (dotAlpha << 24));
		String shownState = RenderUtils.clamp(font, stateLabel, Math.max(24, w - 22 - RenderUtils.width(font, name) - 12));
		RenderUtils.textFlat(graphics, font, shownState, textX + 7, y + 18,
				RenderUtils.withAlpha(0xFF8A8A96, appear));

		// Игроки справа, если известны
		if (data.state() == ServerData.State.SUCCESSFUL && data.players != null) {
			String players = data.players.online() + "";
			RenderUtils.textFlat(graphics, font, players,
					x + w - 6 - RenderUtils.width(font, players), y + 6,
					RenderUtils.withAlpha(0xFF8DE06C, appear));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		if (clickChips(event)) {
			return true;
		}

		if (versionPillBounds != null) {
			double mx = event.x();
			double my = event.y();
			if (mx >= versionPillBounds[0] && mx < versionPillBounds[0] + versionPillBounds[2]
					&& my >= versionPillBounds[1] && my < versionPillBounds[1] + versionPillBounds[3]) {
				playClick();
				this.minecraft.gui.setScreen(new DreamcastVersionSelectScreen(this));
				return true;
			}
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
					joinServer(rows.get(index).data);
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
			this.minecraft.gui.setScreen(new DreamcastServersScreen(parent, lastAddedIp));
		}
	}

	private void joinServer(@Nullable ServerData data) {
		if (data == null) {
			return;
		}
		// Помним сервер: после кика экран отключения предложит реконнект
		com.dreamcast.client.util.AltsManager.rememberServer(data);
		ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(data.ip), data, false, null);
	}

	private void addServer() {
		ServerData editing = new ServerData("", "", ServerData.Type.OTHER);
		this.minecraft.gui.setScreen(new ManageServerScreen(this, Component.literal("Добавить сервер"),
				result -> {
					if (result) {
						ServerList servers = new ServerList(this.minecraft);
						servers.load();
						ServerData existing = servers.get(editing.ip);
						if (existing != null) {
							existing.copyNameIconFrom(editing);
							servers.save();
						} else {
							servers.add(editing, false);
							servers.save();
						}
						Notifications.ok("Серверы", "Сервер добавлен: " + editing.name);
					}
					// Передаём ip нового сервера — его строка «вспыхнет» в списке
					this.minecraft.gui.setScreen(new DreamcastServersScreen(parent, result ? editing.ip : lastAddedIp));
				}, editing));
	}

	private void editServer(@Nullable ServerData data) {
		if (data == null) {
			return;
		}
		ServerData editing = new ServerData(data.name, data.ip, ServerData.Type.OTHER);
		editing.copyFrom(data);
		this.minecraft.gui.setScreen(new ManageServerScreen(this, Component.literal("Изменить сервер"),
				result -> {
					if (result) {
						data.name = editing.name;
						data.ip = editing.ip;
						data.copyFrom(editing);
						ServerList servers = new ServerList(this.minecraft);
						servers.load();
						servers.save();
					}
					reopen();
				}, editing));
	}

	private void deleteSelected() {
		if (selected < 0 || selected >= rows.size()) {
			confirmDelete = false;
			return;
		}
		ServerData victim = rows.get(selected).data;
		ServerList servers = new ServerList(this.minecraft);
		servers.load();
		servers.remove(victim);
		servers.save();
		confirmDelete = false;
		Notifications.info("Серверы", "Сервер удалён: " + victim.name);
		reopen();
	}
}

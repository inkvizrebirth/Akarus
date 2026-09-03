package com.akarus.client.gui.screens;

import com.akarus.client.AkarusClient;
import com.akarus.client.util.RenderUtils;
import com.akarus.client.util.ViaIntegration;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
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
 * Экран мультиплеера в стиле клиента вместо ванильного JoinMultiplayerScreen.
 *
 * Список серверов — тот же servers.dat (класс {@link ServerList}), пинги — тот же
 * {@link ServerStatusPinger}, подключение — {@link ConnectScreen#startConnecting}.
 * Добавление/редактирование и быстрый вход открывают ванильные ManageServerScreen
 * и DirectJoinServerScreen: там простые формы, и они не ломают стиль списка.
 *
 * Справа сверху — пилюля версии ViaFabricPlus, нарисованная в нашем стиле
 * (никаких ванильных кнопок): открывает {@link AkarusVersionSelectScreen}.
 */
public class AkarusServersScreen extends AkarusScreen {

	private static final int ACCENT = 0xFF45E3FF;
	private static final int PANEL_WIDTH = 420;
	private static final int ROW_HEIGHT = 36;
	private static final int ROW_GAP = 4;
	private static final int LIST_TOP = 52;
	private static final int LIST_BOTTOM = 58;

	/** Строка сервера: данные + иконка + анимации. */
	private static final class ServerRow {
		final ServerData data;
		final FaviconTexture icon;

		float hover;
		int y;
		byte @Nullable [] uploadedIcon;

		ServerRow(ServerData data, FaviconTexture icon) {
			this.data = data;
			this.icon = icon;
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

	public AkarusServersScreen(Screen parent) {
		super("Сетевая игра");
		this.parent = parent;
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
			rows.add(new ServerRow(data,
					FaviconTexture.forServer(this.minecraft.getTextureManager(), data.ip)));
		}
		pingAll();
	}

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
						() -> this.minecraft.execute(this::uploadChangedIcons),
						() -> {
							data.setState(data.protocol == net.minecraft.SharedConstants.getCurrentVersion().protocolVersion()
									? ServerData.State.SUCCESSFUL
									: ServerData.State.INCOMPATIBLE);
							this.minecraft.execute(this::uploadChangedIcons);
						},
						EventLoopGroupHolder.remote(this.minecraft.options.useNativeTransport()));
			} catch (UnknownHostException error) {
				data.setState(ServerData.State.UNREACHABLE);
				data.motd = Component.literal("не удалось найти адрес");
			} catch (Exception error) {
				AkarusClient.LOGGER.warn("Пинг {} не удался", data.ip, error);
			}
		});
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

		graphics.centeredText(font, "Сетевая игра", width / 2, 18, 0xFFF4F4FA);
		graphics.centeredText(font, rows.size() + " сервер(ов) · двойной клик — подключиться",
				width / 2, 30, 0xFF9E9EAE);

		// Пилюля версии ViaFabricPlus — своя, в стиле клиента
		drawVersionPill(graphics, mouseX, mouseY);

		int panelWidth = Math.min(PANEL_WIDTH, width - 16);
		int x = width / 2 - panelWidth / 2;
		int listTop = LIST_TOP;
		listHeight = Math.max(1, height - listTop - LIST_BOTTOM);
		int visible = Math.max(1, listHeight / (ROW_HEIGHT + ROW_GAP));

		drawGlassPanel(graphics, x - 8, listTop - 8, panelWidth + 16, listHeight + 16, 10, 1.0f, ACCENT);

		if (rows.isEmpty()) {
			graphics.centeredText(font, "Серверов пока нет", width / 2, listTop + 12, 0xFFE8E8F0);
			graphics.centeredText(font, "«Добавить» — адрес и название, «Адрес» — быстрый вход",
					width / 2, listTop + 24, 0xFF80808C);
		} else {
			graphics.enableScissor(x - 4, listTop - 4, x + panelWidth + 4, listTop + listHeight + 4);
			int y = listTop;
			for (int i = 0; i < rows.size(); i++) {
				ServerRow row = rows.get(i);
				row.y = y;
				if (i >= scroll && i < scroll + visible) {
					drawServerRow(graphics, row, i, x, y, panelWidth, mouseX, mouseY);
					y += ROW_HEIGHT + ROW_GAP;
				}
			}
			graphics.disableScissor();
			drawScrollbar(graphics, x + panelWidth + 3, listTop, listHeight, scroll, visible, rows.size(), ACCENT);
		}

		chips.clear();
		ServerData selection = selected >= 0 && selected < rows.size() ? rows.get(selected).data : null;
		if (confirmDelete && selection != null) {
			chips.add(chip("удалить", this::deleteSelected, true));
			chips.add(chip("отмена", () -> confirmDelete = false));
		} else {
			Chip join = chip("Играть", () -> joinServer(selection));
			join.enabled = selection != null;
			chips.add(join);
			chips.add(chip("Добавить", this::addServer));
			Chip edit = chip("Изменить", () -> editServer(selection));
			edit.enabled = selection != null;
			chips.add(edit);
			chips.add(chip("Адрес", this::directConnect));
			Chip delete = chip("Удалить", () -> confirmDelete = true);
			delete.enabled = selection != null;
			delete.danger = true;
			chips.add(delete);
			// «Обновить» пересоздаёт экран целиком: так корректно умирают старые
			// пинги (removeAll в removed()) и иконки, без гонок с отрисовкой
			chips.add(chip("Обновить", this::reopen));
		}
		chips.add(chip("Назад", this::onClose));
		drawChipRow(graphics, width / 2, height - 44, 20, 5, ACCENT, mouseX, mouseY);
	}

	private void drawVersionPill(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		String label = "◆ " + ViaIntegration.currentVersionLabel();
		int pillWidth = Math.min(190, font.width(label) + 20);
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
		graphics.text(font, shown, pillX + (pillWidth - font.width(shown)) / 2,
				pillY + (pillHeight - font.lineHeight) / 2,
				via ? RenderUtils.mix(0xFFD8E8C9, color, 0.4f + 0.4f * versionPillHover) : 0xFF9E9EAE, false);

		if (inside) {
			graphics.setTooltipForNextFrame(this.minecraft.font.split(Component.literal(via
							? "Версия для подключения — через ViaFabricPlus"
							: "ViaFabricPlus не найден: версия только для справки"),
					220), mouseX, mouseY);
		}

		// Запоминаем границы для клика
		versionPillBounds = new int[]{pillX, pillY, pillWidth, pillHeight};
	}

	private int @Nullable [] versionPillBounds;

	private void drawServerRow(GuiGraphicsExtractor graphics, ServerRow row, int index, int x, int y, int w,
	                           int mouseX, int mouseY) {
		ServerData data = row.data;
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

		// Иконка 24×24 (иконки серверов часто мелкие — крупно рисовать не стоит)
		int iconX = x + 5;
		int iconY = y + (ROW_HEIGHT - 24) / 2;
		graphics.fill(iconX - 1, iconY - 1, iconX + 25, iconY + 25, 0x40000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, row.icon.textureLocation(),
				iconX, iconY, 0.0F, 0.0F, 24, 24, 64, 64);

		int textX = iconX + 28;
		int nameLimit = w - 28 - 64;

		graphics.text(font, RenderUtils.clamp(font, data.name, nameLimit), textX, y + 5,
				selectedRow ? 0xFFFFFFFF : 0xFFE8E8F0, false);

		String info;
		int infoColor = 0xFFA6A6B2;
		switch (data.state()) {
			case PINGING -> {
				info = "проверка…";
				infoColor = 0xFFFFC66C;
			}
			case UNREACHABLE -> {
				info = "не отвечает";
				infoColor = 0xFFFF8095;
			}
			case INCOMPATIBLE -> {
				info = data.version.getString();
				infoColor = 0xFFFF8095;
			}
			case SUCCESSFUL -> info = data.motd.getString();
			default -> info = data.ip;
		}
		graphics.text(font, RenderUtils.clamp(font, info, nameLimit), textX, y + 17, infoColor, false);

		// Справа: пинг и игроки (когда известны)
		if (data.state() == ServerData.State.SUCCESSFUL && data.players != null) {
			String players = data.players.online() + "/" + data.players.max();
			graphics.text(font, players, x + w - 8 - font.width(players), y + 5, 0xFF8DE06C, false);
		}
		if (data.ping > 0L) {
			String ping = data.ping + " мс";
			int pingColor = data.ping < 100L ? 0xFF8DE06C : data.ping < 300L ? 0xFFFFC66C : 0xFFFF8095;
			graphics.text(font, ping, x + w - 8 - font.width(ping), y + 17, pingColor, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (clickChips(event)) {
			return true;
		}

		// Пилюля версии
		if (versionPillBounds != null) {
			double mx = event.x();
			double my = event.y();
			if (mx >= versionPillBounds[0] && mx < versionPillBounds[0] + versionPillBounds[2]
					&& my >= versionPillBounds[1] && my < versionPillBounds[1] + versionPillBounds[3]) {
				playClick();
				this.minecraft.gui.setScreen(new AkarusVersionSelectScreen(this));
				return true;
			}
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
			this.minecraft.gui.setScreen(new AkarusServersScreen(parent));
		}
	}

	private void joinServer(@Nullable ServerData data) {
		if (data == null) {
			return;
		}
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
					}
					reopen();
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

	private void directConnect() {
		ServerData direct = new ServerData("Быстрый вход", "", ServerData.Type.OTHER);
		this.minecraft.gui.setScreen(new DirectJoinServerScreen(this, result -> {
			if (result) {
				ServerList servers = new ServerList(this.minecraft);
				servers.load();
				ServerData known = servers.get(direct.ip);
				joinServer(known != null ? known : direct);
			} else {
				reopen();
			}
		}, direct));
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
		reopen();
	}
}

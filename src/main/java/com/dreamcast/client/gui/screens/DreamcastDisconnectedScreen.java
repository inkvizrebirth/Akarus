package com.dreamcast.client.gui.screens;

import com.dreamcast.client.util.AltsManager;
import com.dreamcast.client.util.Notifications;
import com.dreamcast.client.util.RenderUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Экран «Вас отключили» с быстрым реконнектом.
 *
 * Кнопки: переподключиться к последнему серверу; то же самое, но сразу под
 * случайным ником (альт-режим — удобно, когда банят по нику); в список
 * серверов; в главное меню.
 */
public class DreamcastDisconnectedScreen extends DreamcastScreen {

	private static final int ACCENT = 0xFFFF5C7A;
	private final Screen parent;
	private final Component reason;

	public DreamcastDisconnectedScreen(Screen parent, Component title, Component reason) {
		super(title.getString());
		this.parent = parent;
		this.reason = reason;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		drawDarkBackdrop(graphics);

		RenderUtils.textCentered(graphics, font, "Отключено", width / 2, height / 4 - 30, 0xFFF4F4FA, false);
		float pulse = 0.5f + 0.5f * (float) Math.sin(net.minecraft.util.Util.getMillis() / 700.0);
		int lineW = 64;
		graphics.fill(width / 2 - lineW / 2, height / 4 - 14, width / 2 + lineW / 2, height / 4 - 13,
				RenderUtils.withAlpha(ACCENT, 0.3f + 0.5f * pulse));

		// Причина: короткие строки по центру
		var wrapped = this.minecraft.font.split(reason, Math.min(420, width - 40));
		int y = height / 4;
		for (var line : wrapped) {
			graphics.text(font, line, width / 2 - font.width(line) / 2, y, 0xFF9E9EAE);
			y += font.lineHeight + 1;
		}

		ServerData last = AltsManager.lastServer();
		chips.clear();
		if (last != null && last.ip != null && !last.ip.isEmpty()) {
			chips.add(chip("Переподключиться", () -> reconnect(last)));
			chips.add(chip("С новым ником", () -> {
				String nick = AltsManager.randomNick();
				AltsManager.add(nick);
				if (AltsManager.loginAs(nick)) {
					Notifications.ok("Аккаунты", "Переподключение как " + nick);
				}
				reconnect(last);
			}));
		}
		chips.add(chip("Серверы", () -> {
			if (this.minecraft != null) {
				this.minecraft.gui.setScreen(new DreamcastServersScreen(new DreamcastMenuScreen()));
			}
		}));
		chips.add(chip("В меню", this::onClose));
		drawChipRow(graphics, width / 2, height / 2 + 40, 20, 5, ACCENT, mouseX, mouseY);

		// Фирменная волна клика — поверх всего содержимого
		RenderUtils.drawClickWaves(graphics, ACCENT);
	}

	private void reconnect(ServerData data) {
		if (this.minecraft == null) {
			return;
		}
		ConnectScreen.startConnecting(new DreamcastServersScreen(new DreamcastMenuScreen()),
				this.minecraft, ServerAddress.parseString(data.ip), data, false, null);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		RenderUtils.addClickWave(event.x(), event.y());
		if (clickChips(event)) {
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.gui.setScreen(parent != null ? parent : new DreamcastMenuScreen());
		}
	}
}

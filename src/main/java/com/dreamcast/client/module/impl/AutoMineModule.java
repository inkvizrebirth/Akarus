package com.dreamcast.client.module.impl;

import com.dreamcast.client.DreamcastClient;
import com.dreamcast.client.baritone.BaritoneBridge;
import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.settings.StringSetting;
import com.dreamcast.client.util.RotationHumanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * AutoMine — автоматическая добыча блоков через Baritone.
 *
 * Нужен установленный мод Baritone (Fabric, версия для 26.2).
 * Что и сколько добывать задаётся в настройках модуля.
 *
 * Режимы:
 * <ul>
 *   <li><b>Нормальный</b> — как раньше: {@code #mine}/API, повороты мгновенные;</li>
 *   <li><b>Легитный</b> — {@code #legitmine} (Baritone копает «как игрок»), а довороты
 *       к блокам дополнительно очеловечивает {@link RotationHumanizer}: плавно, с промахом
 *       и переменной скоростью, вместо мгновенных щелчков точно в центр блока.</li>
 * </ul>
 */
public class AutoMineModule extends Module {

	public static final String MODE_NORMAL = "normal";
	public static final String MODE_LEGIT = "legit";

	private final ModeSetting mode = mode("mode", "Режим", MODE_NORMAL,
			ModeSetting.option(MODE_NORMAL, "Нормальный"),
			ModeSetting.option(MODE_LEGIT, "Легитный"));

	private final StringSetting block = textSetting("block", "Блок", "diamond_ore");
	private final IntSetting amount = intSetting("amount", "Сколько", 0, 0, 512);
	private final BooleanSetting chatCommands = bool("chat_commands", "Командами чата", false);

	/** Степень «человечности» доворотов в легитном режиме (0 — почти без шума, 100 — максимум). */
	private final IntSetting randomization = intSetting("randomization", "Рандомизация, %", 80, 0, 100);

	public AutoMineModule() {
		super("auto_mine", "AutoMine", "Автоматическая добыча блоков через Baritone",
				ModuleCategory.MISC, GLFW.GLFW_KEY_B);
	}

	@Override
	protected void onEnable() {
		if (!BaritoneBridge.isAvailable()) {
			notify("§cBaritone не установлен — AutoMine недоступен");
			DreamcastClient.LOGGER.warn("Baritone не найден, AutoMine отключается");
			// Выключаем модуль изнутри, чтобы не оставлять его в «включённом» состоянии
			setEnabledSilently(false);
			return;
		}

		start();
	}

	@Override
	public void tick() {
		// Baritone сам управляет процессом, но цель выдаёт не каждый тик — в легитном
		// режиме здесь доживается «человеческий» доворот камеры
		if (isLegit()) {
			Minecraft client = Minecraft.getInstance();
			RotationHumanizer.tick(client == null ? null : client.player);
		}
	}

	@Override
	protected void onDisable() {
		BaritoneBridge.stop();
	}

	@Override
	public void onSettingsChanged() {
		// Изменили блок или количество — перезапускаем задачу
		if (isEnabled()) {
			start();
		}
	}

	private void start() {
		String target = block.get().trim();
		if (target.isEmpty()) {
			notify("§cУкажи блок для добычи в настройках AutoMine");
			return;
		}

		boolean legit = isLegit();
		int quantity = amount.get();
		// Легитный режим идёт только чат-командой: #legitmine — это отдельный процесс Baritone
		if (BaritoneBridge.mine(target, quantity, chatCommands.isEnabled() || legit, legit)) {
			notify("§7[Dreamcast] Добываю" + (legit ? " §f(легитно)§7" : "") + ": §f" + target
					+ (quantity > 0 ? " §7x§f" + quantity : " §7(без лимита)"));
		} else {
			notify("§c[Dreamcast] Не удалось запустить добычу");
		}
	}

	/** Работает ли модуль в легитном режиме (человечные повороты + #legitmine). */
	public boolean isLegit() {
		return mode.is(MODE_LEGIT);
	}

	/** Степень рандомизации доворотов для RotationHumanizer (0..100). */
	public int getRandomization() {
		return randomization.get();
	}

	private static void notify(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.gui == null) {
			return;
		}
		client.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}
}

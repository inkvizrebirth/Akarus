package com.akarus.client.baritone;

import com.akarus.client.AkarusClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.lang.reflect.Method;

/**
 * Мост к Baritone.
 *
 * Baritone не публикуется в публичный maven, поэтому мы не тащим его в зависимости
 * сборки, а обращаемся к нему через reflection, если мод установлен:
 *
 * <pre>
 * BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().mineByName(quantity, blocks)
 * BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(new GoalBlock(x, y, z)) + path()
 * BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything()
 * </pre>
 *
 * Если reflection по какой-то причине не сработал (другая версия API), есть запасной
 * путь — чат-команды Baritone: {@code #mine 16 diamond_ore} и {@code #stop}.
 */
public final class BaritoneBridge {

	private static final String MOD_ID = "baritone";

	private BaritoneBridge() {
	}

	/** Установлен ли Baritone в этой игре. */
	public static boolean isAvailable() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	/**
	 * Начинает добычу указанных блоков.
	 *
	 * @param block    что добывать, например {@code diamond_ore}
	 * @param quantity сколько предметов получить (0 — без ограничения)
	 * @param forceChat true — сразу использовать чат-команды, минуя API
	 */
	public static boolean mine(String block, int quantity, boolean forceChat) {
		if (!isAvailable()) {
			return false;
		}
		if (!forceChat && mineViaApi(block, quantity)) {
			return true;
		}
		return mineViaChat(block, quantity);
	}

	/**
	 * Задаёт точку назначения и запускает путь к ней.
	 *
	 * @param forceChat true — сразу использовать чат-команду, минуя API
	 */
	public static boolean goal(int x, int y, int z, boolean forceChat) {
		if (!isAvailable()) {
			return false;
		}
		if (!forceChat && goalViaApi(x, y, z)) {
			return true;
		}
		return goalViaChat(x, y, z);
	}

	/** Останавливает всё, что сейчас делает Baritone. */
	public static boolean stop() {
		if (!isAvailable()) {
			return false;
		}
		return stopViaApi() || stopViaChat();
	}

	// ------------------------------------------------------------------
	// Работа через API (reflection)
	// ------------------------------------------------------------------

	private static boolean mineViaApi(String block, int quantity) {
		try {
			Object mineProcess = getMineProcess();
			if (mineProcess == null) {
				return false;
			}
			Method mineByName = mineProcess.getClass().getMethod("mineByName", int.class, String[].class);
			mineByName.invoke(mineProcess, quantity, new String[]{block});
			return true;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AkarusClient.LOGGER.warn("Не удалось вызвать Baritone API, пробую чат-команду", exception);
			return false;
		}
	}

	private static boolean goalViaApi(int x, int y, int z) {
		try {
			Object baritone = getPrimaryBaritone();
			if (baritone == null) {
				return false;
			}

			Object process = baritone.getClass().getMethod("getCustomGoalProcess").invoke(baritone);

			// GoalBlock(int x, int y, int z) — цель в виде конкретного блока
			Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
			Object goal = goalBlockClass.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);

			Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
			process.getClass().getMethod("setGoal", goalClass).invoke(process, goal);
			process.getClass().getMethod("path").invoke(process);
			return true;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AkarusClient.LOGGER.warn("Не удалось задать цель через Baritone API, пробую чат-команду", exception);
			return false;
		}
	}

	/** Идёт ли Baritone прямо сейчас (для статуса в HUD). */
	public static boolean isPathing() {
		try {
			Object baritone = getPrimaryBaritone();
			if (baritone == null) {
				return false;
			}
			Object pathing = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
			Object result = pathing.getClass().getMethod("isPathing").invoke(pathing);
			return Boolean.TRUE.equals(result);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return false;
		}
	}

	private static boolean stopViaApi() {
		try {
			Object baritone = getPrimaryBaritone();
			if (baritone == null) {
				return false;
			}
			Object pathing = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
			Object cancelled = pathing.getClass().getMethod("cancelEverything").invoke(pathing);
			return Boolean.TRUE.equals(cancelled);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AkarusClient.LOGGER.warn("Не удалось остановить Baritone через API", exception);
			return false;
		}
	}

	private static Object getMineProcess() throws ReflectiveOperationException {
		Object baritone = getPrimaryBaritone();
		return baritone == null ? null : baritone.getClass().getMethod("getMineProcess").invoke(baritone);
	}

	private static Object getPrimaryBaritone() throws ReflectiveOperationException {
		Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
		Object provider = apiClass.getMethod("getProvider").invoke(null);
		return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
	}

	// ------------------------------------------------------------------
	// Запасной путь — чат-команды Baritone
	// ------------------------------------------------------------------

	private static boolean mineViaChat(String block, int quantity) {
		// Синтаксис Baritone: mine [количество] <блоки...>
		return sendChat("#mine " + quantity + " " + block);
	}

	private static boolean goalViaChat(int x, int y, int z) {
		// Синтаксис Baritone: goal <x> <y> <z>
		return sendChat("#goal " + x + " " + y + " " + z);
	}

	private static boolean stopViaChat() {
		return sendChat("#stop");
	}

	private static boolean sendChat(String message) {
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener connection = client.getConnection();
		if (connection == null || client.player == null) {
			return false;
		}
		connection.sendChat(message);
		return true;
	}
}

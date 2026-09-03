package com.akarus.client.baritone;

import com.akarus.client.AkarusClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Мост к Baritone.
 *
 * Baritone не публикуется в публичный maven, поэтому в зависимости сборки его нет —
 * обращаемся к нему через reflection, если мод установлен.
 *
 * Почему не обычный {@code getClass().getMethod(...)}
 * ---------------------------------------------------
 * Все методы Baritone объявлены в интерфейсах пакета {@code baritone.api}, а
 * реализация — package-private класс. {@code obj.getClass().getMethod("getMineProcess")}
 * такой метод находит, но вызвать его через reflection нельзя: класс не виден
 * нашему загрузчику, получается {@code IllegalAccessException}. Поэтому метод
 * ищем среди ВСЕХ суперклассов и интерфейсов и берём тот, что объявлен в публичном
 * типе (интерфейсе {@code baritone.api.*}) — он вызывается без ограничений.
 *
 * Ещё два момента:
 * <ul>
 *   <li>признак установки — наличие класса {@code baritone.api.BaritoneAPI}, а не
 *       {@code FabricLoader.isModLoaded("baritone")}: Baritone ставится по-разному
 *       (библиотекой, через Mod Menu, в составе других клиентов), и имя мода тогда
 *       может не совпасть;</li>
 *   <li>если рефлексивный путь не сработал (обновили API), используем чат-команды Baritone:
 *       {@code #goto 100 64 -200}, {@code #mine 16 diamond_ore}, {@code #stop}.</li>
 * </ul>
 */
public final class BaritoneBridge {

	private static final String API_CLASS = "baritone.api.BaritoneAPI";
	private static final String GOAL_PACKAGE = "baritone.api.pathing.goals.";

	/** Классы и методы Baritone дороговаты для поиска каждый кадр — кэшируем. */
	private static final List<String> CLASS_NAMES = new ArrayList<>();
	private static final List<Class<?>> CLASSES = new ArrayList<>();
	private static final List<Method> METHOD_CACHE = new ArrayList<>();
	private static final List<String> METHOD_KEYS = new ArrayList<>();

	private static Boolean installed;

	private BaritoneBridge() {
	}

	/** Установлен ли Baritone в этой игре. */
	public static boolean isAvailable() {
		Boolean cached = installed;
		if (cached != null) {
			return cached;
		}
		boolean found = classFor(API_CLASS) != null;
		installed = found;
		if (!found) {
			AkarusClient.LOGGER.info("Baritone не найден: модули, которые на него опираются, будут работать через чат-команды");
		}
		return found;
	}

	// ------------------------------------------------------------------
	// Публичные операции
	// ------------------------------------------------------------------

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
		// Синтаксис Baritone: #mine [количество] <блоки...>
		return sendChat("#mine " + quantity + " " + block);
	}

	/**
	 * Задаёт точку назначения и запускает поиск пути.
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
		// Синтаксис Baritone: #goto <x> <y> <z>
		return sendChat("#goto " + x + " " + y + " " + z);
	}

	/** Останавливает всё, что сейчас делает Baritone. */
	public static boolean stop() {
		if (!isAvailable()) {
			return false;
		}
		return stopViaApi() || sendChat("#stop");
	}

	/** Идёт ли Baritone прямо сейчас (для статуса в HUD). */
	public static boolean isPathing() {
		try {
			Object baritone = primaryBaritone();
			Object pathing = pathingOf(baritone);
			if (pathing == null) {
				return false;
			}
			return Boolean.TRUE.equals(invoke(pathing, "isPathing"))
					|| Boolean.TRUE.equals(invoke(pathing, "isInProgress"));
		} catch (RuntimeException | ReflectiveOperationException exception) {
			return false;
		}
	}

	// ------------------------------------------------------------------
	// Работа через API
	// ------------------------------------------------------------------

	private static boolean mineViaApi(String block, int quantity) {
		try {
			Object baritone = primaryBaritone();
			Object mineProcess = invoke(baritone, "getMineProcess");
			if (mineProcess == null) {
				return false;
			}
			return invokeVoid(mineProcess, "mineByName", quantity, new String[]{block});
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AkarusClient.LOGGER.warn("Не удалось вызвать Baritone API, пробую чат-команду", exception);
			return false;
		}
	}

	private static boolean goalViaApi(int x, int y, int z) {
		try {
			Object baritone = primaryBaritone();
			if (baritone == null) {
				return false;
			}
			Object goal = createGoal(x, y, z);
			if (goal == null) {
				return false;
			}

			// Основной путь: ICustomGoalProcess#setGoalAndPath — ставит цель и сразу
			// запускает поиск пути одной операцией.
			Object customProcess = invoke(baritone, "getCustomGoalProcess");
			if (customProcess != null && invokeVoid(customProcess, "setGoalAndPath", goal)) {
				return true;
			}

			// Старые версии API: setGoal + path()
			Object pathing = pathingOf(baritone);
			if (pathing == null) {
				return false;
			}
			if (invokeVoid(pathing, "setGoalAndPath", goal)) {
				return true;
			}
			if (invokeVoid(pathing, "setGoal", goal)) {
				// методы называются по-разному в разных версиях Baritone
				invokeVoid(pathing, "path");
				invokeVoid(pathing, "tryPathToCurrentlyCalculating");
				return true;
			}
			return false;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AkarusClient.LOGGER.warn("Не удалось задать цель через Baritone API, пробую чат-команду", exception);
			return false;
		}
	}

	private static boolean stopViaApi() {
		try {
			Object baritone = primaryBaritone();
			Object pathing = pathingOf(baritone);
			if (pathing == null) {
				return false;
			}
			boolean stopped = invokeVoid(pathing, "forceStop");
			stopped |= invokeVoid(pathing, "cancelPath");
			stopped |= invokeVoid(pathing, "cancelEverything");
			return stopped;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			AkarusClient.LOGGER.warn("Не удалось остановить Baritone через API", exception);
			return false;
		}
	}

	/**
	 * @return {@code IPathing} (новые версии) или {@code IPathingBehavior} (старые)
	 */
	private static Object pathingOf(Object baritone) throws ReflectiveOperationException {
		Object pathing = invoke(baritone, "getPathing");
		return pathing != null ? pathing : invoke(baritone, "getPathingBehavior");
	}

	private static Object primaryBaritone() throws ReflectiveOperationException {
		Class<?> apiClass = classFor(API_CLASS);
		if (apiClass == null) {
			return null;
		}
		Object provider = invokeStatic(apiClass, "getProvider");
		return provider == null ? null : invoke(provider, "getPrimaryBaritone");
	}

	/**
	 * Создаёт цель «дойти до блока».
	 *
	 * {@code GoalGetToBlock} — встать рядом с блоком (то, что нужно для AutoWalk);
	 * {@code GoalBlock} — встать на сам блок. Пробуем оба: имена не менялись,
	 * но пакеты в форках бывают разные.
	 */
	private static Object createGoal(int x, int y, int z) {
		for (String goalName : new String[]{"GoalGetToBlock", "GoalBlock"}) {
			Class<?> goalClass = classFor(GOAL_PACKAGE + goalName);
			if (goalClass == null) {
				continue;
			}
			try {
				Constructor<?> constructor = goalClass.getConstructor(int.class, int.class, int.class);
				return constructor.newInstance(x, y, z);
			} catch (ReflectiveOperationException | RuntimeException exception) {
				AkarusClient.LOGGER.debug("Цель {} не подошла", goalName, exception);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Рефлексия, дружелюбная к интерфейсам
	// ------------------------------------------------------------------

	/** Вызывает метод без аргументов; null, если класса/метода нет. */
	private static Object invoke(Object receiver, String name, Object... args) throws ReflectiveOperationException {
		if (receiver == null) {
			return null;
		}
		Method method = findMethod(receiver.getClass(), name, args);
		if (method == null) {
			return null;
		}
		return method.invoke(receiver, args);
	}

	/** true, если метод нашёлся и отработал без исключения. */
	private static boolean invokeVoid(Object receiver, String name, Object... args) throws ReflectiveOperationException {
		if (receiver == null) {
			return false;
		}
		Method method = findMethod(receiver.getClass(), name, args);
		if (method == null) {
			return false;
		}
		method.invoke(receiver, args);
		return true;
	}

	private static Object invokeStatic(Class<?> type, String name) throws ReflectiveOperationException {
		Method method = findMethod(type, name);
		if (method == null || !Modifier.isStatic(method.getModifiers())) {
			return null;
		}
		return method.invoke(null);
	}

	/**
	 * Ищет публичный метод по имени и количеству/типам аргументов — среди класса,
	 * его суперклассов и всех интерфейсов, причём приоритет у объявленных в
	 * публичных типах (см. шапку класса).
	 */
	private static Method findMethod(Class<?> type, String name, Object... args) {
		String key = key(type, name, args.length);
		int cached = METHOD_KEYS.indexOf(key);
		if (cached >= 0) {
			return METHOD_CACHE.get(cached);
		}
		Method found = searchMethod(type, name, args);
		METHOD_KEYS.add(key);
		METHOD_CACHE.add(found);
		return found;
	}

	private static Method searchMethod(Class<?> type, String name, Object... args) {
		Method best = null;
		Deque<Class<?>> queue = new ArrayDeque<>();
		List<Class<?>> seen = new ArrayList<>();
		queue.add(type);

		while (!queue.isEmpty()) {
			Class<?> current = queue.poll();
			if (current == null || current == Object.class || seen.contains(current)) {
				continue;
			}
			seen.add(current);

			for (Method method : current.getDeclaredMethods()) {
				if (!method.getName().equals(name) || method.getParameterCount() != args.length
						|| !argumentsMatch(method, args)) {
					continue;
				}
				boolean accessible = Modifier.isPublic(method.getModifiers())
						&& Modifier.isPublic(current.getModifiers());
				if (!accessible) {
					continue;
				}
				try {
					method.setAccessible(true);
				} catch (RuntimeException exception) {
					// setAccessible может быть запрещён модулем — вызов через публичный
					// интерфейс всё равно пройдёт, поэтому просто игнорируем
				}
				// интерфейс (публичный API Baritone) предпочтительнее реализации
				if (current.isInterface()) {
					return method;
				}
				if (best == null) {
					best = method;
				}
			}

			for (Class<?> iface : current.getInterfaces()) {
				queue.add(iface);
			}
			queue.add(current.getSuperclass());
		}

		return best;
	}

	private static boolean argumentsMatch(Method method, Object... args) {
		Class<?>[] types = method.getParameterTypes();
		for (int i = 0; i < types.length; i++) {
			Object arg = args[i];
			if (arg == null) {
				if (types[i].isPrimitive()) {
					return false;
				}
				continue;
			}
			if (!wrapped(types[i]).isAssignableFrom(wrapped(arg.getClass()))) {
				return false;
			}
		}
		return true;
	}

	/** int.class → Integer.class, чтобы сравнивать аргументы-обёртки с примитивными параметрами. */
	private static Class<?> wrapped(Class<?> type) {
		if (!type.isPrimitive()) {
			return type;
		}
		if (type == int.class) {
			return Integer.class;
		}
		if (type == long.class) {
			return Long.class;
		}
		if (type == double.class) {
			return Double.class;
		}
		if (type == float.class) {
			return Float.class;
		}
		if (type == boolean.class) {
			return Boolean.class;
		}
		if (type == byte.class) {
			return Byte.class;
		}
		if (type == short.class) {
			return Short.class;
		}
		return Character.class;
	}

	private static String key(Class<?> type, String name, int parameters) {
		return type.getName() + '#' + name + '/' + parameters;
	}

	private static Class<?> classFor(String name) {
		int index = CLASS_NAMES.indexOf(name);
		if (index >= 0) {
			return CLASSES.get(index);
		}
		Class<?> found = null;
		try {
			found = Class.forName(name, false, BaritoneBridge.class.getClassLoader());
		} catch (ClassNotFoundException | RuntimeException | LinkageError exception) {
			// Baritone нет — это нормальная ситуация
		}
		CLASS_NAMES.add(name);
		CLASSES.add(found);
		return found;
	}

	// ------------------------------------------------------------------
	// Запасной путь — чат-команды Baritone
	// ------------------------------------------------------------------

	private static boolean sendChat(String message) {
		Minecraft client = Minecraft.getInstance();
		ClientPacketListener connection = client == null ? null : client.getConnection();
		if (connection == null || client.player == null) {
			notify("§c[Akarus] Нет подключения к серверу — Baritone не получить команду");
			return false;
		}
		connection.sendChat(message);
		return true;
	}

	private static void notify(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.gui == null) {
			return;
		}
		client.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}
}

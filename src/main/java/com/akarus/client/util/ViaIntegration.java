package com.akarus.client.util;

import com.akarus.client.AkarusClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Мост к ViaFabricPlus через рефлексию.
 *
 * ViaFabricPlus — мод необязательный (мы вшиваем его релизный jar во вложенные
 * {@code META-INF/jars}, но у игрока может стоять и своя копия, и никакой). Поэтому
 * компилироваться против его классов нельзя — только рефлексия и тихая деградация:
 * без виа кнопка версии показывает нативную версию, а вместо списка — подсказку.
 *
 * Целевой API VFP (проверено на 4.6.3, MC 26.2):
 * <ul>
 *   <li>{@code com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator}
 *       — {@code getTargetVersion()/setTargetVersion(ProtocolVersion)};</li>
 *   <li>{@code com.viaversion.viaversion.api.protocol.version.ProtocolVersion}
 *       — {@code getReversedProtocols()/getProtocol(int)/getName()/getVersionString()}.</li>
 * </ul>
 * Для старых сборок VFP (до 4.0) пробуется и старый пакет {@code de.florianmichael.…}.
 */
public final class ViaIntegration {

	/** Нативная версия, показываемая когда виа нет. */
	private static final String NATIVE_LABEL = "26.2";

	private ViaIntegration() {
	}

	public static boolean available() {
		return protocolTranslatorClass() != null;
	}

	/** Человекочитаемое имя выбранного протокола: «1.8.9», «Auto Detect (1.7+ servers)»… */
	public static String currentVersionLabel() {
		Object version = getTargetVersion();
		if (version == null) {
			return nativeVersionLabel();
		}
		String name = invokeString(version, "getName");
		if (name == null || name.isBlank()) {
			name = invokeString(version, "getVersionString");
		}
		return name == null || name.isBlank() ? nativeVersionLabel() : name;
	}

	/** Версия из SharedConstants (она же — что реально «на клиенте»), если VFP нет. */
	public static String nativeVersionLabel() {
		try {
			Class<?> shared = Class.forName("net.minecraft.SharedConstants");
			Object version = shared.getMethod("getCurrentVersion").invoke(null);
			String name = invokeString(version, "name");
			if (name != null && !name.isBlank()) {
				return name;
			}
		} catch (ReflectiveOperationException exception) {
			AkarusClient.LOGGER.debug("SharedConstants.getCurrentVersion() недоступна", exception);
		}
		return NATIVE_LABEL;
	}

	/** Протокол, выбранный сейчас (объект ProtocolVersion VFP), или null. */
	public static Object getTargetVersion() {
		Class<?> translator = protocolTranslatorClass();
		if (translator == null) {
			return null;
		}
		try {
			return translator.getMethod("getTargetVersion").invoke(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	/** Id выбранного протокола (числовой), или -1. */
	public static int currentProtocolId() {
		Object version = getTargetVersion();
		if (version == null) {
			return -1;
		}
		Object id = invoke(version, "getId");
		return id instanceof Integer intValue ? intValue : -1;
	}

	public static String nativeProtocolLabel() {
		Object nativeVersion = getNativeVersion();
		String name = nativeVersion != null ? invokeString(nativeVersion, "getName") : null;
		return name == null || name.isBlank() ? NATIVE_LABEL : name;
	}

	private static Object getNativeVersion() {
		Class<?> translator = protocolTranslatorClass();
		if (translator == null) {
			return null;
		}
		try {
			return translator.getField("NATIVE_VERSION").get(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	/** Публичный кортеж варианта для UI-экрана. */
	public record VersionEntry(int id, String name, boolean current) {
	}

	/** Собирает список протоколов: сначала имена, потом id, сверка с текущим. */
	public static List<VersionEntry> collectVersions() {
		List<VersionEntry> result = new ArrayList<>();
		Class<?> protocolClass = findClass("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
		if (protocolClass == null) {
			return result;
		}

		Object reversed = invokeStatic(protocolClass, "getReversedProtocols");
		if (!(reversed instanceof Iterable<?> iterable)) {
			return result;
		}

		Object current = getTargetVersion();
		Object currentId = current != null ? invoke(current, "getId") : null;

		for (Object protocol : iterable) {
			String name = invokeString(protocol, "getName");
			if (name == null) {
				name = invokeString(protocol, "getVersionString");
			}
			Object id = invoke(protocol, "getId");
			if (name == null || name.isBlank() || !(id instanceof Integer protocolId)) {
				continue;
			}
			result.add(new VersionEntry(protocolId, name, id.equals(currentId)));
		}
		return result;
	}

	/** Возвращает протокол по id (для выбора) или null. */
	public static Object resolveProtocol(int id) {
		Class<?> protocolClass = findClass("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
		if (protocolClass == null) {
			return null;
		}
		Object resolved = invokeStaticWithArg(protocolClass, "getProtocol", int.class, id);
		return resolved;
	}

	/** Меняет целевой протокол (то, что делает «Via Version Selector» VFP). */
	public static boolean setVersion(int protocolId) {
		Object protocol = resolveProtocol(protocolId);
		Class<?> translator = protocolTranslatorClass();
		if (protocol == null || translator == null) {
			return false;
		}
		try {
			Class<?> protocolClass = protocol.getClass();
			// точный сигнатурный тип — интерфейс-предок; ищем anySingle «setTargetVersion(X)»
			for (Method method : translator.getMethods()) {
				if (method.getName().equals("setTargetVersion")
						&& method.getParameterCount() == 1
						&& method.getParameterTypes()[0].isInstance(protocol)) {
					method.invoke(null, protocol);
					return true;
				}
			}
		} catch (ReflectiveOperationException exception) {
			AkarusClient.LOGGER.warn("Не удалось сменить версию через VFP", exception);
		}
		return false;
	}

	/** «Auto Detect» для новых серверов — специальный вариант VFP (id == -2). */
	public static boolean setAutoDetect() {
		Class<?> translator = protocolTranslatorClass();
		if (translator == null) {
			return false;
		}
		try {
			Object auto = translator.getField("AUTO_DETECT_PROTOCOL").get(null);
			if (auto == null) {
				return false;
			}
			for (Method method : translator.getMethods()) {
				if (method.getName().equals("setTargetVersion")
						&& method.getParameterCount() == 1
						&& method.getParameterTypes()[0].isInstance(auto)) {
					method.invoke(null, auto);
					return true;
				}
			}
		} catch (ReflectiveOperationException exception) {
			AkarusClient.LOGGER.warn("Не удалось включить автоопределение версии", exception);
		}
		return false;
	}

	/** Нативный протокол клиента — «вернуть как есть» (26.2). */
	public static boolean setNative() {
		Object nativeVersion = getNativeVersion();
		Class<?> translator = protocolTranslatorClass();
		if (nativeVersion == null || translator == null) {
			return false;
		}
		try {
			for (Method method : translator.getMethods()) {
				if (method.getName().equals("setTargetVersion")
						&& method.getParameterCount() == 1
						&& method.getParameterTypes()[0].isInstance(nativeVersion)) {
					method.invoke(null, nativeVersion);
					return true;
				}
			}
		} catch (ReflectiveOperationException exception) {
			AkarusClient.LOGGER.warn("Не удалось вернуть нативную версию", exception);
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Рефлекс-помощники
	// ------------------------------------------------------------------

	private static Class<?> protocolTranslatorClass() {
		return findClass(
				"com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator");
	}

	private static Class<?> findClass(String... candidates) {
		for (String name : candidates) {
			try {
				return Class.forName(name);
			} catch (ClassNotFoundException ignored) {
				// пробуем следующий
			}
		}
		return null;
	}

	private static Object invoke(Object target, String method) {
		try {
			return target.getClass().getMethod(method).invoke(target);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	private static String invokeString(Object target, String method) {
		Object value = invoke(target, method);
		return value == null ? null : String.valueOf(value);
	}

	private static Object invokeStatic(Class<?> type, String method) {
		try {
			return type.getMethod(method).invoke(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	private static Object invokeStaticWithArg(Class<?> type, String method, Class<?> argType, Object arg) {
		try {
			return type.getMethod(method, argType).invoke(null, arg);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}
}

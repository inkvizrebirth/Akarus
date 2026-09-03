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
 * без виа пилюля версии показывает нативную версию, а вместо списка — подсказку.
 *
 * Целевой API VFP 4.6.3 (MC 26.2), проверен по исходникам:
 * <ul>
 *   <li>{@code com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator}
 *       — {@code getTargetVersion()/setTargetVersion(ProtocolVersion)},
 *       поля {@code NATIVE_VERSION} и {@code AUTO_DETECT_PROTOCOL};</li>
 *   <li>{@code com.viaversion.viaversion.api.protocol.version.ProtocolVersion}
 *       — {@code getReversedProtocols()} и {@code getName()}.
 *       Внимание: метода {@code getId()} в современных ViaVersion НЕТ — id берётся
 *       из {@code getOriginalVersion()}, но мы вообще не возимся с числами: список
 *       держит сами объекты ProtocolVersion и сравнивает их по ссылкам/equals.</li>
 * </ul>
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

	/**
	 * Правда ли, что сейчас выбрано автоопределение (специальный объект
	 * {@code AUTO_DETECT_PROTOCOL} с id -2). Без VFP — false.
	 */
	public static boolean isAutoDetect() {
		Object current = getTargetVersion();
		Object auto = autoDetectProtocol();
		return current != null && auto != null && current == auto;
	}

	/**
	 * VFP запрещает смену версии, пока открыто соединение с сервером (в том числе
	 * одиночный мир). В таком состоянии список просто подсвечивает блокировку.
	 */
	public static boolean canChangeVersionNow() {
		try {
			Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
			Object client = minecraft.getMethod("getInstance").invoke(null);
			Object connection = minecraft.getMethod("getConnection").invoke(client);
			return connection == null;
		} catch (ReflectiveOperationException exception) {
			return true;
		}
	}

	/** Публичный вариант для UI-экрана: имя + сам объект протокола + признак «текущий». */
	public record VersionEntry(String name, Object protocol, boolean current) {
	}

	/**
	 * Список доступных протоколов (от новых к старым), включая служебные записи
	 * VFP вроде диапазонов версий. Никаких числовых id — только живые объекты.
	 */
	public static List<VersionEntry> collectVersions() {
		List<VersionEntry> result = new ArrayList<>();
		Class<?> protocolClass = protocolVersionClass();
		if (protocolClass == null) {
			return result;
		}

		Object reversed = invokeStatic(protocolClass, "getReversedProtocols");
		if (!(reversed instanceof Iterable<?> iterable)) {
			return result;
		}

		Object current = getTargetVersion();
		for (Object protocol : iterable) {
			String name = invokeString(protocol, "getName");
			if (name == null || name.isBlank()) {
				continue;
			}
			result.add(new VersionEntry(name, protocol, protocol.equals(current)));
		}
		return result;
	}

	/** Меняет целевой протокол (то, что делает «Via Version Selector» VFP). */
	public static boolean setVersion(Object protocol) {
		if (protocol == null) {
			return false;
		}
		return invokeSetTargetVersion(protocol);
	}

	/** «Auto Detect» для новых серверов — специальный вариант VFP. */
	public static boolean setAutoDetect() {
		Object auto = autoDetectProtocol();
		return auto != null && invokeSetTargetVersion(auto);
	}

	/** Нативный протокол клиента — «вернуть как есть» (26.2). */
	public static boolean setNative() {
		Object nativeVersion = nativeProtocol();
		return nativeVersion != null && invokeSetTargetVersion(nativeVersion);
	}

	// ------------------------------------------------------------------
	// Рефлекс-помощники
	// ------------------------------------------------------------------

	private static boolean invokeSetTargetVersion(Object protocol) {
		Class<?> translator = protocolTranslatorClass();
		if (translator == null) {
			return false;
		}
		try {
			// Ищем статический setTargetVersion(X), где X принимает наш протокол.
			// Сигнатурный тип может быть и интерфейсом-предком, и самим ProtocolVersion —
			// перебираем все объявления и берём подходящий.
			Method fallback = null;
			for (Method method : translator.getMethods()) {
				if (!method.getName().equals("setTargetVersion") || method.getParameterCount() != 1) {
					continue;
				}
				Class<?> parameter = method.getParameterTypes()[0];
				if (parameter.isInstance(protocol)) {
					method.invoke(null, protocol);
					return true;
				}
				if (fallback == null && parameter.isAssignableFrom(protocol.getClass())) {
					fallback = method;
				}
			}
			if (fallback != null) {
				fallback.invoke(null, protocol);
				return true;
			}
		} catch (ReflectiveOperationException exception) {
			AkarusClient.LOGGER.warn("Не удалось сменить версию через VFP", exception);
		}
		return false;
	}

	private static Object autoDetectProtocol() {
		Class<?> translator = protocolTranslatorClass();
		if (translator == null) {
			return null;
		}
		try {
			return translator.getField("AUTO_DETECT_PROTOCOL").get(null);
		} catch (ReflectiveOperationException exception) {
			return null;
		}
	}

	private static Object nativeProtocol() {
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

	private static Class<?> protocolTranslatorClass() {
		// Современный пакет VFP 4.x; старые сборки (до 4.0) жили в de.florianmichael
		return findClass(
				"com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator",
				"de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator");
	}

	private static Class<?> protocolVersionClass() {
		return findClass("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
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
}

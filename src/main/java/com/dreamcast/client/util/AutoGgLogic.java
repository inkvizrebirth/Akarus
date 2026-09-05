package com.dreamcast.client.util;

/**
 * Логика AutoGG: кому, когда и что писать после убийства.
 *
 * <p>Всё, что можно проверить без игры, вынесено сюда: момент «цель умерла от
 * нашей руки», форматирование строки и sanitation. Сам модуль только читает мир
 * и дергает чат — так правила остаются тестируемыми (см. {@code AutoGgLogicTest}),
 * а не вросшими в {@code tick()}.</p>
 */
public final class AutoGgLogic {

	/** Максимальная длина строки чата: серверы режут длинные сообщения, а ваниль
	 *  и вовсе падает на пустых — ограничиваемся с запасом. */
	public static final int MAX_MESSAGE_LENGTH = 250;

	/** Плейсхолдер ника цели (оба имени — историческое {@code %player%} и явное {@code %name%}). */
	private static final String[] NAME_TOKENS = {"%player%", "%name%", "{player}", "{name}"};

	private AutoGgLogic() {
	}

	/**
	 * Наше ли это убийство: цель умерла в пределах окна после последнего удара.
	 *
	 * @param lastHitMs когда мы последний раз ударили цель (0 — не били)
	 * @param windowMs   окно «смерть всё ещё от меня»
	 */
	public static boolean ourKill(long nowMs, long lastHitMs, long windowMs) {
		if (lastHitMs <= 0L || windowMs <= 0L) {
			return false;
		}
		long since = nowMs - lastHitMs;
		// since < 0 — часы «прыгнули» (смена таймзоны/синхронизация): считаем свежим
		return since <= windowMs;
	}

	/** Смерть подтверждена: сущности нет в мире, она удалена или не жива. */
	public static boolean dead(boolean present, boolean alive, boolean removed) {
		return !present || removed || !alive;
	}

	/** Пора отправлять (задержка вышла). */
	public static boolean due(long nowMs, long sendAtMs) {
		return nowMs >= sendAtMs;
	}

	/**
	 * Подстановка ника и чистка сообщения: плейсхолдеры, переводы строк, длина.
	 *
	 * <p>Переводы строк вырезаются намеренно: текст настройки приходит из меню,
	 * а сообщение с {@code \n} в чат-пакете — это уже попытка отправить две
	 * строки (или команду), что серверы считают спамом/инъекцией.</p>
	 */
	public static String format(String template, String name) {
		String text = template == null ? "" : template;
		String who = name == null ? "" : name;
		for (String token : NAME_TOKENS) {
			text = text.replace(token, who);
		}
		text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
		text = text.trim();
		if (text.length() > MAX_MESSAGE_LENGTH) {
			text = text.substring(0, MAX_MESSAGE_LENGTH);
		}
		return text;
	}

	/** Есть ли смысл писать: пустое (после подстановки) сообщение — не сообщение. */
	public static boolean sendable(String message) {
		return message != null && !message.isBlank();
	}

	/** Задержка в мс: отрицательную считаем нулевой, верхний предел — на совести настройки. */
	public static long delayMillis(int delayMs) {
		return Math.max(0, delayMs);
	}
}

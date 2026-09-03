package com.dreamcast.client.util;

import com.dreamcast.client.DreamcastClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Открыть папку (или файл) в системном проводнике — без AWT и без Platform API игры.
 *
 * Делаем это сами по {@code os.name}: {@code Desktop} на headless-JVM бросает
 * UnsupportedOperationException, а {@code Util.getOperatingSystem().open(...)} в 26.2
 * не светится в публичном API. Обычный ProcessBuilder надёжнее.
 */
public final class FileOpener {

	private FileOpener() {
	}

	/** Раскрывает папку в файловом менеджере ОС; создаёт её, если её ещё нет. */
	public static void openFolder(Path folder) {
		try {
			if (folder != null) {
				Files.createDirectories(folder);
			}
		} catch (java.io.IOException exception) {
			DreamcastClient.LOGGER.warn("Не удалось создать папку {}", folder, exception);
		}
		open(folder == null ? null : folder.toFile());
	}

	/** Открыть ссылку в браузере по умолчанию (Telegram, Modrinth…). */
	public static void openUrl(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		String os = System.getProperty("os.name", "").toLowerCase();
		try {
			ProcessBuilder builder;
			if (os.contains("win")) {
				builder = new ProcessBuilder("cmd", "/c", "start", "", url);
			} else if (os.contains("mac") || os.contains("darwin")) {
				builder = new ProcessBuilder("open", url);
			} else {
				builder = new ProcessBuilder("xdg-open", url);
			}
			builder.redirectErrorStream(true);
			Process process = builder.start();
			Thread.ofVirtual().start(() -> {
				try {
					process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
				} catch (Exception ignored) {
				}
			});
		} catch (Exception exception) {
			DreamcastClient.LOGGER.warn("Не удалось открыть ссылку {} браузером", url, exception);
		}
	}

	private static void open(File target) {
		if (target == null || !target.exists()) {
			return;
		}

		String os = System.getProperty("os.name", "").toLowerCase();
		try {
			ProcessBuilder builder;
			if (os.contains("win")) {
				builder = new ProcessBuilder("explorer", target.getAbsolutePath());
			} else if (os.contains("mac") || os.contains("darwin")) {
				builder = new ProcessBuilder("open", target.getAbsolutePath());
			} else {
				builder = new ProcessBuilder("xdg-open", target.getAbsolutePath());
			}
			builder.redirectErrorStream(true);
			// Дожидаемся старта, но не самого проводника — он живёт до закрытия окна
			Process process = builder.start();
			Thread.ofVirtual().start(() -> {
				try {
					process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
				} catch (Exception ignored) {
					// закрытый поток — не критично
				}
			});
		} catch (Exception exception) {
			DreamcastClient.LOGGER.warn("Не удалось открыть {} через системный проводник", target, exception);
		}
	}
}

package com.dreamcast.client.util;

import net.minecraft.client.Minecraft;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Отложенные восстановления инвентаря: если модуль прервали (выключили,
 * открылся контейнер) в момент, когда нужен обратный SWAP/слот, а делать его
 * сейчас небезопасно — действие встаёт в очередь и выполняется в тике клиента,
 * как только контейнер закроется.
 *
 * <p>Без этого выключение AutoBuff через ClickGUI при открытом сундуке
 * навсегда теряло предмет в резервном слоте: resetState() стирал bagSource.</p>
 */
public final class PendingRestores {

	/** @return true — восстановление выполнено, элемент покидает очередь. */
	public interface Restore {
		boolean tryRestore(Minecraft client);
	}

	private static final Queue<Restore> PENDING = new ConcurrentLinkedQueue<>();

	public static void add(Restore restore) {
		PENDING.add(restore);
	}

	/** Вызывается каждый тик клиента (END_CLIENT_TICK). */
	public static void tick(Minecraft client) {
		if (PENDING.isEmpty() || client == null || client.player == null) {
			return;
		}
		PENDING.removeIf(restore -> {
			try {
				return restore.tryRestore(client);
			} catch (Throwable error) {
				return true; // битое восстановление не зависает в очереди навсегда
			}
		});
	}

	/** Очередь пуста (для тестов/диагностики). */
	public static boolean isEmpty() {
		return PENDING.isEmpty();
	}

	/** Срочно выполнить всё возможное (выход из мира — очередь теряет смысл). */
	public static void clear() {
		PENDING.clear();
	}
}

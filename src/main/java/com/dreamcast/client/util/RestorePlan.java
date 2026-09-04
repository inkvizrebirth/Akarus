package com.dreamcast.client.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Порядок восстановления после действия AutoBuff (чистая логика).
 *
 * <p>Шаги строго по порядку: вернуть предмет резервного слота (если был
 * обмен с рюкзаком) → восстановить выбранный слот → вернуть взгляд.
 * Прерывание (выключение модуля, смерть, таймаут) прогоняет все шаги
 * немедленно и идемпотентно.</p>
 */
public final class RestorePlan {

	public enum Step {
		SWAP_BACK,      // обратный SWAP: предмет рюкзака и резервный слот хотбара
		RESTORE_SLOT,   // вернуть выбранный слот
		RESTORE_ROTATION, // вернуть pitch/yaw
		DONE
	}

	private final Deque<Step> remaining;

	public RestorePlan(boolean swapNeeded) {
		remaining = new ArrayDeque<>();
		if (swapNeeded) {
			remaining.add(Step.SWAP_BACK);
		}
		remaining.add(Step.RESTORE_SLOT);
		remaining.add(Step.RESTORE_ROTATION);
	}

	/** Следующий шаг к выполнению (без удаления) или DONE. */
	public Step peek() {
		return remaining.isEmpty() ? Step.DONE : remaining.peek();
	}

	/** Шаг выполнен — двигаемся дальше. */
	public void advance() {
		if (!remaining.isEmpty()) {
			remaining.poll();
		}
	}

	public boolean finished() {
		return remaining.isEmpty();
	}

	/** Прерывание: оставшиеся шаги выполнятся немедленно (выдаём их по порядку). */
	public void interrupt() {
		// порядок сохранён: Deque уже упорядочен, «немедленно» = просто
		// пометить, что потребитель заберёт все шаги без пауз — реализовано
		// флагом, который потребитель читает через isInterrupted()
		interrupted = true;
	}

	private boolean interrupted;

	public boolean isInterrupted() {
		return interrupted;
	}
}

package com.akarus.client.util;

import com.akarus.client.AkarusClient;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Движок медиаплеера: PCM-носители (wav/aiff/au) через {@code javax.sound.sampled} —
 * без сторонних библиотек и без OpenAL игры.
 *
 * {@link Clip} декодирует файл в память целиком и играет на системном микшере, поэтому
 * звук не зависит от FPS и не дёргается при лагах. Декодирование уходит в фоновый поток
 * (большой wav не должен вешать игру), а play/pause/остановка — из игрового потока;
 * всё взаимодействие синхронизировано по одному монитору, «осиротевшие» загрузки
 * отменяются по счётчику поколений.
 *
 * MP3 движком JDK не декодируется — вместо падения модуль показывает причину в статусе.
 */
public final class MusicPlayer {

	/** Расширения, которые движок пробует открыть. */
	public static final String[] SUPPORTED_EXTENSIONS = {"wav", "wave", "aiff", "aif", "au"};

	/** Нижняя граница громкости микшера, дБ. */
	private static final float MIN_GAIN_DB = -80.0f;

	private final Object lock = new Object();

	private final List<File> tracks = new ArrayList<>();
	private int index = -1;

	private Clip clip;
	/** Растёт при каждой загрузке/остановке: устаревший загрузчик не вставит свой клип. */
	private int generation;
	private float volume = 0.7f;

	private boolean userPaused;
	private boolean finishedFlag;
	private boolean errorFlag;
	private String lastError = "";

	/** Пересканировать папку; текущий трек сохраняется по имени. */
	public void rescan(File folder, boolean keepPlaying) {
		synchronized (lock) {
			String currentName = hasTrackLocked() ? tracks.get(index).getName() : null;

			tracks.clear();
			File[] files = folder.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isFile() && isSupported(file.getName())) {
						tracks.add(file);
					}
				}
			}
			tracks.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

			int restored = -1;
			if (currentName != null) {
				for (int i = 0; i < tracks.size(); i++) {
					if (tracks.get(i).getName().equals(currentName)) {
						restored = i;
						break;
					}
				}
			}
			if (restored < 0 && !tracks.isEmpty()) {
				restored = 0;
			}
			index = restored;

			if (index < 0) {
				stopLocked();
			} else if (!keepPlaying) {
				stopLocked();
			}
		}
	}

	public static boolean isSupported(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		for (String extension : SUPPORTED_EXTENSIONS) {
			if (lower.endsWith("." + extension)) {
				return true;
			}
		}
		return false;
	}

	public int trackCount() {
		synchronized (lock) {
			return tracks.size();
		}
	}

	public int currentIndex() {
		synchronized (lock) {
			return index;
		}
	}

	public void playIndex(int target, boolean autoplay) {
		synchronized (lock) {
			if (tracks.isEmpty()) {
				return;
			}
			index = Math.floorMod(target, tracks.size());
			userPaused = !autoplay;
			startLoadLocked(tracks.get(index), autoplay);
		}
	}

	/** Продолжить/запустить. */
	public void play() {
		synchronized (lock) {
			userPaused = false;
			if (clip != null) {
				clip.start();
				return;
			}
			if (index < 0) {
				if (tracks.isEmpty()) {
					return;
				}
				index = 0;
			}
			startLoadLocked(tracks.get(index), true);
		}
	}

	public void pause() {
		synchronized (lock) {
			userPaused = true;
			if (clip != null) {
				clip.stop();
			}
		}
	}

	public void togglePlay() {
		synchronized (lock) {
			if (clip != null && clip.isRunning()) {
				pause();
			} else {
				play();
			}
		}
	}

	public void stop() {
		synchronized (lock) {
			stopLocked();
		}
	}

	public void next() {
		synchronized (lock) {
			if (tracks.isEmpty()) {
				return;
			}
			index = index < 0 ? 0 : index + 1;
			if (index >= tracks.size()) {
				index = 0;
			}
			startLoadLocked(tracks.get(index), !userPaused);
		}
	}

	public void previous() {
		synchronized (lock) {
			if (tracks.isEmpty()) {
				return;
			}
			index = index <= 0 ? tracks.size() - 1 : index - 1;
			startLoadLocked(tracks.get(index), !userPaused);
		}
	}

	/**
	 * «Трек доиграл сам» — читается из тика модуля для автоперехода.
	 * Возвращает true ровно один раз и только если паузу не ставили руками.
	 */
	public boolean pumpFinished() {
		synchronized (lock) {
			if (!finishedFlag) {
				return false;
			}
			finishedFlag = false;
			return !userPaused;
		}
	}

	public void setVolume(float linear01) {
		float clamped = Math.max(0.0f, Math.min(1.0f, linear01));
		synchronized (lock) {
			volume = clamped;
			applyVolumeLocked();
		}
	}

	/** Перемотать текущий трек в начало и продолжить. */
	public void restart() {
		synchronized (lock) {
			if (clip == null) {
				play();
				return;
			}
			userPaused = false;
			clip.setMicrosecondPosition(0L);
			clip.start();
		}
	}

	public void loopCurrentForever(boolean on) {
		synchronized (lock) {
			if (clip != null) {
				try {
					clip.loop(on ? Clip.LOOP_CONTINUOUSLY : 0);
				} catch (Exception ignored) {
					// не все реализации поддержат loop в процессе — не страшно
				}
			}
		}
	}

	public boolean hasTrack() {
		synchronized (lock) {
			return hasTrackLocked();
		}
	}

	public String currentName() {
		synchronized (lock) {
			return hasTrackLocked() ? tracks.get(index).getName() : "";
		}
	}

	public boolean isPlaying() {
		synchronized (lock) {
			return clip != null && clip.isRunning();
		}
	}

	public long positionMillis() {
		synchronized (lock) {
			return clip == null ? 0L : clip.getMicrosecondPosition() / 1000L;
		}
	}

	public long durationMillis() {
		synchronized (lock) {
			if (clip == null) {
				return 0L;
			}
			double rate = Math.max(1.0, clip.getFormat().getFrameRate());
			return (long) (clip.getFrameLength() / rate * 1000.0);
		}
	}

	public boolean isError() {
		synchronized (lock) {
			return errorFlag;
		}
	}

	public String lastError() {
		synchronized (lock) {
			return lastError;
		}
	}

	// ------------------------------------------------------------------
	// Внутреннее
	// ------------------------------------------------------------------

	private boolean hasTrackLocked() {
		return index >= 0 && index < tracks.size();
	}

	private void stopLocked() {
		userPaused = false;
		finishedFlag = false;
		generation++;
		closeClipLocked();
	}

	private void closeClipLocked() {
		Clip old = clip;
		clip = null;
		if (old != null) {
			try {
				old.stop();
			} catch (Exception ignored) {
			}
			try {
				old.close();
			} catch (Exception ignored) {
			}
		}
	}

	/** Загрузка в фоне: декодирует без блокировки, вставляет клип только если поколение ещё наше. */
	private void startLoadLocked(File file, boolean autoplay) {
		generation++;
		closeClipLocked();
		errorFlag = false;
		lastError = "";

		if (file == null || !file.isFile()) {
			return;
		}

		final int myGeneration = generation;
		final boolean start = autoplay;
		final File target = file.getAbsoluteFile();
		Thread loader = new Thread(() -> loadOnWorker(target, myGeneration, start), "akarus-media-loader");
		loader.setDaemon(true);
		loader.start();
	}

	private void loadOnWorker(File file, int myGeneration, boolean start) {
		Clip newClip = null;
		AudioInputStream converted = null;
		try (AudioInputStream source = AudioSystem.getAudioInputStream(file)) {
			newClip = AudioSystem.getClip();
			try {
				newClip.open(source);
			} catch (Exception direct) {
				// не-PCM (alaw/ulaw/imadpcm) — пробуем конвертацию в 16-bit PCM
				AudioFormat base = source.getFormat();
				int channels = Math.max(1, base.getChannels());
				float rate = Math.max(8000.0f, base.getSampleRate());
				AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
						rate, 16, channels, channels * 2, rate * channels, false);
				converted = AudioSystem.getAudioInputStream(pcm, source);
				newClip.open(converted);
				converted.close();
				converted = null;
			}

			newClip.addLineListener(event -> {
				if (event.getType() == LineEvent.Type.STOP) {
					synchronized (lock) {
						if (generation == myGeneration && clip == event.getLine()) {
							finishedFlag = true;
						}
					}
				}
			});

			synchronized (lock) {
				if (generation != myGeneration) {
					closeQuietly(newClip, converted);
					return; // за это время трек сменили/остановили
				}
				clip = newClip;
				newClip.setMicrosecondPosition(0L);
				applyVolumeLocked();
				if (start) {
					newClip.start();
				}
			}
			newClip = null; // ответственность теперь на игровом потоке
			AkarusClient.LOGGER.info("Медиаплеер: загружен {}", file.getName());
		} catch (Exception exception) {
			closeQuietly(newClip, converted);
			String message = exception.getMessage();
			String reason = message == null || message.isBlank()
					? exception.getClass().getSimpleName()
					: message;
			synchronized (lock) {
				if (generation == myGeneration) {
					errorFlag = true;
					lastError = reason;
				}
			}
			AkarusClient.LOGGER.warn("Медиаплеер: не удалось открыть {}", file, exception);
		}
	}

	private static void closeQuietly(Clip clip, AudioInputStream stream) {
		if (stream != null) {
			try {
				stream.close();
			} catch (Exception ignored) {
			}
		}
		if (clip != null) {
			try {
				clip.close();
			} catch (Exception ignored) {
			}
		}
	}

	private void applyVolumeLocked() {
		if (clip == null) {
			return;
		}
		try {
			if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
				float db = volume <= 0.001f ? MIN_GAIN_DB : (float) (20.0 * Math.log10(volume));
				db = Math.max(control.getMinimum(), Math.min(control.getMaximum(), db));
				control.setValue(db);
			}
		} catch (Exception ignored) {
			// микшер без MASTER_GAIN — оставляем громкость как есть
		}
	}
}

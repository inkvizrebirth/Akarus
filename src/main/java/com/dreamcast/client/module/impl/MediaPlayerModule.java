package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ButtonSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import com.dreamcast.client.settings.StringSetting;
import com.dreamcast.client.util.FileOpener;
import com.dreamcast.client.util.MusicPlayer;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Path;
import java.util.Random;

/**
 * Медиаплеер: фоновая музыка из папки {@code dreamcast/media} в корне игры.
 *
 * Не использует звук игры (OpenAL/SoundManager) — играет через {@code javax.sound}
 * отдельным потоком, поэтому треку не мешают ни пауза игры, ни перемещение по мирам,
 * ни F1. Поддерживаются PCM-носители: wav/aiff/au. Управление — кнопками в этом
 * меню (в том числе «Открыть папку с музыкой»), статус — карточкой на HUD.
 */
public class MediaPlayerModule extends Module {

	private static final Random RANDOM = new Random();

	private final MusicPlayer player = new MusicPlayer();

	public static final String LOOP_OFF = "off";
	public static final String LOOP_ONE = "one";
	public static final String LOOP_ALL = "all";

	private final BooleanSetting showInHud = bool("hud_card", "Показывать карточку на HUD", true);

	private final IntSetting volume = intSetting("volume", "Громкость, %", 70, 0, 100);

	private final ModeSetting loopMode = mode("loop", "Повтор", LOOP_ALL,
			ModeSetting.option(LOOP_OFF, "Без повтора"),
			ModeSetting.option(LOOP_ONE, "Один трек"),
			ModeSetting.option(LOOP_ALL, "Весь плейлист"));

	private final BooleanSetting shuffle = bool("shuffle", "Перемешивать", false);

	private final BooleanSetting autoStart = bool("auto_start", "Играть сразу при включении модуля", true);

	private final BooleanSetting pauseInMenu = bool("pause_in_menu", "Пауза при открытом меню", false);

	private final StringSetting folder = textSetting("folder", "Папка музыки (от корня игры)", "dreamcast/media");

	private final ButtonSetting toggleButton = buttonSetting("toggle", "Управление", "\u25B6 Играть / пауза",
			this::togglePlayback);

	private final ButtonSetting nextButton = buttonSetting("next", "", "\u23ED Следующий трек",
			() -> player.next());

	private final ButtonSetting openButton = buttonSetting("open_folder", "", "\uD83D\uDCC2 Открыть папку с музыкой",
			this::openMusicFolder);

	/** Для авто-рескана: меняем список только когда папка реально тронута. */
	private long watchedModified = -1L;
	private int scanCooldown;
	private boolean wasUsingMenu;
	/** Трек поставлен на паузу самим модулем (из-за меню) — его и возобновляем. */
	private boolean pausedByMenu;

	public MediaPlayerModule() {
		super("media_player", "MediaPlayer", "Фоновая музыка (wav/aiff/au) из папки dreamcast/media — играет мимо звука игры",
				ModuleCategory.HUD, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected void onEnable() {
		File directory = musicDirectory();
		if (directory != null) {
			directory.mkdirs();
			player.rescan(directory, false);
		}
		player.setVolume(volume.get() / 100.0f);
		if (autoStart.isEnabled() && player.hasTrack()) {
			player.play();
		}
	}

	@Override
	protected void onDisable() {
		player.pause();
		pausedByMenu = false;
		wasUsingMenu = false;
	}

	@Override
	public void onSettingsChanged() {
		player.setVolume(volume.get() / 100.0f);
		if (!pauseInMenu.isEnabled() && pausedByMenu) {
			player.play();
			pausedByMenu = false;
		}
	}

	@Override
	public void tick() {
		// Громкость могли поменять на середине трека — синхронизируем дешево
		player.setVolume(volume.get() / 100.0f);

		// Рескан не чаще раза в секунду и только если папка менялась
		if (--scanCooldown <= 0) {
			scanCooldown = 20;
			File directory = musicDirectory();
			if (directory != null && directory.isDirectory()) {
				long modified = directory.lastModified();
				if (modified != watchedModified) {
					watchedModified = modified;
					player.rescan(directory, true);
				}
			}
		}

		// Пауза при открытом меню/скрине, если включено
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		boolean inMenu = client != null && client.gui != null && client.gui.screen() != null;
		if (pauseInMenu.isEnabled()) {
			if (inMenu && !wasUsingMenu && player.isPlaying()) {
				player.pause();
				pausedByMenu = true;
			} else if (!inMenu && wasUsingMenu && pausedByMenu) {
				// Возобновляем только то, что остановили сами: если игрок ставил
				// паузу руками или трек закончился — не навязываем play()
				player.play();
				pausedByMenu = false;
			}
		} else if (pausedByMenu) {
			// Настройку могли выключить прямо в открытом ClickGUI.
			player.play();
			pausedByMenu = false;
		}
		wasUsingMenu = inMenu;

		// Трек доиграл — реагируем по режиму повтора
		if (player.pumpFinished()) {
			if (loopMode.is(LOOP_ONE)) {
				player.restart();
			} else if (loopMode.is(LOOP_ALL)) {
				if (shuffle.isEnabled() && player.trackCount() > 1) {
					int current = player.currentIndex();
					int next = RANDOM.nextInt(player.trackCount());
					if (next == current) {
						next = (next + 1) % player.trackCount();
					}
					player.playIndex(next, true);
				} else {
					player.next();
				}
			} else {
				player.pause();
			}
		}
	}

	// ------------------------------------------------------------------
	// Действия кнопок и доступ для HUD
	// ------------------------------------------------------------------

	private void togglePlayback() {
		if (!player.hasTrack()) {
			File directory = musicDirectory();
			if (directory != null) {
				directory.mkdirs();
				player.rescan(directory, false);
			}
		}
		player.togglePlay();
	}

	private void openMusicFolder() {
		Path path = musicPath();
		FileOpener.openFolder(path);
		File directory = path == null ? null : path.toFile();
		if (directory != null) {
			player.rescan(directory, true);
		}
	}

	private Path musicPath() {
		String raw = folder.get();
		if (raw == null || raw.isBlank()) {
			return null;
		}
		Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		Path resolved = gameDir.resolve(raw.trim()).normalize();
		// Намеренно не даём «выйти» из папки игры относительным путём
		if (!resolved.startsWith(gameDir)) {
			return gameDir.resolve("dreamcast/media").normalize();
		}
		return resolved;
	}

	private File musicDirectory() {
		Path path = musicPath();
		return path == null ? null : path.toFile();
	}

	public boolean showsHudCard() {
		return showInHud.isEnabled();
	}

	public boolean isPlaying() {
		return player.isPlaying();
	}

	public boolean hasTrack() {
		return player.hasTrack();
	}

	public String currentName() {
		return player.currentName();
	}

	public long positionMillis() {
		return player.positionMillis();
	}

	public long durationMillis() {
		return player.durationMillis();
	}

	public boolean hasError() {
		return player.isError();
	}

	public String errorText() {
		return player.lastError();
	}

	public int trackCount() {
		return player.trackCount();
	}

	public int currentIndex() {
		return player.currentIndex();
	}

	/** Тумблер для хоткея/HUD-клика (оставлено и для будущих элементов). */
	public MusicPlayer engine() {
		return player;
	}
}

package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.module.ModuleManager;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.StringSetting;
import com.dreamcast.client.util.AutoGgLogic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AutoGG — «GG» в чат, когда цель умерла от твоей руки.
 *
 * <p>Как узнаётся «от твоей руки»: клиенту не приходит пакет «кто убил кого»,
 * {@code deathMessage} живёт на сервере. Поэтому мод считает своей смертью ту,
 * которая наступила в окне после <b>нашего</b> удара (момент удара — тот же хук,
 * что у HitParticles/HitSounds: {@code MultiPlayerGameMode#attack}). Окно
 * настраивается: слишком широкое — припишет нам чужие доп. удары, слишком узкое —
 * не догонит смерть с задержкой пакета.</p>
 *
 * <p>Задержка перед отправкой нужна не для красоты: мгновенное «GG» в ту же
 * секунду, что цель легла, выглядит как бот. Плюс за это время успевает прийти
 * подтверждение смерти ({@code isAlive}/{@code isRemoved}), и мы не напишем GG
 * по ложной тревоге, когда цель выжила.</p>
 *
 * <p>Текст — настройка с плейсхолдерами {@code %player%} (он же {@code %name%});
 * переводы строк вырезаются, длина режется — см. {@link AutoGgLogic}.</p>
 */
public class AutoGgModule extends Module {

	/** Кого ждём: ник и момент последнего нашего удара. */
	private record Tracked(String name, long hitMs) {
	}

	/** Отложенное сообщение: когда пора отправить. */
	private record Pending(String message, long sendAtMs) {
	}

	private static final int MAX_TRACKED = 32;

	private final StringSetting text = textSetting("text", "Текст", "*%player%* GG");
	private final IntSetting delay = intSetting("delay", "Задержка (мс)", 500, 0, 5000);
	private final IntSetting window = intSetting("window", "Окно «моя смерть» (мс)", 2500, 250, 15000);
	private final BooleanSetting onlyPlayers = bool("only_players", "Только по игрокам", true);
	private final BooleanSetting onlyAura = bool("only_aura", "Только цели ауры", false);
	private final BooleanSetting skipWhenDead = bool("skip_when_dead", "Не писать, пока сам лежишь", true);

	/** id сущности → кого и когда ударили. Живёт на потоке игры (хук удара и тик). */
	private final Map<Integer, Tracked> tracked = new HashMap<>();
	private final List<Pending> pending = new ArrayList<>();

	public AutoGgModule() {
		super("auto_gg", "AutoGG",
				"Пишет GG в чат, когда цель умерла от твоей руки: свой текст и задержка",
				ModuleCategory.COMBAT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	/** Точка входа миксина атаки: запомнить цель как «мою». */
	public static void onAttack(LocalPlayer player, Entity target) {
		AutoGgModule module = ModuleManager.find(AutoGgModule.class);
		if (module == null || !module.isEnabled() || player == null || target == null) {
			return;
		}
		if (module.onlyPlayers.isEnabled() && !(target instanceof Player)) {
			return;
		}
		if (module.onlyAura.isEnabled()) {
			KillAuraModule aura = ModuleManager.find(KillAuraModule.class);
			Entity current = aura == null ? null : aura.currentTarget();
			if (current != target) {
				return;
			}
		}
		module.note(target);
	}

	private void note(Entity target) {
		if (!(target instanceof LivingEntity) || !target.isAlive()) {
			return;
		}
		if (tracked.size() >= MAX_TRACKED) {
			// самые старые вылетают первыми: список короткий, а порядок в HashMap
			// нестабилен — чистим по времени удара
			int oldestId = -1;
			long oldest = Long.MAX_VALUE;
			for (Map.Entry<Integer, Tracked> entry : tracked.entrySet()) {
				if (entry.getValue().hitMs() < oldest) {
					oldest = entry.getValue().hitMs();
					oldestId = entry.getKey();
				}
			}
			if (oldestId >= 0) {
				tracked.remove(oldestId);
			}
		}
		tracked.put(target.getId(), new Tracked(target.getName().getString(), Util.getMillis()));
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.level == null || client.player == null) {
			tracked.clear();
			pending.clear();
			return;
		}
		long now = Util.getMillis();
		long windowMs = window.get();

		// 1) кто из «моих» целей уже умер — идёт в очередь на отправку
		Iterator<Map.Entry<Integer, Tracked>> entries = tracked.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<Integer, Tracked> entry = entries.next();
			Tracked hit = entry.getValue();
			if (!AutoGgLogic.ourKill(now, hit.hitMs(), windowMs)) {
				entries.remove(); // удар был слишком давно — смерть не наша
				continue;
			}
			Entity entity = client.level.getEntity(entry.getKey());
			boolean present = entity != null;
			if (AutoGgLogic.dead(present, present && entity.isAlive(), present && entity.isRemoved())) {
				String message = AutoGgLogic.format(text.get(), hit.name());
				if (AutoGgLogic.sendable(message)) {
					pending.add(new Pending(message, now + AutoGgLogic.delayMillis(delay.get())));
				}
				entries.remove();
			}
		}

		// 2) очередь: отправляем то, у чего вышла задержка
		Iterator<Pending> queue = pending.iterator();
		while (queue.hasNext()) {
			Pending item = queue.next();
			if (!AutoGgLogic.due(now, item.sendAtMs())) {
				continue;
			}
			queue.remove();
			send(client, item.message());
		}
	}

	private void send(Minecraft client, String message) {
		if (skipWhenDead.isEnabled() && client.player.getHealth() <= 0.0F) {
			return; // лежим: GG после собственной смерти выглядит странно, пишем только живым
		}
		ClientPacketListener connection = client.getConnection();
		if (connection == null) {
			return;
		}
		try {
			connection.sendChat(message);
		} catch (Exception error) {
			// чат может быть отключён или соединение уже закрывается — не повод ронять тик
			com.dreamcast.client.DreamcastClient.LOGGER.warn("AutoGG: чат не отправлен: {}", error.toString());
		}
	}

	@Override
	protected void onDisable() {
		tracked.clear();
		pending.clear();
	}

	/** Сколько целей сейчас под наблюдением — для теста и отладки. */
	public int trackedCount() {
		return tracked.size();
	}
}

package com.akarus.client.settings;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Настройка-список блоков с множественным выбором и поиском (BlockESP).
 *
 * В конфиге хранится строка «id,id,…» (пути из реестра, например
 * {@code diamond_ore,deepslate_diamond_ore}). Список всех блоков реестра
 * строится один раз и переиспользуется: строк в реестре больше тысячи,
 * гонять их через поиск каждый кадр не стоит.
 */
public class BlockListSetting extends Setting<String> {

	/** Одна строка реестра: id (для конфига) и читаемое имя (для поиска). */
	public record BlockEntry(String id, String name) {
	}

	private static List<BlockEntry> registryCache;

	private final Set<String> selected = new LinkedHashSet<>();

	public BlockListSetting(String id, String name, String... defaults) {
		super(id, name, String.join(",", defaults));
		for (String blockId : defaults) {
			if (exists(blockId)) {
				selected.add(blockId);
			}
		}
	}

	/** Все блоки реестра, отсортированные по id. Кэш общий на все настройки. */
	public static List<BlockEntry> allBlocks() {
		if (registryCache == null) {
			List<BlockEntry> entries = new ArrayList<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
				entries.add(new BlockEntry(path, block.getName().getString()));
			}
			entries.sort((a, b) -> a.id().compareTo(b.id()));
			registryCache = Collections.unmodifiableList(entries);
		}
		return registryCache;
	}

	public static boolean exists(String blockId) {
		for (BlockEntry entry : allBlocks()) {
			if (entry.id().equals(blockId)) {
				return true;
			}
		}
		return false;
	}

	/** Строки, подходящие под запрос: ищем и по id, и по читаемому имени. */
	public static List<BlockEntry> search(String query) {
		String needle = query == null ? "" : query.trim().toLowerCase();
		List<BlockEntry> all = allBlocks();
		if (needle.isEmpty()) {
			return all;
		}
		List<BlockEntry> result = new ArrayList<>();
		for (BlockEntry entry : all) {
			if (entry.id().toLowerCase().contains(needle)
					|| entry.name().toLowerCase().contains(needle)) {
				result.add(entry);
			}
		}
		return result;
	}

	public boolean isSelected(String blockId) {
		return selected.contains(blockId);
	}

	public void toggle(String blockId) {
		if (selected.contains(blockId)) {
			selected.remove(blockId);
		} else {
			selected.add(blockId);
		}
		setValue(String.join(",", selected));
	}

	public int count() {
		return selected.size();
	}

	public Set<String> selectedIds() {
		return Collections.unmodifiableSet(selected);
	}

	/** Применяет строку из конфига; вернёт false, если ничего не совпало. */
	public boolean applySaved(String joined) {
		if (joined == null || joined.isBlank()) {
			return true;
		}
		Set<String> parsed = new LinkedHashSet<>();
		for (String part : joined.split(",")) {
			String id = part.trim();
			if (!id.isEmpty() && exists(id)) {
				parsed.add(id);
			}
		}
		selected.clear();
		selected.addAll(parsed);
		setValue(String.join(",", parsed));
		return true;
	}
}

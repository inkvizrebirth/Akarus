package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BlockListSetting;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.ColorSetting;
import com.dreamcast.client.settings.IntSetting;
import com.dreamcast.client.settings.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * BlockESP — подсветка блоков в мире: боксы вокруг всех подходящих блоков
 * (руда, сундуки, кровати — что выберешь в списке).
 *
 * Список блоков — весь реестр с поиском по id и имени; поиск прямо в
 * строке настройки. Сами боксы рисует world-рендер ({@code WorldRenderHook}),
 * а позиции собираются здесь, в тике, раз в секунду — сканировать куб радиуса
 * каждый кадр нельзя.
 */
public class BlockEspModule extends Module {

	/** Один найденный блок: целые координаты + «фаза» для сдвига цвета. */
	public record BlockBox(int x, int y, int z, int phase) {
	}

	private final BlockListSetting blocks = new BlockListSetting("blocks", "Блоки",
			"diamond_ore", "deepslate_diamond_ore", "ancient_debris");
	private final IntSetting radius = intSetting("radius", "Радиус, блоков", 24, 8, 48);
	private final IntSetting scanInterval = intSetting("scan_interval", "Сканировать раз в, тиков", 20, 5, 100);
	private final ModeSetting style = mode("style", "Стиль", "box",
			ModeSetting.option("box", "Бокс"),
			ModeSetting.option("corners", "Только углы"));
	private final ColorSetting color = colorSetting("color", "Цвет", 0xFFFFC66C);
	private final BooleanSetting rainbow = bool("rainbow", "Радуга", false);
	private final IntSetting lineWidth = intSetting("line_width", "Толщина линий", 2, 1, 8);

	/** Лимит одновременно рисуемых боксов: иначе спам тысячами убьёт кадр. */
	private static final int MAX_BOXES = 1024;

	private final List<BlockBox> boxes = new ArrayList<>();
	private int scanTimer;

	public BlockEspModule() {
		super("block_esp", "BlockESP", "Подсветка выбранных блоков: руда, сундуки и что угодно ещё",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || client.level == null || blocks.count() == 0) {
			boxes.clear();
			return;
		}
		if (++scanTimer < Math.max(5, scanInterval.get())) {
			return;
		}
		scanTimer = 0;
		scan(client);
	}

	/** Собирает боксы подходящих блоков вокруг игрока. */
	private void scan(Minecraft client) {
		boxes.clear();
		var selected = blocks.selectedIds();
		if (selected.isEmpty()) {
			return;
		}

		BlockPos center = client.player.blockPosition();
		int r = radius.get();
		int yMin = Math.max(client.level.getMinY(), center.getY() - r / 2);
		int yMax = Math.min(client.level.getMaxY(), center.getY() + r);

		int phase = 0;
		for (BlockPos pos : BlockPos.betweenClosed(
				center.getX() - r, yMin, center.getZ() - r,
				center.getX() + r, yMax, center.getZ() + r)) {
			BlockState state = client.level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			// Сверяем ключ реестра (один lookup на непустой блок)
			String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
					.getKey(state.getBlock()).getPath();
			if (!selected.contains(id)) {
				continue;
			}
			boxes.add(new BlockBox(pos.getX(), pos.getY(), pos.getZ(), phase++));
			if (boxes.size() >= MAX_BOXES) {
				return;
			}
		}
	}

	/** Боксы для отрисовки (может быть пусто). */
	public List<BlockBox> blockBoxes() {
		return boxes;
	}

	public boolean wantsBoxes() {
		return isEnabled() && blocks.count() > 0 && !boxes.isEmpty();
	}

	public boolean cornersOnly() {
		return style.is("corners");
	}

	public int lineColor(int phase) {
		if (rainbow.isEnabled()) {
			return com.dreamcast.client.util.RenderUtils.hsb(
					(System.currentTimeMillis() % 10000L) / 10000.0f + phase * 0.03f, 0.75f, 1.0f, 0xFF);
		}
		return color.get();
	}

	public int lineWidth() {
		return lineWidth.get();
	}
}

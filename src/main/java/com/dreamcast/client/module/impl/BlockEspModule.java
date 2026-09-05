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

	/** Инкрементальный скан: полный обход куба радиуса 48 — ~680 тыс. позиций,
	 *  одним куском это фризит главный поток. Бюджет — по ВРЕМЕНИ (~1.5 мс на
	 *  тик), чтобы и на слабых машинах кадр не проседал. */
	private static final long SCAN_BUDGET_NANOS = 1_500_000L;
	private final List<BlockBox> scanBuffer = new ArrayList<>();
	/** Линейный курсор по объёму скана; -1 — скан не идёт. */
	private int scanCursor = -1;
	/** Центр и мир текущего круга: сменились — круг перезапускается. */
	private net.minecraft.client.multiplayer.ClientLevel scanLevel;
	private int scanCenterX = Integer.MIN_VALUE;
	private int scanCenterY;
	private int scanCenterZ;
	private int scanRadius;
	private int scanSizeX;
	private int scanSizeY;
	private int scanSizeZ;
	private int scanBaseX;
	private int scanBaseY;
	private int scanBaseZ;

	public BlockEspModule() {
		super("block_esp", "BlockESP", "Подсветка выбранных блоков: руда, сундуки и что угодно ещё",
				ModuleCategory.RENDER, GLFW.GLFW_KEY_UNKNOWN);
		// Без регистрации списка он существовал в логике сканера, но не попадал
		// ни в ClickGUI, ни в конфиг — выбрать свой блок было невозможно.
		addSetting(blocks);
	}

	@Override
	public void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || client.level == null || blocks.count() == 0) {
			boxes.clear();
			scanCursor = -1;
			scanBuffer.clear();
			return;
		}
		// Скан уже идёт — двигаем его в рамках бюджета времени. Центр сместился
		// сильнее половины радиуса / другой мир / другой радиус — круг заново
		if (scanCursor >= 0) {
			if (client.level != scanLevel || scanRadius != radius.get()
					|| Math.abs(client.player.blockPosition().getX() - scanCenterX) > scanRadius / 2
					|| Math.abs(client.player.blockPosition().getZ() - scanCenterZ) > scanRadius / 2) {
				beginScan(client);
				return;
			}
			scanStep(client, SCAN_BUDGET_NANOS);
			return;
		}
		if (++scanTimer < Math.max(5, scanInterval.get())) {
			return;
		}
		scanTimer = 0;
		beginScan(client);
	}

	/** Начинает новый круг скана: фиксируем центр и объём, курсор на ноль. */
	private void beginScan(Minecraft client) {
		BlockPos center = client.player.blockPosition();
		int r = radius.get();
		scanLevel = client.level;
		scanCenterX = center.getX();
		scanCenterY = center.getY();
		scanCenterZ = center.getZ();
		scanRadius = r;
		int yMin = Math.max(client.level.getMinY(), center.getY() - r / 2);
		int yMax = Math.min(client.level.getMaxY(), center.getY() + r);
		scanBaseX = center.getX() - r;
		scanBaseY = yMin;
		scanBaseZ = center.getZ() - r;
		scanSizeX = 2 * r + 1;
		scanSizeY = yMax - yMin + 1;
		scanSizeZ = 2 * r + 1;
		scanCursor = 0;
		scanBuffer.clear();
	}

	/** Продвигает скан в рамках бюджета времени; в конце публикует результат. */
	private void scanStep(Minecraft client, long budgetNanos) {
		var selected = blocks.selectedIds();
		int total = scanSizeX * scanSizeY * scanSizeZ;
		long deadline = System.nanoTime() + budgetNanos;
		int phase = scanBuffer.size();
		var mutable = new BlockPos.MutableBlockPos();
		int index = scanCursor;
		while (index < total) {
			if ((index & 0x3FF) == 0 && System.nanoTime() >= deadline) {
				break; // бюджет исчерпан — продолжим в следующем тике
			}
			int y = index / (scanSizeX * scanSizeZ);
			int rest = index % (scanSizeX * scanSizeZ);
			int z = rest / scanSizeX;
			int x = rest % scanSizeX;
			mutable.set(scanBaseX + x, scanBaseY + y, scanBaseZ + z);
			var state = client.level.getBlockState(mutable);
			if (!state.isAir()) {
				String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
						.getKey(state.getBlock()).getPath();
				if (selected.contains(id)) {
					scanBuffer.add(new BlockBox(mutable.getX(), mutable.getY(), mutable.getZ(), phase++));
					if (scanBuffer.size() >= MAX_BOXES) {
						break;
					}
				}
			}
			index++;
		}
		scanCursor = index;
		if (scanCursor >= total || scanBuffer.size() >= MAX_BOXES) {
			// Круг завершён — публикуем (до этого момента рисуем прошлый результат)
			boxes.clear();
			boxes.addAll(scanBuffer);
			scanBuffer.clear();
			scanCursor = -1;
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

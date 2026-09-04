package com.dreamcast.client.module.impl;

import com.dreamcast.client.module.Module;
import com.dreamcast.client.module.ModuleCategory;
import com.dreamcast.client.settings.BooleanSetting;
import com.dreamcast.client.settings.IntSetting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * NoSlow (+ NoWeb) — отмена замедления при использовании предметов и в паутине.
 *
 * <p>Когда игрок ест, пьёт, натягивает лук, блокирует щитом или держит блок,
 * ваниль урезает ввод движения (см. {@code LocalPlayer#modifyInput}). Модуль
 * подменяет этот множитель: можно оставить 0% замедления (как в классических
 * чит-клиентах) или любой свой процент — для «легитного» вида.</p>
 *
 * <p>Фильтры по типу предмета — как в Meteor/Future: еда и зелья, лук/арбалет,
 * щит, блоки — каждая категория включается отдельно.</p>
 *
 * <p>NoWeb объединён сюда: в паутине ваниль «приклеивает» игрока
 * ({@code Entity#makeStuckInBlock}) — миксин просто не даёт паутине выставить
 * замедление.</p>
 */
public class NoSlowModule extends Module {

	private final IntSetting keepSlow = intSetting("keep_slow", "Оставить замедления, %", 0, 0, 100);
	private final BooleanSetting food = bool("food", "Еда и зелья", true);
	private final BooleanSetting bow = bool("bow", "Лук и арбалет", true);
	private final BooleanSetting shield = bool("shield", "Щит", true);
	private final BooleanSetting block = bool("block", "Блоки", true);
	private final BooleanSetting noWeb = bool("no_web", "NoWeb (паутина)", true);

	public NoSlowModule() {
		super("no_slow", "NoSlow", "Без замедления при использовании предметов и в паутине",
				ModuleCategory.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN);
	}

	@Override
	protected boolean defaultEnabled() {
		return false;
	}

	/** Доля замедления, которую нужно ОСТАВИТЬ (0 — вообще без замедления). */
	public float keepSlowFraction() {
		return keepSlow.get() / 100.0f;
	}

	public boolean noWebEnabled() {
		return noWeb.isEnabled();
	}

	/** Подходит ли предмет под включённые фильтры (вызывается из миксина). */
	public boolean appliesTo(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		// CONSUMABLE есть у еды, зелий (в т.ч. splash/lingering) и подобных
		if (food.isEnabled() && stack.has(net.minecraft.core.component.DataComponents.CONSUMABLE)) {
			return true;
		}
		if (bow.isEnabled() && (stack.is(Items.BOW) || stack.is(Items.CROSSBOW))) {
			return true;
		}
		if (shield.isEnabled() && stack.is(Items.SHIELD)) {
			return true;
		}
		// Блоки: BlockItem — предметы, которые ставят блок (в руке «на замахе»)
		return block.isEnabled() && stack.getItem() instanceof net.minecraft.world.item.BlockItem;
	}
}

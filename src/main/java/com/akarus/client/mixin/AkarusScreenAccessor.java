package com.akarus.client.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Доступ к защищённому {@code Screen#addRenderableWidget} — нужен, чтобы добавлять
 * своих виджетов в чужие (ванильные) экраны из Fabric-событий, где мы не подкласс.
 */
@Mixin(Screen.class)
public interface AkarusScreenAccessor {

	@Invoker("addRenderableWidget")
	<T extends GuiEventListener & Renderable & NarratableEntry> T akarus$addRenderableWidget(T widget);
}

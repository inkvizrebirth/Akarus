package com.dreamcast.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к текущему (пере)привязанному ключу {@link KeyMapping}.
 *
 * <p>В 26.2 поле {@code key} — protected, а публичного геттера нет
 * ({@code getDefaultKey()} возвращает дефолт, а не факт перепривязки).
 * Он нужен {@code KeyOwnership}: отпуская программно зажатую клавишу,
 * возвращаем её ФИЗИЧЕСКОЕ состояние — а для этого надо знать, какой
 * GLFW-код ей сейчас назначен.</p>
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

	@Accessor("key")
	InputConstants.Key dreamcast$boundKey();
}

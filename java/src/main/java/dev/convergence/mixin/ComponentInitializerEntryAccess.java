package dev.convergence.mixin;

import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.core.component.DataComponentInitializers$InitializerEntry")
public interface ComponentInitializerEntryAccess {
    @Accessor("key")
    ResourceKey<?> infinity$getKey();
    @Accessor("initializer")
    DataComponentInitializers.Initializer<?> infinity$getInitializer();
}

package dev.convergence.mixin;

import java.util.List;
import net.minecraft.core.component.DataComponentInitializers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla only exposes an item's components once bound; gear rebuilds its base item's defaults. */
@Mixin(DataComponentInitializers.class)
public interface ComponentInitializersAccess {
    @Accessor("initializers")
    List<ComponentInitializerEntryAccess> infinity$getInitializers();
}

package dev.convergence.mixin;

import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets gear append a bind-time step to an item's ordered component chain. */
@Mixin(Item.Properties.class)
public interface ItemPropertiesAccess {
    @Accessor("componentInitializer")
    DataComponentInitializers.Initializer<Item> infinity$getComponents();
    @Accessor("componentInitializer")
    void infinity$setComponents(DataComponentInitializers.Initializer<Item> components);
}

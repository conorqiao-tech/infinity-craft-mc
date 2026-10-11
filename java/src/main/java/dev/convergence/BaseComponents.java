package dev.convergence;

import dev.convergence.mixin.ComponentInitializersAccess;
import dev.convergence.mixin.ItemPropertiesAccess;
import java.util.function.BiConsumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Since 26.1 item components are bound after registration, from initializers that need the
 * loaded registries. Gear that inherits a vanilla item's components therefore reads them
 * while its own components are being built, at the same point in its component order.
 */
final class BaseComponents {
    private BaseComponents() {}

    /** Append a step that receives the base item's default components. Later calls still override it. */
    static Item.Properties then(Item.Properties properties, Item base, BiConsumer<DataComponentMap.Builder, DataComponentMap> step) {
        var access = (ItemPropertiesAccess) properties;
        access.infinity$setComponents(access.infinity$getComponents().andThen((builder, registries, key) -> step.accept(builder, of(base, registries))));
        return properties;
    }

    @SuppressWarnings("unchecked")
    static DataComponentMap of(Item base, HolderLookup.Provider registries) {
        var key = (ResourceKey<Item>) base.builtInRegistryHolder().key();
        var builder = DataComponentMap.builder();
        for (var entry : ((ComponentInitializersAccess) BuiltInRegistries.DATA_COMPONENT_INITIALIZERS).infinity$getInitializers())
            if (entry.infinity$getKey().equals(key))
                ((DataComponentInitializers.Initializer<Item>) entry.infinity$getInitializer()).run(builder, registries, key);
        return builder.build();
    }

    static <T> void copy(DataComponentMap.Builder builder, TypedDataComponent<T> component) {
        builder.set(component.type(), component.value());
    }
}

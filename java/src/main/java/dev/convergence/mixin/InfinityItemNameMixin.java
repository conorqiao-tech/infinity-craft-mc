package dev.convergence.mixin;

import dev.convergence.GearNames;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla appends its translated default name after the properties' component steps. */
@Mixin(Item.Properties.class)
public abstract class InfinityItemNameMixin {
    @Shadow private ResourceKey<Item> id;

    @Inject(method="finalizeInitializer(Lnet/minecraft/network/chat/Component;Lnet/minecraft/resources/Identifier;)Lnet/minecraft/core/component/DataComponentInitializers$Initializer;",
        at=@At("RETURN"),cancellable=true)
    private void infinity$literalDefaultName(Component defaultName,Identifier model,
        CallbackInfoReturnable<DataComponentInitializers.Initializer<Item>> result) {
        if(id==null||!id.identifier().getNamespace().equals("convergence"))return;
        Component name=GearNames.text(id.identifier().getPath());
        result.setReturnValue(result.getReturnValue().andThen((builder,registries,key)->builder.set(DataComponents.ITEM_NAME,name)));
    }
}

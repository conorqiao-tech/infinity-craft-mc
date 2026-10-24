package dev.convergence.mixin;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read native defaults without getInstance's persistent lazy-cache insertion. */
@Mixin(AttributeMap.class)
public interface AttributeMapAccess {
    @Accessor("attributes") Map<Holder<Attribute>, AttributeInstance> infinity$getInstances();
    @Accessor("supplier") AttributeSupplier infinity$getSupplier();
}

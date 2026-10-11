package dev.convergence.mixin;

import dev.convergence.AdminAttribute;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AttributeInstance.class)
public abstract class AdminAttributeMixin implements AdminAttribute {
    @Unique private boolean infinity$expanded;
    @Shadow protected abstract void setDirty();
    public boolean infinity$expanded() { return infinity$expanded; }
    public void infinity$expanded(boolean expanded) {
        if (infinity$expanded != expanded) { infinity$expanded = expanded; setDirty(); }
    }
    @Redirect(method="calculateValue", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/ai/attributes/Attribute;sanitizeValue(D)D"))
    private double expandedValue(Attribute attribute, double value) {
        if (!infinity$expanded || Double.isNaN(value)) return attribute.sanitizeValue(value);
        double minimum = attribute instanceof net.minecraft.world.entity.ai.attributes.RangedAttribute range ? range.getMinValue() : -Float.MAX_VALUE;
        return Math.max(minimum, Math.min(Float.MAX_VALUE, value));
    }
    @Inject(method="replaceFrom", at=@At("TAIL"))
    private void copyExpansion(AttributeInstance source, CallbackInfo ci) {
        infinity$expanded(((AdminAttribute)source).infinity$expanded());
    }
}

package dev.convergence.mixin;

import dev.convergence.AdminAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityAttributeInstance.class)
public abstract class AdminAttributeMixin implements AdminAttribute {
    @Unique private boolean infinity$expanded;
    @Shadow protected abstract void onUpdate();
    public boolean infinity$expanded() { return infinity$expanded; }
    public void infinity$expanded(boolean expanded) {
        if (infinity$expanded != expanded) { infinity$expanded = expanded; onUpdate(); }
    }
    @Redirect(method="computeValue", at=@At(value="INVOKE", target="Lnet/minecraft/entity/attribute/EntityAttribute;clamp(D)D"))
    private double expandedValue(EntityAttribute attribute, double value) {
        if (!infinity$expanded || Double.isNaN(value)) return attribute.clamp(value);
        double minimum = attribute instanceof net.minecraft.entity.attribute.ClampedEntityAttribute range ? range.getMinValue() : -Float.MAX_VALUE;
        return Math.max(minimum, Math.min(Float.MAX_VALUE, value));
    }
    @Inject(method="setFrom", at=@At("TAIL"))
    private void copyExpansion(EntityAttributeInstance source, CallbackInfo ci) {
        infinity$expanded(((AdminAttribute)source).infinity$expanded());
    }
}

package dev.convergence;

import dev.convergence.mixin.AgentFlightAccess;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.Unit;

/** Uses vanilla mob weapon attacks, spear charging and LivingEntity gliding physics. */
final class AgentWeapons {
    private AgentWeapons() {}
    private static ItemStack gear(Item item) {
        var stack=new ItemStack(item);stack.set(DataComponentTypes.UNBREAKABLE,Unit.INSTANCE);return stack;
    }
    static void equip(IronGolemEntity golem,Item weapon) {
        if(!golem.getMainHandStack().isOf(weapon)) {golem.clearActiveItem();golem.equipStack(EquipmentSlot.MAINHAND,gear(weapon));}
        if(!golem.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA))golem.equipStack(EquipmentSlot.CHEST,gear(Items.ELYTRA));
        for(var slot:new EquipmentSlot[]{EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.CHEST})golem.setEquipmentDropChance(slot,0);
    }
    static void spear(IronGolemEntity golem,LivingEntity target) {
        equip(golem,Items.NETHERITE_SPEAR);
        aim(golem,target);
        // Native LivingEntity ticks call the held spear's KineticWeaponComponent.
        if(!golem.isUsingItem())golem.setCurrentHand(Hand.MAIN_HAND);
    }
    static void aim(IronGolemEntity golem,LivingEntity target) {
        double x=target.getX()-golem.getX(),z=target.getZ()-golem.getZ();
        double y=target.getY()+target.getHeight()*.6-golem.getEyeY();
        float yaw=(float)(Math.toDegrees(Math.atan2(z,x))-90);
        golem.setYaw(yaw);golem.setHeadYaw(yaw);golem.setBodyYaw(yaw);
        golem.setPitch((float)-Math.toDegrees(Math.atan2(y,Math.sqrt(x*x+z*z))));
    }
    static void glide(IronGolemEntity golem) {
        if(!golem.isOnGround() && !golem.isTouchingWater() && LivingEntity.canGlideWith(golem.getEquippedStack(EquipmentSlot.CHEST),EquipmentSlot.CHEST))
            ((AgentFlightAccess)golem).infinity$setFlag(7,true);
    }
    static void mace(IronGolemEntity golem,LivingEntity target) {
        golem.stopGliding();golem.clearActiveItem();equip(golem,Items.MACE);aim(golem,target);
    }
    static void stop(IronGolemEntity golem) {golem.clearActiveItem();golem.stopGliding();}
}

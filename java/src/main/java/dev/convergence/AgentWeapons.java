package dev.convergence;

import dev.convergence.mixin.AgentFlightAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Uses vanilla mob weapon attacks, spear charging and LivingEntity gliding physics. */
final class AgentWeapons {
    private AgentWeapons() {}
    private static ItemStack gear(Item item) {
        var stack=new ItemStack(item);stack.set(DataComponents.UNBREAKABLE,Unit.INSTANCE);return stack;
    }
    static void equip(IronGolem golem,Item weapon) {
        if(!golem.getMainHandItem().is(weapon)) {golem.stopUsingItem();golem.setItemSlot(EquipmentSlot.MAINHAND,gear(weapon));}
        if(!golem.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA))golem.setItemSlot(EquipmentSlot.CHEST,gear(Items.ELYTRA));
        for(var slot:new EquipmentSlot[]{EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.CHEST})golem.setDropChance(slot,0);
    }
    static void spear(IronGolem golem,LivingEntity target) {
        equip(golem,Items.NETHERITE_SPEAR);
        aim(golem,target);
        // Native LivingEntity ticks call the held spear's KineticWeaponComponent.
        if(!golem.isUsingItem())golem.startUsingItem(InteractionHand.MAIN_HAND);
    }
    static void aim(IronGolem golem,LivingEntity target) {
        double x=target.getX()-golem.getX(),z=target.getZ()-golem.getZ();
        double y=target.getY()+target.getBbHeight()*.6-golem.getEyeY();
        float yaw=(float)(Math.toDegrees(Math.atan2(z,x))-90);
        golem.setYRot(yaw);golem.setYHeadRot(yaw);golem.setYBodyRot(yaw);
        golem.setXRot((float)-Math.toDegrees(Math.atan2(y,Math.sqrt(x*x+z*z))));
    }
    static void glide(IronGolem golem) {
        if(!golem.onGround() && !golem.isInWater() && LivingEntity.canGlideUsing(golem.getItemBySlot(EquipmentSlot.CHEST),EquipmentSlot.CHEST))
            ((AgentFlightAccess)golem).infinity$setFlag(7,true);
    }
    static void mace(IronGolem golem,LivingEntity target) {
        golem.stopFallFlying();golem.stopUsingItem();equip(golem,Items.MACE);aim(golem,target);
    }
    static void stop(IronGolem golem) {golem.stopUsingItem();golem.stopFallFlying();}
}

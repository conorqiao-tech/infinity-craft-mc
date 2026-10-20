package dev.convergence;

import dev.convergence.mixin.AgentFlightAccess;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Original Infinity components on native weapon classes retain real charge and smash mechanics. */
final class AgentWeapons {
    private static final Map<Item, ItemStack> LOADOUTS = new IdentityHashMap<>();
    private AgentWeapons() {}
    private static String path(Item weapon) {
        return weapon == Items.NETHERITE_SPEAR ? "spear" : weapon == Items.MACE ? "mace"
            : weapon == Items.NETHERITE_SWORD ? "sword" : null;
    }
    private static ItemStack loadout(Item weapon) {
        return LOADOUTS.computeIfAbsent(weapon, base -> {
            String path = path(base);
            if (path == null) throw new IllegalArgumentException("Unsupported helper weapon");
            var original = Convergence.ITEMS.get("convergence:" + path);
            if (original == null) throw new IllegalStateException("The original Infinity helper weapon is not registered");
            var nativeStack = new ItemStack(base);
            nativeStack.applyComponents(original.components());
            return nativeStack;
        });
    }
    /** Viewer-only identity: Polymer's existing overlay supplies the correct pack/client fallback. */
    static ItemStack visibleWeapon(IronGolem helper, ItemStack stack) {
        if (!AgentCompanions.registeredHelper(helper) || stack.isEmpty() || path(stack.getItem()) == null
            || stack.getCount() != 1 || !ItemStack.isSameItemSameComponents(stack, loadout(stack.getItem()))) return stack;
        return Convergence.ITEMS.get("convergence:" + path(stack.getItem())).getDefaultInstance();
    }
    private static ItemStack gear(Item item) {
        var stack=new ItemStack(item);stack.set(DataComponents.UNBREAKABLE,Unit.INSTANCE);return stack;
    }
    static void equip(IronGolem golem,Item weapon) {
        if (!AgentCompanions.registeredHelper(golem)) return;
        var wanted = loadout(weapon);
        var current = golem.getMainHandItem();
        // Reuse the active stack whenever all original catalogue components are
        // present. Replacing it every control tick would restart native charging.
        if (current.getCount() != 1 || !ItemStack.isSameItemSameComponents(current, wanted)) {
            golem.stopUsingItem(); golem.setItemSlot(EquipmentSlot.MAINHAND, wanted.copy());
        }
        if (weapon != Items.NETHERITE_SWORD && !golem.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA))
            golem.setItemSlot(EquipmentSlot.CHEST,gear(Items.ELYTRA));
        if (!golem.getOffhandItem().isEmpty()) golem.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        golem.setDropChance(EquipmentSlot.MAINHAND, 0);
        golem.setDropChance(EquipmentSlot.OFFHAND, 0);
        golem.setDropChance(EquipmentSlot.CHEST, 0);
    }
    static void spear(IronGolem golem,LivingEntity target) {
        if (!AgentCompanions.registeredHelper(golem)) return;
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
        if(AgentCompanions.registeredHelper(golem) && !golem.onGround() && !golem.isInWater() && LivingEntity.canGlideUsing(golem.getItemBySlot(EquipmentSlot.CHEST),EquipmentSlot.CHEST))
            ((AgentFlightAccess)golem).infinity$setFlag(7,true);
    }
    static void mace(IronGolem golem,LivingEntity target) {
        if (!AgentCompanions.registeredHelper(golem)) return;
        golem.stopFallFlying();golem.stopUsingItem();equip(golem,Items.MACE);aim(golem,target);
    }
    static void sword(IronGolem golem,LivingEntity target) {
        if (!AgentCompanions.registeredHelper(golem)) return;
        golem.stopFallFlying();golem.stopUsingItem();equip(golem,Items.NETHERITE_SWORD);aim(golem,target);
    }
    /** Inspect native spear reach without equipping it or resetting an active charge. */
    static boolean spearTooClose(IronGolem golem,LivingEntity target) {
        double minimum=golem.getAttackRangeWith(loadout(Items.NETHERITE_SPEAR)).effectiveMinRange(golem)
            +(golem.getBbWidth()+target.getBbWidth())*.5;
        // A small return threshold keeps a moving target from flipping weapons
        // across the boundary on every five-tick control update.
        if(golem.getMainHandItem().is(Items.MACE))minimum+=.5;
        return golem.distanceToSqr(target)<minimum*minimum;
    }
    static void stop(IronGolem golem) {
        if (!AgentCompanions.registeredHelper(golem)) return;
        golem.stopUsingItem();golem.stopFallFlying();
    }
}

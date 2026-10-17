package dev.convergence;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Craftable relics work without a rank or a suit; all changes use native mechanics. */
final class ElementalRelics {
    static final List<String> PATHS = List.of("firebrand", "tidecaller", "terra_gauntlet", "windstep");
    static final Map<String, Item> BASES = Map.of("firebrand", Items.BLAZE_ROD,
        "tidecaller", Items.PRISMARINE_SHARD, "terra_gauntlet", Items.ECHO_SHARD, "windstep", Items.FEATHER);
    private ElementalRelics() {}

    static void register() {
        for (String path : PATHS) {
            var id = Identifier.fromNamespaceAndPath("convergence", path);
            var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
                .component(DataComponents.ITEM_NAME, GearNames.text(path)).stacksTo(1);
            BaseComponents.then(properties, BASES.get(path), (builder, defaults) -> {
                for (TypedDataComponent<?> component : defaults)
                    if (component.type() != DataComponents.ITEM_NAME && component.type() != DataComponents.ITEM_MODEL
                        && component.type() != DataComponents.MAX_STACK_SIZE) BaseComponents.copy(builder, component);
            });
            var item = Registry.register(BuiltInRegistries.ITEM, id, new Relic(properties));
            Convergence.ITEMS.put(id.toString(), item);
        }
    }

    static List<ItemStack> kit() {
        return PATHS.stream().map(path -> new ItemStack(Convergence.ITEMS.get("convergence:" + path))).toList();
    }

    /** No player targeting, terrain edits, summoned entities or inventory consumption. */
    static int cast(ServerPlayer player) {
        if (!ServerMenu.allowed(player)) return 0;
        String id = Convergence.id(player.getMainHandItem());
        if (!id.startsWith("convergence:")) return 0;
        String path = id.substring("convergence:".length());
        if (!PATHS.contains(path)) return 0;
        // One shared cooldown prevents hotbar swapping from multiplying casts.
        if (!Convergence.ready(player, "elemental_relic", 100)) {
            Convergence.say(player, "Relics recharge for five seconds after each cast.");
            return 0;
        }
        switch (path) {
            case "firebrand" -> {
                int hits = 0;
                Vec3 facing = player.getViewVector(1.0F);
                for (LivingEntity mob : Convergence.monsters(player, 8)) {
                    Vec3 offset = mob.getBoundingBox().getCenter().subtract(player.getEyePosition());
                    if (hits >= 6) break;
                    if (offset.lengthSqr() > .01 && offset.normalize().dot(facing) < .55) continue;
                    if (Convergence.hurt(player, mob, 8)) {
                        mob.igniteForSeconds(4);
                        Convergence.sparks(player, mob, 6);
                        hits++;
                    }
                }
                Convergence.say(player, "Firebrand • " + hits + " hostile targets in the forward cone.");
            }
            case "tidecaller" -> {
                player.clearFire();
                player.heal(4);
                Convergence.effect(player, MobEffects.WATER_BREATHING, 0, 200);
                Convergence.effect(player, MobEffects.DOLPHINS_GRACE, 0, 200);
                Convergence.say(player, "Tidecaller • extinguished, healed up to two hearts; water powers for ten seconds.");
            }
            case "terra_gauntlet" -> {
                Convergence.effect(player, MobEffects.RESISTANCE, 1, 100);
                Convergence.effect(player, MobEffects.HASTE, 1, 100);
                int pushed = 0;
                for (LivingEntity mob : Convergence.monsters(player, 5)) {
                    if (pushed >= 6) break;
                    Vec3 away = mob.position().subtract(player.position()).multiply(1, 0, 1);
                    if (away.lengthSqr() < .01) continue;
                    mob.push(away.normalize().scale(.7).add(0, .15, 0));
                    mob.needsSync = true;
                    pushed++;
                }
                Convergence.say(player, "Earth ward • Resistance II and Haste II; " + pushed + " hostile mobs pushed away.");
            }
            case "windstep" -> {
                Vec3 facing = player.getViewVector(1.0F).multiply(1, 0, 1);
                if (facing.lengthSqr() > .01) Convergence.push(player, facing.normalize().scale(.9).add(0, .3, 0));
                Convergence.effect(player, MobEffects.SLOW_FALLING, 0, 100);
                Convergence.say(player, "Windstep • a short forward dash with five seconds of Slow Falling.");
            }
        }
        return 1;
    }

    private static final class Relic extends Item {
        Relic(Properties properties) { super(properties); }
        @Override public InteractionResult use(Level world, Player player, InteractionHand hand) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (player instanceof ServerPlayer owner) cast(owner);
            return InteractionResult.SUCCESS;
        }
    }
}

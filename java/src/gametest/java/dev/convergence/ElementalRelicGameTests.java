package dev.convergence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/** Connected ordinary players, real native queries, damage, effects and motion.
 * These tests never contact a provider or borrow the playable world.
 */
public class ElementalRelicGameTests {
    private static final class InventoryState {
        final List<ItemStack> items = new ArrayList<>();
        final EnumMap<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        final ItemStack carried;
        final int selected;
        InventoryState(ServerPlayer player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                items.add(player.getInventory().getItem(i).copy());
            for (var slot : EquipmentSlot.values()) equipment.put(slot, player.getItemBySlot(slot).copy());
            carried = player.containerMenu.getCarried().copy();
            selected = player.getInventory().getSelectedSlot();
        }
        void unchanged(GameTestHelper c, ServerPlayer player) {
            c.assertValueEqual(player.getInventory().getContainerSize(), items.size(), "Casting never changes native inventory capacity");
            for (int i = 0; i < items.size(); i++) c.assertTrue(ItemStack.matches(items.get(i), player.getInventory().getItem(i)),
                "Casting preserves every item, count and component at inventory slot " + i);
            for (var entry : equipment.entrySet()) c.assertTrue(ItemStack.matches(entry.getValue(), player.getItemBySlot(entry.getKey())),
                "Casting preserves worn/offhand item components: " + entry.getKey());
            c.assertTrue(ItemStack.matches(carried, player.containerMenu.getCarried()), "Casting neither consumes nor creates a cursor item");
            c.assertValueEqual(player.getInventory().getSelectedSlot(), selected, "Casting preserves the selected hotbar slot");
        }
    }

    private static final class RelicScenario extends NativeSessionFixture {
        private static long ticketSequence = 4100;
        final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++ticketSequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final List<net.minecraft.world.level.ChunkPos> chunks = new ArrayList<>();
        final List<LivingEntity> mobs = new ArrayList<>();
        final Consumer<RelicScenario> checks;
        Joined owner, peer;
        Vec3 origin;
        RelicScenario(GameTestHelper c, Consumer<RelicScenario> checks) { super(c); this.checks = checks; }
        @Override void start() {
            owner = join("relic-owner", 2); peer = join("relic-peer", 5);
            var feet = c.absolutePos(new BlockPos(20, 55, 20)); origin = Vec3.atBottomCenterOf(feet);
            // The declared arena reserves this entire bounded test area.
            for (var at : BlockPos.betweenClosed(feet.offset(-10, -1, -10), feet.offset(10, 4, 10)))
                c.getLevel().setBlockAndUpdate(at, (at.getY() == feet.getY() - 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            for (int x = (feet.getX() - 10) >> 4; x <= (feet.getX() + 10) >> 4; x++)
                for (int z = (feet.getZ() - 10) >> 4; z <= (feet.getZ() + 10) >> 4; z++) {
                    var chunk = new net.minecraft.world.level.ChunkPos(x, z); chunks.add(chunk);
                    c.getLevel().getChunkSource().addTicketWithRadius(ticket, chunk, 2);
                }
            for (var joined : List.of(owner, peer)) {
                var p = joined.player; OperatorGameTests.deop(p); p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent(); p.getInventory().setSelectedSlot(0);
                for (var slot : EquipmentSlot.values()) p.setItemSlot(slot, ItemStack.EMPTY);
                p.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 7));
                var keepsake = new ItemStack(Items.IRON_SWORD); keepsake.setDamageValue(7);
                keepsake.set(DataComponents.CUSTOM_NAME, Component.literal("Keep my named blade"));
                p.getInventory().setItem(6, keepsake); p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
                p.removeAllEffects();
                c.assertFalse(Memberships.gameplayBypass(p), "Relics work for ordinary connected players without a rank bypass");
            }
            place(owner, Vec3.ZERO); place(peer, new Vec3(-1, 0, 3));
            peer.player.setPermanentlyInvulnerable(false);
            // Native simulation sections and normal JOIN/menu callbacks settle
            // before any mobs are created or combat/cooldown evidence is taken.
            later(6, () -> {
                c.assertTrue(ServerMenu.allowed(owner.player), "The actor is a current, connected, eligible native player");
                checks.accept(this);
            });
        }
        void place(Joined joined, Vec3 offset) {
            var at = origin.add(offset);
            joined.player.connection.teleport(at.x, at.y, at.z, 0, 0); joined.drain();
            joined.player.setDeltaMovement(Vec3.ZERO);
        }
        <T extends Mob> T mob(EntityType<T> kind, Vec3 offset) {
            var mob = kind.create(c.getLevel(), EntitySpawnReason.COMMAND);
            c.assertTrue(mob != null, "The real native entity type creates the combat fixture");
            mobs.add(mob); mob.setNoAi(true); mob.setNoGravity(true); mob.setPersistenceRequired();
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); mob.setHealth(100);
            mob.setPos(origin.add(offset)); mob.setDeltaMovement(Vec3.ZERO);
            c.assertTrue(c.getLevel().addFreshEntity(mob), "The combat fixture is registered in the actual native world");
            c.assertTrue(c.getLevel().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox(), entity -> entity == mob).contains(mob),
                "The actual native spatial query sees the exact new fixture entity");
            return mob;
        }
        void hold(ServerPlayer player, String path) {
            var item = Convergence.ITEMS.get("convergence:" + path);
            c.assertTrue(item != null, "The relic exists in the actual canonical registry: " + path);
            var stack = new ItemStack(item); stack.set(DataComponents.CUSTOM_NAME, Component.literal("My saved " + path));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }
        int cast(ServerPlayer player) {
            var before = new InventoryState(player); int result = ElementalRelics.cast(player);
            before.unchanged(c, player); return result;
        }
        @Override void cleanup() {
            for (var mob : mobs) mob.discard();
            for (var joined : players) {
                GameModes.PENDING.remove(joined.player.getUUID());
                GameModes.TRANSITIONS.remove(joined.player.getUUID());
            }
            for (var chunk : chunks) c.getLevel().getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
        }
    }

    private static void scenario(GameTestHelper c, Consumer<RelicScenario> checks) {
        NativeSessionFixture.startSerialized(c, () -> new RelicScenario(c, checks));
    }
    private static void close(GameTestHelper c, double actual, double expected, String message) {
        c.assertTrue(Math.abs(actual - expected) < 1e-7, message + ": actual=" + actual + ", expected=" + expected);
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=1200)
    public void eligibilityAndOneSharedRechargeUseActualServerTicks(GameTestHelper c) {
        scenario(c, f -> {
            var p = f.owner.player;
            var imitation = new ItemStack(Items.BLAZE_ROD); imitation.set(DataComponents.CUSTOM_NAME, Component.literal("Firebrand"));
            p.setItemInHand(InteractionHand.MAIN_HAND, imitation);
            c.assertValueEqual(f.cast(p), 0, "A renamed vanilla base cannot impersonate a canonical relic");
            f.hold(p, "tidecaller"); p.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(f.cast(p), 0, "A real spectator cannot cast"); p.setGameMode(GameType.SURVIVAL);
            p.setHealth(0); c.assertFalse(p.isAlive(), "The eligibility fixture is really dead");
            c.assertValueEqual(f.cast(p), 0, "A dead native player cannot cast"); p.setHealth(p.getMaxHealth());
            GameModes.PENDING.put(p.getUUID(), new GameModes.Switch(GameModes.Mode.CREATIVE, null,
                CommunityServer.Place.of(p), f.server.getTickCount() + 200));
            c.assertValueEqual(f.cast(p), 0, "A pending mode switch cannot cast into the old world"); GameModes.PENDING.remove(p.getUUID());
            GameModes.TRANSITIONS.add(p.getUUID());
            c.assertValueEqual(f.cast(p), 0, "An active mode transition cannot cast"); GameModes.TRANSITIONS.remove(p.getUUID());
            c.assertFalse(Convergence.state(p).cooldown.containsKey("elemental_relic"), "Rejected actors and wrong items never spend the relic recharge");
            f.hold(p, "windstep"); p.setDeltaMovement(new Vec3(.1, .2, .3)); p.setXRot(-60);
            c.assertValueEqual(f.cast(p), 1, "An ordinary player uses the registered Windstep without armor or a rank");
            close(c, p.getDeltaMovement().x, .1, "Native wind impulse preserves the initial sideways velocity");
            close(c, p.getDeltaMovement().y, .5, "Native wind impulse adds its bounded upward motion");
            close(c, p.getDeltaMovement().z, 1.2, "The horizontal view direction drives the actual native dash");
            c.assertTrue(p.needsSync, "The native player velocity is marked for synchronization");
            c.assertValueEqual(p.getEffect(MobEffects.SLOW_FALLING).getDuration(), 100, "Windstep grants five actual seconds of native Slow Falling");
            long due = Convergence.state(p).cooldown.get("elemental_relic");
            c.assertValueEqual(due, Convergence.clock + 100, "One shared recharge expires after exactly 100 native server ticks");
            for (String path : List.of("firebrand", "tidecaller", "terra_gauntlet", "windstep")) {
                f.hold(p, path); var velocity = p.getDeltaMovement(); float health = p.getHealth();
                c.assertValueEqual(f.cast(p), 0, "Hotbar swapping cannot bypass the shared recharge: " + path);
                c.assertValueEqual(p.getDeltaMovement(), velocity, "Rejected recasts cannot stack movement");
                c.assertValueEqual(p.getHealth(), health, "Rejected recasts cannot heal");
                c.assertValueEqual(Convergence.state(p).cooldown.get("elemental_relic"), due, "Rejected recasts never extend or replace the shared deadline");
            }
            f.later(99, () -> {
                c.assertValueEqual(Convergence.clock, due - 1, "The recharge test waited 99 genuine server ticks");
                f.hold(p, "tidecaller"); c.assertValueEqual(f.cast(p), 0, "The final remaining native recharge tick still rejects a different relic");
                f.later(1, () -> {
                    c.assertValueEqual(Convergence.clock, due, "The actual server clock reaches the original five-second deadline");
                    c.assertValueEqual(f.cast(p), 1, "A different relic becomes usable at the real shared deadline");
                    c.assertTrue(p.hasEffect(MobEffects.WATER_BREATHING), "The post-recharge success really applies Tidecaller powers");
                    f.succeed();
                });
            });
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=1200)
    public void firebrandUsesNativeConeRangeSightAndHostileEligibility(GameTestHelper c) {
        scenario(c, f -> {
            var p = f.owner.player; f.hold(p, "firebrand");
            var front = f.mob(EntityTypes.HUSK, new Vec3(0, 0, 4));
            var behind = f.mob(EntityTypes.HUSK, new Vec3(0, 0, -3));
            var side = f.mob(EntityTypes.HUSK, new Vec3(4, 0, 1));
            var distant = f.mob(EntityTypes.HUSK, new Vec3(0, 0, 9));
            var diagonal = f.mob(EntityTypes.HUSK, new Vec3(6, 0, 6));
            var blocked = f.mob(EntityTypes.HUSK, new Vec3(3, 0, 4));
            var friend = f.mob(EntityTypes.HUSK, new Vec3(-2, 0, 4)); friend.addTag("convergence_friend");
            var passive = f.mob(EntityTypes.COW, new Vec3(-3, 0, 3));
            var wall = p.blockPosition().offset(1, 1, 2);
            for (int x = 0; x < 2; x++) for (int y = 0; y < 3; y++)
                p.level().setBlockAndUpdate(wall.offset(x, y, 0), Blocks.STONE.defaultBlockState());
            c.assertTrue(Convergence.visible(p, front) && Convergence.visible(p, friend), "The positive target and protected friend have unobstructed native sight lines");
            c.assertTrue(Convergence.valid(p, f.peer.player) && Convergence.visible(p, f.peer.player), "The connected Survival bystander would otherwise be a visible, eligible PvP target");
            c.assertFalse(Convergence.visible(p, blocked), "The real collidable wall blocks the protected hostile's native sight line");
            var candidates = Convergence.monsters(p, 8);
            c.assertTrue(candidates.contains(front) && candidates.contains(behind) && candidates.contains(side), "The real query includes targets that must subsequently pass or fail the cone check");
            c.assertFalse(candidates.contains(distant) || candidates.contains(diagonal), "Actual spherical range rejects the far and diagonal AABB candidates");
            float health = front.getHealth(), peerHealth = f.peer.player.getHealth();
            c.assertValueEqual(f.cast(p), 1, "The actual registered Firebrand casts");
            c.assertTrue(front.getHealth() < health && front.getHealth() >= health - 8, "The visible forward hostile receives real bounded native damage");
            c.assertTrue(front.isOnFire(), "A successful native hit ignites the actual hostile");
            for (var protectedMob : List.of(behind, side, distant, diagonal, blocked, friend, passive)) {
                c.assertValueEqual(protectedMob.getHealth(), 100f, "Out-of-cone/range, occluded, friendly and passive entities keep their actual health");
                c.assertFalse(protectedMob.isOnFire(), "Excluded entities are never ignited");
            }
            c.assertValueEqual(f.peer.player.getHealth(), peerHealth, "The connected Survival bystander is never damaged by Firebrand");
            c.assertFalse(f.peer.player.isOnFire(), "The connected Survival bystander is never ignited");
            c.assertTrue(p.level().getBlockState(wall).is(Blocks.STONE), "Firebrand does not edit the real obstruction");
            f.succeed();
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=1200)
    public void firebrandLimitsARealCrowdToSixSuccessfulHits(GameTestHelper c) {
        scenario(c, f -> {
            var p = f.owner.player; f.hold(p, "firebrand");
            var crowd = new ArrayList<LivingEntity>();
            for (int i = 0; i < 8; i++) crowd.add(f.mob(EntityTypes.HUSK, new Vec3((i % 3 - 1) * .7, 0, 2 + i * .4)));
            c.assertTrue(Convergence.monsters(p, 8).containsAll(crowd), "All eight actual native hostiles are visible and in range before the cap is tested");
            c.assertValueEqual(f.cast(p), 1, "Firebrand accepts the real hostile crowd");
            c.assertValueEqual(crowd.stream().filter(mob -> mob.getHealth() < 100).count(), 6L, "A single cast damages exactly six actual eligible hostiles");
            c.assertValueEqual(crowd.stream().filter(LivingEntity::isOnFire).count(), 6L, "Only those six successful real hits ignite");
            f.succeed();
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=1200)
    public void earthWardMovesOnlyNearbyHostilesAndAppliesNativeDefenses(GameTestHelper c) {
        scenario(c, f -> {
            var p = f.owner.player; f.hold(p, "terra_gauntlet");
            var near = f.mob(EntityTypes.HUSK, new Vec3(0, 0, 3));
            var diagonal = f.mob(EntityTypes.HUSK, new Vec3(4, 0, 4));
            var distant = f.mob(EntityTypes.HUSK, new Vec3(0, 0, 8));
            var friend = f.mob(EntityTypes.HUSK, new Vec3(-2, 0, 2)); friend.addTag("convergence_friend");
            var passive = f.mob(EntityTypes.COW, new Vec3(3, 0, 1));
            f.peer.player.setDeltaMovement(Vec3.ZERO);
            c.assertValueEqual(f.cast(p), 1, "The real registered Earth relic casts");
            close(c, near.getDeltaMovement().x, 0, "A centered hostile receives no unrelated sideways impulse");
            close(c, near.getDeltaMovement().y, .15, "The actual hostile receives bounded upward native motion");
            close(c, near.getDeltaMovement().z, .7, "The actual hostile is pushed away from the caster");
            c.assertTrue(near.needsSync, "Hostile native motion is marked for synchronization");
            for (var protectedMob : List.of(diagonal, distant, friend, passive)) {
                c.assertValueEqual(protectedMob.getDeltaMovement(), Vec3.ZERO, "Distant, diagonal-out-of-range, friendly and passive entities are not pushed");
                c.assertValueEqual(protectedMob.getHealth(), 100f, "Earth ward never damages excluded entities");
            }
            c.assertValueEqual(near.getHealth(), 100f, "The native Earth push deals no direct damage");
            c.assertValueEqual(f.peer.player.getDeltaMovement(), Vec3.ZERO, "Earth ward never pushes the connected player bystander");
            c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getAmplifier(), 1, "Earth ward applies real Resistance II");
            c.assertValueEqual(p.getEffect(MobEffects.HASTE).getAmplifier(), 1, "Earth ward applies real Haste II");
            c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getDuration(), 100, "Earth defenses have the documented native duration");
            f.succeed();
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=1200)
    public void tidecallerExtinguishesBoundsHealingAndPreservesStrongerPotions(GameTestHelper c) {
        scenario(c, f -> {
            var p = f.owner.player;
            p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40); p.setHealth(10); p.igniteForSeconds(10);
            p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 600, 2));
            p.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 600, 3));
            p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 600, 4)); f.hold(p, "tidecaller");
            c.assertTrue(p.isOnFire(), "Tide's native player fixture is actually burning before the cast");
            c.assertValueEqual(f.cast(p), 1, "The real registered Tidecaller casts");
            c.assertFalse(p.isOnFire(), "The cast clears actual native fire state");
            c.assertValueEqual(p.getHealth(), 14f, "Healing adds exactly four health instead of filling an expanded health bar");
            c.assertValueEqual(p.getMaxHealth(), 40f, "Tide never rewrites an expanded native maximum health");
            c.assertValueEqual(p.getEffect(MobEffects.WATER_BREATHING).getAmplifier(), 2, "An external stronger water potion keeps its amplifier");
            c.assertValueEqual(p.getEffect(MobEffects.WATER_BREATHING).getDuration(), 600, "The stronger water potion keeps its duration");
            c.assertValueEqual(p.getEffect(MobEffects.DOLPHINS_GRACE).getAmplifier(), 3, "An external stronger swimming potion is preserved");
            c.assertValueEqual(p.getEffect(MobEffects.DOLPHINS_GRACE).getDuration(), 600, "The stronger swimming potion is not shortened");
            c.assertValueEqual(p.getEffect(MobEffects.STRENGTH).getDuration(), 600, "An unrelated external potion is untouched");
            var fresh = f.peer.player; fresh.setHealth(fresh.getMaxHealth() - 1); f.hold(fresh, "tidecaller");
            c.assertValueEqual(f.cast(fresh), 1, "Another ordinary connected player has an independent relic recharge");
            c.assertValueEqual(fresh.getHealth(), fresh.getMaxHealth(), "Healing clamps at the actual native maximum");
            c.assertValueEqual(fresh.getEffect(MobEffects.WATER_BREATHING).getAmplifier(), 0, "An unbuffed player receives native Water Breathing I");
            c.assertValueEqual(fresh.getEffect(MobEffects.WATER_BREATHING).getDuration(), 200, "Water powers last ten native seconds");
            c.assertValueEqual(fresh.getEffect(MobEffects.DOLPHINS_GRACE).getDuration(), 200, "Swimming powers last ten native seconds");
            f.succeed();
        });
    }

    @GameTest public void actualNativeCraftingAndLootConnectEveryRelicAndBuildingBlock(GameTestHelper c) {
        // Independent ingredient expectations are submitted to the running
        // server's actual recipe manager; no recipe or result is synthesized.
        Map<String, List<Item>> relics = Map.of(
            "firebrand", List.of(Items.BLAZE_POWDER, Items.AMETHYST_SHARD, Items.BLAZE_ROD),
            "tidecaller", List.of(Items.PRISMARINE_CRYSTALS, Items.HEART_OF_THE_SEA, Items.PRISMARINE_SHARD),
            "terra_gauntlet", List.of(Items.OBSIDIAN, Items.ECHO_SHARD, Items.IRON_INGOT),
            "windstep", List.of(Items.FEATHER, Items.AMETHYST_SHARD, Items.BREEZE_ROD));
        Map<String, List<Item>> blocks = Map.of(
            "ember_crystal", List.of(Items.POLISHED_BLACKSTONE, Items.BLAZE_POWDER),
            "tide_glass", List.of(Items.PRISMARINE, Items.PRISMARINE_CRYSTALS),
            "earth_rune", List.of(Items.STONE_BRICKS, Items.MOSS_BLOCK),
            "air_lattice", List.of(Items.QUARTZ_BLOCK, Items.FEATHER),
            "infinity_prism", List.of(Items.OBSIDIAN, Items.AMETHYST_SHARD));
        c.assertValueEqual(relics.keySet(), java.util.Set.copyOf(ElementalRelics.PATHS), "Native crafting covers every registered relic");
        c.assertValueEqual(blocks.keySet(), ExpandedGear.ELEMENTAL_BLOCKS, "Native crafting and loot cover every new building block");
        var entries = new ArrayList<>(relics.entrySet()); entries.addAll(blocks.entrySet());
        for (var entry : entries) {
            String path = entry.getKey(); boolean building = blocks.containsKey(path);
            var stacks = new ArrayList<ItemStack>();
            for (int i = 0; i < 9; i++) stacks.add(building ? new ItemStack(entry.getValue().getFirst()) : ItemStack.EMPTY);
            if (building) stacks.set(4, new ItemStack(entry.getValue().get(1)));
            else for (int i = 0; i < 3; i++) stacks.set(1 + i * 3, new ItemStack(entry.getValue().get(i)));
            var input = CraftingInput.of(3, 3, stacks);
            var recipe = c.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, c.getLevel()).orElseThrow();
            var id = Identifier.fromNamespaceAndPath("convergence", path);
            c.assertValueEqual(recipe.id().identifier(), id, "The actual native manager selects the intended relic/block recipe");
            var expected = new ItemStack(Convergence.ITEMS.get(id.toString()), building ? 8 : 1);
            c.assertTrue(ItemStack.matches(recipe.value().assemble(input), expected), "Native crafting produces the exact registered item, count and default components");
            stacks.set(4, ItemStack.EMPTY);
            var missingCore = c.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, stacks), c.getLevel());
            c.assertFalse(missingCore.isPresent() && missingCore.orElseThrow().id().identifier().equals(id), "A missing core never grants a relic or custom block");
        }
        Map<String, Integer> light = Map.of("ember_crystal", 15, "tide_glass", 7, "earth_rune", 0, "air_lattice", 5, "infinity_prism", 12);
        var at = c.absolutePos(new BlockPos(2, 2, 2));
        for (String path : blocks.keySet()) {
            var id = Identifier.fromNamespaceAndPath("convergence", path);
            var block = BuiltInRegistries.BLOCK.getValue(id); var state = block.defaultBlockState();
            c.assertFalse(state.isAir(), "The building block is registered as a real native block");
            c.assertTrue(block.asItem() == Convergence.ITEMS.get(id.toString()), "The native block and its canonical inventory item are connected");
            c.assertValueEqual(state.getLightEmission(), light.get(path).intValue(), "Each native block emits its documented light level");
            c.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE), "Native datapack tags make the building block pickaxe-mineable");
            c.assertTrue(new ItemStack(Items.DIAMOND_PICKAXE).isCorrectToolForDrops(state), "An ordinary native diamond pickaxe can harvest the custom block");
            var drops = Block.getDrops(state, c.getLevel(), at, null, null, new ItemStack(Items.DIAMOND_PICKAXE));
            c.assertValueEqual(drops.size(), 1, "The actual registered native loot table returns one drop");
            c.assertTrue(ItemStack.matches(drops.getFirst(), new ItemStack(Convergence.ITEMS.get(id.toString()))), "The actual loot table returns the same canonical block with its literal name and components");
        }
        c.succeed();
    }
}

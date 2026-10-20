package dev.convergence;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** The helper adapter keeps original Infinity gear while native weapon classes perform the attacks. */
public class AgentInfinityWeaponsGameTests {
    private static final class Arena implements AutoCloseable {
        private static long sequence = 3200;
        final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++sequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final List<ChunkPos> chunks = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final GameTestHelper context;
        final ServerPlayer owner;
        final AgentCompanions helpers;
        final IronGolem helper;
        final Zombie hostile;

        Arena(GameTestHelper c, String name, double distance) {
            context = c;
            owner = new ModeGameTests().player(c, "iw-" + Integer.toUnsignedString(name.hashCode(), 36));
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SURVIVAL);
            var feet = c.absolutePos(new BlockPos(20, 55, 20));
            for (var at : BlockPos.betweenClosed(feet.offset(-13, -1, -13), feet.offset(13, 8, 13)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1
                    ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            for (int x = (feet.getX() - 13) >> 4; x <= (feet.getX() + 13) >> 4; x++)
                for (int z = (feet.getZ() - 13) >> 4; z <= (feet.getZ() + 13) >> 4; z++) {
                    var chunk = new ChunkPos(x, z); chunks.add(chunk);
                    c.getLevel().getChunkSource().addTicketWithRadius(ticket, chunk, 2);
                }
            var center = Vec3.atBottomCenterOf(feet);
            owner.setPos(center.add(0, 0, -4));
            helpers = AgentCompanions.get(c.getLevel().getServer());
            helpers.spawn(owner, "alpha");
            var record = helpers.owned(owner, "alpha");
            if (record == null) throw new IllegalStateException("Normal helper creation failed");
            helper = helpers.loaded.get(UUID.fromString(record.getKey()));
            helpers.profile(owner, "alpha", AgentCompanions.Profile.ULTIMATE_FINALS);
            helper.setPos(center);
            hostile = EntityTypes.HUSK.create(c.getLevel(), EntitySpawnReason.COMMAND);
            hostile.setPersistenceRequired();
            // Extend only health. The target's native AI, movement, damage
            // immunity, gravity and knockback remain enabled and unmodified.
            hostile.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); hostile.setHealth(1000);
            hostile.setPos(center.add(distance, 0, 0));
            c.getLevel().addFreshEntity(hostile);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            hostile.discard(); helpers.dismiss(owner, "alpha");
            OperatorGameTests.deop(owner); helpers.server.getPlayerList().remove(owner);
            for (var chunk : chunks) context.getLevel().getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
        }
    }

    private void equip(Arena f, String path) {
        switch (path) {
            case "spear" -> AgentWeapons.spear(f.helper, f.hostile);
            case "mace" -> AgentWeapons.mace(f.helper, f.hostile);
            case "sword" -> AgentWeapons.sword(f.helper, f.hostile);
            default -> throw new IllegalArgumentException(path);
        }
    }

    private static Item base(String path) {
        return switch (path) { case "spear" -> Items.NETHERITE_SPEAR; case "mace" -> Items.MACE; case "sword" -> Items.NETHERITE_SWORD; default -> throw new IllegalArgumentException(path); };
    }

    private static <T> void originalComponent(GameTestHelper c, ItemStack actual, TypedDataComponent<T> original) {
        c.assertTrue(java.util.Objects.equals(actual.get(original.type()), original.value()),
            "The native-backed helper loadout keeps original component " + original.type()
                + "; expected=" + original.value() + ", actual=" + actual.get(original.type()));
    }

    private void originalGear(GameTestHelper c, ItemStack actual, String path) {
        var original = Convergence.ITEMS.get("convergence:" + path).getDefaultInstance();
        c.assertTrue(actual.is(base(path)), "Actual held item retains the native weapon class: " + path);
        for (var component : original.getComponents()) originalComponent(c, actual, component);
        c.assertTrue(java.util.Objects.equals(actual.get(DataComponents.ITEM_MODEL), Identifier.parse("convergence:" + path)),
            "Original model selects the original Infinity artwork");
        c.assertValueEqual(actual.getHoverName().getString(), original.getHoverName().getString(), "The weapon has its exact original Infinity name");
        c.assertTrue(java.util.Objects.equals(actual.get(DataComponents.ATTRIBUTE_MODIFIERS), original.get(DataComponents.ATTRIBUTE_MODIFIERS)),
            "The complete original attack and speed modifiers are retained");
        c.assertTrue(actual.has(DataComponents.UNBREAKABLE), "The original weapon remains unbreakable");
        c.assertValueEqual(actual.getMaxDamage(), 32767, "The original durability is retained");
    }

    /** Classify only this synthetic test viewer through the pinned Floodgate API. */
    private static final class BedrockViewer implements AutoCloseable {
        final org.geysermc.floodgate.core.api.SimpleFloodgateApi api;
        final org.geysermc.floodgate.api.player.FloodgatePlayer classification;
        final UUID id;
        BedrockViewer(ServerPlayer viewer) {
            var current = org.geysermc.floodgate.api.FloodgateApi.getInstance();
            if (!(current instanceof org.geysermc.floodgate.core.api.SimpleFloodgateApi pinned))
                throw new IllegalStateException("The pinned native Floodgate API is unavailable");
            api = pinned; id = viewer.getUUID();
            if (api.getPlayer(id) != null) throw new IllegalStateException("A real Floodgate session must never be replaced by this test fixture");
            classification = (org.geysermc.floodgate.api.player.FloodgatePlayer) Proxy.newProxyInstance(
                org.geysermc.floodgate.api.player.FloodgatePlayer.class.getClassLoader(),
                new Class<?>[]{org.geysermc.floodgate.api.player.FloodgatePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getJavaUniqueId", "getCorrectUniqueId" -> id;
                    case "getJavaUsername", "getCorrectUsername", "getUsername" -> viewer.getGameProfile().name();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Disposable native Bedrock viewer classification";
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
            api.addPlayer(classification);
        }
        @Override public void close() {
            api.setPendingRemove(classification); api.playerRemoved(id);
            if (api.isFloodgatePlayer(id)) throw new IllegalStateException("The test viewer's Floodgate classification was not retired");
        }
    }

    private void wireGear(GameTestHelper c, ItemStack wire, String path, boolean customAppearance,
        net.fabricmc.fabric.api.networking.v1.context.PacketContext context) {
        var original = Convergence.ITEMS.get("convergence:" + path).getDefaultInstance();
        c.assertTrue(wire.is(base(path)), "The viewer receives the supported native weapon identity");
        // Client stacks intentionally differ from server components: Polymer
        // renders native attributes and unbreakability as client tooltip lore.
        // Use the complete normal catalogue projection through the same native
        // codec as the independent client expectation, without a component
        // allowlist that mistakes a presentation change for server mutation.
        var originalSnapshot = original.copy();
        var catalogueWire = eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(original,
            context, c.getLevel().registryAccess());
        var expectedBuffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), c.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(expectedBuffer, catalogueWire);
            var expected = ItemStack.STREAM_CODEC.decode(expectedBuffer);
            c.assertTrue(wire.getCount() == expected.getCount() && ItemStack.isSameItemSameComponents(wire, expected),
                "The complete decoded avatar weapon exactly matches the original catalogue item's normal Polymer/native-codec projection"
                    + "; path=" + path + ", customAppearance=" + customAppearance
                    + ", expected=" + expected.getComponents() + ", actual=" + wire.getComponents());
        } finally { expectedBuffer.release(); }
        c.assertTrue(ItemStack.isSameItemSameComponents(original, originalSnapshot),
            "Client tooltip generation never mutates the registered catalogue stack");
        var restored = eu.pb4.polymer.core.api.item.PolymerItemUtils.getRealItemStack(wire,
            context, c.getLevel().registryAccess());
        c.assertTrue(ItemStack.isSameItemSameComponents(restored, originalSnapshot),
            "The outgoing projection recovers every original server component, including untouched lore");
        c.assertTrue(java.util.Objects.equals(wire.get(DataComponents.ITEM_MODEL), customAppearance
            ? Identifier.parse("convergence:" + path) : base(path).components().get(DataComponents.ITEM_MODEL)),
            customAppearance ? "The accepted-pack or Bedrock viewer receives the original Infinity model"
                : "Java without the pack receives a complete existing native model");
        c.assertValueEqual(wire.getHoverName().getString(), original.getHoverName().getString(), "The viewer keeps the literal Infinity weapon name");
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void allThreeNativeHelperWeaponsCarryCompleteOriginalInfinityGear(GameTestHelper c) {
        try (var f = new Arena(c, "infinity-helper-gear", 6)) {
            for (String path : List.of("spear", "mace", "sword")) {
                var original = Convergence.ITEMS.get("convergence:" + path).getDefaultInstance();
                var snapshot = original.copy();
                equip(f, path); originalGear(c, f.helper.getMainHandItem(), path);
                c.assertTrue(ItemStack.isSameItemSameComponents(original, snapshot), "The registered player item was never altered by helper adaptation");
                c.assertTrue(f.helper.getOffhandItem().isEmpty(), "The helper carries no duplicate offhand weapon");
                for (var slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST})
                    c.assertValueEqual(f.helper.getDropChances().byEquipment(slot), 0F, "Helper gear cannot be farmed as equipment drops");
            }
            c.assertTrue(f.helper.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA), "The existing native helper elytra remains unchanged");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=30)
    public void repeatedInfinitySpearEquipKeepsTheSameLiveNativeCharge(GameTestHelper c) {
        var f = new Arena(c, "infinity-charge-stable", 6.8);
        AgentWeapons.spear(f.helper, f.hostile);
        var stack = f.helper.getMainHandItem();
        for (int tick = 1; tick <= 8; tick++) c.runAtTickTime(tick, () -> {
            if (f.closed.get()) return;
            try {
                AgentWeapons.spear(f.helper, f.hostile);
                c.assertTrue(f.helper.getMainHandItem() == stack && f.helper.getActiveItem() == stack,
                    "Repeated loadout maintenance never replaces the active native spear object");
                originalGear(c, stack, "spear");
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        c.runAtTickTime(9, () -> {
            if (f.closed.get()) return;
            try {
                c.assertTrue(f.helper.isUsingItem() && f.helper.getTicksUsingItem() >= 7,
                    "Real entity ticks advance the original Infinity spear's native windup instead of resetting it");
                c.succeed();
            } finally { f.close(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void incompleteOrDamagedLoadoutIsRepairedOnceWithoutTouchingOtherArmor(GameTestHelper c) {
        try (var f = new Arena(c, "infinity-loadout-repair", 6)) {
            var helmet = new ItemStack(Items.DIAMOND_HELMET);
            f.helper.setItemSlot(EquipmentSlot.HEAD, helmet);
            AgentWeapons.spear(f.helper, f.hostile);
            var incomplete = f.helper.getMainHandItem();
            incomplete.set(DataComponents.ITEM_NAME, Component.literal("Wrong helper weapon"));
            incomplete.setDamageValue(9);
            f.helper.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.NETHERITE_SWORD));
            AgentWeapons.spear(f.helper, f.hostile);
            var repaired = f.helper.getMainHandItem();
            c.assertTrue(repaired != incomplete, "A mismatched original loadout is repaired instead of retained");
            originalGear(c, repaired, "spear");
            c.assertValueEqual(repaired.getDamageValue(), 0, "A canonical unbreakable loadout starts at its original damage");
            AgentWeapons.spear(f.helper, f.hostile);
            c.assertTrue(f.helper.getMainHandItem() == repaired, "Correct components prevent another replacement");
            c.assertTrue(f.helper.getOffhandItem().isEmpty(), "Loadout maintenance removes the duplicate offhand");
            c.assertTrue(f.helper.getItemBySlot(EquipmentSlot.HEAD) == helmet, "Weapon maintenance never changes unrelated armor slots");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void avatarEquipmentUsesCorrectWireModelForJavaWithoutPackJavaPackAndBedrock(GameTestHelper c) throws Exception {
        try (var f = new Arena(c, "infinity-weapon-wire", 6)) {
            var file = Path.of("crossplay-export/infinity-items.json");
            c.assertTrue(Files.size(file) <= 1_048_576, "Generated native mapping export remains bounded");
            var mappings = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("items");
            var avatar = (AgentAvatars) eu.pb4.polymer.core.api.entity.PolymerEntity.get(f.helper);
            var context = f.owner.connection.getPacketContext();
            var pack = eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.getMainUuid();
            boolean previousPack = eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.hasMainPack(context);
            try {
                for (String viewer : List.of("java-without-pack", "java-pack", "bedrock")) {
                    boolean bedrock = viewer.equals("bedrock"), javaPack = viewer.equals("java-pack");
                    eu.pb4.polymer.common.api.PolymerCommonUtils.setHasResourcePack(context, pack, javaPack);
                    try (var classification = bedrock ? new BedrockViewer(f.owner) : null) {
                        c.assertValueEqual(CrossplaySupport.bedrock(f.owner), bedrock, "The native classification matches this test viewer branch");
                        c.assertValueEqual(eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.hasMainPack(context), javaPack,
                            "The native resource-pack state matches this test viewer branch");
                        for (String path : List.of("spear", "mace", "sword")) {
                            equip(f, path);
                            var actual = f.helper.getMainHandItem(); var snapshot = actual.copy();
                            originalGear(c, actual, path);
                            int using = f.helper.getTicksUsingItem(); boolean active = f.helper.isUsingItem();
                            var activeStack = f.helper.getActiveItem();
                            var chest = f.helper.getItemBySlot(EquipmentSlot.CHEST);
                            var helmet = new ItemStack(Items.DIAMOND_HELMET);
                            List<Pair<EquipmentSlot, ItemStack>> equipment = List.of(Pair.of(EquipmentSlot.MAINHAND, actual),
                                Pair.of(EquipmentSlot.CHEST, chest), Pair.of(EquipmentSlot.HEAD, helmet));
                            var projected = avatar.getPolymerVisibleEquipment(equipment, f.owner);
                            var visible = projected.getFirst().getSecond();
                            c.assertTrue(visible.is(Convergence.ITEMS.get("convergence:" + path)),
                                "The avatar uses the registered original item only for outgoing equipment projection");
                            c.assertTrue(projected.get(1).getSecond() == chest && projected.get(2).getSecond() == helmet,
                                "Viewer equipment projection leaves every unrelated armor stack alone");
                            var visibleSnapshot = visible.copy();
                            var wire = eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(visible,
                                context, c.getLevel().registryAccess());
                            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), c.getLevel().registryAccess());
                            try {
                                ItemStack.STREAM_CODEC.encode(buffer, wire);
                                var decoded = ItemStack.STREAM_CODEC.decode(buffer);
                                wireGear(c, decoded, path, bedrock || javaPack, context);
                                if (bedrock) {
                                    var model = decoded.get(DataComponents.ITEM_MODEL).toString();
                                    var definitions = mappings.getAsJsonArray(BuiltInRegistries.ITEM.getKey(decoded.getItem()).toString());
                                    boolean matching = false;
                                    for (var element : definitions) {
                                        var definition = element.getAsJsonObject();
                                        matching |= definition.get("model").getAsString().equals(model)
                                            && definition.get("bedrock_identifier").getAsString().equals("convergence:" + path);
                                    }
                                    c.assertTrue(matching, "The outgoing native Bedrock identity/model selects the existing original Geyser definition");
                                }
                            } finally { buffer.release(); }
                            c.assertTrue(ItemStack.isSameItemSameComponents(visible, visibleSnapshot),
                                "Native packet projection never mutates the avatar's catalogue representation");
                            c.assertTrue(f.helper.getMainHandItem() == actual && equipment.getFirst().getSecond() == actual
                                && ItemStack.isSameItemSameComponents(actual, snapshot), "Viewing equipment never replaces or mutates the actual server weapon");
                            originalGear(c, actual, path);
                            c.assertValueEqual(f.helper.getTicksUsingItem(), using, "Projection never advances or resets native spear windup");
                            c.assertValueEqual(f.helper.isUsingItem(), active, "Projection never starts or stops native item use");
                            c.assertTrue(f.helper.getActiveItem() == activeStack, "The actual active native item object remains untouched");
                        }
                    }
                }
            } finally {
                eu.pb4.polymer.common.api.PolymerCommonUtils.setHasResourcePack(context, pack, previousPack);
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void avatarProjectionRefusesNoncanonicalWeaponsAndNeverOverridesOtherSlots(GameTestHelper c) {
        try (var f = new Arena(c, "infinity-avatar-scope", 6)) {
            AgentWeapons.spear(f.helper, f.hostile);
            var canonical = f.helper.getMainHandItem();
            var altered = canonical.copy(); altered.set(DataComponents.ITEM_NAME, Component.literal("An independently modified weapon"));
            var avatar = (AgentAvatars) eu.pb4.polymer.core.api.entity.PolymerEntity.get(f.helper);
            List<Pair<EquipmentSlot, ItemStack>> other = List.of(Pair.of(EquipmentSlot.MAINHAND, altered),
                Pair.of(EquipmentSlot.OFFHAND, canonical), Pair.of(EquipmentSlot.CHEST, canonical));
            c.assertTrue(avatar.getPolymerVisibleEquipment(other, f.owner) == other,
                "Only a complete canonical main-hand helper weapon uses the outgoing identity adapter");
            var ordinary = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
            List<Pair<EquipmentSlot, ItemStack>> plain = List.of(Pair.of(EquipmentSlot.MAINHAND, canonical));
            c.assertTrue(new AgentAvatars(ordinary).getPolymerVisibleEquipment(plain, f.owner) == plain,
                "An ordinary unregistered golem cannot acquire helper-only projection even with matching components");
            var nativeItem = new ItemStack(Items.NETHERITE_SPEAR);
            var nativeWire = eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(nativeItem,
                f.owner.connection.getPacketContext(), c.getLevel().registryAccess());
            c.assertTrue(java.util.Objects.equals(nativeWire.get(DataComponents.ITEM_MODEL), Items.NETHERITE_SPEAR.components().get(DataComponents.ITEM_MODEL)),
                "Ordinary native item rendering has no global Infinity overlay");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=150)
    public void autonomousOriginalInfinityMaceUsesRealNativeSmashDamage(GameTestHelper c) {
        var f = new Arena(c, "infinity-native-smash", 2);
        float[] previous = {f.hostile.getHealth()};
        for (int tick = 1; tick <= 110; tick++) c.runAtTickTime(tick, () -> {
            if (f.closed.get()) return;
            try {
                float now = f.hostile.getHealth();
                var source = f.hostile.getLastDamageSource();
                if (now < previous[0] && source != null && source.is(DamageTypes.MACE_SMASH)) {
                    originalGear(c, f.helper.getMainHandItem(), "mace");
                    c.assertTrue(previous[0] - now >= 80, "The falling native mace uses the original Infinity attack modifiers");
                    c.assertFalse(f.helper.isNoGravity(), "The native smash retains ordinary gravity");
                    c.succeed(); f.close();
                }
                previous[0] = now;
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        c.runAtTickTime(111, () -> {
            if (f.closed.get()) return;
            try { c.assertTrue(false, "Normal helper and hostile AI ticks must produce an actual Infinity MACE_SMASH contact"); }
            finally { f.close(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=100)
    public void regularHelperAppliesOriginalInfinitySwordAttackThroughNativeLifecycle(GameTestHelper c) {
        var f = new Arena(c, "infinity-native-sword", 2);
        f.helpers.profile(f.owner, "alpha", AgentCompanions.Profile.REGULAR);
        float[] previous = {f.hostile.getHealth()};
        for (int tick = 1; tick <= 70; tick++) c.runAtTickTime(tick, () -> {
            if (f.closed.get()) return;
            try {
                float now = f.hostile.getHealth();
                if (previous[0] - now >= 950) {
                    originalGear(c, f.helper.getMainHandItem(), "sword");
                    c.assertTrue(f.hostile.getLastDamageSource() != null
                        && f.hostile.getLastDamageSource().getEntity() == f.helper,
                        "Actual native helper contact applies the original thousand-damage sword modifiers");
                    c.assertFalse(f.helpers.aerial.containsKey(f.helper.getUUID()), "The Regular sword profile remains grounded");
                    c.succeed(); f.close();
                }
                previous[0] = now;
            } catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
        c.runAtTickTime(71, () -> {
            if (f.closed.get()) return;
            try { c.assertTrue(false, "Regular native lifecycle must apply the original Infinity sword's actual attack modifiers"); }
            finally { f.close(); }
        });
    }

    @GameTest
    public void ordinaryUnownedGolemEquipmentAndNativeUseAreUnaffected(GameTestHelper c) {
        var golem = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
        var target = EntityTypes.HUSK.create(c.getLevel(), EntitySpawnReason.COMMAND);
        var hand = new ItemStack(Items.DIAMOND_SWORD); hand.set(DataComponents.CUSTOM_NAME, Component.literal("Ordinary golem's weapon"));
        var offhand = new ItemStack(Items.SHIELD); var chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        golem.setItemSlot(EquipmentSlot.MAINHAND, hand); golem.setItemSlot(EquipmentSlot.OFFHAND, offhand); golem.setItemSlot(EquipmentSlot.CHEST, chest);
        golem.setDropChance(EquipmentSlot.MAINHAND, .37F); golem.setDropChance(EquipmentSlot.OFFHAND, .28F); golem.setDropChance(EquipmentSlot.CHEST, .17F);
        golem.startUsingItem(InteractionHand.OFF_HAND);
        AgentWeapons.spear(golem, target); AgentWeapons.mace(golem, target); AgentWeapons.sword(golem, target); AgentWeapons.stop(golem);
        c.assertTrue(golem.getMainHandItem() == hand && golem.getOffhandItem() == offhand && golem.getItemBySlot(EquipmentSlot.CHEST) == chest,
            "Unregistered ordinary golems retain every actual equipment stack");
        c.assertTrue(golem.isUsingItem() && golem.getActiveItem() == offhand, "Helper loadouts do not cancel an ordinary golem's native use");
        c.assertValueEqual(golem.getDropChances().byEquipment(EquipmentSlot.MAINHAND), .37F, "Ordinary native drop chances stay untouched");
        c.assertValueEqual(golem.getDropChances().byEquipment(EquipmentSlot.OFFHAND), .28F, "Ordinary offhand remains independently owned");
        c.assertValueEqual(golem.getDropChances().byEquipment(EquipmentSlot.CHEST), .17F, "Ordinary armor remains independently owned");
        c.succeed();
    }
}

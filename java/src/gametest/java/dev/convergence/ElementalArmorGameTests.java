package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;

public class ElementalArmorGameTests {
    private static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final List<EquipmentSlotGroup> GROUPS = List.of(EquipmentSlotGroup.HEAD, EquipmentSlotGroup.CHEST, EquipmentSlotGroup.LEGS, EquipmentSlotGroup.FEET);

    @GameTest public void bothSuitsHaveLiteralNamesOwnEquipmentAndSlotScopedNativeStats(GameTestHelper c) {
        var ops = RegistryOps.create(NbtOps.INSTANCE, c.getLevel().registryAccess());
        for (var suit : ElementalArmor.Suit.values()) for (int i = 0; i < PIECES.size(); i++) {
            String path = suit.prefix() + "_" + PIECES.get(i);
            var stack = gear(path);
            c.assertValueEqual(BuiltInRegistries.ITEM.getKey(stack.getItem()), id(path), "The real registry contains the distinct armor piece");
            c.assertValueEqual(stack.get(DataComponents.ITEM_MODEL), id(path), "The inventory model uses its own item ID");
            c.assertValueEqual(stack.getHoverName().getString(), GearNames.text(path).getString(), "Packless clients receive the literal gear name");
            c.assertFalse(stack.get(DataComponents.ITEM_NAME).getContents() instanceof TranslatableContents, "Names do not depend on a client translation key");
            var equipment = stack.get(DataComponents.EQUIPPABLE);
            c.assertValueEqual(equipment.slot(), SLOTS.get(i), "The piece equips in its actual armor slot");
            c.assertValueEqual(equipment.assetId().orElseThrow().identifier(), id(suit.prefix() + "_armor"), "Each suit owns an independent equipped model");
            c.assertValueEqual(equipment.damageOnHurt(), suit == ElementalArmor.Suit.ELEMENTAL, "Only the normal-durability Elemental suit takes armor wear");
            c.assertValueEqual(equipment.equipSound(), ElementalArmor.baseItem(path).getDefaultInstance().get(DataComponents.EQUIPPABLE).equipSound(), "Native optional equip sound is preserved for every piece");
            c.assertValueEqual(stack.has(DataComponents.GLIDER), suit == ElementalArmor.Suit.INFINITY && SLOTS.get(i) == EquipmentSlot.CHEST,
                "Only the existing Infinity chestpiece gains native flight; all other suit pieces remain unchanged");
            c.assertValueEqual(stack.has(DataComponents.UNBREAKABLE), suit == ElementalArmor.Suit.INFINITY, "Only ultimate Infinity armor is unbreakable");
            c.assertValueEqual(stack.getMaxDamage(), ElementalArmor.baseItem(path).getDefaultInstance().getMaxDamage(), "Native base durability is preserved");
            double armor = 0, toughness = 0, knockback = 0;
            for (var entry : stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers()) {
                c.assertValueEqual(entry.slot(), GROUPS.get(i), "Every equipment modifier is scoped to this piece's slot");
                if (entry.attribute().equals(Attributes.ARMOR)) armor += entry.modifier().amount();
                if (entry.attribute().equals(Attributes.ARMOR_TOUGHNESS)) toughness += entry.modifier().amount();
                if (entry.attribute().equals(Attributes.KNOCKBACK_RESISTANCE)) knockback += entry.modifier().amount();
            }
            c.assertValueEqual(armor, (double) suit.protection(SLOTS.get(i)), "The registered protection matches the suit and slot");
            c.assertValueEqual(toughness, suit.toughness(), "Native equipment includes its full toughness");
            c.assertTrue(Math.abs(knockback - (suit == ElementalArmor.Suit.INFINITY ? .1 : 0)) < 1e-7, "The native netherite bonus is preserved without copying it to Elemental armor (allowing native float precision)");
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Saved " + path));
            if (suit == ElementalArmor.Suit.ELEMENTAL) stack.setDamageValue(11);
            var restored = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, stack).getOrThrow()).getOrThrow();
            c.assertTrue(ItemStack.isSameItemSameComponents(stack, restored), "Real native item NBT retains identity, name, damage and equipment components");
        }
        c.assertTrue(ElementalArmor.ofPath("minecraft:elemental_helmet") == null, "Another namespace cannot impersonate registered armor");
        c.assertTrue(ElementalArmor.ofPath("elemental_helmet_extra") == null, "A similar path cannot impersonate registered armor");
        c.assertValueEqual(ElementalArmor.protection("convergence:infinity_chestplate"), 14, "Crossplay can query the full item identifier");
        c.succeed();
    }

    @GameTest public void selectionPreviewNeverWritesAndExplicitAttunementUsesNativePlayerFile(GameTestHelper c) throws Exception {
        var p = player(c, "element-selection");
        try {
            GameModes.state(p).remove("elemental_armor");
            var before = GameModes.state(p).copy();
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "A new wearer previews Fire");
            c.assertValueEqual(GameModes.state(p), before, "Default preview creates no state or entitlement");
            var invalid = new CompoundTag(); invalid.putString("element", "not-a-real-element"); invalid.putInt("future_field", 7);
            GameModes.state(p).put("elemental_armor", invalid);
            before = GameModes.state(p).copy();
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.FIRE, "Malformed selection falls back to Fire");
            c.assertValueEqual(GameModes.state(p), before, "Malformed preview preserves the original save for recovery");
            var treasured = gear("elemental_chestplate"); treasured.setDamageValue(19);
            treasured.set(DataComponents.CUSTOM_NAME, Component.literal("My attuned armor"));
            p.getInventory().setItem(8, treasured);
            for (var element : ElementalArmor.Element.values()) {
                c.assertValueEqual(ElementalArmor.select(p, element), 1, "An explicit eligible choice succeeds");
                var disk = NbtIo.readCompressed(GameModes.savedPlayerFile(p.level().getServer(), p.getUUID()), NbtAccounter.create(64L * 1024 * 1024));
                var restored = new ServerPlayer(p.level().getServer(), p.level(), p.getGameProfile(), p.clientInformation());
                restored.load(TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), disk));
                c.assertValueEqual(ElementalArmor.selected(restored), element, "The actual player file restores each selected element");
                c.assertValueEqual(GameModes.state(restored).getCompoundOrEmpty("elemental_armor").getIntOr("future_field", 0), 7, "Explicit choice preserves unrelated fields");
                c.assertTrue(ItemStack.isSameItemSameComponents(restored.getInventory().getItem(8), treasured), "Saving attunement also preserves the wearer's real gear");
            }
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void fullElementalSuitActivatesEveryAttunementWithoutReplacingTheArmor(GameTestHelper c) {
        var p = player(c, "element-all-powers");
        try {
            equip(p, ElementalArmor.Suit.ELEMENTAL); p.doTick();
            c.assertValueEqual(p.getArmorValue(), 20, "All four native diamond-strength pieces contribute protection");
            var worn = SLOTS.stream().map(slot -> p.getItemBySlot(slot).copy()).toList();
            for (var element : ElementalArmor.Element.values()) {
                p.removeAllEffects();
                c.assertValueEqual(ElementalArmor.select(p, element), 1, "A full suit accepts each attunement");
                switch (element) {
                    case FIRE -> { effect(c, p, MobEffects.FIRE_RESISTANCE, 0); effect(c, p, MobEffects.STRENGTH, 1); }
                    case WATER -> { effect(c, p, MobEffects.WATER_BREATHING, 0); effect(c, p, MobEffects.DOLPHINS_GRACE, 0); effect(c, p, MobEffects.NIGHT_VISION, 0); }
                    case EARTH -> { effect(c, p, MobEffects.RESISTANCE, 1); effect(c, p, MobEffects.HASTE, 1); }
                    case AIR -> { effect(c, p, MobEffects.SPEED, 1); effect(c, p, MobEffects.JUMP_BOOST, 1); effect(c, p, MobEffects.SLOW_FALLING, 0); }
                }
                for (int i = 0; i < SLOTS.size(); i++)
                    c.assertTrue(ItemStack.isSameItemSameComponents(worn.get(i), p.getItemBySlot(SLOTS.get(i))), "Attuning never replaces or renames an equipped item");
            }
            p.removeAllEffects(); ElementalArmor.select(p, ElementalArmor.Element.FIRE);
            ElementalArmor.select(p, ElementalArmor.Element.WATER);
            c.assertTrue(p.hasEffect(MobEffects.FIRE_RESISTANCE), "Changing elements lets the previous short buff fade naturally");
            effect(c, p, MobEffects.WATER_BREATHING, 0);
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void completeInfinitySuitProvidesUltimateProfileAndPreservesAdminBases(GameTestHelper c) {
        var p = player(c, "infinity-all-powers");
        try {
            var console = p.level().getServer().createCommandSourceStack();
            c.assertTrue(AdminStats.set(console, p, "armor", 7).success(), "The existing admin editor stores an expanded armor base");
            c.assertTrue(AdminStats.set(console, p, "max_health", 200).success(), "The existing admin editor stores the health base");
            c.assertTrue(AdminStats.set(console, p, "attack_damage", 42).success(), "The existing admin editor stores the damage base");
            c.assertTrue(AdminStats.set(console, p, "absorption", 12).success(), "The existing editor establishes real absorption and its capacity");
            equip(p, ElementalArmor.Suit.INFINITY); p.doTick();
            p.setHealth(91);
            float absorption = p.getAbsorptionAmount();
            var adminState = GameModes.state(p).getCompoundOrEmpty(AdminStats.STORAGE).copy();
            ElementalArmor.tick(p);
            c.assertValueEqual(ElementalArmor.fullSuit(p), ElementalArmor.Suit.INFINITY, "All four exact ultimate pieces form the Infinity suit");
            c.assertValueEqual(p.getArmorValue(), 47, "Forty protection adds to the existing admin armor base");
            c.assertValueEqual(p.getAttribute(Attributes.ARMOR).getBaseValue(), 7d, "Suit power never resets an admin armor base");
            c.assertValueEqual(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 200d, "Suit power never resets an admin health base");
            c.assertValueEqual(p.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue(), 42d, "Suit power never resets an admin damage base");
            c.assertValueEqual(p.getHealth(), 91f, "Applying powers does not overwrite current health");
            c.assertValueEqual(p.getAbsorptionAmount(), absorption, "Applying powers does not overwrite current absorption");
            c.assertValueEqual(GameModes.state(p).getCompoundOrEmpty(AdminStats.STORAGE), adminState, "Suit powers preserve admin edit and reset history");
            effect(c, p, MobEffects.FIRE_RESISTANCE, 0); effect(c, p, MobEffects.WATER_BREATHING, 0); effect(c, p, MobEffects.NIGHT_VISION, 0);
            effect(c, p, MobEffects.SPEED, 2); effect(c, p, MobEffects.HASTE, 4); effect(c, p, MobEffects.STRENGTH, 4);
            effect(c, p, MobEffects.RESISTANCE, 3); effect(c, p, MobEffects.REGENERATION, 2);
        } finally { cleanup(p); }
        c.succeed();
    }

    /** Independent pre-flight save shape: unchanged defaults were never written as patches. */
    private static CompoundTag legacyInfinityChest() {
        var item = new CompoundTag();
        item.putString("id", "convergence:infinity_chestplate"); item.putInt("count", 1);
        var components = new CompoundTag();
        components.putString("minecraft:custom_name", "My pre-flight Infinity chestplate");
        components.putInt("minecraft:damage", 37); components.putInt("minecraft:repair_cost", 9);
        var enchantments = new CompoundTag();
        enchantments.putInt("minecraft:protection", 4); enchantments.putInt("minecraft:unbreaking", 3);
        enchantments.putInt("minecraft:mending", 1);
        components.put("minecraft:enchantments", enchantments);
        var trim = new CompoundTag(); trim.putString("material", "minecraft:gold"); trim.putString("pattern", "minecraft:spire");
        components.put("minecraft:trim", trim);
        var data = new CompoundTag(); data.putString("owner_note", "Keep my existing armor");
        var future = new CompoundTag(); future.putInt("receipt", 49); future.putBoolean("pending", true);
        data.put("future_mod_data", future); components.put("minecraft:custom_data", data);
        item.put("components", components);
        return item;
    }

    private static ItemStack legacyInfinityChest(GameTestHelper c) {
        return ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, c.getLevel().registryAccess()), legacyInfinityChest()).getOrThrow();
    }

    private static void assertLegacyChest(GameTestHelper c, ItemStack actual) {
        var expected = legacyInfinityChest(c);
        c.assertTrue(actual.is(Convergence.ITEMS.get("convergence:infinity_chestplate")), "Old gear retains its exact Infinity item identity");
        c.assertTrue(actual.has(DataComponents.GLIDER), "Old saved item gains the new default flight component without replacement");
        c.assertTrue(ItemStack.isSameItemSameComponents(actual, expected), "Name, enchantments, damage, trim, repair cost, custom data and armor defaults are preserved together");
    }

    @GameTest public void existingInfinityChestSaveGainsFlightAndRetainsEveryOwnedComponent(GameTestHelper c) {
        var registries = c.getLevel().registryAccess(); var ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        var legacy = legacyInfinityChest(); var patch = legacy.getCompoundOrEmpty("components");
        c.assertFalse(patch.contains("minecraft:glider") || patch.contains("!minecraft:glider"), "The old native save has neither a flight component nor an explicit removal patch");
        var untouched = legacy.copy(); var stack = ItemStack.CODEC.parse(ops, legacy).getOrThrow();
        c.assertValueEqual(legacy, untouched, "Decoding never edits the original old saved NBT");
        assertLegacyChest(c, stack);
        c.assertValueEqual(stack.getHoverName().getString(), "My pre-flight Infinity chestplate", "The owner's saved name wins over the default label");
        c.assertValueEqual(stack.getDamageValue(), 37, "Existing durability damage is retained even on unbreakable gear");
        c.assertValueEqual(stack.get(DataComponents.REPAIR_COST), 9, "Existing repair cost is retained");
        var enchantments = stack.get(DataComponents.ENCHANTMENTS);
        c.assertValueEqual(enchantments.getLevel(registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION)), 4, "Protection level survives the old save decode");
        c.assertTrue(stack.has(DataComponents.TRIM), "The actual native trim component survives decode");
        c.assertValueEqual(stack.get(DataComponents.CUSTOM_DATA), CustomData.of(patch.getCompoundOrEmpty("minecraft:custom_data")), "Unknown nested owner/mod data is preserved");
        var nativeBase = Items.NETHERITE_CHESTPLATE.getDefaultInstance();
        for (var component : nativeBase.getComponents()) {
            if (List.of(DataComponents.ITEM_NAME, DataComponents.ITEM_MODEL, DataComponents.ATTRIBUTE_MODIFIERS,
                    DataComponents.EQUIPPABLE, DataComponents.DAMAGE, DataComponents.REPAIR_COST,
                    DataComponents.ENCHANTMENTS, DataComponents.CUSTOM_NAME, DataComponents.CUSTOM_DATA,
                    DataComponents.TRIM, DataComponents.UNBREAKABLE).contains(component.type())) continue;
            c.assertValueEqual(stack.get(component.type()), nativeBase.get(component.type()), "Native netherite defaults remain intact: " + component.type());
        }
        var encoded = (CompoundTag) ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        c.assertFalse(encoded.getCompoundOrEmpty("components").contains("minecraft:glider"), "The default is inherited rather than manufactured as an inventory patch");
        assertLegacyChest(c, ItemStack.CODEC.parse(ops, encoded).getOrThrow());
        var intentionallyDisabled = stack.copy(); intentionallyDisabled.remove(DataComponents.GLIDER);
        var disabled = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, intentionallyDisabled).getOrThrow()).getOrThrow();
        c.assertFalse(disabled.has(DataComponents.GLIDER), "An explicitly removed component remains an owner-authored override; no automatic inventory rewrite occurs");
        c.succeed();
    }

    @GameTest public void savedInfinityFlightSurvivesNativePlayerModeAndBackpackStorage(GameTestHelper c) throws Exception {
        var p = player(c, "infinity-old-save");
        try {
            equip(p, ElementalArmor.Suit.INFINITY);
            var old = legacyInfinityChest(c); p.setItemSlot(EquipmentSlot.CHEST, old);
            p.getInventory().setItem(8, old.copy()); p.getEnderChestInventory().setItem(9, old.copy());
            var profile = GameModes.capture(p); profile.put("equipment_chest", legacyInfinityChest());
            var profiles = new CompoundTag(); profiles.put("SURVIVAL", profile);
            GameModes.state(p).put("profiles", profiles); GameModes.state(p).putString("owner_receipt", "preserve mode state");
            var survivalBag = new ArrayList<ItemStack>(java.util.Collections.nCopies(BackpackStorage.SIZE, ItemStack.EMPTY));
            survivalBag.set(7, old.copy()); BackpackStorage.write(p, "SURVIVAL", survivalBag);
            var creativeBag = new ArrayList<ItemStack>(java.util.Collections.nCopies(BackpackStorage.SIZE, ItemStack.EMPTY));
            creativeBag.set(8, new ItemStack(Items.EMERALD, 5)); BackpackStorage.write(p, "CREATIVE", creativeBag);
            PlayerPersistence.save(p);
            var disk = NbtIo.readCompressed(GameModes.savedPlayerFile(p.level().getServer(), p.getUUID()), NbtAccounter.create(64L * 1024 * 1024));
            var restored = new ServerPlayer(p.level().getServer(), p.level(), p.getGameProfile(), p.clientInformation());
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), disk));
            assertLegacyChest(c, restored.getItemBySlot(EquipmentSlot.CHEST));
            assertLegacyChest(c, restored.getInventory().getItem(8)); assertLegacyChest(c, restored.getEnderChestInventory().getItem(9));
            assertLegacyChest(c, BackpackStorage.read(restored, "SURVIVAL").get(7));
            c.assertTrue(BackpackStorage.read(restored, "CREATIVE").get(7).isEmpty(), "Survival gear never leaks into the independent Creative backpack");
            c.assertValueEqual(BackpackStorage.read(restored, "CREATIVE").get(8).getCount(), 5, "Unrelated Creative storage remains unchanged");
            var savedProfile = GameModes.state(restored).getCompoundOrEmpty("profiles").getCompoundOrEmpty("SURVIVAL");
            // The detached disk reader has no packet listener. Restore the
            // actual disk profile into our connected native player before
            // testing effects and flight, which legitimately send packets.
            GameModes.restore(p, savedProfile);
            assertLegacyChest(c, p.getItemBySlot(EquipmentSlot.CHEST));
            c.assertValueEqual(ElementalArmor.fullSuit(p), ElementalArmor.Suit.INFINITY, "The old matching suit remains Infinity after player and mode reload");
            c.assertValueEqual(GameModes.state(restored).getStringOr("owner_receipt", ""), "preserve mode state", "Unrelated owner state survives the native save and mode restore");
            ElementalArmor.tick(p);
            effect(c, p, MobEffects.STRENGTH, 4); effect(c, p, MobEffects.RESISTANCE, 3); effect(c, p, MobEffects.HASTE, 4);
            var airborne = c.absolutePos(new BlockPos(2, 6, 2));
            p.setPos(airborne.getX() + .5, airborne.getY(), airborne.getZ() + .5);
            p.setOnGround(false); p.setDeltaMovement(0, -.4, 0);
            c.assertTrue(p.tryToStartFallFlying() && p.isFallFlying(), "The real reloaded Infinity chestpiece starts native Survival glide with its full set intact");
            assertLegacyChest(c, p.getItemBySlot(EquipmentSlot.CHEST));
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void infinityFlightProjectsNativeElytraWithoutChangingRealArmorOrOtherEquipment(GameTestHelper c) {
        var p = player(c, "infinity-wire");
        try {
            var registries = c.getLevel().registryAccess(); var context = p.connection.getPacketContext();
            var original = legacyInfinityChest(c);
            c.assertValueEqual(CrossplaySupport.BASES.get("infinity_chestplate"), Items.ELYTRA, "The iPad native base is actual elytra");
            c.assertTrue(CrossplaySupport.nativeAppearance("infinity_chestplate", true, true), "Bedrock retains native flight identity even with the resource pack");
            c.assertFalse(CrossplaySupport.nativeAppearance("infinity_chestplate", false, true), "Pack-enabled Java retains its own armor and wings asset");
            var wire = eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(original, context, registries);
            c.assertTrue(wire.is(Items.ELYTRA), "The real Polymer projection sends elytra, allowing native Bedrock glide");
            c.assertTrue(wire.has(DataComponents.GLIDER), "The native outgoing item retains glide");
            c.assertValueEqual(wire.get(DataComponents.EQUIPPABLE), Items.ELYTRA.components().get(DataComponents.EQUIPPABLE), "Packless Java and Bedrock receive complete native elytra equipment defaults");
            c.assertValueEqual(wire.getHoverName(), original.getHoverName(), "Wire projection retains the owner's custom name");
            assertLegacyChest(c, eu.pb4.polymer.core.api.item.PolymerItemUtils.getRealItemStack(wire, registries));
            var nativeWings = new ItemStack(Items.ELYTRA); var ordinaryBefore = nativeWings.copy();
            var ordinaryWire = eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(nativeWings, context, registries);
            c.assertTrue(ItemStack.isSameItemSameComponents(ordinaryWire, ordinaryBefore), "Ordinary elytra and all its components remain native");
            c.assertTrue(gear("chestplate").has(DataComponents.GLIDER) && CrossplaySupport.BASES.get("chestplate") == Items.ELYTRA, "Convergence Wings keep their existing native flight");
            for (String path : List.of("cuirass", "elemental_chestplate", "aurora_chestplate", "ember_chestplate", "backpack", "infinity_helmet", "infinity_leggings", "infinity_boots"))
                c.assertFalse(gear(path).has(DataComponents.GLIDER), "Only the requested Infinity chestpiece gains flight: " + path);
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void mixedWrongSlotAndNamedVanillaArmorCannotGrantSuitPowers(GameTestHelper c) {
        var p = player(c, "element-exact-slots");
        try {
            equip(p, ElementalArmor.Suit.ELEMENTAL);
            p.setItemSlot(EquipmentSlot.CHEST, gear("infinity_chestplate"));
            ElementalArmor.tick(p); c.assertTrue(ElementalArmor.fullSuit(p) == null, "A mixed pair of real sets cannot activate either");
            c.assertFalse(p.hasEffect(MobEffects.STRENGTH), "Mixed equipment receives no Fire powers");
            equip(p, ElementalArmor.Suit.ELEMENTAL);
            p.setItemSlot(EquipmentSlot.HEAD, gear("elemental_boots"));
            p.setItemSlot(EquipmentSlot.FEET, gear("elemental_helmet"));
            ElementalArmor.tick(p); c.assertTrue(ElementalArmor.fullSuit(p) == null, "Real pieces in incorrect slots do not count");
            c.assertFalse(p.hasEffect(MobEffects.FIRE_RESISTANCE), "Wrong-slot equipment receives no Fire powers");
            for (int i = 0; i < SLOTS.size(); i++) {
                var fake = ElementalArmor.baseItem("elemental_" + PIECES.get(i)).getDefaultInstance();
                fake.set(DataComponents.CUSTOM_NAME, GearNames.text("elemental_" + PIECES.get(i)));
                p.setItemSlot(SLOTS.get(i), fake);
            }
            ElementalArmor.tick(p); c.assertTrue(ElementalArmor.fullSuit(p) == null, "Renamed vanilla armor cannot impersonate registered gear");
            c.assertFalse(p.hasEffect(MobEffects.STRENGTH), "A matching display name grants no powers");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void earthGuardRemovesOnlyItsOwnTransientModifier(GameTestHelper c) {
        var p = player(c, "earth-owned-bonus");
        var other = id("test_unrelated_knockback");
        try {
            var resistance = p.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            resistance.setBaseValue(.1);
            resistance.addPermanentModifier(new AttributeModifier(other, .15, AttributeModifier.Operation.ADD_VALUE));
            equip(p, ElementalArmor.Suit.ELEMENTAL); p.doTick();
            ElementalArmor.select(p, ElementalArmor.Element.EARTH);
            c.assertTrue(Math.abs(resistance.getValue() - .85) < .000001, "Earth adds exactly 60% to existing resistance");
            ElementalArmor.tick(p);
            c.assertTrue(Math.abs(resistance.getValue() - .85) < .000001, "Refresh never stacks duplicate Earth modifiers");
            ElementalArmor.select(p, ElementalArmor.Element.AIR);
            c.assertTrue(resistance.getModifier(ElementalArmor.EARTH_GUARD) == null, "Changing attunement immediately removes the owned Earth modifier");
            c.assertTrue(resistance.getModifier(other) != null, "Other systems retain their permanent modifiers");
            c.assertValueEqual(resistance.getBaseValue(), .1, "Changing attunement preserves the admin base");
            ElementalArmor.select(p, ElementalArmor.Element.EARTH);
            p.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY); ElementalArmor.tick(p);
            c.assertTrue(resistance.getModifier(ElementalArmor.EARTH_GUARD) == null, "Removing one piece immediately removes the transient guard");
            c.assertTrue(Math.abs(resistance.getValue() - .25) < .000001, "Removing armor leaves every unrelated source intact");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void spectatorTransitionAndGameLobbiesCannotActivatePowersOrChangeSelection(GameTestHelper c) {
        var p = player(c, "element-mode-guards");
        try {
            equip(p, ElementalArmor.Suit.ELEMENTAL);
            for (var mode : List.of(GameModes.Mode.SURVIVAL, GameModes.Mode.HARDCORE, GameModes.Mode.CREATIVE)) {
                GameModes.state(p).putString("active", mode.name());
                p.setGameMode(mode == GameModes.Mode.CREATIVE ? GameType.CREATIVE : GameType.SURVIVAL);
                c.assertValueEqual(ElementalArmor.select(p, ElementalArmor.Element.EARTH), 1, "Normal gameplay and Creative allow attunement");
            }
            p.removeAllEffects(); p.setGameMode(GameType.SPECTATOR); ElementalArmor.tick(p);
            c.assertFalse(p.hasEffect(MobEffects.RESISTANCE), "Spectators receive no suit powers");
            c.assertTrue(p.getAttribute(Attributes.KNOCKBACK_RESISTANCE).getModifier(ElementalArmor.EARTH_GUARD) == null, "Entering spectator removes only the owned guard");
            c.assertValueEqual(ElementalArmor.select(p, ElementalArmor.Element.WATER), 0, "A stale spectator selection is refused");
            p.setGameMode(GameType.SURVIVAL); GameModes.TRANSITIONS.add(p.getUUID());
            c.assertValueEqual(ElementalArmor.select(p, ElementalArmor.Element.WATER), 0, "A pending mode transition cannot change the selection");
            ElementalArmor.tick(p); c.assertFalse(p.hasEffect(MobEffects.RESISTANCE), "Transitioning players receive no suit powers");
            GameModes.TRANSITIONS.remove(p.getUUID());
            for (var mode : GameModes.Mode.values()) if (!List.of(GameModes.Mode.SURVIVAL, GameModes.Mode.HARDCORE, GameModes.Mode.CREATIVE).contains(mode)) {
                GameModes.state(p).putString("active", mode.name()); ElementalArmor.tick(p);
                c.assertFalse(p.hasEffect(MobEffects.RESISTANCE), "Lobby and minigame modes do not inherit armor abilities: " + mode);
                c.assertValueEqual(ElementalArmor.select(p, ElementalArmor.Element.WATER), 0, "Lobby and minigame choices are refused");
            }
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.EARTH, "Denied choices preserve the last explicit attunement");
        } finally { GameModes.TRANSITIONS.remove(p.getUUID()); cleanup(p); }
        c.succeed();
    }

    private static final class ExpiryScenario extends NativeSessionFixture {
        Joined owner;
        ExpiryScenario(GameTestHelper c) { super(c); }
        @Override void start() {
            owner = join("armor-expiry", 2);
            var p = owner.player;
            OperatorGameTests.deop(p); p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
            GameModes.state(p).putString("active", "SURVIVAL");
            p.getInventory().clearContent(); clearArmor(p); p.removeAllEffects();
            later(6, this::begin);
        }
        void begin() {
            var p = owner.player;
            c.assertTrue(ServerMenu.allowed(p), "The expiry fixture is a current native connected player");
            c.assertTrue(server.getConnection().getConnections().contains(owner.wire.connection), "Normal server connections drive the player's native effect ticks");
            equip(p, ElementalArmor.Suit.ELEMENTAL);
            p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 600, 6));
            p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600, 0));
            ElementalArmor.select(p, ElementalArmor.Element.WATER);
            ElementalArmor.select(p, ElementalArmor.Element.FIRE);
            c.assertValueEqual(p.getEffect(MobEffects.STRENGTH).getAmplifier(), 6, "A stronger potion keeps its amplifier");
            c.assertValueEqual(p.getEffect(MobEffects.STRENGTH).getDuration(), 600, "Applying armor does not shorten a stronger potion");
            c.assertValueEqual(p.getEffect(MobEffects.NIGHT_VISION).getDuration(), 300, "Night Vision has the documented non-flickering duration");
            clearArmor(p); ElementalArmor.tick(p);
            c.assertTrue(p.hasEffect(MobEffects.FIRE_RESISTANCE), "Removing armor never broadly clears active effects");
            int started = server.getTickCount();
            later(90, () -> {
                c.assertValueEqual(server.getTickCount() - started, 90, "Short expiry waits for ninety actual native server ticks");
                c.assertFalse(p.hasEffect(MobEffects.FIRE_RESISTANCE), "The Fire buff expires within the normal short duration");
                c.assertFalse(p.hasEffect(MobEffects.WATER_BREATHING), "The previous Water buff expires without an explicit clear");
                c.assertTrue(p.hasEffect(MobEffects.NIGHT_VISION), "Night Vision avoids abrupt flicker while fading");
                c.assertTrue(p.getEffect(MobEffects.STRENGTH).getDuration() < 600, "The real native player lifecycle has advanced potion durations");
            });
            later(320, () -> {
                c.assertValueEqual(server.getTickCount() - started, 320, "Night Vision expiry waits for actual server ticks");
                c.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION), "The remaining Night Vision naturally expires after removal");
                effect(c, p, MobEffects.STRENGTH, 6); effect(c, p, MobEffects.WEAKNESS, 0);
                succeed();
            });
        }
    }

    @GameTest(maxTicks = 1200) public void removedSuitBuffsExpireNaturallyAndNeverReplaceStrongerOrUnrelatedPotions(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c, () -> new ExpiryScenario(c));
    }

    @GameTest public void nativeConvergenceDeathTickClearsOnlyTheOwnedEarthGuard(GameTestHelper c) {
        var p = player(c, "earth-death-cleanup");
        var other = id("test_death_unrelated_knockback");
        try {
            var resistance = p.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
            resistance.setBaseValue(.1);
            resistance.addPermanentModifier(new AttributeModifier(other, .15, AttributeModifier.Operation.ADD_VALUE));
            equip(p, ElementalArmor.Suit.ELEMENTAL);
            c.assertValueEqual(ElementalArmor.select(p, ElementalArmor.Element.EARTH), 1, "A living full suit establishes its owned Earth guard");
            c.assertTrue(resistance.getModifier(ElementalArmor.EARTH_GUARD) != null, "The fixture begins with a real transient Earth modifier");
            p.setHealth(0); c.assertFalse(p.isAlive(), "The native player is genuinely dead before the server tick");
            Convergence.tick(p.level().getServer());
            c.assertTrue(resistance.getModifier(ElementalArmor.EARTH_GUARD) == null, "The actual dead-player branch clears the owned guard");
            c.assertTrue(resistance.getModifier(other) != null, "Death cleanup preserves unrelated modifiers");
            c.assertValueEqual(resistance.getBaseValue(), .1, "Death cleanup preserves the edited attribute base");
            c.assertValueEqual(ElementalArmor.selected(p), ElementalArmor.Element.EARTH, "Death cleanup preserves the wearer's saved attunement");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void allEightArmorRecipesUseTheActualNativeCraftingManager(GameTestHelper c) {
        var patterns = List.of(List.of("DDD", "DCD"), List.of("D D", "DCD", "DDD"), List.of("DDD", "DCD", "D D"), List.of("D D", "DCD"));
        for (var suit : ElementalArmor.Suit.values()) for (int i = 0; i < PIECES.size(); i++) {
            var stacks = new ArrayList<ItemStack>();
            var material = suit == ElementalArmor.Suit.ELEMENTAL ? Items.DIAMOND : Items.NETHERITE_INGOT;
            var core = suit == ElementalArmor.Suit.ELEMENTAL ? Items.AMETHYST_SHARD : Items.NETHER_STAR;
            for (var row : patterns.get(i)) for (char key : row.toCharArray())
                stacks.add(key == 'D' ? new ItemStack(material) : key == 'C' ? new ItemStack(core) : ItemStack.EMPTY);
            var input = CraftingInput.of(3, patterns.get(i).size(), stacks);
            var recipe = c.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, c.getLevel()).orElseThrow();
            String path = suit.prefix() + "_" + PIECES.get(i);
            c.assertValueEqual(recipe.id().identifier(), id(path), "The actual native manager selects the intended recipe");
            c.assertTrue(ItemStack.isSameItemSameComponents(recipe.value().assemble(input), gear(path)), "Crafting produces the exact registered item and all equipment components");
            for (int j = 0; j < stacks.size(); j++) if (stacks.get(j).is(core)) stacks.set(j, ItemStack.EMPTY);
            var withoutCore = c.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, patterns.get(i).size(), stacks), c.getLevel());
            c.assertFalse(withoutCore.isPresent() && withoutCore.orElseThrow().id().identifier().equals(id(path)), "Omitting the required attunement core never produces custom armor");
        }
        c.succeed();
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("convergence", path); }
    private static ItemStack gear(String path) { return new ItemStack(Convergence.ITEMS.get("convergence:" + path)); }
    private static void equip(ServerPlayer p, ElementalArmor.Suit suit) {
        var kit = ElementalArmor.kit(suit);
        for (int i = 0; i < SLOTS.size(); i++) p.setItemSlot(SLOTS.get(i), kit.get(i));
    }
    private static void clearArmor(ServerPlayer p) { for (var slot : SLOTS) p.setItemSlot(slot, ItemStack.EMPTY); }
    private static ServerPlayer player(GameTestHelper c, String name) {
        // Connected native sessions encode every online profile, including these synchronous fixtures.
        var p = new ModeGameTests().player(c, name.substring(0, Math.min(16, name.length())));
        p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
        GameModes.state(p).putString("active", "SURVIVAL");
        p.getInventory().clearContent(); clearArmor(p); p.removeAllEffects();
        return p;
    }
    private static void effect(GameTestHelper c, ServerPlayer p, Holder<MobEffect> effect, int amplifier) {
        c.assertTrue(p.hasEffect(effect), "A complete suit applies " + effect);
        c.assertValueEqual(p.getEffect(effect).getAmplifier(), amplifier, "The suit supplies the intended level for " + effect);
    }
    private static void cleanup(ServerPlayer p) { p.closeContainer(); p.level().getServer().getPlayerList().remove(p); }
}

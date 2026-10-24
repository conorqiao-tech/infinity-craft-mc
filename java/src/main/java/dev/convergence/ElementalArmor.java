package dev.convergence;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;

/** Two independent solid suits; Elemental attunement is saved with the wearer, not the item. */
public final class ElementalArmor {
    private static final String STATE = "elemental_armor";
    private static final String SELECTION = "element";
    private static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    static final Identifier EARTH_GUARD = Identifier.fromNamespaceAndPath("convergence", "elemental_earth_guard");

    public enum Element {
        FIRE("Fire", "Fire Resistance I and Strength II"),
        WATER("Water", "Water Breathing I, Dolphin's Grace I and Night Vision I"),
        EARTH("Earth", "Resistance II, Haste II and 60% knockback resistance"),
        AIR("Air", "Speed II, Jump Boost II and Slow Falling I");

        private final String displayName;
        private final String powerDescription;

        Element(String displayName, String powerDescription) {
            this.displayName = displayName;
            this.powerDescription = powerDescription;
        }

        public String displayName() { return displayName; }
        public String powerDescription() { return powerDescription; }
    }

    public enum Suit {
        ELEMENTAL("elemental", "Elemental Armor", "Choose Fire, Water, Earth or Air; wear all four matching pieces to activate its powers", 2),
        INFINITY("infinity", "Infinity Armor", "Fire Resistance I, Water Breathing I, Night Vision I, Speed III, Haste V, Strength V, Resistance IV and Regeneration III", 4);

        private final String prefix;
        private final String displayName;
        private final String powerDescription;
        private final double toughness;

        Suit(String prefix, String displayName, String powerDescription, double toughness) {
            this.prefix = prefix;
            this.displayName = displayName;
            this.powerDescription = powerDescription;
            this.toughness = toughness;
        }

        public String prefix() { return prefix; }
        public String displayName() { return displayName; }
        public String powerDescription() { return powerDescription; }
        public double toughness() { return toughness; }
        public int protection(EquipmentSlot slot) {
            return switch (slot) {
                case HEAD, FEET -> this == INFINITY ? 8 : 3;
                case CHEST -> this == INFINITY ? 14 : 8;
                case LEGS -> this == INFINITY ? 10 : 6;
                default -> 0;
            };
        }
    }

    private ElementalArmor() {}

    /** Only the eight registered armor paths, optionally prefixed with convergence:, are accepted. */
    public static Suit ofPath(String path) {
        String local = localPath(path);
        if (local == null) return null;
        for (Suit suit : Suit.values())
            for (String piece : PIECES)
                if (local.equals(suit.prefix + "_" + piece)) return suit;
        return null;
    }

    private static String localPath(String path) {
        if (path == null) return null;
        if (path.startsWith("convergence:")) return path.substring("convergence:".length());
        return path.indexOf(':') < 0 ? path : null;
    }

    private static EquipmentSlot slot(String path) {
        String local = localPath(path);
        if (ofPath(path) == null) return null;
        for (int i = 0; i < PIECES.size(); i++)
            if (local.endsWith("_" + PIECES.get(i))) return SLOTS.get(i);
        return null;
    }

    public static int protection(String path) {
        Suit suit = ofPath(path);
        return suit == null ? 0 : suit.protection(slot(path));
    }

    /** Native base components supply durability, enchanting and all other normal equipment behavior. */
    public static Item baseItem(String path) {
        Suit suit = ofPath(path);
        if (suit == null) return null;
        return switch (slot(path)) {
            case HEAD -> suit == Suit.INFINITY ? Items.NETHERITE_HELMET : Items.DIAMOND_HELMET;
            case CHEST -> suit == Suit.INFINITY ? Items.NETHERITE_CHESTPLATE : Items.DIAMOND_CHESTPLATE;
            case LEGS -> suit == Suit.INFINITY ? Items.NETHERITE_LEGGINGS : Items.DIAMOND_LEGGINGS;
            case FEET -> suit == Suit.INFINITY ? Items.NETHERITE_BOOTS : Items.DIAMOND_BOOTS;
            default -> null;
        };
    }

    private static EquipmentSlotGroup group(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlotGroup.HEAD;
            case CHEST -> EquipmentSlotGroup.CHEST;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case FEET -> EquipmentSlotGroup.FEET;
            default -> throw new IllegalArgumentException("Not an armor slot: " + slot);
        };
    }

    public static void register() {
        for (Suit suit : Suit.values()) for (String piece : PIECES) {
            String path = suit.prefix + "_" + piece;
            Identifier id = Identifier.fromNamespaceAndPath("convergence", path);
            Item base = baseItem(path);
            EquipmentSlot slot = slot(path);
            Item.Properties properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
                .component(DataComponents.ITEM_NAME, GearNames.text(path));
            BaseComponents.then(properties, base, (builder, defaults) -> {
                for (TypedDataComponent<?> component : defaults)
                    if (component.type() != DataComponents.ITEM_NAME && component.type() != DataComponents.ITEM_MODEL)
                        BaseComponents.copy(builder, component);
                var attributes = ItemAttributeModifiers.builder();
                var original = defaults.get(DataComponents.ATTRIBUTE_MODIFIERS);
                for (var entry : original.modifiers()) {
                    AttributeModifier modifier = entry.modifier();
                    if (entry.attribute().equals(Attributes.ARMOR))
                        modifier = new AttributeModifier(modifier.id(), suit.protection(slot), AttributeModifier.Operation.ADD_VALUE);
                    else if (entry.attribute().equals(Attributes.ARMOR_TOUGHNESS))
                        modifier = new AttributeModifier(modifier.id(), suit.toughness, AttributeModifier.Operation.ADD_VALUE);
                    attributes.add(entry.attribute(), modifier, group(slot), entry.display());
                }
                builder.set(DataComponents.ATTRIBUTE_MODIFIERS, attributes.build());
                ResourceKey<EquipmentAsset> asset = ResourceKey.create(
                    ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("equipment_asset")),
                    Identifier.fromNamespaceAndPath("convergence", suit.prefix + "_armor"));
                var equippable = Equippable.builder(slot).setAsset(asset).setDamageOnHurt(suit == Suit.ELEMENTAL);
                defaults.get(DataComponents.EQUIPPABLE).equipSound()
                    .ifPresentOrElse(equippable::setEquipSound, equippable::noEquidSound);
                builder.set(DataComponents.EQUIPPABLE, equippable.build());
                if (suit == Suit.INFINITY) builder.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
                // Keep this saved item and its complete armor components. Flight
                // is an additional default, so existing unmodified stacks gain it
                // when native item loading resolves the current item prototype.
                if (suit == Suit.INFINITY && slot == EquipmentSlot.CHEST)
                    builder.set(DataComponents.GLIDER, Unit.INSTANCE);
            });
            Item item = Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
            Convergence.ITEMS.put(id.toString(), item);
        }
    }

    public static List<ItemStack> kit(Suit suit) {
        return PIECES.stream().map(piece -> new ItemStack(Convergence.ITEMS.get("convergence:" + suit.prefix + "_" + piece))).toList();
    }

    /** Preview is deliberately read-only: missing or malformed saves simply display Fire. */
    public static Element selected(ServerPlayer player) {
        String value = GameModes.state(player).getCompoundOrEmpty(STATE).getStringOr(SELECTION, "fire");
        try { return Element.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return Element.FIRE; }
    }

    public static int select(ServerPlayer player, Element element) {
        if (element == null || !BackpackStorage.storageAllowed(player)) return 0;
        var state = GameModes.state(player).getCompoundOrEmpty(STATE).copy();
        state.putString(SELECTION, element.name().toLowerCase(Locale.ROOT));
        GameModes.state(player).put(STATE, state);
        PlayerPersistence.save(player);
        tick(player);
        return 1;
    }

    public static Suit fullSuit(ServerPlayer player) {
        for (Suit suit : Suit.values()) {
            boolean full = true;
            for (int i = 0; i < PIECES.size(); i++)
                if (!player.getItemBySlot(SLOTS.get(i)).is(Convergence.ITEMS.get("convergence:" + suit.prefix + "_" + PIECES.get(i)))) {
                    full = false;
                    break;
                }
            if (full) return suit;
        }
        return null;
    }

    /** Powers fade naturally within 80 ticks (Night Vision: 300); never clear someone else's effects. */
    public static void tick(ServerPlayer player) {
        Suit suit = BackpackStorage.storageAllowed(player) ? fullSuit(player) : null;
        Element element = selected(player);
        var knockback = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        boolean earth = suit == Suit.ELEMENTAL && element == Element.EARTH;
        if (knockback != null) {
            if (earth && knockback.getModifier(EARTH_GUARD) == null)
                knockback.addTransientModifier(new AttributeModifier(EARTH_GUARD, .6, AttributeModifier.Operation.ADD_VALUE));
            else if (!earth) knockback.removeModifier(EARTH_GUARD);
        }
        if (suit == null) return;
        if (suit == Suit.INFINITY) {
            Convergence.effect(player, MobEffects.FIRE_RESISTANCE, 0, 80);
            Convergence.effect(player, MobEffects.WATER_BREATHING, 0, 80);
            Convergence.effect(player, MobEffects.NIGHT_VISION, 0, 300);
            Convergence.effect(player, MobEffects.SPEED, 2, 80);
            Convergence.effect(player, MobEffects.HASTE, 4, 80);
            Convergence.effect(player, MobEffects.STRENGTH, 4, 80);
            Convergence.effect(player, MobEffects.RESISTANCE, 3, 80);
            Convergence.effect(player, MobEffects.REGENERATION, 2, 80);
            return;
        }
        switch (element) {
            case FIRE -> {
                Convergence.effect(player, MobEffects.FIRE_RESISTANCE, 0, 80);
                Convergence.effect(player, MobEffects.STRENGTH, 1, 80);
            }
            case WATER -> {
                Convergence.effect(player, MobEffects.WATER_BREATHING, 0, 80);
                Convergence.effect(player, MobEffects.DOLPHINS_GRACE, 0, 80);
                Convergence.effect(player, MobEffects.NIGHT_VISION, 0, 300);
            }
            case EARTH -> {
                Convergence.effect(player, MobEffects.RESISTANCE, 1, 80);
                Convergence.effect(player, MobEffects.HASTE, 1, 80);
            }
            case AIR -> {
                Convergence.effect(player, MobEffects.SPEED, 1, 80);
                Convergence.effect(player, MobEffects.JUMP_BOOST, 1, 80);
                Convergence.effect(player, MobEffects.SLOW_FALLING, 0, 80);
            }
        }
    }
}

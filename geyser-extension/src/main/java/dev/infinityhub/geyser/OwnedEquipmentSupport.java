package dev.infinityhub.geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.item.GeyserCustomMappingData;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.geysermc.geyser.text.GeyserLocale;

/** Pinned, owner-only component adapter. Vanilla bases, item types and arrow IDs are retained.
 * Geyser's v2 chargeable generator omits Creative ammunition and crossbow rockets. Only
 * the four Infinity model records and their login component definitions may be replaced.
 */
final class OwnedEquipmentSupport {
    static final Map<String, String> BASES = Map.of("bow", "minecraft:bow", "crossbow", "minecraft:crossbow",
            "shield", "minecraft:shield", "totem", "minecraft:totem_of_undying");
    static final Set<String> NATIVE_ARROWS = Set.of("infinity_arrow", "void_arrow", "starfire_arrow");
    static final Set<Integer> PROTOCOLS = Set.of(1001, 2168, 2193);
    final Map<Integer, ProtocolState> protocols;
    final Map<String, Catalogue.Entry> entries;
    private int componentPacketRoundtrips;
    private int baselineComponentPacketRoundtrips;
    private int ownedModelCases;

    private OwnedEquipmentSupport(Map<Integer, ProtocolState> protocols, Map<String, Catalogue.Entry> entries) {
        this.protocols = Map.copyOf(protocols); this.entries = Map.copyOf(entries);
    }

    static OwnedEquipmentSupport install(Catalogue catalogue) throws Exception {
        Path jar = Path.of(GeyserImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isRegularFile(jar) && InfinityCreativeBridge.GEYSER_SHA256.equals(sha256(jar)),
                "Owned equipment requires the exact pinned Geyser artifact");
        require(Registries.ITEMS.get().keySet().equals(PROTOCOLS), "Unexpected owned equipment protocol registry");
        Map<String, Catalogue.Entry> entries = new LinkedHashMap<>(); Set<Integer> aliasIds = new HashSet<>();
        for (Catalogue.Entry entry : catalogue.items) {
            Item alias = Registries.JAVA_ITEM_IDENTIFIERS.get().get(entry.alias());
            require(alias != null, "Missing declared catalogue alias: " + entry.path()); aliasIds.add(alias.javaId());
            if (BASES.containsKey(entry.path())) {
                require(entry.base().equals(BASES.get(entry.path())) && !entry.nativeAppearance() && entry.maxStack() == 1,
                        "Owned held equipment must export its canonical model: " + entry.path());
                require(entries.put(entry.path(), entry) == null, "Duplicate owned equipment path");
            }
            if (NATIVE_ARROWS.contains(entry.path())) require(entry.nativeAppearance() && entry.base().equals("minecraft:arrow"),
                    "Infinity arrows must preserve the native Bedrock ammo ID: " + entry.path());
        }
        require(entries.keySet().equals(BASES.keySet()) && catalogue.items.stream().filter(e -> NATIVE_ARROWS.contains(e.path())).count() == 3,
                "Incomplete owned held equipment or native arrow catalogue");
        Map<Integer, ProtocolState> prepared = new LinkedHashMap<>();
        for (int protocol : PROTOCOLS.stream().sorted().toList()) {
            ItemMappings mappings = Registries.ITEMS.get(protocol);
            ProtocolState state = new ProtocolState(protocol, mappings, aliasIds);
            for (Catalogue.Entry entry : entries.values()) {
                ItemMapping base = mappings.getMapping(entry.base());
                require(base != ItemMapping.AIR && base.getJavaItem() == Registries.JAVA_ITEM_IDENTIFIERS.get().get(entry.base()),
                        "Canonical Java base is inconsistent: " + entry.path());
                require(base.getCustomItemDefinitions() != null, "Missing canonical custom mapping: " + entry.path());
                Key model = Key.key(entry.canonical()); var records = base.getCustomItemDefinitions().get(model);
                require(records.size() == 1, "Canonical model mapping is not unique: " + entry.path());
                GeyserCustomMappingData old = records.iterator().next();
                require(identifier(old.definition().bedrockIdentifier()).equals(entry.canonical()), "Unowned model record");
                require(old.itemDefinition().getClass() == SimpleItemDefinition.class, "Unsupported custom definition implementation");
                SimpleItemDefinition definition = (SimpleItemDefinition) old.itemDefinition();
                require(definition.getIdentifier().equals(entry.canonical()) && definition.getVersion() == ItemVersion.DATA_DRIVEN
                        && definition.isComponentBased() && mappings.getItemDefinitions().get(definition.getRuntimeId()) == definition,
                        "Owned runtime definition is inconsistent: " + entry.path());
                require(old.definition().displayName().equals(nativeDisplayName(entry)), "Pinned owned mapping display-name conversion changed: " + entry.path());
                validateDefinition(entry, definition, false);
                require(mappings.getCreativeItems().stream().noneMatch(c -> c.getItem().getDefinition().getRuntimeId() == definition.getRuntimeId()),
                        "Canonical definitions cannot replace public Creative alias entries");
                // An existing addon using the same record elsewhere is outside this adapter's ownership.
                long references = state.customRecords.stream().filter(r -> r.record() == old || r.record().itemDefinition() == definition).count();
                require(references == 1, "Owned definition is shared with another custom mapping");
                // The pinned JSON reader adds a leading Bedrock formatting reset to
                // display_name. Validate that native output first, then publish the
                // trusted catalogue literal in just this owner's item component.
                NbtMap nbt = withLiteralName(definition.getComponentData(), entry.name());
                if (entry.path().equals("bow") || entry.path().equals("crossbow")) nbt = withAmmunition(nbt, entry.path().equals("crossbow"));
                SimpleItemDefinition replacement = new SimpleItemDefinition(definition.getIdentifier(), definition.getRuntimeId(),
                        definition.getVersion(), definition.isComponentBased(), nbt);
                validateDefinition(entry, replacement, true);
                GeyserCustomMappingData next = new GeyserCustomMappingData(old.definition(), replacement, old.integerId());
                state.changes.add(new Change(entry, base, model, old, next));
            }
            prepared.put(protocol, state);
        }
        // Every item and protocol is prepared before the first registry mutation. Restore all
        // owned entries if either a comparator rejects a record or a mandatory wire check fails.
        OwnedEquipmentSupport support = new OwnedEquipmentSupport(prepared, entries);
        try {
            for (ProtocolState state : prepared.values()) for (Change change : state.changes) {
                require(change.mapping().getCustomItemDefinitions().remove(change.model(), change.previous()), "Owned record disappeared before commit");
                require(change.mapping().getCustomItemDefinitions().put(change.model(), change.replacement()), "Owned record comparator rejected replacement");
                state.mappings.getItemDefinitions().put(change.previous().itemDefinition().getRuntimeId(), change.replacement().itemDefinition());
            }
            support.verifyUnowned();
            int[] checks = OwnedEquipmentProof.verifyDefinitions(catalogue, support);
            support.componentPacketRoundtrips = checks[0]; support.ownedModelCases = checks[1];
            support.baselineComponentPacketRoundtrips = checks[2];
            require(checks[0] == 3 && checks[1] == 12 && checks[2] == 3, "Incomplete mandatory owned component/model verification");
            return support;
        } catch (Exception error) {
            for (ProtocolState state : prepared.values()) for (Change change : state.changes) {
                change.mapping().getCustomItemDefinitions().remove(change.model(), change.replacement());
                change.mapping().getCustomItemDefinitions().put(change.model(), change.previous());
                state.mappings.getItemDefinitions().put(change.previous().itemDefinition().getRuntimeId(), change.previous().itemDefinition());
            }
            throw error;
        }
    }

    void verifyUnowned() {
        require(Registries.ITEMS.get().keySet().equals(PROTOCOLS), "Protocol registry changed after owned equipment install");
        for (ProtocolState state : protocols.values()) {
            require(Registries.ITEMS.get(state.protocol) == state.mappings, "Protocol mappings replaced");
            Set<Integer> owned = new HashSet<>();
            for (Change change : state.changes) {
                int runtime = change.previous().itemDefinition().getRuntimeId(); owned.add(runtime);
                require(state.mappings.getItemDefinitions().get(runtime) == change.replacement().itemDefinition()
                        && state.mappings.getMapping(change.entry().base()) == change.mapping()
                        && change.mapping().getCustomItemDefinitions().get(change.model()).size() == 1
                        && change.mapping().getCustomItemDefinitions().get(change.model()).iterator().next() == change.replacement(),
                        "Owned mapping lost its exact component definition");
                validateDefinition(change.entry(), (SimpleItemDefinition) change.replacement().itemDefinition(), true);
                NbtMap expected = withLiteralName(((SimpleItemDefinition) change.previous().itemDefinition()).getComponentData(), change.entry().name());
                if (change.entry().path().equals("bow") || change.entry().path().equals("crossbow"))
                    expected = withAmmunition(expected, change.entry().path().equals("crossbow"));
                require(expected.equals(((SimpleItemDefinition) change.replacement().itemDefinition()).getComponentData()), "Unrelated owned components changed");
            }
            require(state.mappings.getItemDefinitions().keySet().equals(state.definitions.keySet()), "Definition registry IDs changed");
            for (var original : state.definitions.entrySet()) if (!owned.contains(original.getKey()))
                require(state.mappings.getItemDefinitions().get(original.getKey().intValue()) == original.getValue(), "Unowned runtime definition changed");
            ItemMapping[] current = state.mappings.getItems();
            require(current.length == state.primary.length && Registries.JAVA_ITEMS.get().size() == state.javaItems.size(), "Primary registry size changed");
            for (int i = 0; i < current.length; i++) if (!state.aliasIds.contains(i)) require(current[i] == state.primary[i], "Unowned primary mapping changed");
            for (int i = 0; i < state.javaItems.size(); i++) if (!state.aliasIds.contains(i)) require(Registries.JAVA_ITEMS.get().get(i) == state.javaItems.get(i), "Unowned Java item changed");
            require(state.mappings.getCreativeItems().equals(state.creativeItems), "Creative catalogue definitions changed");
            int expectedSize = state.customRecords.size(); int actualSize = 0;
            for (ItemMapping mapping : current) if (mapping != null && mapping.getCustomItemDefinitions() != null) actualSize += mapping.getCustomItemDefinitions().size();
            require(actualSize == expectedSize, "Custom mapping registry size changed");
            for (CustomRecord original : state.customRecords) {
                if (state.changes.stream().anyMatch(c -> c.previous() == original.record())) continue;
                require(original.mapping().getCustomItemDefinitions().get(original.model()).stream().anyMatch(r -> r == original.record()), "Unowned custom model record changed");
            }
            // Merely giving new arrow definitions a PROJECTILE field does not establish that
            // ordinary Bedrock bows accept their IDs. The native fallback is intentionally kept.
            for (String path : NATIVE_ARROWS) for (ItemMapping mapping : current) if (mapping != null && mapping.getCustomItemDefinitions() != null)
                require(mapping.getCustomItemDefinitions().get(Key.key("convergence:" + path)).isEmpty(), "Unsupported custom arrow definition registered");
        }
    }

    JsonObject receipt() {
        verifyUnowned(); JsonObject result = new JsonObject(); JsonArray versions = new JsonArray(); PROTOCOLS.stream().sorted().forEach(versions::add);
        result.addProperty("items", 4); result.add("protocols", versions);
        result.addProperty("custom_mapping_records", 12); result.addProperty("owned_login_definitions", 12);
        result.addProperty("unowned_definitions_and_primary_mappings_unchanged", true);
        result.addProperty("native_arrow_fallbacks_preserved", true); result.addProperty("ordinary_vanilla_components_unchanged", true);
        result.addProperty("mandatory_component_packet_roundtrips", componentPacketRoundtrips);
        result.addProperty("mandatory_untouched_baseline_component_packet_roundtrips", baselineComponentPacketRoundtrips);
        result.addProperty("complete_unowned_wire_components_match_native_baseline", true);
        result.addProperty("mandatory_owned_model_cases", ownedModelCases);
        result.addProperty("owned_default_display_names_are_catalogue_literals", true);
        result.addProperty("published_packet", "Native Geyser login ItemComponentPacket registry; no vanilla item definitions replaced");
        result.addProperty("device_rendering_verified", false); return result;
    }

    private static NbtMap withAmmunition(NbtMap nbt, boolean crossbow) {
        NbtMap components = nbt.getCompound("components"), shooter = components.getCompound("minecraft:shooter");
        List<NbtMap> oldAmmo = shooter.getList("ammunition", NbtType.COMPOUND);
        require(oldAmmo.size() == 1 && oldAmmo.getFirst().getCompound("item").getString("name").equals("minecraft:arrow")
                && !oldAmmo.getFirst().getBoolean("use_in_creative") && oldAmmo.getFirst().getBoolean("use_offhand")
                && oldAmmo.getFirst().getBoolean("search_inventory"), "Pinned native shooter ammunition shape changed");
        List<NbtMap> ammo = new ArrayList<>(); ammo.add(oldAmmo.getFirst().toBuilder().putBoolean("use_in_creative", true).build());
        if (crossbow) ammo.add(NbtMap.builder().putCompound("item", NbtMap.builder().putString("name", "minecraft:firework_rocket").build())
                .putBoolean("use_offhand", true).putBoolean("use_in_creative", false).putBoolean("search_inventory", false).build());
        NbtMap replacement = shooter.toBuilder().putList("ammunition", NbtType.COMPOUND, ammo).build();
        return nbt.toBuilder().putCompound("components", components.toBuilder().putCompound("minecraft:shooter", replacement).build()).build();
    }

    private static NbtMap withLiteralName(NbtMap nbt, String name) {
        NbtMap components = nbt.getCompound("components"), displayName = components.getCompound("minecraft:display_name");
        return nbt.toBuilder().putCompound("components", components.toBuilder().putCompound("minecraft:display_name",
                displayName.toBuilder().putString("value", name).build()).build()).build();
    }

    private static String nativeDisplayName(Catalogue.Entry entry) {
        String nativeName = MessageTranslator.convertMessage(Component.text(entry.name()), GeyserLocale.getDefaultLocale());
        require(nativeName.equals("\u00a7r" + entry.name()), "Pinned literal display-name serializer shape changed");
        return nativeName;
    }

    static void validateDefinition(Catalogue.Entry entry, SimpleItemDefinition definition, boolean adapted) {
        NbtMap nbt = definition.getComponentData();
        require(nbt != null && nbt.getString("name").equals(entry.canonical()) && nbt.getInt("id") == definition.getRuntimeId(), "Owned component root mismatch: " + entry.path());
        NbtMap components = nbt.getCompound("components"), properties = components.getCompound("item_properties");
        require(properties.getCompound("minecraft:icon").getCompound("textures").getString("default").equals(entry.icon()), "Owned original icon mismatch: " + entry.path());
        String expectedName = adapted ? entry.name() : nativeDisplayName(entry);
        require(components.getCompound("minecraft:display_name").getString("value").equals(expectedName), "Owned literal name mismatch: " + entry.path());
        require(properties.getBoolean("allow_off_hand"), "Owned equipment offhand option missing");
        if (entry.path().equals("bow") || entry.path().equals("crossbow")) {
            boolean crossbow = entry.path().equals("crossbow"); NbtMap shooter = components.getCompound("minecraft:shooter");
            require(components.containsKey("minecraft:use_modifiers") && shooter.getBoolean("charge_on_draw") == crossbow
                    && shooter.getBoolean("scale_power_by_draw_duration") && properties.getInt("frame_count") == (crossbow ? 10 : 3), "Owned native shooter components missing");
            if (adapted) {
                List<NbtMap> ammo = shooter.getList("ammunition", NbtType.COMPOUND);
                require(ammo.size() == (crossbow ? 2 : 1), "Unexpected owned ammunition count");
                NbtMap arrow = ammo.getFirst();
                require(arrow.getCompound("item").getString("name").equals("minecraft:arrow") && arrow.getBoolean("use_in_creative")
                        && arrow.getBoolean("use_offhand") && arrow.getBoolean("search_inventory"), "Owned Creative arrow component missing");
                if (crossbow) {
                    NbtMap rocket = ammo.get(1);
                    require(rocket.getCompound("item").getString("name").equals("minecraft:firework_rocket") && !rocket.getBoolean("use_in_creative")
                            && rocket.getBoolean("use_offhand") && !rocket.getBoolean("search_inventory"), "Owned crossbow offhand rocket component missing");
                }
            }
        }
    }

    private static String identifier(org.geysermc.geyser.api.util.Identifier id) { return id.namespace() + ":" + id.path(); }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) { byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void require(boolean pass, String message) { if (!pass) throw new IllegalStateException(message); }
    record Change(Catalogue.Entry entry, ItemMapping mapping, Key model, GeyserCustomMappingData previous, GeyserCustomMappingData replacement) { }
    private record CustomRecord(ItemMapping mapping, Key model, GeyserCustomMappingData record) { }
    static final class ProtocolState {
        final int protocol; final ItemMappings mappings; final Set<Integer> aliasIds; final ItemMapping[] primary;
        final List<Item> javaItems; final Map<Integer, ItemDefinition> definitions = new HashMap<>();
        final List<org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData> creativeItems;
        final List<CustomRecord> customRecords = new ArrayList<>(); final List<Change> changes = new ArrayList<>();
        ProtocolState(int protocol, ItemMappings mappings, Set<Integer> aliasIds) {
            this.protocol = protocol; this.mappings = mappings; this.aliasIds = Set.copyOf(aliasIds);
            // Non-vanilla alias IDs deliberately leave null gaps in the Java registry.
            primary = mappings.getItems().clone(); javaItems = new ArrayList<>(Registries.JAVA_ITEMS.get());
            creativeItems = List.copyOf(mappings.getCreativeItems());
            mappings.getItemDefinitions().int2ObjectEntrySet().forEach(e -> definitions.put(e.getIntKey(), e.getValue()));
            for (ItemMapping mapping : primary) if (mapping != null && mapping.getCustomItemDefinitions() != null)
                mapping.getCustomItemDefinitions().entries().forEach(e -> customRecords.add(new CustomRecord(mapping, e.getKey(), e.getValue())));
        }
        Change change(String path) { return changes.stream().filter(c -> c.entry().path().equals(path)).findFirst().orElseThrow(); }
    }
}

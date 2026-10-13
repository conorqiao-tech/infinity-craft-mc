package dev.infinityhub.geyser;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.NonVanillaCustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaItemDataComponents;
import org.geysermc.geyser.api.util.CreativeCategory;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.item.components.resolvable.ResolvableComponentGetter;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.mappings.components.DataComponentReaders;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.registry.type.ItemMappings;

/** A deliberately pinned compatibility adapter, not a global vanilla item translator replacement. */
public final class InfinityCreativeBridge implements Extension {
    public static final String GEYSER_SHA256 = "17a9e00f3be106c797066664679ca73ebf25af693a750f378a9669ca93b4c07e";
    private Catalogue catalogue;
    private Exception failure;
    private final Map<String, Integer> aliasIds = new LinkedHashMap<>();

    @Subscribe
    public void prepare(GeyserPreInitializeEvent event) {
        try {
            Files.createDirectories(dataFolder());
            Files.deleteIfExists(dataFolder().resolve("ready.json"));
            Files.deleteIfExists(dataFolder().resolve("failed.json"));
            Path code = Path.of(GeyserImpl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(code) || !GEYSER_SHA256.equals(sha256(code))) throw new IllegalStateException("Unsupported Geyser artifact; Infinity Creative bridge requires the pinned build");
            catalogue = Catalogue.read(GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("infinity-catalogue.json"));
        } catch (Exception error) { fail(error); }
    }

    @Subscribe
    public void define(GeyserDefineCustomItemsEvent event) {
        if (failure != null || catalogue == null) return;
        try {
            Set<String> bedrockIds = new HashSet<>(); Set<String> javaIds = new HashSet<>(); Set<Integer> numbers = new HashSet<>();
            event.customItemDefinitions().values().forEach(definitions -> definitions.forEach(d -> bedrockIds.add(identifier(d.bedrockIdentifier()))));
            event.nonVanillaCustomItemDefinitions().values().forEach(definitions -> definitions.forEach(d -> {
                bedrockIds.add(identifier(d.bedrockIdentifier())); javaIds.add(identifier(d.identifier())); numbers.add(d.javaId());
            }));
            int next = Math.max(8192, Registries.JAVA_ITEMS.get().size());
            if (!numbers.isEmpty()) next = Math.max(next, numbers.stream().mapToInt(Integer::intValue).max().orElseThrow() + 1);
            if (next < 0 || next + catalogue.items.size() > 32768) throw new IllegalStateException("Custom item alias range is not available");
            List<NonVanillaCustomItemDefinition> definitions = new ArrayList<>();
            for (Catalogue.Entry entry : catalogue.items) {
                if (bedrockIds.contains(entry.alias()) || javaIds.contains(entry.alias()))
                    throw new IllegalStateException("Creative alias collision: " + entry.alias());
                // Vanilla Item objects are not populated yet during this registration event.
                // Validate every real base against all initialized protocol mappings below.
                int javaId = next++;
                Identifier javaAlias = Identifier.of(entry.alias());
                Identifier bedrockAlias = Identifier.of(entry.alias());
                NonVanillaCustomItemDefinition.Builder builder = NonVanillaCustomItemDefinition.builder(javaAlias, bedrockAlias, javaId);
                builder.displayName(entry.name());
                builder.component(JavaItemDataComponents.MAX_STACK_SIZE, entry.maxStack());
                CustomItemBedrockOptions.Builder options = CustomItemBedrockOptions.builder();
                options.icon(entry.icon()); options.allowOffhand(true); options.displayHandheld(entry.handheld());
                options.protectionValue(entry.protection()); options.creativeCategory(CreativeCategory.fromName(entry.category()));
                builder.bedrockOptions(options);
                if (entry.maxDamage() != null) builder.component(JavaItemDataComponents.MAX_DAMAGE, entry.maxDamage());
                // Geyser's 26.2 equippable schema has armor slots, not Java 26.3 hand slots.
                // Native hand behavior comes from the canonical real item after selection.
                if (entry.slot() != null && !entry.slot().equals("offhand") && !entry.slot().equals("mainhand")) {
                    JsonObject equipment = new JsonObject(); equipment.addProperty("slot", entry.slot());
                    DataComponentReaders.readDataComponent(builder, "minecraft:equippable", equipment, "Infinity Creative catalogue");
                }
                definitions.add(builder.build()); aliasIds.put(entry.path(), javaId);
            }
            // All local validation precedes registration. No vanilla definition is replaced.
            for (NonVanillaCustomItemDefinition definition : definitions) event.register(definition);
        } catch (Exception error) { fail(error); }
    }

    @Subscribe
    public void finish(GeyserPostInitializeEvent event) {
        try {
            if (failure != null) throw failure;
            if (catalogue == null || aliasIds.size() != catalogue.items.size()) throw new IllegalStateException("Creative catalogue registration did not run");
            Map<Integer, CatalogueItem> replacements = new HashMap<>();
            Map<Integer, List<ItemMapping>> prepared = new LinkedHashMap<>();
            Map<Integer, ItemMapping[]> originalMappings = new HashMap<>();
            List<Item> originalJavaItems = new ArrayList<>(Registries.JAVA_ITEMS.get());
            if (Registries.ITEMS.get().isEmpty()) throw new IllegalStateException("No Bedrock protocol mappings were initialized");
            for (Int2ObjectMap.Entry<ItemMappings> protocol : Registries.ITEMS.get().int2ObjectEntrySet()) {
                ItemMappings mappings = protocol.getValue(); List<ItemMapping> aliases = new ArrayList<>();
                originalMappings.put(protocol.getIntKey(), mappings.getItems().clone());
                for (Catalogue.Entry entry : catalogue.items) {
                    int id = aliasIds.get(entry.path());
                    ItemMapping old = mappings.getMapping(id);
                    ItemMapping base = mappings.getMapping(entry.base());
                    Item registeredAlias = Registries.JAVA_ITEM_IDENTIFIERS.get().get(entry.alias());
                    Item registeredBase = Registries.JAVA_ITEM_IDENTIFIERS.get().get(entry.base());
                    if (old == ItemMapping.AIR || !old.getBedrockIdentifier().equals(entry.alias()) || old.getJavaItem().javaId() != id
                            || base == ItemMapping.AIR || base.getJavaItem() == Items.AIR
                            || registeredAlias == null || registeredAlias.javaId() != id || registeredBase == null)
                        throw new IllegalStateException("Missing/inconsistent Creative alias in protocol " + protocol.getIntKey() + ": " + entry.path());
                    long visible = mappings.getCreativeItems().stream().filter(c -> c.getItem().getDefinition().getIdentifier().equals(entry.alias())).count();
                    if (visible != 1 || mappings.getMapping(mappings.getCreativeItems().stream().filter(c -> c.getItem().getDefinition().getIdentifier().equals(entry.alias())).findFirst().orElseThrow().getItem()) != old)
                        throw new IllegalStateException("Creative reverse mapping is not unique: " + entry.path());
                    CatalogueItem replacement = replacements.get(id);
                    if (replacement == null) {
                        Item originalItem = old.getJavaItem(); Item baseItem = base.getJavaItem();
                        replacement = new CatalogueItem(entry, originalItem, baseItem); replacements.put(id, replacement);
                    }
                    if (!replacement.javaIdentifier().equals(entry.alias()) || base.getJavaItem().javaId() != registeredBase.javaId())
                        throw new IllegalStateException("Protocol base item inconsistency");
                    ItemMapping aliasMapping = ItemMapping.builder()
                            .bedrockIdentifier(old.getBedrockIdentifier()).bedrockDefinition(old.getBedrockDefinition())
                            .bedrockData(old.getBedrockData()).bedrockBlockDefinition(old.getBedrockBlockDefinition())
                            .translationString(old.getTranslationString()).customItemDefinitions(old.getCustomItemDefinitions())
                            .containsV1Mappings(old.isContainsV1Mappings()).javaItem(replacement).build();
                    aliases.add(aliasMapping);
                }
                prepared.put(protocol.getIntKey(), aliases);
            }
            // Atomic validation boundary: only our newly registered aliases are changed after every protocol passes.
            for (Catalogue.Entry entry : catalogue.items) {
                int id = aliasIds.get(entry.path()); Items.register(replacements.get(id), id);
            }
            for (Map.Entry<Integer, List<ItemMapping>> protocol : prepared.entrySet()) {
                ItemMappings mappings = Registries.ITEMS.get().get(protocol.getKey());
                for (ItemMapping alias : protocol.getValue()) mappings.getItems()[alias.getJavaItem().javaId()] = alias;
                for (Catalogue.Entry entry : catalogue.items) mappings.getCachedJavaMappings().remove(entry.alias());
            }
            Set<Integer> ownedIds = new HashSet<>(aliasIds.values());
            for (Map.Entry<Integer, ItemMapping[]> protocol : originalMappings.entrySet()) {
                ItemMapping[] current = Registries.ITEMS.get().get(protocol.getKey()).getItems();
                ItemMapping[] previous = protocol.getValue();
                if (current.length != previous.length) throw new IllegalStateException("Item mapping array size changed");
                for (int i = 0; i < previous.length; i++) if (!ownedIds.contains(i) && current[i] != previous[i])
                    throw new IllegalStateException("An unowned primary item mapping changed");
            }
            for (int i = 0; i < originalJavaItems.size(); i++) if (!ownedIds.contains(i) && Registries.JAVA_ITEMS.get().get(i) != originalJavaItems.get(i))
                throw new IllegalStateException("An unowned Java item changed");
            CreativeRequestGuard guard = CreativeRequestGuard.install(catalogue, aliasIds);
            JsonObject receipt = new JsonObject(); receipt.addProperty("format_version", 1); receipt.addProperty("geyser_sha256", GEYSER_SHA256);
            receipt.addProperty("catalogue_sha256", catalogue.sha256); receipt.addProperty("items", catalogue.items.size());
            JsonObject protocols = new JsonObject(); prepared.forEach((protocol, entries) -> protocols.addProperty(protocol.toString(), entries.size()));
            receipt.add("protocols", protocols);
            receipt.addProperty("unowned_primary_and_java_items_unchanged", true);
            receipt.addProperty("request_guard_delegate_class", guard.delegateClassName());
            receipt.addProperty("unowned_packet_translators_unchanged", true);
            if (Boolean.getBoolean("infinity.creative.proof")) receipt.add("proof", CreativeProtocolProof.run(catalogue, aliasIds));
            writeReceipt("ready.json", receipt);
            logger().info("Infinity Creative bridge ready: " + catalogue.items.size() + " items, " + prepared.size() + " Bedrock protocols");
        } catch (Exception error) {
            fail(error);
            // Refuse to accept clients with visible but untranslatable catalogue entries.
            GeyserImpl.getInstance().shutdown();
        }
    }

    private void fail(Exception error) {
        failure = error;
        logger().error("Infinity Creative bridge refused startup: " + error.getMessage());
        try {
            Files.deleteIfExists(dataFolder().resolve("ready.json"));
            JsonObject report = new JsonObject(); report.addProperty("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            writeReceipt("failed.json", report);
        } catch (Exception ignored) { logger().error("Cannot write Creative bridge failure receipt"); }
    }
    private void writeReceipt(String name, JsonObject value) throws Exception {
        Files.createDirectories(dataFolder()); Path tmp = dataFolder().resolve(name + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n");
        Files.move(tmp, dataFolder().resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }
    private static String identifier(Identifier id) { return id.namespace() + ":" + id.path(); }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int read; while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

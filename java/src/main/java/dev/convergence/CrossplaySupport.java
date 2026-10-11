package dev.convergence;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.BlockState;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import xyz.nucleoid.packettweaker.PacketContext;

/** Keeps real registries and powers on Fabric; only the network representation changes. */
final class CrossplaySupport {
    static final Map<String, Item> BASES = new LinkedHashMap<>();
    static final Map<String, BlockState> BLOCK_STATES = new LinkedHashMap<>();
    // Bedrock custom items cannot reproduce all native shield/glider/projectile behavior.
    // Preserve those native controls, at the cost of native Bedrock artwork for these items.
    static final Set<String> NATIVE_BEDROCK = Set.of("chestplate", "shield", "totem", "bow", "crossbow",
        "infinity_arrow", "void_arrow", "starfire_arrow", "backpack",
        "aurora_helmet","aurora_chestplate","aurora_leggings","aurora_boots",
        "ember_helmet","ember_chestplate","ember_leggings","ember_boots");
    private static final Set<String> POWER_ITEMS = Set.of("sword", "mace", "spear", "pickaxe", "axe", "shovel", "hoe", "builder_wand", "sculptor_wand");

    static void register() {
        BASES.put("helmet", Items.NETHERITE_HELMET);
        BASES.put("chestplate", Items.ELYTRA);
        BASES.put("leggings", Items.NETHERITE_LEGGINGS);
        BASES.put("boots", Items.NETHERITE_BOOTS);
        BASES.put("sword", Items.NETHERITE_SWORD);
        BASES.put("mace", Items.MACE);
        BASES.put("spear", Items.NETHERITE_SPEAR);
        BASES.put("pickaxe", Items.NETHERITE_PICKAXE);
        BASES.put("axe", Items.NETHERITE_AXE);
        BASES.put("shovel", Items.NETHERITE_SHOVEL);
        BASES.put("hoe", Items.NETHERITE_HOE);
        BASES.put("ingot", Items.NETHERITE_INGOT);
        BASES.put("totem", Items.TOTEM_OF_UNDYING);
        BASES.put("shield", Items.SHIELD);
        BASES.put("bow", Items.BOW);
        BASES.put("crossbow", Items.CROSSBOW);
        BASES.put("backpack", Items.LEATHER_CHESTPLATE);
        BASES.putAll(ExpandedGear.COSMETIC_ARMOR);
        BASES.put("builder_wand",Items.BLAZE_ROD);
        BASES.put("sculptor_wand",Items.STICK);
        for (String path : ExpandedGear.ARROWS.stream().sorted().toList()) BASES.put(path, Items.ARROW);
        for (String path : ExpandedGear.BLOCKS.stream().sorted().toList()) {
            var block = Registries.BLOCK.get(id(path));
            var visual = PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(id("block/" + path)));
            if (visual == null) throw new IllegalStateException("No Polymer block model slot for " + path);
            BLOCK_STATES.put(path, visual);
            PolymerBlock.registerOverlay(block, new PolymerTexturedBlock() {
                public BlockState getPolymerBlockState(BlockState state, PacketContext context) { return visual; }
                public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) { return visual; }
                public boolean forceLightUpdates(BlockState state) { return ExpandedGear.light(path)>0; }
            });
            BASES.put(path, Items.NOTE_BLOCK);
        }
        for (var entry : BASES.entrySet()) {
            Item item = Convergence.ITEMS.get("convergence:" + entry.getKey());
            if (item == null) throw new IllegalStateException("Unknown Infinity item " + entry.getKey());
            PolymerItem.registerOverlay(item, new PolymerItem() {
                public Item getPolymerItem(ItemStack stack, PacketContext context) { return entry.getValue(); }
                public boolean handleMiningOnServer(ItemStack tool, BlockState state, BlockPos pos, ServerPlayerEntity player) {
                    return true;
                }
            });
        }
        if (BASES.size() != Convergence.ITEMS.size()) throw new IllegalStateException("Unmapped Infinity item");
        PolymerResourcePackUtils.addModAssets("convergence");
        // Do not require the Java pack: Geyser receives its own Bedrock resource pack.
        ServerLifecycleEvents.SERVER_STARTED.register(CrossplaySupport::exportMappings);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
            CommandManager.literal("convergence")
                .then(CommandManager.literal("power").executes(c -> usePower(c.getSource().getPlayerOrThrow(), false)))
                .then(CommandManager.literal("altpower").executes(c -> usePower(c.getSource().getPlayerOrThrow(), true)))
                .then(CommandManager.literal("swap").executes(c -> swapHands(c.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("server").executes(c -> {
                    c.getSource().sendFeedback(() -> Text.literal(
                        "Infinity Crossplay: Java and Bedrock share this Fabric world. Java: TCP 25565; Bedrock: UDP 19132 (launcher defaults). "
                        + "Touch: /convergence power, /convergence altpower, /convergence swap. Ask the host for the address."), false);
                    return 1;
                }))));
    }

    static int usePower(ServerPlayerEntity player, boolean alternate) {
        if (player.isSpectator() || !player.isAlive()) return 0;
        String name = Convergence.id(player.getMainHandStack());
        if (!name.startsWith("convergence:")) return 0;
        String path = name.substring("convergence:".length());
        if(PoweredTools.CREATIVE_TOOLS.contains(name)&&!PoweredTools.creativeAllowed(player,name)) {
            player.sendMessage(Text.literal("Building wands require Creative in the Creative world; OP4 may use /gamemode creative in any world."),false);return 0;
        }
        if (!POWER_ITEMS.contains(path) && !(alternate && path.equals("shield"))) {
            player.sendMessage(Text.literal("Hold an Infinity weapon/tool; altpower also casts the shield Ward."), false);
            return 0;
        }
        boolean wasSneaking = player.isSneaking();
        try {
            player.setSneaking(alternate);
            player.getMainHandStack().getItem().use(player.getEntityWorld(), player, Hand.MAIN_HAND);
        } finally {
            player.setSneaking(wasSneaking);
        }
        return 1;
    }

    static int swapHands(ServerPlayerEntity player) {
        if (player.isSpectator() || !player.isAlive()) return 0;
        player.clearActiveItem();
        ItemStack main = player.getMainHandStack();
        player.setStackInHand(Hand.MAIN_HAND, player.getOffHandStack());
        player.setStackInHand(Hand.OFF_HAND, main);
        player.currentScreenHandler.sendContentUpdates();
        return 1;
    }

    static Identifier id(String path) { return Identifier.of("convergence", path); }

    private static void exportMappings(MinecraftServer server) {
        Path folder = Path.of("crossplay-export");
        var items = new JsonObject();
        var ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
        for (var entry : BASES.entrySet()) {
            String path = entry.getKey();
            if (NATIVE_BEDROCK.contains(path)) continue;
            ItemStack stack = Convergence.ITEMS.get("convergence:" + path).getDefaultStack();
            var definition = new JsonObject();
            definition.addProperty("type", "definition");
            definition.addProperty("bedrock_identifier", "convergence:" + path);
            definition.addProperty("model", "convergence:" + path);
            definition.addProperty("display_name", stack.getName().getString());
            var options = new JsonObject();
            options.addProperty("icon", "convergence_" + path);
            options.addProperty("allow_offhand", true);
            options.addProperty("display_handheld", POWER_ITEMS.contains(path));
            // Geyser 2.11 cannot translate a Bedrock Creative-list drag for custom
            // definitions back to a Java item. Keep the item mapping and texture,
            // but grant Creative gear from the server with /convergence hold.
            int protection = switch (path) { case "helmet", "boots" -> 6; case "leggings" -> 8; default -> 0; };
            options.addProperty("protection_value", protection);
            definition.add("bedrock_options", options);
            var components = new JsonObject();
            for (var type : new ComponentType<?>[]{DataComponentTypes.MAX_STACK_SIZE, DataComponentTypes.MAX_DAMAGE,
                DataComponentTypes.EQUIPPABLE, DataComponentTypes.ENCHANTABLE, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE}) {
                addComponent(components, stack, type, ops);
            }
            definition.add("components", components);
            String base = Registries.ITEM.getId(entry.getValue()).toString();
            if (!items.has(base)) items.add(base, new JsonArray());
            items.getAsJsonArray(base).add(definition);
        }
        var itemFile = new JsonObject(); itemFile.addProperty("format_version", 2); itemFile.add("items", items);
        var blockFile = new JsonObject(); blockFile.addProperty("format_version", 1);
        var blocks = new JsonObject(); blockFile.add("blocks", blocks);
        var blockDefinition = new JsonObject();
        blockDefinition.addProperty("name", "infinity_building_blocks");
        blockDefinition.addProperty("only_override_states", true);
        blockDefinition.addProperty("unit_cube", true);
        var overrides = new JsonObject(); blockDefinition.add("state_overrides", overrides);
        blocks.add("minecraft:note_block", blockDefinition);
        for (var entry : BLOCK_STATES.entrySet()) {
            String path = entry.getKey();
            var definition = new JsonObject();
            definition.addProperty("name", "infinity_" + path);
            definition.addProperty("display_name", Convergence.ITEMS.get("convergence:" + path).getName().getString());
            definition.addProperty("only_override_states", true);
            definition.addProperty("unit_cube", true);
            definition.addProperty("destructible_by_mining", ExpandedGear.hardness(path));
            definition.addProperty("light_emission", ExpandedGear.light(path));
            var materials = new JsonObject(); var all = new JsonObject();
            all.addProperty("texture", "convergence_" + path);
            all.addProperty("render_method", "opaque"); all.addProperty("face_dimming", true); all.addProperty("ambient_occlusion", true);
            materials.add("*", all); definition.add("material_instances", materials);
            String state = stateIdentifier(entry.getValue());
            overrides.add(state.substring(state.indexOf('[') + 1, state.length() - 1), definition);
            if (!blockDefinition.has("material_instances")) blockDefinition.add("material_instances", materials);
        }
        try {
            Files.createDirectories(folder);
            var gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(folder.resolve("infinity-items.json"), gson.toJson(itemFile));
            Files.writeString(folder.resolve("infinity-blocks.json"), gson.toJson(blockFile));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot export Infinity crossplay mappings", e);
        }
    }

    private static <T> void addComponent(JsonObject output, ItemStack stack, ComponentType<T> type,
                                        RegistryOps<com.google.gson.JsonElement> ops) {
        T value = stack.get(type);
        if (value != null && type.getCodec() != null) {
            output.add(Registries.DATA_COMPONENT_TYPE.getId(type).toString(), type.getCodec().encodeStart(ops, value).getOrThrow());
        }
    }

    static String stateIdentifier(BlockState state) {
        String properties = state.getEntries().entrySet().stream().sorted(Map.Entry.comparingByKey(
            java.util.Comparator.comparing(Property::getName))).map(CrossplaySupport::propertyString).collect(Collectors.joining(","));
        return Registries.BLOCK.getId(state.getBlock()) + (properties.isEmpty() ? "" : "[" + properties + "]");
    }

    private static <T extends Comparable<T>> String propertyString(Map.Entry<Property<?>, Comparable<?>> entry) {
        @SuppressWarnings("unchecked") Property<T> property = (Property<T>) entry.getKey();
        @SuppressWarnings("unchecked") T value = (T) entry.getValue();
        return property.getName() + "=" + property.name(value);
    }
}

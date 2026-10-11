package dev.convergence;

import com.google.gson.GsonBuilder;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
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
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

/** Keeps real registries and powers on Fabric; only the network representation changes. */
final class CrossplaySupport {
    static final Map<String, Item> BASES = new LinkedHashMap<>();
    static final Map<String, BlockState> BLOCK_STATES = new LinkedHashMap<>();
    // Bedrock custom items cannot reproduce all native shield/glider/projectile behavior.
    // Preserve native controls. The Bedrock pack restyles shared native armor textures
    // and the elytra attachable; native ranged/offhand artwork remains unchanged.
    static final Set<String> NATIVE_BEDROCK = Set.of("helmet", "chestplate", "leggings", "boots", "shield", "totem", "bow", "crossbow",
        "infinity_arrow", "void_arrow", "starfire_arrow", "backpack",
        "aurora_helmet","aurora_chestplate","aurora_leggings","aurora_boots",
        "ember_helmet","ember_chestplate","ember_leggings","ember_boots");
    private static final Set<String> POWER_ITEMS = Set.of("sword", "mace", "spear", "pickaxe", "axe", "shovel", "hoe", "builder_wand", "sculptor_wand");

    static boolean bedrock(ServerPlayer player) {
        var api=org.geysermc.floodgate.api.FloodgateApi.getInstance();
        return player!=null && api!=null && api.isFloodgatePlayer(player.getUUID());
    }

    static boolean nativeAppearance(String path,boolean bedrock,boolean javaPack) {
        return bedrock ? NATIVE_BEDROCK.contains(path) : !javaPack;
    }

    static void wearableFallback(ItemStack out,Item base,boolean bedrock,boolean javaPack) {
        if(bedrock || !javaPack) {
            var wearable=base.components().get(DataComponents.EQUIPPABLE);
            if(wearable!=null)out.set(DataComponents.EQUIPPABLE,wearable);
        }
    }

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
            var block = BuiltInRegistries.BLOCK.getValue(id(path));
            var visual = PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK,
                PolymerBlockModel.of(id("block/" + path)));
            if (visual == null) throw new IllegalStateException("No Polymer block model slot for " + path);
            BLOCK_STATES.put(path, visual);
            PolymerBlock.registerOverlay(block, new PolymerTexturedBlock() {
                @Override public BlockState getPolymerBlockState(BlockState state, PacketContext context) { return visual; }
                @Override public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) { return visual; }
                @Override public boolean forceLightUpdates(BlockState state) { return ExpandedGear.light(path)>0; }
            });
            BASES.put(path, Items.NOTE_BLOCK);
        }
        for (var entry : BASES.entrySet()) {
            Item item = Convergence.ITEMS.get("convergence:" + entry.getKey());
            if (item == null) throw new IllegalStateException("Unknown Infinity item " + entry.getKey());
            PolymerItem.registerOverlay(item, new PolymerItem() {
                @Override public Item getPolymerItem(ItemStack stack, PacketContext context) { return entry.getValue(); }
                @Override public Identifier getPolymerItemModel(ItemStack stack,PacketContext context,HolderLookup.Provider registries) {
                    boolean bedrock=bedrock(PolymerCommonUtils.getPlayer(context));
                    // Bedrock needs the custom model key to select its own resource mapping.
                    // Java without the downloaded pack must use an existing native icon.
                    return nativeAppearance(entry.getKey(),bedrock,PolymerResourcePackUtils.hasMainPack(context))
                        ? entry.getValue().components().get(DataComponents.ITEM_MODEL) : stack.get(DataComponents.ITEM_MODEL);
                }
                @Override public void modifyBasePolymerItemStack(ItemStack out,ItemStack stack,PacketContext context,HolderLookup.Provider registries) {
                    out.set(DataComponents.ITEM_NAME,GearNames.text(entry.getKey()));
                    var customName=stack.get(DataComponents.CUSTOM_NAME);
                    if(customName!=null)out.set(DataComponents.CUSTOM_NAME,GearNames.readableName(customName));
                    // A custom Java equipment asset is invisible/missing when its pack
                    // was declined, and unsupported for native Bedrock wearable mappings.
                    // Keep a complete native wearable asset until the Java pack is loaded.
                    wearableFallback(out,entry.getValue(),bedrock(PolymerCommonUtils.getPlayer(context)),PolymerResourcePackUtils.hasMainPack(context));
                }
                @Override public boolean handleMiningOnServer(ItemStack tool, BlockState state, BlockPos pos, ServerPlayer player) {
                    return true;
                }
            });
        }
        if (BASES.size() != Convergence.ITEMS.size()) throw new IllegalStateException("Unmapped Infinity item");
        PolymerResourcePackUtils.addModAssets("convergence");
        // Do not require the Java pack: Geyser receives its own Bedrock resource pack.
        ServerLifecycleEvents.SERVER_STARTED.register(CrossplaySupport::exportMappings);
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(
            Commands.literal("convergence")
                .then(Commands.literal("power").executes(c -> usePower(c.getSource().getPlayerOrException(), false)))
                .then(Commands.literal("altpower").executes(c -> usePower(c.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("swap").executes(c -> swapHands(c.getSource().getPlayerOrException())))
                .then(Commands.literal("server").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal(serverInfoText()), false);
                    return 1;
                }))));
    }

    static String serverInfoText() {
        return "Infinity Crossplay: Java and Bedrock share this Fabric world. Java: TCP 25565; Bedrock: UDP 19132 (launcher defaults). "
            + "Select the Infinity Menu recovery compass for touch-friendly powers, hand swapping, gear, and helper controls. Ask the host for the address.";
    }

    static int usePower(ServerPlayer player, boolean alternate) {
        if (player.isSpectator() || !player.isAlive()) return 0;
        String name = Convergence.id(player.getMainHandItem());
        if (!name.startsWith("convergence:")) return 0;
        String path = name.substring("convergence:".length());
        if(PoweredTools.CREATIVE_TOOLS.contains(name)&&!PoweredTools.creativeAllowed(player,name)) {
            player.sendSystemMessage(Component.literal("Building wands require Creative in the Creative world; OP4 may use /gamemode creative in any world."));return 0;
        }
        if (!POWER_ITEMS.contains(path) && !(alternate && path.equals("shield"))) {
            player.sendSystemMessage(Component.literal("Hold an Infinity weapon or tool; Alternate Power also casts the shield Ward."));
            return 0;
        }
        boolean wasSneaking = player.isShiftKeyDown();
        try {
            player.setShiftKeyDown(alternate);
            player.getMainHandItem().getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
        } finally {
            player.setShiftKeyDown(wasSneaking);
        }
        return 1;
    }

    static int swapHands(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()) return 0;
        player.stopUsingItem();
        ItemStack main = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, player.getOffhandItem());
        player.setItemInHand(InteractionHand.OFF_HAND, main);
        player.containerMenu.broadcastChanges();
        return 1;
    }

    static Identifier id(String path) { return Identifier.fromNamespaceAndPath("convergence", path); }

    private static void exportMappings(MinecraftServer server) {
        Path folder = Path.of("crossplay-export");
        var items = new JsonObject();
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (var entry : BASES.entrySet()) {
            String path = entry.getKey();
            if (NATIVE_BEDROCK.contains(path)) continue;
            ItemStack stack = Convergence.ITEMS.get("convergence:" + path).getDefaultInstance();
            var definition = new JsonObject();
            definition.addProperty("type", "definition");
            definition.addProperty("bedrock_identifier", "convergence:" + path);
            definition.addProperty("model", "convergence:" + path);
            definition.addProperty("display_name", GearNames.label(path));
            var options = new JsonObject();
            options.addProperty("icon", "convergence_" + path);
            options.addProperty("allow_offhand", true);
            options.addProperty("display_handheld", POWER_ITEMS.contains(path));
            // Geyser 2.11 cannot translate a Bedrock Creative-list drag for custom
            // definitions back to a Java item. Keep the item mapping and texture,
            // but grant real gear into the player inventory from server controls.
            int protection = switch (path) { case "helmet", "boots" -> 6; case "leggings" -> 8; default -> 0; };
            options.addProperty("protection_value", protection);
            definition.add("bedrock_options", options);
            var components = new JsonObject();
            for (var type : new DataComponentType<?>[]{DataComponents.MAX_STACK_SIZE, DataComponents.MAX_DAMAGE,
                DataComponents.EQUIPPABLE, DataComponents.ENCHANTABLE, DataComponents.ENCHANTMENT_GLINT_OVERRIDE}) {
                addComponent(components, stack, type, ops);
            }
            definition.add("components", components);
            String base = BuiltInRegistries.ITEM.getKey(entry.getValue()).toString();
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
            definition.addProperty("display_name", GearNames.label(path));
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

    private static <T> void addComponent(JsonObject output, ItemStack stack, DataComponentType<T> type,
                                        RegistryOps<com.google.gson.JsonElement> ops) {
        T value = stack.get(type);
        if (value != null && type.codec() != null) {
            output.add(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString(), type.codec().encodeStart(ops, value).getOrThrow());
        }
    }

    static String stateIdentifier(BlockState state) {
        String properties = state.getValues().sorted(java.util.Comparator.comparing(value -> value.property().getName()))
            .map(CrossplaySupport::propertyString).collect(Collectors.joining(","));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + (properties.isEmpty() ? "" : "[" + properties + "]");
    }

    private static String propertyString(Property.Value<?> value) {
        return value.property().getName() + "=" + value.valueName();
    }
}

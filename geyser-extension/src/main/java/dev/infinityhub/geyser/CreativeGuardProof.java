package dev.infinityhub.geyser;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.TextProcessingEventOrigin;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftResultsDeprecatedAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;

/** Exercises the installed guard and native responses with owned inventory state, not invented replies. */
final class CreativeGuardProof {
    static JsonObject run(Catalogue catalogue) throws Exception {
        int stale = 0, mixed = 0, otherModes = 0, responsesOnWire = 0, metadata = 0;
        CreativeRequestGuard guard = (CreativeRequestGuard) Registries.BEDROCK_PACKET_TRANSLATORS.get(ItemStackRequestPacket.class);
        for (Int2ObjectMap.Entry<ItemMappings> version : Registries.ITEMS.get().int2ObjectEntrySet()) {
            try (CreativeProtocolProof.CaptureSession session = new CreativeProtocolProof.CaptureSession(version.getValue(), version.getIntKey())) {
                for (Catalogue.Entry entry : catalogue.items) {
                    int index = creativeIndex(session, entry.alias());
                    ItemData alias = session.getItemMappings().getCreativeItems().get(index).getItem();
                    for (int shape = 0; shape < 3; shape++) {
                        ownedInventory(session, catalogue);
                        InventoryState before = InventoryState.capture(session);
                        session.mode = GameMode.SURVIVAL;
                        ItemStackRequest request = staleRequest(1701 + stale, index, alias, shape);
                        metadataCheck(guard, session, request); metadata++;
                        session.packets.clear();
                        List<ItemStackResponse> responses = CreativeProtocolProof.dispatch(session, List.of(request));
                        require(responses.getFirst().getResult() == ItemStackResponseStatus.ERROR,
                                "Stale Survival Creative alias must use the native error response");
                        before.requireSame(session, -1, -1, "stale-only; protocol=" + version.getIntKey()
                                + "; alias=" + entry.path() + "; shape=" + shape + "; request=" + request.getRequestId());
                        require(session.packets.isEmpty(), "Rejected stale alias emitted a downstream grant, drop or click");
                        responsesOnWire += checkNativeResync(session, before.bedrockItems(), before.cursorData());
                        require(session.getPlayerInventory().getNextStateId() == -1, "Native batch state was not reset");
                        stale++;
                    }
                }
                // The guard applies outside Creative, including Adventure and Spectator.
                Catalogue.Entry first = catalogue.items.getFirst(); int firstIndex = creativeIndex(session, first.alias());
                ItemData firstAlias = session.getItemMappings().getCreativeItems().get(firstIndex).getItem();
                for (GameMode mode : new GameMode[]{GameMode.ADVENTURE, GameMode.SPECTATOR}) {
                    ownedInventory(session, catalogue); InventoryState before = InventoryState.capture(session); session.mode = mode;
                    session.packets.clear();
                    List<ItemStackResponse> response = CreativeProtocolProof.dispatch(session,
                            List.of(staleRequest(3001 + otherModes, firstIndex, firstAlias, 2)));
                    require(response.getFirst().getResult() == ItemStackResponseStatus.ERROR, "Non-Creative mode bypassed the guard");
                    before.requireSame(session, -1, -1, "other-mode; protocol=" + version.getIntKey() + "; mode=" + mode
                            + "; request=" + (3001 + otherModes));
                    require(session.packets.isEmpty(), "Non-Creative alias emitted a downstream item");
                    responsesOnWire += checkNativeResync(session, before.bedrockItems(), before.cursorData()); otherModes++;
                }
                // Native ordinary Survival drops must still execute before and after a rejected alias.
                ownedInventory(session, catalogue); InventoryState before = InventoryState.capture(session); session.mode = GameMode.SURVIVAL;
                PlayerInventory inventory = session.getPlayerInventory();
                ItemStackRequest left = ordinaryDrop(4101, 2, inventory.getItem(38).getNetId());
                ItemStackRequest right = ordinaryDrop(4103, 3, inventory.getItem(39).getNetId());
                ItemStackRequest middle = staleRequest(4102, firstIndex, firstAlias, 2);
                ItemStackRequestPacket original = packet(left, middle, right);
                ItemStackRequestPacket sanitized = guard.sanitize(session, original);
                require(sanitized != original && sanitized.getRequests().get(0) == left && sanitized.getRequests().get(2) == right
                        && original.getRequests().get(1) == middle && middle.getActions().length == 4,
                        "Guard changed an ordinary request, batch order or the original input packet");
                session.packets.clear();
                List<ItemStackResponse> responses = CreativeProtocolProof.dispatch(session, List.of(left, middle, right));
                require(responses.get(0).getResult() == ItemStackResponseStatus.OK && responses.get(1).getResult() == ItemStackResponseStatus.ERROR
                        && responses.get(2).getResult() == ItemStackResponseStatus.OK,
                        "Mixed batch did not retain native ordinary success and stale alias rejection");
                require(inventory.getItem(38).getAmount() == 6 && inventory.getItem(39).getAmount() == 6,
                        "Ordinary Survival drops did not remove exactly their legitimate requested items");
                before.items().get(38).requireCountReducedOnly(inventory.getItem(38), 1, "slot 38");
                before.items().get(39).requireCountReducedOnly(inventory.getItem(39), 1, "slot 39");
                before.requireSame(session, 38, 39, "mixed-ordinary; protocol=" + version.getIntKey() + "; requests=4101,4102,4103");
                require(session.packets.size() == 2 && session.packets.stream().allMatch(ServerboundContainerClickPacket.class::isInstance),
                        "Mixed batch must emit only the two actual ordinary Survival click packets");
                InventoryState after = InventoryState.capture(session);
                responsesOnWire += checkNativeResync(session, after.bedrockItems(), after.cursorData()); mixed++;
                // Preserve the ordinary Creative catalogue, invalid indices and non-first Creative actions exactly.
                narrowBoundaryCheck(guard, session, firstIndex, firstAlias);
            }
        }
        int protocols = Registries.ITEMS.get().size();
        require(stale == catalogue.items.size() * protocols * 3 && mixed == protocols && otherModes == protocols * 2,
                "Incomplete stale request protocol/mode coverage");
        JsonObject receipt = new JsonObject();
        receipt.addProperty("stale_survival_alias_requests", stale);
        receipt.addProperty("adventure_and_spectator_requests", otherModes);
        receipt.addProperty("mixed_ordinary_survival_batches", mixed);
        receipt.addProperty("request_metadata_preservation_cases", metadata);
        receipt.addProperty("native_response_and_resync_wire_packets", responsesOnWire);
        receipt.addProperty("complete_cached_components_counts_and_network_ids_preserved", true);
        receipt.addProperty("native_error_cursor_inventory_armor_offhand_and_crafting_resync", true);
        receipt.addProperty("ordinary_requests_and_unowned_creative_entries_unchanged", true);
        receipt.addProperty("bedrock_codec_roundtrips", "Exact configured protocol request/response and full inventory codecs; no authenticated device");
        return receipt;
    }

    private static void metadataCheck(CreativeRequestGuard guard, CreativeProtocolProof.CaptureSession session, ItemStackRequest request) {
        ItemStackRequestPacket input = packet(request); ItemStackRequestPacket result = guard.sanitize(session, input);
        require(result != input && result.getRequests().size() == 1, "Owned stale request was not isolated");
        ItemStackRequest empty = result.getRequests().getFirst();
        require(empty.getRequestId() == request.getRequestId() && empty.getActions().length == 0
                        && empty.getFilterStrings() == request.getFilterStrings()
                        && empty.getTextProcessingEventOrigin() == request.getTextProcessingEventOrigin(),
                "Rejected request lost its ID, filters or text-processing origin");
        session.mode = GameMode.CREATIVE;
        require(guard.sanitize(session, input) == input, "Live Creative requests must remain identical");
        session.mode = GameMode.SURVIVAL;
    }

    private static void narrowBoundaryCheck(CreativeRequestGuard guard, CreativeProtocolProof.CaptureSession session, int aliasIndex, ItemData alias) {
        int ordinaryIndex = creativeIndex(session, "minecraft:diamond");
        ItemData diamond = session.getItemMappings().getCreativeItems().get(ordinaryIndex).getItem();
        ItemStackRequestPacket ordinary = packet(staleRequest(4201, ordinaryIndex, diamond, 0));
        require(guard.sanitize(session, ordinary) == ordinary, "An unowned vanilla Creative request was changed");
        ItemStackRequestPacket invalid = packet(staleRequest(4202, session.getItemMappings().getCreativeItems().size(), alias, 0));
        require(guard.sanitize(session, invalid) == invalid, "Out-of-range native request handling changed");
        ItemStackRequestPacket notFirst = packet(new ItemStackRequest(4203, new ItemStackRequestAction[]{
                new DropAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50, 0), false), new CraftCreativeAction(aliasIndex + 1, 1)}, new String[0]));
        require(guard.sanitize(session, notFirst) == notFirst, "Guard intercepted a request not dispatched as Creative");
    }

    private static ItemStackRequest staleRequest(int id, int index, ItemData alias, int shape) {
        List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new CraftCreativeAction(index + 1, 1)); actions.add(new CraftResultsDeprecatedAction(new ItemData[]{alias}, 1));
        if (shape == 2) actions.add(new DestroyAction(1, slot(ContainerSlotType.HOTBAR, 0, 0)));
        actions.add(shape == 1 ? new PlaceAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50, 0), slot(ContainerSlotType.HOTBAR, 0, 0))
                : new DropAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50, 0), false));
        return new ItemStackRequest(id, actions.toArray(ItemStackRequestAction[]::new), new String[]{"retained filter"}, TextProcessingEventOrigin.ANVIL_TEXT);
    }
    private static ItemStackRequest ordinaryDrop(int id, int hotbar, int netId) {
        return new ItemStackRequest(id, new ItemStackRequestAction[]{new DropAction(1, slot(ContainerSlotType.HOTBAR, hotbar, netId), false)}, new String[0]);
    }
    private static ItemStackRequestSlotData slot(ContainerSlotType type, int slot, int netId) {
        return new ItemStackRequestSlotData(type, slot, netId, new FullContainerName(type, null));
    }
    private static ItemStackRequestPacket packet(ItemStackRequest... requests) {
        ItemStackRequestPacket packet = new ItemStackRequestPacket(); packet.getRequests().addAll(Arrays.asList(requests)); return packet;
    }
    private static int creativeIndex(CreativeProtocolProof.CaptureSession session, String identifier) {
        var entries = session.getItemMappings().getCreativeItems(); int result = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).getItem().getDefinition().getIdentifier().equals(identifier)) {
            require(result == -1, "Creative fixture identifier is not unique"); result = i;
        }
        require(result >= 0, "Missing real Creative fixture entry: " + identifier); return result;
    }

    private static void ownedInventory(CreativeProtocolProof.CaptureSession session, Catalogue catalogue) {
        session.mode = GameMode.CREATIVE;
        PlayerInventory inventory = session.getPlayerInventory();
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, GeyserItemStack.EMPTY, session);
        inventory.setItem(36, owned(session, "minecraft:diamond", 5, "Owned named diamonds", false), session);
        inventory.setItem(37, owned(session, "minecraft:netherite_sword", 1, "Owned damaged sword", true), session);
        inventory.setItem(38, owned(session, "minecraft:diamond", 7, "Owned ordinary diamond drop", false), session);
        inventory.setItem(39, owned(session, "minecraft:emerald", 7, "Owned ordinary emerald drop", false), session);
        inventory.setCursor(owned(session, "minecraft:emerald", 3, "Owned cursor", false), session);
        for (String path : new String[]{"backpack", "aurora_helmet"}) {
            Catalogue.Entry entry = catalogue.items.stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow();
            ItemData data = session.getItemMappings().getCreativeItems().get(creativeIndex(session, entry.alias())).getItem();
            ItemStack canonical = ItemTranslator.translateToJava(session, data);
            require(canonical != null && canonical.getDataComponentsPatch() != null, "Missing canonical owned Infinity fixture");
            canonical.getDataComponentsPatch().put(DataComponentTypes.CUSTOM_NAME, Component.text("Owned " + path));
            inventory.setItem(path.equals("backpack") ? 9 : 5, GeyserItemStack.from(session, canonical), session);
        }
        inventory.setItem(45, owned(session, "minecraft:shield", 1, "Owned offhand", true), session);
        inventory.setItem(1, owned(session, "minecraft:diamond", 2, "Owned crafting slot", false), session);
        inventory.setStateId(8); inventory.incrementStateId(2);
    }
    private static GeyserItemStack owned(CreativeProtocolProof.CaptureSession session, String base, int count, String name, boolean damaged) {
        ItemMapping mapping = session.getItemMappings().getMapping(base);
        DataComponents components = new DataComponents(new HashMap<>());
        if (name != null) {
            components.put(DataComponentTypes.CUSTOM_NAME, Component.text(name));
            components.put(DataComponentTypes.CUSTOM_DATA, NbtMap.builder().putCompound("owned", NbtMap.builder()
                    .putString("marker", name).putInt("persisted", 17).build()).build());
        }
        if (damaged) components.put(DataComponentTypes.DAMAGE, 7);
        return GeyserItemStack.from(session, new ItemStack(mapping.getJavaItem().javaId(), count, components));
    }

    private static int checkNativeResync(CreativeProtocolProof.CaptureSession session, List<ItemData> expected, ItemData cursor) throws Exception {
        Map<Integer, InventoryContentPacket> contents = new HashMap<>(); InventorySlotPacket cursorPacket = null;
        int responses = 0, crafting = 0;
        for (BedrockPacket emitted : session.upstreamPackets) {
            BedrockPacket packet = codecRoundtrip(session, emitted);
            if (packet instanceof ItemStackResponsePacket) responses++;
            if (packet instanceof InventoryContentPacket content)
                require(contents.put(content.getContainerId(), content) == null, "Duplicate native inventory resync");
            if (packet instanceof InventorySlotPacket slot && slot.getContainerId() == ContainerId.UI) {
                if (slot.getSlot() == 0) { require(cursorPacket == null, "Duplicate cursor resync"); cursorPacket = slot; }
                else if (slot.getSlot() >= 28 && slot.getSlot() <= 31) {
                    require(slot.getItem().equals(expected.get(slot.getSlot() - 27)), "Crafting-grid resync differs from owned state"); crafting++;
                }
            }
        }
        require(responses == 1 && cursorPacket != null && cursorPacket.getItem().equals(cursor) && crafting == 4,
                "Missing exact native error/cursor/crafting refresh packets");
        require(contents.size() == 3 && contents.containsKey(ContainerId.INVENTORY) && contents.containsKey(ContainerId.ARMOR)
                && contents.containsKey(ContainerId.OFFHAND), "Missing native full inventory, armor or offhand refresh");
        List<ItemData> inventory = contents.get(ContainerId.INVENTORY).getContents();
        require(inventory.size() == 36, "Native inventory refresh has the wrong number of slots");
        for (int slot = 9; slot < 45; slot++) {
            int bedrock = slot < 36 ? slot : slot - 36;
            require(inventory.get(bedrock).equals(expected.get(slot)), "Native full inventory resync changed owned slot " + slot);
        }
        require(contents.get(ContainerId.ARMOR).getContents().equals(expected.subList(5, 9)), "Native armor resync changed owned equipment");
        require(contents.get(ContainerId.OFFHAND).getContents().equals(List.of(expected.get(45))), "Native offhand resync changed owned item");
        return session.upstreamPackets.size();
    }
    private static BedrockPacket codecRoundtrip(CreativeProtocolProof.CaptureSession session, BedrockPacket packet) throws Exception {
        var codec = session.getUpstream().getSession().getCodec(); var helper = session.getUpstream().getCodecHelper();
        ByteBuf wire = Unpooled.buffer(); String stage = "encoding";
        try {
            codec.tryEncode(helper, wire, packet);
            stage = "decoding";
            require(codec.getPacketDefinition(packet.getClass()).getId()
                            == session.clientCodec.getPacketDefinition(packet.getClass()).getId(),
                    "Pinned server/client packet IDs differ");
            BedrockPacket decoded = session.clientCodec.tryDecode(session.clientCodecHelper, wire,
                    session.clientCodec.getPacketDefinition(packet.getClass()).getId(), PacketRecipient.CLIENT);
            require(!wire.isReadable() && decoded.getClass() == packet.getClass(), "Native response codec did not consume the exact packet");
            if (packet instanceof ItemStackResponsePacket response) require(decoded.equals(response), "Native response IDs/results changed on wire");
            return decoded;
        } catch (Exception error) {
            StringBuilder causes = new StringBuilder();
            for (Throwable cause = error; cause != null && causes.length() < 2048; cause = cause.getCause()) {
                if (!causes.isEmpty()) causes.append(" -> ");
                causes.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            }
            throw new IllegalStateException("Native Bedrock codec proof failed while " + stage + "; protocol=" + codec.getProtocolVersion()
                    + "; packet=" + packet.getClass().getSimpleName() + "; original=" + packet
                    + "; readerIndex=" + wire.readerIndex() + "; writerIndex=" + wire.writerIndex() + "; causes=" + causes, error);
        } finally { wire.release(); }
    }

    private record StackState(byte[] payload, int netId) {
        static StackState capture(GeyserItemStack item) {
            ItemStack stack = item.isEmpty() ? new ItemStack(-1, 0, null) : item.getItemStack();
            return new StackState(encode(stack), item.getNetId());
        }
        private static byte[] encode(ItemStack stack) {
            ByteBuf wire = Unpooled.buffer();
            try {
                new ServerboundSetCreativeModeSlotPacket((short) 0, stack).serialize(wire);
                byte[] bytes = new byte[wire.readableBytes()]; wire.readBytes(bytes); return bytes;
            } finally { wire.release(); }
        }
        void requireSame(GeyserItemStack item, String location) {
            StackState current = capture(item);
            // DataComponents.clone rebuilds its complete HashMap. Native ordinary
            // clicks may therefore reorder encoded component entries without changing
            // any key or nested value. Compare the complete decoded stack, not map order.
            if (netId != current.netId || !Objects.equals(decoded(), current.decoded()))
                throw new IllegalStateException("Owned complete components/count/network ID changed: " + location + "; " + difference(current));
        }
        private ItemStack decoded() {
            ByteBuf wire = Unpooled.wrappedBuffer(payload);
            try {
                ItemStack result = new ServerboundSetCreativeModeSlotPacket(wire).getClickedItem();
                require(!wire.isReadable(), "Owned snapshot decoder left unread data");
                return result;
            } finally { wire.release(); }
        }
        private String difference(StackState current) {
            ItemStack before = decoded(), after = current.decoded();
            return "netId=" + netId + "->" + current.netId + "; bytes=" + payload.length + "->" + current.payload.length
                    + "; exactStackEquals=" + Objects.equals(before, after)
                    + "; before=" + bounded(before) + "; after=" + bounded(after);
        }
        private static String bounded(Object value) {
            String text = String.valueOf(value);
            return text.length() <= 1100 ? text : text.substring(0, 1100) + "[truncated]";
        }
        void requireCountReducedOnly(GeyserItemStack item, int removed, String location) {
            ByteBuf wire = Unpooled.wrappedBuffer(payload);
            try {
                ItemStack original = new ServerboundSetCreativeModeSlotPacket(wire).getClickedItem();
                require(!wire.isReadable() && original.getAmount() > removed, "Invalid ordinary drop fixture: " + location);
                ItemStack expected = new ItemStack(original.getId(), original.getAmount() - removed, original.getDataComponentsPatch());
                StackState current = capture(item);
                // Native Inventory.updateItemNetId retains the ID when the item definition
                // stays the same. ItemStack equality includes ID, count and the complete
                // DataComponents map, including component removals and nested values.
                if (netId != current.netId || !Objects.equals(expected, current.decoded()))
                    throw new IllegalStateException("Ordinary drop changed identity, components or network ID besides its count: "
                            + location + "; expectedCount=" + expected.getAmount() + "; " + difference(current));
            } finally { wire.release(); }
        }
    }
    private record InventoryState(List<StackState> items, StackState cursor, List<ItemData> bedrockItems, ItemData cursorData) {
        static InventoryState capture(CreativeProtocolProof.CaptureSession session) {
            PlayerInventory inventory = session.getPlayerInventory(); List<StackState> items = new ArrayList<>(); List<ItemData> bedrock = new ArrayList<>();
            for (int i = 0; i < inventory.getSize(); i++) { items.add(StackState.capture(inventory.getItem(i))); bedrock.add(inventory.getItem(i).getItemData(session)); }
            return new InventoryState(List.copyOf(items), StackState.capture(inventory.getCursor()), List.copyOf(bedrock), inventory.getCursor().getItemData(session));
        }
        void requireSame(CreativeProtocolProof.CaptureSession session, int changedA, int changedB, String context) {
            for (int i = 0; i < items.size(); i++) if (i != changedA && i != changedB)
                items.get(i).requireSame(session.getPlayerInventory().getItem(i), "slot " + i + "; " + context);
            cursor.requireSame(session.getPlayerInventory().getCursor(), "cursor; " + context);
        }
    }
    private static void require(boolean passed, String message) { if (!passed) throw new IllegalStateException(message); }
}

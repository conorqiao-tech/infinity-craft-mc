package dev.infinityhub.geyser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.bedrock.BedrockItemStackRequestTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;

/**
 * A narrow adapter for this exact Geyser build's stale Creative requests. The
 * native dispatcher can enter its Creative handler after a mode switch; an
 * alias correctly translates to no item there, but DROP then dereferences it
 * after DESTROY may already have changed the cache. An empty request enters
 * Geyser's existing error response and complete inventory resynchronization.
 */
final class CreativeRequestGuard extends PacketTranslator<ItemStackRequestPacket> {
    private static final ItemStackRequestAction[] NO_ACTIONS = new ItemStackRequestAction[0];
    private final BedrockItemStackRequestTranslator delegate;
    private final Map<Integer, String> ownedAliases;

    private CreativeRequestGuard(BedrockItemStackRequestTranslator delegate, Map<Integer, String> ownedAliases) {
        this.delegate = delegate;
        this.ownedAliases = Map.copyOf(ownedAliases);
    }

    static CreativeRequestGuard install(Catalogue catalogue, Map<String, Integer> aliasIds) {
        PacketTranslator<? extends BedrockPacket> original = Registries.BEDROCK_PACKET_TRANSLATORS.get(ItemStackRequestPacket.class);
        if (original == null || original.getClass() != BedrockItemStackRequestTranslator.class)
            throw new IllegalStateException("Unsupported ItemStackRequest translator; refusing to replace another adapter");
        Map<Integer, String> owned = new HashMap<>();
        for (Catalogue.Entry entry : catalogue.items) {
            Integer id = aliasIds.get(entry.path());
            if (id == null || owned.put(id, entry.alias()) != null)
                throw new IllegalStateException("Invalid request-guard alias ownership");
        }
        CreativeRequestGuard guard = new CreativeRequestGuard((BedrockItemStackRequestTranslator) original, owned);
        Map<Class<? extends BedrockPacket>, PacketTranslator<? extends BedrockPacket>> previous =
                new HashMap<>(Registries.BEDROCK_PACKET_TRANSLATORS.get());
        if (Registries.BEDROCK_PACKET_TRANSLATORS.register(ItemStackRequestPacket.class, guard) != original)
            throw new IllegalStateException("ItemStackRequest translator changed during guard installation");
        if (Registries.BEDROCK_PACKET_TRANSLATORS.get().size() != previous.size())
            throw new IllegalStateException("Packet translator registry size changed");
        previous.forEach((type, translator) -> {
            if (type != ItemStackRequestPacket.class && Registries.BEDROCK_PACKET_TRANSLATORS.get(type) != translator)
                throw new IllegalStateException("An unowned packet translator changed");
        });
        return guard;
    }

    @Override
    public void translate(GeyserSession session, ItemStackRequestPacket packet) {
        delegate.translate(session, sanitize(session, packet));
        WingedChestSupport.syncSelf(session);
    }

    @Override
    public boolean shouldExecuteInEventLoop() { return delegate.shouldExecuteInEventLoop(); }

    /** Returns the original packet when untouched; ordinary requests retain their exact objects. */
    ItemStackRequestPacket sanitize(GeyserSession session, ItemStackRequestPacket packet) {
        if (session.getGameMode() == GameMode.CREATIVE) return packet;
        List<ItemStackRequest> requests = packet.getRequests();
        ItemStackRequestPacket sanitized = null;
        for (int i = 0; i < requests.size(); i++) {
            ItemStackRequest request = requests.get(i);
            if (!isOwnedCreativeRequest(session, request)) continue;
            if (sanitized == null) {
                sanitized = new ItemStackRequestPacket();
                sanitized.getRequests().addAll(requests);
            }
            sanitized.getRequests().set(i, new ItemStackRequest(request.getRequestId(), NO_ACTIONS,
                    request.getFilterStrings(), request.getTextProcessingEventOrigin()));
        }
        return sanitized == null ? packet : sanitized;
    }

    private boolean isOwnedCreativeRequest(GeyserSession session, ItemStackRequest request) {
        ItemStackRequestAction[] actions = request.getActions();
        if (actions.length == 0 || !(actions[0] instanceof CraftCreativeAction creative)) return false;
        int index = creative.getCreativeItemNetworkId() - 1;
        List<CreativeItemData> entries = session.getItemMappings().getCreativeItems();
        if (index < 0 || index >= entries.size()) return false;
        ItemMapping mapping = session.getItemMappings().getMapping(entries.get(index).getItem());
        return mapping.getJavaItem() instanceof CatalogueItem
                && mapping.getBedrockIdentifier().equals(ownedAliases.get(mapping.getJavaItem().javaId()));
    }

    String delegateClassName() { return delegate.getClass().getName(); }
}

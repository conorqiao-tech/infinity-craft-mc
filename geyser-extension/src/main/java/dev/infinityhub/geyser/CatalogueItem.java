package dev.infinityhub.geyser;

import java.util.HashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.item.components.resolvable.ResolvableComponentGetter;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

/** Canonicalize BEFORE PlayerInventoryTranslator creates its cached cursor/slot stack. */
final class CatalogueItem extends Item {
    private final Catalogue.Entry entry;
    private final int baseId;
    private final DataComponents canonicalPatch;

    CatalogueItem(Catalogue.Entry entry, Item original, Item base) {
        super(entry.alias(), Item.builder()
                .components(original.gatherComponents(ResolvableComponentGetter.EMPTY, null))
                .resolvableComponents(original.resolvableComponents()));
        this.entry = entry;
        this.baseId = base.javaId();
        // Alias components only describe the public Bedrock catalogue definition. Some are
        // slot-only descriptors, not complete Java wire components. The server reconstructs
        // all authoritative defaults from the trusted Polymer identity, so do not serialize them.
        canonicalPatch = new DataComponents(new HashMap<>());
        canonicalPatch.put(DataComponentTypes.ITEM_NAME, Component.text(entry.name()));
        Key model = entry.nativeAppearance()
                ? base.getComponent(ResolvableComponentGetter.EMPTY, DataComponentTypes.ITEM_MODEL)
                : Key.key(entry.canonical());
        canonicalPatch.put(DataComponentTypes.ITEM_MODEL, model == null ? base.javaKey() : model);
        canonicalPatch.put(DataComponentTypes.CUSTOM_DATA, NbtMap.builder().putCompound("$polymer:stack",
                NbtMap.builder().putString("id", entry.canonical()).putInt("count", 1).build()).build());
    }

    @Override
    public GeyserItemStack translateToJava(GeyserSession session, ItemData itemData, ItemMapping mapping, ItemMappings mappings) {
        // These public aliases are catalogue entries only. They never grant survival inventory items.
        if (session.getGameMode() != GameMode.CREATIVE || itemData.getCount() < 1 || itemData.getCount() > entry.maxStack())
            return GeyserItemStack.EMPTY;
        return GeyserItemStack.of(session, baseId, itemData.getCount(), canonicalPatch.clone());
    }

    @Override
    public void translateNbtToJava(GeyserSession session, NbtMap tag, DataComponents components, ItemMapping mapping) {
        // Never let client alias NBT replace the trusted canonical identity or add forged components.
    }
}

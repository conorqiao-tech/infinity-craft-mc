package dev.convergence;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;

/** A bounded, owner-requested scan of completed chunks; never creates terrain or awards discovery. */
final class OdysseyCompass {
    static final int RANGE = 96;
    static final int CHUNK_RADIUS = RANGE / 16 + 2;
    static final String MARKER = "infinity_odyssey_compass";

    static BlockPos nearest(ServerLevel world, BlockPos origin) {
        var ruin = world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();
        BlockPos nearest = null;
        long distance = (long) RANGE * RANGE + 1;
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        // Template starts can sit outside a piece's nearest chunk. Two extra chunks cover its footprint.
        for (int x = cx - CHUNK_RADIUS; x <= cx + CHUNK_RADIUS; x++) {
            for (int z = cz - CHUNK_RADIUS; z <= cz + CHUNK_RADIUS; z++) {
                var chunk = world.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                var start = chunk.getStartForStructure(ruin);
                if (start == null || !start.isValid()) continue;
                for (var piece : start.getPieces()) {
                    if (!(piece instanceof OdysseyStructure.Piece)) continue;
                    var at = piece.getBoundingBox().getCenter();
                    long dx = (long) at.getX() - origin.getX(), dz = (long) at.getZ() - origin.getZ();
                    long squared = dx * dx + dz * dz;
                    if (squared <= (long) RANGE * RANGE && world.getWorldBorder().isWithinBounds(at)
                        && (squared < distance || squared == distance && nearest != null && at.compareTo(nearest) < 0)) {
                        nearest = at; distance = squared;
                    }
                }
            }
        }
        return nearest;
    }

    static boolean owned(ItemStack stack, ServerPlayer player) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return stack.is(Items.COMPASS) && stack.getCount() == 1 && data != null
            && data.copyTag().getStringOr(MARKER, "").equals(player.getStringUUID());
    }

    static int scan(ServerPlayer player) {
        if (!ServerMenu.allowed(player) || player.containerMenu != player.inventoryMenu
            || !player.inventoryMenu.getCarried().isEmpty()) return 0;
        if (OdysseyJournal.realmIndex(player) < 0) {
            CommunityServer.say(player, "Visit the Overworld, Nether or End in Survival or Hardcore to scan for natural Odyssey ruins.");
            return 0;
        }
        var at = nearest(player.level(), player.blockPosition());
        if (at == null) {
            CommunityServer.say(player, "No loaded Odyssey circuit within " + RANGE + " blocks. Explore new terrain, then scan again. Ruins are rare; this scan does not generate chunks.");
            return 0;
        }
        int slot = -1, empty = -1;
        for (int i = 0; i < 36; i++) {
            var stack = player.getInventory().getItem(i);
            if (owned(stack, player)) { slot = i; break; }
            if (empty < 0 && stack.isEmpty()) empty = i;
        }
        var offhand = player.getOffhandItem();
        boolean updateOffhand = slot < 0 && owned(offhand, player);
        if (slot < 0 && !updateOffhand) slot = empty;
        if (slot < 0 && !updateOffhand) {
            CommunityServer.say(player, "Circuit signal at " + at.toShortString() + ". Clear one inventory slot for its compass; no items were replaced.");
            return 0;
        }
        var stack = updateOffhand ? offhand : player.getInventory().getItem(slot);
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.COMPASS);
            var tag = new CompoundTag(); tag.putString(MARKER, player.getStringUUID());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Odyssey Circuit Compass"));
            player.getInventory().setItem(slot, stack);
        }
        // A generated ruin has no lodestone: keep its native dimension-aware pointer without validation against one.
        stack.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(player.level().dimension(), at)), false));
        stack.set(DataComponents.LORE, new ItemLore(java.util.List.of(
            Component.literal("Circuit signal: " + at.toShortString()),
            Component.literal("Explore safely; the pointer does not guarantee a clear path."),
            Component.literal("Enter the original ruin in Survival or Hardcore to record discovery."),
            Component.literal("Rescan from Odyssey Journal to update this compass."))));
        player.inventoryMenu.sendAllDataToRemote();
        player.level().getServer().getPlayerList().saveAll();
        CommunityServer.say(player, "Circuit signal at " + at.toShortString() + ". Your Odyssey compass points there in this dimension.");
        return 1;
    }
}

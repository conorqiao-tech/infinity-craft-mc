package dev.convergence;

import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/** Exercise actual handled screens and packets, rather than just their icon layout. */
public class AgentMenuGameTests {
    private ServerPlayerEntity player(TestContext c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var data = net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);
        var player = new ServerPlayerEntity(c.getWorld().getServer(), c.getWorld(), profile, data.syncedOptions());
        var connection = new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, player, data);
        player.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        operator(player, LeveledPermissionPredicate.OWNERS);
        BlockPos feet = c.getAbsolutePos(new BlockPos(3, 20, 3));
        for (BlockPos at : BlockPos.iterate(feet.add(-8, -1, -8), feet.add(8, 4, 8)))
            c.getWorld().setBlockState(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        player.setPosition(Vec3d.ofBottomCenter(feet));
        player.setNoGravity(true);
        player.changeGameMode(GameMode.SURVIVAL);
        return player;
    }

    private void operator(ServerPlayerEntity player, LeveledPermissionPredicate level) {
        var manager = player.getEntityWorld().getServer().getPlayerManager();
        manager.removeFromOperators(new net.minecraft.server.PlayerConfigEntry(player.getGameProfile()));
        manager.addToOperators(new net.minecraft.server.PlayerConfigEntry(player.getGameProfile()),
            java.util.Optional.of(level), java.util.Optional.of(false));
    }

    private void cleanup(AgentCompanions helpers, ServerPlayerEntity player) {
        try {
            player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            player.closeHandledScreen();
            // Restore permission before cleanup, so direct mutators remain gated too.
            operator(player, LeveledPermissionPredicate.OWNERS);
            var names = helpers.data.agents.values().stream().filter(a -> a.owner().equals(player.getUuidAsString()))
                .map(AgentCompanions.Agent::name).toList();
            for (String name : names) helpers.dismiss(player, name);
            helpers.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(player.getGameProfile()));
            helpers.server.getPlayerManager().remove(player);
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] cleanup failed for " + player.getName().getString());
            failure.printStackTrace();
            throw failure;
        }
    }

    private AgentMenu.Handler menu(ServerPlayerEntity player) { return (AgentMenu.Handler) player.currentScreenHandler; }
    private void click(ServerPlayerEntity player, int slot) {
        player.currentScreenHandler.onSlotClick(slot, 0, SlotActionType.PICKUP, player);
    }
    private void detail(ServerPlayerEntity player, String name) {
        var handler = menu(player);
        var helpers = AgentCompanions.get(player.getEntityWorld().getServer());
        String id = helpers.owned(player, name).getKey();
        int slot = handler.choices.entrySet().stream().filter(entry -> entry.getValue().equals(id))
            .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
        click(player, slot);
    }

    @GameTest public void helperMenuRequiresCurrentOwnerAndRechecksOpFour(TestContext c) {
        var owner = player(c, "menu-owner");
        var other = player(c, "menu-other");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        try {
            operator(owner, LeveledPermissionPredicate.ADMINS);
            c.assertEquals(AgentMenu.open(owner), 0, "OP3 cannot open helper controls");
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "Rejected open leaves the current inventory alone");
            operator(owner, LeveledPermissionPredicate.OWNERS);
            c.assertEquals(AgentMenu.open(owner), 1, "OP4 can open a vanilla helper chest");
            var screen = menu(owner);
            ScreenHandler vanillaScreen = screen;
            c.assertTrue(vanillaScreen.canUse(owner), "Living OP4 owner can use its screen");
            c.assertFalse(vanillaScreen.canUse(other), "Another OP4 UUID cannot use the screen");
            vanillaScreen.onSlotClick(AgentMenu.helperSlot(0), 0, SlotActionType.PICKUP, other);
            c.assertEquals(helpers.count(owner), 0, "A foreign click cannot create a helper for the owner");
            c.assertEquals(helpers.count(other), 0, "A foreign click cannot create a helper for the sender");
            operator(owner, LeveledPermissionPredicate.ADMINS);
            c.assertFalse(vanillaScreen.canUse(owner), "Permission is checked again after the menu opens");
            vanillaScreen.onSlotClick(AgentMenu.helperSlot(0), 0, SlotActionType.PICKUP, owner);
            c.assertEquals(helpers.count(owner), 0, "Losing OP4 blocks an already-open spawn button");
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "A revoked menu closes safely");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] permission recheck failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); cleanup(helpers, other); }
        c.complete();
    }

    @GameTest public void helperMenuCreatesMultipleHelpersAndControlsProfilesAndSquads(TestContext c) {
        var owner = player(c, "menu-squad");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        int proposals = AgentActions.get(helpers.server).data.proposals.size();
        try {
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(0));
            click(owner, AgentMenu.helperSlot(1));
            c.assertEquals(helpers.count(owner), 2, "Two empty slots create two distinct helpers");
            c.assertTrue(helpers.owned(owner, "helper-1") != null && helpers.owned(owner, "helper-2") != null,
                "Numbered names stay unique as the screen refreshes");
            detail(owner, "helper-1");
            ScreenHandler detailScreen = owner.currentScreenHandler;
            for (var profile : AgentCompanions.Profile.values()) {
                click(owner, AgentMenu.profileSlot(profile.ordinal()));
                c.assertTrue(owner.currentScreenHandler == detailScreen, "Profile selection updates the current container without closing it");
                c.assertEquals(helpers.owned(owner, "helper-1").getValue().profile(), profile,
                    "A real menu click selects " + profile.label());
                c.assertTrue(menu(owner).view.getStack(AgentMenu.profileSlot(profile.ordinal())).getName().getString().startsWith("Selected:"),
                    "In-place refresh marks the newly selected profile");
                c.assertEquals(helpers.owned(owner, "helper-2").getValue().profile(), AgentCompanions.Profile.REGULAR,
                    "Selecting one profile leaves the other helper unchanged");
            }
            click(owner, AgentMenu.GUARD);
            c.assertTrue(owner.currentScreenHandler == detailScreen, "Detail movement keeps the same container and sync ID");
            c.assertEquals(helpers.owned(owner, "helper-1").getValue().mode(), AgentCompanions.Mode.GUARD,
                "Detail movement controls the selected helper");
            click(owner, AgentMenu.BACK);
            ScreenHandler rosterScreen = owner.currentScreenHandler;
            click(owner, AgentMenu.STAY);
            c.assertTrue(owner.currentScreenHandler == rosterScreen, "Squad movement updates its current roster in place");
            c.assertTrue(helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getUuidAsString()))
                .allMatch(a -> a.mode() == AgentCompanions.Mode.STAY), "Squad stay pauses every owned loaded helper");
            c.assertEquals(AgentActions.get(helpers.server).data.proposals.size(), proposals,
                "Profile and movement clicks neither propose nor dispatch server commands");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] squad controls failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.complete();
    }

    @GameTest public void helperMenuDismissalConfirmsAndRejectsStaleHelperIdentity(TestContext c) {
        var owner = player(c, "menu-dismiss");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        try {
            helpers.spawn(owner, "keeper");
            AgentMenu.open(owner);
            detail(owner, "keeper");
            click(owner, AgentMenu.DISMISS);
            c.assertEquals(menu(owner).page, AgentMenu.Page.DISMISS, "Dismiss opens a separate confirmation screen");
            c.assertEquals(helpers.count(owner), 1, "Opening confirmation leaves the helper alive");
            click(owner, AgentMenu.CANCEL);
            c.assertEquals(helpers.count(owner), 1, "Cancel preserves the helper");
            click(owner, AgentMenu.DISMISS);
            String original = helpers.owned(owner, "keeper").getKey();
            ScreenHandler oldConfirm = menu(owner);
            helpers.dismiss(owner, "keeper");
            helpers.spawn(owner, "keeper");
            String replacement = helpers.owned(owner, "keeper").getKey();
            c.assertFalse(original.equals(replacement), "Replacement has a different persistent UUID");
            click(owner, AgentMenu.CONFIRM);
            c.assertTrue(helpers.owned(owner, "keeper") != null, "Stale confirmation cannot dismiss a replacement with the same name");
            c.assertEquals(menu(owner).page, AgentMenu.Page.ROSTER, "Stale identity returns to a fresh roster");
            oldConfirm.onSlotClick(AgentMenu.CONFIRM, 0, SlotActionType.PICKUP, owner);
            c.assertTrue(helpers.owned(owner, "keeper") != null, "An old screen packet cannot touch the new screen");
            detail(owner, "keeper");
            click(owner, AgentMenu.DISMISS);
            click(owner, AgentMenu.CONFIRM);
            c.assertEquals(helpers.count(owner), 0, "Confirm dismisses exactly the current selected UUID");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] dismissal confirmation failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.complete();
    }

    @GameTest public void helperMenuRosterSnapshotCannotSelectAReplacement(TestContext c) {
        var owner = player(c, "menu-snapshot");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        try {
            helpers.spawn(owner, "keeper");
            AgentMenu.open(owner);
            var stale = menu(owner);
            String original = helpers.owned(owner, "keeper").getKey();
            int slot = stale.choices.entrySet().stream().filter(entry -> entry.getValue().equals(original))
                .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            helpers.dismiss(owner, "keeper");
            helpers.spawn(owner, "keeper");
            click(owner, slot);
            c.assertEquals(menu(owner).page, AgentMenu.Page.ROSTER, "A stale roster icon refreshes instead of selecting a replacement");
            c.assertTrue(helpers.owned(owner, "keeper") != null, "Refreshing does not alter the replacement");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] roster snapshot failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.complete();
    }

    @GameTest public void helperMenuNeverTransfersIconsOrPlayerItems(TestContext c) {
        var owner = player(c, "menu-inventory");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        try {
            var item = new ItemStack(Items.DIAMOND, 7);
            item.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Keep this gift"));
            owner.getInventory().setStack(0, item);
            owner.getInventory().setStack(1, new ItemStack(Items.EMERALD, 11));
            var before = new ArrayList<ItemStack>();
            for (int slot = 0; slot < 36; slot++) before.add(owner.getInventory().getStack(slot).copy());
            helpers.spawn(owner, "keeper");
            AgentMenu.open(owner);
            detail(owner, "keeper");
            var screen = menu(owner);
            ScreenHandler vanillaScreen = screen;
            var preview = screen.view.getStack(AgentMenu.profileSlot(0)).copy();
            for (var action : SlotActionType.values()) {
                if (action != SlotActionType.PICKUP && action != SlotActionType.QUICK_MOVE)
                    vanillaScreen.onSlotClick(AgentMenu.profileSlot(0), 0, action, owner);
                vanillaScreen.onSlotClick(54, 0, action, owner);
                vanillaScreen.onSlotClick(-999, 0, action, owner);
            }
            vanillaScreen.selectBundleStack(AgentMenu.profileSlot(0), 1);
            c.assertTrue(vanillaScreen.quickMove(owner, AgentMenu.profileSlot(0)).isEmpty(), "Shift-transfer never returns a preview stack");
            c.assertTrue(vanillaScreen.getCursorStack().isEmpty(), "No click action puts an icon on the cursor");
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(preview, screen.view.getStack(AgentMenu.profileSlot(0))),
                "Drag, throw, hotbar swap, and double-click leave the display icon intact");
            // Shift-click is a valid button activation, but still never transfers its icon.
            vanillaScreen.onSlotClick(AgentMenu.profileSlot(0), 0, SlotActionType.QUICK_MOVE, owner);
            c.assertEquals(helpers.owned(owner, "keeper").getValue().profile(), AgentCompanions.Profile.PRIMITIVE,
                "Shift-click activates a profile without taking the sword icon");
            c.assertTrue(owner.currentScreenHandler.getCursorStack().isEmpty(), "Button activation keeps the new screen cursor empty");
            for (int slot = 0; slot < 36; slot++) {
                var after = owner.getInventory().getStack(slot);
                c.assertTrue(ItemStack.areItemsAndComponentsEqual(before.get(slot), after), "Player inventory components unchanged at " + slot);
                c.assertEquals(after.getCount(), before.get(slot).getCount(), "Player inventory count unchanged at " + slot);
            }
            ScreenHandler current = menu(owner);
            current.setCursorStack(new ItemStack(Items.GOLD_INGOT, 2));
            current.onSlotClick(AgentMenu.STAY, 0, SlotActionType.PICKUP, owner);
            c.assertEquals(helpers.owned(owner, "keeper").getValue().mode(), AgentCompanions.Mode.FOLLOW,
                "A nonempty cursor blocks button activation");
            c.assertEquals(current.getCursorStack().getCount(), 2, "Rejected click does not consume the existing cursor");
            current.setCursorStack(ItemStack.EMPTY);
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] inventory isolation failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.complete();
    }

    @GameTest public void helperMenuCeasefireClearsOnlyItsOwnersPlayerOrder(TestContext c) {
        var owner = player(c, "menu-hive");
        var other = player(c, "menu-otherhive");
        var target = player(c, "menu-target");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        try {
            helpers.spawn(owner, "combat");
            helpers.spawn(other, "combat");
            helpers.profile(owner, "combat", AgentCompanions.Profile.PRIMITIVE);
            helpers.profile(other, "combat", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertTrue(helpers.assignPlayerTarget(owner, target), "First owner's exact player order is eligible");
            c.assertTrue(helpers.assignPlayerTarget(other, target), "Second owner's order stays independently scoped");
            AgentMenu.open(owner);
            ScreenHandler screen = owner.currentScreenHandler;
            c.assertTrue(menu(owner).view.getStack(22).getName().getString().contains(target.getGameProfile().name()),
                "Compass shows the approved target name");
            click(owner, AgentMenu.CEASEFIRE);
            c.assertTrue(owner.currentScreenHandler == screen, "Ceasefire refreshes the existing container without close/open packets");
            c.assertTrue(menu(owner).view.getStack(22).getName().getString().contains("none"), "The refreshed compass immediately removes the stopped player target");
            c.assertFalse(helpers.playerTargetStatus(owner).contains(target.getGameProfile().name()),
                "The actual ceasefire menu click clears its owner's order");
            c.assertTrue(helpers.playerTargetStatus(other).contains(target.getGameProfile().name()),
                "Another owner's active order is preserved");
            c.assertTrue(owner.currentScreenHandler.getCursorStack().isEmpty(), "Ceasefire banner never transfers");
        } finally { helpers.ceasefire(owner); helpers.ceasefire(other); cleanup(helpers, owner); cleanup(helpers, other); cleanup(helpers, target); }
        c.complete();
    }
}

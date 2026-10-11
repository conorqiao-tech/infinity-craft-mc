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
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(1));
            c.assertEquals(helpers.count(owner), 2, "Two empty slots create two distinct helpers");
            c.assertTrue(helpers.owned(owner, "helper-1") != null && helpers.owned(owner, "helper-2") != null,
                "Numbered names stay unique as the screen refreshes");
            AgentMenu.open(owner);
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

    @GameTest public void firstHelperButtonRevealsTheNamedGolemWithoutRepeatingItsClick(TestContext c) {
        var owner = player(c, "menu-first");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        int proposals = AgentActions.get(helpers.server).data.proposals.size();
        try {
            AgentMenu.open(owner);
            var firstScreen = menu(owner);
            c.assertEquals(firstScreen.view.getStack(AgentMenu.helperSlot(0)).getName().getString(),
                "Create your first helper", "An empty squad has an obvious first-helper action");
            c.assertEquals(helpers.count(owner), 0, "Opening helper controls never spawns an entity automatically");
            click(owner, AgentMenu.helperSlot(0));
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler,
                "Creating the helper closes its menu so the owner can see the golem and feedback");
            var record = helpers.owned(owner, "helper-1");
            c.assertTrue(record != null, "The first-helper button saves one named helper");
            var golem = helpers.loaded.get(UUID.fromString(record.getKey()));
            c.assertTrue(golem != null && golem.isAlive() && !golem.isInvisible(),
                "The actual helper entity is loaded, alive, and visible");
            c.assertTrue(golem.isCustomNameVisible() && golem.getCustomName().getString().contains("helper-1"),
                "The visible helper name matches the menu feedback");
            c.assertTrue(golem.getEntityWorld() == owner.getEntityWorld() && golem.squaredDistanceTo(owner) <= 32,
                "The helper appears beside its owner in the same world");
            ((ScreenHandler)firstScreen).onSlotClick(AgentMenu.helperSlot(0), 0, SlotActionType.PICKUP, owner);
            c.assertEquals(helpers.count(owner), 1, "A delayed click from the closed menu cannot create a second helper");
            c.assertEquals(AgentActions.get(helpers.server).data.proposals.size(), proposals,
                "Creating a helper does not approve or execute a server action");
        } finally { cleanup(helpers, owner); }
        c.complete();
    }

    @GameTest public void helperInformationClosesTheChestAndRosterReturnsToInfinityMenu(TestContext c) {
        var owner = player(c, "menu-readable-help");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        try {
            helpers.spawn(owner, "guide");
            AgentMenu.open(owner);
            var roster = menu(owner);
            c.assertEquals(roster.view.getStack(AgentMenu.BACK).getName().getString(), "Back to Infinity Menu", "The helper roster has a visible parent-menu route");
            click(owner, AgentMenu.INFO);
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "Roster help closes its chest before showing chat guidance");
            AgentMenu.open(owner); detail(owner, "guide");
            click(owner, AgentMenu.INFO);
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "Helper status closes its chest so its location and health are readable");
            c.assertTrue(helpers.owned(owner, "guide") != null, "Reading status leaves the helper intact");
            AgentMenu.open(owner);
            ScreenHandler oldRoster = owner.currentScreenHandler;
            click(owner, AgentMenu.BACK);
            c.assertTrue(owner.currentScreenHandler instanceof ServerMenu.Handler, "Roster Back opens the actual Infinity Menu");
            var parent = owner.currentScreenHandler;
            oldRoster.onSlotClick(AgentMenu.INFO, 0, SlotActionType.PICKUP, owner);
            c.assertTrue(owner.currentScreenHandler == parent, "Delayed help clicks do not close the newly opened parent menu");
        } finally { cleanup(helpers, owner); }
        c.complete();
    }

    @GameTest public void askHelperButtonClosesForItsAnswerAndKeepsOwnerPermissions(TestContext c) {
        var owner = player(c, "menu-ask-helper");
        var server = c.getWorld().getServer();
        var helpers = AgentCompanions.get(server);
        var originalService = ServerAssistant.AI.remove(server);
        int proposals = AgentActions.get(server).data.proposals.size();
        try {
            helpers.spawn(owner, "guide");
            AgentMenu.open(owner); detail(owner, "guide");
            var first = menu(owner);
            c.assertEquals(first.view.getStack(AgentMenu.ASK).getName().getString(), "Ask helper", "Local fallback is never mislabeled as Codex");
            c.assertEquals(first.view.getStack(AgentMenu.ASK).getItem(), Items.WRITABLE_BOOK, "The ask button uses a vanilla crossplay icon");
            var lore = first.view.getStack(AgentMenu.ASK).get(DataComponentTypes.LORE).lines();
            c.assertTrue(lore.stream().allMatch(line -> line.getString().length() <= 43), "Guidance disclosure wraps into touch-screen-size lines");
            c.assertTrue(String.join(" ", lore.stream().map(Text::getString).toList()).contains("Answers cannot execute commands"), "The menu explains that answers cannot run commands");
            var session = ServerAssistant.SESSIONS.get(server);
            if (session != null) session.readyAt.remove(owner.getUuid());
            operator(owner, LeveledPermissionPredicate.ADMINS);
            click(owner, AgentMenu.ASK);
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "Revoking OP4 closes a previously opened Ask button");
            session = ServerAssistant.SESSIONS.get(server);
            c.assertTrue(session == null || !session.readyAt.containsKey(owner.getUuid()), "Rejected Ask does not dispatch a question");
            operator(owner, LeveledPermissionPredicate.OWNERS);
            AgentMenu.open(owner); detail(owner, "guide");
            ScreenHandler questionScreen = owner.currentScreenHandler;
            click(owner, AgentMenu.ASK);
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "Asking closes the chest before the answer is delivered");
            session = ServerAssistant.SESSIONS.get(server);
            c.assertTrue(session != null && session.readyAt.containsKey(owner.getUuid()), "The actual named-helper question path applies its shared cooldown");
            c.assertEquals(helpers.owned(owner, "guide").getValue().profile(), AgentCompanions.Profile.REGULAR, "Asking does not change the helper profile");
            c.assertEquals(AgentActions.get(server).data.proposals.size(), proposals, "An answer cannot queue or approve a server command");
            session.readyAt.remove(owner.getUuid());
            questionScreen.onSlotClick(AgentMenu.ASK, 0, SlotActionType.PICKUP, owner);
            c.assertFalse(session.readyAt.containsKey(owner.getUuid()), "A delayed click cannot dispatch a second question");
        } finally {
            if (originalService == null) ServerAssistant.AI.remove(server); else ServerAssistant.AI.put(server, originalService);
            var session = ServerAssistant.SESSIONS.get(server);
            if (session != null) session.readyAt.remove(owner.getUuid());
            cleanup(helpers, owner);
        }
        c.complete();
    }

    @GameTest public void blockedHelperSpawnLeavesItsErrorVisibleAndCanBeRetried(TestContext c) {
        var owner = player(c, "menu-blocked");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        BlockPos feet = owner.getBlockPos();
        try {
            // Leave only the owner's platform. Every searched helper position lacks solid ground.
            for (BlockPos floor : BlockPos.iterate(feet.add(-5, -1, -5), feet.add(5, -1, 5)))
                if (!floor.equals(feet.down())) c.getWorld().setBlockState(floor, Blocks.AIR.getDefaultState());
            c.assertTrue(AgentCompanions.spawnPlace(owner) == null, "The test area cannot safely fit a helper");
            var gift = new ItemStack(Items.DIAMOND, 7);
            owner.getInventory().setStack(0, gift.copy());
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(0));
            c.assertEquals(helpers.count(owner), 0, "A blocked spawn creates no ownership record");
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler,
                "The failed spawn leaves its clear-ground chat error unobscured by a reopened menu");
            c.assertTrue(owner.currentScreenHandler.getCursorStack().isEmpty(), "The create icon never enters the cursor");
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(gift, owner.getInventory().getStack(0))
                && owner.getInventory().getStack(0).getCount() == gift.getCount(), "A failed spawn preserves the owner's items");
            for (BlockPos floor : BlockPos.iterate(feet.add(-5, -1, -5), feet.add(5, -1, 5)))
                c.getWorld().setBlockState(floor, Blocks.STONE.getDefaultState());
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(0));
            c.assertEquals(helpers.count(owner), 1, "The owner can reopen and retry after moving to suitable ground");
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "A successful retry reveals the golem");
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

    @GameTest public void bringHereMenuPreservesHelperClearsCombatAndRejectsOldClicks(TestContext c) {
        var owner = player(c, "menu-recall");
        var target = player(c, "menu-recall-target");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        var actions = AgentActions.get(helpers.server);
        try {
            helpers.spawn(owner, "returning");
            helpers.profile(owner, "returning", AgentCompanions.Profile.PRIMITIVE);
            var id = UUID.fromString(helpers.owned(owner, "returning").getKey());
            var helper = helpers.loaded.get(id);
            helper.setPosition(owner.getEntityPos().add(6, 0, 0));
            helper.setHealth(73);
            c.assertTrue(helpers.assignPlayerTarget(owner, target), "An existing player combat order is active before recall");
            actions.target(owner, target);
            var proposal = actions.data.proposals.values().stream()
                .filter(value -> value.owner.equals(owner.getUuidAsString()) && value.action == AgentActions.Action.TARGET && value.active()).findFirst().orElse(null);
            c.assertTrue(proposal != null, "A real pending target proposal exists before recall; eligibility="
                + helpers.targetEligibility(owner, target) + "; queueFull=" + actions.queueFull(owner));
            c.assertEquals(AgentMenu.open(owner), 1, "Owner can open its recall roster");
            detail(owner, "returning");
            var screen = menu(owner);
            c.assertTrue(screen.view.getStack(AgentMenu.RECALL).getName().getString().startsWith("Bring here"), "Loaded helper has a visible manual recall button");
            var original = helper.getEntityPos();
            ((ScreenHandler) screen).onSlotClick(AgentMenu.RECALL, 0, SlotActionType.PICKUP, target);
            c.assertEquals(helper.getEntityPos(), original, "Another player cannot activate the owner's recall menu");
            click(owner, AgentMenu.RECALL);
            c.assertTrue(owner.currentScreenHandler == owner.playerScreenHandler, "Recall closes the menu so the owner can see the returned helper");
            c.assertTrue(helpers.loaded.get(id) == helper && helper.squaredDistanceTo(owner) < 36, "Same-world recall keeps the existing helper and moves it beside its owner");
            c.assertEquals(helper.getHealth(), 73f, "Menu recall preserves current health");
            c.assertTrue(!helpers.playerTargets.containsKey(owner.getUuid()), "Recall clears the owner's old approved player target");
            c.assertEquals(proposal.state, AgentActions.State.CANCELLED, "Recall also cancels pending target approvals");
            c.assertTrue(helper.getTarget() == null && helper.getNavigation().isIdle(), "Old combat and path targets are stopped");
            var recalledPosition = helper.getEntityPos();
            ((ScreenHandler) screen).onSlotClick(AgentMenu.RECALL, 0, SlotActionType.PICKUP, owner);
            c.assertEquals(helper.getEntityPos(), recalledPosition, "Delayed old-window recall packet cannot trigger another transfer");
            c.assertEquals(helpers.count(owner), 1, "Recall does not replace or duplicate roster members");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] bring-here workflow failed");
            failure.printStackTrace();
            throw failure;
        } finally {
            actions.ceasefire(owner);
            cleanup(helpers, owner); cleanup(helpers, target);
        }
        c.complete();
    }

    @GameTest public void orderMenuQueuesReviewsAndApprovesWithoutRunningItsAction(TestContext c) {
        var owner=player(c,"menu-orders");var helpers=AgentCompanions.get(c.getWorld().getServer());
        var queue=AgentActions.get(c.getWorld().getServer());
        try {
            AgentMenu.open(owner);click(owner,AgentMenu.ORDERS);
            c.assertTrue(owner.currentScreenHandler instanceof AgentOrdersMenu.Handler,"Roster opens command-free order controls");
            click(owner,19);
            var pending=(AgentOrdersMenu.Handler)owner.currentScreenHandler;
            c.assertEquals(pending.page,AgentOrdersMenu.Page.PENDING,"Proposing day opens pending orders");
            String id=pending.proposals.values().iterator().next();
            c.assertEquals(queue.data.proposals.get(id).state,AgentActions.State.PENDING,"A tap cannot approve its own proposal");
            click(owner,0);
            c.assertEquals(((AgentOrdersMenu.Handler)owner.currentScreenHandler).page,AgentOrdersMenu.Page.REVIEW,"Exact action has a separate review");
            ScreenHandler old=owner.currentScreenHandler;click(owner,AgentOrdersMenu.APPROVE);
            c.assertEquals(queue.data.proposals.get(id).state,AgentActions.State.OWNER_APPROVED,"Confirm provides only the owner approval");
            old.onSlotClick(AgentOrdersMenu.APPROVE,0,SlotActionType.QUICK_MOVE,owner);
            c.assertEquals(queue.data.proposals.get(id).state,AgentActions.State.OWNER_APPROVED,"Old clicks never supply Codex approval or execute");
            click(owner,0);click(owner,AgentOrdersMenu.CANCEL);
            c.assertEquals(queue.data.proposals.get(id).state,AgentActions.State.CANCELLED,"Owner can cancel from the review screen");
        } finally { for(var e:java.util.List.copyOf(queue.data.proposals.entrySet()))if(e.getValue().owner.equals(owner.getUuidAsString())&&e.getValue().active())queue.cancel(owner,e.getKey());cleanup(helpers,owner); }
        c.complete();
    }

    @GameTest public void orderMenuTargetSelectionCapturesSessionAndKeepsBothApprovals(TestContext c) {
        var owner=player(c,"menu-target-order");var target=player(c,"menu-target-other");var helpers=AgentCompanions.get(c.getWorld().getServer());
        var queue=AgentActions.get(c.getWorld().getServer());
        try {
            target.setPosition(new Vec3d(owner.getX()+3,owner.getY(),owner.getZ()));
            helpers.spawn(owner,"attacker");helpers.profile(owner,"attacker",AgentCompanions.Profile.PRIMITIVE);
            AgentOrdersMenu.open(owner);click(owner,AgentOrdersMenu.TARGET);
            var menu=(AgentOrdersMenu.Handler)owner.currentScreenHandler;
            int slot=menu.targets.entrySet().stream().filter(e->e.getValue().player()==target).mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            click(owner,slot);
            var pending=(AgentOrdersMenu.Handler)owner.currentScreenHandler;
            String id=pending.proposals.values().iterator().next();
            c.assertEquals(queue.data.proposals.get(id).targetUuid,target.getUuidAsString(),"Menu captured the exact target");
            c.assertTrue(helpers.playerTargets.get(owner.getUuid())==null,"Proposing the target does not start combat");
            click(owner,0);click(owner,AgentOrdersMenu.APPROVE);
            c.assertEquals(queue.data.proposals.get(id).state,AgentActions.State.OWNER_APPROVED,"Owner confirmation alone still waits");
            c.assertTrue(helpers.playerTargets.get(owner.getUuid())==null,"Menu cannot bypass Codex's separate approval");
            operator(owner,LeveledPermissionPredicate.ADMINS);
            click(owner,0);
            c.assertTrue(owner.currentScreenHandler==owner.playerScreenHandler,"Deop closes the order menu immediately");
        } finally { operator(owner,LeveledPermissionPredicate.OWNERS);queue.ceasefire(owner);cleanup(helpers,owner);cleanup(helpers,target); }
        c.complete();
    }

}

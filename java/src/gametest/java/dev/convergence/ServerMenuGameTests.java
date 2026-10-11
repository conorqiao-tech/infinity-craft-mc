package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;

/** Native screens exercise actual inventory state, permissions and crossplay-safe actions. */
public class ServerMenuGameTests {
    private ServerPlayerEntity player(TestContext context, String name) {
        var player = new ModeGameTests().player(context, name);
        player.changeGameMode(GameMode.SURVIVAL);
        return player;
    }
    private void cleanup(ServerPlayerEntity player) {
        GameModes.PENDING.remove(player.getUuid());
        GameModes.TRANSITIONS.remove(player.getUuid());
        player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        player.closeHandledScreen();
        OperatorGameTests.deop(player);
        player.getEntityWorld().getServer().getPlayerManager().remove(player);
    }
    private void click(ServerPlayerEntity player, int slot) {
        player.currentScreenHandler.onSlotClick(slot, 0, SlotActionType.PICKUP, player);
    }
    private ServerMenu.Handler menu(ServerPlayerEntity player) { return (ServerMenu.Handler)player.currentScreenHandler; }
    private ItemStack sword() { return new ItemStack(Convergence.ITEMS.get("convergence:sword")); }
    private long navigatorCount(ServerPlayerEntity player) {
        return player.getInventory().getMainStacks().stream().filter(ServerMenu::isNavigator).mapToLong(ItemStack::getCount).sum();
    }

    @GameTest public void menuCommandIsRegisteredAndRecoversAccessWithFullInventory(TestContext c) {
        var p = player(c, "menu-command");
        try {
            var dispatcher = c.getWorld().getServer().getCommandManager().getDispatcher();
            c.assertTrue(dispatcher.getRoot().getChild("menu") != null, "Advertised recovery command is registered");
            for (int slot = 0; slot < 36; slot++) p.getInventory().setStack(slot, new ItemStack(Items.DIAMOND, 64));
            c.assertEquals(dispatcher.execute("menu", p.getCommandSource()), 1, "Ordinary player can open /menu without an empty inventory slot");
            c.assertEquals(menu(p).page, ServerMenu.Page.MAIN, "Recovery command opens the real main screen");
            c.assertEquals(navigatorCount(p), 0L, "The fallback does not force a control into full inventory");
            for (int slot = 0; slot < 36; slot++) c.assertEquals(p.getInventory().getStack(slot).getCount(), 64, "Full inventory preserved by the command");
            click(p, ServerMenu.BACK);
            p.changeGameMode(GameMode.SPECTATOR);
            c.assertEquals(dispatcher.execute("menu", p.getCommandSource()), 0, "Command uses the same spectator restriction");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new RuntimeException(failure);
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void navigatorIdentityAndRecoveryNeverOverwriteFullInventory(TestContext c) {
        var p = player(c, "navigator-space");
        try {
            var fake = new ItemStack(Items.RECOVERY_COMPASS);
            fake.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Infinity Menu"));
            c.assertFalse(ServerMenu.isNavigator(fake), "A renamed ordinary compass is not a server control");
            for (int slot = 0; slot < 36; slot++) p.getInventory().setStack(slot, new ItemStack(Items.DIAMOND, 64));
            c.assertFalse(ServerMenu.ensureNavigator(p), "Full inventory refuses replacement");
            for (int slot = 0; slot < 36; slot++) c.assertEquals(p.getInventory().getStack(slot).getCount(), 64, "Original stack preserved");
            p.getInventory().setStack(8, ItemStack.EMPTY);
            c.assertTrue(ServerMenu.ensureNavigator(p), "Cleared hotbar slot receives the menu");
            c.assertTrue(ServerMenu.isNavigator(p.getInventory().getStack(8)), "The control has a real marker");
            c.assertEquals(p.getInventory().getStack(8).getItem(), Items.RECOVERY_COMPASS, "A vanilla icon is visible to Bedrock");
            c.assertTrue(ServerMenu.ensureNavigator(p), "Existing control is reused");
            c.assertEquals(navigatorCount(p), 1L, "Repeated recovery creates no inventory duplicates");
            p.getInventory().setStack(8, ItemStack.EMPTY);
            GameModes.TRANSITIONS.add(p.getUuid());
            c.assertFalse(ServerMenu.ensureNavigator(p), "No control is inserted during a profile transition");
            GameModes.TRANSITIONS.remove(p.getUuid());
            p.playerScreenHandler.setCursorStack(new ItemStack(Items.EMERALD, 2));
            c.assertFalse(ServerMenu.ensureNavigator(p), "Cursor items are never disturbed for a replacement");
            c.assertEquals(p.playerScreenHandler.getCursorStack().getCount(), 2, "Cursor count preserved");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void serverMenuPreviewCannotGrantGearOrMoveIconsToSurvivalInventory(TestContext c) {
        var p = player(c, "menu-no-gear");
        var other = player(c, "menu-other-user");
        try {
            p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIAMOND, 7));
            c.assertEquals(ServerMenu.open(p), 1, "Ordinary Survival can discover every feature");
            var main = menu(p);
            ItemStack helperIcon = ((ScreenHandler)main).getSlot(ServerMenu.HELPERS).getStack().copy();
            c.assertEquals(helperIcon.getItem(), Items.IRON_GOLEM_SPAWN_EGG, "Helpers have a clearly visible vanilla golem icon");
            ((ScreenHandler)main).onSlotClick(ServerMenu.HELPERS, 0, SlotActionType.PICKUP, other);
            c.assertTrue(p.currentScreenHandler == main, "A foreign player cannot act through another screen");
            var originalConnection = p.networkHandler;
            try {
                p.networkHandler = other.networkHandler;
                c.assertFalse(((ScreenHandler)main).canUse(p), "A replacement connection cannot reuse the old screen");
                ((ScreenHandler)main).onSlotClick(ServerMenu.BACK, 0, SlotActionType.PICKUP, p);
                c.assertTrue(p.currentScreenHandler == main, "A stale connection cannot even close the current screen");
            } finally { p.networkHandler = originalConnection; }
            for (var action : new SlotActionType[] { SlotActionType.SWAP, SlotActionType.CLONE,
                    SlotActionType.THROW, SlotActionType.QUICK_CRAFT, SlotActionType.PICKUP_ALL }) {
                ((ScreenHandler)main).onSlotClick(ServerMenu.HELPERS, 0, action, p);
                c.assertTrue(ItemStack.areItemsAndComponentsEqual(((ScreenHandler)main).getSlot(ServerMenu.HELPERS).getStack(), helperIcon), "Preview icon survives " + action);
            }
            ((ScreenHandler)main).selectBundleStack(ServerMenu.HELPERS, 0);
            c.assertTrue(((ScreenHandler)main).quickMove(p, ServerMenu.HELPERS).isEmpty(), "Shift-move returns no item");
            c.assertTrue(((ScreenHandler)main).getCursorStack().isEmpty(), "No icon reaches the cursor");
            ((ScreenHandler)main).onSlotClick(ServerMenu.KIT,0,SlotActionType.QUICK_MOVE,p);
            click(p, ServerMenu.BUILDING);
            click(p, ServerMenu.HELPERS);
            c.assertTrue(p.currentScreenHandler == main, "Locked actions do not change screens or grant control");
            c.assertTrue(((ScreenHandler)main).getSlot(ServerMenu.KIT).getStack().getName().getString().startsWith("Locked • "), "Kit refusal is visible in the open menu");
            c.assertTrue(((ScreenHandler)main).getSlot(ServerMenu.HELPERS).getStack().getName().getString().startsWith("Locked • "), "Helper refusal is visible without reading hidden chat");
            c.assertTrue(((ScreenHandler)main).getSlot(ServerMenu.HELPERS).getStack().get(DataComponentTypes.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("OP level 4 required")), "The visible helper refusal explains the exact required permission");
            click(p, ServerMenu.GEAR);
            var gear = menu(p);
            int sword = gear.paths.indexOf("convergence:sword");
            while (gear.pageIndex < sword / ServerMenu.PAGE_SIZE) { click(p, ServerMenu.NEXT); gear = menu(p); }
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertEquals(p.getMainHandStack().getItem(), Items.DIAMOND, "Locked catalog does not conjure a sword");
            c.assertEquals(p.getMainHandStack().getCount(), 7, "Original Survival item count is unchanged");
            c.assertTrue(p.currentScreenHandler == gear, "Catalog remains available for browsing");
            c.assertTrue(((ScreenHandler)gear).getSlot(sword % ServerMenu.PAGE_SIZE).getStack().getName().getString().startsWith("Locked • "), "Denied catalog item carries its refusal in the menu");
        } finally { cleanup(p); cleanup(other); }
        c.complete();
    }

    @GameTest public void gearMenuRechecksPermissionsAndPreservesExistingEquipment(TestContext c) {
        var p = player(c, "menu-op2-gear");
        try {
            OperatorGameTests.level(p, LeveledPermissionPredicate.GAMEMASTERS);
            var old = new ItemStack(Items.DIAMOND_PICKAXE);
            old.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Keep this pickaxe"));
            p.setStackInHand(Hand.MAIN_HAND, old);
            ServerMenu.open(p); click(p, ServerMenu.GEAR);
            var gear = menu(p);
            int sword = gear.paths.indexOf("convergence:sword");
            while (gear.pageIndex < sword / ServerMenu.PAGE_SIZE) { click(p, ServerMenu.NEXT); gear = menu(p); }
            OperatorGameTests.deop(p);
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(p.getMainHandStack(), old), "Revoking OP2 before click prevents item grant");
            OperatorGameTests.level(p, LeveledPermissionPredicate.GAMEMASTERS);
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertEquals(p.getMainHandStack().getItem(), Convergence.ITEMS.get("convergence:sword"), "OP2 can choose gear outside Creative");
            c.assertTrue(p.getInventory().contains(old), "Prior named equipment stays in inventory");
            c.assertTrue(p.currentScreenHandler == p.playerScreenHandler, "Successful selection returns to the world");
            ItemStack equipped = p.getMainHandStack().copy();
            ServerMenu.open(p);
            ScreenHandler newScreen = p.currentScreenHandler;
            ((ScreenHandler)gear).onSlotClick(sword % ServerMenu.PAGE_SIZE, 0, SlotActionType.PICKUP, p);
            c.assertTrue(p.currentScreenHandler == newScreen, "A stale gear handler cannot close a new menu");
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(p.getMainHandStack(), equipped), "Stale clicks cannot grant another item");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void navigatorSelectionRestoresToolForAlternatePowerAndOffhandSwap(TestContext c) {
        var p = player(c, "menu-touch-power");
        p.getInventory().setStack(2, sword());
        p.getInventory().setSelectedSlot(2);
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
        ServerMenu.ensureNavigator(p);
        // JOIN defers automatic controls until the player's inventory has finished restoring.
        c.waitAndRun(6, () -> {
            try {
                ServerMenu.tickPlayer(p);
                p.getInventory().setSelectedSlot(8);
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.currentScreenHandler instanceof ServerMenu.Handler, "Selecting the navigator opens a native menu without typing");
                c.assertEquals(p.getInventory().getSelectedSlot(), 2, "The real tool slot is selected before actions");
                c.assertEquals(p.getMainHandStack().getItem(), Convergence.ITEMS.get("convergence:sword"), "The navigator is not the action target");
                int mode = Convergence.state(p).swordMode;
                click(p, ServerMenu.ALTERNATE);
                c.assertEquals(Convergence.state(p).swordMode, (mode + 1) % 3, "Menu alternate power executes the sword's actual cycle");
                c.assertFalse(p.isSneaking(), "Temporary alternate-power sneak state is restored");
                c.assertTrue(ServerMenu.isNavigator(p.getInventory().getStack(8)), "The control stays available");
                ServerMenu.open(p);
                var oldScreen = menu(p);
                click(p, ServerMenu.SWAP);
                c.assertEquals(p.getMainHandStack().getItem(), Items.SHIELD, "Real offhand moves to main hand");
                c.assertEquals(p.getOffHandStack().getItem(), Convergence.ITEMS.get("convergence:sword"), "Real weapon moves to offhand");
                ((ScreenHandler)oldScreen).onSlotClick(ServerMenu.SWAP, 0, SlotActionType.PICKUP, p);
                c.assertEquals(p.getMainHandStack().getItem(), Items.SHIELD, "A duplicate stale click cannot swap twice");
            } finally { cleanup(p); }
            c.complete();
        });
    }

    @GameTest public void allControlHotbarDoesNotTrapPlayerInReopeningMenu(TestContext c) {
        var p = player(c, "menu-all-controls");
        for (int slot = 0; slot < 9; slot++) p.getInventory().setStack(slot, ServerMenu.navigator(p));
        p.getInventory().setSelectedSlot(0);
        c.waitAndRun(6, () -> {
            try {
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.currentScreenHandler instanceof ServerMenu.Handler, "Selecting a control opens even when every hotbar slot is a control");
                click(p, ServerMenu.BACK);
                for (int tick = 0; tick < 4; tick++) ServerMenu.tickPlayer(p);
                c.assertTrue(p.currentScreenHandler == p.playerScreenHandler, "Closing a held control's screen stays closed");
                p.getInventory().setSelectedSlot(1);
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.currentScreenHandler instanceof ServerMenu.Handler, "Selecting a different control is an intentional new opening");
                click(p, ServerMenu.BACK);
                c.assertEquals(ServerMenu.open(p), 1, "An explicit use or recovery command can reopen the same held control");
                click(p, ServerMenu.BACK);
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.currentScreenHandler == p.playerScreenHandler, "Explicit reopening does not restart a loop");
            } finally { cleanup(p); }
            c.complete();
        });
    }

    @GameTest public void menuRejectsChangedEquipmentAndCursorWithoutItemLoss(TestContext c) {
        var p = player(c, "menu-stale-tool");
        try {
            p.setStackInHand(Hand.MAIN_HAND, sword());
            p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
            ServerMenu.open(p);
            p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.DIAMOND, 9));
            click(p, ServerMenu.SWAP);
            c.assertEquals(p.getMainHandStack().getItem(), Items.DIAMOND, "Changed equipment cannot be swapped by an older menu");
            c.assertEquals(p.getMainHandStack().getCount(), 9, "Changed stack count stays intact");
            c.assertEquals(p.getOffHandStack().getItem(), Items.SHIELD, "Offhand stays intact");
            ServerMenu.open(p);
            var screen = menu(p);
            ((ScreenHandler)screen).setCursorStack(new ItemStack(Items.EMERALD, 3));
            click(p, ServerMenu.SWAP);
            c.assertEquals(((ScreenHandler)screen).getCursorStack().getCount(), 3, "Cursor is not consumed by menu actions");
            c.assertEquals(p.getMainHandStack().getItem(), Items.DIAMOND, "Cursor blocks the action");
            ((ScreenHandler)screen).setCursorStack(ItemStack.EMPTY);
            GameModes.TRANSITIONS.add(p.getUuid());
            click(p, ServerMenu.SWAP);
            c.assertTrue(p.currentScreenHandler == p.playerScreenHandler, "Transition invalidates the old screen");
            c.assertEquals(p.getOffHandStack().getItem(), Items.SHIELD, "Transition cannot leak old inventory actions");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void navigatorOpensHelperRosterWithoutBypassingOpFour(TestContext c) {
        var p = player(c, "menu-helper-link");
        try {
            OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
            ServerMenu.open(p);
            c.assertEquals(((ScreenHandler)menu(p)).getSlot(ServerMenu.HELPERS).getStack().getItem(), Items.IRON_GOLEM_SPAWN_EGG, "Squad entry is a visible golem icon");
            OperatorGameTests.deop(p);
            click(p, ServerMenu.HELPERS);
            c.assertTrue(p.currentScreenHandler instanceof ServerMenu.Handler, "Revoked OP4 cannot reach helper controls");
            OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
            click(p, ServerMenu.HELPERS);
            c.assertTrue(p.currentScreenHandler instanceof AgentMenu.Handler, "Current OP4 opens the existing native helper roster");
            c.assertEquals(((AgentMenu.Handler)p.currentScreenHandler).page, AgentMenu.Page.ROSTER, "Roster exposes the existing Create a helper choices");
            c.assertEquals(AgentCompanions.get(c.getWorld().getServer()).count(p), 0, "Opening the link alone does not create helpers or execute proposals");
            p.closeHandledScreen();
            ServerMenu.open(p); click(p, ServerMenu.HELP);
            c.assertEquals(menu(p).page, ServerMenu.Page.HELP, "Controls are readable without a command");
            c.assertTrue(((ScreenHandler)menu(p)).getSlot(0).getStack().contains(DataComponentTypes.LORE), "Help text is shown on readable cards");
            click(p, ServerMenu.BACK); click(p, ServerMenu.INFO);
            c.assertEquals(menu(p).page, ServerMenu.Page.INFO, "Joining information has a menu page");
            click(p, ServerMenu.BACK);
            c.assertEquals(menu(p).page, ServerMenu.Page.MAIN, "Information pages return to the main menu");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void droppingNavigatorDiscardsOnlyMarkedControlItems(TestContext c) {
        var p = player(c, "menu-dropped");
        ItemEntity control = null, ordinary = null;
        try {
            control = new ItemEntity(p.getEntityWorld(), p.getX(), p.getY() + 1, p.getZ(), ServerMenu.navigator(p));
            p.getEntityWorld().spawnEntity(control);
            c.assertTrue(control.isRemoved(), "A discarded menu cannot accumulate as a world item");
            ordinary = new ItemEntity(p.getEntityWorld(), p.getX(), p.getY() + 1, p.getZ(), new ItemStack(Items.RECOVERY_COMPASS));
            c.assertTrue(p.getEntityWorld().spawnEntity(ordinary), "An ordinary recovery compass still spawns");
            c.assertFalse(ordinary.isRemoved(), "Player-crafted ordinary compasses are untouched");
        } finally {
            if (control != null) control.discard();
            if (ordinary != null) ordinary.discard();
            cleanup(p);
        }
        c.complete();
    }
}

package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Native screens exercise actual inventory state, permissions and crossplay-safe actions. */
public class ServerMenuGameTests {
    private ServerPlayer player(GameTestHelper context, String name) {
        var player = new ModeGameTests().player(context, name);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
    private void cleanup(ServerPlayer player) {
        GameModes.PENDING.remove(player.getUUID());
        GameModes.TRANSITIONS.remove(player.getUUID());
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        OperatorGameTests.deop(player);
        player.level().getServer().getPlayerList().remove(player);
    }
    private void click(ServerPlayer player, int slot) {
        player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }
    private ServerMenu.Handler menu(ServerPlayer player) { return (ServerMenu.Handler)player.containerMenu; }
    private ItemStack sword() { return new ItemStack(Convergence.ITEMS.get("convergence:sword")); }
    private long navigatorCount(ServerPlayer player) {
        return player.getInventory().getNonEquipmentItems().stream().filter(ServerMenu::isNavigator).mapToLong(ItemStack::getCount).sum();
    }

    @GameTest public void menuCommandIsRegisteredAndRecoversAccessWithFullInventory(GameTestHelper c) {
        var p = player(c, "menu-command");
        try {
            var dispatcher = c.getLevel().getServer().getCommands().getDispatcher();
            c.assertTrue(dispatcher.getRoot().getChild("menu") != null, "Advertised recovery command is registered");
            for (int slot = 0; slot < 36; slot++) p.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
            c.assertValueEqual(dispatcher.execute("menu", p.createCommandSourceStack()), 1, "Ordinary player can open /menu without an empty inventory slot");
            c.assertValueEqual(menu(p).page, ServerMenu.Page.MAIN, "Recovery command opens the real main screen");
            c.assertValueEqual(navigatorCount(p), 0L, "The fallback does not force a control into full inventory");
            for (int slot = 0; slot < 36; slot++) c.assertValueEqual(p.getInventory().getItem(slot).getCount(), 64, "Full inventory preserved by the command");
            click(p, ServerMenu.BACK);
            p.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(dispatcher.execute("menu", p.createCommandSourceStack()), 0, "Command uses the same spectator restriction");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new RuntimeException(failure);
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void navigatorIdentityAndRecoveryNeverOverwriteFullInventory(GameTestHelper c) {
        var p = player(c, "navigator-space");
        try {
            var fake = new ItemStack(Items.RECOVERY_COMPASS);
            fake.set(DataComponents.CUSTOM_NAME, Component.literal("Infinity Menu"));
            c.assertFalse(ServerMenu.isNavigator(fake), "A renamed ordinary compass is not a server control");
            for (int slot = 0; slot < 36; slot++) p.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 64));
            c.assertFalse(ServerMenu.ensureNavigator(p), "Full inventory refuses replacement");
            for (int slot = 0; slot < 36; slot++) c.assertValueEqual(p.getInventory().getItem(slot).getCount(), 64, "Original stack preserved");
            p.getInventory().setItem(8, ItemStack.EMPTY);
            c.assertTrue(ServerMenu.ensureNavigator(p), "Cleared hotbar slot receives the menu");
            c.assertTrue(ServerMenu.isNavigator(p.getInventory().getItem(8)), "The control has a real marker");
            c.assertValueEqual(p.getInventory().getItem(8).getItem(), Items.RECOVERY_COMPASS, "A vanilla icon is visible to Bedrock");
            c.assertTrue(ServerMenu.ensureNavigator(p), "Existing control is reused");
            c.assertValueEqual(navigatorCount(p), 1L, "Repeated recovery creates no inventory duplicates");
            p.getInventory().setItem(8, ItemStack.EMPTY);
            GameModes.TRANSITIONS.add(p.getUUID());
            c.assertFalse(ServerMenu.ensureNavigator(p), "No control is inserted during a profile transition");
            GameModes.TRANSITIONS.remove(p.getUUID());
            p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 2));
            c.assertFalse(ServerMenu.ensureNavigator(p), "Cursor items are never disturbed for a replacement");
            c.assertValueEqual(p.inventoryMenu.getCarried().getCount(), 2, "Cursor count preserved");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void serverMenuPreviewCannotGrantGearOrMoveIconsToSurvivalInventory(GameTestHelper c) {
        var p = player(c, "menu-no-gear");
        var other = player(c, "menu-other-user");
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 7));
            c.assertValueEqual(ServerMenu.open(p), 1, "Ordinary Survival can discover every feature");
            var main = menu(p);
            ItemStack helperIcon = ((AbstractContainerMenu)main).getSlot(ServerMenu.HELPERS).getItem().copy();
            c.assertValueEqual(helperIcon.getItem(), Items.IRON_GOLEM_SPAWN_EGG, "Helpers have a clearly visible vanilla golem icon");
            ((AbstractContainerMenu)main).clicked(ServerMenu.HELPERS, 0, ContainerInput.PICKUP, other);
            c.assertTrue(p.containerMenu == main, "A foreign player cannot act through another screen");
            var originalConnection = p.connection;
            try {
                p.connection = other.connection;
                c.assertFalse(((AbstractContainerMenu)main).stillValid(p), "A replacement connection cannot reuse the old screen");
                ((AbstractContainerMenu)main).clicked(ServerMenu.BACK, 0, ContainerInput.PICKUP, p);
                c.assertTrue(p.containerMenu == main, "A stale connection cannot even close the current screen");
            } finally { p.connection = originalConnection; }
            for (var action : new ContainerInput[] { ContainerInput.SWAP, ContainerInput.CLONE,
                    ContainerInput.THROW, ContainerInput.QUICK_CRAFT, ContainerInput.PICKUP_ALL }) {
                ((AbstractContainerMenu)main).clicked(ServerMenu.HELPERS, 0, action, p);
                c.assertTrue(ItemStack.isSameItemSameComponents(((AbstractContainerMenu)main).getSlot(ServerMenu.HELPERS).getItem(), helperIcon), "Preview icon survives " + action);
            }
            ((AbstractContainerMenu)main).setSelectedBundleItemIndex(ServerMenu.HELPERS, 0);
            c.assertTrue(((AbstractContainerMenu)main).quickMoveStack(p, ServerMenu.HELPERS).isEmpty(), "Shift-move returns no item");
            c.assertTrue(((AbstractContainerMenu)main).getCarried().isEmpty(), "No icon reaches the cursor");
            ((AbstractContainerMenu)main).clicked(ServerMenu.KIT,0,ContainerInput.QUICK_MOVE,p);
            click(p, ServerMenu.BUILDING);
            click(p, ServerMenu.HELPERS);
            c.assertTrue(p.containerMenu == main, "Locked actions do not change screens or grant control");
            c.assertTrue(((AbstractContainerMenu)main).getSlot(ServerMenu.KIT).getItem().getHoverName().getString().startsWith("Locked • "), "Kit refusal is visible in the open menu");
            c.assertTrue(((AbstractContainerMenu)main).getSlot(ServerMenu.HELPERS).getItem().getHoverName().getString().startsWith("Locked • "), "Helper refusal is visible without reading hidden chat");
            c.assertTrue(((AbstractContainerMenu)main).getSlot(ServerMenu.HELPERS).getItem().get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("OP level 4 required")), "The visible helper refusal explains the exact required permission");
            click(p, ServerMenu.GEAR);
            var gear = menu(p);
            int sword = gear.paths.indexOf("convergence:sword");
            while (gear.pageIndex < sword / ServerMenu.PAGE_SIZE) { click(p, ServerMenu.NEXT); gear = menu(p); }
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertValueEqual(p.getMainHandItem().getItem(), Items.DIAMOND, "Locked catalog does not conjure a sword");
            c.assertValueEqual(p.getMainHandItem().getCount(), 7, "Original Survival item count is unchanged");
            c.assertTrue(p.containerMenu == gear, "Catalog remains available for browsing");
            c.assertTrue(((AbstractContainerMenu)gear).getSlot(sword % ServerMenu.PAGE_SIZE).getItem().getHoverName().getString().startsWith("Locked • "), "Denied catalog item carries its refusal in the menu");
        } finally { cleanup(p); cleanup(other); }
        c.succeed();
    }

    @GameTest public void gearMenuRechecksPermissionsAndPreservesExistingEquipment(GameTestHelper c) {
        var p = player(c, "menu-op2-gear");
        try {
            OperatorGameTests.level(p, LevelBasedPermissionSet.GAMEMASTER);
            var old = new ItemStack(Items.DIAMOND_PICKAXE);
            old.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this pickaxe"));
            p.setItemInHand(InteractionHand.MAIN_HAND, old);
            ServerMenu.open(p); click(p, ServerMenu.GEAR);
            var gear = menu(p);
            int sword = gear.paths.indexOf("convergence:sword");
            while (gear.pageIndex < sword / ServerMenu.PAGE_SIZE) { click(p, ServerMenu.NEXT); gear = menu(p); }
            OperatorGameTests.deop(p);
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getMainHandItem(), old), "Revoking OP2 before click prevents item grant");
            OperatorGameTests.level(p, LevelBasedPermissionSet.GAMEMASTER);
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertValueEqual(p.getMainHandItem().getItem(), Convergence.ITEMS.get("convergence:sword"), "OP2 can choose gear outside Creative");
            c.assertTrue(p.getInventory().contains(old), "Prior named equipment stays in inventory");
            c.assertTrue(p.containerMenu == p.inventoryMenu, "Successful selection returns to the world");
            ItemStack equipped = p.getMainHandItem().copy();
            ServerMenu.open(p);
            AbstractContainerMenu newScreen = p.containerMenu;
            ((AbstractContainerMenu)gear).clicked(sword % ServerMenu.PAGE_SIZE, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu == newScreen, "A stale gear handler cannot close a new menu");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getMainHandItem(), equipped), "Stale clicks cannot grant another item");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void navigatorSelectionRestoresToolForAlternatePowerAndOffhandSwap(GameTestHelper c) {
        var p = player(c, "menu-touch-power");
        p.getInventory().setItem(2, sword());
        p.getInventory().setSelectedSlot(2);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        ServerMenu.ensureNavigator(p);
        // JOIN defers automatic controls until the player's inventory has finished restoring.
        c.runAfterDelay(6, () -> {
            try {
                ServerMenu.tickPlayer(p);
                p.getInventory().setSelectedSlot(8);
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.containerMenu instanceof ServerMenu.Handler, "Selecting the navigator opens a native menu without typing");
                c.assertValueEqual(p.getInventory().getSelectedSlot(), 2, "The real tool slot is selected before actions");
                c.assertValueEqual(p.getMainHandItem().getItem(), Convergence.ITEMS.get("convergence:sword"), "The navigator is not the action target");
                int mode = Convergence.state(p).swordMode;
                click(p, ServerMenu.ALTERNATE);
                c.assertValueEqual(Convergence.state(p).swordMode, (mode + 1) % 3, "Menu alternate power executes the sword's actual cycle");
                c.assertFalse(p.isShiftKeyDown(), "Temporary alternate-power sneak state is restored");
                c.assertTrue(ServerMenu.isNavigator(p.getInventory().getItem(8)), "The control stays available");
                ServerMenu.open(p);
                var oldScreen = menu(p);
                click(p, ServerMenu.SWAP);
                c.assertValueEqual(p.getMainHandItem().getItem(), Items.SHIELD, "Real offhand moves to main hand");
                c.assertValueEqual(p.getOffhandItem().getItem(), Convergence.ITEMS.get("convergence:sword"), "Real weapon moves to offhand");
                ((AbstractContainerMenu)oldScreen).clicked(ServerMenu.SWAP, 0, ContainerInput.PICKUP, p);
                c.assertValueEqual(p.getMainHandItem().getItem(), Items.SHIELD, "A duplicate stale click cannot swap twice");
            } finally { cleanup(p); }
            c.succeed();
        });
    }

    @GameTest public void allControlHotbarDoesNotTrapPlayerInReopeningMenu(GameTestHelper c) {
        var p = player(c, "menu-all-controls");
        for (int slot = 0; slot < 9; slot++) p.getInventory().setItem(slot, ServerMenu.navigator(p));
        p.getInventory().setSelectedSlot(0);
        c.runAfterDelay(6, () -> {
            try {
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.containerMenu instanceof ServerMenu.Handler, "Selecting a control opens even when every hotbar slot is a control");
                click(p, ServerMenu.BACK);
                for (int tick = 0; tick < 4; tick++) ServerMenu.tickPlayer(p);
                c.assertTrue(p.containerMenu == p.inventoryMenu, "Closing a held control's screen stays closed");
                p.getInventory().setSelectedSlot(1);
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.containerMenu instanceof ServerMenu.Handler, "Selecting a different control is an intentional new opening");
                click(p, ServerMenu.BACK);
                c.assertValueEqual(ServerMenu.open(p), 1, "An explicit use or recovery command can reopen the same held control");
                click(p, ServerMenu.BACK);
                ServerMenu.tickPlayer(p);
                c.assertTrue(p.containerMenu == p.inventoryMenu, "Explicit reopening does not restart a loop");
            } finally { cleanup(p); }
            c.succeed();
        });
    }

    @GameTest public void menuRejectsChangedEquipmentAndCursorWithoutItemLoss(GameTestHelper c) {
        var p = player(c, "menu-stale-tool");
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, sword());
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            ServerMenu.open(p);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 9));
            click(p, ServerMenu.SWAP);
            c.assertValueEqual(p.getMainHandItem().getItem(), Items.DIAMOND, "Changed equipment cannot be swapped by an older menu");
            c.assertValueEqual(p.getMainHandItem().getCount(), 9, "Changed stack count stays intact");
            c.assertValueEqual(p.getOffhandItem().getItem(), Items.SHIELD, "Offhand stays intact");
            ServerMenu.open(p);
            var screen = menu(p);
            ((AbstractContainerMenu)screen).setCarried(new ItemStack(Items.EMERALD, 3));
            click(p, ServerMenu.SWAP);
            c.assertValueEqual(((AbstractContainerMenu)screen).getCarried().getCount(), 3, "Cursor is not consumed by menu actions");
            c.assertValueEqual(p.getMainHandItem().getItem(), Items.DIAMOND, "Cursor blocks the action");
            ((AbstractContainerMenu)screen).setCarried(ItemStack.EMPTY);
            GameModes.TRANSITIONS.add(p.getUUID());
            click(p, ServerMenu.SWAP);
            c.assertTrue(p.containerMenu == p.inventoryMenu, "Transition invalidates the old screen");
            c.assertValueEqual(p.getOffhandItem().getItem(), Items.SHIELD, "Transition cannot leak old inventory actions");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void navigatorOpensHelperRosterWithoutBypassingOpFour(GameTestHelper c) {
        var p = player(c, "menu-helper-link");
        try {
            OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
            ServerMenu.open(p);
            c.assertValueEqual(((AbstractContainerMenu)menu(p)).getSlot(ServerMenu.HELPERS).getItem().getItem(), Items.IRON_GOLEM_SPAWN_EGG, "Squad entry is a visible golem icon");
            OperatorGameTests.deop(p);
            click(p, ServerMenu.HELPERS);
            c.assertTrue(p.containerMenu instanceof ServerMenu.Handler, "Revoked OP4 cannot reach helper controls");
            OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
            click(p, ServerMenu.HELPERS);
            c.assertTrue(p.containerMenu instanceof AgentMenu.Handler, "Current OP4 opens the existing native helper roster");
            c.assertValueEqual(((AgentMenu.Handler)p.containerMenu).page, AgentMenu.Page.ROSTER, "Roster exposes the existing Create a helper choices");
            c.assertValueEqual(AgentCompanions.get(c.getLevel().getServer()).count(p), 0, "Opening the link alone does not create helpers or execute proposals");
            p.closeContainer();
            ServerMenu.open(p); click(p, ServerMenu.HELP);
            c.assertValueEqual(menu(p).page, ServerMenu.Page.HELP, "Controls are readable without a command");
            c.assertTrue(((AbstractContainerMenu)menu(p)).getSlot(0).getItem().has(DataComponents.LORE), "Help text is shown on readable cards");
            click(p, ServerMenu.BACK); click(p, ServerMenu.INFO);
            c.assertValueEqual(menu(p).page, ServerMenu.Page.INFO, "Joining information has a menu page");
            click(p, ServerMenu.BACK);
            c.assertValueEqual(menu(p).page, ServerMenu.Page.MAIN, "Information pages return to the main menu");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void droppingNavigatorDiscardsOnlyMarkedControlItems(GameTestHelper c) {
        var p = player(c, "menu-dropped");
        ItemEntity control = null, ordinary = null;
        try {
            control = new ItemEntity(p.level(), p.getX(), p.getY() + 1, p.getZ(), ServerMenu.navigator(p));
            p.level().addFreshEntity(control);
            c.assertTrue(control.isRemoved(), "A discarded menu cannot accumulate as a world item");
            ordinary = new ItemEntity(p.level(), p.getX(), p.getY() + 1, p.getZ(), new ItemStack(Items.RECOVERY_COMPASS));
            c.assertTrue(p.level().addFreshEntity(ordinary), "An ordinary recovery compass still spawns");
            c.assertFalse(ordinary.isRemoved(), "Player-crafted ordinary compasses are untouched");
        } finally {
            if (control != null) control.discard();
            if (ordinary != null) ordinary.discard();
            cleanup(p);
        }
        c.succeed();
    }
    @GameTest public void ranksMenuShowsActualRoleAndEarnedTierSeparately(GameTestHelper c) {
        var p=player(c,"ranks-menu14");
        try {
            var members=Memberships.get(c.getLevel().getServer());members.account(p.getUUID()).earned=Memberships.Tier.PLUS;
            ServerMenu.open(p);click(p,ServerMenu.RANKS);
            c.assertValueEqual(menu(p).page,ServerMenu.Page.RANKS,"Rank button opens real progression menu");
            c.assertTrue(p.containerMenu.getSlot(4).getItem().getHoverName().getString().contains("PLUS"),"Rank menu recognizes earned rank");
            c.assertTrue(ServerAssistant.instructions(p.createCommandSourceStack()).contains("Caller current badge: PLUS; permanently unlocked rank: PLUS"),"AI receives actual caller rank");
            p.closeContainer();OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);members.account(p.getUUID()).admin=true;
            ServerMenu.open(p);click(p,ServerMenu.RANKS);
            c.assertTrue(p.containerMenu.getSlot(4).getItem().getHoverName().getString().contains("ADMIN"),"Owner role is not confused with paid Ultra");
            c.assertValueEqual(members.permanentTier(p.getUUID()),Memberships.Tier.PLUS,"Admin access does not replace earned rank");
        } finally {Memberships.get(c.getLevel().getServer()).account(p.getUUID()).admin=false;cleanup(p);}
        c.succeed();
    }

}

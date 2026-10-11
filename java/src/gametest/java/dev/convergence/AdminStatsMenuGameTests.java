package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.world.GameMode;

/** Exercise actual chest clicks, delayed confirmations and inventory preservation. */
public class AdminStatsMenuGameTests {
    private ServerPlayerEntity player(TestContext c, String name, boolean admin) {
        var p = new ModeGameTests().player(c, name);
        p.changeGameMode(GameMode.SURVIVAL);
        if (admin) OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
        return p;
    }
    private void cleanup(ServerPlayerEntity p) {
        p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        p.closeHandledScreen();
        OperatorGameTests.deop(p);
        GameModes.PENDING.remove(p.getUuid());
        GameModes.TRANSITIONS.remove(p.getUuid());
        p.getEntityWorld().getServer().getPlayerManager().remove(p);
    }
    private void click(ServerPlayerEntity p, int slot) { p.currentScreenHandler.onSlotClick(slot, 0, SlotActionType.PICKUP, p); }
    private void quickClick(ServerPlayerEntity p, int slot) { p.currentScreenHandler.onSlotClick(slot, 0, SlotActionType.QUICK_MOVE, p); }
    private AdminStatsMenu.Handler menu(ServerPlayerEntity p) { return (AdminStatsMenu.Handler)p.currentScreenHandler; }
    private void edit(ServerPlayerEntity admin, ServerPlayerEntity target, String id) {
        admin.closeHandledScreen();
        AdminStatsMenu.openStat(admin, target, id);
    }

    @GameTest public void actualOpFourCanOpenMenuAndCommandIncludingSpectator(TestContext c) {
        var admin = player(c, "editor-owner", false);
        try {
            c.assertEquals(AdminStatsMenu.open(admin), 0, "Ordinary player cannot open editor");
            OperatorGameTests.level(admin, LeveledPermissionPredicate.ADMINS);
            ServerMenu.open(admin); click(admin, ServerMenu.ADMIN);
            c.assertTrue(admin.currentScreenHandler instanceof ServerMenu.Handler, "OP3 cannot enter Admin editor through main menu");
            admin.closeHandledScreen();
            OperatorGameTests.level(admin, LeveledPermissionPredicate.OWNERS);
            admin.changeGameMode(GameMode.CREATIVE);
            admin.getInventory().setStack(0, new ItemStack(Items.EMERALD, 11));
            ServerMenu.open(admin); click(admin, ServerMenu.ADMIN);
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.PLAYERS, "OP4 enters target picker without a command");
            quickClick(admin, 0);
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.STATS, "Self is the first target");
            c.assertEquals(menu(admin).stats.get(0).id(), "health", "Current health is first");
            c.assertEquals(menu(admin).stats.get(1).id(), "max_health", "Capacity is beside current health");
            quickClick(admin, 1);
            c.assertEquals(menu(admin).statId, "max_health", "Touch transfer selects a statistic in creative");
            quickClick(admin, AdminStatsMenu.PLUS_MEDIUM);
            c.assertEquals(menu(admin).pending, 30d, "Touch transfer stages the same increase as a normal click");
            c.assertEquals(admin.getMaxHealth(), 20f, "Staging does not apply the increase");
            quickClick(admin, AdminStatsMenu.REVIEW);
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.CONFIRM, "Touch transfer opens the separate review");
            c.assertEquals(admin.getMaxHealth(), 20f, "Touch review still needs confirmation");
            ScreenHandler oldReview = admin.currentScreenHandler;
            quickClick(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(admin.getMaxHealth(), 30f, "Touch confirmation applies the reviewed capacity");
            ScreenHandler current = admin.currentScreenHandler;
            oldReview.onSlotClick(AdminStatsMenu.CONFIRM, 0, SlotActionType.QUICK_MOVE, admin);
            c.assertTrue(admin.currentScreenHandler == current, "Old touch confirmation cannot affect the new screen");
            c.assertEquals(admin.getHealth(), 20f, "Changing capacity never silently heals");
            c.assertTrue(admin.currentScreenHandler.getCursorStack().isEmpty(), "Touch controls never acquire menu icons");
            c.assertEquals(admin.getInventory().getStack(0).getCount(), 11, "Touch controls preserve the player's inventory");
            admin.closeHandledScreen();
            admin.changeGameMode(GameMode.SPECTATOR);
            var dispatcher = c.getWorld().getServer().getCommandManager().getDispatcher();
            c.assertEquals(dispatcher.execute("adminstats", admin.getCommandSource()), 1, "OP4 spectator retains administrative access");
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.PLAYERS, "Registered command opens the real editor");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new RuntimeException(failure); }
        finally { cleanup(admin); }
        c.complete();
    }

    @GameTest public void editsRequireReviewAndConfirmAndOldClicksCannotRepeat(TestContext c) {
        var admin = player(c, "editor-review", true);
        var target = player(c, "editor-target", false);
        try {
            target.getInventory().setStack(0, new ItemStack(Items.DIAMOND, 7));
            edit(admin, target, "health");
            click(admin, AdminStatsMenu.RELATED_HEALTH);
            c.assertEquals(menu(admin).statId, "max_health", "Health screen can open capacity without hunting through attributes");
            click(admin, AdminStatsMenu.PLUS_MEDIUM);
            c.assertEquals(target.getMaxHealth(), 20f, "Adjusting pending value does not change target");
            click(admin, AdminStatsMenu.REVIEW);
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.CONFIRM, "Review is a separate screen");
            c.assertEquals(target.getMaxHealth(), 20f, "Review alone does not apply");
            ScreenHandler oldReview = admin.currentScreenHandler;
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getMaxHealth(), 30f, "Confirm changes the real maximum");
            ScreenHandler current = admin.currentScreenHandler;
            oldReview.onSlotClick(AdminStatsMenu.CONFIRM, 0, SlotActionType.PICKUP, admin);
            c.assertTrue(admin.currentScreenHandler == current, "Old confirm does not replace new screen");
            c.assertEquals(target.getMaxHealth(), 30f, "Old confirm cannot apply twice");
            c.assertEquals(target.getHealth(), 20f, "Raising capacity does not silently heal");
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.RELATED_HEALTH);
            c.assertEquals(menu(admin).statId, "health", "Capacity screen links back to current health");
            click(admin, AdminStatsMenu.MAXIMUM);
            c.assertEquals(menu(admin).pending, 30d, "Full-health proposal now exceeds the old twenty-point limit");
            c.assertEquals(target.getHealth(), 20f, "Selecting full health still needs review and confirmation");
            click(admin, AdminStatsMenu.REVIEW); click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getHealth(), 30f, "Confirmed heal fills the new capacity");
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.RESET);
            c.assertEquals(menu(admin).operation, AdminStatsMenu.Operation.RESET, "Reset previews saved original");
            c.assertEquals(target.getMaxHealth(), 30f, "Reset still needs confirmation");
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getMaxHealth(), 20f, "Confirm restores original maximum");
            c.assertEquals(target.getInventory().getStack(0).getCount(), 7, "Target equipment is never replaced by menu icons");
        } finally { cleanup(admin); cleanup(target); }
        c.complete();
    }

    @GameTest public void killPreviewCanBeCancelledAndChangedLimitsInvalidateReview(TestContext c) {
        var admin = player(c, "editor-limits", true);
        var target = player(c, "editor-vitals", false);
        try {
            edit(admin, target, "health"); click(admin, AdminStatsMenu.MINIMUM); click(admin, AdminStatsMenu.REVIEW);
            c.assertTrue(((ScreenHandler)menu(admin)).getSlot(AdminStatsMenu.CONFIRM).getStack().getName().getString().contains("KILL"), "Zero health explicitly labels death");
            click(admin, AdminStatsMenu.CANCEL);
            c.assertTrue(target.isAlive() && target.getHealth() == 20f, "Cancelling lethal preview preserves health");
            target.setHealth(10);
            edit(admin, target, "health"); click(admin, AdminStatsMenu.MAXIMUM); click(admin, AdminStatsMenu.REVIEW);
            target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(15);
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getHealth(), 10f, "A changed maximum cancels instead of silently applying a different amount");
            c.assertTrue(admin.currentScreenHandler == admin.playerScreenHandler, "Stale review closes");
            edit(admin, target, "health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            target.setHealth(9);
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getHealth(), 9f, "Live damage after review cannot be overwritten by stale confirmation");
        } finally { cleanup(admin); cleanup(target); }
        c.complete();
    }

    @GameTest public void revokedPermissionAndChangedPlayerSessionCancelConfirmation(TestContext c) {
        var admin = player(c, "editor-revoke", true);
        var target = player(c, "editor-session", false);
        try {
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            OperatorGameTests.deop(admin); click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getMaxHealth(), 20f, "Revoked permission is checked at confirmation");
            OperatorGameTests.level(admin, LeveledPermissionPredicate.OWNERS);
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            target.changeGameMode(GameMode.CREATIVE); click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getMaxHealth(), 20f, "Changed vanilla game mode invalidates target session");
            target.changeGameMode(GameMode.SURVIVAL);
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            var connection = target.networkHandler;
            try {
                target.networkHandler = admin.networkHandler;
                click(admin, AdminStatsMenu.CONFIRM);
                c.assertEquals(target.getMaxHealth(), 20f, "Replaced target connection cannot inherit old confirmation");
            } finally { target.networkHandler = connection; }
        } finally { cleanup(admin); cleanup(target); }
        c.complete();
    }

    @GameTest public void editingIconsAndForeignClicksNeverTransferItemsOrChangeStats(TestContext c) {
        var admin = player(c, "editor-inventory", true);
        var other = player(c, "editor-foreign", true);
        try {
            admin.getInventory().setStack(0, new ItemStack(Items.EMERALD, 11));
            edit(admin, admin, "max_health");
            ScreenHandler screen = admin.currentScreenHandler;
            var icon = screen.getSlot(AdminStatsMenu.PLUS_SMALL).getStack().copy();
            for (var action : new SlotActionType[]{SlotActionType.SWAP, SlotActionType.CLONE,
                    SlotActionType.THROW, SlotActionType.QUICK_CRAFT, SlotActionType.PICKUP_ALL}) {
                screen.onSlotClick(AdminStatsMenu.PLUS_SMALL, 0, action, admin);
                c.assertTrue(ItemStack.areItemsAndComponentsEqual(icon, screen.getSlot(AdminStatsMenu.PLUS_SMALL).getStack()), "Icon remains server-owned: " + action);
            }
            screen.selectBundleStack(AdminStatsMenu.PLUS_SMALL, 0);
            c.assertTrue(screen.quickMove(admin, AdminStatsMenu.PLUS_SMALL).isEmpty(), "Shift transfer yields no item");
            screen.onSlotClick(AdminStatsMenu.PLUS_SMALL, 0, SlotActionType.PICKUP, other);
            screen.onSlotClick(AdminStatsMenu.PLUS_SMALL, 0, SlotActionType.QUICK_MOVE, other);
            c.assertTrue(admin.currentScreenHandler == screen, "Foreign actor cannot even stage an edit");
            screen.setCursorStack(new ItemStack(Items.DIAMOND, 3));
            click(admin, AdminStatsMenu.PLUS_SMALL);
            quickClick(admin, AdminStatsMenu.PLUS_SMALL);
            c.assertEquals(screen.getCursorStack().getCount(), 3, "Cursor items are preserved");
            c.assertTrue(admin.currentScreenHandler == screen, "Cursor blocks action without changing screen");
            c.assertEquals(admin.getInventory().getStack(0).getCount(), 11, "Original inventory preserved");
            c.assertEquals(admin.getMaxHealth(), 20f, "No inventory gesture mutates stats");
        } finally { cleanup(admin); cleanup(other); }
        c.complete();
    }

    @GameTest public void resetAllReviewsOnlyBaseEditsAndPreservesOneTimeVitals(TestContext c) {
        var admin = player(c, "editor-restore", true);
        var target = player(c, "editor-originals", false);
        try {
            c.assertTrue(AdminStats.set(admin.getCommandSource(), target, "max_health", 80).success(), "Max edited");
            c.assertTrue(AdminStats.set(admin.getCommandSource(), target, "attack_damage", 40).success(), "Damage edited");
            c.assertTrue(AdminStats.set(admin.getCommandSource(), target, "health", 12).success(), "Current health edited");
            c.assertTrue(AdminStats.set(admin.getCommandSource(), target, "xp_level", 25).success(), "XP edited");
            AdminStatsMenu.openStats(admin, target, 0); click(admin, AdminStatsMenu.RESET_ALL);
            c.assertEquals(menu(admin).changes.size(), 2, "Only edited attribute bases appear in reset-all review");
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getMaxHealth(), 20f, "Max restored");
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), 1d, "Damage base restored");
            c.assertEquals(target.getHealth(), 12f, "Reset leaves valid current health alone");
            c.assertEquals(target.experienceLevel, 25, "Reset leaves one-time XP edit alone");
        } finally { cleanup(admin); cleanup(target); }
        c.complete();
    }

    @GameTest public void exactNumberEntryAndRepeatedButtonsKeepEditsStaged(TestContext c) {
        var admin=player(c,"editor-exact",true);var target=player(c,"editor-exact-target",false);
        try {
            edit(admin,target,"max_health");ScreenHandler screen=admin.currentScreenHandler;
            for(int i=0;i<10;i++)quickClick(admin,AdminStatsMenu.PLUS_LARGE);
            c.assertTrue(admin.currentScreenHandler==screen,"Repeated taps update the same screen without close/open packets");
            c.assertEquals(menu(admin).pending,1020d,"Every tap applies to the current pending value");
            c.assertEquals(target.getMaxHealth(),20f,"Repeated taps are still only a draft");
            click(admin,AdminStatsMenu.EXACT);
            c.assertTrue(admin.currentScreenHandler==admin.playerScreenHandler,"Exact entry closes the chest for chat input");
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"not a number"),"Invalid private input is consumed");
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"NaN"),"Non-finite private input is consumed without editing");
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"5000"),"Numeric private input is consumed");
            c.assertEquals(menu(admin).pending,5000d,"Exact input returns to the editor with the exact draft");
            c.assertEquals(target.getMaxHealth(),20f,"Typing the number is not approval");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getMaxHealth(),5000f,"Separate confirmation applies the uncapped server capacity");
            c.assertFalse(AdminStatsMenu.consumeChat(admin,"hello"),"Normal chat is no longer intercepted");
            edit(admin,target,"absorption");click(admin,AdminStatsMenu.RELATED_HEALTH);
            c.assertEquals(menu(admin).statId,"max_absorption","Zero absorption capacity has a direct route to increasing it");
            click(admin,AdminStatsMenu.EXACT);AdminStatsMenu.consumeChat(admin,"2500");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            edit(admin,target,"max_absorption");click(admin,AdminStatsMenu.RELATED_HEALTH);
            c.assertEquals(menu(admin).statId,"absorption","Capacity links directly back to absorption hearts");
            click(admin,AdminStatsMenu.EXACT);AdminStatsMenu.consumeChat(admin,"2400");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            c.assertEquals(target.getAbsorptionAmount(),2400f,"Confirmed absorption exceeds the native capacity cap");
            edit(admin,target,"attack_damage");click(admin,AdminStatsMenu.EXACT);
            OperatorGameTests.deop(admin);
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"9999"),"A pending entry is consumed and refused after permission revocation");
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE),1d,"Revoked entry cannot change damage");
        } finally { cleanup(admin);cleanup(target); }
        c.complete();
    }

}

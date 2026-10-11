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
            ServerMenu.open(admin); click(admin, ServerMenu.ADMIN);
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.PLAYERS, "OP4 enters target picker without a command");
            click(admin, 0);
            c.assertEquals(menu(admin).page, AdminStatsMenu.Page.STATS, "Self is the first target");
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
            edit(admin, target, "max_health");
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
            for (var action : new SlotActionType[]{SlotActionType.SWAP, SlotActionType.QUICK_MOVE, SlotActionType.CLONE,
                    SlotActionType.THROW, SlotActionType.QUICK_CRAFT, SlotActionType.PICKUP_ALL}) {
                screen.onSlotClick(AdminStatsMenu.PLUS_SMALL, 0, action, admin);
                c.assertTrue(ItemStack.areItemsAndComponentsEqual(icon, screen.getSlot(AdminStatsMenu.PLUS_SMALL).getStack()), "Icon remains server-owned: " + action);
            }
            screen.selectBundleStack(AdminStatsMenu.PLUS_SMALL, 0);
            c.assertTrue(screen.quickMove(admin, AdminStatsMenu.PLUS_SMALL).isEmpty(), "Shift transfer yields no item");
            screen.onSlotClick(AdminStatsMenu.PLUS_SMALL, 0, SlotActionType.PICKUP, other);
            c.assertTrue(admin.currentScreenHandler == screen, "Foreign actor cannot even stage an edit");
            screen.setCursorStack(new ItemStack(Items.DIAMOND, 3));
            click(admin, AdminStatsMenu.PLUS_SMALL);
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
}

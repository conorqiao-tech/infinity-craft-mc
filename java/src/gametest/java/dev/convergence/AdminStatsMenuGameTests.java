package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Exercise actual chest clicks, delayed confirmations and inventory preservation. */
public class AdminStatsMenuGameTests {
    private ServerPlayer player(GameTestHelper c, String name, boolean admin) {
        var p = new ModeGameTests().player(c, name);
        p.setGameMode(GameType.SURVIVAL);
        if (admin) OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
        return p;
    }
    private void cleanup(ServerPlayer p) {
        p.containerMenu.setCarried(ItemStack.EMPTY);
        p.closeContainer();
        OperatorGameTests.deop(p);
        GameModes.PENDING.remove(p.getUUID());
        GameModes.TRANSITIONS.remove(p.getUUID());
        p.level().getServer().getPlayerList().remove(p);
    }
    private void click(ServerPlayer p, int slot) { p.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, p); }
    private void quickClick(ServerPlayer p, int slot) { p.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, p); }
    private AdminStatsMenu.Handler menu(ServerPlayer p) { return (AdminStatsMenu.Handler)p.containerMenu; }
    private void edit(ServerPlayer admin, ServerPlayer target, String id) {
        admin.closeContainer();
        AdminStatsMenu.openStat(admin, target, id);
    }

    @GameTest public void actualOpFourCanOpenMenuAndCommandIncludingSpectator(GameTestHelper c) {
        var admin = player(c, "editor-owner", false);
        try {
            c.assertValueEqual(AdminStatsMenu.open(admin), 0, "Ordinary player cannot open editor");
            OperatorGameTests.level(admin, LevelBasedPermissionSet.ADMIN);
            ServerMenu.open(admin); click(admin, ServerMenu.ADMIN);
            c.assertTrue(admin.containerMenu instanceof ServerMenu.Handler, "OP3 cannot enter Admin editor through main menu");
            admin.closeContainer();
            OperatorGameTests.level(admin, LevelBasedPermissionSet.OWNER);
            admin.setGameMode(GameType.CREATIVE);
            admin.getInventory().setItem(0, new ItemStack(Items.EMERALD, 11));
            ServerMenu.open(admin); quickClick(admin, ServerMenu.ADMIN);
            c.assertValueEqual(menu(admin).page, AdminStatsMenu.Page.PLAYERS, "OP4 enters target picker without a command");
            quickClick(admin, 0);
            c.assertValueEqual(menu(admin).page, AdminStatsMenu.Page.STATS, "Self is the first target");
            c.assertValueEqual(menu(admin).stats.get(0).id(), "health", "Current health is first");
            c.assertValueEqual(menu(admin).stats.get(1).id(), "max_health", "Capacity is beside current health");
            quickClick(admin, 1);
            c.assertValueEqual(menu(admin).statId, "max_health", "Touch transfer selects a statistic in creative");
            quickClick(admin, AdminStatsMenu.PLUS_MEDIUM);
            c.assertValueEqual(menu(admin).pending, 30d, "Touch transfer stages the same increase as a normal click");
            c.assertValueEqual(admin.getMaxHealth(), 20f, "Staging does not apply the increase");
            quickClick(admin, AdminStatsMenu.REVIEW);
            c.assertValueEqual(menu(admin).page, AdminStatsMenu.Page.CONFIRM, "Touch transfer opens the separate review");
            c.assertValueEqual(admin.getMaxHealth(), 20f, "Touch review still needs confirmation");
            AbstractContainerMenu oldReview = admin.containerMenu;
            quickClick(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(admin.getMaxHealth(), 30f, "Touch confirmation applies the reviewed capacity");
            AbstractContainerMenu current = admin.containerMenu;
            oldReview.clicked(AdminStatsMenu.CONFIRM, 0, ContainerInput.QUICK_MOVE, admin);
            c.assertTrue(admin.containerMenu == current, "Old touch confirmation cannot affect the new screen");
            c.assertValueEqual(admin.getHealth(), 20f, "Changing capacity never silently heals");
            c.assertTrue(admin.containerMenu.getCarried().isEmpty(), "Touch controls never acquire menu icons");
            c.assertValueEqual(admin.getInventory().getItem(0).getCount(), 11, "Touch controls preserve the player's inventory");
            admin.closeContainer();
            admin.setGameMode(GameType.SPECTATOR);
            var dispatcher = c.getLevel().getServer().getCommands().getDispatcher();
            c.assertValueEqual(dispatcher.execute("adminstats", admin.createCommandSourceStack()), 1, "OP4 spectator retains administrative access");
            c.assertValueEqual(menu(admin).page, AdminStatsMenu.Page.PLAYERS, "Registered command opens the real editor");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new RuntimeException(failure); }
        finally { cleanup(admin); }
        c.succeed();
    }

    @GameTest public void editsRequireReviewAndConfirmAndOldClicksCannotRepeat(GameTestHelper c) {
        var admin = player(c, "editor-review", true);
        var target = player(c, "editor-target", false);
        try {
            target.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            edit(admin, target, "health");
            click(admin, AdminStatsMenu.RELATED_HEALTH);
            c.assertValueEqual(menu(admin).statId, "max_health", "Health screen can open capacity without hunting through attributes");
            click(admin, AdminStatsMenu.PLUS_MEDIUM);
            c.assertValueEqual(target.getMaxHealth(), 20f, "Adjusting pending value does not change target");
            click(admin, AdminStatsMenu.REVIEW);
            c.assertValueEqual(menu(admin).page, AdminStatsMenu.Page.CONFIRM, "Review is a separate screen");
            c.assertValueEqual(target.getMaxHealth(), 20f, "Review alone does not apply");
            AbstractContainerMenu oldReview = admin.containerMenu;
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getMaxHealth(), 30f, "Confirm changes the real maximum");
            AbstractContainerMenu current = admin.containerMenu;
            oldReview.clicked(AdminStatsMenu.CONFIRM, 0, ContainerInput.PICKUP, admin);
            c.assertTrue(admin.containerMenu == current, "Old confirm does not replace new screen");
            c.assertValueEqual(target.getMaxHealth(), 30f, "Old confirm cannot apply twice");
            c.assertValueEqual(target.getHealth(), 20f, "Raising capacity does not silently heal");
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.RELATED_HEALTH);
            c.assertValueEqual(menu(admin).statId, "health", "Capacity screen links back to current health");
            click(admin, AdminStatsMenu.MAXIMUM);
            c.assertValueEqual(menu(admin).pending, 30d, "Full-health proposal now exceeds the old twenty-point limit");
            c.assertValueEqual(target.getHealth(), 20f, "Selecting full health still needs review and confirmation");
            click(admin, AdminStatsMenu.REVIEW); click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getHealth(), 30f, "Confirmed heal fills the new capacity");
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.RESET);
            c.assertValueEqual(menu(admin).operation, AdminStatsMenu.Operation.RESET, "Reset previews saved original");
            c.assertValueEqual(target.getMaxHealth(), 30f, "Reset still needs confirmation");
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getMaxHealth(), 20f, "Confirm restores original maximum");
            c.assertValueEqual(target.getInventory().getItem(0).getCount(), 7, "Target equipment is never replaced by menu icons");
        } finally { cleanup(admin); cleanup(target); }
        c.succeed();
    }

    @GameTest public void killPreviewCanBeCancelledAndChangedLimitsInvalidateReview(GameTestHelper c) {
        var admin = player(c, "editor-limits", true);
        var target = player(c, "editor-vitals", false);
        try {
            edit(admin, target, "health"); click(admin, AdminStatsMenu.MINIMUM); click(admin, AdminStatsMenu.REVIEW);
            c.assertTrue(((AbstractContainerMenu)menu(admin)).getSlot(AdminStatsMenu.CONFIRM).getItem().getHoverName().getString().contains("KILL"), "Zero health explicitly labels death");
            click(admin, AdminStatsMenu.CANCEL);
            c.assertTrue(target.isAlive() && target.getHealth() == 20f, "Cancelling lethal preview preserves health");
            target.setHealth(10);
            edit(admin, target, "health"); click(admin, AdminStatsMenu.MAXIMUM); click(admin, AdminStatsMenu.REVIEW);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(15);
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getHealth(), 10f, "A changed maximum cancels instead of silently applying a different amount");
            c.assertTrue(admin.containerMenu == admin.inventoryMenu, "Stale review closes");
            edit(admin, target, "health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            target.setHealth(9);
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getHealth(), 11f, "An explicitly reviewed absolute heal survives normal live health changes");
        } finally { cleanup(admin); cleanup(target); }
        c.succeed();
    }

    @GameTest public void revokedPermissionAndChangedPlayerSessionCancelConfirmation(GameTestHelper c) {
        var admin = player(c, "editor-revoke", true);
        var target = player(c, "editor-session", false);
        try {
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            OperatorGameTests.deop(admin); click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getMaxHealth(), 20f, "Revoked permission is checked at confirmation");
            OperatorGameTests.level(admin, LevelBasedPermissionSet.OWNER);
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            target.setGameMode(GameType.CREATIVE); click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getMaxHealth(), 20f, "Changed vanilla game mode invalidates target session");
            target.setGameMode(GameType.SURVIVAL);
            edit(admin, target, "max_health"); click(admin, AdminStatsMenu.PLUS_SMALL); click(admin, AdminStatsMenu.REVIEW);
            var connection = target.connection;
            try {
                target.connection = admin.connection;
                click(admin, AdminStatsMenu.CONFIRM);
                c.assertValueEqual(target.getMaxHealth(), 20f, "Replaced target connection cannot inherit old confirmation");
            } finally { target.connection = connection; }
        } finally { cleanup(admin); cleanup(target); }
        c.succeed();
    }

    @GameTest public void editingIconsAndForeignClicksNeverTransferItemsOrChangeStats(GameTestHelper c) {
        var admin = player(c, "editor-inventory", true);
        var other = player(c, "editor-foreign", true);
        try {
            admin.getInventory().setItem(0, new ItemStack(Items.EMERALD, 11));
            edit(admin, admin, "max_health");
            AbstractContainerMenu screen = admin.containerMenu;
            var icon = screen.getSlot(AdminStatsMenu.PLUS_SMALL).getItem().copy();
            for (var action : new ContainerInput[]{ContainerInput.SWAP, ContainerInput.CLONE,
                    ContainerInput.THROW, ContainerInput.QUICK_CRAFT, ContainerInput.PICKUP_ALL}) {
                screen.clicked(AdminStatsMenu.PLUS_SMALL, 0, action, admin);
                c.assertTrue(ItemStack.isSameItemSameComponents(icon, screen.getSlot(AdminStatsMenu.PLUS_SMALL).getItem()), "Icon remains server-owned: " + action);
            }
            screen.setSelectedBundleItemIndex(AdminStatsMenu.PLUS_SMALL, 0);
            c.assertTrue(screen.quickMoveStack(admin, AdminStatsMenu.PLUS_SMALL).isEmpty(), "Shift transfer yields no item");
            screen.clicked(AdminStatsMenu.PLUS_SMALL, 0, ContainerInput.PICKUP, other);
            screen.clicked(AdminStatsMenu.PLUS_SMALL, 0, ContainerInput.QUICK_MOVE, other);
            c.assertTrue(admin.containerMenu == screen, "Foreign actor cannot even stage an edit");
            screen.setCarried(new ItemStack(Items.DIAMOND, 3));
            click(admin, AdminStatsMenu.PLUS_SMALL);
            quickClick(admin, AdminStatsMenu.PLUS_SMALL);
            c.assertValueEqual(screen.getCarried().getCount(), 3, "Cursor items are preserved");
            c.assertTrue(admin.containerMenu == screen, "Cursor blocks action without changing screen");
            c.assertValueEqual(admin.getInventory().getItem(0).getCount(), 11, "Original inventory preserved");
            c.assertValueEqual(admin.getMaxHealth(), 20f, "No inventory gesture mutates stats");
        } finally { cleanup(admin); cleanup(other); }
        c.succeed();
    }

    @GameTest public void resetAllReviewsOnlyBaseEditsAndPreservesOneTimeVitals(GameTestHelper c) {
        var admin = player(c, "editor-restore", true);
        var target = player(c, "editor-originals", false);
        try {
            c.assertTrue(AdminStats.set(admin.createCommandSourceStack(), target, "max_health", 80).success(), "Max edited");
            c.assertTrue(AdminStats.set(admin.createCommandSourceStack(), target, "attack_damage", 40).success(), "Damage edited");
            c.assertTrue(AdminStats.set(admin.createCommandSourceStack(), target, "health", 12).success(), "Current health edited");
            c.assertTrue(AdminStats.set(admin.createCommandSourceStack(), target, "xp_level", 25).success(), "XP edited");
            AdminStatsMenu.openStats(admin, target, 0); click(admin, AdminStatsMenu.RESET_ALL);
            c.assertValueEqual(menu(admin).changes.size(), 2, "Only edited attribute bases appear in reset-all review");
            click(admin, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getMaxHealth(), 20f, "Max restored");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 1d, "Damage base restored");
            c.assertValueEqual(target.getHealth(), 12f, "Reset leaves valid current health alone");
            c.assertValueEqual(target.experienceLevel, 25, "Reset leaves one-time XP edit alone");
        } finally { cleanup(admin); cleanup(target); }
        c.succeed();
    }

    @GameTest public void exactNumberEntryAndRepeatedButtonsKeepEditsStaged(GameTestHelper c) {
        var admin=player(c,"editor-exact",true);var target=player(c,"editor-exact-target",false);
        try {
            edit(admin,target,"max_health");AbstractContainerMenu screen=admin.containerMenu;
            for(int i=0;i<10;i++)quickClick(admin,AdminStatsMenu.PLUS_LARGE);
            c.assertTrue(admin.containerMenu==screen,"Repeated taps update the same screen without close/open packets");
            c.assertValueEqual(menu(admin).pending,1020d,"Every tap applies to the current pending value");
            c.assertValueEqual(target.getMaxHealth(),20f,"Repeated taps are still only a draft");
            click(admin,AdminStatsMenu.EXACT);
            c.assertTrue(admin.containerMenu==admin.inventoryMenu,"Exact entry closes the chest for chat input");
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"not a number"),"Invalid private input is consumed");
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"NaN"),"Non-finite private input is consumed without editing");
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"5000"),"Numeric private input is consumed");
            c.assertValueEqual(menu(admin).pending,5000d,"Exact input returns to the editor with the exact draft");
            c.assertValueEqual(target.getMaxHealth(),20f,"Typing the number is not approval");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getMaxHealth(),5000f,"Separate confirmation applies the uncapped server capacity");
            c.assertFalse(AdminStatsMenu.consumeChat(admin,"hello"),"Normal chat is no longer intercepted");
            edit(admin,target,"absorption");click(admin,AdminStatsMenu.RELATED_HEALTH);
            c.assertValueEqual(menu(admin).statId,"max_absorption","Zero absorption capacity has a direct route to increasing it");
            click(admin,AdminStatsMenu.EXACT);AdminStatsMenu.consumeChat(admin,"2500");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            edit(admin,target,"max_absorption");click(admin,AdminStatsMenu.RELATED_HEALTH);
            c.assertValueEqual(menu(admin).statId,"absorption","Capacity links directly back to absorption hearts");
            click(admin,AdminStatsMenu.EXACT);AdminStatsMenu.consumeChat(admin,"2400");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getAbsorptionAmount(),2400f,"Confirmed absorption exceeds the native capacity cap");
            edit(admin,target,"attack_damage");click(admin,AdminStatsMenu.EXACT);
            OperatorGameTests.deop(admin);
            c.assertTrue(AdminStatsMenu.consumeChat(admin,"9999"),"A pending entry is consumed and refused after permission revocation");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ATTACK_DAMAGE),1d,"Revoked entry cannot change damage");
        } finally { cleanup(admin);cleanup(target); }
        c.succeed();
    }

    private void formReply(org.geysermc.cumulus.form.Form form,String payload) {
        try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,payload); }
        catch(Exception failure) { throw new RuntimeException(failure); }
    }

    @GameTest public void bedrockFormsApplyExactCapacityAndHealDespiteLiveDamage(GameTestHelper c) {
        var admin=player(c,"form-capacity",true);var target=player(c,"form-health",false);
        var forms=new java.util.ArrayList<org.geysermc.cumulus.form.Form>();
        java.util.function.Predicate<org.geysermc.cumulus.form.Form> sender=f->{forms.add(f);return true;};
        try {
            c.assertValueEqual(BedrockStatsMenu.open(admin,target,"max_health",sender),1,"Native editor opens");
            c.assertTrue(forms.getLast() instanceof org.geysermc.cumulus.form.CustomForm,"A native number field replaces chest clicks");
            formReply(forms.getLast(),"[null,\"5000\",0]");
            c.assertTrue(forms.getLast() instanceof org.geysermc.cumulus.form.ModalForm,"Input opens an explicit native confirmation");
            c.assertValueEqual(target.getMaxHealth(),20f,"Input alone never mutates capacity");
            var confirm=forms.getLast();formReply(confirm,"true");
            c.assertValueEqual(target.getMaxHealth(),5000f,"Native confirm applies exact capacity");
            int count=forms.size();formReply(confirm,"true");
            c.assertValueEqual(forms.size(),count,"Replay cannot open or apply another editor");
            BedrockStatsMenu.open(admin,target,"health",sender);formReply(forms.getLast(),"[null,\"4500\",0]");
            target.setHealth(9);formReply(forms.getLast(),"true");
            c.assertValueEqual(target.getHealth(),4500f,"Natural changes do not invalidate the approved absolute health value");
            c.assertTrue(admin.containerMenu==admin.inventoryMenu,"Forms never put an icon on the inventory cursor");
        } finally {cleanup(admin);cleanup(target);}
        c.succeed();
    }

    @GameTest public void bedrockConfirmStillChecksPermissionCapacityAndLatestForm(GameTestHelper c) {
        var admin=player(c,"form-guards",true);var target=player(c,"form-target",false);
        var forms=new java.util.ArrayList<org.geysermc.cumulus.form.Form>();
        java.util.function.Predicate<org.geysermc.cumulus.form.Form> sender=f->{forms.add(f);return true;};
        try {
            target.setHealth(10);
            BedrockStatsMenu.open(admin,target,"health",sender);formReply(forms.getLast(),"[null,\"20\",0]");
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(15);formReply(forms.getLast(),"true");
            c.assertValueEqual(target.getHealth(),10f,"A reduced capacity cancels the old amount");
            BedrockStatsMenu.open(admin,target,"max_health",sender);formReply(forms.getLast(),"[null,\"5000\",0]");
            var old=forms.getLast();BedrockStatsMenu.open(admin,target,"attack_damage",sender);
            formReply(old,"true");c.assertValueEqual(target.getMaxHealth(),15f,"Opening another form invalidates the old confirmation, even in the same tick");
            formReply(forms.getLast(),"[null,\"100\",0]");OperatorGameTests.deop(admin);formReply(forms.getLast(),"true");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ATTACK_DAMAGE),1d,"Deopped actor cannot confirm a form");
        } finally {cleanup(admin);cleanup(target);}
        c.succeed();
    }

    @GameTest public void bedrockCancelInvalidNumbersAndChangedAttributesPreserveStats(GameTestHelper c) {
        var admin=player(c,"form-cancel",true);var target=player(c,"form-base",false);
        var forms=new java.util.ArrayList<org.geysermc.cumulus.form.Form>();
        java.util.function.Predicate<org.geysermc.cumulus.form.Form> sender=f->{forms.add(f);return true;};
        try {
            BedrockStatsMenu.open(admin,target,"max_health",sender);formReply(forms.getLast(),"[null,\"NaN\",0]");
            c.assertTrue(forms.getLast() instanceof org.geysermc.cumulus.form.CustomForm,"Invalid value returns an explicit input form");
            formReply(forms.getLast(),"[null,\"5000\",0]");formReply(forms.getLast(),"false");
            c.assertValueEqual(target.getMaxHealth(),20f,"Cancel preserves capacity");
            BedrockStatsMenu.open(admin,target,"max_health",sender);formReply(forms.getLast(),"[null,\"5000\",0]");
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(50);formReply(forms.getLast(),"true");
            c.assertValueEqual(target.getMaxHealth(),50f,"Concurrent attribute edits still invalidate review");
            BedrockStatsMenu.open(admin,target,"health",sender);var input=forms.getLast();
            target.setGameMode(GameType.CREATIVE);formReply(input,"[null,\"40\",0]");
            c.assertValueEqual(target.getHealth(),20f,"Changed target session cannot inherit an input form");
        } finally {cleanup(admin);cleanup(target);}
        c.succeed();
    }

    @GameTest public void directJavaAbsorptionEntryReviewsBothChangesAndDoesNotNeedCapacityScreen(GameTestHelper c) {
        var admin=player(c,"auto-java-op",true);var target=player(c,"auto-java-target",false);
        try {
            edit(admin,target,"absorption");click(admin,AdminStatsMenu.EXACT);
            AdminStatsMenu.consumeChat(admin,"5000");click(admin,AdminStatsMenu.REVIEW);
            c.assertValueEqual(menu(admin).changes.size(),2,"One review includes capacity and absorption");
            c.assertValueEqual(menu(admin).changes.getFirst().id(),"max_absorption","The capacity change is visible");
            c.assertValueEqual(target.getMaxAbsorption(),0f,"Review does not change either value");
            click(admin,AdminStatsMenu.CANCEL);
            c.assertValueEqual(target.getMaxAbsorption(),0f,"Cancel preserves capacity");
            edit(admin,target,"absorption");click(admin,AdminStatsMenu.PLUS_LARGE);
            c.assertValueEqual(menu(admin).pending,100d,"Buttons no longer stop at a zero capacity");
            click(admin,AdminStatsMenu.REVIEW);click(admin,AdminStatsMenu.CONFIRM);
            c.assertValueEqual(target.getAbsorptionAmount(),100f,"One confirmed edit raises capacity and grants the actual reserve");
            c.assertValueEqual(target.getMaxAbsorption(),100f,"Capacity matches the confirmed plan");
        } finally {cleanup(admin);cleanup(target);}
        c.succeed();
    }

    @GameTest public void bedrockDirectAbsorptionAndArmorPersistAndCapacityChangeCancelsBoth(GameTestHelper c) {
        var admin=player(c,"auto-form-op",true);var target=player(c,"auto-form-target",false);
        var forms=new java.util.ArrayList<org.geysermc.cumulus.form.Form>();
        java.util.function.Predicate<org.geysermc.cumulus.form.Form> sender=f->{forms.add(f);return true;};
        try {
            BedrockStatsMenu.open(admin,target,"absorption",sender);formReply(forms.getLast(),"[null,\"5000\",0]");
            var confirm=(org.geysermc.cumulus.form.ModalForm)forms.getLast();
            c.assertTrue(confirm.content().contains("max_absorption")&&confirm.content().contains("absorption"),"Native confirmation shows both exact changes");
            c.assertValueEqual(target.getMaxAbsorption(),0f,"Typing a value never raises capacity before approval");
            target.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(1);
            formReply(confirm,"true");
            c.assertValueEqual(target.getMaxAbsorption(),1f,"Stale capacity cancels the whole transaction");
            c.assertValueEqual(target.getAbsorptionAmount(),0f,"Stale approval does not apply the dependent amount");
            BedrockStatsMenu.open(admin,target,"absorption",sender);formReply(forms.getLast(),"[null,\"5000\",0]");formReply(forms.getLast(),"true");
            c.assertValueEqual(target.getAbsorptionAmount(),5000f,"Bedrock input raises capacity in the same confirmed edit");
            BedrockStatsMenu.open(admin,target,"armor",sender);formReply(forms.getLast(),"[null,\"500\",0]");formReply(forms.getLast(),"true");
            c.assertValueEqual(target.getAttributeValue(Attributes.ARMOR),500d,"Bedrock form accepts armor above the native attribute bound");
            c.assertValueEqual(target.getAbsorptionAmount(),5000f,"Editing armor does not reset a high absorption reserve");
        } finally {cleanup(admin);cleanup(target);}
        c.succeed();
    }

    @GameTest public void touchArmorMenuGrantsAllAuroraPiecesIncludingChestplate(GameTestHelper c) {
        var admin=player(c,"armor-touch",true);
        try {
            ServerMenu.open(admin);quickClick(admin,ServerMenu.ARMOR);
            c.assertValueEqual(((ServerMenu.Handler)admin.containerMenu).page,ServerMenu.Page.ARMOR,"Bedrock-style transfer opens armor sets");
            quickClick(admin,10);
            for(String part:java.util.List.of("helmet","chestplate","leggings","boots")) {
                var item=Convergence.ITEMS.get("convergence:aurora_"+part);
                c.assertTrue(admin.getInventory().contains(new ItemStack(item)),"Complete set includes "+part);
                var wire=new ItemStack(CrossplaySupport.BASES.get("aurora_"+part));
                wire.set(net.minecraft.core.component.DataComponents.EQUIPPABLE,item.components().get(net.minecraft.core.component.DataComponents.EQUIPPABLE));
                CrossplaySupport.wearableFallback(wire,wire.getItem(),true,true);
                c.assertValueEqual(wire.get(net.minecraft.core.component.DataComponents.EQUIPPABLE),wire.getItem().components().get(net.minecraft.core.component.DataComponents.EQUIPPABLE),"Bedrock always uses native armor assets even if Java pack state is enabled");
                c.assertTrue(CrossplaySupport.nativeAppearance("aurora_"+part,true,true),"Bedrock cosmetic icon uses its native wearable model");
            }
            c.assertFalse(CrossplaySupport.nativeAppearance("sword",true,false),"Mapped weapon artwork remains custom on Bedrock");
        } finally {cleanup(admin);}
        c.succeed();
    }

}

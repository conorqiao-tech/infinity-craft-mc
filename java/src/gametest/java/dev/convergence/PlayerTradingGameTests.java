package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

public class PlayerTradingGameTests {
    static class Store extends PlayerTrading.Storage {
        Store(MinecraftServer server) throws IOException {this(server,Files.createTempDirectory("infinity-p2p-test-"));}
        Store(MinecraftServer server,java.nio.file.Path dir) {super(server,dir.resolve("journals"),dir.resolve("players"));}
        @Override void savePlayers(ServerPlayer a,ServerPlayer b) throws IOException {
            atomic(player(a.getUUID()),RewardTrades.serialize(a));atomic(player(b.getUUID()),RewardTrades.serialize(b));
        }
    }
    record Fixture(PlayerTrading manager,ServerPlayer first,ServerPlayer second,Store storage) {}
    Fixture fixture(GameTestHelper c,String name) {
        try {
            var a=new ModeGameTests().player(c,name+"-a");var b=new ModeGameTests().player(c,name+"-b");
            a.setGameMode(GameType.SURVIVAL);b.setGameMode(GameType.SURVIVAL);b.setPos(a.position());
            var storage=new Store(c.getLevel().getServer());var manager=new PlayerTrading(c.getLevel().getServer(),storage);
            c.assertTrue(manager.request(a,b),"Nearby Survival players can request trade");c.assertTrue(manager.accept(b),"Recipient explicitly accepts");
            return new Fixture(manager,a,b,storage);
        }catch(IOException e){throw new RuntimeException(e);}
    }
    int total(ServerPlayer p,net.minecraft.world.item.Item item) {return p.getInventory().getNonEquipmentItems().stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
    @GameTest public void playerTradeRequiresBothConfirmationsAndPreservesComponents(GameTestHelper c) {
        var f=fixture(c,"pt-both");var gift=new ItemStack(Items.DIAMOND,9);gift.set(DataComponents.CUSTOM_NAME,Component.literal("Gift"));f.first().getInventory().setItem(0,gift);
        f.second().getInventory().setItem(0,new ItemStack(Items.EMERALD,7));
        c.assertTrue(f.manager().offer(f.first(),0,4),"Offer selects a main slot without escrow");c.assertTrue(f.manager().offer(f.second(),0,3),"Partner adds offer");
        var s=f.manager().sessions.get(f.first().getUUID());int version=s.version;
        c.assertTrue(f.manager().confirm(f.first(),version),"First confirmation is accepted");
        c.assertValueEqual(total(f.first(),Items.DIAMOND),9,"First confirmation moves nothing");
        c.assertTrue(f.manager().confirm(f.second(),version),"Second confirmation exchanges");
        c.assertValueEqual(total(f.first(),Items.DIAMOND),5,"Only offered count debited");c.assertValueEqual(total(f.first(),Items.EMERALD),3,"Partner items received");
        c.assertValueEqual(total(f.second(),Items.EMERALD),4,"Partner debit exact");c.assertValueEqual(total(f.second(),Items.DIAMOND),4,"Gift received");
        c.assertTrue(f.second().getInventory().getNonEquipmentItems().stream().filter(s2->s2.is(Items.DIAMOND)).allMatch(s2->ItemStack.isSameItemSameComponents(s2,gift)),"Custom names/components preserved");
        c.assertFalse(f.manager().confirm(f.second(),version),"Repeated confirmation cannot repeat exchange");
        c.assertValueEqual(total(f.second(),Items.DIAMOND),4,"Repeated command adds nothing");c.succeed();
    }
    @GameTest public void changingOffersClearsBothConfirmationsAndRejectsOldScreen(GameTestHelper c) {
        var f=fixture(c,"pt-dirty");f.first().getInventory().setItem(0,new ItemStack(Items.DIAMOND,8));
        f.manager().offer(f.first(),0,2);var s=f.manager().sessions.get(f.first().getUUID());int old=s.version;
        c.assertTrue(f.second().containerMenu instanceof PlayerTrading.ReviewHandler,"Actual vanilla window uses the read-only trade handler");
        AbstractContainerMenu oldScreen=f.second().containerMenu;
        f.manager().confirm(f.first(),old);c.assertTrue(s.confirmed.contains(f.first().getUUID()),"First player initially confirms");
        f.manager().offer(f.first(),0,3);
        c.assertTrue(s.confirmed.isEmpty(),"Changed offer clears every confirmation");
        c.assertTrue(f.second().containerMenu.containerId!=oldScreen.containerId,"New offer opens a fresh vanilla window");
        oldScreen.clicked(49,0,ContainerInput.PICKUP,f.second());
        c.assertTrue(s.confirmed.isEmpty(),"Old GUI confirmation cannot authorize changed offer");
        c.assertFalse(f.manager().confirm(f.first(),old),"Old command revision rejected");c.assertValueEqual(total(f.first(),Items.DIAMOND),8,"No escrow debit");c.succeed();
    }
    @GameTest public void playerTradeReviewDeniesPickupDragDropSwapAndQuickMove(GameTestHelper c) {
        var f=fixture(c,"pt-gui");f.first().getInventory().setItem(0,new ItemStack(Items.DIAMOND,8));f.manager().offer(f.first(),0,2);
        c.assertTrue(f.first().containerMenu instanceof PlayerTrading.ReviewHandler,"Trade review opens its guarded handler");
        AbstractContainerMenu handler=f.first().containerMenu;
        for(var action:ContainerInput.values())handler.clicked(0,0,action,f.first());
        handler.clicked(54,0,ContainerInput.PICKUP,f.first());handler.setSelectedBundleItemIndex(0,1);
        c.assertTrue(handler.quickMoveStack(f.first(),0).isEmpty(),"Shift transfer returns nothing");
        c.assertTrue(handler.getCarried().isEmpty(),"GUI cannot create a cursor item");c.assertValueEqual(total(f.first(),Items.DIAMOND),8,"Actual inventory unchanged");
        c.assertValueEqual(((PlayerTrading.ReviewHandler)handler).view.getItem(0).getCount(),2,"Review contains only the offered display copy");c.succeed();
    }
    @GameTest public void playerTradeFullRecipientInventoryAbortsWithoutLoss(GameTestHelper c) {
        var f=fixture(c,"pt-full");f.first().getInventory().setItem(0,new ItemStack(Items.DIAMOND,1));
        for(int i=0;i<36;i++)f.second().getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));
        f.manager().offer(f.first(),0,1);var s=f.manager().sessions.get(f.first().getUUID());
        f.manager().confirm(f.first(),s.version);c.assertFalse(f.manager().confirm(f.second(),s.version),"No incoming capacity rejects exchange");
        c.assertValueEqual(total(f.first(),Items.DIAMOND),1,"Sender keeps offered item");c.assertValueEqual(total(f.second(),Items.COBBLESTONE),36*64,"Full recipient unchanged");
        c.assertTrue(s.confirmed.isEmpty(),"Capacity failure clears both confirmations");c.succeed();
    }
    @GameTest public void playerTradeFullInventoriesCanExchangeVacatedStacks(GameTestHelper c) {
        var f=fixture(c,"pt-swap");
        for(int i=0;i<36;i++){f.first().getInventory().setItem(i,new ItemStack(Items.COBBLESTONE,64));f.second().getInventory().setItem(i,new ItemStack(Items.DIRT,64));}
        f.manager().offer(f.first(),0,64);f.manager().offer(f.second(),0,64);var s=f.manager().sessions.get(f.first().getUUID());
        f.manager().confirm(f.first(),s.version);c.assertTrue(f.manager().confirm(f.second(),s.version),"Simulation uses slots vacated by outgoing full stacks");
        c.assertValueEqual(total(f.first(),Items.DIRT),64,"Incoming dirt fits exact vacated slot");c.assertValueEqual(total(f.second(),Items.COBBLESTONE),64,"Incoming stone fits");
        c.assertValueEqual(total(f.first(),Items.COBBLESTONE)+total(f.second(),Items.COBBLESTONE),36*64,"Global stone count conserved");c.succeed();
    }
    @GameTest public void playerTradesRejectCreativeProfilesContainersAndCursors(GameTestHelper c) {
        var f=fixture(c,"pt-safe");var s=f.manager().sessions.get(f.first().getUUID());f.manager().cancel(s,"test");
        f.first().setGameMode(GameType.CREATIVE);c.assertFalse(f.manager().request(f.first(),f.second()),"Actual Creative cannot trade");
        f.first().setGameMode(GameType.SURVIVAL);GameModes.state(f.first()).putString("active","CREATIVE");
        c.assertFalse(f.manager().request(f.first(),f.second()),"Creative profile cannot trade despite native Survival");GameModes.state(f.first()).putString("active","SURVIVAL");
        f.first().inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));c.assertFalse(f.manager().request(f.first(),f.second()),"Cursor item rejects request");
        c.assertValueEqual(f.first().inventoryMenu.getCarried().getCount(),1,"Request never takes cursor");f.first().inventoryMenu.setCarried(ItemStack.EMPTY);
        f.first().openMenu(new SimpleMenuProvider((sync,inv,p)->ChestMenu.threeRows(sync,inv,new SimpleContainer(27)),Component.literal("Other storage")));
        c.assertFalse(f.manager().request(f.first(),f.second()),"Other storage must be closed");f.first().closeContainer();
        GameModes.TRANSITIONS.add(f.first().getUUID());c.assertFalse(f.manager().request(f.first(),f.second()),"Pending transfer rejects trade");GameModes.TRANSITIONS.remove(f.first().getUUID());c.succeed();
    }
    @GameTest public void movingDyingDisconnectingAndInventoryDriftCancelWithoutEscrow(GameTestHelper c) {
        var f=fixture(c,"pt-leave");f.first().getInventory().setItem(0,new ItemStack(Items.DIAMOND,8));f.manager().offer(f.first(),0,2);
        f.second().setPos(f.first().getX()+20,f.first().getY(),f.first().getZ());f.manager().tick();c.assertTrue(f.manager().sessions.isEmpty(),"Distance cancels");
        f.second().setPos(f.first().position());f.manager().request(f.first(),f.second());f.manager().accept(f.second());f.manager().offer(f.first(),0,2);
        f.first().getInventory().getItem(0).shrink(1);f.manager().tick();c.assertTrue(f.manager().sessions.isEmpty(),"Changed source stack cancels without ghost offer");
        f.manager().request(f.first(),f.second());f.manager().accept(f.second());f.manager().offer(f.first(),0,2);f.manager().departing(f.second());c.assertTrue(f.manager().sessions.isEmpty(),"Disconnect hook cancels");
        f.manager().request(f.first(),f.second());f.manager().accept(f.second());f.manager().offer(f.first(),0,2);f.second().setHealth(0);f.manager().tick();
        c.assertTrue(f.manager().sessions.isEmpty(),"Death before confirmation cancels");c.assertValueEqual(total(f.first(),Items.DIAMOND),7,"Only explicit source decrement changed actual items");c.succeed();
    }
    @GameTest public void partiallySavedPlayerTradeRollsBackBothAndKeepsOtherState(GameTestHelper c) {
        var f=fixture(c,"pt-failure");var base=f.storage();
        var failing=new PlayerTrading.Storage(base.server,base.journals,base.players) {
            int calls;
            @Override void savePlayers(ServerPlayer a,ServerPlayer b) throws IOException {
                if(calls++==0){atomic(player(a.getUUID()),RewardTrades.serialize(a));throw new IOException("Injected second-player failure");}
                atomic(player(a.getUUID()),RewardTrades.serialize(a));atomic(player(b.getUUID()),RewardTrades.serialize(b));
            }
        };
        var manager=new PlayerTrading(base.server,failing);f.manager().cancel(f.manager().sessions.get(f.first().getUUID()),"test");
        GameModes.state(f.first()).putString("backpack-test","retain");f.first().getInventory().setItem(0,new ItemStack(Items.DIAMOND,8));f.second().getInventory().setItem(0,new ItemStack(Items.EMERALD,6));
        manager.request(f.first(),f.second());manager.accept(f.second());manager.offer(f.first(),0,2);manager.offer(f.second(),0,3);var s=manager.sessions.get(f.first().getUUID());
        manager.confirm(f.first(),s.version);c.assertFalse(manager.confirm(f.second(),s.version),"Partial player save is rejected");
        c.assertValueEqual(total(f.first(),Items.DIAMOND),8,"First debit restored");c.assertValueEqual(total(f.second(),Items.EMERALD),6,"Second debit restored");
        c.assertValueEqual(GameModes.state(f.first()).getStringOr("backpack-test",""),"retain","Unrelated global state remains");
        try{c.assertFalse(PlayerTrading.receipt(failing.readPlayer(f.first().getUUID()),s.id),"Disk rollback removes receipt");c.assertFalse(Files.exists(failing.journal(s.id)),"Verified rollback removes journal");}catch(IOException e){throw new RuntimeException(e);}c.succeed();
    }
    CompoundTag prepared(Fixture f,PlayerTrading.Session s) throws IOException {
        var beforeA=RewardTrades.serialize(f.first());var beforeB=RewardTrades.serialize(f.second());var originalA=PlayerTrading.inventory(f.first());var originalB=PlayerTrading.inventory(f.second());
        PlayerTrading.apply(f.first(),PlayerTrading.simulate(s,f.first()));PlayerTrading.apply(f.second(),PlayerTrading.simulate(s,f.second()));f.manager().mark(f.first(),s.id);f.manager().mark(f.second(),s.id);
        var afterA=RewardTrades.serialize(f.first());var afterB=RewardTrades.serialize(f.second());
        PlayerTrading.apply(f.first(),originalA);PlayerTrading.apply(f.second(),originalB);((ModePlayer)f.first()).infinity$state(beforeA.getCompoundOrEmpty("InfinityModes").copy());((ModePlayer)f.second()).infinity$state(beforeB.getCompoundOrEmpty("InfinityModes").copy());
        var data=new CompoundTag();data.putInt("format",1);data.putString("id",s.id.toString());data.putString("phase","forward");data.putString("first",f.first().getStringUUID());data.putString("second",f.second().getStringUUID());
        data.put("beforeFirst",beforeA);data.put("beforeSecond",beforeB);data.put("afterFirst",afterA);data.put("afterSecond",afterB);return data;
    }
    @GameTest public void startupTradeRecoveryCompletesBothOnceWithoutOverwritingNewerReceipts(GameTestHelper c) {
        var f=fixture(c,"pt-recovery");f.first().getInventory().setItem(0,new ItemStack(Items.DIAMOND,8));f.second().getInventory().setItem(0,new ItemStack(Items.EMERALD,6));
        f.manager().offer(f.first(),0,2);f.manager().offer(f.second(),0,3);var s=f.manager().sessions.get(f.first().getUUID());
        try {
            var data=prepared(f,s);f.storage().prepare(s.id,data);
            var newer=data.getCompoundOrEmpty("afterFirst").copy();newer.getCompoundOrEmpty("InfinityModes").putString("later","do not overwrite");
            f.storage().atomic(f.storage().player(f.first().getUUID()),newer);f.storage().atomic(f.storage().player(f.second().getUUID()),data.getCompoundOrEmpty("beforeSecond"));
            f.storage().recover();var second=f.storage().readPlayer(f.second().getUUID());
            c.assertTrue(PlayerTrading.receipt(second,s.id),"Second saved image recovered");c.assertTrue(PlayerTrading.matches(second,data.getCompoundOrEmpty("afterSecond")),"Recovered debit matches receipt");
            c.assertValueEqual(f.storage().readPlayer(f.first().getUUID()).getCompoundOrEmpty("InfinityModes").getStringOr("later",""),"do not overwrite","Already committed player retains later progress");
            f.storage().recover();c.assertTrue(PlayerTrading.matches(second,f.storage().readPlayer(f.second().getUUID())),"Repeated recovery is idempotent");
        }catch(IOException e){throw new RuntimeException(e);}f.manager().cancel(s,"test");c.succeed();
    }
    @GameTest public void corruptTradeJournalFailsClosedWithoutChangingPlayerFiles(GameTestHelper c) {
        var f=fixture(c,"pt-corrupt");
        try {
            f.storage().savePlayers(f.first(),f.second());var original=f.storage().readPlayer(f.first().getUUID());var id=UUID.randomUUID();var bad=new CompoundTag();bad.putInt("format",1);bad.putString("id",id.toString());bad.putString("phase","unknown");f.storage().prepare(id,bad);
            boolean rejected=false;try{f.storage().recover();}catch(IOException e){rejected=true;}
            c.assertTrue(rejected,"Unexpected journal format stops recovery");
            c.assertTrue(PlayerTrading.matches(original,f.storage().readPlayer(f.first().getUUID())),"Corrupt journal never overwrites player data");
            c.assertTrue(Files.exists(f.storage().journal(id)),"Corrupt evidence is preserved");
        }catch(IOException e){throw new RuntimeException(e);}f.manager().cancel(f.manager().sessions.get(f.first().getUUID()),"test");c.succeed();
    }
}

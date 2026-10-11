package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

public class PlayerTradingGameTests {
    static class Store extends PlayerTrading.Storage {
        Store(MinecraftServer server) throws IOException {this(server,Files.createTempDirectory("infinity-p2p-test-"));}
        Store(MinecraftServer server,java.nio.file.Path dir) {super(server,dir.resolve("journals"),dir.resolve("players"));}
        @Override void savePlayers(ServerPlayerEntity a,ServerPlayerEntity b) throws IOException {
            atomic(player(a.getUuid()),RewardTrades.serialize(a));atomic(player(b.getUuid()),RewardTrades.serialize(b));
        }
    }
    record Fixture(PlayerTrading manager,ServerPlayerEntity first,ServerPlayerEntity second,Store storage) {}
    Fixture fixture(TestContext c,String name) {
        try {
            var a=new ModeGameTests().player(c,name+"-a");var b=new ModeGameTests().player(c,name+"-b");
            a.changeGameMode(GameMode.SURVIVAL);b.changeGameMode(GameMode.SURVIVAL);b.setPosition(a.getEntityPos());
            var storage=new Store(c.getWorld().getServer());var manager=new PlayerTrading(c.getWorld().getServer(),storage);
            c.assertTrue(manager.request(a,b),"Nearby Survival players can request trade");c.assertTrue(manager.accept(b),"Recipient explicitly accepts");
            return new Fixture(manager,a,b,storage);
        }catch(IOException e){throw new RuntimeException(e);}
    }
    int total(ServerPlayerEntity p,net.minecraft.item.Item item) {return p.getInventory().getMainStacks().stream().filter(s->s.isOf(item)).mapToInt(ItemStack::getCount).sum();}
    @GameTest public void playerTradeRequiresBothConfirmationsAndPreservesComponents(TestContext c) {
        var f=fixture(c,"pt-both");var gift=new ItemStack(Items.DIAMOND,9);gift.set(DataComponentTypes.CUSTOM_NAME,Text.literal("Gift"));f.first().getInventory().setStack(0,gift);
        f.second().getInventory().setStack(0,new ItemStack(Items.EMERALD,7));
        c.assertTrue(f.manager().offer(f.first(),0,4),"Offer selects a main slot without escrow");c.assertTrue(f.manager().offer(f.second(),0,3),"Partner adds offer");
        var s=f.manager().sessions.get(f.first().getUuid());int version=s.version;
        c.assertTrue(f.manager().confirm(f.first(),version),"First confirmation is accepted");
        c.assertEquals(total(f.first(),Items.DIAMOND),9,"First confirmation moves nothing");
        c.assertTrue(f.manager().confirm(f.second(),version),"Second confirmation exchanges");
        c.assertEquals(total(f.first(),Items.DIAMOND),5,"Only offered count debited");c.assertEquals(total(f.first(),Items.EMERALD),3,"Partner items received");
        c.assertEquals(total(f.second(),Items.EMERALD),4,"Partner debit exact");c.assertEquals(total(f.second(),Items.DIAMOND),4,"Gift received");
        c.assertTrue(f.second().getInventory().getMainStacks().stream().filter(s2->s2.isOf(Items.DIAMOND)).allMatch(s2->ItemStack.areItemsAndComponentsEqual(s2,gift)),"Custom names/components preserved");
        c.assertFalse(f.manager().confirm(f.second(),version),"Repeated confirmation cannot repeat exchange");
        c.assertEquals(total(f.second(),Items.DIAMOND),4,"Repeated command adds nothing");c.complete();
    }
    @GameTest public void changingOffersClearsBothConfirmationsAndRejectsOldScreen(TestContext c) {
        var f=fixture(c,"pt-dirty");f.first().getInventory().setStack(0,new ItemStack(Items.DIAMOND,8));
        f.manager().offer(f.first(),0,2);var s=f.manager().sessions.get(f.first().getUuid());int old=s.version;
        c.assertTrue(f.second().currentScreenHandler instanceof PlayerTrading.ReviewHandler,"Actual vanilla window uses the read-only trade handler");
        ScreenHandler oldScreen=f.second().currentScreenHandler;
        f.manager().confirm(f.first(),old);c.assertTrue(s.confirmed.contains(f.first().getUuid()),"First player initially confirms");
        f.manager().offer(f.first(),0,3);
        c.assertTrue(s.confirmed.isEmpty(),"Changed offer clears every confirmation");
        c.assertTrue(f.second().currentScreenHandler.syncId!=oldScreen.syncId,"New offer opens a fresh vanilla window");
        oldScreen.onSlotClick(49,0,SlotActionType.PICKUP,f.second());
        c.assertTrue(s.confirmed.isEmpty(),"Old GUI confirmation cannot authorize changed offer");
        c.assertFalse(f.manager().confirm(f.first(),old),"Old command revision rejected");c.assertEquals(total(f.first(),Items.DIAMOND),8,"No escrow debit");c.complete();
    }
    @GameTest public void playerTradeReviewDeniesPickupDragDropSwapAndQuickMove(TestContext c) {
        var f=fixture(c,"pt-gui");f.first().getInventory().setStack(0,new ItemStack(Items.DIAMOND,8));f.manager().offer(f.first(),0,2);
        c.assertTrue(f.first().currentScreenHandler instanceof PlayerTrading.ReviewHandler,"Trade review opens its guarded handler");
        ScreenHandler handler=f.first().currentScreenHandler;
        for(var action:SlotActionType.values())handler.onSlotClick(0,0,action,f.first());
        handler.onSlotClick(54,0,SlotActionType.PICKUP,f.first());handler.selectBundleStack(0,1);
        c.assertTrue(handler.quickMove(f.first(),0).isEmpty(),"Shift transfer returns nothing");
        c.assertTrue(handler.getCursorStack().isEmpty(),"GUI cannot create a cursor item");c.assertEquals(total(f.first(),Items.DIAMOND),8,"Actual inventory unchanged");
        c.assertEquals(((PlayerTrading.ReviewHandler)handler).view.getStack(0).getCount(),2,"Review contains only the offered display copy");c.complete();
    }
    @GameTest public void playerTradeFullRecipientInventoryAbortsWithoutLoss(TestContext c) {
        var f=fixture(c,"pt-full");f.first().getInventory().setStack(0,new ItemStack(Items.DIAMOND,1));
        for(int i=0;i<36;i++)f.second().getInventory().setStack(i,new ItemStack(Items.COBBLESTONE,64));
        f.manager().offer(f.first(),0,1);var s=f.manager().sessions.get(f.first().getUuid());
        f.manager().confirm(f.first(),s.version);c.assertFalse(f.manager().confirm(f.second(),s.version),"No incoming capacity rejects exchange");
        c.assertEquals(total(f.first(),Items.DIAMOND),1,"Sender keeps offered item");c.assertEquals(total(f.second(),Items.COBBLESTONE),36*64,"Full recipient unchanged");
        c.assertTrue(s.confirmed.isEmpty(),"Capacity failure clears both confirmations");c.complete();
    }
    @GameTest public void playerTradeFullInventoriesCanExchangeVacatedStacks(TestContext c) {
        var f=fixture(c,"pt-swap");
        for(int i=0;i<36;i++){f.first().getInventory().setStack(i,new ItemStack(Items.COBBLESTONE,64));f.second().getInventory().setStack(i,new ItemStack(Items.DIRT,64));}
        f.manager().offer(f.first(),0,64);f.manager().offer(f.second(),0,64);var s=f.manager().sessions.get(f.first().getUuid());
        f.manager().confirm(f.first(),s.version);c.assertTrue(f.manager().confirm(f.second(),s.version),"Simulation uses slots vacated by outgoing full stacks");
        c.assertEquals(total(f.first(),Items.DIRT),64,"Incoming dirt fits exact vacated slot");c.assertEquals(total(f.second(),Items.COBBLESTONE),64,"Incoming stone fits");
        c.assertEquals(total(f.first(),Items.COBBLESTONE)+total(f.second(),Items.COBBLESTONE),36*64,"Global stone count conserved");c.complete();
    }
    @GameTest public void playerTradesRejectCreativeProfilesContainersAndCursors(TestContext c) {
        var f=fixture(c,"pt-safe");var s=f.manager().sessions.get(f.first().getUuid());f.manager().cancel(s,"test");
        f.first().changeGameMode(GameMode.CREATIVE);c.assertFalse(f.manager().request(f.first(),f.second()),"Actual Creative cannot trade");
        f.first().changeGameMode(GameMode.SURVIVAL);GameModes.state(f.first()).putString("active","CREATIVE");
        c.assertFalse(f.manager().request(f.first(),f.second()),"Creative profile cannot trade despite native Survival");GameModes.state(f.first()).putString("active","SURVIVAL");
        f.first().playerScreenHandler.setCursorStack(new ItemStack(Items.DIAMOND));c.assertFalse(f.manager().request(f.first(),f.second()),"Cursor item rejects request");
        c.assertEquals(f.first().playerScreenHandler.getCursorStack().getCount(),1,"Request never takes cursor");f.first().playerScreenHandler.setCursorStack(ItemStack.EMPTY);
        f.first().openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inv,p)->GenericContainerScreenHandler.createGeneric9x3(sync,inv,new SimpleInventory(27)),Text.literal("Other storage")));
        c.assertFalse(f.manager().request(f.first(),f.second()),"Other storage must be closed");f.first().closeHandledScreen();
        GameModes.TRANSITIONS.add(f.first().getUuid());c.assertFalse(f.manager().request(f.first(),f.second()),"Pending transfer rejects trade");GameModes.TRANSITIONS.remove(f.first().getUuid());c.complete();
    }
    @GameTest public void movingDyingDisconnectingAndInventoryDriftCancelWithoutEscrow(TestContext c) {
        var f=fixture(c,"pt-leave");f.first().getInventory().setStack(0,new ItemStack(Items.DIAMOND,8));f.manager().offer(f.first(),0,2);
        f.second().setPosition(f.first().getX()+20,f.first().getY(),f.first().getZ());f.manager().tick();c.assertTrue(f.manager().sessions.isEmpty(),"Distance cancels");
        f.second().setPosition(f.first().getEntityPos());f.manager().request(f.first(),f.second());f.manager().accept(f.second());f.manager().offer(f.first(),0,2);
        f.first().getInventory().getStack(0).decrement(1);f.manager().tick();c.assertTrue(f.manager().sessions.isEmpty(),"Changed source stack cancels without ghost offer");
        f.manager().request(f.first(),f.second());f.manager().accept(f.second());f.manager().offer(f.first(),0,2);f.manager().departing(f.second());c.assertTrue(f.manager().sessions.isEmpty(),"Disconnect hook cancels");
        f.manager().request(f.first(),f.second());f.manager().accept(f.second());f.manager().offer(f.first(),0,2);f.second().setHealth(0);f.manager().tick();
        c.assertTrue(f.manager().sessions.isEmpty(),"Death before confirmation cancels");c.assertEquals(total(f.first(),Items.DIAMOND),7,"Only explicit source decrement changed actual items");c.complete();
    }
    @GameTest public void partiallySavedPlayerTradeRollsBackBothAndKeepsOtherState(TestContext c) {
        var f=fixture(c,"pt-failure");var base=f.storage();
        var failing=new PlayerTrading.Storage(base.server,base.journals,base.players) {
            int calls;
            @Override void savePlayers(ServerPlayerEntity a,ServerPlayerEntity b) throws IOException {
                if(calls++==0){atomic(player(a.getUuid()),RewardTrades.serialize(a));throw new IOException("Injected second-player failure");}
                atomic(player(a.getUuid()),RewardTrades.serialize(a));atomic(player(b.getUuid()),RewardTrades.serialize(b));
            }
        };
        var manager=new PlayerTrading(base.server,failing);f.manager().cancel(f.manager().sessions.get(f.first().getUuid()),"test");
        GameModes.state(f.first()).putString("backpack-test","retain");f.first().getInventory().setStack(0,new ItemStack(Items.DIAMOND,8));f.second().getInventory().setStack(0,new ItemStack(Items.EMERALD,6));
        manager.request(f.first(),f.second());manager.accept(f.second());manager.offer(f.first(),0,2);manager.offer(f.second(),0,3);var s=manager.sessions.get(f.first().getUuid());
        manager.confirm(f.first(),s.version);c.assertFalse(manager.confirm(f.second(),s.version),"Partial player save is rejected");
        c.assertEquals(total(f.first(),Items.DIAMOND),8,"First debit restored");c.assertEquals(total(f.second(),Items.EMERALD),6,"Second debit restored");
        c.assertEquals(GameModes.state(f.first()).getString("backpack-test",""),"retain","Unrelated global state remains");
        try{c.assertFalse(PlayerTrading.receipt(failing.readPlayer(f.first().getUuid()),s.id),"Disk rollback removes receipt");c.assertFalse(Files.exists(failing.journal(s.id)),"Verified rollback removes journal");}catch(IOException e){throw new RuntimeException(e);}c.complete();
    }
    NbtCompound prepared(Fixture f,PlayerTrading.Session s) throws IOException {
        var beforeA=RewardTrades.serialize(f.first());var beforeB=RewardTrades.serialize(f.second());var originalA=PlayerTrading.inventory(f.first());var originalB=PlayerTrading.inventory(f.second());
        PlayerTrading.apply(f.first(),PlayerTrading.simulate(s,f.first()));PlayerTrading.apply(f.second(),PlayerTrading.simulate(s,f.second()));f.manager().mark(f.first(),s.id);f.manager().mark(f.second(),s.id);
        var afterA=RewardTrades.serialize(f.first());var afterB=RewardTrades.serialize(f.second());
        PlayerTrading.apply(f.first(),originalA);PlayerTrading.apply(f.second(),originalB);((ModePlayer)f.first()).infinity$state(beforeA.getCompoundOrEmpty("InfinityModes").copy());((ModePlayer)f.second()).infinity$state(beforeB.getCompoundOrEmpty("InfinityModes").copy());
        var data=new NbtCompound();data.putInt("format",1);data.putString("id",s.id.toString());data.putString("phase","forward");data.putString("first",f.first().getUuidAsString());data.putString("second",f.second().getUuidAsString());
        data.put("beforeFirst",beforeA);data.put("beforeSecond",beforeB);data.put("afterFirst",afterA);data.put("afterSecond",afterB);return data;
    }
    @GameTest public void startupTradeRecoveryCompletesBothOnceWithoutOverwritingNewerReceipts(TestContext c) {
        var f=fixture(c,"pt-recovery");f.first().getInventory().setStack(0,new ItemStack(Items.DIAMOND,8));f.second().getInventory().setStack(0,new ItemStack(Items.EMERALD,6));
        f.manager().offer(f.first(),0,2);f.manager().offer(f.second(),0,3);var s=f.manager().sessions.get(f.first().getUuid());
        try {
            var data=prepared(f,s);f.storage().prepare(s.id,data);
            var newer=data.getCompoundOrEmpty("afterFirst").copy();newer.getCompoundOrEmpty("InfinityModes").putString("later","do not overwrite");
            f.storage().atomic(f.storage().player(f.first().getUuid()),newer);f.storage().atomic(f.storage().player(f.second().getUuid()),data.getCompoundOrEmpty("beforeSecond"));
            f.storage().recover();var second=f.storage().readPlayer(f.second().getUuid());
            c.assertTrue(PlayerTrading.receipt(second,s.id),"Second saved image recovered");c.assertTrue(PlayerTrading.matches(second,data.getCompoundOrEmpty("afterSecond")),"Recovered debit matches receipt");
            c.assertEquals(f.storage().readPlayer(f.first().getUuid()).getCompoundOrEmpty("InfinityModes").getString("later",""),"do not overwrite","Already committed player retains later progress");
            f.storage().recover();c.assertTrue(PlayerTrading.matches(second,f.storage().readPlayer(f.second().getUuid())),"Repeated recovery is idempotent");
        }catch(IOException e){throw new RuntimeException(e);}f.manager().cancel(s,"test");c.complete();
    }
    @GameTest public void corruptTradeJournalFailsClosedWithoutChangingPlayerFiles(TestContext c) {
        var f=fixture(c,"pt-corrupt");
        try {
            f.storage().savePlayers(f.first(),f.second());var original=f.storage().readPlayer(f.first().getUuid());var id=UUID.randomUUID();var bad=new NbtCompound();bad.putInt("format",1);bad.putString("id",id.toString());bad.putString("phase","unknown");f.storage().prepare(id,bad);
            boolean rejected=false;try{f.storage().recover();}catch(IOException e){rejected=true;}
            c.assertTrue(rejected,"Unexpected journal format stops recovery");
            c.assertTrue(PlayerTrading.matches(original,f.storage().readPlayer(f.first().getUuid())),"Corrupt journal never overwrites player data");
            c.assertTrue(Files.exists(f.storage().journal(id)),"Corrupt evidence is preserved");
        }catch(IOException e){throw new RuntimeException(e);}f.manager().cancel(f.manager().sessions.get(f.first().getUuid()),"test");c.complete();
    }
}

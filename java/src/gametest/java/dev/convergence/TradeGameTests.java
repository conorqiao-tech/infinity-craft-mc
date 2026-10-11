package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

public class TradeGameTests {
    ServerPlayerEntity player(TestContext c,String name) {
        var p=new ModeGameTests().player(c,name);p.changeGameMode(GameMode.SURVIVAL);return p;
    }
    static final class Store implements RewardTrades.PlayerStore {
        final Path file;
        Store() {try{file=Files.createTempDirectory("infinity-trade-test-").resolve("player.dat");}catch(IOException e){throw new RuntimeException(e);}}
        public void save(ServerPlayerEntity p) throws IOException {NbtIo.writeCompressed(RewardTrades.serialize(p),file);}
        public NbtCompound read(ServerPlayerEntity p) throws IOException {return NbtIo.readCompressed(file,NbtSizeTracker.of(64L*1024*1024));}
    }
    void explorerCost(ServerPlayerEntity p) {
        p.getInventory().setStack(0,new ItemStack(Items.EMERALD,32));p.getInventory().setStack(1,new ItemStack(Items.ENDER_PEARL,4));
    }
    @GameTest public void tradesRejectInsufficientOrModifiedPaymentWithoutDebit(TestContext c) {
        var p=player(c,"trade-short");var store=new Store();p.getInventory().setStack(0,new ItemStack(Items.EMERALD,31));p.getInventory().setStack(1,new ItemStack(Items.ENDER_PEARL,4));
        var named=new ItemStack(Items.EMERALD,64);named.set(DataComponentTypes.CUSTOM_NAME,Text.literal("Keep me"));p.getInventory().setStack(2,named);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Named emeralds cannot fill a payment shortfall");
        c.assertEquals(p.getInventory().getStack(0).getCount(),31,"Insufficient payment is untouched");
        c.assertEquals(p.getInventory().getStack(1).getCount(),4,"Other cost items are untouched");
        c.assertEquals(p.getInventory().getStack(2).getCount(),64,"Modified items are untouched");
        c.assertFalse(RewardTrades.hasPower(p,"explorer"),"Failed trade grants nothing");c.complete();
    }
    @GameTest public void tradesDebitExactMainStacksAndPreserveUnrelatedStorage(TestContext c) {
        var p=player(c,"trade-exact");var store=new Store();
        p.getInventory().setStack(0,new ItemStack(Items.EMERALD,16));p.getInventory().setStack(1,new ItemStack(Items.EMERALD,20));p.getInventory().setStack(2,new ItemStack(Items.ENDER_PEARL,10));
        var named=new ItemStack(Items.EMERALD,64);named.set(DataComponentTypes.CUSTOM_NAME,Text.literal("Protected"));p.getInventory().setStack(3,named);
        p.getInventory().setStack(4,new ItemStack(Items.DIAMOND,7));p.equipStack(EquipmentSlot.OFFHAND,new ItemStack(Items.EMERALD,13));
        p.equipStack(EquipmentSlot.HEAD,new ItemStack(Items.EMERALD,9));p.getEnderChestInventory().setStack(0,new ItemStack(Items.EMERALD,11));
        c.assertTrue(RewardTrades.purchase(p,"explorer",store),"Exact ordinary payment succeeds");
        c.assertTrue(p.getInventory().getStack(0).isEmpty(),"First emerald stack debited");
        c.assertEquals(p.getInventory().getStack(1).getCount(),4,"Only 32 emeralds consumed across stacks");
        c.assertEquals(p.getInventory().getStack(2).getCount(),6,"Only four pearls consumed");
        c.assertTrue(ItemStack.areItemsAndComponentsEqual(p.getInventory().getStack(3),named),"Named components retained");c.assertEquals(named.getCount(),64,"Named count retained");
        c.assertEquals(p.getInventory().getStack(4).getCount(),7,"Unrelated diamonds retained");
        c.assertEquals(p.getOffHandStack().getCount(),13,"Offhand retained");c.assertEquals(p.getEquippedStack(EquipmentSlot.HEAD).getCount(),9,"Armor slot retained");
        c.assertEquals(p.getEnderChestInventory().getStack(0).getCount(),11,"Ender Chest retained");c.complete();
    }
    @GameTest public void tradesDoNotChargeRepeatedOrAchievementOwnedUnlocks(TestContext c) {
        var p=player(c,"trade-repeat");var store=new Store();explorerCost(p);
        c.assertTrue(RewardTrades.purchase(p,"explorer",store),"First permanent receipt issued");explorerCost(p);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Same receipt cannot be bought twice");
        c.assertEquals(p.getInventory().getStack(0).getCount(),32,"Repeated command leaves items");
        var advancement=c.getWorld().getServer().getAdvancementLoader().get(Identifier.of("minecraft","nether/obtain_blaze_rod"));
        for(var criterion:advancement.value().criteria().keySet())p.getAdvancementTracker().grantCriterion(advancement,criterion);
        p.getInventory().setStack(2,new ItemStack(Items.BLAZE_ROD,4));
        c.assertFalse(RewardTrades.purchase(p,"fireguard",store),"Achievement-owned power is not charged");
        c.assertEquals(p.getInventory().getStack(2).getCount(),4,"Achievement ownership preserves rods");c.complete();
    }
    @GameTest public void tradedRanksAndPowersStaySeparateAndUseRealBadges(TestContext c) {
        var p=player(c,"trade-rank");var store=new Store();p.getInventory().setStack(0,new ItemStack(Items.IRON_INGOT,16));p.getInventory().setStack(1,new ItemStack(Items.EMERALD,8));
        c.assertTrue(RewardTrades.purchase(p,"rank-go",store),"Rank has its own explicit trade ID");
        c.assertEquals(RewardTrades.rank(p),Memberships.Tier.GO,"Rank receipt is visible");
        c.assertFalse(RewardTrades.hasPower(p,"hacks"),"A rank does not grant a bundle");
        var members=Memberships.get(c.getWorld().getServer());
        c.assertEquals(members.tier(p.getUuid()),Memberships.Tier.GO,"Membership badge sees bought rank");
        c.assertEquals(members.account(p.getUuid()).earned,Memberships.Tier.FREE,"Trade rank is not copied into world JSON");
        c.assertEquals(c.getWorld().getServer().getScoreboard().getScoreHolderTeam(p.getNameForScoreboard()).getName(),"infinity_go","Badge uses receipt rank");
        explorerCost(p);c.assertTrue(RewardTrades.purchase(p,"explorer",store),"Power can be bought independently");
        c.assertEquals(RewardTrades.rank(p),Memberships.Tier.GO,"Power does not raise cosmetic rank");
        c.assertTrue(RewardTrades.hasPower(p,"explorer"),"Explorer power receipt exists");
        c.assertFalse(AchievementRewards.get(c.getWorld().getServer()).account(p.getUuid()).unlocked.contains("cosmetic:explorer"),"Explorer power is not Explorer cosmetic");c.complete();
    }
    @GameTest public void tradeReceiptsSharePlayerNbtAndStayOutsideModeProfiles(TestContext c) {
        var p=player(c,"trade-persist");var store=new Store();explorerCost(p);
        var profiles=new NbtCompound();var creative=new NbtCompound();creative.putString("marker","creative untouched");profiles.put("CREATIVE",creative);GameModes.state(p).put("profiles",profiles);
        c.assertTrue(RewardTrades.purchase(p,"explorer",store),"Payment and receipt saved");
        try {
            var disk=store.read(p);var fresh=player(c,"trade-restored");var freshId=fresh.getUuid();
            c.assertFalse(freshId.equals(p.getUuid()),"Restored fixture has a distinct connected player identity");
            fresh.readData(NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),disk));
            c.assertEquals(fresh.getUuid(),freshId,"Player profile keeps its distinct UUID after reading saved NBT");
            c.assertTrue(RewardTrades.hasPower(fresh,"explorer"),"Receipt survives real compressed player serialization");
            c.assertTrue(fresh.getInventory().isEmpty(),"Paid items stay debited after reload");
            c.assertEquals(GameModes.state(fresh).getCompoundOrEmpty("profiles").getCompoundOrEmpty("CREATIVE").getString("marker",""),"creative untouched","Other profile persists");
            c.assertFalse(GameModes.state(fresh).getCompoundOrEmpty("profiles").getCompoundOrEmpty("CREATIVE").contains(RewardTrades.RECEIPTS),"Global unlock is not copied into a mode");
            fresh.copyFrom(p,false);c.assertTrue(RewardTrades.hasPower(fresh,"explorer"),"Respawn copy preserves receipt");
        }catch(IOException e){throw new RuntimeException(e);}c.complete();
    }
    @GameTest public void tradesRequireActualSurvivalAndNoCombatOrModeTransition(TestContext c) {
        var p=player(c,"trade-mode");var store=new Store();explorerCost(p);p.changeGameMode(GameMode.CREATIVE);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Actual Creative is rejected even with Survival profile");
        p.changeGameMode(GameMode.SURVIVAL);GameModes.state(p).putString("active","CREATIVE");
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Non-Survival profile is rejected");
        GameModes.state(p).putString("active","SURVIVAL");CommunityServer.get(c.getWorld().getServer()).combat.put(p.getUuid(),c.getWorld().getServer().getTicks()+200);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Combat blocks a trade");CommunityServer.get(c.getWorld().getServer()).combat.remove(p.getUuid());
        GameModes.TRANSITIONS.add(p.getUuid());c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Transfer blocks trading");GameModes.TRANSITIONS.remove(p.getUuid());
        c.assertEquals(p.getInventory().getStack(0).getCount(),32,"Restrictions never take payment");c.complete();
    }
    @GameTest public void silentlyFailedSaveRollsBackOriginalItemsAndGlobalState(TestContext c) {
        var p=player(c,"trade-save-fail");explorerCost(p);GameModes.state(p).putString("keep","global marker");var before=RewardTrades.serialize(p);
        var silent=new RewardTrades.PlayerStore(){public void save(ServerPlayerEntity ignored){}public NbtCompound read(ServerPlayerEntity ignored){return before;}};
        c.assertFalse(RewardTrades.purchase(p,"explorer",silent),"A swallowed save failure is detected from disk receipt");
        c.assertEquals(p.getInventory().getStack(0).getCount(),32,"Emeralds restored");c.assertEquals(p.getInventory().getStack(1).getCount(),4,"Pearls restored");
        c.assertFalse(RewardTrades.hasPower(p,"explorer"),"Unsaved receipt rolled back");
        c.assertEquals(GameModes.state(p).getString("keep",""),"global marker","Other global state restored");c.complete();
    }
    @GameTest public void mismatchedReceiptAndInventorySaveIsRejected(TestContext c) {
        var p=player(c,"trade-mismatch");explorerCost(p);var before=RewardTrades.serialize(p);
        var mismatch=new RewardTrades.PlayerStore() {
            NbtCompound disk;
            public void save(ServerPlayerEntity player){disk=RewardTrades.serialize(player);disk.put("Inventory",before.get("Inventory").copy());}
            public NbtCompound read(ServerPlayerEntity ignored){return disk;}
        };
        c.assertFalse(RewardTrades.purchase(p,"explorer",mismatch),"Receipt alone cannot establish saved payment");
        c.assertEquals(p.getInventory().getStack(0).getCount(),32,"Bad save restores original emeralds");
        c.assertFalse(RewardTrades.hasPower(p,"explorer"),"Bad save grants no active reward");c.complete();
    }
    @GameTest public void vanillaSaveContainsReceiptAndDebitedInventoryTogether(TestContext c) {
        var p=player(c,"trade-vanilla");p.getInventory().setStack(0,new ItemStack(Items.PRISMARINE_SHARD,16));p.getInventory().setStack(1,new ItemStack(Items.EMERALD,8));
        c.assertTrue(RewardTrades.purchase(p,"aquatic"),"Real PlayerManager forced save verifies success");
        try {
            var disk=RewardTrades.VANILLA.read(p);
            c.assertTrue(RewardTrades.receipt(disk.getCompoundOrEmpty("InfinityModes"),"aquatic"),"Vanilla disk contains permanent receipt");
            c.assertTrue(java.util.Objects.equals(disk.get("Inventory"),RewardTrades.serialize(p).get("Inventory")),"Same player replacement stores paid inventory");
        }catch(IOException e){throw new RuntimeException(e);}c.complete();
    }
}

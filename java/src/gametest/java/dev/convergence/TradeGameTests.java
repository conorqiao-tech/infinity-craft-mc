package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;

public class TradeGameTests {
    ServerPlayer player(GameTestHelper c,String name) {
        var p=new ModeGameTests().player(c,name);p.setGameMode(GameType.SURVIVAL);return p;
    }
    static final class Store implements RewardTrades.PlayerStore {
        final Path file;
        Store() {try{file=Files.createTempDirectory("infinity-trade-test-").resolve("player.dat");}catch(IOException e){throw new RuntimeException(e);}}
        public void save(ServerPlayer p) throws IOException {NbtIo.writeCompressed(RewardTrades.serialize(p),file);}
        public CompoundTag read(ServerPlayer p) throws IOException {return NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));}
    }
    void explorerCost(ServerPlayer p) {
        p.getInventory().setItem(0,new ItemStack(Items.EMERALD,32));p.getInventory().setItem(1,new ItemStack(Items.ENDER_PEARL,4));
    }
    @GameTest public void tradesRejectInsufficientOrModifiedPaymentWithoutDebit(GameTestHelper c) {
        var p=player(c,"trade-short");var store=new Store();p.getInventory().setItem(0,new ItemStack(Items.EMERALD,31));p.getInventory().setItem(1,new ItemStack(Items.ENDER_PEARL,4));
        var named=new ItemStack(Items.EMERALD,64);named.set(DataComponents.CUSTOM_NAME,Component.literal("Keep me"));p.getInventory().setItem(2,named);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Named emeralds cannot fill a payment shortfall");
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),31,"Insufficient payment is untouched");
        c.assertValueEqual(p.getInventory().getItem(1).getCount(),4,"Other cost items are untouched");
        c.assertValueEqual(p.getInventory().getItem(2).getCount(),64,"Modified items are untouched");
        c.assertFalse(RewardTrades.hasPower(p,"explorer"),"Failed trade grants nothing");c.succeed();
    }
    @GameTest public void tradesDebitExactMainStacksAndPreserveUnrelatedStorage(GameTestHelper c) {
        var p=player(c,"trade-exact");var store=new Store();
        p.getInventory().setItem(0,new ItemStack(Items.EMERALD,16));p.getInventory().setItem(1,new ItemStack(Items.EMERALD,20));p.getInventory().setItem(2,new ItemStack(Items.ENDER_PEARL,10));
        var named=new ItemStack(Items.EMERALD,64);named.set(DataComponents.CUSTOM_NAME,Component.literal("Protected"));p.getInventory().setItem(3,named);
        p.getInventory().setItem(4,new ItemStack(Items.DIAMOND,7));p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.EMERALD,13));
        p.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.EMERALD,9));p.getEnderChestInventory().setItem(0,new ItemStack(Items.EMERALD,11));
        c.assertTrue(RewardTrades.purchase(p,"explorer",store),"Exact ordinary payment succeeds");
        c.assertTrue(p.getInventory().getItem(0).isEmpty(),"First emerald stack debited");
        c.assertValueEqual(p.getInventory().getItem(1).getCount(),4,"Only 32 emeralds consumed across stacks");
        c.assertValueEqual(p.getInventory().getItem(2).getCount(),6,"Only four pearls consumed");
        c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(3),named),"Named components retained");c.assertValueEqual(named.getCount(),64,"Named count retained");
        c.assertValueEqual(p.getInventory().getItem(4).getCount(),7,"Unrelated diamonds retained");
        c.assertValueEqual(p.getOffhandItem().getCount(),13,"Offhand retained");c.assertValueEqual(p.getItemBySlot(EquipmentSlot.HEAD).getCount(),9,"Armor slot retained");
        c.assertValueEqual(p.getEnderChestInventory().getItem(0).getCount(),11,"Ender Chest retained");c.succeed();
    }
    @GameTest public void tradesDoNotChargeRepeatedOrAchievementOwnedUnlocks(GameTestHelper c) {
        var p=player(c,"trade-repeat");var store=new Store();explorerCost(p);
        c.assertTrue(RewardTrades.purchase(p,"explorer",store),"First permanent receipt issued");explorerCost(p);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Same receipt cannot be bought twice");
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),32,"Repeated command leaves items");
        var advancement=c.getLevel().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath("minecraft","nether/obtain_blaze_rod"));
        for(var criterion:advancement.value().criteria().keySet())p.getAdvancements().award(advancement,criterion);
        p.getInventory().setItem(2,new ItemStack(Items.BLAZE_ROD,4));
        c.assertFalse(RewardTrades.purchase(p,"fireguard",store),"Achievement-owned power is not charged");
        c.assertValueEqual(p.getInventory().getItem(2).getCount(),4,"Achievement ownership preserves rods");c.succeed();
    }
    @GameTest public void tradedRanksAndPowersStaySeparateAndUseRealBadges(GameTestHelper c) {
        var p=player(c,"trade-rank");var store=new Store();p.getInventory().setItem(0,new ItemStack(Items.IRON_INGOT,16));p.getInventory().setItem(1,new ItemStack(Items.EMERALD,8));
        c.assertTrue(RewardTrades.purchase(p,"rank-go",store),"Rank has its own explicit trade ID");
        c.assertValueEqual(RewardTrades.rank(p),Memberships.Tier.GO,"Rank receipt is visible");
        c.assertFalse(RewardTrades.hasPower(p,"hacks"),"A rank does not grant a bundle");
        var members=Memberships.get(c.getLevel().getServer());
        c.assertValueEqual(members.tier(p.getUUID()),Memberships.Tier.GO,"Membership badge sees bought rank");
        c.assertValueEqual(members.account(p.getUUID()).earned,Memberships.Tier.FREE,"Trade rank is not copied into world JSON");
        c.assertValueEqual(c.getLevel().getServer().getScoreboard().getPlayersTeam(p.getScoreboardName()).getName(),"infinity_go","Badge uses receipt rank");
        explorerCost(p);c.assertTrue(RewardTrades.purchase(p,"explorer",store),"Power can be bought independently");
        c.assertValueEqual(RewardTrades.rank(p),Memberships.Tier.GO,"Power does not raise cosmetic rank");
        c.assertTrue(RewardTrades.hasPower(p,"explorer"),"Explorer power receipt exists");
        c.assertFalse(AchievementRewards.get(c.getLevel().getServer()).account(p.getUUID()).unlocked.contains("cosmetic:explorer"),"Explorer power is not Explorer cosmetic");c.succeed();
    }
    @GameTest public void tradeReceiptsSharePlayerNbtAndStayOutsideModeProfiles(GameTestHelper c) {
        var p=player(c,"trade-persist");var store=new Store();explorerCost(p);
        var profiles=new CompoundTag();var creative=new CompoundTag();creative.putString("marker","creative untouched");profiles.put("CREATIVE",creative);GameModes.state(p).put("profiles",profiles);
        c.assertTrue(RewardTrades.purchase(p,"explorer",store),"Payment and receipt saved");
        try {
            var disk=store.read(p);var fresh=player(c,"trade-restored");var freshId=fresh.getUUID();
            c.assertFalse(freshId.equals(p.getUUID()),"Restored fixture has a distinct connected player identity");
            fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),disk));
            c.assertValueEqual(fresh.getUUID(),freshId,"Player profile keeps its distinct UUID after reading saved NBT");
            c.assertTrue(RewardTrades.hasPower(fresh,"explorer"),"Receipt survives real compressed player serialization");
            c.assertTrue(fresh.getInventory().isEmpty(),"Paid items stay debited after reload");
            c.assertValueEqual(GameModes.state(fresh).getCompoundOrEmpty("profiles").getCompoundOrEmpty("CREATIVE").getStringOr("marker",""),"creative untouched","Other profile persists");
            c.assertFalse(GameModes.state(fresh).getCompoundOrEmpty("profiles").getCompoundOrEmpty("CREATIVE").contains(RewardTrades.RECEIPTS),"Global unlock is not copied into a mode");
            fresh.restoreFrom(p,false);c.assertTrue(RewardTrades.hasPower(fresh,"explorer"),"Respawn copy preserves receipt");
        }catch(IOException e){throw new RuntimeException(e);}c.succeed();
    }
    @GameTest public void tradesRequireActualSurvivalAndNoCombatOrModeTransition(GameTestHelper c) {
        var p=player(c,"trade-mode");var store=new Store();explorerCost(p);p.setGameMode(GameType.CREATIVE);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Actual Creative is rejected even with Survival profile");
        p.setGameMode(GameType.SURVIVAL);GameModes.state(p).putString("active","CREATIVE");
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Non-Survival profile is rejected");
        GameModes.state(p).putString("active","SURVIVAL");CommunityServer.get(c.getLevel().getServer()).combat.put(p.getUUID(),c.getLevel().getServer().getTickCount()+200);
        c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Combat blocks a trade");CommunityServer.get(c.getLevel().getServer()).combat.remove(p.getUUID());
        GameModes.TRANSITIONS.add(p.getUUID());c.assertFalse(RewardTrades.purchase(p,"explorer",store),"Transfer blocks trading");GameModes.TRANSITIONS.remove(p.getUUID());
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),32,"Restrictions never take payment");c.succeed();
    }
    @GameTest public void silentlyFailedSaveRollsBackOriginalItemsAndGlobalState(GameTestHelper c) {
        var p=player(c,"trade-save-fail");explorerCost(p);GameModes.state(p).putString("keep","global marker");var before=RewardTrades.serialize(p);
        var silent=new RewardTrades.PlayerStore(){public void save(ServerPlayer ignored){}public CompoundTag read(ServerPlayer ignored){return before;}};
        c.assertFalse(RewardTrades.purchase(p,"explorer",silent),"A swallowed save failure is detected from disk receipt");
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),32,"Emeralds restored");c.assertValueEqual(p.getInventory().getItem(1).getCount(),4,"Pearls restored");
        c.assertFalse(RewardTrades.hasPower(p,"explorer"),"Unsaved receipt rolled back");
        c.assertValueEqual(GameModes.state(p).getStringOr("keep",""),"global marker","Other global state restored");c.succeed();
    }
    @GameTest public void mismatchedReceiptAndInventorySaveIsRejected(GameTestHelper c) {
        var p=player(c,"trade-mismatch");explorerCost(p);var before=RewardTrades.serialize(p);
        var mismatch=new RewardTrades.PlayerStore() {
            CompoundTag disk;
            public void save(ServerPlayer player){disk=RewardTrades.serialize(player);disk.put("Inventory",before.get("Inventory").copy());}
            public CompoundTag read(ServerPlayer ignored){return disk;}
        };
        c.assertFalse(RewardTrades.purchase(p,"explorer",mismatch),"Receipt alone cannot establish saved payment");
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),32,"Bad save restores original emeralds");
        c.assertFalse(RewardTrades.hasPower(p,"explorer"),"Bad save grants no active reward");c.succeed();
    }
    @GameTest public void vanillaSaveContainsReceiptAndDebitedInventoryTogether(GameTestHelper c) {
        var p=player(c,"trade-vanilla");p.getInventory().setItem(0,new ItemStack(Items.PRISMARINE_SHARD,16));p.getInventory().setItem(1,new ItemStack(Items.EMERALD,8));
        c.assertTrue(RewardTrades.purchase(p,"aquatic"),"Real PlayerManager forced save verifies success");
        try {
            var disk=RewardTrades.VANILLA.read(p);
            c.assertTrue(RewardTrades.receipt(disk.getCompoundOrEmpty("InfinityModes"),"aquatic"),"Vanilla disk contains permanent receipt");
            c.assertTrue(java.util.Objects.equals(disk.get("Inventory"),RewardTrades.serialize(p).get("Inventory")),"Same player replacement stores paid inventory");
        }catch(IOException e){throw new RuntimeException(e);}c.succeed();
    }
}

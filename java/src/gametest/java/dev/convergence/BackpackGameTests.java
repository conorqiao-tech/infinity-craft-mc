package dev.convergence;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
public class BackpackGameTests {
    ServerPlayer player(GameTestHelper c,String name){var p=new ModeGameTests().player(c,name);p.setGameMode(GameType.SURVIVAL);return p;}
    SimpleContainer storage(ServerPlayer p){return new BackpackStorage.Storage(p);}
    void unlock(ServerPlayer p){var owned=new CompoundTag();owned.putBoolean("backpack:trail",true);GameModes.state(p).put(BackpackStorage.OWNED,owned);}
    @GameTest public void backpackAndInventoryPersistInSamePlayerFile(GameTestHelper c){
        try {
        var p=player(c,"pack-persist");unlock(p);var storage=storage(p);
        var named=new ItemStack(Items.DIAMOND,5);named.set(DataComponents.CUSTOM_NAME,Component.literal("Treasure"));storage.setItem(8,named);
        p.getInventory().setItem(3,new ItemStack(Items.EMERALD,7));var disk=RewardTrades.serialize(p);
        var fresh=player(c,"pack-restored");fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,fresh.registryAccess(),disk));
        var restored=storage(fresh);c.assertTrue(ItemStack.matches(restored.getItem(8),named),"Sparse slot, count and custom components persist");
        c.assertTrue(restored.getItem(0).isEmpty(),"Empty slots retain positions");c.assertValueEqual(fresh.getInventory().getItem(3).getCount(),7,"Active inventory saved together");
        fresh.restoreFrom(p,false);c.assertTrue(ItemStack.matches(storage(fresh).getItem(8),named),"Personal backpack survives respawn copy");c.succeed();
        }catch(Throwable failure){failure.printStackTrace();throw failure;}
    }
    @GameTest public void allBackpackStyleColorsAndNamesSurviveNativePlayerFile(GameTestHelper c) throws Exception {
        var p=player(c,"pack-style-save");
        try {
            p.getInventory().setItem(8,BackpackStorage.appearance(BackpackStorage.style("trail")));
            p.getInventory().setItem(9,BackpackStorage.appearance(BackpackStorage.style("emerald")));
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,BackpackStorage.appearance(BackpackStorage.style("dragon")));
            PlayerPersistence.save(p);
            var disk=net.minecraft.nbt.NbtIo.readCompressed(GameModes.savedPlayerFile(p.level().getServer(),p.getUUID()),net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));
            var restored=new ServerPlayer(p.level().getServer(),p.level(),p.getGameProfile(),p.clientInformation());
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),disk));
            var actual=java.util.List.of(restored.getInventory().getItem(8),restored.getInventory().getItem(9),restored.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
            for(int i=0;i<BackpackStorage.STYLES.size();i++) {
                var style=BackpackStorage.STYLES.get(i);var stack=actual.get(i);
                c.assertTrue(stack.is(BackpackStorage.ITEM),"The native file retains each real backpack item");
                c.assertValueEqual(stack.get(DataComponents.DYED_COLOR).rgb(),style.color(),"Inventory and worn style colors survive native save/reload: "+style.id());
                c.assertValueEqual(stack.getHoverName().getString(),style.name(),"Earned style names survive native save/reload");
            }
            c.assertValueEqual(actual.stream().map(s->s.get(DataComponents.DYED_COLOR).rgb()).distinct().count(),3L,"The restored styles remain distinct");
        }finally{p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }
    @GameTest public void backpackModeStorageNeverSharesCreativeItems(GameTestHelper c){
        var p=player(c,"pack-modes");unlock(p);var survival=storage(p);survival.setItem(0,new ItemStack(Items.IRON_INGOT,4));
        GameModes.state(p).putString("active","CREATIVE");p.setGameMode(GameType.CREATIVE);c.assertFalse(survival.stillValid(p),"Old screen loses access after mode change");
        var creative=storage(p);c.assertTrue(creative.isEmpty(),"Creative starts a separate empty backpack");creative.setItem(0,new ItemStack(Items.NETHERITE_BLOCK,64));
        GameModes.state(p).putString("active","SURVIVAL");p.setGameMode(GameType.SURVIVAL);var restored=storage(p);
        c.assertTrue(restored.getItem(0).is(Items.IRON_INGOT),"Creative blocks never appear in Survival");c.assertValueEqual(restored.getItem(0).getCount(),4,"Survival count preserved");c.assertFalse(creative.stillValid(p),"Creative screen invalid outside its mode");c.succeed();
    }
    @GameTest public void copiedAccessoryDoesNotCopyStorageOrUnlock(GameTestHelper c){
        var a=player(c,"pack-owner");var b=player(c,"pack-recipient");unlock(a);storage(a).setItem(0,new ItemStack(Items.DIAMOND,9));
        b.getInventory().setItem(0,BackpackStorage.appearance(BackpackStorage.style("dragon")));
        c.assertFalse(BackpackStorage.backpackEarned(b),"Named or copied Dragon item grants no entitlement");c.assertTrue(storage(b).isEmpty(),"Storage belongs to recipient UUID, not accessory");
        c.assertValueEqual(BackpackStorage.open(b),0,"Unearned accessory does not bypass unlock");c.succeed();
    }
    @GameTest public void malformedBackpackFailsWithoutOverwritingSavedItems(GameTestHelper c){
        var p=player(c,"pack-invalid");unlock(p);var malformed=new CompoundTag();malformed.putString("items","wrong type");var all=new CompoundTag();all.put("SURVIVAL",malformed);GameModes.state(p).put(BackpackStorage.STORAGE,all);
        var before=GameModes.state(p).copy();c.assertValueEqual(BackpackStorage.open(p),0,"Corrupt storage is refused");c.assertValueEqual(GameModes.state(p),before,"Corrupt storage stays intact for recovery");c.succeed();
    }
    @GameTest public void backpackTransferMarksBothSourceAndDestinationConsistently(GameTestHelper c){
        var p=player(c,"pack-transfer");unlock(p);p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,10));
        try {
            c.assertValueEqual(BackpackStorage.open(p),1,"Actual backpack chest opens");
            c.assertTrue(p.containerMenu instanceof ChestMenu,"Uses native chest handler");
            var handler=(ChestMenu)p.containerMenu;var sample=BackpackSerializationProbe.begin(p);
            handler.quickMoveStack(p,54); // chest27 + main27: first hotbar slot.
            c.assertValueEqual(sample.attempts,1,"Native shift-transfer's repeated slot notifications encode the bag exactly once");
            c.assertValueEqual(sample.completed,1,"The bag is serialized immediately during the native transfer");
            c.assertTrue(p.getInventory().getItem(0).isEmpty(),"Vanilla quick transfer debits player inventory");
            c.assertValueEqual(handler.getContainer().getItem(0).getCount(),10,"Vanilla quick transfer credits backpack");
            var disk=RewardTrades.serialize(p);var fresh=new ServerPlayer(p.level().getServer(),p.level(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"pack-transfer-read"),p.clientInformation());
            fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,fresh.registryAccess(),disk));
            c.assertTrue(fresh.getInventory().getItem(0).isEmpty(),"Inventory debit persisted");
            c.assertValueEqual(storage(fresh).getItem(0).getCount(),10,"Backpack credit persisted in the same player save before close or another tick");
            handler.quickMoveStack(p,0);c.assertTrue(handler.getContainer().isEmpty(),"Reverse quick transfer empties backpack");
            c.assertValueEqual(sample.completed,2,"Reverse transfer immediately serializes the changed bag once");
            c.assertValueEqual(p.getInventory().getNonEquipmentItems().stream().filter(stack->stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(),10,"Reverse transfer conserves items");p.closeContainer();
            c.assertValueEqual(sample.attempts,2,"Closing an unchanged bag never serializes it again");
            GameModes.state(p).putString("active","MINIGAMES");c.assertFalse(BackpackStorage.storageAllowed(p),"Minigames cannot access stored Survival equipment");
            p.setGameMode(GameType.SPECTATOR);GameModes.state(p).putString("active","HARDCORE");c.assertFalse(BackpackStorage.storageAllowed(p),"Eliminated Hardcore spectator cannot withdraw equipment");
        } finally {BackpackSerializationProbe.end(p);p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }
    @GameTest public void backpackComponentOnlyChangesAreSavedImmediatelyAndSnapshotsStayDetached(GameTestHelper c) {
        var p=player(c,"pack-components");unlock(p);
        try {
            c.assertValueEqual(BackpackStorage.open(p),1,"Actual backpack chest opens");
            var bag=((ChestMenu)p.containerMenu).getContainer();var sample=BackpackSerializationProbe.begin(p);
            bag.setItem(8,new ItemStack(Items.DIAMOND,5));
            var item=bag.getItem(8);item.set(DataComponents.CUSTOM_NAME,Component.literal("First name"));bag.setChanged();
            c.assertValueEqual(sample.completed,2,"A component-only change to the same stack triggers an immediate save");
            c.assertTrue(ItemStack.matches(BackpackStorage.read(p,"SURVIVAL").get(8),item),"The saved bag includes the name without closing or advancing a tick");
            item.set(DataComponents.CUSTOM_NAME,Component.literal("Second name"));bag.setChanged();
            c.assertValueEqual(sample.completed,3,"The comparison snapshot never aliases a live stack's component changes");
            bag.setChanged();bag.setChanged();
            c.assertValueEqual(sample.attempts,3,"Identical contents skip subsequent native notifications");
            var fresh=new ServerPlayer(p.level().getServer(),p.level(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"pack-components-read"),p.clientInformation());
            fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),RewardTrades.serialize(p)));
            var restored=BackpackStorage.read(fresh,"SURVIVAL").get(8);
            c.assertTrue(ItemStack.matches(restored,item),"Native player serialization preserves the final components and count");
            c.assertValueEqual(restored.getCount(),5,"Renaming and duplicate notifications conserve the exact item count");
            c.assertTrue(bag.stillValid(p),"Serialization comparison does not change ordinary bag access");
            GameModes.PENDING.put(p.getUUID(),new GameModes.Switch(GameModes.Mode.CREATIVE,null,CommunityServer.Place.of(p),p.level().getServer().getTickCount()+100));
            c.assertFalse(bag.stillValid(p),"A pending mode change still revokes backpack access immediately");
        } finally {GameModes.PENDING.remove(p.getUUID());BackpackSerializationProbe.end(p);p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }
    @GameTest public void failedBackpackCodecPreservesSavedBagAndRetriesUntilRepaired(GameTestHelper c) {
        var p=player(c,"pack-encode-retry");unlock(p);
        BackpackStorage.Storage bag=null;
        try {
            c.assertValueEqual(BackpackStorage.open(p),1,"Actual backpack chest opens");
            bag=(BackpackStorage.Storage)((ChestMenu)p.containerMenu).getContainer();bag.setItem(0,new ItemStack(Items.DIAMOND,5));
            var original=GameModes.state(p).copy();var sample=BackpackSerializationProbe.begin(p);var item=bag.getItem(0);
            // Native item count encoding accepts 1..99; an invalid in-place mutation exercises its real error path.
            item.setCount(100);
            for(int attempt=1;attempt<=2;attempt++) {
                boolean failed=false;
                try {bag.setChanged();}catch(IllegalStateException expected){failed=expected.getMessage().contains("original storage preserved");}
                c.assertTrue(failed,"The native codec refuses invalid data before overwriting saved storage");
                c.assertValueEqual(sample.attempts,attempt,"An unsuccessful encode never marks those contents as serialized");
                c.assertValueEqual(sample.completed,0,"No failed or partial encoding is committed");
                c.assertValueEqual(GameModes.state(p),original,"Codec failure preserves the complete original player state");
            }
            // Count 65 is accepted by the codec but cannot be reopened under native diamond stack limits.
            item.setCount(65);
            for(int attempt=3;attempt<=4;attempt++) {
                boolean failed=false;
                try {bag.setChanged();}catch(IllegalStateException expected){failed=expected.getMessage().contains("original storage preserved");}
                c.assertTrue(failed,"Native-valid overstacked items are refused before committing a bag that cannot reopen");
                c.assertValueEqual(sample.attempts,attempt,"A rejected stack-size check also leaves retry available");
                c.assertValueEqual(sample.completed,0,"An overstack cannot replace the saved original");
                c.assertValueEqual(GameModes.state(p),original,"Overstack refusal preserves the complete original player state");
            }
            item.setCount(5);bag.setChanged();
            c.assertValueEqual(sample.attempts,4,"Restoring the exact original contents recovers without another encode");
            c.assertValueEqual(GameModes.state(p),original,"Repair preserves the exact original saved bag");
            c.assertTrue(ItemStack.matches(BackpackStorage.read(p,"SURVIVAL").getFirst(),item),"The repaired bag immediately reopens with its exact original contents");
            item.set(DataComponents.CUSTOM_NAME,Component.literal("Repaired treasure"));bag.setChanged();
            c.assertValueEqual(sample.completed,1,"Repairing the real invalid item permits the next immediate save");
            c.assertTrue(ItemStack.matches(BackpackStorage.read(p,"SURVIVAL").getFirst(),item),"Recovery saves the exact repaired count and components");
            bag.setChanged();c.assertValueEqual(sample.attempts,5,"Successful recovery restores notification deduplication");
        } finally {
            if(bag!=null&&!bag.getItem(0).isEmpty())bag.getItem(0).setCount(5);
            BackpackSerializationProbe.end(p);p.closeContainer();p.level().getServer().getPlayerList().remove(p);
        }
        c.succeed();
    }
}

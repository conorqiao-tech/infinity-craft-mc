package dev.convergence;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.*;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.world.GameMode;
public class BackpackGameTests {
    ServerPlayerEntity player(TestContext c,String name){var p=new ModeGameTests().player(c,name);p.changeGameMode(GameMode.SURVIVAL);return p;}
    SimpleInventory storage(ServerPlayerEntity p){return new BackpackStorage.Storage(p);}
    void unlock(ServerPlayerEntity p){var owned=new NbtCompound();owned.putBoolean("backpack:trail",true);GameModes.state(p).put(BackpackStorage.OWNED,owned);}
    @GameTest public void backpackAndInventoryPersistInSamePlayerFile(TestContext c){
        try {
        var p=player(c,"pack-persist");unlock(p);var storage=storage(p);
        var named=new ItemStack(Items.DIAMOND,5);named.set(DataComponentTypes.CUSTOM_NAME,Text.literal("Treasure"));storage.setStack(8,named);
        p.getInventory().setStack(3,new ItemStack(Items.EMERALD,7));var disk=RewardTrades.serialize(p);
        var fresh=player(c,"pack-restored");fresh.readData(NbtReadView.create(ErrorReporter.EMPTY,fresh.getRegistryManager(),disk));
        var restored=storage(fresh);c.assertTrue(ItemStack.areEqual(restored.getStack(8),named),"Sparse slot, count and custom components persist");
        c.assertTrue(restored.getStack(0).isEmpty(),"Empty slots retain positions");c.assertEquals(fresh.getInventory().getStack(3).getCount(),7,"Active inventory saved together");
        fresh.copyFrom(p,false);c.assertTrue(ItemStack.areEqual(storage(fresh).getStack(8),named),"Personal backpack survives respawn copy");c.complete();
        }catch(Throwable failure){failure.printStackTrace();throw failure;}
    }
    @GameTest public void backpackModeStorageNeverSharesCreativeItems(TestContext c){
        var p=player(c,"pack-modes");unlock(p);var survival=storage(p);survival.setStack(0,new ItemStack(Items.IRON_INGOT,4));
        GameModes.state(p).putString("active","CREATIVE");p.changeGameMode(GameMode.CREATIVE);c.assertFalse(survival.canPlayerUse(p),"Old screen loses access after mode change");
        var creative=storage(p);c.assertTrue(creative.isEmpty(),"Creative starts a separate empty backpack");creative.setStack(0,new ItemStack(Items.NETHERITE_BLOCK,64));
        GameModes.state(p).putString("active","SURVIVAL");p.changeGameMode(GameMode.SURVIVAL);var restored=storage(p);
        c.assertTrue(restored.getStack(0).isOf(Items.IRON_INGOT),"Creative blocks never appear in Survival");c.assertEquals(restored.getStack(0).getCount(),4,"Survival count preserved");c.assertFalse(creative.canPlayerUse(p),"Creative screen invalid outside its mode");c.complete();
    }
    @GameTest public void copiedAccessoryDoesNotCopyStorageOrUnlock(TestContext c){
        var a=player(c,"pack-owner");var b=player(c,"pack-recipient");unlock(a);storage(a).setStack(0,new ItemStack(Items.DIAMOND,9));
        b.getInventory().setStack(0,BackpackStorage.appearance(BackpackStorage.style("dragon")));
        c.assertFalse(BackpackStorage.backpackEarned(b),"Named or copied Dragon item grants no entitlement");c.assertTrue(storage(b).isEmpty(),"Storage belongs to recipient UUID, not accessory");
        c.assertEquals(BackpackStorage.open(b),0,"Unearned accessory does not bypass unlock");c.complete();
    }
    @GameTest public void malformedBackpackFailsWithoutOverwritingSavedItems(TestContext c){
        var p=player(c,"pack-invalid");unlock(p);var malformed=new NbtCompound();malformed.putString("items","wrong type");var all=new NbtCompound();all.put("SURVIVAL",malformed);GameModes.state(p).put(BackpackStorage.STORAGE,all);
        var before=GameModes.state(p).copy();c.assertEquals(BackpackStorage.open(p),0,"Corrupt storage is refused");c.assertEquals(GameModes.state(p),before,"Corrupt storage stays intact for recovery");c.complete();
    }
    @GameTest public void backpackTransferMarksBothSourceAndDestinationConsistently(TestContext c){
        var p=player(c,"pack-transfer");unlock(p);p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,10));
        c.assertEquals(BackpackStorage.open(p),1,"Actual backpack chest opens");
        c.assertTrue(p.currentScreenHandler instanceof GenericContainerScreenHandler,"Uses native chest handler");
        var handler=(GenericContainerScreenHandler)p.currentScreenHandler;
        handler.quickMove(p,54); // chest27 + main27: first hotbar slot.
        c.assertTrue(p.getInventory().getStack(0).isEmpty(),"Vanilla quick transfer debits player inventory");
        c.assertEquals(handler.getInventory().getStack(0).getCount(),10,"Vanilla quick transfer credits backpack");
        var disk=RewardTrades.serialize(p);var fresh=player(c,"pack-transfer-read");
        fresh.readData(NbtReadView.create(ErrorReporter.EMPTY,fresh.getRegistryManager(),disk));
        c.assertTrue(fresh.getInventory().getStack(0).isEmpty(),"Inventory debit persisted");
        c.assertEquals(storage(fresh).getStack(0).getCount(),10,"Backpack credit persisted in the same player save");
        handler.quickMove(p,0);c.assertTrue(handler.getInventory().isEmpty(),"Reverse quick transfer empties backpack");
        c.assertEquals(p.getInventory().getMainStacks().stream().filter(stack->stack.isOf(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(),10,"Reverse transfer conserves items");p.closeHandledScreen();
        GameModes.state(p).putString("active","MINIGAMES");c.assertFalse(BackpackStorage.storageAllowed(p),"Minigames cannot access stored Survival equipment");
        p.changeGameMode(GameMode.SPECTATOR);GameModes.state(p).putString("active","HARDCORE");c.assertFalse(BackpackStorage.storageAllowed(p),"Eliminated Hardcore spectator cannot withdraw equipment");c.complete();
    }
}

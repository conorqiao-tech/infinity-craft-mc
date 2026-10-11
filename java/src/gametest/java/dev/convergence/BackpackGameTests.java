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
        c.assertValueEqual(BackpackStorage.open(p),1,"Actual backpack chest opens");
        c.assertTrue(p.containerMenu instanceof ChestMenu,"Uses native chest handler");
        var handler=(ChestMenu)p.containerMenu;
        handler.quickMoveStack(p,54); // chest27 + main27: first hotbar slot.
        c.assertTrue(p.getInventory().getItem(0).isEmpty(),"Vanilla quick transfer debits player inventory");
        c.assertValueEqual(handler.getContainer().getItem(0).getCount(),10,"Vanilla quick transfer credits backpack");
        var disk=RewardTrades.serialize(p);var fresh=player(c,"pack-transfer-read");
        fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,fresh.registryAccess(),disk));
        c.assertTrue(fresh.getInventory().getItem(0).isEmpty(),"Inventory debit persisted");
        c.assertValueEqual(storage(fresh).getItem(0).getCount(),10,"Backpack credit persisted in the same player save");
        handler.quickMoveStack(p,0);c.assertTrue(handler.getContainer().isEmpty(),"Reverse quick transfer empties backpack");
        c.assertValueEqual(p.getInventory().getNonEquipmentItems().stream().filter(stack->stack.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum(),10,"Reverse transfer conserves items");p.closeContainer();
        GameModes.state(p).putString("active","MINIGAMES");c.assertFalse(BackpackStorage.storageAllowed(p),"Minigames cannot access stored Survival equipment");
        p.setGameMode(GameType.SPECTATOR);GameModes.state(p).putString("active","HARDCORE");c.assertFalse(BackpackStorage.storageAllowed(p),"Eliminated Hardcore spectator cannot withdraw equipment");c.succeed();
    }
}

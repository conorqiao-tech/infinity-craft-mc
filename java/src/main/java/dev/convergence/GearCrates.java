package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Real inventory containers, not client-only Creative catalogue preview entries. */
final class GearCrates {
    private static final Map<UUID,GameModes.Mode> CHECKED = new HashMap<>();
    private GearCrates() {}

    static List<ItemStack> crates() {
        var gear=new ArrayList<ItemStack>();
        Convergence.ITEMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            var item=entry.getValue();gear.add(new ItemStack(item,item.getMaxCount()>1?item.getMaxCount():1));
        });
        gear.add(new ItemStack(Items.FIREWORK_ROCKET,64));gear.add(new ItemStack(Items.WHEAT_SEEDS,64));
        var result=new ArrayList<ItemStack>();
        for(int start=0;start<gear.size();start+=27) {
            var crate=new ItemStack(start==0?Items.PURPLE_SHULKER_BOX:Items.CYAN_SHULKER_BOX);
            crate.set(DataComponentTypes.CONTAINER,ContainerComponent.fromStacks(gear.subList(start,Math.min(start+27,gear.size()))));
            crate.set(DataComponentTypes.CUSTOM_NAME,Text.literal("Convergence Set • Box "+(result.size()+1)));
            crate.set(DataComponentTypes.LORE,new LoreComponent(List.of(Text.literal("Place this box and open it to take real Infinity gear."))));
            var marker=new NbtCompound();marker.putBoolean("infinity_gear_crate",true);
            crate.set(DataComponentTypes.CUSTOM_DATA,NbtComponent.of(marker));result.add(crate);
        }
        return List.copyOf(result);
    }

    static boolean isCrate(ItemStack stack) {
        var data=stack.get(DataComponentTypes.CUSTOM_DATA);
        return (stack.isOf(Items.PURPLE_SHULKER_BOX)||stack.isOf(Items.CYAN_SHULKER_BOX))&&data!=null
            &&data.copyNbt().getBoolean("infinity_gear_crate",false);
    }

    static int give(ServerPlayerEntity player) {
        if(!ServerMenu.gearAllowed(player) || player.currentScreenHandler!=player.playerScreenHandler
            || !player.playerScreenHandler.getCursorStack().isEmpty())return 0;
        var boxes=crates();var empty=new ArrayList<Integer>();
        for(int i=0;i<36;i++)if(player.getInventory().getStack(i).isEmpty())empty.add(i);
        if(empty.size()<boxes.size()) { player.sendMessage(Text.literal("Clear "+boxes.size()+" inventory slots for the Convergence Set boxes. Your items were preserved."),false);return 0; }
        for(int i=0;i<boxes.size();i++)player.getInventory().setStack(empty.get(i),boxes.get(i));
        player.playerScreenHandler.syncState();
        player.sendMessage(Text.literal("Convergence Set added to your inventory. Place the labeled shulker boxes and open them for weapons, armor, tools and blocks."),false);return 1;
    }

    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->CHECKED.remove(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(server.getTicks()%20!=0)return;
            for(var player:server.getPlayerManager().getPlayerList()) {
                var mode=GameModes.current(player);
                if(!player.isCreative() || !ServerMenu.allowed(player)) { CHECKED.remove(player.getUuid());continue; }
                if(CHECKED.get(player.getUuid())==mode || player.currentScreenHandler!=player.playerScreenHandler)continue;
                CHECKED.put(player.getUuid(),mode);
                String flag="gear_crates_"+mode.name().toLowerCase(java.util.Locale.ROOT);
                boolean present=false;for(int i=0;i<36;i++)if(isCrate(player.getInventory().getStack(i)))present=true;
                if(present || GameModes.state(player).getBoolean(flag,false))continue;
                if(give(player)==1)GameModes.state(player).putBoolean(flag,true);
                // A full inventory can always retry from the main menu; avoid per-tick notices.
            }
        });
    }
}

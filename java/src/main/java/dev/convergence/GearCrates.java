package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;

/** Real inventory containers, not client-only Creative catalogue preview entries. */
final class GearCrates {
    private static final Map<UUID,GameModes.Mode> CHECKED = new HashMap<>();
    private static final Map<UUID,GameModes.Mode> ADMIN_CHECKED = new HashMap<>();
    static final List<net.minecraft.world.item.Item> ADMIN_ITEMS = List.of(Items.COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK,
        Items.CHAIN_COMMAND_BLOCK, Items.COMMAND_BLOCK_MINECART, Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID,
        Items.JIGSAW, Items.BARRIER, Items.LIGHT, Items.DEBUG_STICK);
    private GearCrates() {}

    static final List<String> STARTER=List.of("sword","mace","spear","pickaxe","axe","shovel","hoe",
        "helmet","chestplate","leggings","boots","shield","totem","bow","crossbow","builder_wand","sculptor_wand");
    /** Real equipment goes into empty player slots; never replace or drop owned items. */
    static int giveDirect(ServerPlayer player) {
        if(!ServerMenu.gearAllowed(player)||player.containerMenu!=player.inventoryMenu
            ||!player.inventoryMenu.getCarried().isEmpty())return 0;
        var paths=new ArrayList<String>(STARTER);
        ExpandedGear.COSMETIC_ARMOR.keySet().stream().sorted().forEach(paths::add);paths.add("backpack");
        int added=0,missing=0;
        for(var path:paths) {
            var item=Convergence.ITEMS.get("convergence:"+path);boolean present=false;
            for(int i=0;i<36;i++)if(player.getInventory().getItem(i).getItem()==item){present=true;break;}
            if(present)continue;
            int slot=player.getInventory().getFreeSlot();
            if(slot<0){missing++;continue;}
            player.getInventory().setItem(slot,new ItemStack(item));added++;
        }
        player.inventoryMenu.sendAllDataToRemote();
        player.sendSystemMessage(Component.literal("Added "+added+" armor pieces, weapons and tools directly to your inventory."
            +(missing>0?" Clear "+missing+" slots, then use Armor & tools → inventory to collect the rest.":" Open your inventory to move or equip them; no boxes or command needed.")));
        return missing==0?1:0;
    }

    static List<ItemStack> crates() {
        var gear=new ArrayList<ItemStack>();
        Convergence.ITEMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
            var item=entry.getValue();gear.add(new ItemStack(item,item.getDefaultMaxStackSize()>1?item.getDefaultMaxStackSize():1));
        });
        gear.add(new ItemStack(Items.FIREWORK_ROCKET,64));gear.add(new ItemStack(Items.WHEAT_SEEDS,64));
        var result=new ArrayList<ItemStack>();
        for(int start=0;start<gear.size();start+=27) {
            var crate=new ItemStack(start==0?Items.DYED_SHULKER_BOX.purple():Items.DYED_SHULKER_BOX.cyan());
            crate.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(gear.subList(start,Math.min(start+27,gear.size()))));
            crate.set(DataComponents.CUSTOM_NAME,Component.literal("Convergence Set • Box "+(result.size()+1)));
            crate.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Place this box and open it to take real Infinity gear."))));
            var marker=new CompoundTag();marker.putBoolean("infinity_gear_crate",true);
            crate.set(DataComponents.CUSTOM_DATA,CustomData.of(marker));result.add(crate);
        }
        return List.copyOf(result);
    }

    static boolean isCrate(ItemStack stack) {
        var data=stack.get(DataComponents.CUSTOM_DATA);
        return (stack.is(Items.DYED_SHULKER_BOX.purple())||stack.is(Items.DYED_SHULKER_BOX.cyan()))&&data!=null
            &&data.copyTag().getBooleanOr("infinity_gear_crate",false);
    }

    static boolean isAdminBox(ItemStack stack) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        return stack.is(Items.DYED_SHULKER_BOX.red()) && data != null && data.copyTag().getBooleanOr("infinity_admin_blocks", false);
    }

    static int giveAdmin(ServerPlayer player) {
        if (!ServerMenu.allowed(player) || !player.isCreative() || !Memberships.operator(player)
            || player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return 0;
        int slot = player.getInventory().getFreeSlot();
        if (slot < 0) { CommunityServer.say(player, "Clear one inventory slot for Admin Blocks. Your items are preserved."); return 0; }
        var box = new ItemStack(Items.DYED_SHULKER_BOX.red());
        box.set(DataComponents.CUSTOM_NAME, Component.literal("Admin Blocks • Creative Toolbox"));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(ADMIN_ITEMS.stream()
            .map(item -> new ItemStack(item, item.getDefaultMaxStackSize())).toList()));
        box.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Place and open for real command blocks, barriers, lights and structure tools."),
            Component.literal("Native operator permissions apply. Java's debug stick has limited Bedrock support."))));
        var tag = new CompoundTag(); tag.putBoolean("infinity_admin_blocks", true);
        box.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        player.getInventory().setItem(slot, box); player.inventoryMenu.sendAllDataToRemote();
        GameModes.state(player).putBoolean("admin_blocks_" + GameModes.current(player).name(), true);
        player.level().getServer().getPlayerList().saveAll();
        CommunityServer.say(player, "Admin Blocks added to Creative inventory. Place the red toolbox and open it. Recover it from Infinity Gear → Admin Blocks.");
        return 1;
    }

    static int give(ServerPlayer player) {
        if(!ServerMenu.gearAllowed(player) || player.containerMenu!=player.inventoryMenu
            || !player.inventoryMenu.getCarried().isEmpty())return 0;
        var boxes=crates();var empty=new ArrayList<Integer>();
        for(int i=0;i<36;i++)if(player.getInventory().getItem(i).isEmpty())empty.add(i);
        if(empty.size()<boxes.size()) { player.sendSystemMessage(Component.literal("Clear "+boxes.size()+" inventory slots for the Convergence Set boxes. Your items were preserved."));return 0; }
        for(int i=0;i<boxes.size();i++)player.getInventory().setItem(empty.get(i),boxes.get(i));
        player.inventoryMenu.sendAllDataToRemote();
        player.sendSystemMessage(Component.literal("Convergence Set added to your inventory. Place the labeled shulker boxes and open them for weapons, armor, tools and blocks."));return 1;
    }

    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{CHECKED.remove(handler.player.getUUID());ADMIN_CHECKED.remove(handler.player.getUUID());});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(server.getTickCount()%20!=0)return;
            for(var player:server.getPlayerList().getPlayers()) {
                var mode=GameModes.current(player);
                if(!player.isCreative() || !ServerMenu.allowed(player)) { CHECKED.remove(player.getUUID());ADMIN_CHECKED.remove(player.getUUID());continue; }
                if (!Memberships.operator(player)) ADMIN_CHECKED.remove(player.getUUID());
                else if (ADMIN_CHECKED.get(player.getUUID()) != mode && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty()) {
                    ADMIN_CHECKED.put(player.getUUID(), mode);
                    boolean present = java.util.stream.IntStream.range(0, 36).anyMatch(i -> isAdminBox(player.getInventory().getItem(i)));
                    if (!present && !GameModes.state(player).getBooleanOr("admin_blocks_" + mode.name(), false)) giveAdmin(player);
                }
                if(CHECKED.get(player.getUUID())==mode || player.containerMenu!=player.inventoryMenu)continue;
                CHECKED.put(player.getUUID(),mode);
                String direct="direct_gear14_"+mode.name().toLowerCase(java.util.Locale.ROOT);
                if(!GameModes.state(player).getBooleanOr(direct,false)) {
                    giveDirect(player);GameModes.state(player).putBoolean(direct,true);
                }
                String flag="gear_crates_"+mode.name().toLowerCase(java.util.Locale.ROOT);
                boolean present=false;for(int i=0;i<36;i++)if(isCrate(player.getInventory().getItem(i)))present=true;
                if(present || GameModes.state(player).getBooleanOr(flag,false))continue;
                if(give(player)==1)GameModes.state(player).putBoolean(flag,true);
                // A full inventory can always retry from the main menu; avoid per-tick notices.
            }
        });
    }
}

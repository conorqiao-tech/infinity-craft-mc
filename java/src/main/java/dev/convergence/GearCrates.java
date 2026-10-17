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
        "helmet","cuirass","chestplate","leggings","boots","shield","totem","bow","crossbow","builder_wand","sculptor_wand");
    static final List<String> CONVERGENCE_SET=List.of("helmet","cuirass","leggings","boots","chestplate");
    static final List<String> AURORA_SET=List.of("aurora_helmet","aurora_chestplate","aurora_leggings","aurora_boots");
    static final List<String> EMBER_SET=List.of("ember_helmet","ember_chestplate","ember_leggings","ember_boots");
    static final List<String> ELEMENTAL_SET=List.of("elemental_helmet","elemental_chestplate","elemental_leggings","elemental_boots");
    static final List<String> INFINITY_SET=List.of("infinity_helmet","infinity_chestplate","infinity_leggings","infinity_boots");
    private static final int CATALOG_REVISION=41;
    /** Real equipment goes into empty player slots; never replace or drop owned items. */
    static int giveDirect(ServerPlayer player) {
        if(!ServerMenu.gearAllowed(player)||player.containerMenu!=player.inventoryMenu
            ||!player.inventoryMenu.getCarried().isEmpty())return 0;
        var paths=new ArrayList<String>(STARTER);
        ExpandedGear.COSMETIC_ARMOR.keySet().stream().sorted().forEach(paths::add);paths.add("backpack");
        // Keep the starter grant below capacity so the compass and a tool swap
        // still fit. New sets have atomic, separately selected Forge kits.
        int added=0,missing=0;
        for(var path:paths) {
            var item=Convergence.ITEMS.get("convergence:"+path);boolean present=false;
            // The native inventory view includes worn armor and the offhand.
            for(int i=0;i<player.getInventory().getContainerSize();i++)if(player.getInventory().getItem(i).getItem()==item){present=true;break;}
            if(present)continue;
            int slot=player.getInventory().getFreeSlot();
            if(slot<0){missing++;continue;}
            player.getInventory().setItem(slot,new ItemStack(item));added++;
        }
        player.inventoryMenu.sendAllDataToRemote();
        if(missing==0)GameModes.state(player).putBoolean("direct_gear27_"+GameModes.current(player).name().toLowerCase(java.util.Locale.ROOT),true);
        player.sendSystemMessage(Component.literal("Added "+added+" armor pieces, weapons and tools directly to your inventory."
            +(missing>0?" Clear "+missing+" slots, then use Armor & tools → inventory to collect the rest.":" Open your inventory to move or equip them; no boxes or command needed.")));
        return missing==0?1:0;
    }

    static List<ItemStack> crates() {
        var result=new ArrayList<ItemStack>();
        result.add(crate(Items.DYED_SHULKER_BOX.purple(),"Convergence Set • Armor & Wings",CONVERGENCE_SET.stream().map(GearCrates::stack).toList()));
        result.add(crate(Items.DYED_SHULKER_BOX.cyan(),"Aurora Set • All Four Pieces",AURORA_SET.stream().map(GearCrates::stack).toList()));
        result.add(crate(Items.DYED_SHULKER_BOX.orange(),"Ember Set • All Four Pieces",EMBER_SET.stream().map(GearCrates::stack).toList()));
        result.add(crate(Items.DYED_SHULKER_BOX.cyan(),"Elemental Armor • Switchable Elements",ELEMENTAL_SET.stream().map(GearCrates::stack).toList()));
        result.add(crate(Items.DYED_SHULKER_BOX.purple(),"Infinity Armor • Ultimate Set",INFINITY_SET.stream().map(GearCrates::stack).toList()));
        var grouped=new java.util.HashSet<String>(CONVERGENCE_SET);grouped.addAll(AURORA_SET);grouped.addAll(EMBER_SET);
        grouped.addAll(ELEMENTAL_SET);grouped.addAll(INFINITY_SET);
        var gear=new ArrayList<ItemStack>();
        Convergence.ITEMS.keySet().stream().map(name->name.split(":")[1]).filter(path->!grouped.contains(path)).sorted().forEach(path->gear.add(stack(path)));
        gear.add(new ItemStack(Items.FIREWORK_ROCKET,64));gear.add(new ItemStack(Items.WHEAT_SEEDS,64));
        for(int start=0;start<gear.size();start+=27) {
            result.add(crate(Items.DYED_SHULKER_BOX.cyan(),"Infinity Tools & Blocks • Box "+(start/27+1),gear.subList(start,Math.min(start+27,gear.size()))));
        }
        return List.copyOf(result);
    }

    private static ItemStack stack(String path) {
        var item=Convergence.ITEMS.get("convergence:"+path);
        return new ItemStack(item,item.getDefaultMaxStackSize());
    }

    private static ItemStack crate(net.minecraft.world.item.Item item,String name,List<ItemStack> contents) {
        var box=new ItemStack(item);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));
        box.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        box.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Place this box and open it to take real Infinity gear."))));
        var marker=new CompoundTag();marker.putBoolean("infinity_gear_crate",true);marker.putInt("infinity_catalog_revision",CATALOG_REVISION);
        box.set(DataComponents.CUSTOM_DATA,CustomData.of(marker));return box;
    }

    static boolean isCrate(ItemStack stack) {
        var data=stack.get(DataComponents.CUSTOM_DATA);
        return (stack.is(Items.DYED_SHULKER_BOX.purple())||stack.is(Items.DYED_SHULKER_BOX.cyan())||stack.is(Items.DYED_SHULKER_BOX.orange()))&&data!=null
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
        PlayerPersistence.save(player);
        CommunityServer.say(player, "Admin Blocks added to Creative inventory. Place the red toolbox and open it. Recover it from Infinity Gear → Admin Blocks.");
        return 1;
    }

    static int give(ServerPlayer player) {
        if(!ServerMenu.gearAllowed(player) || player.containerMenu!=player.inventoryMenu
            || !player.inventoryMenu.getCarried().isEmpty())return 0;
        var boxes=crates();var empty=new ArrayList<Integer>();
        for(int i=0;i<36;i++)if(player.getInventory().getItem(i).isEmpty())empty.add(i);
        if(empty.size()<boxes.size()) { player.sendSystemMessage(Component.literal("Clear "+boxes.size()+" inventory slots for the labeled armor, tools and blocks boxes. Your items were preserved."));return 0; }
        for(int i=0;i<boxes.size();i++)player.getInventory().setItem(empty.get(i),boxes.get(i));
        GameModes.state(player).putBoolean("gear_crates27_"+GameModes.current(player).name().toLowerCase(java.util.Locale.ROOT),true);
        player.inventoryMenu.sendAllDataToRemote();
        player.sendSystemMessage(Component.literal("Convergence, Aurora, Ember, Elemental and Infinity sets added with the tools and blocks boxes. Place a labeled box and open it."));return 1;
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
                String direct="direct_gear27_"+mode.name().toLowerCase(java.util.Locale.ROOT);
                if(!GameModes.state(player).getBooleanOr(direct,false)) {
                    if(giveDirect(player)==1)GameModes.state(player).putBoolean(direct,true);
                }
                String flag="gear_crates27_"+mode.name().toLowerCase(java.util.Locale.ROOT);
                if(GameModes.state(player).getBooleanOr(flag,false))continue;
                if(give(player)==1)GameModes.state(player).putBoolean(flag,true);
                // A full inventory can always retry from the main menu; avoid per-tick notices.
            }
        });
    }
}

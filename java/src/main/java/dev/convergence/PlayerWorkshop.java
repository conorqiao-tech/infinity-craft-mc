package dev.convergence;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;

/** Permanent exploration and inventory tools, independent of event dates or payments. */
final class PlayerWorkshop {
    static final List<String> BIOMES=List.of("Meadow & plains","Forests & jungles","Desert & badlands","Snow & ice","Oceans & rivers","Deep caves","The Nether","The End");
    record SortReview(ServerPlayer owner,ServerGamePacketListenerImpl connection,ServerLevel world,GameModes.Mode mode,List<ItemStack> before,List<ItemStack> after) {}
    private PlayerWorkshop() {}
    static int category(String id){
        if(id.startsWith("minecraft:")&&(id.contains("nether")||id.contains("crimson")||id.contains("warped")||id.contains("basalt")||id.contains("soul_sand")))return 6;
        if(id.equals("minecraft:the_end")||id.contains("end_")||id.equals("minecraft:small_end_islands"))return 7;
        if(id.contains("caves")||id.contains("deep_dark"))return 5;
        if(id.contains("ocean")||id.contains("river"))return 4;
        if(id.contains("cherry"))return 1;
        if(id.contains("snow")||id.contains("frozen")||id.contains("ice")||id.contains("grove"))return 3;
        if(id.contains("desert")||id.contains("badlands"))return 2;
        if(id.contains("forest")||id.contains("jungle")||id.contains("taiga")||id.contains("cherry"))return 1;
        return 0;
    }
    static int stamps(ServerPlayer p){return Integer.bitCount(CommonsEvents.state(p).getIntOr("atlas",0)&255);}
    static int stamp(ServerPlayer p){
        if(!CommonsEvents.harvestAllowed(p)||!p.level().isLoaded(p.blockPosition()))return 0;
        String id=p.level().getBiome(p.blockPosition()).unwrapKey().orElseThrow().identifier().toString();int i=category(id);
        int old=CommonsEvents.state(p).getIntOr("atlas",0)&255;
        if((old&(1<<i))!=0){CommonsEvents.say(p,"That Atlas stamp is already saved. Try another biome family!");return 0;}
        CommonsEvents.state(p).putInt("atlas",old|(1<<i));CommonsEvents.state(p).putString("atlas_"+i,id+" • "+p.blockPosition().toShortString());
        PlayerPersistence.save(p);CommonsEvents.say(p,"Expedition Atlas: "+BIOMES.get(i)+" discovered! "+stamps(p)+"/8 stamps saved.");return 1;
    }
    static List<ItemStack> sorted(List<ItemStack> before){
        var stacks=new ArrayList<ItemStack>();
        // Merge only truly identical components, preserving names, damage, ownership and nested contents.
        for(var source:before){var remaining=source.copy();if(remaining.isEmpty())continue;
            for(var dest:stacks)if(ItemStack.isSameItemSameComponents(dest,remaining)){
                int moved=Math.min(remaining.getCount(),Math.max(0,dest.getMaxStackSize()-dest.getCount()));dest.grow(moved);remaining.shrink(moved);if(remaining.isEmpty())break;
            }
            if(!remaining.isEmpty())stacks.add(remaining);
        }
        stacks.sort(Comparator.comparing((ItemStack s)->BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).thenComparing(s->s.getHoverName().getString()).thenComparingInt(ItemStack::getDamageValue));
        while(stacks.size()<27)stacks.add(ItemStack.EMPTY);
        return stacks.stream().map(ItemStack::copy).toList();
    }
    static SortReview review(ServerPlayer p){
        if(!CommonsEvents.emptyScreen(p))return null;
        var before=new ArrayList<ItemStack>();for(int i=9;i<36;i++)before.add(p.getInventory().getItem(i).copy());
        return new SortReview(p,p.connection,p.level(),GameModes.current(p),List.copyOf(before),sorted(before));
    }
    static int apply(ServerPlayer p,SortReview r){
        if(r==null||r.owner()!=p||r.connection()!=p.connection||r.world()!=p.level()||r.mode()!=GameModes.current(p)||!CommonsEvents.emptyScreen(p))return 0;
        for(int i=0;i<27;i++){
            var actual=p.getInventory().getItem(i+9);var old=r.before().get(i);
            if(actual.getCount()!=old.getCount()||!ItemStack.isSameItemSameComponents(actual,old)){CommonsEvents.say(p,"Inventory changed. Review the organizer again; nothing moved.");return 0;}
        }
        for(int i=0;i<27;i++)p.getInventory().setItem(i+9,r.after().get(i).copy());
        p.inventoryMenu.sendAllDataToRemote();PlayerPersistence.save(p);CommonsEvents.say(p,"Pack organized and saved. Your hotbar, armor and offhand were preserved.");return 1;
    }
}

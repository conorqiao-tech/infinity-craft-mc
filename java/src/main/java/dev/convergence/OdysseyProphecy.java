package dev.convergence;

import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

/** A native written book, readable on Java and iPad, with delivery saved in the player's mode data. */
final class OdysseyProphecy {
    static final String MARKER="infinity_odyssey_prophecy";
    static final List<String> PAGES=List.of(
        "THE ODYSSEY\n\nThe chosen person is you.\n\nBefore the realms were divided, the Keepers built a sleeping circuit. Its light survives in scattered ruins, waiting for the traveler named by the ancient prophecy.",
        "THE CHANGING SHRINE\n\nSeek the broken circuit and its glowing heart. The land dresses it in sandstone, forest stone, ocean crystal or winter ice. Beneath every disguise, the pattern remains the same.",
        "THREE REALMS\n\nIts echoes rest beneath the Overworld sky, inside the Nether's caverns and upon the End's islands. They are uncommon. Search new lands; the old roads may hold no shrine.",
        "ONE CACHE\n\nOne chest guards the Keepers' gifts. The Odyssey fragment within speaks the first words of your journey.\n\nCarry its armor, wings and weapons with purpose. What you do with that power is the part of the prophecy still unwritten.");
    private OdysseyProphecy() {}

    static ItemStack book() {
        var stack=new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(Filterable.passThrough("The Odyssey: Prophecy"),"The Circuit Keepers",0,
            PAGES.stream().map(text->Filterable.<Component>passThrough(Component.literal(text))).toList(),true));
        var tag=new CompoundTag();tag.putBoolean(MARKER,true);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        return stack;
    }
    static boolean isBook(ItemStack stack) {
        var data=stack.get(DataComponents.CUSTOM_DATA);
        return stack.is(Items.WRITTEN_BOOK)&&data!=null&&data.copyTag().getBooleanOr(MARKER,false);
    }
    static String deliveryKey(ServerPlayer player) {return MARKER+"_"+GameModes.current(player).name();}

    /** Lost copies can be recovered from the menu; occupied slots and an open cursor are never replaced. */
    static boolean give(ServerPlayer player,boolean recover) {
        if(!ServerMenu.allowed(player)||player.containerMenu!=player.inventoryMenu||!player.inventoryMenu.getCarried().isEmpty())return false;
        var state=GameModes.state(player);String key=deliveryKey(player);
        if(!recover&&state.getBooleanOr(key,false))return true;
        for(int i=0;i<player.getInventory().getContainerSize();i++)if(isBook(player.getInventory().getItem(i))){state.putBoolean(key,true);return true;}
        int slot=player.getInventory().getFreeSlot();
        if(slot<0) {
            if(recover)CommunityServer.say(player,"Clear one inventory slot for your Odyssey prophecy. Your items are preserved.");
            return false;
        }
        player.getInventory().setItem(slot,book());state.putBoolean(key,true);
        player.inventoryMenu.sendAllDataToRemote();
        CommunityServer.say(player,"The Odyssey prophecy is in your inventory. Open the book to begin; a lost copy is available in Infinity Menu.");
        return true;
    }
    static void register() {
        OdysseyJournal.register();
        ServerTickEvents.END_SERVER_TICK.register(server->{
            if(server instanceof net.minecraft.gametest.framework.GameTestServer||server.getTickCount()%20!=0)return;
            for(var player:server.getPlayerList().getPlayers())give(player,false);
        });
    }
}

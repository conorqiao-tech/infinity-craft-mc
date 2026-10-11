package dev.convergence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;

/** Physical ruin discovery and one-time rewards live in the player's existing atomic save. */
final class OdysseyJournal {
    static final String KEY="odyssey_journal";
    static final List<String> REALMS=List.of("Overworld","Nether","End");
    static CompoundTag state(ServerPlayer p){
        var root=GameModes.state(p);var tag=root.getCompound(KEY).orElse(null);
        if(tag==null){tag=new CompoundTag();root.put(KEY,tag);}return tag;
    }
    static int realmIndex(ServerPlayer p){
        String id=p.level().dimension().identifier().toString();
        return switch(id){case "minecraft:overworld"->0;case "minecraft:the_nether"->1;case "minecraft:the_end"->2;default->-1;};
    }
    static int count(ServerPlayer p){return Integer.bitCount(state(p).getIntOr("realms",0)&7);}
    static boolean discovered(ServerPlayer p,int realm){return (state(p).getIntOr("realms",0)&(1<<realm))!=0;}
    static boolean record(ServerPlayer p,int realm,BlockPos at,String theme){
        if(realm<0||realm>2||discovered(p,realm))return false;
        var tag=state(p);tag.putInt("realms",(tag.getIntOr("realms",0)&7)|(1<<realm));
        tag.putString("shrine_"+realm,at.toShortString()+" • "+theme);
        CommunityServer.say(p,"Odyssey discovery: "+REALMS.get(realm)+" circuit! Open Infinity Menu → Odyssey Journal to claim your next gift.");
        return true;
    }
    static boolean discover(ServerPlayer p){
        int realm=realmIndex(p);
        if(!ServerMenu.allowed(p)||p.isCreative()||realm<0||discovered(p,realm)
            ||(GameModes.current(p)!=GameModes.Mode.SURVIVAL&&GameModes.current(p)!=GameModes.Mode.HARDCORE))return false;
        var world=p.level();var pos=p.blockPosition();
        var ruin=world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();
        var local=world.getChunkAt(pos);var references=new HashSet<Long>();references.add(local.getPos().pack());
        for(long reference:local.getReferencesForStructure(ruin))references.add(reference);
        for(long reference:references){
            var chunk=new net.minecraft.world.level.ChunkPos((int)reference,(int)(reference>>>32));
            if(!world.isLoaded(new BlockPos(chunk.x()<<4,pos.getY(),chunk.z()<<4)))continue;
            var start=world.getChunk(chunk.x(),chunk.z()).getStartForStructure(ruin);
            if(start==null||!start.isValid())continue;
            for(var piece:start.getPieces())if(piece instanceof OdysseyStructure.Piece&&piece.getBoundingBox().isInside(pos)){
                String biome=world.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
                return record(p,realm,pos,OdysseyStructure.theme(biome));
            }
        }
        return false;
    }
    static List<ItemStack> reward(ServerPlayer p,int tier){
        if(tier==1)return List.of(new ItemStack(Items.ECHO_SHARD,8));
        if(tier==2)return CreativeStudio.outfit(p,3);
        var relic=new ItemStack(tier==3?Items.TRIDENT:Items.MACE);
        relic.set(DataComponents.CUSTOM_NAME,Component.literal(tier==3?"Odyssey Circuit Trident":"Odyssey Memory Mace"));
        relic.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Circuit Keepers • "+(tier==3?"Three realms rediscovered":"Memory and timing mastered")),
            Component.literal("A unique named native weapon; normal Minecraft combat applies."))));
        return List.of(relic);
    }
    static boolean earned(ServerPlayer p,int tier){
        if(tier<1||tier>4)return false;
        if(tier<=3)return count(p)>=tier;
        var scores=GameModes.state(p).getCompoundOrEmpty("scores");
        return count(p)==3&&scores.getLongOr("memory",0)>0&&scores.getLongOr("gatedash",0)>0;
    }
    static int claim(ServerPlayer p,int tier){
        if(!ServerMenu.allowed(p)||p.isCreative()||p.containerMenu!=p.inventoryMenu||!p.inventoryMenu.getCarried().isEmpty()
            ||!earned(p,tier)||state(p).getBooleanOr("claimed_"+tier,false))return 0;
        if(GameModes.current(p)!=GameModes.Mode.SURVIVAL&&GameModes.current(p)!=GameModes.Mode.HARDCORE){CommunityServer.say(p,"Return to Survival or Hardcore to claim your gift.");return 0;}
        var stacks=reward(p,tier);var slots=new ArrayList<Integer>();
        for(int i=0;i<36;i++)if(p.getInventory().getItem(i).isEmpty())slots.add(i);
        if(slots.size()<stacks.size()){CommunityServer.say(p,"Clear "+stacks.size()+" inventory slots. Your Odyssey gift remains unclaimed.");return 0;}
        for(int i=0;i<stacks.size();i++)p.getInventory().setItem(slots.get(i),stacks.get(i).copy());
        state(p).putBoolean("claimed_"+tier,true);p.inventoryMenu.sendAllDataToRemote();
        p.level().getServer().getPlayerList().saveAll();CommunityServer.say(p,"Odyssey gift claimed. Your journal and rewards are saved.");return stacks.size();
    }
    static int open(ServerPlayer p){
        if(!ServerMenu.allowed(p)||p.containerMenu!=p.inventoryMenu||!p.inventoryMenu.getCarried().isEmpty())return 0;
        var view=new SimpleContainer(27);
        for(int i=0;i<3;i++)CreativeStudio.icon(view,10+i,i==0?Items.GRASS_BLOCK:i==1?Items.NETHERRACK:Items.END_STONE,
            REALMS.get(i)+": "+(discovered(p,i)?"Discovered":"Seek a circuit ruin"),
            discovered(p,i)?state(p).getStringOr("shrine_"+i,""):"Enter the original circuit in Survival or Hardcore. Search new terrain.");
        for(int tier=1;tier<=4;tier++)CreativeStudio.icon(view,18+tier,tier==2?Items.DIAMOND_CHESTPLATE:tier==3?Items.TRIDENT:tier==4?Items.MACE:Items.ECHO_SHARD,
            "Gift "+tier+" • "+(state(p).getBooleanOr("claimed_"+tier,false)?"Claimed":earned(p,tier)?"Ready to claim":"Locked"),
            tier<=3?"Discover "+tier+" different realms.":"Discover all realms and finish Memory Circuit and Laser Gate Dash.",
            tier==2?"Complete four-piece Tidewarden armor.":tier==3?"Odyssey Circuit Trident.":tier==4?"Odyssey Memory Mace.":"Eight echo shards.");
        CreativeStudio.icon(view,4,Items.WRITTEN_BOOK,"Receive or recover your prophecy","Your original book and the shrine's chest contents are preserved.");
        CreativeStudio.icon(view,26,Items.ARROW,"Back to Infinity Menu");
        p.openMenu(new SimpleMenuProvider((sync,inv,who)->new Handler(sync,inv,view,p),Component.literal("The Odyssey • Journal")));return 1;
    }
    static final class Handler extends ChestMenu{
        final ServerPlayer owner;final ServerGamePacketListenerImpl connection;final Level world;
        Handler(int sync,Inventory inv,SimpleContainer view,ServerPlayer p){super(MenuType.GENERIC_9x3,sync,inv,view,3);owner=p;connection=p.connection;world=p.level();}
        @Override public boolean stillValid(Player p){return p==owner&&owner.connection==connection&&owner.containerMenu==this&&owner.level()==world&&ServerMenu.allowed(owner);}
        @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected){}
        @Override public void clicked(int slot,int button,ContainerInput action,Player p){
            if(p!=owner||owner.containerMenu!=this||owner.connection!=connection)return;
            if(!stillValid(p)){owner.closeContainer();return;}
            if((action!=ContainerInput.PICKUP&&action!=ContainerInput.QUICK_MOVE)||button<0||button>1||!getCarried().isEmpty())return;
            if(slot==26){owner.closeContainer();ServerMenu.open(owner);}
            else if(slot==4){owner.closeContainer();OdysseyProphecy.give(owner,true);}
            else if(slot>=19&&slot<=22){owner.closeContainer();claim(owner,slot-18);open(owner);}
        }
    }
    static void register(){ServerTickEvents.END_SERVER_TICK.register(server->{
        if(server instanceof net.minecraft.gametest.framework.GameTestServer||server.getTickCount()%20!=0)return;
        boolean changed=false;for(var p:server.getPlayerList().getPlayers())changed|=discover(p);
        if(changed)server.getPlayerList().saveAll();
    });}
}

package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Saved personal progression is authoritative. Native advancement displays and durable credits reconcile from it. */
final class AstralJournal {
    static final String KEY="astral_wilds";
    static final List<String> MILESTONES=List.of("first_contact","cinderbound_keeper","rimeglass_ranger","rift_skitter","keepers_craft","astral_orbit");
    static final List<String> TITLES=List.of("The Wild Circuit","Ash Remembers","Rime and Reason","A Fold in the Dark","Keeper's Workshop","A Constellation of Your Own");
    static final List<String> REQUIREMENTS=List.of("Encounter a real ruin guardian in Survival or Hardcore.","Defeat a Cinderbound Keeper near an Odyssey ruin.","Defeat a Rimeglass Ranger near a cold-biome Odyssey ruin.","Defeat a Rift Skitter near an End Odyssey ruin.","Craft all three Astral tools at an ordinary crafting table.","Defeat all three guardians and successfully use all three Astral tools.");
    static final long[] COINS={1,2,2,2,3,5};
    enum Page { JOURNAL, RECIPES }
    record Choice(Item icon,String label,Runnable action){}
    record View(Page page,String title,String content,List<Choice> choices){}
    record Ticket(UUID nonce,ServerPlayer player,ServerGamePacketListenerImpl connection,ServerLevel world,GameModes.Mode mode,int expires){
        boolean valid(){return player.connection==connection&&player.level()==world&&GameModes.current(player)==mode&&emptyScreen(player)&&world.getServer().getTickCount()<expires;}
    }
    record Delivery(Ticket ticket,Form form,int due){}
    static final Map<UUID,Ticket> ACTIVE=new ConcurrentHashMap<>();
    static final Map<UUID,Delivery> DELIVERIES=new ConcurrentHashMap<>();
    private AstralJournal(){}
    /** Read-only previews never manufacture a player compound. */
    static CompoundTag state(ServerPlayer p){return GameModes.state(p).getCompoundOrEmpty(KEY);}
    static int earned(ServerPlayer p){return state(p).getIntOr("earned",0)&63;}
    static boolean has(ServerPlayer p,int index){return index>=0&&index<6&&(earned(p)&(1<<index))!=0;}
    static boolean orbit(ServerPlayer p){return has(p,5)&&state(p).getBooleanOr("orbit",false);}
    static boolean orbitVisible(ServerPlayer p){return ServerMenu.allowed(p)&&!p.isInvisible()&&orbit(p);}
    static void store(ServerPlayer p,CompoundTag next){GameModes.state(p).put(KEY,next);PlayerPersistence.save(p);}
    static boolean emptyScreen(ServerPlayer p){return ServerMenu.allowed(p)&&p.containerMenu==p.inventoryMenu&&p.inventoryMenu.getCarried().isEmpty();}
    static int itemBit(String id){String path=id.startsWith("convergence:")?id.substring(12):id;int index=AstralItems.PATHS.indexOf(path);return index<0?0:1<<index;}
    static void encountered(ServerPlayer p){record(p,"encounter",1);}
    static void killed(ServerPlayer p,AstralWilds.Variant variant){if(variant!=null)record(p,"kills",1<<variant.ordinal());}
    static void crafted(ServerPlayer p,String id){int bit=itemBit(id);if(bit!=0)record(p,"crafted",bit);}
    static void used(ServerPlayer p,String id){int bit=itemBit(id);if(bit!=0)record(p,"used",bit);}
    static void record(ServerPlayer p,String field,int bits){
        if(!AstralWilds.progressionAllowed(p))return;var next=state(p).copy();int old=next.getIntOr(field,0);
        if((old|bits)==old)return;next.putInt(field,old|bits);if(field.equals("kills"))next.putInt("encounter",1);int complete=next.getIntOr("earned",0)&63;
        if(next.getIntOr("encounter",0)!=0)complete|=1;
        int kills=next.getIntOr("kills",0)&7;for(int i=0;i<3;i++)if((kills&(1<<i))!=0)complete|=1<<(i+1);
        if((next.getIntOr("crafted",0)&7)==7)complete|=16;
        if(kills==7&&(next.getIntOr("used",0)&7)==7)complete|=32;
        int newly=complete&~earned(p);next.putInt("earned",complete);store(p,next);reconcile(p);
        for(int i=0;i<6;i++)if((newly&(1<<i))!=0)CommunityServer.say(p,"Astral Wilds • "+TITLES.get(i)+" earned. "+COINS[i]+" Infini reward; see Astral Journal for its saved credit status.");
    }
    /** A failed durable payout stays pending. Stable per-player event IDs make reconnect retries idempotent. */
    static void reconcile(ServerPlayer p){
        int earned=earned(p);if(earned==0)return;var next=state(p).copy();int paid=next.getIntOr("paid",0)&63;int before=paid;
        for(int i=0;i<6;i++)if((earned&(1<<i))!=0){
            var advancement=p.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath("convergence","astral/"+MILESTONES.get(i)));
            if(advancement!=null)p.getAdvancements().award(advancement,"verified");
            if((paid&(1<<i))==0&&InfiniEconomy.reward(p,"astral:"+MILESTONES.get(i),COINS[i]).success())paid|=1<<i;
        }
        if(paid!=before){next.putInt("paid",paid);store(p,next);}
    }
    static int wear(ServerPlayer p,boolean enabled){if(!emptyScreen(p)||enabled&&!has(p,5))return 0;var next=state(p).copy();next.putBoolean("orbit",enabled);store(p,next);return 1;}
    static String checklist(ServerPlayer p,String field){int mask=state(p).getIntOr(field,0)&7;var text=new StringBuilder();for(int i=0;i<3;i++)text.append((mask&(1<<i))!=0?"✓ ":"○ ").append(i==0?"Lantern":i==1?"Riftspool":"Trowel").append(i==2?"":" • ");return text.toString();}
    static View view(ServerPlayer p,Page page){
        var choices=new ArrayList<Choice>();String title,content;
        if(page==Page.JOURNAL){
            title="Astral Wilds • The Living Circuit";var text=new StringBuilder("The Odyssey's old circuits are waking. Native guardian variants keep their own health and equipment when chunks unload. They are rare encounters, not replacement skins for ordinary mobs.\n\n");
            int paid=state(p).getIntOr("paid",0)&63;
            for(int i=0;i<6;i++)text.append(has(p,i)?"✓ ":"○ ").append(TITLES.get(i)).append(" • ").append(COINS[i]).append(" Infini").append(has(p,i)?((paid&(1<<i))!=0?" credited":" credit pending"):"").append('\n').append(REQUIREMENTS.get(i)).append('\n');
            text.append("\nCrafted: ").append(checklist(p,"crafted")).append("\nSuccessfully used: ").append(checklist(p,"used"));
            text.append("\n\nSeek Cinderbound Keepers at Nether or temperate Overworld ruins, Rimeglass Rangers at cold Overworld ruins and Rift Skitters at End ruins. One native guardian encounter per ruin, including previously visited loaded ruins. Peaceful and disabled natural mob spawning are respected. Your Odyssey compass scans loaded terrain; no journal button creates a ruin.\n");
            text.append("Complete the final constellation to wear Astral Orbit: a small original star trail, with no combat or paid-rank advantage.");content=text.toString();
            choices.add(new Choice(Items.CRAFTING_TABLE,"Tools and exact recipes",()->open(p,Page.RECIPES)));
            choices.add(new Choice(Items.COMPASS,"Scan loaded terrain for an Odyssey ruin",()->{OdysseyCompass.scan(p);open(p,Page.JOURNAL);}));
            choices.add(new Choice(Items.END_ROD,(orbit(p)?"Hide":"Wear")+" Astral Orbit • "+(has(p,5)?"earned":"locked"),()->{if(wear(p,!orbit(p))==0)CommunityServer.say(p,"Finish all three guardians and all three successful tool uses to earn Astral Orbit.");open(p,Page.JOURNAL);}));
            choices.add(new Choice(Items.SUNFLOWER,"Retry pending saved Infini credits",()->{reconcile(p);open(p,Page.JOURNAL);}));
            choices.add(new Choice(Items.BOOK,"Back to Odyssey Journal",()->OdysseyJournal.open(p)));
        }else{
            title="Astral Wilds • Keeper's Workshop";
            content="Craft all three at an ordinary crafting table. A=amethyst shard, E=echo shard, G=glowstone dust, P=ender pearl, S=string, H=iron hoe, . = empty.\n\n"
                +"Starshard Lantern: .A. / GEG / .A.\nUse in hand for fifteen seconds of night sight and a visible bearing toward a loaded guardian within 64 blocks. Ten-second recharge. No guardian or terrain is summoned.\n\n"
                +"Riftspool: .S. / PEP / .A.\nUse on clear solid ground to anchor. Use again within twenty seconds and 64 blocks to return. It consumes one plain ender pearl and one durability only after a safe return. Blocked, expired, dead, changed-world, changed-mode or reconnected sessions cannot return. Five-second recharge after returning.\n\n"
                +"Keeper's Trowel: .A. / .H. / .E.\nAim at one growing crop within six blocks. Successful native bone-meal growth consumes one plain bone meal and one durability. It never harvests or duplicates crops. One-second recharge.\n\n"
                +"Creative inventory contains the three real items. Survival and Hardcore use the recipes. Tools work without a rank. Creative/Admin kit delivery needs three empty slots and never replaces your items.";
            if(ServerMenu.gearAllowed(p))choices.add(new Choice(Items.BUNDLE,"Get Creative/Admin Astral kit • three empty slots",()->{if(AstralItems.creativeKit(p)==0)CommunityServer.say(p,"Clear three inventory slots or return to an allowed Creative/Admin session. Nothing was replaced.");open(p,Page.RECIPES);}));
            choices.add(new Choice(Items.BOOK,"Back to Astral Wilds",()->open(p,Page.JOURNAL)));
        }
        choices.add(new Choice(Items.ARROW,"Back to Infinity Menu",()->ServerMenu.open(p)));
        return new View(page,title,content,List.copyOf(choices));
    }
    static int open(ServerPlayer p){return open(p,Page.JOURNAL);}
    static int open(ServerPlayer p,Page page){
        if(!emptyScreen(p))return 0;
        if(CrossplaySupport.bedrock(p))return form(p,page,f->{var t=ACTIVE.get(p.getUUID());if(t==null)return false;DELIVERIES.put(p.getUUID(),new Delivery(t,f,p.level().getServer().getTickCount()+3));return true;});
        var view=view(p,page);var chest=new SimpleContainer(54);var cards=CommonsMenu.wrap(view.content());
        for(int i=0;i<cards.size()&&i<8;i++)CreativeStudio.icon(chest,i,Items.WRITTEN_BOOK,view.title()+" • "+(i+1),cards.get(i).toArray(String[]::new));
        for(int i=0;i<view.choices().size();i++){var choice=view.choices().get(i);CreativeStudio.icon(chest,27+i,choice.icon(),choice.label());}
        p.openMenu(new SimpleMenuProvider((sync,inv,who)->new Handler(sync,inv,chest,p,view),Component.literal(view.title())));return 1;
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner;final ServerGamePacketListenerImpl connection;final ServerLevel world;final GameModes.Mode mode;final View view;
        Handler(int sync,Inventory inv,SimpleContainer chest,ServerPlayer p,View view){super(MenuType.GENERIC_9x6,sync,inv,chest,6);owner=p;connection=p.connection;world=p.level();mode=GameModes.current(p);this.view=view;}
        @Override public boolean stillValid(Player p){return p==owner&&owner.connection==connection&&owner.containerMenu==this&&ServerMenu.allowed(owner)&&owner.level()==world&&GameModes.current(owner)==mode;}
        @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected){}
        @Override public void clicked(int slot,int button,ContainerInput action,Player p){
            if(p!=owner||owner.connection!=connection||owner.containerMenu!=this)return;if(!stillValid(p)){owner.closeContainer();return;}
            int i=slot-27;if((action!=ContainerInput.PICKUP&&action!=ContainerInput.QUICK_MOVE)||button<0||button>1||!getCarried().isEmpty()||i<0||i>=view.choices().size()){sendAllDataToRemote();return;}
            owner.closeContainer();view.choices().get(i).action().run();
        }
    }
    static int form(ServerPlayer p,Page page,Predicate<Form> sender){
        if(!emptyScreen(p))return 0;var view=view(p,page);var t=new Ticket(UUID.randomUUID(),p,p.connection,p.level(),GameModes.current(p),p.level().getServer().getTickCount()+1800);ACTIVE.put(p.getUUID(),t);
        var f=SimpleForm.builder().title(view.title()).content(view.content());for(var choice:view.choices())f.button(choice.label());
        f.validResultHandler(r->response(t,()->{int i=r.clickedButtonId();if(i>=0&&i<view.choices().size())view.choices().get(i).action().run();})).closedOrInvalidResultHandler(()->response(t,()->{}));
        if(!sendSafely(sender,f.build())){ACTIVE.remove(p.getUUID(),t);DELIVERIES.remove(p.getUUID());return 0;}return 1;
    }
    static boolean sendSafely(Predicate<Form> sender,Form form){try{return sender.test(form);}catch(RuntimeException unavailable){return false;}}
    static void response(Ticket t,Runnable action){var server=t.world().getServer();if(!server.isSameThread()){server.execute(()->response(t,action));return;}if(ACTIVE.remove(t.player().getUUID(),t)&&t.valid())action.run();}
    static void register(){
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var t=ACTIVE.get(handler.player.getUUID());if(t!=null&&t.connection()==handler){ACTIVE.remove(handler.player.getUUID(),t);DELIVERIES.remove(handler.player.getUUID());}});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{ACTIVE.entrySet().removeIf(e->e.getValue().world().getServer()==server);DELIVERIES.entrySet().removeIf(e->e.getValue().ticket().world().getServer()==server);});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var e:DELIVERIES.entrySet()){var d=e.getValue();if(d.ticket().world().getServer()!=server||server.getTickCount()<d.due())continue;if(!DELIVERIES.remove(e.getKey(),d)||ACTIVE.get(e.getKey())!=d.ticket())continue;
                if(!d.ticket().valid()||!sendSafely(form->{var api=org.geysermc.floodgate.api.FloodgateApi.getInstance();return api!=null&&api.sendForm(e.getKey(),form);},d.form())){ACTIVE.remove(e.getKey(),d.ticket());CommunityServer.say(d.ticket().player(),"The Astral form could not open. Reopen Astral Wilds from your journal.");}
            }
            ACTIVE.entrySet().removeIf(e->e.getValue().world().getServer()==server&&!e.getValue().valid());
            if(server.getTickCount()%100==0)for(var p:server.getPlayerList().getPlayers())if(ServerMenu.allowed(p))reconcile(p);
            if(server.getTickCount()%10==0)for(var p:server.getPlayerList().getPlayers())if(orbitVisible(p)){
                double angle=server.getTickCount()*.09;double x=p.getX()+Math.cos(angle)*.7,z=p.getZ()+Math.sin(angle)*.7;
                for(var viewer:p.level().getPlayers(q->q.isAlive()&&!q.isSpectator()&&q.distanceToSqr(p)<32*32,8)){
                    p.level().sendParticles(viewer,ParticleTypes.END_ROD,false,false,x,p.getY()+1.1,z,1,.02,.02,.02,0);
                    p.level().sendParticles(viewer,ParticleTypes.ENCHANT,false,false,p.getX()*2-x,p.getY()+.7,p.getZ()*2-z,1,.02,.02,.02,0);
                }
            }
        });
    }
}

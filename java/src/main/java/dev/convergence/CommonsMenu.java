package dev.convergence;

import java.util.*;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** One action model drives real Java chests and Bedrock native forms, including exact delivery reviews. */
final class CommonsMenu {
    enum Page { HOME, BUILDER, HARVEST, DELIVERY, ATLAS, PACK }
    record Choice(Item icon,String label,Runnable run) {}
    record View(Page page,String title,String content,List<Choice> choices,List<ItemStack> arrangement) {}
    record Ticket(UUID nonce,ServerPlayer player,ServerGamePacketListenerImpl connection,ServerLevel world,GameModes.Mode mode,int expires) {
        boolean valid(){return player.connection==connection&&player.level()==world&&GameModes.current(player)==mode
            &&CommonsEvents.emptyScreen(player)&&world.getServer().getTickCount()<expires;}
    }
    record Delivery(Ticket ticket,Form form,int due) {}
    static final Map<UUID,Ticket> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<UUID,Delivery> DELIVERIES=new java.util.concurrent.ConcurrentHashMap<>();
    private CommonsMenu() {}
    static String gift(ServerPlayer p,int id){return CommonsEvents.state(p).getBooleanOr("gift_"+id,false)?"claimed":CommonsEvents.earned(p,id)?"ready":"locked";}
    static void fail(ServerPlayer p){CommonsEvents.say(p,"Check the requirements shown in this event menu. No items or progress changed.");}
    static void result(ServerPlayer p,int n,Page next){if(n==0)fail(p);open(p,next);}
    static View view(ServerPlayer p,Page page,CommonsEvents.Quote quote){
        var choices=new ArrayList<Choice>();List<ItemStack> arrangement=List.of();String title,content;
        if(page==Page.HOME){
            title="Circuit Keepers' Commons";
            content="Builder Exchange: invent a powered circuit and prove a real hopper delivery. Harvest Together: share crops and complete the village pantry.\nProgress is permanent. There is no daily timer or paid entry.";
            choices.add(new Choice(Items.REDSTONE_LAMP,"Builder Exchange • Creative workshop",()->open(p,Page.BUILDER)));
            choices.add(new Choice(Items.WHEAT,"Harvest Together • Survival deliveries",()->open(p,Page.HARVEST)));
            choices.add(new Choice(Items.FILLED_MAP,"Permanent feature • Expedition Atlas",()->open(p,Page.ATLAS)));
            choices.add(new Choice(Items.BUNDLE,"Permanent feature • Pack Organizer",()->open(p,Page.PACK)));
        }else if(page==Page.BUILDER){
            title="Infinity Builder Exchange";var a=CommonsEvents.anchor(p);
            content="Circuit Keepers need a working village delivery network!\n1. In Creative, aim at clear ground and review the Exchange blueprint, or register where you stand.\n2. Build the power line: redstone block "+a.offset(-3,0,0).toShortString()+", dust "+a.offset(-2,0,0).toShortString()+" and "+a.offset(-1,0,0).toShortString()+", lamp "+a.toShortString()+". Solid blocks below dust.\n3. Put a DOWN hopper at "+a.offset(3,1,0).toShortString()+" above a barrel "+a.offset(3,0,0).toShortString()+". Empty both.\n4. Run the live delivery trial nearby. The lamp must stay lit.\nSignal: "+CommonsEvents.state(p).getBooleanOr("signal",false)+" • Delivery: "+CommonsEvents.state(p).getBooleanOr("delivery",false)+"\nToolkit: "+gift(p,0)+". Earn an original circuit banner, diamond pickaxe and DevDay Builder title. Five free slots for materials; three for the gift.";
            choices.add(new Choice(Items.STRUCTURE_BLOCK,"Review Exchange blueprint • aim at ground",()->{if(CommonsEvents.preview(p,false)==0){fail(p);open(p,Page.BUILDER);}}));
            choices.add(new Choice(Items.COMPASS,"Register workshop where I stand",()->result(p,CommonsEvents.setWorkshop(p,p.blockPosition()),Page.BUILDER)));
            choices.add(new Choice(Items.CHEST,"Get five workshop materials • Creative only",()->result(p,CommonsEvents.kit(p),Page.BUILDER)));
            choices.add(new Choice(Items.REDSTONE,"Verify powered circuit",()->result(p,CommonsEvents.inspectSignal(p),Page.BUILDER)));
            choices.add(new Choice(Items.HOPPER,"Run live hopper delivery trial",()->result(p,CommonsEvents.startTrial(p),Page.BUILDER)));
            choices.add(new Choice(Items.DIAMOND_PICKAXE,"Claim Builder toolkit • "+gift(p,0),()->result(p,CommonsEvents.claim(p,0),Page.BUILDER)));
            choices.add(new Choice(Items.NAME_TAG,"Wear DevDay Builder title + lantern motes",()->{CommonsEvents.title(p,1);open(p,Page.BUILDER);}));
            choices.add(new Choice(Items.BARRIER,"Stop my running trial",()->{CommonsEvents.stopTrial(p);open(p,Page.BUILDER);}));
        }else if(page==Page.HARVEST){
            title="Infinity Harvest Together";var pantry=CommonsEvents.pantry(p);
            var text=new StringBuilder("Grow, cook and share food with the Circuit Keepers' village. Donations use your current Survival/Hardcore inventory. Named or modified food is preserved. Each delivery asks for confirmation.\n");
            for(int i=0;i<CommonsEvents.FOODS.size();i++){
                var f=CommonsEvents.FOODS.get(i);final int food=i;
                text.append(f.name()).append(": yours ").append(CommonsEvents.donated(p,i)).append('/').append(f.goal()).append(" • village ").append(pantry.total(i)).append('/').append(f.goal()).append('\n');
                choices.add(new Choice(f.item(),"Review "+f.name()+" delivery",()->{var q=CommonsEvents.quote(p,food);if(q==null){CommonsEvents.say(p,"Bring plain "+f.name()+" in Survival or Hardcore. Your personal quota may already be complete.");open(p,Page.HARVEST);}else open(p,Page.DELIVERY,q);}));
            }
            text.append("Your total: ").append(CommonsEvents.contribution(p)).append(". Field kit: 16 donated items, including a usable Seedlight Jar. Guardian: 64 items AND every village category full.\nField kit: ").append(gift(p,1)).append(" • Guardian gift: ").append(gift(p,2)).append(". Each gift needs three empty slots.\nGarden blueprint is an optional Creative build; no food is granted by this menu.");content=text.toString();
            choices.add(new Choice(Items.IRON_HOE,"Claim field kit • "+gift(p,1),()->result(p,CommonsEvents.claim(p,1),Page.HARVEST)));
            choices.add(new Choice(Items.DIAMOND_HOE,"Claim Harvest Guardian gift • "+gift(p,2),()->result(p,CommonsEvents.claim(p,2),Page.HARVEST)));
            choices.add(new Choice(Items.NAME_TAG,"Wear Harvest Guardian title + garden motes",()->{CommonsEvents.title(p,2);open(p,Page.HARVEST);}));
            choices.add(new Choice(Items.FLOWERING_AZALEA,"Review lantern garden • Creative, aim at ground",()->{if(CommonsEvents.preview(p,true)==0){fail(p);open(p,Page.HARVEST);}}));
        }else if(page==Page.ATLAS){
            title="Expedition Atlas";var text=new StringBuilder("A permanent exploration challenge, independent of holidays. Visit each biome family in Survival or Hardcore, then stamp your current location here.\n");
            for(int i=0;i<PlayerWorkshop.BIOMES.size();i++)text.append(PlayerWorkshop.BIOMES.get(i)).append(": ").append(CommonsEvents.state(p).getStringOr("atlas_"+i,"Undiscovered")).append('\n');
            text.append(PlayerWorkshop.stamps(p)).append("/8 stamps. All eight unlock a Wayfinder title, banner, spawn compass and spyglass. Three empty reward slots.\nGift: ").append(gift(p,3));content=text.toString();
            choices.add(new Choice(Items.MAP,"Stamp the biome I am actually in",()->result(p,PlayerWorkshop.stamp(p),Page.ATLAS)));
            choices.add(new Choice(Items.COMPASS,"Claim Wayfinder gift • "+gift(p,3),()->result(p,CommonsEvents.claim(p,3),Page.ATLAS)));
            choices.add(new Choice(Items.NAME_TAG,"Wear Wayfinder title + star motes",()->{CommonsEvents.title(p,3);open(p,Page.ATLAS);}));
            choices.add(new Choice(Items.BUNDLE,"Open Pack Organizer",()->open(p,Page.PACK)));
        }else if(page==Page.PACK){
            title="Review Pack Organizer";var review=PlayerWorkshop.review(p);
            arrangement=review.after().stream().map(ItemStack::copy).toList();
            var text=new StringBuilder("Review your 27 main inventory slots after sorting. Identical stacks merge; named, damaged and filled items stay distinct. Hotbar, armor, offhand and ender chest stay in place.\n");
            for(int i=0;i<review.after().size();i++){var item=review.after().get(i);if(!item.isEmpty())text.append(i+1).append(". ").append(item.getCount()).append(" × ").append(item.getHoverName().getString()).append('\n');}
            text.append("Confirm saves this exact arrangement. Any changed main-inventory slot cancels the review.");content=text.toString();
            choices.add(new Choice(Items.DYE.lime(),"Confirm this arrangement",()->result(p,PlayerWorkshop.apply(p,review),Page.ATLAS)));
            choices.add(new Choice(Items.DYE.red(),"Cancel • leave inventory as it is",()->open(p,Page.ATLAS)));
        }else{
            if(quote==null)throw new IllegalArgumentException("Delivery review needs an exact quote");
            title="Confirm pantry delivery";
            content="Give exactly "+quote.amount()+" "+CommonsEvents.FOODS.get(quote.food()).name()+" from the inventory you reviewed?\nThese items are consumed. Your saved personal contribution becomes "+(quote.previous()+quote.amount())+".\nIf your inventory, game mode or session changes, this review is refused. Cancel keeps everything.";
            choices.add(new Choice(Items.DYE.lime(),"Confirm exact delivery",()->result(p,CommonsEvents.donate(p,quote),Page.HARVEST)));
            choices.add(new Choice(Items.DYE.red(),"Cancel • keep all items",()->open(p,Page.HARVEST)));
        }
        if(page!=Page.DELIVERY){
            choices.add(new Choice(Items.GLASS_BOTTLE,"Hide Commons title and motes",()->{CommonsEvents.title(p,0);open(p,page);}));
            if(page!=Page.HOME)choices.add(new Choice(Items.BOOK,"Back to Commons",()->open(p,Page.HOME)));
            choices.add(new Choice(Items.ARROW,"Back to Infinity Menu",()->ServerMenu.open(p)));
        }
        return new View(page,title,content,List.copyOf(choices),arrangement);
    }
    static int open(ServerPlayer p){return open(p,Page.HOME);}
    static int open(ServerPlayer p,Page page){return open(p,page,null);}
    static int open(ServerPlayer p,Page page,CommonsEvents.Quote quote){
        if(!CommonsEvents.emptyScreen(p))return 0;
        if(CrossplaySupport.bedrock(p))return form(p,page,quote,f->{var t=ACTIVE.get(p.getUUID());if(t==null)return false;DELIVERIES.put(p.getUUID(),new Delivery(t,f,p.level().getServer().getTickCount()+3));return true;});
        var v=view(p,page,quote);var chest=new SimpleContainer(54);
        var lines=wrap(v.content());for(int i=0;i<lines.size()&&i<4;i++)CreativeStudio.icon(chest,i,Items.BOOK,v.title()+" • guide "+(i+1),lines.get(i).toArray(String[]::new));
        if(page==Page.PACK)for(int i=0;i<27;i++)chest.setItem(9+i,v.arrangement().get(i).copy());
        int start=page==Page.PACK?45:10;
        for(int i=0;i<v.choices().size();i++){var choice=v.choices().get(i);CreativeStudio.icon(chest,start+i,choice.icon(),choice.label());}
        p.openMenu(new SimpleMenuProvider((sync,inv,who)->new Handler(sync,inv,chest,p,v),Component.literal(v.title())));return 1;
    }
    static List<List<String>> wrap(String text){
        var lines=new ArrayList<String>();for(String paragraph:text.split("\n")){
            String line="";for(String word:paragraph.split(" ")){if(line.length()+word.length()+1>44){lines.add(line);line="";}line+=(line.isEmpty()?"":" ")+word;}if(!line.isEmpty())lines.add(line);
        }
        var cards=new ArrayList<List<String>>();for(int i=0;i<lines.size();i+=8)cards.add(List.copyOf(lines.subList(i,Math.min(i+8,lines.size()))));return cards;
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner;final ServerGamePacketListenerImpl connection;final ServerLevel world;final GameModes.Mode mode;final View view;
        Handler(int sync,Inventory inv,SimpleContainer chest,ServerPlayer p,View view){super(MenuType.GENERIC_9x6,sync,inv,chest,6);owner=p;connection=p.connection;world=p.level();mode=GameModes.current(p);this.view=view;}
        @Override public boolean stillValid(Player p){return p==owner&&owner.connection==connection&&owner.containerMenu==this&&ServerMenu.allowed(owner)&&owner.level()==world&&GameModes.current(owner)==mode;}
        @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected){}
        @Override public void clicked(int slot,int button,ContainerInput action,Player p){
            if(p!=owner||owner.connection!=connection||owner.containerMenu!=this)return;
            if(!stillValid(p)){owner.closeContainer();return;}
            int i=slot-(view.page()==Page.PACK?45:10);
            if((action!=ContainerInput.PICKUP&&action!=ContainerInput.QUICK_MOVE)||button<0||button>1||!getCarried().isEmpty()||i<0||i>=view.choices().size()){sendAllDataToRemote();return;}
            owner.closeContainer();view.choices().get(i).run().run();
        }
    }
    static int form(ServerPlayer p,Page page,CommonsEvents.Quote q,Predicate<Form> sender){
        if(!CommonsEvents.emptyScreen(p))return 0;
        var v=view(p,page,q);var t=new Ticket(UUID.randomUUID(),p,p.connection,p.level(),GameModes.current(p),p.level().getServer().getTickCount()+1800);ACTIVE.put(p.getUUID(),t);
        var f=SimpleForm.builder().title(v.title()).content(v.content());for(var choice:v.choices())f.button(choice.label());
        f.validResultHandler(r->response(t,()->{int i=r.clickedButtonId();if(i>=0&&i<v.choices().size())v.choices().get(i).run().run();}))
            .closedOrInvalidResultHandler(()->response(t,()->{}));
        if(!sender.test(f.build())){ACTIVE.remove(p.getUUID(),t);CommonsEvents.say(p,"Event form could not be sent. Reconnect and reopen Commons.");return 0;}return 1;
    }
    static void response(Ticket t,Runnable action){
        var server=t.world().getServer();if(!server.isSameThread()){server.execute(()->response(t,action));return;}
        if(!ACTIVE.remove(t.player().getUUID(),t))return;if(t.valid())action.run();
    }
    static void register(){
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var t=ACTIVE.get(handler.player.getUUID());if(t!=null&&t.connection()==handler){ACTIVE.remove(handler.player.getUUID(),t);DELIVERIES.remove(handler.player.getUUID());}});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{ACTIVE.entrySet().removeIf(e->e.getValue().world().getServer()==server);DELIVERIES.entrySet().removeIf(e->e.getValue().ticket().world().getServer()==server);});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var e:DELIVERIES.entrySet()){var d=e.getValue();if(d.ticket().world().getServer()!=server||server.getTickCount()<d.due())continue;
                if(!DELIVERIES.remove(e.getKey(),d)||ACTIVE.get(e.getKey())!=d.ticket())continue;
                var api=org.geysermc.floodgate.api.FloodgateApi.getInstance();
                if(!d.ticket().valid()||api==null||!api.sendForm(e.getKey(),d.form())){ACTIVE.remove(e.getKey(),d.ticket());CommonsEvents.say(d.ticket().player(),"Event form could not be sent. Reopen Commons.");}
            }
            ACTIVE.entrySet().removeIf(e->e.getValue().world().getServer()==server&&!e.getValue().valid());
        });
    }
}

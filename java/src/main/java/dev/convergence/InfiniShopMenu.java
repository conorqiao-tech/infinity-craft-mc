package dev.convergence;

import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** One exact offer drives the Java readonly chest and delayed native Bedrock confirmation. */
public final class InfiniShopMenu {
    record Choice(Item icon,String text,Runnable action) {}
    record View(String title,String content,List<Choice> choices) {}
    record Ticket(UUID nonce,InfiniEconomy economy,ServerPlayer player,ServerGamePacketListenerImpl connection,
                  ServerLevel world,GameModes.Mode mode,net.minecraft.world.level.GameType nativeMode,int created) {
        boolean valid(){int age=world.getServer().getTickCount()-created;return age>=0&&age<1800&&economy.actor(player)&&player.connection==connection
            &&player.level()==world&&GameModes.current(player)==mode&&player.gameMode.getGameModeForPlayer()==nativeMode&&CommonsEvents.emptyScreen(player);}
    }
    record Delivery(Ticket ticket,Form form,int due) {}
    static final Map<UUID,Ticket> ACTIVE=new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<UUID,Delivery> DELIVERIES=new java.util.concurrent.ConcurrentHashMap<>();
    private InfiniShopMenu(){}
    static String dollars(long cents){return "$"+java.math.BigDecimal.valueOf(cents,2).stripTrailingZeros().toPlainString()+" USD";}
    static String priceSummary(boolean permanent){
        var term=permanent?InfiniEconomy.Term.LIFETIME:InfiniEconomy.Term.PASS;
        return String.join("; ",InfiniEconomy.CATALOGUE.products().stream().filter(p->p.enabled()&&!p.staff()&&p.term()==term)
            .map(p->{String tier=p.tier().name().toLowerCase(java.util.Locale.ROOT);return Character.toUpperCase(tier.charAt(0))+tier.substring(1)+" "+dollars(p.usdCents());}).toList());
    }
    static void reply(ServerPlayer p,InfiniEconomy.Result result){p.sendSystemMessage(Component.literal(result.message()));}
    public static int open(ServerPlayer p){return open(p,InfiniEconomy.get(p.level().getServer()),null);}
    static int open(ServerPlayer p,InfiniEconomy economy,InfiniEconomy.Quote quote){
        if(!economy.actor(p)||!CommonsEvents.emptyScreen(p))return 0;
        if(CrossplaySupport.bedrock(p))return form(p,economy,quote,f->{var ticket=ACTIVE.get(p.getUUID());if(ticket==null)return false;DELIVERIES.put(p.getUUID(),new Delivery(ticket,f,p.level().getServer().getTickCount()+3));return true;});
        var view=view(p,economy,quote);var chest=new SimpleContainer(54);var cards=CommonsMenu.wrap(view.content());
        for(int i=0;i<cards.size()&&i<9;i++)CreativeStudio.icon(chest,i,Items.BOOK,view.title()+" • details "+(i+1),cards.get(i).toArray(String[]::new));
        for(int i=0;i<view.choices().size();i++){var choice=view.choices().get(i);CreativeStudio.icon(chest,10+i,choice.icon(),choice.text());}
        p.openMenu(new SimpleMenuProvider((sync,inventory,who)->new Handler(sync,inventory,chest,p,economy,view),Component.literal(view.title())));return 1;
    }
    static View view(ServerPlayer p,InfiniEconomy economy,InfiniEconomy.Quote quote){
        var choices=new ArrayList<Choice>();String title,content;
        if(quote!=null){
            title="Confirm exact Infini purchase";var item=quote.product();
            content="Buy "+item.title()+" for exactly "+InfiniEconomy.format(item.units())+" Infini?\nYour balance after purchase: "+InfiniEconomy.format(economy.balance(p.getUUID())-item.units())+" Infini.\n";
            if(item.staff())content+="This creates a PENDING staff application, not operator access. Only the owner can separately approve this exact request ID and buyer UUID from the actual server console. Approval is not guaranteed.\n"
                +(item.term()==InfiniEconomy.Term.LIFETIME?"After approval, the purchased role has no scheduled expiry.":"After approval, this purchased role lasts exactly 30 days, without automatic renewal.")
                +"\nDenial/cancellation returns the exact Infini cost only after the native role adapter safely clears this purchase's own role. Independent free Admin/OP grants stay separate. AI server actions still require both approvals.\n";
            else content+=(item.term()==InfiniEconomy.Term.LIFETIME?"This cosmetic grant has no expiry.":"This pass expires "+Instant.ofEpochMilli(quote.expires())+" (UTC). There is no automatic renewal.")
                +"\nIncludes the "+item.tier()+" Supporter title and "+SupporterEffects.name(item.styleId())+" visual effect; higher tiers include lower styles. It does not change earned ranks, combat, game modes or staff permissions. The style is selected after purchase.\n";
            content+="This confirmation spends only existing Infini; it cannot charge USD. Any changed wallet, world, game mode or session cancels the exact review. Cancel preserves all Infini.";
            choices.add(new Choice(Items.DYE.lime(),"Confirm • spend "+InfiniEconomy.format(item.units())+" Infini ("+dollars(item.usdCents())+")",()->{reply(p,economy.purchase(p,quote));open(p,economy,null);}));
            choices.add(new Choice(Items.DYE.red(),"Cancel • keep my Infini",()->open(p,economy,null)));
        }else{
            title="Infini • styles and staff applications";
            content="Balance: "+economy.balanceText(p.getUUID())+" Infini. Refund hold: "+InfiniEconomy.format(economy.debt(p.getUUID()))+".\n"+economy.status()
                +"\n1 Infini = $2 USD configured; checkout is NOT connected. Earn Infini in game. These menus spend only your existing balance. No cashout or cross-server value."
                +"\n30-day passes never auto-renew. Permanent styles never expire. Earned achievement/trade ranks stay free and separate. Supporter styles grant no combat advantage or operator access.\n30-day passes: "+priceSummary(false)+". Permanent: "+priceSummary(true)+". ADMIN applications are separately owner-reviewed; review an offer below for its exact configured cost. USD checkout unavailable.\n";
            var text=new StringBuilder(content);
            for(var item:economy.products()){
                choices.add(new Choice(item.term()==InfiniEconomy.Term.LIFETIME?Items.NETHER_STAR:Items.CLOCK,"Review "+item.title()+" • "+InfiniEconomy.format(item.units())+" Infini ("+dollars(item.usdCents())+")",()->{
                    var offer=economy.quote(p,item.id());if(offer==null){reply(p,InfiniEconomy.no("Cannot review: insufficient Infini, an unpaid refund hold, an already-owned permanent tier, or unavailable storage. Nothing changed."));open(p,economy,null);}else open(p,economy,offer);
                }));
            }
            text.append("Your active cosmetic supporter: ").append(economy.activeSupporter(p.getUUID())).append(". Selected style: ").append(economy.selectedStyle(p.getUUID()).isEmpty()?"hidden":economy.supporterTitle(p.getUUID())).append(".\nADMIN purchases create pending owner-reviewed applications. No automatic operator grant; AI actions keep both approval gates.");content=text.toString();
            var requests=economy.staffRequests().stream().filter(r->r.buyer().equals(p.getUUID())).sorted(Comparator.comparingLong(InfiniEconomy.StaffRequest::purchasedAt).reversed()).limit(3).toList();
            for(var request:requests){
                content+="\nYour staff request "+request.id()+": "+request.state()+(request.expiresAt()==0?"":" • expires "+Instant.ofEpochMilli(request.expiresAt()));
                if(request.state()==InfiniEconomy.StaffState.PENDING)choices.add(new Choice(Items.BARRIER,"Cancel pending staff request • return Infini",()->{reply(p,economy.cancelStaff(p,request.id()));open(p,economy,null);}));
            }
            for(var tier:InfiniEconomy.Supporter.values())if(tier!=InfiniEconomy.Supporter.NONE){String style=InfiniEconomy.style(tier);choices.add(new Choice(Items.NAME_TAG,"Wear "+SupporterEffects.name(style)+" • "+(economy.ownedStyle(p.getUUID(),style)?"owned":"locked"),()->{reply(p,economy.selectStyle(p,style));CommonsEvents.refreshTitle(p);open(p,economy,null);}));}
            choices.add(new Choice(Items.GLASS_BOTTLE,"Hide my supporter title and style",()->{reply(p,economy.selectStyle(p,""));CommonsEvents.refreshTitle(p);open(p,economy,null);}));
            choices.add(new Choice(Items.ARROW,"Back to Infinity Menu",()->ServerMenu.open(p)));
        }
        return new View(title,content,List.copyOf(choices));
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner;final ServerGamePacketListenerImpl connection;final ServerLevel world;final GameModes.Mode mode;final net.minecraft.world.level.GameType nativeMode;final InfiniEconomy economy;final View view;
        Handler(int sync,Inventory inventory,SimpleContainer chest,ServerPlayer p,InfiniEconomy economy,View view){super(MenuType.GENERIC_9x6,sync,inventory,chest,6);owner=p;connection=p.connection;world=p.level();mode=GameModes.current(p);nativeMode=p.gameMode.getGameModeForPlayer();this.economy=economy;this.view=view;}
        @Override public boolean stillValid(Player actor){return actor==owner&&owner.connection==connection&&owner.containerMenu==this&&economy.actor(owner)&&owner.level()==world&&GameModes.current(owner)==mode&&owner.gameMode.getGameModeForPlayer()==nativeMode;}
        @Override public ItemStack quickMoveStack(Player actor,int slot){return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected){}
        @Override public void clicked(int slot,int button,ContainerInput input,Player actor){
            if(actor!=owner||owner.connection!=connection||owner.containerMenu!=this)return;
            if(!stillValid(actor)){owner.closeContainer();return;}int index=slot-10;
            if((input!=ContainerInput.PICKUP&&input!=ContainerInput.QUICK_MOVE)||button<0||button>1||!getCarried().isEmpty()||index<0||index>=view.choices().size()){sendAllDataToRemote();return;}
            owner.closeContainer();view.choices().get(index).action().run();
        }
    }
    static int form(ServerPlayer p,InfiniEconomy economy,InfiniEconomy.Quote quote,Predicate<Form> sender){
        if(!economy.actor(p)||!CommonsEvents.emptyScreen(p))return 0;
        var view=view(p,economy,quote);var ticket=new Ticket(UUID.randomUUID(),economy,p,p.connection,p.level(),GameModes.current(p),p.gameMode.getGameModeForPlayer(),p.level().getServer().getTickCount());ACTIVE.put(p.getUUID(),ticket);
        var form=SimpleForm.builder().title(view.title()).content(view.content());for(var choice:view.choices())form.button(choice.text());
        form.validResultHandler(result->response(ticket,()->{int index=result.clickedButtonId();if(index>=0&&index<view.choices().size())view.choices().get(index).action().run();}))
            .closedOrInvalidResultHandler(()->response(ticket,()->{}));
        boolean sent=false;try{sent=sender.test(form.build());}catch(RuntimeException failure){/* Failed native transport must never leave spending authority behind. */}
        if(!sent){ACTIVE.remove(p.getUUID(),ticket);DELIVERIES.remove(p.getUUID());reply(p,InfiniEconomy.no("Infini form could not be sent. Reopen the shop; nothing was charged."));return 0;}return 1;
    }
    static void response(Ticket ticket,Runnable action){
        var server=ticket.world().getServer();if(!server.isSameThread()){server.execute(()->response(ticket,action));return;}
        if(!ACTIVE.remove(ticket.player().getUUID(),ticket))return;if(ticket.valid())action.run();
    }
    static void register(){
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var ticket=ACTIVE.get(handler.player.getUUID());if(ticket!=null&&ticket.connection()==handler){ACTIVE.remove(handler.player.getUUID(),ticket);DELIVERIES.remove(handler.player.getUUID());}});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{ACTIVE.entrySet().removeIf(e->e.getValue().world().getServer()==server);DELIVERIES.entrySet().removeIf(e->e.getValue().ticket().world().getServer()==server);});
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var entry:DELIVERIES.entrySet()){
                var delivery=entry.getValue();if(delivery.ticket().world().getServer()!=server||server.getTickCount()<delivery.due())continue;
                if(!DELIVERIES.remove(entry.getKey(),delivery)||ACTIVE.get(entry.getKey())!=delivery.ticket())continue;
                boolean sent=false;try{var api=org.geysermc.floodgate.api.FloodgateApi.getInstance();sent=delivery.ticket().valid()&&api!=null&&api.sendForm(entry.getKey(),delivery.form());}catch(RuntimeException failure){/* A disconnected client cannot make the server tick fail. */}
                if(!sent){ACTIVE.remove(entry.getKey(),delivery.ticket());reply(delivery.ticket().player(),InfiniEconomy.no("Infini form could not be sent. Reopen the shop; nothing was charged."));}
            }
            ACTIVE.entrySet().removeIf(e->e.getValue().world().getServer()==server&&!e.getValue().valid());
        });
    }
}

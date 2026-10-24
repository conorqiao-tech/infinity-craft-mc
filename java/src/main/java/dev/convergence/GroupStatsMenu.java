package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.geysermc.cumulus.form.Form;

/** Explicit player identities remain fixed from selection through one reviewed batch. */
final class GroupStatsMenu {
    static final int PAGE_SIZE=45, PREVIOUS=45, ACTION=47, BACK=49, NEXT=53;
    private static final Map<UUID,Request> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<UUID,Input> INPUTS=new ConcurrentHashMap<>();
    private record Input(Request request,List<ServerPlayer> targets) {}
    private GroupStatsMenu() {}

    static final class Request {
        final AdminStatsMenu.Session actor,anchor;
        final String stat;
        final double before;
        final int expires;
        final List<AdminStatsMenu.Session> choices;
        final LinkedHashSet<UUID> selected=new LinkedHashSet<>();
        Request(ServerPlayer actor,ServerPlayer anchor,String stat) {
            this.actor=new AdminStatsMenu.Session(actor);this.anchor=new AdminStatsMenu.Session(anchor);this.stat=stat;
            var found=AdminStats.find(anchor,stat);
            before=found==null?Double.NaN:AdminStats.value(anchor,found).base();
            expires=actor.level().getServer().getTickCount()+1800;
            choices=PlayerStatBatch.matching(actor,anchor,stat).stream()
                .sorted(Comparator.comparing((ServerPlayer p)->p.getName().getString().toLowerCase(java.util.Locale.ROOT)).thenComparing(ServerPlayer::getUUID))
                .map(AdminStatsMenu.Session::new).toList();
        }
        boolean eligible(AdminStatsMenu.Session candidate) {
            var player=candidate.player();var found=AdminStats.find(player,stat);
            return candidate.valid() && AdminStats.accessError(actor.player().createCommandSourceStack(),player)==null
                && found!=null && Double.compare(AdminStats.value(player,found).base(),before)==0;
        }
        boolean valid() {
            if(ACTIVE.get(actor.player().getUUID())!=this || !actor.valid() || !AdminStatsMenu.allowed(actor.player())
                || actor.world().getServer().getTickCount()>=expires || !Double.isFinite(before) || !eligible(anchor))return false;
            return choices.stream().filter(s->selected.contains(s.player().getUUID())).allMatch(this::eligible)
                && selected.stream().allMatch(id->choices.stream().anyMatch(s->s.player().getUUID().equals(id)));
        }
        List<ServerPlayer> targets() {
            return choices.stream().filter(s->selected.contains(s.player().getUUID())).map(AdminStatsMenu.Session::player).toList();
        }
        boolean toggle(int index) {
            if(!valid() || index<0 || index>=choices.size() || !eligible(choices.get(index)))return false;
            UUID id=choices.get(index).player().getUUID();
            if(!selected.remove(id))selected.add(id);
            return true;
        }
        String label() {var found=AdminStats.find(anchor.player(),stat);return found==null?stat:found.label();}
    }

    static void register() {
        BedrockGroupStatsMenu.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            var request=ACTIVE.get(handler.player.getUUID());
            if(request!=null && request.actor.player()==handler.player && request.actor.connection()==handler)cancelPending(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            ACTIVE.entrySet().removeIf(e->e.getValue().actor.world().getServer()==server);
            INPUTS.entrySet().removeIf(e->e.getValue().request.actor.world().getServer()==server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var request:List.copyOf(ACTIVE.values()))if(request.actor.world().getServer()==server && !request.valid()) {
                ACTIVE.remove(request.actor.player().getUUID(),request);
                INPUTS.computeIfPresent(request.actor.player().getUUID(),(id,input)->input.request==request?null:input);
            }
        });
    }
    static void cancelPending(ServerPlayer actor) {
        ACTIVE.computeIfPresent(actor.getUUID(),(id,request)->AdminStatsMenu.sameActor(request.actor,actor)?null:request);
        INPUTS.computeIfPresent(actor.getUUID(),(id,input)->AdminStatsMenu.sameActor(input.request.actor,actor)?null:input);
    }
    static void cancel(Request request) {
        UUID id=request.actor.player().getUUID();
        ACTIVE.remove(id,request);
        INPUTS.computeIfPresent(id,(key,input)->input.request==request?null:input);
    }
    static boolean emptyScreen(ServerPlayer actor) {return actor.containerMenu==actor.inventoryMenu && actor.inventoryMenu.getCarried().isEmpty();}
    static void message(ServerPlayer actor,String text) {actor.sendSystemMessage(Component.literal(text));}

    static int open(ServerPlayer actor,ServerPlayer anchor,String stat) {
        if(CrossplaySupport.bedrock(actor))return open(actor,anchor,stat,BedrockGroupStatsMenu.sender(actor));
        var request=begin(actor,anchor,stat);
        if(request==null)return 0;
        show(request,0,null);return 1;
    }
    static int open(ServerPlayer actor,ServerPlayer anchor,String stat,Predicate<Form> sender) {
        var request=begin(actor,anchor,stat);
        if(request==null)return 0;
        BedrockGroupStatsMenu.selection(request,0,sender);return 1;
    }
    private static Request begin(ServerPlayer actor,ServerPlayer anchor,String stat) {
        if(!AdminStatsMenu.allowed(actor) || !emptyScreen(actor) || anchor==null
            || AdminStats.accessError(actor.createCommandSourceStack(),anchor)!=null || AdminStats.find(anchor,stat)==null)return null;
        AdminStatsMenu.cancelEditors(actor);
        var request=new Request(actor,anchor,stat);ACTIVE.put(actor.getUUID(),request);
        if(!request.valid()) {cancelPending(actor);return null;}
        return request;
    }

    static void number(Request request) {
        var actor=request.actor.player();
        if(!request.valid() || !emptyScreen(actor) || request.selected.isEmpty())return;
        AdminStatsMenu.cancelInput(actor);BedrockStatsMenu.cancelPending(actor);BedrockGroupStatsMenu.cancelPending(actor);
        INPUTS.put(actor.getUUID(),new Input(request,request.targets()));
        message(actor,"Enter the exact new "+request.label()+" for the "+request.selected.size()+" selected players, or type cancel. This private input expires in 90 seconds. Nothing changes before confirmation.");
    }
    static boolean consumeChat(ServerPlayer actor,String text) {
        var input=INPUTS.get(actor.getUUID());if(input==null)return false;
        consumeChat(actor,text,input);return true;
    }
    private static void consumeChat(ServerPlayer actor,String text,Input input) {
        var server=input.request.actor.world().getServer();
        if(!server.isSameThread()) {server.execute(()->consumeChat(actor,text,input));return;}
        if(INPUTS.get(actor.getUUID())!=input || input.request.actor.player()!=actor)return;
        var request=input.request;
        if(!request.valid() || !input.targets.equals(request.targets())) {
            INPUTS.remove(actor.getUUID(),input);message(actor,"The selected players or their shared value changed. Reopen the group editor.");return;
        }
        if(text.trim().equalsIgnoreCase("cancel")) {cancel(request);message(actor,"Group stat edit cancelled. Nothing changed.");return;}
        if(!emptyScreen(actor)) {message(actor,"Close your current screen and empty the cursor, then enter the value again.");return;}
        double amount;
        try {amount=Double.parseDouble(text.trim());}catch(NumberFormatException invalid) {message(actor,"Enter a finite numeric value, or type cancel. Nothing changed.");return;}
        var plan=PlayerStatBatch.prepare(actor,input.targets,request.stat,amount);
        String error=plan.invalidReason();
        if(!error.isEmpty()) {message(actor,error);return;}
        INPUTS.remove(actor.getUUID(),input);show(request,0,plan);
    }

    static void show(Request request,int index,PlayerStatBatch.Plan plan) {
        var actor=request.actor.player();
        if(!request.valid() || !emptyScreen(actor))return;
        INPUTS.remove(actor.getUUID());BedrockStatsMenu.cancelPending(actor);BedrockGroupStatsMenu.cancelPending(actor);
        int count=plan==null?request.choices.size():plan.changes().size();
        int page=Math.max(0,Math.min(index,Math.max(0,(count-1)/PAGE_SIZE)));
        var view=new SimpleContainer(54);
        for(int slot=0,i=page*PAGE_SIZE;slot<PAGE_SIZE && i<count;slot++,i++) {
            if(plan==null) {
                var choice=request.choices.get(i);var player=choice.player();
                var current=AdminStats.find(player,request.stat);boolean eligible=request.eligible(choice);
                AgentMenu.icon(view,slot,!eligible?Items.BARRIER:request.selected.contains(player.getUUID())?Items.DYE.lime():Items.PLAYER_HEAD,
                    (!eligible?"Unavailable: ":request.selected.contains(player.getUUID())?"Selected: ":"Select: ")+player.getName().getString()+" • "+(current==null?"unavailable":AdminStatsMenu.number(AdminStats.value(player,current).base())));
                AgentMenu.description(view,slot,"Player ID: "+player.getStringUUID()+". Toggle only this named player. "+(eligible?"":"This player changed; reopen the list."));
            } else {
                var change=plan.changes().get(i);
                AgentMenu.icon(view,slot,Items.PLAYER_HEAD,change.name());
                String details="Player ID: "+change.uuid()+". ";
                for(var adjustment:change.changes())details+=adjustment.id()+": "+AdminStatsMenu.number(adjustment.before())+" → "+AdminStatsMenu.number(adjustment.after())+". ";
                AgentMenu.description(view,slot,details);
            }
        }
        if(page>0)AgentMenu.icon(view,PREVIOUS,Items.ARROW,"Previous players");
        if((page+1)*PAGE_SIZE<count)AgentMenu.icon(view,NEXT,Items.ARROW,"Next players");
        if(plan!=null)AgentMenu.icon(view,ACTION,Items.DYE.lime(),"Confirm changes for "+plan.targets().size()+" selected players");
        else AgentMenu.icon(view,ACTION,request.selected.isEmpty()?Items.BARRIER:Items.WRITABLE_BOOK,
            request.selected.isEmpty()?"Select at least one player":"Enter exact number • "+request.selected.size()+" selected");
        AgentMenu.icon(view,BACK,Items.BARRIER,"Cancel • nothing changes");
        actor.openMenu(new SimpleMenuProvider((sync,inventory,who)->new Handler(sync,inventory,view,request,page,plan),
            Component.literal((plan==null?"Choose matching players • ":"Review player changes • ")+request.label())));
    }

    static final class Handler extends ChestMenu {
        final Request request;final int page;final PlayerStatBatch.Plan plan;final SimpleContainer view;
        Handler(int sync,Inventory inventory,SimpleContainer view,Request request,int page,PlayerStatBatch.Plan plan) {
            super(MenuType.GENERIC_9x6,sync,inventory,view,6);this.request=request;this.page=page;this.plan=plan;this.view=view;
        }
        @Override public boolean stillValid(Player player) {return player==request.actor.player() && player.containerMenu==this && request.valid();}
        @Override public ItemStack quickMoveStack(Player player,int slot) {return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected) {}
        @Override public void clicked(int slot,int button,ContainerInput action,Player player) {
            var actor=request.actor.player();
            if(player!=actor || actor.containerMenu!=this || actor.connection!=request.actor.connection())return;
            if(!stillValid(player)) {actor.closeContainer();cancel(request);message(actor,"The group edit expired or a selected player changed. Nothing changed.");return;}
            if((action!=ContainerInput.PICKUP && action!=ContainerInput.QUICK_MOVE) || button<0 || button>1 || !getCarried().isEmpty() || slot<0 || slot>=54) {sendAllDataToRemote();return;}
            if(slot==BACK) {actor.closeContainer();cancel(request);AdminStatsMenu.openStat(actor,request.anchor.player(),request.stat);return;}
            if((slot==PREVIOUS || slot==NEXT) && !getSlot(slot).getItem().isEmpty()) {actor.closeContainer();show(request,page+(slot==NEXT?1:-1),plan);return;}
            if(slot==ACTION) {
                if(plan==null) {if(request.selected.isEmpty())return;actor.closeContainer();number(request);}
                else {actor.closeContainer();cancel(request);var result=plan.apply();message(actor,result.message());}
                return;
            }
            if(plan==null && slot<PAGE_SIZE && page*PAGE_SIZE+slot<request.choices.size()) {
                if(!request.toggle(page*PAGE_SIZE+slot)) {actor.closeContainer();cancel(request);message(actor,"That player changed. Reopen the group editor.");return;}
                actor.closeContainer();show(request,page,null);
            }
        }
    }
}

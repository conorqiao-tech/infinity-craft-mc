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
import net.minecraft.server.level.ServerPlayer;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Native touch toggles preserve explicitly chosen sessions, never an inferred group. */
final class BedrockGroupStatsMenu {
    static final int PAGE_SIZE=15;
    private record Ticket(UUID nonce,GroupStatsMenu.Request request) {}
    private record Delivery(Ticket ticket,Form form,int due) {}
    private static final Map<UUID,Ticket> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<UUID,Delivery> DELIVERIES=new ConcurrentHashMap<>();
    private BedrockGroupStatsMenu() {}
    static void cancelPending(ServerPlayer actor) {
        ACTIVE.computeIfPresent(actor.getUUID(),(id,ticket)->AdminStatsMenu.sameActor(ticket.request.actor,actor)?null:ticket);
        DELIVERIES.computeIfPresent(actor.getUUID(),(id,delivery)->AdminStatsMenu.sameActor(delivery.ticket.request.actor,actor)?null:delivery);
    }
    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            var t=ACTIVE.get(handler.player.getUUID());
            if(t!=null && t.request.actor.player()==handler.player && t.request.actor.connection()==handler)cancelPending(handler.player);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            ACTIVE.entrySet().removeIf(e->e.getValue().request.actor.world().getServer()==server);
            DELIVERIES.entrySet().removeIf(e->e.getValue().ticket.request.actor.world().getServer()==server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var entry:DELIVERIES.entrySet()) {
                var delivery=entry.getValue();var ticket=delivery.ticket;var actor=ticket.request.actor.player();
                if(ticket.request.actor.world().getServer()!=server || server.getTickCount()<delivery.due)continue;
                if(!DELIVERIES.remove(entry.getKey(),delivery) || ACTIVE.get(entry.getKey())!=ticket)continue;
                if(!ticket.request.valid() || !GroupStatsMenu.emptyScreen(actor)) {ACTIVE.remove(entry.getKey(),ticket);continue;}
                var api=org.geysermc.floodgate.api.FloodgateApi.getInstance();
                if(api==null || !api.sendForm(entry.getKey(),delivery.form)) {
                    ACTIVE.remove(entry.getKey(),ticket);GroupStatsMenu.message(actor,"The group editor could not be sent. Reconnect and reopen the stat editor.");
                }
            }
            ACTIVE.entrySet().removeIf(e->e.getValue().request.actor.world().getServer()==server && !e.getValue().request.valid());
        });
    }
    static Predicate<Form> sender(ServerPlayer actor) {
        return form->{var ticket=ACTIVE.get(actor.getUUID());if(ticket==null)return false;
            DELIVERIES.put(actor.getUUID(),new Delivery(ticket,form,actor.level().getServer().getTickCount()+3));return true;};
    }
    private static Ticket begin(GroupStatsMenu.Request request) {
        var actor=request.actor.player();
        AdminStatsMenu.cancelInput(actor);BedrockStatsMenu.cancelPending(actor);cancelPending(actor);
        var ticket=new Ticket(UUID.randomUUID(),request);ACTIVE.put(actor.getUUID(),ticket);return ticket;
    }
    private static void send(Ticket ticket,Form form,Predicate<Form> sender) {
        if(!sender.test(form)) {ACTIVE.remove(ticket.request.actor.player().getUUID(),ticket);
            GroupStatsMenu.message(ticket.request.actor.player(),"The group editor could not be sent. Reopen the stat editor.");}
    }
    private static void response(Ticket ticket,Runnable action) {
        var server=ticket.request.actor.world().getServer();
        if(!server.isSameThread()) {server.execute(()->response(ticket,action));return;}
        var actor=ticket.request.actor.player();
        if(!ACTIVE.remove(actor.getUUID(),ticket))return;
        DELIVERIES.computeIfPresent(actor.getUUID(),(id,delivery)->delivery.ticket==ticket?null:delivery);
        if(!ticket.request.valid() || !GroupStatsMenu.emptyScreen(actor)) {
            GroupStatsMenu.message(actor,"The group edit expired or the selected players changed. Nothing changed.");return;
        }
        action.run();
    }
    private static void cancel(Ticket ticket) {response(ticket,()->{
        GroupStatsMenu.cancel(ticket.request);GroupStatsMenu.message(ticket.request.actor.player(),"Group stat edit cancelled. Nothing changed.");
    });}

    static void selection(GroupStatsMenu.Request request,int index,Predicate<Form> sender) {
        if(!request.valid() || !GroupStatsMenu.emptyScreen(request.actor.player()))return;
        int page=Math.max(0,Math.min(index,Math.max(0,(request.choices.size()-1)/PAGE_SIZE)));
        var ticket=begin(request);var actions=new ArrayList<Runnable>();
        var form=SimpleForm.builder().title("Choose matching players")
            .content(request.label()+" = "+AdminStatsMenu.number(request.before)+"\nSelected: "+request.selected.size()+". Tap names to select or deselect. No players are selected automatically.");
        for(int i=page*PAGE_SIZE;i<request.choices.size() && i<(page+1)*PAGE_SIZE;i++) {
            int choice=i;var candidate=request.choices.get(i);var player=candidate.player();
            var current=AdminStats.find(player,request.stat);
            form.button((!request.eligible(candidate)?"Unavailable: ":request.selected.contains(player.getUUID())?"✓ ":"○ ")+player.getName().getString()+" • "+(current==null?"unavailable":AdminStatsMenu.number(AdminStats.value(player,current).base())));
            actions.add(()->{if(!request.toggle(choice)) {GroupStatsMenu.cancel(request);GroupStatsMenu.message(request.actor.player(),"That player changed. Reopen the group editor.");return;}
                selection(request,page,sender);});
        }
        if(page>0) {form.button("Previous players");actions.add(()->selection(request,page-1,sender));}
        if((page+1)*PAGE_SIZE<request.choices.size()) {form.button("Next players");actions.add(()->selection(request,page+1,sender));}
        if(!request.selected.isEmpty()) {form.button("Enter exact number • "+request.selected.size()+" selected");actions.add(()->number(request,sender));}
        form.button("Cancel • nothing changes");actions.add(()->{GroupStatsMenu.cancel(request);
            GroupStatsMenu.message(request.actor.player(),"Group stat edit cancelled. Nothing changed.");});
        form.validResultHandler(r->response(ticket,()->{int choice=r.clickedButtonId();if(choice>=0 && choice<actions.size())actions.get(choice).run();}))
            .closedOrInvalidResultHandler(()->cancel(ticket));
        send(ticket,form.build(),sender);
    }

    private static void number(GroupStatsMenu.Request request,Predicate<Form> sender) {
        if(!request.valid() || request.selected.isEmpty())return;
        var targets=request.targets();var ticket=begin(request);
        var form=CustomForm.builder().title("Set selected players' stat")
            .label(request.label()+" • "+targets.size()+" explicitly selected players\nCurrent shared value: "+AdminStatsMenu.number(request.before)
                +"\nHealth and absorption include any needed capacity increase in the review. Group health cannot be set to zero.")
            .input("Exact new value","Example: 5000",AdminStatsMenu.number(request.before));
        form.validResultHandler(r->response(ticket,()->{
            if(!targets.equals(request.targets()))return;
            String text=r.asInput(1);double amount;
            try {amount=Double.parseDouble(text==null?"":text.trim());}
            catch(NumberFormatException invalid) {GroupStatsMenu.message(request.actor.player(),"Enter a finite numeric value. Nothing changed.");number(request,sender);return;}
            var plan=PlayerStatBatch.prepare(request.actor.player(),targets,request.stat,amount);
            String error=plan.invalidReason();
            if(!error.isEmpty()) {GroupStatsMenu.message(request.actor.player(),error);if(request.valid())number(request,sender);return;}
            review(request,plan,0,sender);
        })).closedOrInvalidResultHandler(()->cancel(ticket));
        send(ticket,form.build(),sender);
    }

    private static void review(GroupStatsMenu.Request request,PlayerStatBatch.Plan plan,int index,Predicate<Form> sender) {
        if(!request.valid())return;
        int page=Math.max(0,Math.min(index,Math.max(0,(plan.changes().size()-1)/PAGE_SIZE)));
        var ticket=begin(request);var actions=new ArrayList<Runnable>();
        var form=SimpleForm.builder().title("Review selected player changes")
            .content("Only the "+plan.targets().size()+" explicitly selected players below will change. Review their exact before and after values. Nothing changes until Confirm. "+GroupStatsMenu.vitalsNote(request,plan));
        for(int i=page*PAGE_SIZE;i<plan.changes().size() && i<(page+1)*PAGE_SIZE;i++) {
            var target=plan.changes().get(i);var text=new StringBuilder(target.name()).append("\n");
            for(var change:target.changes())text.append(change.id()).append(": ").append(AdminStatsMenu.number(change.before()))
                .append(" → ").append(AdminStatsMenu.number(change.after())).append("\n");
            for(var clamp:target.consequences())text.append(AdminStatsMenu.clampText(clamp.id(),clamp.before(),clamp.after(),clamp.capacity())).append("\n");
            form.button(text.toString());actions.add(()->review(request,plan,page,sender));
        }
        if(page>0) {form.button("Previous changes");actions.add(()->review(request,plan,page-1,sender));}
        if((page+1)*PAGE_SIZE<plan.changes().size()) {form.button("Next changes");actions.add(()->review(request,plan,page+1,sender));}
        form.button("Confirm changes for "+plan.targets().size()+" selected players");actions.add(()->{
            GroupStatsMenu.cancel(request);var result=plan.apply();GroupStatsMenu.message(request.actor.player(),result.message());
        });
        form.button("Cancel • nothing changes");actions.add(()->{GroupStatsMenu.cancel(request);GroupStatsMenu.message(request.actor.player(),"Group stat edit cancelled. Nothing changed.");});
        form.validResultHandler(r->response(ticket,()->{int choice=r.clickedButtonId();if(choice>=0 && choice<actions.size())actions.get(choice).run();}))
            .closedOrInvalidResultHandler(()->cancel(ticket));
        send(ticket,form.build(),sender);
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;

/** Bedrock's native buttons and number input avoid simulated chest/cursor clicks. */
final class BedrockStatsMenu {
    private static final Map<UUID,Ticket> ACTIVE=new ConcurrentHashMap<>();
    private static final Map<UUID,Delivery> DELIVERIES=new ConcurrentHashMap<>();
    private record Ticket(UUID nonce,AdminStatsMenu.Session actor,AdminStatsMenu.TargetSession target,int expires) {}
    private record Delivery(Ticket ticket,Form form,int due) {}
    private BedrockStatsMenu() {}

    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{ACTIVE.remove(handler.player.getUUID());DELIVERIES.remove(handler.player.getUUID());});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{
            ACTIVE.entrySet().removeIf(e->e.getValue().actor().world().getServer()==server);
            DELIVERIES.entrySet().removeIf(e->e.getValue().ticket().actor().world().getServer()==server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var entry:DELIVERIES.entrySet()) {
                var delivery=entry.getValue();var t=delivery.ticket();
                if(t.actor().world().getServer()!=server || server.getTickCount()<delivery.due())continue;
                if(!DELIVERIES.remove(entry.getKey(),delivery) || ACTIVE.get(entry.getKey())!=t)continue;
                if(!t.actor().valid() || !AdminStatsMenu.allowed(t.actor().player()) || !emptyScreen(t.actor().player())) {
                    response(t,()->{});continue;
                }
                var api=org.geysermc.floodgate.api.FloodgateApi.getInstance();
                if(api==null || !api.sendForm(entry.getKey(),delivery.form())) {
                    ACTIVE.remove(entry.getKey(),t);message(t.actor().player(),"The Bedrock editor could not be sent. Reconnect and reopen My stats.");
                }
            }
            ACTIVE.entrySet().removeIf(e->{
            var t=e.getValue();
            return t.actor().world().getServer()==server && (!t.actor().valid() || server.getTickCount()>=t.expires());
            });
        });
    }

    static int open(ServerPlayer actor,LivingEntity target,String stat) {
        return open(actor,target,stat,form->{
            // Let the Bedrock client finish closing its chest/previous form first.
            var t=ACTIVE.get(actor.getUUID());
            if(t==null)return false;
            DELIVERIES.put(actor.getUUID(),new Delivery(t,form,actor.level().getServer().getTickCount()+3));return true;
        });
    }

    // A sender parameter lets native tests exercise the same encoded forms and callbacks.
    static int open(ServerPlayer actor,LivingEntity target,String stat,Predicate<Form> sender) {
        if(!AdminStatsMenu.allowed(actor) || !emptyScreen(actor)) { message(actor,"Close other screens before editing stats. OP level 4 is required.");return 0; }
        if(target!=null && AdminStats.accessError(actor.createCommandSourceStack(),target)!=null) {
            message(actor,AdminStats.accessError(actor.createCommandSourceStack(),target));return 0;
        }
        if(target==null) targets(actor,sender);
        else if(stat==null) stats(actor,target,sender);
        else edit(actor,target,stat,sender);
        return 1;
    }

    private static boolean emptyScreen(ServerPlayer actor) {
        return actor.containerMenu==actor.inventoryMenu && actor.inventoryMenu.getCarried().isEmpty();
    }
    private static void message(ServerPlayer actor,String text) { actor.sendSystemMessage(Component.literal(text)); }
    private static Ticket begin(ServerPlayer actor,LivingEntity target) {
        var t=new Ticket(UUID.randomUUID(),new AdminStatsMenu.Session(actor),target==null?null:new AdminStatsMenu.TargetSession(target),actor.level().getServer().getTickCount()+1800);
        ACTIVE.put(actor.getUUID(),t);return t;
    }
    private static void send(Ticket t,Form form,Predicate<Form> sender) {
        if(!sender.test(form)) { ACTIVE.remove(t.actor().player().getUUID(),t);message(t.actor().player(),"The Bedrock editor could not be sent. Reopen My stats after reconnecting."); }
    }
    private static void response(Ticket t,Runnable action) {
        var server=t.actor().world().getServer();
        if(!server.isSameThread()) { server.execute(()->response(t,action));return; }
        var actor=t.actor().player();
        if(!ACTIVE.remove(actor.getUUID(),t))return; // Old, expired or replayed form.
        if(!t.actor().valid() || !AdminStatsMenu.allowed(actor) || !emptyScreen(actor) || server.getTickCount()>=t.expires()
                || t.target()!=null && (!t.target().valid() || AdminStats.accessError(actor.createCommandSourceStack(),t.target().entity())!=null)) {
            message(actor,"This review expired or the player, AI helper or permission changed. Reopen My stats.");return;
        }
        action.run();
    }
    private static void cancel(Ticket t) { response(t,()->message(t.actor().player(),"Stat edit cancelled. Nothing changed.")); }

    private static void targets(ServerPlayer actor,Predicate<Form> sender) {
        var server=actor.level().getServer();
        var choices=new ArrayList<>(server.getPlayerList().getPlayers().stream()
            .sorted(Comparator.comparing((ServerPlayer p)->p!=actor).thenComparing(p->p.getName().getString()))
            .map(AdminStatsMenu.TargetSession::new).toList());
        AgentCompanions.get(server).loaded.values().stream().map(AdminStatsMenu.TargetSession::new)
            .filter(AdminStatsMenu.TargetSession::valid).sorted(Comparator.comparing(t->AdminStats.displayName(t.entity()))).forEach(choices::add);
        var t=begin(actor,null);
        var form=SimpleForm.builder().title("Edit players and AI").content("Choose a living target. Only your own confirmation is needed for manual stat edits.");
        for(var choice:choices)form.button((choice.entity()==actor?"Yourself: ":"")+AdminStats.displayName(choice.entity()));
        form.validResultHandler(r->response(t,()->{
            int i=r.clickedButtonId();
            if(i<0 || i>=choices.size() || !choices.get(i).valid()) {message(actor,"That target changed. Reopen the editor.");return;}
            open(actor,choices.get(i).entity(),null,sender);
        })).closedOrInvalidResultHandler(()->cancel(t));
        send(t,form.build(),sender);
    }

    private static void stats(ServerPlayer actor,LivingEntity target,Predicate<Form> sender) {
        var choices=AdminStats.list(target);var t=begin(actor,target);
        var form=SimpleForm.builder().title("Stats: "+AdminStats.displayName(target))
            .content("Tap a stat, type its value, then Confirm. Health and absorption automatically include any needed capacity increase. The numbers here are authoritative even when the client bars stop growing.");
        for(var stat:choices)form.button(stat.label()+" = "+AdminStatsMenu.number(AdminStats.value(target,stat).base()));
        form.button("Restore all edited attributes").button("Back to Infinity Menu");
        form.validResultHandler(r->response(t,()->{
            int i=r.clickedButtonId();
            if(i>=0 && i<choices.size())edit(actor,target,choices.get(i).id(),sender);
            else if(i==choices.size())review(actor,target,AdminStatsMenu.Operation.RESET_ALL,null,0,restorations(target,null),sender);
            else if(i==choices.size()+1)ServerMenu.open(actor);
        })).closedOrInvalidResultHandler(()->cancel(t));
        send(t,form.build(),sender);
    }

    private static List<AdminStatsMenu.Change> restorations(LivingEntity target,String id) {
        return AdminStats.list(target).stream().filter(s->s.attribute() && (id==null || s.id().equals(id)) && AdminStats.value(target,s).edited())
            .map(s->new AdminStatsMenu.Change(s.id(),AdminStats.value(target,s).base(),AdminStats.original(target,s))).toList();
    }

    private static void edit(ServerPlayer actor,LivingEntity target,String id,Predicate<Form> sender) {
        var stat=AdminStats.find(target,id);
        if(stat==null) {message(actor,"That stat is no longer available.");return;}
        var t=begin(actor,target);
        String range=AdminStats.inputMaximum(stat)==Float.MAX_VALUE ? "Enter a finite value of at least "+AdminStatsMenu.number(stat.minimum())+"." : "Range: "+AdminStatsMenu.number(stat.minimum())+" to "+AdminStatsMenu.number(stat.maximum());
        var form=CustomForm.builder().title(stat.label()+" • "+AdminStats.displayName(target))
            .label("Current: "+AdminStatsMenu.number(AdminStats.value(target,stat).base())+"\nEffective: "+AdminStatsMenu.number(AdminStats.value(target,stat).effective())+"\n"+range+"\n"+AdminStats.boundsHint(stat))
            .input("Exact new value","Example: 5000",AdminStatsMenu.number(AdminStats.value(target,stat).base()))
            .dropdown("Action",stat.attribute()?List.of("Set exact value","Restore original attribute"):List.of("Set exact value"));
        form.validResultHandler(r->response(t,()->{
            if(r.asDropdown(2)==1 && stat.attribute()) {
                review(actor,target,AdminStatsMenu.Operation.RESET,id,0,restorations(target,id),sender);return;
            }
            double amount;
            try {amount=Double.parseDouble(r.asInput(1).trim());}
            catch(NumberFormatException invalid) {message(actor,"Enter a numeric value. Nothing changed.");edit(actor,target,id,sender);return;}
            var current=AdminStats.find(target,id);
            if(current==null || !Double.isFinite(amount) || amount<current.minimum() || amount>AdminStats.inputMaximum(current) || current.integer() && amount!=Math.rint(amount)) {
                message(actor,"That value is outside the current range. Nothing changed.");edit(actor,target,id,sender);return;
            }
            amount=AdminStats.normalizedValue(current,amount);
            try {review(actor,target,AdminStatsMenu.Operation.SET,id,amount,AdminStatsMenu.plannedChanges(target,id,amount),sender);}
            catch(IllegalArgumentException invalid){message(actor,invalid.getMessage());edit(actor,target,id,sender);}
        })).closedOrInvalidResultHandler(()->cancel(t));
        send(t,form.build(),sender);
    }

    private static void review(ServerPlayer actor,LivingEntity target,AdminStatsMenu.Operation operation,String id,double pending,
            List<AdminStatsMenu.Change> changes,Predicate<Form> sender) {
        if(changes.isEmpty()) {message(actor,"No saved admin edits to restore.");stats(actor,target,sender);return;}
        var t=begin(actor,target);var text=new StringBuilder("Target: ").append(AdminStats.displayName(target)).append("\n");
        for(var change:changes)text.append(change.id()).append(": ").append(AdminStatsMenu.number(change.before())).append(" → ").append(AdminStatsMenu.number(change.after())).append("\n");
        boolean lethal=changes.stream().anyMatch(c->c.id().equals("health") && c.after()==0);
        if(lethal)text.append("WARNING: zero health kills the target.\n");
        if(operation==AdminStatsMenu.Operation.SET && !AdminStats.find(target,id).attribute())text.append("Sets this exact amount even if normal gameplay changes the current value.\n");
        text.append("Manual stat edits do not wait for AI or Codex approval. This review expires after 90 seconds.");
        var form=ModalForm.builder().title("Confirm stat edit").content(text.toString()).button1(lethal?"Confirm • KILL target":"Confirm changes").button2("Cancel");
        form.validResultHandler(r->response(t,()->{
            if(!r.clickedFirst()) {message(actor,"Stat edit cancelled. Nothing changed.");stats(actor,target,sender);return;}
            String error=AdminStatsMenu.reviewError(target,operation,pending,changes);
            if(error==null && operation!=AdminStatsMenu.Operation.SET && !changes.equals(restorations(target,operation==AdminStatsMenu.Operation.RESET_ALL?null:id)))
                error="The saved originals changed. Review the restore again.";
            if(error!=null) {message(actor,error);stats(actor,target,sender);return;}
            var result=switch(operation) {
                case SET->AdminStats.set(actor.createCommandSourceStack(),target,id,pending);
                case RESET->AdminStats.reset(actor.createCommandSourceStack(),target,id);
                case RESET_ALL->AdminStats.resetAll(actor.createCommandSourceStack(),target);
            };
            message(actor,result.message());
            if(AdminStatsMenu.allowed(actor) && t.target().valid())stats(actor,target,sender);
        })).closedOrInvalidResultHandler(()->cancel(t));
        send(t,form.build(),sender);
    }
}

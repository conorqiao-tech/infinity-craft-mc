package dev.convergence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** One session for helper controls and exact-order reviews; form replies never run off-thread. */
final class HelperForms {
    record Choice(String label, Runnable action) {}
    record Ticket(UUID nonce, AdminStatsMenu.Session actor, BooleanSupplier context, int expires, Predicate<Form> sender) {
        boolean sessionValid() {
            var p = actor.player();
            return actor.valid() && AgentMenu.allowed(p) && emptyScreen(p)
                && p.level().getServer().getTickCount() - expires < 0;
        }
        boolean valid() { return sessionValid() && context.getAsBoolean(); }
    }
    record Delivery(Ticket ticket, Form form, int due) {}
    static final Map<UUID, Ticket> ACTIVE = new ConcurrentHashMap<>();
    static final Map<UUID, Delivery> DELIVERIES = new ConcurrentHashMap<>();
    private HelperForms() {}

    static boolean emptyScreen(ServerPlayer p) {
        return p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty();
    }

    static void message(ServerPlayer p, String text) { p.sendSystemMessage(Component.literal(text)); }

    static Predicate<Form> sender(ServerPlayer p) {
        return form -> {
            var t = ACTIVE.get(p.getUUID());
            if (t == null || t.actor().player() != p) return false;
            DELIVERIES.put(p.getUUID(), new Delivery(t, form, p.level().getServer().getTickCount() + 3));
            return true;
        };
    }

    static int show(ServerPlayer p, String title, String content, List<Choice> choices,
            BooleanSupplier context, Predicate<Form> sender) {
        if (!AgentMenu.allowed(p) || !emptyScreen(p)) {
            message(p, "Close other screens before using helpers. A living OP4 owner is required.");
            return 0;
        }
        var actions = List.copyOf(choices);
        var t = new Ticket(UUID.randomUUID(), new AdminStatsMenu.Session(p), context,
            p.level().getServer().getTickCount() + 1800, sender);
        if (!t.sessionValid()) { message(p, "This helper screen expired. Reopen AI Helpers."); return 0; }
        if (!context.getAsBoolean()) return changed(p, sender);
        ACTIVE.put(p.getUUID(), t);
        DELIVERIES.remove(p.getUUID());
        var form = SimpleForm.builder().title(title).content(content
            + "\n\nReopen after changing worlds or reconnecting. This screen expires after 90 seconds.");
        for (var action : actions) form.button(action.label());
        form.validResultHandler(r -> response(t, () -> {
            int i = r.clickedButtonId();
            if (i >= 0 && i < actions.size()) actions.get(i).action().run();
        })).closedOrInvalidResultHandler(() -> cancel(t));
        return send(t, form.build(), sender) ? 1 : 0;
    }

    private static int changed(ServerPlayer p, Predicate<Form> sender) {
        return show(p, "Helper or order changed", "The helper or order changed, or its target is temporarily unavailable. No action was applied. Reopen your squad to review its current state.",
            List.of(new Choice("Reopen AI Helpers", () -> BedrockAgentMenu.open(p, sender))), () -> true, sender);
    }

    static boolean send(Ticket t, Form form, Predicate<Form> sender) {
        boolean sent;
        try { sent = sender.test(form); }
        catch (RuntimeException failure) { sent = false; }
        if (sent) return true;
        clear(t);
        message(t.actor().player(), "The helper screen could not be sent. Reconnect and reopen AI Helpers.");
        return false;
    }

    private static void cancel(Ticket t) {
        var server = t.actor().world().getServer();
        if (!server.isSameThread()) { server.execute(() -> cancel(t)); return; }
        clear(t);
    }

    static void response(Ticket t, Runnable action) {
        var server = t.actor().world().getServer();
        if (!server.isSameThread()) { server.execute(() -> response(t, action)); return; }
        if (!ACTIVE.remove(t.actor().player().getUUID(), t)) return;
        DELIVERIES.computeIfPresent(t.actor().player().getUUID(), (id, d) -> d.ticket() == t ? null : d);
        if (!t.sessionValid()) {
            message(t.actor().player(), "This helper screen expired, or its player, helper or order changed. Reopen AI Helpers.");
            return;
        }
        if (!t.context().getAsBoolean()) {
            changed(t.actor().player(), t.sender());
            return;
        }
        action.run();
    }

    private static void clear(Ticket t) {
        ACTIVE.remove(t.actor().player().getUUID(), t);
        DELIVERIES.computeIfPresent(t.actor().player().getUUID(), (id, d) -> d.ticket() == t ? null : d);
    }

    // Called by the normal server tick; exposed to native tests to exercise queued delivery checks.
    static void tick(MinecraftServer server) {
        for (var e : DELIVERIES.entrySet()) {
            var d = e.getValue(); var t = d.ticket();
            if (t.actor().world().getServer() != server || server.getTickCount() - d.due() < 0) continue;
            if (!DELIVERIES.remove(e.getKey(), d) || ACTIVE.get(e.getKey()) != t) continue;
            if (!t.valid()) {
                if (t.sessionValid()) response(t, () -> {}); else clear(t);
                continue;
            }
            send(t, d.form(), form -> {
                var api = org.geysermc.floodgate.api.FloodgateApi.getInstance();
                return api != null && api.sendForm(e.getKey(), form);
            });
        }
        ACTIVE.entrySet().removeIf(e -> {
            var t = e.getValue();
            if (t.actor().world().getServer() != server || t.sessionValid()) return false;
            DELIVERIES.computeIfPresent(e.getKey(), (id, d) -> d.ticket() == t ? null : d);
            return true;
        });
    }

    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var t = ACTIVE.get(handler.player.getUUID());
            if (t != null && t.actor().player() == handler.player && t.actor().connection() == handler) clear(t);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE.entrySet().removeIf(e -> e.getValue().actor().world().getServer() == server);
            DELIVERIES.entrySet().removeIf(e -> e.getValue().ticket().actor().world().getServer() == server);
        });
        ServerTickEvents.END_SERVER_TICK.register(HelperForms::tick);
    }
}

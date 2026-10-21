package dev.convergence;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.LivingEntity;

/** Explicit non-player targets are temporary exact-entity orders, never a broad damage exemption. */
final class AgentEntityTargets {
    static final long LIFETIME_MS = 5 * 60_000L;
    static final Map<MinecraftServer, AgentEntityTargets> INSTANCES = new WeakHashMap<>();
    record Order(ServerPlayer owner, ServerGamePacketListenerImpl connection, LivingEntity target,
                 UUID targetId, ServerLevel world, long issued, long expires) {}
    final MinecraftServer server;
    final Clock clock;
    final Map<UUID, Order> orders = new HashMap<>();

    AgentEntityTargets(MinecraftServer server, Clock clock) { this.server = server; this.clock = clock; }
    static AgentEntityTargets get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new AgentEntityTargets(s, Clock.systemUTC()));
    }

    static String problem(ServerPlayer owner, LivingEntity target) {
        if (owner == null || !AgentMenu.allowed(owner)) return "A living, connected OP4 owner is required.";
        if (target == null || target instanceof ServerPlayer) return "Player targets use the separate two-approval player order.";
        if (!target.isAlive() || target.isRemoved() || target.isSpectator()) return "That target is no longer available.";
        if (AgentCompanions.registeredHelper(target)) return "Registered helpers cannot be squad targets.";
        if (target.level() != owner.level() || owner.level().getEntity(target.getUUID()) != target)
            return "The exact target must remain loaded in your current world.";
        if (owner.distanceToSqr(target) > 48 * 48) return "The target must remain within 48 blocks of you.";
        var helpers = AgentCompanions.get(owner.level().getServer());
        for (var entry : helpers.loaded.entrySet()) {
            var agent = helpers.data.agents.get(entry.getKey().toString());
            if (agent != null && helpers.playerOrderHelperAvailable(entry.getValue(), agent, owner)) return null;
        }
        return "Choose Follow or Guard for a loaded Primitive or Ultimate Finals helper first.";
    }

    /** Pure view used by status screens; expiry checks never alter combat during a read. */
    static Order peek(ServerPlayer owner) {
        if (owner == null) return null;
        var targets = INSTANCES.get(owner.level().getServer());
        if (targets == null) return null;
        var order = targets.orders.get(owner.getUUID());
        // An old disconnected actor must never revoke a replacement session's order.
        if (order == null || order.owner != owner) return null;
        long now = targets.clock.millis();
        if (order.connection != owner.connection || order.world != owner.level()
                || order.targetId != null && !order.targetId.equals(order.target.getUUID())
                || order.world != order.target.level() || now < order.issued || now >= order.expires
                || problem(owner, order.target) != null) {
            return null;
        }
        return order;
    }

    static LivingEntity current(ServerPlayer owner) {
        var valid = peek(owner);
        if (valid != null) return valid.target;
        if (owner != null) {
            var targets = INSTANCES.get(owner.level().getServer());
            if (targets != null) {
                var order = targets.orders.get(owner.getUUID());
                if (order != null && order.owner == owner) targets.orders.remove(owner.getUUID(), order);
            }
        }
        return null;
    }

    static String summary(ServerPlayer owner) {
        var order = peek(owner);
        if (order == null) return "Explicit mob target: none. Choose a nearby living target in Orders; ordinary defense still chooses hostiles only.";
        long remaining = Math.max(0, (order.expires - get(owner.level().getServer()).clock.millis() + 999) / 1000);
        return "Explicit mob target: " + AgentEntityTargetMenu.plain(order.target.getName().getString()) + " • " + order.targetId
            + "; " + remaining + "s remaining. Ceasefire stops this exact order.";
    }

    static boolean matches(ServerPlayer owner, LivingEntity target) {
        // Native Mob.getTarget checks canAttack. That getter is also used by
        // status screens, so permission checks must not retire an order here.
        var order = peek(owner);
        return target != null && order != null && order.target == target;
    }

    static boolean select(ServerPlayer owner, LivingEntity target) {
        if (problem(owner, target) != null) return false;
        // Retire prior player approvals before creating the new, explicitly chosen mob order.
        AgentActions.get(owner.level().getServer()).ceasefire(owner);
        if (problem(owner, target) != null) return false;
        var targets = get(owner.level().getServer());
        long now = targets.clock.millis();
        targets.orders.put(owner.getUUID(), new Order(owner, owner.connection, target, target.getUUID(),
            owner.level(), now, now + LIFETIME_MS));
        return true;
    }

    static void clear(ServerPlayer owner) {
        if (owner == null) return;
        var targets = INSTANCES.get(owner.level().getServer());
        if (targets == null) return;
        var order = targets.orders.get(owner.getUUID());
        if (order != null && (order.owner == owner || targets.server.getPlayerList().getPlayer(owner.getUUID()) == owner))
            targets.orders.remove(owner.getUUID(), order);
    }

    static void invalidate(ServerPlayer owner) { clear(owner); }

    private static void unavailable(LivingEntity target) {
        var targets = INSTANCES.get(target.level().getServer());
        if (targets != null) targets.orders.entrySet().removeIf(e -> e.getValue().target == target);
    }

    static void initialize() {
        ServerPlayerEvents.LEAVE.register(AgentEntityTargets::invalidate);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> invalidate(oldPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> invalidate(player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) invalidate(player);
            unavailable(entity);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof LivingEntity living) unavailable(living);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
    }
}

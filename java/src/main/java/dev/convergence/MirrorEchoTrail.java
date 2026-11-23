package dev.convergence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** A four-tick arc reaches the next banner before the shard's five-tick cooldown ends. */
final class MirrorEchoTrail {
    private record Arc(EchoChallenge run, MinecraftServer server, Vec3 from, Vec3 to, int started) {}
    private static final Map<ServerPlayer, Arc> ARCS = new ConcurrentHashMap<>();
    private static final int STEPS = 8;

    private MirrorEchoTrail() {}

    private static Vec3 above(BlockPos pos) { return Vec3.atCenterOf(pos).add(0, 1.65, 0); }

    static void star(ServerPlayer p, BlockPos pos) {
        var tip = above(pos);
        p.level().sendParticles(p, ParticleTypes.END_ROD, false, false, tip.x, tip.y, tip.z, 5, .07, .14, .07, .012);
        flare(p, tip);
    }

    static void connect(ServerPlayer p, BlockPos from, BlockPos to, EchoChallenge run) {
        var server = p.level().getServer();
        ARCS.put(p, new Arc(run, server, above(from), above(to), server.getTickCount()));
    }

    private static void flare(ServerPlayer p, Vec3 to) {
        p.level().sendParticles(p, ParticleTypes.GLOW, false, false, to.x, to.y, to.z, 4, .08, .08, .08, 0);
    }

    static void tick(MinecraftServer server) {
        if (ARCS.isEmpty()) return;
        ARCS.entrySet().removeIf(entry -> {
            var p = entry.getKey();
            var arc = entry.getValue();
            if (arc.server != server) return false;
            int elapsed = server.getTickCount() - arc.started;
            if (!arc.run.valid(p) || elapsed > STEPS / 2) return true;
            if (elapsed <= 0) return false;
            for (int step = 2 * elapsed - 1; step <= 2 * elapsed; step++) {
                double t = (double)step / STEPS;
                var point = arc.from.lerp(arc.to, t).add(0, 3 * t * (1 - t), 0);
                p.level().sendParticles(p, ParticleTypes.END_ROD, false, false, point.x, point.y, point.z, 1, 0, 0, 0, 0);
            }
            if (elapsed < STEPS / 2) return false;
            flare(p, arc.to);
            return true;
        });
    }

    static void cancel(ServerPlayer p) { ARCS.remove(p); }
    static void stopped(MinecraftServer server) { ARCS.values().removeIf(arc -> arc.server == server); }
}

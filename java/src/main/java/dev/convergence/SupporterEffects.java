package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Small, original particle compositions; cosmetic purchases never alter combat or movement. */
final class SupporterEffects {
    record Glint(ParticleOptions particle, Vec3 local) {}
    @FunctionalInterface interface Sink { void send(ServerPlayer player, Glint glint); }
    private SupporterEffects() {}

    static String title(ServerPlayer p) {
        return InfiniEconomy.get(p.level().getServer()).supporterTitle(p.getUUID());
    }

    static String name(String style) {
        return switch (style) {
            case "supporter_go" -> "Copper Comet";
            case "supporter_plus" -> "Aurora Helix";
            case "supporter_pro" -> "Rift Halo";
            case "supporter_ultra" -> "Starlight Crown";
            default -> "Hidden";
        };
    }

    /** Positions are in blocks around the player. No display entities, armor overlays or physics. */
    static List<Glint> frame(String style, long ticks) {
        int count = switch (style) {
            case "supporter_go" -> 3;
            case "supporter_plus" -> 4;
            case "supporter_pro" -> 6;
            case "supporter_ultra" -> 8;
            default -> 0;
        };
        var result = new ArrayList<Glint>(count);
        double phase = Math.floorMod(ticks, 2400) * Math.PI / 100;
        for (int i = 0; i < count; i++) {
            double a = phase + i * Math.PI * 2 / count;
            double radius = style.equals("supporter_go") ? .3 : .48;
            double y = switch (style) {
                case "supporter_go" -> .14 + i * .12;
                case "supporter_plus" -> .35 + i * .36;
                case "supporter_pro" -> 1.1 + Math.sin(a * 2) * .2;
                default -> 1.95 + (i % 2) * .12;
            };
            ParticleOptions particle = switch (style) {
                case "supporter_go" -> ParticleTypes.WAX_ON;
                case "supporter_plus" -> i % 2 == 0 ? ParticleTypes.ENCHANT : ParticleTypes.END_ROD;
                case "supporter_pro" -> ParticleTypes.REVERSE_PORTAL;
                default -> i % 2 == 0 ? ParticleTypes.END_ROD : ParticleTypes.TOTEM_OF_UNDYING;
            };
            result.add(new Glint(particle, new Vec3(Math.cos(a) * radius, y, Math.sin(a) * radius)));
        }
        return List.copyOf(result);
    }

    static int render(ServerPlayer p, InfiniEconomy economy, int ticks, Sink sink) {
        if (!ServerMenu.allowed(p) || p.isInvisible() || !economy.available()) return 0;
        var frame = frame(economy.selectedStyle(p.getUUID()), ticks);
        for (var glint : frame) sink.send(p, glint);
        return frame.size();
    }

    static void send(ServerPlayer p, Glint glint) {
        var at = p.position().add(glint.local());
        for (var viewer : p.level().getPlayers(v -> v.isAlive() && v.distanceToSqr(p) <= 48 * 48, 8)) {
            p.level().sendParticles(viewer, glint.particle(), false, false,
                at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int ticks = server.getTickCount();
            if (ticks % 40 != 0) return;
            var players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;
            var economy = InfiniEconomy.get(server);
            // Rotate the starting player; at most 128 source points per two-second pass.
            int start = Math.floorMod(ticks / 40, players.size()), remaining = 128;
            for (int i = 0; i < players.size() && remaining >= 8; i++)
                remaining -= render(players.get((start + i) % players.size()), economy, ticks, SupporterEffects::send);
        });
    }
}

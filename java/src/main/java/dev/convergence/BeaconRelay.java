package dev.convergence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

record BeaconRelay(BlockPos glass, BlockPos lead, Phase phase) {
    enum Phase { RELAY, AT_GLASS, AT_OBSERVER, AT_LEAD, FINISHED }

    BeaconRelay(BlockPos glass) { this(glass.immutable(), null, Phase.RELAY); }

    boolean complete() { return phase != Phase.RELAY; }

    BeaconRelay at(Phase next) { return new BeaconRelay(glass, lead, next); }

    String rejection(MidpointBeacon span, BlockPos tap) {
        if (complete()) return "Infinity ribbon complete.";
        boolean atFirst = tap.equals(span.first());
        if (!atFirst && !tap.equals(span.second())) return "Choose one of the two span endpoints.";
        if (tap.equals(lead)) return "That lantern already answered. Visit the other endpoint.";
        BlockPos other = atFirst ? span.second() : span.first();
        if (lead == null && glass.distSqr(tap) > glass.distSqr(other))
            return "Start at the endpoint nearer the lit glass. Count its height, too.";
        return null;
    }

    BeaconRelay advance(BlockPos tap) {
        return lead == null ? new BeaconRelay(glass, tap.immutable(), Phase.RELAY) : at(Phase.AT_GLASS);
    }

    void draw(ServerPlayer p, BlockPos tap) {
        var crown = Vec3.atCenterOf(glass).add(0, 1.35, 0);
        var tip = Vec3.atCenterOf(tap).add(0, .7, 0);
        GalleryTrail.drawLink(p, crown, tip, 1, .35);
        GalleryTrail.spark(p, ParticleTypes.GLOW, tip);
        if (!complete()) return;
        GalleryTrail.drawLink(p, Vec3.atCenterOf(lead).add(0, .7, 0), crown, 1, .35);
        double yaw = Math.toRadians(p.getYRot());
        var across = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        for (int i = 0; i < 32; i++) {
            double t = Math.PI * i / 16;
            var ribbon = crown.add(across.scale(.75 * Math.sin(t))).add(0, .29 * Math.sin(2 * t), 0);
            GalleryTrail.spark(p, ParticleTypes.WAX_ON, ribbon);
        }
        GalleryTrail.spark(p, ParticleTypes.GLOW, crown);
    }

    void drawReturn(ServerPlayer p, BlockPos finisher, BlockPos observer) {
        var crown = Vec3.atCenterOf(glass).add(0, 1.35, 0);
        var from = Vec3.atCenterOf(finisher).add(0, .7, 0);
        for (int i = 0; i <= 12; i++) {
            double t = i / 12.0;
            GalleryTrail.spark(p, ParticleTypes.ELECTRIC_SPARK, from.lerp(crown, t).add(0, .6 * Math.sin(Math.PI * t), 0));
        }
        if (phase == Phase.AT_OBSERVER) return;
        if (phase == Phase.AT_LEAD) {
            var tip = Vec3.atCenterOf(observer).add(0, .7, 0);
            GalleryTrail.drawLink(p, crown, tip, 1, .45);
            GalleryTrail.spark(p, ParticleTypes.GLOW, tip);
            return;
        }
        GalleryTrail.spark(p, ParticleTypes.GLOW, Vec3.atCenterOf(lead).add(0, .7, 0));
        draw(p, finisher);
        double yaw = Math.toRadians(p.getYRot());
        var across = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        for (int i = 0; i < 16; i++) {
            double a = Math.PI * i / 8;
            double radius = i % 4 == 0 ? .42 : .13;
            GalleryTrail.spark(p, ParticleTypes.END_ROD, crown.add(across.scale(radius * Math.cos(a))).add(0, radius * Math.sin(a), 0));
        }
    }
}

package dev.convergence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

record BeaconRelay(BlockPos glass, BlockPos lead, boolean complete) {
    BeaconRelay(BlockPos glass) { this(glass.immutable(), null, false); }

    String rejection(MidpointBeacon span, BlockPos tap) {
        if (complete) return "Infinity ribbon complete.";
        boolean atFirst = tap.equals(span.first());
        if (!atFirst && !tap.equals(span.second())) return "Choose one of the two span endpoints.";
        if (tap.equals(lead)) return "That lantern already answered. Visit the other endpoint.";
        BlockPos other = atFirst ? span.second() : span.first();
        if (lead == null && glass.distSqr(tap) > glass.distSqr(other))
            return "Start at the endpoint nearer the lit glass. Count its height, too.";
        return null;
    }

    BeaconRelay advance(BlockPos tap) {
        return lead == null ? new BeaconRelay(glass, tap.immutable(), false) : new BeaconRelay(glass, lead, true);
    }

    void draw(ServerPlayer p, BlockPos tap) {
        var crown = Vec3.atCenterOf(glass).add(0, 1.35, 0);
        var tip = Vec3.atCenterOf(tap).add(0, .7, 0);
        GalleryTrail.drawLink(p, crown, tip, 1, .35);
        GalleryTrail.spark(p, ParticleTypes.GLOW, tip);
        if (!complete) return;
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
}

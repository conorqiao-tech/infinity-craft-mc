package dev.convergence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

record MidpointBeacon(BlockPos first, BlockPos second) {
    // Twice the signed error avoids rounding a half block, including at negative coordinates.
    String adjustment(BlockPos glass) {
        long dx = 2L * glass.getX() - first.getX() - second.getX();
        long dz = 2L * glass.getZ() - first.getZ() - second.getZ();
        long dy = 2L * (glass.getY() - 1L) - first.getY() - second.getY();
        if (Math.abs(dx) > 1) return dx > 0 ? "Try glass farther west." : "Try glass farther east.";
        if (Math.abs(dz) > 1) return dz > 0 ? "Try glass farther north." : "Try glass farther south.";
        if (Math.abs(dy) > 1) return dy > 0 ? "Try lower glass." : "Try higher glass.";
        return null;
    }

    void draw(ServerPlayer p, BlockPos glass) {
        var center = Vec3.atCenterOf(glass);
        var crown = center.add(0, .95, 0);
        GalleryTrail.drawLink(p, Vec3.atCenterOf(first).add(0, .55, 0), center, 1, 0);
        GalleryTrail.drawLink(p, Vec3.atCenterOf(second).add(0, .55, 0), center, 1, 0);
        for (int i = 0; i <= 5; i++)
            GalleryTrail.spark(p, ParticleTypes.WAX_OFF, center.lerp(crown, i / 5.0));
        for (int i = 0; i < 12; i++) {
            double angle = Math.PI * i / 6;
            GalleryTrail.spark(p, ParticleTypes.END_ROD, crown.add(.36 * Math.cos(angle), 0, .36 * Math.sin(angle)));
        }
        GalleryTrail.spark(p, ParticleTypes.GLOW, crown);
    }
}

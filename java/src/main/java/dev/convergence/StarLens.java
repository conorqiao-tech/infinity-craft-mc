package dev.convergence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class StarLens {
    private StarLens() {}

    static void project(ServerPlayer player, BlockPos lens, StarLoom.Chart chart) {
        var from = Vec3.atCenterOf(lens).add(0, .5, 0);
        var center = from.add(0, 1.5, 0);
        double yaw = Math.toRadians(player.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var ink = new DustParticleOptions(chart.ink, .85f);
        for (int i = 0; i < 6; i++) GalleryTrail.spark(player, ink, from.add(0, i * .12, 0));
        for (int cell = 0; cell < 9; cell++) {
            var point = center.add(right.scale((cell % 3 - 1) * .46)).add(0, (1 - cell / 3) * .46, 0);
            if (StarLoom.lit(chart.goal, cell)) {
                GalleryTrail.spark(player, ParticleTypes.GLOW, point);
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.add(0, .10, 0));
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.add(0, -.10, 0));
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.add(right.scale(.10)));
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.subtract(right.scale(.10)));
            } else {
                GalleryTrail.spark(player, ParticleTypes.WAX_OFF, point);
            }
        }
        for (int i = -3; i <= 3; i++) {
            for (int sign : new int[]{-1, 1}) {
                GalleryTrail.spark(player, ink, center.add(right.scale(i * .23)).add(0, sign * .69, 0));
                if (Math.abs(i) < 3)
                    GalleryTrail.spark(player, ink, center.add(right.scale(sign * .69)).add(0, i * .23, 0));
            }
        }
    }
}

package dev.convergence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class StarLens {
    private StarLens() {}

    static int starsInColumn(StarLoom.Chart chart, int column) {
        int count = 0;
        for (int cell = column; cell < 9; cell += 3)
            if (StarLoom.lit(chart.goal, cell)) count++;
        return count;
    }

    static boolean densest(StarLoom.Chart chart, int column) {
        int count = starsInColumn(chart, column);
        for (int other = 0; other < 3; other++) if (starsInColumn(chart, other) > count) return false;
        return true;
    }

    static void project(ServerPlayer player, BlockPos lens, StarLoom.Chart chart, int traceColumn) {
        var from = Vec3.atCenterOf(lens).add(0, .5, 0);
        var center = from.add(0, 1.5, 0);
        double yaw = Math.toRadians(player.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var ink = new DustParticleOptions(chart.ink, .85f);
        for (int i = 0; i < 6; i++) GalleryTrail.spark(player, ink, from.add(0, i * .12, 0));
        board(player, center, right, new Vec3(0, 1, 0), chart, traceColumn);
    }

    static void board(ServerPlayer player, Vec3 center, Vec3 right, Vec3 up, StarLoom.Chart chart, int traceColumn) {
        var ink = new DustParticleOptions(chart.ink, .85f);
        for (int cell = 0; cell < 9; cell++) {
            var point = center.add(right.scale((cell % 3 - 1) * .46)).add(up.scale((1 - cell / 3) * .46));
            if (StarLoom.lit(chart.goal, cell)) {
                GalleryTrail.spark(player, ParticleTypes.GLOW, point);
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.add(up.scale(.10)));
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.add(up.scale(-.10)));
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.add(right.scale(.10)));
                GalleryTrail.spark(player, ParticleTypes.END_ROD, point.subtract(right.scale(.10)));
            } else {
                GalleryTrail.spark(player, ParticleTypes.WAX_OFF, point);
            }
        }
        if (traceColumn >= 0) {
            var base = center.add(right.scale((traceColumn - 1) * .46)).add(up.scale(-.83));
            for (int dot = -2; dot <= 2; dot++) {
                var mark = base.add(right.scale(dot * .08));
                GalleryTrail.spark(player, ParticleTypes.ELECTRIC_SPARK, mark);
                GalleryTrail.spark(player, ParticleTypes.ELECTRIC_SPARK, mark.add(up.scale(-.12)));
            }
        }
        for (int i = -3; i <= 3; i++) {
            for (int sign : new int[]{-1, 1}) {
                GalleryTrail.spark(player, ink, center.add(right.scale(i * .23)).add(up.scale(sign * .69)));
                if (Math.abs(i) < 3)
                    GalleryTrail.spark(player, ink, center.add(right.scale(sign * .69)).add(up.scale(i * .23)));
            }
        }
    }
}

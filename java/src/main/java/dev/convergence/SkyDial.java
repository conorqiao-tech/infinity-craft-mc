package dev.convergence;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;

final class SkyDial {
    private SkyDial() {}

    static void read(ServerPlayer player, ItemFrame frame, Skyprint print) {
        if (!CodaPostcard.canListen(player)) return;
        int target = Math.floorMod((print.column() - 1) * 2, 8);
        int angle = Math.floorMod(frame.getRotation(), 8);
        int turns = Math.floorMod(target - angle, 8);
        var center = GalleryTrail.framePoint(frame);
        var right = StarPrint.frameRight(player, frame);
        var ink = new DustParticleOptions(print.chart().ink, .72f);
        for (int notch = 0; notch < 8; notch++)
            GalleryTrail.spark(player, ink, center.add(ray(right, notch).scale(.53)));
        var needle = ray(right, angle);
        for (int i = 0; i <= 5; i++)
            GalleryTrail.spark(player, ParticleTypes.END_ROD, center.add(needle.scale(.085 * i)));
        var goal = ray(right, target);
        var tangent = ray(right, target + 2);
        var tip = center.add(goal.scale(.55));
        // A fork marks the target and a four-sided flare marks a successful reading.
        for (int side : new int[]{-1, 1}) {
            for (int dot = 1; dot <= 3; dot++)
                GalleryTrail.spark(player, ParticleTypes.WAX_ON,
                    tip.add(goal.scale(.032 * dot)).add(tangent.scale(side * .032 * dot)));
        }
        String direction = switch (print.column()) { case 0 -> "left"; case 1 -> "up"; default -> "right"; };
        if (turns != 0) {
            ConcertStage.overlay(player, "Sky Dial • Aim " + direction + " • " + turns
                + (turns == 1 ? " clockwise turn" : " clockwise turns"));
            return;
        }
        for (int notch = 0; notch < 8; notch++) {
            double radius = notch % 2 == 0 ? .21 : .075;
            GalleryTrail.spark(player, ParticleTypes.ELECTRIC_SPARK, center.add(ray(right, notch).scale(radius)));
        }
        InfinityChimes.stopPlayback(player);
        EncoreDuet.leave(player);
        CircuitConductor.soundVoice(player, print.voice(), print.pitch());
        ConcertStage.overlay(player, "Sky Dial • Aligned " + direction + " • " + print.caption());
    }

    private static Vec3 ray(Vec3 right, int notch) {
        double angle = notch * Math.PI / 4;
        return right.scale(Math.sin(angle)).add(0, Math.cos(angle), 0);
    }
}

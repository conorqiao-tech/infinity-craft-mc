package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

record LanternWalk(InlayPrism prism, boolean reverse, ResourceKey<Level> dimension, List<BlockPos> stops) {
    private static final AttachmentType<LanternWalk> WALK =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "lantern_walk"));
    private static final int RANGE = 12;

    static void open(ServerPlayer p, InlayPrism prism, boolean reverse) {
        p.setAttached(WALK, new LanternWalk(prism, reverse, p.level().dimension(), List.of()));
    }

    static void clear(ServerPlayer p) { p.removeAttached(WALK); }

    static void visit(ServerPlayer p, InlayPrism held, BlockPos pos) {
        var walk = p.getAttached(WALK);
        if (walk == null || !walk.prism.equals(held)) {
            ConcertStage.overlay(p, "Lantern Walk • Finish a Prism Crossing with this prism first.");
            return;
        }
        if (walk.stops.size() == 3) {
            ConcertStage.overlay(p, "Lantern Walk • Constellation complete • Start a new crossing to walk again.");
            return;
        }
        if (!walk.stops.isEmpty() && !walk.dimension.equals(p.level().dimension())) {
            ConcertStage.overlay(p, "Lantern Walk • Return to the dimension of your first lantern.");
            return;
        }
        if (walk.stops.contains(pos)) {
            ConcertStage.overlay(p, "Lantern Walk • Already visited • Choose a different lantern.");
            return;
        }
        if (walk.stops.stream().anyMatch(stop -> stop.distSqr(pos) > RANGE * RANGE)) {
            ConcertStage.overlay(p, "Lantern Walk • Too far • Stay within 12 blocks of every earlier lantern.");
            return;
        }
        var route = new ArrayList<>(walk.stops);
        route.add(pos.immutable());
        var next = new LanternWalk(held, walk.reverse, p.level().dimension(), List.copyOf(route));
        p.setAttached(WALK, next);
        var score = held.score();
        int voice = next.voiceAt(route.size() - 1);
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        CircuitConductor.soundVoice(p, voice, score.voices().get(voice));
        next.draw(p);
        ConcertStage.overlay(p, "Lantern Walk • " + route.size() + "/3 • " + score.voiceName(voice)
            + (route.size() == 3 ? " • Constellation complete" : " • Next: " + score.voiceName(next.voiceAt(route.size()))));
    }

    private int voiceAt(int stop) { return prism.score().cornerAt(reverse ? 2 - stop : stop); }

    private void draw(ServerPlayer p) {
        var points = stops.stream().map(pos -> Vec3.atCenterOf(pos).add(0, .55, 0)).toList();
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            var ink = prism.score().voices().get(voiceAt(i)).ink;
            GalleryTrail.spark(p, new DustParticleOptions(ink, 1.2f), point);
            for (int mark = 0; mark <= i; mark++)
                GalleryTrail.spark(p, ParticleTypes.WAX_OFF, point.add(0, .18 * (mark + 1), 0));
            if (i > 0) GalleryTrail.drawLink(p, points.get(i - 1), point, 1, 0);
        }
        if (points.size() == 3) {
            GalleryTrail.drawLink(p, points.getLast(), points.getFirst(), 1, .65);
            var center = points.get(0).add(points.get(1)).add(points.get(2)).scale(1.0 / 3);
            GalleryTrail.spark(p, ParticleTypes.GLOW, center);
            for (int ray = 0; ray < 8; ray++) {
                double angle = ray * Math.PI / 4;
                GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(.3 * Math.cos(angle), 0, .3 * Math.sin(angle)));
            }
        }
    }
}

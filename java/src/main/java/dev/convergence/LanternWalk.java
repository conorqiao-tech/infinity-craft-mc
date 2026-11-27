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

record LanternWalk(InlayPrism prism, boolean reverse, ResourceKey<Level> dimension, List<BlockPos> stops, int surveyedFrom) {
    private static final AttachmentType<LanternWalk> WALK =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "lantern_walk"));
    private static final int RANGE = 12;

    static void open(ServerPlayer p, InlayPrism prism, boolean reverse) {
        p.setAttached(WALK, new LanternWalk(prism, reverse, p.level().dimension(), List.of(), -1));
    }

    static void clear(ServerPlayer p) { p.removeAttached(WALK); }

    static void visit(ServerPlayer p, InlayPrism held, BlockPos pos) {
        var walk = p.getAttached(WALK);
        if (walk == null || !walk.prism.equals(held)) {
            ConcertStage.overlay(p, "Lantern Walk • Finish a Prism Crossing with this prism first.");
            return;
        }
        if (!walk.stops.isEmpty() && !walk.dimension.equals(p.level().dimension())) {
            ConcertStage.overlay(p, "Lantern Walk • Return to the dimension of your first lantern.");
            return;
        }
        if (walk.stops.size() == 3) {
            if (p.isShiftKeyDown()) walk.survey(p, pos);
            else ConcertStage.overlay(p, "Lantern Walk • Constellation complete • Sneak-use a stop to survey its opposite span.");
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
        var next = new LanternWalk(held, walk.reverse, p.level().dimension(), List.copyOf(route), -1);
        p.setAttached(WALK, next);
        var score = held.score();
        int voice = next.voiceAt(route.size() - 1);
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        CircuitConductor.soundVoice(p, voice, score.voices().get(voice));
        next.draw(p);
        ConcertStage.overlay(p, "Lantern Walk • " + route.size() + "/3 • " + score.voiceName(voice)
            + (route.size() == 3 ? " • Constellation complete • Sneak-use opposite a longest span" : " • Next: " + score.voiceName(next.voiceAt(route.size()))));
    }

    private int voiceAt(int stop) { return prism.score().cornerAt(reverse ? 2 - stop : stop); }

    // Endpoints are in visit order, including when stop 2 is the viewpoint.
    private record Span(int from, int to) {
        double squaredLength(List<BlockPos> stops) { return stops.get(from).distSqr(stops.get(to)); }
    }

    private static final List<Span> OPPOSITE = List.of(new Span(1, 2), new Span(0, 2), new Span(0, 1));

    private void survey(ServerPlayer p, BlockPos pos) {
        int observer = stops.indexOf(pos);
        if (observer < 0) {
            ConcertStage.overlay(p, "Star Survey • Choose one of the three lanterns you visited.");
            return;
        }
        if (surveyedFrom >= 0) {
            ConcertStage.overlay(p, "Star Survey • Already charted from stop " + (surveyedFrom + 1));
            return;
        }
        var span = OPPOSITE.get(observer);
        double lengthSquared = span.squaredLength(stops);
        String ends = "stops " + (span.from + 1) + " and " + (span.to + 1);
        if (OPPOSITE.stream().anyMatch(edge -> edge.squaredLength(stops) > lengthSquared)) {
            ConcertStage.overlay(p, "Star Survey • " + ends + " make a shorter span • Try another viewpoint.");
            return;
        }
        p.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, observer));
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        var score = prism.score();
        int first = voiceAt(span.from), second = voiceAt(span.to);
        CircuitConductor.soundVoice(p, first, score.voices().get(first));
        CircuitConductor.soundVoice(p, second, score.voices().get(second));
        drawSurvey(p, pos, stops.get(span.from), stops.get(span.to));
        ConcertStage.overlay(p, "Star Survey • From stop " + (observer + 1) + " to " + ends + " • "
            + score.voiceName(first) + " + " + score.voiceName(second));
    }

    private static void drawSurvey(ServerPlayer p, BlockPos observer, BlockPos first, BlockPos second) {
        var rose = Vec3.atCenterOf(observer).add(0, 1.2, 0);
        var from = Vec3.atCenterOf(first).add(0, .55, 0);
        var to = Vec3.atCenterOf(second).add(0, .55, 0);
        var midpoint = from.lerp(to, .5);
        GalleryTrail.drawLink(p, from, to, 1, 0);
        GalleryTrail.spark(p, ParticleTypes.GLOW, from);
        GalleryTrail.spark(p, ParticleTypes.GLOW, to);
        // The elevated rose and dotted sightline stay distinct even on a straight walk.
        for (int i = 1; i <= 8; i++) GalleryTrail.spark(p, ParticleTypes.WAX_ON, rose.lerp(midpoint, i / 8.0));
        double yaw = Math.toRadians(p.getYRot());
        var across = new Vec3(-Math.cos(yaw) * .13, 0, -Math.sin(yaw) * .13);
        for (var point : List.of(midpoint.add(0, .19, 0), midpoint.add(across), midpoint.add(0, -.19, 0), midpoint.subtract(across)))
            GalleryTrail.spark(p, ParticleTypes.ELECTRIC_SPARK, point);
        for (int ray = 0; ray < 8; ray++) {
            double angle = Math.PI * ray / 4;
            double radius = ray % 2 == 0 ? .45 : .23;
            for (int i = 1; i <= 3; i++) GalleryTrail.spark(p, ParticleTypes.WAX_OFF,
                rose.add(Math.cos(angle) * radius * i / 3, 0, Math.sin(angle) * radius * i / 3));
        }
    }

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

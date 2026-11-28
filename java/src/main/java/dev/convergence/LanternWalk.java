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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

record LanternWalk(InlayPrism prism, boolean reverse, ResourceKey<Level> dimension, List<BlockPos> stops,
                   int surveyedFrom, BeaconRelay relay) {
    private static final AttachmentType<LanternWalk> WALK =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "lantern_walk"));
    private static final int RANGE = 12;

    static void open(ServerPlayer p, InlayPrism prism, boolean reverse) {
        p.setAttached(WALK, new LanternWalk(prism, reverse, p.level().dimension(), List.of(), -1, null));
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
            else if (walk.relay != null) walk.relayLantern(p, pos);
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
        var next = new LanternWalk(held, walk.reverse, p.level().dimension(), List.copyOf(route), -1, null);
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
        p.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, observer, null));
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        var score = prism.score();
        int first = voiceAt(span.from), second = voiceAt(span.to);
        CircuitConductor.soundVoice(p, first, score.voices().get(first));
        CircuitConductor.soundVoice(p, second, score.voices().get(second));
        drawSurvey(p, pos, stops.get(span.from), stops.get(span.to));
        ConcertStage.overlay(p, "Star Survey • From stop " + (observer + 1) + " to " + ends + " • "
            + score.voiceName(first) + " + " + score.voiceName(second) + " • Aim glass one block above the midpoint");
    }

    static LanternWalk surveyed(ServerPlayer p, InlayPrism held) {
        var walk = p.getAttached(WALK);
        return walk != null && walk.surveyedFrom >= 0 && walk.prism.equals(held)
            && walk.dimension.equals(p.level().dimension()) ? walk : null;
    }

    void lightBeacon(ServerPlayer p, BlockPos glass) {
        if (relay != null) {
            if (relay.complete() && p.isShiftKeyDown() && relay.glass().equals(glass)) {
                returnBeat(p, glass, true);
                return;
            }
            ConcertStage.overlay(p, "Midpoint Beacon • Already lit at " + relay.glass().toShortString());
            return;
        }
        var span = OPPOSITE.get(surveyedFrom);
        var target = new MidpointBeacon(stops.get(span.from), stops.get(span.to));
        String adjustment = target.adjustment(glass);
        if (adjustment != null) {
            ConcertStage.overlay(p, "Midpoint Beacon • " + adjustment);
            return;
        }
        p.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, surveyedFrom, new BeaconRelay(glass)));
        int observerVoice = voiceAt(surveyedFrom);
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        CircuitConductor.soundVoice(p, observerVoice, prism.score().voices().get(observerVoice));
        target.draw(p, glass);
        ConcertStage.overlay(p, "Midpoint Beacon • " + glass.toShortString() + " • " + prism.score().voiceName(observerVoice)
            + " solo • Use the nearer span lantern, then the other");
    }

    static LanternWalk lensAt(ServerPlayer player, InlayPrism prism, BlockPos pos) {
        var walk = surveyed(player, prism);
        if (walk == null || walk.relay == null) return null;
        var relay = walk.relay;
        if (relay.phase() != BeaconRelay.Phase.FINISHED && relay.phase() != BeaconRelay.Phase.PROJECTED) return null;
        return pos.below().equals(relay.glass()) && player.level().getBlockState(relay.glass()).is(Blocks.GLASS) ? walk : null;
    }

    void useLens(ServerPlayer player, BlockPos pos, Block tint) {
        int voice = voiceAt(surveyedFrom);
        var chart = prism.score().voices().get(voice);
        if (tint != Blocks.STAINED_GLASS.pick(CadencePennant.ink(chart))) {
            ConcertStage.overlay(player, "Star Lens • Match the chart of the observer who sat out the closing duet.");
            return;
        }
        boolean first = relay.phase() == BeaconRelay.Phase.FINISHED;
        if (first) {
            player.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, surveyedFrom, relay.at(BeaconRelay.Phase.PROJECTED)));
            InfinityChimes.stopPlayback(player);
            EncoreDuet.leave(player);
            CircuitConductor.soundVoice(player, voice, chart);
        }
        StarLens.project(player, pos, chart, relay.traceColumn());
        ConcertStage.overlay(player, "Star Lens • " + prism.score().voiceName(voice)
            + (first ? " • Find a column with the most stars • Tap its lantern in walk order" : " • Chart projected again"));
    }

    private void traceColumn(ServerPlayer player, BlockPos lantern) {
        int column = stops.indexOf(lantern);
        if (column < 0) {
            ConcertStage.overlay(player, "Star Trace • Use one of your three walk lanterns, left to right in visit order.");
            return;
        }
        var chart = prism.score().voices().get(voiceAt(surveyedFrom));
        var lens = relay.glass().above();
        if (!player.level().getBlockState(relay.glass()).is(Blocks.GLASS)
                || !player.level().getBlockState(lens).is(Blocks.STAINED_GLASS.pick(CadencePennant.ink(chart)))) {
            ConcertStage.overlay(player, "Star Trace • Restore your matching lens on the original clear glass.");
            return;
        }
        boolean first = relay.traceColumn() < 0;
        if (!first && column != relay.traceColumn()) {
            ConcertStage.overlay(player, "Star Trace • Your mark is on column " + (relay.traceColumn() + 1));
            return;
        }
        int count = StarLens.starsInColumn(chart, column);
        if (first) {
            for (int other = 0; other < 3; other++) {
                if (StarLens.starsInColumn(chart, other) > count) {
                    ConcertStage.overlay(player, "Star Trace • Another column has more bright stars. Count from the left.");
                    return;
                }
            }
            player.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, surveyedFrom, relay.trace(column)));
            int voice = voiceAt(column);
            InfinityChimes.stopPlayback(player);
            EncoreDuet.leave(player);
            CircuitConductor.soundVoice(player, voice, prism.score().voices().get(voice));
        }
        StarLens.project(player, lens, chart, column);
        GalleryTrail.drawLink(player, Vec3.atCenterOf(lantern).add(0, .6, 0), Vec3.atCenterOf(lens), 1, .35);
        ConcertStage.overlay(player, "Star Trace • Column " + (column + 1) + " • " + count + " stars • "
            + prism.score().voiceName(voiceAt(column)) + (first ? " solo" : " • Silent redraw"));
    }

    private void relayLantern(ServerPlayer p, BlockPos pos) {
        if (relay.phase() == BeaconRelay.Phase.PROJECTED) {
            traceColumn(p, pos);
            return;
        }
        if (relay.complete()) {
            returnBeat(p, pos, false);
            return;
        }
        var span = OPPOSITE.get(surveyedFrom);
        String clue = relay.rejection(new MidpointBeacon(stops.get(span.from), stops.get(span.to)), pos);
        if (clue != null) {
            ConcertStage.overlay(p, "Beacon Relay • " + clue);
            return;
        }
        var next = relay.advance(pos);
        p.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, surveyedFrom, next));
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        var score = prism.score();
        if (next.complete()) {
            score.soundBeat(p, 3);
            ConcertStage.overlay(p, "Beacon Relay • Full chord • Sneak-use your lit glass to start Ribbon Return");
        } else {
            int voice = voiceAt(stops.indexOf(pos));
            CircuitConductor.soundVoice(p, voice, score.voices().get(voice));
            ConcertStage.overlay(p, "Beacon Relay • " + score.voiceName(voice) + " solo • Visit the other endpoint");
        }
        next.draw(p, pos);
    }

    private void returnBeat(ServerPlayer p, BlockPos tap, boolean onGlass) {
        var observer = stops.get(surveyedFrom);
        var span = OPPOSITE.get(surveyedFrom);
        int lead = stops.indexOf(relay.lead());
        int finish = lead == span.from ? span.to : span.from;
        var score = prism.score();
        var next = switch (relay.phase()) {
            case AT_GLASS -> onGlass ? BeaconRelay.Phase.AT_OBSERVER : null;
            case AT_OBSERVER -> !onGlass && observer.equals(tap) ? BeaconRelay.Phase.AT_LEAD : null;
            case AT_LEAD -> !onGlass && relay.lead().equals(tap) ? BeaconRelay.Phase.FINISHED : null;
            default -> null;
        };
        if (next == null) {
            String clue = switch (relay.phase()) {
                case AT_GLASS -> "Sneak-use the glass you lit.";
                case AT_OBSERVER -> "Visit your survey observer.";
                case AT_LEAD -> "Visit the lantern that led your relay.";
                default -> "Center star complete.";
            };
            ConcertStage.overlay(p, "Ribbon Return • " + clue);
            return;
        }
        var advanced = relay.at(next);
        p.setAttached(WALK, new LanternWalk(prism, reverse, dimension, stops, surveyedFrom, advanced));
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        if (next == BeaconRelay.Phase.FINISHED) {
            for (int i : List.of(lead, finish)) {
                int voice = voiceAt(i);
                CircuitConductor.soundVoice(p, voice, score.voices().get(voice));
            }
            ConcertStage.overlay(p, "Ribbon Return • 3/3 • " + score.voiceName(voiceAt(lead)) + " + "
                + score.voiceName(voiceAt(finish)) + " • Center star complete • Place a stained-glass lens on your clear glass");
        } else {
            boolean opening = next == BeaconRelay.Phase.AT_OBSERVER;
            int voice = voiceAt(opening ? finish : surveyedFrom);
            CircuitConductor.soundVoice(p, voice, score.voices().get(voice));
            ConcertStage.overlay(p, "Ribbon Return • " + (opening ? "1/3" : "2/3") + " • " + score.voiceName(voice)
                + (opening ? " • Visit your survey observer" : " • Return to your lead: " + score.voiceName(voiceAt(lead))));
        }
        advanced.drawReturn(p, stops.get(finish), observer);
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

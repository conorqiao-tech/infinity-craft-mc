package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;

final class GalleryTrail {
    private static final int GOAL = 3;
    private static final int REACH = 12;
    // A visit belongs to this player session. There is no saved route or exhibit registry.
    private static final AttachmentType<List<Stop>> ROUTE =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "gallery_trail"));

    record Stop(UUID frameId, Vec3 point, Direction face, StarPrint print) {
        Vec3 right() {
            return face.getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0).cross(face.getUnitVec3());
        }
        boolean stillDisplayed(ServerPlayer p) {
            return p.level().getEntity(frameId) instanceof ItemFrame frame
                && framePoint(frame).equals(point) && print.equals(StarPrint.read(frame.getItem()));
        }
    }

    private GalleryTrail() {}

    static void register() {
        EncoreDuet.register();
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> p.removeAttached(ROUTE));
    }

    static Vec3 framePoint(ItemFrame frame) {
        return frame.getBoundingBox().getCenter().add(frame.getDirection().getUnitVec3().scale(.12));
    }

    static void visit(ServerPlayer p, ItemFrame frame, StarPrint print) {
        var route = route(p);
        if (route.size() == GOAL) route = List.of();
        var next = new Stop(frame.getUUID(), framePoint(frame), frame.getDirection(), print);
        boolean changed = !p.isShiftKeyDown() && route.stream().anyMatch(stop -> !stop.stillDisplayed(p));
        if (p.isShiftKeyDown() || changed) route = List.of();
        if (!route.isEmpty()) {
            var last = route.getLast();
            var needed = last.print.notes().getLast();
            String hint = null;
            if (route.stream().anyMatch(stop -> stop.frameId.equals(next.frameId)))
                hint = "Already visited. Find another print opening on " + needed.label + ".";
            else if (last.point.distanceToSqr(next.point) > REACH * REACH)
                hint = "Too far. Find a print within " + REACH + " blocks of the last frame.";
            else if (print.notes().getFirst() != needed)
                hint = "Opens on " + print.notes().getFirst().label + ". Find one opening on " + needed.label + ".";
            if (hint != null) {
                ConcertStage.overlay(p, "Trail " + route.size() + "/" + GOAL + " • " + hint);
                return;
            }
        }

        var stops = new ArrayList<>(route);
        stops.add(next);
        boolean complete = stops.size() == GOAL;
        p.setAttached(ROUTE, List.copyOf(stops));
        String progress = complete ? "Gallery Trail complete • 3/3" : "Trail " + stops.size() + "/" + GOAL
            + " • Find a print opening on " + print.notes().getLast().label;
        ConcertStage.overlay(p, changed ? "Display changed • " + progress : progress);
        var notes = print.notes();
        InfinityChimes.play(p, notes.stream().map(note -> note.note).toList(), beat -> {
            if (complete && beat == notes.size() - 1) {
                for (int i = 0; i < stops.size(); i++) {
                    var here = stops.get(i);
                    drawLink(p, here.point, stops.get((i + 1) % stops.size()).point, 1, 0);
                    halo(p, here);
                }
                ConcertStage.overlay(p, "Last frame • Feather for solo (Sneak reverses) • Each use a plain stick for a duet.");
            } else {
                if (stops.size() > 1) drawLink(p, stops.get(stops.size() - 2).point, next.point, (beat + 1.0) / notes.size(), 0);
                spark(p, ParticleTypes.GLOW, next.point);
            }
        });
    }

    static List<Stop> route(ServerPlayer p) {
        return p.getAttachedOrElse(ROUTE, List.of());
    }

    static boolean encoreReady(ServerPlayer p) {
        return route(p).size() == GOAL;
    }

    record Beat(StarLoom.Chart note, int from, int to) {}

    static void conduct(ServerPlayer p, ItemFrame frame) {
        var route = route(p);
        if (route.size() != GOAL) return;
        if (!route.getLast().frameId.equals(frame.getUUID())) {
            ConcertStage.overlay(p, "Use the feather on the last frame of your trail.");
            return;
        }
        boolean reverse = p.isShiftKeyDown();
        var beats = medley(route, reverse);
        InfinityChimes.play(p, beats.stream().map(beat -> beat.note.note).toList(), () -> {
            if (p.getAttached(ROUTE) != route) return false;
            if (route.stream().anyMatch(stop -> !stop.stillDisplayed(p))) {
                p.removeAttached(ROUTE);
                ConcertStage.overlay(p, "Encore ended • The display changed. Solve a new trail.");
                return false;
            }
            return true;
        }, i -> {
            var beat = beats.get(i);
            showBeat(p, route, beat);
            String frames = "Frame " + (beat.from + 1);
            if (beat.from != beat.to) frames += " to " + (beat.to + 1);
            ConcertStage.overlay(p, (reverse ? "Reverse encore" : "Encore") + " • " + frames + " • " + beat.note.label);
        });
    }

    static List<Beat> medley(List<Stop> route, boolean reverse) {
        var beats = new ArrayList<Beat>();
        for (int part = 0; part < route.size(); part++) {
            int from = reverse ? route.size() - 1 - part : part;
            var notes = route.get(from).print.notes();
            if (reverse) notes = notes.reversed();
            // The outgoing frame already sounds the next frame's opening note.
            for (int i = part == 0 ? 0 : 1; i < notes.size(); i++) {
                boolean handoff = i == notes.size() - 1 && part < route.size() - 1;
                int to = handoff ? from + (reverse ? -1 : 1) : from;
                beats.add(new Beat(notes.get(i), from, to));
            }
        }
        return List.copyOf(beats);
    }

    static void showBeat(ServerPlayer p, List<Stop> route, Beat beat) {
        var from = route.get(beat.from);
        var to = route.get(beat.to);
        var ink = new DustParticleOptions(beat.note.ink, 1f);
        spark(p, ink, from.point);
        if (beat.from != beat.to) {
            drawLink(p, from.point, to.point, 1, .45);
            spark(p, ParticleTypes.GLOW, from.point);
            spark(p, ParticleTypes.GLOW, to.point);
            spark(p, ink, to.point);
        }
    }

    static void halo(ServerPlayer p, Stop stop) {
        var normal = stop.face.getUnitVec3();
        var right = stop.right();
        var up = normal.cross(right);
        for (int k = 0; k < 12; k++) {
            double angle = 2 * Math.PI * k / 12;
            spark(p, ParticleTypes.WAX_ON, stop.point.add(right.scale(.32 * Math.cos(angle))).add(up.scale(.32 * Math.sin(angle))));
        }
    }

    private static void drawLink(ServerPlayer p, Vec3 from, Vec3 to, double fraction, double arcHeight) {
        int steps = Math.max(2, (int)Math.ceil(from.distanceTo(to) * 2));
        for (int i = 0; i <= Math.ceil(steps * fraction); i++) {
            double t = (double)i / steps;
            spark(p, ParticleTypes.END_ROD, from.lerp(to, t).add(0, 4 * arcHeight * t * (1 - t), 0));
        }
    }

    static void spark(ServerPlayer p, ParticleOptions particle, Vec3 point) {
        p.level().sendParticles(p, particle, false, false, point.x, point.y, point.z, 1, 0, 0, 0, 0);
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.core.Direction;
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

    private record Stop(UUID frameId, Vec3 point, Direction face, StarPrint print) {
        boolean stillDisplayed(ServerPlayer p) {
            return p.level().getEntity(frameId) instanceof ItemFrame frame
                && framePoint(frame).equals(point) && print.equals(StarPrint.read(frame.getItem()));
        }
    }

    private GalleryTrail() {}

    static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> p.removeAttached(ROUTE));
    }

    static Vec3 framePoint(ItemFrame frame) {
        return frame.getBoundingBox().getCenter().add(frame.getDirection().getUnitVec3().scale(.12));
    }

    static void visit(ServerPlayer p, ItemFrame frame, StarPrint print) {
        var route = p.getAttachedOrElse(ROUTE, List.of());
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
        if (complete) p.removeAttached(ROUTE);
        else p.setAttached(ROUTE, List.copyOf(stops));
        String progress = complete ? "Gallery Trail complete • 3/3" : "Trail " + stops.size() + "/" + GOAL
            + " • Find a print opening on " + print.notes().getLast().label;
        ConcertStage.overlay(p, changed ? "Display changed • " + progress : progress);
        var notes = print.notes();
        InfinityChimes.play(p, notes.stream().map(note -> note.note).toList(), beat -> {
            if (complete && beat == notes.size() - 1) {
                for (int i = 0; i < stops.size(); i++) {
                    var here = stops.get(i);
                    drawLink(p, here.point, stops.get((i + 1) % stops.size()).point, 1);
                    halo(p, here);
                }
            } else {
                if (stops.size() > 1) drawLink(p, stops.get(stops.size() - 2).point, next.point, (beat + 1.0) / notes.size());
                spark(p, ParticleTypes.GLOW, next.point);
            }
        });
    }

    private static void halo(ServerPlayer p, Stop stop) {
        var normal = stop.face.getUnitVec3();
        var right = stop.face.getAxis() == Direction.Axis.Y ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0).cross(normal);
        var up = normal.cross(right);
        for (int k = 0; k < 12; k++) {
            double angle = 2 * Math.PI * k / 12;
            spark(p, ParticleTypes.WAX_ON, stop.point.add(right.scale(.32 * Math.cos(angle))).add(up.scale(.32 * Math.sin(angle))));
        }
    }

    private static void drawLink(ServerPlayer p, Vec3 from, Vec3 to, double fraction) {
        int steps = Math.max(2, (int)Math.ceil(from.distanceTo(to) * 2));
        for (int i = 0; i <= Math.ceil(steps * fraction); i++)
            spark(p, ParticleTypes.END_ROD, from.lerp(to, (double)i / steps));
    }

    private static void spark(ServerPlayer p, ParticleOptions particle, Vec3 point) {
        p.level().sendParticles(p, particle, false, false, point.x, point.y, point.z, 1, 0, 0, 0, 0);
    }
}

package dev.convergence;

import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.Vec3;

final class PostcardPairs {
    private static final AttachmentType<Selection> FIRST =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "postcard_pair"));
    private record Link(Selection first, Selection second) {}
    private static final AttachmentType<Link> LINK =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "postcard_circuit_link"));
    private static final int MAX_DISTANCE = 12;

    record Selection(UUID frameId, Vec3 point, CodaPostcard card, boolean swap) {
        StarLoom.Chart chime() { return swap ? card.bell() : card.chime(); }
        StarLoom.Chart bell() { return swap ? card.chime() : card.bell(); }

        boolean stillDisplayed(ServerPlayer p) {
            return p.level().getEntity(frameId) instanceof ItemFrame frame && !frame.isRemoved()
                && point.equals(GalleryTrail.framePoint(frame)) && card.equals(CodaPostcard.read(frame.getItem()));
        }
    }

    static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
            p.removeAttached(FIRST);
            p.removeAttached(LINK);
            CircuitConductor.forget(p);
        });
    }

    static void tap(ServerPlayer p, ItemFrame frame, CodaPostcard card) {
        var next = new Selection(frame.getUUID(), GalleryTrail.framePoint(frame), card, p.isShiftKeyDown());
        var first = p.getAttached(FIRST);
        if (first == null || !first.stillDisplayed(p)) {
            String prefix = first == null ? "First postcard" : "Display changed • New first postcard";
            p.removeAttached(LINK);
            p.setAttached(FIRST, next);
            card.playFrame(p, frame);
            ConcertStage.overlay(p, prefix + " • Find a chime on " + next.bell().label + " • Tap here to clear");
            return;
        }
        if (first.frameId.equals(next.frameId)) {
            p.removeAttached(FIRST);
            ConcertStage.overlay(p, "Pair cleared • Tap a postcard to begin.");
            return;
        }
        if (first.point.distanceToSqr(next.point) > MAX_DISTANCE * MAX_DISTANCE) {
            ConcertStage.overlay(p, "Too far • Choose a postcard within 12 blocks of the first.");
            return;
        }
        if (first.bell() != next.chime()) {
            ConcertStage.overlay(p, "Find a chime on " + first.bell().label + " • Sneak to swap this card's voices.");
            return;
        }
        p.removeAttached(FIRST);
        p.setAttached(LINK, new Link(first, next));
        EncoreDuet.leave(p);
        InfinityChimes.stopPlayback(p);
        InfinityChimes.sound(p, first.chime().note, SoundEvents.NOTE_BLOCK_CHIME);
        InfinityChimes.sound(p, next.bell().note, SoundEvents.NOTE_BLOCK_BELL);
        threads(p, first, next);
        ConcertStage.overlay(p, "Linked at " + first.bell().label + " • Chime: " + first.chime().label + " + Bell: " + next.bell().label);
    }

    static void closeCircuit(ServerPlayer p, ItemFrame frame, CodaPostcard card) {
        var link = p.getAttached(LINK);
        if (link == null) {
            ConcertStage.overlay(p, "Link two postcards with a plain echo shard first.");
            return;
        }
        var a = link.first;
        var b = link.second;
        if (!a.stillDisplayed(p) || !b.stillDisplayed(p)) {
            p.removeAttached(LINK);
            ConcertStage.overlay(p, "Display changed • Make a fresh pair with your echo shard.");
            return;
        }
        var c = new Selection(frame.getUUID(), GalleryTrail.framePoint(frame), card, p.isShiftKeyDown());
        String target = b.bell().label + " → " + a.chime().label;
        if (c.frameId.equals(a.frameId) || c.frameId.equals(b.frameId)) {
            ConcertStage.overlay(p, "Choose a third frame • " + target);
            return;
        }
        if (c.point.distanceToSqr(a.point) > MAX_DISTANCE * MAX_DISTANCE
            || c.point.distanceToSqr(b.point) > MAX_DISTANCE * MAX_DISTANCE) {
            ConcertStage.overlay(p, "Choose a third frame within 12 blocks of both postcards.");
            return;
        }
        if (c.chime() != b.bell() || c.bell() != a.chime()) {
            ConcertStage.overlay(p, "Circuit needs " + target + " • Sneak to swap this card's voices.");
            return;
        }

        p.removeAttached(LINK);
        EncoreDuet.leave(p);
        InfinityChimes.stopPlayback(p);
        CircuitConductor.close(p, a, b, c);
        circuitEdge(p, a, b, 1);
        circuitEdge(p, b, c, 2);
        circuitEdge(p, c, a, 3);
        circuitSeal(p, a.point.add(b.point).add(c.point).scale(1.0 / 3));
        ConcertStage.overlay(p, "Circuit closed • " + a.chime().label + " → " + a.bell().label + " → " + b.bell().label + " → " + a.chime().label);
    }

    private static void circuitEdge(ServerPlayer p, Selection from, Selection to, int order) {
        var dust = new DustParticleOptions(from.bell().ink, .75f);
        int steps = Math.max(4, (int)Math.ceil(from.point.distanceTo(to.point) * 2));
        // Bow the return edge so a row or column of frames still encloses a loop.
        var bow = order == 3 ? perpendicular(to.point.subtract(from.point)).scale(.65) : Vec3.ZERO;
        for (int i = 0; i < steps; i++) {
            double t = (double)i / steps;
            GalleryTrail.spark(p, dust, from.point.lerp(to.point, t).add(bow.scale(4 * t * (1 - t))));
        }
        for (int i = 0; i < order; i++) GalleryTrail.spark(p, ParticleTypes.END_ROD, from.point.add(0, .12 + i * .12, 0));
    }

    private static void circuitSeal(ServerPlayer p, Vec3 center) {
        // Keep the hollow seal visible even when all three edges lie on one line.
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(p.getLookAngle().normalize()).normalize();
        var origin = center.add(up.scale(.55));
        for (int side = 0; side < 4; side++) {
            double angle = side * Math.PI / 2;
            var start = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
            var end = up.scale(Math.cos(angle)).subtract(right.scale(Math.sin(angle)));
            for (int i = 0; i < 3; i++) {
                GalleryTrail.spark(p, ParticleTypes.GLOW, origin.add(start.lerp(end, i / 3.0).scale(.28)));
            }
        }
    }

    private static Vec3 perpendicular(Vec3 delta) {
        var direction = delta.normalize();
        var axis = Math.abs(direction.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        return axis.subtract(direction.scale(axis.dot(direction))).normalize();
    }

    private static void threads(ServerPlayer p, Selection first, Selection next) {
        var across = perpendicular(next.point.subtract(first.point));
        var chime = new DustParticleOptions(first.chime().ink, .65f);
        var bell = new DustParticleOptions(next.bell().ink, .65f);
        int steps = 4 * Math.max(4, (int)Math.ceil(first.point.distanceTo(next.point)));
        for (int i = 0; i <= steps; i++) {
            double t = (double)i / steps;
            var center = first.point.lerp(next.point, t);
            var offset = across.scale(.32 * Math.sin(2 * Math.PI * t));
            GalleryTrail.spark(p, chime, center.add(offset));
            GalleryTrail.spark(p, bell, center.subtract(offset));
        }
        var middle = first.point.lerp(next.point, .5);
        GalleryTrail.spark(p, new DustParticleOptions(first.bell().ink, 1.25f), middle);
        GalleryTrail.spark(p, ParticleTypes.GLOW, middle);
    }

    private PostcardPairs() {}
}

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
    private static final int MAX_DISTANCE = 12;

    private record Selection(UUID frameId, Vec3 point, CodaPostcard card, boolean swap) {
        StarLoom.Chart chime() { return swap ? card.bell() : card.chime(); }
        StarLoom.Chart bell() { return swap ? card.chime() : card.bell(); }

        boolean stillDisplayed(ServerPlayer p) {
            return p.level().getEntity(frameId) instanceof ItemFrame frame && !frame.isRemoved()
                && point.equals(GalleryTrail.framePoint(frame)) && card.equals(CodaPostcard.read(frame.getItem()));
        }
    }

    static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> p.removeAttached(FIRST));
    }

    static void tap(ServerPlayer p, ItemFrame frame, CodaPostcard card) {
        var next = new Selection(frame.getUUID(), GalleryTrail.framePoint(frame), card, p.isShiftKeyDown());
        var first = p.getAttached(FIRST);
        if (first == null || !first.stillDisplayed(p)) {
            String prefix = first == null ? "First postcard" : "Display changed • New first postcard";
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
        EncoreDuet.leave(p);
        InfinityChimes.stopPlayback(p);
        InfinityChimes.sound(p, first.chime().note, SoundEvents.NOTE_BLOCK_CHIME);
        InfinityChimes.sound(p, next.bell().note, SoundEvents.NOTE_BLOCK_BELL);
        threads(p, first, next);
        ConcertStage.overlay(p, "Linked at " + first.bell().label + " • Chime: " + first.chime().label + " + Bell: " + next.bell().label);
    }

    private static void threads(ServerPlayer p, Selection first, Selection next) {
        var direction = next.point.subtract(first.point).normalize();
        var axis = Math.abs(direction.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        var across = axis.subtract(direction.scale(axis.dot(direction))).normalize();
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

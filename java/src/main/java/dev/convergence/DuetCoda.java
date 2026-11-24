package dev.convergence;

import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

final class DuetCoda {
    private record Choice(GalleryTrail.Stop stop, StarLoom.Chart note) {}

    private Choice chime;
    private Choice bell;

    void choose(ServerPlayer player, ServerPlayer partner, boolean lead, GalleryTrail.Stop stop) {
        var notes = stop.print().notes();
        var pick = new Choice(stop, player.isShiftKeyDown() ? notes.getFirst() : notes.getLast());
        if (lead) chime = pick;
        else bell = pick;

        if (chime == null || bell == null) {
            var ink = new DustParticleOptions(pick.note.ink, 1f);
            for (var listener : List.of(player, partner)) GalleryTrail.spark(listener, ink, stop.point());
            ConcertStage.overlay(player, "You chose " + pick.note.label + " • Waiting for " + partner.getGameProfile().name());
            ConcertStage.overlay(partner, player.getGameProfile().name() + " chose " + pick.note.label
                + " • Tap with glow ink for the last note, Sneak for the first.");
            return;
        }

        for (var listener : List.of(player, partner)) {
            InfinityChimes.sound(listener, chime.note.note, SoundEvents.NOTE_BLOCK_CHIME);
            InfinityChimes.sound(listener, bell.note.note, SoundEvents.NOTE_BLOCK_BELL);
            drawLoops(listener, chime, bell);
            ConcertStage.overlay(listener, "Coda • Chime: " + chime.note.label + " + Bell: " + bell.note.label + " • Pick again!");
        }
        chime = null;
        bell = null;
    }

    private static void drawLoops(ServerPlayer listener, Choice lead, Choice reply) {
        var from = lead.stop.point();
        var to = reply.stop.point();
        var middle = from.lerp(to, .5);
        double span = from.distanceTo(to);
        var across = span < .001 ? lead.stop.right() : to.subtract(from).scale(1 / span);
        var rise = lead.stop.face().getUnitVec3().cross(across);
        rise = rise.lengthSqr() < .000001 ? lead.stop.right() : rise.normalize();
        double width = span < .001 ? .36 : span * .5 + .22;
        double height = Math.min(1.25, .22 + span * .13);
        int steps = 4 * Math.clamp((int)Math.ceil(span * 1.5), 12, 32);
        var leadInk = new DustParticleOptions(lead.note.ink, .75f);
        var replyInk = new DustParticleOptions(reply.note.ink, .75f);
        for (int i = 0; i < steps; i++) {
            double angle = 2 * Math.PI * i / steps;
            var point = middle.add(across.scale(width * Math.sin(angle))).add(rise.scale(height * Math.sin(2 * angle)));
            GalleryTrail.spark(listener, i < steps / 2 ? replyInk : leadInk, point);
            if (i % 8 == 4) GalleryTrail.spark(listener, ParticleTypes.END_ROD, point);
        }
        GalleryTrail.spark(listener, ParticleTypes.GLOW, middle);
    }
}

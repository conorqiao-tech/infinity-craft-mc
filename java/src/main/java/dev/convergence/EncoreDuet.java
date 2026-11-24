package dev.convergence;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;

final class EncoreDuet {
    private static final AttachmentType<Long> INVITE_UNTIL = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "duet_invite"));
    private static final AttachmentType<EncoreDuet> DUET = AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "encore_duet"));
    private final UUID lead;
    private final UUID reply;
    private final List<GalleryTrail.Stop> route;
    private final List<GalleryTrail.Beat> notes;
    private int played;
    private int lastPrompt;
    private DuetCoda coda;

    private EncoreDuet(ServerPlayer first, ServerPlayer second, List<GalleryTrail.Stop> route) {
        lead = first.getUUID();
        reply = second.getUUID();
        this.route = route;
        notes = GalleryTrail.medley(route, false);
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (var player : server.getPlayerList().getPlayers()) {
                var expiry = player.getAttached(INVITE_UNTIL);
                if (expiry != null && expiry <= player.level().getGameTime()) {
                    player.removeAttached(INVITE_UNTIL);
                    ConcertStage.overlay(player, "Duet invite expired • Tap the last frame to try again.");
                }
                var pair = player.getAttached(DUET);
                if (pair != null && pair.lead.equals(player.getUUID()) && server.getTickCount() - pair.lastPrompt >= 30) {
                    var partner = pair.availablePartner(player);
                    if (partner != null) {
                        if (pair.coda == null) pair.prompt(player, partner);
                        else pair.lastPrompt = server.getTickCount();
                    }
                }
            }
        });
        ServerPlayerEvents.LEAVE.register(EncoreDuet::leave);
        ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> leave(old));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> leave(player));
    }

    static boolean hasBaton(ServerPlayer p) {
        var held = p.getMainHandItem();
        return held.is(Items.STICK) && held.getComponentsPatch().isEmpty();
    }

    static void leave(ServerPlayer p) {
        p.removeAttached(INVITE_UNTIL);
        var duet = p.removeAttached(DUET);
        if (duet == null) return;
        var other = duet.partner(p);
        if (other != null && other.getAttached(DUET) == duet) {
            other.removeAttached(DUET);
            ConcertStage.overlay(other, (duet.coda == null ? "Duet ended" : "Coda closed") + " • Pair again at the last frame.");
        }
    }

    private ServerPlayer partner(ServerPlayer p) {
        return p.level().getServer().getPlayerList().getPlayer(lead.equals(p.getUUID()) ? reply : lead);
    }

    static boolean useCodaInk(ServerPlayer p, ItemFrame frame) {
        var pair = p.getAttached(DUET);
        if (pair == null || pair.coda == null) return false;
        for (var stop : pair.route) {
            if (!stop.frameId().equals(frame.getUUID())) continue;
            if (InfinityJourney.emptyScreen(p)) {
                var other = pair.availablePartner(p);
                if (other != null) pair.coda.choose(p, other, pair.lead.equals(p.getUUID()), stop);
            }
            return true;
        }
        return false;
    }

    static void tap(ServerPlayer p, ItemFrame frame) {
        if (p.isShiftKeyDown()) {
            leave(p);
            ConcertStage.overlay(p, "Duet closed • Tap the last frame to pair again.");
            return;
        }
        var duet = p.getAttached(DUET);
        if (duet != null && duet.coda == null) {
            duet.play(p, frame);
            return;
        }
        if (duet != null) {
            if (!duet.route.getLast().frameId().equals(frame.getUUID())) {
                ConcertStage.overlay(p, "Pick a coda note with glow ink, or tap the last frame with a stick to pair again.");
                return;
            }
            leave(p);
        }
        var route = GalleryTrail.route(p);
        if (!GalleryTrail.encoreReady(p)) return;
        var last = route.getLast();
        if (!last.frameId().equals(frame.getUUID())) {
            ConcertStage.overlay(p, "Pair for a duet at the last frame of your trail.");
            return;
        }
        if (route.stream().anyMatch(s -> !s.stillDisplayed(p))) {
            p.removeAttached(INVITE_UNTIL);
            ConcertStage.overlay(p, "The display changed. Solve a new trail to play a duet.");
            return;
        }
        long now = p.level().getGameTime();
        InfinityChimes.stopPlayback(p);
        for (var other : p.level().players()) {
            if (other == p || other.getAttachedOrElse(INVITE_UNTIL, 0L) <= now || !hasBaton(other)
                || other.position().distanceToSqr(last.point()) > 36 || !InfinityJourney.emptyScreen(other)
                || ConcertStage.current(other) != null || !route.equals(GalleryTrail.route(other))) continue;
            p.removeAttached(INVITE_UNTIL);
            other.removeAttached(INVITE_UNTIL);
            var pair = new EncoreDuet(other, p, route);
            other.setAttached(DUET, pair);
            p.setAttached(DUET, pair);
            InfinityChimes.stopPlayback(other);
            pair.prompt(other, p);
            return;
        }
        p.setAttached(INVITE_UNTIL, now + 600);
        ConcertStage.overlay(p, "Waiting 30s • Have your partner solve this trail and tap here with a plain stick.");
    }

    private boolean leadTurn() { return played % 2 == 0; }

    private ServerPlayer availablePartner(ServerPlayer p) {
        var other = partner(p);
        var last = route.getLast().point();
        if (other == null || other.getAttached(DUET) != this || other.level() != p.level() || !ServerMenu.allowed(other) || !ServerMenu.allowed(p)
            || other.position().distanceToSqr(last) > 32 * 32 || p.position().distanceToSqr(last) > 32 * 32
            || route.stream().anyMatch(s -> !s.stillDisplayed(p))) {
            leave(p);
            ConcertStage.overlay(p, "Duet ended • Check the frames and pair again at the last frame.");
            return null;
        }
        return other;
    }

    private void play(ServerPlayer p, ItemFrame frame) {
        var other = availablePartner(p);
        if (other == null) return;
        var beat = notes.get(played);
        if (!p.getUUID().equals(leadTurn() ? lead : reply) || !route.get(beat.from()).frameId().equals(frame.getUUID())) {
            cue(p);
            return;
        }
        var instrument = leadTurn() ? SoundEvents.NOTE_BLOCK_CHIME : SoundEvents.NOTE_BLOCK_BELL;
        for (var listener : List.of(p, other)) {
            InfinityChimes.sound(listener, beat.note().note, instrument);
            GalleryTrail.showBeat(listener, route, beat);
        }
        played++;
        if (played < notes.size()) {
            prompt(p, other);
            return;
        }
        coda = new DuetCoda();
        for (var listener : List.of(p, other)) {
            for (var stop : route) GalleryTrail.halo(listener, stop);
            ConcertStage.overlay(listener, "Duet complete • " + played + " notes together • Glow ink on a frame picks its last note. Sneak for its first.");
        }
    }

    private void prompt(ServerPlayer a, ServerPlayer b) {
        lastPrompt = a.level().getServer().getTickCount();
        cue(a);
        cue(b);
    }

    private void cue(ServerPlayer listener) {
        var beat = notes.get(played);
        var stop = route.get(beat.from());
        var right = stop.right();
        var up = stop.face().getUnitVec3().cross(right);
        if (leadTurn()) {
            for (int k = -2; k <= 2; k++) GalleryTrail.spark(listener, ParticleTypes.END_ROD, stop.point().add(right.scale(k * .13)));
        } else {
            for (int k = 0; k < 8; k++) {
                double angle = Math.PI * k / 4;
                double x = Math.cos(angle), y = Math.sin(angle);
                double scale = .32 / (Math.abs(x) + Math.abs(y));
                GalleryTrail.spark(listener, ParticleTypes.END_ROD, stop.point().add(right.scale(scale * x)).add(up.scale(scale * y)));
            }
        }
        boolean mine = listener.getUUID().equals(leadTurn() ? lead : reply);
        var who = mine ? "Your turn • Tap frame " : partner(listener).getGameProfile().name() + "'s turn • Frame ";
        ConcertStage.overlay(listener, who + (beat.from() + 1) + " • " + beat.note().label + " • " + (played + 1) + "/" + notes.size());
    }
}

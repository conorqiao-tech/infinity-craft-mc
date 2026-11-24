package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;

import static dev.convergence.EchoChallengeGameTests.overlays;
import static dev.convergence.GalleryTrailGameTests.*;
import static dev.convergence.StarLoom.Chart.*;

public class EncoreDuetGameTests {
    static final class Gallery implements AutoCloseable {
        final List<ItemFrame> frames = new ArrayList<>();
        final List<NativePlayConnection> wires = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();

        Gallery(GameTestHelper c, String prefix) {
            var fixture = new ModeGameTests();
            try {
                for (var name : List.of("Lead", "Reply", "Visitor")) {
                    var wire = new NativePlayConnection(c.getLevel().registryAccess());
                    wires.add(wire);
                    var p = fixture.player(c, prefix + name, wire.connection);
                    players.add(p);
                    p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
                }
                var lead = players.getFirst();
                var pos = lead.blockPosition().atY(lead.level().getMaxY() - 12);
                int step = (pos.getX() & 15) < 12 ? 1 : -1;
                frames.add(exhibit(lead, pos, KITE, HALF_MOON));
                frames.add(exhibit(lead, pos.offset(2 * step, 1, 0), HALF_MOON, EMBER_PEAKS, EMBER_PEAKS));
                frames.add(exhibit(lead, pos.offset(4 * step, 0, 0), EMBER_PEAKS, KITE));
                for (var p : players.subList(0, 2)) {
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
                    for (var frame : frames) visit(p, frame, false);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                }
                players.get(2).setPos(players.get(1).position().add(.4, 0, 0));
                wires.forEach(NativePlayConnection::drain);
            } catch (RuntimeException | Error e) { close(); throw e; }
        }
        void tap(int player, int frame) { visit(players.get(player), frames.get(frame), false); }
        List<Packet<?>> drain(int player) { return wires.get(player).drain(); }

        void finishDuet() {
            tap(0, 2); tap(1, 2);
            int[] targets = {0, 0, 1, 1, 2};
            for (int i = 0; i < targets.length; i++) tap(i % 2, targets[i]);
            for (var p : players.subList(0, 2)) p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOW_INK_SAC, 3));
            wires.forEach(NativePlayConnection::drain);
        }

        @Override public void close() {
            frames.forEach(ItemFrame::discard);
            for (var p : players) InfinityJourneyGameTests.cleanup(new CommonsGameTests(), p);
            for (var wire : wires) wire.channel.finishAndReleaseAll();
        }
    }

    static List<ClientboundSoundPacket> music(List<Packet<?>> packets) {
        return packets.stream().filter(ClientboundSoundPacket.class::isInstance).map(ClientboundSoundPacket.class::cast)
            .filter(s -> s.getSound().equals(SoundEvents.NOTE_BLOCK_CHIME) || s.getSound().equals(SoundEvents.NOTE_BLOCK_BELL)).toList();
    }

    @GameTest(maxTicks = 95) public void partnersTradeUntimedNotesWhileNearbyVisitorGetsNoCues(GameTestHelper c) {
        var g = new Gallery(c, "Duet");
        try {
            g.tap(0, 2); g.tap(1, 2);
            var leadCues = g.drain(0);
            var replyCues = g.drain(1);
            c.assertTrue(overlays(leadCues).contains("Your turn • Tap frame 1 • Kite • 1/5")
                && overlays(replyCues).contains(g.players.get(0).getGameProfile().name() + "'s turn • Frame 1 • Kite • 1/5"),
                "Both players see who leads and which frame to use");
            var bar = particles(replyCues, ParticleTypes.END_ROD);
            var center = GalleryTrail.framePoint(g.frames.getFirst());
            c.assertTrue(bar.size() == 5 && bar.stream().allMatch(v -> v.y == center.y && v.z == center.z)
                && bar.getFirst().x != bar.getLast().x, "Five points mark the lead with a bar on the frame face");
            g.tap(1, 0); g.tap(0, 1);
            c.assertTrue(music(g.drain(0)).isEmpty() && music(g.drain(1)).isEmpty(), "Wrong player and wrong frame cannot spend a beat");
        } catch (RuntimeException | Error e) { g.close(); throw e; }
        c.runAfterDelay(55, () -> {
            try {
                c.assertTrue(overlays(g.drain(0)).contains("Your turn • Tap frame 1 • Kite • 1/5")
                    && music(g.drain(1)).isEmpty(), "The first cue renews while players wait, with no missed beat");
                var expected = List.of(KITE, HALF_MOON, EMBER_PEAKS, EMBER_PEAKS, KITE);
                int[] targets = {0, 0, 1, 1, 2};
                for (int beat = 0; beat < expected.size(); beat++) {
                    g.tap(beat % 2, targets[beat]);
                    for (int listener = 0; listener < 2; listener++) {
                        var packets = g.drain(listener);
                        var sounds = music(packets);
                        c.assertValueEqual(sounds.size(), 1, "One shared sound per accepted tap");
                        c.assertTrue(sounds.getFirst().getSound().equals(beat % 2 == 0 ? SoundEvents.NOTE_BLOCK_CHIME : SoundEvents.NOTE_BLOCK_BELL)
                            && sounds.getFirst().getPitch() == NoteBlock.getPitchFromNote(expected.get(beat).note),
                            "Both hear the authored pitch in the current player's instrument");
                        if (beat == 0) {
                            var diamond = particles(packets, ParticleTypes.END_ROD);
                            var center = GalleryTrail.framePoint(g.frames.getFirst());
                            c.assertTrue(diamond.size() == 8 && diamond.stream().allMatch(v ->
                                Math.abs(Math.abs(v.x - center.x) + Math.abs(v.y - center.y) - .32) < .00001 && v.z == center.z),
                                "Eight perimeter points make a hollow diamond for the reply");
                        }
                        if (beat == 1 || beat == 3) c.assertValueEqual(particles(packets, ParticleTypes.GLOW).size(), 2,
                            "Both frames flash together on the shared note");
                        if (beat == 4) c.assertTrue(overlays(packets).contains("Duet complete • 5 notes together • Glow ink on a frame picks its last note. Sneak for its first.")
                            && particles(packets, ParticleTypes.WAX_ON).size() == 36, "Both players get three final halos");
                    }
                }
                var visitor = g.drain(2);
                c.assertTrue(music(visitor).isEmpty() && particles(visitor, ParticleTypes.END_ROD).isEmpty()
                    && particles(visitor, ParticleTypes.GLOW).isEmpty() && particles(visitor, ParticleTypes.WAX_ON).isEmpty(),
                    "Unpaired visitor hears no duet and sees no markers or halos");
                for (var frame : g.frames) c.assertValueEqual(frame.getRotation(), 0, "Both sticks leave frames untouched");
            } finally { g.close(); }
            c.succeed();
        });
    }

    @GameTest public void performersChooseChangeAndRepeatPrivateCodaChords(GameTestHelper c) {
        try (var g = new Gallery(c, "Coda")) {
            g.finishDuet();
            var first = g.frames.getFirst();
            var last = g.frames.getLast();
            var origin = GalleryTrail.framePoint(first);
            var middle = origin.lerp(GalleryTrail.framePoint(last), .5);
            for (boolean sameFrame : List.of(false, true)) {
                if (sameFrame) {
                    visit(g.players.getFirst(), first, true); // Chime opens on Kite.
                } else {
                    g.tap(1, 0); // Bell initially selects Half Moon.
                    visit(g.players.get(1), last, true); // Revise to Ember Peaks before the lead chooses.
                }
                var waiting = g.drain(sameFrame ? 0 : 1);
                var other = g.drain(sameFrame ? 1 : 0);
                var partnerName = g.players.get(sameFrame ? 1 : 0).getGameProfile().name();
                c.assertTrue(overlays(waiting).contains("You chose " + (sameFrame ? KITE : EMBER_PEAKS).label + " • Waiting for " + partnerName)
                    && music(waiting).isEmpty() && music(other).isEmpty(), "One performer can choose and revise a silent note");
                g.tap(sameFrame ? 1 : 0, 0); // The other voice closes on Half Moon in both cases.
                var lead = sameFrame ? KITE : HALF_MOON;
                var reply = sameFrame ? HALF_MOON : EMBER_PEAKS;
                var center = sameFrame ? origin : middle;
                for (int i = 0; i < 2; i++) {
                    var packets = g.drain(i);
                    var chord = music(packets);
                    c.assertTrue(chord.size() == 2 && chord.get(0).getSound().equals(SoundEvents.NOTE_BLOCK_CHIME)
                        && chord.get(0).getPitch() == NoteBlock.getPitchFromNote(lead.note)
                        && chord.get(1).getSound().equals(SoundEvents.NOTE_BLOCK_BELL)
                        && chord.get(1).getPitch() == NoteBlock.getPitchFromNote(reply.note),
                        "The second tap sends both selected pitches to each performer in the same tick");
                    var dots = particles(packets, ParticleTypes.DUST);
                    for (var note : List.of(lead, reply)) {
                        long colored = packets.stream().filter(packet -> packet instanceof ClientboundLevelParticlesPacket fx
                            && fx.particle() instanceof DustParticleOptions dust
                            && dust.getColor().equals(ARGB.vector3fFromRGB24(note.ink))).count();
                        c.assertValueEqual(colored, (long)dots.size() / 2, "Each chosen ink draws half the figure eight");
                    }
                    c.assertTrue(dots.size() >= 48 && dots.stream().allMatch(p -> Math.abs(p.z - center.z) < .00001)
                        && particles(packets, ParticleTypes.GLOW).equals(List.of(center))
                        && dots.stream().filter(p -> p.distanceToSqr(center) < .000001).count() == 2,
                        "Both colored loops cross at the glow on the frame plane");
                    double radius = sameFrame ? .35 : origin.distanceTo(middle);
                    c.assertTrue(dots.stream().anyMatch(p -> p.x < center.x - radius) && dots.stream().anyMatch(p -> p.x > center.x + radius)
                        && dots.stream().anyMatch(p -> p.y > center.y + .20) && dots.stream().anyMatch(p -> p.y < center.y - .20)
                        && (!sameFrame || dots.stream().allMatch(p -> p.distanceTo(center) < .5)),
                        "Two frames get a spanning figure eight and a shared frame gets a compact one");
                    c.assertTrue(overlays(packets).contains("Coda • Chime: " + lead.label + " + Bell: " + reply.label + " • Pick again or stamp paper at the last frame."),
                        "The chord names both roles and pitches");
                }
                var observer = g.drain(2);
                c.assertTrue(music(observer).isEmpty() && overlays(observer).isEmpty() && particles(observer, ParticleTypes.DUST).isEmpty()
                    && particles(observer, ParticleTypes.GLOW).isEmpty() && particles(observer, ParticleTypes.END_ROD).isEmpty(),
                    "A nearby visitor receives no coda sounds, hints or particles");
            }
            for (var p : g.players.subList(0, 2)) c.assertValueEqual(p.getMainHandItem().getCount(), 3, "Coda ink is reusable");
            for (var frame : g.frames) c.assertValueEqual(frame.getRotation(), 0, "Coda selections preserve displayed prints");
        }
        c.succeed();
    }

    @GameTest public void codaLeavesOrdinaryFrameUseAloneAndDropsAnUnfinishedChord(GameTestHelper c) {
        try (var g = new Gallery(c, "Rest")) {
            g.finishDuet();
            var first = g.frames.getFirst();
            var lead = g.players.getFirst();
            var reply = g.players.get(1);
            g.players.get(2).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOW_INK_SAC));
            g.tap(2, 0);
            c.assertValueEqual(first.getRotation(), 1, "An unpaired visitor keeps vanilla frame interaction");
            var special = new ItemStack(Items.GLOW_INK_SAC);
            special.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
            lead.setItemInHand(InteractionHand.MAIN_HAND, special);
            g.tap(0, 0);
            c.assertValueEqual(first.getRotation(), 2, "Named ink keeps vanilla frame interaction");
            lead.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOW_INK_SAC));
            g.tap(0, 0);
            reply.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
            g.tap(1, 2);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Coda closed")), "Restarting releases both performers");
            g.tap(0, 0);
            c.assertValueEqual(first.getRotation(), 3, "Former partners can use frames normally again");
            lead.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
            g.tap(0, 2);
            c.assertTrue(overlays(g.drain(1)).contains("Your turn • Tap frame 1 • Kite • 1/5")
                && music(g.drain(0)).isEmpty(), "Fresh pairing starts a new duet without sounding a stale choice");
        }
        c.succeed();
    }

    @GameTest public void cancelledInviteAndPartnerExitRequireFreshPairing(GameTestHelper c) {
        try (var g = new Gallery(c, "Pair")) {
            g.tap(0, 2);
            visit(g.players.get(0), g.frames.getLast(), true);
            g.tap(1, 2);
            c.assertTrue(overlays(g.drain(1)).stream().anyMatch(s -> s.startsWith("Waiting 30s")), "Cancelled invitation cannot admit a partner");
            g.tap(0, 2);
            c.assertTrue(overlays(g.drain(1)).contains("Your turn • Tap frame 1 • Kite • 1/5"), "The new inviter leads");
            visit(g.players.get(0), g.frames.getFirst(), true);
            c.assertTrue(overlays(g.drain(1)).stream().anyMatch(s -> s.startsWith("Duet ended")), "Either player can release both batons");
            g.tap(1, 0);
            c.assertTrue(music(g.drain(1)).isEmpty(), "An ended duet cannot play notes");
            g.tap(1, 2); g.tap(0, 2); g.drain(0); g.drain(1);
            g.frames.get(1).setItem(new ItemStack(Items.APPLE));
            g.tap(1, 0);
            c.assertTrue(music(g.drain(1)).isEmpty() && overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Duet ended")),
                "Changing a print closes the duet before a note sounds");
        }
        c.succeed();
    }
}

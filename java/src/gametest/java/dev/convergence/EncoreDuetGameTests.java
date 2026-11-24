package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
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
    private static final class Gallery implements AutoCloseable {
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

        @Override public void close() {
            frames.forEach(ItemFrame::discard);
            for (var p : players) InfinityJourneyGameTests.cleanup(new CommonsGameTests(), p);
            for (var wire : wires) wire.channel.finishAndReleaseAll();
        }
    }

    private static List<ClientboundSoundPacket> music(List<Packet<?>> packets) {
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
                        if (beat == 4) c.assertTrue(overlays(packets).contains("Duet complete • 5 notes together")
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

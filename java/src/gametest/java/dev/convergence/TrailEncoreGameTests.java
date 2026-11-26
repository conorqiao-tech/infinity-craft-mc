package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;

import static dev.convergence.BannerConcertGameTests.pitches;
import static dev.convergence.EchoChallengeGameTests.overlays;
import static dev.convergence.GalleryTrailGameTests.*;
import static dev.convergence.StarLoom.Chart.*;

public class TrailEncoreGameTests {
    private static void solve(ServerPlayer p, List<ItemFrame> frames) {
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
        for (var frame : frames) visit(p, frame, false);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
    }

    private static void checkMedley(GameTestHelper c, List<ItemFrame> frames, List<Packet<?>> packets, boolean reverse) {
        var written = List.of(KITE, KITE, HALF_MOON, EMBER_PEAKS, EMBER_PEAKS, KITE, HALF_MOON);
        var expected = reverse ? written.reversed() : written;
        c.assertValueEqual(pitches(packets), expected.stream().map(note -> NoteBlock.getPitchFromNote(note.note)).toList(),
            "Seven pitches keep written repeats and sound both overlaps once");
        var path = (reverse ? frames.reversed() : frames).stream().map(GalleryTrail::framePoint).toList();
        c.assertValueEqual(particles(packets, ParticleTypes.GLOW), List.of(path.get(0), path.get(1), path.get(1), path.get(2)),
            "Each handoff flashes exactly the departing and arriving frames");
        var arcs = particles(packets, ParticleTypes.END_ROD);
        for (int i = 0; i < 2; i++) {
            var a = path.get(i); var b = path.get(i + 1);
            c.assertTrue(arcs.contains(a) && arcs.contains(b) && arcs.stream().anyMatch(v -> {
                double t = (v.x - a.x) / (b.x - a.x);
                return t > .3 && t < .7 && v.y > a.lerp(b, t).y + .3;
            }), "The luminous arch rises between the same two flashes");
        }
        String title = reverse ? "Reverse encore" : "Encore";
        var labels = overlays(packets).stream().filter(s -> s.startsWith(title + " • Frame")).toList();
        c.assertValueEqual(labels.size(), expected.size(), "One text cue per heard note");
        c.assertTrue(labels.contains(title + (reverse ? " • Frame 3 to 2 • Ember Peaks" : " • Frame 1 to 2 • Half Moon"))
            && labels.contains(title + (reverse ? " • Frame 2 to 1 • Half Moon" : " • Frame 2 to 3 • Ember Peaks")),
            "Both transfer beats name their frames and pitch in travel order");
    }

    @GameTest(maxTicks = 85) public void twoConductorsHearOppositeJoinedScoresAndSeeTheirOwnHandoffs(GameTestHelper c) {
        var fw = new NativePlayConnection(c.getLevel().registryAccess());
        var rw = new NativePlayConnection(c.getLevel().registryAccess());
        var f = new ModeGameTests();
        var first = f.player(c, "encore-front", fw.connection);
        var second = f.player(c, "encore-back", rw.connection);
        var frames = new ArrayList<ItemFrame>();
        Runnable cleanup = () -> {
            frames.forEach(ItemFrame::discard);
            var fixture = new CommonsGameTests();
            InfinityJourneyGameTests.cleanup(fixture, first); InfinityJourneyGameTests.cleanup(fixture, second);
            fw.channel.finishAndReleaseAll(); rw.channel.finishAndReleaseAll();
        };
        try {
            first.closeContainer(); second.closeContainer();
            first.setGameMode(GameType.SURVIVAL); second.setGameMode(GameType.SURVIVAL);
            var root = first.blockPosition().atY(first.level().getMaxY() - 15);
            frames.add(exhibit(first, root, KITE, KITE, HALF_MOON));
            frames.add(exhibit(first, root.offset(2, 1, 0), HALF_MOON, EMBER_PEAKS, EMBER_PEAKS));
            frames.add(exhibit(first, root.offset(5, 0, 0), EMBER_PEAKS, KITE, HALF_MOON));
            solve(first, frames); solve(second, frames);
            InfinityChimes.stopPlayback(second);
            fw.drain(); rw.drain();
            visit(first, frames.getFirst(), false);
            var wrong = fw.drain();
            c.assertTrue(pitches(wrong).isEmpty() && overlays(wrong).contains("Use the feather on the last frame of your trail."),
                "A feather on the wrong display explains where to conduct");
            visit(first, frames.getLast(), false);
        } catch (RuntimeException | Error e) { cleanup.run(); throw e; }
        int start = first.level().getServer().getTickCount();
        List<Packet<?>> forward = new ArrayList<>(), backward = new ArrayList<>();
        c.onEachTick(() -> {
            int tick = first.level().getServer().getTickCount() - start;
            try {
                if (tick == 4) visit(second, frames.getLast(), true);
                forward.addAll(fw.drain());
                var packets = rw.drain();
                if (tick < 4) c.assertTrue(pitches(packets).isEmpty() && particles(packets, ParticleTypes.DUST).isEmpty(),
                    "The second conductor receives none of the first conductor's opening cue");
                backward.addAll(packets);
                if (tick < 58) return;
                checkMedley(c, frames, forward, false);
                checkMedley(c, frames, backward, true);
                c.assertTrue(first.getMainHandItem().is(Items.FEATHER) && second.getMainHandItem().is(Items.FEATHER),
                    "Both conductors retain their feather");
                for (var frame : frames) c.assertValueEqual(frame.getRotation(), 0, "Conducting never turns the print");
                first.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
                visit(first, frames.getFirst(), false);
                c.assertFalse(GalleryTrail.encoreReady(first), "Starting a new compass trail releases the previous encore");
                c.assertTrue(GalleryTrail.encoreReady(second), "Other conductor retains their encore");
            } catch (RuntimeException | Error e) { cleanup.run(); throw e; }
            cleanup.run(); c.succeed();
        });
    }

    @GameTest(maxTicks = 50) public void replacingAPrintStopsTheEncoreBeforeItsNextChime(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var p = new ModeGameTests().player(c, "encore-change", wire.connection);
        var frames = new ArrayList<ItemFrame>();
        Runnable cleanup = () -> {
            frames.forEach(ItemFrame::discard);
            InfinityJourneyGameTests.cleanup(new CommonsGameTests(), p);
            wire.channel.finishAndReleaseAll();
        };
        try {
            p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
            BlockPos root = p.blockPosition().atY(p.level().getMaxY() - 11);
            frames.add(exhibit(p, root, KITE, HALF_MOON));
            frames.add(exhibit(p, root.offset(2, 1, 0), HALF_MOON, EMBER_PEAKS));
            frames.add(exhibit(p, root.offset(4, 0, 0), EMBER_PEAKS, KITE));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
            visit(p, frames.getLast(), false);
            c.assertValueEqual(frames.getLast().getRotation(), 1, "Before a solve the feather still rotates a framed print");
            solve(p, frames); wire.drain();
            visit(p, frames.getLast(), false);
            frames.get(1).setItem(new ItemStack(Items.APPLE));
        } catch (RuntimeException | Error e) { cleanup.run(); throw e; }
        c.runAfterDelay(18, () -> {
            try {
                var packets = wire.drain();
                c.assertValueEqual(pitches(packets), List.of(NoteBlock.getPitchFromNote(KITE.note)), "Only the opening note sounded");
                c.assertTrue(particles(packets, ParticleTypes.GLOW).isEmpty() && particles(packets, ParticleTypes.END_ROD).isEmpty(),
                    "No handoff was rendered after the display changed");
                c.assertTrue(overlays(packets).contains("Encore ended • The display changed. Solve a new trail.")
                    && !GalleryTrail.encoreReady(p), "Changed exhibit retires the completed route");
            } finally { cleanup.run(); }
            c.succeed();
        });
    }
}

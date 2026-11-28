package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.phys.Vec3;

import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.BannerConcertGameTests.pitches;
import static dev.convergence.EchoChallengeGameTests.overlays;
import static dev.convergence.StarLoom.Chart.*;

public class GalleryTrailGameTests {
    static ItemFrame exhibit(ServerPlayer author, BlockPos pos, StarLoom.Chart... notes) {
        InfinityJourney.open(author, InfinityJourney.Page.MIRROR_COMPOSER);
        while (!MirrorComposer.score(author).isEmpty()) choose(author, "Undo last note");
        for (var note : notes) choose(author, "Add " + note.label);
        author.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
        choose(author, "Star Prints");
        choose(author, "Print my draft");
        author.closeContainer();
        var frame = new ItemFrame(author.level(), pos, Direction.NORTH);
        frame.setItem(author.getMainHandItem().copy());
        if (!author.level().addFreshEntity(frame)) throw new AssertionError("Exhibit could not be placed");
        return frame;
    }

    static void visit(ServerPlayer p, ItemFrame frame, boolean crouch) {
        p.setPos(frame.position().add(0, -1, -1.3));
        p.connection.handleInteract(new ServerboundInteractPacket(frame.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, crouch));
    }

    static List<Vec3> particles(List<Packet<?>> packets, ParticleType<?> type) {
        return packets.stream().filter(ClientboundLevelParticlesPacket.class::isInstance).map(ClientboundLevelParticlesPacket.class::cast)
            .filter(pkt -> pkt.particle().getType() == type).map(pkt -> new Vec3(pkt.x(), pkt.y(), pkt.z())).toList();
    }

    @GameTest public void compassReadsAllEightSkyDialNotchesWithoutTurningTheFrame(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Dial")) {
            var reader = g.players.get(1);
            var north = g.frames.getFirst();
            var walls = new ArrayList<ItemFrame>();
            walls.add(north);
            for (var face : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                var position = north.blockPosition().above(2 * walls.size());
                var wall = face == Direction.SOUTH ? new GlowItemFrame(reader.level(), position, face)
                    : new ItemFrame(reader.level(), position, face);
                c.assertTrue(reader.level().addFreshEntity(wall), "Place a dial facing " + face);
                walls.add(wall);
                g.frames.add(wall);
            }
            var score = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1);
            var prints = List.of(new Skyprint(EMBER_PEAKS, 0, 1, HALF_MOON), new Skyprint(HALF_MOON, 1, 0, KITE),
                new Skyprint(EMBER_PEAKS, 2, 2, EMBER_PEAKS));
            for (var frame : walls) for (var print : prints) {
                var paper = print.paper();
                frame.setItem(paper.copy());
                frame.setRotation(0);
                int target = List.of(6, 0, 2).get(print.column());
                var center = GalleryTrail.framePoint(frame);
                var right = frame.getDirection().getCounterClockWise().getUnitVec3();
                for (int angle = 0; angle < 8; angle++) {
                    reader.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
                    visit(reader, frame, false);
                    var packets = g.drain(1);
                    int remaining = Math.floorMod(target - angle, 8);
                    SealCipherGameTests.expectSounds(c, packets, score, remaining == 0 ? List.of(print.voice()) : List.of());
                    c.assertValueEqual(frame.getRotation(), angle, "Compass reads preserve rotation");
                    var needle = particles(packets, ParticleTypes.END_ROD);
                    c.assertValueEqual(needle.size(), 6, "Six needle points on every reading");
                    double radians = angle * Math.PI / 4;
                    var tip = center.add(right.scale(.425 * Math.sin(radians))).add(0, .425 * Math.cos(radians), 0);
                    c.assertTrue(needle.stream().anyMatch(point -> point.distanceTo(tip) < .001), "Needle follows the wall face");
                    c.assertValueEqual(particles(packets, ParticleTypes.ELECTRIC_SPARK).size(), remaining == 0 ? 8 : 0,
                        "Only an aligned reading flashes");
                    if (remaining == 0) {
                        visit(reader, frame, false);
                        SealCipherGameTests.expectSounds(c, g.drain(1), score, List.of(print.voice()));
                    } else {
                        c.assertTrue(overlays(packets).stream().anyMatch(line -> line.contains(remaining + " clockwise turn")),
                            "Hint reports clockwise distance including wraparound");
                    }
                    reader.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    visit(reader, frame, false);
                    c.assertValueEqual(frame.getRotation(), (angle + 1) % 8, "An empty hand uses vanilla frame rotation");
                    SealCipherGameTests.expectSounds(c, g.drain(1), score, List.of());
                }
                c.assertTrue(ItemStack.matches(frame.getItem(), paper), "Reading and turning retain the printed score");
            }
            for (var face : List.of(Direction.UP, Direction.DOWN)) {
                var horizontal = new ItemFrame(reader.level(), north.blockPosition().above(face == Direction.UP ? 8 : 10), face);
                c.assertTrue(reader.level().addFreshEntity(horizontal), "Place a horizontal frame");
                g.frames.add(horizontal);
                horizontal.setItem(prints.get(1).paper());
                reader.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
                visit(reader, horizontal, false);
                c.assertValueEqual(horizontal.getRotation(), 1, "Floor and ceiling frames keep ordinary compass interaction");
                var packets = g.drain(1);
                SealCipherGameTests.expectSounds(c, packets, score, List.of());
                c.assertTrue(particles(packets, ParticleTypes.END_ROD).isEmpty(), "A horizontal frame has no dial needle");
            }
            var visitor = g.drain(2);
            SealCipherGameTests.expectSounds(c, visitor, score, List.of());
            c.assertTrue(particles(visitor, ParticleTypes.END_ROD).isEmpty(), "Other players do not see the dial reading");
            reader.setItemInHand(InteractionHand.MAIN_HAND, CommonsEvents.named(Items.COMPASS, 1, "Named compass", "Ordinary frame use"));
            visit(reader, north, false);
            c.assertValueEqual(north.getRotation(), 1, "A named compass keeps vanilla frame interaction");
        }
        c.succeed();
    }

    @GameTest(maxTicks = 80) public void visitorSolvesThreeFramedMelodiesWithPrivateLinksAndHalos(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var otherWire = new NativePlayConnection(c.getLevel().registryAccess());
        var fixture = new ModeGameTests();
        var p = fixture.player(c, "trail-solver", wire.connection);
        var other = fixture.player(c, "trail-artist", otherWire.connection);
        var frames = new ArrayList<ItemFrame>();
        Runnable cleanup = () -> {
            frames.forEach(ItemFrame::discard);
            var f = new CommonsGameTests();
            InfinityJourneyGameTests.cleanup(f, p);
            InfinityJourneyGameTests.cleanup(f, other);
            wire.channel.finishAndReleaseAll();
            otherWire.channel.finishAndReleaseAll();
        };
        try {
            p.closeContainer(); other.closeContainer();
            p.setGameMode(GameType.SURVIVAL); other.setGameMode(GameType.SURVIVAL);
            var root = p.blockPosition().atY(p.level().getMaxY() - 9);
            frames.add(exhibit(other, root, KITE, HALF_MOON));
            frames.add(exhibit(other, root.offset(2, 2, 0), HALF_MOON, EMBER_PEAKS));
            frames.add(exhibit(other, root.offset(5, 0, 0), EMBER_PEAKS, KITE));
            other.setPos(frames.getFirst().position().add(1, -1, -1.2));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
            other.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
            InfinityChimes.stopPlayback(other);
            wire.drain(); otherWire.drain();
            visit(p, frames.getFirst(), false);
        } catch (RuntimeException | Error e) { cleanup.run(); throw e; }
        int start = p.level().getServer().getTickCount();
        var notes = new ArrayList<Float>();
        var hints = new ArrayList<String>();
        var rings = new ArrayList<Vec3>();
        var link = new ArrayList<Vec3>();
        c.onEachTick(() -> {
            int elapsed = p.level().getServer().getTickCount() - start;
            try {
                if (elapsed == 10) {
                    visit(p, frames.getFirst(), false);
                    visit(p, frames.getLast(), false);
                }
                if (elapsed == 12) visit(p, frames.get(1), false);
                if (elapsed == 24) visit(p, frames.getLast(), false);
                var packets = wire.drain();
                hints.addAll(overlays(packets));
                notes.addAll(pitches(packets));
                if (elapsed < 24) link.addAll(particles(packets, ParticleTypes.END_ROD));
                rings.addAll(particles(packets, ParticleTypes.WAX_ON));
                var nearby = otherWire.drain();
                c.assertTrue(pitches(nearby).isEmpty() && particles(nearby, ParticleTypes.END_ROD).isEmpty()
                    && particles(nearby, ParticleTypes.WAX_ON).isEmpty(), "Visitor's notes, links and finale stay private");
                if (elapsed < 34) return;
                c.assertTrue(hints.contains("Trail 1/3 • Find a print opening on Half Moon")
                    && hints.contains("Trail 1/3 • Already visited. Find another print opening on Half Moon.")
                    && hints.contains("Trail 1/3 • Opens on Ember Peaks. Find one opening on Half Moon.")
                    && hints.contains("Trail 2/3 • Find a print opening on Ember Peaks")
                    && hints.contains("Gallery Trail complete • 3/3")
                    && hints.contains("Last frame • Feather for solo (Sneak reverses) • Each use a plain stick for a duet."), "Clues survive a duplicate and a wrong note");
                var expected = List.of(KITE, HALF_MOON, HALF_MOON, EMBER_PEAKS, EMBER_PEAKS, KITE);
                c.assertValueEqual(notes, expected.stream().map(chart -> NoteBlock.getPitchFromNote(chart.note)).toList(), "Each accepted score plays once");
                c.assertTrue(link.contains(GalleryTrail.framePoint(frames.getFirst())) && link.contains(GalleryTrail.framePoint(frames.get(1))),
                    "Bright link joins the first two displays");
                for (var frame : frames) {
                    Vec3 center = GalleryTrail.framePoint(frame);
                    c.assertValueEqual(rings.stream().filter(point -> Math.abs(point.distanceTo(center) - .32) < .0001
                        && Math.abs(point.z - center.z) < .0001).count(), 12L, "A complete gold halo lies on each frame's face");
                    c.assertValueEqual(frame.getRotation(), 0, "Compass leaves every exhibit intact");
                }
                visit(other, frames.get(1), false);
                c.assertTrue(overlays(otherWire.drain()).contains("Trail 1/3 • Find a print opening on Ember Peaks"), "Nearby artist starts an independent route");
                visit(p, frames.getFirst(), false);
                c.assertTrue(overlays(wire.drain()).contains("Trail 1/3 • Find a print opening on Half Moon"), "Completed trail can be played again");
            } catch (RuntimeException | Error e) { cleanup.run(); throw e; }
            cleanup.run();
            c.succeed();
        });
    }

    @GameTest public void distanceRestartAndChangedExhibitsUseTheCurrentDisplay(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var p = new ModeGameTests().player(c, "trail-curator", wire.connection);
        var frames = new ArrayList<ItemFrame>();
        try {
            p.closeContainer();
            p.setGameMode(GameType.SURVIVAL);
            var root = p.blockPosition().atY(p.level().getMaxY() - 15);
            var a = exhibit(p, root, KITE, HALF_MOON); frames.add(a);
            // Keep the radius boundary independent of the test world's random chunk alignment.
            var b = exhibit(p, root.below(12), HALF_MOON, EMBER_PEAKS); frames.add(b);
            var far = exhibit(p, root.below(13), HALF_MOON, KITE); frames.add(far);
            var special = new ItemStack(Items.COMPASS);
            special.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
            p.setItemInHand(InteractionHand.MAIN_HAND, special);
            visit(p, a, false);
            c.assertValueEqual(a.getRotation(), 1, "Decorated compasses keep ordinary frame interaction");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
            wire.drain();
            visit(p, a, false);
            visit(p, far, false);
            visit(p, b, false);
            var hints = overlays(wire.drain());
            c.assertTrue(hints.contains("Trail 1/3 • Too far. Find a print within 12 blocks of the last frame.")
                && hints.contains("Trail 2/3 • Find a print opening on Ember Peaks"), "Exactly twelve blocks links, thirteen keeps the old route");
            visit(p, b, true);
            c.assertTrue(overlays(wire.drain()).contains("Trail 1/3 • Find a print opening on Ember Peaks"), "Sneak starts fresh at the tapped exhibit");
            b.setItem(new ItemStack(Items.APPLE));
            visit(p, far, false);
            c.assertTrue(overlays(wire.drain()).contains("Display changed • Trail 1/3 • Find a print opening on Kite"), "Replacing a visited print starts a fresh route");
        } finally {
            frames.forEach(ItemFrame::discard);
            InfinityJourneyGameTests.cleanup(new CommonsGameTests(), p);
            wire.channel.finishAndReleaseAll();
        }
        c.succeed();
    }
}

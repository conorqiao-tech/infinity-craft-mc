package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
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

import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.BannerConcertGameTests.dust;
import static dev.convergence.BannerConcertGameTests.pitches;
import static dev.convergence.StarLoom.Chart.*;

public class ScoreGalleryGameTests {
    private static void tap(ServerPlayer p, ItemFrame frame, boolean crouch) {
        p.connection.handleInteract(new ServerboundInteractPacket(frame.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, crouch));
    }

    private static void printScore(ServerPlayer p) {
        p.closeContainer();
        p.setGameMode(GameType.SURVIVAL);
        InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER);
        for (var note : List.of(KITE, KITE, EMBER_PEAKS)) choose(p, "Add " + note.label);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
        choose(p, "Star Prints");
        choose(p, "Print my draft");
        p.closeContainer();
    }

    private static List<Vec3> stars(List<Packet<?>> packets) {
        return dust(packets).stream().filter(pkt -> ((DustParticleOptions)pkt.particle()).getScale() == .8f)
            .map(pkt -> new Vec3(pkt.x(), pkt.y(), pkt.z())).toList();
    }

    @GameTest(maxTicks = 80) public void visitorsReadOneFramedPrintInOppositeDirections(GameTestHelper c) {
        var firstWire = new NativePlayConnection(c.getLevel().registryAccess());
        var secondWire = new NativePlayConnection(c.getLevel().registryAccess());
        var players = new ModeGameTests();
        var first = players.player(c, "gallery-first", firstWire.connection);
        var second = players.player(c, "gallery-second", secondWire.connection);
        var frame = new ItemFrame(first.level(), first.blockPosition().offset(0, 1, 1), Direction.NORTH);
        Runnable cleanup = () -> {
            frame.discard();
            var f = new CommonsGameTests();
            InfinityJourneyGameTests.cleanup(f, first);
            InfinityJourneyGameTests.cleanup(f, second);
            firstWire.channel.finishAndReleaseAll();
            secondWire.channel.finishAndReleaseAll();
        };
        String author = first.getGameProfile().name();
        try {
            printScore(first);
            second.closeContainer();
            second.setGameMode(GameType.SURVIVAL);
            second.setPos(first.position().add(.5, 0, 0));
            c.assertTrue(first.level().addFreshEntity(frame), "Exhibit created");
            tap(first, frame, false);
            c.assertTrue(first.getMainHandItem().isEmpty() && StarPrint.read(frame.getItem()).composer().equals(author),
                "Using the print on an empty frame mounts the original sheet");
            first.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD));
            second.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD));
            firstWire.drain();
            secondWire.drain();
            tap(first, frame, false);
        } catch (RuntimeException | Error e) { cleanup.run(); throw e; }

        var server = first.level().getServer();
        int start = server.getTickCount();
        List<Packet<?>> outward = new ArrayList<>(), backward = new ArrayList<>();
        List<Vec3> forwardStars = new ArrayList<>(), returnStars = new ArrayList<>();
        c.onEachTick(() -> {
            int tick = server.getTickCount() - start;
            try {
                if (tick == 4) tap(second, frame, true);
                var a = firstWire.drain();
                var b = secondWire.drain();
                outward.addAll(a);
                backward.addAll(b);
                if (tick < 4) c.assertTrue(dust(b).isEmpty() && pitches(b).isEmpty(), "A visitor who has not tapped hears and sees nothing");
                if (EchoChallengeGameTests.overlays(a).contains("Score Gallery → " + author + " • 3/3 • Ember Peaks")) forwardStars.addAll(stars(a));
                if (EchoChallengeGameTests.overlays(b).contains("Score Gallery ← " + author + " • 3/3 • Kite")) returnStars.addAll(stars(b));
                if (tick < 24) return;
                var tune = List.of(KITE, KITE, EMBER_PEAKS).stream().map(note -> NoteBlock.getPitchFromNote(note.note)).toList();
                c.assertValueEqual(pitches(outward), tune, "First visitor hears only the written order");
                c.assertValueEqual(pitches(backward), tune.reversed(), "Crouching visitor hears only the reverse order");
                c.assertValueEqual(forwardStars.size(), 3, "Three distinct stars appear on the final forward beat");
                c.assertValueEqual(returnStars, forwardStars.reversed(), "Reverse traversal lights the same physical stars in reverse");
                var center = frame.getBoundingBox().getCenter();
                for (var star : forwardStars) {
                    c.assertTrue(Math.abs(center.z - star.z - .12) < .00001 && Math.abs(center.x - star.x) < .35
                        && Math.abs(center.y - star.y) < .35, "All stars are on the visible face of this frame");
                }
                c.assertTrue(forwardStars.getFirst().x > forwardStars.get(1).x && forwardStars.get(1).x > forwardStars.getLast().x
                    && forwardStars.getFirst().y == forwardStars.get(1).y && forwardStars.getLast().y > forwardStars.get(1).y,
                    "Kite repeats horizontally and Ember Peaks rises");
                c.assertValueEqual(frame.getRotation(), 0, "Playing leaves the framed exhibit in place");
                c.assertValueEqual(StarPrint.read(frame.getItem()).notes(), List.of(KITE, KITE, EMBER_PEAKS), "Frame retains the original print");
                c.assertTrue(first.getMainHandItem().getCount() == 1 && second.getMainHandItem().getCount() == 1, "Both visitors retain their shards");
                c.assertTrue(MirrorComposer.score(second).isEmpty(), "The visitor's draft stays blank");
            } catch (RuntimeException | Error e) { cleanup.run(); throw e; }
            cleanup.run();
            c.succeed();
        });
    }

    @GameTest public void horizontalGlowFramesProjectOntoTheirFaceAndOrdinaryPaperStillRotates(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var player = new ModeGameTests().player(c, "gallery-glow", wire.connection);
        var frames = new ArrayList<ItemFrame>();
        try {
            printScore(player);
            var forms = new ArrayList<Form>();
            InfinityJourney.form(player, InfinityJourney.Page.STAR_PRINTS, forms::add);
            var help = ((SimpleForm)forms.getFirst()).content();
            c.assertTrue(help.contains("amethyst shard") && help.contains("turn on Sneak before tapping"),
                "Held-print touch form explains how to play the exhibit in either direction");
            var print = player.getMainHandItem().copy();
            player.setYRot(0);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD));
            for (var face : List.of(Direction.UP, Direction.DOWN)) {
                BlockPos pos = player.blockPosition().offset(0, face == Direction.UP ? 0 : 2, 1);
                var frame = new GlowItemFrame(player.level(), pos, face);
                frames.add(frame);
                player.level().addFreshEntity(frame);
                frame.setItem(new ItemStack(Items.PAPER));
                wire.drain();
                tap(player, frame, false);
                c.assertValueEqual(frame.getRotation(), 1, "Shard on ordinary paper uses normal frame rotation");
                c.assertTrue(pitches(wire.drain()).isEmpty(), "Ordinary exhibit does not play a score");
                frame.setItem(print.copy());
                tap(player, frame, false);
                var points = stars(wire.drain());
                c.assertValueEqual(points.size(), 1, "One opening star over the glow frame");
                var offset = points.getFirst().subtract(frame.getBoundingBox().getCenter());
                c.assertTrue(Math.abs(offset.y * face.getStepY() - .12) < .00001 && offset.x > 0
                    && offset.z * face.getStepY() < 0, "Star lies outward from the floor or ceiling and reads from visitor's side");
                c.assertValueEqual(frame.getRotation(), 1, "Glow frame holds its rotation during playback");
            }
        } finally {
            frames.forEach(ItemFrame::discard);
            InfinityJourneyGameTests.cleanup(new CommonsGameTests(), player);
            wire.channel.finishAndReleaseAll();
        }
        c.succeed();
    }
}

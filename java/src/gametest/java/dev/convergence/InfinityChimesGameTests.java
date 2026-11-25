package dev.convergence;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;

public class InfinityChimesGameTests {
    private record Soundboard(ServerPlayer p, BlockPos pos, BlockState original) implements AutoCloseable {
        static Soundboard place(ServerPlayer p, int pitch) {
            var origin = p.blockPosition().atY(p.level().getMaxY() - 5);
            p.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
            var pos = origin.offset(0, 1, 3);
            var board = new Soundboard(p, pos, p.level().getBlockState(pos));
            board.tune(pitch);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            return board;
        }
        void tune(int pitch) {
            p.level().setBlock(pos, Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, pitch), 2);
        }
        public void close() { p.level().setBlock(pos, original, 2); }
    }

    private static void click(ServerPlayer p, String label) {
        var menu = (InfinityJourney.Handler)p.containerMenu;
        var labels = menu.view.choices().stream().map(InfinityJourney.Choice::label).toList();
        int index = labels.indexOf(label);
        if (index < 0) throw new AssertionError("Missing " + label + " among " + labels);
        menu.clicked(27 + index, 0, ContainerInput.PICKUP, p);
    }

    private static Form form(ServerPlayer p) {
        var forms = new ArrayList<Form>();
        InfinityJourney.form(p, InfinityJourney.Page.CHIMES, forms::add);
        return forms.getFirst();
    }

    @GameTest public void copiesPhysicalPitchAndPersistsEightNoteScore(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "chimes-copy");
        try (var board = Soundboard.place(p, 0)) {
            InfinityJourney.open(p);
            click(p, "Infinity Chimes • personal score");
            c.assertFalse(GameModes.state(p).contains(InfinityChimes.KEY), "Opening a blank score writes nothing");
            board.tune(6);
            click(p, "Copy F#3");
            c.assertTrue(InfinityChimes.score(p).isEmpty(), "A retuned target invalidates the old action");
            click(p, "Copy C4");
            click(p, "Undo last note • C4");
            c.assertFalse(GameModes.state(p).contains(InfinityChimes.KEY), "Undoing the only note returns to blank");
            click(p, "Copy C4");
            board.tune(24);
            p.closeContainer();
            InfinityJourney.open(p, InfinityJourney.Page.CHIMES);
            for (int i = 0; i < 7; i++) click(p, "Copy F#5");
            var full = ((InfinityJourney.Handler)p.containerMenu).view;
            c.assertTrue(full.content().contains("8/8") && full.choices().stream().noneMatch(choice -> choice.label().startsWith("Copy")), "Eight notes fill the score");
            c.assertValueEqual(p.level().getBlockState(board.pos()).getValue(NoteBlock.NOTE), 24, "Copying preserves the block's pitch");
            click(p, "Undo last note • F#5");
            c.assertValueEqual(InfinityChimes.score(f.reloadDisk(p)), List.of(6, 24, 24, 24, 24, 24, 24), "Saved sequence preserves repeated pitches, order, and Undo");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void formRechecksTargetScoreAndTicket(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "chimes-form");
        try (var board = Soundboard.place(p, 12)) {
            var expired = form(p);
            var current = form(p);
            var codec = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(current).codec();
            var first = JsonParser.parseString(codec.jsonData(current)).getAsJsonObject().getAsJsonArray("buttons").get(0).getAsJsonObject();
            c.assertValueEqual(first.get("text").getAsString(), "Copy F#4", "Bedrock form names the sampled pitch");
            c.assertValueEqual(first.getAsJsonObject("image").get("data").getAsString(), "textures/blocks/noteblock", "Bedrock uses the native soundboard texture");
            f.reply(expired, "0");
            c.assertTrue(InfinityChimes.score(p).isEmpty(), "Expired Bedrock ticket has no effect");
            f.reply(current, "0");
            f.reply(current, "0");
            c.assertValueEqual(InfinityChimes.score(p), List.of(12), "A Bedrock response copies exactly once");
            p.closeContainer();
            var behindWall = form(p);
            var wall = board.pos().north();
            var original = p.level().getBlockState(wall);
            try {
                p.level().setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
                f.reply(behindWall, "0");
                c.assertValueEqual(InfinityChimes.score(p), List.of(12), "A wall added before response blocks capture");
            } finally { p.level().setBlock(wall, original, 2); }
            p.closeContainer();
            var oldScore = form(p);
            var root = GameModes.state(p);
            root.putString(InfinityChimes.KEY, "future format");
            f.reply(oldScore, "0");
            c.assertValueEqual(root.getStringOr(InfinityChimes.KEY, ""), "future format", "Stale Copy preserves unfamiliar score data");
            for (int[] invalid : List.of(new int[]{-1}, new int[]{25}, new int[9])) {
                root.putIntArray(InfinityChimes.KEY, invalid);
                var view = InfinityChimes.view(p);
                c.assertTrue(view.choices().size() == 1 && view.content().contains("host review"), "Invalid pitches or lengths only offer Back");
            }
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    private static List<Float> pitches(NativePlayConnection wire) {
        return wire.drain().stream().filter(ClientboundSoundPacket.class::isInstance)
            .map(ClientboundSoundPacket.class::cast).filter(packet -> packet.getSound().equals(SoundEvents.NOTE_BLOCK_CHIME))
            .map(ClientboundSoundPacket::getPitch).toList();
    }

    @GameTest(maxTicks = 45) public void playsOnlyToOwnerOnBeatAndCancelsAfterEdit(GameTestHelper c) {
        var ownerWire = new NativePlayConnection(c.getLevel().registryAccess());
        var nearbyWire = new NativePlayConnection(c.getLevel().registryAccess());
        var players = new ModeGameTests();
        var owner = players.player(c, "chimes-solo", ownerWire.connection);
        var nearby = players.player(c, "chimes-nearby", nearbyWire.connection);
        Runnable close = () -> {
            var f = new CommonsGameTests();
            InfinityJourneyGameTests.cleanup(f, owner);
            InfinityJourneyGameTests.cleanup(f, nearby);
            ownerWire.channel.finishAndReleaseAll();
            nearbyWire.channel.finishAndReleaseAll();
        };
        try {
            nearby.setPos(owner.position().add(1, 0, 0));
            owner.closeContainer();
            GameModes.state(owner).putIntArray(InfinityChimes.KEY, new int[]{0, 12, 24});
            InfinityJourney.open(owner, InfinityJourney.Page.CHIMES);
            ownerWire.drain();
            nearbyWire.drain();
            click(owner, "Play my score");
            c.assertValueEqual(pitches(ownerWire), List.of(.5f), "The first native chime is immediate");
        } catch (Throwable e) { close.run(); throw e; }
        int start = c.getLevel().getServer().getTickCount();
        c.onEachTick(() -> {
            int elapsed = c.getLevel().getServer().getTickCount() - start;
            try {
                // This callback observes packets from the preceding END_SERVER_TICK.
                var expected = switch (elapsed) {
                    case 9 -> List.of(1f);
                    case 17 -> List.of(2f);
                    default -> List.<Float>of();
                };
                c.assertValueEqual(pitches(ownerWire), expected, "Owner's encoded pitches at tick " + elapsed);
                c.assertTrue(pitches(nearbyWire).isEmpty(), "A player one block away receives no chime at tick " + elapsed);
                if (elapsed == 17) {
                    click(owner, "Play my score");
                    c.assertValueEqual(pitches(ownerWire), List.of(.5f), "Replay starts at the first pitch");
                    click(owner, "Undo last note • F#5");
                }
            } catch (Throwable e) { close.run(); throw e; }
            if (elapsed == 35) { close.run(); c.succeed(); }
        });
    }
}

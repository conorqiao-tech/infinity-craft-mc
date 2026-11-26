package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.EncoreDuetGameTests.Gallery;
import static dev.convergence.GalleryTrailGameTests.particles;
import static dev.convergence.StarLoom.Chart.*;

public class SealCipherGameTests {
    private static final List<String> NAMES = List.of("Chime", "Harp", "Bell");

    private record Desk(ServerPlayer player, BlockPos pos, BlockState previous) implements AutoCloseable {
        static Desk place(ServerPlayer p) {
            var pos = p.blockPosition().offset(0, 0, 2);
            var desk = new Desk(p, pos, p.level().getBlockState(pos));
            p.level().setBlock(pos, Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, 8), 2);
            return desk;
        }
        void use() {
            var hand = InteractionHand.MAIN_HAND;
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit);
        }
        @Override public void close() { player.level().setBlock(pos, previous, 2); }
    }

    private static List<ClientboundSoundPacket> notes(List<Packet<?>> packets) {
        return packets.stream().filter(ClientboundSoundPacket.class::isInstance).map(ClientboundSoundPacket.class::cast)
            .filter(pkt -> pkt.getSource() == SoundSource.RECORDS).toList();
    }

    private static void expectClue(GameTestHelper c, List<Packet<?>> packet, ChordSeal seal, List<Integer> indices) {
        expectSounds(c, packet, seal, indices);
        c.assertValueEqual(particles(packet, ParticleTypes.END_ROD).size(), indices.size() * 12, "One shape for every voice");
        c.assertValueEqual(particles(packet, ParticleTypes.WAX_ON).size(), indices.size() == 2 ? 1 : 0, "One recessed socket per clue");
    }

    private static void expectSounds(GameTestHelper c, List<Packet<?>> packet, ChordSeal seal, List<Integer> indices) {
        var sounds = List.of(SoundEvents.NOTE_BLOCK_CHIME, SoundEvents.NOTE_BLOCK_HARP, SoundEvents.NOTE_BLOCK_BELL);
        var heard = notes(packet);
        c.assertValueEqual(heard.size(), indices.size(), "Exactly the clue voices sound");
        for (int i = 0; i < heard.size(); i++) {
            int corner = indices.get(i);
            c.assertTrue(heard.get(i).getSound().equals(sounds.get(corner))
                && heard.get(i).getPitch() == NoteBlock.getPitchFromNote(seal.voices().get(corner).note), "Stored voice and pitch");
        }
    }

    @GameTest public void solveAllSixGiftedSealRoutesWithoutChangingPlaybackOrBlock(GameTestHelper c) {
        try (var g = new Gallery(c, "Cipher"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            for (int opening = 0; opening < 3; opening++) for (int dir : List.of(1, -1)) {
                var seal = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), opening, dir);
                var gift = seal.item(); p.setItemInHand(InteractionHand.MAIN_HAND, gift);
                p.setShiftKeyDown(true);
                p.gameMode.useItem(p, p.level(), gift, InteractionHand.MAIN_HAND);
                p.setShiftKeyDown(false); g.drain(0);
                desk.use();
                for (int round = 0; round < 3; round++) {
                    int lead = Math.floorMod(opening + round * dir, 3);
                    int answer = Math.floorMod(lead + dir, 3);
                    int missing = 3 - lead - answer;
                    var expected = List.of(lead, answer);
                    expectClue(c, g.drain(0), seal, expected);
                    var view = ((InfinityJourney.Handler)p.containerMenu).view;
                    c.assertTrue(view.content().contains(seal.voiceName(lead)) && view.content().contains(seal.voiceName(answer)), "Clue can be read while muted");
                    choose(p, NAMES.get(lead));
                    c.assertTrue(notes(g.drain(0)).isEmpty() && ((InfinityJourney.Handler)p.containerMenu).view.title().endsWith(round + "/3"), "Wrong choice keeps score silently");
                    choose(p, "Replay clue"); expectClue(c, g.drain(0), seal, expected);
                    choose(p, NAMES.get(missing));
                }
                expectClue(c, g.drain(0), seal, List.of(0,1,2));
                c.assertTrue(((InfinityJourney.Handler)p.containerMenu).view.title().endsWith("3/3"), "All three clues solved");
                choose(p, "Play again"); expectClue(c, g.drain(0), seal, List.of(opening, Math.floorMod(opening + dir, 3)));
                p.closeContainer();
                p.gameMode.useItem(p, p.level(), gift, InteractionHand.MAIN_HAND);
                c.assertTrue(EchoChallengeGameTests.overlays(g.drain(0)).stream().anyMatch(t -> t.startsWith("Chord Seal 2/4")), "Normal playback kept its own cursor");
                c.assertTrue(seal.equals(ChordSeal.read(p.getMainHandItem())) && p.getMainHandItem().getCount() == 1, "Reusable seal survives guessing");
            }
            var neighbor = g.drain(1);
            c.assertTrue(notes(neighbor).isEmpty() && particles(neighbor, ParticleTypes.END_ROD).isEmpty(), "Clues and win are private");
            c.assertValueEqual(p.level().getBlockState(desk.pos).getValue(NoteBlock.NOTE), 8, "Note block was never tuned");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CLAY_BALL));
            desk.use();
            c.assertTrue(p.containerMenu == p.inventoryMenu && p.level().getBlockState(desk.pos).getValue(NoteBlock.NOTE) == 9, "Plain clay keeps vanilla tuning");
        }
        c.succeed();
    }

    @GameTest public void performTheMissingVoicesInBothDirections(GameTestHelper c) {
        try (var g = new Gallery(c, "Refrain"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            for (int opening = 0; opening < 3; opening++) for (int step : List.of(1, -1)) {
                var charts = opening == 1 ? List.of(KITE, KITE, KITE) : List.of(KITE, HALF_MOON, EMBER_PEAKS);
                var seal = new ChordSeal(charts, opening, step);
                p.setItemInHand(InteractionHand.MAIN_HAND, seal.item());
                p.setShiftKeyDown(true); p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
                p.setShiftKeyDown(false);
                desk.use();
                var missing = new ArrayList<Integer>();
                for (int i = 0; i < 3; i++) {
                    int lead = Math.floorMod(opening + i * step, 3);
                    int answer = Math.floorMod(lead + step, 3);
                    missing.add(3 - lead - answer);
                    choose(p, NAMES.get(missing.get(i)));
                }
                g.drain(0);
                for (boolean backward : List.of(false, true)) {
                    choose(p, backward ? "Backward refrain" : "Forward refrain");
                    c.assertTrue(notes(g.drain(0)).isEmpty(), "Choosing a direction is silent");
                    for (int i = 0; i < 3; i++) {
                        int voice = missing.get(backward ? 2 - i : i);
                        choose(p, NAMES.get((voice + 1) % 3));
                        choose(p, "Reveal next voice");
                        var menu = (InfinityJourney.Handler)p.containerMenu;
                        c.assertTrue(menu.view.title().endsWith(i + "/3") && menu.view.content().contains("Next voice: " + NAMES.get(voice)),
                            "Silent miss and hint preserve this knot");
                        c.assertTrue(menu.getSlot(backward ? 15 - i * 2 : 11 + i * 2).getItem().is(Items.TARGET), "Current knot has a distinct shape");
                        expectSounds(c, g.drain(0), seal, List.of());
                        choose(p, NAMES.get(voice));
                        var packets = g.drain(0);
                        expectSounds(c, packets, seal, i == 2 ? List.of(0,1,2) : List.of(voice));
                        c.assertValueEqual(particles(packets, ParticleTypes.END_ROD).size(), 12 * (i + 1), "One knot per correct answer");
                    }
                    c.assertTrue(((InfinityJourney.Handler)p.containerMenu).view.title().endsWith("3/3"), "Refrain complete");
                }
                choose(p, "Back to cipher");
                c.assertTrue(((InfinityJourney.Handler)p.containerMenu).view.title().equals("Seal Cipher • 3/3"), "Completed cipher is preserved");
                p.closeContainer();
                p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
                c.assertTrue(EchoChallengeGameTests.overlays(g.drain(0)).stream().anyMatch(t -> t.startsWith("Chord Seal 2/4")),
                    "Refrain leaves ordinary playback where it was");
            }
            var nearby = g.drain(1);
            c.assertTrue(notes(nearby).isEmpty() && particles(nearby, ParticleTypes.END_ROD).isEmpty(), "Performance stays private");
            c.assertValueEqual(p.level().getBlockState(desk.pos).getValue(NoteBlock.NOTE), 8, "Seal leaves note block tuned");
        }
        c.succeed();
    }

    @GameTest public void delayedRefrainResponsesCannotPlayAfterRestartOrSwap(GameTestHelper c) {
        try (var g = new Gallery(c, "Knot"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            var seal = new ChordSeal(List.of(KITE, KITE, KITE), 0, 1);
            p.setItemInHand(InteractionHand.MAIN_HAND, seal.item());
            desk.use();
            for (var name : List.of("Bell", "Chime", "Harp")) choose(p, name);
            choose(p, "Backward refrain");
            p.closeContainer();
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            var first = (SimpleForm)forms.getFirst();
            c.assertTrue(first.content().contains("Backward") && first.content().contains("Next"), "Touch form shows direction and active knot");
            var reply = new CommonsGameTests();
            g.drain(0); reply.reply(first, "1");
            expectSounds(c, g.drain(0), seal, List.of(1));
            choose(p, "Back to cipher");
            choose(p, "Forward refrain");
            c.assertTrue(((InfinityJourney.Handler)p.containerMenu).view.title().endsWith("0/3"), "Changing direction starts at the first knot");
            p.closeContainer(); reply.reply(first, "1");
            expectSounds(c, g.drain(0), seal, List.of());
            forms.clear(); InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ChordSeal(List.of(EMBER_PEAKS, KITE, HALF_MOON), 0, 1).item());
            reply.reply((SimpleForm)forms.getFirst(), "0");
            expectSounds(c, g.drain(0), seal, List.of());
            desk.use();
            c.assertTrue(((InfinityJourney.Handler)p.containerMenu).view.title().equals("Seal Cipher • 0/3"), "Fresh seal needs its own solve");
        }
        c.succeed();
    }

    @GameTest public void staleTouchAnswersAndRepeatedPitchesCannotMiscreditARound(GameTestHelper c) {
        try (var g = new Gallery(c, "Socket"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            var seal = new ChordSeal(List.of(KITE, KITE, KITE), 1, -1);
            p.setItemInHand(InteractionHand.MAIN_HAND, seal.item()); desk.use();
            expectClue(c, g.drain(0), seal, List.of(1,0));
            p.closeContainer();
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            var f = (SimpleForm)forms.getFirst();
            c.assertTrue(f.content().contains("Kite harp + Kite chime"), "Same pitch still has two named instruments");
            var reply = new CommonsGameTests();
            reply.reply(f, "2"); expectClue(c, g.drain(0), seal, List.of(0,2));
            p.closeContainer();
            reply.reply(f, "2");
            c.assertTrue(notes(g.drain(0)).isEmpty() && InfinityJourney.view(p, InfinityJourney.Page.SEAL_CIPHER).title().endsWith("1/3"),
                "Replaying the first correct form cannot resolve a second clue");
            forms.clear(); InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            var other = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1);
            p.setItemInHand(InteractionHand.MAIN_HAND, other.item()); reply.reply((SimpleForm)forms.getFirst(), "1");
            c.assertTrue(notes(g.drain(0)).isEmpty() && p.containerMenu == p.inventoryMenu, "Delayed response after a seal swap is inert");
            desk.use(); expectClue(c, g.drain(0), other, List.of(0,1));
        }
        c.succeed();
    }
}

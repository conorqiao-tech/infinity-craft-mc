package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatterns;
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

    private static String pair(int first) {
        return NAMES.get(first) + " + " + NAMES.get((first + 1) % 3);
    }

    @GameTest public void arrangeEveryKnotAsSoloAndAnswerWithTheComplement(GameTestHelper c) {
        try (var g = new Gallery(c, "Cadence"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            for (int opening = 0; opening < 3; opening++) for (int step : List.of(1, -1)) {
                var charts = opening == 0 ? List.of(KITE, KITE, KITE) : List.of(KITE, HALF_MOON, EMBER_PEAKS);
                var seal = new ChordSeal(charts, opening, step);
                p.setItemInHand(InteractionHand.MAIN_HAND, seal.item());
                p.setShiftKeyDown(true); p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
                p.setShiftKeyDown(false); desk.use();
                var missing = new ArrayList<Integer>();
                for (int i = 0; i < 3; i++) {
                    int lead = Math.floorMod(opening + i * step, 3);
                    missing.add(3 - lead - Math.floorMod(lead + step, 3));
                    choose(p, NAMES.get(missing.get(i)));
                }
                for (boolean backward : List.of(false, true)) {
                    choose(p, backward ? "Backward refrain" : "Forward refrain");
                    var order = backward ? missing.reversed() : missing;
                    for (int voice : order) choose(p, NAMES.get(voice));
                    choose(p, "Weave a cadence"); g.drain(0);
                    for (int knot = 0; knot < 3; knot++) {
                        int solo = order.get(knot), first = (solo + 1) % 3, second = (solo + 2) % 3;
                        choose(p, "Knot " + (knot + 1) + " • " + NAMES.get(solo));
                        var call = g.drain(0);
                        expectSounds(c, call, seal, List.of(solo));
                        c.assertValueEqual(particles(call, ParticleTypes.END_ROD).size(), 7, "One private call stem");
                        for (int wrong : List.of(solo, second)) {
                            choose(p, pair(wrong));
                            var miss = g.drain(0);
                            expectSounds(c, miss, seal, List.of());
                            c.assertTrue(particles(miss, ParticleTypes.END_ROD).isEmpty()
                                && ((InfinityJourney.Handler)p.containerMenu).view.content().contains(seal.voiceName(solo)),
                                "Wrong answer leaves the solo in place");
                        }
                        choose(p, pair(first));
                        var win = g.drain(0);
                        expectSounds(c, win, seal, List.of(first, second));
                        c.assertValueEqual(particles(win, ParticleTypes.END_ROD).size(), 19, "Both arms complete the fork");
                        var finished = (InfinityJourney.Handler)p.containerMenu;
                        c.assertTrue(finished.getSlot(21).getItem().is(SealCipher.ICONS.get(first))
                            && finished.getSlot(23).getItem().is(SealCipher.ICONS.get(second)), "Both answer icons are visible");
                        choose(p, "Play full chord"); expectSounds(c, g.drain(0), seal, List.of(0, 1, 2));
                        choose(p, "Weave again"); expectSounds(c, g.drain(0), seal, List.of());
                    }
                    choose(p, "Back to refrain");
                }
                p.closeContainer(); p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
                c.assertTrue(EchoChallengeGameTests.overlays(g.drain(0)).stream().anyMatch(t -> t.startsWith("Chord Seal 2/4")),
                    "Cadence preserved ordinary playback");
            }
            var observer = g.drain(1);
            c.assertTrue(notes(observer).isEmpty() && particles(observer, ParticleTypes.END_ROD).isEmpty(), "Only the performer receives the fork and music");
            c.assertValueEqual(p.level().getBlockState(desk.pos).getValue(NoteBlock.NOTE), 8, "Note block tuning preserved");
        }
        c.succeed();
    }

    private static final String WEAVE = "Weave pennant • 1 white banner";

    private static void finishBackwardRefrain(Desk desk, ChordSeal seal) {
        var p = desk.player;
        p.setItemInHand(InteractionHand.MAIN_HAND, seal.item()); desk.use();
        for (String voice : List.of("Bell", "Chime", "Harp")) choose(p, voice);
        choose(p, "Backward refrain");
        for (String voice : List.of("Harp", "Chime", "Bell")) choose(p, voice);
        choose(p, "Weave a cadence");
    }

    private static InfinityJourney.View menu(ServerPlayer p) { return ((InfinityJourney.Handler)p.containerMenu).view; }

    @GameTest public void pennantsKeepAllThreeRolesWhenPlacedAndDropped(GameTestHelper c) {
        try (var g = new Gallery(c, "Bunting"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            var support = desk.pos.west();
            var floor = p.level().getBlockState(support);
            var overhead = p.level().getBlockState(support.above());
            var wall = p.level().getBlockState(support.west());
            var underWall = p.level().getBlockState(support.west().below());
            try {
                p.level().setBlock(support, Blocks.STONE.defaultBlockState(), 2);
                p.level().setBlock(support.west().below(), Blocks.AIR.defaultBlockState(), 2);
                for (boolean unison : List.of(false, true)) {
                    var seal = new ChordSeal(unison ? List.of(KITE, KITE, KITE) : List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1);
                    var inks = unison ? List.of(DyeColor.CYAN, DyeColor.CYAN, DyeColor.CYAN) : List.of(DyeColor.CYAN, DyeColor.PURPLE, DyeColor.ORANGE);
                    finishBackwardRefrain(desk, seal);
                    for (int knot = 0; knot < 3; knot++) {
                        int solo = List.of(1, 0, 2).get(knot), left = (solo + 1) % 3, right = (solo + 2) % 3;
                        choose(p, "Knot " + (knot+1) + " • " + NAMES.get(solo));
                        c.assertTrue(menu(p).choices().stream().noneMatch(x -> x.label().equals(WEAVE)), "Answer the duet before weaving");
                        choose(p, pair(left));
                        var preview = ((InfinityJourney.Handler)p.containerMenu).getSlot(15).getItem().copy();
                        p.getInventory().setItem(8, new ItemStack(Items.BANNER.white()));
                        choose(p, WEAVE);
                        c.assertTrue(menu(p).choices().stream().noneMatch(x -> x.label().equals(WEAVE)), "Once per cadence");
                        var finished = p.getInventory().getNonEquipmentItems().stream().filter(x -> ItemStack.matches(x, preview)).findFirst().orElseThrow();
                        var layers = finished.get(DataComponents.BANNER_PATTERNS);
                        var marks = List.of(BannerPatterns.STRIPE_CENTER, BannerPatterns.STRIPE_DOWNRIGHT, BannerPatterns.CIRCLE_MIDDLE);
                        var patterns = List.of(BannerPatterns.STRIPE_LEFT, BannerPatterns.STRIPE_RIGHT, marks.get(solo));
                        var colors = List.of(inks.get(left), inks.get(right), inks.get(solo));
                        c.assertValueEqual(layers.layers().size(), 3, "Three layers on the white cloth");
                        for (int i=0; i<3; i++) {
                            var layer = layers.layers().get(i);
                            c.assertTrue(layer.pattern().is(patterns.get(i)) && layer.color() == colors.get(i), "Expected pattern and dye in layer " + i);
                        }
                        for (var face : List.of(Direction.UP, Direction.WEST)) {
                            var target = support.relative(face);
                            p.level().setBlock(target, Blocks.AIR.defaultBlockState(), 2);
                            var hit = new BlockHitResult(Vec3.atCenterOf(support).relative(face, .5), face, support, false);
                            p.setItemInHand(InteractionHand.MAIN_HAND, finished.copy());
                            p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
                            var block = p.level().getBlockState(target);
                            var entity = p.level().getBlockEntity(target);
                            c.assertTrue(block.is(face == Direction.UP ? Blocks.BANNER.white() : Blocks.WALL_BANNER.white())
                                && entity instanceof BannerBlockEntity b && b.getPatterns().equals(layers), "Placed banner keeps every layer on " + face);
                            var drops = Block.getDrops(block, p.level(), target, entity, p, ItemStack.EMPTY);
                            c.assertTrue(drops.stream().anyMatch(drop -> layers.equals(drop.get(DataComponents.BANNER_PATTERNS))), "Dropped pennant retains its design");
                            p.level().setBlock(target, Blocks.AIR.defaultBlockState(), 2);
                        }
                        p.setItemInHand(InteractionHand.MAIN_HAND, seal.item());
                        finished.setCount(0);
                        choose(p, "Weave again");
                    }
                    p.closeContainer();
                }
            } finally {
                p.level().setBlock(support.west().below(), underWall, 2);
                p.level().setBlock(support.west(), wall, 2);
                p.level().setBlock(support.above(), overhead, 2);
                p.level().setBlock(support, floor, 2);
            }
        }
        c.succeed();
    }

    @GameTest public void pennantWeavingIsAtomicAndStaleFormsCannotSpendTwice(GameTestHelper c) {
        try (var g = new Gallery(c, "Pennant"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player; var seal = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1);
            finishBackwardRefrain(desk, seal); choose(p, "Knot 1 • Harp"); choose(p, "Bell + Chime");
            var inv = p.getInventory();
            int held = inv.getSelectedSlot();
            for (int i=0; i<36; i++) if (i!=held) inv.setItem(i, new ItemStack(Items.STONE, 64));
            var named = CommonsEvents.named(Items.BANNER.white(), 1, "Keep me", "A gift");
            inv.setItem(5, named.copy()); p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BANNER.white()));
            choose(p, WEAVE);
            c.assertTrue(menu(p).content().contains("Bring a plain white banner") && ItemStack.matches(named, inv.getItem(5))
                && p.getOffhandItem().getCount()==1, "Named and offhand banners are ineligible");
            inv.setItem(6, new ItemStack(Items.BANNER.white(), 2));
            choose(p, WEAVE);
            c.assertTrue(menu(p).content().contains("Make room") && inv.getItem(6).getCount()==2, "Blocked output preserves the blank stack");
            var preview = ((InfinityJourney.Handler)p.containerMenu).getSlot(15).getItem().copy();
            inv.setItem(7, preview.copy());
            p.closeContainer();
            var forms = new ArrayList<Form>(); InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            var f = (SimpleForm)forms.getFirst();
            int button = 0;
            while (!f.buttons().get(button).text().equals(WEAVE)) button++;
            var reply = new CommonsGameTests(); g.drain(0); reply.reply(f, Integer.toString(button));
            expectSounds(c, g.drain(0), seal, List.of());
            c.assertTrue(inv.getItem(7).getCount()==2 && inv.getItem(6).getCount()==1 && ItemStack.matches(inv.getItem(5),named),
                "Existing matching pennant stack accepts one result without dropping anything");
            p.closeContainer(); reply.reply(f, Integer.toString(button));
            c.assertTrue(inv.getItem(7).getCount()==2 && inv.getItem(6).getCount()==1, "Replayed touch answer cannot weave again");
            finishBackwardRefrain(desk, seal); choose(p, "Knot 1 • Harp"); choose(p, "Bell + Chime");
            choose(p, WEAVE);
            c.assertTrue(inv.getItem(6).isEmpty() && inv.getItem(7).getCount() == 3,
                "Consuming the last blank clears its slot when the result merges elsewhere");
            inv.setItem(6, new ItemStack(Items.BANNER.white()));
            p.closeContainer();
            finishBackwardRefrain(desk, seal); choose(p, "Knot 2 • Chime"); choose(p, "Harp + Bell");
            choose(p,WEAVE);
            c.assertTrue(inv.getItem(6).is(Items.BANNER.white()) && inv.getItem(6).has(DataComponents.CUSTOM_NAME), "A singleton blank can supply its own output slot");
        }
        c.succeed();
    }

    @GameTest public void staleCadenceAnswerCannotResolveTwiceOrAfterASealSwap(GameTestHelper c) {
        try (var g = new Gallery(c, "Fork"); var desk = Desk.place(g.players.getFirst())) {
            var p = desk.player;
            var seal = new ChordSeal(List.of(KITE, KITE, KITE), 0, 1);
            p.setItemInHand(InteractionHand.MAIN_HAND, seal.item()); desk.use();
            for (String voice : List.of("Bell", "Chime", "Harp")) choose(p, voice);
            choose(p, "Backward refrain");
            for (String voice : List.of("Harp", "Chime", "Bell")) choose(p, voice);
            choose(p, "Weave a cadence"); choose(p, "Knot 2 • Chime");
            choose(p, "Choose another solo"); choose(p, "Knot 1 • Harp");
            p.closeContainer(); g.drain(0);
            var forms = new ArrayList<Form>(); var reply = new CommonsGameTests();
            InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            var answer = (SimpleForm)forms.getFirst();
            c.assertTrue(answer.content().contains("Kite harp"), "Touch form names the solo even at unison");
            reply.reply(answer, "2"); expectSounds(c, g.drain(0), seal, List.of(2, 0));
            p.closeContainer(); reply.reply(answer, "2"); expectSounds(c, g.drain(0), seal, List.of());
            forms.clear(); InfinityJourney.form(p, InfinityJourney.Page.SEAL_CIPHER, forms::add);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ChordSeal(List.of(EMBER_PEAKS, HALF_MOON, KITE), 0, 1).item());
            reply.reply((SimpleForm)forms.getFirst(), "0"); expectSounds(c, g.drain(0), seal, List.of());
            desk.use();
            c.assertTrue(((InfinityJourney.Handler)p.containerMenu).view.title().endsWith("0/3"), "New seal starts at its first clue");
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

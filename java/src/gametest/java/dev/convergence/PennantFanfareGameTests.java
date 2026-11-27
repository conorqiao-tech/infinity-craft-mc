package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static dev.convergence.StarLoom.Chart.*;

public class PennantFanfareGameTests {
    private record Stand(ServerPlayer owner, BlockPos pos, BlockState previous) implements AutoCloseable {
        static Stand at(ServerPlayer p, int dx) {
            var pos = p.blockPosition().offset(dx, 1, 1);
            return new Stand(p, pos, p.level().getBlockState(pos));
        }

        BannerBlockEntity mount(ItemStack flag, boolean wall) {
            owner.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            owner.level().setBlock(pos, (wall ? Blocks.WALL_BANNER.white() : Blocks.BANNER.white()).defaultBlockState(), 2);
            var banner = (BannerBlockEntity) owner.level().getBlockEntity(pos);
            banner.applyComponentsFromItemStack(flag);
            return banner;
        }

        void tap(ServerPlayer p) {
            var hand = InteractionHand.MAIN_HAND;
            var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
            p.gameMode.useItemOn(p, p.level(), p.getItemInHand(hand), hand, hit);
        }

        @Override public void close() { owner.level().setBlock(pos, previous, 2); }
    }

    @GameTest public void readAllPennantScoresAndRejectLookalikes(GameTestHelper c) {
        try (var gallery = new EncoreDuetGameTests.Gallery(c, "Dyes")) {
            var p = gallery.players.getFirst();
            var white = new BannerBlockEntity(BlockPos.ZERO, Blocks.BANNER.white().defaultBlockState());
            for (var chime : values()) for (var harp : values()) for (var bell : values()) {
                var seal = new ChordSeal(List.of(chime, harp, bell), 0, 1);
                for (int solo=0; solo<3; solo++) {
                    var item = CadencePennant.banner(p, seal, solo);
                    var cloth = new ItemStack(Items.BANNER.white());
                    cloth.set(DataComponents.BANNER_PATTERNS, item.get(DataComponents.BANNER_PATTERNS));
                    white.applyComponentsFromItemStack(cloth);
                    var score = CadencePennant.read(white);
                    c.assertTrue(score != null && score.solo() == solo && score.call() == seal.voices().get(solo)
                        && score.left() == seal.voices().get((solo+1)%3) && score.right() == seal.voices().get((solo+2)%3),
                        "Three pattern layers alone identify the solo and both pitches");
                }
            }
            var item = CadencePennant.banner(p, new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1), 1);
            var layers = item.get(DataComponents.BANNER_PATTERNS).layers();
            var l = layers.get(0); var r = layers.get(1); var mark = layers.get(2);
            var blue = new BannerBlockEntity(BlockPos.ZERO, Blocks.BANNER.blue().defaultBlockState());
            blue.applyComponentsFromItemStack(item);
            c.assertTrue(CadencePennant.read(blue) == null, "Reject a different base color");
            for (var invalid : List.of(List.of(r,l,mark), List.of(l,r,l), List.of(l,r), List.of(l,r,mark,l))) {
                item.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(invalid));
                white.applyComponentsFromItemStack(item);
                c.assertTrue(CadencePennant.read(white) == null, "Reject swapped, unknown, missing and extra layers");
            }
            for (int i=0; i<3; i++) {
                var invalid = new ArrayList<>(layers);
                invalid.set(i, new BannerPatternLayers.Layer(layers.get(i).pattern(), DyeColor.GREEN));
                item.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(invalid));
                white.applyComponentsFromItemStack(item);
                c.assertTrue(CadencePennant.read(white) == null, "Reject an unrecognized ink in any layer");
            }
        }
        c.succeed();
    }

    private static void beat(GameTestHelper c, EncoreDuetGameTests.Gallery gallery, int listener, Stand flag,
                             ChordSeal seal, List<Integer> voices) {
        flag.tap(gallery.players.get(listener));
        var packets = gallery.drain(listener);
        SealCipherGameTests.expectSounds(c, packets, seal, voices);
        c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.END_ROD).size(), voices.size()*9,
            "One stem or two answer branches");
        if (!voices.isEmpty()) {
            String caption = String.join(" + ", voices.stream().map(seal::voiceName).toList());
            c.assertTrue(EchoChallengeGameTests.overlays(packets).stream().anyMatch(x -> x.contains(caption)), "Text names the actual instruments and charts");
        }
    }

    private static void perform(EncoreDuetGameTests.Gallery g, int listener, Stand stand) {
        var player = g.players.get(listener);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
        stand.tap(player);
        stand.tap(player);
        g.drain(listener);
    }

    private static void paper(GameTestHelper c, EncoreDuetGameTests.Gallery g, int listener, Stand stand,
                              ChordSeal chord, int count) {
        var player = g.players.get(listener);
        var sheets = new ItemStack(Items.PAPER, 4);
        player.setItemInHand(InteractionHand.MAIN_HAND, sheets);
        stand.tap(player);
        var packets = g.drain(listener);
        SealCipherGameTests.expectSounds(c, packets, chord, count == 3 ? List.of(0, 1, 2) : List.of());
        var corners = GalleryTrailGameTests.particles(packets, ParticleTypes.GLOW);
        c.assertValueEqual(corners.size(), count,
            "One private light per collected role");
        c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.END_ROD).size(), count == 3 ? 15 : 0,
            "Only the full trio connects its corners");
        c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.WAX_ON).size(), count == 0 ? 0 : 3 - count,
            "Empty instrument sockets are visible");
        if (count == 3) {
            var chime = corners.get(0); var harp = corners.get(1); var bell = corners.get(2);
            double yaw = Math.toRadians(player.getYRot());
            var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
            c.assertTrue(chime.y > harp.y && harp.subtract(bell).dot(right) > .6,
                "The guide and flag both show chime above, harp on the right and bell on the left");
        }
        String prompt = count == 0 ? "feather first" : "Chorus " + count + "/3";
        c.assertTrue(EchoChallengeGameTests.overlays(packets).stream().anyMatch(x -> x.contains(prompt)), "Shows " + prompt);
        c.assertValueEqual(sheets.getCount(), 4, "Paper is reusable");
    }

    @GameTest public void collectSoloPitchesFromDifferentPennants(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Chorus");
             var first = Stand.at(g.players.getFirst(), 0); var second = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst();
            var low = new ChordSeal(List.of(KITE, KITE, KITE), 0, 1);
            var high = new ChordSeal(List.of(EMBER_PEAKS, EMBER_PEAKS, EMBER_PEAKS), 0, 1);
            var middle = new ChordSeal(List.of(HALF_MOON, HALF_MOON, HALF_MOON), 0, 1);
            var expected = new ChordSeal(List.of(EMBER_PEAKS, KITE, HALF_MOON), 0, 1);
            var harp = CadencePennant.banner(p, low, 1);
            first.mount(harp, false);
            second.mount(harp, true);
            paper(c, g, 0, first, expected, 0);
            perform(g, 0, first);
            paper(c, g, 0, second, expected, 0);
            first.mount(harp, false);
            paper(c, g, 0, first, expected, 0);
            perform(g, 0, first);
            var cloth = (BannerBlockEntity)p.level().getBlockEntity(first.pos());
            cloth.applyComponentsFromItemStack(CadencePennant.banner(p, high, 1));
            paper(c, g, 0, first, expected, 0);
            cloth.applyComponentsFromItemStack(harp);
            paper(c, g, 0, first, expected, 0);
            perform(g, 0, first);
            p.setShiftKeyDown(true);
            first.tap(p);
            p.setShiftKeyDown(false);
            g.drain(0);
            paper(c, g, 0, first, expected, 0);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
            first.tap(p);
            g.drain(0);
            paper(c, g, 1, first, expected, 0);
            paper(c, g, 0, first, expected, 1);
            paper(c, g, 0, first, expected, 0);
            second.mount(CadencePennant.banner(p, low, 0), true);
            perform(g, 0, second);
            paper(c, g, 0, second, expected, 2);
            perform(g, 1, second);
            paper(c, g, 1, second, expected, 1);
            second.mount(CadencePennant.banner(p, high, 0), true);
            perform(g, 0, second);
            paper(c, g, 0, second, expected, 2);
            first.mount(CadencePennant.banner(p, middle, 2), false);
            perform(g, 0, first);
            paper(c, g, 0, first, expected, 3);
            paper(c, g, 0, first, expected, 0);
            perform(g, 0, first);
            paper(c, g, 0, first, expected, 1);
            var spectator = g.drain(2);
            SealCipherGameTests.expectSounds(c, spectator, expected, List.of());
            c.assertTrue(GalleryTrailGameTests.particles(spectator, ParticleTypes.GLOW).isEmpty(), "Bystander sees no collected solos");
        }
        c.succeed();
    }

    private static void conduct(GameTestHelper c, EncoreDuetGameTests.Gallery g, int listener, Stand stand,
                                ChordSeal expected, boolean sneak, List<Integer> notes, String caption) {
        var player = g.players.get(listener);
        var stick = new ItemStack(Items.STICK, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, stick);
        player.setShiftKeyDown(sneak);
        stand.tap(player);
        player.setShiftKeyDown(false);
        var packets = g.drain(listener);
        SealCipherGameTests.expectSounds(c, packets, expected, notes);
        c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.GLOW).size(), notes.size(),
            "Each sounding instrument lights its corner");
        var captions = EchoChallengeGameTests.overlays(packets);
        if (notes.isEmpty()) {
            c.assertTrue(captions.stream().noneMatch(x -> x.contains("Chorus Baton")), "A different flag or listener cannot advance the baton");
        } else {
            c.assertTrue(captions.stream().anyMatch(x -> x.contains(caption)), "Baton direction and count are readable");
        }
        c.assertValueEqual(stick.getCount(), 3, "Conducting preserves the stick");
    }

    private static void ring(GameTestHelper c, EncoreDuetGameTests.Gallery g, int listener, Stand flag,
                             ChordSeal chord, List<Integer> expected) {
        var p = g.players.get(listener);
        var shards = new ItemStack(Items.AMETHYST_SHARD, 3);
        p.setItemInHand(InteractionHand.MAIN_HAND, shards);
        flag.tap(p);
        var packets = g.drain(listener);
        SealCipherGameTests.expectSounds(c, packets, chord, expected);
        var knot = GalleryTrailGameTests.particles(packets, ParticleTypes.ELECTRIC_SPARK);
        var tips = GalleryTrailGameTests.particles(packets, ParticleTypes.END_ROD);
        c.assertValueEqual(knot.size(), expected.isEmpty() ? 0 : 1, "Only an unlocked loop rings at its crossing");
        c.assertValueEqual(tips.size(), expected.size(), "Both outer tips flare for the duet");
        if (!expected.isEmpty()) {
            c.assertTrue(tips.getFirst().distanceTo(tips.getLast()) > 1.0
                && tips.getFirst().lerp(tips.getLast(), .5).distanceTo(knot.getFirst()) < .001,
                "The two tips frame the central spark");
        }
        c.assertValueEqual(shards.getCount(), 3, "The shard is reusable");
    }

    @GameTest public void ringOuterVoicesAfterBothBatonDirections(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Loop");
             var finish = Stand.at(g.players.getFirst(), 0); var other = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst();
            var chord = new ChordSeal(List.of(HALF_MOON, EMBER_PEAKS, HALF_MOON), 0, 1);
            var low = new ChordSeal(List.of(KITE, KITE, KITE), 0, 1);
            var finishDesign = CadencePennant.banner(p, new ChordSeal(List.of(KITE, KITE, HALF_MOON), 0, 1), 2);
            var joined = List.of(1, 0, 1, 2);
            for (int i = 0; i < joined.size(); i++) {
                var flag = i == 3 ? finish : other;
                flag.mount(i == 3 ? finishDesign : CadencePennant.banner(p, i == 0 ? low : chord, joined.get(i)), i == 3);
                perform(g, 0, flag);
                paper(c, g, 0, flag, chord, List.of(1, 2, 2, 3).get(i));
            }
            other.mount(finishDesign, false);
            for (boolean backward : List.of(true, true, false)) {
                var order = backward ? List.of(2, 0, 1) : List.of(1, 0, 2);
                for (int i = 0; i < 3; i++) {
                    conduct(c, g, 0, finish, chord, backward, List.of(order.get(i)), (i + 1) + "/4");
                }
                ring(c, g, 0, finish, chord, List.of());
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                finish.tap(p);
                var packets = g.drain(0);
                SealCipherGameTests.expectSounds(c, packets, chord, List.of(0, 1, 2));
                c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.WAX_ON).size(),
                    backward ? 3 : 0, "An unearned lobe has three guide dots");
                c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.ELECTRIC_SPARK).size(),
                    backward ? 0 : 1, "A repeated direction leaves the crossing dark");
            }
            conduct(c, g, 0, finish, chord, true, List.of(2), "Backward 1/4");
            conduct(c, g, 0, finish, chord, false, List.of(0), "Backward 2/4");
            ring(c, g, 1, finish, chord, List.of());
            ring(c, g, 0, other, chord, List.of());
            ring(c, g, 0, finish, chord, List.of(1, 2));
            ring(c, g, 0, finish, chord, List.of(1, 2));
            conduct(c, g, 0, finish, chord, false, List.of(1), "Backward 3/4");
            conduct(c, g, 0, finish, chord, false, List.of(0, 1, 2), "Backward 4/4");
            perform(g, 0, other);
            ring(c, g, 0, finish, chord, List.of(1, 2));
            paper(c, g, 0, other, chord, 1);
            ring(c, g, 0, finish, chord, List.of());
            var visitor = g.drain(2);
            SealCipherGameTests.expectSounds(c, visitor, chord, List.of());
            c.assertTrue(GalleryTrailGameTests.particles(visitor, ParticleTypes.ELECTRIC_SPARK).isEmpty(),
                "The completed loop belongs to its conductor");
        }
        c.succeed();
    }

    @GameTest public void conductCollectedSolosInBothDirections(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Baton");
             var finish = Stand.at(g.players.getFirst(), 0); var other = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst();
            var expected = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1);
            var low = new ChordSeal(List.of(KITE, KITE, KITE), 0, 1);
            var middle = new ChordSeal(List.of(HALF_MOON, HALF_MOON, HALF_MOON), 0, 1);
            var flag = CadencePennant.banner(p, middle, 1);
            finish.mount(flag, true);
            for (int i = 0; i < 4; i++) {
                int role = List.of(2, 0, 2, 1).get(i);
                var stand = i == 3 ? finish : other;
                if (stand == other) stand.mount(CadencePennant.banner(p, i == 2 ? expected : low, role), false);
                perform(g, 0, stand);
                paper(c, g, 0, stand, expected, List.of(1, 2, 2, 3).get(i));
            }
            other.mount(flag, false);
            conduct(c, g, 1, finish, expected, false, List.of(), "");
            for (boolean backward : List.of(false, true)) {
                var order = backward ? List.of(1, 0, 2) : List.of(2, 0, 1);
                for (int i = 0; i < 4; i++) {
                    conduct(c, g, 0, other, expected, false, List.of(), "");
                    var notes = i == 3 ? List.of(0, 1, 2) : List.of(order.get(i));
                    conduct(c, g, 0, finish, expected, i == 0 ? backward : !backward, notes,
                        (backward ? "Backward " : "Forward ") + (i + 1) + "/4");
                }
            }
            // Start a fresh phrase, then collect a solo toward a new trio.
            conduct(c, g, 0, finish, expected, false, List.of(2), "Forward 1/4");
            perform(g, 0, other);
            paper(c, g, 0, other, expected, 1);
            conduct(c, g, 0, finish, expected, false, List.of(), "");
            for (int role : List.of(0, 2)) {
                finish.mount(CadencePennant.banner(p, expected, role), true);
                perform(g, 0, finish);
                paper(c, g, 0, finish, expected, role == 0 ? 2 : 3);
            }
            conduct(c, g, 0, finish, expected, false, List.of(1), "Forward 1/4");
            finish.mount(CadencePennant.banner(p, expected, 2), true);
            conduct(c, g, 0, finish, expected, false, List.of(), "");
            for (int role : List.of(1, 0, 2)) {
                var stand = role == 2 ? finish : other;
                stand.mount(CadencePennant.banner(p, expected, role), false);
                perform(g, 0, stand);
                paper(c, g, 0, stand, expected, role == 1 ? 1 : role == 0 ? 2 : 3);
            }
            conduct(c, g, 0, finish, expected, true, List.of(2), "Backward 1/4");
            var cloth = (BannerBlockEntity) p.level().getBlockEntity(finish.pos());
            cloth.applyComponentsFromItemStack(CadencePennant.banner(p, low, 2));
            conduct(c, g, 0, finish, expected, false, List.of(), "");
            cloth.applyComponentsFromItemStack(CadencePennant.banner(p, expected, 2));
            conduct(c, g, 0, finish, expected, false, List.of(), "");
            var visitor = g.drain(2);
            SealCipherGameTests.expectSounds(c, visitor, expected, List.of());
            c.assertTrue(GalleryTrailGameTests.particles(visitor, ParticleTypes.GLOW).isEmpty(), "Baton visuals stay private");
        }
        c.succeed();
    }

    @GameTest public void featherPlaysPrivateCallAndAnswerOnTheSamePlacedFlag(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Fanfare");
             var stand = Stand.at(g.players.getFirst(), 0); var wall = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst(); var guest = g.players.get(1);
            var seal = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 1, -1);
            var flag = CadencePennant.banner(p, seal, 1);
            var cloth = stand.mount(flag, false); wall.mount(flag, true);
            var feather = new ItemStack(Items.FEATHER, 3);
            p.setItemInHand(InteractionHand.MAIN_HAND, feather);
            guest.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
            var harp = List.of(1); var duet = List.of(2,0);
            beat(c,g,0,stand,seal,harp);
            beat(c,g,1,stand,seal,harp);
            beat(c,g,0,wall,seal,harp);
            beat(c,g,1,stand,seal,duet);
            beat(c,g,0,wall,seal,duet);
            beat(c,g,0,stand,seal,harp);
            p.setShiftKeyDown(true); beat(c,g,0,stand,seal,harp); p.setShiftKeyDown(false);
            beat(c,g,0,stand,seal,duet);
            beat(c,g,0,stand,seal,harp);
            cloth.applyComponentsFromItemStack(CadencePennant.banner(p, seal, 0));
            beat(c,g,0,stand,seal,List.of(0));
            stand.mount(CadencePennant.banner(p, seal, 0),false);
            beat(c,g,0,stand,seal,List.of(0));
            beat(c,g,0,stand,seal,List.of(1,2));
            beat(c,g,0,stand,seal,List.of(0));
            var current = (BannerBlockEntity)p.level().getBlockEntity(stand.pos());
            current.applyComponentsFromItemStack(new ItemStack(Items.BANNER.white()));
            beat(c,g,0,stand,seal,List.of());
            current.applyComponentsFromItemStack(CadencePennant.banner(p, seal, 0));
            beat(c,g,0,stand,seal,List.of(0));
            var unison = new ChordSeal(List.of(KITE, KITE, KITE), 0, 1);
            stand.mount(CadencePennant.banner(p, unison, 2), false);
            beat(c, g, 0, stand, unison, List.of(2));
            beat(c, g, 0, stand, unison, List.of(0, 1));
            p.setItemInHand(InteractionHand.MAIN_HAND, CommonsEvents.named(Items.FEATHER, 1, "My quill"));
            beat(c,g,0,stand,seal,List.of());
            c.assertValueEqual(feather.getCount(),3,"Performance does not spend feathers");
            var bystander = g.drain(2);
            SealCipherGameTests.expectSounds(c,bystander,seal,List.of());
            c.assertTrue(GalleryTrailGameTests.particles(bystander,ParticleTypes.END_ROD).isEmpty(), "Fanfare is private");
        }
        c.succeed();
    }
}

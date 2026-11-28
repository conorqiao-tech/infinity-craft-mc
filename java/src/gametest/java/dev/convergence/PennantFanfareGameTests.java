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
            return at(p, pos);
        }

        static Stand at(ServerPlayer p, BlockPos pos) {
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

    private static void inlay(GameTestHelper c, EncoreDuetGameTests.Gallery g, int listener, Stand flag,
                              ChordSeal chord, List<Integer> notes, String feedback) {
        var player = g.players.get(listener);
        var dust = new ItemStack(Items.GLOWSTONE_DUST, 4);
        player.setItemInHand(InteractionHand.MAIN_HAND, dust);
        flag.tap(player);
        var packets = g.drain(listener);
        SealCipherGameTests.expectSounds(c, packets, chord, notes);
        c.assertValueEqual(dust.getCount(), 4, "Guessing preserves the dust");
        var tips = GalleryTrailGameTests.particles(packets, ParticleTypes.WAX_OFF);
        c.assertValueEqual(tips.size(), notes.isEmpty() ? 0 : 4, "Only a correct answer sets the four diamond tips");
        if (!feedback.isEmpty()) {
            c.assertTrue(EchoChallengeGameTests.overlays(packets).stream().anyMatch(s -> s.contains(feedback)),
                "The answer has readable feedback");
        }
        if (feedback.startsWith("Right instrument") || feedback.startsWith("Find the instrument")) {
            c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.WAX_ON).size(), 4,
                "A missed guess leaves an open four-dot socket");
        }
    }

    @GameTest public void inlayMatchesMiddleRoleAndReplacedPitchOncePerRing(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Inlay");
             var finish = Stand.at(g.players.getFirst(), 0); var guess = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst();
            var old = new ChordSeal(List.of(HALF_MOON, HALF_MOON, HALF_MOON), 0, 1);
            var chord = new ChordSeal(List.of(EMBER_PEAKS, EMBER_PEAKS, KITE), 0, 1);
            var roles = List.of(1, 0, 0, 1, 2);
            var counts = List.of(1, 2, 2, 2, 3);
            for (int i = 0; i < roles.size(); i++) {
                var flag = i == 4 ? finish : guess;
                flag.mount(CadencePennant.banner(p, i < 2 ? old : chord, roles.get(i)), i == 4);
                perform(g, 0, flag);
                paper(c, g, 0, flag, chord, counts.get(i));
            }
            for (boolean backward : List.of(false, true)) {
                var order = backward ? List.of(2, 0, 1) : List.of(1, 0, 2);
                for (int i = 0; i < 4; i++) conduct(c, g, 0, finish, chord, backward,
                    i == 3 ? List.of(0, 1, 2) : List.of(order.get(i)), (i + 1) + "/4");
            }
            var correct = CadencePennant.banner(p, chord, 0);
            guess.mount(correct, false);
            inlay(c, g, 0, guess, chord, List.of(), "");
            ring(c, g, 0, finish, chord, List.of(1, 2));
            conduct(c, g, 0, finish, chord, true, List.of(2), "Backward 1/4");
            guess.mount(CadencePennant.banner(p, chord, 1), true);
            inlay(c, g, 0, guess, chord, List.of(), "Find the instrument");
            guess.mount(CadencePennant.banner(p, old, 0), false);
            inlay(c, g, 0, guess, chord, List.of(), "Right instrument");
            guess.mount(correct, false);
            inlay(c, g, 1, guess, chord, List.of(), "");
            inlay(c, g, 0, guess, chord, List.of(1, 0, 2), "Diamond set");
            inlay(c, g, 0, guess, chord, List.of(), "");
            conduct(c, g, 0, finish, chord, false, List.of(0), "Backward 2/4");
            ring(c, g, 0, finish, chord, List.of(1, 2));
            inlay(c, g, 0, guess, chord, List.of(1, 0, 2), "Diamond set");
            ring(c, g, 0, finish, chord, List.of(1, 2));
            finish.mount(CadencePennant.banner(p, chord, 2), true);
            inlay(c, g, 0, guess, chord, List.of(), "finishing flag is gone");
            c.assertTrue(GalleryTrailGameTests.particles(g.drain(2), ParticleTypes.WAX_OFF).isEmpty(),
                "Nearby players receive no diamond");
        }
        c.succeed();
    }

    @GameTest public void cutOnePrismAndPlayItsGiftedUnisonFacets(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Prism");
             var finish = Stand.at(g.players.getFirst(), 0); var other = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst();
            var song = new ChordSeal(List.of(KITE, KITE, KITE), 2, -1);
            for (int i = 0; i < 3; i++) {
                var flag = i == 2 ? finish : other;
                flag.mount(CadencePennant.banner(p, song, song.cornerAt(i)), i == 2);
                perform(g, 0, flag);
                paper(c, g, 0, flag, song, i + 1);
            }
            for (boolean backward : List.of(true, false)) {
                for (int i = 0; i < 4; i++) conduct(c, g, 0, finish, song, backward,
                    i == 3 ? List.of(0, 1, 2) : List.of(song.cornerAt(backward ? 2 - i : i)), (i + 1) + "/4");
            }
            ring(c, g, 0, finish, song, List.of(2, 0));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.QUARTZ));
            finish.tap(p);
            c.assertTrue(InlayPrism.read(p.getMainHandItem()) == null, "A rung but unsolved loop cannot be cut");
            other.mount(CadencePennant.banner(p, song, 1), false);
            inlay(c, g, 0, other, song, List.of(2, 1, 0), "Diamond set");
            var inv = p.getInventory();
            int free = (inv.getSelectedSlot() + 1) % 9;
            for (int slot = 0; slot < 36; slot++) inv.setItem(slot, new ItemStack(Items.STONE, 64));
            var quartz = new ItemStack(Items.QUARTZ, 2);
            p.setItemInHand(InteractionHand.MAIN_HAND, quartz);
            finish.tap(p);
            c.assertTrue(ItemStack.matches(p.getMainHandItem(), new ItemStack(Items.QUARTZ, 2)), "No room preserves both quartz");
            inv.setItem(free, ItemStack.EMPTY);
            other.tap(p);
            c.assertTrue(inv.getItem(free).isEmpty() && InlayPrism.read(p.getMainHandItem()) == null,
                "The answering flag cannot cut a prism");
            finish.tap(p);
            var prism = p.getMainHandItem();
            c.assertValueEqual(InlayPrism.read(prism), new InlayPrism(song), "The item encodes the unison route");
            c.assertTrue(ItemStack.matches(inv.getItem(free), new ItemStack(Items.QUARTZ)), "One ordinary quartz remains");
            var ops = p.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
            var gift = ItemStack.CODEC.parse(ops, ItemStack.CODEC.encodeStart(ops, prism).getOrThrow()).getOrThrow();
            p.setItemInHand(InteractionHand.MAIN_HAND, inv.removeItemNoUpdate(free));
            finish.tap(p);
            c.assertTrue(ItemStack.matches(p.getMainHandItem(), new ItemStack(Items.QUARTZ)), "Only one cut per correct answer");
            ring(c, g, 0, finish, song, List.of(2, 0));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.QUARTZ));
            finish.tap(p);
            c.assertTrue(InlayPrism.read(p.getMainHandItem()) == null, "Ringing alone does not renew the cut");
            inlay(c, g, 0, other, song, List.of(2, 1, 0), "Diamond set");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.QUARTZ));
            finish.tap(p);
            c.assertValueEqual(InlayPrism.read(p.getMainHandItem()), new InlayPrism(song), "A fresh solve allows a second cut");
            var changed = new ChordSeal(List.of(EMBER_PEAKS, EMBER_PEAKS, EMBER_PEAKS), 1, 1);
            other.mount(CadencePennant.banner(p, changed, 2), false);
            perform(g, 0, other);
            paper(c, g, 0, other, changed, 1);
            var friend = g.players.get(1);
            friend.setItemInHand(InteractionHand.MAIN_HAND, gift);
            for (boolean right : List.of(false, true, true, false)) {
                friend.setShiftKeyDown(right);
                friend.gameMode.useItem(friend, friend.level(), gift, InteractionHand.MAIN_HAND);
                var packets = g.drain(1);
                SealCipherGameTests.expectSounds(c, packets, song, right ? List.of(1, 0) : List.of(2, 1));
                var lights = GalleryTrailGameTests.particles(packets, ParticleTypes.GLOW);
                c.assertValueEqual(lights.size(), 2, "Only the sounding pair lights up");
                c.assertValueEqual(GalleryTrailGameTests.particles(packets, ParticleTypes.WAX_OFF).size(), 4,
                    "The central inlay diamond is visible on either facet");
                c.assertTrue(EchoChallengeGameTests.overlays(packets).stream().anyMatch(s -> s.contains(right ? "Right facet" : "Left facet")),
                    "The caption distinguishes unison facets");
                c.assertTrue(ItemStack.matches(gift, friend.getMainHandItem()), "Playback preserves the gift");
            }
            var visitor = g.drain(2);
            SealCipherGameTests.expectSounds(c, visitor, song, List.of());
            c.assertTrue(GalleryTrailGameTests.particles(visitor, ParticleTypes.GLOW).isEmpty(), "Prism playback is private");
        }
        c.succeed();
    }

    @GameTest public void crossUnisonPrismBothWaysWithoutRepeatingTheTriad(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Cross"); var block = Stand.at(g.players.getFirst(), 1)) {
            var p = g.players.getFirst();
            p.level().setBlock(block.pos, Blocks.AMETHYST_BLOCK.defaultBlockState(), 2);
            var score = new ChordSeal(List.of(KITE, KITE, KITE), 2, -1); // Bell, harp, chime
            var item = new InlayPrism(score).item();
            p.setItemInHand(InteractionHand.MAIN_HAND, item);
            for (boolean reverse : List.of(false, true)) {
                p.setShiftKeyDown(reverse);
                p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(block.pos));
                block.tap(p);
                // A vanilla right click can send both block and item use.
                p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                var start = g.drain(0);
                SealCipherGameTests.expectSounds(c, start, score, List.of());
                String first = score.voiceName(reverse ? 0 : 2), last = score.voiceName(reverse ? 2 : 0);
                c.assertTrue(EchoChallengeGameTests.overlays(start).stream().anyMatch(s -> s.contains(first + " then " + last)),
                    "Both clues follow the chosen direction, even at unison");

                p.setYRot(0); p.setXRot(-90); // Answer away from the amethyst.
                for (int step = 0; step < 2; step++) {
                    boolean right = (step == 0) == reverse;
                    p.setShiftKeyDown(!right);
                    p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                    var miss = g.drain(0);
                    SealCipherGameTests.expectSounds(c, miss, score, List.of());
                    c.assertTrue(EchoChallengeGameTests.overlays(miss).stream().anyMatch(s -> s.contains("Find " + (right ? score.voiceName(0) : score.voiceName(2)))),
                        "A miss keeps the same named target");
                    p.setShiftKeyDown(right);
                    p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                    var match = g.drain(0);
                    SealCipherGameTests.expectSounds(c, match, score, step == 1 ? List.of(0,1,2) : right ? List.of(1,0) : List.of(2,1));
                    c.assertValueEqual(GalleryTrailGameTests.particles(match, ParticleTypes.END_ROD).size(),
                        step == 0 ? 9 : 17, "One diagonal becomes two on completion");
                    c.assertValueEqual(GalleryTrailGameTests.particles(match, ParticleTypes.GLOW).size(),
                        step == 0 ? 0 : 1, "Only the closing chord lights the center star");
                }
                p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, reverse ? List.of(2,1) : List.of(1,0));
            }
            block.tap(p);
            var other = new ChordSeal(List.of(KITE, HALF_MOON, EMBER_PEAKS), 0, 1);
            var swapped = new InlayPrism(other).item();
            p.setItemInHand(InteractionHand.MAIN_HAND, swapped);
            p.setShiftKeyDown(false);
            p.gameMode.useItem(p, p.level(), swapped, InteractionHand.MAIN_HAND);
            SealCipherGameTests.expectSounds(c, g.drain(0), other, List.of(0,1));
            p.setItemInHand(InteractionHand.MAIN_HAND, item);
            p.setShiftKeyDown(true);
            p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
            SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of(1,0));
            c.assertTrue(p.level().getBlockState(block.pos).is(Blocks.AMETHYST_BLOCK), "The station is reusable");
            var visitor = g.drain(2);
            SealCipherGameTests.expectSounds(c, visitor, score, List.of());
            c.assertTrue(GalleryTrailGameTests.particles(visitor, ParticleTypes.GLOW).isEmpty(), "Private completion star");
        }
        c.succeed();
    }

    @GameTest public void walkLanternsInCrossingOrderWithPairwiseRange(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Walk"); var station = Stand.at(g.players.getFirst(), 1);
             var arch = Stand.at(station.owner, station.pos.offset(0, 0, 3));
             var pool = Stand.at(station.owner, arch.pos.offset(8, 0, 3));
             var far = Stand.at(station.owner, arch.pos.offset(12, 0, 1));
             var edge = Stand.at(station.owner, arch.pos.offset(12, 0, 0))) {
            var p = station.owner;
            p.level().setBlock(station.pos, Blocks.AMETHYST_BLOCK.defaultBlockState(), 2);
            for (var post : List.of(arch, pool, far, edge))
                p.level().setBlock(post.pos, Blocks.LANTERN.defaultBlockState(), 2);
            var score = new ChordSeal(List.of(KITE, KITE, KITE), 2, -1);
            var item = new InlayPrism(score).item();
            p.setItemInHand(InteractionHand.MAIN_HAND, item);
            for (boolean reverse : List.of(false, true)) {
                p.setPos(Vec3.atBottomCenterOf(station.pos).add(0, -1, -1));
                p.setShiftKeyDown(reverse);
                station.tap(p);
                arch.tap(p);
                var locked = g.drain(0);
                SealCipherGameTests.expectSounds(c, locked, score, List.of());
                c.assertTrue(EchoChallengeGameTests.overlays(locked).stream().anyMatch(t -> t.contains("Finish a Prism Crossing")),
                    "Starting a new crossing clears any previous walk");
                p.setXRot(-90);
                p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                p.setShiftKeyDown(!reverse);
                p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                g.drain(0);
                var visitor = g.players.get(2);
                visitor.setItemInHand(InteractionHand.MAIN_HAND, item.copy());
                arch.tap(visitor);
                SealCipherGameTests.expectSounds(c, g.drain(2), score, List.of());

                var posts = List.of(arch, pool, edge);
                for (int i = 0; i < posts.size(); i++) {
                    var post = posts.get(i);
                    p.setPos(Vec3.atBottomCenterOf(post.pos).add(0, -1, -1));
                    p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(post.pos));
                    post.tap(p);
                    p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                    var beat = g.drain(0);
                    int voice = score.cornerAt(reverse ? 2 - i : i);
                    SealCipherGameTests.expectSounds(c, beat, score, List.of(voice));
                    c.assertTrue(EchoChallengeGameTests.overlays(beat).stream().anyMatch(t -> t.contains(score.voiceName(voice))),
                        "The muted caption names the solo");
                    c.assertValueEqual(GalleryTrailGameTests.particles(beat, ParticleTypes.WAX_OFF).size(),
                        (i + 1) * (i + 2) / 2, "A stop has one, two or three marks");
                    var stars = GalleryTrailGameTests.particles(beat, ParticleTypes.GLOW);
                    c.assertValueEqual(stars.size(), i == 2 ? 1 : 0, "Only a finished route lights the central star");
                    if (i == 2) {
                        var returnApex = Vec3.atCenterOf(arch.pos).add(6, 1.20, 0);
                        c.assertTrue(GalleryTrailGameTests.particles(beat, ParticleTypes.END_ROD).stream()
                            .anyMatch(point -> point.distanceTo(returnApex) < .001), "The raised return arc closes the route");
                        var center = Vec3.atCenterOf(arch.pos).add(20.0 / 3, .55, 1);
                        c.assertTrue(stars.getFirst().distanceTo(center) < .001, "Star at the actual route centroid");
                    }
                    post.tap(p);
                    SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                    if (i == 0) {
                        p.setXRot(-90); p.setShiftKeyDown(false);
                        p.gameMode.useItem(p, p.level(), item, InteractionHand.MAIN_HAND);
                        SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of(2,1));
                    }
                    if (i == 1) {
                        p.setPos(Vec3.atBottomCenterOf(far.pos).add(0, -1, -1));
                        c.assertTrue(p.level().getBlockState(far.pos).is(Blocks.LANTERN), "Outlying lantern is present");
                        far.tap(p);
                        var rejected = g.drain(0);
                        SealCipherGameTests.expectSounds(c, rejected, score, List.of());
                        c.assertTrue(EchoChallengeGameTests.overlays(rejected).stream().anyMatch(t -> t.contains("Too far")),
                            "Distance squared 145 from the first lantern fails even near the second");
                    }
                }
                far.tap(p);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                c.assertTrue(GalleryTrailGameTests.particles(g.drain(2), ParticleTypes.GLOW).isEmpty(),
                    "Only the walker sees the star");
                c.assertTrue(ItemStack.matches(item, p.getMainHandItem()), "The prism survives the walk");
            }
        }
        c.succeed();
    }

    @GameTest public void aimGlassAtSignedHalfBlockMidpoints(GameTestHelper c) {
        var target = new MidpointBeacon(new BlockPos(-8, -6, -6), new BlockPos(-1, -1, -1));
        // Midpoint (-4.5, -3.5, -3.5). Lift the chosen block by one, after rounding.
        for (int x = -5; x <= -4; x++) for (int y = -3; y <= -2; y++) for (int z = -4; z <= -3; z++)
            c.assertTrue(target.adjustment(new BlockPos(x, y, z)) == null, "All eight nearest cells work");
        for (var miss : List.of(new BlockPos(-6,-3,-4), new BlockPos(-3,-3,-4), new BlockPos(-4,-4,-4),
                new BlockPos(-4,-1,-4), new BlockPos(-4,-3,-5), new BlockPos(-4,-3,-2)))
            c.assertTrue(target.adjustment(miss) != null, "Each adjacent non-nearest cell misses");
        var exact = new MidpointBeacon(new BlockPos(-4, 0, -4), new BlockPos(4, 2, 4));
        c.assertTrue(exact.adjustment(new BlockPos(0, 2, 0)) == null
            && exact.adjustment(new BlockPos(0, 1, 0)) != null, "Glass sits above an exact midpoint");
        c.succeed();
    }

    @GameTest public void relayOrderUsesTheLitCellAndAcceptsEitherTiedLead(GameTestHelper c) {
        var span = new MidpointBeacon(new BlockPos(-7, 2, -1), new BlockPos(0, 2, 3));
        var west = new BeaconRelay(new BlockPos(-4, 3, 1));
        var east = new BeaconRelay(new BlockPos(-3, 3, 1));
        c.assertTrue(west.rejection(span, span.first()) == null && west.rejection(span, span.second()) != null
            && east.rejection(span, span.first()) != null && east.rejection(span, span.second()) == null,
            "Choosing the other valid half-block reverses the relay order");
        var flat = new MidpointBeacon(new BlockPos(-6, 2, -1), new BlockPos(0, 2, 3));
        for (var lead : List.of(flat.first(), flat.second())) {
            var open = new BeaconRelay(new BlockPos(-3, 3, 1));
            var other = lead.equals(flat.first()) ? flat.second() : flat.first();
            c.assertTrue(open.rejection(flat, lead) == null, "Either equidistant endpoint may lead");
            var half = open.advance(lead);
            c.assertTrue(half.rejection(flat, lead) != null && half.rejection(flat, other) == null, "Two distinct ends required");
            c.assertTrue(half.advance(other).rejection(flat, lead) != null, "The finished relay stays closed");
        }
        c.succeed();
    }

    private static void tapWithFollowup(ServerPlayer p, Stand stand) {
        p.setPos(Vec3.atBottomCenterOf(stand.pos).add(0, -1, -1));
        p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(stand.pos));
        stand.tap(p);
        // Some clients also send an item-use packet for the same click.
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    @GameTest public void sightLongestLanternSpanInThreeDimensions(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Sight"); var station = Stand.at(g.players.getFirst(), 1);
             var a = Stand.at(station.owner, station.pos.offset(0, 0, 2));
             var b = Stand.at(station.owner, a.pos.offset(8, 0, 0));
             var peak = Stand.at(station.owner, a.pos.offset(7, 4, 0));
             var ridge = Stand.at(station.owner, a.pos.offset(4, 7, 0));
             var extra = Stand.at(station.owner, a.pos.offset(2, 0, 2))) {
            var p = station.owner;
            var song = new ChordSeal(List.of(HALF_MOON, KITE, EMBER_PEAKS), 2, -1);
            var prism = new InlayPrism(song).item();
            p.setItemInHand(InteractionHand.MAIN_HAND, prism);
            p.level().setBlock(station.pos, Blocks.AMETHYST_BLOCK.defaultBlockState(), 2);
            for (var post : List.of(a, b, peak, ridge, extra))
                p.level().setBlock(post.pos, Blocks.LANTERN.defaultBlockState(), 2);
            // Peak makes squared lengths 64, 65 and 17. Ridge makes 64, 65 and 65.
            for (int route = 0; route < 3; route++) {
                boolean reverse = route != 1;
                p.setPos(Vec3.atBottomCenterOf(station.pos).add(0, -1, -1));
                p.setShiftKeyDown(reverse);
                station.tap(p);
                p.setXRot(-90);
                p.gameMode.useItem(p, p.level(), prism, InteractionHand.MAIN_HAND);
                p.setShiftKeyDown(!reverse);
                p.gameMode.useItem(p, p.level(), prism, InteractionHand.MAIN_HAND);
                var third = route == 0 ? peak : ridge;
                var posts = List.of(a, b, third);
                for (var post : posts) {
                    p.setPos(Vec3.atBottomCenterOf(post.pos).add(0, -1, -1));
                    post.tap(p);
                }
                g.drain(0);
                p.setShiftKeyDown(true);
                third.tap(p);
                var miss = g.drain(0);
                SealCipherGameTests.expectSounds(c, miss, song, List.of());
                c.assertTrue(EchoChallengeGameTests.overlays(miss).stream().anyMatch(t -> t.contains("stops 1 and 2 make a shorter span")),
                    "64 loses to 65 even though the projection onto the ground is longest");
                p.setPos(Vec3.atBottomCenterOf(extra.pos).add(0, -1, -1));
                extra.tap(p);
                var outside = g.drain(0);
                SealCipherGameTests.expectSounds(c, outside, song, List.of());
                c.assertTrue(EchoChallengeGameTests.overlays(outside).stream().anyMatch(t -> t.contains("three lanterns you visited")),
                    "An unvisited viewpoint does not consume the survey");
                int observer = route == 1 ? 0 : 1; // Both tied observers win on independent walks.
                var viewpoint = posts.get(observer);
                p.setPos(Vec3.atBottomCenterOf(viewpoint.pos).add(0, -1, -1));
                p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(viewpoint.pos));
                p.setShiftKeyDown(false); viewpoint.tap(p);
                SealCipherGameTests.expectSounds(c, g.drain(0), song, List.of());
                p.setShiftKeyDown(true); viewpoint.tap(p);
                p.gameMode.useItem(p, p.level(), prism, InteractionHand.MAIN_HAND);
                var hit = g.drain(0);
                int from = observer == 0 ? 1 : 0;
                SealCipherGameTests.expectSounds(c, hit, song,
                    List.of(song.cornerAt(reverse ? 2 - from : from), song.cornerAt(reverse ? 0 : 2)));
                var first = Vec3.atCenterOf(posts.get(from).pos).add(0, .55, 0);
                var last = Vec3.atCenterOf(third.pos).add(0, .55, 0);
                c.assertValueEqual(GalleryTrailGameTests.particles(hit, ParticleTypes.GLOW), List.of(first, last),
                    "Only the two far endpoints light up");
                var sight = GalleryTrailGameTests.particles(hit, ParticleTypes.WAX_ON);
                c.assertTrue(sight.getLast().distanceTo(first.lerp(last, .5)) < .001, "Dotted sight ends at true 3D midpoint");
                var diamond = GalleryTrailGameTests.particles(hit, ParticleTypes.ELECTRIC_SPARK);
                c.assertValueEqual(diamond.size(), 4, "A vertical diamond distinguishes the midpoint");
                c.assertTrue(diamond.getFirst().distanceTo(first.lerp(last, .5).add(0, .19, 0)) < .001,
                    "Diamond rises above the far edge");
                c.assertValueEqual(GalleryTrailGameTests.particles(hit, ParticleTypes.WAX_OFF).size(), 24,
                    "A raised eight-ray compass marks the observer");
                viewpoint.tap(p);
                SealCipherGameTests.expectSounds(c, g.drain(0), song, List.of());
                // On the tie, first try the glass for the other valid survey viewpoint.
                var wrongCell = route == 1 ? a.pos.offset(2, 4, 0) : a.pos.offset(6, 4, 0);
                var goodCell = switch (route) {
                    case 0 -> a.pos.offset(3, 3, 0);
                    case 1 -> a.pos.offset(6, 4, 0);
                    default -> a.pos.offset(2, 5, 0);
                };
                var alternate = route == 0 ? goodCell.east() : route == 1 ? goodCell.above() : goodCell.below();
                try (var wrong = Stand.at(p, wrongCell); var glass = Stand.at(p, goodCell);
                     var otherGlass = Stand.at(p, alternate)) {
                    for (var target : List.of(wrong, glass, otherGlass))
                        p.level().setBlock(target.pos, Blocks.GLASS.defaultBlockState(), 2);
                    tapWithFollowup(p, wrong);
                    var rejected = g.drain(0);
                    SealCipherGameTests.expectSounds(c, rejected, song, List.of());
                    c.assertTrue(EchoChallengeGameTests.overlays(rejected).stream().anyMatch(t -> t.contains("Try glass")),
                        "Use the surveyed edge even when another longest span ties");
                    tapWithFollowup(p, glass);
                    var lit = g.drain(0);
                    int solo = song.cornerAt(reverse ? 2 - observer : observer);
                    SealCipherGameTests.expectSounds(c, lit, song, List.of(solo));
                    var crown = Vec3.atCenterOf(glass.pos).add(0, .95, 0);
                    c.assertValueEqual(GalleryTrailGameTests.particles(lit, ParticleTypes.GLOW), List.of(crown),
                        "The beacon rises over the chosen glass cell");
                    c.assertTrue(GalleryTrailGameTests.particles(lit, ParticleTypes.END_ROD).stream()
                        .anyMatch(point -> point.distanceTo(crown.add(.36, 0, 0)) < .001), "Halo marks the top of the beam");
                    tapWithFollowup(p, otherGlass);
                    var spent = g.drain(0);
                    SealCipherGameTests.expectSounds(c, spent, song, List.of());
                    c.assertTrue(GalleryTrailGameTests.particles(spent, ParticleTypes.GLOW).isEmpty(), "Only one beacon per survey");
                    c.assertTrue(p.level().getBlockState(glass.pos).is(Blocks.GLASS), "Clear glass stays intact");
                    // The elevated glass is nearer the third lantern even on route 0, where its X projection favors stop 1.
                    var far = posts.get(from);
                    p.setShiftKeyDown(false);
                    for (var missPost : List.of(far, viewpoint, extra)) {
                        tapWithFollowup(p, missPost);
                        SealCipherGameTests.expectSounds(c, g.drain(0), song, List.of());
                    }
                    tapWithFollowup(p, third);
                    var lead = g.drain(0);
                    SealCipherGameTests.expectSounds(c, lead, song, List.of(song.cornerAt(reverse ? 0 : 2)));
                    c.assertValueEqual(GalleryTrailGameTests.particles(lead, ParticleTypes.GLOW),
                        List.of(Vec3.atCenterOf(third.pos).add(0, .7, 0)), "The first relay arch singles out its endpoint");
                    tapWithFollowup(p, third);
                    SealCipherGameTests.expectSounds(c, g.drain(0), song, List.of());
                    tapWithFollowup(p, far);
                    var finish = g.drain(0);
                    SealCipherGameTests.expectSounds(c, finish, song, List.of(0, 1, 2));
                    var center = Vec3.atCenterOf(glass.pos).add(0, 1.35, 0);
                    var ribbon = GalleryTrailGameTests.particles(finish, ParticleTypes.WAX_ON);
                    c.assertValueEqual(ribbon.size(), 32, "Both loops are visible without sound");
                    c.assertTrue(ribbon.getFirst().distanceTo(center) < .001 && ribbon.get(16).distanceTo(center) < .001
                        && ribbon.get(8).distanceTo(ribbon.get(24)) > 1.49, "Two distinct lobes cross above the chosen glass");
                    tapWithFollowup(p, far);
                    SealCipherGameTests.expectSounds(c, g.drain(0), song, List.of());
                }
                var bystander = g.drain(2);
                SealCipherGameTests.expectSounds(c, bystander, song, List.of());
                c.assertTrue(GalleryTrailGameTests.particles(bystander, ParticleTypes.GLOW).isEmpty(), "Survey effects are private");
            }
        }
        c.succeed();
    }

    @GameTest public void playReturnFromChosenGlassAndActualTiedRelayOrder(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Ribbon"); var a = Stand.at(g.players.getFirst(), 2);
             var observer = Stand.at(a.owner, a.pos.offset(1, 1, 1)); var b = Stand.at(a.owner, a.pos.offset(3, 3, 0));
             var high = Stand.at(a.owner, a.pos.offset(1, 3, 0)); var low = Stand.at(a.owner, a.pos.offset(1, 2, 0))) {
            var p = a.owner;
            var score = new ChordSeal(List.of(KITE, KITE, KITE), 2, -1);
            var prism = new InlayPrism(score);
            p.setItemInHand(InteractionHand.MAIN_HAND, prism.item());
            for (var post : List.of(a, observer, b)) p.level().setBlock(post.pos, Blocks.LANTERN.defaultBlockState(), 2);
            for (var cell : List.of(high, low)) p.level().setBlock(cell.pos, Blocks.GLASS.defaultBlockState(), 2);
            // At high the squared distances to A/B are 10/4. One block lower they are 5/5.
            for (int run = 0; run < 3; run++) {
                var glass = run == 0 ? high : low;
                var otherGlass = run == 0 ? low : high;
                var lead = run == 1 ? a : b;
                var finish = run == 1 ? b : a;
                boolean reverse = run == 0;
                int leadVoice = score.cornerAt((lead == a) != reverse ? 0 : 2);
                int finishVoice = score.cornerAt((finish == a) != reverse ? 0 : 2);
                LanternWalk.open(p, prism, reverse);
                p.setShiftKeyDown(false);
                for (var post : List.of(a, observer, b)) tapWithFollowup(p, post);
                p.setShiftKeyDown(true);
                tapWithFollowup(p, observer);
                tapWithFollowup(p, glass);
                g.drain(0);
                tapWithFollowup(p, glass);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                p.setShiftKeyDown(false);
                if (run == 0) {
                    tapWithFollowup(p, a);
                    SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                }
                tapWithFollowup(p, lead);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of(leadVoice));
                tapWithFollowup(p, finish);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of(0, 1, 2));
                tapWithFollowup(p, glass); // Plain use never starts the return.
                p.setShiftKeyDown(true);
                tapWithFollowup(p, otherGlass);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                tapWithFollowup(p, glass);
                var opening = g.drain(0);
                SealCipherGameTests.expectSounds(c, opening, score, List.of(finishVoice));
                var center = Vec3.atCenterOf(glass.pos).add(0, 1.35, 0);
                var arc = GalleryTrailGameTests.particles(opening, ParticleTypes.ELECTRIC_SPARK);
                c.assertTrue(arc.size() == 13 && arc.getFirst().distanceTo(Vec3.atCenterOf(finish.pos).add(0, .7, 0)) < .001
                    && arc.getLast().distanceTo(center) < .001, "Dotted arc returns from the actual finisher to the chosen glass");
                tapWithFollowup(p, glass);
                p.setShiftKeyDown(false);
                tapWithFollowup(p, lead);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                tapWithFollowup(p, observer);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of(score.cornerAt(1)));
                tapWithFollowup(p, observer);
                tapWithFollowup(p, finish);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                tapWithFollowup(p, lead);
                var last = g.drain(0);
                SealCipherGameTests.expectSounds(c, last, score, List.of(leadVoice, finishVoice));
                c.assertValueEqual(GalleryTrailGameTests.particles(last, ParticleTypes.GLOW),
                    List.of(Vec3.atCenterOf(lead.pos).add(0, .7, 0), Vec3.atCenterOf(finish.pos).add(0, .7, 0), center),
                    "Only the endpoint duet and the glass carry the final glow");
                c.assertTrue(GalleryTrailGameTests.particles(last, ParticleTypes.END_ROD).stream()
                    .anyMatch(point -> point.distanceTo(center.add(0, .42, 0)) < .001), "A star crowns the ribbon at its center");
                c.assertValueEqual(GalleryTrailGameTests.particles(last, ParticleTypes.WAX_ON).size(), 32, "Both lobes close");
                tapWithFollowup(p, lead);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                var bystander = g.drain(2);
                SealCipherGameTests.expectSounds(c, bystander, score, List.of());
                c.assertTrue(GalleryTrailGameTests.particles(bystander, ParticleTypes.ELECTRIC_SPARK).isEmpty(), "Return is private");
            }
        }
        c.succeed();
    }

    @GameTest public void projectObserverChartsThroughTheEarnedStarLens(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Lens"); var a = Stand.at(g.players.getFirst(), 2);
             var obs = Stand.at(a.owner, a.pos.offset(2, 0, 1)); var b = Stand.at(a.owner, a.pos.east(4));
             var glass = Stand.at(a.owner, a.pos.offset(2, 1, 0)); var lens = Stand.at(a.owner, glass.pos.above());
             var neighbor = Stand.at(a.owner, lens.pos.east())) {
            var p = a.owner;
            var charts = List.of(KITE, HALF_MOON, EMBER_PEAKS);
            var tints = List.of(Blocks.STAINED_GLASS.cyan(), Blocks.STAINED_GLASS.purple(), Blocks.STAINED_GLASS.orange());
            var masks = List.of("010101010", "010110010", "101111111");
            for (var post : List.of(a, obs, b)) p.level().setBlock(post.pos, Blocks.LANTERN.defaultBlockState(), 2);
            for (int run = 0; run < 3; run++) {
                var visits = switch (run) {
                    case 0 -> List.of(a, obs, b);
                    case 1 -> List.of(obs, b, a);
                    default -> List.of(a, b, obs);
                };
                var score = new ChordSeal(run == 0 ? List.of(KITE, KITE, KITE) : charts, run, 1);
                var prism = new InlayPrism(score);
                p.setItemInHand(InteractionHand.MAIN_HAND, prism.item());
                int index = visits.indexOf(obs);
                int voice = score.cornerAt(run == 2 ? 2 - index : index);
                var facet = List.of(score.cornerAt(0), score.cornerAt(1));
                p.level().setBlock(glass.pos, Blocks.GLASS.defaultBlockState(), 2);
                p.level().setBlock(lens.pos, tints.get(run).defaultBlockState(), 2);
                p.level().setBlock(neighbor.pos, tints.get(run).defaultBlockState(), 2);
                LanternWalk.open(p, prism, run == 2);
                p.setShiftKeyDown(false);
                for (var post : visits) tapWithFollowup(p, post);
                p.setShiftKeyDown(true);
                tapWithFollowup(p, obs);
                tapWithFollowup(p, glass);
                p.setShiftKeyDown(false);
                var lead = run == 1 ? b : a;
                var finish = lead == a ? b : a;
                tapWithFollowup(p, lead);
                tapWithFollowup(p, finish);
                g.drain(0);
                tapWithFollowup(p, lens);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, facet);
                p.setShiftKeyDown(true);
                tapWithFollowup(p, glass);
                p.setShiftKeyDown(false);
                tapWithFollowup(p, obs);
                tapWithFollowup(p, lead);
                g.drain(0);
                tapWithFollowup(p, neighbor);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, facet);
                p.level().setBlock(glass.pos, Blocks.STONE.defaultBlockState(), 2);
                tapWithFollowup(p, lens);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, facet);
                p.level().setBlock(glass.pos, Blocks.GLASS.defaultBlockState(), 2);
                p.level().setBlock(lens.pos, Blocks.STAINED_GLASS_PANE.purple().defaultBlockState(), 2);
                tapWithFollowup(p, lens);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, facet);
                p.level().setBlock(lens.pos, tints.get((run + 1) % 3).defaultBlockState(), 2);
                tapWithFollowup(p, lens);
                var miss = g.drain(0);
                SealCipherGameTests.expectSounds(c, miss, score, List.of());
                c.assertTrue(GalleryTrailGameTests.particles(miss, ParticleTypes.GLOW).isEmpty(), "Wrong tint preserves the unheard solo");
                p.level().setBlock(lens.pos, tints.get(run).defaultBlockState(), 2);
                for (int attempt = 0; attempt < 2; attempt++) {
                    tapWithFollowup(p, lens);
                    var packets = g.drain(0);
                    SealCipherGameTests.expectSounds(c, packets, score, attempt == 0 ? List.of(voice) : List.of());
                    var stars = GalleryTrailGameTests.particles(packets, ParticleTypes.GLOW);
                    var sockets = GalleryTrailGameTests.particles(packets, ParticleTypes.WAX_OFF);
                    c.assertValueEqual(stars.size()+sockets.size(), 9, "Nine cells remain visible on silent re-projection");
                    for (int cell = 0; cell < 9; cell++) {
                        // The fixture faces south, so the viewer's right is west.
                        var point = Vec3.atCenterOf(lens.pos).add((1 - cell % 3) * .46, 2 + (1 - cell / 3) * .46, 0);
                        var expected = masks.get(run).charAt(cell) == '1' ? stars : sockets;
                        c.assertTrue(expected.stream().anyMatch(dot -> dot.distanceTo(point) < .002), "Correct screen cell " + cell);
                    }
                }
                c.assertTrue(p.level().getBlockState(lens.pos).is(tints.get(run)), "Projection leaves the lens intact");
                var visitor = g.drain(2);
                SealCipherGameTests.expectSounds(c, visitor, score, List.of());
                c.assertTrue(GalleryTrailGameTests.particles(visitor, ParticleTypes.GLOW).isEmpty(), "Only the owner sees the chart");
            }
        }
        c.succeed();
    }

    @GameTest public void stampOneDensestColumnInWalkVisitOrder(GameTestHelper c) {
        try (var g = new EncoreDuetGameTests.Gallery(c, "Trace"); var west = Stand.at(g.players.getFirst(), 2);
             var observer = Stand.at(west.owner, west.pos.offset(2, 0, 1)); var east = Stand.at(west.owner, west.pos.east(4));
             var glass = Stand.at(west.owner, west.pos.offset(2, 1, 0)); var lens = Stand.at(west.owner, glass.pos.above())) {
            var p = west.owner;
            var visits = List.of(east, observer, west);
            for (var post : visits) p.level().setBlock(post.pos, Blocks.LANTERN.defaultBlockState(), 2);
            for (int run = 0; run < 4; run++) {
                var chart = List.of(KITE, HALF_MOON, EMBER_PEAKS, EMBER_PEAKS).get(run);
                int winner = List.of(1, 1, 0, 2).get(run);
                int loser = winner == 1 ? 0 : 1;
                var score = new ChordSeal(run == 0 ? List.of(KITE, KITE, KITE) : List.of(KITE, chart, HALF_MOON), 0, 1);
                var prism = new InlayPrism(score);
                p.setItemInHand(InteractionHand.MAIN_HAND, prism.item());
                p.level().setBlock(glass.pos, Blocks.GLASS.defaultBlockState(), 2);
                var tint = Blocks.STAINED_GLASS.pick(CadencePennant.ink(chart)).defaultBlockState();
                p.level().setBlock(lens.pos, tint, 2);
                LanternWalk.open(p, prism, true);
                p.setShiftKeyDown(false);
                for (var post : visits) tapWithFollowup(p, post);
                p.setShiftKeyDown(true);
                tapWithFollowup(p, observer);
                tapWithFollowup(p, glass);
                p.setShiftKeyDown(false);
                tapWithFollowup(p, east);
                tapWithFollowup(p, west);
                p.setShiftKeyDown(true);
                tapWithFollowup(p, glass);
                p.setShiftKeyDown(false);
                tapWithFollowup(p, observer);
                tapWithFollowup(p, east);
                g.drain(0);
                tapWithFollowup(p, visits.get(winner));
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                tapWithFollowup(p, lens);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of(1));
                g.drain(2);

                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
                tapWithFollowup(p, lens);
                c.assertTrue(p.getMainHandItem().getComponentsPatch().isEmpty(), "Paper stays blank until a column wins");
                p.setItemInHand(InteractionHand.MAIN_HAND, prism.item());
                tapWithFollowup(p, visits.get(loser));
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                p.level().setBlock(lens.pos, Blocks.GLASS.defaultBlockState(), 2);
                tapWithFollowup(p, visits.get(winner));
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                p.level().setBlock(lens.pos, tint, 2);
                p.setShiftKeyDown(true);
                tapWithFollowup(p, visits.get(winner));
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                p.setShiftKeyDown(false);
                for (int attempt = 0; attempt < 3; attempt++) {
                    tapWithFollowup(p, attempt == 2 ? lens : visits.get(winner));
                    var packets = g.drain(0);
                    SealCipherGameTests.expectSounds(c, packets, score, attempt == 0 ? List.of(2 - winner) : List.of());
                    var marks = GalleryTrailGameTests.particles(packets, ParticleTypes.ELECTRIC_SPARK);
                    c.assertValueEqual(marks.size(), 10, "Two five-dot underlines mark exactly one column");
                    var center = Vec3.atCenterOf(lens.pos).add((1 - winner) * .46, 2 - .83, 0);
                    for (int row = 0; row < 2; row++) for (int dot = -2; dot <= 2; dot++) {
                        var point = center.add(dot * .08, -row * .12, 0);
                        c.assertTrue(marks.stream().anyMatch(mark -> mark.distanceTo(point) < .002), "Mark stays under the selected column");
                    }
                    if (attempt == 0) {
                        // Test the other tied winner on both Ember routes.
                        tapWithFollowup(p, visits.get(winner == 1 ? 2 : 2 - winner));
                        var alternate = g.drain(0);
                        SealCipherGameTests.expectSounds(c, alternate, score, List.of());
                        c.assertTrue(GalleryTrailGameTests.particles(alternate, ParticleTypes.ELECTRIC_SPARK).isEmpty(), "A second choice cannot move the mark");
                    }
                }
                // Print away from the walk's spatial order and read it as a different player.
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 2));
                p.level().setBlock(lens.pos, Blocks.STAINED_GLASS.white().defaultBlockState(), 2);
                tapWithFollowup(p, lens);
                c.assertTrue(p.getMainHandItem().getCount() == 2 && Skyprint.read(p.getMainHandItem()) == null,
                    "Wrong lens leaves both blanks in hand");
                p.level().setBlock(lens.pos, tint, 2);
                if (run == 2) {
                    var namedSheet = CommonsEvents.named(Items.PAPER, 1, "Letter", "Keep this text");
                    p.setItemInHand(InteractionHand.MAIN_HAND, namedSheet);
                    tapWithFollowup(p, lens);
                    c.assertTrue(ItemStack.matches(namedSheet, p.getMainHandItem()), "Named paper keeps its contents");
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 2));
                    var filled = new ArrayList<Integer>();
                    for (int slot = 0; slot < 36; slot++) {
                        if (p.getInventory().getItem(slot).isEmpty()) {
                            filled.add(slot);
                            p.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                        }
                    }
                    tapWithFollowup(p, lens);
                    c.assertTrue(p.getMainHandItem().getCount() == 2 && Skyprint.read(p.getMainHandItem()) == null,
                        "Full inventory preserves both blanks and the earned trace for retry");
                    for (int slot : filled) p.getInventory().setItem(slot, ItemStack.EMPTY);
                }
                int blanks = p.getInventory().countItem(Items.PAPER);
                tapWithFollowup(p, lens);
                SealCipherGameTests.expectSounds(c, g.drain(0), score, List.of());
                var sheet = p.getMainHandItem();
                int selectedVoice = 2 - winner;
                c.assertValueEqual(Skyprint.read(sheet), new Skyprint(chart, winner, selectedVoice, score.voices().get(selectedVoice)),
                    "The ink carries the observer chart and the selected lantern's own role and pitch");
                c.assertValueEqual(p.getInventory().countItem(Items.PAPER), blanks, "One sheet printed, remaining paper retained");
                var friend = g.players.get(1);
                friend.setItemInHand(InteractionHand.MAIN_HAND, sheet.copy());
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                LanternWalk.clear(p);
                p.level().setBlock(lens.pos, Blocks.AIR.defaultBlockState(), 2);
                p.level().setBlock(glass.pos, Blocks.AIR.defaultBlockState(), 2);
                friend.setXRot(-90);
                friend.setYRot(0);
                g.drain(1);
                for (boolean quiet : List.of(false, true, false)) {
                    friend.setShiftKeyDown(quiet);
                    friend.gameMode.useItem(friend, friend.level(), friend.getMainHandItem(), InteractionHand.MAIN_HAND);
                    var readback = g.drain(1);
                    SealCipherGameTests.expectSounds(c, readback, score, quiet ? List.of() : List.of(selectedVoice));
                    c.assertValueEqual(GalleryTrailGameTests.particles(readback, ParticleTypes.GLOW).size(),
                        Integer.bitCount(chart.goal), "Recipient sees the observer constellation");
                    var marks = GalleryTrailGameTests.particles(readback, ParticleTypes.ELECTRIC_SPARK);
                    c.assertValueEqual(marks.size(), 10, "Recipient sees both lines on quiet and audible use");
                    var center = friend.getEyePosition().add(friend.getLookAngle().normalize().scale(1.6));
                    var right = new Vec3(-.68, 0, 0);
                    var up = right.cross(friend.getLookAngle()).normalize().scale(.68);
                    var mark = center.add(right.scale((winner - 1) * .46)).add(up.scale(-.83));
                    c.assertTrue(marks.stream().anyMatch(point -> point.distanceTo(mark) < .002),
                        "Recipient's underline is beneath the actual winning column");
                }
                var other = g.drain(2);
                SealCipherGameTests.expectSounds(c, other, score, List.of());
                c.assertTrue(GalleryTrailGameTests.particles(other, ParticleTypes.ELECTRIC_SPARK).isEmpty(), "The mark stays private");
            }
        }
        c.succeed();
    }

    @GameTest public void skyprintReadsOnlyAnEarnableScore(GameTestHelper c) {
        var print = new Skyprint(EMBER_PEAKS, 0, 2, HALF_MOON);
        c.assertValueEqual(Skyprint.read(print.paper()), print, "Ink distinguishes the chart from the solo's pitch");
        c.assertTrue(Skyprint.read(new Skyprint(EMBER_PEAKS, 1, 0, KITE).paper()) == null, "Reject a non-winning column");
        var bad = print.paper();
        var data = bad.get(DataComponents.CUSTOM_DATA).copyTag();
        var score = data.getCompound("infinity_skyprint_v1").orElseThrow();
        score.remove("voice");
        data.put("infinity_skyprint_v1", score);
        bad.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(data));
        c.assertTrue(Skyprint.read(bad) == null, "Missing role cannot become a chime");
        c.assertTrue(StarPrint.read(print.paper()) == null && CodaPostcard.read(print.paper()) == null,
            "A Skyprint is distinct from other music papers");
        c.succeed();
    }

    @GameTest public void decodeSixPrismRoutesAndKeepSealDataSeparate(GameTestHelper c) {
        var pitches = List.of(KITE, EMBER_PEAKS, HALF_MOON);
        for (int start = 0; start < 3; start++) for (int step : List.of(-1, 1)) {
            var seal = new ChordSeal(pitches, start, step);
            var prism = new InlayPrism(seal);
            var item = prism.item();
            c.assertValueEqual(InlayPrism.read(item), prism, "Three distinct pitches and their route survive cutting");
            c.assertTrue(ChordSeal.read(item) == null && InlayPrism.read(seal.item()) == null,
                "A prism and a stamped seal retain their own use actions");
        }
        c.assertTrue(InlayPrism.read(new ItemStack(Items.QUARTZ)) == null, "Ordinary quartz is not playable");
        var invalid = new InlayPrism(new ChordSeal(pitches, 0, 0)).item();
        c.assertTrue(InlayPrism.read(invalid) == null, "Reject a route with repeated roles");
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

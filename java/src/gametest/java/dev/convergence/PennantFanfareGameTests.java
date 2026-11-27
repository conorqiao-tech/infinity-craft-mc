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

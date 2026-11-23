package dev.convergence;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;

public class BannerConcertGameTests {
    private static final class Stage implements AutoCloseable {
        final ServerPlayer p;
        final BlockPos origin;
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Stage(ServerPlayer p) {
            this.p = p;
            origin = p.blockPosition().atY(p.level().getMaxY() - 6);
            p.setPos(Vec3.atBottomCenterOf(origin));
        }
        BlockPos pos(StarLoom.Chart chart) { return origin.offset(chart.ordinal() - 1, 1, 2); }
        void placeAll() {
            for (var chart : StarLoom.Chart.values()) {
                var recipe = (ShapelessRecipe)p.level().getServer().getRecipeManager().byKey(chart.recipe).orElseThrow().value();
                var flag = recipe.assemble(CraftingInput.EMPTY);
                flag.set(DataComponents.CUSTOM_NAME, Component.literal("My stage flag"));
                var pos = pos(chart);
                var face = chart == StarLoom.Chart.HALF_MOON ? Direction.NORTH : Direction.UP;
                var support = pos.relative(face.getOpposite());
                before.put(pos, p.level().getBlockState(pos));
                before.put(support, p.level().getBlockState(support));
                p.level().setBlock(support, Blocks.STONE.defaultBlockState(), 2);
                p.setItemInHand(InteractionHand.MAIN_HAND, flag);
                use(support, face);
                if (p.level().getBlockEntity(pos) == null) throw new AssertionError("Banner placement failed for " + chart);
            }
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD, 3));
        }
        void use(BlockPos target, Direction face) {
            var point = Vec3.atCenterOf(target).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(.5));
            p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
            var hit = new BlockHitResult(point, face, target, false);
            p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        }
        void tap(StarLoom.Chart chart) {
            for (int i = 0; i < 5; i++) p.getCooldowns().tick();
            use(pos(chart), Direction.NORTH);
        }
        public void close() {
            for (var entry : before.entrySet()) p.level().setBlock(entry.getKey(), entry.getValue(), 2);
        }
    }

    private static void choose(ServerPlayer p, String label) {
        var menu = (InfinityJourney.Handler)p.containerMenu;
        for (int i = 0; i < menu.view.choices().size(); i++) {
            if (menu.view.choices().get(i).label().startsWith(label)) {
                menu.clicked(menu.view.javaSlot(i), 0, ContainerInput.PICKUP, p);
                return;
            }
        }
        throw new AssertionError("Missing " + label);
    }

    @GameTest public void javaPerformerPlaysAllThreeScoresOnPlacedAndHangingBanners(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "concert-solo");
        try (var stage = new Stage(p)) {
            stage.placeAll();
            c.assertTrue(p.level().getBlockState(stage.pos(StarLoom.Chart.HALF_MOON)).getBlock() instanceof WallBannerBlock, "Half Moon hangs on the wall");
            stage.tap(StarLoom.Chart.KITE);
            c.assertFalse(GameModes.state(p).contains(BannerConcert.KEY), "Improvising has no score to save");
            for (var song : List.of(BannerConcert.Song.MOON_DANCE, BannerConcert.Song.CINDER_WALTZ, BannerConcert.Song.INFINITY_REFRAIN)) {
                InfinityJourney.open(p, InfinityJourney.Page.STAR_LOOM);
                choose(p, "Banner Concert");
                choose(p, "Play " + song.title);
                for (var note : song.notes) stage.tap(note);
                var run = BannerConcert.read(p);
                c.assertTrue(run.song() == BannerConcert.Song.FREE && (run.flawless() & song.mark()) != 0, "Playing every cue earns Flawless and returns to free play");
            }
            c.assertValueEqual(p.getMainHandItem().getCount(), 3, "The shard stack stays intact");
            var saved = BannerConcert.read(f.reloadDisk(p));
            c.assertTrue(saved.finished() == 7 && saved.flawless() == 7, "All three marks persist on disk");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void touchSelectionAndMissedCueKeepThePhrasePlayable(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "concert-touch");
        try (var stage = new Stage(p)) {
            stage.placeAll();
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.BANNER_CONCERT, forms::add);
            var form = forms.getFirst();
            var codec = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec();
            var json = JsonParser.parseString(codec.jsonData(form)).getAsJsonObject();
            c.assertTrue(json.get("content").getAsString().contains("Kite → Half Moon → Kite"), "Touch score lists the phrase in order");
            f.reply(form, "0");
            stage.tap(StarLoom.Chart.KITE);
            stage.tap(StarLoom.Chart.EMBER_PEAKS);
            var miss = BannerConcert.read(p);
            c.assertTrue(miss.next() == 1 && miss.missed(), "Wrong banner keeps the Half Moon cue");
            stage.tap(StarLoom.Chart.HALF_MOON);
            stage.tap(StarLoom.Chart.KITE);
            c.assertTrue(BannerConcert.read(p).finished() == 1 && BannerConcert.read(p).flawless() == 0, "Recovery earns Played");
            InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT);
            choose(p, "Play Moon Dance");
            for (var note : BannerConcert.Song.MOON_DANCE.notes) stage.tap(note);
            c.assertValueEqual(BannerConcert.read(p).flawless(), 1, "Clean replay upgrades the phrase");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    private static List<ClientboundLevelParticlesPacket> dust(List<Packet<?>> packets) {
        return packets.stream().filter(ClientboundLevelParticlesPacket.class::isInstance).map(ClientboundLevelParticlesPacket.class::cast)
            .filter(packet -> packet.particle() instanceof DustParticleOptions).toList();
    }
    private static List<Float> pitches(List<Packet<?>> packets) {
        return packets.stream().filter(ClientboundSoundPacket.class::isInstance).map(ClientboundSoundPacket.class::cast)
            .filter(packet -> packet.getSound().equals(SoundEvents.NOTE_BLOCK_CHIME)).map(ClientboundSoundPacket::getPitch).toList();
    }

    @GameTest public void performerReceivesStarPatternsChimesAndInfinityFinale(GameTestHelper c) {
        var ownerWire = new NativePlayConnection(c.getLevel().registryAccess());
        var otherWire = new NativePlayConnection(c.getLevel().registryAccess());
        var f = new CommonsGameTests();
        var players = new ModeGameTests();
        var p = players.player(c, "concert-lights", ownerWire.connection);
        var other = players.player(c, "concert-guest", otherWire.connection);
        try (var stage = new Stage(p)) {
            p.closeContainer();
            p.setGameMode(GameType.SURVIVAL);
            stage.placeAll();
            other.setPos(p.position().add(1, 0, 0));
            InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT);
            choose(p, "Play Cinder Waltz");
            ownerWire.drain();
            otherWire.drain();
            var notes = BannerConcert.Song.CINDER_WALTZ.notes;
            for (int i = 0; i < notes.size(); i++) {
                var note = notes.get(i);
                stage.tap(note);
                var packets = ownerWire.drain();
                c.assertValueEqual(pitches(packets), List.of(NoteBlock.getPitchFromNote(note.note)), "Tap sends the design's native pitch");
                var motes = dust(packets);
                int stars = Integer.bitCount(note.goal);
                c.assertValueEqual(motes.size(), stars + (i == notes.size() - 1 ? 24 : 0), "Each lit star gets a mote and the finish adds a figure eight");
                c.assertTrue(motes.stream().allMatch(pkt -> pkt.y() > stage.pos(note).getY() + 1 && pkt.count() == 1), "The light show is drawn above the banner");
            }
            var observed = otherWire.drain();
            c.assertTrue(dust(observed).isEmpty() && pitches(observed).isEmpty(), "The nearby guest receives no private chime or dust");
        } finally {
            InfinityJourneyGameTests.cleanup(f, p);
            InfinityJourneyGameTests.cleanup(f, other);
            ownerWire.channel.finishAndReleaseAll();
            otherWire.channel.finishAndReleaseAll();
        }
        c.succeed();
    }
}

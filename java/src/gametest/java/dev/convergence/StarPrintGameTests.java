package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

import static dev.convergence.BannerConcertGameTests.Stage;
import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.BannerConcertGameTests.dust;
import static dev.convergence.BannerConcertGameTests.pitches;
import static dev.convergence.EchoChallengeGameTests.overlays;
import static dev.convergence.StarLoom.Chart.*;

public class StarPrintGameTests {
    private static void write(ServerPlayer p, StarLoom.Chart... notes) {
        InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER);
        for (var note : notes) choose(p, "Add " + note.label);
    }

    private static void usePaper(ServerPlayer p) {
        p.gameMode.useItem(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    @GameTest public void inkUsesOnlyTheReviewedPlainPaperAndKeepsTheScoreOnTheItem(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "score-sheet");
        try {
            write(p, KITE, HALF_MOON);
            p.closeContainer();
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.STONE));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 2));
            c.assertFalse(StarPrint.view(p).choices().stream().anyMatch(b -> b.label().startsWith("Print my")), "Full pack must have room for leftover blank paper");
            var named = new ItemStack(Items.PAPER);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Letter"));
            p.setItemInHand(InteractionHand.MAIN_HAND, named);
            c.assertFalse(StarPrint.view(p).choices().stream().anyMatch(b -> b.label().startsWith("Print my")), "A personal letter is not blank stock");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.STAR_PRINTS, forms::add);
            var form = (SimpleForm)forms.getFirst();
            int stamp = form.buttons().stream().map(b -> b.text()).toList().indexOf("Print my draft • 1 paper");
            c.assertTrue(stamp >= 0, "A single sheet fits a full inventory");
            p.setItemInHand(InteractionHand.MAIN_HAND, named);
            f.reply(form, Integer.toString(stamp));
            c.assertTrue(ItemStack.matches(p.getMainHandItem(), named), "A delayed form cannot stamp a replacement sheet");
            p.closeContainer();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
            InfinityJourney.open(p, InfinityJourney.Page.STAR_PRINTS);
            choose(p, "Print my draft");
            var saved = StarPrint.read(f.reloadDisk(p).getMainHandItem());
            c.assertValueEqual(saved, new StarPrint(p.getGameProfile().name(), List.of(KITE, HALF_MOON)), "Author and written notes survive an actual player inventory save");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest(maxTicks = 140) public void aReaderUnfoldsAndPerformsAGiftWhileKeepingTheirDraft(GameTestHelper c) {
        var readerWire = new NativePlayConnection(c.getLevel().registryAccess());
        var authorWire = new NativePlayConnection(c.getLevel().registryAccess());
        var fixture = new CommonsGameTests();
        var players = new ModeGameTests();
        var reader = players.player(c, "print-reader", readerWire.connection);
        var author = players.player(c, "print-author", authorWire.connection);
        var stage = new Stage(reader);
        var written = List.of(KITE, KITE, EMBER_PEAKS);
        var ownDraft = List.of(HALF_MOON, EMBER_PEAKS);
        Runnable close = () -> {
            stage.close();
            InfinityJourneyGameTests.cleanup(fixture, reader);
            InfinityJourneyGameTests.cleanup(fixture, author);
            readerWire.channel.finishAndReleaseAll();
            authorWire.channel.finishAndReleaseAll();
        };
        try {
            reader.closeContainer(); author.closeContainer();
            reader.setGameMode(GameType.SURVIVAL); author.setGameMode(GameType.SURVIVAL);
            stage.placeAll();
            author.setPos(reader.position().add(1, 0, 0));
            write(reader, HALF_MOON, EMBER_PEAKS); reader.closeContainer();
            write(author, KITE, KITE, EMBER_PEAKS); author.closeContainer();
            author.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 5));
            InfinityJourney.open(author, InfinityJourney.Page.MIRROR_COMPOSER);
            choose(author, "Star Prints");
            choose(author, "Print my draft");
            var sheet = StarPrint.read(author.getMainHandItem());
            c.assertValueEqual(sheet.notes(), written, "Print captures original order including repeats");
            int blanks = 0;
            for (int i = 0; i < 36; i++) {
                var stack = author.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.PAPER))) blanks += stack.getCount();
            }
            c.assertValueEqual(blanks, 4, "Exactly one of the five sheets is inked");
            choose(author, "Back to Mirror Composer");
            choose(author, "Add Half Moon"); author.closeContainer();
            author.drop(false);
            var dropped = author.level().getEntitiesOfClass(ItemEntity.class, author.getBoundingBox().inflate(4), item -> sheet.equals(StarPrint.read(item.getItem()))).getFirst();
            dropped.setNoPickUpDelay();
            dropped.playerTouch(reader);
            c.assertTrue(dropped.isRemoved(), "Reader picks up the dropped item");
            int found = -1;
            for (int i = 0; i < 36; i++) if (sheet.equals(StarPrint.read(reader.getInventory().getItem(i)))) found = i;
            c.assertTrue(found >= 0, "The recipient holds the original author's print");
            var selected = reader.getInventory().getSelectedSlot();
            var paper = reader.getInventory().getItem(found);
            reader.getInventory().setItem(found, reader.getInventory().getItem(selected));
            reader.setItemInHand(InteractionHand.MAIN_HAND, paper);
            usePaper(reader);
            var menu = (InfinityJourney.Handler)reader.containerMenu;
            c.assertTrue(menu.view.content().contains(author.getGameProfile().name()) && menu.view.content().contains("Kite → Kite → Ember Peaks"), "Using the paper opens the printed score");
            reader.setYRot(0); reader.setXRot(0);
            readerWire.drain(); authorWire.drain();
            choose(reader, "Unfold held print");
        } catch (RuntimeException | Error e) { close.run(); throw e; }
        var server = reader.level().getServer();
        int start = server.getTickCount();
        var tune = new ArrayList<Float>();
        var captions = new ArrayList<String>();
        var stars = new ArrayList<ClientboundLevelParticlesPacket>();
        var performance = new ArrayList<Float>();
        c.onEachTick(() -> {
            int elapsed = server.getTickCount() - start;
            try {
                var packets = readerWire.drain();
                if (elapsed <= 22) {
                    tune.addAll(pitches(packets));
                    captions.addAll(overlays(packets).stream().filter(s -> s.startsWith("Star Print • ")).toList());
                    if (overlays(packets).contains("Star Print • 3/3 • Ember Peaks")) stars.addAll(dust(packets));
                } else performance.addAll(pitches(packets));
                var bystander = authorWire.drain();
                c.assertTrue(dust(bystander).isEmpty() && pitches(bystander).isEmpty(), "The nearby author receives none of the reader's performance");
                if (elapsed == 22) {
                    c.assertValueEqual(tune, written.stream().map(n -> NoteBlock.getPitchFromNote(n.note)).toList(), "Unfold reproduces the gift even after the author changes their draft");
                    c.assertValueEqual(captions, List.of("Star Print • 1/3 • Kite","Star Print • 2/3 • Kite","Star Print • 3/3 • Ember Peaks"), "Each printed beat names its note");
                    var positions = stars.stream().filter(pkt -> ((DustParticleOptions)pkt.particle()).getScale() == .8f).toList();
                    c.assertValueEqual(positions.size(), 3, "All three notes form visible stars");
                    c.assertTrue(positions.getFirst().x() > positions.get(1).x() && positions.get(1).x() > positions.getLast().x()
                        && positions.getFirst().y() == positions.get(1).y() && positions.getLast().y() > positions.get(1).y(), "Repeated notes occupy separate columns and higher notes rise");
                    var forms = new ArrayList<Form>();
                    InfinityJourney.form(reader, InfinityJourney.Page.STAR_PRINTS, forms::add);
                    var form = (SimpleForm)forms.getFirst();
                    int play = form.buttons().stream().map(b -> b.text()).toList().indexOf("Perform held print");
                    c.assertTrue(play >= 0, "Reader can perform directly from the touch form");
                    fixture.reply(form, Integer.toString(play));
                    int spare = reader.getInventory().getFreeSlot();
                    reader.getInventory().setItem(spare, reader.getMainHandItem());
                    reader.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD));
                    stage.tap(KITE); stage.tap(EMBER_PEAKS);
                    readerWire.drain();
                }
                if (elapsed < 105) return;
                c.assertValueEqual(performance, List.of(KITE,KITE,EMBER_PEAKS,KITE,KITE).stream().map(n -> NoteBlock.getPitchFromNote(n.note)).toList(),
                    "Linked banners play the borrowed score out and back");
                c.assertTrue(ConcertStage.current(reader) == null, "Print performance finishes");
                var loaded = fixture.reloadDisk(author);
                c.assertValueEqual(MirrorComposer.score(loaded), List.of(KITE,KITE,EMBER_PEAKS,HALF_MOON), "Author retains their later edit");
                c.assertValueEqual(MirrorComposer.score(reader), ownDraft, "Reading and performing the print preserves the recipient's draft");
            } catch (RuntimeException | Error e) { close.run(); throw e; }
            close.run();
            c.succeed();
        });
    }
}

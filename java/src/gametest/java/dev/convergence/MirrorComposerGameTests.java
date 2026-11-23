package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

import static dev.convergence.BannerConcertGameTests.Stage;
import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.BannerConcertGameTests.pitches;
import static dev.convergence.EchoChallengeGameTests.overlays;
import static dev.convergence.StarLoom.Chart.*;

public class MirrorComposerGameTests {
    private static void open(ServerPlayer p) {
        InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT);
        choose(p, "Echo Challenge");
        choose(p, "Mirror Composer");
    }

    @GameTest(maxTicks = 55) public void writesSixNotesOnTouchAndUndoesByEar(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var f = new CommonsGameTests();
        var p = new ModeGameTests().player(c, "score-editor", wire.connection);
        Runnable close = () -> {
            InfinityJourneyGameTests.cleanup(f, p);
            wire.channel.finishAndReleaseAll();
        };
        var phrase = List.of(KITE, EMBER_PEAKS, KITE, HALF_MOON, HALF_MOON, EMBER_PEAKS);
        try {
            p.closeContainer();
            p.setGameMode(GameType.SURVIVAL);
            open(p);
            for (var note : phrase) {
                p.closeContainer();
                var forms = new ArrayList<Form>();
                InfinityJourney.form(p, InfinityJourney.Page.MIRROR_COMPOSER, forms::add);
                var form = (SimpleForm)forms.getFirst();
                var buttons = form.buttons().stream().map(b -> b.text()).toList();
                f.reply(form, Integer.toString(buttons.indexOf("Add " + note.label)));
            }
            var menu = (InfinityJourney.Handler)p.containerMenu;
            c.assertFalse(menu.view.choices().stream().anyMatch(b -> b.label().startsWith("Add ")), "Six-note score is full");
            for (int i = 0; i < phrase.size(); i++) {
                var stack = menu.getSlot(9 + i).getItem();
                c.assertTrue(stack.is(phrase.get(i).banner) && stack.has(DataComponents.BANNER_PATTERNS), "Java out row shows the woven design");
            }
            c.assertTrue(menu.getSlot(18).getItem().is(HALF_MOON.banner), "Return row starts after the turning note");
            wire.drain();
            choose(p, "Undo last note");
            var shorter = phrase.subList(0, 5);
            var heard = new ArrayList<>(pitches(wire.drain()));
            c.runAfterDelay(36, () -> {
                try {
                    heard.addAll(pitches(wire.drain()));
                    c.assertValueEqual(heard, shorter.stream().map(n -> NoteBlock.getPitchFromNote(n.note)).toList(), "Undo plays the shortened motif in order");
                    c.assertValueEqual(MirrorComposer.score(f.reloadDisk(p)), shorter, "Undo saves the exact shortened melody");
                } finally { close.run(); }
                c.succeed();
            });
        } catch (RuntimeException | Error e) { close.run(); throw e; }
    }

    @GameTest(maxTicks = 105) public void linkedBannersPerformTheWrittenRoundTripWithASingleTurn(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var guestWire = new NativePlayConnection(c.getLevel().registryAccess());
        var players = new ModeGameTests();
        var f = new CommonsGameTests();
        var p = players.player(c, "score-stage", wire.connection);
        var guest = players.player(c, "score-viewer", guestWire.connection);
        var stage = new Stage(p);
        Runnable close = () -> {
            stage.close();
            InfinityJourneyGameTests.cleanup(f, p);
            InfinityJourneyGameTests.cleanup(f, guest);
            wire.channel.finishAndReleaseAll();
            guestWire.channel.finishAndReleaseAll();
        };
        try {
            p.closeContainer();
            p.setGameMode(GameType.SURVIVAL);
            stage.placeAll();
            guest.setPos(p.position().add(1, 0, 0));
            open(p);
            choose(p, "Add Kite");
            choose(p, "Add Kite");
            choose(p, "Add Half Moon");
            choose(p, "Perform on my banners");
            stage.tap(KITE);
            stage.tap(HALF_MOON);
            wire.drain();
            guestWire.drain();
        } catch (RuntimeException | Error e) { close.run(); throw e; }
        var server = p.level().getServer();
        int start = server.getTickCount();
        var pulseTicks = new ArrayList<Integer>();
        var heard = new ArrayList<Float>();
        var captions = new ArrayList<String>();
        var ring = new ArrayList<ClientboundLevelParticlesPacket>();
        var pivotLine = new ArrayList<ClientboundLevelParticlesPacket>();
        var flares = new ArrayList<ClientboundLevelParticlesPacket>();
        c.onEachTick(() -> {
            int elapsed = server.getTickCount() - start;
            try {
                var packets = wire.drain();
                for (float pitch : pitches(packets)) {
                    heard.add(pitch);
                    pulseTicks.add(elapsed);
                }
                var labels = overlays(packets);
                captions.addAll(labels.stream().filter(s -> s.startsWith("Out •") || s.startsWith("Mirror turn •") || s.startsWith("Return •")).toList());
                for (var packet : packets) {
                    if (packet instanceof ClientboundLevelParticlesPacket dot) {
                        if (dot.particle() == ParticleTypes.WAX_ON) ring.add(dot);
                        if (dot.particle() == ParticleTypes.END_ROD && labels.contains("Mirror turn • Half Moon")) pivotLine.add(dot);
                        if (dot.particle() == ParticleTypes.GLOW) flares.add(dot);
                    }
                }
                var observed = guestWire.drain();
                c.assertTrue(pitches(observed).isEmpty()
                    && observed.stream().noneMatch(ClientboundLevelParticlesPacket.class::isInstance), "A nearby listener receives no stage notes or particles");
                if (elapsed < 81) return;
                c.assertValueEqual(heard, List.of(KITE, KITE, HALF_MOON, KITE, KITE).stream().map(n -> NoteBlock.getPitchFromNote(n.note)).toList(),
                    "Score sounds out and back, preserving repeated notes and playing the pivot once");
                c.assertValueEqual(pulseTicks.stream().map(t -> t - pulseTicks.getFirst()).toList(), List.of(0, 12, 24, 48, 60), "Only the turning note holds an extra beat");
                c.assertValueEqual(captions, List.of("Out • Kite", "Out • Kite", "Mirror turn • Half Moon", "Return • Kite", "Return • Kite"), "All phases have a named visual cue");
                var pos = stage.pos(HALF_MOON);
                c.assertValueEqual(ring.size(), 16, "One gold ring marks the pivot");
                for (var dot : ring) {
                    c.assertTrue(Math.abs(Math.hypot(dot.x() - pos.getX() - .5, dot.z() - pos.getZ() - .5) - .6) < .0001
                        && Math.abs(dot.y() - pos.getY() - 2.25) < .0001, "Ring encircles the turning banner");
                }
                c.assertValueEqual(pivotLine.size(), 5, "A five-star axis bisects the pivot ring");
                c.assertValueEqual(flares.size(), 2, "Both return arcs land, including the repeated final note");
                c.assertTrue(ConcertStage.current(p) == null, "Performance hands control back");
                stage.tap(EMBER_PEAKS);
                c.assertTrue(overlays(wire.drain()).contains("Ember Peaks • Free play"), "The third, unlinked design works in free play");
            } catch (RuntimeException | Error e) { close.run(); throw e; }
            close.run();
            c.succeed();
        });
    }
}

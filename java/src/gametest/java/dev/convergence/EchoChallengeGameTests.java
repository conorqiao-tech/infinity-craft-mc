package dev.convergence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import org.geysermc.cumulus.form.Form;

import static dev.convergence.BannerConcertGameTests.Stage;
import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.BannerConcertGameTests.dust;
import static dev.convergence.BannerConcertGameTests.pitches;

public class EchoChallengeGameTests {
    private static List<String> overlays(List<Packet<?>> packets) {
        return packets.stream().filter(ClientboundSystemChatPacket.class::isInstance).map(ClientboundSystemChatPacket.class::cast)
            .filter(ClientboundSystemChatPacket::overlay).map(packet -> packet.content().getString()).toList();
    }

    private static void openEcho(ServerPlayer p) {
        InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT);
        choose(p, "Echo Challenge");
    }

    @GameTest public void leavingEchoOrSelectingAScoreRestoresConcertControls(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "echo-menu");
        try (var stage = new Stage(p)) {
            stage.placeAll();
            openEcho(p);
            choose(p, "Start Echo Challenge");
            stage.tap(StarLoom.Chart.KITE);
            openEcho(p);
            choose(p, "Leave challenge");
            openEcho(p);
            choose(p, "Start Echo Challenge");
            for (var chart : StarLoom.Chart.values()) stage.tap(chart);
            InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT);
            choose(p, "Play Moon Dance");
            for (var note : BannerConcert.Song.MOON_DANCE.notes) stage.tap(note);
            c.assertValueEqual(BannerConcert.read(p).flawless(), 1, "Selecting a written score takes control of the same banners");
            c.assertTrue(EchoChallenge.view(p).content().contains("Personal best • 0/3"), "Ending a call early keeps its previous best");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest(maxTicks = 610) public void performerEchoesThePrivateGrowingCallFromNativeCues(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var guestWire = new NativePlayConnection(c.getLevel().registryAccess());
        var f = new CommonsGameTests();
        var players = new ModeGameTests();
        var p = players.player(c, "echo-listener", wire.connection);
        var guest = players.player(c, "echo-neighbor", guestWire.connection);
        var stage = new Stage(p);

        class Performance {
            List<StarLoom.Chart> previous = List.of();
            int attempt;

            void afterCall(int length) {
                c.runAfterDelay(20 * (length + 1) + 8, () -> {
                    try { answer(length); }
                    catch (RuntimeException | Error failure) { close(); throw failure; }
                });
            }

            void answer(int length) {
                var packets = wire.drain();
                var notes = new ArrayList<StarLoom.Chart>();
                var sounds = pitches(packets);
                var labels = overlays(packets);
                var stars = dust(packets);
                c.assertValueEqual(sounds.size(), length, "One private chime per beat");
                int expectedStars = 0;
                for (float pitch : sounds) {
                    var note = Arrays.stream(StarLoom.Chart.values()).filter(chart -> NoteBlock.getPitchFromNote(chart.note) == pitch).findFirst().orElseThrow();
                    notes.add(note);
                    int count = Integer.bitCount(note.goal);
                    c.assertTrue(labels.contains("Listen " + notes.size() + "/" + length + " • " + note.label), "Every audible beat also names its chart");
                    c.assertTrue(stars.size() >= expectedStars + count, "Each beat emits its constellation");
                    var pos = stage.pos(note);
                    for (var dot : stars.subList(expectedStars, expectedStars + count)) {
                        c.assertTrue(Math.abs(dot.x() - (pos.getX() + .5)) < .85 && dot.y() > pos.getY() + 1
                            && Math.abs(dot.z() - (pos.getZ() + .5)) < .85, "Constellation is drawn at the linked banner");
                    }
                    expectedStars += count;
                }
                c.assertValueEqual(stars.size(), expectedStars, "Only the linked stars light during the call");
                c.assertTrue(labels.contains("Your turn • Echo " + "○".repeat(length)), "The last cue gives way to an empty answer strip");
                var nearby = guestWire.drain();
                c.assertTrue(pitches(nearby).isEmpty() && dust(nearby).isEmpty()
                    && overlays(nearby).stream().noneMatch(label -> label.startsWith("Listen ") || label.startsWith("Your turn")), "Neighbor receives no call or progress strip");
                c.assertTrue(notes.subList(0, previous.size()).equals(previous), "A retry keeps every note and a new round extends the previous call");
                previous = List.copyOf(notes);
                if (attempt++ == 0) {
                    var wrong = Arrays.stream(StarLoom.Chart.values()).filter(note -> note != notes.getFirst()).findFirst().orElseThrow();
                    stage.tap(wrong);
                    c.assertTrue(overlays(wire.drain()).contains("Miss • Listen again • 3 notes"), "Miss identifies the retry");
                    afterCall(length);
                    return;
                }
                if (attempt == 2) {
                    stage.tap(notes.getFirst());
                    c.assertTrue(overlays(wire.drain()).contains("Echo ●○○"), "The first tap fills one pip");
                    var forms = new ArrayList<Form>();
                    InfinityJourney.form(p, InfinityJourney.Page.ECHO_CHALLENGE, forms::add);
                    f.reply(forms.getFirst(), "0");
                    wire.drain();
                    afterCall(length);
                    return;
                }
                for (var note : notes) stage.tap(note);
                var echoes = wire.drain();
                c.assertTrue(overlays(echoes).contains("Echo " + "●".repeat(length - 1) + "○"), "The answer strip tracks matching taps");
                c.assertTrue(overlays(echoes).stream().anyMatch(label -> label.contains("●".repeat(length))), "Every circle fills on the last note");
                String record = "Personal best • " + (length - 2) + "/3";
                c.assertTrue(EchoChallenge.view(f.reloadDisk(p)).content().contains(record), "Personal best survives native player-file reload");
                if (length < 5) afterCall(length + 1);
                else {
                    c.assertValueEqual(dust(echoes).size(), expectedStars + 24, "The last answer earns the infinity flourish");
                    c.assertValueEqual(p.getMainHandItem().getCount(), 3, "The performer retains every shard");
                    stage.tap(StarLoom.Chart.KITE);
                    c.assertTrue(overlays(wire.drain()).contains("Kite • Free play"), "The last round restores improvisation");
                    var guestPackets = guestWire.drain();
                    c.assertTrue(dust(guestPackets).isEmpty() && pitches(guestPackets).isEmpty(), "The finale stays private");
                    close();
                    c.succeed();
                }
            }

            void close() {
                stage.close();
                InfinityJourneyGameTests.cleanup(f, p);
                InfinityJourneyGameTests.cleanup(f, guest);
                wire.channel.finishAndReleaseAll();
                guestWire.channel.finishAndReleaseAll();
            }
        }
        var test = new Performance();
        try {
            p.closeContainer();
            p.setGameMode(GameType.SURVIVAL);
            stage.placeAll();
            guest.setPos(p.position().add(1, 0, 0));
            openEcho(p);
            choose(p, "Start Echo Challenge");
            for (var chart : StarLoom.Chart.values()) stage.tap(chart);
            wire.drain();
            guestWire.drain();
            stage.tap(StarLoom.Chart.EMBER_PEAKS);
            c.assertTrue(pitches(wire.drain()).isEmpty(), "An early tap is silent and cannot enter the answer");
            test.afterCall(3);
        } catch (RuntimeException | Error failure) { test.close(); throw failure; }
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

import static dev.convergence.BannerConcertGameTests.Stage;
import static dev.convergence.BannerConcertGameTests.pitches;
import static dev.convergence.EchoChallengeGameTests.overlays;

public class MirrorEchoGameTests {
    private static List<ClientboundLevelParticlesPacket> lights(List<Packet<?>> packets) {
        return packets.stream().filter(ClientboundLevelParticlesPacket.class::isInstance).map(ClientboundLevelParticlesPacket.class::cast)
            .filter(dot -> dot.particle() == ParticleTypes.END_ROD || dot.particle() == ParticleTypes.GLOW).toList();
    }

    @GameTest(maxTicks = 590) public void touchPlayerTracesThreeCallsBackwardsOnTheirOwnStage(GameTestHelper c) {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        var observer = new NativePlayConnection(c.getLevel().registryAccess());
        var players = new ModeGameTests();
        var f = new CommonsGameTests();
        ServerPlayer p = players.player(c, "mirror-solo", wire.connection);
        var guest = players.player(c, "mirror-guest", observer.connection);
        var stage = new Stage(p);

        class Concert {
            List<StarLoom.Chart> call = List.of();
            boolean triedForward;
            void later(int ticks, Runnable action) {
                c.runAfterDelay(ticks, () -> {
                    try { action.run(); }
                    catch (RuntimeException | Error e) { close(); throw e; }
                });
            }
            void listen(int length) {
                later(20 * (length + 1) + 8, () -> {
                    var packets = wire.drain();
                    c.assertTrue(overlays(packets).contains("Your turn • Reverse order " + "○".repeat(length)), "Handoff gives the rule in words");
                    var heard = pitches(packets).stream().map(pitch -> Arrays.stream(StarLoom.Chart.values())
                        .filter(chart -> NoteBlock.getPitchFromNote(chart.note) == pitch).findFirst().orElseThrow()).toList();
                    c.assertValueEqual(heard.size(), length, "The player hears one note per beat");
                    c.assertValueEqual(heard.subList(0, call.size()), call, "Retry reuses the call and a new round extends it");
                    call = heard;
                    if (triedForward) { tap(0); return; }
                    c.assertValueEqual(call.stream().distinct().count(), 3L, "Opening mirror call uses all three banners");
                    // Embedded players have no client movement ticks to advance the shard cooldown.
                    stage.tap(call.getFirst());
                    var miss = wire.drain();
                    c.assertTrue(overlays(miss).contains("Miss • Listen again • 3 notes") && lights(miss).isEmpty(), "Forward answer misses without a mirror star");
                    triedForward = true;
                    listen(3);
                });
            }
            Vec3 tip(int tap) {
                return Vec3.atCenterOf(stage.pos(call.reversed().get(tap))).add(0, 1.65, 0);
            }
            void tap(int index) {
                stage.tap(call.reversed().get(index));
                var hit = wire.drain();
                c.assertValueEqual(pitches(hit).size(), 1, "A real shard interaction sounds the reverse note");
                if (index == 0) {
                    var dots = lights(hit);
                    c.assertValueEqual(dots.size(), 2, "First correct reverse tap emits a star and flare");
                    c.assertTrue(dots.getFirst().particle() == ParticleTypes.END_ROD && dots.getLast().particle() == ParticleTypes.GLOW,
                        "The first star uses native rod and glow particles");
                    checkPos(dots.getLast(), tip(index));
                    later(6, () -> tap(1));
                    return;
                }
                c.assertTrue(lights(hit).isEmpty(), "The traveling arc starts on a later tick");
                later(3, () -> {
                    var arc = new ArrayList<>(lights(wire.drain()));
                    c.assertTrue(!arc.isEmpty() && arc.size() < 8, "Part of the arc has traveled after the first ticks");
                    later(3, () -> {
                        arc.addAll(lights(wire.drain()));
                        c.assertValueEqual(arc.size(), 9, "Eight samples and one destination flare reach the next banner");
                        for (int i = 0; i < 8; i++) {
                            double t = (i + 1) / 8.0;
                            c.assertTrue(arc.get(i).particle() == ParticleTypes.END_ROD, "Arc carries a native silver spark");
                            checkPos(arc.get(i), tip(index - 1).lerp(tip(index), t).add(0, 3 * t * (1 - t), 0));
                        }
                        checkPos(arc.getLast(), tip(index));
                        c.assertTrue(arc.getLast().particle() == ParticleTypes.GLOW, "The arrival flare marks the new banner");
                        if (index + 1 < call.size()) tap(index + 1);
                        else roundDone();
                    });
                });
            }
            void checkPos(ClientboundLevelParticlesPacket dot, Vec3 expected) {
                var actual = new Vec3(dot.x(), dot.y(), dot.z());
                c.assertTrue(actual.distanceToSqr(expected) < .000001, "Arc follows reverse taps on the linked banners");
            }
            void roundDone() {
                var spectators = observer.drain();
                c.assertTrue(lights(spectators).isEmpty() && pitches(spectators).isEmpty(), "A nearby player receives no mirror lights or notes");
                var scores = EchoChallenge.view(f.reloadDisk(p)).content();
                c.assertTrue(scores.contains("Mirror best • " + (call.size() - 2) + "/3") && scores.contains("Personal best • 2/3"),
                    "Mirror progress survives reload separately from a previous classic record");
                if (call.size() < 5) { listen(call.size() + 1); return; }
                stage.tap(StarLoom.Chart.KITE);
                c.assertTrue(overlays(wire.drain()).contains("Kite • Free play"), "Completing the last arc restores ordinary play");
                close();
                c.succeed();
            }
            void close() {
                stage.close();
                InfinityJourneyGameTests.cleanup(f, p);
                InfinityJourneyGameTests.cleanup(f, guest);
                wire.channel.finishAndReleaseAll();
                observer.channel.finishAndReleaseAll();
            }
        }
        var test = new Concert();
        try {
            p.closeContainer();
            p.setGameMode(GameType.SURVIVAL);
            stage.placeAll();
            guest.setPos(p.position().add(1, 0, 0));
            GameModes.state(p).putInt("infinity_echo_best_v1", 2);
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.ECHO_CHALLENGE, forms::add);
            var form = (SimpleForm)forms.getFirst();
            var buttons = form.buttons().stream().map(button -> button.text()).toList();
            int choice = buttons.indexOf("Start Mirror Echo");
            c.assertTrue(choice >= 0 && form.content().contains("last banner"), "Native touch form offers and explains the mirror game");
            f.reply(form, Integer.toString(choice));
            for (var chart : StarLoom.Chart.values()) stage.tap(chart);
            wire.drain();
            observer.drain();
            test.listen(3);
        } catch (RuntimeException | Error e) { test.close(); throw e; }
    }
}

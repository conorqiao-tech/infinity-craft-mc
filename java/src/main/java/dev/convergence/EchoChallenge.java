package dev.convergence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

final class EchoChallenge {
    private enum Style {
        CLASSIC("Echo Challenge", "Echo", "infinity_echo_best_v1"),
        MIRROR("Mirror Echo", "Reverse order", "infinity_mirror_echo_best_v1");

        final String title, cue, bestKey;
        Style(String title, String cue, String bestKey) {
            this.title = title;
            this.cue = cue;
            this.bestKey = bestKey;
        }
    }
    private static final int BEAT_TICKS = 20;
    private static final List<StarLoom.Chart> CHARTS = List.of(StarLoom.Chart.values());
    // All interactions, including form replies, run on the server thread.
    private static final Map<ServerPlayer, EchoChallenge> ACTIVE = new ConcurrentHashMap<>();

    private enum Phase { LINK, LISTEN, ECHO }
    private final Style style;
    private final ServerLevel world;
    private final GameModes.Mode mode;
    private final GameType gameType;
    private final Map<StarLoom.Chart, BlockPos> stage = new EnumMap<>(StarLoom.Chart.class);
    private final List<StarLoom.Chart> call = new ArrayList<>(5);
    private Phase phase = Phase.LINK;
    private int beat, due;

    private EchoChallenge(ServerPlayer p, Style style) {
        this.style = style;
        world = p.level();
        mode = GameModes.current(p);
        gameType = p.gameMode();
    }

    private static int best(ServerPlayer p, Style style) {
        var state = GameModes.state(p);
        if (!state.contains(style.bestKey)) return 0;
        if (!(state.get(style.bestKey) instanceof IntTag)) return -1;
        int value = state.getIntOr(style.bestKey, -1);
        return value >= 0 && value <= 3 ? value : -1;
    }

    boolean valid(ServerPlayer p) {
        return world == p.level() && ServerMenu.allowed(p) && mode == GameModes.current(p) && gameType == p.gameMode();
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var run = ACTIVE.get(p);
        var concert = BannerConcert.read(p);
        var choices = new ArrayList<InfinityJourney.Choice>();
        String body = "Link one of each Star Loom banner with your amethyst shard. In Echo Challenge, copy the call in order. In Mirror Echo, start with the last banner you heard and play the call backwards. Each round adds one note. Answer at your own pace.";
        if (concert == null || concert.song() != BannerConcert.Song.FREE) body += "\nReturn to free play to start a challenge.";
        else {
            for (var style : Style.values()) {
                int record = best(p, style);
                if (record < 0) {
                    body += "\nYour saved " + style.title + " record needs host review.";
                    continue;
                }
                body += "\n" + (style == Style.CLASSIC ? "Personal best" : "Mirror best") + " • " + record + "/3 rounds";
                if (run == null) {
                    var icon = style == Style.CLASSIC ? Items.ECHO_SHARD : Items.SPYGLASS;
                    choices.add(new InfinityJourney.Choice(icon, "Start " + style.title, () -> start(p, style)));
                }
            }
            if (run != null) {
                body += "\nPlaying " + run.style.title;
                if (run.phase == Phase.LINK) body += "\nStill to link • " + run.unlinked();
                else {
                    body += "\nRound " + (run.call.size() - 2) + "/3 • " + run.call.size() + " notes";
                    choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Replay this call", () -> {
                        if (ACTIVE.get(p) == run && run.valid(p)) run.listen(p, "Listen again");
                    }));
                }
                choices.add(new InfinityJourney.Choice(Items.FEATHER, "Leave challenge", () -> {
                    if (ACTIVE.get(p) == run) {
                        leave(p);
                        CommunityServer.say(p, run.style.title + " ended • Free play");
                    }
                }));
            }
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Banner Concert", () -> InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT)));
        return new InfinityJourney.View("Banner Concert • Echo Challenge", body, choices);
    }

    private static void start(ServerPlayer p, Style style) {
        if (ACTIVE.containsKey(p) || !InfinityJourney.emptyScreen(p) || best(p, style) < 0) return;
        var current = BannerConcert.read(p);
        if (current == null || current.song() != BannerConcert.Song.FREE) return;
        MirrorEchoTrail.cancel(p);
        ACTIVE.put(p, new EchoChallenge(p, style));
        CommunityServer.say(p, style.title + " • Tap Kite, Half Moon and Ember Peaks once each to link your stage.");
    }

    private String unlinked() {
        return CHARTS.stream().filter(chart -> !stage.containsKey(chart)).map(chart -> chart.label).collect(Collectors.joining(", "));
    }

    private void appendNote(ServerPlayer p) {
        int index;
        if (style == Style.MIRROR && call.size() == 2) {
            // All three banners open the mirror game, so the first call always changes when reversed.
            for (var chart : CHARTS) {
                if (!call.contains(chart)) { call.add(chart); return; }
            }
        }
        if (call.isEmpty()) index = p.getRandom().nextInt(CHARTS.size());
        else {
            // Choose uniformly among the other charts, so successive flashes stay distinct.
            int last = CHARTS.indexOf(call.getLast());
            index = (last + 1 + p.getRandom().nextInt(CHARTS.size() - 1)) % CHARTS.size();
        }
        call.add(CHARTS.get(index));
    }

    private void listen(ServerPlayer p, String message) {
        phase = Phase.LISTEN;
        beat = 0;
        due = world.getServer().getTickCount() + BEAT_TICKS;
        overlay(p, message + " • " + call.size() + " notes");
    }

    static boolean listening(ServerPlayer p) {
        var run = ACTIVE.get(p);
        return run != null && run.phase == Phase.LISTEN && run.valid(p);
    }

    static boolean tap(ServerPlayer p, BlockPos pos, StarLoom.Chart chart) {
        var run = ACTIVE.get(p);
        if (run == null) return false;
        if (!run.valid(p)) { leave(p); return false; }
        if (run.phase == Phase.LINK) {
            run.stage.put(chart, pos.immutable());
            BannerConcert.showNote(p, pos, chart);
            if (run.stage.size() < CHARTS.size()) overlay(p, "Still to link • " + run.unlinked());
            else {
                for (int i = 0; i < 3; i++) run.appendNote(p);
                run.listen(p, "Round 1 • Listen");
            }
        } else if (run.phase == Phase.ECHO) {
            if (!pos.equals(run.stage.get(chart))) overlay(p, "Play the banners you linked");
            else run.answer(p, pos, chart);
        }
        return true;
    }

    private String pips() { return "●".repeat(beat) + "○".repeat(call.size() - beat); }

    private void answer(ServerPlayer p, BlockPos pos, StarLoom.Chart chart) {
        BannerConcert.showNote(p, pos, chart);
        int index = style == Style.MIRROR ? call.size() - 1 - beat : beat;
        if (call.get(index) != chart) {
            listen(p, "Miss • Listen again");
            return;
        }
        if (style == Style.MIRROR) {
            if (beat == 0) MirrorEchoTrail.star(p, pos);
            else MirrorEchoTrail.connect(p, stage.get(call.get(index + 1)), pos, this);
        }
        beat++;
        if (beat < call.size()) {
            overlay(p, style.cue + " " + pips());
            return;
        }
        String completed = pips();
        int cleared = call.size() - 2;
        int previousBest = best(p, style);
        if (previousBest >= 0 && cleared > previousBest) {
            GameModes.state(p).putInt(style.bestKey, cleared);
            PlayerPersistence.save(p);
        }
        if (cleared == 3) {
            overlay(p, style.cue + " " + completed + " • Complete!");
            BannerConcert.finale(p, pos);
            ACTIVE.remove(p); // Let the final mirror arc reach the last banner.
            CommunityServer.say(p, style.title + " complete! All three rounds matched. Back in free play.");
        } else {
            appendNote(p);
            listen(p, completed + " • Round " + cleared + " matched! Listen");
        }
    }

    private boolean playback(ServerPlayer p, int now) {
        if (phase != Phase.LISTEN || now - due < 0) return true;
        if (beat == call.size()) {
            beat = 0;
            phase = Phase.ECHO;
            overlay(p, "Your turn • " + style.cue + " " + pips());
        } else {
            var chart = call.get(beat);
            var pos = stage.get(chart);
            if (!pos.closerToCenterThan(p.position(), 16) || !world.hasChunkAt(pos)
                    || !(world.getBlockEntity(pos) instanceof BannerBlockEntity banner) || BannerConcert.identify(p, banner) != chart) {
                CommunityServer.say(p, "Your stage is out of range or has changed. Start again beside your three banners.");
                return false;
            }
            BannerConcert.showNote(p, pos, chart);
            beat++;
            overlay(p, "Listen " + beat + "/" + call.size() + " • " + chart.label);
            due = now + BEAT_TICKS;
        }
        return true;
    }

    private static void overlay(ServerPlayer p, String text) { p.sendOverlayMessage(Component.literal(text)); }
    static void leave(ServerPlayer p) {
        ACTIVE.remove(p);
        MirrorEchoTrail.cancel(p);
    }
    static void stopped(MinecraftServer server) {
        ACTIVE.values().removeIf(run -> run.world.getServer() == server);
        MirrorEchoTrail.stopped(server);
    }

    static void tick(MinecraftServer server) {
        MirrorEchoTrail.tick(server);
        if (ACTIVE.isEmpty()) return;
        ACTIVE.entrySet().removeIf(entry -> {
            var run = entry.getValue();
            var p = entry.getKey();
            return run.world.getServer() == server && (!run.valid(p) || !run.playback(p, server.getTickCount()));
        });
    }
}

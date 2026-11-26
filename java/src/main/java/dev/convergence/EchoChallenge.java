package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

final class EchoChallenge extends ConcertStage {
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
    private final Style style;
    private final List<StarLoom.Chart> call = new ArrayList<>(5);
    private boolean answering;
    private int beat, due;

    private EchoChallenge(ServerPlayer p, Style style) {
        super(p, CHARTS);
        this.style = style;
    }

    private static int best(ServerPlayer p, Style style) {
        var state = GameModes.state(p);
        if (!state.contains(style.bestKey)) return 0;
        if (!(state.get(style.bestKey) instanceof IntTag)) return -1;
        int value = state.getIntOr(style.bestKey, -1);
        return value >= 0 && value <= 3 ? value : -1;
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var session = current(p);
        var run = session instanceof EchoChallenge echo ? echo : null;
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
                if (session == null) {
                    var icon = style == Style.CLASSIC ? Items.ECHO_SHARD : Items.SPYGLASS;
                    choices.add(new InfinityJourney.Choice(icon, "Start " + style.title, () -> start(p, style)));
                }
            }
            if (run != null) {
                body += "\nPlaying " + run.style.title;
                if (run.linking()) body += "\nStill to link • " + run.unlinked();
                else {
                    body += "\nRound " + (run.call.size() - 2) + "/3 • " + run.call.size() + " notes";
                    choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Replay this call", () -> {
                        if (current(p) == run && run.valid(p)) run.listen(p, "Listen again");
                    }));
                }
                choices.add(new InfinityJourney.Choice(Items.FEATHER, "Leave challenge", () -> {
                    if (current(p) == run) {
                        leave(p);
                        CommunityServer.say(p, run.style.title + " ended • Free play");
                    }
                }));
            }
        }
        choices.add(new InfinityJourney.Choice(Items.WRITABLE_BOOK, "Mirror Composer • write a melody",
            () -> InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER)));
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Banner Concert", () -> InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT)));
        return new InfinityJourney.View("Banner Concert • Echo Challenge", body, choices);
    }

    private static void start(ServerPlayer p, Style style) {
        if (best(p, style) >= 0) ConcertStage.start(p, new EchoChallenge(p, style), style.title);
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
        answering = false;
        beat = 0;
        due = world.getServer().getTickCount() + BEAT_TICKS;
        overlay(p, message + " • " + call.size() + " notes");
    }

    @Override void linked(ServerPlayer p) {
        for (int i = 0; i < 3; i++) appendNote(p);
        listen(p, "Round 1 • Listen");
    }
    @Override boolean takingAnswers() { return answering; }

    private String pips() { return "●".repeat(beat) + "○".repeat(call.size() - beat); }

    @Override void answer(ServerPlayer p, BlockPos pos, StarLoom.Chart chart) {
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
            finish(p);
            CommunityServer.say(p, style.title + " complete! All three rounds matched. Back in free play.");
        } else {
            appendNote(p);
            listen(p, completed + " • Round " + cleared + " matched! Listen");
        }
    }

    @Override boolean playback(ServerPlayer p, int now) {
        if (answering || now - due < 0) return true;
        if (beat == call.size()) {
            beat = 0;
            answering = true;
            overlay(p, "Your turn • " + style.cue + " " + pips());
        } else {
            var chart = call.get(beat);
            var pos = playable(p, chart);
            if (pos == null) return false;
            BannerConcert.showNote(p, pos, chart);
            beat++;
            overlay(p, "Listen " + beat + "/" + call.size() + " • " + chart.label);
            due = now + BEAT_TICKS;
        }
        return true;
    }
}

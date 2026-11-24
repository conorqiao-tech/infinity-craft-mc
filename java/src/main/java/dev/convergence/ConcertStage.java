package dev.convergence;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

/** One private performance owns the performer's banner input until they leave or it finishes. */
abstract class ConcertStage {
    private static final Map<ServerPlayer, ConcertStage> ACTIVE = new ConcurrentHashMap<>();
    private final EnumSet<StarLoom.Chart> instruments;
    final Map<StarLoom.Chart, BlockPos> stage = new EnumMap<>(StarLoom.Chart.class);
    final ServerLevel world;
    private final GameModes.Mode mode;
    private final GameType gameType;

    ConcertStage(ServerPlayer p, Collection<StarLoom.Chart> instruments) {
        this.instruments = EnumSet.copyOf(instruments);
        world = p.level();
        mode = GameModes.current(p);
        gameType = p.gameMode();
    }

    static ConcertStage current(ServerPlayer p) { return ACTIVE.get(p); }
    static void start(ServerPlayer p, ConcertStage run, String title) {
        if (!InfinityJourney.emptyScreen(p) || current(p) != null) return;
        var concert = BannerConcert.read(p);
        if (concert == null || concert.song() != BannerConcert.Song.FREE) return;
        EncoreDuet.leave(p);
        MirrorEchoTrail.cancel(p);
        InfinityChimes.stopPlayback(p);
        ACTIVE.put(p, run);
        CommunityServer.say(p, title + " • Use your amethyst shard to link " + run.unlinked());
    }
    final boolean valid(ServerPlayer p) {
        return world == p.level() && ServerMenu.allowed(p) && mode == GameModes.current(p) && gameType == p.gameMode();
    }
    final boolean linking() { return stage.size() < instruments.size(); }
    final String unlinked() {
        return instruments.stream().filter(chart -> !stage.containsKey(chart)).map(chart -> chart.label).collect(Collectors.joining(", "));
    }

    /** A missing or changed banner ends playback before the next cue. */
    final BlockPos playable(ServerPlayer p, StarLoom.Chart chart) {
        var pos = stage.get(chart);
        if (pos.closerToCenterThan(p.position(), 16) && world.hasChunkAt(pos)
                && world.getBlockEntity(pos) instanceof BannerBlockEntity banner && BannerConcert.identify(p, banner) == chart) return pos;
        CommunityServer.say(p, "Your stage is out of range or has changed. Start again beside your banners.");
        return null;
    }

    abstract void linked(ServerPlayer p);
    abstract boolean playback(ServerPlayer p, int now);
    boolean takingAnswers() { return false; }
    void answer(ServerPlayer p, BlockPos pos, StarLoom.Chart chart) {}

    static boolean listening(ServerPlayer p) {
        var run = current(p);
        return run != null && run.valid(p) && !run.linking() && !run.takingAnswers();
    }

    static boolean tap(ServerPlayer p, BlockPos pos, StarLoom.Chart chart) {
        var run = current(p);
        if (run == null) return false;
        if (!run.valid(p)) { leave(p); return false; }
        if (run.linking()) {
            if (run.instruments.contains(chart)) {
                run.stage.put(chart, pos.immutable());
                BannerConcert.showNote(p, pos, chart);
            }
            if (run.linking()) overlay(p, "Still to link • " + run.unlinked());
            else run.linked(p);
        } else if (run.takingAnswers()) {
            if (pos.equals(run.stage.get(chart))) run.answer(p, pos, chart);
            else overlay(p, "Play the banners you linked");
        }
        return true;
    }

    static void overlay(ServerPlayer p, String text) { p.sendOverlayMessage(Component.literal(text)); }
    final void finish(ServerPlayer p) { ACTIVE.remove(p, this); } // The final arc can still land.
    static void leave(ServerPlayer p) { ACTIVE.remove(p); MirrorEchoTrail.cancel(p); }
    static void stopped(MinecraftServer server) {
        ACTIVE.values().removeIf(run -> run.world.getServer() == server);
        MirrorEchoTrail.stopped(server);
    }
    static void tick(MinecraftServer server) {
        MirrorEchoTrail.tick(server);
        if (ACTIVE.isEmpty()) return;
        ACTIVE.entrySet().removeIf(entry -> {
            var p = entry.getKey();
            var run = entry.getValue();
            return run.world.getServer() == server && (!run.valid(p) || !run.linking() && !run.playback(p, server.getTickCount()));
        });
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.phys.Vec3;

import static dev.convergence.StarLoom.Chart.EMBER_PEAKS;
import static dev.convergence.StarLoom.Chart.HALF_MOON;
import static dev.convergence.StarLoom.Chart.KITE;

final class BannerConcert {
    static final String KEY = "infinity_banner_concert_v1";

    enum Song {
        FREE(0, "Free play"),
        MOON_DANCE(1, "Moon Dance", KITE, HALF_MOON, KITE),
        CINDER_WALTZ(2, "Cinder Waltz", EMBER_PEAKS, HALF_MOON, EMBER_PEAKS, KITE),
        INFINITY_REFRAIN(3, "Infinity Refrain", KITE, HALF_MOON, EMBER_PEAKS, KITE, EMBER_PEAKS, HALF_MOON, KITE);

        final int id;
        final String title;
        final List<StarLoom.Chart> notes;

        Song(int id, String title, StarLoom.Chart... notes) {
            this.id = id;
            this.title = title;
            this.notes = List.of(notes);
        }
        int mark() { return 1 << (id - 1); }
        String score() { return notes.stream().map(note -> note.label).collect(Collectors.joining(" → ")); }
    }

    record Performance(Song song, int next, boolean missed, int finished, int flawless) {
        Performance start(Song choice) { return new Performance(choice, 0, false, finished, flawless); }
        String cue() { return song.title + " • " + (next + 1) + "/" + song.notes.size() + " • " + song.notes.get(next).label; }
    }
    private BannerConcert() {}

    static Performance read(ServerPlayer p) {
        var tag = GameModes.state(p).get(KEY);
        if (tag == null) return new Performance(Song.FREE, 0, false, 0, 0);
        if (!(tag instanceof IntArrayTag array) || array.size() != 5) return null;
        int[] n = array.getAsIntArray();
        for (var song : Song.values()) {
            if (song.id != n[0]) continue;
            if (n[1] < 0 || n[1] >= Math.max(1, song.notes.size()) || n[2] < 0 || n[2] > 1
                    || song == Song.FREE && n[2] != 0 || (n[3] & ~7) != 0 || (n[4] & ~n[3]) != 0) return null;
            return new Performance(song, n[1], n[2] == 1, n[3], n[4]);
        }
        return null;
    }

    private static void write(ServerPlayer p, Performance run) {
        GameModes.state(p).putIntArray(KEY, new int[]{run.song.id, run.next, run.missed ? 1 : 0, run.finished, run.flawless});
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var run = read(p);
        var choices = new ArrayList<InfinityJourney.Choice>();
        var body = new StringBuilder("Place Star Loom banners where you can reach them. Hold an amethyst shard and use it on a banner to play its note and light its constellation. In free play, tap them in any order.");
        if (run == null) body.append("\nYour saved concert needs host review.");
        else {
            if (run.song != Song.FREE) body.append("\nNext banner • ").append(run.cue());
            body.append("\n\nPlay each phrase in order. A missed note keeps your place. Play every cue on the first try to earn Flawless.");
            for (var song : Song.values()) {
                if (song == Song.FREE) continue;
                String record = (run.flawless & song.mark()) != 0 ? " • Flawless" : (run.finished & song.mark()) != 0 ? " • Played" : "";
                body.append("\n\n").append(song.title).append(record).append("\n").append(song.score());
                String label = (song == run.song ? "Restart " : "Play ") + song.title;
                choices.add(new InfinityJourney.Choice(Items.AMETHYST_SHARD, label, "textures/items/amethyst_shard", () -> select(p, run, song)));
            }
            if (run.song != Song.FREE)
                choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Return to free play", () -> select(p, run, Song.FREE)));
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Star Loom", () -> InfinityJourney.open(p, InfinityJourney.Page.STAR_LOOM)));
        return new InfinityJourney.View("Star Loom • Banner Concert", body.toString(), choices);
    }

    private static void select(ServerPlayer p, Performance expected, Song song) {
        if (!InfinityJourney.emptyScreen(p)) return;
        if (!expected.equals(read(p))) {
            InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT);
            return;
        }
        var run = expected.start(song);
        write(p, run);
        CommunityServer.say(p, song == Song.FREE ? "Banner Concert • free play" : "Tap a banner with your amethyst shard. " + run.cue());
    }

    // Match what is woven into the placed banner, including equivalent hand-woven or renamed copies.
    private static StarLoom.Chart identify(ServerPlayer p, BannerBlockEntity banner) {
        var base = Items.BANNER.pick(banner.getBaseColor());
        for (var chart : StarLoom.Chart.values()) {
            if (chart.banner != base) continue;
            var recipe = p.level().getServer().getRecipeManager().byKey(chart.recipe).orElse(null);
            if (recipe == null || !(recipe.value() instanceof ShapelessRecipe fixed)) continue;
            var design = fixed.assemble(CraftingInput.EMPTY);
            if (design.is(base) && !banner.getPatterns().layers().isEmpty()
                    && banner.getPatterns().equals(design.get(DataComponents.BANNER_PATTERNS))) return chart;
        }
        return null;
    }

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND
                    || !p.getMainHandItem().is(Items.AMETHYST_SHARD) || !InfinityJourney.emptyScreen(p)
                    || !(world.getBlockEntity(hit.getBlockPos()) instanceof BannerBlockEntity banner)) return InteractionResult.PASS;
            var chart = identify(p, banner);
            if (chart == null) return InteractionResult.PASS;
            var run = read(p);
            if (run == null) return InteractionResult.PASS;
            if (!p.getCooldowns().isOnCooldown(p.getMainHandItem())) {
                p.getCooldowns().addCooldown(p.getMainHandItem(), 5);
                play(p, hit.getBlockPos(), chart, run);
            }
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private static void play(ServerPlayer p, BlockPos pos, StarLoom.Chart chart, Performance run) {
        var center = Vec3.atCenterOf(pos).add(0, 1.25, 0);
        var towardPlayer = new Vec3(p.getX() - center.x, 0, p.getZ() - center.z).normalize();
        if (towardPlayer.lengthSqr() < .01) towardPlayer = new Vec3(0, 0, 1);
        center = center.add(towardPlayer.scale(.55));
        var right = new Vec3(towardPlayer.z, 0, -towardPlayer.x);
        var ink = new DustParticleOptions(chart.ink, 1.15f);
        for (int cell = 0; cell < 9; cell++) {
            if (StarLoom.lit(chart.goal, cell))
                dot(p, ink, center, right, (cell % 3 - 1) * .24, (1 - cell / 3) * .24);
        }
        p.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_CHIME, SoundSource.RECORDS,
            center.x, center.y, center.z, 1f, NoteBlock.getPitchFromNote(chart.note), p.getRandom().nextLong()));

        if (run.song == Song.FREE) {
            p.sendOverlayMessage(Component.literal(chart.label + " • Free play"));
            return;
        }
        if (chart != run.song.notes.get(run.next)) {
            if (!run.missed) write(p, new Performance(run.song, run.next, true, run.finished, run.flawless));
            p.sendOverlayMessage(Component.literal("Try again • " + run.cue()));
        } else if (run.next + 1 < run.song.notes.size()) {
            var next = new Performance(run.song, run.next + 1, run.missed, run.finished, run.flawless);
            write(p, next);
            p.sendOverlayMessage(Component.literal(next.cue()));
        } else {
            int mark = run.song.mark();
            write(p, new Performance(Song.FREE, 0, false, run.finished | mark, run.missed ? run.flawless : run.flawless | mark));
            PlayerPersistence.save(p);
            CommunityServer.say(p, run.song.title + (run.missed ? " • Played!" : " • Flawless!") + " Back in free play.");
            var gold = new DustParticleOptions(0xFFD070, .9f);
            var teal = new DustParticleOptions(0x66DAD1, .9f);
            for (int i = 0; i < 24; i++) {
                double angle = i * Math.PI / 12;
                dot(p, i < 12 ? gold : teal, center, right, .86 * Math.sin(angle), .64 + .28 * Math.sin(2 * angle));
            }
        }
    }

    private static void dot(ServerPlayer p, DustParticleOptions ink, Vec3 center, Vec3 right, double x, double y) {
        p.level().sendParticles(p, ink, false, false, center.x + right.x * x, center.y + y, center.z + right.z * x, 1, 0, 0, 0, 0);
    }
}

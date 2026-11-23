package dev.convergence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.phys.Vec3;

final class MirrorComposer extends ConcertStage {
    private static final String KEY = "infinity_mirror_motif_v1";
    private static final int MAX_NOTES = 6;
    private static final int BEAT_TICKS = 12;
    private final List<StarLoom.Chart> motif;
    private int beat, due;

    private MirrorComposer(ServerPlayer p, List<StarLoom.Chart> motif) {
        super(p, motif);
        this.motif = List.copyOf(motif);
    }

    // Store the pitches the player actually wrote, independently of enum ordering.
    static List<StarLoom.Chart> score(ServerPlayer p) {
        var tag = GameModes.state(p).get(KEY);
        if (tag == null) return List.of();
        if (!(tag instanceof IntArrayTag pitches) || pitches.size() > MAX_NOTES) return null;
        var notes = new ArrayList<StarLoom.Chart>();
        for (int pitch : pitches.getAsIntArray()) {
            var chart = Arrays.stream(StarLoom.Chart.values()).filter(c -> c.note == pitch).findFirst().orElse(null);
            if (chart == null) return null;
            notes.add(chart);
        }
        return List.copyOf(notes);
    }

    private static String names(List<StarLoom.Chart> notes) {
        return notes.stream().map(chart -> chart.label).collect(Collectors.joining(" → "));
    }
    private static List<StarLoom.Chart> returning(List<StarLoom.Chart> notes) {
        return notes.subList(0, notes.size() - 1).reversed();
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var notes = score(p);
        var run = current(p);
        var choices = new ArrayList<InfinityJourney.Choice>();
        String body = "Choose two to six notes. Your banners play them out and back, sounding the turning note once.";
        if (notes == null) body += "\nYour saved motif needs host review.";
        else {
            body += "\nWritten • " + notes.size() + "/" + MAX_NOTES;
            if (!notes.isEmpty()) body += "\nOut • " + names(notes);
            if (notes.size() >= 2) body += "\nReturn • " + names(returning(notes));
            if (run instanceof MirrorComposer show) {
                body += show.linking() ? "\nStill to link • " + show.unlinked() : "\nYour banners are performing.";
                choices.add(new InfinityJourney.Choice(Items.FEATHER, "Stop performance", () -> {
                    if (current(p) == show) leave(p);
                    InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER);
                }));
            } else if (run != null) body += "\nFinish or leave your Echo Challenge to compose.";
            else {
                if (notes.size() < MAX_NOTES) {
                    for (var chart : StarLoom.Chart.values()) {
                        choices.add(new InfinityJourney.Choice(chart.banner, "Add " + chart.label, () -> {
                            var next = new ArrayList<>(notes);
                            next.add(chart);
                            edit(p, notes, next, List.of(chart));
                        }));
                    }
                }
                if (!notes.isEmpty()) {
                    var shorter = notes.subList(0, notes.size() - 1);
                    choices.add(new InfinityJourney.Choice(Items.FEATHER, "Undo last note • " + notes.getLast().label,
                        () -> edit(p, notes, shorter, shorter)));
                }
                if (notes.size() >= 2) choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Perform on my banners", () -> {
                    if (notes.equals(score(p))) start(p, new MirrorComposer(p, notes), "Mirror Composer");
                    else InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER);
                }));
            }
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Echo Challenge", () -> InfinityJourney.open(p, InfinityJourney.Page.ECHO_CHALLENGE)));
        return new InfinityJourney.View("Star Loom • Mirror Composer", body, choices);
    }

    private static void edit(ServerPlayer p, List<StarLoom.Chart> expected, List<StarLoom.Chart> next, List<StarLoom.Chart> preview) {
        if (!InfinityJourney.emptyScreen(p)) return;
        if (current(p) == null && expected.equals(score(p))) {
            if (next.isEmpty()) GameModes.state(p).remove(KEY);
            else GameModes.state(p).putIntArray(KEY, next.stream().mapToInt(c -> c.note).toArray());
            PlayerPersistence.save(p);
            InfinityChimes.play(p, preview.stream().map(c -> c.note).toList());
        }
        InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER);
    }

    static void draw(ServerPlayer p, SimpleContainer chest) {
        var notes = score(p);
        if (notes == null || notes.isEmpty()) return;
        drawRow(p, chest, 9, "Out", notes);
        drawRow(p, chest, 18, "Return", returning(notes));
    }

    private static void drawRow(ServerPlayer p, SimpleContainer chest, int slot, String direction, List<StarLoom.Chart> notes) {
        for (var chart : notes) {
            var recipe = p.level().getServer().getRecipeManager().byKey(chart.recipe).orElse(null);
            var banner = recipe != null && recipe.value() instanceof ShapelessRecipe fixed
                ? fixed.assemble(CraftingInput.EMPTY).copy() : new ItemStack(chart.banner);
            banner.set(DataComponents.CUSTOM_NAME, Component.literal(direction + " • " + chart.label));
            chest.setItem(slot++, banner);
        }
    }

    @Override void linked(ServerPlayer p) {
        due = world.getServer().getTickCount() + BEAT_TICKS;
        overlay(p, "Mirror Composer • Watch your banners");
    }

    @Override boolean playback(ServerPlayer p, int now) {
        if (now - due < 0) return true;
        int turn = motif.size() - 1;
        int index = beat <= turn ? beat : 2 * turn - beat;
        var chart = motif.get(index);
        var pos = playable(p, chart);
        if (pos == null) return false;
        BannerConcert.showNote(p, pos, chart);
        String direction = "Out";
        if (beat == turn) {
            direction = "Mirror turn";
            turnLight(p, pos);
        } else if (beat > turn) {
            direction = "Return";
            MirrorEchoTrail.connect(p, stage.get(motif.get(index + 1)), pos, this);
        }
        overlay(p, direction + " • " + chart.label);
        if (beat == 2 * turn) {
            BannerConcert.finale(p, pos);
            CommunityServer.say(p, "Mirror Composer • Your melody is back home. Back in free play.");
            return false;
        }
        due = now + (beat == turn ? 2 * BEAT_TICKS : BEAT_TICKS);
        beat++;
        return true;
    }

    private static void turnLight(ServerPlayer p, BlockPos pos) {
        var center = Vec3.atCenterOf(pos).add(0, 1.75, 0);
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            p.level().sendParticles(p, ParticleTypes.WAX_ON, false, false,
                center.x + .6 * Math.cos(angle), center.y, center.z + .6 * Math.sin(angle), 1, 0, 0, 0, 0);
        }
        for (int i = -2; i <= 2; i++)
            p.level().sendParticles(p, ParticleTypes.END_ROD, false, false, center.x, center.y + i * .18, center.z, 1, 0, 0, 0, 0);
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;

/** Nine lights per chart. A press flips the selected light and its edge neighbors. */
final class StarLoom {
    private static final List<Integer> PLAY_SLOTS = List.of(23, 24, 25, 32, 33, 34, 41, 42, 43);
    private static final int RESTART_SLOT = 49, BACK_SLOT = 53;

    enum Chart {
        KITE("kite", "Kite", InfinityJourney.Page.KITE, 0b101_011_100, 0b010_101_010, 2,
            Items.BANNER.blue(), 0x60DCE9, 6, "6 blue wool, 1 stick, 1 cyan dye and 1 white dye"),
        HALF_MOON("half_moon", "Half Moon", InfinityJourney.Page.HALF_MOON, 0b111_100_110, 0b010_110_010, 4,
            Items.BANNER.purple(), 0xC6A6FF, 10, "6 purple wool, 1 stick, 1 yellow dye and 1 purple dye"),
        EMBER_PEAKS("ember_peaks", "Ember Peaks", InfinityJourney.Page.EMBER_PEAKS, 0b001_111_110, 0b101_111_111, 6,
            Items.BANNER.black(), 0xFF9857, 13, "6 black wool, 1 stick, 1 yellow dye and 1 orange dye");

        final String label, key, materials;
        final InfinityJourney.Page page;
        final int start, goal, par, ink, note;
        final Item banner;
        final ResourceKey<Recipe<?>> recipe;

        Chart(String id, String label, InfinityJourney.Page page, int start, int goal, int par, Item banner, int ink, int note, String materials) {
            this.label = label; this.page = page; this.start = start; this.goal = goal; this.par = par;
            this.banner = banner; this.ink = ink; this.note = note; this.materials = materials;
            key = "infinity_star_loom_v1_" + id;
            recipe = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("convergence", "star_loom/" + id));
        }
        static Chart forPage(InfinityJourney.Page page) {
            return switch (page) {
                case KITE -> KITE;
                case HALF_MOON -> HALF_MOON;
                case EMBER_PEAKS -> EMBER_PEAKS;
                default -> null;
            };
        }
    }

    record Run(int lights, int moves, int best) {}
    private StarLoom() {}

    static Run read(ServerPlayer player, Chart chart) {
        var tag = GameModes.state(player).get(chart.key);
        if (tag == null) return new Run(chart.start, 0, 0);
        if (!(tag instanceof IntArrayTag values) || values.size() != 3) return null;
        var data = values.getAsIntArray();
        if (data[0] < 0 || data[0] > 511 || data[1] < 0 || data[2] < 0) return null;
        return new Run(data[0], data[1], data[2]);
    }

    // The top-left star is the high bit, so a binary chart reads like the visible rows.
    private static int bit(int cell) { return 1 << (8 - cell); }
    static boolean lit(int board, int cell) { return (board & bit(cell)) != 0; }
    private static String coordinate(int cell) { return "" + (char)('A' + cell / 3) + (1 + cell % 3); }
    private static String state(int board, int cell) { return lit(board, cell) ? "lit" : "dark"; }

    private static Run flip(Chart chart, Run run, int cell) {
        int lights = run.lights();
        for (int i = 0; i < 9; i++) {
            if (Math.abs(cell / 3 - i / 3) + Math.abs(cell % 3 - i % 3) <= 1) lights ^= bit(i);
        }
        int moves = (int)Math.min(Integer.MAX_VALUE, (long)run.moves() + 1);
        int best = run.best();
        if (lights == chart.goal) best = best == 0 ? moves : Math.min(best, moves);
        return new Run(lights, moves, best);
    }

    private static void update(ServerPlayer p, Chart chart, Run expected, Run next) {
        if (!InfinityJourney.emptyScreen(p)) return;
        if (expected.equals(read(p, chart))) {
            GameModes.state(p).putIntArray(chart.key, new int[]{next.lights(), next.moves(), next.best()});
            if (next.lights() == chart.goal) {
                p.awardRecipesByKey(List.of(chart.recipe));
                // Ongoing games follow normal player saves. Save the finish and the recipe award together.
                PlayerPersistence.save(p);
                CommunityServer.say(p, chart.label + " matched in " + next.moves() + " moves! Your banner recipe is ready.");
            }
        }
        InfinityJourney.open(p, chart.page);
    }

    static InfinityJourney.View gallery(ServerPlayer p) {
        var choices = new ArrayList<InfinityJourney.Choice>();
        for (var chart : Chart.values()) {
            var run = read(p, chart);
            String best = run != null && run.best() > 0 ? " • best " + run.best() : "";
            choices.add(new InfinityJourney.Choice(chart.banner, chart.label + " • par " + chart.par + best, () -> InfinityJourney.open(p, chart.page)));
        }
        choices.add(new InfinityJourney.Choice(Items.AMETHYST_SHARD, "Banner Concert • play your banners", "textures/items/amethyst_shard",
            () -> InfinityJourney.open(p, InfinityJourney.Page.BANNER_CONCERT)));
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(p)));
        return new InfinityJourney.View("Infinity Star Loom", "Match a constellation by flipping stars. Each chart remembers your board and personal best. Solve one to learn its banner recipe.", choices);
    }

    static InfinityJourney.View board(ServerPlayer p, Chart chart) {
        var run = read(p, chart);
        var choices = new ArrayList<InfinityJourney.Choice>();
        var slots = new ArrayList<Integer>();
        var body = new StringBuilder();
        if (run == null) {
            body.append("Your saved chart needs host review.");
        } else {
            boolean finished = run.lights() == chart.goal;
            body.append(finished ? "Constellation matched!" : "Match the target. Flip a star to switch it and its neighbors above, below, left and right.")
                .append("\nMoves ").append(run.moves()).append(" • Par ").append(chart.par);
            if (run.best() > 0) body.append(" • Best ").append(run.best());
            body.append("\nTarget  /  Your board    (+ lit, - dark)");
            for (int row = 0; row < 3; row++) {
                body.append("\n").append((char)('A' + row)).append("   ");
                for (int col = 0; col < 3; col++) body.append(lit(chart.goal, 3 * row + col) ? "+ " : "- ");
                body.append("   /   ");
                for (int col = 0; col < 3; col++) body.append(lit(run.lights(), 3 * row + col) ? "+ " : "- ");
            }
            if (!finished) {
                for (int i = 0; i < 9; i++) {
                    final int cell = i;
                    boolean on = lit(run.lights(), cell);
                    String label = "Flip " + coordinate(cell) + " • " + state(run.lights(), cell);
                    choices.add(new InfinityJourney.Choice(on ? Items.STAINED_GLASS.cyan() : Items.STAINED_GLASS.black(),
                        label, on ? "textures/blocks/glass_cyan" : "textures/blocks/glass_black", () -> update(p, chart, run, flip(chart, run, cell))));
                }
                slots.addAll(PLAY_SLOTS);
            }
            if (run.best() > 0) body.append("\nBanner recipe at a crafting table: ").append(chart.materials).append(". One ingredient per slot, in any order.");
            if (run.moves() > 0) {
                choices.add(new InfinityJourney.Choice(Items.COMPASS, finished ? "Play again" : "Restart this chart", () -> update(p, chart, run, new Run(chart.start, 0, run.best()))));
                slots.add(RESTART_SLOT);
            }
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Star Loom", () -> InfinityJourney.open(p, InfinityJourney.Page.STAR_LOOM)));
        slots.add(BACK_SLOT);
        return new InfinityJourney.View("Star Loom • " + chart.label, body.toString(), choices, slots);
    }

    static void drawChart(SimpleContainer chest, ServerPlayer p, Chart chart) {
        var run = read(p, chart);
        if (run == null) return;
        CreativeStudio.icon(chest, 11, Items.GLOWSTONE_DUST, "Target constellation");
        CreativeStudio.icon(chest, 15, Items.AMETHYST_SHARD, "Your board");
        for (int i = 0; i < 9; i++) {
            int slot = PLAY_SLOTS.get(i);
            CreativeStudio.icon(chest, slot - 4, lit(chart.goal, i) ? Items.STAINED_GLASS.yellow() : Items.STAINED_GLASS.black(),
                "Target " + coordinate(i) + " • " + state(chart.goal, i));
            if (run.lights() == chart.goal)
                CreativeStudio.icon(chest, slot, lit(chart.goal, i) ? Items.STAINED_GLASS.cyan() : Items.STAINED_GLASS.black(),
                    coordinate(i) + " • " + state(chart.goal, i));
        }
    }
}

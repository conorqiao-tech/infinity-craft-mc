package dev.convergence;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.geysermc.cumulus.form.Form;

public class StarLoomGameTests {
    private static void click(ServerPlayer p, int slot) {
        ((InfinityJourney.Handler)p.containerMenu).clicked(slot, 0, ContainerInput.PICKUP, p);
    }

    private static void choose(ServerPlayer p, String labelPrefix) {
        var menu = (InfinityJourney.Handler)p.containerMenu;
        for (int slot = 0; slot < 54; slot++) {
            if (menu.getSlot(slot).getItem().getHoverName().getString().startsWith(labelPrefix)) {
                click(p, slot);
                return;
            }
        }
        throw new AssertionError("Missing control " + labelPrefix);
    }

    @GameTest public void threeJavaBoardsUnlockRecipesAndSavePersonalBests(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "star-weaver");
        try {
            InfinityJourney.open(p);
            choose(p, "Infinity Star Loom");
            var solutions = List.of(List.of(24, 41), List.of(23, 33, 34, 42), List.of(23, 24, 32, 34, 42, 43));
            for (var chart : StarLoom.Chart.values()) {
                choose(p, chart.label);
                c.assertFalse(GameModes.state(p).contains(chart.key), "Viewing the chart creates no saved moves");
                c.assertFalse(p.getRecipeBook().contains(chart.recipe), "Unplayed chart starts without its recipe");
                click(p, 19);
                c.assertValueEqual(StarLoom.read(p, chart).moves(), 0, "The gold target grid is read only");
                var route = solutions.get(chart.ordinal());
                for (int slot : route) click(p, slot);
                c.assertValueEqual(StarLoom.read(p, chart), new StarLoom.Run(chart.goal, chart.par, chart.par), "Java cells reach " + chart.label + " in par");
                c.assertTrue(p.getRecipeBook().contains(chart.recipe), "The first finish teaches the corresponding banner");
                choose(p, "Back to Star Loom");
            }
            choose(p, "Kite");
            choose(p, "Play again");
            click(p, 23);
            click(p, 23);
            click(p, 24);
            click(p, 41);
            var restored = f.reloadDisk(p);
            c.assertValueEqual(StarLoom.read(restored, StarLoom.Chart.KITE), new StarLoom.Run(StarLoom.Chart.KITE.goal, 4, 2), "Slower replay keeps the personal best across disk reload");
            c.assertTrue(restored.getRecipeBook().contains(StarLoom.Chart.KITE.recipe), "The learned recipe persists with the finished chart");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    private static Form kiteForm(ServerPlayer p) {
        var forms = new ArrayList<Form>();
        InfinityJourney.form(p, InfinityJourney.Page.KITE, forms::add);
        return forms.getFirst();
    }

    @GameTest public void bedrockGridShowsBothBoardsAndResumesTheSameRound(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "star-touch");
        var chart = StarLoom.Chart.KITE;
        try {
            var form = kiteForm(p);
            var codec = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec();
            var json = JsonParser.parseString(codec.jsonData(form)).getAsJsonObject();
            c.assertTrue(json.get("content").getAsString().contains("A   - + -    /   + - +"), "Bedrock has a row-aligned target and playable board");
            c.assertTrue(json.getAsJsonArray("buttons").get(1).getAsJsonObject().get("text").getAsString().startsWith("Flip A2"), "The second touch control is the top middle star");
            f.reply(form, "1");
            f.reply(form, "1");
            c.assertValueEqual(StarLoom.read(p, chart).moves(), 1, "A touch registers once");
            p.closeContainer();
            PlayerPersistence.save(p);
            c.assertValueEqual(StarLoom.read(f.reloadDisk(p), chart).moves(), 1, "Ongoing chart survives an ordinary native save");
            f.reply(kiteForm(p), "6");
            c.assertValueEqual(StarLoom.read(p, chart).best(), 2, "Bedrock reaches the Kite target from its resumed board");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void allThreeBannerRecipesMakeTheirNamedLayeredDesigns(GameTestHelper c) {
        var manager = c.getLevel().getServer().getRecipeManager();
        var bases = List.of(DyeColor.BLUE, DyeColor.PURPLE, DyeColor.BLACK);
        var dyes = List.of(List.of(DyeColor.CYAN, DyeColor.WHITE), List.of(DyeColor.YELLOW, DyeColor.PURPLE), List.of(DyeColor.YELLOW, DyeColor.ORANGE));
        var shapes = List.of(List.of("stripe_center","rhombus"), List.of("circle","half_vertical_right"), List.of("triangle_bottom","triangles_bottom"));
        for (var chart : StarLoom.Chart.values()) {
            int i = chart.ordinal();
            var grid = new ArrayList<ItemStack>();
            Item wool = Items.WOOL.pick(bases.get(i));
            for (int j = 0; j < 6; j++) grid.add(new ItemStack(wool));
            for (var dye : dyes.get(i)) grid.add(new ItemStack(Items.DYE.pick(dye)));
            grid.add(new ItemStack(Items.STICK));
            var input = CraftingInput.of(3, 3, grid);
            var recipe = manager.getRecipeFor(RecipeType.CRAFTING, input, c.getLevel()).orElseThrow();
            c.assertValueEqual(recipe.id(), chart.recipe, "Native crafting selects the chart recipe");
            var result = recipe.value().assemble(input);
            c.assertTrue(result.is(Items.BANNER.pick(bases.get(i))) && result.getCount() == 1, "Craft produces one placeable banner");
            c.assertValueEqual(result.getHoverName().getString(), "Star Loom • " + chart.label, "The design keeps its name");
            var layers = result.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers();
            c.assertValueEqual(layers.stream().map(l -> l.pattern().unwrapKey().orElseThrow().identifier().getPath()).toList(), shapes.get(i), "The crafted banner carries both visual layers in order");
        }
        c.succeed();
    }
}

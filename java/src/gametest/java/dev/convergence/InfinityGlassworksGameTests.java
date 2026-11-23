package dev.convergence;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import org.geysermc.cumulus.form.Form;

public class InfinityGlassworksGameTests {
    private record Glass(String id, Item pane, Item block) {}
    private static List<Glass> variants() {
        var result = new ArrayList<Glass>();
        result.add(new Glass("glass", Items.GLASS_PANE, Items.GLASS));
        for (var color : DyeColor.values()) {
            result.add(new Glass(color.getName() + "_stained_glass", Items.STAINED_GLASS_PANE.pick(color), Items.STAINED_GLASS.pick(color)));
        }
        return result;
    }

    private static Identifier recipeId(Glass glass) {
        return Identifier.fromNamespaceAndPath("convergence", "glassworks/" + glass.id());
    }

    private static ArrayList<ItemStack> filled(Item item) {
        var grid = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++) grid.add(new ItemStack(item));
        return grid;
    }

    @GameTest public void everyGlassColorRecastsAtAnyEmptySlot(GameTestHelper c) {
        var manager = c.getLevel().getServer().getRecipeManager();
        for (var glass : variants()) {
            for (int empty = 0; empty < 9; empty++) {
                var grid = filled(glass.pane());
                grid.set(empty, ItemStack.EMPTY);
                var input = CraftingInput.of(3, 3, grid);
                var recipe = manager.getRecipeFor(RecipeType.CRAFTING, input, c.getLevel()).orElseThrow();
                c.assertValueEqual(recipe.id().identifier(), recipeId(glass), "Native recipe for " + glass.id());
                var result = recipe.value().assemble(input);
                c.assertTrue(result.is(glass.block()) && result.getCount() == 3, "Eight panes give three matching blocks");
            }
        }
        var grid = filled(Items.GLASS_PANE);
        grid.set(4, ItemStack.EMPTY);
        grid.set(0, new ItemStack(Items.STAINED_GLASS_PANE.blue()));
        c.assertTrue(manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, grid), c.getLevel()).isEmpty(), "Mixed colors cannot be reclaimed together");
        grid.set(0, ItemStack.EMPTY);
        grid.get(1).grow(1);
        c.assertTrue(manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, grid), c.getLevel()).isEmpty(), "Eight items in seven slots cannot pay for eight ingredients");
        grid = filled(Items.GLASS_PANE);
        c.assertTrue(manager.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, grid), c.getLevel()).isEmpty(), "A ninth pane cannot be silently consumed");
        grid.set(4, new ItemStack(Items.DYE.red()));
        var dyeInput = CraftingInput.of(3, 3, grid);
        var dyed = manager.getRecipeFor(RecipeType.CRAFTING, dyeInput, c.getLevel()).orElseThrow().value().assemble(dyeInput);
        c.assertTrue(dyed.is(Items.STAINED_GLASS_PANE.red()) && dyed.getCount() == 8, "The ordinary center-dye recipe still makes eight red panes");
        c.succeed();
    }

    @GameTest public void realCraftingTableRoundTripConservesGlass(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "glass-recast");
        BlockPos pos = p.blockPosition().east();
        var level = p.level();
        var before = level.getBlockState(pos);
        try {
            var table = Blocks.CRAFTING_TABLE.defaultBlockState();
            level.setBlock(pos, table, 2);
            p.openMenu(table.getMenuProvider(level, pos));
            var menu = (CraftingMenu)p.containerMenu;
            var grid = menu.getInputGridSlots();
            for (int i = 0; i < 6; i++) grid.get(i).set(new ItemStack(Items.STAINED_GLASS.cyan()));
            menu.clicked(CraftingMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, p);
            c.assertTrue(menu.getCarried().is(Items.STAINED_GLASS_PANE.cyan()) && menu.getCarried().getCount() == 16, "Six cyan blocks cut into sixteen panes");
            for (int i = 0; i < 8; i++) grid.get(i).set(menu.getCarried().split(2));
            c.assertTrue(menu.getCarried().isEmpty(), "Only the sixteen panes from the first craft are available");
            for (int batch = 1; batch <= 2; batch++) {
                menu.clicked(CraftingMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, p);
                c.assertTrue(menu.getCarried().is(Items.STAINED_GLASS.cyan()) && menu.getCarried().getCount() == batch * 3, "Each native take adds exactly three matching blocks");
                for (int i = 0; i < 8; i++) c.assertValueEqual(grid.get(i).getItem().getCount(), 2 - batch, "Taking the output consumes one pane from every occupied slot");
            }
            c.assertTrue(menu.getResultSlot().getItem().isEmpty(), "There is no third result to take");
            p.closeContainer();
            c.assertValueEqual(p.getInventory().countItem(Items.STAINED_GLASS.cyan()), 6, "Closing returns exactly the original six blocks");
        } finally {
            level.setBlock(pos, before, 2);
            InfinityJourneyGameTests.cleanup(f, p);
        }
        c.succeed();
    }

    @GameTest public void discoveringPanesUnlocksRecipesAndBuilderExplainsThem(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "glass-apprentice");
        try {
            var family = variants();
            for (var glass : family) c.assertFalse(knows(p, glass), "A new player starts without the Glassworks recipe");
            p.getInventory().setItem(0, new ItemStack(Items.STAINED_GLASS_PANE.lime()));
            p.inventoryMenu.broadcastChanges();
            for (var glass : family) c.assertTrue(knows(p, glass), "Obtaining one pane unlocks " + glass.id());
            CommonsMenu.open(p, CommonsMenu.Page.BUILDER);
            var menu = (CommonsMenu.Handler)p.containerMenu;
            var books = menu.slots.stream().limit(4).map(slot -> slot.getItem().get(net.minecraft.core.component.DataComponents.LORE))
                .filter(java.util.Objects::nonNull).flatMap(lore -> lore.lines().stream()).map(line -> line.getString()).toList();
            c.assertTrue(String.join(" ", books).contains("eight matching glass panes"), "Java's visible guide cards explain the craft");
            p.closeContainer();
            var forms = new ArrayList<Form>();
            CommonsMenu.form(p, CommonsMenu.Page.BUILDER, null, forms::add);
            var form = forms.getFirst();
            var codec = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec();
            var content = JsonParser.parseString(codec.jsonData(form)).getAsJsonObject().get("content").getAsString();
            c.assertTrue(content.contains("eight matching glass panes") && content.contains("three glass blocks"), "The serialized Bedrock guide explains the same exchange");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    private static boolean knows(ServerPlayer p, Glass glass) {
        return p.getRecipeBook().contains(ResourceKey.create(Registries.RECIPE, recipeId(glass)));
    }
}

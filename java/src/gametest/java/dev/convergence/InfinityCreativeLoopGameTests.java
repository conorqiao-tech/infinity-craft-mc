package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.Blocks;

/** Real Creative Studio review and undo using only a disposable test world. */
public class InfinityCreativeLoopGameTests {
    @GameTest public void infinityOrreryReviewsPlacesAndUndoesBothOrbitalPlanes(GameTestHelper c) {
        var f = new CreativeStudioGameTests();
        var p = f.player(c, "studio-orrery");
        try {
            var original = java.util.List.of("Arch", "Pavilion", "Tree", "Builder Exchange", "Harvest Garden", "Infinity Loop");
            for (int i = 0; i < original.size(); i++)
                c.assertValueEqual(CreativeStudio.stampLabel(CreativeStudio.Stamp.values()[i]), original.get(i), "Saved blueprint indexes keep their original meanings");
            CreativeStudio.settings(p).putInt("stamp", CreativeStudio.Stamp.INFINITY_LOOP.ordinal());
            CreativeStudio.settings(p).putInt("palette", 1);
            CreativeStudio.open(p);
            p.containerMenu.clicked(CreativeStudio.STAMP, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu.getSlot(CreativeStudio.STAMP).getItem().getHoverName().getString().contains("Infinity Orrery"), "Cycle from the loop to the appended stamp");
            var ground = f.previewGround(p);
            var at = ground.above();
            var plan = CreativeStudio.stamp(p, at);
            c.assertValueEqual(plan.size(), 33, "The Orrery has thirty-three unique cells");
            for (var pos : plan.keySet()) p.level().removeBlock(pos, false);
            p.containerMenu.clicked(CreativeStudio.PREVIEW, 0, ContainerInput.PICKUP, p);
            var preview = CreativeStudio.PREVIEWS.get(p.getUUID());
            c.assertTrue(preview != null && preview.blocks().equals(plan), "The native review contains the raycast Orrery plan");
            for (var pos : plan.keySet()) c.assertTrue(p.level().getBlockState(pos).isAir(), "Preview creates no blocks");
            var obstruction = at.offset(0, 3, 3);
            p.level().setBlock(obstruction, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            p.containerMenu.clicked(CreativeStudio.CONFIRM, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.level().getBlockState(obstruction).is(Blocks.DIAMOND_BLOCK), "A new obstruction stays intact");
            for (var pos : plan.keySet()) if (!pos.equals(obstruction)) c.assertTrue(p.level().getBlockState(pos).isAir(), "A refused confirmation places no other cells");
            p.level().removeBlock(obstruction, false);

            CreativeStudio.open(p);
            p.containerMenu.clicked(CreativeStudio.PREVIEW, 0, ContainerInput.PICKUP, p);
            p.containerMenu.clicked(CreativeStudio.CONFIRM, 0, ContainerInput.PICKUP, p);
            for (var entry : plan.entrySet()) c.assertValueEqual(p.level().getBlockState(entry.getKey()), entry.getValue(), "Each placed block matches its reviewed state");
            c.assertTrue(p.level().getBlockState(obstruction).is(Blocks.CUT_COPPER.waxed().unaffected()), "The horizontal orbit uses waxed cut copper");
            c.assertTrue(p.level().getBlockState(at.above(3)).is(Blocks.GOLD_BLOCK), "The two rings enclose a gold sun");
            c.assertTrue(p.level().getBlockState(at.above(6)).is(Blocks.RED_NETHER_BRICKS), "The upright orbit uses the selected Ember accent");
            c.assertTrue(p.level().getBlockState(at.offset(2, 3, -2)).is(Blocks.SHROOMLIGHT), "The planet uses the selected palette light");
            CreativeStudio.open(p);
            p.containerMenu.clicked(CreativeStudio.UNDO_BUTTON, 0, ContainerInput.PICKUP, p);
            for (var pos : plan.keySet()) c.assertTrue(p.level().getBlockState(pos).isAir(), "The native Undo button clears each reviewed cell");
            c.assertTrue(p.level().getBlockState(ground).is(Blocks.STONE), "The ground below the sculpture survives undo");
        } finally { f.cleanup(p); }
        c.succeed();
    }

    @GameTest public void existingSelectionsStayStableAndInfinityLoopUsesReviewedEmptyCells(GameTestHelper c) {
        var f = new CreativeStudioGameTests(); var p = f.player(c, "infinity-loop");
        try {
            c.assertValueEqual(CreativeStudio.Stamp.ARCH.ordinal(), 0, "The existing Arch selection keeps its saved index");
            c.assertValueEqual(CreativeStudio.Stamp.HARVEST_GARDEN.ordinal(), 4, "The existing Harvest selection keeps its saved index");
            c.assertValueEqual(CreativeStudio.PALETTES.get(4).name(), "Frost", "Existing palette indexes remain stable");
            CreativeStudio.settings(p).putInt("stamp", 4);
            CreativeStudio.settings(p).putInt("palette", 5);
            CreativeStudio.open(p); p.containerMenu.clicked(CreativeStudio.STAMP, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(CreativeStudio.option(p, "stamp", CreativeStudio.Stamp.values().length), 5, "The real menu cycles to the new blueprint");
            c.assertTrue(p.containerMenu.getSlot(CreativeStudio.STAMP).getItem().getHoverName().getString().contains("Infinity Loop"), "The menu displays the readable blueprint name");
            p.closeContainer();
            var at = p.blockPosition().offset(0, 0, 5); var first = f.blueprint(p, at);
            c.assertValueEqual(first.blocks().size(), 14, "The two-loop design has a bounded fourteen-block footprint");
            var obstruction = at.offset(-3, 1, 0); p.level().setBlock(obstruction, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            c.assertValueEqual(CreativeStudio.confirm(p, first), 0, "A changed footprint refuses the whole reviewed placement");
            c.assertTrue(p.level().getBlockState(obstruction).is(Blocks.DIAMOND_BLOCK), "The existing block remains untouched");
            p.level().removeBlock(obstruction, false);
            var ready = f.blueprint(p, at);
            c.assertValueEqual(CreativeStudio.confirm(p, ready), 14, "Confirmation places the real Infinity Loop");
            c.assertTrue(p.level().getBlockState(at.offset(0, 1, 0)).is(Blocks.SEA_LANTERN), "The loop's crossing uses the Infinity palette light");
            c.assertTrue(p.level().getBlockState(at.offset(-3, 1, 0)).is(Blocks.CONCRETE.purple()), "The loop uses the new purple palette");
            c.assertValueEqual(CreativeStudio.undo(p), 14, "Studio Undo restores every placed cell");
            for (var position : ready.blocks().keySet()) c.assertTrue(p.level().getBlockState(position).isAir(), "Undo restores the original empty footprint");
        } finally { f.cleanup(p); } c.succeed();
    }
}

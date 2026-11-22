package dev.convergence;

import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;

public class TidewatchFlagGameTests {
    private record Loom(ServerPlayer player, BlockPos pos, BlockState original) implements AutoCloseable {
        static Loom place(ServerPlayer p) {
            var origin = p.blockPosition().atY(p.level().getMaxY() - 4);
            p.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
            var pos = origin.offset(0, 1, 3);
            var loom = new Loom(p, pos, p.level().getBlockState(pos));
            p.level().setBlock(pos, Blocks.LOOM.defaultBlockState(), 2);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            return loom;
        }

        public void close() { player.level().setBlock(pos, original, 2); }
    }

    private static void click(ServerPlayer p, String prefix) {
        var menu = (InfinityJourney.Handler) p.containerMenu;
        for (int i = 0; i < menu.view.choices().size(); i++) {
            if (menu.view.choices().get(i).label().startsWith(prefix)) {
                menu.clicked(27 + i, 0, ContainerInput.PICKUP, p);
                return;
            }
        }
        throw new AssertionError("Missing choice " + prefix + " on " + menu.view);
    }

    private static void supplies(ServerPlayer p, TidewatchFlags.Design d, int count) {
        var inv = p.getInventory();
        inv.setItem(0, new ItemStack(Items.BANNER.white(), count));
        inv.setItem(1, new ItemStack(Items.DYE.pick(d.firstDye), count));
        inv.setItem(2, new ItemStack(Items.DYE.pick(d.secondDye), count));
    }

    private static Form form(ServerPlayer p) {
        p.closeContainer();
        var forms = new ArrayList<Form>();
        InfinityJourney.form(p, InfinityJourney.Page.SIGNAL_FLAGS, forms::add);
        return forms.getFirst();
    }

    @GameTest public void eachSightingWeavesItsOwnPlaceableDesign(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "flag-weaver");
        try (var loom = Loom.place(p)) {
            var support = loom.pos().west();
            var floor = p.level().getBlockState(support);
            var air = p.level().getBlockState(support.above());
            try {
                p.level().setBlock(support, Blocks.STONE.defaultBlockState(), 2);
                var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
                for (var design : TidewatchFlags.Design.values()) {
                    GameModes.state(p).putInt(InfinityTidewatch.KEY, design.species.bit);
                    supplies(p, design, 1);
                    InfinityJourney.open(p, InfinityJourney.Page.TIDEWATCH);
                    click(p, "Signal flags");
                    var menu = (InfinityJourney.Handler) p.containerMenu;
                    c.assertValueEqual(menu.view.choices().size(), 2, "One sighting unlocks exactly one recipe and Back");
                    var slot = 9 + design.ordinal();
                    var preview = menu.getSlot(slot).getItem().copy();
                    var layers = preview.get(DataComponents.BANNER_PATTERNS);
                    c.assertTrue(layers != null && layers.layers().size() == 2, "Each preview contains two banner layers");
                    c.assertTrue(layers.layers().getFirst().pattern().is(design.firstPattern)
                            && layers.layers().getLast().pattern().is(design.secondPattern), "Both native patterns are in order");
                    c.assertValueEqual(layers.layers().getFirst().color(), design.firstDye, "First dye matches the cloth");
                    c.assertValueEqual(layers.layers().getLast().color(), design.secondDye, "Second dye matches the cloth");
                    menu.clicked(slot, 0, ContainerInput.PICKUP, p);
                    c.assertTrue(menu.getCarried().isEmpty(), "Preview stays in its display slot");
                    click(p, "Weave " + design.label);
                    p.closeContainer();
                    p.getInventory().setSelectedSlot(0);
                    c.assertTrue(ItemStack.matches(p.getMainHandItem(), preview), "The paid banner matches the displayed design");
                    c.assertTrue(p.getInventory().getItem(1).isEmpty() && p.getInventory().getItem(2).isEmpty(), "Each dye was spent");
                    p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
                    var placed = p.level().getBlockEntity(support.above());
                    c.assertTrue(placed instanceof BannerBlockEntity b && b.getPatterns().equals(layers), "Vanilla placement retains the reviewed design");
                    c.assertTrue(p.getMainHandItem().isEmpty(), "Placement consumes the woven banner");
                    p.level().setBlock(support.above(), air, 2);
                }
            } finally {
                p.level().setBlock(support.above(), air, 2);
                p.level().setBlock(support, floor, 2);
            }
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void hardcoreFormChecksLoomJournalModeAndInventoryAtConfirmation(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "flag-hardcore");
        try {
            GameModes.switchNow(p, GameModes.Mode.HARDCORE, null);
            try (var loom = Loom.place(p)) {
                var design = TidewatchFlags.Design.REEF;
                var root = GameModes.state(p);
                root.putInt(InfinityTidewatch.KEY, design.species.bit);
                supplies(p, design, 2);
                var changed = form(p);
                p.getInventory().getItem(0).shrink(1);
                f.reply(changed, "0");
                var removed = form(p);
                p.level().setBlock(loom.pos(), Blocks.AIR.defaultBlockState(), 2);
                f.reply(removed, "0");
                c.assertValueEqual(((InfinityJourney.Handler) p.containerMenu).view.choices().size(), 1, "An absent loom offers only Back");
                p.level().setBlock(loom.pos(), Blocks.LOOM.defaultBlockState(), 2);
                var invalid = form(p);
                root.putString(InfinityTidewatch.KEY, "future-format");
                f.reply(invalid, "0");
                c.assertValueEqual(root.getStringOr(InfinityTidewatch.KEY, ""), "future-format", "Unknown saved journal is preserved");
                root.putInt(InfinityTidewatch.KEY, design.species.bit);
                var wrongMode = form(p);
                p.setGameMode(GameType.CREATIVE);
                f.reply(wrongMode, "0");
                p.setGameMode(GameType.SURVIVAL);
                c.assertTrue(ItemStack.matches(p.getInventory().getItem(0), new ItemStack(Items.BANNER.white())), "Refused confirmations leave the plain banner");
                c.assertValueEqual(p.getInventory().getItem(1).getCount(), 2, "Refused confirmations spend no dye");
                var paid = form(p);
                f.reply(paid, "0");
                f.reply(paid, "0");
                var saved = f.reloadDisk(p).getInventory();
                c.assertTrue(ItemStack.matches(saved.getItem(0), design.banner(p)), "Bedrock form weaves the native flag in Hardcore");
                c.assertTrue(saved.getItem(1).getCount() == 1 && saved.getItem(2).getCount() == 1, "One response pays for exactly one flag");
            }
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void protectsSpecialSuppliesAndAccountsForFullInventory(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "flag-pockets");
        try (var loom = Loom.place(p)) {
            var design = TidewatchFlags.Design.SHOAL;
            var inv = p.getInventory();
            GameModes.state(p).putInt(InfinityTidewatch.KEY, design.species.bit);
            for (int i = 3; i < 36; i++) inv.setItem(i, new ItemStack(Items.STONE, 64));
            supplies(p, design, 2);
            f.reply(form(p), "0");
            c.assertValueEqual(inv.getItem(0).getCount(), 2, "No room for output preserves all inputs");
            var custom = CommonsEvents.named(Items.DYE.cyan(), 1, "Keepsake");
            inv.setItem(1, custom.copy());
            f.reply(form(p), "0");
            c.assertTrue(ItemStack.matches(inv.getItem(1), custom), "Named dye is preserved");
            inv.setItem(0, design.banner(p));
            inv.setItem(1, new ItemStack(Items.DYE.cyan()));
            f.reply(form(p), "0");
            c.assertTrue(ItemStack.matches(inv.getItem(0), design.banner(p)) && inv.getItem(2).getCount() == 2, "A patterned banner cannot pay for a flag");
            supplies(p, design, 2);
            inv.setItem(33, new ItemStack(Items.BANNER.white()));
            f.reply(form(p), "0");
            c.assertTrue(ItemStack.matches(inv.getItem(33), design.banner(p)) && inv.getItem(0).getCount() == 2, "A singleton frees a slot in a full inventory");
            supplies(p, design, 2);
            f.reply(form(p), "0");
            c.assertTrue(ItemStack.matches(inv.getItem(33), design.banner(p).copyWithCount(2)), "An identical output stack can accept the next flag");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }
}

package dev.convergence;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;

public class InfinityApiaryGameTests {
    private record Hive(ServerPlayer player, BlockPos pos, BlockState original, BlockState under, BlockState floor)
            implements AutoCloseable {
        static Hive place(ServerPlayer p, Block block) {
            var origin = p.blockPosition().atY(p.level().getMaxY() - 5);
            p.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
            var pos = origin.offset(0, 1, 3);
            var level = p.level();
            var hive = new Hive(p, pos, level.getBlockState(pos), level.getBlockState(pos.below()), level.getBlockState(pos.below(2)));
            level.setBlock(pos, block.defaultBlockState(), 2);
            level.setBlock(pos.below(2), Blocks.CAMPFIRE.defaultBlockState(), 2);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
            return hive;
        }
        void fill(int amount) {
            player.level().setBlock(pos, player.level().getBlockState(pos).setValue(BeehiveBlock.HONEY_LEVEL, amount), 2);
        }
        void use(InteractionHand hand, ItemStack tool) {
            player.setItemInHand(hand, tool);
            var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, -.5), Direction.NORTH, pos, false);
            player.gameMode.useItemOn(player, player.level(), tool, hand, hit);
        }
        public void close() {
            player.level().setBlock(pos, original, 2);
            player.level().setBlock(pos.below(), under, 2);
            player.level().setBlock(pos.below(2), floor, 2);
        }
    }

    private static void click(ServerPlayer player, String label) {
        var menu = (InfinityJourney.Handler) player.containerMenu;
        var choices = menu.view.choices().stream().map(InfinityJourney.Choice::label).toList();
        int index = choices.indexOf(label);
        if (index < 0) throw new AssertionError("Missing choice " + label + " among " + choices);
        menu.clicked(27 + index, 0, ContainerInput.PICKUP, player);
    }

    private static Form form(ServerPlayer p) {
        var forms = new ArrayList<Form>();
        InfinityJourney.form(p, InfinityJourney.Page.APIARY, forms::add);
        return forms.getFirst();
    }

    @GameTest public void readsHiveAndRecordsBothVanillaHarvests(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "apiary-weaver");
        try (var hive = Hive.place(p, Blocks.BEEHIVE)) {
            hive.fill(5);
            var entity = (BeehiveBlockEntity) p.level().getBlockEntity(hive.pos());
            entity.storeBee(BeehiveBlockEntity.Occupant.create(80));
            var region = new AABB(hive.pos()).inflate(2);
            var beforeDrops = new HashSet<>(p.level().getEntitiesOfClass(ItemEntity.class, region));
            try {
                InfinityJourney.open(p);
                click(p, "Infinity Apiary • inspect a hive");
                var content = ((InfinityJourney.Handler)p.containerMenu).view.content();
                c.assertTrue(content.contains("Beehive • Honey 5/5") && content.contains("Campfire smoke reaches it."), "Journey shows the inspected hive and native smoke");
                c.assertFalse(GameModes.state(p).contains(InfinityApiary.KEY), "Inspection creates no challenge record");
                click(p, "Start Apiary Keeper");
                p.closeContainer();

                hive.use(InteractionHand.OFF_HAND, new ItemStack(Items.GLASS_BOTTLE));
                c.assertTrue(p.getOffhandItem().is(Items.HONEY_BOTTLE), "Vanilla fills the final glass bottle in the off hand");
                c.assertValueEqual(InfinityApiary.progress(p), 1, "Bottling gives one receipt");
                c.assertValueEqual(p.level().getBlockState(hive.pos()).getValue(BeehiveBlock.HONEY_LEVEL), 0, "The hive empties normally");
                hive.fill(5);
                hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                c.assertValueEqual(InfinityApiary.progress(p), 1, "Another bottle cannot fill the comb entry");
                hive.fill(5);
                var shears = new ItemStack(Items.SHEARS);
                shears.setDamageValue(shears.getMaxDamage() - 1);
                hive.use(InteractionHand.MAIN_HAND, shears);
                c.assertTrue(p.getMainHandItem().isEmpty(), "Last-use shears break normally");
                int comb = p.level().getEntitiesOfClass(ItemEntity.class, region).stream()
                    .filter(e -> !beforeDrops.contains(e) && e.getItem().is(Items.HONEYCOMB)).mapToInt(e -> e.getItem().getCount()).sum();
                c.assertValueEqual(comb, 3, "Vanilla drops exactly three honeycomb");
                c.assertValueEqual(entity.getOccupantCount(), 1, "Smoke keeps the resident bee inside");
                c.assertTrue(InfinityApiary.view(p).content().contains("Apiary Keeper complete."), "Completed entry appears in the guide");
                c.assertValueEqual(InfinityApiary.progress(f.reloadDisk(p)), 3, "Both receipts survive a player save and reload");
            } finally {
                for (var drop : p.level().getEntitiesOfClass(ItemEntity.class, region)) {
                    if (!beforeDrops.contains(drop) && drop.getItem().is(Items.HONEYCOMB)) drop.discard();
                }
            }
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void nestFormAndHardcoreRejectStaleStartAndUnsmokedHarvests(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "apiary-keeper");
        try {
            GameModes.switchNow(p, GameModes.Mode.HARDCORE, null);
            c.assertValueEqual(GameModes.of(p.level()), GameModes.Mode.HARDCORE, "Reached the Hardcore realm");
            try (var hive = Hive.place(p, Blocks.BEE_NEST)) {
                hive.fill(5);
                hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                c.assertFalse(GameModes.state(p).contains(InfinityApiary.KEY), "Harvesting before starting stays ordinary");
                var stale = form(p);
                var current = form(p);
                var codec = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(current).codec();
                var json = JsonParser.parseString(codec.jsonData(current)).getAsJsonObject();
                c.assertTrue(json.get("content").getAsString().contains("Bee nest • Honey 0/5"), "Bedrock form reports the emptied nest");
                var start = json.getAsJsonArray("buttons").get(0).getAsJsonObject();
                c.assertValueEqual(start.get("text").getAsString(), "Start Apiary Keeper", "Bedrock presents the start control");
                c.assertValueEqual(start.getAsJsonObject("image").get("data").getAsString(), "textures/items/honeycomb", "The button uses the Bedrock comb sprite");
                f.reply(stale, "0");
                c.assertFalse(GameModes.state(p).contains(InfinityApiary.KEY), "An old Bedrock start button cannot enroll a player");
                f.reply(current, "0");
                p.closeContainer();
                hive.fill(4);
                hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                c.assertTrue(p.getMainHandItem().is(Items.GLASS_BOTTLE), "An unripe nest leaves the bottle empty");
                hive.fill(5);
                hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                c.assertValueEqual(InfinityApiary.progress(p), 0, "Unripe nest and unrelated tool earn no credit");
                p.level().setBlock(hive.pos().below(), Blocks.STONE.defaultBlockState(), 2);
                c.assertTrue(InfinityApiary.view(p).content().contains("Campfire smoke reaches it."), "Vanilla recognizes a campfire immediately under the cover");
                var lower = hive.pos().below(3);
                var original = p.level().getBlockState(lower);
                try {
                    p.level().setBlock(hive.pos().below(2), Blocks.AIR.defaultBlockState(), 2);
                    p.level().setBlock(lower, Blocks.CAMPFIRE.defaultBlockState(), 2);
                    c.assertTrue(InfinityApiary.view(p).content().contains("No campfire smoke"), "A solid block with an air gap below it obstructs the smoke");
                    hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                } finally {
                    p.level().setBlock(lower, original, 2);
                    p.level().setBlock(hive.pos().below(2), Blocks.CAMPFIRE.defaultBlockState(), 2);
                }
                c.assertTrue(p.getMainHandItem().is(Items.HONEY_BOTTLE) && InfinityApiary.progress(p) == 0, "Unsmoked collection still gives normal honey without credit");
                p.level().setBlock(hive.pos().below(), Blocks.AIR.defaultBlockState(), 2);
                hive.fill(5);
                p.setGameMode(GameType.CREATIVE);
                hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                c.assertValueEqual(InfinityApiary.progress(p), 0, "Creative harvesting earns no credit");
                p.setGameMode(GameType.SURVIVAL);
                hive.fill(5);
                c.assertTrue(InfinityApiary.view(p).content().contains("Bee nest • Honey 5/5"), "Guide names the natural nest");
                hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
                c.assertValueEqual(InfinityApiary.progress(f.reloadDisk(p)), 1, "A natural nest in Hardcore records the bottle");
            }
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void unreadableProgressAndObstructedHivesAreLeftAlone(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "apiary-future");
        try (var hive = Hive.place(p, Blocks.BEEHIVE)) {
            var wall = hive.pos().north();
            var before = p.level().getBlockState(wall);
            try {
                p.level().setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
                c.assertTrue(InfinityApiary.view(p).content().contains("Aim at a bee nest"), "The guide cannot inspect through a wall");
            } finally { p.level().setBlock(wall, before, 2); }
            p.setPos(p.position().add(0, 0, -5));
            c.assertTrue(InfinityApiary.view(p).content().contains("Aim at a bee nest"), "The guide respects native building reach");
            p.setPos(p.position().add(0, 0, 5));
            var root = GameModes.state(p);
            root.putString(InfinityApiary.KEY, "later-format");
            hive.fill(5);
            hive.use(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
            c.assertValueEqual(root.getStringOr(InfinityApiary.KEY, ""), "later-format", "Native harvesting preserves unreadable progress");
            root.putInt(InfinityApiary.KEY, 4);
            var view = InfinityApiary.view(p);
            c.assertTrue(view.content().contains("needs host review") && view.choices().stream().noneMatch(choice -> choice.label().startsWith("Start")), "Unknown bits cannot be reset by opening the guide");
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }
}

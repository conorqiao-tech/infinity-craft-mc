package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.geysermc.cumulus.form.Form;

public class InfinityOreSurveyGameTests {
    static void cleanup(CommonsGameTests f, ServerPlayer p) {
        InfinityJourney.ACTIVE.remove(p.getUUID()); InfinityJourney.DELIVERIES.remove(p.getUUID()); f.cleanup(p);
    }
    static boolean breakOre(ServerPlayer p, BlockPos pos, Block block) {
        p.level().setBlock(pos, block.defaultBlockState(), 3);
        return p.gameMode.destroyBlock(pos);
    }
    static int rawIronAt(ServerPlayer p, BlockPos pos) {
        return p.level().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2),
            entity -> entity.getItem().is(Items.RAW_IRON)).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    @GameTest public void everyOreAndItsNativeVariantMapsToOneSurveyEntry(GameTestHelper c) {
        var normal = List.of(Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.DIAMOND_ORE,
            Blocks.REDSTONE_ORE, Blocks.EMERALD_ORE, Blocks.LAPIS_ORE, Blocks.COPPER_ORE);
        var deep = List.of(Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        for (int i = 0; i < 8; i++) {
            c.assertValueEqual(InfinityOreSurvey.ore(normal.get(i).defaultBlockState()), i, "Normal ore maps to its own entry");
            c.assertValueEqual(InfinityOreSurvey.ore(deep.get(i).defaultBlockState()), i, "Deepslate ore maps to the same entry");
        }
        c.assertValueEqual(InfinityOreSurvey.ore(Blocks.NETHER_GOLD_ORE.defaultBlockState()), 2, "Nether gold maps to gold");
        c.assertValueEqual(InfinityOreSurvey.ore(Blocks.STONE.defaultBlockState()), -1, "Ordinary stone is not an ore"); c.succeed();
    }

    @GameTest public void realBreaksCountOnlyAfterOptInAndDoNotChangeMiningRules(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "survey-break");
        try {
            var pos = c.absolutePos(new BlockPos(4, 3, 4)); p.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            int initialDrops = rawIronAt(p, pos);
            c.assertTrue(breakOre(p, pos, Blocks.IRON_ORE), "A native Survival block break succeeds");
            int ordinaryDrops = rawIronAt(p, pos) - initialDrops;
            c.assertTrue(ordinaryDrops > 0, "The native break produced ordinary raw-iron drops");
            c.assertFalse(GameModes.state(p).contains(InfinityOreSurvey.KEY), "Mining before opt-in creates no survey data");
            c.assertValueEqual(InfinityOreSurvey.setActive(p, true), 1, "The player starts the survey");
            c.assertTrue(breakOre(p, pos, Blocks.IRON_ORE), "The same native break still succeeds");
            c.assertValueEqual(rawIronAt(p, pos) - initialDrops - ordinaryDrops, ordinaryDrops, "Enabling the survey leaves normal iron drops unchanged");
            c.assertTrue(p.level().getBlockState(pos).isAir(), "The ordinary block break still removes its block");
            c.assertValueEqual(InfinityOreSurvey.found(p), 2, "The actual break records iron once");
            c.assertTrue(breakOre(p, pos, Blocks.DEEPSLATE_IRON_ORE), "The native deepslate break succeeds");
            c.assertValueEqual(InfinityOreSurvey.found(p), 2, "The same ore variant cannot create duplicate progress");
            c.assertValueEqual(InfinityOreSurvey.found(f.reloadDisk(p)), 2, "Native player save retains the survey");
            p.setGameMode(GameType.CREATIVE); c.assertTrue(breakOre(p, pos, Blocks.DIAMOND_ORE), "The Creative break still works");
            c.assertValueEqual(InfinityOreSurvey.found(p), 2, "Creative breaking does not count");
            p.setGameMode(GameType.SURVIVAL); c.assertValueEqual(InfinityOreSurvey.setActive(p, false), 1, "The survey can pause");
            c.assertTrue(breakOre(p, pos, Blocks.GOLD_ORE), "Ordinary mining still works while paused");
            c.assertValueEqual(InfinityOreSurvey.found(p), 2, "Pausing keeps prior progress and ignores later breaks");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void unknownDataAndFullInventoryArePreserved(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "survey-data");
        try {
            var root = GameModes.state(p); root.putString(InfinityOreSurvey.KEY, "future opaque value"); var before = root.copy();
            c.assertValueEqual(InfinityOreSurvey.setActive(p, true), 0, "Unknown state is refused");
            c.assertValueEqual(root, before, "Unknown state is preserved exactly");
            var future = new CompoundTag(); future.putInt("format", 2); future.putString("evidence", "keep"); root.put(InfinityOreSurvey.KEY, future); before = root.copy();
            c.assertValueEqual(InfinityOreSurvey.setActive(p, true), 0, "A future format is refused");
            c.assertValueEqual(root, before, "Future state is retained");
            var complete = new CompoundTag(); complete.putInt("format", 1); complete.putInt("found", InfinityOreSurvey.ALL); complete.putBoolean("active", false); root.put(InfinityOreSurvey.KEY, complete);
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            c.assertValueEqual(InfinityOreSurvey.giveBanner(p), 0, "A full inventory refuses the banner");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(), 64, "No existing stack is replaced");
            p.getInventory().setItem(0, ItemStack.EMPTY);
            c.assertValueEqual(InfinityOreSurvey.giveBanner(p), 1, "A completed survey adds a decorative banner to an empty slot");
            c.assertTrue(p.getInventory().getItem(0).get(DataComponents.BANNER_PATTERNS) != null, "The native banner has its original pattern");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(0), f.reloadDisk(p).getInventory().getItem(0)), "The native save retains the banner");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void javaAndBedrockSurveyControlsRejectStaleChoices(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "survey-menu");
        try {
            InfinityJourney.open(p); p.containerMenu.clicked(35, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(((InfinityJourney.Handler) p.containerMenu).view.title(), "Infinity Ore Survey", "Java opens the survey through Journey");
            p.containerMenu.clicked(27, 0, ContainerInput.PICKUP, p); p.closeContainer();
            c.assertTrue(InfinityOreSurvey.active(p), "The real Java button starts the survey");
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.SURVEY, form -> { forms.add(form); return true; });
            InfinityJourney.form(p, InfinityJourney.Page.SURVEY, form -> { forms.add(form); return true; });
            f.reply(forms.get(0), "0"); c.assertTrue(InfinityOreSurvey.active(p), "An old Bedrock form cannot pause the survey");
            f.reply(forms.get(1), "0"); c.assertFalse(InfinityOreSurvey.active(p), "The current encoded response pauses the survey");
            p.closeContainer(); forms.clear();
            InfinityJourney.form(p, InfinityJourney.Page.SURVEY, form -> { forms.add(form); return true; });
            c.assertValueEqual(InfinityOreSurvey.setActive(p, true), 1, "The survey changed while its form was open");
            f.reply(forms.get(0), "0"); c.assertTrue(InfinityOreSurvey.active(p), "A stale Start label cannot unexpectedly pause it");
        } finally { cleanup(f, p); } c.succeed();
    }
}

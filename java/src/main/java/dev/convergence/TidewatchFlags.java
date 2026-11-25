package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Sighting-unlocked loom designs made from ordinary Survival supplies. */
final class TidewatchFlags {
    enum Design {
        SHOAL(InfinityTidewatch.Species.COD, "Shoal", BannerPatterns.STRIPE_SMALL, DyeColor.CYAN,
            BannerPatterns.STRIPE_BOTTOM, DyeColor.BLUE),
        UPSTREAM(InfinityTidewatch.Species.SALMON, "Upstream", BannerPatterns.STRIPE_DOWNLEFT, DyeColor.RED,
            BannerPatterns.TRIANGLE_BOTTOM, DyeColor.GRAY),
        REEF(InfinityTidewatch.Species.TROPICAL, "Reef", BannerPatterns.STRIPE_CENTER, DyeColor.ORANGE,
            BannerPatterns.RHOMBUS_MIDDLE, DyeColor.LIGHT_BLUE),
        CAUTION(InfinityTidewatch.Species.PUFFER, "Caution", BannerPatterns.BORDER, DyeColor.BLACK,
            BannerPatterns.RHOMBUS_MIDDLE, DyeColor.YELLOW);

        final InfinityTidewatch.Species species;
        final String label;
        final ResourceKey<BannerPattern> firstPattern, secondPattern;
        final DyeColor firstDye, secondDye;

        Design(InfinityTidewatch.Species species, String label, ResourceKey<BannerPattern> firstPattern,
                DyeColor firstDye, ResourceKey<BannerPattern> secondPattern, DyeColor secondDye) {
            this.species = species;
            this.label = label;
            this.firstPattern = firstPattern;
            this.firstDye = firstDye;
            this.secondPattern = secondPattern;
            this.secondDye = secondDye;
        }

        boolean unlocked(int seen) { return seen >= 0 && (seen & species.bit) != 0; }

        String dyes() {
            return firstDye.getSerializedName().replace('_', ' ') + " + " + secondDye.getSerializedName().replace('_', ' ');
        }

        ItemStack banner(ServerPlayer p) {
            var patterns = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
            var banner = CommonsEvents.named(Items.BANNER.white(), 1, "Tidewatch • " + label, species.label + " signal flag");
            banner.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
                .add(patterns.getOrThrow(firstPattern), firstDye)
                .add(patterns.getOrThrow(secondPattern), secondDye).build());
            return banner;
        }
    }

    private TidewatchFlags() {}

    private static BlockPos aimedLoom(ServerPlayer p) {
        var hit = p.pick(Math.min(5.0, p.blockInteractionRange()), 1, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block
                && p.level().getBlockState(block.getBlockPos()).is(Blocks.LOOM)) return block.getBlockPos();
        return null;
    }

    // Prefer singletons so a full inventory can reuse a slot freed by the recipe.
    private static boolean takeOne(List<ItemStack> stacks, Item item) {
        var plain = new ItemStack(item);
        int first = -1;
        for (int i = 0; i < stacks.size(); i++) {
            var stack = stacks.get(i);
            if (!ItemStack.isSameItemSameComponents(stack, plain)) continue;
            if (stack.getCount() == 1) { stacks.set(i, ItemStack.EMPTY); return true; }
            if (first < 0) first = i;
        }
        if (first < 0) return false;
        stacks.get(first).shrink(1);
        return true;
    }

    private static String weave(ServerPlayer p, Design design, BlockPos loom, List<ItemStack> before) {
        if (p.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || !CommonsEvents.harvestAllowed(p)
                || !design.unlocked(InfinityTidewatch.sightings(p))) return "This design requires the sighting in Survival or Hardcore.";
        if (loom == null || !loom.equals(aimedLoom(p))) return "Aim at the same loom within building reach and reopen Signal flags.";
        var after = new ArrayList<ItemStack>(before.size());
        for (int i = 0; i < before.size(); i++) {
            if (!ItemStack.matches(p.getInventory().getItem(i), before.get(i))) return "Your inventory changed. Review the recipe again.";
            after.add(before.get(i).copy());
        }
        if (!takeOne(after, Items.BANNER.white()) || !takeOne(after, Items.DYE.pick(design.firstDye))
                || !takeOne(after, Items.DYE.pick(design.secondDye))) return "Bring a plain white banner and one plain dye of each listed color.";
        var flag = design.banner(p);
        // Place the result in a matching stack or the first free slot after taking ingredients.
        int output = -1;
        for (int i = 0; i < after.size(); i++) {
            var stack = after.get(i);
            if (stack.isEmpty() && output < 0) output = i;
            if (ItemStack.isSameItemSameComponents(stack, flag) && stack.getCount() < stack.getMaxStackSize()) {
                output = i;
                break;
            }
        }
        if (output < 0) return "Make room for the finished flag. Your supplies are intact.";
        after.set(output, flag.copyWithCount(after.get(output).getCount() + 1));
        for (int i = 0; i < after.size(); i++) {
            if (!ItemStack.matches(before.get(i), after.get(i))) p.getInventory().setItem(i, after.get(i));
        }
        PlayerPersistence.save(p);
        p.inventoryMenu.sendAllDataToRemote();
        return "Wove the " + design.label + " signal flag.";
    }

    static InfinityJourney.View view(ServerPlayer p) {
        int seen = InfinityTidewatch.sightings(p);
        var loom = aimedLoom(p);
        boolean canWeave = p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && CommonsEvents.harvestAllowed(p);
        var inventory = p.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList();
        var body = new StringBuilder("Each sighting unlocks a flag. Aim at a loom within building reach. Weaving uses one plain white banner and one of each listed dye from your hotbar or main inventory.\n");
        if (seen < 0) body.append("\nYour saved Tidewatch journal needs host review.\n");
        else if (!canWeave) body.append("\nVisit a loom in Survival or Hardcore to weave.\n");
        else if (loom == null) body.append("\nAim at the loom, then reopen this page to weave.\n");
        var choices = new ArrayList<InfinityJourney.Choice>();
        for (var design : Design.values()) {
            boolean unlocked = design.unlocked(seen);
            body.append("\n").append(design.label).append(" • ").append(design.dyes()).append(" • ")
                .append(unlocked ? "unlocked" : "record " + design.species.label);
            if (unlocked && canWeave && loom != null) choices.add(new InfinityJourney.Choice(design.species.icon,
                "Weave " + design.label + " • " + design.dyes(), design.species.bedrockTexture, () -> {
                    CommunityServer.say(p, weave(p, design, loom, inventory));
                    InfinityJourney.open(p, InfinityJourney.Page.SIGNAL_FLAGS);
                }));
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Tidewatch", () -> InfinityJourney.open(p, InfinityJourney.Page.TIDEWATCH)));
        return new InfinityJourney.View("Tidewatch • Signal flags", body.toString(), List.copyOf(choices));
    }
}

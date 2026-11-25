package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** A hive inspection and two personal receipts for ordinary smoky harvests. */
public final class InfinityApiary {
    static final String KEY = "infinity_apiary_v1";
    static final String COMB_TEXTURE = "textures/items/honeycomb";
    private static final int ABSENT = -1, UNREADABLE = -2;
    private static final int HONEY = 1, COMB = 2, COMPLETE = HONEY | COMB;

    private InfinityApiary() {}

    static int progress(ServerPlayer player) {
        var tag = GameModes.state(player).get(KEY);
        if (tag == null) return ABSENT;
        if (tag instanceof IntTag value && value.intValue() >= 0 && value.intValue() <= COMPLETE) return value.intValue();
        return UNREADABLE;
    }

    private static boolean eligible(ServerPlayer player) {
        return player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && CommonsEvents.harvestAllowed(player);
    }

    // Called after vanilla resets the full, smoked hive. The tool is captured before vanilla uses it up.
    public static void recordHarvest(ServerPlayer player, Item tool) {
        int received = progress(player);
        if (received < 0 || received == COMPLETE || !eligible(player)) return;
        int harvest;
        if (tool == Items.GLASS_BOTTLE) harvest = HONEY;
        else if (tool == Items.SHEARS) harvest = COMB;
        else return;
        if ((received & harvest) != 0) return;
        int updated = received | harvest;
        GameModes.state(player).putInt(KEY, updated);
        PlayerPersistence.save(player);
        CommunityServer.say(player, updated == COMPLETE ? "Apiary Keeper complete • honey and comb collected."
            : harvest == HONEY ? "Apiary Keeper • honey bottled. Next, gather comb with shears."
            : "Apiary Keeper • comb gathered. Next, fill a glass bottle.");
    }

    private static String inspection(ServerPlayer player) {
        var hit = player.pick(Math.min(5, player.blockInteractionRange()), 1, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            var pos = block.getBlockPos();
            var world = player.level();
            var state = world.getBlockState(pos);
            if (state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST)) {
                int honey = state.getValue(BeehiveBlock.HONEY_LEVEL);
                boolean smoked = CampfireBlock.isSmokeyPos(world, pos);
                String nextStep;
                if (honey < BeehiveBlock.MAX_HONEY_LEVELS) nextStep = "Let the bees fill it to 5/5.";
                else if (smoked) nextStep = "Ready. Close the guide and use a glass bottle or shears on the hive.";
                else nextStep = "Full. Set up a lit campfire below the hive before collecting.";
                return (state.is(Blocks.BEE_NEST) ? "Bee nest" : "Beehive") + " • Honey " + honey + "/5 • "
                    + (smoked ? "Campfire smoke reaches it." : "No campfire smoke reaches it.") + "\n" + nextStep;
            }
        }
        return "Aim at a bee nest or beehive within reach, then inspect it again.";
    }

    static InfinityJourney.View view(ServerPlayer player) {
        int received = progress(player);
        var body = new StringBuilder(inspection(player)).append("\n\n");
        var choices = new ArrayList<InfinityJourney.Choice>();
        if (received == UNREADABLE) body.append("Your Apiary Keeper record needs host review.");
        else if (received == ABSENT) {
            body.append("Apiary Keeper • In Survival or Hardcore, collect one honey bottle and one honeycomb harvest from full hives with campfire smoke.");
            if (eligible(player)) choices.add(new InfinityJourney.Choice(Items.HONEYCOMB, "Start Apiary Keeper", COMB_TEXTURE, () -> {
                if (eligible(player) && progress(player) == ABSENT) {
                    GameModes.state(player).putInt(KEY, 0);
                    PlayerPersistence.save(player);
                }
                InfinityJourney.open(player, InfinityJourney.Page.APIARY);
            }));
        } else {
            body.append(received == COMPLETE ? "Apiary Keeper complete." : "Apiary Keeper • Harvest full hives with campfire smoke in Survival or Hardcore.")
                .append("\n").append((received & HONEY) != 0 ? "✓" : "○").append(" Bottle honey with a glass bottle")
                .append("\n").append((received & COMB) != 0 ? "✓" : "○").append(" Gather honeycomb with shears");
        }
        choices.add(new InfinityJourney.Choice(Items.BEEHIVE, "Inspect hive again", () -> InfinityJourney.open(player, InfinityJourney.Page.APIARY)));
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(player)));
        return new InfinityJourney.View("Infinity Apiary", body.toString(), List.copyOf(choices));
    }
}

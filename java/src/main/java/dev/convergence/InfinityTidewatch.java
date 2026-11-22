package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/** A field journal of non-destructive, player-initiated sightings. */
final class InfinityTidewatch {
    static final String KEY = "infinity_tidewatch_fish_v1";
    static final int RANGE = 8;
    static final int COMPLETE = 15;

    enum Species {
        COD(1, EntityTypes.COD, Items.COD, "textures/items/fish_raw", "Cod", "Look along the ocean shelf."),
        SALMON(2, EntityTypes.SALMON, Items.SALMON, "textures/items/fish_salmon_raw", "Salmon", "Follow a river upstream."),
        TROPICAL(4, EntityTypes.TROPICAL_FISH, Items.TROPICAL_FISH, "textures/items/fish_clownfish_raw", "Tropical fish", "Explore warm seas or lush caves."),
        PUFFER(8, EntityTypes.PUFFERFISH, Items.PUFFERFISH, "textures/items/fish_pufferfish_raw", "Pufferfish", "Watch warm waters from a safe distance.");

        final int bit;
        final EntityType<? extends AbstractFish> type;
        final Item icon;
        final String bedrockTexture;
        final String label;
        final String hint;

        Species(int bit, EntityType<? extends AbstractFish> type, Item icon, String bedrockTexture, String label, String hint) {
            this.bit = bit;
            this.type = type;
            this.icon = icon;
            this.bedrockTexture = bedrockTexture;
            this.label = label;
            this.hint = hint;
        }
    }

    private InfinityTidewatch() {}

    // Zero is an unopened journal. A negative value preserves an unreadable saved record.
    static int sightings(ServerPlayer player) {
        var tag = GameModes.state(player).get(KEY);
        if (tag == null) return 0;
        if (!(tag instanceof IntTag value)) return -1;
        int bits = value.intValue();
        return bits >= 0 && bits <= COMPLETE ? bits : -1;
    }

    static boolean record(ServerPlayer player, Species species) {
        int seen = sightings(player);
        if (seen < 0 || (seen & species.bit) != 0 || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                || !CommonsEvents.harvestAllowed(player)) return false;
        var world = player.level();
        var nearby = world.getEntitiesOfClass(AbstractFish.class, player.getBoundingBox().inflate(RANGE),
            fish -> fish.getType() == species.type && fish.isAlive() && !fish.isInvisibleTo(player)
                && fish.distanceToSqr(player) <= RANGE * RANGE);
        for (var fish : nearby) {
            if (!AgentCompanions.loadedRoom(world, new AABB(player.getEyePosition(), fish.getEyePosition()))
                    || !world.getFluidState(fish.blockPosition()).is(FluidTags.WATER)
                    || !player.hasLineOfSight(fish, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, fish.getEyeY())) continue;
            GameModes.state(player).putInt(KEY, seen | species.bit);
            PlayerPersistence.save(player);
            world.sendParticles(player, ParticleTypes.BUBBLE, false, false,
                fish.getX(), fish.getEyeY(), fish.getZ(), 6, .18, .1, .18, .01);
            return true;
        }
        return false;
    }

    static InfinityJourney.View view(ServerPlayer player) {
        int seen = sightings(player);
        var choices = new ArrayList<InfinityJourney.Choice>();
        var body = new StringBuilder("Find a live fish in water within eight blocks in Survival or Hardcore. "
            + "Choose its species while the path between you and the fish is clear. Aquarium sightings count.\n");
        if (seen < 0) {
            body.append("\nYour saved Tidewatch journal needs host review.");
        } else {
            body.append("\n").append(Integer.bitCount(seen)).append("/4 species recorded.\n");
            for (var species : Species.values()) {
                boolean recorded = (seen & species.bit) != 0;
                body.append(recorded ? "✓ " : "○ ").append(species.label).append(" • ").append(species.hint).append('\n');
                if (!recorded) choices.add(new InfinityJourney.Choice(species.icon, "Record " + species.label, species.bedrockTexture, () -> {
                    boolean added = record(player, species);
                    CommunityServer.say(player, added ? "Tidewatch recorded " + species.label + "."
                        : "No new sighting. Check your mode, the species, its distance and the view through the water.");
                    InfinityJourney.open(player, InfinityJourney.Page.TIDEWATCH);
                }));
            }
            if (seen == COMPLETE) body.append("Field journal complete. You have recorded all four fish.");
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(player)));
        return new InfinityJourney.View("Infinity Tidewatch", body.toString(), List.copyOf(choices));
    }
}

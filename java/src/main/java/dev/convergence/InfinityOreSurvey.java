package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.state.BlockState;

/** Optional mining challenge: observes successful native breaks, never changes their drops or terrain. */
final class InfinityOreSurvey {
    static final String KEY = "infinity_ore_survey";
    static final List<String> NAMES = List.of("Coal", "Iron", "Gold", "Diamond", "Redstone", "Emerald", "Lapis", "Copper");
    static final int ALL = 255;
    private InfinityOreSurvey() {}
    static CompoundTag state(ServerPlayer p) { return GameModes.state(p).getCompoundOrEmpty(KEY); }
    static boolean writable(ServerPlayer p) {
        var root = GameModes.state(p);
        if (!root.contains(KEY)) return true;
        var saved = root.getCompound(KEY).orElse(null);
        if (saved == null || !(saved.get("format") instanceof IntTag) || saved.getIntOr("format", -1) != 1
            || saved.contains("found") && !(saved.get("found") instanceof IntTag)
            || saved.contains("active") && !(saved.get("active") instanceof ByteTag)) return false;
        int found = saved.getIntOr("found", 0); byte active = saved.getByteOr("active", (byte) 0);
        return found >= 0 && found <= ALL && (active == 0 || active == 1);
    }
    static int found(ServerPlayer p) { return writable(p) ? state(p).getIntOr("found", 0) : 0; }
    static boolean active(ServerPlayer p) { return writable(p) && state(p).getBooleanOr("active", false); }
    static int setActive(ServerPlayer p, boolean enabled) {
        if (!InfinityJourney.emptyScreen(p) || !writable(p)) return 0;
        var next = state(p).copy(); next.putInt("format", 1); next.putBoolean("active", enabled);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p); return 1;
    }
    static int ore(BlockState block) {
        if (block.is(Blocks.COAL_ORE) || block.is(Blocks.DEEPSLATE_COAL_ORE)) return 0;
        if (block.is(Blocks.IRON_ORE) || block.is(Blocks.DEEPSLATE_IRON_ORE)) return 1;
        if (block.is(Blocks.GOLD_ORE) || block.is(Blocks.DEEPSLATE_GOLD_ORE) || block.is(Blocks.NETHER_GOLD_ORE)) return 2;
        if (block.is(Blocks.DIAMOND_ORE) || block.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return 3;
        if (block.is(Blocks.REDSTONE_ORE) || block.is(Blocks.DEEPSLATE_REDSTONE_ORE)) return 4;
        if (block.is(Blocks.EMERALD_ORE) || block.is(Blocks.DEEPSLATE_EMERALD_ORE)) return 5;
        if (block.is(Blocks.LAPIS_ORE) || block.is(Blocks.DEEPSLATE_LAPIS_ORE)) return 6;
        if (block.is(Blocks.COPPER_ORE) || block.is(Blocks.DEEPSLATE_COPPER_ORE)) return 7;
        return -1;
    }
    static boolean observe(ServerPlayer p, BlockState block) {
        int index = ore(block);
        if (index < 0 || !active(p)) return false;
        var mode = GameModes.current(p);
        if (!ServerMenu.allowed(p) || p.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
            || (mode != GameModes.Mode.SURVIVAL && mode != GameModes.Mode.HARDCORE) || GameModes.of(p.level()) != mode) return false;
        int before = found(p), bit = 1 << index;
        if ((before & bit) != 0) return false;
        var next = state(p).copy(); next.putInt("format", 1); next.putInt("found", before | bit);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p);
        CommunityServer.say(p, "Infinity Ore Survey • " + NAMES.get(index) + " recorded! " + Integer.bitCount(before | bit) + "/8 ores.");
        p.level().sendParticles(p, ParticleTypes.END_ROD, false, false, p.getX(), p.getY() + 1, p.getZ(), 6, .3, .3, .3, .01);
        return true;
    }
    static ItemStack banner(ServerPlayer p) {
        var stack = CommonsEvents.named(Items.BANNER.blue(), 1, "Infinity • Ore Survey Banner",
            "A constellation of eight ores, recorded by an Infinity surveyor.");
        var patterns = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        stack.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .add(patterns.getOrThrow(BannerPatterns.CROSS), DyeColor.BLACK)
            .add(patterns.getOrThrow(BannerPatterns.RHOMBUS_MIDDLE), DyeColor.CYAN)
            .add(patterns.getOrThrow(BannerPatterns.CIRCLE_MIDDLE), DyeColor.YELLOW).build());
        return stack;
    }
    static int giveBanner(ServerPlayer p) {
        if (!InfinityJourney.emptyScreen(p) || !writable(p) || found(p) != ALL) return 0;
        int slot = -1;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) { slot = i; break; }
        if (slot < 0) return 0;
        p.getInventory().setItem(slot, banner(p)); PlayerPersistence.save(p); p.inventoryMenu.sendAllDataToRemote();
        CommunityServer.say(p, "Infinity Ore Survey • your banner was added to an empty slot."); return 1;
    }
    static InfinityJourney.View view(ServerPlayer p) {
        var choices = new ArrayList<InfinityJourney.Choice>(); int mask = found(p);
        var body = new StringBuilder("Start the survey, then break one of each listed ore in Survival or Hardcore. Normal and deepslate variants count together; Nether gold also counts. Placed ores count too. The survey changes no blocks, drops or experience. Pausing keeps progress.\n\n");
        if (!writable(p)) body.append("Saved survey data needs host review; it is preserved and changes are unavailable.\n\n");
        for (int i = 0; i < NAMES.size(); i++) body.append((mask & (1 << i)) != 0 ? "✓ " : "○ ").append(NAMES.get(i)).append('\n');
        boolean activeAtView = active(p);
        choices.add(new InfinityJourney.Choice(Items.IRON_PICKAXE, activeAtView ? "Pause survey • progress stays" : "Start or resume survey", () -> {
            if (active(p) != activeAtView || setActive(p, !activeAtView) == 0) CommunityServer.say(p, "Survey state changed or is unavailable; reopen the survey.");
            InfinityJourney.open(p, InfinityJourney.Page.SURVEY);
        }));
        choices.add(new InfinityJourney.Choice(Items.BANNER.blue(), "Get Survey Banner • " + (mask == ALL ? "unlocked" : "record all eight"), () -> {
            if (mask != ALL || giveBanner(p) == 0) CommunityServer.say(p, "Record all eight ores, clear a slot and reopen the survey; nothing was replaced.");
            InfinityJourney.open(p, InfinityJourney.Page.SURVEY);
        }));
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(p, InfinityJourney.Page.JOURNEY)));
        return new InfinityJourney.View("Infinity Ore Survey", body.toString(), List.copyOf(choices));
    }
    static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, block, blockEntity) -> {
            if (!(player instanceof ServerPlayer p) || p.level() != world) return;
            try { observe(p, block); } catch (RuntimeException failure) {
                System.err.println("[Infinity] Ore Survey progress could not be confirmed: " + failure);
                CommunityServer.say(p, "Ore Survey progress could not be confirmed. The ordinary block break remains separate.");
            }
        });
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

/** Observes player-caused hostile defeats; does not target, alter or remove entities. */
final class InfinityDefender {
    static final String KEY = "infinity_defender";
    static final List<String> NAMES = List.of("Zombie", "Skeleton", "Spider", "Creeper", "Enderman", "Blaze", "Guardian", "Wither Skeleton");
    static final int ALL = 255;
    private InfinityDefender() {}
    static CompoundTag state(ServerPlayer p) { return GameModes.state(p).getCompoundOrEmpty(KEY); }
    static boolean writable(ServerPlayer p) {
        var root = GameModes.state(p);
        if (!root.contains(KEY)) return true;
        var saved = root.getCompound(KEY).orElse(null);
        if (saved == null || !(saved.get("format") instanceof IntTag) || saved.getIntOr("format", -1) != 1
            || saved.contains("defeated") && !(saved.get("defeated") instanceof IntTag)
            || saved.contains("active") && !(saved.get("active") instanceof ByteTag)) return false;
        int defeated = saved.getIntOr("defeated", 0); byte active = saved.getByteOr("active", (byte) 0);
        return defeated >= 0 && defeated <= ALL && (active == 0 || active == 1);
    }
    static int defeated(ServerPlayer p) { return writable(p) ? state(p).getIntOr("defeated", 0) : 0; }
    static boolean active(ServerPlayer p) { return writable(p) && state(p).getBooleanOr("active", false); }
    static int setActive(ServerPlayer p, boolean enabled) {
        if (!InfinityJourney.emptyScreen(p) || !writable(p)) return 0;
        var next = state(p).copy(); next.putInt("format", 1); next.putBoolean("active", enabled);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p); return 1;
    }
    static int type(EntityType<?> type) {
        if (type == EntityTypes.ZOMBIE || type == EntityTypes.HUSK || type == EntityTypes.DROWNED) return 0;
        if (type == EntityTypes.SKELETON || type == EntityTypes.STRAY || type == EntityTypes.BOGGED) return 1;
        if (type == EntityTypes.SPIDER || type == EntityTypes.CAVE_SPIDER) return 2;
        if (type == EntityTypes.CREEPER) return 3;
        if (type == EntityTypes.ENDERMAN) return 4;
        if (type == EntityTypes.BLAZE) return 5;
        if (type == EntityTypes.GUARDIAN || type == EntityTypes.ELDER_GUARDIAN) return 6;
        if (type == EntityTypes.WITHER_SKELETON) return 7;
        return -1;
    }
    static boolean observe(ServerPlayer p, LivingEntity entity) {
        int index = type(entity.getType());
        if (index < 0 || !active(p) || entity.level() != p.level()
            || entity.entityTags().contains(AgentCompanions.TAG) || entity.entityTags().contains("convergence_friend")) return false;
        var mode = GameModes.current(p);
        if (!ServerMenu.allowed(p) || p.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
            || (mode != GameModes.Mode.SURVIVAL && mode != GameModes.Mode.HARDCORE) || GameModes.of(p.level()) != mode) return false;
        int before = defeated(p), bit = 1 << index;
        if ((before & bit) != 0) return false;
        var next = state(p).copy(); next.putInt("format", 1); next.putInt("defeated", before | bit);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p);
        CommunityServer.say(p, "Infinity Defender • " + NAMES.get(index) + " recorded! " + Integer.bitCount(before | bit) + "/8 encounters.");
        p.level().sendParticles(p, ParticleTypes.END_ROD, false, false, p.getX(), p.getY() + 1, p.getZ(), 6, .3, .3, .3, .01);
        return true;
    }
    static ItemStack banner(ServerPlayer p) {
        var stack = CommonsEvents.named(Items.BANNER.red(), 1, "Infinity • Defender Banner",
            "A constellation earned by defending against eight hostile families.");
        var patterns = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        stack.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .add(patterns.getOrThrow(BannerPatterns.CROSS), DyeColor.BLACK)
            .add(patterns.getOrThrow(BannerPatterns.RHOMBUS_MIDDLE), DyeColor.PURPLE)
            .add(patterns.getOrThrow(BannerPatterns.CIRCLE_MIDDLE), DyeColor.CYAN).build());
        return stack;
    }
    static int giveBanner(ServerPlayer p) {
        if (!InfinityJourney.emptyScreen(p) || !writable(p) || defeated(p) != ALL) return 0;
        int slot = -1;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) { slot = i; break; }
        if (slot < 0) return 0;
        p.getInventory().setItem(slot, banner(p)); PlayerPersistence.save(p); p.inventoryMenu.sendAllDataToRemote();
        CommunityServer.say(p, "Infinity Defender • your banner was added to an empty slot."); return 1;
    }
    static InfinityJourney.View view(ServerPlayer p) {
        var choices = new ArrayList<InfinityJourney.Choice>(); int mask = defeated(p);
        var body = new StringBuilder("Start the Defender path, then personally defeat each listed hostile family in Survival or Hardcore. Spawned hostiles can count. This challenge does not choose targets, change combat, or alter creatures. Pausing keeps progress.\n\n");
        if (!writable(p)) body.append("Saved Defender data needs host review; it is preserved and changes are unavailable.\n\n");
        for (int i = 0; i < NAMES.size(); i++) body.append((mask & (1 << i)) != 0 ? "✓ " : "○ ").append(NAMES.get(i)).append('\n');
        boolean activeAtView = active(p);
        choices.add(new InfinityJourney.Choice(Items.IRON_SWORD, activeAtView ? "Pause Defender • progress stays" : "Start or resume Defender", () -> {
            if (active(p) != activeAtView || setActive(p, !activeAtView) == 0) CommunityServer.say(p, "Defender state changed or is unavailable; reopen the page.");
            InfinityJourney.open(p, InfinityJourney.Page.DEFENDER);
        }));
        choices.add(new InfinityJourney.Choice(Items.BANNER.red(), "Get Defender Banner • " + (mask == ALL ? "unlocked" : "record all eight"), () -> {
            if (mask != ALL || giveBanner(p) == 0) CommunityServer.say(p, "Record all eight families, clear a slot and reopen Defender; nothing was replaced.");
            InfinityJourney.open(p, InfinityJourney.Page.DEFENDER);
        }));
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(p, InfinityJourney.Page.JOURNEY)));
        return new InfinityJourney.View("Infinity Defender", body.toString(), List.copyOf(choices));
    }
    static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getEntity() instanceof ServerPlayer p)) return;
            try { observe(p, entity); } catch (RuntimeException failure) {
                System.err.println("[Infinity] Defender progress could not be confirmed: " + failure);
                CommunityServer.say(p, "Defender progress could not be confirmed. The ordinary combat outcome remains separate.");
            }
        });
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

/** Opt-in challenge across the existing courses; no course blocks or saved bests are rewritten. */
final class InfinityRelay {
    static final String KEY = "infinity_circuit_relay";
    static final List<String> COURSES = List.of("parkour", "sprint", "dropper", "redlight", "crystalhunt",
        "colorrush", "memory", "gatedash", "ruins", "maze");
    static final int ALL = (1 << 10) - 1;
    private InfinityRelay() {}
    static CompoundTag state(ServerPlayer p) { return GameModes.state(p).getCompoundOrEmpty(KEY); }
    static boolean writable(ServerPlayer p) {
        var root = GameModes.state(p);
        if (!root.contains(KEY)) return true;
        var tag = root.getCompound(KEY).orElse(null);
        if (tag == null || !(tag.get("format") instanceof IntTag) || tag.getIntOr("format", -1) != 1
            || tag.contains("completed") && !(tag.get("completed") instanceof IntTag)
            || tag.contains("active") && !(tag.get("active") instanceof ByteTag)) return false;
        int completed = tag.getIntOr("completed", 0); byte active = tag.getByteOr("active", (byte) 0);
        return completed >= 0 && completed <= ALL && (active == 0 || active == 1);
    }
    static int completed(ServerPlayer p) { return writable(p) ? state(p).getIntOr("completed", 0) : 0; }
    static boolean active(ServerPlayer p) { return writable(p) && state(p).getBooleanOr("active", false); }
    static int setActive(ServerPlayer p, boolean enabled) {
        if (!InfinityJourney.emptyScreen(p) || !writable(p)) return 0;
        var next = state(p).copy(); next.putInt("format", 1); next.putBoolean("active", enabled);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p); return 1;
    }
    /** Called only after the current native course run finishes successfully. */
    static boolean finished(ServerPlayer p, String id) {
        int index = COURSES.indexOf(id);
        if (index < 0 || !active(p) || !p.isAlive() || p.isSpectator() || p.hasDisconnected()) return false;
        var spec = ModeMaps.MAPS.get(id);
        if (spec == null || GameModes.current(p) != spec.mode() || GameModes.of(p.level()) != spec.mode()) return false;
        int before = completed(p), bit = 1 << index;
        if ((before & bit) != 0) return false;
        var next = state(p).copy(); next.putInt("format", 1); next.putInt("completed", before | bit);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p);
        CommunityServer.say(p, "Infinity Circuit Relay • " + spec.title() + " lit! " + Integer.bitCount(before | bit) + "/" + COURSES.size() + " courses.");
        p.level().sendParticles(p, ParticleTypes.END_ROD, false, false, p.getX(), p.getY() + 1, p.getZ(), 8, .35, .35, .35, .01);
        return true;
    }
    static ItemStack banner(ServerPlayer p) {
        var stack = CommonsEvents.named(Items.BANNER.black(), 1, "Infinity • Circuit Relay Banner",
            "A constellation earned by finishing every Infinity course.");
        var patterns = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        stack.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .add(patterns.getOrThrow(BannerPatterns.CROSS), DyeColor.PURPLE)
            .add(patterns.getOrThrow(BannerPatterns.RHOMBUS_MIDDLE), DyeColor.CYAN)
            .add(patterns.getOrThrow(BannerPatterns.CIRCLE_MIDDLE), DyeColor.WHITE).build());
        return stack;
    }
    static int giveBanner(ServerPlayer p) {
        if (!InfinityJourney.emptyScreen(p) || !writable(p) || completed(p) != ALL) return 0;
        int slot = -1;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) { slot = i; break; }
        if (slot < 0) return 0;
        p.getInventory().setItem(slot, banner(p)); PlayerPersistence.save(p); p.inventoryMenu.sendAllDataToRemote();
        CommunityServer.say(p, "Infinity Circuit Relay • your banner was added to an empty slot."); return 1;
    }
    static InfinityJourney.View view(ServerPlayer p) {
        var choices = new ArrayList<InfinityJourney.Choice>();
        int mask = completed(p);
        var body = new StringBuilder("Light the Infinity circuit by finishing each existing minigame and adventure course once after starting the relay. Your best times remain separate. Stopping the relay keeps progress. Existing course blocks and items are not replaced.\n\n");
        if (!writable(p)) body.append("Saved relay data needs host review; it is preserved and changes are unavailable.\n\n");
        for (int i = 0; i < COURSES.size(); i++) {
            String id = COURSES.get(i); var spec = ModeMaps.MAPS.get(id);
            body.append((mask & (1 << i)) != 0 ? "✓ " : "○ ").append(spec == null ? id : spec.title());
            if (!ModeMaps.available(id)) body.append(" • currently unavailable");
            body.append('\n');
        }
        boolean activeAtView = active(p);
        choices.add(new InfinityJourney.Choice(Items.REDSTONE_TORCH, activeAtView ? "Pause relay • progress stays" : "Start or resume relay", () -> {
            if (active(p) != activeAtView || setActive(p, !activeAtView) == 0) CommunityServer.say(p, "Relay state changed or is unavailable; reopen the relay.");
            InfinityJourney.open(p, InfinityJourney.Page.RELAY);
        }));
        choices.add(new InfinityJourney.Choice(Items.SLIME_BALL, "Choose a minigame", () -> CourseSelector.open(p, GameModes.Mode.MINIGAMES)));
        choices.add(new InfinityJourney.Choice(Items.MAP, "Choose an adventure", () -> CourseSelector.open(p, GameModes.Mode.ADVENTURE)));
        choices.add(new InfinityJourney.Choice(Items.BANNER.black(), "Get Relay Banner • " + (mask == ALL ? "unlocked" : "finish all ten"), () -> {
            if (mask != ALL || giveBanner(p) == 0) CommunityServer.say(p, "Finish all ten courses, clear a slot and reopen the relay; nothing was replaced.");
            InfinityJourney.open(p, InfinityJourney.Page.RELAY);
        }));
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Infinity Journey", () -> InfinityJourney.open(p, InfinityJourney.Page.JOURNEY)));
        return new InfinityJourney.View("Infinity Circuit Relay", body.toString(), List.copyOf(choices));
    }
}

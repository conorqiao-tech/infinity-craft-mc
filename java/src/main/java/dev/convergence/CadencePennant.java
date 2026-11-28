package dev.convergence;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

record CadencePennant(int solo, StarLoom.Chart call, StarLoom.Chart left, StarLoom.Chart right) {
    private static final List<String> MARKS = List.of("Pillar", "Sash", "Roundel");
    private static final List<ResourceKey<BannerPattern>> SYMBOLS = List.of(
        BannerPatterns.STRIPE_CENTER, BannerPatterns.STRIPE_DOWNRIGHT, BannerPatterns.CIRCLE_MIDDLE);

    static DyeColor ink(StarLoom.Chart chart) {
        return switch (chart) {
            case KITE -> DyeColor.CYAN;
            case HALF_MOON -> DyeColor.PURPLE;
            case EMBER_PEAKS -> DyeColor.ORANGE;
        };
    }

    private static StarLoom.Chart chart(DyeColor dye) {
        for (var chart : StarLoom.Chart.values()) if (ink(chart) == dye) return chart;
        return null;
    }

    static CadencePennant read(BannerBlockEntity banner) {
        var layers = banner.getPatterns().layers();
        if (banner.getBaseColor() != DyeColor.WHITE || layers.size() != 3
                || !layers.get(0).pattern().is(BannerPatterns.STRIPE_LEFT)
                || !layers.get(1).pattern().is(BannerPatterns.STRIPE_RIGHT)) return null;
        var left = chart(layers.get(0).color());
        var right = chart(layers.get(1).color());
        var call = chart(layers.get(2).color());
        if (left == null || right == null || call == null) return null;
        for (int solo = 0; solo < SYMBOLS.size(); solo++) {
            if (layers.get(2).pattern().is(SYMBOLS.get(solo))) return new CadencePennant(solo, call, left, right);
        }
        return null;
    }

    static ItemStack banner(ServerPlayer p, ChordSeal seal, int solo) {
        int left = (solo + 1) % 3, right = (solo + 2) % 3;
        var flag = CommonsEvents.named(Items.BANNER.white(), 1, "Cadence Pennant • " + MARKS.get(solo),
            "Solo • " + seal.voiceName(solo), "Left • " + seal.voiceName(left), "Right • " + seal.voiceName(right));
        var patterns = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        flag.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .add(patterns.getOrThrow(BannerPatterns.STRIPE_LEFT), ink(seal.voices().get(left)))
            .add(patterns.getOrThrow(BannerPatterns.STRIPE_RIGHT), ink(seal.voices().get(right)))
            .add(patterns.getOrThrow(SYMBOLS.get(solo)), ink(seal.voices().get(solo))).build());
        return flag;
    }
}

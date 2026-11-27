package dev.convergence;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

final class CadencePennant {
    private static final List<String> MARKS = List.of("Pillar", "Sash", "Roundel");
    private static DyeColor ink(StarLoom.Chart chart) {
        return switch (chart) {
            case KITE -> DyeColor.CYAN;
            case HALF_MOON -> DyeColor.PURPLE;
            case EMBER_PEAKS -> DyeColor.ORANGE;
        };
    }

    private CadencePennant() {}

    static ItemStack banner(ServerPlayer p, ChordSeal seal, int solo) {
        int left = (solo + 1) % 3, right = (solo + 2) % 3;
        var flag = CommonsEvents.named(Items.BANNER.white(), 1, "Cadence Pennant • " + MARKS.get(solo),
            "Solo • " + seal.voiceName(solo), "Left • " + seal.voiceName(left), "Right • " + seal.voiceName(right));
        var mark = switch (solo) {
            case 0 -> BannerPatterns.STRIPE_CENTER;
            case 1 -> BannerPatterns.STRIPE_DOWNRIGHT;
            case 2 -> BannerPatterns.CIRCLE_MIDDLE;
            default -> throw new IllegalArgumentException("Unknown solo instrument " + solo);
        };
        var patterns = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        flag.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .add(patterns.getOrThrow(BannerPatterns.STRIPE_LEFT), ink(seal.voices().get(left)))
            .add(patterns.getOrThrow(BannerPatterns.STRIPE_RIGHT), ink(seal.voices().get(right)))
            .add(patterns.getOrThrow(mark), ink(seal.voices().get(solo))).build());
        return flag;
    }
}

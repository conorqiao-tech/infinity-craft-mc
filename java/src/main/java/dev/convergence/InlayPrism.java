package dev.convergence;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

record InlayPrism(ChordSeal score) {
    private static final String KEY = "infinity_inlay_prism_v1";

    ItemStack item() {
        var prism = CommonsEvents.named(Items.QUARTZ, 1, "Inlay Prism",
            "First • " + score.voiceName(score.cornerAt(0)), "Middle • " + score.voiceName(score.cornerAt(1)),
            "Last • " + score.voiceName(score.cornerAt(2)), "Use for the left facet. Sneak-use for the right.");
        prism.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        score.writeScore(prism, KEY);
        return prism;
    }

    static InlayPrism read(ItemStack item) {
        if (!item.is(Items.QUARTZ)) return null;
        var score = ChordSeal.readScore(item, KEY);
        return score == null ? null : new InlayPrism(score);
    }

    void play(ServerPlayer p) {
        if (!CodaPostcard.canListen(p)) return;
        boolean rightFacet = p.isShiftKeyDown();
        int first = score.cornerAt(rightFacet ? 1 : 0);
        int second = score.cornerAt(rightFacet ? 2 : 1);
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        CircuitConductor.soundVoice(p, first, score.voices().get(first));
        CircuitConductor.soundVoice(p, second, score.voices().get(second));
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        var center = p.getEyePosition().add(forward.scale(1.5));
        var tips = List.of(center.subtract(right.scale(.46)), center, center.add(right.scale(.46)));
        for (int i = 0; i < tips.size(); i++) {
            var tip = tips.get(i);
            int role = score.cornerAt(i);
            GalleryTrail.spark(p, new DustParticleOptions(score.voices().get(role).ink, .8f), tip);
            if (role == first || role == second) GalleryTrail.spark(p, ParticleTypes.GLOW, tip);
        }
        var top = center.add(up.scale(.28));
        var bottom = center.subtract(up.scale(.28));
        for (var corner : List.of(center.add(up.scale(.09)), center.add(right.scale(.065)),
                                  center.subtract(up.scale(.09)), center.subtract(right.scale(.065)))) {
            GalleryTrail.spark(p, ParticleTypes.WAX_OFF, corner);
        }
        for (int i : List.of(0, 2)) {
            var tip = tips.get(i);
            boolean active = (i == 2) == rightFacet;
            for (int j = 0; j < 8; j += active ? 1 : 3) {
                var effect = active ? ParticleTypes.END_ROD : ParticleTypes.WAX_ON;
                GalleryTrail.spark(p, effect, top.lerp(tip, j / 8.0));
                GalleryTrail.spark(p, effect, bottom.lerp(tip, j / 8.0));
            }
        }
        ConcertStage.overlay(p, "Inlay Prism • " + (rightFacet ? "Right" : "Left") + " facet • "
            + score.voiceName(first) + " + " + score.voiceName(second));
    }
}

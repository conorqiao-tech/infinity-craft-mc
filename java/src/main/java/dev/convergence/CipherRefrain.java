package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

record CipherRefrain(boolean backward, int played, Feedback feedback, RefrainCadence cadence) {
    private CipherRefrain(boolean backward, int played, Feedback feedback) { this(backward, played, feedback, null); }
    enum Feedback { READY, MISS, HINT }

    static void addDirections(List<InfinityJourney.Choice> choices, ServerPlayer p, SealCipher round) {
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Forward refrain",
            () -> round.openRefrain(p, new CipherRefrain(false, 0, Feedback.READY))));
        choices.add(new InfinityJourney.Choice(Items.SPECTRAL_ARROW, "Backward refrain",
            () -> round.openRefrain(p, new CipherRefrain(true, 0, Feedback.READY))));
    }

    CipherRefrain withCadence(RefrainCadence next) {
        return new CipherRefrain(backward, played, feedback, next);
    }

    int voice(ChordSeal seal, int beat) {
        int clue = backward ? 2 - beat : beat;
        return seal.cornerAt(clue + 2);
    }

    CipherRefrain answer(ChordSeal seal, int guess) {
        if (guess == voice(seal, played)) return new CipherRefrain(backward, played + 1, Feedback.READY);
        return new CipherRefrain(backward, played, Feedback.MISS);
    }

    private String knot(ChordSeal seal, int i) {
        if (i < played) return SealCipher.NAMES.get(voice(seal, i));
        return i == played ? "Next" : "Open";
    }

    InfinityJourney.View view(ServerPlayer p, SealCipher round) {
        if (cadence != null) return cadence.view(p, round);
        var choices = new ArrayList<InfinityJourney.Choice>();
        var seal = round.seal();
        String score = (backward ? "Backward" : "Forward") + " • "
            + (backward ? String.join(" ← ", knot(seal, 2), knot(seal, 1), knot(seal, 0))
                        : String.join(" → ", knot(seal, 0), knot(seal, 1), knot(seal, 2)));
        String prompt;
        if (played == 3) {
            prompt = "Three knots tied. The full chord rings.";
            choices.add(new InfinityJourney.Choice(Items.STRING, "Weave a cadence",
                () -> round.openRefrain(p, withCadence(RefrainCadence.START))));
            addDirections(choices, p, round);
        } else {
            prompt = switch (feedback) {
                case READY -> "Play the missing voices in " + (backward ? "reverse clue order." : "clue order.");
                case MISS -> "Try another voice, or reveal the next one.";
                case HINT -> "Next voice: " + SealCipher.NAMES.get(voice(seal, played));
            };
            for (int i = 0; i < SealCipher.NAMES.size(); i++) {
                int guess = i;
                choices.add(new InfinityJourney.Choice(SealCipher.ICONS.get(i), SealCipher.NAMES.get(i),
                    () -> round.playRefrain(p, guess)));
            }
            choices.add(new InfinityJourney.Choice(Items.SPYGLASS, "Reveal next voice",
                () -> round.openRefrain(p, new CipherRefrain(backward, played, Feedback.HINT))));
        }
        choices.add(new InfinityJourney.Choice(Items.CLAY_BALL, "Back to cipher", () -> round.openRefrain(p, null)));
        return new InfinityJourney.View("Cipher Refrain • " + played + "/3", score + "\n" + prompt, choices);
    }

    void draw(ServerPlayer p, SimpleContainer chest, ChordSeal seal) {
        if (cadence != null) { cadence.draw(p, chest, seal, this); return; }
        for (int i = 0; i < 3; i++) {
            var icon = i < played ? SealCipher.ICONS.get(voice(seal, i)) : i == played ? Items.TARGET : Items.IRON_NUGGET;
            int slot = backward ? 15 - i * 2 : 11 + i * 2;
            CreativeStudio.icon(chest, slot, icon, "Knot " + (i + 1) + " • " + knot(seal, i));
            if (i < 2) CreativeStudio.icon(chest, 12 + i * 2, Items.STRING, "Refrain thread");
        }
    }

    void showKnots(ServerPlayer p) {
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        if (backward) right = right.scale(-1);
        var start = p.getEyePosition().add(forward.scale(1.6)).subtract(right.scale(.4));
        for (int i = 0; i < played; i++) {
            var center = start.add(right.scale(i * .4));
            for (int j = 0; j < 12; j++) {
                double angle = Math.PI * j / 6;
                var offset = right.scale(.14 * Math.cos(angle)).add(up.scale(.085 * Math.sin(2 * angle)));
                GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(offset));
            }
            if (i > 0) GalleryTrail.spark(p, ParticleTypes.WAX_ON, center.subtract(right.scale(.2)));
        }
    }
}

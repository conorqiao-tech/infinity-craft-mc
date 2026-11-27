package dev.convergence;

import java.util.ArrayList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

record RefrainCadence(int solo, Phase phase, BannerWeaving.Result pennant) {
    private RefrainCadence(int solo, Phase phase) { this(solo, phase, null); }
    enum Phase { CHOOSE, ANSWER, RETRY, COMPLETE }
    static final RefrainCadence START = new RefrainCadence(-1, Phase.CHOOSE);

    private static int next(int voice) { return (voice + 1) % 3; }

    private static String pairName(int first) {
        return SealCipher.NAMES.get(first) + " + " + SealCipher.NAMES.get(next(first));
    }

    private void call(ServerPlayer p, SealCipher round, int voice) {
        if (!round.current(p)) return;
        CircuitConductor.soundVoice(p, voice, round.seal().voices().get(voice));
        drawThread(p, false);
        round.openRefrain(p, round.refrain().withCadence(new RefrainCadence(voice, Phase.ANSWER)));
    }

    private void answer(ServerPlayer p, SealCipher round, int first) {
        if (!round.current(p)) return;
        int second = next(first);
        boolean correct = first != solo && second != solo;
        if (correct) {
            CircuitConductor.soundVoice(p, first, round.seal().voices().get(first));
            CircuitConductor.soundVoice(p, second, round.seal().voices().get(second));
            drawThread(p, true);
        }
        var result = new RefrainCadence(solo, correct ? Phase.COMPLETE : Phase.RETRY);
        round.openRefrain(p, round.refrain().withCadence(result));
    }

    private void weavePennant(ServerPlayer p, SealCipher round) {
        if (phase != Phase.COMPLETE || pennant == BannerWeaving.Result.WOVEN || !round.current(p)) return;
        var flag = CadencePennant.banner(p, round.seal(), solo);
        var result = BannerWeaving.weave(p, flag, Items.BANNER.white());
        round.openRefrain(p, round.refrain().withCadence(new RefrainCadence(solo, phase, result)));
    }

    InfinityJourney.View view(ServerPlayer p, SealCipher round) {
        var choices = new ArrayList<InfinityJourney.Choice>();
        var refrain = round.refrain();
        String prompt;
        if (phase == Phase.CHOOSE) {
            prompt = "Choose a knot from your finished refrain to play the solo.";
            for (int i = 0; i < 3; i++) {
                int voice = refrain.voice(round.seal(), i);
                choices.add(new InfinityJourney.Choice(SealCipher.ICONS.get(voice),
                    "Knot " + (i + 1) + " • " + SealCipher.NAMES.get(voice), () -> call(p, round, voice)));
            }
        } else if (phase == Phase.COMPLETE) {
            prompt = round.seal().voiceName(solo) + " called. " + pairName(next(solo)) + " answered together.";
            choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Play full chord", () -> {
                if (round.current(p)) {
                    round.seal().soundBeat(p, 3);
                    round.openRefrain(p, refrain);
                }
            }));
            prompt += "\n" + switch (pennant) {
                case null -> "Weave a Cadence Pennant with one plain white banner.";
                case NO_MATERIAL -> "Bring a plain white banner in your hotbar or main inventory.";
                case NO_ROOM -> "Make room for the pennant, then try again.";
                case WOVEN -> "Pennant woven. Place it or give it to another player.";
            };
            if (pennant != BannerWeaving.Result.WOVEN) choices.add(new InfinityJourney.Choice(Items.BANNER.white(),
                "Weave pennant • 1 white banner", () -> weavePennant(p, round)));
            choices.add(new InfinityJourney.Choice(Items.STRING, "Weave again", () -> round.openRefrain(p, refrain.withCadence(START))));
        } else {
            prompt = "Solo • " + round.seal().voiceName(solo) + "\n"
                + (phase == Phase.RETRY ? "That pair includes the soloist. Try another pair." : "Which two instruments answer?");
            for (int i = 0; i < 3; i++) {
                int first = i;
                choices.add(new InfinityJourney.Choice(SealCipher.ICONS.get(i), pairName(i), () -> answer(p, round, first)));
            }
            choices.add(new InfinityJourney.Choice(Items.STRING, "Choose another solo",
                () -> round.openRefrain(p, refrain.withCadence(START))));
        }
        choices.add(new InfinityJourney.Choice(Items.CLAY_BALL, "Back to refrain", () -> round.openRefrain(p, refrain.withCadence(null))));
        return new InfinityJourney.View("Refrain Cadence", prompt, choices);
    }

    void draw(ServerPlayer p, SimpleContainer chest, ChordSeal seal, CipherRefrain refrain) {
        if (phase == Phase.CHOOSE) {
            for (int i = 0; i < 3; i++) {
                int voice = refrain.voice(seal, i);
                CreativeStudio.icon(chest, 11 + 2 * i, SealCipher.ICONS.get(voice), "Knot " + (i + 1) + " • " + seal.voiceName(voice));
            }
            return;
        }
        if (phase == Phase.COMPLETE) chest.setItem(15, CadencePennant.banner(p, seal, solo));
        CreativeStudio.icon(chest, 13, SealCipher.ICONS.get(solo), "Call • " + seal.voiceName(solo));
        CreativeStudio.icon(chest, 22, Items.STRING, "Cadence thread");
        for (int i = 0; i < 2; i++) {
            int voice = (solo + 1 + i) % 3;
            boolean complete = phase == Phase.COMPLETE;
            CreativeStudio.icon(chest, 21 + i * 2, complete ? SealCipher.ICONS.get(voice) : Items.IRON_NUGGET,
                complete ? "Answer • " + seal.voiceName(voice) : "Open answer");
        }
    }

    private static void drawThread(ServerPlayer p, boolean fork) {
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        var center = p.getEyePosition().add(forward.scale(1.6));
        for (int i = 0; i < 7; i++) {
            GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(up.scale(.05 * i)));
            if (fork && i > 0) {
                var branch = center.subtract(up.scale(.035 * i));
                GalleryTrail.spark(p, ParticleTypes.END_ROD, branch.add(right.scale(.05 * i)));
                GalleryTrail.spark(p, ParticleTypes.END_ROD, branch.subtract(right.scale(.05 * i)));
            }
        }
    }
}

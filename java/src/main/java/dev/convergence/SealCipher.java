package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

record SealCipher(ChordSeal seal, int solved, boolean retry) {
    private static final AttachmentType<SealCipher> ACTIVE =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "seal_cipher"));
    private static final List<String> NAMES = List.of("Chime", "Harp", "Bell");
    private static final List<Item> ICONS = List.of(Items.AMETHYST_SHARD, Items.STRING, Items.BELL);

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND || p.isSpectator()
                || !world.getBlockState(hit.getBlockPos()).is(Blocks.NOTE_BLOCK)) return InteractionResult.PASS;
            var seal = ChordSeal.read(p.getMainHandItem());
            if (seal == null) return InteractionResult.PASS;
            if (CodaPostcard.canListen(p)) new SealCipher(seal, 0, false).present(p, true);
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private int missing() { return seal.cornerAt(solved + 2); }

    private void present(ServerPlayer p, boolean sound) {
        p.setAttached(ACTIVE, this);
        if (sound) {
            InfinityChimes.stopPlayback(p);
            EncoreDuet.leave(p);
            seal.soundBeat(p, solved);
            showMarks(p);
        }
        InfinityJourney.open(p, InfinityJourney.Page.SEAL_CIPHER);
    }

    private boolean current(ServerPlayer p) {
        return p.getAttached(ACTIVE) == this && seal.equals(ChordSeal.read(p.getMainHandItem())) && CodaPostcard.canListen(p);
    }

    private void answer(ServerPlayer p, int guess) {
        if (!current(p) || solved == 3) return;
        boolean correct = guess == missing();
        new SealCipher(seal, correct ? solved + 1 : solved, !correct).present(p, correct);
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var round = p.getAttached(ACTIVE);
        if (round == null || !round.seal.equals(ChordSeal.read(p.getMainHandItem())))
            return new InfinityJourney.View("Seal Cipher", "Hold a stamped seal and use it on a note block.", List.of());
        var choices = new ArrayList<InfinityJourney.Choice>();
        if (round.solved == 3) {
            choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Play again", () -> {
                if (round.current(p)) new SealCipher(round.seal, 0, false).present(p, true);
            }));
            return new InfinityJourney.View("Seal Cipher • 3/3", "Cipher solved. Chime, harp and bell sound together.", choices);
        }
        for (int i = 0; i < NAMES.size(); i++) {
            int guess = i;
            choices.add(new InfinityJourney.Choice(ICONS.get(i), NAMES.get(i), () -> round.answer(p, guess)));
        }
        choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Replay clue", () -> {
            if (round.current(p)) round.present(p, true);
        }));
        var score = round.seal;
        String clue = score.voiceName(score.cornerAt(round.solved)) + " + " + score.voiceName(score.cornerAt(round.solved + 1));
        String prompt = round.retry ? "That voice is sounding. Try the third instrument." : "Which instrument is silent?";
        return new InfinityJourney.View("Seal Cipher • " + round.solved + "/3", "Clue " + (round.solved + 1)
            + " • " + clue + "\n" + prompt, choices);
    }

    static void draw(ServerPlayer p, SimpleContainer chest) {
        var round = p.getAttached(ACTIVE);
        if (round == null || !round.seal.equals(ChordSeal.read(p.getMainHandItem()))) return;
        for (int i = 0; i < 3; i++) {
            boolean silent = round.solved < 3 && round.missing() == i;
            CreativeStudio.icon(chest, 10 + i * 2, silent ? Items.FLINT : ICONS.get(i),
                silent ? "Empty socket" : round.seal.voiceName(i));
            CreativeStudio.icon(chest, 19 + i * 2, i < round.solved ? Items.GLOWSTONE_DUST : Items.CLAY_BALL,
                "Clue " + (i + 1) + (i < round.solved ? " • solved" : " • waiting"));
        }
    }

    private void showMarks(ServerPlayer p) {
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        var center = p.getEyePosition().add(forward.scale(1.6));
        for (int i = 0; i < 3; i++) {
            var slot = center.add(right.scale((i - 1) * .42));
            if (solved < 3 && i == missing()) {
                GalleryTrail.spark(p, ParticleTypes.WAX_ON, slot.add(up.scale(-.17)));
                continue;
            }
            for (int dot = 0; dot < 12; dot++) {
                double t = dot / 11.0;
                double angle = dot * Math.PI / 6;
                var point = switch (i) {
                    case 0 -> slot.add(up.scale(.3 * (t - .5)));
                    case 1 -> slot.add(right.scale(.32 * (t - .5))).add(up.scale(.16 - .6 * Math.abs(t - .5)));
                    default -> slot.add(right.scale(.14 * Math.cos(angle))).add(up.scale(.14 * Math.sin(angle)));
                };
                GalleryTrail.spark(p, ParticleTypes.END_ROD, point);
            }
            if (solved == 3) GalleryTrail.spark(p, ParticleTypes.GLOW, slot);
        }
    }
}

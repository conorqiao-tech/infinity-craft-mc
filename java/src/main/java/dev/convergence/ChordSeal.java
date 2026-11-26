package dev.convergence;

import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

record ChordSeal(List<StarLoom.Chart> voices, int opening, int direction) {
    private static final String KEY = "infinity_chord_seal_v1";
    private record Playback(ChordSeal seal, int nextBeat) {}
    private static final AttachmentType<Playback> PLAYBACK =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "chord_seal_listener"));

    ChordSeal { voices = List.copyOf(voices); }

    ItemStack item() {
        var item = CommonsEvents.named(Items.CLAY_BALL, 1, "Infinity Chord Seal", MirrorComposer.names(voices),
            "Use to hear the next beat. Sneak-use to start again.", "Use on a note block to solve the Seal Cipher.");
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        var score = new CompoundTag();
        score.putIntArray("pitches", voices.stream().mapToInt(v -> v.note).toArray());
        score.putIntArray("route", new int[]{opening, direction});
        var tag = new CompoundTag();
        tag.put(KEY, score);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return item;
    }

    static ChordSeal read(ItemStack item) {
        if (!item.is(Items.CLAY_BALL)) return null;
        var data = item.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        var score = data.copyTag().getCompound(KEY).orElse(null);
        if (score == null || !(score.get("pitches") instanceof IntArrayTag pitches) || pitches.size() != 3
            || !(score.get("route") instanceof IntArrayTag route) || route.size() != 2) return null;
        int[] order = route.getAsIntArray();
        if (order[0] < 0 || order[0] > 2 || (order[1] != -1 && order[1] != 1)) return null;
        var voices = MirrorComposer.decode(pitches);
        return voices == null ? null : new ChordSeal(voices, order[0], order[1]);
    }

    void play(ServerPlayer p) {
        if (!CodaPostcard.canListen(p)) return;
        var previous = p.getAttached(PLAYBACK);
        int beat = !p.isShiftKeyDown() && previous != null && equals(previous.seal) ? previous.nextBeat : 0;
        int lead = cornerAt(beat);
        int answer = cornerAt(beat + 1);
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        soundBeat(p, beat);
        medallion(p, beat);
        String heard = beat == 3 ? "Full triad" : voiceName(lead) + " + " + voiceName(answer);
        ConcertStage.overlay(p, "Chord Seal " + (beat + 1) + "/4 • " + heard);
        p.setAttached(PLAYBACK, new Playback(this, (beat + 1) % 4));
    }

    int cornerAt(int beat) {
        return Math.floorMod(opening + direction * beat, 3);
    }

    void soundBeat(ServerPlayer p, int beat) {
        if (beat == 3) {
            for (int i = 0; i < 3; i++) CircuitConductor.soundVoice(p, i, voices.get(i));
        } else {
            int lead = cornerAt(beat);
            int answer = cornerAt(beat + 1);
            CircuitConductor.soundVoice(p, lead, voices.get(lead));
            CircuitConductor.soundVoice(p, answer, voices.get(answer));
        }
    }

    String voiceName(int corner) {
        return voices.get(corner).label + " " + CircuitConductor.VOICES.get(corner);
    }

    private static void medallion(ServerPlayer p, int beat) {
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        var center = p.getEyePosition().add(forward.scale(1.55));
        for (int dot = 0; dot < 24; dot++) {
            double angle = Math.PI * dot / 12;
            var radial = up.scale(Math.cos(angle)).add(right.scale(Math.sin(angle)));
            GalleryTrail.spark(p, dot % 6 == 0 ? ParticleTypes.WAX_ON : ParticleTypes.END_ROD, center.add(radial.scale(.33)));
            if (dot == beat * 6) {
                for (int i = 1; i <= 3; i++) GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(radial.scale(.33 + i * .055)));
            }
        }
        if (beat == 3) {
            GalleryTrail.spark(p, ParticleTypes.GLOW, center);
            for (int i = 0; i < 3; i++) {
                double a = i * 2 * Math.PI / 3;
                var ray = up.scale(Math.cos(a)).add(right.scale(Math.sin(a)));
                for (int j = 1; j <= 3; j++) GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(ray.scale(j * .055)));
            }
        }
    }
}

package dev.convergence;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

record CodaPostcard(String lead, StarLoom.Chart chime, String reply, StarLoom.Chart bell) {
    private static final String KEY = "infinity_coda_postcard_v1";

    String credits() {
        return "Chime: " + lead + " (" + chime.label + ") • Bell: " + reply + " (" + bell.label + ")";
    }

    ItemStack paper() {
        var card = CommonsEvents.named(Items.PAPER, 1, "Coda Postcard", credits(), "Hold and use to listen anywhere.");
        var chord = new CompoundTag();
        chord.putString("lead", lead);
        chord.putString("reply", reply);
        chord.putIntArray("pitches", new int[]{chime.note, bell.note});
        var data = new CompoundTag();
        data.put(KEY, chord);
        card.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return card;
    }

    static CodaPostcard read(ItemStack card) {
        if (!card.is(Items.PAPER)) return null;
        var data = card.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        var chord = data.copyTag().getCompound(KEY).orElse(null);
        if (chord == null || !(chord.get("pitches") instanceof IntArrayTag pitches) || pitches.size() != 2) return null;
        var notes = MirrorComposer.decode(pitches);
        String lead = chord.getStringOr("lead", ""), reply = chord.getStringOr("reply", "");
        if (notes == null || lead.isBlank() || reply.isBlank() || lead.length() > 64 || reply.length() > 64) return null;
        return new CodaPostcard(lead, notes.getFirst(), reply, notes.getLast());
    }

    void playIfHeld(ServerPlayer p) {
        if (!equals(read(p.getMainHandItem())) || !canListen(p)) return;
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        play(p, p.getEyePosition().add(forward.scale(1.6)), right, up, false);
    }

    void playFrame(ServerPlayer p, ItemFrame frame) {
        EncoreDuet.leave(p);
        var right = StarPrint.frameRight(p, frame);
        var up = frame.getDirection().getUnitVec3().cross(right);
        play(p, GalleryTrail.framePoint(frame), right.scale(.72), up.scale(.72), p.isShiftKeyDown());
    }

    static boolean canListen(ServerPlayer p) {
        if (!InfinityJourney.emptyScreen(p) || p.isSpectator()) return false;
        if (ConcertStage.current(p) == null) return true;
        ConcertStage.overlay(p, "Finish your banner performance to hear the postcard.");
        return false;
    }

    private void play(ServerPlayer p, Vec3 middle, Vec3 right, Vec3 up, boolean swap) {
        var left = swap ? bell : chime;
        var rightVoice = swap ? chime : bell;
        InfinityChimes.stopPlayback(p);
        InfinityChimes.sound(p, left.note, SoundEvents.NOTE_BLOCK_CHIME);
        InfinityChimes.sound(p, rightVoice.note, SoundEvents.NOTE_BLOCK_BELL);
        var leftNote = middle.add(right.scale(-.38)).add(up.scale((left.note - 10) * .05));
        var rightNote = middle.add(right.scale(.38)).add(up.scale((rightVoice.note - 10) * .05));
        var link = new DustParticleOptions(0x9BAEC5, .4f);
        for (int i = 1; i < 8; i++) GalleryTrail.spark(p, link, leftNote.lerp(rightNote, i / 8.0));
        for (int i = -2; i <= 2; i++) GalleryTrail.spark(p, ParticleTypes.END_ROD, leftNote.add(up.scale(i * .065)));
        for (int i = 0; i < 12; i++) {
            double angle = 2 * Math.PI * i / 12;
            GalleryTrail.spark(p, ParticleTypes.WAX_ON, rightNote.add(right.scale(.13 * Math.cos(angle))).add(up.scale(.13 * Math.sin(angle))));
        }
        GalleryTrail.spark(p, new DustParticleOptions(left.ink, .9f), leftNote);
        GalleryTrail.spark(p, new DustParticleOptions(rightVoice.ink, .9f), rightNote);
        ConcertStage.overlay(p, swap ? "Voice swap • Chime: " + left.label + " • Bell: " + rightVoice.label : credits());
    }
}

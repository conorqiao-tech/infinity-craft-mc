package dev.convergence;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

record Skyprint(StarLoom.Chart chart, int column, int voice, StarLoom.Chart pitch) {
    private static final String KEY = "infinity_skyprint_v1";

    String caption() {
        return "Chart " + chart.label + " • Column " + (column + 1) + " • Solo " + pitch.label + " " + CircuitConductor.VOICES.get(voice);
    }

    ItemStack paper() {
        var sheet = CommonsEvents.named(Items.PAPER, 1, "Skyprint • " + chart.label, caption(),
            "Use in the air to play. Sneak-use for a silent view.",
            "For Sky Dial, mount in a wall frame, turn by hand and read with a compass.");
        var score = new CompoundTag();
        score.putIntArray("pitches", new int[]{chart.note, pitch.note});
        score.putInt("column", column);
        score.putInt("voice", voice);
        var tag = new CompoundTag();
        tag.put(KEY, score);
        sheet.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return sheet;
    }

    static Skyprint read(ItemStack sheet) {
        if (!sheet.is(Items.PAPER)) return null;
        var data = sheet.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        var tag = data.copyTag().getCompound(KEY).orElse(null);
        if (tag == null || !(tag.get("pitches") instanceof IntArrayTag pitches) || pitches.size() != 2) return null;
        var notes = MirrorComposer.decode(pitches);
        int column = tag.getIntOr("column", -1);
        int voice = tag.getIntOr("voice", -1);
        if (notes == null || column < 0 || column > 2 || voice < 0 || voice >= CircuitConductor.VOICES.size()
                || !StarLens.densest(notes.getFirst(), column)) return null;
        return new Skyprint(notes.getFirst(), column, voice, notes.getLast());
    }

    void show(ServerPlayer player) {
        // A block tap can be followed by item use after the last blank became the print.
        if (player.pick(player.blockInteractionRange(), 1, false).getType() != HitResult.Type.MISS
                || !CodaPostcard.canListen(player)) return;
        var forward = player.getLookAngle().normalize();
        double yaw = Math.toRadians(player.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        StarLens.board(player, player.getEyePosition().add(forward.scale(1.6)), right.scale(.68), up.scale(.68), chart, column);
        if (!player.isShiftKeyDown()) {
            InfinityChimes.stopPlayback(player);
            EncoreDuet.leave(player);
            CircuitConductor.soundVoice(player, voice, pitch);
        }
        ConcertStage.overlay(player, "Skyprint • " + caption() + (player.isShiftKeyDown() ? " • Silent view" : ""));
    }
}

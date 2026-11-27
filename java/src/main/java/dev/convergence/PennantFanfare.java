package dev.convergence;

import java.lang.ref.WeakReference;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.phys.Vec3;

final class PennantFanfare {
    private record Call(WeakReference<BannerBlockEntity> banner, CadencePennant score) {}
    private static final AttachmentType<Call> PENDING =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "pennant_fanfare"));

    private PennantFanfare() {}

    static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> p.removeAttached(PENDING));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND || p.isSpectator()
                    || !p.getMainHandItem().is(Items.FEATHER) || !p.getMainHandItem().getComponentsPatch().isEmpty()
                    || !(world.getBlockEntity(hit.getBlockPos()) instanceof BannerBlockEntity banner)) return InteractionResult.PASS;
            var score = CadencePennant.read(banner);
            if (score == null) {
                var pending = p.getAttached(PENDING);
                if (pending != null && pending.banner.get() == banner) p.removeAttached(PENDING);
                return InteractionResult.PASS;
            }
            if (CodaPostcard.canListen(p)) play(p, banner, score);
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private static void play(ServerPlayer p, BannerBlockEntity banner, CadencePennant score) {
        var previous = p.getAttached(PENDING);
        boolean answer = !p.isShiftKeyDown() && previous != null && previous.banner.get() == banner && previous.score.equals(score);
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        int solo = score.solo();
        if (answer) {
            p.removeAttached(PENDING);
            int left = (solo + 1) % 3, right = (solo + 2) % 3;
            CircuitConductor.soundVoice(p, left, score.left());
            CircuitConductor.soundVoice(p, right, score.right());
            ConcertStage.overlay(p, "Pennant answer • " + name(score.left(), left) + " + " + name(score.right(), right));
        } else {
            p.setAttached(PENDING, new Call(new WeakReference<>(banner), score));
            CircuitConductor.soundVoice(p, solo, score.call());
            ConcertStage.overlay(p, "Pennant call • " + name(score.call(), solo) + " • Tap again for the answer");
        }
        display(p, banner.getBlockPos(), answer);
    }

    private static String name(StarLoom.Chart chart, int role) {
        return chart.label + " " + CircuitConductor.VOICES.get(role);
    }

    private static void display(ServerPlayer p, BlockPos pos, boolean answer) {
        var anchor = Vec3.atCenterOf(pos).add(0, .35, 0);
        var facing = p.getEyePosition().subtract(anchor).normalize();
        var center = anchor.add(facing.scale(.55));
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        for (int i = 0; i < 9; i++) {
            double t = i / 8.0;
            if (answer) {
                var row = center.add(0, -.23 * t, 0);
                GalleryTrail.spark(p, ParticleTypes.END_ROD, row.add(right.scale(.07 + .28 * t)));
                GalleryTrail.spark(p, ParticleTypes.END_ROD, row.subtract(right.scale(.07 + .28 * t)));
            } else {
                GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(0, .34 * t, 0));
            }
        }
    }
}

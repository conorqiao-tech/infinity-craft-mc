package dev.convergence;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

record PrismCrossing(InlayPrism prism, boolean reverse, boolean second) {
    private static final AttachmentType<PrismCrossing> ROUND =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "prism_crossing"));

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || p.isSpectator() || hand != InteractionHand.MAIN_HAND
                || !world.getBlockState(hit.getBlockPos()).is(Blocks.AMETHYST_BLOCK)) return InteractionResult.PASS;
            var prism = InlayPrism.read(p.getMainHandItem());
            if (prism == null) return InteractionResult.PASS;
            if (CodaPostcard.canListen(p)) {
                var round = new PrismCrossing(prism, p.isShiftKeyDown(), false);
                p.setAttached(ROUND, round);
                ConcertStage.overlay(p, "Prism Crossing • " + round.target(round.reverse) + " then "
                    + round.target(!round.reverse) + " • Aim into the air and play their facets.");
            }
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    static void clear(ServerPlayer p) { p.removeAttached(ROUND); }

    private String target(boolean right) {
        var score = prism.score();
        return score.voiceName(score.cornerAt(right ? 2 : 0));
    }

    static boolean answer(ServerPlayer p, InlayPrism held, boolean right) {
        var round = p.getAttached(ROUND);
        if (round == null) return false;
        if (!round.prism.equals(held)) {
            p.removeAttached(ROUND);
            return false;
        }
        // A vanilla client can follow its block-use packet with item use for the same click.
        if (p.pick(p.blockInteractionRange(), 1, false) instanceof BlockHitResult hit
            && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
            && p.level().getBlockState(hit.getBlockPos()).is(Blocks.AMETHYST_BLOCK)) return true;
        boolean expected = round.reverse != round.second;
        if (expected != right) {
            ConcertStage.overlay(p, "Prism Crossing • Try again • " + (round.second ? "2" : "1")
                + "/2 • Find " + round.target(expected));
            return true;
        }
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        int facetBeat = right ? 1 : 0;
        held.score().soundBeat(p, round.second ? 3 : facetBeat);
        round.draw(p);
        if (round.second) {
            p.removeAttached(ROUND);
            ConcertStage.overlay(p, "Prism Crossing • 2/2 • Star complete • Full chord");
        } else {
            p.setAttached(ROUND, new PrismCrossing(held, round.reverse, true));
            ConcertStage.overlay(p, "Prism Crossing • 1/2 • Next: " + round.target(!right));
        }
        return true;
    }

    private void draw(ServerPlayer p) {
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        var center = p.getEyePosition().add(forward.scale(1.5));
        for (int step = -4; step <= 4; step++) {
            var column = center.add(right.scale(step * .10));
            double height = (reverse ? -1 : 1) * step * .065;
            GalleryTrail.spark(p, ParticleTypes.END_ROD, column.add(up.scale(height)));
            if (second && step != 0) GalleryTrail.spark(p, ParticleTypes.END_ROD, column.subtract(up.scale(height)));
        }
        if (second) {
            for (int ray = 0; ray < 4; ray++) {
                double angle = Math.PI * ray / 2;
                GalleryTrail.spark(p, ParticleTypes.WAX_OFF, center.add(right.scale(.12 * Math.cos(angle)))
                    .add(up.scale(.12 * Math.sin(angle))));
            }
            GalleryTrail.spark(p, ParticleTypes.GLOW, center);
        }
    }
}

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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

record PrismCrossing(InlayPrism prism, boolean reverse, boolean second) {
    private static final AttachmentType<PrismCrossing> ROUND =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "prism_crossing"));

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || p.isSpectator() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            var station = world.getBlockState(hit.getBlockPos());
            if (!station.is(Blocks.AMETHYST_BLOCK) && !station.is(Blocks.LANTERN) && !station.is(Blocks.GLASS))
                return InteractionResult.PASS;
            var prism = InlayPrism.read(p.getMainHandItem());
            if (prism == null) return InteractionResult.PASS;
            if (station.is(Blocks.GLASS)) {
                var walk = LanternWalk.surveyed(p, prism);
                if (walk == null) return InteractionResult.PASS;
                if (CodaPostcard.canListen(p)) walk.lightBeacon(p, hit.getBlockPos());
                return InteractionResult.SUCCESS_SERVER;
            }
            if (!CodaPostcard.canListen(p)) return InteractionResult.SUCCESS_SERVER;
            if (station.is(Blocks.LANTERN)) {
                LanternWalk.visit(p, prism, hit.getBlockPos());
            } else {
                LanternWalk.clear(p);
                var round = new PrismCrossing(prism, p.isShiftKeyDown(), false);
                p.setAttached(ROUND, round);
                ConcertStage.overlay(p, "Prism Crossing • " + round.target(round.reverse) + " then "
                    + round.target(!round.reverse) + " • Aim into the air and play their facets.");
            }
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    static void clear(ServerPlayer p) {
        p.removeAttached(ROUND);
        LanternWalk.clear(p);
    }

    private String target(boolean right) {
        var score = prism.score();
        return score.voiceName(score.cornerAt(right ? 2 : 0));
    }

    static boolean answer(ServerPlayer p, InlayPrism held, boolean right) {
        var round = p.getAttached(ROUND);
        // Vanilla can follow a block use with item use for the same physical click.
        if (p.pick(p.blockInteractionRange(), 1, false) instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK) {
            var station = p.level().getBlockState(hit.getBlockPos());
            if (station.is(Blocks.LANTERN) || (round != null && station.is(Blocks.AMETHYST_BLOCK))
                || (station.is(Blocks.GLASS) && LanternWalk.surveyed(p, held) != null)) return true;
        }
        if (round == null) return false;
        if (!round.prism.equals(held)) {
            p.removeAttached(ROUND);
            return false;
        }
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
            LanternWalk.open(p, held, round.reverse);
            ConcertStage.overlay(p, "Prism Crossing • 2/2 • Star complete • Full chord • Visit three lanterns");
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

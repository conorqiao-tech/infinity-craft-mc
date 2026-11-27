package dev.convergence;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.phys.Vec3;

final class PennantFanfare {
    private record Call(WeakReference<BannerBlockEntity> banner, CadencePennant score, boolean answered) {}
    private static final AttachmentType<Call> PENDING =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "pennant_fanfare"));

    private static final AttachmentType<Map<Integer, StarLoom.Chart>> CHORUS =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "pennant_chorus"));

    private PennantFanfare() {}

    static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> p.removeAttached(PENDING));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND || p.isSpectator()
                    || !p.getMainHandItem().getComponentsPatch().isEmpty()) return InteractionResult.PASS;
            var held = p.getMainHandItem();
            boolean paper = held.is(Items.PAPER);
            if ((!paper && !held.is(Items.FEATHER))
                    || !(world.getBlockEntity(hit.getBlockPos()) instanceof BannerBlockEntity banner)) return InteractionResult.PASS;
            var score = CadencePennant.read(banner);
            var previous = p.getAttached(PENDING);
            if (previous != null && previous.banner.get() == banner && !previous.score.equals(score)) {
                p.removeAttached(PENDING);
                previous = null;
            }
            if (score == null) return InteractionResult.PASS;
            if (CodaPostcard.canListen(p)) {
                boolean sameFlag = previous != null && previous.banner.get() == banner;
                if (paper) {
                    if (sameFlag && previous.answered) {
                        p.removeAttached(PENDING);
                        collect(p, banner.getBlockPos(), score);
                    } else {
                        ConcertStage.overlay(p, "Play this flag’s call and answer with a feather first.");
                    }
                } else {
                    play(p, banner, score, sameFlag && !previous.answered && !p.isShiftKeyDown());
                }
            }
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    private static void play(ServerPlayer p, BannerBlockEntity banner, CadencePennant score, boolean answer) {
        p.setAttached(PENDING, new Call(new WeakReference<>(banner), score, answer));
        InfinityChimes.stopPlayback(p);
        EncoreDuet.leave(p);
        int solo = score.solo();
        if (answer) {
            int left = (solo + 1) % 3, right = (solo + 2) % 3;
            CircuitConductor.soundVoice(p, left, score.left());
            CircuitConductor.soundVoice(p, right, score.right());
            ConcertStage.overlay(p, "Pennant answer • " + name(score.left(), left) + " + " + name(score.right(), right) + " • Paper collects the solo");
        } else {
            CircuitConductor.soundVoice(p, solo, score.call());
            ConcertStage.overlay(p, "Pennant call • " + name(score.call(), solo) + " • Tap again for the answer");
        }
        display(p, banner.getBlockPos(), answer);
    }

    private static String name(StarLoom.Chart chart, int role) {
        return chart.label + " " + CircuitConductor.VOICES.get(role);
    }

    private static void collect(ServerPlayer p, BlockPos pos, CadencePennant flag) {
        var voices = new HashMap<>(p.getAttachedOrElse(CHORUS, Map.of()));
        voices.put(flag.solo(), flag.call());
        var caption = new StringJoiner(" + ");
        boolean complete = voices.size() == 3;
        if (complete) {
            p.removeAttached(CHORUS);
            InfinityChimes.stopPlayback(p);
            EncoreDuet.leave(p);
        } else {
            p.setAttached(CHORUS, Map.copyOf(voices));
        }
        var center = faceCenter(p, pos);
        var right = viewerRight(p);
        var corners = List.of(center.add(0, .32, 0), center.add(right.scale(-.33)).add(0, -.18, 0),
            center.add(right.scale(.33)).add(0, -.18, 0));
        for (int role = 0; role < 3; role++) {
            var chart = voices.get(role);
            var point = corners.get(role);
            if (chart == null) {
                GalleryTrail.spark(p, ParticleTypes.WAX_ON, point);
                caption.add(CircuitConductor.VOICES.get(role) + " —");
                continue;
            }
            caption.add(name(chart, role));
            GalleryTrail.spark(p, new DustParticleOptions(chart.ink, 1f), point);
            GalleryTrail.spark(p, ParticleTypes.GLOW, point);
            if (complete) {
                CircuitConductor.soundVoice(p, role, chart);
                var next = corners.get((role + 1) % 3);
                for (int dot = 1; dot <= 5; dot++) {
                    GalleryTrail.spark(p, ParticleTypes.END_ROD, point.lerp(next, dot / 6.0));
                }
            }
        }
        ConcertStage.overlay(p, "Pennant Chorus " + voices.size() + "/3 • " + caption);
    }

    private static Vec3 faceCenter(ServerPlayer p, BlockPos pos) {
        var anchor = Vec3.atCenterOf(pos).add(0, .35, 0);
        return anchor.add(p.getEyePosition().subtract(anchor).normalize().scale(.55));
    }

    private static Vec3 viewerRight(ServerPlayer p) {
        double yaw = Math.toRadians(p.getYRot());
        return new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
    }

    private static void display(ServerPlayer p, BlockPos pos, boolean answer) {
        var center = faceCenter(p, pos);
        var right = viewerRight(p);
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

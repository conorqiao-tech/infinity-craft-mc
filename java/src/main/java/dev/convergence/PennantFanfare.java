package dev.convergence;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    private static final AttachmentType<Baton> BATON =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "chorus_baton"));

    private enum Inlay { IDLE, OPEN, SET }

    private static final class Baton {
        final WeakReference<BannerBlockEntity> banner;
        final CadencePennant design;
        final Map<Integer, StarLoom.Chart> voices;
        final List<Integer> order;
        int beat;
        boolean backward;
        boolean leftLit;
        boolean rightLit;
        Inlay inlay = Inlay.IDLE;

        Baton(BannerBlockEntity banner, CadencePennant design, Map<Integer, StarLoom.Chart> voices) {
            this.banner = new WeakReference<>(banner);
            this.design = design;
            this.voices = Map.copyOf(voices);
            this.order = List.copyOf(voices.keySet());
        }

        void conduct(ServerPlayer p, BlockPos pos) {
            if (beat == 0) backward = p.isShiftKeyDown();
            InfinityChimes.stopPlayback(p);
            EncoreDuet.leave(p);
            String label;
            if (beat == 3) {
                label = soundAndDrawChorus(p, pos, voices);
                if (backward) rightLit = true;
                else leftLit = true;
                drawLoop(p, pos, false);
                label += " • " + loopHint();
            } else {
                int role = order.get(backward ? 2 - beat : beat);
                var chart = voices.get(role);
                CircuitConductor.soundVoice(p, role, chart);
                var center = faceCenter(p, pos);
                var point = chorusCorners(p, pos).get(role);
                for (int i = 0; i < 6; i++) GalleryTrail.spark(p, ParticleTypes.END_ROD, center.lerp(point, i / 6.0));
                GalleryTrail.spark(p, new DustParticleOptions(chart.ink, 1f), point);
                GalleryTrail.spark(p, ParticleTypes.GLOW, point);
                label = name(chart, role);
            }
            ConcertStage.overlay(p, "Chorus Baton • " + (backward ? "Backward " : "Forward ") + (beat + 1) + "/4 • " + label);
            beat = (beat + 1) % 4;
        }

        private String loopHint() {
            if (leftLit && rightLit) return "∞ Both lit • Ring with amethyst";
            if (leftLit) return "Left lit • Finish backward";
            if (rightLit) return "Right lit • Finish forward";
            return "Finish a forward and a backward phrase";
        }

        void ring(ServerPlayer p, BlockPos pos) {
            if (!leftLit || !rightLit) {
                ConcertStage.overlay(p, "Infinity Loop • " + loopHint());
                return;
            }
            int first = order.getFirst(), last = order.getLast();
            InfinityChimes.stopPlayback(p);
            EncoreDuet.leave(p);
            CircuitConductor.soundVoice(p, first, voices.get(first));
            CircuitConductor.soundVoice(p, last, voices.get(last));
            inlay = Inlay.OPEN;
            drawLoop(p, pos, true);
            ConcertStage.overlay(p, "Infinity Loop • " + name(voices.get(first), first) + " + " + name(voices.get(last), last)
                + " • Find the middle solo with glowstone dust");
        }

        void answer(ServerPlayer p, BlockPos pos, CadencePennant guess) {
            var source = banner.get();
            if (source == null || source.isRemoved() || source.getLevel() != p.level()
                    || !p.level().isLoaded(source.getBlockPos()) || p.level().getBlockEntity(source.getBlockPos()) != source
                    || !design.equals(CadencePennant.read(source))) {
                p.removeAttached(BATON);
                ConcertStage.overlay(p, "Loop Inlay • The finishing flag is gone or changed");
                return;
            }
            int middle = order.get(1);
            if (guess.solo() != middle) {
                ConcertStage.overlay(p, "Loop Inlay • " + name(guess.call(), guess.solo()) + " • Find the instrument that joined second");
            } else if (guess.call() != voices.get(middle)) {
                ConcertStage.overlay(p, "Loop Inlay • " + name(guess.call(), middle) + " • Right instrument, try its collected pitch");
            } else {
                inlay = Inlay.SET;
                InfinityChimes.stopPlayback(p);
                EncoreDuet.leave(p);
                for (int role : order) CircuitConductor.soundVoice(p, role, voices.get(role));
                ConcertStage.overlay(p, "Loop Inlay • " + name(guess.call(), middle) + " • Diamond set");
            }
            drawLoop(p, pos, false);
        }

        private void drawLoop(ServerPlayer p, BlockPos pos, boolean ringing) {
            var center = faceCenter(p, pos).add(0, .60, 0);
            var right = viewerRight(p);
            drawLobe(p, center, right.scale(-1), voices.get(order.getFirst()), leftLit);
            drawLobe(p, center, right, voices.get(order.getLast()), rightLit);
            if (leftLit && rightLit) GalleryTrail.spark(p, ParticleTypes.ELECTRIC_SPARK, center);
            if (inlay != Inlay.IDLE) drawInlay(p, center, right);
            if (ringing) {
                GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(right.scale(.52)));
                GalleryTrail.spark(p, ParticleTypes.END_ROD, center.subtract(right.scale(.52)));
            }
        }

        private void drawInlay(ServerPlayer p, Vec3 center, Vec3 right) {
            var corners = List.of(center.add(0, .17, 0), center.add(right.scale(.13)),
                center.add(0, -.17, 0), center.subtract(right.scale(.13)));
            var ink = new DustParticleOptions(voices.get(order.get(1)).ink, .65f);
            for (int i = 0; i < corners.size(); i++) {
                var corner = corners.get(i);
                if (inlay == Inlay.OPEN) {
                    GalleryTrail.spark(p, ParticleTypes.WAX_ON, corner);
                } else {
                    GalleryTrail.spark(p, ParticleTypes.WAX_OFF, corner);
                    var next = corners.get((i + 1) % corners.size());
                    for (int dot = 0; dot < 4; dot++) GalleryTrail.spark(p, ink, corner.lerp(next, dot / 4.0));
                }
            }
        }

        private static void drawLobe(ServerPlayer p, Vec3 center, Vec3 outward, StarLoom.Chart chart, boolean lit) {
            var dust = new DustParticleOptions(chart.ink, .55f);
            for (int i = lit ? 0 : 4; i < 16; i += lit ? 1 : 4) {
                double t = Math.PI * i / 16;
                var point = center.add(outward.scale(.52 * Math.sin(t))).add(0, .19 * Math.sin(2 * t), 0);
                GalleryTrail.spark(p, lit ? dust : ParticleTypes.WAX_ON, point);
            }
        }
    }

    private PennantFanfare() {}

    static void register() {
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p, from, to) -> {
            p.removeAttached(PENDING);
            p.removeAttached(BATON);
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND || p.isSpectator()
                    || !p.getMainHandItem().getComponentsPatch().isEmpty()) return InteractionResult.PASS;
            var held = p.getMainHandItem();
            boolean paper = held.is(Items.PAPER);
            boolean stick = held.is(Items.STICK);
            boolean shard = held.is(Items.AMETHYST_SHARD);
            boolean dust = held.is(Items.GLOWSTONE_DUST);
            if ((!paper && !stick && !shard && !dust && !held.is(Items.FEATHER))
                    || !(world.getBlockEntity(hit.getBlockPos()) instanceof BannerBlockEntity banner)) return InteractionResult.PASS;
            var score = CadencePennant.read(banner);
            var previous = p.getAttached(PENDING);
            if (previous != null && previous.banner.get() == banner && !previous.score.equals(score)) {
                p.removeAttached(PENDING);
                previous = null;
            }
            var baton = p.getAttached(BATON);
            boolean finishingFlag = baton != null && baton.banner.get() == banner;
            if (finishingFlag && !baton.design.equals(score)) {
                p.removeAttached(BATON);
                baton = null;
                finishingFlag = false;
            }
            if (stick || shard) {
                if (!finishingFlag) return InteractionResult.PASS;
                if (CodaPostcard.canListen(p)) {
                    if (shard) baton.ring(p, banner.getBlockPos());
                    else baton.conduct(p, banner.getBlockPos());
                }
                return InteractionResult.SUCCESS_SERVER;
            }
            if (score == null) return InteractionResult.PASS;
            if (dust) {
                if (baton == null || baton.inlay != Inlay.OPEN) return InteractionResult.PASS;
                if (CodaPostcard.canListen(p)) baton.answer(p, banner.getBlockPos(), score);
                return InteractionResult.SUCCESS_SERVER;
            }
            if (CodaPostcard.canListen(p)) {
                boolean sameFlag = previous != null && previous.banner.get() == banner;
                if (paper) {
                    if (sameFlag && previous.answered) {
                        p.removeAttached(PENDING);
                        collect(p, banner, score);
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

    private static void collect(ServerPlayer p, BannerBlockEntity banner, CadencePennant flag) {
        var voices = new LinkedHashMap<>(p.getAttachedOrElse(CHORUS, Map.of()));
        voices.put(flag.solo(), flag.call());
        boolean complete = voices.size() == 3;
        if (complete) {
            p.removeAttached(CHORUS);
            p.setAttached(BATON, new Baton(banner, flag, voices));
            InfinityChimes.stopPlayback(p);
            EncoreDuet.leave(p);
        } else {
            p.removeAttached(BATON);
            p.setAttached(CHORUS, Collections.unmodifiableMap(voices));
        }
        String caption = soundAndDrawChorus(p, banner.getBlockPos(), voices);
        ConcertStage.overlay(p, "Pennant Chorus " + voices.size() + "/3 • " + caption + (complete ? " • Conduct with a stick" : ""));
    }

    private static List<Vec3> chorusCorners(ServerPlayer p, BlockPos pos) {
        var center = faceCenter(p, pos);
        var right = viewerRight(p);
        return List.of(center.add(0, .32, 0), center.add(right.scale(.33)).add(0, -.18, 0),
            center.add(right.scale(-.33)).add(0, -.18, 0));
    }

    private static String soundAndDrawChorus(ServerPlayer p, BlockPos pos, Map<Integer, StarLoom.Chart> voices) {
        var corners = chorusCorners(p, pos);
        var caption = new StringJoiner(" + ");
        boolean complete = voices.size() == 3;
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
        return caption.toString();
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

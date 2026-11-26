package dev.convergence;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;

record CircuitCounterpoint(CircuitConductor phrase, int beat) {
    private static final AttachmentType<CircuitCounterpoint> LAST =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "circuit_counterpoint"));

    static void remember(ServerPlayer p, CircuitConductor phrase) {
        p.setAttached(LAST, new CircuitCounterpoint(phrase, 0));
    }

    static void forget(ServerPlayer p) {
        p.removeAttached(LAST);
    }

    static void tap(ServerPlayer p, ItemFrame frame) {
        var part = p.getAttached(LAST);
        if (part == null) {
            ConcertStage.overlay(p, "Finish a four-beat Conductor phrase with a blaze rod first.");
            return;
        }
        var phrase = part.phrase;
        if (phrase.corners().stream().anyMatch(corner -> !corner.stillDisplayed(p))) {
            CircuitConductor.forget(p);
            ConcertStage.overlay(p, "Display changed • Close a fresh circuit and conduct it again.");
            return;
        }
        int lead = phrase.cornerAt(part.beat);
        int answer = phrase.cornerAt(part.beat + 1);
        if (!phrase.corners().get(answer).frameId().equals(frame.getUUID())) {
            ConcertStage.overlay(p, "Counterpoint " + part.beat + "/4 • Answer " + part.cornerName(answer));
            return;
        }
        EncoreDuet.leave(p);
        InfinityChimes.stopPlayback(p);
        if (part.beat == 3) {
            phrase.chord(p);
            for (var corner : phrase.corners()) {
                mark(p, (ItemFrame)p.level().getEntity(corner.frameId()), Mark.STAR);
            }
            remember(p, phrase);
            ConcertStage.overlay(p, "Counterpoint 4/4 • All three voices • Replay from " + part.cornerName(phrase.cornerAt(1)));
            return;
        }
        phrase.sound(p, lead);
        phrase.sound(p, answer);
        mark(p, (ItemFrame)p.level().getEntity(phrase.corners().get(lead).frameId()), Mark.BAR);
        mark(p, frame, Mark.RING);
        p.setAttached(LAST, new CircuitCounterpoint(phrase, part.beat + 1));
        ConcertStage.overlay(p, "Counterpoint " + (part.beat + 1) + "/4 • " + part.cornerName(lead)
            + " + " + part.cornerName(answer) + " • Next " + part.cornerName(phrase.cornerAt(part.beat + 2)));
    }

    private String cornerName(int index) {
        return "corner " + (index + 1) + " (" + phrase.corners().get(index).chime().label + ")";
    }

    private enum Mark { BAR, RING, STAR }

    private static void mark(ServerPlayer p, ItemFrame frame, Mark shape) {
        var center = GalleryTrail.framePoint(frame);
        var right = StarPrint.frameRight(p, frame);
        var up = frame.getDirection().getUnitVec3().cross(right);
        switch (shape) {
            case BAR -> {
                for (int n = -2; n <= 2; n++) GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(right.scale(n * .12)));
            }
            case RING -> {
                for (int n = 0; n < 16; n++) {
                    double a = n * Math.PI / 8;
                    GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(right.scale(.32 * Math.cos(a))).add(up.scale(.32 * Math.sin(a))));
                }
            }
            case STAR -> {
                GalleryTrail.spark(p, ParticleTypes.GLOW, center);
                for (int n = 0; n < 3; n++) {
                    double a = n * 2 * Math.PI / 3;
                    var ray = up.scale(Math.cos(a)).add(right.scale(Math.sin(a)));
                    for (int j = 1; j <= 3; j++) GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(ray.scale(j * .11)));
                }
            }
        }
    }
}

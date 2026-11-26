package dev.convergence;

import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.decoration.ItemFrame;

record CircuitConductor(List<PostcardPairs.Selection> corners, int opening, int direction, int played) {
    private static final AttachmentType<CircuitConductor> LAST =
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("convergence", "circuit_conductor"));
    private static final List<String> VOICES = List.of("chime", "harp", "bell");

    static void close(ServerPlayer p, PostcardPairs.Selection a, PostcardPairs.Selection b, PostcardPairs.Selection c) {
        var circuit = new CircuitConductor(List.of(a, b, c), 0, 1, 0);
        p.setAttached(LAST, circuit);
        CircuitCounterpoint.forget(p);
        circuit.chord(p);
    }

    static void forget(ServerPlayer p) {
        p.removeAttached(LAST);
        CircuitCounterpoint.forget(p);
    }

    int cornerAt(int beat) {
        return Math.floorMod(opening + direction * beat, 3);
    }

    void sound(ServerPlayer p, int corner) {
        var note = corners.get(corner).chime().note;
        var instrument = switch (corner) {
            case 0 -> SoundEvents.NOTE_BLOCK_CHIME;
            case 1 -> SoundEvents.NOTE_BLOCK_HARP;
            default -> SoundEvents.NOTE_BLOCK_BELL;
        };
        InfinityChimes.sound(p, note, instrument);
    }

    void chord(ServerPlayer p) {
        for (int i = 0; i < 3; i++) sound(p, i);
    }

    static void tap(ServerPlayer p, ItemFrame frame) {
        var circuit = p.getAttached(LAST);
        if (circuit == null) {
            ConcertStage.overlay(p, "Close a postcard circuit with prismarine crystals first.");
            return;
        }
        if (circuit.corners.stream().anyMatch(c -> !c.stillDisplayed(p))) {
            forget(p);
            ConcertStage.overlay(p, "Display changed • Close a new circuit before conducting.");
            return;
        }
        int index = -1;
        for (int i = 0; i < 3; i++) {
            if (circuit.corners.get(i).frameId().equals(frame.getUUID())) index = i;
        }
        if (circuit.played == 0) {
            if (index < 0) {
                ConcertStage.overlay(p, "Choose an opening frame from your last closed circuit.");
                return;
            }
            circuit = new CircuitConductor(circuit.corners, index, p.isShiftKeyDown() ? -1 : 1, 0);
        }
        if (index != circuit.cornerAt(circuit.played)) {
            circuit.prompt(p);
            return;
        }

        EncoreDuet.leave(p);
        InfinityChimes.stopPlayback(p);
        if (circuit.played == 3) {
            CircuitCounterpoint.remember(p, circuit);
            circuit.chord(p);
            circuit.ribbon(p, frame, 4);
            GalleryTrail.spark(p, ParticleTypes.GLOW, GalleryTrail.framePoint(frame));
            ConcertStage.overlay(p, "Conductor 4/4 • Home on corner " + (index + 1) + " • Copper ingot: answer corner " + (circuit.cornerAt(1) + 1));
            p.setAttached(LAST, new CircuitConductor(circuit.corners, 0, 1, 0));
        } else {
            circuit.sound(p, index);
            var next = new CircuitConductor(circuit.corners, circuit.opening, circuit.direction, circuit.played + 1);
            p.setAttached(LAST, next);
            next.ribbon(p, frame, next.played);
            next.prompt(p);
        }
    }

    private void prompt(ServerPlayer p) {
        int index = cornerAt(played);
        var next = corners.get(index);
        var target = (ItemFrame)p.level().getEntity(next.frameId());
        var center = next.point();
        var right = StarPrint.frameRight(p, target);
        var up = target.getDirection().getUnitVec3().cross(right);
        boolean home = played == 3;
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * i / 8;
            double x = Math.cos(angle), y = Math.sin(angle);
            double radius = home ? .35 : .35 / (Math.abs(x) + Math.abs(y));
            GalleryTrail.spark(p, ParticleTypes.END_ROD, center.add(right.scale(radius * x)).add(up.scale(radius * y)));
        }
        if (home) GalleryTrail.spark(p, ParticleTypes.GLOW, center);
        ConcertStage.overlay(p, "Conductor " + played + "/4 • " + (home ? "Return to" : "Next") + " corner " + (index + 1)
            + " • " + next.chime().label + " " + VOICES.get(index));
    }

    private void ribbon(ServerPlayer p, ItemFrame frame, int filled) {
        var right = StarPrint.frameRight(p, frame);
        var up = frame.getDirection().getUnitVec3().cross(right);
        var start = GalleryTrail.framePoint(frame).add(up.scale(-.34));
        for (int i = 0; i < 4; i++) {
            var mark = start.add(right.scale((i - 1.5) * .18));
            if (i < filled) {
                var ink = new DustParticleOptions(corners.get(cornerAt(i)).chime().ink, .8f);
                GalleryTrail.spark(p, ink, mark);
                GalleryTrail.spark(p, ink, mark.add(up.scale(.1)));
            } else {
                GalleryTrail.spark(p, new DustParticleOptions(0x9BAEC5, .42f), mark);
            }
        }
    }
}

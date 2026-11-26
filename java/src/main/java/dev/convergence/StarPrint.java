package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

record StarPrint(String composer, List<StarLoom.Chart> notes) {
    private static final String KEY = "infinity_star_print_v1";

    StarPrint { notes = List.copyOf(notes); }

    static StarPrint read(ItemStack paper) {
        if (!paper.is(Items.PAPER)) return null;
        var data = paper.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        var tag = data.copyTag().getCompound(KEY).orElse(null);
        if (tag == null || !(tag.get("notes") instanceof IntArrayTag pitches)) return null;
        String by = tag.getStringOr("composer", "");
        var notes = MirrorComposer.decode(pitches);
        if (by.isBlank() || by.length() > 64 || notes == null || notes.size() < 2) return null;
        return new StarPrint(by, notes);
    }

    private ItemStack paper() {
        var paper = CommonsEvents.named(Items.PAPER, 1, "Star Print • " + composer,
            "Written by " + composer, MirrorComposer.names(notes), "Use to open the printed score.");
        var score = new CompoundTag();
        score.putString("composer", composer);
        score.putIntArray("notes", notes.stream().mapToInt(chart -> chart.note).toArray());
        var tag = new CompoundTag();
        tag.put(KEY, score);
        paper.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return paper;
    }

    static void register() {
        GalleryTrail.register();
        PostcardPairs.register();
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(entity instanceof ItemFrame frame) || !(player instanceof ServerPlayer p)
                || hand != InteractionHand.MAIN_HAND || p.isSpectator()) return InteractionResult.PASS;
            var held = p.getMainHandItem();
            if ((held.is(Items.AMETHYST_SHARD) || held.is(Items.ECHO_SHARD) || held.is(Items.PRISMARINE_CRYSTALS)) && held.getComponentsPatch().isEmpty()) {
                var postcard = CodaPostcard.read(frame.getItem());
                if (postcard != null) {
                    if (CodaPostcard.canListen(p)) {
                        if (held.is(Items.ECHO_SHARD)) PostcardPairs.tap(p, frame, postcard);
                        else if (held.is(Items.PRISMARINE_CRYSTALS)) PostcardPairs.closeCircuit(p, frame, postcard);
                        else postcard.playFrame(p, frame);
                    }
                    return InteractionResult.SUCCESS_SERVER;
                }
            }
            if ((held.is(Items.GLOW_INK_SAC) || held.is(Items.PAPER)) && held.getComponentsPatch().isEmpty() && EncoreDuet.useCodaItem(p, frame))
                return InteractionResult.SUCCESS_SERVER;
            boolean compass = held.is(Items.COMPASS) && held.getComponentsPatch().isEmpty();
            boolean feather = held.is(Items.FEATHER) && held.getComponentsPatch().isEmpty() && GalleryTrail.encoreReady(p);
            boolean stick = EncoreDuet.hasBaton(p) && GalleryTrail.encoreReady(p);
            if (!stick && !feather && !compass && !held.is(Items.AMETHYST_SHARD)) return InteractionResult.PASS;
            var print = read(frame.getItem());
            if (print == null && !(stick && GalleryTrail.route(p).stream().anyMatch(s -> s.frameId().equals(frame.getUUID()))))
                return InteractionResult.PASS;
            if (InfinityJourney.emptyScreen(p)) {
                if (ConcertStage.current(p) != null) ConcertStage.overlay(p, "Finish your banner performance to play this print.");
                else if (stick) EncoreDuet.tap(p, frame);
                else {
                    EncoreDuet.leave(p);
                    if (feather) GalleryTrail.conduct(p, frame);
                    else if (compass) GalleryTrail.visit(p, frame, print);
                    else print.playFrame(p, frame);
                }
            }
            return InteractionResult.SUCCESS_SERVER;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer p) || hand != InteractionHand.MAIN_HAND)
                return InteractionResult.PASS;
            var postcard = CodaPostcard.read(p.getMainHandItem());
            if (postcard != null) {
                postcard.playIfHeld(p);
                return InteractionResult.SUCCESS_SERVER;
            }
            if (read(p.getMainHandItem()) == null) return InteractionResult.PASS;
            if (InfinityJourney.emptyScreen(p)) InfinityJourney.open(p, InfinityJourney.Page.STAR_PRINTS);
            return InteractionResult.SUCCESS_SERVER;
        });
    }

    static InfinityJourney.View view(ServerPlayer p) {
        var choices = new ArrayList<InfinityJourney.Choice>();
        var held = p.getMainHandItem();
        var print = read(held);
        var postcard = CodaPostcard.read(held);
        String body;
        if (postcard != null) {
            body = "Coda Postcard\n" + postcard.credits() + "\nHold and use it to hear the chord anywhere."
                + "\nFrame it and tap with a plain amethyst shard. Sneak and tap to swap which instrument plays each note."
                + "\nPostcard Pairs: use a plain echo shard on two framed postcards within 12 blocks. Match the first bell to the next chime. Sneak to swap either tap."
                + "\nCircuit: after linking a pair, tap a third signed frame with plain prismarine crystals. Match the second bell back to the first chime.";
            if (ConcertStage.current(p) == null)
                choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Play held postcard", () -> postcard.playIfHeld(p)));
            else body += "\nFinish or stop your current performance first.";
        } else if (print != null) {
            body = "Held print • " + print.composer + "\n" + MirrorComposer.names(print.notes)
                + "\nUnfold to hear the written notes. Perform to play this paper out and back on linked banners. Your own melody stays saved."
                + "\nUse an amethyst shard on a framed print to play it. Crouch first for reverse. On touch, turn on Sneak before tapping."
                + "\nGallery Trail: use a plain compass on three different frames. Match the last note to the next opening note. Crouch and tap to start fresh."
                + "\nEncore: after the third frame, use a plain feather on that frame for the whole medley. Turn on Sneak first to play backward."
                + "\nDuet: both solve the same trail, then tap its last frame with plain sticks. Take turns tapping the marked frame."
                + "\nCoda: after the duet, each tap any trail frame with a plain glow ink sac. Tap for its last note, or Sneak and tap for its first."
                + "\nTap the last frame with plain paper to stamp your latest chord onto a Coda Postcard.";
            if (ConcertStage.current(p) == null) {
                choices.add(new InfinityJourney.Choice(Items.PAPER, "Unfold held print", () -> {
                    if (print.stillHeld(p)) print.unfold(p);
                }));
                choices.add(new InfinityJourney.Choice(Items.NOTE_BLOCK, "Perform held print", () -> {
                    if (print.stillHeld(p)) MirrorComposer.perform(p, print.notes, "Star Print by " + print.composer);
                }));
            } else body += "\nFinish or stop your current performance first.";
        } else {
            var notes = MirrorComposer.score(p);
            body = "Print a copy of your melody on one plain sheet of paper. Give it to another player to hear or perform.";
            if (notes == null) body += "\nYour saved motif needs host review.";
            else if (notes.size() < 2) body += "\nWrite at least two notes in Mirror Composer, or hold a Star Print to read it.";
            else {
                body += "\nMy draft • " + MirrorComposer.names(notes);
                if (!ItemStack.isSameItemSameComponents(held, new ItemStack(Items.PAPER)))
                    body += "\nHold plain paper in your main hand to print.";
                else if (held.getCount() > 1 && p.getInventory().getFreeSlot() < 0)
                    body += "\nMake room for the remaining paper, or hold a single sheet.";
                else {
                    int slot = p.getInventory().getSelectedSlot();
                    var sheets = held.copy();
                    choices.add(new InfinityJourney.Choice(Items.PAPER, "Print my draft • 1 paper", () -> stamp(p, notes, slot, sheets)));
                }
            }
        }
        choices.add(new InfinityJourney.Choice(Items.ARROW, "Back to Mirror Composer", () -> InfinityJourney.open(p, InfinityJourney.Page.MIRROR_COMPOSER)));
        return new InfinityJourney.View("Mirror Composer • Star Prints", body, choices);
    }

    private static void stamp(ServerPlayer p, List<StarLoom.Chart> expected, int slot, ItemStack sheets) {
        if (!InfinityJourney.emptyScreen(p)) return;
        var inventory = p.getInventory();
        if (inventory.getSelectedSlot() == slot && ItemStack.matches(sheets, p.getMainHandItem()) && expected.equals(MirrorComposer.score(p))) {
            inkSheet(p, new StarPrint(p.getGameProfile().name(), expected).paper());
        }
        InfinityJourney.open(p, InfinityJourney.Page.STAR_PRINTS);
    }

    static boolean inkSheet(ServerPlayer p, ItemStack result) {
        var sheets = p.getMainHandItem();
        if (!ItemStack.isSameItemSameComponents(sheets, new ItemStack(Items.PAPER))) return false;
        var inventory = p.getInventory();
        if (sheets.getCount() > 1) {
            int spare = inventory.getFreeSlot();
            if (spare < 0) return false;
            inventory.setItem(spare, sheets.copyWithCount(sheets.getCount() - 1));
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, result);
        inventory.setChanged();
        PlayerPersistence.save(p);
        return true;
    }

    private boolean stillHeld(ServerPlayer p) {
        if (!InfinityJourney.emptyScreen(p) || ConcertStage.current(p) != null) return false;
        if (equals(read(p.getMainHandItem()))) return true;
        InfinityJourney.open(p, InfinityJourney.Page.STAR_PRINTS);
        return false;
    }

    private void unfold(ServerPlayer p) {
        var forward = p.getLookAngle().normalize();
        double yaw = Math.toRadians(p.getYRot());
        var right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        var up = right.cross(forward).normalize();
        var center = p.getEyePosition().add(forward.scale(1.6));
        project(p, notes, center, right, up, "Star Print");
    }

    private void playFrame(ServerPlayer p, ItemFrame frame) {
        var normal = frame.getDirection().getUnitVec3();
        var right = frameRight(p, frame);
        var up = normal.cross(right);
        var center = GalleryTrail.framePoint(frame);
        boolean reverse = p.isShiftKeyDown();
        String title = (reverse ? "Score Gallery ← " : "Score Gallery → ") + composer;
        project(p, reverse ? notes.reversed() : notes, center, right.scale(reverse ? -.52 : .52), up.scale(.52), title);
    }

    static Vec3 frameRight(ServerPlayer p, ItemFrame frame) {
        // Floor and ceiling displays read from the visitor's side of the frame.
        return frame.getDirection().getAxis() == Direction.Axis.Y ? p.getDirection().getClockWise().getUnitVec3()
            : new Vec3(0, 1, 0).cross(frame.getDirection().getUnitVec3());
    }

    private static void project(ServerPlayer p, List<StarLoom.Chart> notes, Vec3 center, Vec3 right, Vec3 up, String title) {
        var stars = new ArrayList<Vec3>(notes.size());
        for (int i = 0; i < notes.size(); i++) {
            double offset = (i - (notes.size() - 1) * .5) * .25;
            stars.add(center.add(right.scale(offset)).add(up.scale((notes.get(i).note - 10) * .055)));
        }
        InfinityChimes.play(p, notes.stream().map(chart -> chart.note).toList(), beat -> {
            // Redraw the written prefix as each chime sounds so the whole phrase is visible on its final beat.
            for (int i = 0; i <= beat; i++) {
                var point = stars.get(i);
                if (i > 0) {
                    var previous = stars.get(i - 1);
                    for (int j = 1; j <= 3; j++) dot(p, previous.lerp(point, j / 4.0), 0x8198B5, .32f);
                }
                dot(p, point, notes.get(i).ink, .8f);
            }
            var active = stars.get(beat);
            for (int sign : new int[]{-1, 1}) {
                dot(p, active.add(right.scale(sign * .06)), 0xFFD68B, .5f);
                dot(p, active.add(up.scale(sign * .06)), 0xFFD68B, .5f);
            }
            ConcertStage.overlay(p, title + " • " + (beat + 1) + "/" + notes.size() + " • " + notes.get(beat).label);
        });
    }

    private static void dot(ServerPlayer p, Vec3 pos, int color, float size) {
        p.level().sendParticles(p, new DustParticleOptions(color, size), false, false, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
    }
}

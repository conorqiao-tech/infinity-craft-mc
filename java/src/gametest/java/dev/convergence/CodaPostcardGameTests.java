package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.NoteBlock;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

import static dev.convergence.BannerConcertGameTests.choose;
import static dev.convergence.EncoreDuetGameTests.Gallery;
import static dev.convergence.EncoreDuetGameTests.music;
import static dev.convergence.EchoChallengeGameTests.overlays;
import static dev.convergence.GalleryTrailGameTests.particles;
import static dev.convergence.GalleryTrailGameTests.visit;
import static dev.convergence.StarLoom.Chart.*;

public class CodaPostcardGameTests {
    @GameTest public void wallFloorAndCeilingPostcardsCanSwapVoicesForOneVisitor(GameTestHelper c) {
        try (var g = new Gallery(c, "Wall")) {
            var viewer = g.players.get(2);
            var card = new CodaPostcard("Mira", KITE, "Jun", EMBER_PEAKS);
            for (var face : List.of(Direction.NORTH, Direction.UP, Direction.DOWN)) {
                var at = g.frames.getFirst().blockPosition().below(5);
                var frame = face == Direction.NORTH ? new ItemFrame(viewer.level(), at, face) : new GlowItemFrame(viewer.level(), at, face);
                g.frames.add(frame);
                c.assertTrue(viewer.level().addFreshEntity(frame), "Gallery accepts the mounted postcard");
                var author = g.players.getFirst();
                author.setItemInHand(InteractionHand.MAIN_HAND, card.paper());
                visit(author, frame, false);
                c.assertTrue(author.getMainHandItem().isEmpty() && card.equals(CodaPostcard.read(frame.getItem())),
                    "Vanilla frame interaction mounts the signed card");
                viewer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.AMETHYST_SHARD, 2));
                for (boolean swap : List.of(false, true)) {
                    g.wires.forEach(NativePlayConnection::drain);
                    visit(viewer, frame, swap);
                    var packets = g.drain(2);
                    var sounds = music(packets);
                    var chime = swap ? EMBER_PEAKS : KITE;
                    var bell = swap ? KITE : EMBER_PEAKS;
                    c.assertTrue(sounds.size() == 2 && sounds.get(0).getSound().equals(SoundEvents.NOTE_BLOCK_CHIME)
                        && sounds.get(1).getSound().equals(SoundEvents.NOTE_BLOCK_BELL)
                        && sounds.get(0).getPitch() == NoteBlock.getPitchFromNote(chime.note)
                        && sounds.get(1).getPitch() == NoteBlock.getPitchFromNote(bell.note), "Only the notes trade instruments on a Sneak tap");
                    c.assertTrue(overlays(packets).contains(swap ? "Voice swap • Chime: Ember Peaks • Bell: Kite" : card.credits()),
                        "The visitor can distinguish a new arrangement from the signed original");
                    var origin = GalleryTrail.framePoint(frame);
                    var right = StarPrint.frameRight(viewer, frame);
                    var up = face.getUnitVec3().cross(right);
                    var bar = particles(packets, ParticleTypes.END_ROD);
                    var ring = particles(packets, ParticleTypes.WAX_ON);
                    c.assertTrue(bar.size() == 5 && ring.size() == 12, "Each role keeps its own shape");
                    var offset = ring.get(0).lerp(ring.get(6), .5).subtract(bar.get(2));
                    c.assertTrue(Math.abs(offset.dot(up) - .72 * .05 * (bell.note - chime.note)) < .00001
                        && offset.dot(right) > .5, "Pitch height reverses while roles keep their sides");
                    for (var fx : List.of(ParticleTypes.DUST, ParticleTypes.END_ROD, ParticleTypes.WAX_ON))
                        c.assertTrue(particles(packets, fx).stream().allMatch(v -> v.distanceTo(origin) < .5
                            && Math.abs(v.subtract(origin).dot(face.getUnitVec3())) < .00001), "Every particle lies on the exhibit face");
                    var nearby = g.drain(0);
                    c.assertTrue(music(nearby).isEmpty() && particles(nearby, ParticleTypes.DUST).isEmpty() && overlays(nearby).isEmpty(),
                        "Other visitors receive no chord, graphic or credits");
                }
                c.assertTrue(frame.getRotation() == 0 && viewer.getMainHandItem().getCount() == 2 && card.equals(CodaPostcard.read(frame.getItem())),
                    "Revoicing preserves the mounted card and the visitor's shards");
                var decorated = new ItemStack(Items.AMETHYST_SHARD);
                decorated.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
                viewer.setItemInHand(InteractionHand.MAIN_HAND, decorated);
                visit(viewer, frame, false);
                c.assertTrue(frame.getRotation() == 1 && music(g.drain(2)).isEmpty(), "Named shards retain ordinary frame interaction");
                frame.discard();
            }
        }
        c.succeed();
    }

    @GameTest public void twoVisitorsLinkTheirOwnPostcardsByTheOrientedSharedPitch(GameTestHelper c) {
        try (var g = new Gallery(c, "Pairs")) {
            var first = g.frames.getFirst();
            var second = g.frames.get(1);
            var a = new CodaPostcard("Mira", KITE, "Jun", HALF_MOON);
            var b = new CodaPostcard("Jun", EMBER_PEAKS, "Mira", HALF_MOON);
            first.setItem(a.paper());
            second.setItem(b.paper());
            for (var p : g.players) p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ECHO_SHARD, 2));
            g.tap(0, 0);
            c.assertTrue(music(g.drain(0)).size() == 2, "First card previews both signed voices");
            visit(g.players.get(1), second, true);
            var independent = g.drain(1);
            c.assertTrue(overlays(independent).stream().anyMatch(s -> s.contains("Find a chime on Ember Peaks"))
                && particles(independent, ParticleTypes.GLOW).isEmpty(), "Second visitor starts a separate reversed pair");
            g.tap(0, 1);
            var miss = g.drain(0);
            c.assertTrue(music(miss).isEmpty() && overlays(miss).stream().anyMatch(s -> s.startsWith("Find a chime on Half Moon")), "Wrong opening keeps the first bell");
            visit(g.players.getFirst(), second, true);
            var win = g.drain(0);
            var chord = music(win);
            c.assertTrue(chord.size() == 2 && chord.getFirst().getSound().equals(SoundEvents.NOTE_BLOCK_CHIME)
                && chord.getFirst().getPitch() == NoteBlock.getPitchFromNote(KITE.note)
                && chord.getLast().getSound().equals(SoundEvents.NOTE_BLOCK_BELL)
                && chord.getLast().getPitch() == NoteBlock.getPitchFromNote(EMBER_PEAKS.note), "The outer voices sound together");
            var start = GalleryTrail.framePoint(first);
            var end = GalleryTrail.framePoint(second);
            var middle = start.lerp(end, .5);
            var dots = particles(win, ParticleTypes.DUST);
            c.assertTrue(dots.size() == 35 && dots.get(8).distanceTo(dots.get(9)) > .6
                && dots.get(16).distanceTo(middle) < .00001 && dots.get(17).distanceTo(middle) < .00001
                && dots.get(24).y < dots.get(25).y && dots.get(8).y > dots.get(9).y
                && particles(win, ParticleTypes.GLOW).equals(List.of(middle)), "Two luminous strands cross at the shared star");
            c.assertTrue(overlays(win).contains("Linked at Half Moon • Chime: Kite + Bell: Ember Peaks"), "A muted visitor can identify all three pitches");
            for (int i = 1; i <= 2; i++) {
                var spectator = g.drain(i);
                c.assertTrue(music(spectator).isEmpty() && overlays(spectator).isEmpty()
                    && particles(spectator, ParticleTypes.DUST).isEmpty() && particles(spectator, ParticleTypes.GLOW).isEmpty(), "Pair is private");
            }
            g.tap(1, 1);
            c.assertTrue(overlays(g.drain(1)).stream().anyMatch(s -> s.startsWith("Pair cleared")), "Other visitor can still clear their own anchor");
            g.tap(0, 1);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("First postcard")), "Winner can immediately start again");
            c.assertTrue(first.getRotation() == 0 && second.getRotation() == 0 && a.equals(CodaPostcard.read(first.getItem()))
                && b.equals(CodaPostcard.read(second.getItem())) && g.players.getFirst().getMainHandItem().getCount() == 2, "Both displays and echo shards survive");
        }
        c.succeed();
    }

    @GameTest public void replacedPostcardsRestartAndVerticalLinksRespectTwelveBlocks(GameTestHelper c) {
        try (var g = new Gallery(c, "PairRange")) {
            var p = g.players.getFirst();
            var first = g.frames.getFirst();
            var a = new CodaPostcard("Jun", KITE, "Mira", HALF_MOON);
            var b = new CodaPostcard("Mira", HALF_MOON, "Jun", EMBER_PEAKS);
            first.setItem(a.paper());
            var edge = new GlowItemFrame(p.level(), first.blockPosition().below(12), Direction.NORTH);
            var far = new ItemFrame(p.level(), first.blockPosition().below(13), Direction.NORTH);
            for (var f : List.of(edge, far)) {
                f.setItem(b.paper());
                g.frames.add(f);
                c.assertTrue(p.level().addFreshEntity(f), "Mounted second card");
            }
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ECHO_SHARD));
            visit(p, first, false); g.drain(0);
            visit(p, far, false);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Too far")), "Thirteen blocks is outside linking range");
            visit(p, edge, false);
            var result = g.drain(0);
            var dots = particles(result, ParticleTypes.DUST);
            c.assertTrue(particles(result, ParticleTypes.GLOW).size() == 1 && dots.get(24).distanceTo(dots.get(25)) > .6,
                "Twelve blocks is allowed and vertical strands separate horizontally");
            visit(p, first, false); g.drain(0);
            first.setItem(b.paper());
            visit(p, edge, false);
            var stale = g.drain(0);
            c.assertTrue(overlays(stale).stream().anyMatch(s -> s.startsWith("Display changed"))
                && particles(stale, ParticleTypes.GLOW).isEmpty(), "Changed signed score starts a fresh pair instead of completing a stale one");
            var named = new ItemStack(Items.ECHO_SHARD);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Keepsake"));
            p.setItemInHand(InteractionHand.MAIN_HAND, named);
            visit(p, first, false);
            c.assertTrue(first.getRotation() == 1 && music(g.drain(0)).isEmpty(), "Named echo shard retains vanilla handling");
        }
        c.succeed();
    }

    private static void linkCircuitPair(Gallery g) {
        var p = g.players.getFirst();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ECHO_SHARD));
        visit(p, g.frames.getFirst(), false);
        visit(p, g.frames.get(1), true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PRISMARINE_CRYSTALS, 3));
        g.wires.forEach(NativePlayConnection::drain);
    }

    @GameTest public void aThirdSignedFrameClosesOnePrivateCircuit(GameTestHelper c) {
        try (var g = new Gallery(c, "Orbit")) {
            var player = g.players.getFirst();
            var first = g.frames.getFirst();
            var second = g.frames.get(1);
            var third = g.frames.get(2);
            var card = new CodaPostcard("Sol", KITE, "Mira", EMBER_PEAKS);
            first.setItem(new CodaPostcard("Sol", KITE, "Jun", HALF_MOON).paper());
            second.setItem(new CodaPostcard("Jun", EMBER_PEAKS, "Mira", HALF_MOON).paper());
            third.setItem(card.paper());
            linkCircuitPair(g);
            var visitor = g.players.get(1);
            visitor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PRISMARINE_CRYSTALS));
            visit(visitor, third, true);
            c.assertTrue(overlays(g.drain(1)).stream().anyMatch(s -> s.startsWith("Link two postcards")), "A visitor cannot spend another player's link");
            visit(player, second, false);
            visit(player, third, false);
            var misses = g.drain(0);
            c.assertTrue(overlays(misses).contains("Choose a third frame • Ember Peaks → Kite")
                && overlays(misses).contains("Circuit needs Ember Peaks → Kite • Sneak to swap this card's voices.")
                && particles(misses, ParticleTypes.GLOW).isEmpty(), "Wrong display and wrong orientation keep the target");
            visit(player, third, true);
            var packets = g.drain(0);
            var chord = packets.stream().filter(ClientboundSoundPacket.class::isInstance).map(ClientboundSoundPacket.class::cast)
                .filter(s -> s.getSource() == SoundSource.RECORDS).toList();
            c.assertTrue(chord.size() == 3 && chord.get(0).getSound().equals(SoundEvents.NOTE_BLOCK_CHIME)
                && chord.get(1).getSound().equals(SoundEvents.NOTE_BLOCK_HARP) && chord.get(2).getSound().equals(SoundEvents.NOTE_BLOCK_BELL)
                && chord.get(0).getPitch() == NoteBlock.getPitchFromNote(KITE.note)
                && chord.get(1).getPitch() == NoteBlock.getPitchFromNote(HALF_MOON.note)
                && chord.get(2).getPitch() == NoteBlock.getPitchFromNote(EMBER_PEAKS.note), "One interaction resolves the three oriented voices together");
            c.assertTrue(overlays(packets).contains("Circuit closed • Kite → Half Moon → Ember Peaks → Kite"), "Muted play names the complete circuit");
            var corners = particles(packets, ParticleTypes.END_ROD);
            for (int i = 0; i < 3; i++) {
                var pt = GalleryTrail.framePoint(g.frames.get(i));
                c.assertTrue(corners.stream().filter(v -> v.distanceTo(pt) < .5).count() == i + 1, "Ordered corners have one, two and three markers");
                c.assertTrue(particles(packets, ParticleTypes.DUST).contains(pt), "A colored edge starts at each display");
            }
            c.assertValueEqual(particles(packets, ParticleTypes.GLOW).stream().distinct().count(), 12L, "The hollow seal has twelve distinct points");
            var other = g.drain(1);
            c.assertTrue(other.stream().noneMatch(ClientboundSoundPacket.class::isInstance) && particles(other, ParticleTypes.GLOW).isEmpty()
                && particles(other, ParticleTypes.DUST).isEmpty() && particles(other, ParticleTypes.END_ROD).isEmpty() && overlays(other).isEmpty(), "The adjacent visitor gets no feedback from the circuit");
            c.assertTrue(card.equals(CodaPostcard.read(third.getItem())) && third.getRotation() == 0 && player.getMainHandItem().getCount() == 3,
                "Closing preserves the exhibit and crystals");
            visit(player, third, true);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Link two postcards")), "The link can only be closed once");
        }
        c.succeed();
    }

    @GameTest public void circuitChecksBothDistancesAndInvalidatesReplacedOrAbandonedLinks(GameTestHelper c) {
        try (var g = new Gallery(c, "Radius")) {
            var p = g.players.getFirst();
            var first = g.frames.getFirst();
            var second = g.frames.get(1);
            var a = new CodaPostcard("Mira", KITE, "Jun", HALF_MOON);
            var b = new CodaPostcard("Sol", EMBER_PEAKS, "Jun", HALF_MOON);
            first.setItem(a.paper()); second.setItem(b.paper());
            var lower = new GlowItemFrame(p.level(), first.blockPosition().below(12), Direction.NORTH);
            lower.setItem(new CodaPostcard("Jun", EMBER_PEAKS, "Sol", KITE).paper());
            g.frames.add(lower);
            c.assertTrue(p.level().addFreshEntity(lower), "Third display is mounted");
            linkCircuitPair(g);
            visit(p, lower, false);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Choose a third frame within 12")), "Third frame must be near both ends, even when exactly 12 from the first");
            second.setItem(a.paper());
            visit(p, lower, false);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Display changed")), "Changed second signature invalidates the link");
            second.setItem(b.paper()); linkCircuitPair(g);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ECHO_SHARD));
            visit(p, lower, false);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PRISMARINE_CRYSTALS));
            visit(p, lower, false);
            c.assertTrue(overlays(g.drain(0)).stream().anyMatch(s -> s.startsWith("Link two postcards")), "Starting a fresh pair retires the previous circuit");
            var middle = new ItemFrame(p.level(), first.blockPosition().below(6), Direction.NORTH);
            middle.setItem(b.paper()); g.frames.add(middle);
            c.assertTrue(p.level().addFreshEntity(middle), "Middle frame completes the vertical gallery");
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ECHO_SHARD));
            visit(p, lower, false); // Clear the pending anchor from the abandoned pair.
            visit(p, first, false); visit(p, middle, true);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PRISMARINE_CRYSTALS));
            g.drain(0); visit(p, lower, false);
            var line = g.drain(0);
            var apex = GalleryTrail.framePoint(middle).add(.65, 0, 0);
            c.assertTrue(particles(line, ParticleTypes.DUST).stream().anyMatch(v -> v.distanceTo(apex) < .00001)
                && particles(line, ParticleTypes.GLOW).size() == 12, "At 12 blocks a straight vertical circuit bows outward and earns its seal");
            var named = new ItemStack(Items.PRISMARINE_CRYSTALS);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Memento"));
            p.setItemInHand(InteractionHand.MAIN_HAND, named); visit(p, lower, false);
            c.assertValueEqual(lower.getRotation(), 1, "Named crystals keep ordinary frame interaction");
        }
        c.succeed();
    }

    private static void checkReplay(GameTestHelper c, List<Packet<?>> packets, CodaPostcard card) {
        var voices = music(packets);
        c.assertTrue(voices.size() == 2 && voices.getFirst().getSound().equals(SoundEvents.NOTE_BLOCK_CHIME)
            && voices.getLast().getSound().equals(SoundEvents.NOTE_BLOCK_BELL)
            && voices.getFirst().getPitch() == NoteBlock.getPitchFromNote(card.chime().note)
            && voices.getLast().getPitch() == NoteBlock.getPitchFromNote(card.bell().note), "One replay delivers the two original voices");
        var bar = particles(packets, ParticleTypes.END_ROD);
        var ring = particles(packets, ParticleTypes.WAX_ON);
        // Both readers face south. The left-hand voice is east, and the right-hand voice is west.
        c.assertTrue(bar.size() == 5 && ring.size() == 12 && bar.stream().allMatch(p -> p.x == bar.getFirst().x)
            && ring.stream().allMatch(p -> p.x < bar.getFirst().x - .5 && p.z == bar.getFirst().z), "Bar and ring occupy separate columns");
        var center = ring.get(0).lerp(ring.get(6), .5);
        c.assertTrue(ring.stream().allMatch(p -> Math.abs(p.distanceTo(center) - .13) < .00001)
            && Math.abs((center.y - bar.get(2).y) - (card.bell().note - card.chime().note) * .05) < .00001,
            "The bell circle sits at its pitch relative to the chime, including level unisons");
        c.assertTrue(overlays(packets).contains(card.credits()), "Reader sees both performers, instruments and notes");
    }

    @GameTest public void aGiftReplaysTheSignedChordAfterPerformersLeaveTheGallery(GameTestHelper c) {
        try (var g = new Gallery(c, "Gift")) {
            g.finishDuet();
            var lead = g.players.getFirst();
            var reply = g.players.get(1);
            var reader = g.players.get(2);
            visit(reply, g.frames.getLast(), true);
            visit(lead, g.frames.getFirst(), true);
            g.tap(0, 1); // Only one vote for the next chord. The completed chord is the printable one.
            lead.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 3));
            g.tap(0, 2);
            var card = new CodaPostcard(lead.getGameProfile().name(), KITE, reply.getGameProfile().name(), EMBER_PEAKS);
            var restored = new CommonsGameTests().reloadDisk(lead);
            c.assertValueEqual(CodaPostcard.read(restored.getMainHandItem()), card, "Disk reload preserves the chord and original performers");
            int blanks = 0;
            for (int i = 0; i < 36; i++) {
                var stack = restored.getInventory().getItem(i);
                if (stack.is(Items.PAPER) && stack.getComponentsPatch().isEmpty()) blanks += stack.getCount();
            }
            c.assertValueEqual(blanks, 2, "Exactly one blank sheet becomes a postcard");
            g.tap(1, 0);
            reply.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
            g.tap(1, 2);
            var newer = new CodaPostcard(card.lead(), EMBER_PEAKS, card.reply(), HALF_MOON);
            c.assertTrue(newer.equals(CodaPostcard.read(reply.getMainHandItem())) && card.equals(CodaPostcard.read(lead.getMainHandItem())),
                "A later chord changes future postcards and preserves copies already stamped");
            EncoreDuet.leave(lead);
            g.frames.forEach(frame -> frame.discard());
            reader.getInventory().clearContent();
            reader.setPos(lead.position());
            lead.drop(false);
            var item = lead.level().getEntitiesOfClass(ItemEntity.class, lead.getBoundingBox().inflate(4),
                drop -> card.equals(CodaPostcard.read(drop.getItem()))).getFirst();
            item.setNoPickUpDelay();
            item.playerTouch(reader);
            c.assertTrue(item.isRemoved() && card.equals(CodaPostcard.read(reader.getMainHandItem())), "A third player picks up and holds the gift");
            reader.setPos(lead.position().add(48, 0, 0));
            lead.setPos(reader.position().add(1, 0, 0));
            reader.setXRot(0); reader.setYRot(0);
            InfinityJourney.open(reader, InfinityJourney.Page.MIRROR_COMPOSER);
            choose(reader, "Add Half Moon"); choose(reader, "Add Kite");
            reader.closeContainer();
            g.wires.forEach(NativePlayConnection::drain);
            reader.gameMode.useItem(reader, reader.level(), reader.getMainHandItem(), InteractionHand.MAIN_HAND);
            checkReplay(c, g.drain(2), card);
            var bystander = g.drain(0);
            c.assertTrue(music(bystander).isEmpty() && overlays(bystander).isEmpty()
                && particles(bystander, ParticleTypes.DUST).isEmpty() && particles(bystander, ParticleTypes.WAX_ON).isEmpty()
                && particles(bystander, ParticleTypes.END_ROD).isEmpty(), "The nearby author receives none of the reader's replay");
            c.assertTrue(card.equals(CodaPostcard.read(reader.getMainHandItem())) && List.of(HALF_MOON, KITE).equals(MirrorComposer.score(reader)),
                "Playback preserves the gift and reader's own score");
        }
        c.succeed();
    }

    @GameTest public void eitherPerformerCanStampAUnisonAndPlayItFromTheTouchMenu(GameTestHelper c) {
        try (var g = new Gallery(c, "Mail")) {
            g.finishDuet();
            var lead = g.players.getFirst();
            var reply = g.players.get(1);
            reply.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
            g.tap(1, 2);
            c.assertTrue(reply.getMainHandItem().getComponentsPatch().isEmpty() && g.frames.getLast().getRotation() == 0,
                "An unfinished first chord leaves paper and the last frame intact");
            reply.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOW_INK_SAC));
            g.tap(0, 0); g.tap(1, 0);
            var special = new ItemStack(Items.PAPER);
            special.set(DataComponents.CUSTOM_NAME, Component.literal("Letter"));
            reply.setItemInHand(InteractionHand.MAIN_HAND, special);
            g.tap(1, 2);
            c.assertTrue(ItemStack.matches(special, reply.getMainHandItem()) && g.frames.getLast().getRotation() == 1,
                "Personal stationery keeps normal frame use");
            for (int i = 0; i < 36; i++) reply.getInventory().setItem(i, new ItemStack(Items.STONE));
            reply.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 2));
            g.tap(1, 2);
            c.assertTrue(reply.getMainHandItem().getCount() == 2 && reply.getMainHandItem().getComponentsPatch().isEmpty(),
                "Full inventory preserves both sheets");
            reply.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER));
            g.tap(1, 2);
            var card = new CodaPostcard(lead.getGameProfile().name(), HALF_MOON, reply.getGameProfile().name(), HALF_MOON);
            c.assertValueEqual(CodaPostcard.read(reply.getMainHandItem()), card, "Reply can stamp a unison with a single sheet in a full inventory");
            reply.setXRot(0); reply.setYRot(0);
            var forms = new ArrayList<Form>();
            InfinityJourney.form(reply, InfinityJourney.Page.STAR_PRINTS, forms::add);
            var form = (SimpleForm) forms.getFirst();
            int button = form.buttons().stream().map(b -> b.text()).toList().indexOf("Play held postcard");
            c.assertTrue(button >= 0 && form.content().contains(card.credits()), "Touch form identifies the card and provides playback");
            g.drain(1);
            new CommonsGameTests().reply(form, Integer.toString(button));
            checkReplay(c, g.drain(1), card);
            forms.clear();
            InfinityJourney.form(reply, InfinityJourney.Page.STAR_PRINTS, forms::add);
            reply.setItemInHand(InteractionHand.MAIN_HAND, special);
            new CommonsGameTests().reply(forms.getFirst(), Integer.toString(button));
            c.assertTrue(music(g.drain(1)).isEmpty(), "Delayed touch-menu taps cannot play a replaced card");
        }
        c.succeed();
    }
}

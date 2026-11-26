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
import net.minecraft.sounds.SoundEvents;
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

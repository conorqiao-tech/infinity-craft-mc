package dev.convergence;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.fish.AbstractFish;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.geysermc.cumulus.form.Form;

public class InfinityTidewatchGameTests {
    private static int button(InfinityJourney.View view, String label) {
        return view.choices().stream().map(InfinityJourney.Choice::label).toList().indexOf(label);
    }

    private static void click(ServerPlayer p, String label) {
        var menu = (InfinityJourney.Handler) p.containerMenu;
        int index = button(menu.view, label);
        if (index < 0) throw new AssertionError("Missing menu choice " + label);
        menu.clicked(27 + index, 0, ContainerInput.PICKUP, p);
    }

    @GameTest public void recordsFourLivingSpeciesThroughJavaAndBedrockControls(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "tidewatch");
        var level = p.level();
        var origin = p.blockPosition().above(60);
        p.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        var pool = origin.offset(4, 1, 0);
        var window = origin.offset(2, 1, 0);
        var originalPool = level.getBlockState(pool);
        var originalWindow = level.getBlockState(window);
        var school = new ArrayList<AbstractFish>();
        try {
            var initial = GameModes.state(p).copy();
            InfinityJourney.open(p);
            click(p, "Infinity Tidewatch • fish field journal");
            c.assertValueEqual(GameModes.state(p), initial, "Opening the field guide creates no saved data");
            c.assertTrue(p.containerMenu.getSlot(27).getItem().is(Items.COD), "The first Java sighting uses the cod icon");
            p.closeContainer();
            var firstForms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.TIDEWATCH, form -> { firstForms.add(form); return true; });
            var firstForm = firstForms.getFirst();
            var encoded = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(firstForm).codec().jsonData(firstForm);
            var buttons = JsonParser.parseString(encoded).getAsJsonObject().getAsJsonArray("buttons");
            var paths = List.of("fish_raw", "fish_salmon_raw", "fish_clownfish_raw", "fish_pufferfish_raw");
            for (int i = 0; i < paths.size(); i++) {
                var image = buttons.get(i).getAsJsonObject().getAsJsonObject("image");
                c.assertValueEqual(image.get("type").getAsString(), "path", "Bedrock loads the local item texture");
                c.assertValueEqual(image.get("data").getAsString(), "textures/items/" + paths.get(i), "Bedrock button uses its species sprite");
            }
            f.reply(firstForm, "0");
            for (var species : InfinityTidewatch.Species.values()) {
                var fish = species.type.create(level, EntitySpawnReason.COMMAND);
                fish.setPos(pool.getX() + .5, pool.getY(), pool.getZ() + .5);
                fish.setNoAi(true);
                level.addFreshEntity(fish);
                school.add(fish);
            }
            click(p, "Record Cod");
            c.assertValueEqual(InfinityTidewatch.sightings(p), 0, "A fish on dry land does not count");
            level.setBlock(pool, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(window, Blocks.STONE.defaultBlockState(), 2);
            click(p, "Record Cod");
            c.assertValueEqual(InfinityTidewatch.sightings(p), 0, "A solid wall blocks the sighting");
            level.setBlock(window, Blocks.GLASS.defaultBlockState(), 2);
            click(p, "Record Cod");
            c.assertValueEqual(InfinityTidewatch.sightings(p), 1, "An aquarium window allows the sighting");
            click(p, "Record Salmon");
            click(p, "Record Tropical fish");
            p.closeContainer();
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.TIDEWATCH, form -> { forms.add(form); return true; });
            f.reply(firstForm, "0");
            c.assertValueEqual(InfinityTidewatch.sightings(p), 7, "Replaying the old cod button cannot record the pufferfish now at index zero");
            f.reply(forms.getFirst(), "0");
            c.assertValueEqual(InfinityTidewatch.sightings(f.reloadDisk(p)), 15, "The encoded pufferfish choice completes the durable journal");
            c.assertTrue(((InfinityJourney.Handler) p.containerMenu).view.content().contains("Field journal complete"), "The reopened journal shows completion");
            p.closeContainer();
            c.assertFalse(InfinityTidewatch.record(p, InfinityTidewatch.Species.COD), "A repeat sighting earns no second entry");
            for (var fish : school) c.assertTrue(fish.isAlive() && fish.getHealth() == fish.getMaxHealth(), "Every observed fish remains unharmed");
            c.assertTrue(p.getInventory().isEmpty(), "Sighting fish neither needs nor grants inventory items");
        } finally {
            for (var fish : school) fish.discard();
            level.setBlock(pool, originalPool, 2);
            level.setBlock(window, originalWindow, 2);
            InfinityJourneyGameTests.cleanup(f, p);
        }
        c.succeed();
    }

    @GameTest public void hardcoreRealmRecordsSightingsIntoThePersonalJournal(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "tide-hardcore");
        try {
            GameModes.switchNow(p, GameModes.Mode.HARDCORE, null);
            c.assertValueEqual(GameModes.of(p.level()), GameModes.Mode.HARDCORE, "Entered the native Hardcore realm");
            var level = p.level();
            var at = p.blockPosition().above(12);
            p.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
            var pool = at.offset(2, 0, 0);
            var before = level.getBlockState(pool);
            var salmon = InfinityTidewatch.Species.SALMON.type.create(level, EntitySpawnReason.COMMAND);
            try {
                level.setBlock(pool, Blocks.WATER.defaultBlockState(), 2);
                salmon.setPos(pool.getX() + .5, pool.getY(), pool.getZ() + .5);
                level.addFreshEntity(salmon);
                InfinityJourney.open(p, InfinityJourney.Page.TIDEWATCH);
                click(p, "Record Salmon");
                c.assertValueEqual(InfinityTidewatch.sightings(f.reloadDisk(p)), 2, "Hardcore sightings persist on the character");
            } finally {
                salmon.discard();
                level.setBlock(pool, before, 2);
            }
        } finally { InfinityJourneyGameTests.cleanup(f, p); }
        c.succeed();
    }

    @GameTest public void rangeModeAndUnreadableJournalRefuseSightings(GameTestHelper c) {
        var f = new CommonsGameTests();
        var p = f.survival(c, "tide-limits");
        var level = p.level();
        var at = p.blockPosition().above(65);
        var pool = at.offset(9, 0, 0);
        var before = level.getBlockState(pool);
        var cod = InfinityTidewatch.Species.COD.type.create(level, EntitySpawnReason.COMMAND);
        try {
            p.setPos(at.getX() + .5, at.getY(), at.getZ() + .5);
            level.setBlock(pool, Blocks.WATER.defaultBlockState(), 2);
            cod.setPos(p.position().add(9, 0, 0));
            level.addFreshEntity(cod);
            c.assertFalse(InfinityTidewatch.record(p, InfinityTidewatch.Species.COD), "Nine blocks is out of range");
            p.setPos(p.position().add(1, 0, 0));
            c.assertFalse(InfinityTidewatch.record(p, InfinityTidewatch.Species.SALMON), "Another species cannot fill this entry");
            p.setGameMode(GameType.CREATIVE);
            c.assertFalse(InfinityTidewatch.record(p, InfinityTidewatch.Species.COD), "Creative cannot record a sighting");
            p.setGameMode(GameType.SURVIVAL);
            c.assertTrue(InfinityTidewatch.record(p, InfinityTidewatch.Species.COD), "Exactly eight blocks is in range");
            var root = GameModes.state(p);
            root.putString(InfinityTidewatch.KEY, "future-format");
            c.assertFalse(InfinityTidewatch.record(p, InfinityTidewatch.Species.COD), "Unknown journal data blocks changes");
            c.assertValueEqual(root.getStringOr(InfinityTidewatch.KEY, ""), "future-format", "The unknown value survives");
            root.putInt(InfinityTidewatch.KEY, 16);
            c.assertValueEqual(InfinityTidewatch.view(p).choices().size(), 1, "Unknown bits leave only the navigation control");
        } finally {
            cod.discard();
            level.setBlock(pool, before, 2);
            InfinityJourneyGameTests.cleanup(f, p);
        }
        c.succeed();
    }
}

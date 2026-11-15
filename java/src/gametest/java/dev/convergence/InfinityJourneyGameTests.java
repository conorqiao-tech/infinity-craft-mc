package dev.convergence;

import java.util.ArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;

/** Native saved-player, menu and encoded crossplay form controls in a disposable world. */
public class InfinityJourneyGameTests {
    static void setAllEvidence(ServerPlayer p) {
        var root = GameModes.state(p);
        var commons = new CompoundTag(); commons.putInt("atlas", 7); commons.putBoolean("signal", true);
        commons.putBoolean("delivery", true); commons.putInt("food_0", 16); root.put(CommonsEvents.KEY, commons);
        var odyssey = new CompoundTag(); odyssey.putInt("realms", 1); root.put(OdysseyJournal.KEY, odyssey);
        var astral = new CompoundTag(); astral.putInt("kills", 1); root.put(AstralJournal.KEY, astral);
        var scores = new CompoundTag(); scores.putLong("memory", 1234); root.put("scores", scores);
    }
    static void cleanup(CommonsGameTests f, ServerPlayer p) {
        InfinityJourney.ACTIVE.remove(p.getUUID()); InfinityJourney.DELIVERIES.remove(p.getUUID()); f.cleanup(p);
    }

    @GameTest public void previewsAreReadOnlyAndLockedPathsCannotAttune(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "journey-preview");
        try {
            var before = GameModes.state(p).copy();
            c.assertValueEqual(InfinityJourney.eligible(p), 0, "A new player has no earned paths");
            InfinityJourney.view(p, InfinityJourney.Page.JOURNEY);
            c.assertTrue(InfinityJourney.emblem(p, true).get(DataComponents.BANNER_PATTERNS) != null, "The original native sigil has real banner layers");
            c.assertValueEqual(GameModes.state(p), before, "Viewing the journey does not create or alter saved state");
            c.assertValueEqual(InfinityJourney.attune(p, 0), 0, "A locked path cannot be attuned");
            c.assertValueEqual(InfinityJourney.select(p, 7), 0, "The final trail requires all six paths");
            c.assertValueEqual(GameModes.state(p), before, "Refused choices preserve existing state");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void evidenceThresholdsAndRealPantryDeliveryAreChecked(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "journey-evidence");
        try {
            var root = GameModes.state(p); var commons = new CompoundTag(); commons.putInt("atlas", 3); root.put(CommonsEvents.KEY, commons);
            c.assertValueEqual(InfinityJourney.eligible(p), 0, "Two biome families are insufficient");
            commons.putInt("atlas", 7); commons.putBoolean("signal", true);
            c.assertValueEqual(InfinityJourney.eligible(p), 1, "Three stamps count; a circuit without delivery does not");
            commons.putBoolean("delivery", true);
            var odyssey = new CompoundTag(); odyssey.putInt("realms", 8); root.put(OdysseyJournal.KEY, odyssey);
            var astral = new CompoundTag(); astral.putInt("kills", 8); root.put(AstralJournal.KEY, astral);
            var scores = new CompoundTag(); scores.putLong("memory", Long.MAX_VALUE); root.put("scores", scores);
            c.assertValueEqual(InfinityJourney.eligible(p), 9, "Unknown realm and kill bits and an invalid score do not unlock paths");
            odyssey.putInt("realms", 1); astral.putInt("kills", 4); scores.putLong("memory", 1200);
            c.assertValueEqual(InfinityJourney.eligible(p), 31, "Saved ruin, guardian and genuine course score unlock their paths");
            p.getInventory().setItem(0, new ItemStack(Items.WHEAT, 16));
            c.assertValueEqual(CommonsEvents.donate(p, CommonsEvents.quote(p, 0)), 16, "The native pantry consumes a reviewed delivery");
            c.assertValueEqual(InfinityJourney.eligible(p), InfinityJourney.ALL, "A real pantry delivery unlocks the final path");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void attunementsSurviveNativeSaveWithoutChangingInventory(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "journey-save");
        try {
            p.getInventory().setItem(0, CommonsEvents.named(Items.DIAMOND_PICKAXE, 1, "Existing player tool"));
            var item = p.getInventory().getItem(0).copy(); setAllEvidence(p);
            for (int i = 0; i < 6; i++) {
                c.assertValueEqual(InfinityJourney.attune(p, i), 1, "Each eligible path can be attuned");
                if (i < 5) c.assertValueEqual(InfinityJourney.select(p, 7), 0, "Constellation stays locked until all six are attuned");
            }
            c.assertValueEqual(InfinityJourney.select(p, 7), 1, "All six paths unlock the Constellation trail");
            c.assertValueEqual(InfinityJourney.style(p), 7, "The selected final trail is active");
            c.assertTrue(ItemStack.isSameItemSameComponents(item, p.getInventory().getItem(0)), "The existing tool and its components remain unchanged");
            for (int i = 1; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            c.assertValueEqual(InfinityJourney.giveSigil(p), 0, "A full inventory refuses the cosmetic banner");
            c.assertTrue(ItemStack.isSameItemSameComponents(item, p.getInventory().getItem(0)), "A refused gift does not replace the existing tool");
            p.getInventory().setItem(1, ItemStack.EMPTY);
            c.assertValueEqual(InfinityJourney.giveSigil(p), 1, "The earned banner goes into an empty slot");
            c.assertTrue(p.getInventory().getItem(1).get(DataComponents.BANNER_PATTERNS) != null, "The reward carries its native original pattern");
            var reloaded = f.reloadDisk(p);
            c.assertValueEqual(InfinityJourney.attuned(reloaded), InfinityJourney.ALL, "Native player save retains all attunements");
            c.assertValueEqual(InfinityJourney.style(reloaded), 7, "Native player save retains the selected trail");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(1), reloaded.getInventory().getItem(1)), "The banner survives the native player save");
            p.setInvisible(true); c.assertValueEqual(InfinityJourney.visibleStyle(p), 0, "Invisible players do not emit the trail");
            p.setInvisible(false); c.assertValueEqual(InfinityJourney.visibleStyle(p), 7, "Visible players regain their chosen trail");
            c.assertValueEqual(InfinityJourney.select(p, 0), 1, "The trail can be turned off");
            c.assertValueEqual(InfinityJourney.style(p), 0, "No trail remains selected");
            c.assertValueEqual(InfinityJourney.hideOthers(p, true), 1, "A player can hide other Journey trails");
            c.assertTrue(InfinityJourney.hidesOthers(f.reloadDisk(p)), "The viewing preference survives the native save");
            var other = f.survival(c, "journey-viewer");
            try {
                c.assertFalse(InfinityJourney.maySee(p, other), "The preference hides a different player's trail");
                c.assertTrue(InfinityJourney.maySee(p, p), "The player's own chosen trail remains visible to them");
            } finally { cleanup(f, other); }
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void unknownOrMalformedJourneyDataIsNeverOverwritten(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "journey-unknown");
        try {
            setAllEvidence(p); var root = GameModes.state(p);
            root.putString(InfinityJourney.KEY, "future opaque data");
            var before = root.copy();
            c.assertValueEqual(InfinityJourney.attune(p, 0), 0, "A non-compound value is refused");
            c.assertValueEqual(InfinityJourney.select(p, 0), 0, "Turning a trail off cannot overwrite an unknown value");
            c.assertValueEqual(root, before, "The unknown value remains exact");
            var future = new CompoundTag(); future.putInt("format", 2); future.putString("evidence", "keep");
            root.put(InfinityJourney.KEY, future); before = root.copy();
            c.assertValueEqual(InfinityJourney.attune(p, 0), 0, "A future data format is refused");
            c.assertValueEqual(root, before, "Future data remains exact");
            var malformed = new CompoundTag(); malformed.putInt("format", 1); malformed.putInt("attuned", 999);
            root.put(InfinityJourney.KEY, malformed); before = root.copy();
            c.assertValueEqual(InfinityJourney.attune(p, 0), 0, "An invalid saved mask is refused");
            c.assertValueEqual(root, before, "Malformed saved data remains exact");
            malformed.putString("attuned", "not an integer"); before = root.copy();
            c.assertValueEqual(InfinityJourney.attune(p, 0), 0, "A non-integer saved mask is refused");
            c.assertValueEqual(root, before, "The original tag type is preserved");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void javaMenuAndEncodedBedrockFormsRejectStaleActions(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "journey-menu");
        try {
            setAllEvidence(p);
            ServerMenu.open(p); p.containerMenu.clicked(ServerMenu.JOURNEY, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu instanceof InfinityJourney.Handler, "The real Infinity Menu opens the Journey");
            p.containerMenu.clicked(33, 0, ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu instanceof InfinityJourney.Handler, "Java opens the trail choices");
            p.containerMenu.clicked(27, 0, ContainerInput.PICKUP, p); p.closeContainer();
            c.assertValueEqual(InfinityJourney.attuned(p), 1, "A real Java click attunes the chosen eligible path");
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { forms.add(form); return true; });
            InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { forms.add(form); return true; });
            f.reply(forms.get(0), "1");
            c.assertValueEqual(InfinityJourney.attuned(p), 1, "An older encoded Bedrock form cannot attune");
            f.reply(forms.get(1), "1");
            c.assertValueEqual(InfinityJourney.attuned(p), 3, "The current encoded Bedrock form attunes once");
            p.closeContainer(); f.reply(forms.get(1), "2");
            c.assertValueEqual(InfinityJourney.attuned(p), 3, "Replaying an encoded response is ignored");
            forms.clear(); InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { forms.add(form); return true; });
            p.setGameMode(GameType.CREATIVE); f.reply(forms.get(0), "2");
            c.assertValueEqual(InfinityJourney.attuned(p), 3, "A native mode change invalidates the form");
            p.setGameMode(GameType.SURVIVAL);
            forms.clear(); InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { forms.add(form); return true; });
            ServerMenu.open(p); p.closeContainer(); f.reply(forms.get(0), "2");
            c.assertValueEqual(InfinityJourney.attuned(p), 3, "An intervening native screen invalidates an older form even after closing");
            c.assertValueEqual(InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { throw new IllegalStateException("injected"); }), 0, "A failed form transport is contained");
            c.assertFalse(InfinityJourney.ACTIVE.containsKey(p.getUUID()), "A failed transport leaves no live ticket");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void javaMenuPreviewCannotBeTakenOrSwapped(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "journey-slots");
        try {
            p.getInventory().setItem(0, CommonsEvents.named(Items.DIAMOND_PICKAXE, 1, "Existing tool"));
            var original = p.getInventory().getItem(0).copy();
            InfinityJourney.open(p); var menu = (InfinityJourney.Handler) p.containerMenu;
            for (var action : ContainerInput.values()) {
                menu.clicked(17, 0, action, p);
                c.assertTrue(menu.getCarried().isEmpty(), "The preview cannot become the cursor item: " + action);
                c.assertTrue(ItemStack.isSameItemSameComponents(original, p.getInventory().getItem(0)), "The hotbar remains unchanged: " + action);
                c.assertTrue(menu.getSlot(17).getItem().get(DataComponents.BANNER_PATTERNS) != null, "The preview remains in the menu: " + action);
            }
        } finally { cleanup(f, p); } c.succeed();
    }
}

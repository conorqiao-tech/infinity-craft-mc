package dev.convergence;

import java.util.ArrayList;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;

public class InfinityRelayGameTests {
    static void completeRedLight(ServerPlayer p) {
        var spec = ModeMaps.MAPS.get("redlight"); var run = ModeMaps.RUNS.get(p.getUUID());
        for (int i = 1; i < spec.points().size(); i++) {
            p.setPos(Vec3.atBottomCenterOf(spec.points().get(i))); p.setOnGround(true); ModeMaps.tick(p, run.startedTick + i);
        }
    }
    static void cleanup(CommonsGameTests f, ServerPlayer p) {
        ModeMaps.RUNS.remove(p.getUUID()); InfinityJourney.ACTIVE.remove(p.getUUID());
        InfinityJourney.DELIVERIES.remove(p.getUUID()); f.cleanup(p);
    }

    @GameTest public void currentCourseCatalogueIsFullyCovered(GameTestHelper c) {
        c.assertValueEqual(Set.copyOf(InfinityRelay.COURSES), ModeMaps.MAPS.keySet(), "Every existing minigame and adventure has one stable relay bit");
        c.assertValueEqual(InfinityRelay.COURSES.size(), 10, "The relay has ten distinct courses");
        c.succeed();
    }

    @GameTest public void realCourseCompletionCountsOnlyAfterOptInAndOnlyOnce(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = new ModeGameTests().player(c, "relay-real");
        try {
            GameModes.switchNow(p, GameModes.Mode.MINIGAMES, "redlight"); p.setNoGravity(true);
            completeRedLight(p);
            c.assertFalse(GameModes.state(p).contains(InfinityRelay.KEY), "A course finish does not create relay data before opt-in");
            long best = GameModes.state(p).getCompoundOrEmpty("scores").getLongOr("redlight", -1);
            c.assertTrue(best > 0, "The ordinary course result is still recorded");
            c.assertValueEqual(InfinityRelay.setActive(p, true), 1, "The player explicitly starts the relay");
            ModeMaps.begin(p, "redlight"); completeRedLight(p);
            int bit = 1 << InfinityRelay.COURSES.indexOf("redlight");
            c.assertValueEqual(InfinityRelay.completed(p), bit, "A genuine ordered course finish lights its own relay bit");
            c.assertValueEqual(InfinityRelay.completed(f.reloadDisk(p)), bit, "The native player save retains the relay progress");
            ModeMaps.begin(p, "redlight"); completeRedLight(p);
            c.assertValueEqual(InfinityRelay.completed(p), bit, "Repeating a course cannot add another bit");
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").getLongOr("redlight", -1) > 0, "The ordinary personal best remains available");
            c.assertValueEqual(InfinityRelay.setActive(p, false), 1, "The relay can be paused");
            c.assertFalse(InfinityRelay.finished(p, "parkour"), "Paused relay ignores a later finish");
            c.assertValueEqual(InfinityRelay.completed(p), bit, "Pausing preserves prior progress");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void savedUnknownRelayDataAndInventoryRemainIntact(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "relay-data");
        try {
            var root = GameModes.state(p); root.putString(InfinityRelay.KEY, "unknown future state"); var before = root.copy();
            c.assertValueEqual(InfinityRelay.setActive(p, true), 0, "A non-compound relay state is refused");
            c.assertValueEqual(root, before, "Unknown state is preserved exactly");
            var future = new CompoundTag(); future.putInt("format", 2); future.putString("evidence", "keep"); root.put(InfinityRelay.KEY, future); before = root.copy();
            c.assertValueEqual(InfinityRelay.setActive(p, true), 0, "A future relay format is refused");
            c.assertValueEqual(root, before, "Future state is not overwritten");
            var complete = new CompoundTag(); complete.putInt("format", 1); complete.putInt("completed", InfinityRelay.ALL); complete.putBoolean("active", false); root.put(InfinityRelay.KEY, complete);
            for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            c.assertValueEqual(InfinityRelay.giveBanner(p), 0, "A full inventory refuses the banner");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(), 64, "Existing items are not replaced");
            p.getInventory().setItem(0, ItemStack.EMPTY);
            c.assertValueEqual(InfinityRelay.giveBanner(p), 1, "A complete relay grants a repeatable cosmetic banner into an empty slot");
            c.assertTrue(p.getInventory().getItem(0).get(DataComponents.BANNER_PATTERNS) != null, "The reward uses the original native design");
            c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(0), f.reloadDisk(p).getInventory().getItem(0)), "The native save retains the banner");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void javaAndBedrockNavigateTheSameRelayAndRejectStaleForms(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "relay-menu");
        try {
            InfinityJourney.open(p); p.containerMenu.clicked(34, 0, ContainerInput.PICKUP, p);
            c.assertValueEqual(((InfinityJourney.Handler) p.containerMenu).view.title(), "Infinity Circuit Relay", "Java opens the existing-courses relay");
            p.containerMenu.clicked(27, 0, ContainerInput.PICKUP, p); p.closeContainer();
            c.assertTrue(InfinityRelay.active(p), "The real Java button starts the relay");
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.RELAY, form -> { forms.add(form); return true; });
            InfinityJourney.form(p, InfinityJourney.Page.RELAY, form -> { forms.add(form); return true; });
            f.reply(forms.get(0), "0"); c.assertTrue(InfinityRelay.active(p), "A stale Bedrock form cannot pause the relay");
            f.reply(forms.get(1), "0"); c.assertFalse(InfinityRelay.active(p), "The current encoded Bedrock response pauses it");
            p.closeContainer(); f.reply(forms.get(1), "0"); c.assertFalse(InfinityRelay.active(p), "A replay cannot start the relay again");
            forms.clear(); InfinityJourney.form(p, InfinityJourney.Page.RELAY, form -> { forms.add(form); return true; });
            c.assertValueEqual(InfinityRelay.setActive(p, true), 1, "The relay changed while the form was open");
            f.reply(forms.get(0), "0");
            c.assertTrue(InfinityRelay.active(p), "A stale Start label cannot unexpectedly pause an already-started relay");
        } finally { cleanup(f, p); } c.succeed();
    }

    @GameTest public void journeyFormsDoNotInvertAChangedPreferenceOrAttuneAnOldLockedChoice(GameTestHelper c) {
        var f = new CommonsGameTests(); var p = f.survival(c, "relay-stale-ui");
        try {
            var forms = new ArrayList<Form>();
            InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { forms.add(form); return true; });
            var commons = new CompoundTag(); commons.putInt("atlas", 7); GameModes.state(p).put(CommonsEvents.KEY, commons);
            f.reply(forms.get(0), "0");
            c.assertValueEqual(InfinityJourney.attuned(p), 0, "A choice displayed as locked does not attune after progress changes");
            p.closeContainer(); forms.clear();
            InfinityJourney.form(p, InfinityJourney.Page.STYLES, form -> { forms.add(form); return true; });
            c.assertValueEqual(InfinityJourney.hideOthers(p, true), 1, "The preference changed while the form was open");
            f.reply(forms.get(0), "9");
            c.assertTrue(InfinityJourney.hidesOthers(p), "An old Hide label cannot unexpectedly show other trails");
        } finally { cleanup(f, p); } c.succeed();
    }
}

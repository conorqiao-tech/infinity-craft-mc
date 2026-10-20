package dev.convergence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** One sequential workflow avoids concurrent fixtures consuming the shared server cap. */
public class HelperRosterPaginationGameTests {
    private static AgentMenu.Handler javaMenu(ServerPlayer owner) { return (AgentMenu.Handler)owner.containerMenu; }

    private static void javaClick(ServerPlayer owner, int slot) {
        owner.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, owner);
    }

    private static int javaButton(ServerPlayer owner, String prefix) {
        var view = javaMenu(owner).view;
        for (int slot = 0; slot < view.getContainerSize(); slot++)
            if (!view.getItem(slot).isEmpty() && view.getItem(slot).getHoverName().getString().startsWith(prefix)) return slot;
        return -1;
    }

    private static void reply(Form form, int button) {
        try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form, Integer.toString(button)); }
        catch (Exception failure) { throw new IllegalStateException("Could not dispatch native form reply", failure); }
    }

    private static final class Touch implements Predicate<Form> {
        final List<SimpleForm> forms = new ArrayList<>();
        @Override public boolean test(Form form) {
            var encoded = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            var wire = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
            if (!wire.get("type").getAsString().equals("form")) throw new IllegalStateException("Expected a native touch form");
            forms.add((SimpleForm)form); return true;
        }
        SimpleForm last() { return forms.getLast(); }
        int button(String prefix) {
            var buttons = last().buttons();
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).text().startsWith(prefix)) return i;
            return -1;
        }
        void click(String prefix) {
            int index = button(prefix);
            if (index < 0) throw new IllegalStateException("No native button " + prefix + " in " + last().title());
            reply(last(), index);
        }
    }

    private static void lastTouchPage(Touch touch) {
        for (int page = 0; page < 20; page++) {
            if (touch.button("Next page") < 0) return;
            touch.click("Next page");
        }
        throw new IllegalStateException("Native helper pages did not terminate");
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void fiftySavedHelpersRemainReachableAndSafeThroughBothRealMenus(GameTestHelper c) {
        var owner = new ModeGameTests().player(c, "fifty-helper-owner");
        OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
        owner.setGameMode(GameType.SURVIVAL); owner.closeContainer(); owner.getInventory().clearContent();
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        int proposals = AgentActions.get(helpers.server).data.proposals.size();
        var feet = c.absolutePos(new BlockPos(20, 55, 20));
        for (var at : BlockPos.betweenClosed(feet.offset(-15, -1, -15), feet.offset(15, 4, 15)))
            c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        owner.setPos(Vec3.atBottomCenterOf(feet));
        var gift = new ItemStack(Items.DIAMOND, 7);
        gift.set(DataComponents.CUSTOM_NAME, Component.literal("Keep my inventory")); owner.getInventory().setItem(0, gift.copy());
        var touch = new Touch();
        try {
            c.assertValueEqual(AgentCompanions.LIMIT, 50, "The owner can save fifty helpers");
            c.assertValueEqual(AgentCompanions.GLOBAL_LIMIT, 50, "The server retains its explicit fifty-helper cap");
            AgentMenu.open(owner);
            c.assertValueEqual(helpers.count(owner), 0, "Opening the empty roster creates nothing");
            var emptyWindow = javaMenu(owner);
            javaClick(owner, javaButton(owner, "Create your first helper"));
            c.assertValueEqual(helpers.count(owner), 1, "Only an explicit create click summons the first helper");
            var first = helpers.owned(owner, "helper-1");
            var firstEntity = helpers.loaded.get(UUID.fromString(first.getKey()));
            firstEntity.setPos(Vec3.atBottomCenterOf(feet).add(-13, 0, -12));
            for (int index = 2; index <= 50; index++) {
                String name = String.format(java.util.Locale.ROOT, "squad-%02d", index);
                c.assertValueEqual(helpers.spawn(owner, name), 1, "A real helper fills slot " + index);
                var entity = helpers.loaded.get(UUID.fromString(helpers.owned(owner, name).getKey()));
                int place = index - 1;
                entity.setPos(Vec3.atBottomCenterOf(feet).add(-13 + (place % 10) * 3, 0, -12 + (place / 10) * 3));
            }
            c.assertValueEqual(helpers.count(owner), 50, "All fifty real helper identities exist");
            Set<String> ids = Set.copyOf(helpers.data.agents.keySet());
            helpers.spawn(owner, "overflow");
            c.assertTrue(helpers.owned(owner, "overflow") == null && helpers.count(owner) == 50, "An explicit fifty-first spawn is refused");
            var restored = AgentCompanions.read(helpers.file);
            c.assertTrue(restored.agents.equals(helpers.data.agents), "Native roster loading resumes every saved UUID and record at capacity");
            var last = helpers.owned(owner, "squad-50"); String lastId = last.getKey();
            var lastEntity = helpers.loaded.get(UUID.fromString(lastId));
            lastEntity.setHealth(37); lastEntity.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);

            AgentMenu.open(owner); var firstPage = javaMenu(owner);
            Set<String> javaIds = new HashSet<>();
            for (int page = 0; page < 20; page++) {
                var view = javaMenu(owner); javaIds.addAll(view.choices.values());
                c.assertTrue(view.choices.size() <= 6, "The chest keeps six distinct helper buttons and separate controls");
                int next = javaButton(owner, "Next page");
                if (next < 0) break;
                javaClick(owner, next);
            }
            c.assertTrue(javaIds.equals(ids), "Next-page inputs expose all fifty exact helper UUIDs without duplicates or omissions");
            var finalPage = javaMenu(owner);
            firstPage.clicked(AgentMenu.helperSlot(0), 0, ContainerInput.PICKUP, owner);
            emptyWindow.clicked(AgentMenu.helperSlot(0), 0, ContainerInput.PICKUP, owner);
            c.assertTrue(owner.containerMenu == finalPage && helpers.count(owner) == 50, "Delayed old-page and old-create packets cannot affect the current page");
            int lastSlot = finalPage.choices.entrySet().stream().filter(e -> e.getValue().equals(lastId)).mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            javaClick(owner, lastSlot); c.assertValueEqual(javaMenu(owner).helperId, lastId, "The final page selects the exact fiftieth helper");
            javaClick(owner, AgentMenu.STAY); javaClick(owner, AgentMenu.profileSlot(AgentCompanions.Profile.DEBUG.ordinal()));
            var javaPosition = lastEntity.position();
            c.assertTrue(helpers.data.agents.get(lastId).profile() == AgentCompanions.Profile.DEBUG
                && helpers.data.agents.get(lastId).mode() == AgentCompanions.Mode.STAY, "The helper begins passive and paused");
            javaClick(owner, javaButton(owner, "Enable combos and follow me"));
            c.assertTrue(helpers.data.agents.get(lastId).profile() == AgentCompanions.Profile.ULTIMATE_FINALS
                && helpers.data.agents.get(lastId).mode() == AgentCompanions.Mode.FOLLOW, "The explicit Java button enables actual combos and Follow together");
            c.assertTrue(helpers.loaded.get(UUID.fromString(lastId)) == lastEntity && lastEntity.position().equals(javaPosition)
                && lastEntity.getHealth() == 37 && lastEntity.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() == .31,
                "Enabling combos preserves the selected UUID, entity, location, health and edited speed");
            javaClick(owner, AgentMenu.BACK);
            c.assertTrue(javaMenu(owner).choices.containsValue(lastId), "Back returns to the final roster page");
            var staleRoster = javaMenu(owner); helpers.mode(owner, "squad-50", AgentCompanions.Mode.STAY);
            staleRoster.clicked(lastSlot, 0, ContainerInput.PICKUP, owner);
            c.assertTrue(javaMenu(owner).page == AgentMenu.Page.ROSTER && helpers.data.agents.get(lastId).mode() == AgentCompanions.Mode.STAY,
                "A changed roster requires a fresh choice instead of using stale final-page state");
            owner.closeContainer();

            BedrockAgentMenu.open(owner, touch);
            c.assertTrue(touch.button("Create") < 0, "The full native roster cannot offer another create action");
            Set<String> touchNames = new HashSet<>();
            var oldTouchPage = touch.last(); int oldNext = touch.button("Next page");
            for (int page = 0; page < 20; page++) {
                for (var button : touch.last().buttons()) for (var agent : helpers.data.agents.values())
                    if (button.text().startsWith(agent.name() + " •")) touchNames.add(agent.name());
                c.assertTrue(touch.last().buttons().size() <= 12, "Native roster pages keep a short touch-friendly list");
                if (touch.button("Next page") < 0) break;
                touch.click("Next page");
            }
            c.assertValueEqual(touchNames.size(), 50, "Encoded native form navigation exposes all fifty saved names");
            int screens = touch.forms.size(); reply(oldTouchPage, oldNext);
            c.assertValueEqual(touch.forms.size(), screens, "A replayed old page response cannot reopen another form");
            touch.click("squad-50 •"); touch.click("Stay / pause"); touch.click("Choose profile"); touch.click("Choose: Debug");
            var touchPosition = lastEntity.position();
            c.assertTrue(helpers.data.agents.get(lastId).profile() == AgentCompanions.Profile.DEBUG
                && helpers.data.agents.get(lastId).mode() == AgentCompanions.Mode.STAY, "Native controls begin with a passive Stay helper");
            touch.click("Enable combos and follow me");
            c.assertTrue(helpers.data.agents.get(lastId).profile() == AgentCompanions.Profile.ULTIMATE_FINALS
                && helpers.data.agents.get(lastId).mode() == AgentCompanions.Mode.FOLLOW, "An encoded native button enables actual combos and Follow together");
            c.assertTrue(helpers.loaded.get(UUID.fromString(lastId)) == lastEntity && lastEntity.position().equals(touchPosition)
                && lastEntity.getHealth() == 37 && lastEntity.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() == .31,
                "Native combo enabling preserves the existing identity, position and edited stats");
            touch.click("Choose profile"); touch.click("Choose: Regular");
            c.assertTrue(helpers.data.agents.get(lastId).profile() == AgentCompanions.Profile.REGULAR
                && helpers.data.agents.get(lastId).mode() == AgentCompanions.Mode.FOLLOW, "Real native touch callbacks update the fiftieth helper");
            touch.click("Back to your squad"); c.assertTrue(touch.button("squad-50 •") >= 0, "Native Back preserves the final page");
            helpers.mode(owner, "squad-50", AgentCompanions.Mode.STAY); touch.click("squad-50 •");
            c.assertValueEqual(touch.last().title(), "Helper or order changed", "A stale native roster response offers recovery without action");
            touch.click("Reopen AI Helpers"); lastTouchPage(touch); touch.click("squad-50 •");
            var oldDetail = touch.last(); int follow = touch.button("Follow me");
            helpers.unload(lastEntity); reply(oldDetail, follow);
            c.assertTrue(helpers.loaded.get(UUID.fromString(lastId)) == null && helpers.count(owner) == 50,
                "An unloaded fiftieth helper remains saved and cannot receive a stale loaded-entity action");
            c.assertValueEqual(helpers.data.agents.get(lastId).mode(), AgentCompanions.Mode.STAY, "Stale native Follow never changes an unloaded helper");
            helpers.load(lastEntity);
            c.assertTrue(helpers.loaded.get(UUID.fromString(lastId)) == lastEntity, "Loading resumes the same fiftieth entity identity");
            c.assertValueEqual(lastEntity.getHealth(), 37f, "Paging, actions and resume preserve edited health");
            c.assertValueEqual(lastEntity.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(), .31d, "Paging and resume preserve edited movement stats");
            c.assertTrue(helpers.data.agents.keySet().equals(ids), "No page, stale input or resume replaces a saved helper UUID");
            c.assertTrue(AgentActions.get(helpers.server).data.proposals.size() == proposals
                && !helpers.playerTargets.containsKey(owner.getUUID()), "Combo enabling never creates a target order or supplies an approval");
            c.assertTrue(ItemStack.isSameItemSameComponents(gift, owner.getInventory().getItem(0))
                && owner.getInventory().getItem(0).getCount() == 7 && owner.inventoryMenu.getCarried().isEmpty(),
                "No Java or native control leaks icons or changes the owner's inventory");
        } finally {
            HelperForms.ACTIVE.remove(owner.getUUID()); HelperForms.DELIVERIES.remove(owner.getUUID());
            owner.containerMenu.setCarried(ItemStack.EMPTY); owner.closeContainer();
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            for (var name : helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID())).map(AgentCompanions.Agent::name).toList()) helpers.dismiss(owner, name);
            OperatorGameTests.deop(owner); helpers.server.getPlayerList().remove(owner);
        }
        c.succeed();
    }
}

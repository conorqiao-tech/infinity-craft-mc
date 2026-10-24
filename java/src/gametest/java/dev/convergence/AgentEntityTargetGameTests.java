package dev.convergence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.impl.FormDefinitions;

/** Exact chosen mobs can be attacked; ordinary animals, stale reviews and players retain their gates. */
public class AgentEntityTargetGameTests {
    private static final class Fixture implements AutoCloseable {
        final AgentActionsGameTests.Fixture live;
        final AgentActions priorQueue;
        final AgentEntityTargets priorTargets, targets;
        final List<LivingEntity> creatures = new ArrayList<>();
        final List<Form> forms = new ArrayList<>();
        final Predicate<Form> sender = form -> { encoded(form); forms.add(form); return true; };
        Fixture(GameTestHelper c, String name) throws Exception {
            live = new AgentActionsGameTests().new Fixture(c, name);
            priorQueue = AgentActions.INSTANCES.put(live.core.server, live.queue);
            targets = new AgentEntityTargets(live.core.server, live.clock);
            priorTargets = AgentEntityTargets.INSTANCES.put(live.core.server, targets);
        }
        <T extends Mob> T mob(net.minecraft.world.entity.EntityType<T> type, String name, double x, double z) {
            var mob = type.create(live.cWorld(), EntitySpawnReason.COMMAND);
            mob.setNoAi(true); mob.setPersistenceRequired(); mob.setCustomName(Component.literal(name));
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
            mob.setHealth(100);
            mob.setPos(live.helper.position().add(x, 0, z)); live.cWorld().addFreshEntity(mob); creatures.add(mob); return mob;
        }
        Form last() { return forms.getLast(); }
        public void close() throws Exception {
            AgentEntityTargets.clear(live.owner);
            for (var creature : creatures) creature.discard();
            HelperForms.ACTIVE.remove(live.owner.getUUID()); HelperForms.DELIVERIES.remove(live.owner.getUUID());
            live.owner.closeContainer(); live.owner.inventoryMenu.setCarried(ItemStack.EMPTY);
            OperatorGameTests.level(live.owner, LevelBasedPermissionSet.OWNER);
            try { live.close(); }
            finally {
                if (priorQueue == null) AgentActions.INSTANCES.remove(live.core.server, live.queue);
                else AgentActions.INSTANCES.put(live.core.server, priorQueue);
                if (priorTargets == null) AgentEntityTargets.INSTANCES.remove(live.core.server, targets);
                else AgentEntityTargets.INSTANCES.put(live.core.server, priorTargets);
            }
        }
    }

    private static JsonObject encoded(Form form) {
        return JsonParser.parseString(FormDefinitions.instance().definitionFor(form).codec().jsonData(form)).getAsJsonObject();
    }
    private static void click(Form form, String prefix) {
        var buttons = encoded(form).getAsJsonArray("buttons");
        for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).getAsJsonObject().get("text").getAsString().startsWith(prefix)) {
            try { FormDefinitions.instance().definitionFor(form).handleFormResponse(form, Integer.toString(i)); return; }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }
        throw new IllegalStateException("Missing native target button: " + prefix);
    }
    private static void javaClick(Fixture f, int slot) {
        var menu = f.live.owner.containerMenu;
        menu.clicked(slot, 0, ContainerInput.PICKUP, f.live.owner);
    }

    @GameTest public void exactAnimalAndPetOrdersPermitNativeDamageWithoutBroadProtectionBypass(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "mobchosen")) {
            var cow = f.mob(EntityTypes.COW, "chosen-cow", 1.1, 0);
            var other = f.mob(EntityTypes.COW, "protected-cow", 1.1, .5);
            var pet = f.mob(EntityTypes.WOLF, "chosen-pet", 1.1, -.5); pet.tame(f.live.owner);
            // A selected victim must survive the new 100-base native helper hit
            // so the following observations can still inspect its live target
            // identity and revoke its permission. Taming changes wolf vitals,
            // so establish this finite test capacity after the native tame call.
            for (var selected : List.of(cow, pet)) {
                selected.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
                selected.setHealth(1000);
            }
            float cowHealth = cow.getHealth(), otherHealth = other.getHealth(), petHealth = pet.getHealth();
            f.live.control();
            c.assertTrue(f.live.helper.getTarget() == null, "Ordinary defense does not acquire animals or pets");
            c.assertFalse(f.live.helper.doHurtTarget(c.getLevel(), cow), "Unselected animal remains protected");
            c.assertTrue(AgentEntityTargets.select(f.live.owner, cow), "Owner can deliberately choose one exact ordinary animal");
            f.live.core.control(f.live.helper, f.live.core.owned(f.live.owner, "fighter").getValue(), 20);
            c.assertTrue(f.live.helper.getTarget() == cow && cow.getHealth() < cowHealth, "Normal helper native melee damages the chosen animal");
            c.assertTrue(cow.isAlive() && cow.getLastDamageSource() != null && cow.getLastDamageSource().getEntity() == f.live.helper,
                "The selected animal survives actual native helper damage for the remaining exact-permission observations");
            c.assertFalse(f.live.helper.doHurtTarget(c.getLevel(), other), "Another animal cannot inherit a chosen target's damage permission");
            c.assertFalse(f.live.helper.doHurtTarget(c.getLevel(), pet), "An unselected pet remains protected");
            c.assertValueEqual(other.getHealth(), otherHealth, "Incidental animal remains untouched");
            c.assertValueEqual(pet.getHealth(), petHealth, "Incidental pet remains untouched");
            c.assertTrue(AgentEntityTargets.select(f.live.owner, pet), "Explicit pet selection is distinct from automatic acquisition");
            f.live.core.control(f.live.helper, f.live.core.owned(f.live.owner, "fighter").getValue(), 40);
            c.assertTrue(f.live.helper.getTarget() == pet && pet.getHealth() < petHealth, "The deliberately selected pet is a native combat target");
            c.assertTrue(pet.isAlive() && pet.getLastDamageSource() != null && pet.getLastDamageSource().getEntity() == f.live.helper,
                "The selected pet survives actual native helper damage before the explicit ceasefire check");
            f.live.core.ceasefire(f.live.owner);
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null && f.live.helper.getTarget() == null, "Ceasefire clears the exact order and current attack");
            c.assertFalse(AgentCompanions.allowDamage(pet, pet.damageSources().mobAttack(f.live.helper)), "Permission closes immediately after ceasefire");
        }
        c.succeed();
    }

    @GameTest public void expiryStatusReadAndUnloadedIdentityNeverApproveAReplacement(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "mobexpiry")) {
            var cow = f.mob(EntityTypes.COW, "same-name", 1, 0);
            c.assertTrue(AgentEntityTargets.select(f.live.owner, cow), "Exact order can be created");
            var order = f.targets.orders.get(f.live.owner.getUUID());
            f.live.clock.advance(AgentEntityTargets.LIFETIME_MS);
            c.assertTrue(AgentEntityTargets.peek(f.live.owner) == null && AgentEntityTargets.summary(f.live.owner).contains("none"), "Expired status is unavailable");
            c.assertTrue(f.targets.orders.get(f.live.owner.getUUID()) == order, "Read-only status does not revoke or rewrite the order");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null, "Combat validation retires the expired order");
            c.assertTrue(AgentEntityTargets.select(f.live.owner, cow), "A fresh explicit selection after expiry is available");
            cow.discard(); var replacement = f.mob(EntityTypes.COW, "same-name", 1, 0);
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null && !AgentEntityTargets.matches(f.live.owner, replacement), "A replacement with the same name never inherits the target order");
            c.assertFalse(AgentEntityTargets.select(f.live.owner, f.live.helper), "Registered helpers remain excluded");
            c.assertFalse(AgentEntityTargets.select(f.live.owner, f.live.target), "Players cannot enter the mob-only direct order path");
        }
        c.succeed();
    }

    @GameTest public void nativeTouchAnimalReviewRequiresFreshExactConfirmationAndCannotReplay(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "mobtouch")) {
            var cow = f.mob(EntityTypes.COW, "touch-cow", 1, 0);
            f.live.owner.getInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            AgentEntityTargetMenu.openBedrock(f.live.owner, f.sender);
            click(f.last(), "Choose: touch-cow"); var review = f.last();
            String text = encoded(review).get("content").getAsString();
            c.assertTrue(text.contains(cow.getStringUUID()) && text.contains("minecraft:cow") && text.contains("Position when selected"), "Native review shows exact UUID, type, world and captured location");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null, "Opening a target review starts no attack");
            click(review, "Confirm this mob target");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == cow, "The actual encoded confirmation chooses only the exact reviewed animal");
            AgentEntityTargets.clear(f.live.owner); click(review, "Confirm this mob target");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null, "Consumed native form cannot replay an attack order");
            c.assertValueEqual(f.live.owner.getInventory().getItem(0).getCount(), 7, "Native forms preserve real inventory");
            AgentEntityTargetMenu.openBedrock(f.live.owner, f.sender); click(f.last(), "Choose: touch-cow"); var stale = f.last();
            cow.discard(); click(stale, "Confirm this mob target");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null, "Removed targets invalidate the exact native review");
        }
        c.succeed();
    }

    @GameTest public void expiredMobStatusGetterChainDoesNotMutateOrdersWeaponsTargetsOrFlight(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "mobstatus")) {
            var cow = f.mob(EntityTypes.COW, "status-cow", 1, 0);
            f.live.core.profile(f.live.owner, "fighter", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertTrue(AgentEntityTargets.select(f.live.owner, cow), "Exact mob order is active before the status fixture");
            var agent = f.live.core.owned(f.live.owner, "fighter").getValue();
            f.live.helper.setTarget(cow);
            AgentWeapons.spear(f.live.helper, cow);
            var flight = new AgentCompanions.Aerial(agent, cow, f.live.helper.position(), f.live.core.server.getTickCount());
            f.live.core.aerial.put(f.live.helper.getUUID(), flight);
            var order = f.targets.orders.get(f.live.owner.getUUID());
            var held = f.live.helper.getMainHandItem().copy();
            int useTicks = f.live.helper.getTicksUsingItem();
            float cowHealth = cow.getHealth(), helperHealth = f.live.helper.getHealth();
            f.live.clock.advance(AgentEntityTargets.LIFETIME_MS);
            // The actual status renderer calls native Mob.getTarget, which in
            // turn reaches the helper's canAttack target-permission mixin.
            String status = HelperCombatStatus.summary(f.live.owner, f.live.helper.getStringUUID(), agent);
            c.assertTrue(status.contains("Chosen mob target expired or changed"), "Actual helper status explains the expired non-player target");
            c.assertTrue(f.targets.orders.get(f.live.owner.getUUID()) == order, "Native getter validation during status never removes the expired order");
            c.assertTrue(f.live.core.aerial.get(f.live.helper.getUUID()) == flight && f.live.helper.getTarget() == cow,
                "Status leaves the captured target and aerial sequence intact until normal combat validation");
            c.assertTrue(ItemStack.isSameItemSameComponents(f.live.helper.getMainHandItem(), held)
                && f.live.helper.isUsingItem() && f.live.helper.getTicksUsingItem() == useTicks,
                "Status neither changes the held weapon nor resets native charging");
            c.assertValueEqual(cow.getHealth(), cowHealth, "Status applies no victim damage");
            c.assertValueEqual(f.live.helper.getHealth(), helperHealth, "Status applies no helper damage or healing");
            c.assertFalse(AgentCompanions.allowDamage(cow, cow.damageSources().mobAttack(f.live.helper)), "Expired order still cannot authorize actual damage");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null, "The normal mutating combat check may retire the expired order");
        }
        c.succeed();
    }

    @GameTest public void nativeTouchPlayerSelectionStillNeedsBothExistingApprovalGates(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "mobplayer")) {
            AgentEntityTargetMenu.openBedrock(f.live.owner, f.sender);
            click(f.last(), "Choose: " + f.live.target.getName().getString());
            click(f.last(), "Propose this player");
            var entry = f.live.queue.data.proposals.entrySet().stream().filter(e -> e.getValue().active()).findFirst().orElseThrow();
            c.assertValueEqual(entry.getValue().state, AgentActions.State.PENDING, "Generic living-target picker creates only a pending player proposal");
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null && f.live.core.validPlayerTarget(f.live.owner) == null, "Neither mob permission nor player combat activates from choosing a player");
            f.live.queue.ownerApprove(f.live.owner, entry.getKey());
            c.assertTrue(f.live.core.validPlayerTarget(f.live.owner) == null, "Owner approval alone remains insufficient");
            f.live.queue.codexApprove(f.live.owner.createCommandSourceStack(), entry.getKey());
            c.assertTrue(f.live.core.validPlayerTarget(f.live.owner) == null, "An in-game owner cannot supply the separate console gate");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), entry.getKey());
            c.assertTrue(f.live.core.validPlayerTarget(f.live.owner).target() == f.live.target, "Both existing gates activate exactly the selected live player");
        }
        c.succeed();
    }

    @GameTest public void javaTargetPagesReachBeyondFortyFiveAndDiscardStaleEntityReview(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "mobpages")) {
            for (int i = 0; i < 50; i++) f.mob(EntityTypes.COW, "paged-creature-" + i, 1 + i % 10 * .4, -2 + i / 10 * .4);
            AgentEntityTargetMenu.open(f.live.owner);
            var first = (AgentEntityTargetMenu.Handler) f.live.owner.containerMenu;
            var wanted = f.creatures.stream().filter(e -> first.choices.values().stream().noneMatch(s -> s.target() == e)).findFirst().orElseThrow();
            int turns = 0;
            while (((AgentEntityTargetMenu.Handler) f.live.owner.containerMenu).choices.values().stream().noneMatch(s -> s.target() == wanted)) {
                c.assertTrue(turns++ < 12, "Pagination must reach every listed nearby target"); javaClick(f, AgentEntityTargetMenu.NEXT);
            }
            var page = (AgentEntityTargetMenu.Handler) f.live.owner.containerMenu;
            int slot = page.choices.entrySet().stream().filter(e -> e.getValue().target() == wanted).mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            javaClick(f, slot);
            var review = (AgentEntityTargetMenu.Handler) f.live.owner.containerMenu;
            c.assertTrue(review.selected.target() == wanted && AgentEntityTargets.current(f.live.owner) == null, "Java page selection captures the exact target without attacking");
            wanted.discard(); javaClick(f, AgentEntityTargetMenu.CONFIRM);
            c.assertTrue(AgentEntityTargets.current(f.live.owner) == null, "Removed target cannot be confirmed from an old Java screen");
            c.assertTrue(f.live.owner.containerMenu.getCarried().isEmpty(), "Java target icons never enter the cursor");
        }
        c.succeed();
    }
}

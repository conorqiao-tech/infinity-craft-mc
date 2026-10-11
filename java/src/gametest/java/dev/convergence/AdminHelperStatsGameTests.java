package dev.convergence;

import net.minecraft.world.entity.EntityTypes;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Registered helper edits use real entity attributes without granting a combat order. */
public class AdminHelperStatsGameTests {
    private ServerPlayer owner(GameTestHelper c, String name) {
        var p = new ModeGameTests().player(c, name);
        OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
        p.setGameMode(GameType.SURVIVAL);
        var feet = c.absolutePos(new BlockPos(3, 20, 3));
        for (var pos : BlockPos.betweenClosed(feet.offset(-7, -1, -7), feet.offset(7, 4, 7)))
            c.getLevel().setBlockAndUpdate(pos, pos.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        p.setPos(Vec3.atBottomCenterOf(feet));
        p.setNoGravity(true);
        return p;
    }
    private IronGolem helper(GameTestHelper c, ServerPlayer p, String name) {
        var agents = AgentCompanions.get(c.getLevel().getServer());
        agents.spawn(p, name);
        var record = agents.owned(p, name);
        c.assertTrue(record != null, "Real helper creation registers an ownership record");
        var helper = agents.loaded.get(UUID.fromString(record.getKey()));
        c.assertTrue(helper != null && AdminStats.helper(helper), "Real helper is the exact registered loaded entity");
        return helper;
    }
    private void cleanup(ServerPlayer p) {
        var server = p.level().getServer();
        var agents = AgentCompanions.get(server);
        // Some permission tests intentionally deop the owner before cleanup.
        OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
        p.closeContainer();
        agents.ceasefire(p);
        var names = agents.data.agents.values().stream().filter(record -> record.owner().equals(p.getStringUUID())).map(AgentCompanions.Agent::name).toList();
        for (var name : names) agents.dismiss(p, name);
        OperatorGameTests.deop(p);
        server.getPlayerList().remove(p);
    }
    private void set(GameTestHelper c, ServerPlayer actor, IronGolem helper, String id, double value) {
        var result = AdminStats.set(actor.createCommandSourceStack(), helper, id, value);
        c.assertTrue(result.success(), "Edit helper " + id + ": " + result.message());
    }
    private CompoundTag save(IronGolem helper) {
        var writer = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.registryAccess());
        helper.saveWithoutId(writer);
        return writer.buildResult();
    }
    private void read(IronGolem helper, CompoundTag data) {
        helper.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.registryAccess(), data));
    }
    private void click(ServerPlayer owner, int slot) {
        owner.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, owner);
    }

    @GameTest public void helperEditorChangesActualHealthCombatAndMovementAttributes(GameTestHelper c) {
        var owner = owner(c, "helper-stat-owner");
        var agents = AgentCompanions.get(c.getLevel().getServer());
        try {
            var helper = helper(c, owner, "editable");
            set(c, owner, helper, "max_health", 600);
            set(c, owner, helper, "health", 450);
            set(c, owner, helper, "attack_damage", 80);
            set(c, owner, helper, "movement_speed", .75);
            set(c, owner, helper, "max_absorption", 40);
            set(c, owner, helper, "absorption", 35);
            c.assertValueEqual(helper.getMaxHealth(), 600f, "Real helper maximum changes");
            c.assertValueEqual(helper.getHealth(), 450f, "Real helper health changes");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 80d, "Real attack damage changes");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), .75d, "Real navigation attribute changes");
            c.assertValueEqual(helper.getAbsorptionAmount(), 35f, "Real helper absorption changes");
            for (var profile : AgentCompanions.Profile.values()) {
                agents.profile(owner, "editable", profile);
                agents.mode(owner, "editable", AgentCompanions.Mode.STAY);
                AgentCompanions.prepare(helper);
                agents.control(helper, agents.owned(owner, "editable").getValue(), 20);
                c.assertValueEqual(helper.getMaxHealth(), 600f, "Prepare/control preserves health attribute in " + profile);
                c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 80d, "Profile does not overwrite edited damage");
                c.assertValueEqual(helper.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), .75d, "Profile does not overwrite edited speed");
            }
            set(c, owner, helper, "max_health", 120);
            c.assertValueEqual(helper.getHealth(), 120f, "Lower maximum immediately clamps actual helper health");
            set(c, owner, helper, "health", 0);
            c.assertFalse(helper.isAlive(), "Zero health performs an explicit helper death");
            c.assertFalse(agents.data.agents.containsKey(helper.getStringUUID()), "Helper death removes roster ownership through the normal death event");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void directHelperAbsorptionSurvivesNativeReloadAndProfileChange(GameTestHelper c) {
        var owner=owner(c,"helper-auto-reserve");
        try {
            var helper=helper(c,owner,"reserve");
            set(c,owner,helper,"absorption",5000);
            c.assertValueEqual(helper.getMaxAbsorption(),5000f,"One helper edit raises the real capacity");
            var saved=save(helper);
            c.assertTrue(AdminStats.resetAll(owner.createCommandSourceStack(),helper).success(),"Helper reset clears expanded capacity");
            read(helper,saved);
            c.assertValueEqual(helper.getAbsorptionAmount(),5000f,"Helper native reload preserves high absorption");
            var agents=AgentCompanions.get(c.getLevel().getServer());
            agents.profile(owner,"reserve",AgentCompanions.Profile.ULTIMATE_FINALS);
            AgentCompanions.prepare(helper);
            c.assertValueEqual(helper.getMaxAbsorption(),5000f,"Helper profile changes keep the capacity");
            c.assertValueEqual(helper.getAbsorptionAmount(),5000f,"Helper profile changes do not clear the reserve");
        } finally {cleanup(owner);}
        c.succeed();
    }

    @GameTest public void helperEditingRequiresActualOp4ButCanEditAnotherOwnersHelper(GameTestHelper c) {
        var owner = owner(c, "helper-owning-op");
        var actor = owner(c, "helper-editing-op");
        try {
            var helper = helper(c, owner, "other-owner");
            set(c, actor, helper, "max_health", 180);
            c.assertValueEqual(helper.getMaxHealth(), 180f, "An administrator may edit another owner's registered helper");
            var cached = actor.createCommandSourceStack();
            OperatorGameTests.deop(actor);
            c.assertFalse(AdminStats.set(cached, helper, "max_health", 500).success(), "A cached editor source is invalid after deop");
            c.assertFalse(AdminStats.set(actor.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS), helper, "max_health", 500).success(), "Forged source permission cannot replace real OP4");
            c.assertFalse(AdminStats.resetAll(actor.createCommandSourceStack(), helper).success(), "Reset requires the same actual authorization");
            c.assertValueEqual(helper.getMaxHealth(), 180f, "Rejected edits preserve the actual helper value");
            c.assertValueEqual(AdminStats.original(helper, AdminStats.find(helper, "max_health")), 100d, "Rejected edits preserve original reset history");
        } finally { cleanup(actor); cleanup(owner); }
        c.succeed();
    }

    @GameTest public void unregisteredUnloadedDismissedAndStaleHelperObjectsAreRejected(GameTestHelper c) {
        var owner = owner(c, "helper-identity-op");
        var agents = AgentCompanions.get(c.getLevel().getServer());
        var ordinary = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
        IronGolem copy = null;
        try {
            var helper = helper(c, owner, "identity");
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), ordinary, "max_health", 400).success(), "Ordinary golems are outside the AI editor");
            ordinary.addTag(AgentCompanions.TAG);
            agents.loaded.put(ordinary.getUUID(), ordinary);
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), ordinary, "max_health", 400).success(), "A tag and loaded entry without roster ownership do not forge a helper");
            agents.loaded.remove(ordinary.getUUID());
            copy = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.LOAD);
            read(copy, save(helper));
            c.assertValueEqual(copy.getUUID(), helper.getUUID(), "Stale copy has the same persistent UUID");
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), copy, "max_health", 400).success(), "Matching UUID cannot replace exact loaded entity identity");
            agents.loaded.remove(helper.getUUID());
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), helper, "max_health", 400).success(), "An unloaded registered helper cannot be edited");
            agents.loaded.put(helper.getUUID(), helper);
            set(c, owner, helper, "max_health", 160);
            agents.dismiss(owner, "identity");
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), helper, "max_health", 400).success(), "Dismissed helper cannot be edited");
            c.assertFalse(AdminStats.reset(owner.createCommandSourceStack(), helper, "max_health").success(), "Dismissal also invalidates reset");
        } finally {
            agents.loaded.remove(ordinary.getUUID());
            ordinary.discard();
            if (copy != null) copy.discard();
            cleanup(owner);
        }
        c.succeed();
    }

    @GameTest public void helperEntityNbtPreservesOriginalsAttributesAndExternalModifiers(GameTestHelper c) {
        var owner = owner(c, "helper-save-op");
        var agents = AgentCompanions.get(c.getLevel().getServer());
        try {
            var helper = helper(c, owner, "saved");
            var externalId = Identifier.fromNamespaceAndPath("infinity_test", "helper_extra_damage");
            helper.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(19);
            helper.getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(new AttributeModifier(externalId, 5, AttributeModifier.Operation.ADD_VALUE));
            set(c, owner, helper, "attack_damage", 45);
            set(c, owner, helper, "attack_damage", 70);
            set(c, owner, helper, "max_health", 5000);
            set(c, owner, helper, "health", 4500);
            var stored = save(helper);
            c.assertTrue(stored.contains("InfinityAdminStats"), "Originals are saved in the same entity NBT as vanilla attributes");
            c.assertTrue(AdminStats.resetAll(owner.createCommandSourceStack(), helper).success(), "Reset deliberately changes the live entity before read");
            read(helper, stored);
            agents.load(helper);
            c.assertValueEqual(helper.getMaxHealth(), 5000f, "Native entity reload preserves the expanded effective maximum");
            c.assertValueEqual(helper.getHealth(), 4500f, "Native entity reload preserves health above the normal attribute cap");
            c.assertValueEqual(helper.getAttributeValue(Attributes.ATTACK_DAMAGE), 75d, "Reload preserves edited base and independent modifier");
            c.assertValueEqual(AdminStats.original(helper, AdminStats.find(helper, "attack_damage")), 19d, "First original persists across repeated edits and reload");
            c.assertTrue(AdminStats.resetAll(owner.createCommandSourceStack(), helper).success(), "Reloaded entity can restore originals");
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 19d, "Reset restores the original custom base");
            c.assertValueEqual(helper.getAttributeValue(Attributes.ATTACK_DAMAGE), 24d, "Reset leaves the independent modifier in place");
            c.assertValueEqual(helper.getMaxHealth(), 100f, "Reset restores the golem's original maximum");
            c.assertValueEqual(helper.getHealth(), 100f, "Reset clamps current health to the restored maximum");
            c.assertFalse(save(helper).contains("InfinityAdminStats"), "Reset removes obsolete entity undo data");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void helperStatListOmitsPlayerOnlyVitalsAndRejectsTheirCommands(GameTestHelper c) {
        var owner = owner(c, "helper-fields-op");
        try {
            var helper = helper(c, owner, "fields");
            var ids = AdminStats.list(helper).stream().map(AdminStats.Stat::id).toList();
            for (var id : List.of("health", "absorption", "max_health", "attack_damage", "movement_speed"))
                c.assertTrue(ids.contains(id), "Supported helper field is discoverable: " + id);
            for (var id : List.of("food", "saturation", "exhaustion", "xp_level")) {
                c.assertFalse(ids.contains(id), "Player-only field is omitted: " + id);
                c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), helper, id, 5).success(), "Player-only setter is rejected for a helper: " + id);
            }
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), helper, "attack_damage", Double.NaN).success(), "Non-finite helper damage is rejected");
            c.assertFalse(AdminStats.set(owner.createCommandSourceStack(), helper, "max_health", Math.nextUp((double)Float.MAX_VALUE)).success(), "Non-representable helper health is refused");
            c.assertTrue(((AdminStatEntity) helper).infinity$adminStats().isEmpty(), "Rejected fields leave no misleading reset metadata");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void editedDamageChangesRealMeleeWithoutGrantingPlayerTargetApproval(GameTestHelper c) {
        var owner = owner(c, "helper-damage-op");
        var agents = AgentCompanions.get(c.getLevel().getServer());
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var helper = helper(c, owner, "fighter");
            set(c, owner, helper, "attack_damage", 80);
            helper.setPos(owner.position().add(2, 0, 0));
            zombie.setPos(helper.position().add(1, 0, 0));
            zombie.setNoAi(true);
            zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);
            zombie.getAttribute(Attributes.ARMOR).setBaseValue(0);
            zombie.setHealth(500);
            c.getLevel().addFreshEntity(zombie);
            helper.setTarget(zombie);
            c.assertTrue(helper.doHurtTarget(c.getLevel(), zombie), "Normal approved hostile combat still performs real golem melee");
            c.assertTrue(zombie.getHealth() <= 460, "The attack consumes the edited damage attribute, not the vanilla golem damage");
            agents.profile(owner, "fighter", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(agents.playerTargets.containsKey(owner.getUUID()), "Editing combat stats creates no approved player target");
            helper.setTarget(owner);
            c.assertFalse(helper.doHurtTarget(c.getLevel(), owner), "Even edited aggressive helpers cannot damage a player without the independent target gate");
            c.assertFalse(AgentCompanions.allowDamage(owner, owner.damageSources().mobAttack(helper)), "Direct damage remains subject to player target approval");
        } finally { zombie.discard(); cleanup(owner); }
        c.succeed();
    }

    @GameTest public void helperMenuConfirmsEditsAndInvalidatesChangedProfilesOrDismissal(GameTestHelper c) {
        var owner = owner(c, "helper-menu-op");
        var agents = AgentCompanions.get(c.getLevel().getServer());
        try {
            var helper = helper(c, owner, "menu-helper");
            set(c, owner, helper, "health", 90);
            c.assertValueEqual(AdminStatsMenu.open(owner), 1, "Admin target menu opens");
            var roster = (AdminStatsMenu.Handler) owner.containerMenu;
            int index = -1;
            for (int i = 0; i < roster.targets.size(); i++) if (roster.targets.get(i).entity() == helper) index = i;
            c.assertTrue(index >= 0, "Target roster includes the exact registered helper");
            for (int page = 0; page < index / AdminStatsMenu.PAGE_SIZE; page++) click(owner, AdminStatsMenu.NEXT);
            click(owner, index % AdminStatsMenu.PAGE_SIZE);
            var selected = (AdminStatsMenu.Handler) owner.containerMenu;
            c.assertTrue(selected.targetSession.entity() == helper, "Selecting the helper retains exact entity identity");
            int health = -1;
            for (int i = 0; i < selected.stats.size(); i++) if (selected.stats.get(i).id().equals("health")) health = i;
            c.assertTrue(health >= 0, "Helper health appears in stat menu");
            click(owner, health);
            click(owner, AdminStatsMenu.MINUS_MEDIUM);
            click(owner, AdminStatsMenu.REVIEW);
            c.assertValueEqual(helper.getHealth(), 90f, "Staging and review do not apply the helper edit");
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(helper.getHealth(), 80f, "Confirmation changes the actual helper health");
            owner.closeContainer();
            double originalDamage = helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
            c.assertValueEqual(AdminStatsMenu.openStat(owner, helper, "attack_damage"), 1, "Helper damage editor opens");
            click(owner, AdminStatsMenu.PLUS_MEDIUM);
            click(owner, AdminStatsMenu.REVIEW);
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), originalDamage + 10, "Confirmed helper damage edit applies");
            owner.closeContainer();
            AdminStatsMenu.openStat(owner, helper, "attack_damage");
            click(owner, AdminStatsMenu.PLUS_MEDIUM);
            click(owner, AdminStatsMenu.REVIEW);
            var profileReview = owner.containerMenu;
            agents.profile(owner, "menu-helper", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(profileReview.stillValid(owner), "Changing the helper profile invalidates an existing review");
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(helper.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), originalDamage + 10, "Stale profile review does not apply");
            AdminStatsMenu.openStat(owner, helper, "health");
            click(owner, AdminStatsMenu.MINUS_SMALL);
            click(owner, AdminStatsMenu.REVIEW);
            var dismissalReview = owner.containerMenu;
            agents.dismiss(owner, "menu-helper");
            c.assertFalse(dismissalReview.stillValid(owner), "Dismissal invalidates a staged helper edit");
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertValueEqual(helper.getHealth(), 80f, "Dismissed entity is not changed by an old review");
        } finally { cleanup(owner); }
        c.succeed();
    }
}

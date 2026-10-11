package dev.convergence;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/** Registered helper edits use real entity attributes without granting a combat order. */
public class AdminHelperStatsGameTests {
    private ServerPlayerEntity owner(TestContext c, String name) {
        var p = new ModeGameTests().player(c, name);
        OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
        p.changeGameMode(GameMode.SURVIVAL);
        var feet = c.getAbsolutePos(new BlockPos(3, 20, 3));
        for (var pos : BlockPos.iterate(feet.add(-7, -1, -7), feet.add(7, 4, 7)))
            c.getWorld().setBlockState(pos, pos.getY() == feet.getY() - 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        p.setPosition(Vec3d.ofBottomCenter(feet));
        p.setNoGravity(true);
        return p;
    }
    private IronGolemEntity helper(TestContext c, ServerPlayerEntity p, String name) {
        var agents = AgentCompanions.get(c.getWorld().getServer());
        agents.spawn(p, name);
        var record = agents.owned(p, name);
        c.assertTrue(record != null, "Real helper creation registers an ownership record");
        var helper = agents.loaded.get(UUID.fromString(record.getKey()));
        c.assertTrue(helper != null && AdminStats.helper(helper), "Real helper is the exact registered loaded entity");
        return helper;
    }
    private void cleanup(ServerPlayerEntity p) {
        var server = p.getEntityWorld().getServer();
        var agents = AgentCompanions.get(server);
        // Some permission tests intentionally deop the owner before cleanup.
        OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
        p.closeHandledScreen();
        agents.ceasefire(p);
        var names = agents.data.agents.values().stream().filter(record -> record.owner().equals(p.getUuidAsString())).map(AgentCompanions.Agent::name).toList();
        for (var name : names) agents.dismiss(p, name);
        OperatorGameTests.deop(p);
        server.getPlayerManager().remove(p);
    }
    private void set(TestContext c, ServerPlayerEntity actor, IronGolemEntity helper, String id, double value) {
        var result = AdminStats.set(actor.getCommandSource(), helper, id, value);
        c.assertTrue(result.success(), "Edit helper " + id + ": " + result.message());
    }
    private NbtCompound save(IronGolemEntity helper) {
        var writer = NbtWriteView.create(ErrorReporter.EMPTY, helper.getRegistryManager());
        helper.writeData(writer);
        return writer.getNbt();
    }
    private void read(IronGolemEntity helper, NbtCompound data) {
        helper.readData(NbtReadView.create(ErrorReporter.EMPTY, helper.getRegistryManager(), data));
    }
    private void click(ServerPlayerEntity owner, int slot) {
        owner.currentScreenHandler.onSlotClick(slot, 0, SlotActionType.PICKUP, owner);
    }

    @GameTest public void helperEditorChangesActualHealthCombatAndMovementAttributes(TestContext c) {
        var owner = owner(c, "helper-stat-owner");
        var agents = AgentCompanions.get(c.getWorld().getServer());
        try {
            var helper = helper(c, owner, "editable");
            set(c, owner, helper, "max_health", 600);
            set(c, owner, helper, "health", 450);
            set(c, owner, helper, "attack_damage", 80);
            set(c, owner, helper, "movement_speed", .75);
            set(c, owner, helper, "max_absorption", 40);
            set(c, owner, helper, "absorption", 35);
            c.assertEquals(helper.getMaxHealth(), 600f, "Real helper maximum changes");
            c.assertEquals(helper.getHealth(), 450f, "Real helper health changes");
            c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), 80d, "Real attack damage changes");
            c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), .75d, "Real navigation attribute changes");
            c.assertEquals(helper.getAbsorptionAmount(), 35f, "Real helper absorption changes");
            for (var profile : AgentCompanions.Profile.values()) {
                agents.profile(owner, "editable", profile);
                agents.mode(owner, "editable", AgentCompanions.Mode.STAY);
                AgentCompanions.prepare(helper);
                agents.control(helper, agents.owned(owner, "editable").getValue(), 20);
                c.assertEquals(helper.getMaxHealth(), 600f, "Prepare/control preserves health attribute in " + profile);
                c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), 80d, "Profile does not overwrite edited damage");
                c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), .75d, "Profile does not overwrite edited speed");
            }
            set(c, owner, helper, "max_health", 120);
            c.assertEquals(helper.getHealth(), 120f, "Lower maximum immediately clamps actual helper health");
            set(c, owner, helper, "health", 0);
            c.assertFalse(helper.isAlive(), "Zero health performs an explicit helper death");
            c.assertFalse(agents.data.agents.containsKey(helper.getUuidAsString()), "Helper death removes roster ownership through the normal death event");
        } finally { cleanup(owner); }
        c.complete();
    }

    @GameTest public void helperEditingRequiresActualOp4ButCanEditAnotherOwnersHelper(TestContext c) {
        var owner = owner(c, "helper-owning-op");
        var actor = owner(c, "helper-editing-op");
        try {
            var helper = helper(c, owner, "other-owner");
            set(c, actor, helper, "max_health", 180);
            c.assertEquals(helper.getMaxHealth(), 180f, "An administrator may edit another owner's registered helper");
            var cached = actor.getCommandSource();
            OperatorGameTests.deop(actor);
            c.assertFalse(AdminStats.set(cached, helper, "max_health", 500).success(), "A cached editor source is invalid after deop");
            c.assertFalse(AdminStats.set(actor.getCommandSource().withPermissions(PermissionPredicate.ALL), helper, "max_health", 500).success(), "Forged source permission cannot replace real OP4");
            c.assertFalse(AdminStats.resetAll(actor.getCommandSource(), helper).success(), "Reset requires the same actual authorization");
            c.assertEquals(helper.getMaxHealth(), 180f, "Rejected edits preserve the actual helper value");
            c.assertEquals(AdminStats.original(helper, AdminStats.find(helper, "max_health")), 100d, "Rejected edits preserve original reset history");
        } finally { cleanup(actor); cleanup(owner); }
        c.complete();
    }

    @GameTest public void unregisteredUnloadedDismissedAndStaleHelperObjectsAreRejected(TestContext c) {
        var owner = owner(c, "helper-identity-op");
        var agents = AgentCompanions.get(c.getWorld().getServer());
        var ordinary = EntityType.IRON_GOLEM.create(c.getWorld(), SpawnReason.COMMAND);
        IronGolemEntity copy = null;
        try {
            var helper = helper(c, owner, "identity");
            c.assertFalse(AdminStats.set(owner.getCommandSource(), ordinary, "max_health", 400).success(), "Ordinary golems are outside the AI editor");
            ordinary.addCommandTag(AgentCompanions.TAG);
            agents.loaded.put(ordinary.getUuid(), ordinary);
            c.assertFalse(AdminStats.set(owner.getCommandSource(), ordinary, "max_health", 400).success(), "A tag and loaded entry without roster ownership do not forge a helper");
            agents.loaded.remove(ordinary.getUuid());
            copy = EntityType.IRON_GOLEM.create(c.getWorld(), SpawnReason.LOAD);
            read(copy, save(helper));
            c.assertEquals(copy.getUuid(), helper.getUuid(), "Stale copy has the same persistent UUID");
            c.assertFalse(AdminStats.set(owner.getCommandSource(), copy, "max_health", 400).success(), "Matching UUID cannot replace exact loaded entity identity");
            agents.loaded.remove(helper.getUuid());
            c.assertFalse(AdminStats.set(owner.getCommandSource(), helper, "max_health", 400).success(), "An unloaded registered helper cannot be edited");
            agents.loaded.put(helper.getUuid(), helper);
            set(c, owner, helper, "max_health", 160);
            agents.dismiss(owner, "identity");
            c.assertFalse(AdminStats.set(owner.getCommandSource(), helper, "max_health", 400).success(), "Dismissed helper cannot be edited");
            c.assertFalse(AdminStats.reset(owner.getCommandSource(), helper, "max_health").success(), "Dismissal also invalidates reset");
        } finally {
            agents.loaded.remove(ordinary.getUuid());
            ordinary.discard();
            if (copy != null) copy.discard();
            cleanup(owner);
        }
        c.complete();
    }

    @GameTest public void helperEntityNbtPreservesOriginalsAttributesAndExternalModifiers(TestContext c) {
        var owner = owner(c, "helper-save-op");
        var agents = AgentCompanions.get(c.getWorld().getServer());
        try {
            var helper = helper(c, owner, "saved");
            var externalId = Identifier.of("infinity_test", "helper_extra_damage");
            helper.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE).setBaseValue(19);
            helper.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE).addPersistentModifier(new EntityAttributeModifier(externalId, 5, EntityAttributeModifier.Operation.ADD_VALUE));
            set(c, owner, helper, "attack_damage", 45);
            set(c, owner, helper, "attack_damage", 70);
            set(c, owner, helper, "max_health", 320);
            set(c, owner, helper, "health", 280);
            var stored = save(helper);
            c.assertTrue(stored.contains("InfinityAdminStats"), "Originals are saved in the same entity NBT as vanilla attributes");
            c.assertTrue(AdminStats.resetAll(owner.getCommandSource(), helper).success(), "Reset deliberately changes the live entity before read");
            read(helper, stored);
            agents.load(helper);
            c.assertEquals(helper.getMaxHealth(), 320f, "Native entity reload preserves edited maximum");
            c.assertEquals(helper.getHealth(), 280f, "Native entity reload preserves current health");
            c.assertEquals(helper.getAttributeValue(EntityAttributes.ATTACK_DAMAGE), 75d, "Reload preserves edited base and independent modifier");
            c.assertEquals(AdminStats.original(helper, AdminStats.find(helper, "attack_damage")), 19d, "First original persists across repeated edits and reload");
            c.assertTrue(AdminStats.resetAll(owner.getCommandSource(), helper).success(), "Reloaded entity can restore originals");
            c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), 19d, "Reset restores the original custom base");
            c.assertEquals(helper.getAttributeValue(EntityAttributes.ATTACK_DAMAGE), 24d, "Reset leaves the independent modifier in place");
            c.assertEquals(helper.getMaxHealth(), 100f, "Reset restores the golem's original maximum");
            c.assertEquals(helper.getHealth(), 100f, "Reset clamps current health to the restored maximum");
            c.assertFalse(save(helper).contains("InfinityAdminStats"), "Reset removes obsolete entity undo data");
        } finally { cleanup(owner); }
        c.complete();
    }

    @GameTest public void helperStatListOmitsPlayerOnlyVitalsAndRejectsTheirCommands(TestContext c) {
        var owner = owner(c, "helper-fields-op");
        try {
            var helper = helper(c, owner, "fields");
            var ids = AdminStats.list(helper).stream().map(AdminStats.Stat::id).toList();
            for (var id : List.of("health", "absorption", "max_health", "attack_damage", "movement_speed"))
                c.assertTrue(ids.contains(id), "Supported helper field is discoverable: " + id);
            for (var id : List.of("food", "saturation", "exhaustion", "xp_level")) {
                c.assertFalse(ids.contains(id), "Player-only field is omitted: " + id);
                c.assertFalse(AdminStats.set(owner.getCommandSource(), helper, id, 5).success(), "Player-only setter is rejected for a helper: " + id);
            }
            c.assertFalse(AdminStats.set(owner.getCommandSource(), helper, "attack_damage", Double.NaN).success(), "Non-finite helper damage is rejected");
            c.assertFalse(AdminStats.set(owner.getCommandSource(), helper, "max_health", 1025).success(), "Native helper health limit remains valid");
            c.assertTrue(((AdminStatEntity) helper).infinity$adminStats().isEmpty(), "Rejected fields leave no misleading reset metadata");
        } finally { cleanup(owner); }
        c.complete();
    }

    @GameTest public void editedDamageChangesRealMeleeWithoutGrantingPlayerTargetApproval(TestContext c) {
        var owner = owner(c, "helper-damage-op");
        var agents = AgentCompanions.get(c.getWorld().getServer());
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var helper = helper(c, owner, "fighter");
            set(c, owner, helper, "attack_damage", 80);
            helper.setPosition(owner.getEntityPos().add(2, 0, 0));
            zombie.setPosition(helper.getEntityPos().add(1, 0, 0));
            zombie.setAiDisabled(true);
            zombie.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(500);
            zombie.getAttributeInstance(EntityAttributes.ARMOR).setBaseValue(0);
            zombie.setHealth(500);
            c.getWorld().spawnEntity(zombie);
            helper.setTarget(zombie);
            c.assertTrue(helper.tryAttack(c.getWorld(), zombie), "Normal approved hostile combat still performs real golem melee");
            c.assertTrue(zombie.getHealth() <= 460, "The attack consumes the edited damage attribute, not the vanilla golem damage");
            agents.profile(owner, "fighter", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(agents.playerTargets.containsKey(owner.getUuid()), "Editing combat stats creates no approved player target");
            helper.setTarget(owner);
            c.assertFalse(helper.tryAttack(c.getWorld(), owner), "Even edited aggressive helpers cannot damage a player without the independent target gate");
            c.assertFalse(AgentCompanions.allowDamage(owner, owner.getDamageSources().mobAttack(helper)), "Direct damage remains subject to player target approval");
        } finally { zombie.discard(); cleanup(owner); }
        c.complete();
    }

    @GameTest public void helperMenuConfirmsEditsAndInvalidatesChangedProfilesOrDismissal(TestContext c) {
        var owner = owner(c, "helper-menu-op");
        var agents = AgentCompanions.get(c.getWorld().getServer());
        try {
            var helper = helper(c, owner, "menu-helper");
            set(c, owner, helper, "health", 90);
            c.assertEquals(AdminStatsMenu.open(owner), 1, "Admin target menu opens");
            var roster = (AdminStatsMenu.Handler) owner.currentScreenHandler;
            int index = -1;
            for (int i = 0; i < roster.targets.size(); i++) if (roster.targets.get(i).entity() == helper) index = i;
            c.assertTrue(index >= 0, "Target roster includes the exact registered helper");
            for (int page = 0; page < index / AdminStatsMenu.PAGE_SIZE; page++) click(owner, AdminStatsMenu.NEXT);
            click(owner, index % AdminStatsMenu.PAGE_SIZE);
            var selected = (AdminStatsMenu.Handler) owner.currentScreenHandler;
            c.assertTrue(selected.targetSession.entity() == helper, "Selecting the helper retains exact entity identity");
            int health = -1;
            for (int i = 0; i < selected.stats.size(); i++) if (selected.stats.get(i).id().equals("health")) health = i;
            c.assertTrue(health >= 0, "Helper health appears in stat menu");
            click(owner, health);
            click(owner, AdminStatsMenu.MINUS_MEDIUM);
            click(owner, AdminStatsMenu.REVIEW);
            c.assertEquals(helper.getHealth(), 90f, "Staging and review do not apply the helper edit");
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertEquals(helper.getHealth(), 80f, "Confirmation changes the actual helper health");
            owner.closeHandledScreen();
            double originalDamage = helper.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE);
            c.assertEquals(AdminStatsMenu.openStat(owner, helper, "attack_damage"), 1, "Helper damage editor opens");
            click(owner, AdminStatsMenu.PLUS_MEDIUM);
            click(owner, AdminStatsMenu.REVIEW);
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), originalDamage + 10, "Confirmed helper damage edit applies");
            owner.closeHandledScreen();
            AdminStatsMenu.openStat(owner, helper, "attack_damage");
            click(owner, AdminStatsMenu.PLUS_MEDIUM);
            click(owner, AdminStatsMenu.REVIEW);
            var profileReview = owner.currentScreenHandler;
            agents.profile(owner, "menu-helper", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(profileReview.canUse(owner), "Changing the helper profile invalidates an existing review");
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertEquals(helper.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), originalDamage + 10, "Stale profile review does not apply");
            AdminStatsMenu.openStat(owner, helper, "health");
            click(owner, AdminStatsMenu.MINUS_SMALL);
            click(owner, AdminStatsMenu.REVIEW);
            var dismissalReview = owner.currentScreenHandler;
            agents.dismiss(owner, "menu-helper");
            c.assertFalse(dismissalReview.canUse(owner), "Dismissal invalidates a staged helper edit");
            click(owner, AdminStatsMenu.CONFIRM);
            c.assertEquals(helper.getHealth(), 80f, "Dismissed entity is not changed by an old review");
        } finally { cleanup(owner); }
        c.complete();
    }
}

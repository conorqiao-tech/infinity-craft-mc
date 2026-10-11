package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

/** Exercise the actual player state and persistence paths, not only the editor's metadata. */
public class AdminStatsGameTests {
    private ServerPlayerEntity player(TestContext c, String name) {
        var p = new ModeGameTests().player(c, name);
        p.changeGameMode(GameMode.SURVIVAL);
        return p;
    }
    private ServerPlayerEntity owner(TestContext c, String name) {
        var p = player(c, name);
        OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
        return p;
    }
    private void cleanup(ServerPlayerEntity p) {
        var server = p.getEntityWorld().getServer();
        p.closeHandledScreen();
        OperatorGameTests.deop(p);
        GameModes.PENDING.remove(p.getUuid());
        CommunityServer.get(server).pending.remove(p.getUuid());
        if (server.getPlayerManager().getPlayer(p.getUuid()) == p) server.getPlayerManager().remove(p);
    }
    private void set(TestContext c, ServerCommandSource actor, ServerPlayerEntity target, String id, double value) {
        var result = AdminStats.set(actor, target, id, value);
        c.assertTrue(result.success(), "Set " + id + " to " + value + ": " + result.message());
    }
    private NbtCompound save(ServerPlayerEntity p) {
        var writer = NbtWriteView.create(ErrorReporter.EMPTY, p.getRegistryManager());
        p.writeData(writer);
        return writer.getNbt();
    }
    private void read(ServerPlayerEntity p, NbtCompound saved) {
        p.readData(NbtReadView.create(ErrorReporter.EMPTY, p.getRegistryManager(), saved));
    }

    @GameTest public void adminEditsAnotherPlayersHealthFoodExperienceAndAttributes(TestContext c) {
        var admin = player(c, "stats-admin");
        var target = player(c, "stats-target");
        var memberships = Memberships.get(c.getWorld().getServer());
        try {
            c.assertTrue(memberships.grantAdmin(admin.getUuid()), "Admin role grants real OP4");
            var actor = admin.getCommandSource();
            set(c, actor, target, "max_health", 80);
            set(c, actor, target, "health", 67);
            c.assertEquals(target.getMaxHealth(), 80f, "Max health changes the actual attribute");
            c.assertEquals(target.getHealth(), 67f, "Current health changes immediately");
            set(c, actor, target, "food", 12);
            set(c, actor, target, "saturation", 8);
            c.assertEquals(target.getHungerManager().getFoodLevel(), 12, "Food changes actual hunger state");
            c.assertEquals(target.getHungerManager().getSaturationLevel(), 8f, "Saturation changes actual hunger state");
            set(c, actor, target, "xp_level", 30);
            c.assertEquals(target.experienceLevel, 30, "Experience level changes");
            c.assertEquals(target.experienceProgress, 0f, "Setting level starts a consistent new progress bar");
            c.assertEquals(target.totalExperience, 1395, "Total XP agrees with vanilla level 30");
            set(c, actor, target, "max_absorption", 24);
            set(c, actor, target, "absorption", 18);
            c.assertEquals(target.getAbsorptionAmount(), 18f, "Absorption hearts are actually granted");
            set(c, actor, target, "max_health", 25);
            c.assertEquals(target.getHealth(), 25f, "Lowering max health clamps current health immediately");
            set(c, actor, target, "attack_damage", 45);
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.ATTACK_DAMAGE), 45d, "Attack damage edits base value");
            set(c, actor, target, "health", 0);
            c.assertFalse(target.isAlive(), "Explicit zero health performs the requested player death");
        } finally {
            memberships.revokeAdmin(admin.getUuid());
            cleanup(admin);
            cleanup(target);
        }
        c.complete();
    }

    @GameTest public void editorRejectsOrdinaryRevokedAndForgedActorsWithoutMutation(TestContext c) {
        var actor = player(c, "stats-no-op");
        var target = player(c, "stats-protected");
        try {
            double original = target.getAttributeBaseValue(EntityAttributes.MAX_HEALTH);
            c.assertFalse(AdminStats.set(actor.getCommandSource(), target, "max_health", 100).success(), "Ordinary player cannot edit");
            c.assertFalse(AdminStats.set(actor.getCommandSource().withPermissions(PermissionPredicate.ALL), target, "max_health", 100).success(), "Forged source cannot replace actual OP4");
            OperatorGameTests.level(actor, LeveledPermissionPredicate.OWNERS);
            var cachedSource = actor.getCommandSource();
            c.assertFalse(AdminStats.set(cachedSource.withPermissions(PermissionPredicate.NONE), target, "max_health", 100).success(), "Actual OP4 still needs source permission");
            OperatorGameTests.deop(actor);
            c.assertFalse(AdminStats.set(cachedSource, target, "max_health", 100).success(), "Cached source cannot outlive deop");
            c.assertFalse(AdminStats.resetAll(cachedSource, target).success(), "Reset also checks current permission");
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.MAX_HEALTH), original, "Rejected edits leave target unchanged");
            c.assertTrue(Double.isNaN(AdminStats.original(target, AdminStats.find(target, "max_health"))), "Rejected attempts never create reset history");
        } finally { cleanup(actor); cleanup(target); }
        c.complete();
    }

    @GameTest public void editorRejectsNonFiniteOutOfRangeAndFractionalIntegerValues(TestContext c) {
        var actor = owner(c, "stats-bounds");
        var target = player(c, "stats-bounded");
        try {
            var source = actor.getCommandSource();
            var before = save(target);
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
                c.assertFalse(AdminStats.set(source, target, "max_health", invalid).success(), "Non-finite values are rejected");
            for (var stat : AdminStats.list(target)) {
                if (!stat.attribute()) continue;
                c.assertFalse(AdminStats.set(source, target, stat.id(), stat.minimum() - 1).success(), "Below native bound: " + stat.id());
                c.assertFalse(AdminStats.set(source, target, stat.id(), Math.nextUp(stat.maximum())).success(), "Above representable or physics bound: " + stat.id());
            }
            c.assertFalse(AdminStats.set(source, target, "food", 3.5).success(), "Integer food does not silently truncate");
            c.assertFalse(AdminStats.set(source, target, "xp_level", 1.5).success(), "Integer XP level does not silently truncate");
            c.assertFalse(AdminStats.set(source, target, "missing_attribute", 1).success(), "Unknown stat is rejected");
            c.assertEquals(GameModes.state(target), before.getCompoundOrEmpty("InfinityModes"), "Invalid input never leaves a persistence entry");
            c.assertEquals(target.getMaxHealth(), 20f, "Invalid input leaves actual health limit intact");
            set(c, source, target, "health", target.getMaxHealth() + 1);
            c.assertEquals(target.getHealth(), target.getMaxHealth(), "Oversized heal stops at the effective health maximum");
        } finally { cleanup(actor); cleanup(target); }
        c.complete();
    }

    @GameTest public void editsAndResetPreserveEquipmentEffectsAndOriginalBase(TestContext c) {
        var actor = owner(c, "stats-modifier");
        var target = player(c, "stats-equipped");
        var damage = target.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        var externalId = Identifier.of("infinity_test", "external_damage");
        var external = new EntityAttributeModifier(externalId, 7, EntityAttributeModifier.Operation.ADD_VALUE);
        damage.setBaseValue(3);
        damage.addPersistentModifier(external);
        target.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        target.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 400, 1));
        c.waitAndRun(2, () -> {
            try {
                double before = damage.getValue();
                int modifiers = damage.getModifiers().size();
                set(c, actor.getCommandSource(), target, "attack_damage", 40);
                c.assertEquals(damage.getValue(), before + 37, "Gear and Strength continue contributing above edited base");
                set(c, actor.getCommandSource(), target, "attack_damage", 70);
                c.assertEquals(AdminStats.original(target, AdminStats.find(target, "attack_damage")), 3d, "Repeated edits retain first original base");
                c.assertTrue(AdminStats.reset(actor.getCommandSource(), target, "attack_damage").success(), "Explicit reset succeeds");
                c.assertEquals(damage.getBaseValue(), 3d, "Reset restores existing base, not a hard-coded vanilla default");
                c.assertEquals(damage.getValue(), before, "Reset preserves all external modifier contributions");
                c.assertEquals(damage.getModifiers().size(), modifiers, "No modifiers are cleared or duplicated");
                c.assertEquals(damage.getModifier(externalId), external, "External persistent modifier survives");
                c.assertTrue(target.hasStatusEffect(StatusEffects.STRENGTH), "External potion remains active");
                c.assertTrue(target.getMainHandStack().isOf(Items.DIAMOND_SWORD) && target.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE), "Equipment remains equipped");
                c.assertTrue(Double.isNaN(AdminStats.original(target, AdminStats.find(target, "attack_damage"))), "Reset removes the old history entry");
            } finally { cleanup(actor); cleanup(target); }
            c.complete();
        });
    }

    @GameTest public void modeSwitchKeepsBaseAttributesGlobalAndVitalsInTheirOwnMode(TestContext c) {
        var actor = owner(c, "stats-modes-op");
        var target = player(c, "stats-modes");
        try {
            var source = actor.getCommandSource();
            set(c, source, target, "max_health", 80);
            set(c, source, target, "health", 65);
            set(c, source, target, "food", 12);
            set(c, source, target, "xp_level", 20);
            GameModes.switchNow(target, GameModes.Mode.CREATIVE, null);
            c.assertEquals(target.getMaxHealth(), 80f, "Base attribute applies across modes");
            c.assertEquals(target.getHealth(), 20f, "A new mode does not inherit Survival current health");
            set(c, source, target, "health", 37);
            set(c, source, target, "food", 7);
            set(c, source, target, "xp_level", 5);
            GameModes.switchNow(target, GameModes.Mode.SURVIVAL, null);
            c.assertEquals(target.getHealth(), 65f, "Survival health returns from its own snapshot");
            c.assertEquals(target.getHungerManager().getFoodLevel(), 12, "Survival food is isolated");
            c.assertEquals(target.experienceLevel, 20, "Survival XP is isolated");
            c.assertEquals(target.getMaxHealth(), 80f, "Returning preserves the global maximum");
            c.assertTrue(AdminStats.reset(source, target, "max_health").success(), "Base reset remains available after mode switches");
            c.assertEquals(target.getHealth(), 20f, "Reset clamps current mode health to restored maximum");
            GameModes.switchNow(target, GameModes.Mode.CREATIVE, null);
            c.assertEquals(target.getHealth(), 20f, "Inactive saved health is clamped when that mode is restored");
            c.assertEquals(target.experienceLevel, 5, "Clamping health does not erase other saved mode values");
        } finally { cleanup(actor); cleanup(target); }
        c.complete();
    }

    @GameTest public void nativePlayerSerializationPreservesMovementAndOriginalResetValues(TestContext c) {
        var actor = owner(c, "stats-save-op");
        var target = player(c, "stats-save");
        try {
            var source = actor.getCommandSource();
            var speed = target.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
            double original = speed.getBaseValue();
            float originalWalk = target.getAbilities().getWalkSpeed();
            set(c, source, target, "movement_speed", .37);
            set(c, source, target, "max_health", 92);
            double edited = speed.getBaseValue();
            var stored = save(target);
            c.assertTrue(AdminStats.resetAll(source, target).success(), "Reset changes live values before reading saved state");
            read(target, stored);
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), edited, "Vanilla readCustomData retains edited movement speed");
            c.assertEquals((double) target.getAbilities().getWalkSpeed(), edited, "Saved walking ability agrees with movement attribute");
            c.assertEquals(target.getMaxHealth(), 92f, "Other base attributes survive vanilla player serialization");
            c.assertEquals(AdminStats.original(target, AdminStats.find(target, "movement_speed")), original, "Original speed baseline survives serialization");
            c.assertTrue(AdminStats.resetAll(source, target).success(), "Persisted edits can be reset after read");
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), original, "Reset restores original attribute base");
            c.assertEquals(target.getAbilities().getWalkSpeed(), originalWalk, "Reset independently restores original walk speed");
            c.assertEquals(target.getMaxHealth(), 20f, "Reset restores original max health");
        } finally { cleanup(actor); cleanup(target); }
        c.complete();
    }

    @GameTest public void deathRespawnPreservesEditedAttributesAndCanStillResetAfterSaving(TestContext c) {
        var actor = owner(c, "stats-respawn-op");
        var initial = player(c, "stats-respawn");
        ServerPlayerEntity target = initial;
        try {
            var source = actor.getCommandSource();
            double original = target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED);
            set(c, source, target, "movement_speed", .45);
            set(c, source, target, "max_health", 60);
            double edited = target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED);
            target.damage(target.getEntityWorld(), target.getDamageSources().genericKill(), Float.MAX_VALUE);
            c.assertFalse(target.isAlive(), "Test uses an actual player death");
            // Use the client packet path so the connection points at the newly created player life.
            var connection = target.networkHandler;
            connection.onClientStatus(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
                net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
            target = connection.player;
            c.assertTrue(target != initial, "The respawn packet creates and rebinds a new player life");
            target.onTeleportationDone();
            target.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
            c.assertEquals(target.getMaxHealth(), 60f, "Vanilla death copy preserves edited max health");
            c.assertEquals(target.getHealth(), 60f, "Respawn fills the real edited max health");
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), edited, "Respawn preserves movement attribute");
            c.assertEquals((double) target.getAbilities().getWalkSpeed(), edited, "Respawn preserves the walking ability required for later reload");
            read(target, save(target));
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), edited, "Saving and reading after death does not revert speed");
            c.assertEquals(AdminStats.original(target, AdminStats.find(target, "movement_speed")), original, "ModePlayer copies reset metadata on actual respawn");
            c.assertTrue(AdminStats.reset(source, target, "movement_speed").success(), "Respawned online target can reset its edit");
            c.assertEquals(target.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED), original, "Reset after respawn restores the original speed");
        } finally { cleanup(actor); cleanup(target); }
        c.complete();
    }

    @GameTest public void staleDisconnectedTargetsCannotBeEdited(TestContext c) {
        var actor = owner(c, "stats-stale-op");
        var target = player(c, "stats-stale");
        try {
            var source = actor.getCommandSource();
            set(c, source, target, "max_health", 44);
            c.getWorld().getServer().getPlayerManager().remove(target);
            c.assertFalse(AdminStats.set(source, target, "max_health", 88).success(), "A disconnected target object cannot be edited");
            c.assertFalse(AdminStats.reset(source, target, "max_health").success(), "A disconnected target cannot be reset either");
            c.assertEquals(target.getMaxHealth(), 44f, "Stale target is not mutated");
        } finally { cleanup(actor); cleanup(target); }
        c.complete();
    }

    @GameTest public void extendedCombatValuesApplyPersistAndResetOnlyEditedInstances(TestContext c) {
        var actor=owner(c,"stats-expanded-op");var target=player(c,"stats-expanded");
        var ordinary=player(c,"stats-normal");
        try {
            set(c,actor.getCommandSource(),target,"max_health",5000);
            set(c,actor.getCommandSource(),target,"health",4500);
            set(c,actor.getCommandSource(),target,"attack_damage",5000);
            set(c,actor.getCommandSource(),target,"armor",500);
            c.assertEquals(target.getMaxHealth(),5000f,"Effective capacity exceeds the normal 1024 cap");
            c.assertEquals(target.getAttributeValue(EntityAttributes.ATTACK_DAMAGE),5000d,"Effective damage exceeds the normal 2048 cap");
            c.assertEquals(target.getAttributeValue(EntityAttributes.ARMOR),500d,"Effective armor exceeds the normal 30 cap");
            ordinary.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(5000);
            c.assertEquals(ordinary.getMaxHealth(),1024f,"An unedited instance retains native bounds");
            var saved=save(target);
            target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(20);target.setHealth(12);
            read(target,saved);
            c.assertEquals(target.getMaxHealth(),5000f,"Extended effective capacity survives native save/reload");
            c.assertEquals(target.getHealth(),4500f,"Reload restores high health after restoring the expansion flag");
            c.assertEquals(target.getAttributeValue(EntityAttributes.ATTACK_DAMAGE),5000d,"Extended damage survives reload");
            c.assertTrue(AdminStats.resetAll(actor.getCommandSource(),target).success(),"Reset still works");
            c.assertEquals(target.getMaxHealth(),20f,"Reset restores native capacity and clamps current health");
            c.assertEquals(target.getHealth(),20f,"Reset clamps health coherently");
            target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(5000);
            c.assertEquals(target.getMaxHealth(),1024f,"Reset removes the per-instance override");
        } finally { cleanup(actor);cleanup(target);cleanup(ordinary); }
        c.complete();
    }

    @GameTest public void expandedFoodReservesAreRealAndRemainOneTimeEdits(TestContext c) {
        var actor=owner(c,"stats-reserves-op");var target=player(c,"stats-reserves");
        try {
            set(c,actor.getCommandSource(),target,"food",100);
            set(c,actor.getCommandSource(),target,"saturation",80);
            set(c,actor.getCommandSource(),target,"exhaustion",100);
            c.assertEquals(target.getHungerManager().getFoodLevel(),100,"Food is not restricted to twenty by the editor");
            c.assertEquals(target.getHungerManager().getSaturationLevel(),80f,"Saturation reserve is real");
            var saved=save(target);read(target,saved);
            c.assertEquals(target.getHungerManager().getFoodLevel(),100,"Food survives native save/load");
            c.assertEquals(AdminStats.value(target,AdminStats.find(target,"exhaustion")).base(),100d,"Exhaustion survives native save/load");
        } finally { cleanup(actor);cleanup(target); }
        c.complete();
    }

}

package dev.convergence;

import net.minecraft.world.entity.EntityTypes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Exercise the actual player state and persistence paths, not only the editor's metadata. */
public class AdminStatsGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var p = new ModeGameTests().player(c, name);
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }
    private ServerPlayer owner(GameTestHelper c, String name) {
        var p = player(c, name);
        OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
        return p;
    }
    private void cleanup(ServerPlayer p) {
        var server = p.level().getServer();
        p.closeContainer();
        OperatorGameTests.deop(p);
        GameModes.PENDING.remove(p.getUUID());
        CommunityServer.get(server).pending.remove(p.getUUID());
        if (server.getPlayerList().getPlayer(p.getUUID()) == p) server.getPlayerList().remove(p);
    }
    private void set(GameTestHelper c, CommandSourceStack actor, ServerPlayer target, String id, double value) {
        var result = AdminStats.set(actor, target, id, value);
        c.assertTrue(result.success(), "Set " + id + " to " + value + ": " + result.message());
    }
    private CompoundTag save(ServerPlayer p) {
        var writer = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, p.registryAccess());
        p.saveWithoutId(writer);
        return writer.buildResult();
    }
    private void read(ServerPlayer p, CompoundTag saved) {
        p.load(TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), saved));
    }

    @GameTest public void adminEditsAnotherPlayersHealthFoodExperienceAndAttributes(GameTestHelper c) {
        var admin = player(c, "stats-admin");
        var target = player(c, "stats-target");
        var memberships = Memberships.get(c.getLevel().getServer());
        try {
            c.assertTrue(memberships.grantAdmin(admin.getUUID()), "Admin role grants real OP4");
            var actor = admin.createCommandSourceStack();
            set(c, actor, target, "max_health", 80);
            set(c, actor, target, "health", 67);
            c.assertValueEqual(target.getMaxHealth(), 80f, "Max health changes the actual attribute");
            c.assertValueEqual(target.getHealth(), 67f, "Current health changes immediately");
            set(c, actor, target, "food", 12);
            set(c, actor, target, "saturation", 8);
            c.assertValueEqual(target.getFoodData().getFoodLevel(), 12, "Food changes actual hunger state");
            c.assertValueEqual(target.getFoodData().getSaturationLevel(), 8f, "Saturation changes actual hunger state");
            set(c, actor, target, "xp_level", 30);
            c.assertValueEqual(target.experienceLevel, 30, "Experience level changes");
            c.assertValueEqual(target.experienceProgress, 0f, "Setting level starts a consistent new progress bar");
            c.assertValueEqual(target.totalExperience, 1395, "Total XP agrees with vanilla level 30");
            set(c, actor, target, "max_absorption", 24);
            set(c, actor, target, "absorption", 18);
            c.assertValueEqual(target.getAbsorptionAmount(), 18f, "Absorption hearts are actually granted");
            set(c, actor, target, "max_health", 25);
            c.assertValueEqual(target.getHealth(), 25f, "Lowering max health clamps current health immediately");
            set(c, actor, target, "attack_damage", 45);
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 45d, "Attack damage edits base value");
            set(c, actor, target, "health", 0);
            c.assertFalse(target.isAlive(), "Explicit zero health performs the requested player death");
        } finally {
            memberships.revokeAdmin(admin.getUUID());
            cleanup(admin);
            cleanup(target);
        }
        c.succeed();
    }

    @GameTest public void editorRejectsOrdinaryRevokedAndForgedActorsWithoutMutation(GameTestHelper c) {
        var actor = player(c, "stats-no-op");
        var target = player(c, "stats-protected");
        try {
            double original = target.getAttributeBaseValue(Attributes.MAX_HEALTH);
            c.assertFalse(AdminStats.set(actor.createCommandSourceStack(), target, "max_health", 100).success(), "Ordinary player cannot edit");
            c.assertFalse(AdminStats.set(actor.createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS), target, "max_health", 100).success(), "Forged source cannot replace actual OP4");
            OperatorGameTests.level(actor, LevelBasedPermissionSet.OWNER);
            var cachedSource = actor.createCommandSourceStack();
            c.assertFalse(AdminStats.set(cachedSource.withPermission(PermissionSet.NO_PERMISSIONS), target, "max_health", 100).success(), "Actual OP4 still needs source permission");
            OperatorGameTests.deop(actor);
            c.assertFalse(AdminStats.set(cachedSource, target, "max_health", 100).success(), "Cached source cannot outlive deop");
            c.assertFalse(AdminStats.resetAll(cachedSource, target).success(), "Reset also checks current permission");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MAX_HEALTH), original, "Rejected edits leave target unchanged");
            c.assertTrue(Double.isNaN(AdminStats.original(target, AdminStats.find(target, "max_health"))), "Rejected attempts never create reset history");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void editorRejectsNonFiniteOutOfRangeAndFractionalIntegerValues(GameTestHelper c) {
        var actor = owner(c, "stats-bounds");
        var target = player(c, "stats-bounded");
        try {
            var source = actor.createCommandSourceStack();
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
            c.assertValueEqual(GameModes.state(target), before.getCompoundOrEmpty("InfinityModes"), "Invalid input never leaves a persistence entry");
            c.assertValueEqual(target.getMaxHealth(), 20f, "Invalid input leaves actual health limit intact");
            set(c, source, target, "health", target.getMaxHealth() + 1);
            c.assertValueEqual(target.getMaxHealth(),21f,"Direct health entry automatically increases capacity");
            c.assertValueEqual(target.getHealth(),21f,"Direct entry applies the amount rather than silently truncating it");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void editsAndResetPreserveEquipmentEffectsAndOriginalBase(GameTestHelper c) {
        var actor = owner(c, "stats-modifier");
        var target = player(c, "stats-equipped");
        var damage = target.getAttribute(Attributes.ATTACK_DAMAGE);
        var externalId = Identifier.fromNamespaceAndPath("infinity_test", "external_damage");
        var external = new AttributeModifier(externalId, 7, AttributeModifier.Operation.ADD_VALUE);
        damage.setBaseValue(3);
        damage.addPermanentModifier(external);
        target.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        target.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        target.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 400, 1));
        c.runAfterDelay(2, () -> {
            try {
                double before = damage.getValue();
                int modifiers = damage.getModifiers().size();
                set(c, actor.createCommandSourceStack(), target, "attack_damage", 40);
                c.assertValueEqual(damage.getValue(), before + 37, "Gear and Strength continue contributing above edited base");
                set(c, actor.createCommandSourceStack(), target, "attack_damage", 70);
                c.assertValueEqual(AdminStats.original(target, AdminStats.find(target, "attack_damage")), 3d, "Repeated edits retain first original base");
                c.assertTrue(AdminStats.reset(actor.createCommandSourceStack(), target, "attack_damage").success(), "Explicit reset succeeds");
                c.assertValueEqual(damage.getBaseValue(), 3d, "Reset restores existing base, not a hard-coded vanilla default");
                c.assertValueEqual(damage.getValue(), before, "Reset preserves all external modifier contributions");
                c.assertValueEqual(damage.getModifiers().size(), modifiers, "No modifiers are cleared or duplicated");
                c.assertValueEqual(damage.getModifier(externalId), external, "External persistent modifier survives");
                c.assertTrue(target.hasEffect(MobEffects.STRENGTH), "External potion remains active");
                c.assertTrue(target.getMainHandItem().is(Items.DIAMOND_SWORD) && target.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), "Equipment remains equipped");
                c.assertTrue(Double.isNaN(AdminStats.original(target, AdminStats.find(target, "attack_damage"))), "Reset removes the old history entry");
            } finally { cleanup(actor); cleanup(target); }
            c.succeed();
        });
    }

    @GameTest public void modeSwitchKeepsBaseAttributesGlobalAndVitalsInTheirOwnMode(GameTestHelper c) {
        var actor = owner(c, "stats-modes-op");
        var target = player(c, "stats-modes");
        try {
            var source = actor.createCommandSourceStack();
            set(c, source, target, "max_health", 80);
            set(c, source, target, "health", 65);
            set(c, source, target, "food", 12);
            set(c, source, target, "xp_level", 20);
            GameModes.switchNow(target, GameModes.Mode.CREATIVE, null);
            c.assertValueEqual(target.getMaxHealth(), 80f, "Base attribute applies across modes");
            c.assertValueEqual(target.getHealth(), 20f, "A new mode does not inherit Survival current health");
            set(c, source, target, "health", 37);
            set(c, source, target, "food", 7);
            set(c, source, target, "xp_level", 5);
            GameModes.switchNow(target, GameModes.Mode.SURVIVAL, null);
            c.assertValueEqual(target.getHealth(), 65f, "Survival health returns from its own snapshot");
            c.assertValueEqual(target.getFoodData().getFoodLevel(), 12, "Survival food is isolated");
            c.assertValueEqual(target.experienceLevel, 20, "Survival XP is isolated");
            c.assertValueEqual(target.getMaxHealth(), 80f, "Returning preserves the global maximum");
            c.assertTrue(AdminStats.reset(source, target, "max_health").success(), "Base reset remains available after mode switches");
            c.assertValueEqual(target.getHealth(), 20f, "Reset clamps current mode health to restored maximum");
            GameModes.switchNow(target, GameModes.Mode.CREATIVE, null);
            c.assertValueEqual(target.getHealth(), 20f, "Inactive saved health is clamped when that mode is restored");
            c.assertValueEqual(target.experienceLevel, 5, "Clamping health does not erase other saved mode values");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void nativePlayerSerializationPreservesMovementAndOriginalResetValues(GameTestHelper c) {
        var actor = owner(c, "stats-save-op");
        var target = player(c, "stats-save");
        try {
            var source = actor.createCommandSourceStack();
            var speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
            double original = speed.getBaseValue();
            float originalWalk = target.getAbilities().getWalkingSpeed();
            set(c, source, target, "movement_speed", .37);
            set(c, source, target, "max_health", 92);
            double edited = speed.getBaseValue();
            var stored = save(target);
            c.assertTrue(AdminStats.resetAll(source, target).success(), "Reset changes live values before reading saved state");
            read(target, stored);
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), edited, "Vanilla readCustomData retains edited movement speed");
            c.assertValueEqual((double) target.getAbilities().getWalkingSpeed(), edited, "Saved walking ability agrees with movement attribute");
            c.assertValueEqual(target.getMaxHealth(), 92f, "Other base attributes survive vanilla player serialization");
            c.assertValueEqual(AdminStats.original(target, AdminStats.find(target, "movement_speed")), original, "Original speed baseline survives serialization");
            c.assertTrue(AdminStats.resetAll(source, target).success(), "Persisted edits can be reset after read");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), original, "Reset restores original attribute base");
            c.assertValueEqual(target.getAbilities().getWalkingSpeed(), originalWalk, "Reset independently restores original walk speed");
            c.assertValueEqual(target.getMaxHealth(), 20f, "Reset restores original max health");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void deathRespawnPreservesEditedAttributesAndCanStillResetAfterSaving(GameTestHelper c) {
        var actor = owner(c, "stats-respawn-op");
        var initial = player(c, "stats-respawn");
        ServerPlayer target = initial;
        try {
            var source = actor.createCommandSourceStack();
            double original = target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
            set(c, source, target, "movement_speed", .45);
            set(c, source, target, "max_health", 60);
            double edited = target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
            target.hurtServer(target.level(), target.damageSources().genericKill(), Float.MAX_VALUE);
            c.assertFalse(target.isAlive(), "Test uses an actual player death");
            // Use the client packet path so the connection points at the newly created player life.
            var connection = target.connection;
            connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            target = connection.player;
            c.assertTrue(target != initial, "The respawn packet creates and rebinds a new player life");
            target.hasChangedDimension();
            target.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            c.assertValueEqual(target.getMaxHealth(), 60f, "Vanilla death copy preserves edited max health");
            c.assertValueEqual(target.getHealth(), 60f, "Respawn fills the real edited max health");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), edited, "Respawn preserves movement attribute");
            c.assertValueEqual((double) target.getAbilities().getWalkingSpeed(), edited, "Respawn preserves the walking ability required for later reload");
            read(target, save(target));
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), edited, "Saving and reading after death does not revert speed");
            c.assertValueEqual(AdminStats.original(target, AdminStats.find(target, "movement_speed")), original, "ModePlayer copies reset metadata on actual respawn");
            c.assertTrue(AdminStats.reset(source, target, "movement_speed").success(), "Respawned online target can reset its edit");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), original, "Reset after respawn restores the original speed");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void staleDisconnectedTargetsCannotBeEdited(GameTestHelper c) {
        var actor = owner(c, "stats-stale-op");
        var target = player(c, "stats-stale");
        try {
            var source = actor.createCommandSourceStack();
            set(c, source, target, "max_health", 44);
            c.getLevel().getServer().getPlayerList().remove(target);
            c.assertFalse(AdminStats.set(source, target, "max_health", 88).success(), "A disconnected target object cannot be edited");
            c.assertFalse(AdminStats.reset(source, target, "max_health").success(), "A disconnected target cannot be reset either");
            c.assertValueEqual(target.getMaxHealth(), 44f, "Stale target is not mutated");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void extendedCombatValuesApplyPersistAndResetOnlyEditedInstances(GameTestHelper c) {
        var actor=owner(c,"stats-expanded-op");var target=player(c,"stats-expanded");
        var ordinary=player(c,"stats-normal");
        try {
            set(c,actor.createCommandSourceStack(),target,"max_health",5000);
            set(c,actor.createCommandSourceStack(),target,"health",4500);
            set(c,actor.createCommandSourceStack(),target,"attack_damage",5000);
            set(c,actor.createCommandSourceStack(),target,"armor",500);
            c.assertValueEqual(target.getMaxHealth(),5000f,"Effective capacity exceeds the normal 1024 cap");
            c.assertValueEqual(target.getAttributeValue(Attributes.ATTACK_DAMAGE),5000d,"Effective damage exceeds the normal 2048 cap");
            c.assertValueEqual(target.getAttributeValue(Attributes.ARMOR),500d,"Effective armor exceeds the normal 30 cap");
            ordinary.getAttribute(Attributes.MAX_HEALTH).setBaseValue(5000);
            c.assertValueEqual(ordinary.getMaxHealth(),1024f,"An unedited instance retains native bounds");
            var saved=save(target);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);target.setHealth(12);
            read(target,saved);
            c.assertValueEqual(target.getMaxHealth(),5000f,"Extended effective capacity survives native save/reload");
            c.assertValueEqual(target.getHealth(),4500f,"Reload restores high health after restoring the expansion flag");
            c.assertValueEqual(target.getAttributeValue(Attributes.ATTACK_DAMAGE),5000d,"Extended damage survives reload");
            c.assertTrue(AdminStats.resetAll(actor.createCommandSourceStack(),target).success(),"Reset still works");
            c.assertValueEqual(target.getMaxHealth(),20f,"Reset restores native capacity and clamps current health");
            c.assertValueEqual(target.getHealth(),20f,"Reset clamps health coherently");
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(5000);
            c.assertValueEqual(target.getMaxHealth(),1024f,"Reset removes the per-instance override");
        } finally { cleanup(actor);cleanup(target);cleanup(ordinary); }
        c.succeed();
    }

    @GameTest public void expandedFoodReservesAreRealAndRemainOneTimeEdits(GameTestHelper c) {
        var actor=owner(c,"stats-reserves-op");var target=player(c,"stats-reserves");
        try {
            set(c,actor.createCommandSourceStack(),target,"food",100);
            set(c,actor.createCommandSourceStack(),target,"saturation",80);
            set(c,actor.createCommandSourceStack(),target,"exhaustion",100);
            c.assertValueEqual(target.getFoodData().getFoodLevel(),100,"Food is not restricted to twenty by the editor");
            c.assertValueEqual(target.getFoodData().getSaturationLevel(),80f,"Saturation reserve is real");
            var saved=save(target);read(target,saved);
            c.assertValueEqual(target.getFoodData().getFoodLevel(),100,"Food survives native save/load");
            c.assertValueEqual(AdminStats.value(target,AdminStats.find(target,"exhaustion")).base(),100d,"Exhaustion survives native save/load");
        } finally { cleanup(actor);cleanup(target); }
        c.succeed();
    }

    @GameTest public void directVitalEntryRaisesCapacityAndReloadKeepsConsumedAbsorption(GameTestHelper c) {
        var actor=owner(c,"reserve-auto-op");var target=player(c,"reserve-auto");
        try {
            var preview=AdminStats.planSet(target,"absorption",5000);
            c.assertValueEqual(preview.size(),2,"One edit reviews capacity and absorption together");
            c.assertValueEqual(preview.getFirst().id(),"max_absorption","Capacity appears before the dependent amount");
            c.assertValueEqual(target.getMaxAbsorption(),0f,"A preview never changes the target");
            set(c,actor.createCommandSourceStack(),target,"health",5000);
            set(c,actor.createCommandSourceStack(),target,"absorption",5000);
            c.assertValueEqual(target.getHealth(),5000f,"Health needs only one direct edit");
            c.assertValueEqual(target.getAbsorptionAmount(),5000f,"Absorption needs only one direct edit");
            target.hurtServer(c.getLevel(),target.damageSources().generic(),10);
            c.assertValueEqual(target.getAbsorptionAmount(),4990f,"Normal damage consumes the actual reserve");
            var saved=save(target);
            c.assertTrue(AdminStats.resetAll(actor.createCommandSourceStack(),target).success(),"Capacity originals can be restored together");
            read(target,saved);
            c.assertValueEqual(target.getMaxAbsorption(),5000f,"Expanded capacity survives native read");
            c.assertValueEqual(target.getAbsorptionAmount(),4990f,"Native reload preserves the remaining reserve without clipping or refilling it");
            c.assertValueEqual(AdminStats.original(target,AdminStats.find(target,"max_absorption")),0d,"First capacity original remains resettable");
        } finally {cleanup(actor);cleanup(target);}
        c.succeed();
    }

    @GameTest public void automaticCapacityPreservesModifiersAndImpossibleEditsAreAtomic(GameTestHelper c) {
        var actor=owner(c,"reserve-mod-op");var target=player(c,"reserve-mod");
        var capacity=target.getAttribute(Attributes.MAX_ABSORPTION);
        var id=Identifier.fromNamespaceAndPath("infinity_test","capacity_penalty");
        var modifier=new AttributeModifier(id,-100,AttributeModifier.Operation.ADD_VALUE);
        try {
            capacity.addPermanentModifier(modifier);
            set(c,actor.createCommandSourceStack(),target,"absorption",5000);
            c.assertValueEqual(target.getAbsorptionAmount(),5000f,"Automatic increase accounts for a negative external modifier");
            c.assertValueEqual(capacity.getModifier(id),modifier,"External modifiers are untouched");
            c.assertTrue(AdminStats.resetAll(actor.createCommandSourceStack(),target).success(),"Reset remains available");
            capacity.removeModifier(id);
            var blocked=new AttributeModifier(id,-1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            capacity.addPermanentModifier(blocked);
            var before=save(target);
            c.assertFalse(AdminStats.set(actor.createCommandSourceStack(),target,"absorption",100).success(),"A zero capacity multiplier cannot be satisfied by any base");
            c.assertValueEqual(capacity.getBaseValue(),0d,"Failure does not partially raise the base");
            c.assertValueEqual(target.getAbsorptionAmount(),0f,"Failure does not apply the amount");
            c.assertValueEqual(GameModes.state(target),before.getCompoundOrEmpty("InfinityModes"),"Failure creates no reset history");
            c.assertFalse(AdminStats.set(actor.createCommandSourceStack(),target,"health",Math.nextUp((double)Float.MAX_VALUE)).success(),"Nonrepresentable health is refused before changing capacity");
            c.assertValueEqual(target.getMaxHealth(),20f,"Invalid health leaves capacity intact");
        } finally {cleanup(actor);cleanup(target);}
        c.succeed();
    }

    @GameTest public void expandedArmorChangesActualProtectionAndResetRestoresVanilla(GameTestHelper c) {
        var actor=owner(c,"armor-protect-op");var target=player(c,"armor-protect");var ordinary=player(c,"armor-ordinary");
        try {
            set(c,actor.createCommandSourceStack(),target,"armor",500);
            var attacker=net.minecraft.world.entity.EntityTypes.ZOMBIE.create(c.getLevel(),net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            var source=target.damageSources().mobAttack(attacker);
            c.assertValueEqual(net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(target,10,source,500,0),0f,"Expanded armor exceeds the native 80 percent ceiling without negative damage");
            ordinary.getAttribute(Attributes.ARMOR).setBaseValue(500);
            c.assertTrue(net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(ordinary,10,source,30,0)>1.9f,"An unedited instance keeps vanilla protection");
            target.hurtServer(c.getLevel(),source,10);
            c.assertValueEqual(target.getHealth(),20f,"Real damage uses expanded armor protection");
            var saved=save(target);read(target,saved);
            c.assertValueEqual(target.getAttributeValue(Attributes.ARMOR),500d,"Native reload keeps the edited armor number");
            c.assertValueEqual(net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(target,10,source,500,0),0f,"Reload keeps expanded protection enabled");
            target.damageCooldownTime=0;
            target.hurtServer(c.getLevel(),target.damageSources().genericKill(),2);
            c.assertValueEqual(target.getHealth(),18f,"Native damage that bypasses armor still bypasses it");
            c.assertTrue(AdminStats.reset(actor.createCommandSourceStack(),target,"armor").success(),"Armor reset succeeds");
            c.assertValueEqual(net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(target,10,source,0,0),10f,"Reset restores normal armor calculations");
        } finally {cleanup(actor);cleanup(target);cleanup(ordinary);}
        c.succeed();
    }

}

package dev.convergence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Native sessions and values, with no stand-in mutation or persistence backend. */
public class PlayerStatBatchGameTests {
    private ServerPlayer player(GameTestHelper c, String name, boolean admin) {
        var player = new ModeGameTests().player(c, name);
        player.setGameMode(GameType.SURVIVAL);
        if (admin) OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
        return player;
    }

    private void cleanup(ServerPlayer player) {
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        OperatorGameTests.deop(player);
        GameModes.PENDING.remove(player.getUUID());
        GameModes.TRANSITIONS.remove(player.getUUID());
        GameModes.OPERATOR_TRANSFERS.remove(player.getUUID());
        CommunityServer.get(player.level().getServer()).cancelPending(player);
        if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player)
            player.level().getServer().getPlayerList().remove(player);
    }

    private CompoundTag save(ServerPlayer player) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(out);
        return out.buildResult();
    }

    private double base(ServerPlayer player) { return player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE); }
    private void base(ServerPlayer player, double value) { player.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(value); }
    private boolean historyEmpty(ServerPlayer player) { return GameModes.state(player).getCompoundOrEmpty(AdminStats.STORAGE).isEmpty(); }

    private void reject(GameTestHelper c, PlayerStatBatch.Plan plan, String reason) {
        c.assertFalse(plan.invalidReason().isEmpty(), "Invalid group explains refusal: " + reason);
        c.assertFalse(plan.apply().success(), "Invalid group cannot apply: " + reason);
    }

    @GameTest public void explicitSubsetPreviewIsImmutableAndOneConfirmationUsesNativeSetters(GameTestHelper c) {
        var actor = player(c, "batch-subset-op", true);
        var first = player(c, "batch-first", false);
        var second = player(c, "batch-second", false);
        var excluded = player(c, "batch-excluded", false);
        var different = player(c, "batch-different", false);
        try {
            for (var target : List.of(first, second, excluded)) base(target, 3);
            base(different, 9);
            var external = new AttributeModifier(Identifier.fromNamespaceAndPath("infinity_test", "batch_external"),
                7, AttributeModifier.Operation.ADD_VALUE);
            second.getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(external);
            var suggestions = PlayerStatBatch.matching(actor, first, "attack_damage");
            c.assertTrue(suggestions.containsAll(List.of(first, second, excluded)), "Matching compares selected base, preserving different effective modifiers");
            c.assertFalse(suggestions.contains(different), "Different bases are ineligible");
            var chosen = new ArrayList<>(List.of(first, second));
            var beforeFirst = save(first); var beforeSecond = save(second); var beforeExcluded = save(excluded);
            var plan = PlayerStatBatch.prepare(actor, chosen, "minecraft:attack_damage", 40);
            chosen.clear();
            c.assertTrue(plan.invalidReason().isEmpty(), "Explicit subset prepares a valid review");
            c.assertValueEqual(plan.commonBefore(), 3d, "Review keeps common native base");
            c.assertValueEqual(plan.statId(), "attack_damage", "Stat identifier is canonical");
            c.assertValueEqual(plan.targets(), List.of(first, second), "Caller list changes cannot add or drop targets");
            c.assertValueEqual(plan.changes().get(1).uuid(), second.getUUID(), "Preview binds actual UUID");
            c.assertValueEqual(plan.changes().get(1).changes(), List.of(new AdminStats.Adjustment("attack_damage", 3, 40)), "Preview is the native planned change");
            boolean immutable = false;
            try { plan.targets().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            c.assertTrue(immutable, "Targets cannot be edited after review");
            immutable = false;
            try { plan.changes().getFirst().changes().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            c.assertTrue(immutable, "Nested adjustment lists are immutable");
            c.assertValueEqual(save(first), beforeFirst, "Preview does not mutate first native state");
            c.assertValueEqual(save(second), beforeSecond, "Preview does not mutate second native state");
            c.assertTrue(plan.apply().success(), "One confirmation applies the selected group");
            c.assertValueEqual(base(first), 40d, "First native base changes");
            c.assertValueEqual(base(second), 40d, "Second native base changes");
            c.assertValueEqual(second.getAttribute(Attributes.ATTACK_DAMAGE).getValue(), 47d, "Permanent external modifier remains effective");
            c.assertValueEqual(second.getAttribute(Attributes.ATTACK_DAMAGE).getModifier(external.id()), external, "No modifier is replaced");
            c.assertValueEqual(save(excluded), beforeExcluded, "Unselected matching player is entirely unchanged");
            c.assertValueEqual(base(different), 9d, "Nonmatching player remains unchanged");
            c.assertValueEqual(base(actor), 1d, "Actor is not implicitly selected");
            c.assertFalse(plan.apply().success(), "Replayed confirmation cannot apply again");
            c.assertValueEqual(AdminStats.original(first, AdminStats.find(first, "attack_damage")), 3d, "Native original records first base");
        } finally { for (var p : List.of(actor, first, second, excluded, different)) cleanup(p); }
        c.succeed();
    }

    @GameTest public void invalidMembersNumbersAndMismatchedBasesRejectBeforeAnyEdit(GameTestHelper c) {
        var actor = player(c, "batch-invalid-op", true);
        var first = player(c, "batch-invalid-a", false);
        var second = player(c, "batch-invalid-b", false);
        try {
            var untouched = save(first);
            var uncachedDraft = PlayerStatBatch.prepare(actor, List.of(first, second), "attack_damage", 40);
            c.assertTrue(uncachedDraft.invalidReason().isEmpty(), "Uncached default attribute can be reviewed");
            c.assertValueEqual(save(first), untouched, "Preparing and validating uncached selected/default capacities preserves full native NBT");
            for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
                reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "attack_damage", bad), "non-finite");
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, first), "attack_damage", 40), "duplicate identity");
            reject(c, PlayerStatBatch.prepare(actor, Arrays.asList(first, null), "attack_damage", 40), "null identity");
            reject(c, PlayerStatBatch.prepare(actor, List.of(), "attack_damage", 40), "empty group");
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "missing_attribute", 40), "unsupported statistic");
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "food", 3.5), "fractional integer");
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "health", 0), "lethal raw value");
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "health", Double.MIN_VALUE), "positive double normalizes to lethal float zero");
            reject(c, PlayerStatBatch.prepare(first, List.of(first, second), "attack_damage", 40), "ordinary actor");
            base(second, 2);
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "attack_damage", 40), "mismatched current base");
            c.assertValueEqual(base(first), 1d, "No earlier member changes when a later member is invalid");
            c.assertValueEqual(base(second), 2d, "Refusal preserves preexisting different base");
            c.assertTrue(historyEmpty(first) && historyEmpty(second), "Invalid reviews create no original records");
            c.assertTrue(first.isAlive() && second.isAlive(), "Lethal and normalized-zero group values never kill a player");
            base(second, 1);
            var beforeDeath = PlayerStatBatch.prepare(actor, List.of(first, second), "attack_damage", 40);
            second.hurtServer(second.level(), second.damageSources().genericKill(), Float.MAX_VALUE);
            c.assertFalse(second.isAlive(), "Test exercises actual native target death");
            reject(c, beforeDeath, "selected target died after preview");
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "attack_damage", 40), "dead target cannot join a new group");
            c.assertFalse(PlayerStatBatch.matching(actor, first, "attack_damage").contains(second), "Dead actual players are excluded from suggestions");
            actor.level().getServer().getPlayerList().remove(second);
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, second), "attack_damage", 40), "removed offline session cannot join group");
            c.assertValueEqual(base(first), 1d, "Death and offline rejection leave earlier living member unchanged");
        } finally { cleanup(actor); cleanup(first); cleanup(second); }
        c.succeed();
    }

    @GameTest public void baseModifierCapacityAndResetHistoryDriftRejectTheWholeGroup(GameTestHelper c) {
        var actor = player(c, "batch-drift-op", true);
        var first = player(c, "batch-drift-a", false);
        var last = player(c, "batch-drift-last", false);
        try {
            var plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            base(last, 2); reject(c, plan, "last selected base changed"); base(last, 1);
            c.assertFalse(plan.apply().success(), "A rejected review cannot revive after the value is restored");
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            var plus = new AttributeModifier(Identifier.fromNamespaceAndPath("infinity_test", "batch_plus"), 2, AttributeModifier.Operation.ADD_VALUE);
            var minus = new AttributeModifier(Identifier.fromNamespaceAndPath("infinity_test", "batch_minus"), -2, AttributeModifier.Operation.ADD_VALUE);
            var damage = last.getAttribute(Attributes.ATTACK_DAMAGE);
            damage.addPermanentModifier(plus); damage.addPermanentModifier(minus);
            c.assertValueEqual(damage.getValue(), 1d, "Drift deliberately leaves the same effective number");
            reject(c, plan, "modifier identities changed despite equal effective value");
            damage.removeModifier(plus.id()); damage.removeModifier(minus.id());
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            last.getAttribute(Attributes.MAX_HEALTH).setBaseValue(30);
            reject(c, plan, "related native capacity changed"); last.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            var history = new CompoundTag(); var savedOriginal = new CompoundTag(); savedOriginal.putDouble("original", 1);
            history.put("minecraft:attack_damage", savedOriginal); GameModes.state(last).put(AdminStats.STORAGE, history);
            reject(c, plan, "reset history changed");
            c.assertValueEqual(base(first), 1d, "Every full-group preflight rejects before touching its first member");
            c.assertValueEqual(base(last), 1d, "Reviewed requested amount was never applied");
            c.assertTrue(historyEmpty(first), "No first-member original was saved on rejected reviews");
            c.assertValueEqual(GameModes.state(last).getCompoundOrEmpty(AdminStats.STORAGE), history, "External reset history is preserved");
            for (var p : List.of(first, last))
                c.assertTrue(AdminStats.set(actor.createCommandSourceStack(), p, "attack_damage", 5000).success(), "Prepare real expanded above-cap attributes");
            var beforeExpandedPreview = save(first);
            var detached = AdminStats.detachedAttribute(first, Attributes.ATTACK_DAMAGE);
            c.assertTrue(detached != first.getAttribute(Attributes.ATTACK_DAMAGE), "Preview copy is detached from actual native instance");
            c.assertTrue(((AdminAttribute)detached).infinity$expanded(), "Native replaceFrom mixin copies the expanded flag");
            c.assertValueEqual(detached.getValue(), 5000d, "Detached preview retains above-cap effective value");
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 6000);
            c.assertTrue(plan.invalidReason().isEmpty(), "Expanded copies form a valid unchanged group review");
            c.assertValueEqual(save(first), beforeExpandedPreview, "Expanded detached preview preserves full actual player NBT");
            ((AdminAttribute)last.getAttribute(Attributes.ATTACK_DAMAGE)).infinity$expanded(false);
            c.assertValueEqual(base(last), 5000d, "Expansion-only drift leaves the selected base unchanged");
            c.assertFalse(((AdminAttribute)AdminStats.detachedAttribute(last, Attributes.ATTACK_DAMAGE)).infinity$expanded(), "A fresh detached copy follows the changed real expansion flag");
            reject(c, plan, "only the expansion flag changed after above-cap review");
            c.assertValueEqual(base(first), 5000d, "Expansion flag drift refuses before updating another selected player");
            ((AdminAttribute)last.getAttribute(Attributes.ATTACK_DAMAGE)).infinity$expanded(true);
        } finally { cleanup(actor); cleanup(first); cleanup(last); }
        c.succeed();
    }

    @GameTest public void permissionConnectionVanillaModeAndPendingTransferAreExactSessions(GameTestHelper c) {
        var actor = player(c, "batch-session-op", true);
        var first = player(c, "batch-session-a", false);
        var last = player(c, "batch-session-b", false);
        try {
            var plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            OperatorGameTests.deop(actor); reject(c, plan, "OP4 revoked"); OperatorGameTests.level(actor, LevelBasedPermissionSet.OWNER);
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            var connection = last.connection;
            try { last.connection = first.connection; reject(c, plan, "target listener changed"); }
            finally { last.connection = connection; }
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            connection = actor.connection;
            try { actor.connection = first.connection; reject(c, plan, "actor listener changed"); }
            finally { actor.connection = connection; }
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            last.setGameMode(GameType.CREATIVE); reject(c, plan, "target native mode changed"); last.setGameMode(GameType.SURVIVAL);
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            actor.setGameMode(GameType.SPECTATOR); reject(c, plan, "actor native mode changed"); actor.setGameMode(GameType.SURVIVAL);
            plan = PlayerStatBatch.prepare(actor, List.of(first, last), "attack_damage", 40);
            GameModes.TRANSITIONS.add(last.getUUID());
            try { reject(c, plan, "target is transferring"); } finally { GameModes.TRANSITIONS.remove(last.getUUID()); }
            c.assertValueEqual(base(first), 1d, "Session failures leave first member unchanged");
            c.assertValueEqual(base(last), 1d, "Session failures leave last member unchanged");
            c.assertTrue(historyEmpty(first) && historyEmpty(last), "Session failures create no original history");
        } finally { cleanup(actor); cleanup(first); cleanup(last); }
        c.succeed();
    }

    @GameTest public void actualWorldAndModeTransferInvalidatesActorAndTargetReviews(GameTestHelper c) {
        var actor = player(c, "batch-world-op", true);
        var target = player(c, "batch-world-target", false);
        try {
            var plan = PlayerStatBatch.prepare(actor, List.of(actor, target), "attack_damage", 40);
            var start = target.level();
            GameModes.switchNow(target, GameModes.Mode.CREATIVE, null);
            c.assertTrue(target.level() != start, "Target uses actual mode dimension transfer");
            reject(c, plan, "target world/mode changed");
            GameModes.switchNow(target, GameModes.Mode.SURVIVAL, null);
            plan = PlayerStatBatch.prepare(actor, List.of(actor, target), "attack_damage", 40);
            GameModes.switchNow(actor, GameModes.Mode.CREATIVE, null);
            reject(c, plan, "actor world/mode changed");
            c.assertValueEqual(base(actor), 1d, "Self inclusion cannot bypass actor session binding");
            c.assertValueEqual(base(target), 1d, "Other selected player remains unchanged");
        } finally { cleanup(actor); cleanup(target); }
        c.succeed();
    }

    @GameTest public void healthBatchReviewsPerPlayerCapacityIncludingActorSelfAndNativeDiskSave(GameTestHelper c) throws Exception {
        var actor = player(c, "batch-health-op", true);
        var target = player(c, "batch-health-target", false);
        var excluded = player(c, "batch-health-excluded", false);
        var penalty = new AttributeModifier(Identifier.fromNamespaceAndPath("infinity_test", "batch_health_penalty"),
            -100, AttributeModifier.Operation.ADD_VALUE);
        try {
            c.assertTrue(AdminStats.set(actor.createCommandSourceStack(), target, "max_health", 200).success(), "Prepare distinct existing target capacity");
            target.getAttribute(Attributes.MAX_HEALTH).addPermanentModifier(penalty);
            for (var p : List.of(actor, target, excluded)) p.setHealth(12);
            GameModes.state(target).putString("batch_unrelated", "preserve player settings");
            target.getInventory().setItem(8, new ItemStack(Items.DIAMOND, 7));
            var beforeActor = save(actor); var beforeTarget = save(target);
            var plan = PlayerStatBatch.prepare(actor, List.of(actor, target), "health", 5000);
            c.assertTrue(plan.invalidReason().isEmpty(), "Same current health can have different capacities and modifiers");
            c.assertValueEqual(plan.changes().getFirst().changes().getFirst(), new AdminStats.Adjustment("max_health", 20, 5000), "Actor capacity review is exact");
            var targetCapacity = plan.changes().get(1).changes().getFirst();
            c.assertValueEqual(targetCapacity.id(), "max_health", "Second review includes its own native capacity");
            c.assertValueEqual(targetCapacity.before(), 200d, "Second review preserves existing base");
            c.assertTrue(targetCapacity.after() >= 5099 && targetCapacity.after() < 5102, "Second review compensates its own negative modifier within native float/search precision");
            c.assertValueEqual(save(actor), beforeActor, "Capacity probing never mutates actor");
            c.assertValueEqual(save(target), beforeTarget, "Capacity probing never mutates target");
            c.assertTrue(plan.apply().success(), "Nonlethal self-inclusive batch applies");
            for (var p : List.of(actor, target)) {
                c.assertValueEqual(p.getHealth(), 5000f, "Native health set after its own reviewed capacity");
                c.assertTrue(p.getMaxHealth() >= 5000 && p.getMaxHealth() < 5002, "Effective capacity accommodates the exact native health");
                PlayerPersistence.save(p);
                var disk = NbtIo.readCompressed(GameModes.savedPlayerFile(p.level().getServer(), p.getUUID()), NbtAccounter.create(64L * 1024 * 1024));
                var restored = new ServerPlayer(p.level().getServer(), p.level(), p.getGameProfile(), p.clientInformation());
                restored.load(TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), disk));
                c.assertValueEqual(restored.getHealth(), 5000f, "Ordinary individual native player file retains high current health");
                c.assertValueEqual(restored.getMaxHealth(), p.getMaxHealth(), "Expanded native capacity survives player disk read exactly");
                c.assertValueEqual(AdminStats.original(restored, AdminStats.find(restored, "max_health")), 20d, "First saved original survives disk load");
            }
            c.assertValueEqual(target.getAttribute(Attributes.MAX_HEALTH).getModifier(penalty.id()), penalty, "Capacity modifier preserved");
            c.assertValueEqual(GameModes.state(target).getStringOr("batch_unrelated", ""), "preserve player settings", "Other player metadata preserved");
            c.assertValueEqual(target.getInventory().getItem(8).getCount(), 7, "Inventory preserved");
            c.assertValueEqual(excluded.getHealth(), 12f, "Unselected equal-health player not healed");
            c.assertValueEqual(excluded.getMaxHealth(), 20f, "Unselected capacity unchanged");
            c.assertTrue(AdminStats.reset(actor.createCommandSourceStack(), target, "max_health").success(), "Existing individual reset restores the original, not batch intermediate value");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.MAX_HEALTH), 20d, "Original base retained across prior and batch edits");
        } finally { cleanup(actor); cleanup(target); cleanup(excluded); }
        c.succeed();
    }

    @GameTest public void impossibleLastAbsorptionCapacityNeverPartiallyGrowsEarlierMembers(GameTestHelper c) {
        var actor = player(c, "batch-absorb-op", true);
        var first = player(c, "batch-absorb-a", false);
        var last = player(c, "batch-absorb-b", false);
        var id = Identifier.fromNamespaceAndPath("infinity_test", "batch_absorb_penalty");
        try {
            var capacity = last.getAttribute(Attributes.MAX_ABSORPTION);
            capacity.addPermanentModifier(new AttributeModifier(id, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            reject(c, PlayerStatBatch.prepare(actor, List.of(first, last), "absorption", 100), "last player's impossible capacity");
            c.assertValueEqual(first.getMaxAbsorption(), 0f, "Earlier capacity remains zero");
            c.assertValueEqual(first.getAbsorptionAmount(), 0f, "Earlier hearts remain zero");
            c.assertTrue(historyEmpty(first) && historyEmpty(last), "No partial original records");
            capacity.removeModifier(id);
            var penalty = new AttributeModifier(id, -10, AttributeModifier.Operation.ADD_VALUE);
            capacity.addPermanentModifier(penalty);
            var plan = PlayerStatBatch.prepare(actor, List.of(first, last), "absorption", 100.25);
            double plannedBase = plan.changes().get(1).changes().getFirst().after();
            c.assertTrue(plannedBase > 110 && plannedBase < 112, "Per-target absorption capacity accounts for modifier within native float/search precision");
            c.assertTrue(plan.apply().success(), "Valid capacities then apply together");
            c.assertValueEqual(first.getAbsorptionAmount(), 100.25f, "First native absorption amount");
            c.assertValueEqual(last.getAbsorptionAmount(), 100.25f, "Second native absorption amount");
            c.assertValueEqual(capacity.getModifier(id), penalty, "External penalty unchanged");
        } finally { cleanup(actor); cleanup(first); cleanup(last); }
        c.succeed();
    }

    @GameTest public void movementNormalizationVitalsAndExistingOriginalResetStayNative(GameTestHelper c) {
        var actor = player(c, "batch-values-op", true);
        var first = player(c, "batch-values-a", false);
        var last = player(c, "batch-values-b", false);
        try {
            double original = first.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
            float walk = first.getAbilities().getWalkingSpeed();
            var plan = PlayerStatBatch.prepare(actor, List.of(first, last), "movement_speed", .37);
            c.assertValueEqual(plan.requested(), (double) (float) .37, "Preview uses native float movement normalization");
            c.assertTrue(plan.apply().success(), "Movement batch applies");
            for (var p : List.of(first, last)) {
                c.assertValueEqual(p.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), plan.requested(), "Native movement base matches reviewed number");
                c.assertValueEqual((double) p.getAbilities().getWalkingSpeed(), plan.requested(), "Native walking ability remains paired");
            }
            c.assertTrue(PlayerStatBatch.prepare(actor, List.of(first, last), "movement_speed", .45).apply().success(), "Repeated batch edit succeeds");
            for (var p : List.of(first, last)) c.assertValueEqual(AdminStats.original(p, AdminStats.find(p, "movement_speed")), original, "Repeated edits retain first native original");
            for (var entry : List.of(new AdminStats.Adjustment("food", 0, 12), new AdminStats.Adjustment("saturation", 0, 1.25),
                    new AdminStats.Adjustment("exhaustion", 0, .1), new AdminStats.Adjustment("xp_level", 0, 30))) {
                var edit = PlayerStatBatch.prepare(actor, List.of(first, last), entry.id(), entry.after());
                c.assertTrue(edit.apply().success(), "Native one-time group value: " + entry.id());
            }
            c.assertValueEqual(first.totalExperience, 1395, "Native XP total agrees with level 30");
            for (var p : List.of(first, last)) {
                var stored = save(p);
                c.assertTrue(AdminStats.resetAll(actor.createCommandSourceStack(), p).success(), "Existing reset-all remains available");
                p.load(TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), stored));
                c.assertValueEqual(p.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), (double) (float) .45, "Native serialization restores edited movement");
                c.assertValueEqual(p.getFoodData().getFoodLevel(), 12, "Food survives native read");
                c.assertValueEqual(p.experienceLevel, 30, "XP survives native read");
                c.assertTrue(AdminStats.resetAll(actor.createCommandSourceStack(), p).success(), "Persisted original can still reset");
                c.assertValueEqual(p.getAttributeBaseValue(Attributes.MOVEMENT_SPEED), original, "Original movement restored");
                c.assertValueEqual(p.getAbilities().getWalkingSpeed(), walk, "Original ability restored independently");
                c.assertValueEqual(p.experienceLevel, 30, "Attribute reset does not roll back one-time XP");
            }
        } finally { cleanup(actor); cleanup(first); cleanup(last); }
        c.succeed();
    }

    private static final class ReconnectScenario extends NativeSessionFixture {
        Joined actor, target;
        PlayerStatBatch.Plan obsolete;
        ReconnectScenario(GameTestHelper c) { super(c); }
        @Override void start() {
            actor = join("group-real-op", 2); target = join("group-real-target", 6);
            actor.player.setGameMode(GameType.SURVIVAL); target.player.setGameMode(GameType.SURVIVAL);
            OperatorGameTests.level(actor.player, LevelBasedPermissionSet.OWNER);
            obsolete = PlayerStatBatch.prepare(actor.player, List.of(actor.player, target.player), "attack_damage", 40);
            c.assertTrue(obsolete.invalidReason().isEmpty(), "Initial real network sessions are eligible");
            var oldTarget = target.player;
            reconnect(target, replacement -> {
                target = replacement;
                c.assertTrue(target.player != oldTarget && target.player.getUUID().equals(oldTarget.getUUID()), "Native saved login replaces target session with same UUID");
                c.assertFalse(obsolete.apply().success(), "Old target session cannot authorize the new login");
                c.assertValueEqual(actor.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 1d, "Rejected reconnect leaves earlier actor unchanged");
                var fresh = PlayerStatBatch.prepare(actor.player, List.of(actor.player, target.player), "attack_damage", 40);
                c.assertTrue(fresh.apply().success(), "Explicit new review works with current target login");
                PlayerPersistence.save(actor.player); PlayerPersistence.save(target.player);
                obsolete = PlayerStatBatch.prepare(actor.player, List.of(actor.player, target.player), "attack_damage", 50);
                var oldActor = actor.player;
                reconnect(actor, newActor -> {
                    actor = newActor;
                    OperatorGameTests.level(actor.player, LevelBasedPermissionSet.OWNER);
                    c.assertTrue(actor.player != oldActor && actor.player.getUUID().equals(oldActor.getUUID()), "Actor also uses genuine saved login replacement");
                    c.assertFalse(obsolete.apply().success(), "Restored OP4 cannot revive an old actor connection's approval");
                    c.assertValueEqual(actor.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 40d, "Ordinary native save/rejoin retains successful group edit");
                    c.assertValueEqual(target.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE), 40d, "Rejected obsolete actor leaves other target unchanged");
                    c.assertValueEqual(AdminStats.original(actor.player, AdminStats.find(actor.player, "attack_damage")), 1d, "Original reset baseline survives genuine reconnect");
                    c.assertTrue(PlayerStatBatch.prepare(actor.player, List.of(actor.player, target.player), "attack_damage", 50).apply().success(), "Fresh reviewed intent after reconnect succeeds");
                    succeed();
                });
            });
        }
    }

    @GameTest(maxTicks = 1200) public void genuineSavedReconnectCannotReuseActorOrTargetSession(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c, () -> new ReconnectScenario(c));
    }
}

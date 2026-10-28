package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Native creation transactions only; every case owns an isolated roster and real entity set. */
public class HelperBatchGameTests {
    private record InsertionFailure(AgentCompanions helpers, UUID owner, Path blocked, AtomicReference<UUID> inserted) {}
    private static final AtomicReference<InsertionFailure> INSERTION_FAILURE = new AtomicReference<>();
    static {
        // Test-only, one-shot listener: unrelated worlds, owners and entities
        // never enter this fault. No production API or hook is introduced.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            var fault = INSERTION_FAILURE.get();
            if (fault == null || fault.helpers.server != world.getServer() || !AgentCompanions.isAgent(entity)) return;
            var record = fault.helpers.data.agents.get(entity.getStringUUID());
            if (record == null || !record.owner().equals(fault.owner.toString()) || !record.name().startsWith("helper-")) return;
            if (!INSERTION_FAILURE.compareAndSet(fault, null)) return;
            fault.inserted.set(entity.getUUID());
            try { Files.createDirectory(fault.blocked); Files.writeString(fault.blocked.resolve("block-rollback"), "after ownership save"); }
            catch (java.io.IOException failure) { throw new IllegalStateException("Could not establish the private rollback fault", failure); }
            throw new IllegalStateException("Exact-owner one-shot native entity insertion fault");
        });
    }
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper c;
        final AgentCompanions previous, helpers;
        final ServerPlayer owner;
        final Path directory;
        final BlockPos feet;
        Fixture(GameTestHelper c) throws Exception {
            this.c = c; var server = c.getLevel().getServer();
            previous = AgentCompanions.get(server);
            c.assertTrue(previous.data.agents.isEmpty(), "The exclusive batch fixture begins with no unrelated helper runtime");
            directory = Files.createTempDirectory("infinity-helper-batch-");
            helpers = new AgentCompanions(server, directory.resolve("agents.json"));
            AgentCompanions.INSTANCES.put(server, helpers);
            owner = new ModeGameTests().player(c, "batch-owner");
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.closeContainer(); owner.setGameMode(GameType.SURVIVAL); owner.getInventory().clearContent();
            feet = c.absolutePos(new BlockPos(20, 55, 20));
            for (var at : BlockPos.betweenClosed(feet.offset(-17,-1,-17), feet.offset(17,3,17)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY()-1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            owner.setPos(Vec3.atBottomCenterOf(feet)); helpers.save();
        }
        void unloaded(UUID ownerId, int count, String prefix) {
            for (int i = 1; i <= count; i++) helpers.data.agents.put(UUID.randomUUID().toString(),
                new AgentCompanions.Agent(ownerId.toString(), prefix+i, AgentCompanions.Mode.STAY,
                    AgentCompanions.Profile.REGULAR, c.getLevel().dimension().identifier().toString(), feet.getX(), feet.getY(), feet.getZ()));
            helpers.save();
        }
        @Override public void close() throws Exception {
            HelperForms.ACTIVE.remove(owner.getUUID()); HelperForms.DELIVERIES.remove(owner.getUUID());
            for (var helper : new ArrayList<>(helpers.loaded.values())) { AgentAvatars.remove(helper); helper.discard(); }
            helpers.loaded.clear(); helpers.data.agents.clear();
            owner.closeContainer(); OperatorGameTests.deop(owner); c.getLevel().getServer().getPlayerList().remove(owner);
            if (AgentCompanions.INSTANCES.get(helpers.server) == helpers) AgentCompanions.INSTANCES.put(helpers.server, previous);
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void oneBatchFillsRemainingFiftyWithDistinctNativeHelpersAndKeepsExistingIdentity(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "my-existing"), 1, "The existing single-helper path still creates one helper");
            var old = f.helpers.owned(f.owner, "my-existing");
            var oldEntity = f.helpers.loaded.get(UUID.fromString(old.getKey()));
            oldEntity.setHealth(37); oldEntity.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
            var oldPosition = oldEntity.position();
            var gift = new ItemStack(Items.DIAMOND, 7); gift.set(DataComponents.CUSTOM_NAME, Component.literal("Keep my items"));
            f.owner.getInventory().setItem(8, gift.copy());
            int proposals = AgentActions.get(f.helpers.server).data.proposals.size();
            c.assertValueEqual(f.helpers.remaining(f.owner), 49, "The existing helper consumes one saved slot");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 49, AgentCompanions.Profile.ULTIMATE_FINALS), 49, "One call creates the entire exact requested batch");
            c.assertValueEqual(f.helpers.count(f.owner), 50, "The batch fills the owner's total to fifty, rather than adding fifty on top");
            c.assertValueEqual(f.helpers.loaded.size(), 50, "Every new roster entry has one real loaded helper");
            var ids = new HashSet<UUID>(); var names = new HashSet<String>();
            for (var entry : f.helpers.data.agents.entrySet()) {
                var entity = f.helpers.loaded.get(UUID.fromString(entry.getKey()));
                c.assertTrue(entity != null && c.getLevel().getEntity(entity.getUUID()) == entity && entity.isAlive(), "Every saved identity belongs to its actual native entity");
                c.assertTrue(ids.add(entity.getUUID()) && names.add(entry.getValue().name()), "No UUID or per-owner name is duplicated");
                if (!entry.getKey().equals(old.getKey())) {
                    c.assertValueEqual(entry.getValue().profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "The selected profile is saved at creation");
                    c.assertValueEqual(entry.getValue().mode(), AgentCompanions.Mode.FOLLOW, "New helpers follow immediately");
                    c.assertTrue(entity.entityTags().contains("infinity_owner_"+f.owner.getStringUUID()), "Native tags retain exact ownership");
                }
            }
            var bodies = new ArrayList<>(f.helpers.loaded.values());
            for (int i = 0; i < bodies.size(); i++) for (int j = i+1; j < bodies.size(); j++)
                c.assertFalse(bodies.get(i).getBoundingBox().intersects(bodies.get(j).getBoundingBox()), "Preflight never places two helpers in overlapping bodies");
            c.assertTrue(f.helpers.data.agents.get(old.getKey()) == old.getValue() && f.helpers.loaded.get(oldEntity.getUUID()) == oldEntity,
                "The original saved record and loaded entity objects are unchanged");
            c.assertTrue(oldEntity.getHealth() == 37 && oldEntity.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() == .31 && oldEntity.position().equals(oldPosition),
                "The batch preserves the old helper's health, edited attributes and position");
            c.assertTrue(ItemStack.isSameItemSameComponents(f.owner.getInventory().getItem(8), gift), "Creation never consumes or replaces player inventory");
            c.assertValueEqual(AgentActions.get(f.helpers.server).data.proposals.size(), proposals, "No AI command/player-target approval is created or supplied");
            c.assertTrue(f.helpers.playerTargets.isEmpty(), "Creating a combat squad never creates a player target");
            c.assertValueEqual(AgentCompanions.read(f.helpers.file).agents, f.helpers.data.agents, "Every created UUID/profile survives the actual roster file roundtrip");
            byte[] full = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "A second activation cannot create helper fifty-one");
            c.assertTrue(Arrays.equals(full, Files.readAllBytes(f.helpers.file)) && f.helpers.loaded.size() == 50, "Capacity refusal changes neither saved roster nor native entities");
        }
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 50, AgentCompanions.Profile.REGULAR), 50,
                "An empty owner can request the maximum fifty real helpers in a single activation");
            c.assertTrue(f.helpers.count(f.owner) == 50 && f.helpers.loaded.size() == 50 && f.helpers.remaining(f.owner) == 0,
                "The exact maximum creates fifty saved native entities and consumes all remaining capacity");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void unloadedAndOtherOwnersConsumeCapacityAndNeverGetReplacedByABatch(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 17, "helper-"); f.unloaded(UUID.randomUUID(), 26, "other-");
            var original = Map.copyOf(f.helpers.data.agents); byte[] before = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.remaining(f.owner), 7, "All forty-three saved unloaded helpers reserve server slots, including another owner");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 8, AgentCompanions.Profile.REGULAR), 0, "A stale requested quantity above remaining capacity is refused in full");
            c.assertTrue(Arrays.equals(before, Files.readAllBytes(f.helpers.file)) && f.helpers.loaded.isEmpty(), "Over-capacity request does not write or materialize any unloaded helper");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 7, AgentCompanions.Profile.PRIMITIVE), 7, "A batch uses exactly the remaining server capacity");
            c.assertValueEqual(f.helpers.count(f.owner), 24, "Own unloaded entries plus the new batch form the owner's total");
            c.assertValueEqual(f.helpers.data.agents.size(), 50, "Other owners' unloaded entries still count globally");
            c.assertTrue(original.entrySet().stream().allMatch(e -> f.helpers.data.agents.get(e.getKey()) == e.getValue()), "No existing owner or other-owner record was rewritten or replaced");
            c.assertTrue(original.keySet().stream().noneMatch(id -> f.helpers.loaded.containsKey(UUID.fromString(id))), "The batch never recreates unloaded helpers");
            c.assertTrue(f.helpers.owned(f.owner, "helper-18") != null && f.helpers.owned(f.owner, "helper-24") != null, "Generated names skip saved unloaded names");
            c.assertValueEqual(f.helpers.remaining(f.owner), 0, "No owner can exceed the global cap");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void blockedOrWetPlacementRefusesTheEntireBatchWithoutPhantomOwnership(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            for (var at : BlockPos.betweenClosed(f.feet.offset(-17,0,-17), f.feet.offset(17,2,17)))
                if (at.getX() != f.feet.getX() || at.getZ() != f.feet.getZ()) c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            // One valid cell is insufficient for an exact request for two.
            var one = f.feet.offset(2,0,0); for (int y = 0; y < 3; y++) c.getLevel().setBlockAndUpdate(one.above(y), Blocks.AIR.defaultBlockState());
            byte[] before = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR), 0, "An incomplete placement plan cannot create a smaller partial squad");
            c.assertTrue(f.helpers.data.agents.isEmpty() && f.helpers.loaded.isEmpty() && Arrays.equals(before, Files.readAllBytes(f.helpers.file)), "Failed planning leaves no saved identity, native entity or disk write");
            c.getLevel().setBlockAndUpdate(one, Blocks.WATER.defaultBlockState());
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "Water is not a safe native helper placement");
            c.assertTrue(f.helpers.data.agents.isEmpty() && f.helpers.loaded.isEmpty(), "Wet placement cannot create phantom helpers");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void actualAtomicOwnershipSaveFailureSpawnsNothingAndRetryDoesNotDuplicate(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.helpers.spawn(f.owner, "kept"); var old = f.helpers.owned(f.owner, "kept");
            var entity = f.helpers.loaded.get(UUID.fromString(old.getKey())); entity.setHealth(29);
            var before = Map.copyOf(f.helpers.data.agents); byte[] bytes = Files.readAllBytes(f.helpers.file);
            Path blocked = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".tmp");
            Files.createDirectory(blocked); Files.writeString(blocked.resolve("block-write"), "real filesystem failure");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.ULTIMATE_FINALS), 0, "The real native ownership writer fails before any entity is added");
            c.assertTrue(f.helpers.data.agents.equals(before) && f.helpers.loaded.size() == 1 && f.helpers.loaded.get(entity.getUUID()) == entity && entity.getHealth() == 29,
                "Failed save rolls back only staged identities and preserves the existing helper");
            c.assertTrue(Arrays.equals(bytes, Files.readAllBytes(f.helpers.file)), "Atomic writer failure retains the original ownership file byte for byte");
            Files.delete(blocked.resolve("block-write")); Files.delete(blocked);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.ULTIMATE_FINALS), 3, "An explicit retry after storage repair creates exactly three helpers");
            c.assertValueEqual(f.helpers.count(f.owner), 4, "The failed attempt reserved no phantom duplicate names or slots");
            c.assertTrue(f.helpers.owned(f.owner, "helper-1") != null && f.helpers.owned(f.owner, "helper-3") != null && f.helpers.owned(f.owner, "helper-4") == null,
                "Retry starts from the original free names without missing or duplicate identities");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void singleSpawnOwnershipSaveFailureLeavesNoGhostForLaterSaves(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 24, "own-"); f.unloaded(UUID.randomUUID(), 24, "other-");
            f.helpers.spawn(f.owner, "kept"); var kept = f.helpers.owned(f.owner, "kept");
            var entity = f.helpers.loaded.get(UUID.fromString(kept.getKey())); entity.setHealth(33);
            var before = Map.copyOf(f.helpers.data.agents); byte[] bytes = Files.readAllBytes(f.helpers.file);
            Path previous = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".previous");
            c.assertValueEqual(f.helpers.remaining(f.owner), 1, "Exactly one server slot remains before the failing create");
            Path blocked = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".tmp");
            Files.createDirectory(blocked); Files.writeString(blocked.resolve("block-write"), "real filesystem failure");
            c.assertValueEqual(f.helpers.spawn(f.owner, "retry"), 0, "The real atomic ownership writer fails and the single create reports no success");
            c.assertTrue(f.helpers.data.agents.equals(before) && f.helpers.owned(f.owner, "retry") == null, "No ghost identity remains in the in-memory roster");
            c.assertTrue(before.entrySet().stream().allMatch(e -> f.helpers.data.agents.get(e.getKey()) == e.getValue()), "Every existing record object, loaded or unloaded, is kept");
            c.assertTrue(f.helpers.loaded.size() == 1 && f.helpers.loaded.get(entity.getUUID()) == entity && entity.isAlive() && entity.getHealth() == 33,
                "No native entity is added and the existing helper is untouched");
            c.assertTrue(Arrays.equals(bytes, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "The roster file is unchanged and .previous holds the last good roster, never the failed identity");
            c.assertValueEqual(f.helpers.remaining(f.owner), 1, "The failed attempt reserves no server slot");
            c.assertFalse(f.helpers.creationRecoveryRequired, "A clean synchronous failure leaves nothing on disk that needs manual recovery");
            Files.delete(blocked.resolve("block-write")); Files.delete(blocked);
            c.assertValueEqual(f.helpers.profile(f.owner, "kept", AgentCompanions.Profile.DEBUG), 1, "An unrelated roster change saves after storage repair");
            c.assertValueEqual(AgentCompanions.read(f.helpers.file).agents.keySet(), before.keySet(), "A later successful save persists only the existing identities");
            OperatorGameTests.level(f.owner, LevelBasedPermissionSet.ADMIN);
            f.helpers.spawn(f.owner, "retry");
            c.assertTrue(f.helpers.owned(f.owner, "retry") == null && f.helpers.data.agents.keySet().equals(before.keySet()), "OP3 still cannot create a helper after the failure");
            OperatorGameTests.level(f.owner, LevelBasedPermissionSet.OWNER);
            c.assertValueEqual(f.helpers.spawn(f.owner, "retry"), 1, "Retry reuses the failed name and the last server slot");
            var retry = f.helpers.owned(f.owner, "retry"); var retried = f.helpers.loaded.get(UUID.fromString(retry.getKey()));
            c.assertTrue(retried != null && c.getLevel().getEntity(retried.getUUID()) == retried, "The retried identity belongs to a real native entity");
            c.assertTrue(f.helpers.data.agents.size() == 50 && f.helpers.remaining(f.owner) == 0, "The server reaches exactly fifty without a phantom entry");
            var saved = AgentCompanions.read(f.helpers.file).agents;
            c.assertValueEqual(saved, f.helpers.data.agents, "The saved roster matches memory after the successful retry");
            c.assertTrue(saved.keySet().stream().filter(id -> !before.containsKey(id)).toList().equals(java.util.List.of(retry.getKey())),
                "The only new saved identity is the retried helper");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void failedNativeInsertionAndOwnershipRollbackLatchAllFurtherCreationUntilManualRecovery(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.helpers.spawn(f.owner, "kept-recovery"); var kept = f.helpers.owned(f.owner, "kept-recovery");
            var entity = f.helpers.loaded.get(UUID.fromString(kept.getKey())); entity.setHealth(31);
            var before = Map.copyOf(f.helpers.data.agents); byte[] bytes = Files.readAllBytes(f.helpers.file);
            var pendingForm = new AtomicReference<org.geysermc.cumulus.form.Form>(); int[] formActions = {0};
            HelperForms.show(f.owner,"Review helper profile","A pre-failure native helper screen",
                java.util.List.of(new HelperForms.Choice("Change profile",()->formActions[0]++)),()->true,
                form->{pendingForm.set(form);return true;});
            var inserted = new AtomicReference<UUID>(); Path blocked = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".tmp");
            var fault = new InsertionFailure(f.helpers, f.owner.getUUID(), blocked, inserted);
            c.assertTrue(INSERTION_FAILURE.compareAndSet(null, fault), "The private exact-owner fault is armed once");
            boolean failed = false;
            try { f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.PRIMITIVE); }
            catch (IllegalStateException expected) { failed = expected.getMessage().contains("ownership rollback failed"); }
            finally { INSERTION_FAILURE.compareAndSet(fault, null); }
            c.assertTrue(failed && inserted.get() != null, "The real native entity callback failed after the ownership write and the real rollback writer then failed");
            c.assertTrue(f.helpers.creationRecoveryRequired && f.helpers.remaining(f.owner) == 0, "The double failure latches creation closed for the whole runtime");
            c.assertTrue(f.helpers.data.agents.equals(before) && f.helpers.loaded.size() == 1 && f.helpers.loaded.get(entity.getUUID()) == entity && entity.getHealth() == 31,
                "Rollback removes only the new batch's native state, preserving the existing helper object and health");
            c.assertTrue(c.getLevel().getEntity(inserted.get()) == null, "The partially inserted new native helper is removed");
            Path previous = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".previous");
            c.assertTrue(Arrays.equals(bytes, Files.readAllBytes(previous)), "The original recovery copy is never replaced by the failed staged batch");
            byte[] unresolved = Files.readAllBytes(f.helpers.file);
            c.assertFalse(Arrays.equals(bytes, unresolved), "The unresolved staged ownership file is retained as recovery evidence rather than hidden");
            Files.delete(blocked.resolve("block-rollback")); Files.delete(blocked);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "Repairing storage alone does not silently clear the recovery latch");
            f.helpers.spawn(f.owner, "must-not-appear");
            c.assertTrue(f.helpers.owned(f.owner,"must-not-appear") == null && f.helpers.data.agents.equals(before) && f.helpers.loaded.size() == 1,
                "The original single-helper path is blocked too, without modifying existing ownership");
            c.assertTrue(Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "Subsequent requests cannot overwrite either the unresolved roster or its original recovery copy");
            var pending = pendingForm.get();
            org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(pending).handleFormResponse(pending,"0");
            c.assertValueEqual(formActions[0],0,"A real encoded pre-failure touch response is consumed without applying or claiming a helper menu action");
            c.assertValueEqual(f.helpers.profile(f.owner,"kept-recovery",AgentCompanions.Profile.DEBUG),0,"Profile changes are refused before mutation while ownership is frozen");
            c.assertValueEqual(f.helpers.mode(f.owner,"kept-recovery",AgentCompanions.Mode.STAY),0,"Movement changes are refused before mutation");
            c.assertFalse(f.helpers.enableCombos(f.owner,"kept-recovery"),"Combo activation cannot rewrite a frozen record");
            c.assertValueEqual(f.helpers.squad(f.owner,AgentCompanions.Mode.STAY),0,"Squad movement cannot rewrite frozen records");
            c.assertValueEqual(f.helpers.squadProfile(f.owner,AgentCompanions.Profile.ULTIMATE_FINALS),0,"Squad profiles cannot rewrite frozen records");
            c.assertValueEqual(f.helpers.recall(f.owner,"kept-recovery"),0,"Recall cannot move or rewrite a frozen helper");
            c.assertValueEqual(f.helpers.recallSquad(f.owner),0,"The squad recall wrapper cannot claim a completed blocked move");
            c.assertValueEqual(f.helpers.dismiss(f.owner,"kept-recovery"),0,"Dismissal cannot remove a frozen ownership record or entity");
            c.assertTrue(f.helpers.data.agents.get(kept.getKey()) == kept.getValue() && entity.isAlive() && entity.getHealth() == 31,
                "Every refused existing-helper operation preserves the exact record and native entity");
            entity.setDeltaMovement(.3,0,.2); f.helpers.control(entity,kept.getValue(),f.helpers.server.getTickCount());
            c.assertTrue(entity.getDeltaMovement().lengthSqr() == 0 && entity.getTarget() == null && !entity.isUsingItem() && !entity.isFallFlying(),
                "The native controller safely pauses movement, attacks, item use and gliding during recovery");
            AgentActions.get(f.helpers.server).ceasefire(f.owner);
            c.assertTrue(f.helpers.playerTargets.isEmpty(),"Direct ceasefire remains usable without writing helper ownership");
            f.helpers.save();
            c.assertTrue(Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "A defensive save call cannot rotate recovery evidence even after the filesystem is writable");
            c.assertTrue(entity.hurtServer(c.getLevel(),entity.damageSources().genericKill(),10000),"An actual native lethal hit reaches the existing helper");
            c.assertFalse(entity.isAlive(),"The native helper death callback runs");
            c.assertTrue(f.helpers.data.agents.get(kept.getKey()) == kept.getValue() && !f.helpers.loaded.containsKey(entity.getUUID()),
                "Automatic death clears loaded state while retaining the frozen ownership record for manual recovery");
            c.assertTrue(Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "Actual native death neither throws nor destroys the unresolved roster or its last-good recovery file");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void batchCreationRechecksNativeOwnerPermissionsLifeAndBounds(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            byte[] before = Files.readAllBytes(f.helpers.file);
            for (int n : new int[]{-1,0,51,Integer.MAX_VALUE}) c.assertValueEqual(f.helpers.spawnBatch(f.owner,n,AgentCompanions.Profile.REGULAR),0,"Invalid quantity is refused: "+n);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,null),0,"An invalid profile is refused");
            OperatorGameTests.level(f.owner,LevelBasedPermissionSet.ADMIN);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,AgentCompanions.Profile.REGULAR),0,"OP3 cannot use batch creation");
            OperatorGameTests.level(f.owner,LevelBasedPermissionSet.OWNER);f.owner.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,AgentCompanions.Profile.REGULAR),0,"Spectators cannot create helpers");
            f.owner.setGameMode(GameType.SURVIVAL);f.owner.setHealth(0);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,AgentCompanions.Profile.REGULAR),0,"Dead owners cannot create helpers");
            f.owner.setHealth(20);
            c.assertTrue(f.helpers.data.agents.isEmpty()&&f.helpers.loaded.isEmpty()&&Arrays.equals(before,Files.readAllBytes(f.helpers.file)),"Denied requests never write or create entities");
        }
        c.succeed();
    }
}

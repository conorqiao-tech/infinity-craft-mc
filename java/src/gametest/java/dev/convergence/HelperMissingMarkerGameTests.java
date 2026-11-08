package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.Vec3;

/** Synthetic roster collisions and missing helper markers; no live world or backup. */
public class HelperMissingMarkerGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper c;
        final AgentCompanions previous, helpers;
        final Path directory;
        final UUID owner;
        final ServerPlayer player;
        final List<Entity> entities = new ArrayList<>();

        Fixture(GameTestHelper c) throws Exception { this(c, HelperPersistence.NATIVE, false); }
        Fixture(GameTestHelper c, HelperPersistence.Io io, boolean withPlayer) throws Exception {
            this.c = c;
            var server = c.getLevel().getServer();
            previous = AgentCompanions.get(server);
            c.assertTrue(previous.data.agents.isEmpty(), "The isolated fixture starts without unrelated roster entries");
            directory = Files.createTempDirectory("infinity-missing-marker-");
            helpers = new AgentCompanions(server, directory.resolve("agents.json"), Clock.systemUTC(), new HelperPersistence(io));
            AgentCompanions.INSTANCES.put(server, helpers);
            player = withPlayer ? new ModeGameTests().player(c, "missing-marker-owner") : null;
            if (player != null) OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
            owner = player == null ? UUID.randomUUID() : player.getUUID();
            helpers.save();
        }
        UUID record() {
            UUID id = UUID.randomUUID();
            helpers.data.agents.put(id.toString(), new AgentCompanions.Agent(owner.toString(), "kept",
                AgentCompanions.Mode.STAY, AgentCompanions.Profile.REGULAR, c.getLevel().dimension().identifier().toString(), 0, 70, 0));
            helpers.save();
            return id;
        }
        IronGolem body(UUID id, boolean marker) {
            var golem = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.LOAD);
            golem.setUUID(id);
            if (marker) golem.addTag(AgentCompanions.TAG);
            golem.addTag("infinity_owner_" + owner);
            golem.addTag("infinity_name_kept");
            golem.setCustomName(Component.literal("Synthetic edited name"));
            golem.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(15);
            golem.setPos(Vec3.atBottomCenterOf(c.absolutePos(new BlockPos(5, 5, 5))));
            entities.add(golem);
            return golem;
        }
        void add(Entity entity) {
            if (!entities.contains(entity)) entities.add(entity);
            c.assertTrue(c.getLevel().addFreshEntity(entity), "A synthetic entity reaches the native load callback");
        }
        @Override public void close() throws Exception {
            for (var entity : entities) if (!entity.isRemoved()) entity.discard();
            helpers.loaded.clear(); helpers.data.agents.clear();
            if (player != null) {
                player.closeContainer(); OperatorGameTests.deop(player);
                helpers.server.getPlayerList().remove(player);
            }
            if (AgentCompanions.INSTANCES.get(helpers.server) == helpers) AgentCompanions.INSTANCES.put(helpers.server, previous);
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    /** A test-only callback at the roster publication point. */
    private static final class OnRosterMove implements HelperPersistence.Io {
        Path roster;
        Runnable afterMove;
        @Override public void copy(Path from, Path to) throws IOException { HelperPersistence.NATIVE.copy(from, to); }
        @Override public void writeDurably(Path path, byte[] bytes) throws IOException { HelperPersistence.NATIVE.writeDurably(path, bytes); }
        @Override public void atomicMove(Path from, Path to) throws IOException {
            HelperPersistence.NATIVE.atomicMove(from, to);
            if (to.equals(roster) && afterMove != null) { var action = afterMove; afterMove = null; action.run(); }
        }
        @Override public void delete(Path path) throws IOException { HelperPersistence.NATIVE.delete(path); }
        @Override public void syncFile(Path path) throws IOException { HelperPersistence.NATIVE.syncFile(path); }
        @Override public void syncDirectory(Path directory) throws IOException { HelperPersistence.NATIVE.syncDirectory(directory); }
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void modeCommandBeforeTickDoesNotRetagAnUnmarkedBody(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, HelperPersistence.NATIVE, true)) {
            UUID id = f.record(); var body = f.body(id, true); f.add(body);
            body.setCustomName(Component.literal("Synthetic command edit"));
            body.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(15);
            body.removeTag(AgentCompanions.TAG);
            byte[] roster = Files.readAllBytes(f.helpers.file);
            byte[] previous = Files.readAllBytes(HelperPersistence.previousFile(f.helpers.file));
            int result = f.helpers.mode(f.player, "kept", AgentCompanions.Mode.FOLLOW);
            c.assertTrue(result == 0 && f.helpers.creationRecoveryRequired && !AgentCompanions.isAgent(body)
                    && body.getCustomName().getString().equals("Synthetic command edit")
                    && body.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 15
                    && Arrays.equals(roster, Files.readAllBytes(f.helpers.file))
                    && Arrays.equals(previous, Files.readAllBytes(HelperPersistence.previousFile(f.helpers.file))),
                "A command before the next tick must freeze without saving or retagging the body");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void modeCommandFreezesIfMarkerDisappearsDuringRosterPublication(GameTestHelper c) throws Exception {
        var io = new OnRosterMove();
        try (var f = new Fixture(c, io, true)) {
            UUID id = f.record(); var body = f.body(id, true); f.add(body);
            body.setCustomName(Component.literal("Synthetic publication edit"));
            body.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(15);
            byte[] roster = Files.readAllBytes(f.helpers.file);
            io.roster = f.helpers.file; io.afterMove = () -> body.removeTag(AgentCompanions.TAG);
            int result = f.helpers.mode(f.player, "kept", AgentCompanions.Mode.FOLLOW);
            c.assertTrue(result == 0 && f.helpers.creationRecoveryRequired && !AgentCompanions.isAgent(body)
                    && body.getCustomName().getString().equals("Synthetic publication edit")
                    && body.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 15
                    && f.helpers.data.agents.get(id.toString()).mode() == AgentCompanions.Mode.STAY
                    && Arrays.equals(roster, Files.readAllBytes(HelperPersistence.previousFile(f.helpers.file)))
                    && !Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                "A detected mid-write marker loss freezes before retagging; the newly published roster and its prior copy are both kept");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void dismissalKeepsBodyIfMarkerDisappearsDuringRosterPublication(GameTestHelper c) throws Exception {
        var io = new OnRosterMove();
        try (var f = new Fixture(c, io, true)) {
            UUID id = f.record(); var body = f.body(id, true); f.add(body);
            body.setCustomName(Component.literal("Synthetic dismissal edit"));
            body.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(15);
            byte[] roster = Files.readAllBytes(f.helpers.file);
            var victim = EntityTypes.PIG.create(c.getLevel(), EntitySpawnReason.LOAD);
            boolean[] hurt = {false}, couldAttack = {false};
            io.roster = f.helpers.file; io.afterMove = () -> {
                body.removeTag(AgentCompanions.TAG);
                hurt[0] = body.hurtServer(c.getLevel(), body.damageSources().generic(), 5);
                couldAttack[0] = AgentCompanions.allowDamage(victim, body.damageSources().mobAttack(body));
            };
            int result = f.helpers.dismiss(f.player, "kept");
            c.assertTrue(result == 0 && f.helpers.creationRecoveryRequired && !body.isRemoved() && !hurt[0] && !couldAttack[0]
                    && !AgentCompanions.isAgent(body) && f.helpers.loaded.get(id) == body
                    && body.getCustomName().getString().equals("Synthetic dismissal edit")
                    && body.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 15
                    && f.helpers.data.agents.containsKey(id.toString())
                    && !f.helpers.provenRemoved.contains(id.toString())
                    && Files.exists(HelperPersistence.recoveryMarker(f.helpers.file))
                    && Arrays.equals(roster, Files.readAllBytes(HelperPersistence.previousFile(f.helpers.file)))
                    && !Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                "An ambiguous guarded dismissal shields and retains its body, previous roster, new on-disk roster and recovery guard; hurt="
                    + hurt[0] + ", outgoing allowed=" + couldAttack[0]);
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void nativeDeathAfterMarkerLossFreezesAndKeepsRoster(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            UUID id = f.record(); var body = f.body(id, true); f.add(body);
            byte[] roster = Files.readAllBytes(f.helpers.file);
            body.removeTag(AgentCompanions.TAG);
            c.assertTrue(body.hurtServer(c.getLevel(), body.damageSources().genericKill(), 10000) && !body.isAlive(),
                "An explicit bypass causes a real synthetic native death");
            c.assertTrue(f.helpers.creationRecoveryRequired && f.helpers.data.agents.containsKey(id.toString())
                    && !f.helpers.provenRemoved.contains(id.toString())
                    && Files.exists(HelperPersistence.recoveryMarker(f.helpers.file))
                    && Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                "Marker loss observed at native death freezes and retains the roster and recovery note");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void missingMarkerOnNativeLoadFreezesAndPreservesTheBody(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            UUID id = f.record(); byte[] saved = Files.readAllBytes(f.helpers.file);
            var body = f.body(id, false); var tags = Set.copyOf(body.entityTags());
            body.setPersistentAngerEndTime(4321);
            boolean persistence = body.isPersistenceRequired(), noAi = body.isNoAi();
            f.add(body);
            float before = body.getHealth();
            boolean damaged = body.hurtServer(c.getLevel(), body.damageSources().generic(), 5);
            var victim = EntityTypes.PIG.create(c.getLevel(), EntitySpawnReason.LOAD);
            boolean canAttack = AgentCompanions.allowDamage(victim, body.damageSources().mobAttack(body));
            c.assertTrue(f.helpers.creationRecoveryRequired && !damaged && !canAttack,
                "A missing marker with a roster UUID freezes, shields the body and blocks outgoing harm: frozen="
                    + f.helpers.creationRecoveryRequired + ", damaged=" + damaged + ", outgoing allowed=" + canAttack);
            c.assertTrue(!body.isRemoved() && body.getHealth() == before && body.entityTags().equals(tags)
                    && body.getCustomName().getString().equals("Synthetic edited name")
                    && body.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 15
                    && body.getPersistentAngerEndTime() == 4321 && body.isPersistenceRequired() == persistence && body.isNoAi() == noAi
                    && Arrays.equals(saved, Files.readAllBytes(f.helpers.file)),
                "The uncertain body and roster stay unchanged for host review");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void markerRemovedFromLoadedBodyFreezesAndDoesNotRewrite(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            UUID id = f.record(); var body = f.body(id, true); f.add(body);
            c.assertTrue(f.helpers.loaded.get(id) == body, "The tagged helper is registered before the synthetic mutation");
            body.setCustomName(Component.literal("Synthetic later edit"));
            body.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(15);
            body.setPersistentAngerEndTime(4321);
            boolean persistence = body.isPersistenceRequired(), noAi = body.isNoAi();
            body.removeTag(AgentCompanions.TAG); var tags = Set.copyOf(body.entityTags());
            var victim = EntityTypes.PIG.create(c.getLevel(), EntitySpawnReason.LOAD);
            boolean canAttack = AgentCompanions.allowDamage(victim, body.damageSources().mobAttack(body));
            float before = body.getHealth();
            boolean damaged = body.hurtServer(c.getLevel(), body.damageSources().generic(), 5);
            f.helpers.aerial.put(id, new AgentCompanions.Aerial(f.helpers.data.agents.get(id.toString()), victim,
                body.position(), f.helpers.server.getTickCount()));
            f.helpers.tick();
            c.assertFalse(f.helpers.aerial.containsKey(id), "Marker loss stops the synthetic aerial plan before its next update");
            f.helpers.controlLoaded();
            c.assertTrue(f.helpers.creationRecoveryRequired && !damaged && !canAttack,
                "Marker loss is guarded before the next control pass: frozen=" + f.helpers.creationRecoveryRequired
                    + ", damaged=" + damaged + ", outgoing allowed=" + canAttack);
            c.assertTrue(body.getHealth() == before && body.entityTags().equals(tags)
                    && body.getCustomName().getString().equals("Synthetic later edit")
                    && body.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 15
                    && body.getPersistentAngerEndTime() == 4321 && body.isPersistenceRequired() == persistence && body.isNoAi() == noAi,
                "A marker-loss freeze does not recreate tags or overwrite edited fields");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void differentEntityKindWithRosterUuidFreezesWithoutEditingIt(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            UUID id = f.record(); byte[] saved = Files.readAllBytes(f.helpers.file);
            var pig = EntityTypes.PIG.create(c.getLevel(), EntitySpawnReason.LOAD);
            pig.setUUID(id); pig.setCustomName(Component.literal("Synthetic other kind"));
            pig.setPos(Vec3.atBottomCenterOf(c.absolutePos(new BlockPos(5, 5, 5))));
            var tags = Set.copyOf(pig.entityTags()); f.add(pig);
            c.assertTrue(f.helpers.creationRecoveryRequired && !pig.isRemoved() && pig.entityTags().equals(tags)
                    && pig.getCustomName().getString().equals("Synthetic other kind")
                    && Arrays.equals(saved, Files.readAllBytes(f.helpers.file)),
                "A different entity kind claiming a roster UUID freezes without editing the body or roster");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void startupScanDetectsBodyLoadedBeforeTheHelperRuntime(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            UUID id = f.record(); var body = f.body(id, false); var tags = Set.copyOf(body.entityTags());
            AgentCompanions.INSTANCES.remove(f.helpers.server);
            try { f.add(body); }
            finally { AgentCompanions.INSTANCES.put(f.helpers.server, f.helpers); }
            c.assertFalse(f.helpers.creationRecoveryRequired, "The callback had no helper runtime at the synthetic earlier load");
            f.helpers.load(body); // The server-start scan calls this for every already loaded entity.
            c.assertTrue(f.helpers.creationRecoveryRequired && !body.isRemoved() && body.entityTags().equals(tags),
                "The startup scan freezes without retagging the earlier loaded body");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void unmarkedDuplicateDoesNotReplaceTheLoadedMapping(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            UUID id = f.record(); var original = f.body(id, true); f.add(original);
            var other = f.body(id, false); var tags = Set.copyOf(other.entityTags());
            f.helpers.load(other); // Same-UUID native insertion is normally rejected; model the ambiguous callback directly.
            c.assertTrue(f.helpers.creationRecoveryRequired && f.helpers.loaded.get(id) == original
                    && !original.isRemoved() && !other.isRemoved() && other.entityTags().equals(tags),
                "An unmarked duplicate freezes and keeps the first exact mapping and both synthetic bodies");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void unrelatedUntaggedGolemKeepsNormalBehavior(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.record(); var unrelated = f.body(UUID.randomUUID(), false); f.add(unrelated);
            float before = unrelated.getHealth();
            boolean damaged = unrelated.hurtServer(c.getLevel(), unrelated.damageSources().generic(), 5);
            c.assertTrue(!f.helpers.creationRecoveryRequired && !f.helpers.loaded.containsKey(unrelated.getUUID())
                    && damaged && unrelated.getHealth() < before,
                "An unrelated ordinary golem remains outside helper ownership and damage shielding");
        }
        c.succeed();
    }
}

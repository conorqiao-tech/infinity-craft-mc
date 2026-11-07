package dev.convergence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Synthetic roster generations; no live world or backup is read. */
public class HelperPreviousIdentityGameTests {
    private static AgentCompanions.Data empty() { return new AgentCompanions.Data(); }
    private static void add(AgentCompanions.Data data, String id) {
        data.agents.put(id, new AgentCompanions.Agent(UUID.randomUUID().toString(), "older",
                AgentCompanions.Mode.STAY, AgentCompanions.Profile.REGULAR, "minecraft:overworld", 0, 64, 0));
    }
    private static byte[] bytes(AgentCompanions.Data data, boolean legacy) {
        var value = CommunityServer.GSON.toJsonTree(data).getAsJsonObject();
        if (legacy) { value.addProperty("format", 2); value.remove("guardedRemovals"); }
        return CommunityServer.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    }
    private static Path directory() throws IOException { return Files.createTempDirectory("infinity-previous-identity-"); }
    private static void cleanup(Path dir) throws IOException {
        try (var entries = Files.walk(dir)) {
            for (var entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }
    private static AgentCompanions runtime(GameTestHelper c, Path roster) {
        return new AgentCompanions(c.getLevel().getServer(), roster);
    }

    @GameTest(maxTicks=180)
    public void unrepresentedPreviousIdentityFreezesBeforeAnySave(GameTestHelper c) throws Exception {
        Path dir = directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            var older=empty(); add(older, UUID.randomUUID().toString());
            byte[] now=bytes(empty(),true), prior=bytes(older,true); Files.write(roster,now); Files.write(previous,prior);
            var helpers=runtime(c,roster);
            c.assertTrue(helpers.creationRecoveryRequired, "An unrepresented previous active identity freezes at startup");
            boolean refused=false; try { helpers.save(); } catch (IllegalStateException expected) { refused=true; }
            c.assertTrue(refused && Arrays.equals(now,Files.readAllBytes(roster)) && Arrays.equals(prior,Files.readAllBytes(previous)),
                    "A save cannot rotate either copied roster generation");
            c.assertTrue(runtime(c,roster).creationRecoveryRequired, "A fresh runtime still freezes for review");
        } finally { cleanup(dir); }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void previousIdentityAppearingAfterStartupBlocksTheNextCommit(GameTestHelper c) throws Exception {
        Path dir=directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            byte[] now=bytes(empty(),false); Files.write(roster,now); var helpers=runtime(c,roster);
            c.assertFalse(helpers.creationRecoveryRequired, "A fresh current roster starts unfrozen");
            var older=empty(); add(older,UUID.randomUUID().toString()); byte[] prior=bytes(older,false); Files.write(previous,prior);
            var invoked=new AtomicBoolean();
            var result=helpers.commit(() -> invoked.set(true), AgentCompanions.Save.ORDINARY, true);
            c.assertTrue(result==AgentCompanions.Commit.BLOCKED && !invoked.get() && helpers.creationRecoveryRequired,
                    "A new unrepresented previous identity blocks the mutation before staging");
            c.assertTrue(Arrays.equals(now,Files.readAllBytes(roster)) && Arrays.equals(prior,Files.readAllBytes(previous))
                    && runtime(c,roster).creationRecoveryRequired, "Both copied generations and restart evidence remain");
        } finally { cleanup(dir); }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void representedAndReservedPreviousIdentitiesDoNotFreeze(GameTestHelper c) throws Exception {
        for (boolean reserved : new boolean[]{false,true}) {
            Path dir=directory();
            try {
                var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
                String id=UUID.randomUUID().toString(); var older=empty(); add(older,id); var current=empty();
                if (reserved) current.dismissed.add(id); else current.agents.put(id,older.agents.get(id));
                Files.write(roster,bytes(current,false)); Files.write(previous,bytes(older,false));
                var helpers=runtime(c,roster); c.assertFalse(helpers.creationRecoveryRequired, "A represented or reserved identity does not freeze");
                helpers.save(); c.assertFalse(helpers.creationRecoveryRequired, "Ordinary saves remain available for this scoped control");
            } finally { cleanup(dir); }
        }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void sameIdentityWithDifferentPreviousOwnerKeepsBothGenerations(GameTestHelper c) throws Exception {
        Path dir=directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            String id=UUID.randomUUID().toString(); var older=empty(); add(older,id);
            var priorAgent=older.agents.get(id); var current=empty();
            current.agents.put(id, new AgentCompanions.Agent(UUID.randomUUID().toString(), priorAgent.name(),
                    priorAgent.mode(), priorAgent.profile(), priorAgent.dimension(), priorAgent.x(), priorAgent.y(), priorAgent.z()));
            byte[] now=bytes(current,false), prior=bytes(older,false); Files.write(roster,now); Files.write(previous,prior);
            var helpers=runtime(c,roster);
            boolean refused=false; try { helpers.save(); } catch (IllegalStateException expected) { refused=true; }
            c.assertTrue(refused && helpers.creationRecoveryRequired && Arrays.equals(now,Files.readAllBytes(roster))
                    && Arrays.equals(prior,Files.readAllBytes(previous)),
                    "A conflicting previous owner freezes and preserves both synthetic roster generations; refused=" + refused
                    + ", frozen=" + helpers.creationRecoveryRequired + ", previous preserved=" + Arrays.equals(prior,Files.readAllBytes(previous)));
        } finally { cleanup(dir); }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void sameIdentityWithDifferentPreviousNameBlocksNextCommit(GameTestHelper c) throws Exception {
        Path dir=directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            String id=UUID.randomUUID().toString(); var current=empty(); add(current,id);
            byte[] now=bytes(current,false); Files.write(roster,now); var helpers=runtime(c,roster);
            c.assertFalse(helpers.creationRecoveryRequired, "A single current roster starts unfrozen");
            var agent=current.agents.get(id); var older=empty();
            older.agents.put(id, new AgentCompanions.Agent(agent.owner(), "different", agent.mode(), agent.profile(),
                    agent.dimension(), agent.x(), agent.y(), agent.z()));
            byte[] prior=bytes(older,false); Files.write(previous,prior); var invoked=new AtomicBoolean();
            var result=helpers.commit(() -> invoked.set(true),AgentCompanions.Save.ORDINARY,true);
            c.assertTrue(result==AgentCompanions.Commit.BLOCKED && !invoked.get() && helpers.creationRecoveryRequired
                    && Arrays.equals(now,Files.readAllBytes(roster)) && Arrays.equals(prior,Files.readAllBytes(previous)),
                    "A conflicting previous name blocks mutation and retains both synthetic roster generations; result=" + result
                    + ", mutation invoked=" + invoked.get() + ", previous preserved=" + Arrays.equals(prior,Files.readAllBytes(previous)));
        } finally { cleanup(dir); }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void ordinaryModeProfileAndPositionDifferencesDoNotFreeze(GameTestHelper c) throws Exception {
        Path dir=directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            String id=UUID.randomUUID().toString(); var older=empty(); add(older,id); var agent=older.agents.get(id);
            var current=empty(); current.agents.put(id,new AgentCompanions.Agent(agent.owner(),agent.name(),
                    AgentCompanions.Mode.FOLLOW,AgentCompanions.Profile.PRIMITIVE,"minecraft:the_nether",12,70,-4));
            Files.write(roster,bytes(current,false)); Files.write(previous,bytes(older,false));
            var helpers=runtime(c,roster); c.assertFalse(helpers.creationRecoveryRequired,
                    "Normal nonidentity changes across generations do not freeze");
            helpers.save(); c.assertFalse(helpers.creationRecoveryRequired,"Ordinary save remains available");
        } finally { cleanup(dir); }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void unreadablePreviousRosterIsRetainedAndFreezes(GameTestHelper c) throws Exception {
        Path dir=directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            byte[] now=bytes(empty(),false), prior="not a roster".getBytes(StandardCharsets.UTF_8);
            Files.write(roster,now); Files.write(previous,prior); var helpers=runtime(c,roster);
            c.assertTrue(helpers.creationRecoveryRequired && Arrays.equals(now,Files.readAllBytes(roster))
                    && Arrays.equals(prior,Files.readAllBytes(previous)), "An invalid previous roster is retained for review");
        } finally { cleanup(dir); }
        c.succeed();
    }

    @GameTest(maxTicks=180)
    public void previousDisagreementStillFreezesIfTheMarkerCannotBeWritten(GameTestHelper c) throws Exception {
        Path dir=directory();
        try {
            var roster=dir.resolve("agents.json"); var previous=HelperPersistence.previousFile(roster);
            var older=empty(); add(older,UUID.randomUUID().toString()); byte[] prior=bytes(older,false);
            Files.write(roster,bytes(empty(),false)); Files.write(previous,prior);
            HelperPersistence.Io io=new HelperPersistence.Io() {
                public void copy(Path a,Path b) throws IOException { HelperPersistence.NATIVE.copy(a,b); }
                public void writeDurably(Path p,byte[] b) throws IOException {
                    if (p.equals(HelperPersistence.recoveryTemporary(roster))) throw new IOException("synthetic marker failure");
                    HelperPersistence.NATIVE.writeDurably(p,b);
                }
                public void atomicMove(Path a,Path b) throws IOException { HelperPersistence.NATIVE.atomicMove(a,b); }
                public void delete(Path p) throws IOException { HelperPersistence.NATIVE.delete(p); }
                public void syncFile(Path p) throws IOException { HelperPersistence.NATIVE.syncFile(p); }
                public void syncDirectory(Path p) throws IOException { HelperPersistence.NATIVE.syncDirectory(p); }
            };
            var first=new AgentCompanions(c.getLevel().getServer(),roster,Clock.systemUTC(),new HelperPersistence(io));
            var second=new AgentCompanions(c.getLevel().getServer(),roster,Clock.systemUTC(),new HelperPersistence(io));
            c.assertTrue(first.creationRecoveryRequired && second.creationRecoveryRequired && Files.notExists(HelperPersistence.recoveryMarker(roster))
                    && Arrays.equals(prior,Files.readAllBytes(previous)), "Rechecking the surviving prior roster freezes both runtimes");
        } finally { cleanup(dir); }
        c.succeed();
    }
}

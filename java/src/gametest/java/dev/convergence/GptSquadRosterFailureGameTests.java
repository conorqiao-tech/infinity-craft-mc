package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** A fault that freezes ownership needs its own disposable native world. */
public class GptSquadRosterFailureGameTests {
    @GameTest public void failedNativeRosterWriteLeavesActionDurablyConsumed(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptNativeDisk")) {
            var before = Set.copyOf(f.queue.data.proposals.keySet());
            var plan = new GptSquadPlanner.Plan(AgentCompanions.Mode.GUARD, AgentCompanions.Profile.ULTIMATE_FINALS,
                AgentCompanions.Formation.RING, "Keep the reviewed loaded squad together.");
            f.queue.proposeSquad(f.owner, GptSquadPlanner.Scope.capture(f.owner), plan);
            String id = f.queue.data.proposals.keySet().stream().filter(key -> !before.contains(key)).findFirst().orElseThrow();
            f.queue.ownerApprove(f.owner, id); var original = f.core.owned(f.owner, "fighter").getValue();
            var shape = AgentCompanions.formation(f.owner); Path blocker = f.core.file.resolveSibling(f.core.file.getFileName() + ".tmp");
            c.assertFalse(Files.exists(blocker), "Native temp boundary starts clear");
            Files.createDirectory(blocker); Files.writeString(blocker.resolve("keep"), "block actual atomic write");
            try {
                f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
                c.assertValueEqual(AgentActions.read(f.queue.file).proposals.get(id).state, AgentActions.State.DISPATCHED, "Durable consumption precedes failing native roster write");
                c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Native save failure restores existing record");
                c.assertValueEqual(AgentCompanions.formation(f.owner), shape, "Formation unchanged after failed roster write");
                c.assertTrue(f.core.creationRecoveryRequired && Files.exists(HelperPersistence.recoveryMarker(f.core.file)),
                    "The detected temporary roster freezes ownership and leaves a recovery note");
            } finally { Files.delete(blocker.resolve("keep")); Files.delete(blocker); }
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Storage repair cannot replay the terminal action");
            c.assertTrue(f.commands.isEmpty(), "No fallback to null or arbitrary command");
        } c.succeed();
    }
}

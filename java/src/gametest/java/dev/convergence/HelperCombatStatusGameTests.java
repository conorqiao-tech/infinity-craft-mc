package dev.convergence;

import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Native state explanations; these tests do not establish Bedrock touch rendering. */
public class HelperCombatStatusGameTests {
    private String summary(AgentTacticsGameTests.Arena arena) {
        return HelperCombatStatus.summary(arena.owner, arena.golem.getStringUUID(), arena.record(arena.golem));
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void statusTracksRegularUltimateAndStayWithoutStartingCombat(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "status-modes")) {
            arena.helpers.profile(arena.owner, "alpha", AgentCompanions.Profile.REGULAR);
            c.assertTrue(summary(arena).contains("Choose Ultimate Finals"), "Regular explains why it does not perform combos");
            arena.helpers.profile(arena.owner, "alpha", AgentCompanions.Profile.ULTIMATE_FINALS);
            AgentWeapons.spear(arena.golem, arena.target);
            arena.helpers.nextLeap.put(arena.golem.getUUID(), arena.helpers.server.getTickCount() + 41);
            var weapon = arena.golem.getMainHandItem().copy();
            int use = arena.golem.getTicksUsingItem();
            var text = summary(arena);
            c.assertTrue(arena.golem.getMainHandItem().is(Items.NETHERITE_SPEAR)
                && text.contains("Held: " + arena.golem.getMainHandItem().getHoverName().getString())
                && text.contains("gliding: no") && text.contains("target: none"),
                "Ultimate status reports the actual held weapon, glide flag and target");
            c.assertTrue(text.contains("Waiting for an eligible hostile") && text.contains("Aerial cooldown: 3s"),
                "Idle combat and real tick cooldown are explained separately");
            c.assertTrue(text.contains("A leap needs a clear, dry, level route within seven blocks")
                && text.contains("close targets use a mace opener"), "Cooldown readiness describes current range and terrain requirements without claiming the leap path was checked");
            c.assertTrue(arena.golem.getTarget() == null && !arena.helpers.aerial.containsKey(arena.golem.getUUID()), "Reading status does not acquire a target or launch a combo");
            c.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(weapon, arena.golem.getMainHandItem())
                && arena.golem.getTicksUsingItem() == use, "Reading status does not replace or restart the actual spear");
            c.assertTrue(HelperCombatStatus.squadSummary(arena.owner).contains("1 Ultimate Finals ready for target checks"),
                "The squad reports target-check availability, not guaranteed attacks");
            AgentWeapons.mace(arena.golem, arena.target);
            arena.golem.setTarget(arena.target);
            arena.golem.setOnGround(false);
            AgentWeapons.glide(arena.golem);
            var moving = summary(arena);
            c.assertTrue(arena.golem.getMainHandItem().is(Items.MACE) && arena.golem.isFallFlying()
                && moving.contains("Held: " + arena.golem.getMainHandItem().getHoverName().getString())
                && moving.contains("gliding: yes") && moving.contains("target: " + arena.target.getName().getString()),
                "Status reads the native mace, glide flag and actual current target without substituting a planned sequence");
            arena.helpers.mode(arena.owner, "alpha", AgentCompanions.Mode.STAY);
            c.assertTrue(summary(arena).contains("Paused: staying") && summary(arena).contains("Combo paused"), "Stay remains an explicit combat blocker");
            c.assertTrue(HelperCombatStatus.squadSummary(arena.owner).contains("0 Ultimate Finals ready for target checks"),
                "A saved Ultimate profile on Stay is not counted as ready");
            arena.helpers.mode(arena.owner, "alpha", AgentCompanions.Mode.FOLLOW);
            arena.helpers.profile(arena.owner, "alpha", AgentCompanions.Profile.DEBUG);
            c.assertTrue(summary(arena).contains("Combat is off") && summary(arena).contains("Follow and Guard still control movement"),
                "A passive combat profile does not imply movement is disabled");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void statusExplainsUnloadedWrongWorldOwnerAndStaleIdentity(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "status-state")) {
            var id = arena.golem.getUUID();
            var record = arena.record(arena.golem);
            arena.helpers.loaded.remove(id);
            try {
                c.assertTrue(summary(arena).contains("Paused: unloaded") && summary(arena).contains("Visit its saved area")
                    && summary(arena).contains("otherwise ask the host"), "Saved ownership does not claim an unavailable helper is alive or teleportable");
            } finally { arena.helpers.loaded.put(id, arena.golem); }
            var otherWorld = GameModes.world(arena.helpers.server, GameModes.Mode.HARDCORE);
            var elsewhere = EntityTypes.IRON_GOLEM.create(otherWorld, EntitySpawnReason.COMMAND);
            c.assertTrue(elsewhere != null, "Native helper entity exists for the wrong-world state");
            elsewhere.setUUID(id);
            arena.helpers.loaded.put(id, elsewhere);
            try { c.assertTrue(summary(arena).contains("owner in another dimension"), "A loaded helper in another world cannot appear combat ready"); }
            finally { arena.helpers.loaded.put(id, arena.golem); elsewhere.discard(); }
            OperatorGameTests.level(arena.owner, LevelBasedPermissionSet.ADMIN);
            c.assertTrue(summary(arena).contains("owner needs OP4"), "OP3 is not described as ready");
            OperatorGameTests.level(arena.owner, LevelBasedPermissionSet.OWNER);
            arena.owner.setGameMode(GameType.SPECTATOR);
            c.assertTrue(summary(arena).contains("owner in spectator mode"), "Spectator ownership pauses helpers");
            arena.owner.setGameMode(GameType.SURVIVAL);
            var replacement = record.profile(AgentCompanions.Profile.REGULAR);
            arena.helpers.data.agents.put(id.toString(), replacement);
            c.assertTrue(HelperCombatStatus.summary(arena.owner, id.toString(), record).contains("changed"), "A stale profile record must be selected again");
            var foreign = new AgentCompanions.Agent(UUID.randomUUID().toString(), "foreign", AgentCompanions.Mode.FOLLOW,
                AgentCompanions.Profile.ULTIMATE_FINALS, record.dimension(), record.x(), record.y(), record.z());
            arena.helpers.data.agents.put(id.toString(), foreign);
            try { c.assertTrue(HelperCombatStatus.summary(arena.owner, id.toString(), foreign).contains("no longer in your roster"), "Status never exposes another owner's helper"); }
            finally { arena.helpers.data.agents.put(id.toString(), replacement); }
            arena.helpers.dismiss(arena.owner, "alpha");
            c.assertTrue(HelperCombatStatus.squadSummary(arena.owner).contains("No helpers yet"), "An empty squad explains the explicit first-helper step");
            c.assertTrue(HelperCombatStatus.squadSummary(null).contains("owner offline"), "Offline ownership has an explicit prerequisite");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void readingStatusCannotApprovePlayersOrCeasefireExpiredOrders(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "status-orders")) {
            var target = new ModeGameTests().player(c, "status-player");
            try {
                target.setGameMode(GameType.SURVIVAL);
                target.setPos(arena.golem.position().add(1, 0, 0));
                AgentWeapons.mace(arena.golem, target);
                float health = target.getHealth();
                c.assertTrue(summary(arena).contains("owner approval and live Codex approval"), "No player order is invented by a status read");
                c.assertTrue(!arena.helpers.playerTargets.containsKey(arena.owner.getUUID()), "Reading creates no reviewed order");
                c.assertFalse(arena.golem.doHurtTarget(c.getLevel(), target), "Displaying readiness never bypasses actual player damage approval");
                c.assertValueEqual(target.getHealth(), health, "An unapproved player's health is unchanged");
                long now = arena.helpers.clock.millis();
                var expired = new AgentCompanions.PlayerTarget(arena.owner, arena.owner.connection, target, target.connection,
                    arena.owner.level(), now - 1000, now - 1);
                arena.helpers.playerTargets.put(arena.owner.getUUID(), expired);
                arena.helpers.nextLeap.put(arena.golem.getUUID(), arena.helpers.server.getTickCount() + 40);
                var text = summary(arena);
                c.assertTrue(text.contains("Player order expired"), "An expired record is not advertised as a reviewed usable target");
                c.assertTrue(arena.helpers.playerTargets.get(arena.owner.getUUID()) == expired,
                    "Status must not call the mutating order validator and silently ceasefire");
                c.assertValueEqual(arena.helpers.nextLeap.get(arena.golem.getUUID()), arena.helpers.server.getTickCount() + 40,
                    "Status cannot reset the real aerial cooldown");
                var selfOrder = new AgentCompanions.PlayerTarget(arena.owner, arena.owner.connection, arena.owner, arena.owner.connection,
                    arena.owner.level(), now, now + 10000);
                arena.helpers.playerTargets.put(arena.owner.getUUID(), selfOrder);
                c.assertTrue(summary(arena).contains("Helpers cannot target their owner"), "Even a stored order cannot make its owner eligible");
                target.setGameMode(GameType.CREATIVE);
                var creativeOrder = new AgentCompanions.PlayerTarget(arena.owner, arena.owner.connection, target, target.connection,
                    arena.owner.level(), now, now + 10000);
                arena.helpers.playerTargets.put(arena.owner.getUUID(), creativeOrder);
                c.assertTrue(summary(arena).contains("survival or adventure"), "Creative targets remain ineligible in readiness text");
                c.assertTrue(arena.helpers.playerTargets.get(arena.owner.getUUID()) == creativeOrder,
                    "Eligibility explanations do not mutate the live order record");
            } finally {
                arena.helpers.playerTargets.remove(arena.owner.getUUID());
                target.level().getServer().getPlayerList().remove(target);
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void expiredPlayerCurrentAndAerialTargetsAreNeverDescribedAsAnActiveCombo(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "status-expiry")) {
            var target = new ModeGameTests().player(c, "status-expired");
            try {
                target.setGameMode(GameType.SURVIVAL);
                target.setPos(arena.golem.position().add(5, 0, 0));
                // Fixture state represents an order that previously completed both reviews.
                // Normal control establishes the actual player target and its native canAttack gate.
                c.assertTrue(arena.helpers.assignPlayerTarget(arena.owner, target), "The fixture starts with an eligible reviewed player order");
                arena.helpers.control(arena.golem, arena.record(arena.golem), arena.helpers.server.getTickCount());
                c.assertTrue(arena.golem.getTarget() == target, "Native control supplies a real current player target before expiry");
                long now = arena.helpers.clock.millis();
                var expired = new AgentCompanions.PlayerTarget(arena.owner, arena.owner.connection, target, target.connection,
                    arena.owner.level(), now - 1000, now - 1);
                arena.helpers.playerTargets.put(arena.owner.getUUID(), expired);
                var held = arena.golem.getMainHandItem().copy();
                int use = arena.golem.getTicksUsingItem();
                float health = target.getHealth();
                var currentText = summary(arena);
                c.assertTrue(currentText.contains("Player combo blocked") && currentText.contains("Player order expired"),
                    "A current target pointer cannot advertise a combo after its reviewed order expired");
                c.assertFalse(currentText.contains("sequence runs when") || currentText.contains("sequence in progress"),
                    "Expired player approval is not contradicted by a generic active-combo sentence");
                c.assertTrue(arena.golem.getTarget() == target && arena.helpers.playerTargets.get(arena.owner.getUUID()) == expired,
                    "Read-only current-target status does not clear the target or order");
                arena.golem.setTarget(null);
                c.assertTrue(arena.golem.getTarget() == null, "The second case relies on the aerial target alone");
                var sequence = new AgentCompanions.Aerial(arena.record(arena.golem), target,
                    arena.golem.position().add(1, 0, 0), arena.helpers.server.getTickCount());
                arena.helpers.aerial.put(arena.golem.getUUID(), sequence);
                var aerialText = summary(arena);
                c.assertTrue(aerialText.contains("Player combo blocked") && aerialText.contains("Player order expired"),
                    "An aerial target pointer alone cannot advertise an expired player combo");
                c.assertFalse(aerialText.contains("sequence in progress"), "An expired aerial record is never presented as an active eligible combo");
                c.assertTrue(arena.helpers.aerial.get(arena.golem.getUUID()) == sequence
                    && arena.helpers.playerTargets.get(arena.owner.getUUID()) == expired,
                    "Status leaves actual sequence cancellation to the existing combat lifecycle");
                c.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(held, arena.golem.getMainHandItem())
                    && arena.golem.getTicksUsingItem() == use, "Both status reads leave actual equipment and spear use unchanged");
                c.assertValueEqual(target.getHealth(), health, "Reading expired target state causes no player damage");
            } finally { target.level().getServer().getPlayerList().remove(target); }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void openingJavaMenusAfterPlayerOrderExpiryDoesNotCeasefireCombatState(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "status-open")) {
            var target = new ModeGameTests().player(c, "status-menu");
            try {
                target.setGameMode(GameType.SURVIVAL);
                target.setPos(arena.golem.position().add(5, 0, 0));
                c.assertTrue(arena.helpers.assignPlayerTarget(arena.owner, target), "Fixture starts with an eligible reviewed player order");
                arena.helpers.control(arena.golem, arena.record(arena.golem), arena.helpers.server.getTickCount());
                c.assertTrue(arena.golem.getTarget() == target, "Normal control establishes the reviewed target before expiry");
                AgentWeapons.spear(arena.golem, target);
                arena.golem.setOnGround(false);
                AgentWeapons.glide(arena.golem);
                c.assertTrue(arena.golem.isUsingItem() && arena.golem.isFallFlying(), "Fixture retains actual native charge and flight state");
                var sequence = new AgentCompanions.Aerial(arena.record(arena.golem), target,
                    arena.golem.position().add(1, 0, 0), arena.helpers.server.getTickCount());
                arena.helpers.aerial.put(arena.golem.getUUID(), sequence);
                long now = arena.helpers.clock.millis();
                var expired = new AgentCompanions.PlayerTarget(arena.owner, arena.owner.connection, target, target.connection,
                    arena.owner.level(), now - 1000, now - 1);
                arena.helpers.playerTargets.put(arena.owner.getUUID(), expired);
                var held = arena.golem.getMainHandItem().copy();
                int use = arena.golem.getTicksUsingItem();
                float health = target.getHealth();

                AgentMenu.open(arena.owner);
                var roster = (AgentMenu.Handler)arena.owner.containerMenu;
                c.assertTrue(roster.view.getItem(22).getHoverName().getString().contains("Player order expired"),
                    "Opening the real roster explains expiry without running its lifecycle validator");
                int slot = roster.choices.entrySet().stream().filter(e -> e.getValue().equals(arena.golem.getStringUUID()))
                    .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
                roster.clicked(slot, 0, ContainerInput.PICKUP, arena.owner);
                var detail = (AgentMenu.Handler)arena.owner.containerMenu;
                detail.clicked(AgentMenu.INFO, 0, ContainerInput.PICKUP, arena.owner);
                c.assertTrue(arena.helpers.playerTargets.get(arena.owner.getUUID()) == expired
                    && arena.golem.getTarget() == target && arena.helpers.aerial.get(arena.golem.getUUID()) == sequence,
                    "Roster opening, detail opening and status reading preserve the exact order, target and flight sequence");
                c.assertTrue(arena.golem.isFallFlying() && arena.golem.isUsingItem()
                    && ItemStack.isSameItemSameComponents(held, arena.golem.getMainHandItem())
                    && use == arena.golem.getTicksUsingItem(), "Menu reads do not cancel flight, swap weapons or restart native charge");
                c.assertValueEqual(target.getHealth(), health, "Menu reads cause no player damage");
            } finally {
                arena.owner.closeContainer();
                arena.helpers.playerTargets.remove(arena.owner.getUUID());
                target.level().getServer().getPlayerList().remove(target);
            }
        }
        c.succeed();
    }
}

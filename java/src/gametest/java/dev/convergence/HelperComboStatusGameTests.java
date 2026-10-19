package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Real status/menu reads expose observed decisions without advancing combat. */
public class HelperComboStatusGameTests {
    private String summary(AgentTacticsGameTests.Arena arena) {
        return HelperCombatStatus.summary(arena.owner, arena.golem.getStringUUID(), arena.record(arena.golem));
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void comboPhaseAndReasonReadsKeepNativeCombatStateUnchanged(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "combo-read")) {
            var id = arena.golem.getUUID();
            arena.golem.setTarget(arena.target);
            AgentWeapons.spear(arena.golem, arena.target);
            int tick = arena.helpers.server.getTickCount();
            var feedback = new AgentCompanions.ComboFeedback("Charging spear", "Holding the native charge before pursuit.", tick);
            arena.helpers.combatFeedback.put(id, feedback);
            arena.helpers.nextLeap.put(id, tick + 45);
            arena.helpers.lastAttack.put(id, tick - 10);
            var weapon = arena.golem.getMainHandItem().copy();
            int use = arena.golem.getTicksUsingItem();
            var position = arena.golem.position();
            var velocity = arena.golem.getDeltaMovement();
            var path = arena.golem.getNavigation().getPath();
            float targetHealth = arena.target.getHealth();
            float helperHealth = arena.golem.getHealth();
            var text = summary(arena);
            var javaText = AgentMenu.helperInfo(arena.owner, id.toString(), arena.record(arena.golem));
            c.assertTrue(text.contains("Charging spear") && text.contains("Holding the native charge before pursuit."),
                "Native form status reports the observed phase and concrete decision reason");
            c.assertTrue(javaText.contains("Charging spear") && javaText.contains("Location:") && javaText.contains("HP:"),
                "Java and native forms share combat facts, with actual health and location for Java status");
            c.assertTrue(arena.helpers.comboFeedback(id) == feedback && arena.helpers.nextLeap.get(id) == tick + 45
                && arena.helpers.lastAttack.get(id) == tick - 10, "Status reads retain the observation and real combat timers");
            c.assertTrue(ItemStack.isSameItemSameComponents(weapon, arena.golem.getMainHandItem())
                && use == arena.golem.getTicksUsingItem(), "Status never swaps or restarts the native weapon");
            c.assertTrue(arena.golem.position().equals(position) && arena.golem.getDeltaMovement().equals(velocity)
                && arena.golem.getNavigation().getPath() == path && arena.golem.getTarget() == arena.target,
                "Status does not move, navigate, reacquire or change the target");
            c.assertValueEqual(arena.target.getHealth(), targetHealth, "Status deals no target damage");
            c.assertValueEqual(arena.golem.getHealth(), helperHealth, "Status changes no helper health");
            c.assertFalse(arena.helpers.aerial.containsKey(id), "Status does not launch a planned combo");
            c.assertFalse(arena.helpers.playerTargets.containsKey(arena.owner.getUUID()), "Status never creates a player approval");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void observedGroundFallbackAndAerialPhasesAppearInTheJavaStatusTooltip(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "combo-phases")) {
            var id = arena.golem.getUUID();
            arena.golem.setTarget(arena.target);
            String[][] observations = {{"Ground mace", "Target is inside the spear's minimum reach."},
                {"Approaching target", "Ground route selected; the ceiling prevents a safe leap."},
                {"Waiting for squad", "Another helper is taking the aerial turn."},
                {"Launching", "Native spear charge complete; takeoff started."},
                {"Gliding", "Brief native elytra flight."},
                {"Mace descent", "Following the target while descending."},
                {"Follow-up", "Waiting for the ordinary attack cooldown."},
                {"Recovering", "The target moved; returning to ground pursuit."}};
            for (var observation : observations) {
                var feedback = new AgentCompanions.ComboFeedback(observation[0], observation[1], arena.helpers.server.getTickCount());
                arena.helpers.combatFeedback.put(id, feedback);
                c.assertTrue(summary(arena).contains(observation[0]) && summary(arena).contains(observation[1]),
                    "A cached control decision is shown with its concrete reason: " + observation[0]);
            }
            AgentMenu.open(arena.owner);
            var roster = (AgentMenu.Handler)arena.owner.containerMenu;
            int slot = roster.choices.entrySet().stream().filter(e -> e.getValue().equals(id.toString()))
                .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            roster.clicked(slot, 0, ContainerInput.PICKUP, arena.owner);
            var detail = (AgentMenu.Handler)arena.owner.containerMenu;
            var lore = detail.view.getItem(AgentMenu.INFO).get(DataComponents.LORE);
            c.assertTrue(lore != null && lore.lines().stream().anyMatch(line -> line.getString().contains("Recovering")),
                "The actual Java detail tooltip exposes the observed recovery phase");
            detail.clicked(AgentMenu.INFO, 0, ContainerInput.PICKUP, arena.owner);
            c.assertTrue(arena.owner.containerMenu == arena.owner.inventoryMenu, "The Java status action reveals its shared facts in chat");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void pausedAndPassiveProfilesDoNotAdvertiseCachedAttackPhases(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "combo-paused")) {
            var id = arena.golem.getUUID();
            arena.helpers.mode(arena.owner, "alpha", AgentCompanions.Mode.STAY);
            var stale = new AgentCompanions.ComboFeedback("Launching", "Old attack phase.", arena.helpers.server.getTickCount());
            arena.helpers.combatFeedback.put(id, stale);
            c.assertTrue(summary(arena).contains("Combo paused") && !summary(arena).contains("Old attack phase."),
                "Stay hides a cached attack observation instead of claiming an active launch");
            c.assertTrue(arena.helpers.comboFeedback(id) == stale, "A status read leaves cancellation to control ticks");
            arena.helpers.mode(arena.owner, "alpha", AgentCompanions.Mode.FOLLOW);
            arena.helpers.profile(arena.owner, "alpha", AgentCompanions.Profile.DEBUG);
            arena.helpers.combatFeedback.put(id, stale);
            c.assertTrue(summary(arena).contains("Combat is off") && !summary(arena).contains("Old attack phase."),
                "Passive profiles do not render a stale attack phase");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void javaStatusCannotAdvertiseOrRevokeAnExpiredReviewedPlayerCombo(GameTestHelper c) {
        try (var arena = new AgentTacticsGameTests.Arena(c, "combo-expired")) {
            var target = new ModeGameTests().player(c, "combo-review");
            try {
                target.setGameMode(GameType.SURVIVAL);
                target.setPos(arena.golem.position().add(5, 0, 0));
                c.assertTrue(arena.helpers.assignPlayerTarget(arena.owner, target), "Fixture begins with an eligible reviewed target");
                arena.helpers.control(arena.golem, arena.record(arena.golem), arena.helpers.server.getTickCount());
                c.assertTrue(arena.golem.getTarget() == target, "Normal control establishes a real player target before expiry");
                AgentMenu.open(arena.owner);
                var roster = (AgentMenu.Handler)arena.owner.containerMenu;
                int slot = roster.choices.entrySet().stream().filter(e -> e.getValue().equals(arena.golem.getStringUUID()))
                    .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
                roster.clicked(slot, 0, ContainerInput.PICKUP, arena.owner);
                var detail = (AgentMenu.Handler)arena.owner.containerMenu;
                long now = arena.helpers.clock.millis();
                var expired = new AgentCompanions.PlayerTarget(arena.owner, arena.owner.connection, target, target.connection,
                    arena.owner.level(), now - 1000, now - 1);
                arena.helpers.playerTargets.put(arena.owner.getUUID(), expired);
                var feedback = new AgentCompanions.ComboFeedback("Launching", "Stale reviewed-player launch.", arena.helpers.server.getTickCount());
                arena.helpers.combatFeedback.put(arena.golem.getUUID(), feedback);
                float health = target.getHealth();
                var text = AgentMenu.helperInfo(arena.owner, arena.golem.getStringUUID(), arena.record(arena.golem));
                c.assertTrue(text.contains("Player combo blocked") && text.contains("Player order expired")
                    && !text.contains("Stale reviewed-player launch."), "An expired player review hides cached attack-phase text");
                detail.clicked(AgentMenu.INFO, 0, ContainerInput.PICKUP, arena.owner);
                c.assertTrue(arena.helpers.playerTargets.get(arena.owner.getUUID()) == expired && arena.golem.getTarget() == target,
                    "Java status uses the read-only summary rather than implicitly ceasefiring the expired order");
                c.assertTrue(arena.helpers.comboFeedback(arena.golem.getUUID()) == feedback, "The observation remains unchanged after the Java action");
                c.assertValueEqual(target.getHealth(), health, "Expired status causes no player damage");
            } finally {
                arena.helpers.playerTargets.remove(arena.owner.getUUID());
                target.level().getServer().getPlayerList().remove(target);
            }
        }
        c.succeed();
    }
}

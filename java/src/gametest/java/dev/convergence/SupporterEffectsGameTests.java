package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

/** The cosmetic selector controls real rendering eligibility, without gameplay entitlements. */
public class SupporterEffectsGameTests {
    @GameTest public void originalCompositionsHaveBoundedDistinctShapes(GameTestHelper c) {
        var shapes = new java.util.HashSet<Object>();
        for (var style : List.of("supporter_go", "supporter_plus", "supporter_pro", "supporter_ultra")) {
            var frame = SupporterEffects.frame(style, 80);
            c.assertTrue(frame.size() >= 3 && frame.size() <= 8, "Small per-source particle budget");
            for (var glint : frame) {
                var at = glint.local();
                c.assertTrue(Double.isFinite(at.x) && Double.isFinite(at.y) && Double.isFinite(at.z)
                    && Math.abs(at.x) <= .5 && Math.abs(at.z) <= .5 && at.y > 0 && at.y < 2.2,
                    "Cosmetics stay within a small finite player envelope");
            }
            c.assertValueEqual(frame, SupporterEffects.frame(style, 2480), "Bounded phase remains stable over time");
            shapes.add(frame);
        }
        c.assertValueEqual(shapes.size(), 4, "All four styles have distinct compositions");
        c.assertTrue(SupporterEffects.frame("", 0).isEmpty(), "Hidden style sends no particles");
        c.assertTrue(SupporterEffects.frame("admin", 0).isEmpty(), "Staff label alone is never a paid particle entitlement");
        c.succeed();
    }

    @GameTest public void paidStyleRendersWithoutChangingInventoryMovementOrEarnedRank(GameTestHelper c) {
        try (var f = new InfiniEconomyGameTests().new Fixture(c)) {
            var p = f.player("supporter-style");
            var rank = Memberships.get(c.getLevel().getServer()).permanentTier(p.getUUID());
            var at = p.position(); float health = p.getHealth();
            f.economy.credit(p.getUUID(), "style-funds", 10000);
            c.assertTrue(f.economy.purchase(p, f.economy.quote(p, "ultra_lifetime")).success(), "Actual reviewed cosmetic purchase");
            var sent = new ArrayList<SupporterEffects.Glint>();
            c.assertValueEqual(SupporterEffects.render(p, f.economy, 40, (owner, glint) -> sent.add(glint)), 8, "Selected Ultra style emits its real composition");
            p.setInvisible(true);
            c.assertValueEqual(SupporterEffects.render(p, f.economy, 60, (owner, glint) -> sent.add(glint)), 0, "Cosmetics must not reveal an invisible player");
            p.setInvisible(false);
            c.assertValueEqual(p.position(), at, "Cosmetic does not move a player");
            c.assertValueEqual(p.getHealth(), health, "Cosmetic does not change health");
            c.assertTrue(p.getInventory().isEmpty(), "Cosmetic grants no physical inventory items");
            c.assertValueEqual(Memberships.get(c.getLevel().getServer()).permanentTier(p.getUUID()), rank, "Earned rank remains independent");
            c.assertFalse(Memberships.operator(p), "Cosmetic cannot grant staff access");
            f.economy.selectStyle(p, "");
            c.assertValueEqual(SupporterEffects.render(p, f.economy, 80, (owner, glint) -> sent.add(glint)), 0, "Saved hide selection stops all emissions");
        }
        c.succeed();
    }

    @GameTest public void expiredOrRefundHeldStylesAndSpectatorsAreNotRendered(GameTestHelper c) {
        try (var f = new InfiniEconomyGameTests().new Fixture(c)) {
            var p = f.player("supporter-expiry");
            f.economy.credit(p.getUUID(), "style-pass-funds", 750);
            var quote = f.economy.quote(p, "go_pass");
            c.assertTrue(f.economy.purchase(p, quote).success(), "Thirty-day style purchased");
            var sent = new ArrayList<SupporterEffects.Glint>();
            c.assertValueEqual(SupporterEffects.render(p, f.economy, 40, (owner, glint) -> sent.add(glint)), 3, "Active style is visible");
            p.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(SupporterEffects.render(p, f.economy, 80, (owner, glint) -> sent.add(glint)), 0, "Spectator eligibility hides particles");
            p.setGameMode(GameType.SURVIVAL);
            f.economy.refund(p.getUUID(), "style-chargeback", "style-pass-funds", 750);
            c.assertValueEqual(SupporterEffects.render(p, f.economy, 120, (owner, glint) -> sent.add(glint)), 0, "Refund hold removes paid cosmetics without affecting earned gameplay");
            f.economy.credit(p.getUUID(), "style-restored", 750);
            f.now.set(quote.expires());
            c.assertValueEqual(SupporterEffects.render(p, f.reopen(), 160, (owner, glint) -> sent.add(glint)), 0, "Native render eligibility respects exact durable expiry");
        }
        c.succeed();
    }

    @GameTest public void bothShopsAndAstralJournalAreReachableFromInfinityMenu(GameTestHelper c) {
        var fixtures = new CommonsGameTests(); var p = fixtures.survival(c, "infini-entrypoints");
        try {
            ServerMenu.open(p); p.containerMenu.clicked(ServerMenu.INFINI, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu instanceof InfiniShopMenu.Handler, "Wallet opens a real shop screen");
            p.closeContainer(); ServerMenu.open(p); p.containerMenu.clicked(ServerMenu.ASTRAL, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu instanceof AstralJournal.Handler, "Astral menu is directly reachable");
            p.closeContainer(); OdysseyJournal.open(p); p.containerMenu.clicked(8, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, p);
            c.assertTrue(p.containerMenu instanceof AstralJournal.Handler, "Odyssey journal also links the expansion");
        } finally { fixtures.cleanup(p); }
        c.succeed();
    }
}

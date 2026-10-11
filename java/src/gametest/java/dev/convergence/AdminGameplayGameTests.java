package dev.convergence;

import java.util.List;
import java.nio.file.Files;
import java.util.Optional;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

/** Admin is real OP4; permission provenance, AI review and mode inventories remain explicit. */
public class AdminGameplayGameTests {
    private ServerPlayerEntity player(TestContext c, String name) {
        var player = new ModeGameTests().player(c, name);
        player.changeGameMode(GameMode.SURVIVAL);
        c.assertTrue(Memberships.get(c.getWorld().getServer()).grantAdmin(player.getUuid()), "Trusted Admin grant persists and promotes");
        return player;
    }
    private void cleanup(ServerPlayerEntity player) {
        var server = player.getEntityWorld().getServer();
        player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        player.closeHandledScreen();
        Memberships.get(server).revokeAdmin(player.getUuid());
        GameModes.PENDING.remove(player.getUuid());
        CommunityServer.get(server).pending.remove(player.getUuid());
        OperatorGameTests.deop(player);
        server.getPlayerManager().remove(player);
    }
    private void click(ServerPlayerEntity player, int slot) {
        player.currentScreenHandler.onSlotClick(slot, 0, SlotActionType.PICKUP, player);
    }
    private CommunityServer.Place safePlace(ServerPlayerEntity player, int dx) {
        BlockPos feet = player.getBlockPos().add(dx, 0, 0);
        var world = player.getEntityWorld();
        world.setBlockState(feet.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(feet, Blocks.AIR.getDefaultState());
        world.setBlockState(feet.up(), Blocks.AIR.getDefaultState());
        return new CommunityServer.Place(world.getRegistryKey().getValue().toString(), feet.getX() + .5,
            feet.getY(), feet.getZ() + .5, 0, 0);
    }

    @GameTest public void adminGrantsRealOp4ButCannotSupplyCodexApproval(TestContext c) {
        var p = player(c, "admin-op");
        try {
            c.assertTrue(Memberships.operator(p) && Memberships.owner(p.getCommandSource()), "Admin gains actual vanilla OP4");
            c.assertTrue(CommunityServer.staff(p.getCommandSource()), "Admin has community administration");
            c.assertTrue(AgentCompanions.operator(p.getCommandSource()), "Admin can use helper controls");
            var root = c.getWorld().getServer().getCommandManager().getDispatcher().getRoot();
            for (String command : List.of("op", "gamemode", "membership", "community", "agent"))
                c.assertTrue(root.getChild(command).canUse(p.getCommandSource()), "Admin unlocks privileged command " + command);
            c.assertFalse(root.getChild("agent-codex-approve").canUse(p.getCommandSource()), "Admin cannot impersonate the separate live Codex review");
            c.assertEquals(AgentMenu.open(p), 1, "Admin can open helper controls");
            c.assertEquals(Memberships.get(c.getWorld().getServer()).label(p.getUuid()), "ADMIN", "Admin badge overlays OP4");
            c.assertTrue(Memberships.get(c.getWorld().getServer()).revokeAdmin(p.getUuid()), "Role revocation is persisted");
            c.assertFalse(Memberships.gameplayBypass(p) || Memberships.operator(p), "Revoking a role-owned promotion removes its OP4 access");
            c.assertFalse(p.currentScreenHandler.canUse(p), "An open helper menu rechecks revoked permissions");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void adminRewardsAndAppearanceUnlocksAreTemporaryPermissions(TestContext c) {
        var p = player(c, "admin-unlocks");
        var rewards = new RewardGameTests().isolated(c);
        try {
            c.assertTrue(rewards.power(p, "hacks"), "Admin can use an unearned power bundle");
            c.assertTrue(p.getAbilities().allowFlying && p.hasStatusEffect(StatusEffects.RESISTANCE), "Admin bundle has actual gameplay effects");
            c.assertTrue(rewards.cosmetic(p, "wither"), "Admin can choose an unearned cosmetic");
            c.assertTrue(rewards.account(p.getUuid()).unlocked.isEmpty(), "Permission does not fabricate achievement receipts");
            c.assertTrue(BackpackStorage.backpackEarned(p), "Admin can open backpack storage without an achievement");
            c.assertEquals(BackpackStorage.claim(p, "dragon"), 1, "Admin can claim a backpack appearance");
            c.assertEquals(BackpackStorage.armor(p, "aurora"), 1, "Admin can claim cosmetic armor");
            c.assertTrue(BackpackStorage.owned(p).isEmpty(), "Appearance permission does not fabricate unlock receipts");
            p.changeGameMode(GameMode.SPECTATOR);
            c.assertTrue(AchievementRewards.allowed(p), "Admin inherits existing OP4 reward policy");
            c.assertEquals(BackpackStorage.open(p), 0, "Admin spectator cannot withdraw stored items");
            p.changeGameMode(GameMode.SURVIVAL);
            c.assertTrue(Memberships.get(c.getWorld().getServer()).revokeAdmin(p.getUuid()), "Admin revocation succeeds");
            rewards.apply(p);
            c.assertFalse(p.getAbilities().allowFlying || p.hasStatusEffect(StatusEffects.RESISTANCE), "Revocation removes unearned active powers");
            c.assertFalse(rewards.unlocked(p, "hacks") || BackpackStorage.backpackEarned(p), "Revocation removes entitlement bypasses");
            c.assertEquals(rewards.emit(p), 0, "Revoked unearned cosmetic stops emitting");
            c.assertFalse(rewards.cosmetic(p, "diamond"), "A revoked Admin cannot claim another locked cosmetic");
        } finally { rewards.clearPowers(p, false); cleanup(p); }
        c.complete();
    }

    @GameTest public void adminGearMenusPreserveItemsAndRecheckTheRole(TestContext c) {
        var p = player(c, "admin-gear");
        try {
            var old = new ItemStack(Items.DIAMOND_PICKAXE);
            old.set(DataComponentTypes.CUSTOM_NAME, Text.literal("My original pickaxe"));
            p.setStackInHand(Hand.MAIN_HAND, old);
            c.assertTrue(ServerMenu.gearAllowed(p), "Admin can use the catalog in ordinary Survival");
            ServerMenu.open(p); click(p, ServerMenu.GEAR);
            var gear = (ServerMenu.Handler)p.currentScreenHandler;
            int sword = gear.paths.indexOf("convergence:sword");
            while (gear.pageIndex < sword / ServerMenu.PAGE_SIZE) { click(p, ServerMenu.NEXT); gear = (ServerMenu.Handler)p.currentScreenHandler; }
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertEquals(p.getMainHandStack().getItem(), Convergence.ITEMS.get("convergence:sword"), "Admin menu equips real Infinity gear");
            c.assertTrue(p.getInventory().contains(old), "The existing named item stays in inventory");
            c.assertEquals(Convergence.giveBuildingKit(p), 1, "Admin can claim the building kit with its OP4 access");
            c.assertTrue(p.getInventory().contains(new ItemStack(Convergence.ITEMS.get("convergence:builder_wand"))), "The building kit contains a real builder wand");
            ServerMenu.open(p); click(p, ServerMenu.GEAR);
            gear = (ServerMenu.Handler)p.currentScreenHandler;
            c.assertTrue(Memberships.get(c.getWorld().getServer()).revokeAdmin(p.getUuid()), "Admin revocation succeeds");
            ItemStack before = p.getMainHandStack().copy();
            ((ScreenHandler)gear).onSlotClick(0, 0, SlotActionType.PICKUP, p);
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(before, p.getMainHandStack()), "Revoking Admin while the catalog is open prevents further grants");
            c.assertFalse(ServerMenu.gearAllowed(p), "Catalog eligibility rechecks current membership");
            p.closeHandledScreen();
            c.assertEquals(Convergence.giveKit(p), 0, "Revoked Survival Admin cannot use the kit fallback");
            c.assertTrue(Memberships.get(c.getWorld().getServer()).grantAdmin(p.getUuid()), "Admin grant succeeds");
            p.changeGameMode(GameMode.SPECTATOR);
            c.assertEquals(Convergence.giveKit(p), 0, "Admin kit respects spectator state");
            c.assertEquals(Convergence.giveBuildingKit(p), 0, "Admin building kit respects spectator state");
            c.assertEquals(Convergence.holdCreativeItem(p, "mace"), 0, "Admin hand placement respects spectator state");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void adminHomesAndTravelHaveFullOperatorAccess(TestContext c) {
        var p = player(c, "admin-homes");
        var community = CommunityServer.get(c.getWorld().getServer());
        try {
            for (int i = 0; i < 5; i++) community.setHome(p, "home" + i);
            c.assertEquals(community.homes(p).size(), 5, "Admin has more than three homes");
            var start = CommunityServer.Place.of(p);
            var target = safePlace(p, 4);
            community.combat.put(p.getUuid(), community.server.getTicks() + 500);
            community.cooldowns.put(p.getUuid(), community.server.getTicks() + 500);
            community.queue(p, target, null);
            c.assertEquals(p.getX(), target.x(), "Admin travel is immediate despite ordinary timers");
            c.assertFalse(community.pending.containsKey(p.getUuid()), "Admin travel leaves no countdown");
            p.getInventory().setStack(0, new ItemStack(Items.DIAMOND, 3));
            community.queue(p, GameModes.defaultPlace(community.server, GameModes.Mode.CREATIVE), null);
            c.assertEquals(GameModes.current(p), GameModes.Mode.CREATIVE, "Full OP4 home travel can change worlds");
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)), "Cross-world OP travel still isolates Survival inventory");
            community.queue(p, target, null);
            c.assertEquals(p.getInventory().getStack(0).getCount(), 3, "Returning restores Survival inventory");
            c.assertTrue(Memberships.get(community.server).revokeAdmin(p.getUuid()), "Admin revocation succeeds");
            community.setHome(p, "extra");
            c.assertEquals(community.homes(p).size(), 5, "Revocation blocks extra homes without deleting saved homes");
            community.queue(p, start, null);
            c.assertEquals(p.getX(), target.x(), "Normal combat restriction returns immediately after revocation");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void adminInstantModesPreserveInventoriesAndHardcoreState(TestContext c) {
        var p = player(c, "admin-modes");
        var server = c.getWorld().getServer();
        try {
            p.getInventory().setStack(0, new ItemStack(Items.DIAMOND, 5));
            SimpleInventory survivalBackpack = new BackpackStorage.Storage(p);
            survivalBackpack.setStack(0, new ItemStack(Items.IRON_INGOT, 4));
            CommunityServer.get(server).combat.put(p.getUuid(), server.getTicks() + 500);
            GameModes.request(p, GameModes.Mode.CREATIVE, null);
            c.assertEquals(GameModes.current(p), GameModes.Mode.CREATIVE, "Admin mode changes are immediate despite ordinary combat timers");
            c.assertFalse(GameModes.PENDING.containsKey(p.getUuid()), "Instant mode access creates no warmup");
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)), "Survival inventory is not copied into Creative");
            c.assertFalse(survivalBackpack.canPlayerUse(p), "Old backpack handler cannot cross modes");
            SimpleInventory creativeBackpack = new BackpackStorage.Storage(p);
            c.assertTrue(creativeBackpack.isEmpty(), "Creative backpack starts separate");
            creativeBackpack.setStack(0, new ItemStack(Items.GOLD_BLOCK, 64));
            p.getInventory().setStack(0, new ItemStack(Items.NETHERITE_BLOCK, 64));
            GameModes.request(p, GameModes.Mode.SURVIVAL, null);
            c.assertTrue(p.getInventory().getStack(0).isOf(Items.DIAMOND), "Returning restores the original Survival item");
            c.assertEquals(p.getInventory().getStack(0).getCount(), 5, "Returning preserves the original Survival count");
            SimpleInventory restoredBackpack = new BackpackStorage.Storage(p);
            c.assertTrue(restoredBackpack.getStack(0).isOf(Items.IRON_INGOT), "Creative backpack items never enter Survival storage");
            GameModes.state(p).putBoolean("eliminated", true);
            GameModes.request(p, GameModes.Mode.HARDCORE, null);
            c.assertEquals(GameModes.current(p), GameModes.Mode.HARDCORE, "Admin may return to an eliminated Hardcore profile");
            c.assertEquals(p.getGameMode(), GameMode.SURVIVAL, "Admin can play Hardcore instead of spectating");
            c.assertTrue(GameModes.state(p).getBoolean("eliminated", false), "Bypass does not erase stored elimination state");
            c.assertTrue(Memberships.get(server).revokeAdmin(p.getUuid()), "Admin revocation succeeds");
            c.assertEquals(GameModes.gameMode(p), GameMode.SPECTATOR, "Revocation restores the stored Hardcore elimination rule");
            c.assertTrue(Memberships.get(server).grantAdmin(p.getUuid()), "Admin grant succeeds");
            GameModes.request(p, GameModes.Mode.HUB, "main");
            c.assertTrue(Memberships.operator(p), "Admin keeps actual OP4 in the Hub");
        } finally { cleanup(p); }
        c.complete();
    }

    @GameTest public void adminProvenanceRestoresPriorOpAndPreservesIndependentOp(TestContext c) {
        var p = new ModeGameTests().player(c, "admin-provenance");
        var members = new ModeGameTests().members(c);
        try {
            members.server.getPlayerManager().addToOperators(OperatorGameTests.entry(p), Optional.of(LeveledPermissionPredicate.GAMEMASTERS), Optional.of(true));
            c.assertTrue(members.grantAdmin(p.getUuid()), "Existing OP2 can receive Admin");
            c.assertTrue(Memberships.operator(p), "Promotion reaches OP4");
            var reload = new Memberships(members.server, members.file, members.configFile);
            c.assertTrue(reload.account(p.getUuid()).adminOpOwned, "UUID-bound promotion provenance survives reload");
            c.assertTrue(reload.revokeAdmin(p.getUuid()), "Reloaded service restores owned promotion");
            var original = members.server.getPlayerManager().getOpList().get(OperatorGameTests.entry(p));
            c.assertEquals(original.getLevel(), LeveledPermissionPredicate.GAMEMASTERS, "Revocation restores prior OP2");
            c.assertTrue(original.canBypassPlayerLimit(), "Original player-limit flag survives promotion and revocation");
            OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
            c.assertTrue(reload.grantAdmin(p.getUuid()), "Independent OP4 can receive Admin badge");
            c.assertFalse(reload.account(p.getUuid()).adminOpOwned, "Existing OP4 is never claimed by Admin");
            c.assertTrue(reload.revokeAdmin(p.getUuid()) && Memberships.operator(p), "Revocation preserves preexisting OP4");
            OperatorGameTests.deop(p);
            c.assertTrue(reload.grantAdmin(p.getUuid()), "Admin can create a new owned promotion");
            OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
            c.assertTrue(reload.revokeAdmin(p.getUuid()) && Memberships.operator(p), "A later manual OP4 grant is preserved");
        } finally { members.revokeAdmin(p.getUuid()); cleanup(p); }
        c.complete();
    }

    @GameTest public void adminMigrationAndPermissionChangesFailClosedOnStorageFailure(TestContext c) throws Exception {
        var p = new ModeGameTests().player(c, "admin-persistence");
        var members = new ModeGameTests().members(c);
        try {
            members.account(p.getUuid()).admin = true;
            members.save();
            var reload = new Memberships(members.server, members.file, members.configFile);
            c.assertTrue(reload.syncAdminOperator(p) && Memberships.operator(p), "A saved legacy Admin migrates to actual OP4");
            var originalFile = Files.readString(reload.file);
            Files.delete(reload.file); Files.createDirectory(reload.file); Files.writeString(reload.file.resolve("block"), "block");
            c.assertFalse(reload.revokeAdmin(p.getUuid()), "An unwritable membership file blocks revocation before operator mutation");
            c.assertTrue(Memberships.operator(p) && reload.account(p.getUuid()).admin, "Failed persistence preserves prior role and OP state");
            Files.delete(reload.file.resolve("block")); Files.delete(reload.file); Files.writeString(reload.file, originalFile);
            c.assertTrue(reload.revokeAdmin(p.getUuid()) && !Memberships.operator(p), "Revocation succeeds after storage recovery");
            Files.delete(reload.file); Files.createDirectory(reload.file); Files.writeString(reload.file.resolve("block"), "block");
            c.assertFalse(reload.grantAdmin(p.getUuid()), "An unwritable membership file blocks promotion before operator mutation");
            c.assertFalse(Memberships.operator(p) || reload.account(p.getUuid()).admin, "Failed grant does not leave live permissions");
            Files.delete(reload.file.resolve("block")); Files.delete(reload.file); reload.save();
        } finally { members.revokeAdmin(p.getUuid()); cleanup(p); }
        c.complete();
    }

    @GameTest public void adminBypassesWeaponCooldownsUntilItsRoleIsRevoked(TestContext c) {
        var p = player(c, "admin-cooldowns");
        try {
            p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Convergence.ITEMS.get("convergence:mace")));
            p.setStackInHand(Hand.OFF_HAND, new ItemStack(Convergence.ITEMS.get("convergence:spear")));
            Convergence.state(p).cooldown.put("spear", Convergence.clock + 200);
            c.assertTrue(Convergence.arm(p), "Admin arms a real spear despite its cooldown");
            c.assertTrue(Convergence.ready(p, "test_power", 200) && Convergence.ready(p, "test_power", 200), "Admin repeats timed powers immediately");
            c.assertTrue(Memberships.get(c.getWorld().getServer()).revokeAdmin(p.getUuid()), "Admin revocation succeeds");
            c.assertFalse(Convergence.arm(p), "Stored spear cooldown applies again after revocation");
            OperatorGameTests.level(p, LeveledPermissionPredicate.OWNERS);
            c.assertTrue(Convergence.ready(p, "test_power", 200), "Ordinary OP4 can first activate a power");
            c.assertFalse(Convergence.ready(p, "test_power", 200), "Unlimited weapon cooldown privilege belongs to Admin role");
        } finally { cleanup(p); }
        c.complete();
    }
}

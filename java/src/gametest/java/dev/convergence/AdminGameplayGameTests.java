package dev.convergence;

import java.util.List;
import java.nio.file.Files;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Admin is real OP4; permission provenance, AI review and mode inventories remain explicit. */
public class AdminGameplayGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var player = new ModeGameTests().player(c, name);
        player.setGameMode(GameType.SURVIVAL);
        c.assertTrue(Memberships.get(c.getLevel().getServer()).grantAdmin(player.getUUID()), "Trusted Admin grant persists and promotes");
        return player;
    }
    private void cleanup(ServerPlayer player) {
        var server = player.level().getServer();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        Memberships.get(server).revokeAdmin(player.getUUID());
        GameModes.PENDING.remove(player.getUUID());
        CommunityServer.get(server).pending.remove(player.getUUID());
        OperatorGameTests.deop(player);
        server.getPlayerList().remove(player);
    }
    private void click(ServerPlayer player, int slot) {
        player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }
    private CommunityServer.Place safePlace(ServerPlayer player, int dx) {
        BlockPos feet = player.blockPosition().offset(dx, 0, 0);
        var world = player.level();
        world.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
        return new CommunityServer.Place(world.dimension().identifier().toString(), feet.getX() + .5,
            feet.getY(), feet.getZ() + .5, 0, 0);
    }

    @GameTest public void adminGrantsRealOp4ButCannotSupplyCodexApproval(GameTestHelper c) {
        var p = player(c, "admin-op");
        try {
            c.assertTrue(Memberships.operator(p) && Memberships.owner(p.createCommandSourceStack()), "Admin gains actual vanilla OP4");
            c.assertTrue(CommunityServer.staff(p.createCommandSourceStack()), "Admin has community administration");
            c.assertTrue(AgentCompanions.operator(p.createCommandSourceStack()), "Admin can use helper controls");
            var root = c.getLevel().getServer().getCommands().getDispatcher().getRoot();
            for (String command : List.of("op", "gamemode", "membership", "community", "agent"))
                c.assertTrue(root.getChild(command).canUse(p.createCommandSourceStack()), "Admin unlocks privileged command " + command);
            c.assertFalse(root.getChild("agent-codex-approve").canUse(p.createCommandSourceStack()), "Admin cannot impersonate the separate live Codex review");
            c.assertValueEqual(AgentMenu.open(p), 1, "Admin can open helper controls");
            c.assertValueEqual(Memberships.get(c.getLevel().getServer()).label(p.getUUID()), "ADMIN", "Admin badge overlays OP4");
            c.assertTrue(Memberships.get(c.getLevel().getServer()).revokeAdmin(p.getUUID()), "Role revocation is persisted");
            c.assertFalse(Memberships.gameplayBypass(p) || Memberships.operator(p), "Revoking a role-owned promotion removes its OP4 access");
            c.assertFalse(p.containerMenu.stillValid(p), "An open helper menu rechecks revoked permissions");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminRewardsAndAppearanceUnlocksAreTemporaryPermissions(GameTestHelper c) {
        var p = player(c, "admin-unlocks");
        var rewards = new RewardGameTests().isolated(c);
        try {
            c.assertTrue(rewards.power(p, "hacks"), "Admin can use an unearned power bundle");
            c.assertTrue(p.getAbilities().mayfly && p.hasEffect(MobEffects.RESISTANCE), "Admin bundle has actual gameplay effects");
            c.assertTrue(rewards.cosmetic(p, "wither"), "Admin can choose an unearned cosmetic");
            c.assertTrue(rewards.account(p.getUUID()).unlocked.isEmpty(), "Permission does not fabricate achievement receipts");
            c.assertTrue(BackpackStorage.backpackEarned(p), "Admin can open backpack storage without an achievement");
            c.assertValueEqual(BackpackStorage.claim(p, "dragon"), 1, "Admin can claim a backpack appearance");
            c.assertValueEqual(BackpackStorage.armor(p, "aurora"), 1, "Admin can claim cosmetic armor");
            c.assertTrue(BackpackStorage.owned(p).isEmpty(), "Appearance permission does not fabricate unlock receipts");
            p.setGameMode(GameType.SPECTATOR);
            c.assertTrue(AchievementRewards.allowed(p), "Admin inherits existing OP4 reward policy");
            c.assertValueEqual(BackpackStorage.open(p), 0, "Admin spectator cannot withdraw stored items");
            p.setGameMode(GameType.SURVIVAL);
            c.assertTrue(Memberships.get(c.getLevel().getServer()).revokeAdmin(p.getUUID()), "Admin revocation succeeds");
            rewards.apply(p);
            c.assertFalse(p.getAbilities().mayfly || p.hasEffect(MobEffects.RESISTANCE), "Revocation removes unearned active powers");
            c.assertFalse(rewards.unlocked(p, "hacks") || BackpackStorage.backpackEarned(p), "Revocation removes entitlement bypasses");
            c.assertValueEqual(rewards.emit(p), 0, "Revoked unearned cosmetic stops emitting");
            c.assertFalse(rewards.cosmetic(p, "diamond"), "A revoked Admin cannot claim another locked cosmetic");
        } finally { rewards.clearPowers(p, false); cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminGearMenusPreserveItemsAndRecheckTheRole(GameTestHelper c) {
        var p = player(c, "admin-gear");
        try {
            var old = new ItemStack(Items.DIAMOND_PICKAXE);
            old.set(DataComponents.CUSTOM_NAME, Component.literal("My original pickaxe"));
            p.setItemInHand(InteractionHand.MAIN_HAND, old);
            c.assertTrue(ServerMenu.gearAllowed(p), "Admin can use the catalog in ordinary Survival");
            ServerMenu.open(p); click(p, ServerMenu.GEAR);
            var gear = (ServerMenu.Handler)p.containerMenu;
            int sword = gear.paths.indexOf("convergence:sword");
            while (gear.pageIndex < sword / ServerMenu.PAGE_SIZE) { click(p, ServerMenu.NEXT); gear = (ServerMenu.Handler)p.containerMenu; }
            click(p, sword % ServerMenu.PAGE_SIZE);
            c.assertValueEqual(p.getMainHandItem().getItem(), Convergence.ITEMS.get("convergence:sword"), "Admin menu equips real Infinity gear");
            c.assertTrue(p.getInventory().contains(old), "The existing named item stays in inventory");
            c.assertValueEqual(Convergence.giveBuildingKit(p), 1, "Admin can claim the building kit with its OP4 access");
            c.assertTrue(p.getInventory().contains(new ItemStack(Convergence.ITEMS.get("convergence:builder_wand"))), "The building kit contains a real builder wand");
            ServerMenu.open(p); click(p, ServerMenu.GEAR);
            gear = (ServerMenu.Handler)p.containerMenu;
            c.assertTrue(Memberships.get(c.getLevel().getServer()).revokeAdmin(p.getUUID()), "Admin revocation succeeds");
            ItemStack before = p.getMainHandItem().copy();
            ((AbstractContainerMenu)gear).clicked(0, 0, ContainerInput.PICKUP, p);
            c.assertTrue(ItemStack.isSameItemSameComponents(before, p.getMainHandItem()), "Revoking Admin while the catalog is open prevents further grants");
            c.assertFalse(ServerMenu.gearAllowed(p), "Catalog eligibility rechecks current membership");
            p.closeContainer();
            c.assertValueEqual(Convergence.giveKit(p), 0, "Revoked Survival Admin cannot use the kit fallback");
            c.assertTrue(Memberships.get(c.getLevel().getServer()).grantAdmin(p.getUUID()), "Admin grant succeeds");
            p.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(Convergence.giveKit(p), 0, "Admin kit respects spectator state");
            c.assertValueEqual(Convergence.giveBuildingKit(p), 0, "Admin building kit respects spectator state");
            c.assertValueEqual(Convergence.holdCreativeItem(p, "mace"), 0, "Admin hand placement respects spectator state");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminHomesAndTravelHaveFullOperatorAccess(GameTestHelper c) {
        var p = player(c, "admin-homes");
        var community = CommunityServer.get(c.getLevel().getServer());
        try {
            for (int i = 0; i < 5; i++) community.setHome(p, "home" + i);
            c.assertValueEqual(community.homes(p).size(), 5, "Admin has more than three homes");
            var start = CommunityServer.Place.of(p);
            var target = safePlace(p, 4);
            community.combat.put(p.getUUID(), community.server.getTickCount() + 500);
            community.cooldowns.put(p.getUUID(), community.server.getTickCount() + 500);
            community.queue(p, target, null);
            c.assertValueEqual(p.getX(), target.x(), "Admin travel is immediate despite ordinary timers");
            c.assertFalse(community.pending.containsKey(p.getUUID()), "Admin travel leaves no countdown");
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            community.queue(p, GameModes.defaultPlace(community.server, GameModes.Mode.CREATIVE), null);
            c.assertValueEqual(GameModes.current(p), GameModes.Mode.CREATIVE, "Full OP4 home travel can change worlds");
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)), "Cross-world OP travel still isolates Survival inventory");
            community.queue(p, target, null);
            c.assertValueEqual(p.getInventory().getItem(0).getCount(), 3, "Returning restores Survival inventory");
            c.assertTrue(Memberships.get(community.server).revokeAdmin(p.getUUID()), "Admin revocation succeeds");
            community.setHome(p, "extra");
            c.assertValueEqual(community.homes(p).size(), 5, "Revocation blocks extra homes without deleting saved homes");
            community.queue(p, start, null);
            c.assertValueEqual(p.getX(), target.x(), "Normal combat restriction returns immediately after revocation");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminInstantModesPreserveInventoriesAndHardcoreState(GameTestHelper c) {
        var p = player(c, "admin-modes");
        var server = c.getLevel().getServer();
        try {
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            SimpleContainer survivalBackpack = new BackpackStorage.Storage(p);
            survivalBackpack.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            CommunityServer.get(server).combat.put(p.getUUID(), server.getTickCount() + 500);
            GameModes.request(p, GameModes.Mode.CREATIVE, null);
            c.assertValueEqual(GameModes.current(p), GameModes.Mode.CREATIVE, "Admin mode changes are immediate despite ordinary combat timers");
            c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "Instant mode access creates no warmup");
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)), "Survival inventory is not copied into Creative");
            c.assertFalse(survivalBackpack.stillValid(p), "Old backpack handler cannot cross modes");
            SimpleContainer creativeBackpack = new BackpackStorage.Storage(p);
            c.assertTrue(creativeBackpack.isEmpty(), "Creative backpack starts separate");
            creativeBackpack.setItem(0, new ItemStack(Items.GOLD_BLOCK, 64));
            p.getInventory().setItem(0, new ItemStack(Items.NETHERITE_BLOCK, 64));
            GameModes.request(p, GameModes.Mode.SURVIVAL, null);
            c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND), "Returning restores the original Survival item");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(), 5, "Returning preserves the original Survival count");
            SimpleContainer restoredBackpack = new BackpackStorage.Storage(p);
            c.assertTrue(restoredBackpack.getItem(0).is(Items.IRON_INGOT), "Creative backpack items never enter Survival storage");
            GameModes.state(p).putBoolean("eliminated", true);
            GameModes.request(p, GameModes.Mode.HARDCORE, null);
            c.assertValueEqual(GameModes.current(p), GameModes.Mode.HARDCORE, "Admin may return to an eliminated Hardcore profile");
            c.assertValueEqual(p.gameMode(), GameType.SURVIVAL, "Admin can play Hardcore instead of spectating");
            c.assertTrue(GameModes.state(p).getBooleanOr("eliminated", false), "Bypass does not erase stored elimination state");
            c.assertTrue(Memberships.get(server).revokeAdmin(p.getUUID()), "Admin revocation succeeds");
            c.assertValueEqual(GameModes.gameMode(p), GameType.SPECTATOR, "Revocation restores the stored Hardcore elimination rule");
            c.assertTrue(Memberships.get(server).grantAdmin(p.getUUID()), "Admin grant succeeds");
            GameModes.request(p, GameModes.Mode.HUB, "main");
            c.assertTrue(Memberships.operator(p), "Admin keeps actual OP4 in the Hub");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminProvenanceRestoresPriorOpAndPreservesIndependentOp(GameTestHelper c) {
        var p = new ModeGameTests().player(c, "admin-provenance");
        var members = new ModeGameTests().members(c);
        try {
            members.server.getPlayerList().op(OperatorGameTests.entry(p), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.of(true));
            c.assertTrue(members.grantAdmin(p.getUUID()), "Existing OP2 can receive Admin");
            c.assertTrue(Memberships.operator(p), "Promotion reaches OP4");
            var reload = new Memberships(members.server, members.file, members.configFile);
            c.assertTrue(reload.account(p.getUUID()).adminOpOwned, "UUID-bound promotion provenance survives reload");
            c.assertTrue(reload.revokeAdmin(p.getUUID()), "Reloaded service restores owned promotion");
            var original = members.server.getPlayerList().getOps().get(OperatorGameTests.entry(p));
            c.assertValueEqual(original.permissions(), LevelBasedPermissionSet.GAMEMASTER, "Revocation restores prior OP2");
            c.assertTrue(original.getBypassesPlayerLimit(), "Original player-limit flag survives promotion and revocation");
            OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
            c.assertTrue(reload.grantAdmin(p.getUUID()), "Independent OP4 can receive Admin badge");
            c.assertFalse(reload.account(p.getUUID()).adminOpOwned, "Existing OP4 is never claimed by Admin");
            c.assertTrue(reload.revokeAdmin(p.getUUID()) && Memberships.operator(p), "Revocation preserves preexisting OP4");
            OperatorGameTests.deop(p);
            c.assertTrue(reload.grantAdmin(p.getUUID()), "Admin can create a new owned promotion");
            OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
            c.assertTrue(reload.revokeAdmin(p.getUUID()) && Memberships.operator(p), "A later manual OP4 grant is preserved");
        } finally { members.revokeAdmin(p.getUUID()); cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminMigrationAndPermissionChangesFailClosedOnStorageFailure(GameTestHelper c) throws Exception {
        var p = new ModeGameTests().player(c, "admin-persistence");
        var members = new ModeGameTests().members(c);
        try {
            members.account(p.getUUID()).admin = true;
            members.save();
            var reload = new Memberships(members.server, members.file, members.configFile);
            c.assertTrue(reload.syncAdminOperator(p) && Memberships.operator(p), "A saved legacy Admin migrates to actual OP4");
            var originalFile = Files.readString(reload.file);
            Files.delete(reload.file); Files.createDirectory(reload.file); Files.writeString(reload.file.resolve("block"), "block");
            c.assertFalse(reload.revokeAdmin(p.getUUID()), "An unwritable membership file blocks revocation before operator mutation");
            c.assertTrue(Memberships.operator(p) && reload.account(p.getUUID()).admin, "Failed persistence preserves prior role and OP state");
            Files.delete(reload.file.resolve("block")); Files.delete(reload.file); Files.writeString(reload.file, originalFile);
            c.assertTrue(reload.revokeAdmin(p.getUUID()) && !Memberships.operator(p), "Revocation succeeds after storage recovery");
            Files.delete(reload.file); Files.createDirectory(reload.file); Files.writeString(reload.file.resolve("block"), "block");
            c.assertFalse(reload.grantAdmin(p.getUUID()), "An unwritable membership file blocks promotion before operator mutation");
            c.assertFalse(Memberships.operator(p) || reload.account(p.getUUID()).admin, "Failed grant does not leave live permissions");
            Files.delete(reload.file.resolve("block")); Files.delete(reload.file); reload.save();
        } finally { members.revokeAdmin(p.getUUID()); cleanup(p); }
        c.succeed();
    }

    @GameTest public void adminBypassesWeaponCooldownsUntilItsRoleIsRevoked(GameTestHelper c) {
        var p = player(c, "admin-cooldowns");
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Convergence.ITEMS.get("convergence:mace")));
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Convergence.ITEMS.get("convergence:spear")));
            Convergence.state(p).cooldown.put("spear", Convergence.clock + 200);
            c.assertTrue(Convergence.arm(p), "Admin arms a real spear despite its cooldown");
            c.assertTrue(Convergence.ready(p, "test_power", 200) && Convergence.ready(p, "test_power", 200), "Admin repeats timed powers immediately");
            c.assertTrue(Memberships.get(c.getLevel().getServer()).revokeAdmin(p.getUUID()), "Admin revocation succeeds");
            c.assertFalse(Convergence.arm(p), "Stored spear cooldown applies again after revocation");
            OperatorGameTests.level(p, LevelBasedPermissionSet.OWNER);
            c.assertTrue(Convergence.ready(p, "test_power", 200), "Ordinary OP4 can first activate a power");
            c.assertFalse(Convergence.ready(p, "test_power", 200), "Unlimited weapon cooldown privilege belongs to Admin role");
        } finally { cleanup(p); }
        c.succeed();
    }
}

package dev.convergence;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class ModeGameTests {
    ServerPlayer player(GameTestHelper c,String name) {
        var server=c.getLevel().getServer();var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),name);
        var data=net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false);
        var p=new ServerPlayer(server,c.getLevel(),profile,data.clientInformation());
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);server.getPlayerList().placeNewPlayer(connection,p,data);
        p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        var feet=c.absolutePos(new BlockPos(2,2,2));c.getLevel().setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
        c.getLevel().setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());c.getLevel().setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());
        p.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);p.setNoGravity(true);return p;
    }
    Memberships members(GameTestHelper c) {
        try {var dir=Files.createTempDirectory("infinity-memberships-test-");return new Memberships(c.getLevel().getServer(),dir.resolve("members.json"),dir.resolve("config.json"));}
        catch(Exception e){throw new RuntimeException(e);}
    }
    @GameTest public void modesHaveRealWorldsAndHardcoreDifficulty(GameTestHelper c) {
        var server=c.getLevel().getServer();
        for(var mode:GameModes.Mode.values())c.assertTrue(GameModes.world(server,mode)!=null,"Dimension exists: "+mode);
        c.assertValueEqual(GameModes.world(server,GameModes.Mode.HARDCORE).getDifficulty(),Difficulty.HARD,"Hardcore uses hard difficulty");
        c.assertValueEqual(GameModes.world(server,GameModes.Mode.ADVENTURE).getDifficulty(),Difficulty.PEACEFUL,"Built-in maps are peaceful");c.succeed();
    }
    @GameTest public void modesSnapshotKeepsArmorEnderXpHealthAndEffects(GameTestHelper c) {
        var p=player(c,"profile-snapshot");p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));
        p.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Convergence.ITEMS.get("convergence:chestplate")));
        p.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        p.getEnderChestInventory().setItem(3,new ItemStack(Items.EMERALD,11));p.setHealth(13);p.getFoodData().setFoodLevel(12);
        p.setExperienceLevels(9);p.setExperiencePoints(4);p.addEffect(new MobEffectInstance(MobEffects.POISON,120));
        var snapshot=GameModes.capture(p);p.getInventory().clearContent();p.getEnderChestInventory().clearContent();p.removeAllEffects();p.setHealth(20);
        GameModes.restore(p,snapshot);
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Main inventory preserved");
        c.assertTrue(p.getItemBySlot(EquipmentSlot.CHEST).is(Convergence.ITEMS.get("convergence:chestplate")),"Custom chestplate preserved");
        c.assertTrue(p.getOffhandItem().is(Items.TOTEM_OF_UNDYING),"Offhand preserved");
        c.assertValueEqual(p.getEnderChestInventory().getItem(3).getCount(),11,"Ender chest preserved");c.assertValueEqual(p.getHealth(),13f,"Health preserved");
        c.assertValueEqual(p.getFoodData().getFoodLevel(),12,"Food preserved");c.assertValueEqual(p.experienceLevel,9,"XP level preserved");
        c.assertTrue(p.hasEffect(MobEffects.POISON),"Negative effect cannot be erased by mode switching");c.succeed();
    }
    @GameTest public void modesSwitchInventoryAndReturnToOriginalWorld(GameTestHelper c) {
        var p=player(c,"world-switch");var start=CommunityServer.Place.of(p);p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.CREATIVE,"Mode switched");c.assertValueEqual(GameModes.of(p.level()),GameModes.Mode.CREATIVE,"Dimension switched");
        c.assertValueEqual(p.gameMode(),GameType.CREATIVE,"Creative ability enabled");
        c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)),"Survival inventory stays hidden behind the Creative starter gear");
        p.getInventory().setItem(0,new ItemStack(Items.NETHERITE_BLOCK,64));p.getEnderChestInventory().setItem(0,new ItemStack(Items.DIAMOND_BLOCK,64));
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND),"Creative items do not enter survival");c.assertValueEqual(p.getInventory().getItem(0).getCount(),3,"Original count preserved");
        c.assertTrue(p.getEnderChestInventory().isEmpty(),"Creative Ender chest isolated");c.assertFalse(CommunityServer.moved(start,p),"Returned to own location");
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);c.assertValueEqual(p.getInventory().getItem(0).getCount(),64,"Creative inventory resumes");c.succeed();
    }
    @GameTest public void modesRejectPlayerAndItemPortalLeaks(GameTestHelper c) {
        var p=player(c,"portal-guard");var creative=GameModes.world(c.getLevel().getServer(),GameModes.Mode.CREATIVE);
        c.assertFalse(p.teleportTo(creative,0,100,0,Set.of(),0,0,true),"Direct teleport cannot bypass mode transfer");
        var target=new TeleportTransition(creative,new Vec3(0,100,0),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING);
        c.assertTrue(p.teleport(target)==null,"Portal cannot bypass mode transfer");
        var item=new ItemEntity(c.getLevel(),0,100,0,new ItemStack(Items.DIAMOND));
        c.assertTrue(item.teleport(target)==null,"Dropped items cannot leak through portals");
        var community=CommunityServer.get(c.getLevel().getServer());
        community.queue(p,new CommunityServer.Place("convergence:creative",0.5,-60,0.5,0,0),null);
        c.assertFalse(community.pending.containsKey(p.getUUID()),"Home/warp cannot bypass mode transfer");c.succeed();
    }
    @GameTest public void modesSaveWithVanillaPlayerDataAndCopyOnRespawn(GameTestHelper c) {
        var p=player(c,"saved-modes");p.getInventory().setItem(1,new ItemStack(Items.EMERALD,5));GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        var writer=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.registryAccess());p.saveWithoutId(writer);
        c.assertTrue(writer.buildResult().contains("InfinityModes"),"State shares vanilla player save");
        var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"restored-modes");var fresh=new ServerPlayer(c.getLevel().getServer(),p.level(),profile,p.clientInformation());
        fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),writer.buildResult()));
        c.assertValueEqual(GameModes.current(fresh),GameModes.Mode.CREATIVE,"Active mode survives serialization");
        c.assertTrue(GameModes.state(fresh).getCompoundOrEmpty("profiles").contains("SURVIVAL"),"Inactive inventory survives serialization");
        GameModes.state(p).putBoolean("eliminated",true);fresh.restoreFrom(p,false);
        c.assertTrue(GameModes.state(fresh).getBooleanOr("eliminated",false),"One-life marker survives respawn");c.succeed();
    }
    @GameTest public void hardcoreDeathLocksOnlyHardcore(GameTestHelper c) {
        var p=player(c,"one-life");GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);GameModes.death(p);
        c.assertTrue(GameModes.state(p).getBooleanOr("eliminated",false),"Death recorded");
        c.assertValueEqual(GameModes.gameMode(p),GameType.SPECTATOR,"Eliminated Hardcore is spectator");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);GameModes.request(p,GameModes.Mode.HARDCORE,null);
        c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()),"Eliminated players cannot re-enter");
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.SURVIVAL,"Other modes remain playable");c.succeed();
    }
    @GameTest public void hardcoreRealDeathAndRespawnCannotReturnToLife(GameTestHelper c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=player(c,"real-hardcore");GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);
        p.hasChangedDimension();p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        p.hurtServer(p.level(),p.damageSources().genericKill(),Float.MAX_VALUE);
        c.assertFalse(p.isAlive(),"Damage caused actual death");
        c.assertTrue(GameModes.state(p).getBooleanOr("eliminated",false),"Actual death event records elimination");
        var next=c.getLevel().getServer().getPlayerList().respawn(p,false,Entity.RemovalReason.KILLED);
        c.assertValueEqual(GameModes.current(next),GameModes.Mode.HARDCORE,"Respawn copies active mode");
        c.assertValueEqual(GameModes.of(next.level()),GameModes.Mode.HARDCORE,"Respawn stays in Hardcore world");
        c.assertValueEqual(next.gameMode(),GameType.SPECTATOR,"Respawn only allows spectating");
        var commands=c.getLevel().getServer().getCommands().getDispatcher();
        c.assertValueEqual(commands.execute("play minigames",next.createCommandSourceStack()),1,"Eliminated Hardcore spectator can open a minigame menu");
        c.assertTrue(next.containerMenu instanceof CourseSelector.Handler,"Spectator sees the vanilla course menu");
        var menu=(CourseSelector.Handler)next.containerMenu;
        ((net.minecraft.world.inventory.AbstractContainerMenu)menu).clicked(CourseSelector.slot(0,CourseSelector.courses(GameModes.Mode.MINIGAMES).size()),0,net.minecraft.world.inventory.ContainerInput.PICKUP,next);
        c.assertTrue(GameModes.PENDING.containsKey(next.getUUID()),"Spectator can choose a course after Hardcore elimination");
        GameModes.PENDING.remove(next.getUUID());
        GameModes.switchNow(next,GameModes.Mode.SURVIVAL,null);
        c.assertValueEqual(next.gameMode(),GameType.SURVIVAL,"Can continue Survival after losing Hardcore");c.succeed();
    }
    @GameTest public void adminRoleGrantsActualOperatorCommands(GameTestHelper c) {
        var p=player(c,"admin-commands");var s=Memberships.get(c.getLevel().getServer());
        try {
            c.assertTrue(s.grantAdmin(p.getUUID()),"Trusted grant succeeds");
            var root=s.server.getCommands().getDispatcher().getRoot();
            for(String command:List.of("staff","membership","community","op"))
                c.assertTrue(root.getChild(command).canUse(p.createCommandSourceStack()),"Admin has actual OP4 command "+command);
        } finally {s.revokeAdmin(p.getUUID());OperatorGameTests.deop(p);}
        c.succeed();
    }
    @GameTest public void modesRespawnInTheirOwnWorld(GameTestHelper c) {
        var p=player(c,"respawn-mode");GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        var target=p.findRespawnPositionAndUseSpawnBlock(false,TeleportTransition.DO_NOTHING);
        c.assertValueEqual(GameModes.of(target.newLevel()),GameModes.Mode.CREATIVE,"Death cannot respawn Creative inventory into survival");c.succeed();
    }
    @GameTest public void modesRequireWarmupAndCombatDelay(GameTestHelper c) {
        var p=player(c,"mode-warmup");GameModes.request(p,GameModes.Mode.CREATIVE,null);
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.SURVIVAL,"Request is not instant");
        c.assertTrue(GameModes.PENDING.get(p.getUUID()).finish()>c.getLevel().getServer().getTickCount(),"Warmup in future");GameModes.PENDING.remove(p.getUUID());
        CommunityServer.get(c.getLevel().getServer()).combat.put(p.getUUID(),c.getLevel().getServer().getTickCount()+200);
        GameModes.request(p,GameModes.Mode.CREATIVE,null);c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()),"Combat blocks escape");c.succeed();
    }
    @GameTest public void membershipsCodeGrantsPersistentAdminAndOperator(GameTestHelper c) {
        var p=player(c,"code-user");var s=members(c);s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        try {
            c.assertTrue(s.redeem(p.getUUID(),"test-secret",1000),"Correct code unlocks Admin");
            c.assertValueEqual(s.label(p.getUUID()),"ADMIN","Admin rank saved");
            c.assertTrue(Memberships.operator(p),"Code grants actual OP4");
            var reload=new Memberships(s.server,s.file,s.configFile);
            c.assertTrue(reload.account(p.getUUID()).admin && reload.account(p.getUUID()).adminOpOwned,"Role and operator provenance persist");
            var root=s.server.getCommands().getDispatcher().getRoot();
            c.assertTrue(root.getChild("membership").canUse(p.createCommandSourceStack()),"Admin can manage ranks");
            c.assertFalse(root.getChild("agent-codex-approve").canUse(p.createCommandSourceStack()),"Admin cannot replace Codex review");
        } finally {s.revokeAdmin(p.getUUID());OperatorGameTests.deop(p);}
        c.succeed();
    }
    @GameTest public void membershipsCodeRateLimitSurvivesReload(GameTestHelper c) {
        var s=members(c);UUID id=UUID.randomUUID();s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        c.assertFalse(s.redeem(id,"wrong",1000),"Wrong code rejected");c.assertFalse(s.redeem(id,"test-secret",1001),"Immediate retries throttled");
        s.redeem(id,"wrong",11001);s.redeem(id,"wrong",21002);
        c.assertTrue(s.account(id).lockedUntil>=921002,"Three failures lock for 15 minutes");
        var reload=new Memberships(s.server,s.file,s.configFile);c.assertTrue(reload.account(id).lockedUntil>=921002,"Lock survives restart");
        c.assertFalse(reload.account(id).admin,"Failures never grant role");c.succeed();
    }
    @GameTest public void membershipsExpireToFreeWithoutRemovingAdmin(GameTestHelper c) {
        var s=members(c);UUID id=UUID.randomUUID();var a=s.account(id);a.tier=Memberships.Tier.ULTRA;a.expires=System.currentTimeMillis()+60000;
        c.assertValueEqual(s.tier(id),Memberships.Tier.ULTRA,"Active temporary override recognized");a.expires=1;a.admin=true;
        c.assertValueEqual(s.tier(id),Memberships.Tier.FREE,"Expired override falls back to Free");c.assertValueEqual(s.label(id),"ADMIN","Free Admin unaffected by override expiry");
        a.earned=Memberships.Tier.PLUS;c.assertValueEqual(s.tier(id),Memberships.Tier.PLUS,"Earned rank survives an expired override");
        c.assertValueEqual(s.label(id),"ADMIN","Admin badge overlays the earned rank");c.succeed();
    }
    @GameTest public void achievementRanksUnlockAutomaticallyAndPersist(GameTestHelper c) {
        var p=player(c,"achievement-ranks");var s=members(c);
        c.assertValueEqual(s.tier(p.getUUID()),Memberships.Tier.FREE,"New player begins on Free");
        String[][] advancements={
            {"story/upgrade_tools","story/smelt_iron","adventure/kill_a_mob"},
            {"story/iron_tools","story/mine_diamond","story/enchant_item"},
            {"story/enter_the_nether","nether/obtain_blaze_rod","story/enter_the_end"},
            {"end/kill_dragon","end/enter_end_gateway","end/find_end_city"}};
        Memberships.Tier[] tiers={Memberships.Tier.GO,Memberships.Tier.PLUS,Memberships.Tier.PRO,Memberships.Tier.ULTRA};
        for(int i=0;i<advancements.length;i++) {
            for(int j=0;j<advancements[i].length;j++) {
                var entry=s.server.getAdvancements().get(Identifier.fromNamespaceAndPath("minecraft",advancements[i][j]));
                c.assertTrue(entry!=null,"Vanilla advancement exists: "+advancements[i][j]);
                p.getAdvancements().award(entry,entry.value().criteria().keySet().iterator().next());
                s.syncAchievements(p);
                var expected=j==advancements[i].length-1?tiers[i]:i==0?Memberships.Tier.FREE:tiers[i-1];
                c.assertValueEqual(s.tier(p.getUUID()),expected,"All three advancements are required for "+tiers[i]);
            }
            c.assertValueEqual(s.account(p.getUUID()).earned,tiers[i],"Completed group is recorded permanently: "+tiers[i]);
        }
        var reload=new Memberships(s.server,s.file,s.configFile);
        c.assertValueEqual(reload.account(p.getUUID()).earned,Memberships.Tier.ULTRA,"Achievement rank survives reload");
        var a=reload.account(p.getUUID());a.tier=Memberships.Tier.GO;a.expires=System.currentTimeMillis()+60000;
        c.assertValueEqual(reload.tier(p.getUUID()),Memberships.Tier.ULTRA,"Lower temporary override cannot demote an earned rank");
        a.tier=Memberships.Tier.FREE;a.expires=0;
        c.assertValueEqual(reload.tier(p.getUUID()),Memberships.Tier.ULTRA,"Revoking an override cannot revoke an earned rank");
        try {
            var legacy=s.file.resolveSibling("legacy.json");
            Files.writeString(legacy,"{\"format\":1,\"accounts\":{\""+p.getStringUUID()+"\":{\"tier\":\"GO\",\"expires\":1,\"admin\":false,\"attempts\":0,\"lockedUntil\":0}}}");
            var migrated=new Memberships(s.server,legacy,s.configFile);
            c.assertValueEqual(migrated.account(p.getUUID()).earned,Memberships.Tier.FREE,"Old membership data gains a safe default achievement rank");
        } catch(java.io.IOException e) {throw new RuntimeException(e);}
        c.succeed();
    }
    @GameTest public void builtInMapsHaveSafeStartsAndOrderedFinishes(GameTestHelper c) {
        var p=player(c,"map-runner");
        for(var spec:ModeMaps.MAPS.values()) {
            if(spec.kind()!=ModeMaps.Kind.CHECKPOINTS)continue;
            GameModes.switchNow(p,spec.mode(),spec.id());ModeMaps.begin(p,spec.id());
            var start=CommunityServer.Place.of(p);c.assertTrue(CommunityServer.safe(p.level(),start),"Map start safe: "+spec.id());
            var last=spec.points().getLast();p.setPos(Vec3.atBottomCenterOf(last));p.setOnGround(true);ModeMaps.tick(p);
            c.assertTrue(ModeMaps.RUNS.containsKey(p.getUUID()),"Skipping to finish is rejected");
            for(int i=1;i<spec.points().size();i++){p.setPos(Vec3.atBottomCenterOf(spec.points().get(i)));p.setOnGround(true);ModeMaps.tick(p);}
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUUID()),"Ordered route completes: "+spec.id());
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").contains(spec.id()),"Personal best saved");
        }
        c.succeed();
    }
}

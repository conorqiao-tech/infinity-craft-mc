package dev.convergence;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.NbtReadView;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TeleportTarget;

public class ModeGameTests {
    ServerPlayerEntity player(TestContext c,String name) {
        var server=c.getWorld().getServer();var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),name);
        var data=net.minecraft.server.network.ConnectedClientData.createDefault(profile,false);
        var p=new ServerPlayerEntity(server,c.getWorld(),profile,data.syncedOptions());
        var connection=new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);server.getPlayerManager().onPlayerConnect(connection,p,data);
        p.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        var feet=c.getAbsolutePos(new BlockPos(2,2,2));c.getWorld().setBlockState(feet.down(),Blocks.STONE.getDefaultState());
        c.getWorld().setBlockState(feet,Blocks.AIR.getDefaultState());c.getWorld().setBlockState(feet.up(),Blocks.AIR.getDefaultState());
        p.setPosition(feet.getX()+.5,feet.getY(),feet.getZ()+.5);p.setNoGravity(true);return p;
    }
    Memberships members(TestContext c) {
        try {var dir=Files.createTempDirectory("infinity-memberships-test-");return new Memberships(c.getWorld().getServer(),dir.resolve("members.json"),dir.resolve("config.json"));}
        catch(Exception e){throw new RuntimeException(e);}
    }
    @GameTest public void modesHaveRealWorldsAndHardcoreDifficulty(TestContext c) {
        var server=c.getWorld().getServer();
        for(var mode:GameModes.Mode.values())c.assertTrue(GameModes.world(server,mode)!=null,"Dimension exists: "+mode);
        c.assertEquals(GameModes.world(server,GameModes.Mode.HARDCORE).getDifficulty(),Difficulty.HARD,"Hardcore uses hard difficulty");
        c.assertEquals(GameModes.world(server,GameModes.Mode.ADVENTURE).getDifficulty(),Difficulty.PEACEFUL,"Built-in maps are peaceful");c.complete();
    }
    @GameTest public void modesSnapshotKeepsArmorEnderXpHealthAndEffects(TestContext c) {
        var p=player(c,"profile-snapshot");p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,7));
        p.equipStack(EquipmentSlot.CHEST,new ItemStack(Convergence.ITEMS.get("convergence:chestplate")));
        p.equipStack(EquipmentSlot.OFFHAND,new ItemStack(Items.TOTEM_OF_UNDYING));
        p.getEnderChestInventory().setStack(3,new ItemStack(Items.EMERALD,11));p.setHealth(13);p.getHungerManager().setFoodLevel(12);
        p.setExperienceLevel(9);p.setExperiencePoints(4);p.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON,120));
        var snapshot=GameModes.capture(p);p.getInventory().clear();p.getEnderChestInventory().clear();p.clearStatusEffects();p.setHealth(20);
        GameModes.restore(p,snapshot);
        c.assertEquals(p.getInventory().getStack(0).getCount(),7,"Main inventory preserved");
        c.assertTrue(p.getEquippedStack(EquipmentSlot.CHEST).isOf(Convergence.ITEMS.get("convergence:chestplate")),"Custom chestplate preserved");
        c.assertTrue(p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING),"Offhand preserved");
        c.assertEquals(p.getEnderChestInventory().getStack(3).getCount(),11,"Ender chest preserved");c.assertEquals(p.getHealth(),13f,"Health preserved");
        c.assertEquals(p.getHungerManager().getFoodLevel(),12,"Food preserved");c.assertEquals(p.experienceLevel,9,"XP level preserved");
        c.assertTrue(p.hasStatusEffect(StatusEffects.POISON),"Negative effect cannot be erased by mode switching");c.complete();
    }
    @GameTest public void modesSwitchInventoryAndReturnToOriginalWorld(TestContext c) {
        var p=player(c,"world-switch");var start=CommunityServer.Place.of(p);p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,3));
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        c.assertEquals(GameModes.current(p),GameModes.Mode.CREATIVE,"Mode switched");c.assertEquals(GameModes.of(p.getEntityWorld()),GameModes.Mode.CREATIVE,"Dimension switched");
        c.assertEquals(p.getGameMode(),GameMode.CREATIVE,"Creative ability enabled");
        c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)),"Survival inventory stays hidden behind the Creative starter gear");
        p.getInventory().setStack(0,new ItemStack(Items.NETHERITE_BLOCK,64));p.getEnderChestInventory().setStack(0,new ItemStack(Items.DIAMOND_BLOCK,64));
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertTrue(p.getInventory().getStack(0).isOf(Items.DIAMOND),"Creative items do not enter survival");c.assertEquals(p.getInventory().getStack(0).getCount(),3,"Original count preserved");
        c.assertTrue(p.getEnderChestInventory().isEmpty(),"Creative Ender chest isolated");c.assertFalse(CommunityServer.moved(start,p),"Returned to own location");
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);c.assertEquals(p.getInventory().getStack(0).getCount(),64,"Creative inventory resumes");c.complete();
    }
    @GameTest public void modesRejectPlayerAndItemPortalLeaks(TestContext c) {
        var p=player(c,"portal-guard");var creative=GameModes.world(c.getWorld().getServer(),GameModes.Mode.CREATIVE);
        c.assertFalse(p.teleport(creative,0,100,0,Set.of(),0,0,true),"Direct teleport cannot bypass mode transfer");
        var target=new TeleportTarget(creative,new Vec3d(0,100,0),Vec3d.ZERO,0,0,TeleportTarget.NO_OP);
        c.assertTrue(p.teleportTo(target)==null,"Portal cannot bypass mode transfer");
        var item=new ItemEntity(c.getWorld(),0,100,0,new ItemStack(Items.DIAMOND));
        c.assertTrue(item.teleportTo(target)==null,"Dropped items cannot leak through portals");
        var community=CommunityServer.get(c.getWorld().getServer());
        community.queue(p,new CommunityServer.Place("convergence:creative",0.5,-60,0.5,0,0),null);
        c.assertFalse(community.pending.containsKey(p.getUuid()),"Home/warp cannot bypass mode transfer");c.complete();
    }
    @GameTest public void modesSaveWithVanillaPlayerDataAndCopyOnRespawn(TestContext c) {
        var p=player(c,"saved-modes");p.getInventory().setStack(1,new ItemStack(Items.EMERALD,5));GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        var writer=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());p.writeData(writer);
        c.assertTrue(writer.getNbt().contains("InfinityModes"),"State shares vanilla player save");
        var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"restored-modes");var fresh=new ServerPlayerEntity(c.getWorld().getServer(),p.getEntityWorld(),profile,p.getClientOptions());
        fresh.readData(NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),writer.getNbt()));
        c.assertEquals(GameModes.current(fresh),GameModes.Mode.CREATIVE,"Active mode survives serialization");
        c.assertTrue(GameModes.state(fresh).getCompoundOrEmpty("profiles").contains("SURVIVAL"),"Inactive inventory survives serialization");
        GameModes.state(p).putBoolean("eliminated",true);fresh.copyFrom(p,false);
        c.assertTrue(GameModes.state(fresh).getBoolean("eliminated",false),"One-life marker survives respawn");c.complete();
    }
    @GameTest public void hardcoreDeathLocksOnlyHardcore(TestContext c) {
        var p=player(c,"one-life");GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);GameModes.death(p);
        c.assertTrue(GameModes.state(p).getBoolean("eliminated",false),"Death recorded");
        c.assertEquals(GameModes.gameMode(p),GameMode.SPECTATOR,"Eliminated Hardcore is spectator");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);GameModes.request(p,GameModes.Mode.HARDCORE,null);
        c.assertFalse(GameModes.PENDING.containsKey(p.getUuid()),"Eliminated players cannot re-enter");
        c.assertEquals(GameModes.current(p),GameModes.Mode.SURVIVAL,"Other modes remain playable");c.complete();
    }
    @GameTest public void hardcoreRealDeathAndRespawnCannotReturnToLife(TestContext c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=player(c,"real-hardcore");GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);
        p.onTeleportationDone();p.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        p.damage(p.getEntityWorld(),p.getDamageSources().genericKill(),Float.MAX_VALUE);
        c.assertFalse(p.isAlive(),"Damage caused actual death");
        c.assertTrue(GameModes.state(p).getBoolean("eliminated",false),"Actual death event records elimination");
        var next=c.getWorld().getServer().getPlayerManager().respawnPlayer(p,false,Entity.RemovalReason.KILLED);
        c.assertEquals(GameModes.current(next),GameModes.Mode.HARDCORE,"Respawn copies active mode");
        c.assertEquals(GameModes.of(next.getEntityWorld()),GameModes.Mode.HARDCORE,"Respawn stays in Hardcore world");
        c.assertEquals(next.getGameMode(),GameMode.SPECTATOR,"Respawn only allows spectating");
        var commands=c.getWorld().getServer().getCommandManager().getDispatcher();
        c.assertEquals(commands.execute("play minigames",next.getCommandSource()),1,"Eliminated Hardcore spectator can open a minigame menu");
        c.assertTrue(next.currentScreenHandler instanceof CourseSelector.Handler,"Spectator sees the vanilla course menu");
        var menu=(CourseSelector.Handler)next.currentScreenHandler;
        ((net.minecraft.screen.ScreenHandler)menu).onSlotClick(CourseSelector.slot(0,CourseSelector.courses(GameModes.Mode.MINIGAMES).size()),0,net.minecraft.screen.slot.SlotActionType.PICKUP,next);
        c.assertTrue(GameModes.PENDING.containsKey(next.getUuid()),"Spectator can choose a course after Hardcore elimination");
        GameModes.PENDING.remove(next.getUuid());
        GameModes.switchNow(next,GameModes.Mode.SURVIVAL,null);
        c.assertEquals(next.getGameMode(),GameMode.SURVIVAL,"Can continue Survival after losing Hardcore");c.complete();
    }
    @GameTest public void adminRoleCanModerateButCannotGrantRanksOrRunOperatorCommands(TestContext c) {
        var p=player(c,"scoped-admin");var s=Memberships.get(c.getWorld().getServer());s.account(p.getUuid()).admin=true;
        var root=s.server.getCommandManager().getDispatcher().getRoot();
        c.assertTrue(root.getChild("staff").canUse(p.getCommandSource()),"Admin can use dedicated moderation tools");
        c.assertFalse(root.getChild("membership").canUse(p.getCommandSource()),"Admin cannot grant temporary rank overrides");
        c.assertFalse(root.getChild("community").canUse(p.getCommandSource()),"Admin cannot build or change shared worlds");
        c.assertFalse(root.getChild("op").canUse(p.getCommandSource()),"Admin cannot grant operator");c.complete();
    }
    @GameTest public void modesRespawnInTheirOwnWorld(TestContext c) {
        var p=player(c,"respawn-mode");GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        var target=p.getRespawnTarget(false,TeleportTarget.NO_OP);
        c.assertEquals(GameModes.of(target.world()),GameModes.Mode.CREATIVE,"Death cannot respawn Creative inventory into survival");c.complete();
    }
    @GameTest public void modesRequireWarmupAndCombatDelay(TestContext c) {
        var p=player(c,"mode-warmup");GameModes.request(p,GameModes.Mode.CREATIVE,null);
        c.assertEquals(GameModes.current(p),GameModes.Mode.SURVIVAL,"Request is not instant");
        c.assertTrue(GameModes.PENDING.get(p.getUuid()).finish()>c.getWorld().getServer().getTicks(),"Warmup in future");GameModes.PENDING.remove(p.getUuid());
        CommunityServer.get(c.getWorld().getServer()).combat.put(p.getUuid(),c.getWorld().getServer().getTicks()+200);
        GameModes.request(p,GameModes.Mode.CREATIVE,null);c.assertFalse(GameModes.PENDING.containsKey(p.getUuid()),"Combat blocks escape");c.complete();
    }
    @GameTest public void membershipsCodeGrantsScopedAdminWithoutOperator(TestContext c) {
        var p=player(c,"code-user");var s=members(c);s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        c.assertTrue(s.redeem(p.getUuid(),"test-secret",1000),"Correct code unlocks Admin");
        c.assertEquals(s.label(p.getUuid()),"ADMIN","Admin rank saved");
        c.assertFalse(CommunityServer.staff(p.getCommandSource()),"No operator level granted");
        var reload=new Memberships(s.server,s.file,s.configFile);c.assertTrue(reload.account(p.getUuid()).admin,"Role persists");
        var root=s.server.getCommandManager().getDispatcher().getRoot();
        c.assertFalse(root.getChild("membership").canUse(p.getCommandSource()),"Players cannot grant rank overrides");
        c.assertFalse(root.getChild("staff").canUse(p.getCommandSource()),"Ordinary players lack moderation commands");c.complete();
    }
    @GameTest public void membershipsCodeRateLimitSurvivesReload(TestContext c) {
        var s=members(c);UUID id=UUID.randomUUID();s.config.adminCodeEnabled=true;s.config.adminCodeSha256=Memberships.hash("test-secret");
        c.assertFalse(s.redeem(id,"wrong",1000),"Wrong code rejected");c.assertFalse(s.redeem(id,"test-secret",1001),"Immediate retries throttled");
        s.redeem(id,"wrong",11001);s.redeem(id,"wrong",21002);
        c.assertTrue(s.account(id).lockedUntil>=921002,"Three failures lock for 15 minutes");
        var reload=new Memberships(s.server,s.file,s.configFile);c.assertTrue(reload.account(id).lockedUntil>=921002,"Lock survives restart");
        c.assertFalse(reload.account(id).admin,"Failures never grant role");c.complete();
    }
    @GameTest public void membershipsExpireToFreeWithoutRemovingAdmin(TestContext c) {
        var s=members(c);UUID id=UUID.randomUUID();var a=s.account(id);a.tier=Memberships.Tier.ULTRA;a.expires=System.currentTimeMillis()+60000;
        c.assertEquals(s.tier(id),Memberships.Tier.ULTRA,"Active temporary override recognized");a.expires=1;a.admin=true;
        c.assertEquals(s.tier(id),Memberships.Tier.FREE,"Expired override falls back to Free");c.assertEquals(s.label(id),"ADMIN","Free Admin unaffected by override expiry");
        a.earned=Memberships.Tier.PLUS;c.assertEquals(s.tier(id),Memberships.Tier.PLUS,"Earned rank survives an expired override");
        c.assertEquals(s.label(id),"ADMIN","Admin badge overlays the earned rank");c.complete();
    }
    @GameTest public void achievementRanksUnlockAutomaticallyAndPersist(TestContext c) {
        var p=player(c,"achievement-ranks");var s=members(c);
        c.assertEquals(s.tier(p.getUuid()),Memberships.Tier.FREE,"New player begins on Free");
        String[][] advancements={
            {"story/upgrade_tools","story/smelt_iron","adventure/kill_a_mob"},
            {"story/iron_tools","story/mine_diamond","story/enchant_item"},
            {"story/enter_the_nether","nether/obtain_blaze_rod","story/enter_the_end"},
            {"end/kill_dragon","end/enter_end_gateway","end/find_end_city"}};
        Memberships.Tier[] tiers={Memberships.Tier.GO,Memberships.Tier.PLUS,Memberships.Tier.PRO,Memberships.Tier.ULTRA};
        for(int i=0;i<advancements.length;i++) {
            for(int j=0;j<advancements[i].length;j++) {
                var entry=s.server.getAdvancementLoader().get(Identifier.of("minecraft",advancements[i][j]));
                c.assertTrue(entry!=null,"Vanilla advancement exists: "+advancements[i][j]);
                p.getAdvancementTracker().grantCriterion(entry,entry.value().criteria().keySet().iterator().next());
                s.syncAchievements(p);
                var expected=j==advancements[i].length-1?tiers[i]:i==0?Memberships.Tier.FREE:tiers[i-1];
                c.assertEquals(s.tier(p.getUuid()),expected,"All three advancements are required for "+tiers[i]);
            }
            c.assertEquals(s.account(p.getUuid()).earned,tiers[i],"Completed group is recorded permanently: "+tiers[i]);
        }
        var reload=new Memberships(s.server,s.file,s.configFile);
        c.assertEquals(reload.account(p.getUuid()).earned,Memberships.Tier.ULTRA,"Achievement rank survives reload");
        var a=reload.account(p.getUuid());a.tier=Memberships.Tier.GO;a.expires=System.currentTimeMillis()+60000;
        c.assertEquals(reload.tier(p.getUuid()),Memberships.Tier.ULTRA,"Lower temporary override cannot demote an earned rank");
        a.tier=Memberships.Tier.FREE;a.expires=0;
        c.assertEquals(reload.tier(p.getUuid()),Memberships.Tier.ULTRA,"Revoking an override cannot revoke an earned rank");
        try {
            var legacy=s.file.resolveSibling("legacy.json");
            Files.writeString(legacy,"{\"format\":1,\"accounts\":{\""+p.getUuidAsString()+"\":{\"tier\":\"GO\",\"expires\":1,\"admin\":false,\"attempts\":0,\"lockedUntil\":0}}}");
            var migrated=new Memberships(s.server,legacy,s.configFile);
            c.assertEquals(migrated.account(p.getUuid()).earned,Memberships.Tier.FREE,"Old membership data gains a safe default achievement rank");
        } catch(java.io.IOException e) {throw new RuntimeException(e);}
        c.complete();
    }
    @GameTest public void builtInMapsHaveSafeStartsAndOrderedFinishes(TestContext c) {
        var p=player(c,"map-runner");
        for(var spec:ModeMaps.MAPS.values()) {
            if(spec.kind()!=ModeMaps.Kind.CHECKPOINTS)continue;
            GameModes.switchNow(p,spec.mode(),spec.id());ModeMaps.begin(p,spec.id());
            var start=CommunityServer.Place.of(p);c.assertTrue(CommunityServer.safe(p.getEntityWorld(),start),"Map start safe: "+spec.id());
            var last=spec.points().getLast();p.setPosition(Vec3d.ofBottomCenter(last));p.setOnGround(true);ModeMaps.tick(p);
            c.assertTrue(ModeMaps.RUNS.containsKey(p.getUuid()),"Skipping to finish is rejected");
            for(int i=1;i<spec.points().size();i++){p.setPosition(Vec3d.ofBottomCenter(spec.points().get(i)));p.setOnGround(true);ModeMaps.tick(p);}
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"Ordered route completes: "+spec.id());
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").contains(spec.id()),"Personal best saved");
        }
        c.complete();
    }
}

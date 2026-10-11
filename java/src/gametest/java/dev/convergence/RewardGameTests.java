package dev.convergence;

import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

public class RewardGameTests {
    ServerPlayer player(GameTestHelper c,String name) {
        var p=new ModeGameTests().player(c,name);p.setGameMode(GameType.SURVIVAL);return p;
    }
    AchievementRewards isolated(GameTestHelper c) {
        try{return new AchievementRewards(c.getLevel().getServer(),Files.createTempDirectory("infinity-rewards-test-").resolve("rewards.json"));}
        catch(Exception e){throw new RuntimeException(e);}
    }
    void grant(GameTestHelper c,ServerPlayer p,String id) {
        var reward=AchievementRewards.find(id);var entry=c.getLevel().getServer().getAdvancements().get(reward.advancement());
        c.assertTrue(entry!=null,"Reward advancement exists: "+reward.advancement());
        // Direct criterion grants make entitlement tests independent of expensive world exploration.
        for(var criterion:entry.value().criteria().keySet())p.getAdvancements().award(entry,criterion);
    }
    @GameTest public void rewardsRemainLockedEvenWithAnUltraRank(GameTestHelper c) {
        var p=player(c,"rank-no-rewards");var s=isolated(c);var membership=Memberships.get(s.server).account(p.getUUID());membership.earned=Memberships.Tier.ULTRA;
        c.assertFalse(s.power(p,"hacks"),"Ultra rank cannot unlock the power bundle");
        c.assertFalse(s.cosmetic(p,"wither"),"Ultra rank cannot unlock the Wither cosmetic");
        c.assertFalse(p.getAbilities().mayfly,"Locked command cannot grant flight");
        c.assertFalse(p.hasEffect(MobEffects.RESISTANCE),"Locked command cannot grant Resistance");c.succeed();
    }
    @GameTest public void rewardUnlocksAndCosmeticPersistWithoutChangingFreeRank(GameTestHelper c) {
        var p=player(c,"free-earned-reward");var s=isolated(c);grant(c,p,"hacks");grant(c,p,"wither");s.sync(p);
        c.assertTrue(s.unlocked(p,"hacks"),"How Did We Get Here unlocks its own bundle");
        c.assertTrue(s.cosmetic(p,"wither"),"Withering Heights unlocks the Wither aura");
        c.assertValueEqual(Memberships.get(s.server).tier(p.getUUID()),Memberships.Tier.FREE,"Reward achievements do not change rank");
        var reload=new AchievementRewards(s.server,s.file);
        c.assertTrue(reload.unlocked(p,"hacks"),"Permanent UUID reward survives reload");
        c.assertValueEqual(reload.account(p.getUUID()).cosmetic,"wither","Selected cosmetic survives reload");
        c.assertTrue(reload.active.isEmpty(),"Active powers are not saved");c.succeed();
    }
    @GameTest public void bundleGrantsAndRemovesOnlyItsOwnAbilitiesAndEffects(GameTestHelper c) {
        var p=player(c,"bundle-cleanup");var s=isolated(c);grant(c,p,"hacks");
        c.assertTrue(s.power(p,"hacks"),"Unlocked bundle activates in Survival");
        c.assertTrue(p.getAbilities().mayfly,"Bundle allows normal player flight controls");
        c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getAmplifier(),1,"Resistance II is amplifier one");
        c.assertTrue(p.hasEffect(MobEffects.NIGHT_VISION),"Night Vision is active");
        s.power(p,"off");
        c.assertFalse(p.getAbilities().mayfly,"Off removes owned flight permission");
        c.assertFalse(p.getAbilities().flying,"Off cannot leave the player flying");
        c.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION),"Off removes owned Night Vision immediately");
        c.assertFalse(p.hasEffect(MobEffects.RESISTANCE),"Off removes owned Resistance immediately");c.succeed();
    }
    @GameTest public void powersPreserveExistingPotionsAndWeakerHiddenEffects(GameTestHelper c) {
        var p=player(c,"preserve-potions");var s=isolated(c);grant(c,p,"hacks");
        var night=new MobEffectInstance(MobEffects.NIGHT_VISION,1200,0,false,true,true);p.addEffect(night);
        c.assertTrue(s.power(p,"hacks"),"Potion preservation starts with an active reward bundle");
        c.assertTrue(p.getEffect(MobEffects.NIGHT_VISION)==night,"Existing Night Vision instance is untouched");
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,1200,0,false,true,true));
        s.power(p,"off");
        c.assertTrue(p.getEffect(MobEffects.NIGHT_VISION)==night,"Stopping power preserves an existing potion");
        var resistance=p.getEffect(MobEffects.RESISTANCE);
        c.assertTrue(resistance!=null,"Weaker external potion survives behind the reward effect");
        c.assertValueEqual(resistance.getAmplifier(),0,"Cleanup promotes Resistance I instead of deleting it");
        c.assertTrue(resistance.getDuration()>1000,"External potion retains its remaining duration");c.succeed();
    }
    @GameTest public void cleanupPreservesArmorUpgradesWithoutAHiddenReward(GameTestHelper c) {
        var p=player(c,"preserve-armor");var s=isolated(c);grant(c,p,"hacks");
        c.assertTrue(s.power(p,"hacks"),"Armor preservation starts with an active reward bundle");
        Convergence.effect(p,MobEffects.RESISTANCE,2,40);
        s.power(p,"off");var resistance=p.getEffect(MobEffects.RESISTANCE);
        c.assertTrue(resistance!=null,"Armor resistance survives reward cleanup");
        c.assertValueEqual(resistance.getAmplifier(),2,"Stronger armor effect remains");
        var writer=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess());writer.store("effect",MobEffectInstance.CODEC,resistance);
        c.assertFalse(writer.buildResult().getCompoundOrEmpty("effect").contains("hidden_effect"),"Owned Resistance II is removed from the armor effect's hidden chain");c.succeed();
    }
    @GameTest public void modeCaptureAndCreativeReturnCannotRestorePowers(GameTestHelper c) {
        var p=player(c,"reward-mode-isolation");var s=AchievementRewards.get(c.getLevel().getServer());grant(c,p,"hacks");
        c.assertTrue(s.power(p,"hacks"),"Mode isolation starts with an active reward bundle");
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        c.assertValueEqual(s.state(p).power,"off","Mode change clears the active bundle before snapshotting");
        c.assertFalse(p.hasEffect(MobEffects.RESISTANCE),"Survival reward effects do not enter Creative");
        c.assertTrue(p.getAbilities().mayfly,"Creative keeps its native flight");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertValueEqual(p.gameMode(),GameType.SURVIVAL,"Returned to Survival");
        c.assertFalse(p.getAbilities().mayfly,"Creative return cannot restore reward flight");
        c.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION),"Saved Survival snapshot does not restore reward Night Vision");
        GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);
        c.assertFalse(s.power(p,"hacks"),"Hardcore rejects reward activation");c.succeed();
    }
    @GameTest public void windstepUsesCooldownAndMovementPowersRejectCombat(GameTestHelper c) {
        var p=player(c,"windstep-cooldown");var s=isolated(c);grant(c,p,"windstep");grant(c,p,"hacks");
        c.assertTrue(s.power(p,"windstep"),"Take Aim unlocks Windstep");
        c.assertValueEqual(p.getEffect(MobEffects.SPEED).getAmplifier(),1,"Speed II uses amplifier one");
        c.assertValueEqual(p.getEffect(MobEffects.JUMP_BOOST).getAmplifier(),1,"Jump Boost II uses amplifier one");
        s.power(p,"off");c.assertFalse(s.power(p,"windstep"),"Off does not erase the cooldown");
        CommunityServer.get(s.server).combat.put(p.getUUID(),s.server.getTickCount()+200);
        c.assertFalse(s.power(p,"hacks"),"Flight cannot activate to escape a recent fight");c.succeed();
    }
    @GameTest public void deathRespawnAndLeaveClearTemporaryPowers(GameTestHelper c) {
        var p=player(c,"reward-life-cleanup");var s=AchievementRewards.get(c.getLevel().getServer());grant(c,p,"hacks");
        c.assertTrue(s.power(p,"hacks"),"Lifecycle cleanup starts with an active reward bundle");
        p.hurtServer(p.level(),p.damageSources().genericKill(),Float.MAX_VALUE);
        c.assertFalse(p.isAlive(),"Player actually died");
        c.assertFalse(p.getAbilities().mayfly,"Death clears owned flight");
        var next=s.server.getPlayerList().respawn(p,false,Entity.RemovalReason.KILLED);
        c.assertFalse(next.getAbilities().mayfly,"Respawn starts without temporary flight");
        c.assertFalse(next.hasEffect(MobEffects.RESISTANCE),"Respawn starts without the reward buff");
        CommunityServer.get(s.server).combat.remove(next.getUUID());
        c.assertTrue(s.power(next,"hacks"),"Respawned player can activate before leave cleanup");s.leave(next);
        c.assertFalse(next.getAbilities().mayfly,"Leave cleans abilities before player save");
        c.assertFalse(next.hasEffect(MobEffects.NIGHT_VISION),"Leave cleans owned effects");
        c.assertFalse(s.active.containsKey(next.getUUID()),"Leave discards active session state");
        c.assertTrue(s.unlocked(next,"hacks"),"Cleanup preserves permanent reward entitlement");c.succeed();
    }
    @GameTest public void cosmeticParticlesStayLocalAndSelectionCanBeTurnedOff(GameTestHelper c) {
        var p=player(c,"local-cosmetic");var s=isolated(c);grant(c,p,"diamond");s.cosmetic(p,"diamond");
        int nearby=0;for(var viewer:s.server.getPlayerList().getPlayers())if(viewer.level()==p.level()&&viewer.distanceToSqr(p)<=AchievementRewards.PARTICLE_DISTANCE*AchievementRewards.PARTICLE_DISTANCE)nearby++;
        c.assertValueEqual(s.emit(p),Math.min(nearby,AchievementRewards.MAX_VIEWERS),"Only nearby players receive bounded cosmetic particles");
        s.cosmetic(p,"off");c.assertValueEqual(s.emit(p),0,"Off emits no cosmetic particles");c.succeed();
    }
    @GameTest public void extraBundlesSwitchCleanlyWithoutStacking(GameTestHelper c) {
        var p=player(c,"bundle-presets");var s=isolated(c);grant(c,p,"explorer");grant(c,p,"aquatic");grant(c,p,"nether");
        c.assertTrue(s.power(p,"explorer"),"Adventuring Time unlocks the explorer bundle");
        c.assertTrue(p.hasEffect(MobEffects.NIGHT_VISION)&&p.hasEffect(MobEffects.SPEED),"Explorer gives vision and movement");
        c.assertTrue(s.power(p,"aquatic"),"Tactical Fishing unlocks the aquatic bundle");
        c.assertFalse(p.hasEffect(MobEffects.SPEED)||p.hasEffect(MobEffects.NIGHT_VISION),"Switching removes the previous preset");
        c.assertTrue(p.hasEffect(MobEffects.WATER_BREATHING)&&p.hasEffect(MobEffects.DOLPHINS_GRACE),"Aquatic gives both water effects");
        c.assertTrue(s.power(p,"nether"),"Hot Tourist Destinations unlocks the nether bundle");
        c.assertFalse(p.hasEffect(MobEffects.WATER_BREATHING),"Nether cannot stack aquatic effects");
        c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getAmplifier(),0,"Nether supplies Resistance I");
        s.power(p,"off");c.succeed();
    }
    @GameTest public void cooldownAndCrashRecoverySurviveARewardReload(GameTestHelper c) {
        var p=player(c,"reward-crash-recovery");var s=isolated(c);grant(c,p,"windstep");grant(c,p,"hacks");
        c.assertTrue(s.power(p,"windstep"),"Reconnect cooldown starts with an active Windstep");
        s.leave(p);var reload=new AchievementRewards(s.server,s.file);reload.joined(p);
        c.assertFalse(reload.power(p,"windstep"),"Windstep cooldown cannot be bypassed by reconnect or reward reload");
        c.assertTrue(reload.power(p,"hacks"),"Crash recovery starts with an active reward bundle");
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,1200,0,false,true,true));
        // Simulate losing transient ownership while vanilla player data still contains the effect chain.
        var restarted=new AchievementRewards(s.server,s.file);restarted.joined(p);
        c.assertFalse(p.getAbilities().mayfly,"Crash recovery removes saved temporary Survival flight");
        c.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION),"Crash recovery removes a saved reward vision layer");
        c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getAmplifier(),0,"Crash recovery preserves only the external weaker potion");
        c.assertTrue(p.getEffect(MobEffects.RESISTANCE).getDuration()>1000,"Recovered external potion keeps its duration");c.succeed();
    }
    @GameTest public void modeCapturePreservesWeakerPotionButExcludesRewardLayer(GameTestHelper c) {
        var p=player(c,"capture-owned-layer");var s=AchievementRewards.get(c.getLevel().getServer());grant(c,p,"hacks");
        c.assertTrue(s.power(p,"hacks"),"Snapshot potion preservation starts with an active reward bundle");
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,1200,0,false,true,true));
        var snapshot=GameModes.capture(p);p.removeAllEffects();GameModes.restore(p,snapshot);
        c.assertFalse(p.getAbilities().mayfly,"Capture clears reward flight");
        c.assertValueEqual(p.getEffect(MobEffects.RESISTANCE).getAmplifier(),0,"Captured profile contains potion Resistance I, not the reward Resistance II");
        c.assertTrue(p.getEffect(MobEffects.RESISTANCE).getDuration()>1000,"Captured profile preserves potion duration");
        c.assertFalse(p.hasEffect(MobEffects.NIGHT_VISION),"Owned Night Vision is never included in mode snapshots");c.succeed();
    }
    @GameTest public void failedRewardWritesRetryAndCannotLeaveAnUnsavedPowerActive(GameTestHelper c) {
        var p=player(c,"reward-write-failure");var s=isolated(c);
        try {
            Files.createDirectory(s.file);grant(c,p,"hacks");s.sync(p);
            c.assertTrue(s.dirty,"Write failure retains pending entitlement data");
            c.assertTrue(s.nextSaveTick>s.server.getTickCount(),"Failed writes are retried at a bounded interval");
            c.assertFalse(s.power(p,"hacks"),"Power activation refuses unsaved ownership metadata");
            c.assertFalse(p.getAbilities().mayfly||p.hasEffect(MobEffects.RESISTANCE),"Failure cleanup leaves no temporary ability behind");
            Files.delete(s.file);s.nextSaveTick=0;
            c.assertTrue(s.flush(),"Pending data saves once the storage failure is repaired");
            c.assertTrue(new AchievementRewards(s.server,s.file).unlocked(p,"hacks"),"Retry preserves the achievement entitlement");
            c.succeed();
        }catch(Exception error){throw new RuntimeException(error);}
    }
}

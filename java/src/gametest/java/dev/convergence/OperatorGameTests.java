package dev.convergence;

import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public class OperatorGameTests {
    static NameAndId entry(ServerPlayer p) {return new NameAndId(p.getUUID(),p.getScoreboardName());}
    static void level(ServerPlayer p,LevelBasedPermissionSet permissions) {
        p.level().getServer().getPlayerList().op(entry(p),Optional.of(permissions),Optional.of(false));
    }
    static void deop(ServerPlayer p) {p.level().getServer().getPlayerList().deop(entry(p));}
    ServerPlayer operator(GameTestHelper c,String name) {
        var p=new ModeGameTests().player(c,name);level(p,LevelBasedPermissionSet.OWNER);return p;
    }
    @GameTest public void op4UsesEveryRewardWithoutCreatingPermanentUnlocks(GameTestHelper c) {
        var p=operator(c,"op-rewards");var rewards=new RewardGameTests().isolated(c);
        try {
            CommunityServer.get(c.getLevel().getServer()).combat.put(p.getUUID(),c.getLevel().getServer().getTickCount()+200);
            for(var mode:GameModes.Mode.values()) {
                GameModes.switchNow(p,mode,null);
                c.assertTrue(rewards.power(p,"hacks"),"OP4 uses flight in "+mode);
                c.assertTrue(p.getAbilities().mayfly&&p.hasEffect(MobEffects.RESISTANCE),"Power applies in "+mode);
                rewards.power(p,"off");
            }
            c.assertTrue(rewards.cosmetic(p,"wither"),"OP4 uses an unearned cosmetic");
            c.assertTrue(rewards.account(p.getUUID()).unlocked.isEmpty(),"Operator access does not permanently unlock rewards");
            c.assertTrue(rewards.power(p,"windstep"),"OP4 activates Windstep during combat");rewards.power(p,"off");
            c.assertTrue(rewards.power(p,"windstep"),"OP4 can repeat Windstep without waiting for cooldown");rewards.power(p,"off");
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,5));
            c.assertTrue(RewardTrades.purchase(p,"hacks"),"OP command explains free access");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),5,"Operator access never consumes trade items");
            c.assertFalse(RewardTrades.hasPower(p,"hacks"),"No permanent trade receipt is fabricated");
            deop(p);
            c.assertFalse(rewards.power(p,"hacks"),"Deop removes unearned reward access");
            c.assertValueEqual(rewards.emit(p),0,"Unearned operator cosmetic stops after deop");
        } finally {rewards.clearPowers(p,false);deop(p);}
        c.succeed();
    }
    @GameTest public void op4ModeChangesBypassCombatWarmupAndHardcoreElimination(GameTestHelper c) {
        var p=operator(c,"op-modes");
        try {
            var community=CommunityServer.get(c.getLevel().getServer());community.combat.put(p.getUUID(),community.server.getTickCount()+200);
            GameModes.state(p).putBoolean("eliminated",true);GameModes.request(p,GameModes.Mode.HARDCORE,null);
            c.assertValueEqual(GameModes.current(p),GameModes.Mode.HARDCORE,"OP4 can immediately enter eliminated Hardcore");
            c.assertValueEqual(p.gameMode(),GameType.SURVIVAL,"OP4 can play instead of spectating");
            c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()),"OP4 skips countdown");
            GameModes.request(p,GameModes.Mode.HUB,null);p.setGameMode(GameType.CREATIVE);
            p.setPos(p.getX(),70,p.getZ());LobbyServer.tick(p);
            c.assertValueEqual(p.getY(),70.0,"Hub fall rescue does not interfere with operator travel");
        } catch(RuntimeException|Error failure) {deop(p);throw failure;}
        c.runAfterDelay(2,()->{
            try {c.assertValueEqual(p.gameMode(),GameType.CREATIVE,"Server ticks respect operator /gamemode in Hub");}
            finally {deop(p);}
            c.succeed();
        });
    }
    @GameTest public void op4VanillaTeleportsKeepModeInventoriesAndSavedStateCoherent(GameTestHelper c) {
        var p=operator(c,"op-teleports");var server=c.getLevel().getServer();
        try {
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));
            var creative=GameModes.world(server,GameModes.Mode.CREATIVE);
            c.assertTrue(p.teleportTo(creative,0,100,0,Set.of(),0,0,true),"OP4 can use direct cross-mode teleport");
            c.assertValueEqual(GameModes.current(p),GameModes.Mode.CREATIVE,"Teleport updates saved active mode immediately");
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.DIAMOND)),"Operator teleport restores the separate Creative inventory");
            p.getInventory().setItem(0,new ItemStack(Items.NETHERITE_BLOCK,7));
            var target=new TeleportTransition(server.overworld(),new Vec3(0,100,0),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING);
            c.assertTrue(p.teleport(target)!=null,"OP4 can use cross-mode portal transfer");
            c.assertValueEqual(GameModes.current(p),GameModes.Mode.SURVIVAL,"Portal updates saved active mode");
            c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND),"Survival inventory is restored");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),3,"Survival count is preserved");
            p.teleportTo(creative,0,100,0,Set.of(),0,0,true);
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Creative profile survives vanilla teleports");
            c.assertTrue(GameModes.OPERATOR_TRANSFERS.isEmpty(),"Temporary teleport state is cleared");
        } finally {deop(p);}
        c.succeed();
    }
    @GameTest public void op4HomesAndChatBypassPlayerLimits(GameTestHelper c) {
        var p=operator(c,"op-community");var community=CommunityServer.get(c.getLevel().getServer());
        try {
            GameModes.switchNow(p,GameModes.Mode.HUB,null);p.setGameMode(GameType.SPECTATOR);
            for(int i=0;i<5;i++)community.setHome(p,"op"+i);
            c.assertValueEqual(community.homes(p).size(),5,"OP4 has unlimited homes including in Hub spectator mode");
            var destination=CommunityServer.Place.of(p);p.setPos(p.getX()+5,p.getY(),p.getZ());
            community.combat.put(p.getUUID(),community.server.getTickCount()+200);community.cooldowns.put(p.getUUID(),community.server.getTickCount()+200);
            community.queue(p,destination,null);
            c.assertFalse(community.pending.containsKey(p.getUUID()),"OP4 teleport has no warmup");
            c.assertTrue(p.position().distanceToSqr(new Vec3(destination.x(),destination.y(),destination.z()))<.01,"OP4 teleports during combat in spectator Hub");
            community.data.mutedUntil.put(p.getStringUUID(),System.currentTimeMillis()+60000);
            c.assertTrue(community.allowChat(p)&&community.allowChat(p),"OP4 ignores mute and chat cooldown");
        } finally {deop(p);}
        c.succeed();
    }
    @GameTest public void lowerOperatorsRequireAnAdminGrantForFullOverride(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"limited-op");var rewards=new RewardGameTests().isolated(c);
        try {
            level(p,LevelBasedPermissionSet.GAMEMASTER);
            c.assertFalse(Memberships.operator(p),"OP2 remains scoped to vanilla permission level");
            c.assertFalse(rewards.power(p,"hacks"),"OP2 cannot skip reward achievements");deop(p);
            c.assertTrue(Memberships.get(c.getLevel().getServer()).grantAdmin(p.getUUID()),"Trusted Admin grant succeeds");
            c.assertTrue(rewards.cosmetic(p,"wither"),"Admin has the requested cosmetic gameplay bypass");
            c.assertTrue(Memberships.operator(p)&&Memberships.owner(p.createCommandSourceStack()),"Admin grants real OP4 commands");
            c.assertTrue(AgentCompanions.operator(p.createCommandSourceStack()),"Admin can control helpers through their normal review gates");
            c.assertTrue(Memberships.get(c.getLevel().getServer()).revokeAdmin(p.getUUID()),"Admin revocation succeeds");
        } finally {deop(p);}
        c.succeed();
    }
    @GameTest public void operatorFlightIsPreservedAndOperatorCanModerateStaff(GameTestHelper c) {
        var p=operator(c,"op-owner");var staff=new ModeGameTests().player(c,"op-staff-target");var server=c.getLevel().getServer();
        var rewards=new RewardGameTests().isolated(c);
        try {
            p.setGameMode(GameType.SURVIVAL);p.getAbilities().mayfly=true;p.getAbilities().flying=true;
            rewards.joined(p);
            c.assertTrue(p.getAbilities().mayfly&&p.getAbilities().flying,"Reward join cleanup preserves deliberate operator flight");
            c.assertTrue(rewards.power(p,"hacks"),"OP4 can activate bundle over its own flight");rewards.power(p,"off");
            c.assertTrue(p.getAbilities().mayfly&&p.getAbilities().flying,"Reward cleanup preserves preexisting flight");
            Memberships.get(server).account(staff.getUUID()).admin=true;
            Memberships.moderate(p.createCommandSourceStack(),staff,"mute");
            c.assertTrue(CommunityServer.get(server).data.mutedUntil.containsKey(staff.getStringUUID()),"OP4 may moderate scoped staff accounts");
        } finally {rewards.clearPowers(p,false);deop(p);}
        c.succeed();
    }
}

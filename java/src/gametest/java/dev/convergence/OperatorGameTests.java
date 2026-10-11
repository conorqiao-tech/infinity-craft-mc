package dev.convergence;

import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;

public class OperatorGameTests {
    static PlayerConfigEntry entry(ServerPlayerEntity p) {return new PlayerConfigEntry(p.getUuid(),p.getNameForScoreboard());}
    static void level(ServerPlayerEntity p,LeveledPermissionPredicate permissions) {
        p.getEntityWorld().getServer().getPlayerManager().addToOperators(entry(p),Optional.of(permissions),Optional.of(false));
    }
    static void deop(ServerPlayerEntity p) {p.getEntityWorld().getServer().getPlayerManager().removeFromOperators(entry(p));}
    ServerPlayerEntity operator(TestContext c,String name) {
        var p=new ModeGameTests().player(c,name);level(p,LeveledPermissionPredicate.OWNERS);return p;
    }
    @GameTest public void op4UsesEveryRewardWithoutCreatingPermanentUnlocks(TestContext c) {
        var p=operator(c,"op-rewards");var rewards=new RewardGameTests().isolated(c);
        try {
            CommunityServer.get(c.getWorld().getServer()).combat.put(p.getUuid(),c.getWorld().getServer().getTicks()+200);
            for(var mode:GameModes.Mode.values()) {
                GameModes.switchNow(p,mode,null);
                c.assertTrue(rewards.power(p,"hacks"),"OP4 uses flight in "+mode);
                c.assertTrue(p.getAbilities().allowFlying&&p.hasStatusEffect(StatusEffects.RESISTANCE),"Power applies in "+mode);
                rewards.power(p,"off");
            }
            c.assertTrue(rewards.cosmetic(p,"wither"),"OP4 uses an unearned cosmetic");
            c.assertTrue(rewards.account(p.getUuid()).unlocked.isEmpty(),"Operator access does not permanently unlock rewards");
            c.assertTrue(rewards.power(p,"windstep"),"OP4 activates Windstep during combat");rewards.power(p,"off");
            c.assertTrue(rewards.power(p,"windstep"),"OP4 can repeat Windstep without waiting for cooldown");rewards.power(p,"off");
            p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,5));
            c.assertTrue(RewardTrades.purchase(p,"hacks"),"OP command explains free access");
            c.assertEquals(p.getInventory().getStack(0).getCount(),5,"Operator access never consumes trade items");
            c.assertFalse(RewardTrades.hasPower(p,"hacks"),"No permanent trade receipt is fabricated");
            deop(p);
            c.assertFalse(rewards.power(p,"hacks"),"Deop removes unearned reward access");
            c.assertEquals(rewards.emit(p),0,"Unearned operator cosmetic stops after deop");
        } finally {rewards.clearPowers(p,false);deop(p);}
        c.complete();
    }
    @GameTest public void op4ModeChangesBypassCombatWarmupAndHardcoreElimination(TestContext c) {
        var p=operator(c,"op-modes");
        try {
            var community=CommunityServer.get(c.getWorld().getServer());community.combat.put(p.getUuid(),community.server.getTicks()+200);
            GameModes.state(p).putBoolean("eliminated",true);GameModes.request(p,GameModes.Mode.HARDCORE,null);
            c.assertEquals(GameModes.current(p),GameModes.Mode.HARDCORE,"OP4 can immediately enter eliminated Hardcore");
            c.assertEquals(p.getGameMode(),GameMode.SURVIVAL,"OP4 can play instead of spectating");
            c.assertFalse(GameModes.PENDING.containsKey(p.getUuid()),"OP4 skips countdown");
            GameModes.request(p,GameModes.Mode.HUB,null);p.changeGameMode(GameMode.CREATIVE);
            p.setPosition(p.getX(),70,p.getZ());LobbyServer.tick(p);
            c.assertEquals(p.getY(),70.0,"Hub fall rescue does not interfere with operator travel");
        } catch(RuntimeException|Error failure) {deop(p);throw failure;}
        c.waitAndRun(2,()->{
            try {c.assertEquals(p.getGameMode(),GameMode.CREATIVE,"Server ticks respect operator /gamemode in Hub");}
            finally {deop(p);}
            c.complete();
        });
    }
    @GameTest public void op4VanillaTeleportsKeepModeInventoriesAndSavedStateCoherent(TestContext c) {
        var p=operator(c,"op-teleports");var server=c.getWorld().getServer();
        try {
            p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,3));
            var creative=GameModes.world(server,GameModes.Mode.CREATIVE);
            c.assertTrue(p.teleport(creative,0,100,0,Set.of(),0,0,true),"OP4 can use direct cross-mode teleport");
            c.assertEquals(GameModes.current(p),GameModes.Mode.CREATIVE,"Teleport updates saved active mode immediately");
            c.assertTrue(p.getInventory().isEmpty(),"Operator teleport restores target inventory");
            p.getInventory().setStack(0,new ItemStack(Items.NETHERITE_BLOCK,7));
            var target=new TeleportTarget(server.getOverworld(),new Vec3d(0,100,0),Vec3d.ZERO,0,0,TeleportTarget.NO_OP);
            c.assertTrue(p.teleportTo(target)!=null,"OP4 can use cross-mode portal transfer");
            c.assertEquals(GameModes.current(p),GameModes.Mode.SURVIVAL,"Portal updates saved active mode");
            c.assertTrue(p.getInventory().getStack(0).isOf(Items.DIAMOND),"Survival inventory is restored");
            c.assertEquals(p.getInventory().getStack(0).getCount(),3,"Survival count is preserved");
            p.teleport(creative,0,100,0,Set.of(),0,0,true);
            c.assertEquals(p.getInventory().getStack(0).getCount(),7,"Creative profile survives vanilla teleports");
            c.assertTrue(GameModes.OPERATOR_TRANSFERS.isEmpty(),"Temporary teleport state is cleared");
        } finally {deop(p);}
        c.complete();
    }
    @GameTest public void op4HomesAndChatBypassPlayerLimits(TestContext c) {
        var p=operator(c,"op-community");var community=CommunityServer.get(c.getWorld().getServer());
        try {
            GameModes.switchNow(p,GameModes.Mode.HUB,null);p.changeGameMode(GameMode.SPECTATOR);
            for(int i=0;i<5;i++)community.setHome(p,"op"+i);
            c.assertEquals(community.homes(p).size(),5,"OP4 has unlimited homes including in Hub spectator mode");
            var destination=CommunityServer.Place.of(p);p.setPosition(p.getX()+5,p.getY(),p.getZ());
            community.combat.put(p.getUuid(),community.server.getTicks()+200);community.cooldowns.put(p.getUuid(),community.server.getTicks()+200);
            community.queue(p,destination,null);
            c.assertFalse(community.pending.containsKey(p.getUuid()),"OP4 teleport has no warmup");
            c.assertTrue(p.getEntityPos().squaredDistanceTo(new Vec3d(destination.x(),destination.y(),destination.z()))<.01,"OP4 teleports during combat in spectator Hub");
            community.data.mutedUntil.put(p.getUuidAsString(),System.currentTimeMillis()+60000);
            c.assertTrue(community.allowChat(p)&&community.allowChat(p),"OP4 ignores mute and chat cooldown");
        } finally {deop(p);}
        c.complete();
    }
    @GameTest public void lowerOperatorsAndAdminCodesDoNotGainFullOverride(TestContext c) {
        var p=new ModeGameTests().player(c,"limited-op");var rewards=new RewardGameTests().isolated(c);
        try {
            level(p,LeveledPermissionPredicate.GAMEMASTERS);
            c.assertFalse(Memberships.operator(p),"OP2 remains scoped to vanilla permission level");
            c.assertFalse(rewards.power(p,"hacks"),"OP2 cannot skip reward achievements");deop(p);
            Memberships.get(c.getWorld().getServer()).account(p.getUuid()).admin=true;
            c.assertFalse(rewards.cosmetic(p,"wither"),"Admin code cannot skip rewards");
        } finally {deop(p);}
        c.complete();
    }
    @GameTest public void operatorFlightIsPreservedAndOperatorCanModerateStaff(TestContext c) {
        var p=operator(c,"op-owner");var staff=new ModeGameTests().player(c,"op-staff-target");var server=c.getWorld().getServer();
        var rewards=new RewardGameTests().isolated(c);
        try {
            p.changeGameMode(GameMode.SURVIVAL);p.getAbilities().allowFlying=true;p.getAbilities().flying=true;
            rewards.joined(p);
            c.assertTrue(p.getAbilities().allowFlying&&p.getAbilities().flying,"Reward join cleanup preserves deliberate operator flight");
            c.assertTrue(rewards.power(p,"hacks"),"OP4 can activate bundle over its own flight");rewards.power(p,"off");
            c.assertTrue(p.getAbilities().allowFlying&&p.getAbilities().flying,"Reward cleanup preserves preexisting flight");
            Memberships.get(server).account(staff.getUuid()).admin=true;
            Memberships.moderate(p.getCommandSource(),staff,"mute");
            c.assertTrue(CommunityServer.get(server).data.mutedUntil.containsKey(staff.getUuidAsString()),"OP4 may moderate scoped staff accounts");
        } finally {rewards.clearPowers(p,false);deop(p);}
        c.complete();
    }
}

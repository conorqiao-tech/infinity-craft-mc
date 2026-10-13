package dev.convergence;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.gametest.framework.GameTestHelper;

public class SpawnSafetyGameTests {
    private static final ServerboundClientCommandPacket RESPAWN=new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
    private static final class DisconnectGate extends io.netty.channel.ChannelOutboundHandlerAdapter {
        io.netty.channel.ChannelPromise pending;
        net.minecraft.network.protocol.common.ClientboundDisconnectPacket packet;
        @Override public void write(io.netty.channel.ChannelHandlerContext context,Object message,io.netty.channel.ChannelPromise promise) {
            if(message instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket disconnect) {packet=disconnect;pending=promise;}
            else context.write(message,promise);
        }
        void complete() {if(pending!=null){var promise=pending;pending=null;promise.setSuccess();}}
    }

    private void awaitLogin(GameTestHelper c,PrepareSpawnTask task,int remaining,Runnable check,Runnable cleanup) {
        boolean finished=false;
        try {
            if(task.tick()) {finished=true;check.run();c.succeed();}
            else if(remaining==0) {finished=true;c.assertTrue(false,"Native login preparation finishes within its bounded test window");}
            else c.runAfterDelay(1,()->awaitLogin(c,task,remaining-1,check,cleanup));
        } catch(RuntimeException|Error failure) {finished=true;throw failure;}
        finally {if(finished)cleanup.run();}
    }

    @GameTest public void survivalPreflightLeavesSpawnResolutionToNativeRespawn(GameTestHelper c) {
        var server=c.getLevel().getServer();
        var profile=new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"survival-preflight");
        var data=CommonListenerCookie.createInitial(profile,false);var lookups=new int[1];
        // This native player is not registered or connected; the preflight must
        // not need a connection, a saved destination, or a native spawn lookup.
        var p=new ServerPlayer(server,c.getLevel(),profile,data.clientInformation()) {
            @Override public TeleportTransition findRespawnPositionAndUseSpawnBlock(boolean consumeAnchor,TeleportTransition.PostTeleportTransition callback) {
                lookups[0]++;
                return super.findRespawnPositionAndUseSpawnBlock(consumeAnchor,callback);
            }
        };
        p.setHealth(0);
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.SURVIVAL,"The probe uses the ordinary Survival mode");
        c.assertTrue(GameModes.preflightRespawn(p),"Survival proceeds to the existing native respawn");
        c.assertValueEqual(lookups[0],0,"Survival preflight never repeats the native spawn finder");
        c.succeed();
    }

    @GameTest(maxTicks=200) public void unavailableDeathRespawnKeepsOldPlayerAndBrokenBedStateUntilHostRepair(GameTestHelper c) throws Exception {
        var network=new Connection(PacketFlow.SERVERBOUND);var gate=new DisconnectGate();var channel=new io.netty.channel.embedded.EmbeddedChannel(network,gate);
        var p=new ModeGameTests().player(c,"respawn-unavailable",network);var server=p.level().getServer();
        GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.HARDCORE,"The respawn fixture successfully entered Hardcore");
        c.assertValueEqual(GameModes.of(p.level()),GameModes.Mode.HARDCORE,"The respawn fixture is in the actual Hardcore dimension");
        var world=p.level();var border=world.getWorldBorder();double x=border.getCenterX(),z=border.getCenterZ(),size=border.getSize();
        var broken=p.blockPosition().above(10);var block=world.getBlockState(broken);
        p.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(world.dimension(),broken,0,0),false),false);
        p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD,2));
        p.setHealth(0);GameModes.death(p);
        var before=GameModes.state(p).copy();var respawn=p.getRespawnConfig();var position=p.position();
        PlayerPersistence.save(p);var file=GameModes.savedPlayerFile(server,p.getUUID());var saved=Files.readAllBytes(file);
        var task=new PrepareSpawnTask(server,p.nameAndId());
        Runnable cleanup=()->{
            task.close();border.setCenter(x,z);border.setSize(size);world.setBlock(broken,block,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            p.inventoryMenu.setCarried(ItemStack.EMPTY);
            var current=server.getPlayerList().getPlayer(p.getUUID());if(current!=null)server.getPlayerList().remove(current);
            gate.complete();channel.finishAndReleaseAll();
        };
        boolean scheduled=false;
        try {
            world.setBlock(broken,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            border.setCenter(10000,10000);border.setSize(1);
            c.assertValueEqual(p.gameMode(),GameType.SURVIVAL,"Eliminated Hardcore has not yet received AFTER_RESPAWN spectator abilities");
            p.connection.handleClientCommand(RESPAWN);
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==p,"Destination refusal occurs before vanilla replaces the old player");
            c.assertTrue(p.connection.player==p,"The network handler still owns the same player");
            c.assertValueEqual(p.position(),position,"Refusal cannot move the dead player to another realm");
            c.assertValueEqual(GameModes.state(p),before,"Mode, elimination and saved profiles are unchanged");
            c.assertValueEqual(p.getRespawnConfig(),respawn,"Broken-bed preflight does not clear native respawn configuration");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Refusal does not restore or erase inventory");
            c.assertValueEqual(p.inventoryMenu.getCarried().getCount(),2,"Refusal preserves the carried cursor");
            c.assertTrue(Arrays.equals(Files.readAllBytes(file),saved),"Preflight refusal does not rewrite player data");
            c.assertTrue(gate.packet!=null&&gate.packet.reason().getString().contains("repair the destination"),"The client receives a clear host-repair reason rather than remaining in respawn loading");
            c.assertFalse(channel.config().isAutoRead(),"The closing connection stops reading more respawn requests");
            border.setCenter(x,z);border.setSize(size);
            // Native disconnect cleanup saves the same old player; reconnect loads that saved realm and state.
            gate.complete();channel.runPendingTasks();network.handleDisconnection();
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==null,"Completing the native disconnect then removes the original player normally");
            var data=CommonListenerCookie.createInitial(p.getGameProfile(),false);
            // In 26.3 the configuration task loads native NBT and selects its saved realm before placeNewPlayer.
            task.start(packet->{});
            c.runAfterDelay(1,()->awaitLogin(c,task,180,()->{
                var connection=new Connection(PacketFlow.SERVERBOUND);var rejoinChannel=new io.netty.channel.embedded.EmbeddedChannel(connection);
                try {
                    var rejoined=task.spawnPlayer(connection,data);
                    rejoined.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                    c.assertValueEqual(GameModes.current(rejoined),GameModes.Mode.HARDCORE,"Native reconnect loads the original mode rather than substituting Survival");
                    c.assertValueEqual(GameModes.of(rejoined.level()),GameModes.Mode.HARDCORE,"Native reconnect selects the saved Hardcore dimension even with zero health");
                    c.assertValueEqual(GameModes.state(rejoined),before,"Native reconnect preserves elimination and inactive mode profiles");
                    c.assertValueEqual(rejoined.getInventory().getItem(0).getCount(),7,"Native reconnect loads the saved inventory");
                    c.assertValueEqual(rejoined.getHealth(),0f,"The dead-player request still requires actual native respawn");
                    rejoined.connection.handleClientCommand(RESPAWN);
                    var fresh=server.getPlayerList().getPlayer(p.getUUID());
                    c.assertTrue(fresh!=null&&fresh!=rejoined,"A repaired destination permits actual native respawn");
                    c.assertValueEqual(GameModes.of(fresh.level()),GameModes.Mode.HARDCORE,"Normal respawn stays in its original realm");
                    c.assertValueEqual(fresh.gameMode(),GameType.SPECTATOR,"AFTER_RESPAWN sets eliminated Hardcore spectator before another game tick");
                } finally {rejoinChannel.finishAndReleaseAll();}
            },cleanup));
            scheduled=true;
        } finally {if(!scheduled)cleanup.run();}
    }

    @GameTest public void respawnPreflightKeepsAnchorChargeAndNativeDeathConsumesExactlyOne(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"anchor-preflight");var server=p.level().getServer();var nether=server.getLevel(Level.NETHER);
        var anchor=new BlockPos(c.absolutePos(new BlockPos(2,2,2)).getX(),80,c.absolutePos(new BlockPos(2,2,2)).getZ());
        var original=new LinkedHashMap<BlockPos,BlockState>();
        try {
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=2;dy++) {
                var at=anchor.offset(dx,dy,dz);original.put(at,nether.getBlockState(at));
                nether.setBlock(at,(dy==-1?Blocks.STONE:Blocks.AIR).defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            }
            nether.setBlock(anchor,Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE,2),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            c.assertTrue(p.teleportTo(nether,anchor.getX()+1.5,80,anchor.getZ()+.5,Set.of(),0,0,true),"Native Survival player enters the Nether");
            p.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(Level.NETHER,anchor,0,0),false),false);
            var target=p.findRespawnPositionAndUseSpawnBlock(false,TeleportTransition.DO_NOTHING);
            c.assertTrue(target.newLevel()==nether&&!target.missingRespawnBlock(),"The real charged anchor is a valid native respawn target");
            c.assertTrue(GameModes.preflightRespawn(p),"The real native respawn finder can be used as a passive preflight");
            c.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE),2,"Passive preflight never consumes an anchor charge");
            p.connection.handleClientCommand(RESPAWN);
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==p,"An alive player's packet keeps the native early return");
            c.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE),2,"An alive request never consumes an anchor charge");
            p.setHealth(0);p.connection.handleClientCommand(RESPAWN);
            var fresh=server.getPlayerList().getPlayer(p.getUUID());
            c.assertTrue(fresh!=null&&fresh!=p&&fresh.level()==nether,"The real death packet still performs native anchor respawn");
            c.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE),1,"Preflight plus actual death consumes exactly one charge");
            fresh.wonGame=true;fresh.connection.handleClientCommand(RESPAWN);
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())!=fresh,"The native credits branch still replaces the player");
            c.assertValueEqual(nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE),1,"Credits return keeps its native non-consuming anchor behavior");
        } finally {
            var current=server.getPlayerList().getPlayer(p.getUUID());if(current!=null)server.getPlayerList().remove(current);
            for(var entry:original.entrySet())nether.setBlock(entry.getKey(),entry.getValue(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        }
        c.succeed();
    }

    @GameTest public void switchSafetyValidationDoesNotAddBlockedCourseOrHubRespawnExceptions(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"course-respawn");var server=p.level().getServer();
        try {
            for(var mode:new GameModes.Mode[]{GameModes.Mode.MINIGAMES,GameModes.Mode.HUB}) {
                GameModes.switchNow(p,mode,mode==GameModes.Mode.MINIGAMES?"parkour":null);
                var place=mode==GameModes.Mode.HUB?LobbyServer.place(server,"main"):GameModes.mapPlace(server,mode,"parkour");
                var at=BlockPos.containing(place.x(),place.y(),place.z());var world=p.level();var original=world.getBlockState(at);
                try {
                    world.setBlock(at,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
                    var target=p.findRespawnPositionAndUseSpawnBlock(false,TeleportTransition.DO_NOTHING);
                    c.assertTrue(target.newLevel()==world,"Existing "+mode+" respawn keeps its own realm");
                    c.assertValueEqual(target.position(),new net.minecraft.world.phys.Vec3(place.x(),place.y(),place.z()),"Switch preflight does not change the existing fixed respawn contract");
                    c.assertTrue(world.getBlockState(at).is(Blocks.STONE),"Resolving respawn never overwrites a player build");
                } finally {world.setBlock(at,original,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);}
            }
        } finally {server.getPlayerList().remove(p);ModeMaps.RUNS.remove(p.getUUID());}
        c.succeed();
    }
}

package dev.convergence;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Native player membership and state lifetimes around the ordinary Convergence tick. */
public class ConvergenceTickStateGameTests {
    @SuppressWarnings("unchecked")
    private static Map<UUID,Convergence.State> states() {
        try {
            var field=Convergence.class.getDeclaredField("STATES");field.setAccessible(true);
            return (Map<UUID,Convergence.State>)field.get(null);
        } catch(ReflectiveOperationException failure) {
            throw new AssertionError("Cannot observe the real transient player states",failure);
        }
    }

    private static final class Joined implements AutoCloseable {
        final Connection connection=new Connection(PacketFlow.SERVERBOUND);
        final EmbeddedChannel channel=new EmbeddedChannel(connection);
        final ServerPlayer player;
        Joined(GameTestHelper c,String name) { this(c,new GameProfile(UUID.randomUUID(),name)); }
        Joined(GameTestHelper c,GameProfile profile) {
            var server=c.getLevel().getServer();var cookie=CommonListenerCookie.createInitial(profile,false);
            player=new ServerPlayer(server,c.getLevel(),profile,cookie.clientInformation());
            server.getPlayerList().placeNewPlayer(connection,player,cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var feet=c.absolutePos(new BlockPos(2,2,2));
            c.getLevel().setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());
            player.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);
            player.setNoGravity(true);player.setGameMode(GameType.SURVIVAL);
        }
        void disconnect() {
            channel.close();channel.runPendingTasks();connection.handleDisconnection();
        }
        @Override public void close() {
            var server=player.level().getServer();
            player.containerMenu.setCarried(ItemStack.EMPTY);player.closeContainer();
            GameModes.PENDING.remove(player.getUUID());GameModes.TRANSITIONS.remove(player.getUUID());
            if(server.getPlayerList().getPlayer(player.getUUID())==player)server.getPlayerList().remove(player);
            channel.finishAndReleaseAll();connection.handleDisconnection();states().remove(player.getUUID());
        }
    }

    @GameTest public void joinedLivingPlayersRetainTheirStateAndNormalTickUpdates(GameTestHelper c) {
        try(var joined=new Joined(c,"tick-living")) {
            var p=joined.player;var server=p.level().getServer();
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==p,"The fixture is a real joined player in the native UUID map");
            var state=Convergence.state(p);state.swordMode=2;state.cooldown.put("tick-life",Convergence.clock+100);
            p.setOnGround(false);state.peak=p.getY()+4;state.y=p.getY();state.age=p.tickCount;
            Convergence.tick(server);
            c.assertTrue(states().get(p.getUUID())==state,"A living joined player's original state object survives pruning");
            c.assertValueEqual(state.swordMode,2,"Tick cleanup preserves the selected sword power");
            c.assertValueEqual(state.cooldown.get("tick-life"),Long.valueOf(Convergence.clock+100),"Tick cleanup preserves live cooldowns");
            c.assertValueEqual(state.drop,4.0,"Normal fall-distance tracking still updates the retained state");
            c.assertValueEqual(state.y,p.getY(),"Current height is recorded");
            c.assertValueEqual(state.age,p.tickCount,"Current native player age is recorded");
        }
        c.succeed();
    }

    @GameTest public void nativeDeathPrunesStateWhileTheDeadPlayerIsStillJoined(GameTestHelper c) {
        try(var dead=new Joined(c,"tick-dead");var living=new Joined(c,"tick-survivor")) {
            var p=dead.player;var server=p.level().getServer();
            var old=Convergence.state(p);old.swordMode=2;old.cooldown.put("tick-life",Convergence.clock+100);
            var survivor=Convergence.state(living.player);survivor.swordMode=1;
            p.hasChangedDimension();p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            p.hurtServer(p.level(),p.damageSources().genericKill(),Float.MAX_VALUE);
            c.assertFalse(p.isAlive(),"Native damage caused actual death");
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==p,"The dead player remains joined until disconnect or respawn");
            Convergence.tick(server);
            c.assertFalse(states().containsKey(p.getUUID()),"The existing death branch removes the complete transient state");
            c.assertTrue(states().get(living.player.getUUID())==survivor,"Pruning a dead player preserves another living player");
            Convergence.tick(server);
            c.assertFalse(states().containsKey(p.getUUID()),"Further ticks cannot recreate a dead player's state");
        }
        c.succeed();
    }

    @GameTest public void nativeDisconnectPrunesStateAndSameUuidRejoinStartsFreshAfterCleanup(GameTestHelper c) {
        try(var original=new Joined(c,"tick-reconnect")) {
            var p=original.player;var server=p.level().getServer();var old=Convergence.state(p);
            old.swordMode=2;old.cooldown.put("tick-life",Convergence.clock+100);old.target=UUID.randomUUID();
            original.disconnect();
            c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==null,"Native channel disconnect removes the UUID map entry");
            c.assertFalse(server.getPlayerList().getPlayers().contains(p),"Native disconnect also removes the player from iteration");
            Convergence.tick(server);
            c.assertFalse(states().containsKey(p.getUUID()),"The next normal tick removes disconnected state");
            // Even a retained reference cannot keep an unjoined player's newly created state alive.
            Convergence.state(p);Convergence.tick(server);
            c.assertFalse(states().containsKey(p.getUUID()),"State recreated from a disconnected object is pruned again");
            try(var rejoined=new Joined(c,p.getGameProfile())) {
                var fresh=rejoined.player;
                c.assertTrue(fresh!=p&&server.getPlayerList().getPlayer(p.getUUID())==fresh,"The same UUID belongs to a newly joined native player");
                Convergence.tick(server);var current=states().get(fresh.getUUID());
                c.assertTrue(current!=null&&current!=old,"Rejoining after cleanup receives a new state object");
                c.assertValueEqual(current.swordMode,0,"The previous sword selection cannot survive removed RAM state");
                c.assertTrue(current.cooldown.isEmpty()&&current.target==null,"Old cooldowns and combo targets are absent");
            }
        }
        c.succeed();
    }

    @GameTest public void sameUuidRejoinBeforeCleanupKeepsExistingUuidStateAndResetsAgeDependentCombo(GameTestHelper c) {
        try(var original=new Joined(c,"tick-replace")) {
            var p=original.player;var server=p.level().getServer();var old=Convergence.state(p);
            old.swordMode=2;old.cooldown.put("tick-life",Convergence.clock+100);
            old.age=40;old.armed=Convergence.clock+100;old.target=UUID.randomUUID();old.peak=p.getY()+20;
            original.disconnect();
            try(var rejoined=new Joined(c,p.getGameProfile())) {
                var fresh=rejoined.player;
                c.assertTrue(server.getPlayerList().getPlayer(p.getUUID())==fresh,"Native UUID membership points to the replacement player");
                Convergence.tick(server);
                c.assertTrue(states().get(fresh.getUUID())==old,"As before, rejoining before any pruning tick retains UUID-keyed state");
                c.assertValueEqual(old.swordMode,2,"The existing UUID selection semantics remain unchanged");
                c.assertTrue(old.cooldown.containsKey("tick-life"),"The existing UUID cooldown semantics remain unchanged");
                c.assertTrue(old.armed==0&&old.target==null,"The unchanged age-rewind branch cancels stale combo work");
                c.assertValueEqual(old.peak,fresh.getY(),"Age rewind resets fall tracking for the replacement player");
            }
        }
        c.succeed();
    }
}

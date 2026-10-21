package dev.convergence;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Connected native play sessions and saved logins, confined to disposable GameTest worlds. */
abstract class NativeSessionFixture implements AutoCloseable {
    private static boolean running;
    final GameTestHelper c;
    final MinecraftServer server;
    final List<Joined> players = new ArrayList<>();
    private PrepareSpawnTask login;
    private boolean closed;

    NativeSessionFixture(GameTestHelper c) { this.c=c;server=c.getLevel().getServer(); }
    abstract void start();
    void cleanup() {}

    /** Scenario labels are diagnostics, not real account names. Native player-info
     * packets accept at most sixteen characters; retain already legal names and
     * derive a stable ASCII name for long or descriptive labels. UUID generation
     * and the complete saved GameProfile used by reconnect remain unchanged.
     */
    static String nativeProfileName(String label) {
        java.util.Objects.requireNonNull(label,"Native fixture label");
        if(label.isBlank())throw new IllegalArgumentException("Native fixture label must not be blank");
        if(label.matches("[A-Za-z0-9_]{1,16}"))return label;
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256")
                .digest(label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "T"+java.util.HexFormat.of().formatHex(digest).substring(0,15);
        } catch(java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java must provide SHA-256 for native fixture names",impossible);
        }
    }

    static final class Joined implements AutoCloseable {
        final MinecraftServer server;
        final NativePlayConnection wire;
        ServerPlayer player;
        private PositionMoveRotation clientPosition;
        private boolean closed;
        Joined(GameTestHelper c,String name,int x) {
            server=c.getLevel().getServer();wire=new NativePlayConnection(c.getLevel().registryAccess());
            try {
                var profile=new GameProfile(UUID.randomUUID(),nativeProfileName(name));var cookie=CommonListenerCookie.createInitial(profile,false);
                player=new ServerPlayer(server,c.getLevel(),profile,cookie.clientInformation());clientPosition=PositionMoveRotation.of(player);
                server.getPlayerList().placeNewPlayer(wire.connection,player,cookie);configure();
                var feet=c.absolutePos(new BlockPos(x,2,2));
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)
                    c.getLevel().setBlockAndUpdate(feet.offset(dx,dy,dz),(dy==-1?Blocks.STONE:Blocks.AIR).defaultBlockState());
                player.connection.teleport(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);drain();
                server.getConnection().getConnections().add(wire.connection);
            } catch(RuntimeException|Error failure){close();throw failure;}
        }
        Joined(GameTestHelper c,PrepareSpawnTask task,GameProfile profile) {
            server=c.getLevel().getServer();wire=new NativePlayConnection(c.getLevel().registryAccess());
            try {
                player=task.spawnPlayer(wire.connection,CommonListenerCookie.createInitial(profile,false));clientPosition=PositionMoveRotation.of(player);
                configure();drain();server.getConnection().getConnections().add(wire.connection);
            } catch(RuntimeException|Error failure){close();throw failure;}
        }
        private void configure() {
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());var info=ClientInformation.createDefault();
            player.updateOptions(new ClientInformation(info.language(),2,info.chatVisibility(),info.chatColors(),info.modelCustomisation(),
                info.mainHand(),info.textFilteringEnabled(),info.allowsListing(),info.particleStatus()));
            player.setNoGravity(true);player.setPermanentlyInvulnerable(true);player.getFoodData().setFoodLevel(6);player.getFoodData().setSaturation(0);
        }
        void drain() {if(!closed)for(var packet:wire.drain())acknowledge(packet);}
        private void acknowledge(Packet<?> packet) {
            if(packet instanceof ClientboundBundlePacket bundle)bundle.subPackets().forEach(this::acknowledge);
            else if(packet instanceof ClientboundRespawnPacket)player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            else if(packet instanceof ClientboundPlayerPositionPacket position) {
                clientPosition=PositionMoveRotation.calculateAbsolute(clientPosition,position.change(),position.relatives());var at=clientPosition.position();
                player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id(),at.x(),at.y(),at.z(),clientPosition.yRot(),clientPosition.xRot()));
            } else if(packet instanceof ClientboundChunkBatchFinishedPacket) {
                player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64f));player.level().getChunkSource().move(player);
            } else if(packet instanceof ClientboundKeepAlivePacket keepAlive)
                player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
        }
        @Override public void close() {
            if(closed)return;closed=true;server.getConnection().getConnections().remove(wire.connection);
            try {if(player!=null) {
                OperatorGameTests.deop(player);player.containerMenu.setCarried(ItemStack.EMPTY);player.closeContainer();
                wire.connection.disconnect(Component.literal("Session lifecycle fixture finished"));wire.connection.handleDisconnection();
            }} finally {wire.channel.finishAndReleaseAll();}
        }
    }

    Joined join(String name,int x) {var joined=new Joined(c,name,x);players.add(joined);return joined;}
    void reconnect(Joined old,Consumer<Joined> continuation) {
        c.assertTrue(login==null,"Only one native saved login is prepared at a time");
        var profile=old.player.getGameProfile();old.close();
        c.assertTrue(server.getPlayerList().getPlayer(profile.id())==null,"Actual native disconnect removed the original player");
        login=new PrepareSpawnTask(server,old.player.nameAndId());login.start(packet->{});
        pollLogin(profile,continuation,180);
    }
    private void pollLogin(GameProfile profile,Consumer<Joined> continuation,int remaining) {
        guard(()->{
            c.assertTrue(remaining>0,"Native reconnect preparation remains bounded");
            if(!login.tick()){later(1,()->pollLogin(profile,continuation,remaining-1));return;}
            var task=login;var replacement=new Joined(c,task,profile);players.add(replacement);task.close();login=null;
            c.assertTrue(replacement.player.getUUID().equals(profile.id()),"Saved login retains the same UUID");
            continuation.accept(replacement);
        });
    }
    void guard(Runnable action) {
        if(closed)return;
        try {for(var player:players)player.drain();action.run();}
        catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    void later(int ticks,Runnable action){c.runAfterDelay(ticks,()->guard(action));}
    void succeed(){close();c.succeed();}
    @Override public void close() {
        if(closed)return;closed=true;Throwable problem=null;
        var actions=new ArrayList<Runnable>();actions.add(this::cleanup);
        if(login!=null)actions.add(login::close);
        for(var player:players)actions.add(player::close);
        for(var action:actions)try{action.run();}catch(RuntimeException|Error failure){if(problem==null)problem=failure;else problem.addSuppressed(failure);}
        running=false;
        if(problem instanceof RuntimeException failure)throw failure;
        if(problem instanceof Error failure)throw failure;
    }
    static void startSerialized(GameTestHelper c,Supplier<? extends NativeSessionFixture> factory) {startSerialized(c,factory,700);}
    private static void startSerialized(GameTestHelper c,Supplier<? extends NativeSessionFixture> factory,int remaining) {
        if(running){c.assertTrue(remaining>0,"Native session fixture queue remains bounded");c.runAfterDelay(1,()->startSerialized(c,factory,remaining-1));return;}
        running=true;
        try {var fixture=factory.get();fixture.later(500,()->{c.assertTrue(false,"Native session scenario exceeded its deadline");});fixture.guard(fixture::start);}
        catch(RuntimeException|Error failure){running=false;throw failure;}
    }
}

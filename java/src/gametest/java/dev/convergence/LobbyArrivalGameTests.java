package dev.convergence;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** One phased case in an exclusive paced runtime: real connections, unloads and FULL publication. */
public class LobbyArrivalGameTests {
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    private static Object field(Object object,String name) {
        try {
            var type=object instanceof Class<?> cls?cls:object.getClass();
            var field=type.getDeclaredField(name);field.setAccessible(true);
            return field.get(object instanceof Class<?>?null:object);
        } catch(ReflectiveOperationException failure){throw new IllegalStateException("Pinned lobby fixture field unavailable: "+name,failure);}
    }
    private static void flatten(Packet<?> packet,List<Packet<?>> output) {
        if(packet instanceof ClientboundBundlePacket bundle)bundle.subPackets().forEach(child->flatten(child,output));
        else output.add(packet);
    }

    private static final class Joined implements AutoCloseable {
        final MinecraftServer server;
        final NativePlayConnection wire;
        final Connection connection;
        final EmbeddedChannel channel;
        final List<String> messages=new ArrayList<>();
        ServerPlayer player;
        PositionMoveRotation clientPosition;
        boolean closed;
        Joined(GameTestHelper c,String name) {
            server=c.getLevel().getServer();wire=new NativePlayConnection(c.getLevel().registryAccess());
            connection=wire.connection;channel=wire.channel;
            var profile=new GameProfile(UUID.randomUUID(),NativeSessionFixture.nativeProfileName(name));var cookie=CommonListenerCookie.createInitial(profile,false);
            player=new ServerPlayer(server,c.getLevel(),profile,cookie.clientInformation());clientPosition=PositionMoveRotation.of(player);
            try {server.getPlayerList().placeNewPlayer(connection,player,cookie);configure();
                var feet=c.absolutePos(new BlockPos(2,2,2));
                c.getLevel().setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
                c.getLevel().setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());
                c.getLevel().setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());
                player.connection.teleport(feet.getX()+.5,feet.getY(),feet.getZ()+.5,0,0);
                drain();server.getConnection().getConnections().add(connection);}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        Joined(GameTestHelper c,PrepareSpawnTask task,GameProfile profile) {
            server=c.getLevel().getServer();wire=new NativePlayConnection(c.getLevel().registryAccess());
            connection=wire.connection;channel=wire.channel;
            try {player=task.spawnPlayer(connection,CommonListenerCookie.createInitial(profile,false));clientPosition=PositionMoveRotation.of(player);
                configure();drain();server.getConnection().getConnections().add(connection);}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        void configure() {
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var info=ClientInformation.createDefault();
            player.updateOptions(new ClientInformation(info.language(),2,info.chatVisibility(),info.chatColors(),info.modelCustomisation(),
                info.mainHand(),info.textFilteringEnabled(),info.allowsListing(),info.particleStatus()));
            player.setNoGravity(true);player.setPermanentlyInvulnerable(true);
        }
        void drain() {
            if(closed)return;
            var packets=new ArrayList<Packet<?>>();for(var packet:wire.drain())flatten(packet,packets);
            for(var packet:packets) {
                if(packet instanceof ClientboundRespawnPacket)player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                else if(packet instanceof ClientboundPlayerPositionPacket position) {
                    clientPosition=PositionMoveRotation.calculateAbsolute(clientPosition,position.change(),position.relatives());var at=clientPosition.position();
                    player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id(),at.x(),at.y(),at.z(),clientPosition.yRot(),clientPosition.xRot()));
                } else if(packet instanceof ClientboundChunkBatchFinishedPacket) {
                    player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64f));player.level().getChunkSource().move(player);
                } else if(packet instanceof ClientboundSystemChatPacket chat)messages.add(chat.content().getString());
            }
        }
        long messagesContaining(String text){drain();return messages.stream().filter(message->message.contains(text)).count();}
        @Override public void close() {
            if(closed)return;closed=true;server.getConnection().getConnections().remove(connection);
            if(player!=null){OperatorGameTests.deop(player);player.containerMenu.setCarried(ItemStack.EMPTY);player.closeContainer();
                connection.disconnect(Component.literal("Lobby arrival fixture finished"));connection.handleDisconnection();}
            channel.finishAndReleaseAll();
        }
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper c;
        final MinecraftServer server;
        final ServerLevel hub;
        final TicketType type;
        final TicketStorage storage;
        final Set<ChunkPos> watched=new LinkedHashSet<>();
        final Map<BlockPos,BlockState> terrain=new LinkedHashMap<>();
        final List<Object> retired=new ArrayList<>();
        final DestinationChunksProbe.Sample probe;
        Joined owner,observer;
        PrepareSpawnTask login;
        CompoundTag before;
        Object menu;
        byte[] ownerFile,observerFile;
        int requestedTicks,lastRetry,attempts;
        boolean closed;
        Scenario(GameTestHelper c) {
            this.c=c;server=c.getLevel().getServer();hub=GameModes.world(server,GameModes.Mode.HUB);
            type=(TicketType)field(DestinationChunks.class,"TICKET");storage=(TicketStorage)field(hub.getChunkSource(),"ticketStorage");
            for(var lobby:LobbyServer.LOBBIES.values())watched.addAll(halo(lobby.id()));
            // Fixture preparation uses real completed native chunks before observation starts.
            for(var pos:watched)hub.getChunk(pos.x(),pos.z());
            owner=new Joined(c,"lobby-owner");observer=new Joined(c,"lobby-observer");
            GameModes.switchNow(owner.player,GameModes.Mode.HUB,"main");owner.drain();
            c.assertValueEqual(GameModes.current(owner.player),GameModes.Mode.HUB,"The joined native owner really enters Hub before the same-mode measurement");
            probe=DestinationChunksProbe.begin(server,hub.getChunkSource(),type,watched);
        }
        Set<ChunkPos> halo(String id) {
            var center=LobbyServer.LOBBIES.get(id).center();int x=center.getX()>>4,z=center.getZ()>>4;
            // All real halls have centered block-zero coordinates; their native epsilon/margin halo is these four chunks.
            return Set.of(new ChunkPos(x-1,z-1),new ChunkPos(x-1,z),new ChunkPos(x,z-1),new ChunkPos(x,z));
        }
        Object job(){return ((Map<?,?>)field(LobbyServer.class,"ARRIVALS")).get(owner.player.getUUID());}
        Object retry(){return ((Map<?,?>)field(LobbyServer.class,"RESCUE_RETRIES")).get(owner.player.getUUID());}
        <T>T measure(Supplier<T> action){return probe.measure(action);}
        void measured(Runnable action){measure(()->{action.run();return null;});}
        void guard(Runnable action) {
            if(closed)return;
            try {owner.drain();observer.drain();action.run();}
            catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void later(int ticks,Runnable action){c.runAfterDelay(ticks,()->guard(action));}
        void await(String name,int remaining,BooleanSupplier ready,Runnable waiting,Runnable done) {
            if(ready.getAsBoolean()){done.run();return;}
            c.assertTrue(remaining>0,name+" completes in the bounded actual native tick window");waiting.run();
            later(1,()->await(name,remaining-1,ready,waiting,done));
        }
        void command(String command) {
            measured(()->{try{c.assertValueEqual(server.getCommands().getDispatcher().execute(command,owner.player.createCommandSourceStack()),1,"The native command is accepted: "+command);}
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){throw new IllegalStateException(failure);}});
        }
        byte[] file(ServerPlayer p){try{return Files.readAllBytes(GameModes.savedPlayerFile(server,p.getUUID()));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
        void snapshot() {
            var p=owner.player;p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.getInventory().setItem(8,ServerMenu.navigator(p));
            var profiles=GameModes.state(p).getCompoundOrEmpty("profiles").copy();var saved=new CompoundTag();saved.putString("proof","inactive profile stays intact");profiles.put("CREATIVE",saved);
            GameModes.state(p).put("profiles",profiles);ServerMenu.open(p);p.containerMenu.setCarried(new ItemStack(Items.EMERALD,3));
            PlayerPersistence.save(p);PlayerPersistence.save(observer.player);before=GameModes.state(p).copy();menu=p.containerMenu;
            ownerFile=file(p);observerFile=file(observer.player);GameModes.state(observer.player).putBoolean("unsaved_observer",true);
            owner.drain();owner.messages.clear();observer.drain();
        }
        void unchanged(boolean waiting) {
            var p=owner.player;var expected=before.copy();if(!waiting)expected.putString("last_lobby","adventure");
            c.assertValueEqual(GameModes.current(p),GameModes.Mode.HUB,"Same-mode preparation keeps the actual Hub realm active");
            c.assertTrue(p.level()==hub&&p.containerMenu==menu,"Preparation and commit do not close or replace the native menu");
            c.assertValueEqual(GameModes.state(p),expected,"Lobby travel never captures, removes or restores profiles");
            c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND)&&p.getInventory().getItem(0).getCount()==7,"Native main inventory remains active");
            c.assertTrue(p.containerMenu.getCarried().is(Items.EMERALD)&&p.containerMenu.getCarried().getCount()==3,"The menu cursor is neither returned early nor discarded");
            c.assertTrue(Arrays.equals(ownerFile,file(p))&&Arrays.equals(observerFile,file(observer.player)),"Lobby preparation and arrival never write participant or observer saves");
        }
        void ticketsReleased(String id) {
            var pools=(Map<?,?>)field(DestinationChunks.class,"POOLS");var pool=pools.get(server);
            Map<?,?> entries=Map.of();if(pool!=null){var worlds=(Map<?,?>)field(pool,"worlds");var found=worlds.get(hub);if(found instanceof Map<?,?> map)entries=map;}
            for(var position:halo(id)) {
                var tickets=storage.getTickets(ChunkPos.pack(position.x(),position.z()));
                c.assertTrue(tickets==null||tickets.stream().noneMatch(ticket->ticket.getType().equals(type)),"Retired arrival releases its real native ticket: "+position);
                var entry=entries.get(position);c.assertTrue(entry==null||(int)field(entry,"references")==0,"Canceled queued work owns no stale manager reference");
            }
        }
        void retired(Object job,String id) {
            c.assertTrue(job!=null,"The tested cancellation had a genuine pending arrival");retired.add(job);
            c.assertValueEqual(((FixedDestination)field(job,"destination")).status(),FixedDestination.Status.FAILED,"The old exact selector is closed");ticketsReleased(id);
        }
        void moveAway() {
            c.assertTrue(owner.player.teleportTo(hub,8000.5,160,8000.5,Set.of(),0,0,true),"Native same-Hub teleport moves the fixture beyond every hall's simulation tickets");
            owner.drain();hub.getChunkSource().move(owner.player);
        }
        void cold(Runnable done) {
            moveAway();await("Physical Hub hall unloading",240,()->watched.stream().allMatch(pos->hub.getChunkSource().getChunkNow(pos.x(),pos.z())==null),()->{},done);
        }
        void start() {
            cold(()->{
                snapshot();requestedTicks=owner.player.tickCount;command("lobby creative");var first=job();
                c.assertTrue(first!=null,"A physically cold native command installs a pending exact arrival");var started=field(first,"started");
                command("lobby creative");c.assertTrue(job()==first&&field(first,"started").equals(started),"Same-id repeat retains the exact job and deadline");
                var sign=LobbyServer.MAIN_SIGNS.entrySet().stream().filter(entry->entry.getValue().equals("adventure")).findFirst().orElseThrow();
                c.assertValueEqual(measure(()->LobbyServer.useSign(owner.player,sign.getKey())),InteractionResult.SUCCESS,"The real registered Hall sign accepts its same-mode visit");
                var explicit=job();c.assertTrue(explicit!=null&&explicit!=first&&field(explicit,"id").equals("adventure"),"A newer explicit Hall sign replaces the older command");retired(first,"creative");
                owner.player.setPos(owner.player.getX(),70,owner.player.getZ());measured(()->LobbyServer.tick(owner.player));
                c.assertTrue(job()==explicit&&!(boolean)field(explicit,"rescue"),"Automatic rescue never overwrites an explicit pending Hall");unchanged(true);
                await("Real cold Hall FULL arrival",240,()->job()==null,()->{
                    unchanged(true);owner.player.setPos(owner.player.getX(),owner.player.getY()-.01,owner.player.getZ());
                },()->{
                    c.assertTrue(owner.player.tickCount>requestedTicks,"The joined native player continues ticking during technical preparation");
                    c.assertFalse(CommunityServer.moved(LobbyServer.place(server,"adventure"),owner.player),"Actual FULL readiness commits the exact requested Adventure Hall");
                    unchanged(false);c.assertValueEqual(owner.messagesContaining("Welcome to Adventure Lobby."),1L,"The delivered native Hall label appears once");
                    c.assertValueEqual(owner.messagesContaining("Welcome to Creative Lobby."),0L,"The replaced request cannot welcome or teleport late");
                    c.assertValueEqual(probe.blockingFutureCalls,0,"Measured commands, signs and polls never call native getChunkFuture");ticketsReleased("adventure");
                    later(3,this::blockedRescue);
                });
            });
        }
        void change(BlockPos pos,BlockState state){terrain.putIfAbsent(pos,hub.getBlockState(pos));hub.setBlock(pos,state,FLAGS);}
        void restore(){for(var entry:terrain.entrySet())hub.setBlock(entry.getKey(),entry.getValue(),FLAGS);terrain.clear();}
        void blockedRescue() {
            c.assertValueEqual(owner.messagesContaining("Welcome to Adventure Lobby."),1L,"Registered later ticks do not repeat the completed arrival");
            change(LobbyServer.LOBBIES.get("adventure").center().above(),Blocks.STONE.defaultBlockState());owner.messages.clear();
            owner.player.setPos(owner.player.getX(),70,owner.player.getZ());measured(()->LobbyServer.tick(owner.player));
            c.assertTrue(job()==null&&retry()!=null,"An actually blocked auto-rescue records a technical retry instead of mutating terrain");
            lastRetry=(int)field(retry(),"next");attempts=1;blockedTicks(45);
        }
        void blockedTicks(int remaining) {
            var retry=retry();c.assertTrue(retry!=null,"The stable blocked fall episode retains its retry record");int next=(int)field(retry,"next");
            if(next!=lastRetry){c.assertTrue(next-lastRetry>=20,"Actual retry deadlines advance by at least20 native game ticks");lastRetry=next;attempts++;}
            c.assertTrue(attempts<=3,"A45-tick blocked episode does not rescan every tick");
            c.assertValueEqual(owner.messagesContaining("That lobby platform could not be reached."),1L,"Successful native chat writes report a stable rescue problem once per episode");
            c.assertTrue(hub.getBlockState(LobbyServer.LOBBIES.get("adventure").center().above()).is(Blocks.STONE),"Rescue never repairs or overwrites a player's obstruction");
            if(remaining>0){later(1,()->blockedTicks(remaining-1));return;}
            restore();await("Host-repaired automatic rescue",30,()->owner.player.getY()>=74,()->{},()->{
                c.assertFalse(CommunityServer.moved(LobbyServer.place(server,"adventure"),owner.player),"The20-tick retry eventually uses the genuinely repaired Hall");
                c.assertValueEqual(owner.messagesContaining("Welcome to Adventure Lobby."),1L,"The repaired episode delivers one successful label");
                cold(this::operatorAndRecovery);
            });
        }
        void operatorAndRecovery() {
            OperatorGameTests.level(owner.player,LevelBasedPermissionSet.OWNER);command("lobby");var privileged=job();
            c.assertTrue(privileged!=null&&(boolean)field(privileged,"bypass"),"Cold OP access still owns a genuine technical waiting job");
            OperatorGameTests.deop(owner.player);measured(()->LobbyServer.tick(owner.player));c.assertTrue(job()==null,"Revoked operator permission cancels its technical job");retired(privileged,"main");
            GameModes.state(owner.player).putString("last_lobby","main");owner.player.setPos(owner.player.getX(),70,owner.player.getZ());
            measured(()->LobbyServer.tick(owner.player));var rescued=job();c.assertTrue(rescued!=null&&(boolean)field(rescued,"rescue"),"Physically cold automatic rescue really starts pending");
            owner.player.setPos(owner.player.getX(),160,owner.player.getZ());measured(()->LobbyServer.tick(owner.player));c.assertTrue(job()==null,"Independent recovery cancels a cold rescue before completion");retired(rescued,"main");
            owner.player.setPos(owner.player.getX(),70,owner.player.getZ());measured(()->LobbyServer.tick(owner.player));var promoted=job();
            c.assertTrue(promoted!=null&&(boolean)field(promoted,"rescue"),"A new fall starts a real pending rescue");
            OperatorGameTests.level(owner.player,LevelBasedPermissionSet.OWNER);measured(()->LobbyServer.tick(owner.player));
            c.assertTrue(job()==null,"Granting OP while a cold automatic rescue waits preserves operator travel");retired(promoted,"main");
            owner.player.setPos(owner.player.getX(),160,owner.player.getZ());
            // This is real fixture loading after cancellation, not a substituted future or fabricated READY.
            for(var pos:halo("main"))hub.getChunk(pos.x(),pos.z());change(LobbyServer.LOBBIES.get("main").center().above(),Blocks.STONE.defaultBlockState());
            c.assertFalse(CommunityServer.safe(hub,LobbyServer.place(server,"main")),"The hot privileged destination is truly blocked under ordinary native safety");
            command("lobby");c.assertTrue(job()==null,"An already FULL unsafe OP Hall remains immediate");
            c.assertFalse(CommunityServer.moved(LobbyServer.place(server,"main"),owner.player),"OP keeps the existing unsafe-platform bypass");restore();OperatorGameTests.deop(owner.player);
            later(3,()->{c.assertTrue(job()==null,"Real completed terrain cannot resurrect any retired rescue");cold(this::lifecycle);});
        }
        void lifecycle() {
            command("lobby");var modeJob=job();GameModes.switchNow(owner.player,GameModes.Mode.SURVIVAL,null);
            c.assertValueEqual(GameModes.current(owner.player),GameModes.Mode.SURVIVAL,"The native fixture performs a real cross-mode/dimension transition");
            c.assertTrue(job()==null,"A new cross-mode transition closes same-mode technical preparation");retired(modeJob,"main");
            // Warm fixture-only Main Hub terrain to return through an actual valid immediate mode selector.
            for(var pos:halo("main"))hub.getChunk(pos.x(),pos.z());GameModes.switchNow(owner.player,GameModes.Mode.HUB,"main");owner.drain();
            c.assertValueEqual(GameModes.current(owner.player),GameModes.Mode.HUB,"The fixture returns through actual native safe terrain");
            cold(()->{
                command("lobby");var deathJob=job();owner.player.setHealth(0);GameModes.death(owner.player);
                c.assertFalse(owner.player.isAlive(),"The joined native player has genuinely reached dead health");
                c.assertTrue(job()==null,"The explicit death lifecycle action closes its technical arrival");retired(deathJob,"main");owner.player.setHealth(20);
                command("lobby");var disconnected=job();var old=owner;var profile=old.player.getGameProfile();var id=old.player.getUUID();old.close();
                c.assertTrue(server.getPlayerList().getPlayer(id)==null,"Actual native disconnect removes the old session");retired(disconnected,"main");
                login=new PrepareSpawnTask(server,old.player.nameAndId());login.start(packet->{});
                await("Native same-UUID login",180,()->login.tick(),()->{},()->{
                    owner=new Joined(c,login,profile);c.assertTrue(owner.player!=old.player&&server.getPlayerList().getPlayer(id)==owner.player,"Native login creates the actual replacement session");
                    command("lobby");var replacement=job();c.assertTrue(replacement!=null,"The same UUID may submit its own fresh cold Hall request");
                    measured(()->LobbyServer.arrive(old.player,"creative"));
                    ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(old.player.connection,server);
                    c.assertTrue(job()==replacement,"Stale request/disconnect actions never replace or cancel the current exact-session job");
                    LobbyServer.cancelArrival(owner.player);retired(replacement,"main");
                    for(var pos:watched)hub.getChunk(pos.x(),pos.z());later(3,()->{
                        c.assertTrue(job()==null,"Late actual FULL publication cannot commit retired lifecycle jobs");
                        c.assertTrue(owner.player.getX()>7000&&owner.player.getZ()>7000,"The replacement stays at its genuine saved remote Hub location");
                        c.assertValueEqual(probe.blockingFutureCalls,0,"Measured lifecycle cancellation and stale calls do not wait through native getChunkFuture");
                        close();c.succeed();
                    });
                });
            });
        }
        @Override public void close() {
            if(closed)return;closed=true;Throwable first=null;
            for(var cleanup:List.<Runnable>of(()->{if(owner!=null)owner.close();},()->{if(observer!=null)observer.close();},
                ()->{if(login!=null)login.close();},this::restore,()->DestinationChunksProbe.end(probe)))try{cleanup.run();}
                catch(RuntimeException|Error failure){if(first==null)first=failure;else first.addSuppressed(failure);}
            if(first instanceof RuntimeException runtime)throw runtime;if(first instanceof Error error)throw error;
        }
    }

    @GameTest(maxTicks=1800)
    public void nativeSameModeHallArrivalKeepsStateAndBoundsTechnicalWaiting(GameTestHelper c) {
        var scenario=new Scenario(c);scenario.guard(scenario::start);
    }
}

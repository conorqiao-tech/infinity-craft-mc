package dev.convergence;

import com.mojang.authlib.GameProfile;
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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

/** Exclusive paced cases. Fixture scopes serialize because the native load probe is singular. */
public class CommunityTeleportGameTests {
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
    private static boolean running;

    private static Object field(Object object,String name) {
        try {
            var type=object instanceof Class<?> cls?cls:object.getClass();
            var field=type.getDeclaredField(name);field.setAccessible(true);
            return field.get(object instanceof Class<?>?null:object);
        } catch(ReflectiveOperationException failure){throw new IllegalStateException("Pinned community fixture field unavailable: "+name,failure);}
    }
    private static int count(ServerPlayer player,Item item) {
        int result=0;for(int slot=0;slot<player.getInventory().getContainerSize();slot++) {
            var stack=player.getInventory().getItem(slot);if(stack.is(item))result+=stack.getCount();
        }
        return result;
    }
    private static Set<ChunkPos> halo(CommunityServer.Place place) {
        int minX=(net.minecraft.util.Mth.floor(place.x()-.3-1e-7)-1)>>4,maxX=(net.minecraft.util.Mth.floor(place.x()+.3+1e-7)+1)>>4;
        int minZ=(net.minecraft.util.Mth.floor(place.z()-.3-1e-7)-1)>>4,maxZ=(net.minecraft.util.Mth.floor(place.z()+.3+1e-7)+1)>>4;
        var result=new LinkedHashSet<ChunkPos>();for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)result.add(new ChunkPos(x,z));
        return result;
    }

    private static final class Joined implements AutoCloseable {
        final MinecraftServer server;
        final NativePlayConnection wire;
        ServerPlayer player;
        PositionMoveRotation clientPosition;
        boolean closed;
        Joined(GameTestHelper c,String name,int x) {
            server=c.getLevel().getServer();wire=new NativePlayConnection(c.getLevel().registryAccess());
            var profile=new GameProfile(UUID.randomUUID(),NativeSessionFixture.nativeProfileName(name));var cookie=CommonListenerCookie.createInitial(profile,false);
            try {
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
            try {player=task.spawnPlayer(wire.connection,CommonListenerCookie.createInitial(profile,false));clientPosition=PositionMoveRotation.of(player);
                configure();drain();server.getConnection().getConnections().add(wire.connection);}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        void configure() {
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());var info=ClientInformation.createDefault();
            player.updateOptions(new ClientInformation(info.language(),2,info.chatVisibility(),info.chatColors(),info.modelCustomisation(),
                info.mainHand(),info.textFilteringEnabled(),info.allowsListing(),info.particleStatus()));
            player.setNoGravity(true);player.setPermanentlyInvulnerable(true);player.getFoodData().setFoodLevel(6);player.getFoodData().setSaturation(0);
        }
        void drain() {if(!closed)for(var packet:wire.drain())acknowledge(packet);}
        void acknowledge(Packet<?> packet) {
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
            try {if(player!=null){OperatorGameTests.deop(player);player.containerMenu.setCarried(ItemStack.EMPTY);player.closeContainer();
                wire.connection.disconnect(Component.literal("Community teleport fixture finished"));wire.connection.handleDisconnection();}}
            finally {wire.channel.finishAndReleaseAll();}
        }
    }

    private abstract static class Scenario implements AutoCloseable {
        final GameTestHelper c;
        final MinecraftServer server;
        final CommunityServer community;
        final Map<ServerLevel,Map<BlockPos,BlockState>> terrain=new LinkedHashMap<>();
        final CommunityServer.Place originalSpawn;
        final LevelData.RespawnData originalNativeSpawn;
        final TicketType type;
        Joined owner,observer;
        PrepareSpawnTask login;
        DestinationChunksProbe.Sample probe;
        ServerLevel watchedWorld;
        Set<ChunkPos> watched=Set.of();
        List<DestinationChunks.Lease> fixtureLeases=new ArrayList<>();
        CompoundTag before;
        Object menu;
        byte[] ownerFile,observerFile;
        CommunityServer.Place origin;
        int requested,playerTicks;
        boolean closed;
        Scenario(GameTestHelper c) {
            this.c=c;server=c.getLevel().getServer();community=CommunityServer.get(server);originalSpawn=community.data.spawn;
            originalNativeSpawn=server.getRespawnData();type=(TicketType)field(DestinationChunks.class,"TICKET");
            try {owner=new Joined(c,"community-owner",2);observer=new Joined(c,"comm-observer",5);}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        abstract void start();
        void guard(Runnable action) {
            if(closed)return;
            try {owner.drain();observer.drain();action.run();}
            catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void later(int ticks,Runnable action){c.runAfterDelay(ticks,()->guard(action));}
        void await(String name,int remaining,BooleanSupplier ready,Runnable waiting,Runnable done) {
            if(ready.getAsBoolean()){done.run();return;}
            c.assertTrue(remaining>0,name+" completes in the bounded actual native tick window");waiting.run();later(1,()->await(name,remaining-1,ready,waiting,done));
        }
        <T>T measure(Supplier<T> action){return probe==null?action.get():probe.measure(action);}
        void measured(Runnable action){measure(()->{action.run();return null;});}
        void command(String command){command(command,1);}
        void command(String command,int expected) {
            measured(()->{try{c.assertValueEqual(server.getCommands().getDispatcher().execute(command,owner.player.createCommandSourceStack()),expected,"The native command handles: "+command);}
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){throw new IllegalStateException(failure);}});
        }
        byte[] file(ServerPlayer player){try{return Files.readAllBytes(GameModes.savedPlayerFile(server,player.getUUID()));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}
        Object job(){return community.pending.get(owner.player.getUUID());}
        void watch(ServerLevel world,Set<ChunkPos> chunks) {
            if(probe!=null)DestinationChunksProbe.end(probe);watchedWorld=world;watched=chunks;
            probe=DestinationChunksProbe.begin(server,world.getChunkSource(),type,chunks);
        }
        void releaseFixture(){for(var lease:fixtureLeases)lease.close();fixtureLeases.clear();}
        void prepareRemote(ServerLevel world,CommunityServer.Place place,Runnable done) {
            watch(world,halo(place));
            for(var pos:watched)fixtureLeases.add(DestinationChunks.acquire(world,pos));
            await("Real remote fixture FULL publication",240,()->fixtureLeases.stream().allMatch(DestinationChunks.Lease::ready),()->{},()->{
                var feet=BlockPos.containing(place.x(),place.y(),place.z());
                for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=1;dy++)
                    change(world,feet.offset(dx,dy,dz),(dy==-1?Blocks.STONE:Blocks.AIR).defaultBlockState());
                releaseFixture();cold(done);
            });
        }
        void change(ServerLevel world,BlockPos pos,BlockState state){terrain.computeIfAbsent(world,key->new LinkedHashMap<>()).putIfAbsent(pos,world.getBlockState(pos));world.setBlock(pos,state,FLAGS);}
        void cold(Runnable done) {
            await("Physical command destination unload",240,()->watched.stream().allMatch(pos->watchedWorld.getChunkSource().getChunkNow(pos.x(),pos.z())==null),()->{},done);
        }
        void snapshot() {
            var p=owner.player;
            // Establish the existing lazy Creative menu/tick state before freezing the full baseline.
            if(GameModes.current(p)==GameModes.Mode.CREATIVE)CreativeStudio.settings(p);
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.getInventory().setItem(8,ServerMenu.navigator(p));
            var profiles=GameModes.state(p).getCompoundOrEmpty("profiles").copy();var untouched=new CompoundTag();untouched.putString("proof","unrelated inactive mode data");
            profiles.put("HARDCORE",untouched);GameModes.state(p).put("profiles",profiles);ServerMenu.open(p);p.containerMenu.setCarried(new ItemStack(Items.EMERALD,3));
            PlayerPersistence.save(p);PlayerPersistence.save(observer.player);before=GameModes.state(p).copy();menu=p.containerMenu;
            ownerFile=file(p);observerFile=file(observer.player);GameModes.state(observer.player).putBoolean("unsaved_observer",true);
            origin=CommunityServer.Place.of(p);requested=server.getTickCount();playerTicks=p.tickCount;owner.drain();observer.drain();
        }
        void unchanged(String reason) {
            var p=owner.player;c.assertValueEqual(GameModes.state(p),before,reason+" keeps all native mode/profile state");
            c.assertTrue(p.containerMenu==menu,reason+" keeps the native menu open");
            c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND)&&p.getInventory().getItem(0).getCount()==7,reason+" preserves the current inventory");
            c.assertTrue(p.containerMenu.getCarried().is(Items.EMERALD)&&p.containerMenu.getCarried().getCount()==3,reason+" preserves the cursor stack");
            c.assertTrue(Arrays.equals(ownerFile,file(p))&&Arrays.equals(observerFile,file(observer.player)),reason+" does not save participants or an unrelated observer");
        }
        void released() {
            var storage=(TicketStorage)field(watchedWorld.getChunkSource(),"ticketStorage");
            for(var pos:watched){var tickets=storage.getTickets(ChunkPos.pack(pos.x(),pos.z()));
                c.assertTrue(tickets==null||tickets.stream().noneMatch(ticket->ticket.getType().equals(type)),"Retired command releases its owned native ticket: "+pos);}
        }
        void warm(ServerLevel world,CommunityServer.Place place) {
            // A previous visit does not retain its old collision halo after a dimension change.
            // Load real fixture terrain outside the measured command, then prove publication.
            for(var pos:halo(place)) {
                var full=world.getChunk(pos.x(),pos.z());
                c.assertTrue(world.getChunkSource().getChunkNow(pos.x(),pos.z())==full,
                    "The separate warm command fixture has an actually published FULL collision chunk: "+pos);
            }
        }
        void returnToOrigin(Runnable done) {
            c.assertTrue(owner.player.teleportTo(server.overworld(),origin.x(),origin.y(),origin.z(),Set.of(),origin.yaw(),origin.pitch(),true),"Fixture returns through a real native teleport");
            owner.drain();owner.player.level().getChunkSource().move(owner.player);community.cooldowns.remove(owner.player.getUUID());community.combat.remove(owner.player.getUUID());cold(done);
        }
        void exact(CommunityServer.Place place,String label) {
            c.assertFalse(CommunityServer.moved(place,owner.player),label+" uses the frozen exact coordinates/dimension");
            c.assertValueEqual(owner.player.getYRot(),place.yaw(),label+" preserves native yaw");c.assertValueEqual(owner.player.getXRot(),place.pitch(),label+" preserves native pitch");
        }
        void succeed(){close();c.succeed();}
        @Override public void close() {
            if(closed)return;closed=true;Throwable first=null;
            for(var cleanup:List.<Runnable>of(()->{if(owner!=null){community.cancelPending(owner.player);community.cancelRequests(owner.player);owner.close();}},
                ()->{if(observer!=null){community.cancelPending(observer.player);community.cancelRequests(observer.player);observer.close();}},
                ()->{if(login!=null)login.close();},this::releaseFixture,
                ()->{for(var world:terrain.entrySet())for(var entry:world.getValue().entrySet())world.getKey().setBlock(entry.getKey(),entry.getValue(),FLAGS);terrain.clear();},
                ()->{community.data.spawn=originalSpawn;server.setRespawnData(originalNativeSpawn);},
                ()->{if(probe!=null)DestinationChunksProbe.end(probe);}))try{cleanup.run();}
                catch(RuntimeException|Error failure){if(first==null)first=failure;else first.addSuppressed(failure);}
            running=false;if(first instanceof RuntimeException runtime)throw runtime;if(first instanceof Error error)throw error;
        }
    }

    private static final class ExactScenario extends Scenario {
        final CommunityServer.Place target;
        int route;
        ExactScenario(GameTestHelper c) {
            super(c);var world=server.overworld();var feet=new ChunkPos(24000,24000).getBlockAt(0,90,0);
            target=new CommunityServer.Place(world.dimension().identifier().toString(),feet.getX()+.5,feet.getY(),feet.getZ()+.5,117.25f,-13.5f);
        }
        @Override void start(){prepareRemote(server.overworld(),target,this::route);}
        void route() {
            snapshot();community.homes(owner.player).put("native_home",target);community.data.warps.put("native_warp",target);
            c.assertTrue(watched.stream().allMatch(pos->watchedWorld.getChunkSource().getChunkNow(pos.x(),pos.z())==null),"Each command route starts physically cold");
            if(route==0)command("home native_home");
            else if(route==1)command("warp native_warp");
            else if(route==2){community.data.spawn=target;command("spawn");}
            else {community.data.spawn=null;server.setRespawnData(LevelData.RespawnData.of(server.overworld().dimension(),BlockPos.containing(target.x(),target.y(),target.z()),target.yaw(),target.pitch()));command("spawn");}
            c.assertTrue(job()!=null,"Cold native command creates real technical preparation");unchanged("Cold command acceptance");
            await("Cold exact command commit",240,()->job()==null,()->{
                measured(community::tick);if(job()!=null){unchanged("Actual waiting tick");c.assertValueEqual(CommunityServer.Place.of(owner.player),origin,"Ordinary player cannot move before commit");}
            },()->{
                c.assertTrue(server.getTickCount()-requested>=60,"Each real ordinary command retains its full60-tick warmup");
                c.assertTrue(owner.player.tickCount-playerTicks>=60,"The actual connected native player keeps ticking through cold preparation");
                exact(target,"Home/warp/configured or native Survival spawn");unchanged("Same-mode exact commit");
                c.assertTrue(community.cooldowns.getOrDefault(owner.player.getUUID(),0)>server.getTickCount(),"Only successful ordinary travel installs cooldown");released();
                c.assertValueEqual(probe.blockingFutureCalls,0,"Measured native command acceptance/polling never calls getChunkFuture");
                if(++route<4)returnToOrigin(this::route);else returnToOrigin(this::cancellation);
            });
        }
        void cancellation() {
            snapshot();command("home native_home");c.assertTrue(job()!=null,"Movement case has a real pending home");
            later(2,()->{
                var p=owner.player;p.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(p.getX()+.5,p.getY(),p.getZ(),true,false));
                c.assertTrue(CommunityServer.moved(origin,p),"A real inbound movement packet crosses the cancellation threshold");
                later(2,()->{c.assertTrue(job()==null,"Registered native ticks cancel the moved home request");unchanged("Movement cancellation");released();
                    community.cooldowns.remove(p.getUUID());community.combat.remove(p.getUUID());snapshot();command("home native_home");
                    c.assertTrue(job()!=null,"Damage case starts a separate real pending home");p.hasChangedDimension();
                    c.assertTrue(p.hurtServer(p.level(),p.damageSources().genericKill(),1f),"Positive actual native damage reaches the joined ordinary player");
                    c.assertTrue(job()==null,"Actual damage event cancels technical preparation immediately");unchanged("Damage cancellation");released();
                    var canceledAt=CommunityServer.Place.of(p);
                    later(65,()->{c.assertTrue(job()==null,"Later actual terrain completion cannot recreate a canceled request");
                        c.assertValueEqual(CommunityServer.Place.of(p),canceledAt,"Late actual terrain completion cannot teleport the canceled participant");
                        community.combat.remove(p.getUUID());consent();});
                });
            });
        }
        void consent() {
            var host=observer.player;var visitor=owner.player;community.request(visitor,host);
            c.assertTrue(job()==null,"A real player visit request never moves or acquires a destination before consent");
            var frozen=CommunityServer.Place.of(host);snapshot();community.answer(host,true);c.assertTrue(job()!=null,"Real host acceptance creates the visitor's native countdown");
            host.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(host.getX()+.5,host.getY(),host.getZ(),true,false));
            c.assertTrue(CommunityServer.moved(frozen,host),"The host actually moves after accepting");
            await("Consented frozen player visit",100,()->job()==null,()->{measured(community::tick);if(job()!=null)unchanged("Player visit wait");},()->{
                exact(frozen,"Consented player visit");c.assertTrue(server.getTickCount()-requested>=60,"Consent retains the actual ordinary countdown despite host movement");
                unchanged("Accepted same-mode player visit");community.cooldowns.remove(visitor.getUUID());
                community.request(visitor,host);community.answer(host,true);c.assertTrue(job()!=null,"A second accepted visit is genuinely pending before host disconnect");
                var old=observer;var profile=host.getGameProfile();old.close();c.assertTrue(job()==null,"Actual host disconnect retires its accepted visitor request");
                login=new PrepareSpawnTask(server,host.nameAndId());login.start(packet->{});
                await("Native host reconnect",180,()->login.tick(),()->{},()->{
                    observer=new Joined(c,login,profile);c.assertTrue(observer.player!=host,"Native saved login produces the actual replacement host session");
                    community.request(visitor,observer.player);community.answer(observer.player,true);var replacement=job();
                    c.assertTrue(replacement!=null,"Fresh host consent creates a fresh exact-session visitor job");
                    ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(host.connection,server);
                    c.assertTrue(job()==replacement,"A delayed old host disconnect cannot cancel fresh consent");
                    // Warm fixture-only Creative destination: mode lifecycle, not cold preparation, is under test here.
                    var creative=GameModes.defaultPlace(server,GameModes.Mode.CREATIVE);for(var pos:halo(creative))GameModes.world(server,GameModes.Mode.CREATIVE).getChunk(pos.x(),pos.z());
                    GameModes.switchNow(observer.player,GameModes.Mode.CREATIVE,null);observer.drain();
                    c.assertValueEqual(GameModes.current(observer.player),GameModes.Mode.CREATIVE,"The host really changes realm and native mode");
                    measured(community::tick);c.assertTrue(job()==null,"Accepted consent expires when its exact host changes realm/mode");
                    var after=CommunityServer.Place.of(visitor);later(65,()->{c.assertValueEqual(CommunityServer.Place.of(visitor),after,"Late native completion cannot revive revoked host consent");succeed();});
                });
            });
        }
        @Override public void close(){try{if(owner!=null)community.homes(owner.player).remove("native_home");community.data.warps.remove("native_warp");}finally{super.close();}}
    }

    private static final class DefaultAndOperatorScenario extends Scenario {
        final ServerLevel creative;
        final CommunityServer.Place remote;
        final net.minecraft.world.level.border.WorldBorder border;
        final double borderX,borderZ,borderSize;
        final Map<BlockPos,BlockState> grid=new LinkedHashMap<>();
        CommunityServer.Place originalCreativeDefault;
        boolean cacheChanged;
        DefaultAndOperatorScenario(GameTestHelper c) {
            super(c);creative=GameModes.world(server,GameModes.Mode.CREATIVE);var feet=new ChunkPos(26000,26000).getBlockAt(0,120,0);
            remote=new CommunityServer.Place(creative.dimension().identifier().toString(),feet.getX()+.5,feet.getY(),feet.getZ()+.5,37,-8);
            border=creative.getWorldBorder();borderX=border.getCenterX();borderZ=border.getCenterZ();borderSize=border.getSize();
        }
        @Override void start(){prepareRemote(creative,remote,this::operator);}
        void operator() {
            snapshot();OperatorGameTests.level(owner.player,LevelBasedPermissionSet.OWNER);measured(()->community.queue(owner.player,remote,null));
            c.assertTrue(job()!=null,"Cold OP route retains actual technical preparation instead of synchronously loading");unchanged("Cold OP acceptance");
            OperatorGameTests.deop(owner.player);measured(community::tick);c.assertTrue(job()==null,"Revoking OP before cold commit cancels the privileged route");unchanged("Cold OP revocation");released();
            OperatorGameTests.level(owner.player,LevelBasedPermissionSet.OWNER);measured(()->community.queue(owner.player,remote,null));
            c.assertTrue(job()!=null,"Fresh exact privileged permission may create its own cold request");
            await("Actual cold OP cross-world FULL commit",240,()->job()==null,()->{measured(community::tick);if(job()!=null)unchanged("Cold OP technical wait");},()->{
                c.assertTrue(owner.player.tickCount>playerTicks,"The real joined OP keeps ticking during cold terrain preparation");exact(remote,"Native OP cross-world commit");
                c.assertValueEqual(GameModes.current(owner.player),GameModes.Mode.CREATIVE,"Real native OP teleport updates active mode");
                c.assertValueEqual(count(owner.player,Items.DIAMOND),0,"Survival diamonds never leak into Creative inventory");
                c.assertValueEqual(count(owner.player,Items.EMERALD),0,"Survival cursor items never leak into Creative inventory");
                c.assertTrue(owner.player.containerMenu.getCarried().isEmpty(),"The native cross-mode hook conserves and returns the old cursor before restore");
                c.assertTrue(Arrays.equals(observerFile,file(observer.player)),"A successful cross-mode participant save never writes the observer");released();
                owner.player.getInventory().setItem(0,new ItemStack(Items.NETHERITE_BLOCK,11));
                community.combat.put(owner.player.getUUID(),server.getTickCount()+500);community.cooldowns.put(owner.player.getUUID(),server.getTickCount()+500);
                warm(server.overworld(),origin);
                measured(()->community.queue(owner.player,origin,null));owner.drain();
                c.assertTrue(job()==null,"Already FULL OP return is immediate despite ordinary timers");
                c.assertValueEqual(GameModes.current(owner.player),GameModes.Mode.SURVIVAL,"Warm native OP return restores the original mode");
                c.assertValueEqual(count(owner.player,Items.DIAMOND),7,"Warm return restores every original Survival diamond");
                c.assertValueEqual(count(owner.player,Items.EMERALD),3,"The old cursor returns exactly once in its original mode");
                c.assertValueEqual(count(owner.player,Items.NETHERITE_BLOCK),0,"Creative blocks stay in their separate profile");
                warm(creative,remote);
                measured(()->community.queue(owner.player,remote,null));owner.drain();
                c.assertTrue(job()==null,"Already FULL OP outward travel remains immediate");c.assertValueEqual(count(owner.player,Items.NETHERITE_BLOCK),11,"The real Creative profile is restored rather than duplicated");
                OperatorGameTests.deop(owner.player);community.combat.remove(owner.player.getUUID());community.cooldowns.remove(owner.player.getUUID());
                defaultGrid();
            });
        }
        void defaultGrid() {
            // Actual remote player tickets permit unloading the origin search area.
            int y=creative.getMaxY()-3;var chunks=new LinkedHashSet<ChunkPos>();
            for(int x=-2;x<=1;x++)for(int z=-2;z<=1;z++){creative.getChunk(x,z);chunks.add(new ChunkPos(x,z));}
            for(int x=-16;x<=16;x+=4)for(int z=-16;z<=16;z+=4)for(int dy=0;dy<=2;dy++) {
                var pos=new BlockPos(x,y+dy,z);grid.put(pos,creative.getBlockState(pos));creative.setBlock(pos,(dy==0?Blocks.WATER:Blocks.AIR).defaultBlockState(),FLAGS);
            }
            var safe=GameModes.DEFAULT_SPAWN_CANDIDATES.get(64);creative.setBlock(new BlockPos(safe.getX(),y,safe.getZ()),Blocks.STONE.defaultBlockState(),FLAGS);
            border.setCenter(0,0);border.setSize(33);
            // Clear only this test's default cache entry; all saved player-home profiles stay intact.
            var cache=(Map<?,?>)field(GameModes.class,"DEFAULT_SPAWNS");var defaults=cache.get(server);
            if(defaults instanceof Map<?,?> map)originalCreativeDefault=(CommunityServer.Place)map.remove(GameModes.Mode.CREATIVE);cacheChanged=true;
            watch(creative,chunks);cold(()->{
                int starts=probe.starts.size();community.combat.put(owner.player.getUUID(),server.getTickCount()+200);command("spawn",0);
                measured(()->DestinationChunks.tick(server));c.assertTrue(job()==null,"Combat restriction refuses native default spawn before preparation");
                c.assertValueEqual(probe.starts.size(),starts,"A refused actual spawn command starts no native cold search work");released();
                community.combat.remove(owner.player.getUUID());
                var profiles=GameModes.state(owner.player).getCompoundOrEmpty("profiles").copy();var stale=new CompoundTag();
                stale.putString("place",CommunityServer.GSON.toJson(remote));profiles.put("CREATIVE",stale);GameModes.state(owner.player).put("profiles",profiles);
                snapshot();command("spawn");c.assertTrue(job()!=null,"An authorized cold default spawn starts real incremental preparation");
                await("Native cold origin default search",300,()->job()==null,()->{measured(community::tick);if(job()!=null)unchanged("Default search wait");},()->{
                    var expected=new CommunityServer.Place(creative.dimension().identifier().toString(),safe.getX()+.5,y+1,safe.getZ()+.5,0,0);
                    exact(expected,"Default spawn without an inactive saved-home fallback");c.assertTrue(server.getTickCount()-requested>=60,"Origin search cannot skip ordinary warmup");
                    unchanged("Same-mode default search completion");c.assertTrue(!probe.starts.isEmpty(),"The default search used real native cold load requests");
                    for(var entry:grid.entrySet())if(entry.getKey().getY()==y)c.assertTrue(creative.getBlockState(entry.getKey()).equals(entry.getKey().getX()==safe.getX()&&entry.getKey().getZ()==safe.getZ()?Blocks.STONE.defaultBlockState():Blocks.WATER.defaultBlockState()),"Search does not repair flooded terrain or manufacture platforms");
                    c.assertValueEqual(probe.blockingFutureCalls,0,"Measured native default/OP requests never call getChunkFuture");released();warmBudget();
                });
            });
        }
        void warmBudget() {
            // All native terrain is explicitly FULL before this separate command-budget observation.
            for(var pos:watched)creative.getChunk(pos.x(),pos.z());
            var cache=(Map<?,?>)field(GameModes.class,"DEFAULT_SPAWNS");var defaults=cache.get(server);if(defaults instanceof Map<?,?> map)map.remove(GameModes.Mode.CREATIVE);
            community.cooldowns.remove(owner.player.getUUID());snapshot();
            try(var reads=ModeDestinationHeightmapProbe.begin(creative)) {
                command("spawn");c.assertTrue(job()!=null,"The actual all-loaded flooded search remains a pending native command");
                c.assertValueEqual(reads.columns.size(),64,"One command acceptance reads exactly its64-native-column search budget");
                c.assertValueEqual(reads.columns,GameModes.DEFAULT_SPAWN_CANDIDATES.subList(0,64),"Actual command reads retain original nearest-ring order");
            }
            try(var reads=ModeDestinationHeightmapProbe.begin(creative)) {
                measured(community::tick);c.assertValueEqual(reads.columns.size(),1,"The next actual poll continues at the first genuinely safe column");
                c.assertValueEqual(reads.columns.getFirst(),GameModes.DEFAULT_SPAWN_CANDIDATES.get(64),"The command resumes the exact native search rather than restarting");
            }
            c.assertTrue(job()!=null,"Technical readiness still cannot skip the ordinary countdown");unchanged("Ready default command before countdown");
            community.cancelPending(owner.player);released();course(0);
        }
        void course(int index) {
            var mode=index==0?GameModes.Mode.MINIGAMES:GameModes.Mode.ADVENTURE;var map=ModeMaps.defaultMap(mode);
            c.assertTrue(map!=null,"The ordinary course refusal uses an actual available built-in map");var world=GameModes.world(server,mode);
            var place=GameModes.mapPlace(server,mode,map);for(var pos:halo(place))world.getChunk(pos.x(),pos.z());
            GameModes.switchNow(owner.player,mode,map);owner.drain();c.assertValueEqual(GameModes.current(owner.player),mode,"The fixture enters its real course dimension/mode");
            c.assertTrue(owner.player.teleportTo(world,8000.5,160,8000.5,Set.of(),0,0,true),"The ordinary native fixture moves beyond this course's simulation tickets");
            owner.drain();world.getChunkSource().move(owner.player);watch(world,halo(place));
            cold(()->{
                var state=GameModes.state(owner.player).copy();var position=CommunityServer.Place.of(owner.player);var saved=file(owner.player);int starts=probe.starts.size();
                command("spawn",0);measured(()->DestinationChunks.tick(server));
                c.assertTrue(job()==null,"Ordinary "+mode+" spawn rejects the course policy before any technical preparation");
                c.assertValueEqual(probe.starts.size(),starts,"A rejected actual course command starts no native cold map terrain");released();
                c.assertValueEqual(GameModes.state(owner.player),state,"The rejected course command cannot reset progress or mode profiles");
                c.assertValueEqual(CommunityServer.Place.of(owner.player),position,"The rejected course command never teleports");
                c.assertTrue(Arrays.equals(saved,file(owner.player)),"The rejected course command never rewrites native player data");
                if(index==0)course(1);else hardcore();
            });
        }
        void hardcore() {
            var mode=GameModes.Mode.HARDCORE;var world=GameModes.world(server,mode);
            // Native completed fixture terrain makes mode entry immediate. The later command starts cold.
            var destination=GameModes.defaultPlace(server,mode);for(var pos:halo(destination))world.getChunk(pos.x(),pos.z());
            GameModes.switchNow(owner.player,mode,null);owner.drain();c.assertValueEqual(GameModes.current(owner.player),mode,"The ordinary fixture enters the actual Hardcore realm");
            c.assertTrue(owner.player.teleportTo(world,8000.5,160,8000.5,Set.of(),0,0,true),"The living Hardcore fixture moves beyond its default's simulation tickets");
            owner.drain();world.getChunkSource().move(owner.player);watch(world,halo(destination));
            cold(()->{
                community.cooldowns.remove(owner.player.getUUID());community.combat.remove(owner.player.getUUID());snapshot();command("spawn");
                c.assertTrue(job()!=null,"Actual Hardcore spawn prepares its genuinely cold default without changing mode profiles");
                await("Native cold Hardcore default command",240,()->job()==null,()->{measured(community::tick);if(job()!=null)unchanged("Hardcore default wait");},()->{
                    exact(destination,"Living Hardcore's actual default command");c.assertTrue(server.getTickCount()-requested>=60,"Hardcore default travel retains its real ordinary countdown");
                    unchanged("Hardcore same-mode default commit");c.assertTrue(!probe.starts.isEmpty(),"Hardcore's cold default actually starts native chunk work");
                    c.assertValueEqual(probe.blockingFutureCalls,0,"Measured Hardcore command/polls never call native getChunkFuture");released();succeed();
                });
            });
        }
        @SuppressWarnings("unchecked")
        @Override public void close() {
            try {if(creative!=null){for(var entry:grid.entrySet())creative.setBlock(entry.getKey(),entry.getValue(),FLAGS);grid.clear();}
                if(border!=null){border.setCenter(borderX,borderZ);border.setSize(borderSize);}
                if(cacheChanged){var cache=(Map<MinecraftServer,Map<GameModes.Mode,CommunityServer.Place>>)field(GameModes.class,"DEFAULT_SPAWNS");var defaults=cache.get(server);
                    if(defaults!=null){if(originalCreativeDefault==null)defaults.remove(GameModes.Mode.CREATIVE);else defaults.put(GameModes.Mode.CREATIVE,originalCreativeDefault);}}}
            finally {super.close();}
        }
    }

    private static void startSerialized(GameTestHelper c,int remaining,Supplier<Scenario> factory) {
        if(running){c.assertTrue(remaining>0,"The exclusive native fixture queue remains bounded");c.runAfterDelay(1,()->startSerialized(c,remaining-1,factory));return;}
        running=true;
        try {var scenario=factory.get();scenario.guard(scenario::start);}
        catch(RuntimeException|Error failure){running=false;throw failure;}
    }
    @GameTest(maxTicks=3200)
    public void nativeExactCommandTravelKeepsCountdownStateOrientationAndConsentSessions(GameTestHelper c) {
        startSerialized(c,2600,()->new ExactScenario(c));
    }
    @GameTest(maxTicks=3200)
    public void nativeDefaultAndOperatorTravelPreparesColdTerrainAndConservesModeInventories(GameTestHelper c) {
        startSerialized(c,2600,()->new DefaultAndOperatorScenario(c));
    }
}

package dev.convergence;

import com.mojang.authlib.GameProfile;
import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueOutput;

/** Exercise the listener-owned refresh history through real native connections and packets. */
public class ClientVitalsRefreshGameTests {
    private static final class Joined implements AutoCloseable {
        final NativePlayConnection wire;
        final Connection network;
        final EmbeddedChannel channel;
        ServerPlayer player;
        Joined(GameTestHelper c,String name){this(c,new GameProfile(UUID.randomUUID(),name.length()<=16?name:name.substring(0,7)+"-"+Integer.toUnsignedString(name.hashCode(),16)),null,true);}
        Joined(GameTestHelper c,GameProfile profile,Integer entityId,boolean reset){
            wire=new NativePlayConnection(c.getLevel().registryAccess());network=wire.connection;channel=wire.channel;
            var server=c.getLevel().getServer();var cookie=CommonListenerCookie.createInitial(profile,false);
            player=new ServerPlayer(server,c.getLevel(),profile,cookie.clientInformation());
            if(entityId!=null)player.setId(entityId);
            server.getPlayerList().placeNewPlayer(network,player,cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            OperatorGameTests.level(player,LevelBasedPermissionSet.OWNER);
            var feet=c.absolutePos(new BlockPos(2,2,2));
            c.getLevel().setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());
            player.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);
            player.setNoGravity(true);player.setGameMode(GameType.SURVIVAL);
            drain();
            if(reset)resetView();
        }
        List<Packet<?>> raw(){return wire.drain();}
        List<Packet<?>> drain(){
            var result=new ArrayList<Packet<?>>();
            for(var packet:raw())flatten(packet,result);
            return result;
        }
        List<Packet<?>> send(Packet<?> packet){player.connection.send(packet);return drain();}
        void resetView(){send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(player.level()),(byte)0));}
        void set(GameTestHelper c,String stat,double value){
            var result=AdminStats.set(player.createCommandSourceStack(),player,stat,value);
            c.assertTrue(result.success(),result.message());drain();
        }
        void disconnect(){channel.close();channel.runPendingTasks();network.handleDisconnection();}
        @Override public void close(){
            var server=player.level().getServer();var current=server.getPlayerList().getPlayer(player.getUUID());
            if(current!=null){
                current.containerMenu.setCarried(ItemStack.EMPTY);current.closeContainer();OperatorGameTests.deop(current);
                GameModes.PENDING.remove(current.getUUID());GameModes.TRANSITIONS.remove(current.getUUID());
                server.getPlayerList().remove(current);
            }
            channel.finishAndReleaseAll();network.handleDisconnection();
        }
    }

    /** Native world-registered targets for the bounded-history test; no login handshakes or saved profiles. */
    private static final class TrackedPlayer implements AutoCloseable {
        final NativePlayConnection wire;
        final Connection network;
        final EmbeddedChannel channel;
        final ServerPlayer player;
        TrackedPlayer(ServerLevel world,int index){
            wire=new NativePlayConnection(world.registryAccess());network=wire.connection;channel=wire.channel;
            var profile=new GameProfile(UUID.randomUUID(),"hud-bound-"+index);
            var cookie=CommonListenerCookie.createInitial(profile,false);
            player=new ServerPlayer(world.getServer(),world,profile,cookie.clientInformation());
            player.connection=new ServerGamePacketListenerImpl(world.getServer(),network,player,cookie);
            network.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(world.registryAccess()),player.connection),player.connection);
            channel.runPendingTasks();channel.checkException();
            player.setNoGravity(true);player.setPos(0,100,0);world.addNewPlayer(player);
        }
        @Override public void close(){
            player.level().removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);
            channel.finishAndReleaseAll();
        }
    }

    private static void flatten(Packet<?> packet,List<Packet<?>> output){
        if(packet instanceof ClientboundBundlePacket bundle)for(var child:bundle.subPackets())flatten(child,output);
        else output.add(packet);
    }
    private static ClientboundUpdateAttributesPacket capacities(LivingEntity target){
        return new ClientboundUpdateAttributesPacket(target.getId(),List.of(
            target.getAttribute(Attributes.MAX_HEALTH),target.getAttribute(Attributes.MAX_ABSORPTION)));
    }
    private static ClientboundUpdateAttributesPacket healthCapacity(LivingEntity target){
        return new ClientboundUpdateAttributesPacket(target.getId(),List.of(target.getAttribute(Attributes.MAX_HEALTH)));
    }
    private static ClientboundSetEntityDataPacket metadata(ServerPlayer target,boolean health,boolean absorption){
        var values=new ArrayList<SynchedEntityData.DataValue<?>>();
        if(health)values.add(SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(),target.getHealth()));
        if(absorption)values.add(SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(),target.getAbsorptionAmount()));
        return new ClientboundSetEntityDataPacket(target.getId(),values);
    }
    private static ClientboundSetHealthPacket healthPacket(ServerPlayer target){
        return new ClientboundSetHealthPacket(target.getHealth(),target.getFoodData().getFoodLevel(),target.getFoodData().getSaturationLevel());
    }
    private static ClientboundSetHealthPacket ownerHud(GameTestHelper c,List<Packet<?>> packets,float expected){
        var values=packets.stream().filter(ClientboundSetHealthPacket.class::isInstance).map(ClientboundSetHealthPacket.class::cast).toList();
        c.assertValueEqual(values.size(),1,"A required owner refresh emits exactly one native health packet");
        c.assertValueEqual(values.getFirst().getHealth(),expected,"The owner sees the actual projected current health");
        return values.getFirst();
    }
    private static void ownerRefresh(GameTestHelper c,List<Packet<?>> packets,float health,float absorption){
        c.assertValueEqual(packets.size(),3,"An owner capacity refresh is attributes, HUD and absorption metadata");
        c.assertTrue(packets.getFirst() instanceof ClientboundUpdateAttributesPacket,"Native attributes remain first");
        ownerHud(c,packets,health);
        var tracker=(ClientboundSetEntityDataPacket)packets.getLast();
        c.assertValueEqual(tracker.packedItems().getFirst().id(),PlayerVitalsAccess.infinity$absorption().id(),"Owner metadata uses the native player absorption field");
        c.assertValueEqual(tracker.packedItems().getFirst().value(),absorption,"Owner absorption keeps its native projection");
    }
    private static void attributesOnly(GameTestHelper c,List<Packet<?>> packets){
        c.assertValueEqual(packets.size(),1,"Unchanged delivered capacity does not manufacture another refresh");
        c.assertTrue(packets.getFirst() instanceof ClientboundUpdateAttributesPacket,"The real attribute packet is still delivered");
    }
    private static ClientVitals.CapacityHistory history(ServerPlayer player){
        try {
            for(Class<?> type=player.connection.getClass();type!=null;type=type.getSuperclass())
                for(Field field:type.getDeclaredFields())if(field.getType()==ClientVitals.CapacityHistory.class){
                    field.setAccessible(true);return (ClientVitals.CapacityHistory)field.get(player.connection);
                }
            throw new AssertionError("Native play listener has no private capacity history");
        }catch(IllegalAccessException failure){throw new AssertionError(failure);}
    }
    @SuppressWarnings("unchecked")
    private static Map<Integer,?> entries(ClientVitals.CapacityHistory history){
        try {
            var field=ClientVitals.CapacityHistory.class.getDeclaredField("targets");field.setAccessible(true);
            return (Map<Integer,?>)field.get(history);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static boolean disabled(ClientVitals.CapacityHistory history){
        try {
            var field=ClientVitals.CapacityHistory.class.getDeclaredField("disabled");field.setAccessible(true);
            return field.getBoolean(history);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static net.minecraft.nbt.CompoundTag saved(ServerPlayer player){
        var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,player.registryAccess());
        player.saveWithoutId(output);return output.buildResult();
    }
    private static void await(GameTestHelper c,java.util.concurrent.CompletableFuture<?> future,int remaining,Runnable check,Runnable cleanup){
        boolean finished=false;
        try {
            if(future.isDone()){finished=true;future.join();check.run();}
            else if(remaining==0){finished=true;c.assertTrue(false,"The native async send completes within its bounded test window");}
            else c.runAfterDelay(1,()->await(c,future,remaining-1,check,cleanup));
        }catch(RuntimeException|Error failure){finished=true;throw failure;}
        finally{if(finished)cleanup.run();}
    }

    @GameTest public void deliveredCapacitiesSuppressOnlyRedundantRefreshes(GameTestHelper c){
        try(var f=new Joined(c,"hud-history-flags")){
            c.assertTrue(entries(history(f.player)).isEmpty(),"A native respawn packet resets the listener's view");
            f.send(healthPacket(f.player));f.send(metadata(f.player,true,true));
            c.assertTrue(entries(history(f.player)).isEmpty(),"Ordinary vitals alone never declare capacities known");
            // Creating native attribute packets lazily instantiates the saved
            // attribute instances. Establish them before taking the baseline.
            var health=healthCapacity(f.player);
            var absorption=new ClientboundUpdateAttributesPacket(f.player.getId(),List.of(f.player.getAttribute(Attributes.MAX_ABSORPTION)));
            var all=capacities(f.player);
            var unrelated=new ClientboundUpdateAttributesPacket(f.player.getId(),List.of(f.player.getAttribute(Attributes.ARMOR)));
            var before=saved(f.player);
            ownerRefresh(c,f.send(health),20,0);attributesOnly(c,f.send(health));
            ownerRefresh(c,f.send(absorption),20,0);
            for(int i=0;i<30;i++)attributesOnly(c,f.send(all));
            c.assertTrue(f.send(unrelated).getFirst()==unrelated,"Non-capacity attributes retain native identity");
            c.assertValueEqual(entries(history(f.player)).size(),1,"Repeated updates retain one bounded target entry");
            c.assertValueEqual(saved(f.player),before,"Capacity suppression changes no authoritative player data");
        }
        c.succeed();
    }

    @GameTest public void trueCapacityChangesAboveFortyAndResetAlwaysRefresh(GameTestHelper c){
        try(var f=new Joined(c,"hud-history-max")){
            f.set(c,"max_health",400);f.set(c,"max_absorption",100);f.player.setHealth(20);f.player.setAbsorptionAmount(10);
            ownerRefresh(c,f.send(capacities(f.player)),2,10);attributesOnly(c,f.send(capacities(f.player)));
            f.set(c,"max_health",800);
            ownerRefresh(c,f.send(capacities(f.player)),1,10);attributesOnly(c,f.send(capacities(f.player)));
            f.set(c,"max_health",Math.nextUp(800d));
            ownerRefresh(c,f.send(capacities(f.player)),1,10);
            f.set(c,"max_absorption",200);
            ownerRefresh(c,f.send(capacities(f.player)),1,10);
            f.player.setHealth(40);ownerRefresh(c,f.send(capacities(f.player)),2,10);
            f.player.setAbsorptionAmount(20);ownerRefresh(c,f.send(capacities(f.player)),2,20);
            f.set(c,"max_health",20);f.set(c,"max_absorption",20);f.player.setHealth(20);
            ownerRefresh(c,f.send(capacities(f.player)),20,20);attributesOnly(c,f.send(capacities(f.player)));
            c.assertValueEqual(f.player.getMaxHealth(),20f,"Wire history never changes real capacities");
        }
        c.succeed();
    }

    @GameTest public void ordinaryHealthAndMetadataDeliveriesUpdateExistingHistory(GameTestHelper c){
        try(var f=new Joined(c,"hud-history-delivery")){
            f.set(c,"max_health",400);f.set(c,"max_absorption",100);f.player.setHealth(20);f.player.setAbsorptionAmount(10);
            ownerRefresh(c,f.send(capacities(f.player)),2,10);
            f.player.setHealth(40);ownerHud(c,f.send(healthPacket(f.player)),4);
            attributesOnly(c,f.send(capacities(f.player)));
            f.player.setAbsorptionAmount(15);var absorption=metadata(f.player,false,true);
            c.assertTrue(f.send(absorption).getFirst()==absorption,"Already-bounded absorption metadata keeps its native identity");
            attributesOnly(c,f.send(capacities(f.player)));
            f.player.setHealth(60);
            var unrelated=new ClientboundSetEntityDataPacket(f.player.getId(),List.of(new SynchedEntityData.DataValue<>(0,EntityDataSerializers.BYTE,(byte)0)));
            c.assertTrue(f.send(unrelated).getFirst()==unrelated,"Unrelated metadata is still delivered untouched");
            ownerRefresh(c,f.send(capacities(f.player)),6,15);
            f.player.setHealth(80);f.send(metadata(f.player,true,false));attributesOnly(c,f.send(capacities(f.player)));
        }
        c.succeed();
    }

    @GameTest public void ownerFoodAndSaturationAreRefreshedAndOrdinaryHudDeliveryCounts(GameTestHelper c){
        try(var f=new Joined(c,"hud-history-hunger")){
            f.player.getFoodData().setFoodLevel(20);f.player.getFoodData().setSaturation(5);
            ownerRefresh(c,f.send(capacities(f.player)),20,0);
            f.player.getFoodData().setFoodLevel(18);
            var food=f.send(capacities(f.player));ownerRefresh(c,food,20,0);
            c.assertValueEqual(((ClientboundSetHealthPacket)food.get(1)).getFood(),18,"A capacity observation repairs changed native hunger");
            f.player.getFoodData().setSaturation(2);
            var saturation=f.send(capacities(f.player));ownerRefresh(c,saturation,20,0);
            c.assertValueEqual(((ClientboundSetHealthPacket)saturation.get(1)).getSaturation(),2f,"Owner saturation participates in the exact refresh signature");
            f.player.getFoodData().setFoodLevel(17);f.player.getFoodData().setSaturation(3);
            var packet=healthPacket(f.player);c.assertTrue(f.send(packet).getFirst()==packet,"Normal HUD updates remain unchanged native packets");
            attributesOnly(c,f.send(capacities(f.player)));
        }
        c.succeed();
    }

    @GameTest public void observersKeepIndependentHistoryAndNeverReceiveAnotherPlayersHud(GameTestHelper c){
        try(var target=new Joined(c,"hud-history-target");var a=new Joined(c,"hud-history-view-a");var b=new Joined(c,"hud-history-view-b")){
            // Later native joins broadcast player-info and chat to earlier
            // connections. Finish those deliveries before the measured sends.
            target.drain();a.drain();b.drain();target.resetView();a.resetView();b.resetView();
            var packet=capacities(target.player);
            c.assertValueEqual(a.send(packet).size(),2,"First observer gets attributes and player metadata");
            c.assertValueEqual(b.send(packet).size(),2,"A second observer has an independent fresh view");
            attributesOnly(c,a.send(packet));attributesOnly(c,b.send(packet));
            target.player.getFoodData().setFoodLevel(11);target.player.getFoodData().setSaturation(1);
            attributesOnly(c,a.send(packet));attributesOnly(c,b.send(packet));
            target.player.setHealth(18);var refresh=a.send(packet);
            c.assertValueEqual(refresh.size(),2,"An undelivered observed health change still causes a refresh");
            c.assertFalse(refresh.stream().anyMatch(ClientboundSetHealthPacket.class::isInstance),"An observer never receives the target's private HUD");
            target.player.setHealth(16);a.send(metadata(target.player,true,false));
            attributesOnly(c,a.send(packet));c.assertValueEqual(b.send(packet).size(),2,"One observer's metadata delivery cannot prime another observer");
            ownerRefresh(c,target.send(capacities(target.player)),16,0);
            c.assertValueEqual(a.player.getHealth(),20f,"Observed history does not mutate the viewer's actual health");
        }
        c.succeed();
    }

    @GameTest public void orderedNestedLifecyclePacketsInvalidateOnlyTheIntendedView(GameTestHelper c){
        try(var target=new Joined(c,"hud-history-order-target");var viewer=new Joined(c,"hud-history-order-view")){
            var attrs=capacities(target.player);viewer.send(attrs);attributesOnly(c,viewer.send(attrs));
            var remove=new ClientboundRemoveEntitiesPacket(target.player.getId());
            var add=new ClientboundAddEntityPacket(target.player,0,target.player.blockPosition());
            var nested=new ClientboundBundlePacket(List.of(remove,add,attrs));
            var source=List.<Packet<? super ClientGamePacketListener>>of(attrs,nested,attrs);
            viewer.player.connection.send(new ClientboundBundlePacket(source));
            var raw=viewer.raw();var output=new ArrayList<Packet<?>>();
            for(var packet:raw){
                if(packet instanceof ClientboundBundlePacket bundle)for(var child:bundle.subPackets())c.assertFalse(child instanceof ClientboundBundlePacket,"Actual outbound bundles contain no nested bundles");
                flatten(packet,output);
            }
            c.assertValueEqual(output.size(),6,"Ordered invalidation creates one fresh observed refresh between unchanged attributes");
            c.assertTrue(output.get(0)==attrs&&output.get(1)==remove&&output.get(2)==add&&output.get(3)==attrs&&output.get(5)==attrs,
                "Flattening preserves exact native lifecycle and attribute order");
            c.assertTrue(output.get(4) instanceof ClientboundSetEntityDataPacket,"Only the post-add capacity gets synthetic tracked vitals");
            c.assertTrue(source.get(1)==nested,"The shared original bundle is not mutated");
            viewer.send(new ClientboundAddEntityPacket(target.player,0,target.player.blockPosition()));
            c.assertValueEqual(viewer.send(attrs).size(),2,"A repeated native AddEntity with the same UUID still invalidates prior capacity");
            ownerRefresh(c,viewer.send(capacities(viewer.player)),20,0);
            viewer.send(new ClientboundRemoveEntitiesPacket(target.player.getId()));
            c.assertFalse(entries(history(viewer.player)).containsKey(target.player.getId()),"Native removal forgets its target");
            c.assertTrue(entries(history(viewer.player)).containsKey(viewer.player.getId()),"Removing another target preserves the owner's known capacity");
            attributesOnly(c,viewer.send(capacities(viewer.player)));
            viewer.resetView();c.assertTrue(entries(history(viewer.player)).isEmpty(),"Native Respawn invalidates every prior observed target");
            c.assertValueEqual(viewer.send(attrs).size(),2,"Respawn repairs an unchanged observed capacity for the fresh client view");
        }
        c.succeed();
    }

    @GameTest(maxTicks=40) public void nativeDeathRespawnRebindsTheExistingHandlerToItsFreshPlayer(GameTestHelper c){
        var f=new Joined(c,"hud-history-respawn");boolean scheduled=false;
        try {
            var old=f.player;var handler=old.connection;var oldHistory=history(old);
            ownerRefresh(c,f.send(capacities(old)),20,0);
            var feet=old.blockPosition();
            old.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(old.level().dimension(),feet,0,0),true),false);
            old.setHealth(0);
            handler.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            var fresh=old.level().getServer().getPlayerList().getPlayer(old.getUUID());
            c.assertTrue(fresh!=null&&fresh!=old,"The real native respawn replaces the joined player");
            c.assertTrue(fresh.connection==handler,"Vanilla retains the same native network handler");
            f.player=fresh;fresh.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var packets=f.drain();
            c.assertTrue(packets.stream().anyMatch(ClientboundRespawnPacket.class::isInstance),"The native respawn packet crossed the actual outbound hook");
            c.assertTrue(history(fresh)==oldHistory,"The listener retains its bounded history object while rebinding targets");
            for(var entry:entries(oldHistory).values()){
                try {
                    var field=entry.getClass().getDeclaredField("target");field.setAccessible(true);
                    c.assertTrue(field.get(entry)!=old,"The old native player cannot remain a remembered target after respawn");
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            }
            // Vanilla sends the freshly reset health through ServerPlayer's
            // real following tick, not directly inside its respawn routine.
            c.runAfterDelay(1,()->{
                try {
                    var delivered=f.drain();
                    c.assertTrue(delivered.stream().anyMatch(packet->packet instanceof ClientboundSetHealthPacket health&&health.getHealth()==20),
                        "The actual following native player tick refreshes the fresh player's real HUD");
                    f.send(capacities(fresh));attributesOnly(c,f.send(capacities(fresh)));
                }finally{f.close();}
                c.succeed();
            });
            scheduled=true;
        }finally{if(!scheduled)f.close();}
    }

    @GameTest public void nativeReconnectWithSameUuidAndEntityIdGetsANewListenerHistory(GameTestHelper c){
        try(var original=new Joined(c,"hud-history-rejoin")){
            var old=original.player;var oldHistory=history(old);ownerRefresh(c,original.send(capacities(old)),20,0);
            original.disconnect();
            c.assertTrue(old.level().getServer().getPlayerList().getPlayer(old.getUUID())==null,"The actual channel disconnect removes the old player session");
            try(var fresh=new Joined(c,old.getGameProfile(),old.getId(),true)){
                c.assertTrue(fresh.player!=old&&fresh.player.getUUID().equals(old.getUUID())&&fresh.player.getId()==old.getId(),
                    "The native registration really reuses both UUID and entity ID with a fresh player object");
                c.assertTrue(history(fresh.player)!=oldHistory,"Reconnect never shares the prior listener's history object");
                ownerRefresh(c,fresh.send(capacities(fresh.player)),20,0);attributesOnly(c,fresh.send(capacities(fresh.player)));
                c.assertValueEqual(oldHistory==history(fresh.player),false,"The old connection cannot suppress the new connection's first refresh");
            }
        }
        c.succeed();
    }

    @GameTest(maxTicks=60) public void registeredHelpersAreRememberedAndVisibleOrdinaryMobsAreNot(GameTestHelper c){
        var f=new Joined(c,"hud-history-helper");var owner=f.player;var agents=AgentCompanions.get(owner.level().getServer());
        var feet=c.absolutePos(new BlockPos(4,12,4));
        for(var pos:BlockPos.betweenClosed(feet.offset(-4,-1,-4),feet.offset(4,4,4)))
            c.getLevel().setBlockAndUpdate(pos,pos.getY()==feet.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        owner.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);
        var cow=EntityTypes.COW.create(c.getLevel(),EntitySpawnReason.COMMAND);boolean scheduled=false;String helperId=null;
        try {
            c.assertTrue(cow!=null,"The native passive mob fixture exists in Peaceful");
            cow.setNoAi(true);cow.setNoGravity(true);cow.setPos(feet.getX()+3.5,feet.getY(),feet.getZ()+3.5);
            c.assertTrue(c.getLevel().addFreshEntity(cow),"The ordinary mob is registered in the actual world");
            agents.spawn(owner,"refresh-robot");var record=agents.owned(owner,"refresh-robot");
            c.assertTrue(record!=null,"The fixture uses a real registered helper");helperId=record.getKey();
            var helper=agents.loaded.get(UUID.fromString(helperId));helper.setNoAi(true);
            var set=AdminStats.set(owner.createCommandSourceStack(),helper,"max_health",400);c.assertTrue(set.success(),set.message());helper.setHealth(200);
            c.runAfterDelay(6,()->{
                try {
                    c.assertTrue(owner.level().getEntity(helper.getId())==helper,"The helper is visible to the actual native entity lookup");
                    c.assertTrue(owner.level().getEntity(cow.getId())==cow,"The ordinary mob is visible before testing its eligibility");
                    f.drain();f.resetView();var attrs=healthCapacity(helper);var first=f.send(attrs);
                    c.assertValueEqual(first.size(),2,"Registered helper capacity refreshes tracked native health");
                    c.assertFalse(first.stream().anyMatch(ClientboundSetHealthPacket.class::isInstance),"A helper never receives the owner's private HUD");
                    var metadata=(ClientboundSetEntityDataPacket)first.getLast();
                    c.assertValueEqual(metadata.packedItems().size(),1,"Native helper metadata has no player absorption field");
                    c.assertValueEqual(metadata.packedItems().getFirst().value(),20f,"Helper current health keeps its native percentage");
                    attributesOnly(c,f.send(attrs));
                    var ordinary=healthCapacity(cow);c.assertTrue(f.send(ordinary).getFirst()==ordinary,"A visible ordinary living mob keeps its native capacity packet");
                    c.assertFalse(entries(history(owner)).containsKey(cow.getId()),"An ordinary living mob never consumes history capacity");
                } finally {cow.discard();agents.dismiss(owner,"refresh-robot");f.close();}
                c.succeed();
            });
            scheduled=true;
        } finally {if(!scheduled){if(cow!=null)cow.discard();if(helperId!=null)agents.dismiss(owner,"refresh-robot");f.close();}}
    }

    @GameTest public void nativeObservedTargetChurnKeepsFifoHistoryBounded(GameTestHelper c){
        try(var viewer=new Joined(c,"hud-history-bound")){
            var targets=new ArrayList<TrackedPlayer>();
            try {
                for(int i=0;i<256;i++){
                    var target=new TrackedPlayer(viewer.player.level(),i);targets.add(target);
                    c.assertTrue(viewer.player.level().getEntity(target.player.getId())==target.player,"Each bounded fixture uses a real native registered target");
                    viewer.drain();viewer.send(new ClientboundAddEntityPacket(target.player,0,target.player.blockPosition()));
                    c.assertValueEqual(viewer.send(healthCapacity(target.player)).size(),2,"A new native target gets one observed refresh");
                    c.assertTrue(entries(history(viewer.player)).size()<=256,"Target churn never exceeds the bounded native history");
                }
                var history=history(viewer.player);c.assertValueEqual(entries(history).size(),256,"The history fills to its documented exact bound");
                var first=targets.getFirst().player;var second=targets.get(1).player;
                attributesOnly(c,viewer.send(healthCapacity(first)));
                var newest=new TrackedPlayer(viewer.player.level(),256);targets.add(newest);
                viewer.drain();viewer.send(new ClientboundAddEntityPacket(newest.player,0,newest.player.blockPosition()));
                c.assertValueEqual(viewer.send(healthCapacity(newest.player)).size(),2,"An additional actual target can still refresh");
                c.assertValueEqual(entries(history).size(),256,"Inserting past the bound evicts exactly one entry");
                c.assertFalse(entries(history).containsKey(first.getId()),"A repeated lookup does not reorder FIFO eviction");
                c.assertTrue(entries(history).containsKey(second.getId()),"The next original target remains remembered");
                attributesOnly(c,viewer.send(healthCapacity(second)));
                c.assertValueEqual(viewer.send(healthCapacity(first)).size(),2,"An evicted native target gets a fresh observed refresh when seen again");
                c.assertValueEqual(entries(history).size(),256,"Reintroducing an evicted target retains the same bound");
            } finally {for(var target:targets)target.close();}
        }
        c.succeed();
    }

    @GameTest(maxTicks=80) public void offThreadRelevantSendPermanentlyFallsBackButUnrelatedSendDoesNot(GameTestHelper c){
        var f=new Joined(c,"hud-history-thread");boolean scheduled=false;
        try {
            var packet=capacities(f.player);ownerRefresh(c,f.send(packet),20,0);
            var history=history(f.player);attributesOnly(c,f.send(packet));
            var unrelated=new ClientboundSetExperiencePacket(0,0,0);
            var first=java.util.concurrent.CompletableFuture.runAsync(()->f.player.connection.send(unrelated));
            var handedOff=new boolean[]{false};
            c.runAfterDelay(1,()->await(c,first,25,()->{
                    f.drain();
                    c.assertFalse(disabled(history),"An unrelated native packet sent off-thread does not disable the relevant-packet history");
                    attributesOnly(c,f.send(packet));
                    // The packet was built on the server thread; the worker only calls the native send method.
                    var relevant=java.util.concurrent.CompletableFuture.runAsync(()->f.player.connection.send(packet));
                    c.runAfterDelay(1,()->await(c,relevant,25,()->{
                        var packets=f.drain();var index=packets.indexOf(packet);
                        c.assertTrue(index>=0&&index+2<packets.size(),"The actual asynchronous capacity send preserves its attribute plus required refresh packets");
                        c.assertTrue(packets.get(index+1) instanceof ClientboundSetHealthPacket&&packets.get(index+2) instanceof ClientboundSetEntityDataPacket,
                            "An off-thread capacity send uses the existing baseline owner refresh");
                        c.assertTrue(disabled(history)&&entries(history).isEmpty(),"Conservative fallback clears remembered targets permanently");
                        ownerRefresh(c,f.send(packet),20,0);ownerRefresh(c,f.send(packet),20,0);
                        f.resetView();
                        c.assertTrue(disabled(history),"A later native Respawn never re-enables an unsafe history");
                        ownerRefresh(c,f.send(packet),20,0);c.succeed();
                    },f::close));
                    handedOff[0]=true;
            },()->{if(!handedOff[0])f.close();}));
            scheduled=true;
        }finally{if(!scheduled)f.close();}
    }
}

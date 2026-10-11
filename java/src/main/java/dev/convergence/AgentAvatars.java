package dev.convergence;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.minecraft.world.entity.EntityTypes;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.GameType;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

/** A native player avatar for a registered helper; its ownership and combat stay server-side. */
final class AgentAvatars implements PolymerEntity {
    final IronGolem helper;
    final Set<ServerPlayer> viewers=new HashSet<>();
    AgentAvatars(IronGolem helper) {this.helper=helper;}
    static void attach(IronGolem helper) {
        if(!(PolymerEntity.get(helper) instanceof AgentAvatars))
            PolymerEntityUtils.setPolymerEntity(helper,new AgentAvatars(helper));
    }
    static void remove(IronGolem helper) {
        if(PolymerEntity.get(helper) instanceof AgentAvatars avatar) {
            var packet=new ClientboundPlayerInfoRemovePacket(List.of(helper.getUUID()));
            for(var viewer:new ArrayList<>(avatar.viewers))if(!viewer.hasDisconnected())viewer.connection.send(packet);
            avatar.viewers.clear();
        }
    }
    @Override public EntityType<?> getPolymerEntityType(PacketContext context) {return EntityTypes.PLAYER;}
    @Override public boolean canSynchronizeToPolymerClient(ServerPlayer player) {return false;}
    ClientboundPlayerInfoUpdatePacket profilePacket() {
        var packet=PolymerEntityUtils.createMutablePlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT));
        // Share the signed robot texture while preserving each helper's native identity.
        String name="AI_"+helper.getStringUUID().replace("-","").substring(0,12);
        packet.entries().add(new ClientboundPlayerInfoUpdatePacket.Entry(helper.getUUID(),AgentSkin.profile(helper.getUUID(),name),
            false,0,GameType.SURVIVAL,helper.getCustomName()==null?Component.literal("AI helper"):helper.getCustomName(),true,0,null));
        return packet;
    }
    @Override public void onBeforeSpawnPacket(ServerPlayer player,Consumer<Packet<?>> sender) {
        viewers.add(player);sender.accept(profilePacket());
    }
    @Override public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data,ServerPlayer player,boolean initial) {
        var human=PolymerEntityUtils.getDefaultSynchedEntityData(EntityTypes.PLAYER);
        var golem=PolymerEntityUtils.getDefaultSynchedEntityData(EntityTypes.IRON_GOLEM);
        // Keep only the shared Entity/LivingEntity prefix. Mob/golem fields occupy
        // player-specific indices with incompatible types or different meanings.
        int common=0;
        while(common<Math.min(human.length,golem.length)&&human[common]!=null&&golem[common]!=null
            &&human[common].getAccessor().serializer()==golem[common].getAccessor().serializer())common++;
        final int boundary=common;
        data.removeIf(entry->entry.id()>=boundary);
        if(initial)for(int i=boundary;i<human.length;i++)if(human[i]!=null)data.add(human[i].value());
    }
    @Override public void onEntityTrackerTick(Set<ServerPlayerConnection> listeners) {
        var active=new HashSet<ServerPlayer>();for(var listener:listeners)active.add(listener.getPlayer());
        var packet=new ClientboundPlayerInfoRemovePacket(List.of(helper.getUUID()));
        viewers.removeIf(viewer->{
            if(active.contains(viewer)&&!viewer.hasDisconnected())return false;
            if(!viewer.hasDisconnected())viewer.connection.send(packet);
            return true;
        });
    }
}

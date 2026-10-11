package dev.convergence;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.network.PlayerAssociatedNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import xyz.nucleoid.packettweaker.PacketContext;

/** A native player avatar for a registered helper; its ownership and combat stay server-side. */
final class AgentAvatars implements PolymerEntity {
    final IronGolemEntity helper;
    final Set<ServerPlayerEntity> viewers=new HashSet<>();
    AgentAvatars(IronGolemEntity helper) {this.helper=helper;}
    static void attach(IronGolemEntity helper) {
        if(!(PolymerEntity.get(helper) instanceof AgentAvatars))
            PolymerEntityUtils.setPolymerEntity(helper,new AgentAvatars(helper));
    }
    static void remove(IronGolemEntity helper) {
        if(PolymerEntity.get(helper) instanceof AgentAvatars avatar) {
            var packet=new PlayerRemoveS2CPacket(List.of(helper.getUuid()));
            for(var viewer:new ArrayList<>(avatar.viewers))if(!viewer.isDisconnected())viewer.networkHandler.sendPacket(packet);
            avatar.viewers.clear();
        }
    }
    public EntityType<?> getPolymerEntityType(PacketContext context) {return EntityType.PLAYER;}
    public boolean canSynchronizeToPolymerClient(ServerPlayerEntity player) {return false;}
    PlayerListS2CPacket profilePacket() {
        var packet=PolymerEntityUtils.createMutablePlayerListPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,
            PlayerListS2CPacket.Action.UPDATE_GAME_MODE,PlayerListS2CPacket.Action.UPDATE_LISTED,
            PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,PlayerListS2CPacket.Action.UPDATE_HAT));
        // Share the signed robot texture while preserving each helper's native identity.
        String name="AI_"+helper.getUuidAsString().replace("-","").substring(0,12);
        packet.getEntries().add(new PlayerListS2CPacket.Entry(helper.getUuid(),AgentSkin.profile(helper.getUuid(),name),
            false,0,GameMode.SURVIVAL,helper.getCustomName()==null?Text.literal("AI helper"):helper.getCustomName(),true,0,null));
        return packet;
    }
    public void onBeforeSpawnPacket(ServerPlayerEntity player,Consumer<Packet<?>> sender) {
        viewers.add(player);sender.accept(profilePacket());
    }
    public void modifyRawTrackedData(List<DataTracker.SerializedEntry<?>> data,ServerPlayerEntity player,boolean initial) {
        var human=PolymerEntityUtils.getDefaultTrackedData(EntityType.PLAYER);
        var golem=PolymerEntityUtils.getDefaultTrackedData(EntityType.IRON_GOLEM);
        // Keep only the shared Entity/LivingEntity prefix. Mob/golem fields occupy
        // player-specific indices with incompatible types or different meanings.
        int common=0;
        while(common<Math.min(human.length,golem.length)&&human[common]!=null&&golem[common]!=null
            &&human[common].getData().dataType()==golem[common].getData().dataType())common++;
        final int boundary=common;
        data.removeIf(entry->entry.id()>=boundary);
        if(initial)for(int i=boundary;i<human.length;i++)if(human[i]!=null)data.add(human[i].toSerialized());
    }
    public void onEntityTrackerTick(Set<PlayerAssociatedNetworkHandler> listeners) {
        var active=new HashSet<ServerPlayerEntity>();for(var listener:listeners)active.add(listener.getPlayer());
        var packet=new PlayerRemoveS2CPacket(List.of(helper.getUuid()));
        viewers.removeIf(viewer->{
            if(active.contains(viewer)&&!viewer.isDisconnected())return false;
            if(!viewer.isDisconnected())viewer.networkHandler.sendPacket(packet);
            return true;
        });
    }
}

package dev.convergence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.convergence.mixin.RcPlaneStateAccess;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Short-lived native falling-block assemblies: no client mod, terrain placement or drops. */
public final class RcPlanes {
    public static final String TAG="infinity_rc_plane";
    static final Map<UUID,Plane> ACTIVE=new HashMap<>();
    static final Map<UUID,Plane> PARTS=new HashMap<>();
    static final List<Vec3> OFFSETS=List.of(new Vec3(0,0,-2),new Vec3(0,0,-1),Vec3.ZERO,
        new Vec3(0,0,1),new Vec3(-1,0,0),new Vec3(-2,0,0),new Vec3(1,0,0),new Vec3(2,0,0),new Vec3(0,1,1));
    static final class Plane {
        final ServerPlayer owner;final ServerGamePacketListenerImpl connection;final ServerLevel world;
        final List<FallingBlockEntity> parts=new ArrayList<>();final int expires;
        Vec3 center;boolean hover;
        Plane(ServerPlayer p,Vec3 center){owner=p;connection=p.connection;world=p.level();this.center=center;expires=world.getServer().getTickCount()+6000;}
    }
    private RcPlanes(){}
    static Vec3 position(Vec3 center,Vec3 offset,float yaw){
        return center.add(offset.yRot((float)Math.toRadians(-yaw)));
    }
    static boolean clear(Plane plane,Vec3 center){
        for(var offset:OFFSETS){
            var pos=position(center,offset,plane.owner.getYRot());var block=BlockPos.containing(pos);
            var box=new AABB(pos.x-.49,pos.y,pos.z-.49,pos.x+.49,pos.y+.98,pos.z+.49);
            if(!plane.world.isInWorldBounds(block)||!plane.world.isLoaded(block)||!plane.world.getWorldBorder().isWithinBounds(box)
                ||plane.world.getBlockCollisions(null,box).iterator().hasNext()
                ||!plane.world.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,box,e->e.isAlive()&&!e.isSpectator()).isEmpty())return false;
        }
        return true;
    }
    static int launch(ServerPlayer owner){
        if(!CreativeStudio.allowed(owner))return 0;
        if(ACTIVE.containsKey(owner.getUUID())){Convergence.say(owner,"Your plane is already flying. Toggle hover or recall it first.");return 0;}
        if(ACTIVE.size()>=8){Convergence.say(owner,"Eight planes are already active. Recall an unused plane first.");return 0;}
        var center=owner.getEyePosition().add(owner.getLookAngle().scale(7)).add(0,2,0);var plane=new Plane(owner,center);
        if(!clear(plane,center)){Convergence.say(owner,"Plane needs clear air ahead. Aim toward an open area.");return 0;}
        var palette=CreativeStudio.palette(owner);
        ACTIVE.put(owner.getUUID(),plane);
        for(int i=0;i<OFFSETS.size();i++){
            var part=new FallingBlockEntity(EntityTypes.FALLING_BLOCK,owner.level());
            ((RcPlaneStateAccess)part).infinity$blockState((i==0?palette.light():i==8?Blocks.GLASS:palette.accent()).defaultBlockState());
            part.setNoGravity(true);part.dropItem=false;part.setHurtsEntities(0,0);part.addTag(TAG);
            part.setPos(position(center,OFFSETS.get(i),owner.getYRot()));part.setStartPos(part.blockPosition());
            plane.parts.add(part);PARTS.put(part.getUUID(),plane);
            if(!owner.level().addFreshEntity(part)){remove(plane);Convergence.say(owner,"Plane could not launch. Nothing was placed or dropped.");return 0;}
        }
        Convergence.say(owner,"RC plane launched! Look to steer. Studio or your remote toggles hover; Recall removes it.");return 1;
    }
    static boolean valid(Plane plane){
        return CreativeStudio.allowed(plane.owner)&&plane.owner.connection==plane.connection&&plane.owner.level()==plane.world
            &&plane.world.getServer().getTickCount()<plane.expires&&plane.center.distanceToSqr(plane.owner.position())<=64*64
            &&plane.parts.stream().allMatch(part->!part.isRemoved());
    }
    static void tick(){
        for(var plane:List.copyOf(ACTIVE.values())){
            if(!valid(plane)){remove(plane);continue;}
            double speed=switch(CreativeStudio.option(plane.owner,"speed",3)){case 1->.22;case 2->.32;default->.12;};
            var next=plane.hover?plane.center:plane.center.add(plane.owner.getLookAngle().scale(speed));
            if(!clear(plane,next)){plane.hover=true;plane.owner.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Plane hovering: obstacle ahead."));continue;}
            plane.center=next;
            for(int i=0;i<plane.parts.size();i++){
                var part=plane.parts.get(i);part.setDeltaMovement(Vec3.ZERO);part.setPos(position(next,OFFSETS.get(i),plane.owner.getYRot()));
            }
        }
    }
    static int hover(ServerPlayer owner){
        if(!CreativeStudio.allowed(owner))return 0;
        var plane=ACTIVE.get(owner.getUUID());if(plane==null){Convergence.say(owner,"Launch your plane first.");return 0;}
        plane.hover=!plane.hover;Convergence.say(owner,plane.hover?"Plane hovering.":"Plane following your look direction.");return 1;
    }
    static String status(ServerPlayer owner){var p=ACTIVE.get(owner.getUUID());return p==null?"No plane":p.hover?"Hovering":"Flying";}
    static void remove(Plane plane){
        ACTIVE.remove(plane.owner.getUUID(),plane);
        for(var part:plane.parts){PARTS.remove(part.getUUID());part.discard();}
    }
    static int recall(ServerPlayer owner){var plane=ACTIVE.get(owner.getUUID());if(plane==null)return 0;remove(plane);return 1;}
    /** Called before vanilla's falling-block tick can place terrain or produce an item. */
    public static boolean tickPart(FallingBlockEntity part){
        if(!part.entityTags().contains(TAG))return false;
        var plane=PARTS.get(part.getUUID());
        if(plane==null||!valid(plane)){part.discard();return true;}
        part.baseTick();part.setDeltaMovement(Vec3.ZERO);return true;
    }
    static void register(){
        ServerTickEvents.END_SERVER_TICK.register(server->tick());
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{
            if(entity instanceof FallingBlockEntity part&&part.entityTags().contains(TAG)&&!PARTS.containsKey(part.getUUID()))part.discard();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{for(var plane:List.copyOf(ACTIVE.values()))remove(plane);PARTS.clear();});
    }
}

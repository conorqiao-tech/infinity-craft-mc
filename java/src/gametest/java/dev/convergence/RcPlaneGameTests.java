package dev.convergence;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Real world queries, native collision boxes and normal server-tick notices. */
public class RcPlaneGameTests {
    private static final class TrackingTickets implements AutoCloseable {
        private static long sequence=2100;
        private final net.minecraft.server.level.TicketType ticket=new net.minecraft.server.level.TicketType(++sequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING|net.minecraft.server.level.TicketType.FLAG_SIMULATION|net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        private final net.minecraft.server.level.ServerLevel world;
        private final java.util.List<net.minecraft.world.level.ChunkPos> chunks=new java.util.ArrayList<>();
        TrackingTickets(ServerPlayer owner){
            world=owner.level();var at=owner.chunkPosition();
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
                var chunk=new net.minecraft.world.level.ChunkPos(at.x()+x,at.z()+z);chunks.add(chunk);
                world.getChunkSource().addTicketWithRadius(ticket,chunk,2);
            }
        }
        @Override public void close(){
            for(var chunk:chunks)world.getChunkSource().removeTicketWithRadius(ticket,chunk,2);
            chunks.clear();
        }
    }
    private static Vec3 center(ServerPlayer p){return p.getEyePosition().add(0,2,7);}
    private static AABB box(Vec3 center,Vec3 offset,float yaw){
        var at=RcPlanes.position(center,offset,yaw);
        return new AABB(at.x-.49,at.y,at.z-.49,at.x+.49,at.y+.98,at.z+.49);
    }
    private static RcPlanePerformanceProbe.Sample check(GameTestHelper c,RcPlanes.Plane plane,Vec3 at,boolean clear,int queries){
        var sample=RcPlanePerformanceProbe.begin(plane.owner);
        try {
            boolean actual=RcPlanes.clear(plane,at);
            c.assertValueEqual(actual,clear,"Real plane clearance matches the exact part geometry; native candidates="+sample.candidates);
            c.assertValueEqual(sample.queries,queries,"Each safe candidate uses one native entity query; blocked worlds use none");
            return sample;
        } finally {RcPlanePerformanceProbe.end(plane.owner);}
    }

    @GameTest(maxTicks=40) public void clearAirUsesOneNativeQueryAndUnionCornersRemainEmpty(GameTestHelper c){
        var studio=new CreativeStudioGameTests();var p=studio.player(c,"plane-union");p.setYRot(0);
        var tracking=new TrackingTickets(p);
        var at=center(p);var plane=new RcPlanes.Plane(p,at);
        var mob=EntityTypes.COW.create(p.level(),EntitySpawnReason.COMMAND);
        boolean scheduled=false;
        try {
            c.assertTrue(mob!=null,"The passive living fixture can be created in the native Peaceful test world");
            var empty=check(c,plane,at,true,1);
            c.assertValueEqual(empty.bounds.size(),1,"The native query receives one combined bounding box");
            mob.setNoAi(true);mob.setNoGravity(true);mob.setPos(at.add(1.6,0,-1.6));
            c.assertTrue(p.level().addFreshEntity(mob),"The corner fixture is a real registered living mob");
            // Forced test chunks need native ticks to promote their entity sections to query visibility.
            c.runAfterDelay(6,()->{
                try {
                    for(var offset:RcPlanes.OFFSETS)c.assertFalse(mob.getBoundingBox().intersects(box(at,offset,0)),"The corner mob touches no actual plane part");
                    var corner=check(c,plane,at,true,1);
                    c.assertTrue(corner.bounds.getFirst().intersects(mob.getBoundingBox()),"The corner is inside the actual native broad-phase bounds");
                    c.assertTrue(corner.candidates.contains(mob.getUUID()),"The native broad-phase query really returned the corner mob");
                    c.succeed();
                } finally {mob.discard();studio.cleanup(p);tracking.close();}
            });
            scheduled=true;
        } finally {if(!scheduled){if(mob!=null)mob.discard();studio.cleanup(p);tracking.close();}}
    }

    @GameTest(maxTicks=40) public void exactPartCollisionRetainsAliveAndSpectatorEligibility(GameTestHelper c){
        var studio=new CreativeStudioGameTests();var p=studio.player(c,"plane-living");p.setYRot(0);
        var tracking=new TrackingTickets(p);
        var at=center(p);var plane=new RcPlanes.Plane(p,at);
        var mob=EntityTypes.COW.create(p.level(),EntitySpawnReason.COMMAND);ServerPlayer other=null;
        boolean scheduled=false;
        try {
            c.assertTrue(mob!=null,"The passive living fixture can be created in the native Peaceful test world");
            mob.setNoAi(true);mob.setNoGravity(true);mob.setPos(at);
            c.assertTrue(p.level().addFreshEntity(mob),"The part fixture is a real registered living mob");
            other=studio.player(c,"plane-spectator");other.setPos(at);
            // Creative realm ticks respect a real OP4 player's explicit spectator choice.
            OperatorGameTests.level(other,net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
            other.setGameMode(GameType.SPECTATOR);
            var spectator=other;
            c.runAfterDelay(6,()->{
                boolean nextScheduled=false;
                try {
                    c.assertTrue(spectator.isSpectator(),"The real OP4 bystander remains a spectator after native realm ticks");
                    c.assertTrue(check(c,plane,at,false,1).candidates.contains(mob.getUUID()),"An alive mob intersecting a real part blocks flight");
                    mob.setHealth(0);
                    c.assertFalse(mob.isAlive(),"The native health update makes the collision fixture dead before the predicate check");
                    c.assertFalse(check(c,plane,at,true,1).candidates.contains(mob.getUUID()),"A dead mob keeps the existing native exclusion");
                    mob.discard();
                    c.assertTrue(spectator.isSpectator(),"The native spectator exclusion is exercised by an actual spectator");
                    c.assertFalse(check(c,plane,at,true,1).candidates.contains(spectator.getUUID()),"A native spectator can overlap without blocking the plane");
                    spectator.setGameMode(GameType.SURVIVAL);
                    c.runAfterDelay(1,()->{
                        try {
                            c.assertValueEqual(spectator.gameMode(),GameType.SURVIVAL,"The real OP4 bystander remains in Survival after the native transition tick");
                            c.assertTrue(check(c,plane,at,false,1).candidates.contains(spectator.getUUID()),"The same player blocks the same part after leaving spectator mode");
                            c.succeed();
                        } finally {mob.discard();studio.cleanup(spectator);studio.cleanup(p);tracking.close();}
                    });
                    nextScheduled=true;
                } finally {if(!nextScheduled){mob.discard();studio.cleanup(spectator);studio.cleanup(p);tracking.close();}}
            });
            scheduled=true;
        } finally {if(!scheduled){if(mob!=null)mob.discard();if(other!=null)studio.cleanup(other);studio.cleanup(p);tracking.close();}}
    }

    @GameTest(maxTicks=40) public void rotatedWingsUseTheirOwnExactNativeCollisionBoxes(GameTestHelper c){
        var studio=new CreativeStudioGameTests();var p=studio.player(c,"plane-rotation");p.setYRot(0);
        var tracking=new TrackingTickets(p);
        var at=center(p);var plane=new RcPlanes.Plane(p,at);var mob=EntityTypes.COW.create(p.level(),EntitySpawnReason.COMMAND);
        boolean scheduled=false;
        try {
            c.assertTrue(mob!=null,"The passive living fixture can be created in the native Peaceful test world");
            mob.setNoAi(true);mob.setNoGravity(true);mob.setPos(at.add(0,0,2));
            c.assertTrue(p.level().addFreshEntity(mob),"The rotation fixture is a real registered living mob");
            c.runAfterDelay(6,()->{
                boolean nextScheduled=false;
                try {
                    for(var offset:RcPlanes.OFFSETS)c.assertFalse(mob.getBoundingBox().intersects(box(at,offset,0)),"Unrotated parts leave the fixture clear");
                    c.assertTrue(p.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,mob.getBoundingBox(),entity->entity==mob).contains(mob),
                        "The native section query tracks the rotation fixture before testing the plane");
                    check(c,plane,at,true,1);
                    p.setYRot(90);
                    c.assertTrue(mob.getBoundingBox().intersects(box(at,new Vec3(2,0,0),90)),"The real ninety-degree wing rotation reaches the fixture");
                    check(c,plane,at,false,1);
                    p.setYRot(45);
                    mob.setPos(RcPlanes.position(at,RcPlanes.OFFSETS.getFirst(),45));
                    c.runAfterDelay(1,()->{
                        try {check(c,plane,at,false,1);c.succeed();}
                        finally {mob.discard();studio.cleanup(p);tracking.close();}
                    });
                    nextScheduled=true;
                } finally {if(!nextScheduled){mob.discard();studio.cleanup(p);tracking.close();}}
            });
            scheduled=true;
        } finally {if(!scheduled){if(mob!=null)mob.discard();studio.cleanup(p);tracking.close();}}
    }

    @GameTest public void blockedBorderBoundsAndUnloadedAirNeverQueryEntities(GameTestHelper c){
        var studio=new CreativeStudioGameTests();var p=studio.player(c,"plane-world-guard");p.setYRot(0);
        var at=center(p);var plane=new RcPlanes.Plane(p,at);var world=p.level();
        var obstacle=BlockPos.containing(RcPlanes.position(at,RcPlanes.OFFSETS.getLast(),0));var original=world.getBlockState(obstacle);
        var border=world.getWorldBorder();double x=border.getCenterX(),z=border.getCenterZ(),size=border.getSize();
        try {
            world.setBlock(obstacle,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            check(c,plane,at,false,0);
            c.assertTrue(world.getBlockState(obstacle).is(Blocks.STONE),"Collision checks cannot break or replace the obstruction");
            world.setBlock(obstacle,original,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            border.setCenter(at.x,at.z);border.setSize(1);
            check(c,plane,at,false,0);
            border.setCenter(x,z);border.setSize(size);
            check(c,plane,new Vec3(at.x,world.getMaxY()+2,at.z),false,0);
            var unloaded=new Vec3(1000000,at.y,1000000);
            c.assertFalse(world.isLoaded(BlockPos.containing(unloaded)),"The remote-air fixture has no loaded chunk");
            int loaded=world.getChunkSource().getLoadedChunksCount();
            check(c,plane,unloaded,false,0);
            c.assertValueEqual(world.getChunkSource().getLoadedChunksCount(),loaded,"Refusing unloaded air never requests a chunk");
        } finally {border.setCenter(x,z);border.setSize(size);world.setBlock(obstacle,original,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);studio.cleanup(p);}
        c.succeed();
    }

    @GameTest(maxTicks=40) public void obstacleWarningIsImmediateBoundedAndResetsAfterClearance(GameTestHelper c){
        var studio=new CreativeStudioGameTests();var p=studio.player(c,"plane-warning");p.setYRot(0);p.setXRot(0);
        var world=p.level();RcPlanes.Plane plane=null;BlockPos obstacle=null;net.minecraft.world.level.block.state.BlockState original=null;
        boolean scheduled=false;
        try {
            c.assertValueEqual(RcPlanes.launch(p),1,"The warning fixture uses a real flying native assembly");
            plane=RcPlanes.ACTIVE.get(p.getUUID());var active=plane;
            obstacle=plane.parts.getFirst().blockPosition();var blocked=obstacle;
            original=world.getBlockState(obstacle);var previous=original;
            var sample=RcPlanePerformanceProbe.begin(p);
            world.setBlock(obstacle,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            RcPlanes.tick();
            c.assertValueEqual(sample.noticeTicks.size(),1,"The first real obstruction sends its warning immediately");
            c.assertTrue(active.hover,"The obstruction still enables native hover");
            for(int i=0;i<12;i++)RcPlanes.tick();
            c.assertValueEqual(sample.noticeTicks.size(),1,"Repeated checks in the same server tick do not resend an identical warning");
            c.assertValueEqual(sample.queries,0,"A solid obstruction needs no living-entity query");
            c.runAfterDelay(12,()->{
                try {
                    c.assertValueEqual(sample.noticeTicks.size(),2,"Normal server ticks refresh the persistent warning once after ten ticks");
                    c.assertValueEqual(sample.noticeTicks.get(1)-sample.noticeTicks.get(0),10,"The actual native notices are ten game ticks apart");
                    world.setBlock(blocked,previous,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);RcPlanes.tick();
                    c.assertFalse(active.obstacleWarning,"A clear candidate resets the repeated-warning state");
                    c.assertValueEqual(sample.queries,1,"The repaired candidate performs one real native entity query");
                    world.setBlock(blocked,Blocks.STONE.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);RcPlanes.tick();
                    c.assertValueEqual(sample.noticeTicks.size(),3,"A new obstruction warns immediately even before the previous refresh interval");
                    p.setGameMode(GameType.SURVIVAL);RcPlanes.tick();
                    c.assertFalse(RcPlanes.ACTIVE.containsKey(p.getUUID()),"Permission loss still recalls the plane before collision work");
                    c.assertTrue(active.parts.stream().allMatch(net.minecraft.world.entity.item.FallingBlockEntity::isRemoved),"Revocation removes every native part");
                    c.assertValueEqual(sample.noticeTicks.size(),3,"Revocation does not emit another obstruction warning");
                    c.assertValueEqual(sample.queries,1,"Revocation cannot perform another world query");
                    c.succeed();
                } finally {RcPlanePerformanceProbe.end(p);world.setBlock(blocked,previous,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);studio.cleanup(p);}
            });
            scheduled=true;
        } finally {
            if(!scheduled){RcPlanePerformanceProbe.end(p);if(obstacle!=null&&original!=null)world.setBlock(obstacle,original,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);studio.cleanup(p);}
        }
    }
}

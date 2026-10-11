package dev.convergence;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Runs in a fresh exclusive group: every border/terrain scope closes before its case returns. */
public class ModeDestinationGameTests {
    private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;

    private static final class BorderScope implements AutoCloseable {
        final net.minecraft.world.level.border.WorldBorder border;
        final double x,z,size;
        BorderScope(ServerLevel world){border=world.getWorldBorder();x=border.getCenterX();z=border.getCenterZ();size=border.getSize();}
        @Override public void close(){border.setCenter(x,z);border.setSize(size);}
    }
    private static void warmHalo(GameTestHelper c,ServerLevel world,BlockPos feet){
        // Preload a small superset of the native collision halo before any observations.
        int cx=feet.getX()>>4,cz=feet.getZ()>>4;
        for(int x=cx-1;x<=cx+1;x++)for(int z=cz-1;z<=cz+1;z++){
            var chunk=world.getChunk(x,z);
            c.assertTrue(world.getChunkSource().getChunkNow(x,z)==chunk,"The fixture has a published native FULL collision chunk");
        }
    }
    private static final class WaterGrid implements AutoCloseable {
        final ServerLevel world;
        final BorderScope border;
        final Map<BlockPos,BlockState> original=new LinkedHashMap<>();
        final int y;
        WaterGrid(GameTestHelper c){
            world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.CREATIVE);border=new BorderScope(world);
            y=world.getMaxY()-3;
            try {
                // [-16,16] columns and BlockCollisions' one-block margin occupy these 16 chunks.
                for(int x=-2;x<=1;x++)for(int z=-2;z<=1;z++){
                    var chunk=world.getChunk(x,z);
                    c.assertTrue(world.getChunkSource().getChunkNow(x,z)==chunk,"Every water-grid FULL halo chunk is actually complete");
                }
                for(int x=-16;x<=16;x+=4)for(int z=-16;z<=16;z+=4)for(int dy=0;dy<=2;dy++){
                    var pos=new BlockPos(x,y+dy,z);original.put(pos,world.getBlockState(pos));
                }
                border.border.setCenter(0,0);border.border.setSize(33);
                for(var pos:original.keySet())world.setBlock(pos,pos.getY()==y?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
                for(int x=-16;x<=16;x+=4)for(int z=-16;z<=16;z+=4)
                    c.assertValueEqual(world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),y+1,"The real native heightmap reaches the owned water floor");
            } catch(RuntimeException|Error failure){close();throw failure;}
        }
        void assertWaterUnchanged(GameTestHelper c){
            for(int x=-16;x<=16;x+=4)for(int z=-16;z<=16;z+=4){
                var floor=new BlockPos(x,y,z);
                c.assertTrue(world.getBlockState(floor).is(Blocks.WATER),"No fallback platform replaces a rejected water column");
                c.assertTrue(world.getBlockState(floor.above()).isAir()&&world.getBlockState(floor.above(2)).isAir(),"Rejected search never builds in headspace");
            }
        }
        @Override public void close(){
            try {for(var entry:original.entrySet())world.setBlock(entry.getKey(),entry.getValue(),FLAGS);}
            finally {border.close();}
        }
    }

    @GameTest public void frozenCourseCannotFallBackWhileUnavailableThenRecoverUnderAnotherFootprint(GameTestHelper c){
        var server=c.getLevel().getServer();var world=GameModes.world(server,GameModes.Mode.MINIGAMES);
        c.assertTrue(ModeMaps.available("sprint")&&ModeMaps.available("parkour"),"Both actual built-in courses begin available");
        for(var id:java.util.List.of("sprint","parkour"))warmHalo(c,world,ModeMaps.start(id));
        try(var destination=new ModeDestinations(server,GameModes.Mode.MINIGAMES,"sprint",null,null)){
            ModeMaps.AVAILABLE.remove("sprint");
            var first=destination.poll();ModeMaps.AVAILABLE.add("sprint");
            c.assertValueEqual(first,ModeDestinations.Status.FAILED,"A frozen unavailable course refuses another available course's footprint");
            c.assertValueEqual(destination.poll(),ModeDestinations.Status.FAILED,"Restoring availability does not revive a refused request");
            c.assertTrue(destination.selected()==null&&destination.failure()!=null,"No fallback place leaks from the refused selector");
        } finally {ModeMaps.AVAILABLE.add("sprint");}
        try(var fresh=new ModeDestinations(server,GameModes.Mode.MINIGAMES,"sprint",null,null)){
            c.assertValueEqual(fresh.poll(),ModeDestinations.Status.READY,"A fresh request recovers after the exact course is restored");
            c.assertValueEqual(fresh.selected(),GameModes.mapPlace(server,GameModes.Mode.MINIGAMES,"sprint"),"Recovery uses the requested course's own retained footprint");
        }
        c.succeed();
    }

    @GameTest public void requestedHubHallRevalidatesBorderFloorAndHeadBeforePlayerMutation(GameTestHelper c){
        var server=c.getLevel().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        var place=LobbyServer.place(server,"adventure");var feet=BlockPos.containing(place.x(),place.y(),place.z());
        warmHalo(c,world,feet);var floor=world.getBlockState(feet.below());var head=world.getBlockState(feet.above());
        var p=new ModeGameTests().player(c,"selector-hub-state");
        p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD,3));
        var profiles=new net.minecraft.nbt.CompoundTag();var saved=new net.minecraft.nbt.CompoundTag();saved.putString("proof","existing mode profile");
        profiles.put("CREATIVE",saved);GameModes.state(p).put("profiles",profiles);
        var before=GameModes.state(p).copy();var origin=CommunityServer.Place.of(p);var menu=p.containerMenu;
        try {
            // Each variant owns a new real READY selector and restores its mutations synchronously.
            for(int change=0;change<3;change++)try(var border=new BorderScope(world);
                var destination=new ModeDestinations(server,GameModes.Mode.HUB,"adventure",null,null)){
                c.assertValueEqual(destination.poll(),ModeDestinations.Status.READY,"The built requested hall has genuine ready native terrain");
                c.assertValueEqual(destination.selected(),place,"The requested Adventure hall is selected directly, without a Main Hub detour");
                c.assertFalse(destination.defaultSearchResult(),"A requested hall is not an origin-search cache result");
                if(change==0){border.border.setCenter(10_000,10_000);border.border.setSize(1);}
                else if(change==1)world.setBlock(feet.below(),Blocks.AIR.defaultBlockState(),FLAGS);
                else world.setBlock(feet.above(),Blocks.STONE.defaultBlockState(),FLAGS);
                c.assertValueEqual(destination.poll(),ModeDestinations.Status.FAILED,"A formerly ready fixed hall refuses changed border/floor/headspace");
                c.assertTrue(destination.selected()==null&&destination.failure()!=null,"Failure clears the stale selected destination");
                c.assertValueEqual(GameModes.state(p),before,"Preparation never captures, removes or restores player profiles");
                c.assertValueEqual(CommunityServer.Place.of(p),origin,"Preparation never teleports the player");
                c.assertTrue(p.containerMenu==menu,"Preparation never closes the current menu");
                c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND)&&p.getInventory().getItem(0).getCount()==7,"Inventory remains unchanged");
                c.assertTrue(p.inventoryMenu.getCarried().is(Items.EMERALD)&&p.inventoryMenu.getCarried().getCount()==3,"The carried cursor is not returned early");
            } finally {world.setBlock(feet.below(),floor,FLAGS);world.setBlock(feet.above(),head,FLAGS);}
        } finally {
            world.setBlock(feet.below(),floor,FLAGS);world.setBlock(feet.above(),head,FLAGS);
            p.inventoryMenu.setCarried(ItemStack.EMPTY);server.getPlayerList().remove(p);
        }
        c.succeed();
    }

    @GameTest public void loadedWaterGridUsesSixtyFourColumnsPerPollThenPreservesNativeHeightAndOrder(GameTestHelper c){
        var server=c.getLevel().getServer();
        try(var grid=new WaterGrid(c)){
            try(var destination=new ModeDestinations(server,GameModes.Mode.CREATIVE,null,null,null)){
                try(var sample=ModeDestinationHeightmapProbe.begin(grid.world)){
                    c.assertValueEqual(destination.poll(),ModeDestinations.Status.PENDING,"An all-loaded search stops after its real 64-column budget");
                    c.assertValueEqual(sample.columns.size(),64,"First poll performs exactly 64 actual native heightmap reads");
                    c.assertValueEqual(sample.columns,GameModes.DEFAULT_SPAWN_CANDIDATES.subList(0,64),"Actual first-poll reads retain the nearest-ring order");
                }
                c.assertTrue(destination.selected()==null,"A pending flooded search exposes no fallback destination");
                try(var sample=ModeDestinationHeightmapProbe.begin(grid.world)){
                    c.assertValueEqual(destination.poll(),ModeDestinations.Status.FAILED,"The remaining 17 eligible columns exhaust the bounded border");
                    c.assertValueEqual(sample.columns.size(),17,"Second poll reads the remaining 17 real native columns only");
                    c.assertValueEqual(sample.columns,GameModes.DEFAULT_SPAWN_CANDIDATES.subList(64,81),"Actual second-poll reads continue the original ring order");
                }
                c.assertTrue(destination.selected()==null&&destination.failure()!=null,"An exhausted search fails instead of inventing a fallback platform");
                grid.assertWaterUnchanged(c);
            }
            // A genuine safe floor at the first tied ring candidate distinguishes feet Y from floor Y.
            var firstSafe=new BlockPos(-4,grid.y,-4);grid.world.setBlock(firstSafe,Blocks.STONE.defaultBlockState(),FLAGS);
            try(var destination=new ModeDestinations(server,GameModes.Mode.CREATIVE,null,null,null);
                var sample=ModeDestinationHeightmapProbe.begin(grid.world)){
                c.assertValueEqual(destination.poll(),ModeDestinations.Status.READY,"The actual safe native ring candidate is found");
                c.assertValueEqual(sample.columns,java.util.List.of(BlockPos.ZERO,new BlockPos(-4,0,-4)),"The origin is rejected before the original first ring tie is selected");
                var selected=destination.selected();
                c.assertValueEqual(selected.x(),-3.5,"The original first ring candidate's centered X is retained");
                c.assertValueEqual(selected.z(),-3.5,"The original first ring candidate's centered Z is retained");
                c.assertValueEqual(selected.y(),(double)grid.y+1,"LevelChunk's top occupied Y is converted into the native feet height with +1");
                c.assertTrue(CommunityServer.safe(grid.world,selected),"The selected +1 height is actually safe under native collision checks");
                c.assertTrue(destination.defaultSearchResult(),"Only the native origin-search result is marked cacheable");
            }
        }
        c.succeed();
    }

    @GameTest public void actualRetainedCollisionFootprintsStayWithinOneHundredOriginChunks(GameTestHelper c) throws ReflectiveOperationException {
        var server=c.getLevel().getServer();var world=GameModes.world(server,GameModes.Mode.CREATIVE);
        var retain=ModeDestinations.class.getDeclaredMethod("retain",ServerLevel.class,double.class,double.class);retain.setAccessible(true);
        var field=ModeDestinations.class.getDeclaredField("leases");field.setAccessible(true);
        try(var destination=new ModeDestinations(server,GameModes.Mode.CREATIVE,null,null,null)){
            for(var column:GameModes.DEFAULT_SPAWN_CANDIDATES)retain.invoke(destination,world,column.getX()+.5,column.getZ()+.5);
            var leases=(Map<?,?>)field.get(destination);
            c.assertValueEqual(GameModes.DEFAULT_SPAWN_CANDIDATES.size(),1089,"The structural bound covers every real origin column");
            c.assertValueEqual(leases.size(),100,"Actual retain deduplicates all native collision footprints into exactly 100 origin chunks");
            var chunks=new HashSet<ChunkPos>();
            for(var key:leases.keySet()){
                var x=key.getClass().getDeclaredMethod("x");var z=key.getClass().getDeclaredMethod("z");x.setAccessible(true);z.setAccessible(true);
                chunks.add(new ChunkPos((int)x.invoke(key),(int)z.invoke(key)));
            }
            for(int x=-5;x<=4;x++)for(int z=-5;z<=4;z++)c.assertTrue(chunks.contains(new ChunkPos(x,z)),"The actual native halo includes the required edge chunk");
            retain.invoke(destination,world,128.5,128.5);
            c.assertValueEqual(leases.size(),104,"A distant saved location adds at most its four native collision chunks");
            // This measures the actual retain footprint, not loading or fabricated READY results.
        }
        c.succeed();
    }
}

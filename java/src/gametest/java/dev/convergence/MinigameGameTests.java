package dev.convergence;

import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class MinigameGameTests {
    private ServerPlayerEntity player(TestContext c,String name,String map) {
        var p=new ModeGameTests().player(c,name);
        GameModes.switchNow(p,GameModes.Mode.MINIGAMES,map);
        p.setNoGravity(true);return p;
    }
    private void cleanup(ServerPlayerEntity p) {
        ModeMaps.RUNS.remove(p.getUuid());
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        p.getEntityWorld().getServer().getPlayerManager().remove(p);
    }
    private void descendGate(ServerPlayerEntity p,BlockPos gate,int tick) {
        p.setOnGround(false);p.setPosition(gate.getX()+.5,gate.getY()+1,gate.getZ()+.5);ModeMaps.tick(p,tick);
        p.setPosition(gate.getX()+.5,gate.getY()-1,gate.getZ()+.5);ModeMaps.tick(p,tick+1);
    }
    private void completeDropper(ServerPlayerEntity p,int elapsed) {
        var spec=ModeMaps.MAPS.get("dropper");var run=ModeMaps.RUNS.get(p.getUuid());
        for(int i=1;i<=3;i++)descendGate(p,spec.points().get(i),run.startedTick+i*5);
        p.setOnGround(false);p.setPosition(Vec3d.ofBottomCenter(spec.points().getLast()));ModeMaps.tick(p,run.startedTick+elapsed);
    }
    @GameTest public void newMinigamesHaveSafeStartsAndDistinctPhysicalCourses(TestContext c) {
        var p=player(c,"new-games","dropper");
        try {
            c.assertEquals(ModeMaps.MAPS.get("dropper").kind(),ModeMaps.Kind.DROPPER,"Dropper has fall mechanics");
            c.assertEquals(ModeMaps.MAPS.get("redlight").kind(),ModeMaps.Kind.REDLIGHT,"Red Light Run has timed movement mechanics");
            c.assertTrue(CommunityServer.safe(p.getEntityWorld(),CommunityServer.Place.of(p)),"Dropper starts on dry solid ground");
            var world=p.getEntityWorld();
            c.assertTrue(world.getBlockState(new BlockPos(165,100,0)).isAir(),"Dropper first hole is open");
            c.assertTrue(world.getBlockState(new BlockPos(155,100,0)).isOf(Blocks.YELLOW_CONCRETE),"First colored obstacle exists");
            c.assertTrue(world.getBlockState(new BlockPos(161,42,-4)).isOf(Blocks.WATER),"Catch pool is nine blocks wide");
            ModeMaps.begin(p,"redlight");
            c.assertTrue(CommunityServer.safe(world,CommunityServer.Place.of(p)),"Red Light Run starts on dry solid ground");
            c.assertTrue(world.getBlockState(new BlockPos(225,82,-4)).isOf(Blocks.LIME_CONCRETE),"Race has visible green lights");
            c.assertTrue(world.getBlockState(new BlockPos(225,84,-4)).isOf(Blocks.RED_CONCRETE),"Race has visible red lights");
            for(String id:new String[]{"parkour","sprint","ruins","maze"})c.assertEquals(ModeMaps.MAPS.get(id).kind(),ModeMaps.Kind.CHECKPOINTS,"Existing course preserved: "+id);
            var root=c.getWorld().getServer().getCommandManager().getDispatcher().getRoot();
            c.assertTrue(root.getChild("minigame").getChild("dropper")!=null,"Dropper command registered");
            c.assertTrue(root.getChild("minigame").getChild("redlight")!=null,"Red Light Run command registered");
            c.assertTrue(root.getChild("retry").getChild("redlight")!=null,"Replay command registered");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void dropperRequiresAllHolesThenWaterAndKeepsBestOnReplay(TestContext c) {
        var p=player(c,"dropper-score","dropper");
        try {
            p.setPosition(Vec3d.ofBottomCenter(ModeMaps.MAPS.get("dropper").points().getLast()));ModeMaps.tick(p);
            c.assertTrue(ModeMaps.RUNS.containsKey(p.getUuid()),"Skipping directly to water cannot complete");
            c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).next,1,"Skipped drop resets all gates");
            completeDropper(p,100);
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"All three holes plus water completes the drop");
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("dropper",-1),5000L,"Personal best records elapsed server ticks");
            ModeMaps.begin(p,"dropper");c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).next,1,"Replay resets progress");
            completeDropper(p,200);
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("dropper",-1),5000L,"Slower replay does not replace best");
            var write=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());p.writeData(write);
            var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"dropper-reload");
            var restored=new ServerPlayerEntity(c.getWorld().getServer(),p.getEntityWorld(),profile,p.getClientOptions());
            restored.readData(NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),write.getNbt()));
            c.assertEquals(GameModes.state(restored).getCompoundOrEmpty("scores").getLong("dropper",-1),5000L,"Best survives vanilla player NBT reload");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void dropperObstacleAndOutOfBoundsRestartWithoutAwardingScore(TestContext c) {
        var p=player(c,"dropper-fail","dropper");
        try {
            p.setPosition(155.5,101,.5);p.setOnGround(true);ModeMaps.tick(p);
            c.assertTrue(p.getEntityPos().squaredDistanceTo(Vec3d.ofBottomCenter(ModeMaps.start("dropper")))<.01,"Landing on solid obstacle returns to launch deck");
            c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).next,1,"Obstacle clears progress");
            p.setPosition(180,30,0);ModeMaps.tick(p);
            c.assertTrue(p.getEntityPos().squaredDistanceTo(Vec3d.ofBottomCenter(ModeMaps.start("dropper")))<.01,"Leaving the shaft returns to launch deck");
            c.assertFalse(GameModes.state(p).getCompoundOrEmpty("scores").contains("dropper"),"Failed drops cannot award a score");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest(maxTicks=110) public void dropperNativeCollisionFallPassesHolesAndLandsInWater(TestContext c) {
        var p=player(c,"dropper-physics","dropper");
        p.setPosition(164.5,121,1.5);p.setOnGround(false);
        double[] speed={0};boolean[] done={false};
        // The test player has no real client. Apply the vanilla falling acceleration
        // through Entity.move so the server's actual block collision shapes govern it.
        c.runAtEveryTick(()->{
            if(done[0])return;
            try {
                speed[0]=(speed[0]+.08)*.98;
                p.move(MovementType.SELF,new Vec3d(0,-speed[0],0));ModeMaps.tick(p);
                if(!ModeMaps.RUNS.containsKey(p.getUuid())) {
                    c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").contains("dropper"),"A collision-checked fall through the openings reaches the water finish");
                    c.assertTrue(p.getY()<44,"Finish is in the lower water pool");
                    done[0]=true;cleanup(p);c.complete();
                }
            } catch(RuntimeException failure) {done[0]=true;cleanup(p);throw failure;}
        });
    }
    @GameTest public void redLightGraceAllowsReactionButCumulativeMovementRestarts(TestContext c) {
        var p=player(c,"redlight-grace","redlight");
        try {
            var run=ModeMaps.RUNS.get(p.getUuid());int red=run.startedTick+ModeMaps.GREEN_TICKS;
            p.setPosition(p.getX()+.5,p.getY(),p.getZ());ModeMaps.tick(p,red);
            c.assertTrue(ModeMaps.RUNS.get(p.getUuid())==run,"Movement during the 200 ms red transition grace is allowed");
            p.setPosition(p.getX()+.5,p.getY(),p.getZ());ModeMaps.tick(p,red+ModeMaps.RED_GRACE_TICKS-1);
            c.assertTrue(ModeMaps.RUNS.get(p.getUuid())==run,"Grace lasts four ticks");
            p.setPosition(p.getX()+.1,p.getY(),p.getZ());ModeMaps.tick(p,red+ModeMaps.RED_GRACE_TICKS);
            c.assertTrue(ModeMaps.RUNS.get(p.getUuid())==run,"Small final drift is tolerated");
            p.setPosition(p.getX()+.1,p.getY(),p.getZ());ModeMaps.tick(p,red+ModeMaps.RED_GRACE_TICKS+1);
            c.assertTrue(ModeMaps.RUNS.get(p.getUuid())!=run,"Accumulated movement cannot evade the red phase");
            c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).next,1,"Red violation resets checkpoint progress");
            c.assertTrue(p.getEntityPos().squaredDistanceTo(Vec3d.ofBottomCenter(ModeMaps.start("redlight")))<.01,"Red violation returns to start");
            c.assertFalse(GameModes.state(p).getCompoundOrEmpty("scores").contains("redlight"),"Violation does not award a score");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void redLightOrderedFinishStopsOnRedAndReplaysWithoutLosingBest(TestContext c) {
        var p=player(c,"redlight-score","redlight");
        try {
            var spec=ModeMaps.MAPS.get("redlight");var run=ModeMaps.RUNS.get(p.getUuid());
            p.setPosition(Vec3d.ofBottomCenter(spec.points().getLast()));p.setOnGround(true);ModeMaps.tick(p,run.startedTick+1);
            c.assertEquals(run.next,1,"Cannot skip straight to race finish");
            for(int i=1;i<spec.points().size();i++){p.setPosition(Vec3d.ofBottomCenter(spec.points().get(i)));ModeMaps.tick(p,run.startedTick+i);}
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"Ordered route completes during green");
            long best=GameModes.state(p).getCompoundOrEmpty("scores").getLong("redlight",-1);
            c.assertEquals(best,200L,"Race time stored");
            ModeMaps.begin(p,"redlight");run=ModeMaps.RUNS.get(p.getUuid());
            p.setPosition(Vec3d.ofBottomCenter(spec.points().get(1)));p.setOnGround(true);
            ModeMaps.tick(p,run.startedTick+ModeMaps.GREEN_TICKS);
            c.assertEquals(run.next,1,"Red phase never advances checkpoints even within transition grace");
            ModeMaps.tick(p,run.startedTick+ModeMaps.GREEN_TICKS+ModeMaps.RED_TICKS);
            c.assertEquals(run.next,2,"Standing at the checkpoint advances when green returns");
            for(int i=2;i<spec.points().size();i++){p.setPosition(Vec3d.ofBottomCenter(spec.points().get(i)));ModeMaps.tick(p,run.startedTick+120+i);}
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"Second run completes");
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("redlight",-1),best,"Slower race preserves best");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void minigamesProvideNoGearAndCannotLeakModeInventories(TestContext c) {
        var p=new ModeGameTests().player(c,"minigame-items");
        try {
            p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,3));
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"dropper");
            c.assertTrue(p.getInventory().isEmpty(),"Dropper needs and grants no temporary gear");
            p.getInventory().setStack(0,new ItemStack(Items.GOLD_INGOT,7));
            ModeMaps.begin(p,"redlight");c.assertEquals(p.getInventory().getStack(0).getCount(),7,"Race does not replace or grant equipment");
            GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            c.assertTrue(p.getInventory().getStack(0).isOf(Items.DIAMOND),"Survival inventory restored");
            c.assertEquals(p.getInventory().getStack(0).getCount(),3,"Original survival stack preserved");
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"Leaving minigames clears run state");
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"redlight");
            c.assertTrue(p.getInventory().getStack(0).isOf(Items.GOLD_INGOT),"Minigame items remain in their own profile");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void minigameGenerationAcceptsLegacyMarkersAndPreservesExistingBuilds(TestContext c) {
        try {
            var file=Files.createTempDirectory("infinity-map-marker-").resolve("maps.json");
            Files.writeString(file,"{\"version\":1,\"maps\":[\"parkour\",\"sprint\",\"ruins\",\"maze\"]}");
            var built=ModeMaps.builtMaps(file);
            c.assertEquals(built.size(),4,"Legacy marker reads all original courses");
            c.assertFalse(built.contains("dropper"),"Legacy marker leaves new dropper eligible for generation");
            c.assertFalse(built.contains("redlight"),"Legacy marker leaves new race eligible for generation");
            var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.MINIGAMES);var at=ModeMaps.start("parkour").down();var old=world.getBlockState(at);
            try {world.setBlockState(at,Blocks.DIAMOND_BLOCK.getDefaultState());ModeMaps.build(c.getWorld().getServer());c.assertTrue(world.getBlockState(at).isOf(Blocks.DIAMOND_BLOCK),"Repeated generation does not rebuild existing courses");}
            finally {world.setBlockState(at,old);}
            var occupied=c.getAbsolutePos(new BlockPos(2,5,2));c.getWorld().setBlockState(occupied,Blocks.CHEST.getDefaultState());boolean refused=false;
            try {ModeMaps.requireEmpty(c.getWorld(),occupied,occupied);}catch(IllegalStateException expected){refused=true;}
            c.assertTrue(refused,"New course preflight refuses occupied terrain");c.assertTrue(c.getWorld().getBlockState(occupied).isOf(Blocks.CHEST),"Occupied block is preserved");
            Files.writeString(file,"{corrupt");boolean invalid=false;try{ModeMaps.builtMaps(file);}catch(IllegalStateException expected){invalid=true;}
            c.assertTrue(invalid,"Corrupt generation marker is reported");c.assertEquals(Files.readString(file),"{corrupt","Corrupt marker remains available for recovery");
        } catch(java.io.IOException e){throw new RuntimeException(e);}c.complete();
    }
    @GameTest public void publicRecordsBackfillPersonalNbtAndPersistPerMap(TestContext c) {
        var p=player(c,"records-owner","parkour");
        try {
            var scores=GameModes.state(p).getCompoundOrEmpty("scores");
            scores.putLong("parkour",3450);scores.putLong("redlight",2200);GameModes.state(p).put("scores",scores);
            MinigameRecords.sync(p);
            var board=MinigameRecords.get(c.getWorld().getServer());
            c.assertEquals(board.boards.get("parkour").get(p.getUuid()).millis(),3450L,"Existing personal best is indexed without replay");
            c.assertEquals(board.boards.get("redlight").get(p.getUuid()).millis(),2200L,"Second map has its own time");
            c.assertFalse(board.boards.get("dropper").containsKey(p.getUuid()),"Unfinished maps stay absent");
            c.assertTrue(Files.exists(board.file),"Public index is persisted beside the world");
            var reloaded=new MinigameRecords(board.file);
            c.assertEquals(reloaded.boards.get("parkour").get(p.getUuid()).millis(),3450L,"Indexed record survives reload");
            scores.putLong("parkour",3100);MinigameRecords.sync(p);
            c.assertEquals(new MinigameRecords(board.file).boards.get("parkour").get(p.getUuid()).millis(),3100L,"New best updates the durable index");
            var root=c.getWorld().getServer().getCommandManager().getDispatcher().getRoot();
            c.assertTrue(root.getChild("best")!=null,"Personal best command registered");
            for(String id:MinigameRecords.MAP_IDS)c.assertTrue(root.getChild("leaderboard").getChild(id)!=null,"Leaderboard command for "+id);
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void leaderboardSortsTiesAndRejectsCorruptDataWithoutOverwriting(TestContext c) {
        try {
            var path=Files.createTempDirectory("infinity-records-").resolve("records.json");
            var records=new MinigameRecords(path);
            var first=new MinigameRecords.Score(UUID.randomUUID(),"Zephyr",1900);
            var second=new MinigameRecords.Score(UUID.randomUUID(),"Alpha",1900);
            var third=new MinigameRecords.Score(UUID.randomUUID(),"Beta",2200);
            records.upsert("sprint",third);records.upsert("sprint",first);records.upsert("sprint",second);records.save();
            var restored=new MinigameRecords(path);
            var ordered=restored.ordered("sprint");
            c.assertEquals(ordered.get(0).name(),"Alpha","Equal times sort deterministically by name");
            c.assertEquals(ordered.get(1).name(),"Zephyr","Other tied time follows without changing either value");
            c.assertEquals(ordered.get(2).millis(),2200L,"Slower time follows tied leaders");
            c.assertEquals(MinigameRecords.rankAt(ordered,0),1,"First tied player is rank one");
            c.assertEquals(MinigameRecords.rankAt(ordered,1),1,"Second tied player shares rank one");
            c.assertEquals(MinigameRecords.rankAt(ordered,2),3,"Rank after the tie skips rank two");
            c.assertFalse(restored.upsert("sprint",new MinigameRecords.Score(UUID.randomUUID(),"Invalid",0)),"Zero time cannot enter records");
            Files.writeString(path,"{bad json");boolean refused=false;
            try {new MinigameRecords(path);}catch(IllegalStateException expected){refused=true;}
            c.assertTrue(refused,"Corrupt records are reported");
            c.assertEquals(Files.readString(path),"{bad json","Corrupt original is preserved for recovery");
        } catch(java.io.IOException e){throw new RuntimeException(e);}c.complete();
    }
}

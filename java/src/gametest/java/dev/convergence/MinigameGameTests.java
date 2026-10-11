package dev.convergence;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.BlockState;
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
            ModeMaps.begin(p,"crystalhunt");
            c.assertTrue(CommunityServer.safe(world,CommunityServer.Place.of(p)),"Crystal Hunt starts on dry solid ground");
            c.assertEquals(ModeMaps.MAPS.get("crystalhunt").kind(),ModeMaps.Kind.CRYSTAL_HUNT,"Crystal Hunt has unordered collection mechanics");
            c.assertTrue(world.getBlockState(ModeMaps.start("crystalhunt").down()).isOf(Blocks.SEA_LANTERN),"Crystal Hunt start opens selector");
            c.assertTrue(world.getBlockState(ModeMaps.MAPS.get("crystalhunt").points().get(1).down().west()).isOf(Blocks.AMETHYST_BLOCK),"Crystal pads have visible amethyst surrounds");
            ModeMaps.begin(p,"colorrush");
            c.assertTrue(CommunityServer.safe(world,CommunityServer.Place.of(p)),"Color Rush starts on dry solid ground");
            c.assertEquals(ModeMaps.MAPS.get("colorrush").kind(),ModeMaps.Kind.COLOR_RUSH,"Color Rush has timed pad mechanics");
            c.assertTrue(world.getBlockState(ModeMaps.start("colorrush").down()).isOf(Blocks.SEA_LANTERN),"Color Rush start opens selector");
            c.assertTrue(world.getBlockState(ModeMaps.MAPS.get("colorrush").points().get(1).down().west()).isOf(Blocks.RED_CONCRETE),"Red pad has visible floor color");
            c.assertTrue(root.getChild("minigame").getChild("crystalhunt")!=null,"Crystal Hunt command registered");
            c.assertTrue(root.getChild("minigame").getChild("colorrush")!=null,"Color Rush command registered");
            c.assertTrue(root.getChild("retry").getChild("colorrush")!=null,"Color Rush replay registered");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void crystalHuntCollectsAnyOrderAndEachPadOnlyOnce(TestContext c) {
        var p=player(c,"crystal-score","crystalhunt");
        try {
            var points=ModeMaps.MAPS.get("crystalhunt").points();var run=ModeMaps.RUNS.get(p.getUuid());
            var first=points.get(4);
            p.setPosition(first.getX()+1.9,first.getY(),first.getZ()+1.9);p.setOnGround(true);ModeMaps.tick(p,run.startedTick+1);
            c.assertEquals(Integer.bitCount(run.foundMask),1,"The edge of any amethyst pad can be collected first");
            ModeMaps.tick(p,run.startedTick+2);
            c.assertEquals(Integer.bitCount(run.foundMask),1,"Standing on one crystal cannot collect it twice");
            int elapsed=3;
            for(int i:new int[]{2,5,1}) {p.setPosition(Vec3d.ofBottomCenter(points.get(i)));p.setOnGround(true);ModeMaps.tick(p,run.startedTick+elapsed++);}
            c.assertEquals(Integer.bitCount(run.foundMask),4,"Four distinct crystals remain a live run");
            c.assertTrue(ModeMaps.RUNS.containsKey(p.getUuid()),"Last crystal is still required");
            p.setPosition(Vec3d.ofBottomCenter(points.get(3)));p.setOnGround(true);ModeMaps.tick(p,run.startedTick+20);
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"All five pads complete the hunt");
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("crystalhunt",-1),1000L,"Hunt saves elapsed ticks");
            ModeMaps.begin(p,"crystalhunt");run=ModeMaps.RUNS.get(p.getUuid());
            c.assertEquals(run.foundMask,0,"Replay clears collected crystals");
            p.setPosition(320,81,0);ModeMaps.tick(p,run.startedTick+1);
            c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).foundMask,0,"Leaving the arena restarts the hunt");
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("crystalhunt",-1),1000L,"A failed replay preserves the best");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void colorRushRewardsFastMatchingPadsAndEnforcesPulseDeadline(TestContext c) {
        var p=player(c,"color-score","colorrush");
        try {
            var points=ModeMaps.MAPS.get("colorrush").points();var run=ModeMaps.RUNS.get(p.getUuid());
            c.assertEquals(run.colorDeadline-run.startedTick,ModeMaps.COLOR_ROUND_TICKS,"First pulse gives five seconds");
            for(int i=1;i<ModeMaps.COLOR_ROUNDS;i++)c.assertTrue(run.colors[i]!=run.colors[i-1],"Consecutive pulse colors differ");
            p.setOnGround(true);
            int wrong=(run.colors[0]+1)%4;
            p.setPosition(Vec3d.ofBottomCenter(points.get(wrong+1)));ModeMaps.tick(p,run.colorDeadline);
            c.assertTrue(ModeMaps.RUNS.get(p.getUuid())!=run,"Missing the right pad at deadline restarts the run");
            c.assertFalse(GameModes.state(p).getCompoundOrEmpty("scores").contains("colorrush"),"Wrong pad gives no score");
            run=ModeMaps.RUNS.get(p.getUuid());
            p.setPosition(Vec3d.ofBottomCenter(points.get(run.colors[0]+1)));p.setOnGround(true);
            ModeMaps.tick(p,run.colorDeadline+1);
            c.assertTrue(ModeMaps.RUNS.get(p.getUuid())!=run,"A correct pad reached after the deadline is too late");
            run=ModeMaps.RUNS.get(p.getUuid());int pulseTick=run.startedTick;
            for(int i=0;i<ModeMaps.COLOR_ROUNDS;i++) {
                var pad=points.get(run.colors[i]+1);
                p.setPosition(i==0?new Vec3d(pad.getX()+2.9,pad.getY(),pad.getZ()+2.9):Vec3d.ofBottomCenter(pad));
                p.setOnGround(true);
                pulseTick+=60;ModeMaps.tick(p,pulseTick);
                if(i<ModeMaps.COLOR_ROUNDS-1) {
                    c.assertEquals(run.next,i+2,"The whole colored floor, including its edge, advances one pulse");
                    c.assertEquals(run.colorDeadline-pulseTick,ModeMaps.COLOR_ROUND_TICKS,"Each match starts a fresh five-second limit");
                }
            }
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUuid()),"Five successful pulses complete Color Rush");
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("colorrush",-1),15000L,"Color Rush stores the player's reaction and travel time");
            ModeMaps.begin(p,"colorrush");run=ModeMaps.RUNS.get(p.getUuid());
            p.setPosition(380,81,0);ModeMaps.tick(p,run.startedTick+1);
            c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).next,1,"Leaving Color Rush resets to pulse one");
            c.assertEquals(GameModes.state(p).getCompoundOrEmpty("scores").getLong("colorrush",-1),15000L,"Failed replay preserves the best");
        } finally {cleanup(p);}c.complete();
    }
    @GameTest public void selectedMinigameSurvivesPlayerReloadAndInvalidSelectionsFallBack(TestContext c) {
        var p=player(c,"selected-course","colorrush");
        try {
            c.assertEquals(ModeMaps.selectedMap(p,GameModes.Mode.MINIGAMES),"colorrush","Entering a course saves selection");
            var write=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());p.writeData(write);
            var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),"selected-reload");
            var restored=new ServerPlayerEntity(c.getWorld().getServer(),p.getEntityWorld(),profile,p.getClientOptions());
            restored.readData(NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),write.getNbt()));
            c.assertEquals(ModeMaps.selectedMap(restored,GameModes.Mode.MINIGAMES),"colorrush","Chosen course survives player NBT reload");
            GameModes.state(restored).putString("selected_minigames_map","maze");
            c.assertEquals(ModeMaps.selectedMap(restored,GameModes.Mode.MINIGAMES),"parkour","A map from another mode falls back safely");
            GameModes.state(restored).remove("selected_minigames_map");
            c.assertEquals(ModeMaps.selectedMap(restored,GameModes.Mode.MINIGAMES),"parkour","Old profiles without a selection use the default");
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
    @GameTest public void dropperCornerOpeningCountsAsCheckpoint(TestContext c) {
        var p=player(c,"dropper-corner","dropper");
        try {
            var gate=ModeMaps.MAPS.get("dropper").points().get(1);
            var run=ModeMaps.RUNS.get(p.getUuid());
            double x=gate.getX()+2.6,z=gate.getZ()+2.6;
            p.setOnGround(false);p.setPosition(x,gate.getY()+1,z);ModeMaps.tick(p,run.startedTick+1);
            p.setPosition(x,gate.getY()-1,z);ModeMaps.tick(p,run.startedTick+2);
            c.assertEquals(run.next,2,"A fall through the square opening near its corner counts");
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
            c.assertFalse(built.contains("crystalhunt"),"Legacy marker leaves Crystal Hunt eligible for generation");
            c.assertFalse(built.contains("colorrush"),"Legacy marker leaves Color Rush eligible for generation");
            var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.MINIGAMES);var at=ModeMaps.start("parkour").down();var old=world.getBlockState(at);
            try {
                world.setBlockState(at,Blocks.DIAMOND_BLOCK.getDefaultState());
                ModeMaps.build(c.getWorld().getServer());
                c.assertTrue(world.getBlockState(at).isOf(Blocks.DIAMOND_BLOCK),"Repeated generation does not overwrite an edited course");
                c.assertFalse(ModeMaps.available("parkour"),"Conflicted course is not offered to new players");
            } finally {world.setBlockState(at,old);ModeMaps.build(c.getWorld().getServer());}
            var occupied=c.getAbsolutePos(new BlockPos(2,5,2));c.getWorld().setBlockState(occupied,Blocks.CHEST.getDefaultState());boolean refused=false;
            try {ModeMaps.requireEmpty(c.getWorld(),occupied,occupied);}catch(IllegalStateException expected){refused=true;}
            c.assertTrue(refused,"New course preflight refuses occupied terrain");c.assertTrue(c.getWorld().getBlockState(occupied).isOf(Blocks.CHEST),"Occupied block is preserved");
            Files.writeString(file,"{corrupt");boolean invalid=false;try{ModeMaps.builtMaps(file);}catch(IllegalStateException expected){invalid=true;}
            c.assertTrue(invalid,"Corrupt generation marker is reported");c.assertEquals(Files.readString(file),"{corrupt","Corrupt marker remains available for recovery");
        } catch(java.io.IOException e){throw new RuntimeException(e);}c.complete();
    }
    @GameTest public void interruptedMapIntentResumesOnlyKnownBlocksAndMissingLegacyMarkerSkipsAmbiguity(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.MINIGAMES);
        var spec=new ModeMaps.MapSpec("parkour",GameModes.Mode.MINIGAMES,"Recovery fixture",List.of(
            new BlockPos(4096,81,4096),new BlockPos(4102,81,4096)));
        var plan=ModeMaps.plan(spec);
        Map<BlockPos,BlockState> original=new HashMap<>();
        for(BlockPos cursor:BlockPos.iterate(plan.min(),plan.max())) {
            var at=cursor.toImmutable();original.put(at,world.getBlockState(at));
        }
        var absent=new ModeMaps.MapMarker(new LinkedHashSet<>(),new LinkedHashSet<>());
        var pending=new ModeMaps.MapMarker(new LinkedHashSet<>(),new LinkedHashSet<>(List.of("parkour")));
        var built=new ModeMaps.MapMarker(new LinkedHashSet<>(List.of("parkour")),new LinkedHashSet<>());
        try {
            var empty=ModeMaps.inspect(world,spec,plan);
            c.assertTrue(empty.empty(),"Untouched map volume is eligible for a new install");
            c.assertTrue(ModeMaps.mayReconcile(absent,spec,empty),"An empty missing-marker map may be installed");
            var floor=spec.points().getFirst().down();
            world.setBlockState(floor,plan.blocks.get(floor));
            var partial=ModeMaps.inspect(world,spec,plan);
            c.assertTrue(partial.conflict()==null && partial.missing()>0 && partial.occupied(),"Interrupted build has known blocks plus missing cells");
            c.assertFalse(ModeMaps.mayReconcile(absent,spec,partial),"An unmarked partial map is ambiguous and must be preserved");
            c.assertTrue(ModeMaps.mayReconcile(pending,spec,partial),"A saved install intent allows the known partial map to resume");
            c.assertTrue(ModeMaps.apply(world,plan),"Recovery fills only missing air cells");
            c.assertTrue(ModeMaps.inspect(world,spec,plan).complete(),"Resumed course matches the final plan");
            c.assertFalse(ModeMaps.apply(world,plan),"Completed course is idempotent");
            world.setBlockState(floor,Blocks.AIR.getDefaultState());
            c.assertTrue(ModeMaps.mayReconcile(built,spec,ModeMaps.inspect(world,spec,plan)),"Marker-listed course can repair a missing saved chunk cell");
            ModeMaps.apply(world,plan);
            c.assertTrue(world.getBlockState(floor).isOf(Blocks.SEA_LANTERN),"Missing course lantern is restored");
            var occupied=spec.points().getLast().up();
            world.setBlockState(occupied,Blocks.CHEST.getDefaultState());
            c.assertEquals(ModeMaps.inspect(world,spec,plan).conflict(),occupied,"Unknown host block stops recovery, even with install intent");
            c.assertFalse(ModeMaps.mayReconcile(pending,spec,ModeMaps.inspect(world,spec,plan)),"Host block cannot be overwritten by a pending install");
        } finally {original.forEach((at,state)->world.setBlockState(at,state,3));}
        c.complete();
    }
    @GameTest public void mapMarkerTracksInterruptedInstallWithoutLosingLegacyIds(TestContext c) {
        try {
            var file=Files.createTempDirectory("infinity-map-intent-").resolve("maps.json");
            ModeMaps.writeMarker(file,new LinkedHashSet<>(List.of("parkour","sprint")),new LinkedHashSet<>(List.of("dropper")));
            var restored=ModeMaps.readMarker(file);
            c.assertTrue(restored.built().containsAll(List.of("parkour","sprint")),"Completed legacy maps stay marked");
            c.assertTrue(restored.installing().contains("dropper"),"Unfinished map intent survives restart");
            c.assertFalse(ModeMaps.builtMaps(file).contains("dropper"),"Pending course cannot masquerade as completed");
            Files.writeString(file,"{\"version\":3,\"maps\":[\"parkour\"],\"installing\":[\"parkour\"]}");
            boolean rejected=false;try{ModeMaps.readMarker(file);}catch(IllegalStateException expected){rejected=true;}
            c.assertTrue(rejected,"Contradictory marker is rejected without modifying it");
        } catch(java.io.IOException error) {throw new RuntimeException(error);}
        c.complete();
    }
    @GameTest public void bothNewArenaPreflightsRefuseOccupiedBlocksWithoutChangingMarkers(TestContext c) {
        try {
            var server=c.getWorld().getServer();
            var world=GameModes.world(server,GameModes.Mode.MINIGAMES);
            var marker=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("infinity-built-in-maps.json");
            String saved=Files.readString(marker);
            var oldCourse=ModeMaps.MAPS.get("parkour").points().getLast().down();var oldBlock=world.getBlockState(oldCourse);
            for(var entry:java.util.Map.of("crystalhunt",new BlockPos(284,80,-12),"colorrush",new BlockPos(340,80,-13)).entrySet()) {
                var at=entry.getValue();var original=world.getBlockState(at);
                try {
                    world.setBlockState(at,Blocks.CHEST.getDefaultState());
                    boolean refused=false;
                    try {ModeMaps.requireNewMapEmpty(server,ModeMaps.MAPS.get(entry.getKey()));}
                    catch(IllegalStateException expected) {refused=expected.getMessage().contains("would replace existing blocks");}
                    c.assertTrue(refused,"Preflight refuses an occupied "+entry.getKey()+" build volume");
                    c.assertTrue(world.getBlockState(at).isOf(Blocks.CHEST),"Preflight leaves occupied block intact");
                    c.assertEquals(world.getBlockState(oldCourse),oldBlock,"Preflight does not rebuild the old parkour course");
                    c.assertEquals(Files.readString(marker),saved,"Preflight cannot change the generation marker");
                } finally {world.setBlockState(at,original);}
            }
        } catch(java.io.IOException e) {throw new RuntimeException(e);}c.complete();
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

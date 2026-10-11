package dev.convergence;

import java.nio.file.Files;
import java.util.*;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Small built-in maps, generated once in dedicated dimensions, never over survival builds. */
final class ModeMaps {
    enum Kind { CHECKPOINTS, DROPPER, REDLIGHT }
    record MapSpec(String id, GameModes.Mode mode, String title, List<BlockPos> points, Kind kind) {
        MapSpec(String id, GameModes.Mode mode, String title, List<BlockPos> points) { this(id, mode, title, points, Kind.CHECKPOINTS); }
    }
    static final int GREEN_TICKS = 80;
    static final int RED_TICKS = 40;
    static final int RED_GRACE_TICKS = 4;
    static final double RED_MOVE_TOLERANCE = .15;
    static final Map<String, MapSpec> MAPS = new LinkedHashMap<>();
    static {
        MAPS.put("parkour", new MapSpec("parkour", GameModes.Mode.MINIGAMES, "Sky Steps", List.of(
            new BlockPos(0,81,0), new BlockPos(6,82,0), new BlockPos(12,81,3), new BlockPos(18,82,0), new BlockPos(24,81,3), new BlockPos(30,81,0))));
        MAPS.put("sprint", new MapSpec("sprint", GameModes.Mode.MINIGAMES, "Switchback Sprint", List.of(
            new BlockPos(64,81,0), new BlockPos(70,81,10), new BlockPos(76,81,-10), new BlockPos(82,81,10), new BlockPos(88,81,-10), new BlockPos(94,81,0))));
        MAPS.put("dropper", new MapSpec("dropper", GameModes.Mode.MINIGAMES, "Prism Dropper", List.of(
            new BlockPos(160,121,0), new BlockPos(165,101,0), new BlockPos(163,81,2), new BlockPos(165,61,0), new BlockPos(165,42,0)), Kind.DROPPER));
        MAPS.put("redlight", new MapSpec("redlight", GameModes.Mode.MINIGAMES, "Red Light Run", List.of(
            new BlockPos(224,81,0), new BlockPos(231,81,0), new BlockPos(238,81,0), new BlockPos(245,81,0), new BlockPos(252,81,0)), Kind.REDLIGHT));
        MAPS.put("ruins", new MapSpec("ruins", GameModes.Mode.ADVENTURE, "The Five Seals", List.of(
            new BlockPos(0,81,0), new BlockPos(10,81,0), new BlockPos(10,81,10), new BlockPos(-10,81,10), new BlockPos(-10,81,-10), new BlockPos(10,81,-10), new BlockPos(0,81,0))));
        MAPS.put("maze", new MapSpec("maze", GameModes.Mode.ADVENTURE, "Lantern Labyrinth", List.of(
            new BlockPos(64,81,0), new BlockPos(68,81,10), new BlockPos(72,81,-10), new BlockPos(76,81,10), new BlockPos(80,81,-10), new BlockPos(86,81,0))));
    }
    static void build(MinecraftServer server) {
        var marker = server.getSavePath(WorldSavePath.ROOT).resolve("infinity-built-in-maps.json");
        Set<String> built = builtMaps(marker);
        var missing = MAPS.values().stream().filter(spec -> !built.contains(spec.id)).toList();
        if (missing.isEmpty()) return;
        // Check all new course volumes before changing any blocks. An upgrade must not
        // rebuild the old maps or silently overwrite a host's build in the new area.
        for (var spec : missing) if (spec.kind != Kind.CHECKPOINTS) {
            var world = GameModes.world(server, spec.mode);
            if (world == null) throw new IllegalStateException("Infinity dimension missing: " + spec.mode);
            BlockPos min = spec.kind == Kind.DROPPER ? new BlockPos(154,40,-9) : new BlockPos(221,80,-5);
            BlockPos max = spec.kind == Kind.DROPPER ? new BlockPos(176,123,9) : new BlockPos(255,85,5);
            requireEmpty(world, min, max);
        }
        for (var spec : missing) {
            var world = GameModes.world(server, spec.mode);
            if (world == null) throw new IllegalStateException("Infinity dimension missing: " + spec.mode + ". Enable the mod data pack and restart.");
            if (spec.kind == Kind.DROPPER) { buildDropper(world); continue; }
            if (spec.kind == Kind.REDLIGHT) { buildRedlight(world); continue; }
            int base = spec.points.getFirst().getX();
            if (!spec.id.equals("parkour")) {
                int min = spec.id.equals("ruins") ? -14 : base - 3;
                int max = spec.id.equals("ruins") ? 14 : spec.points.getLast().getX() + 3;
                for (int x=min; x<=max; x++) for (int z=-14; z<=14; z++) {
                    world.setBlockState(new BlockPos(x,80,z), (spec.mode==GameModes.Mode.ADVENTURE ? Blocks.STONE_BRICKS : Blocks.QUARTZ_BLOCK).getDefaultState(), 3);
                    if (x==min || x==max || Math.abs(z)==14)
                        for (int y=81;y<=84;y++) world.setBlockState(new BlockPos(x,y,z), Blocks.DEEPSLATE_BRICKS.getDefaultState(), 3);
                }
                if (!spec.id.equals("ruins")) {
                    int wall=0;
                    for (int x=base+3; x<spec.points.getLast().getX(); x+=spec.id.equals("maze")?4:6) {
                        for (int z=-13;z<=13;z++) if ((wall%2==0 && z<9) || (wall%2==1 && z>-9))
                            for(int y=81;y<=83;y++) world.setBlockState(new BlockPos(x,y,z), Blocks.DEEPSLATE_BRICKS.getDefaultState(),3);
                        wall++;
                    }
                } else {
                    // Ruined colonnades around five glowing seals. All seals must be found in order.
                    for(int x : new int[]{-6,6}) for(int z : new int[]{-6,0,6})
                        for(int y=81;y<=84;y++) world.setBlockState(new BlockPos(x,y,z), (y==84?Blocks.SEA_LANTERN:Blocks.CHISELED_STONE_BRICKS).getDefaultState(),3);
                }
            }
            for (var point : spec.points) {
                for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                    world.setBlockState(point.add(dx,-1,dz), Blocks.QUARTZ_BLOCK.getDefaultState(),3);
                world.setBlockState(point.down(), Blocks.SEA_LANTERN.getDefaultState(),3);
                world.setBlockState(point, Blocks.AIR.getDefaultState(),3);
                world.setBlockState(point.up(), Blocks.AIR.getDefaultState(),3);
            }
        }
        built.addAll(MAPS.keySet());
        try { CommunityServer.atomicJson(marker, Map.of("version",2,"maps",built)); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot save map generation marker",e); }
    }
    static Set<String> builtMaps(java.nio.file.Path marker) {
        if (!Files.exists(marker)) return new LinkedHashSet<>();
        try {
            var data = com.google.gson.JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            int version = data.get("version").getAsInt();
            if ((version != 1 && version != 2) || !data.has("maps")) throw new IllegalArgumentException("Invalid map marker");
            var built = new LinkedHashSet<String>();
            for (var map : data.getAsJsonArray("maps")) built.add(map.getAsString());
            return built;
        } catch (Exception e) { throw new IllegalStateException("Cannot read built-in map marker " + marker + "; the original was not overwritten.", e); }
    }
    static void requireEmpty(ServerWorld world, BlockPos min, BlockPos max) {
        for (BlockPos at : BlockPos.iterate(min, max)) if (!world.isInBuildLimit(at) || !world.getWorldBorder().contains(at) || !world.getBlockState(at).isAir())
            throw new IllegalStateException("New minigame would replace existing blocks at " + at + ". Move the build or restore a matching backup; existing maps were not rebuilt.");
    }
    static void put(ServerWorld world, int x, int y, int z, BlockState block) { world.setBlockState(new BlockPos(x,y,z),block,3); }
    static void buildDropper(ServerWorld world) {
        for (int x=154; x<=176; x++) for (int z=-9; z<=9; z++) {
            put(world,x,40,z,Blocks.POLISHED_DEEPSLATE.getDefaultState());
            if (x==154 || x==176 || Math.abs(z)==9) for (int y=41; y<=123; y++) put(world,x,y,z,Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
            else if (Math.abs(x-165)>2 || Math.abs(z)>2) put(world,x,120,z,Blocks.QUARTZ_BLOCK.getDefaultState());
        }
        var spec=MAPS.get("dropper"); var colors=List.of(Blocks.YELLOW_CONCRETE,Blocks.ORANGE_CONCRETE,Blocks.PURPLE_CONCRETE);
        for(int gate=1;gate<=3;gate++) {
            var center=spec.points.get(gate);
            for(int x=155;x<=175;x++) for(int z=-8;z<=8;z++) {
                int dx=Math.abs(x-center.getX()), dz=Math.abs(z-center.getZ());
                if(dx<=2&&dz<=2) continue;
                put(world,x,center.getY()-1,z,((dx==3&&dz<=3)||(dz==3&&dx<=3)?Blocks.SEA_LANTERN:colors.get(gate-1)).getDefaultState());
            }
        }
        for(int x=161;x<=169;x++) for(int z=-4;z<=4;z++) for(int y=41;y<=42;y++) put(world,x,y,z,Blocks.WATER.getDefaultState());
        put(world,165,40,0,Blocks.SEA_LANTERN.getDefaultState());
        put(world,160,120,0,Blocks.SEA_LANTERN.getDefaultState());
    }
    static void buildRedlight(ServerWorld world) {
        for(int x=221;x<=255;x++) for(int z=-5;z<=5;z++) {
            put(world,x,80,z,(z==0?Blocks.QUARTZ_BLOCK:Blocks.LIGHT_GRAY_CONCRETE).getDefaultState());
            if(x==221||x==255||Math.abs(z)==5) for(int y=81;y<=84;y++) put(world,x,y,z,Blocks.GLASS.getDefaultState());
        }
        for(var point:MAPS.get("redlight").points) put(world,point.getX(),80,point.getZ(),Blocks.SEA_LANTERN.getDefaultState());
        for(int x:new int[]{225,233,241,249}) {
            put(world,x,82,-4,Blocks.LIME_CONCRETE.getDefaultState());
            put(world,x,84,-4,Blocks.RED_CONCRETE.getDefaultState());
        }
        for(int z=-4;z<=4;z++) put(world,252,80,z,Blocks.GOLD_BLOCK.getDefaultState());
        put(world,252,80,0,Blocks.SEA_LANTERN.getDefaultState());
    }
    static String defaultMap(GameModes.Mode mode) { return mode==GameModes.Mode.ADVENTURE?"ruins":"parkour"; }
    static BlockPos start(String map) { return MAPS.get(map).points.getFirst(); }
    static final class Run {
        final String map; final long started=System.nanoTime(); int next=1;
        final int startedTick; Vec3d previous; Vec3d redAnchor; int lastPhase=-1;
        Run(String map, int tick, Vec3d previous) { this.map=map; this.startedTick=tick; this.previous=previous; }
    }
    static final Map<UUID,Run> RUNS=new HashMap<>();
    static void begin(ServerPlayerEntity p, String id) {
        var map=MAPS.get(id); if(map==null || GameModes.current(p)!=map.mode) return;
        var pos=start(id); p.teleport(p.getEntityWorld(),pos.getX()+.5,pos.getY(),pos.getZ()+.5,Set.of(),-90,0,true);
        p.setVelocity(Vec3d.ZERO); p.fallDistance=0;
        p.getHungerManager().setFoodLevel(20); p.getHungerManager().setSaturationLevel(5);
        RUNS.put(p.getUuid(),new Run(id,p.getEntityWorld().getServer().getTicks(),p.getEntityPos()));
        String instructions = switch(map.kind) {
            case DROPPER -> "Walk into the opening east of spawn. Steer through all three glowing holes, then land in the water. Missing a hole restarts the run.";
            case REDLIGHT -> "Run east on GREEN; stop moving on RED. Your action bar shows the light. Moving during red restarts the race. Visit the four checkpoints in order.";
            default -> "Visit each glowing checkpoint in order.";
        };
        CommunityServer.say(p,map.title+": "+instructions+" /retry "+id+" restarts; /play survival leaves. No special items are needed.");
        hint(p,RUNS.get(p.getUuid()));
    }
    static void hint(ServerPlayerEntity p, Run run) {
        var points=MAPS.get(run.map).points; var at=points.get(run.next);
        CommunityServer.say(p,"Checkpoint "+run.next+"/"+(points.size()-1)+": X "+at.getX()+", Y "+at.getY()+", Z "+at.getZ()+" (sea lantern).");
        p.sendMessage(Text.literal("Checkpoint "+run.next+" of "+(points.size()-1)+" | /retry "+run.map).formatted(Formatting.AQUA),true);
    }
    static void tick(ServerPlayerEntity p) {
        tick(p,p.getEntityWorld().getServer().getTicks());
    }
    static void tick(ServerPlayerEntity p,int tick) {
        var run=RUNS.get(p.getUuid()); if(run==null) return;
        var spec=MAPS.get(run.map); if(GameModes.current(p)!=spec.mode) { RUNS.remove(p.getUuid()); return; }
        if(!p.isAlive() || p.isSpectator()) return;
        if(spec.kind==Kind.DROPPER) { dropper(p,run,spec,tick); return; }
        if(spec.kind==Kind.REDLIGHT && !redlight(p,run,tick)) return;
        if(!Memberships.operator(p)&&p.getY()<77) { begin(p,run.map); CommunityServer.say(p,"You fell. Timer restarted — try again!"); return; }
        var target=spec.points.get(run.next);
        if(p.getEntityPos().squaredDistanceTo(Vec3d.ofBottomCenter(target))>1.4 || !p.isOnGround()) return;
        run.next++;
        if(run.next<spec.points.size()) { hint(p,run); return; }
        finish(p,run,tick);
    }
    static void restart(ServerPlayerEntity p,Run run,String reason) { begin(p,run.map); CommunityServer.say(p,reason+" Timer restarted — try again!"); }
    static double horizontal(Vec3d a,Vec3d b) { double x=a.x-b.x,z=a.z-b.z;return x*x+z*z; }
    static void dropper(ServerPlayerEntity p,Run run,MapSpec spec,int tick) {
        Vec3d now=p.getEntityPos(),previous=run.previous;run.previous=now;
        if(now.x<155 || now.x>176 || Math.abs(now.z)>9 || now.y<40) { restart(p,run,"You left the dropper course.");return; }
        boolean water=p.getEntityWorld().getFluidState(p.getBlockPos()).isIn(FluidTags.WATER)
            || p.getEntityWorld().getFluidState(p.getBlockPos().down()).isIn(FluidTags.WATER);
        if(now.y<44 && water) {
            if(run.next==spec.points.size()-1 && horizontal(now,Vec3d.ofBottomCenter(spec.points.getLast()))<=20.25) finish(p,run,tick);
            else restart(p,run,"Reach every glowing hole before the pool.");
            return;
        }
        if(p.isOnGround() && now.y<120 && p.getEntityWorld().getBlockState(p.getBlockPos().down()).isSolidBlock(p.getEntityWorld(),p.getBlockPos().down())) {
            restart(p,run,"You landed on an obstacle instead of the water.");return;
        }
        var target=spec.points.get(run.next);
        if(run.next<spec.points.size()-1 && previous.y>target.getY() && now.y<=target.getY()
            && horizontal(now,Vec3d.ofBottomCenter(target))<=4.84) { run.next++;hint(p,run); }
    }
    static boolean redlight(ServerPlayerEntity p,Run run,int tick) {
        int phase=Math.floorMod(tick-run.startedTick,GREEN_TICKS+RED_TICKS);boolean green=phase<GREEN_TICKS;
        Vec3d now=p.getEntityPos(),before=run.previous;run.previous=now;
        if(now.x<222 || now.x>255 || Math.abs(now.z)>4.9 || now.y<77) { restart(p,run,"You left the race lane.");return false; }
        if(green) run.redAnchor=null;
        else if(phase<GREEN_TICKS+RED_GRACE_TICKS) run.redAnchor=now;
        else {
            if(run.redAnchor==null) run.redAnchor=before;
            if(horizontal(now,run.redAnchor)>RED_MOVE_TOLERANCE*RED_MOVE_TOLERANCE) { restart(p,run,"You moved on RED.");return false; }
        }
        int light=green?0:1;
        if(run.lastPhase!=light || tick%10==0) {
            int remaining=((green?GREEN_TICKS-phase:GREEN_TICKS+RED_TICKS-phase)+19)/20;
            p.sendMessage(Text.literal((green?"GREEN — RUN":"RED — STOP")+" | "+Math.max(1,remaining)+"s").formatted(green?Formatting.GREEN:Formatting.RED),true);
            run.lastPhase=light;
        }
        return green;
    }
    static void finish(ServerPlayerEntity p,Run run,int tick) {
        var spec=MAPS.get(run.map);
        long millis=spec.kind==Kind.CHECKPOINTS?(System.nanoTime()-run.started)/1_000_000:Math.max(1,(long)(tick-run.startedTick)*50);
        var state=GameModes.state(p); var scores=state.getCompoundOrEmpty("scores");
        long old=scores.getLong(run.map,Long.MAX_VALUE); scores.putLong(run.map,Math.min(old,millis)); state.put("scores",scores);
        RUNS.remove(p.getUuid());
        p.getEntityWorld().getServer().getPlayerManager().saveAllPlayerData();
        try {MinigameRecords.sync(p);}catch(IllegalStateException e) {CommunityServer.say(p,"Your best was saved, but the public leaderboard is temporarily unavailable.");}
        p.sendMessage(Text.literal("Course clear! "+MinigameRecords.time(millis)+" | /retry "+run.map).formatted(Formatting.GREEN),true);
        CommunityServer.say(p,"Completed "+spec.title+" in "+String.format(Locale.ROOT,"%.2f",millis/1000.0)+"s! Personal best: "+String.format(Locale.ROOT,"%.2f",Math.min(old,millis)/1000.0)+"s. /retry "+run.map+" to race again.");
    }
}

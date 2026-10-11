package dev.convergence;

import java.nio.file.Files;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Small built-in maps, generated once in dedicated dimensions, never over survival builds. */
final class ModeMaps {
    enum Kind { CHECKPOINTS, DROPPER, REDLIGHT, CRYSTAL_HUNT, COLOR_RUSH }
    record MapSpec(String id, GameModes.Mode mode, String title, List<BlockPos> points, Kind kind) {
        MapSpec(String id, GameModes.Mode mode, String title, List<BlockPos> points) { this(id, mode, title, points, Kind.CHECKPOINTS); }
    }
    static final int GREEN_TICKS = 80;
    static final int RED_TICKS = 40;
    static final int RED_GRACE_TICKS = 4;
    static final double RED_MOVE_TOLERANCE = .15;
    static final int COLOR_ROUND_TICKS = 100;
    static final int COLOR_ROUNDS = 5;
    static final List<String> COLORS = List.of("RED", "BLUE", "YELLOW", "GREEN");
    static final Map<String, MapSpec> MAPS = new LinkedHashMap<>();
    static final Set<String> AVAILABLE = new HashSet<>();
    static {
        MAPS.put("parkour", new MapSpec("parkour", GameModes.Mode.MINIGAMES, "Sky Steps", List.of(
            new BlockPos(0,81,0), new BlockPos(6,82,0), new BlockPos(12,81,3), new BlockPos(18,82,0), new BlockPos(24,81,3), new BlockPos(30,81,0))));
        MAPS.put("sprint", new MapSpec("sprint", GameModes.Mode.MINIGAMES, "Switchback Sprint", List.of(
            new BlockPos(64,81,0), new BlockPos(70,81,10), new BlockPos(76,81,-10), new BlockPos(82,81,10), new BlockPos(88,81,-10), new BlockPos(94,81,0))));
        MAPS.put("dropper", new MapSpec("dropper", GameModes.Mode.MINIGAMES, "Prism Dropper", List.of(
            new BlockPos(160,121,0), new BlockPos(165,101,0), new BlockPos(163,81,2), new BlockPos(165,61,0), new BlockPos(165,42,0)), Kind.DROPPER));
        MAPS.put("redlight", new MapSpec("redlight", GameModes.Mode.MINIGAMES, "Red Light Run", List.of(
            new BlockPos(224,81,0), new BlockPos(231,81,0), new BlockPos(238,81,0), new BlockPos(245,81,0), new BlockPos(252,81,0)), Kind.REDLIGHT));
        MAPS.put("crystalhunt", new MapSpec("crystalhunt", GameModes.Mode.MINIGAMES, "Crystal Hunt", List.of(
            new BlockPos(288,81,0), new BlockPos(294,81,-8), new BlockPos(302,81,-8), new BlockPos(306,81,0),
            new BlockPos(302,81,8), new BlockPos(294,81,8)), Kind.CRYSTAL_HUNT));
        MAPS.put("colorrush", new MapSpec("colorrush", GameModes.Mode.MINIGAMES, "Color Rush", List.of(
            new BlockPos(354,81,0), new BlockPos(354,81,-8), new BlockPos(362,81,0),
            new BlockPos(354,81,8), new BlockPos(346,81,0)), Kind.COLOR_RUSH));
        MAPS.put("ruins", new MapSpec("ruins", GameModes.Mode.ADVENTURE, "The Five Seals", List.of(
            new BlockPos(0,81,0), new BlockPos(10,81,0), new BlockPos(10,81,10), new BlockPos(-10,81,10), new BlockPos(-10,81,-10), new BlockPos(10,81,-10), new BlockPos(0,81,0))));
        MAPS.put("maze", new MapSpec("maze", GameModes.Mode.ADVENTURE, "Lantern Labyrinth", List.of(
            new BlockPos(64,81,0), new BlockPos(68,81,10), new BlockPos(72,81,-10), new BlockPos(76,81,10), new BlockPos(80,81,-10), new BlockPos(86,81,0))));
    }
    /** The planned final blocks allow a failed install to resume without replacing unknown builds. */
    static final class BlockPlan {
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        void put(BlockPos pos, BlockState state) {
            blocks.put(pos, state);
            minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
        }
        void put(int x, int y, int z, BlockState state) { put(new BlockPos(x, y, z), state); }
        BlockPos min() { return new BlockPos(minX, minY, minZ); }
        BlockPos max() { return new BlockPos(maxX, maxY, maxZ); }
    }
    record MapMarker(Set<String> built, Set<String> installing) {}
    record Inspection(BlockPos conflict, boolean occupied, int missing) {
        boolean complete() { return conflict == null && occupied && missing == 0; }
        boolean empty() { return conflict == null && !occupied; }
    }

    static boolean available(String id) { return AVAILABLE.contains(id); }

    static void build(MinecraftServer server) {
        AVAILABLE.clear();
        var marker = server.getWorldPath(LevelResource.ROOT).resolve("infinity-built-in-maps.json");
        MapMarker saved = readMarker(marker);
        Set<String> confirmed = new LinkedHashSet<>(saved.built);
        Set<String> installing = new LinkedHashSet<>(saved.installing);
        Set<String> ready = new HashSet<>();
        var plans = new LinkedHashMap<MapSpec, BlockPlan>();
        for (var spec : MAPS.values()) {
            var world = GameModes.world(server, spec.mode);
            if (world == null) throw new IllegalStateException("Infinity dimension missing: " + spec.mode + ". Enable the mod data pack and restart.");
            var plan = plan(spec);
            var check = inspect(world, spec, plan);
            if (check.conflict != null) {
                System.err.println("[Infinity] Preserved occupied " + spec.id + " map region at " + check.conflict
                    + "; the course is unavailable until the host resolves the build or restores a matching backup.");
                continue;
            }
            // Without a marker, a partial map could also be a host build. Only a
            // completely empty volume or an exact finished course is unambiguous.
            if (!mayReconcile(saved, spec, check)) {
                System.err.println("[Infinity] Preserved ambiguous unmarked " + spec.id
                    + " map region; restore its marker or move the build before enabling this course.");
                continue;
            }
            if (check.empty() && !saved.built.contains(spec.id) && !saved.installing.contains(spec.id))
                installing.add(spec.id);
            plans.put(spec, plan);
            ready.add(spec.id);
        }
        // Record intent before changing blocks so a crash cannot make a partially
        // written course indistinguishable from a pre-existing player build.
        if (!installing.equals(saved.installing)) writeMarker(marker, confirmed, installing);
        boolean placed = false;
        for (var entry : plans.entrySet()) {
            placed |= apply(GameModes.world(server, entry.getKey().mode), entry.getValue());
            confirmed.add(entry.getKey().id);
            installing.remove(entry.getKey().id);
        }
        // Flush changed chunks before committing completion. A crash between the
        // intent and completion writes resumes only the explicitly pending courses.
        if (placed && !server.saveAllChunks(true, true, false))
            throw new IllegalStateException("Could not flush built-in map chunks; the map marker was not changed.");
        if (!confirmed.equals(saved.built) || !installing.equals(saved.installing) || !Files.exists(marker))
            writeMarker(marker, confirmed, installing);
        AVAILABLE.clear();
        AVAILABLE.addAll(ready);
    }

    static void writeMarker(java.nio.file.Path marker, Set<String> built, Set<String> installing) {
        try { CommunityServer.atomicJson(marker, Map.of("version",3,"maps",built,"installing",installing)); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot save map generation marker",e); }
    }

    static boolean mayReconcile(MapMarker marker, MapSpec spec, Inspection check) {
        return check.conflict == null && (marker.built.contains(spec.id) || marker.installing.contains(spec.id)
            || check.empty() || check.complete());
    }

    static BlockPlan plan(MapSpec spec) {
        var plan = new BlockPlan();
        if (spec.kind == Kind.DROPPER) { buildDropper(plan); return plan; }
        if (spec.kind == Kind.REDLIGHT) { buildRedlight(plan); return plan; }
        if (spec.kind == Kind.CRYSTAL_HUNT) { buildCrystalHunt(plan); return plan; }
        if (spec.kind == Kind.COLOR_RUSH) { buildColorRush(plan); return plan; }
        int base = spec.points.getFirst().getX();
        if (!spec.id.equals("parkour")) {
            int min = spec.id.equals("ruins") ? -14 : base - 3;
            int max = spec.id.equals("ruins") ? 14 : spec.points.getLast().getX() + 3;
            for (int x=min; x<=max; x++) for (int z=-14; z<=14; z++) {
                plan.put(x,80,z,(spec.mode==GameModes.Mode.ADVENTURE ? Blocks.STONE_BRICKS : Blocks.QUARTZ_BLOCK).defaultBlockState());
                if (x==min || x==max || Math.abs(z)==14)
                    for (int y=81;y<=84;y++) plan.put(x,y,z,Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            }
            if (!spec.id.equals("ruins")) {
                int wall=0;
                for (int x=base+3; x<spec.points.getLast().getX(); x+=spec.id.equals("maze")?4:6) {
                    for (int z=-13;z<=13;z++) if ((wall%2==0 && z<9) || (wall%2==1 && z>-9))
                        for(int y=81;y<=83;y++) plan.put(x,y,z,Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                    wall++;
                }
            } else {
                for(int x : new int[]{-6,6}) for(int z : new int[]{-6,0,6})
                    for(int y=81;y<=84;y++) plan.put(x,y,z,(y==84?Blocks.SEA_LANTERN:Blocks.CHISELED_STONE_BRICKS).defaultBlockState());
            }
        }
        for (var point : spec.points) {
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                plan.put(point.offset(dx,-1,dz),Blocks.QUARTZ_BLOCK.defaultBlockState());
            plan.put(point.below(),Blocks.SEA_LANTERN.defaultBlockState());
            plan.put(point,Blocks.AIR.defaultBlockState());
            plan.put(point.above(),Blocks.AIR.defaultBlockState());
        }
        return plan;
    }

    /** Inspect every map cell before writing; never replace a block outside the plan. */
    static Inspection inspect(ServerLevel world, MapSpec spec, BlockPlan plan) {
        BlockPos sign = CourseSelector.signPos(spec);
        boolean occupied = false;
        int missing = 0;
        for (BlockPos cursor : BlockPos.betweenClosed(plan.min(), plan.max())) {
            BlockPos pos = cursor.immutable();
            if (!world.isInWorldBounds(pos) || !world.getWorldBorder().isWithinBounds(pos)) return new Inspection(pos, occupied, missing);
            BlockState actual = world.getBlockState(pos);
            BlockState expected = plan.blocks.get(pos);
            if (!actual.isAir()) occupied = true;
            if (expected != null) {
                if (actual.equals(expected) || expected.is(Blocks.WATER) && actual.is(Blocks.WATER)) continue;
                if (actual.isAir()) { if (!expected.isAir()) missing++; continue; }
            } else if (actual.isAir()
                || pos.equals(sign) && CourseSelector.selectorSign(world, pos)
                || spec.kind == Kind.DROPPER && pos.getY() >= 41 && pos.getY() <= 42 && actual.is(Blocks.WATER)) continue;
            return new Inspection(pos, occupied, missing);
        }
        return new Inspection(null, occupied, missing);
    }

    static BlockPos conflict(ServerLevel world, MapSpec spec, BlockPlan plan) { return inspect(world, spec, plan).conflict; }

    static boolean apply(ServerLevel world, BlockPlan plan) {
        boolean changed = false;
        for (var entry : plan.blocks.entrySet()) if (!entry.getValue().isAir() && world.getBlockState(entry.getKey()).isAir()) {
            world.setBlock(entry.getKey(), entry.getValue(), 3);
            changed = true;
        }
        return changed;
    }
    static Set<String> builtMaps(java.nio.file.Path marker) { return readMarker(marker).built(); }
    static MapMarker readMarker(java.nio.file.Path marker) {
        if (!Files.exists(marker)) return new MapMarker(new LinkedHashSet<>(), new LinkedHashSet<>());
        try {
            var data = com.google.gson.JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
            int version = data.get("version").getAsInt();
            if ((version != 1 && version != 2 && version != 3) || !data.has("maps")
                || version == 3 && !data.has("installing")) throw new IllegalArgumentException("Invalid map marker");
            var built = new LinkedHashSet<String>();
            for (var map : data.getAsJsonArray("maps")) built.add(map.getAsString());
            var installing = new LinkedHashSet<String>();
            if (version == 3) for (var map : data.getAsJsonArray("installing")) installing.add(map.getAsString());
            if (!Collections.disjoint(built, installing)) throw new IllegalArgumentException("A map cannot be built and installing");
            return new MapMarker(built, installing);
        } catch (Exception e) { throw new IllegalStateException("Cannot read built-in map marker " + marker + "; the original was not overwritten.", e); }
    }
    static void requireNewMapEmpty(MinecraftServer server,MapSpec spec) {
        if(spec.kind==Kind.CHECKPOINTS) return;
        var world=GameModes.world(server,spec.mode);
        if(world==null) throw new IllegalStateException("Infinity dimension missing: "+spec.mode);
        BlockPos min=switch(spec.kind) {
            case DROPPER -> new BlockPos(154,40,-9);
            case REDLIGHT -> new BlockPos(221,80,-5);
            case CRYSTAL_HUNT -> new BlockPos(284,80,-12);
            case COLOR_RUSH -> new BlockPos(340,80,-13);
            default -> throw new IllegalStateException("No dedicated build volume for "+spec.id);
        };
        BlockPos max=switch(spec.kind) {
            case DROPPER -> new BlockPos(176,123,9);
            case REDLIGHT -> new BlockPos(255,85,5);
            case CRYSTAL_HUNT -> new BlockPos(310,85,12);
            case COLOR_RUSH -> new BlockPos(368,85,13);
            default -> throw new IllegalStateException("No dedicated build volume for "+spec.id);
        };
        requireEmpty(world,min,max);
    }
    static void requireEmpty(ServerLevel world, BlockPos min, BlockPos max) {
        for (BlockPos at : BlockPos.betweenClosed(min, max)) if (!world.isInWorldBounds(at) || !world.getWorldBorder().isWithinBounds(at) || !world.getBlockState(at).isAir())
            throw new IllegalStateException("New minigame would replace existing blocks at " + at + ". Move the build or restore a matching backup; existing maps were not rebuilt.");
    }
    static void put(BlockPlan plan, int x, int y, int z, BlockState block) { plan.put(x,y,z,block); }
    static void buildDropper(BlockPlan plan) {
        for (int x=154; x<=176; x++) for (int z=-9; z<=9; z++) {
            put(plan,x,40,z,Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            if (x==154 || x==176 || Math.abs(z)==9) for (int y=41; y<=123; y++) put(plan,x,y,z,Blocks.STAINED_GLASS.lightBlue().defaultBlockState());
            else if (Math.abs(x-165)>2 || Math.abs(z)>2) put(plan,x,120,z,Blocks.QUARTZ_BLOCK.defaultBlockState());
        }
        var spec=MAPS.get("dropper"); var colors=List.of(Blocks.CONCRETE.yellow(),Blocks.CONCRETE.orange(),Blocks.CONCRETE.purple());
        for(int gate=1;gate<=3;gate++) {
            var center=spec.points.get(gate);
            for(int x=155;x<=175;x++) for(int z=-8;z<=8;z++) {
                int dx=Math.abs(x-center.getX()), dz=Math.abs(z-center.getZ());
                if(dx<=2&&dz<=2) continue;
                put(plan,x,center.getY()-1,z,((dx==3&&dz<=3)||(dz==3&&dx<=3)?Blocks.SEA_LANTERN:colors.get(gate-1)).defaultBlockState());
            }
        }
        for(int x=161;x<=169;x++) for(int z=-4;z<=4;z++) for(int y=41;y<=42;y++) put(plan,x,y,z,Blocks.WATER.defaultBlockState());
        put(plan,165,40,0,Blocks.SEA_LANTERN.defaultBlockState());
        put(plan,160,120,0,Blocks.SEA_LANTERN.defaultBlockState());
    }
    static void buildRedlight(BlockPlan plan) {
        for(int x=221;x<=255;x++) for(int z=-5;z<=5;z++) {
            put(plan,x,80,z,(z==0?Blocks.QUARTZ_BLOCK:Blocks.CONCRETE.lightGray()).defaultBlockState());
            if(x==221||x==255||Math.abs(z)==5) for(int y=81;y<=84;y++) put(plan,x,y,z,Blocks.GLASS.defaultBlockState());
        }
        for(var point:MAPS.get("redlight").points) put(plan,point.getX(),80,point.getZ(),Blocks.SEA_LANTERN.defaultBlockState());
        for(int x:new int[]{225,233,241,249}) {
            put(plan,x,82,-4,Blocks.CONCRETE.lime().defaultBlockState());
            put(plan,x,84,-4,Blocks.CONCRETE.red().defaultBlockState());
        }
        for(int z=-4;z<=4;z++) put(plan,252,80,z,Blocks.GOLD_BLOCK.defaultBlockState());
        put(plan,252,80,0,Blocks.SEA_LANTERN.defaultBlockState());
    }
    static void buildCrystalHunt(BlockPlan plan) {
        for(int x=284;x<=310;x++) for(int z=-12;z<=12;z++) {
            put(plan,x,80,z,Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            if(x==284||x==310||Math.abs(z)==12)
                for(int y=81;y<=84;y++) put(plan,x,y,z,Blocks.STAINED_GLASS.purple().defaultBlockState());
        }
        var points=MAPS.get("crystalhunt").points;
        for(int i=0;i<points.size();i++) {
            var center=points.get(i);
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                put(plan,center.getX()+dx,80,center.getZ()+dz,
                    (i==0?Blocks.QUARTZ_BLOCK:Blocks.AMETHYST_BLOCK).defaultBlockState());
            put(plan,center.getX(),80,center.getZ(),Blocks.SEA_LANTERN.defaultBlockState());
        }
        for(int z:new int[]{-8,0,8}) {
            put(plan,287,81,z,Blocks.AMETHYST_BLOCK.defaultBlockState());
            put(plan,287,82,z,Blocks.SEA_LANTERN.defaultBlockState());
        }
    }
    static void buildColorRush(BlockPlan plan) {
        for(int x=340;x<=368;x++) for(int z=-13;z<=13;z++) {
            put(plan,x,80,z,Blocks.CONCRETE.white().defaultBlockState());
            if(x==340||x==368||Math.abs(z)==13)
                for(int y=81;y<=84;y++) put(plan,x,y,z,Blocks.STAINED_GLASS.cyan().defaultBlockState());
        }
        var points=MAPS.get("colorrush").points;
        var tiles=List.of(Blocks.CONCRETE.red(),Blocks.CONCRETE.blue(),Blocks.CONCRETE.yellow(),Blocks.CONCRETE.lime());
        for(int i=1;i<points.size();i++) {
            var center=points.get(i);
            for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++)
                put(plan,center.getX()+dx,80,center.getZ()+dz,tiles.get(i-1).defaultBlockState());
            put(plan,center.getX(),80,center.getZ(),Blocks.SEA_LANTERN.defaultBlockState());
        }
        var start=points.getFirst();
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
            put(plan,start.getX()+dx,80,start.getZ()+dz,Blocks.QUARTZ_BLOCK.defaultBlockState());
        put(plan,start.getX(),80,start.getZ(),Blocks.SEA_LANTERN.defaultBlockState());
    }
    static String defaultMap(GameModes.Mode mode) {
        String preferred = mode == GameModes.Mode.ADVENTURE ? "ruins" : "parkour";
        if (available(preferred)) return preferred;
        return MAPS.values().stream().filter(spec -> spec.mode == mode && available(spec.id)).map(MapSpec::id).findFirst().orElse(null);
    }
    static String selectedMap(ServerPlayer p, GameModes.Mode mode) {
        String selected=GameModes.state(p).getStringOr("selected_"+mode.name().toLowerCase(Locale.ROOT)+"_map","");
        var spec=MAPS.get(selected);
        return spec!=null && spec.mode()==mode && available(selected) ? selected : defaultMap(mode);
    }
    static BlockPos start(String map) { return MAPS.get(map).points.getFirst(); }
    static final class Run {
        final String map; final long started=System.nanoTime(); int next=1;
        final int startedTick; Vec3 previous; Vec3 redAnchor; int lastPhase=-1;
        int foundMask; int colorDeadline; final int[] colors=new int[COLOR_ROUNDS];
        Run(String map, int tick, Vec3 previous) { this.map=map; this.startedTick=tick; this.previous=previous; }
    }
    static final Map<UUID,Run> RUNS=new HashMap<>();
    static void begin(ServerPlayer p, String id) {
        var map=MAPS.get(id); if(map==null || GameModes.current(p)!=map.mode) return;
        if (!available(id)) { CommunityServer.say(p,"This course is unavailable because its map region could not be verified. Ask the host to check the server log."); return; }
        var pos=start(id); p.teleportTo(p.level(),pos.getX()+.5,pos.getY(),pos.getZ()+.5,Set.of(),-90,0,true);
        p.setDeltaMovement(Vec3.ZERO); p.fallDistance=0;
        p.getFoodData().setFoodLevel(20); p.getFoodData().setSaturation(5);
        GameModes.state(p).putString("selected_"+map.mode.name().toLowerCase(Locale.ROOT)+"_map",id);
        var run=new Run(id,p.level().getServer().getTickCount(),p.position());
        if(map.kind==Kind.COLOR_RUSH) {
            var random=new SplittableRandom(p.getUUID().getMostSignificantBits()^p.getUUID().getLeastSignificantBits()^run.startedTick);
            for(int i=0;i<COLOR_ROUNDS;i++) {
                int color=random.nextInt(COLORS.size());
                if(i>0 && color==run.colors[i-1]) color=(color+1+random.nextInt(COLORS.size()-1))%COLORS.size();
                run.colors[i]=color;
            }
            run.colorDeadline=run.startedTick+COLOR_ROUND_TICKS;
        }
        RUNS.put(p.getUUID(),run);
        String instructions = switch(map.kind) {
            case DROPPER -> "Walk into the opening east of spawn. Steer through all three glowing holes, then land in the water. Missing a hole restarts the run.";
            case REDLIGHT -> "Run east on GREEN; stop moving on RED. Your action bar shows the light. Moving during red restarts the race. Visit the four checkpoints in order.";
            case CRYSTAL_HUNT -> "Touch the five glowing amethyst pads in any order. Each pad counts once; collect them all to finish.";
            case COLOR_RUSH -> "Watch the action bar. Reach the named color within five seconds to start the next pulse. Clear five pulses to finish; a timeout restarts the run.";
            default -> "Visit each glowing checkpoint in order.";
        };
        String selectorHint=map.kind==Kind.DROPPER
            ? "Right-click the glowing start or pool-center lantern to choose a different course."
            : "Right-click a glowing checkpoint lantern to choose a different course.";
        CommunityServer.say(p,map.title+": "+instructions+" "+selectorHint+" /retry "+id+" restarts; /play survival leaves. No special items are needed.");
        if(map.kind==Kind.CRYSTAL_HUNT) crystalHint(p,run);
        else if(map.kind==Kind.COLOR_RUSH) colorHint(p,run,run.startedTick);
        else hint(p,run);
    }
    static void hint(ServerPlayer p, Run run) {
        var points=MAPS.get(run.map).points; var at=points.get(run.next);
        CommunityServer.say(p,"Checkpoint "+run.next+"/"+(points.size()-1)+": X "+at.getX()+", Y "+at.getY()+", Z "+at.getZ()+" (sea lantern).");
        p.sendOverlayMessage(Component.literal("Checkpoint "+run.next+" of "+(points.size()-1)+" | /retry "+run.map).withStyle(ChatFormatting.AQUA));
    }
    static void crystalHint(ServerPlayer p,Run run) {
        int found=Integer.bitCount(run.foundMask);
        p.sendOverlayMessage(Component.literal("Crystals "+found+"/5 | Touch the glowing amethyst pads").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
    static void colorHint(ServerPlayer p,Run run,int tick) {
        int color=run.colors[run.next-1];
        ChatFormatting formatting=switch(color) {
            case 0 -> ChatFormatting.RED;
            case 1 -> ChatFormatting.BLUE;
            case 2 -> ChatFormatting.YELLOW;
            default -> ChatFormatting.GREEN;
        };
        int seconds=Math.max(1,(run.colorDeadline-tick+19)/20);
        p.sendOverlayMessage(Component.literal("Pulse "+run.next+"/"+COLOR_ROUNDS+" | "+COLORS.get(color)+" | "+seconds+"s").withStyle(formatting));
    }
    static void tick(ServerPlayer p) {
        tick(p,p.level().getServer().getTickCount());
    }
    static void tick(ServerPlayer p,int tick) {
        var run=RUNS.get(p.getUUID()); if(run==null) return;
        var spec=MAPS.get(run.map); if(GameModes.current(p)!=spec.mode) { RUNS.remove(p.getUUID()); return; }
        if(!p.isAlive() || p.isSpectator()) return;
        if(spec.kind==Kind.DROPPER) { dropper(p,run,spec,tick); return; }
        if(spec.kind==Kind.CRYSTAL_HUNT) { crystalHunt(p,run,spec,tick); return; }
        if(spec.kind==Kind.COLOR_RUSH) { colorRush(p,run,spec,tick); return; }
        if(spec.kind==Kind.REDLIGHT && !redlight(p,run,tick)) return;
        if(!Memberships.operator(p)&&p.getY()<77) { begin(p,run.map); CommunityServer.say(p,"You fell. Timer restarted — try again!"); return; }
        var target=spec.points.get(run.next);
        if(p.position().distanceToSqr(Vec3.atBottomCenterOf(target))>1.4 || !p.onGround()) return;
        run.next++;
        if(run.next<spec.points.size()) { hint(p,run); return; }
        finish(p,run,tick);
    }
    static void restart(ServerPlayer p,Run run,String reason) { begin(p,run.map); CommunityServer.say(p,reason+" Timer restarted — try again!"); }
    static double horizontal(Vec3 a,Vec3 b) { double x=a.x-b.x,z=a.z-b.z;return x*x+z*z; }
    static void crystalHunt(ServerPlayer p,Run run,MapSpec spec,int tick) {
        Vec3 now=p.position();
        if(now.x<285 || now.x>309 || Math.abs(now.z)>11.9 || now.y<77) {restart(p,run,"You left the crystal arena.");return;}
        if(!p.onGround()) return;
        var feet=BlockPos.containing(now);
        for(int i=1;i<spec.points.size();i++) {
            int bit=1<<(i-1);
            var center=spec.points.get(i);
            if((run.foundMask&bit)!=0 || Math.abs(feet.getX()-center.getX())>1 || Math.abs(feet.getZ()-center.getZ())>1) continue;
            run.foundMask|=bit;
            if(Integer.bitCount(run.foundMask)==spec.points.size()-1) finish(p,run,tick);
            else crystalHint(p,run);
            return;
        }
    }
    static boolean onColorPad(Vec3 now,BlockPos center) {
        var feet=BlockPos.containing(now);
        return Math.abs(feet.getX()-center.getX())<=2 && Math.abs(feet.getZ()-center.getZ())<=2;
    }
    static void colorRush(ServerPlayer p,Run run,MapSpec spec,int tick) {
        Vec3 now=p.position();
        if(now.x<341 || now.x>367 || Math.abs(now.z)>12.9 || now.y<77) {restart(p,run,"You left the color arena.");return;}
        if(tick>run.colorDeadline) {restart(p,run,"The color pulse expired.");return;}
        var correct=spec.points.get(run.colors[run.next-1]+1);
        if(p.onGround() && onColorPad(now,correct)) {
            run.next++;
            if(run.next>COLOR_ROUNDS) {finish(p,run,tick);return;}
            run.colorDeadline=tick+COLOR_ROUND_TICKS;
            colorHint(p,run,tick);
        } else if(tick==run.colorDeadline) restart(p,run,"The color pulse expired.");
        else if(tick%10==0) colorHint(p,run,tick);
    }
    static void dropper(ServerPlayer p,Run run,MapSpec spec,int tick) {
        Vec3 now=p.position(),previous=run.previous;run.previous=now;
        if(now.x<155 || now.x>176 || Math.abs(now.z)>9 || now.y<40) { restart(p,run,"You left the dropper course.");return; }
        boolean water=p.level().getFluidState(p.blockPosition()).is(FluidTags.WATER)
            || p.level().getFluidState(p.blockPosition().below()).is(FluidTags.WATER);
        if(now.y<44 && water) {
            if(run.next==spec.points.size()-1 && horizontal(now,Vec3.atBottomCenterOf(spec.points.getLast()))<=20.25) finish(p,run,tick);
            else restart(p,run,"Reach every glowing hole before the pool.");
            return;
        }
        if(p.onGround() && now.y<120 && p.level().getBlockState(p.blockPosition().below()).isRedstoneConductor(p.level(),p.blockPosition().below())) {
            restart(p,run,"You landed on an obstacle instead of the water.");return;
        }
        var target=spec.points.get(run.next);
        var box=p.getBoundingBox();
        boolean throughHole=box.minX>=target.getX()-2 && box.maxX<=target.getX()+3
            && box.minZ>=target.getZ()-2 && box.maxZ<=target.getZ()+3;
        if(run.next<spec.points.size()-1 && previous.y>target.getY() && now.y<=target.getY()
            && throughHole) { run.next++;hint(p,run); }
    }
    static boolean redlight(ServerPlayer p,Run run,int tick) {
        int phase=Math.floorMod(tick-run.startedTick,GREEN_TICKS+RED_TICKS);boolean green=phase<GREEN_TICKS;
        Vec3 now=p.position(),before=run.previous;run.previous=now;
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
            p.sendOverlayMessage(Component.literal((green?"GREEN — RUN":"RED — STOP")+" | "+Math.max(1,remaining)+"s").withStyle(green?ChatFormatting.GREEN:ChatFormatting.RED));
            run.lastPhase=light;
        }
        return green;
    }
    static void finish(ServerPlayer p,Run run,int tick) {
        var spec=MAPS.get(run.map);
        long millis=spec.kind==Kind.CHECKPOINTS?(System.nanoTime()-run.started)/1_000_000:Math.max(1,(long)(tick-run.startedTick)*50);
        var state=GameModes.state(p); var scores=state.getCompoundOrEmpty("scores");
        long old=scores.getLongOr(run.map,Long.MAX_VALUE); scores.putLong(run.map,Math.min(old,millis)); state.put("scores",scores);
        RUNS.remove(p.getUUID());
        p.level().getServer().getPlayerList().saveAll();
        try {MinigameRecords.sync(p);}catch(IllegalStateException e) {CommunityServer.say(p,"Your best was saved, but the public leaderboard is temporarily unavailable.");}
        p.sendOverlayMessage(Component.literal("Course clear! "+MinigameRecords.time(millis)+" | /retry "+run.map).withStyle(ChatFormatting.GREEN));
        CommunityServer.say(p,"Completed "+spec.title+" in "+String.format(Locale.ROOT,"%.2f",millis/1000.0)+"s! Personal best: "+String.format(Locale.ROOT,"%.2f",Math.min(old,millis)/1000.0)+"s. /retry "+run.map+" to race again.");
    }
}

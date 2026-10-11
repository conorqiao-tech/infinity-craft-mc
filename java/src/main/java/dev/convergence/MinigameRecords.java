package dev.convergence;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/** Public records index; each player's saved mode NBT remains the source of their best time. */
final class MinigameRecords {
    static final List<String> MAP_IDS=List.of("parkour","sprint","dropper","redlight");
    static final Map<MinecraftServer, MinigameRecords> INSTANCES=new WeakHashMap<>();
    record Score(UUID player, String name, long millis) {}
    final Path file;
    final Map<String,Map<UUID,Score>> boards=new LinkedHashMap<>();

    MinigameRecords(Path file) {
        this.file=file;
        for(String id:MAP_IDS)boards.put(id,new HashMap<>());
        if(!Files.exists(file))return;
        try {
            var data=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if(data.get("format").getAsInt()!=1)throw new IllegalArgumentException("Unsupported records format");
            var saved=data.getAsJsonObject("boards");
            if(saved==null)throw new IllegalArgumentException("Missing boards");
            for(var entry:saved.entrySet()) {
                var board=boards.get(entry.getKey());
                if(board==null)throw new IllegalArgumentException("Unknown map");
                for(var row:entry.getValue().getAsJsonArray()) {
                    var record=row.getAsJsonObject();
                    var score=new Score(UUID.fromString(record.get("player").getAsString()),record.get("name").getAsString(),record.get("millis").getAsLong());
                    if(!valid(score) || board.putIfAbsent(score.player(),score)!=null)throw new IllegalArgumentException("Invalid or duplicate record");
                }
            }
        } catch(Exception e) {throw new IllegalStateException("Cannot read minigame records. The original file was not overwritten.",e);}
    }
    static MinigameRecords get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,s->new MinigameRecords(s.getSavePath(WorldSavePath.ROOT).resolve("infinity-minigame-records.json")));
    }
    static boolean valid(Score score) {
        return score.player()!=null && score.millis()>0 && score.millis()<Long.MAX_VALUE && score.name()!=null
            && !score.name().isBlank() && score.name().length()<=64 && score.name().chars().noneMatch(Character::isISOControl);
    }
    boolean upsert(String id,Score score) {
        if(!MAP_IDS.contains(id) || !valid(score))return false;
        var board=boards.get(id);var old=board.get(score.player());
        if(old!=null && old.millis()==score.millis() && old.name().equals(score.name()))return false;
        board.put(score.player(),score);
        return true;
    }
    void save() {
        var saved=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var board:boards.entrySet()) {
            var rows=new ArrayList<Map<String,Object>>();
            for(var score:board.getValue().values())rows.add(Map.of("player",score.player().toString(),"name",score.name(),"millis",score.millis()));
            saved.put(board.getKey(),rows);
        }
        try {CommunityServer.atomicJson(file,Map.of("format",1,"boards",saved));}
        catch(IOException e) {throw new IllegalStateException("Could not save minigame records; player bests are still stored in player data.",e);}
    }
    static void sync(ServerPlayerEntity player) {
        var records=get(player.getEntityWorld().getServer());
        var scores=GameModes.state(player).getCompoundOrEmpty("scores");boolean changed=false;
        for(String id:MAP_IDS) {
            long best=scores.getLong(id,Long.MAX_VALUE);
            if(best>0 && best<Long.MAX_VALUE)changed|=records.upsert(id,new Score(player.getUuid(),player.getName().getString(),best));
        }
        if(changed)records.save();
    }
    List<Score> ordered(String id) {
        return boards.get(id).values().stream()
            .sorted(Comparator.comparingLong(Score::millis).thenComparing(s->s.name().toLowerCase(Locale.ROOT)).thenComparing(s->s.player().toString())).toList();
    }
    static int rankAt(List<Score> scores,int index) {
        while(index>0 && scores.get(index-1).millis()==scores.get(index).millis())index--;
        return index+1;
    }
    static String time(long millis) {return String.format(Locale.ROOT,"%.2f",millis/1000.0)+"s";}
    static int best(ServerPlayerEntity player) {
        var scores=GameModes.state(player).getCompoundOrEmpty("scores");
        CommunityServer.say(player,"Your minigame bests:");
        for(String id:MAP_IDS) {
            long best=scores.getLong(id,Long.MAX_VALUE);
            CommunityServer.say(player,ModeMaps.MAPS.get(id).title()+": "+(best>0 && best<Long.MAX_VALUE?time(best):"not finished")+" | /minigame "+id);
        }
        return 1;
    }
    static int leaderboard(ServerPlayerEntity player,String id) {
        if(id==null)return CommunityServer.say(player,"Choose a map: /leaderboard parkour, sprint, dropper or redlight. /best shows your own times.");
        try {sync(player);}catch(IllegalStateException e) {return CommunityServer.say(player,"Leaderboard is temporarily unavailable. Your personal best is still available with /best.");}
        var scores=get(player.getEntityWorld().getServer()).ordered(id);
        if(scores.isEmpty())return CommunityServer.say(player,ModeMaps.MAPS.get(id).title()+": no finishes yet. /minigame "+id+" to set a time.");
        CommunityServer.say(player,ModeMaps.MAPS.get(id).title()+" top times:");
        for(int i=0;i<Math.min(5,scores.size());i++) {
            var score=scores.get(i);
            CommunityServer.say(player,"#"+rankAt(scores,i)+" "+score.name()+" — "+time(score.millis()));
        }
        return 1;
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(CommandManager.literal("best").executes(ctx->best(ctx.getSource().getPlayerOrThrow())));
            var root=CommandManager.literal("leaderboard").executes(ctx->leaderboard(ctx.getSource().getPlayerOrThrow(),null));
            for(String id:MAP_IDS)root.then(CommandManager.literal(id).executes(ctx->leaderboard(ctx.getSource().getPlayerOrThrow(),id)));
            dispatcher.register(root);
        });
    }
}

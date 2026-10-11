package dev.convergence;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.permission.Permission.Level;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

/** Free advancement ranks and optional temporary owner overrides are UUID-bound. */
final class Memberships {
    enum Tier { FREE, GO, PLUS, PRO, ULTRA }
    record Milestone(String task, Identifier advancement) {}
    record RankGoal(Tier tier, List<Milestone> milestones) {}
    static Milestone task(String description,String path) {return new Milestone(description,Identifier.of("minecraft",path));}
    static final List<RankGoal> GOALS=List.of(
        new RankGoal(Tier.GO,List.of(
            task("get a stone pickaxe","story/upgrade_tools"),
            task("get an iron ingot","story/smelt_iron"),
            task("kill a monster","adventure/kill_a_mob"))),
        new RankGoal(Tier.PLUS,List.of(
            task("get an iron pickaxe","story/iron_tools"),
            task("get a diamond","story/mine_diamond"),
            task("enchant an item","story/enchant_item"))),
        new RankGoal(Tier.PRO,List.of(
            task("enter the Nether","story/enter_the_nether"),
            task("get a blaze rod","nether/obtain_blaze_rod"),
            task("enter the End","story/enter_the_end"))),
        new RankGoal(Tier.ULTRA,List.of(
            task("defeat the Ender Dragon","end/kill_dragon"),
            task("enter an End gateway","end/enter_end_gateway"),
            task("find an End city","end/find_end_city"))));
    static final Map<MinecraftServer,Memberships> INSTANCES=new WeakHashMap<>();
    static final class Account {
        Tier tier=Tier.FREE; long expires=0; Tier earned=Tier.FREE;
        boolean admin=false; int attempts=0; long lockedUntil=0;
    }
    static final class Data { int format=1; Map<String,Account> accounts=new TreeMap<>(); }
    static final class Config {
        boolean adminCodeEnabled=false;
        String adminCodeSha256="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    }
    final MinecraftServer server; final Path file; final Path configFile;
    Data data=new Data(); Config config=new Config();
    Memberships(MinecraftServer server,Path file,Path configFile) {
        this.server=server;this.file=file;this.configFile=configFile;
        try {
            if(Files.exists(file)) {
                data=CommunityServer.GSON.fromJson(Files.readString(file),Data.class);
                if(data==null||data.format!=1||data.accounts==null)throw new IllegalArgumentException("Invalid member data");
                for(var entry:data.accounts.entrySet()) {UUID.fromString(entry.getKey());var a=entry.getValue();if(a==null||a.tier==null||a.expires<0||a.attempts<0)throw new IllegalArgumentException("Invalid member");if(a.earned==null)a.earned=Tier.FREE;}
            }
            if(Files.exists(configFile))config=CommunityServer.GSON.fromJson(Files.readString(configFile),Config.class);
            else CommunityServer.atomicJson(configFile,config);
            if(config==null||config.adminCodeSha256==null||!config.adminCodeSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid Admin code hash");
        } catch(Exception e) {throw new IllegalStateException("Cannot load membership data/config. Original files were not overwritten.",e);}
    }
    static Memberships get(MinecraftServer server) {return INSTANCES.computeIfAbsent(server,s->new Memberships(s,s.getSavePath(WorldSavePath.ROOT).resolve("infinity-memberships.json"),s.getRunDirectory().resolve("config/infinity-memberships.json")));}
    Account account(UUID id) {return data.accounts.computeIfAbsent(id.toString(),key->new Account());}
    Tier tier(UUID id) {
        var player=server.getPlayerManager().getPlayer(id);if(player!=null&&operator(player))return Tier.ULTRA;
        var a=account(id);var override=a.expires>System.currentTimeMillis()?a.tier:Tier.FREE;
        Tier earned=a.earned;
        if(player!=null&&RewardTrades.rank(player).ordinal()>earned.ordinal())earned=RewardTrades.rank(player);
        return override.ordinal()>earned.ordinal()?override:earned;
    }
    String label(UUID id) {var player=server.getPlayerManager().getPlayer(id);if(player!=null&&operator(player))return "OP";return account(id).admin?"ADMIN":tier(id).name();}
    void save() {try {if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".previous"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);CommunityServer.atomicJson(file,data);}catch(Exception e){throw new IllegalStateException("Cannot save memberships",e);}}
    boolean done(ServerPlayerEntity p,Milestone milestone) {
        AdvancementEntry advancement=server.getAdvancementLoader().get(milestone.advancement());
        return advancement!=null&&p.getAdvancementTracker().getProgress(advancement).isDone();
    }
    void syncAchievements(ServerPlayerEntity p) {
        var a=account(p.getUuid());
        for(int i=GOALS.size()-1;i>=0;i--) {
            var goal=GOALS.get(i);
            if(goal.tier().ordinal()<=a.earned.ordinal())break;
            if(!goal.milestones().stream().allMatch(milestone->done(p,milestone)))continue;
            a.earned=goal.tier();save();badge(p);
            CommunityServer.say(p,"Achievement rank unlocked: "+a.earned+"! Your cosmetic badge is permanent. /rank shows your progress.");
            break;
        }
    }
    static String hash(String code) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    boolean redeem(UUID id,String code,long now) {
        var a=account(id);
        if(!config.adminCodeEnabled||a.lockedUntil>now||code.length()>128)return false;
        if(a.lockedUntil!=0){if(a.attempts>=3)a.attempts=0;a.lockedUntil=0;}
        boolean matches=MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.US_ASCII),config.adminCodeSha256.getBytes(StandardCharsets.US_ASCII));
        if(matches){a.admin=true;a.attempts=0;a.lockedUntil=0;save();return true;}
        a.attempts++;a.lockedUntil=now+(a.attempts>=3?15*60_000:10_000);save();return false;
    }
    static boolean owner(ServerCommandSource source) {return source.getPermissions().hasPermission(new Level(PermissionLevel.OWNERS));}
    static boolean operator(ServerPlayerEntity player) {return owner(player.getCommandSource());}
    static boolean admin(ServerCommandSource source) {return owner(source)||(source.getEntity() instanceof ServerPlayerEntity p&&get(source.getServer()).account(p.getUuid()).admin);}
    void badge(ServerPlayerEntity p) {
        var board=server.getScoreboard();String label=label(p.getUuid());String name="infinity_"+label.toLowerCase(Locale.ROOT);
        var previous=board.getScoreHolderTeam(p.getNameForScoreboard());
        if(previous!=null&&!previous.getName().startsWith("infinity_"))return;
        var team=board.getTeam(name);if(team==null) {team=board.addTeam(name);team.setPrefix(Text.literal("["+label+"] "));team.setColor(switch(label){case "GO"->Formatting.GREEN;case "PLUS"->Formatting.AQUA;case "PRO"->Formatting.LIGHT_PURPLE;case "ULTRA"->Formatting.GOLD;case "ADMIN","OP"->Formatting.RED;default->Formatting.GRAY;});}
        if(previous!=team){board.addScoreHolderToTeam(p.getNameForScoreboard(),team);server.getCommandManager().sendCommandTree(p);}
    }
    int rank(ServerPlayerEntity p) {
        syncAchievements(p);var a=account(p.getUuid());
        String temporary=a.expires>System.currentTimeMillis()&&a.tier!=Tier.FREE?
            "; temporary owner override: "+a.tier+" until "+java.time.Instant.ofEpochMilli(a.expires):"";
        String progress="";
        Tier permanent=RewardTrades.rank(p).ordinal()>a.earned.ordinal()?RewardTrades.rank(p):a.earned;
        for(var goal:GOALS)if(goal.tier().ordinal()>permanent.ordinal()) {
            var missing=goal.milestones().stream().filter(milestone->!done(p,milestone)).map(Milestone::task).toList();
            progress=". Next: "+goal.tier()+" "+(goal.milestones().size()-missing.size())+"/"+goal.milestones().size()+
                " complete; remaining: "+String.join(", ",missing);
            break;
        }
        return CommunityServer.say(p,"Your rank: "+label(p.getUuid())+". Permanent: "+permanent+temporary+progress+". All game modes are free; /ranks lists milestones; /trades shows item alternatives.");
    }
    static int moderate(ServerCommandSource source,ServerPlayerEntity target,String action) {
        if(!owner(source)&&(admin(target.getCommandSource())||CommunityServer.staff(target.getCommandSource())))return CommunityServer.info(source,"Staff accounts are protected. Ask the owner to resolve this.");
        var c=CommunityServer.get(source.getServer());
        if(action.equals("kick"))target.networkHandler.disconnect(Text.literal("Removed by Infinity staff. Please review the server rules."));
        else {if(action.equals("mute"))c.data.mutedUntil.put(target.getUuidAsString(),System.currentTimeMillis()+600_000);else c.data.mutedUntil.remove(target.getUuidAsString());c.save();}
        System.out.println("[Infinity audit] "+source.getName()+" used "+action+" on "+target.getNameForScoreboard());
        return CommunityServer.info(source,"Staff action applied: "+action+". Public-chat mutes last 10 minutes.");
    }
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(Memberships::get);
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{var s=get(server);s.syncAchievements(handler.player);s.badge(handler.player);});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(server.getTicks()%100==0){var s=get(server);for(var p:server.getPlayerManager().getPlayerList()){s.syncAchievements(p);s.badge(p);}}});
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(CommandManager.literal("ranks").executes(ctx->{
                CommunityServer.info(ctx.getSource(),"All ranks are free, permanent cosmetic badges. Complete all three server advancements listed for a rank, or exchange Survival items with /trades. Your highest permanent rank shows. FREE includes every game mode.");
                for(var goal:GOALS)CommunityServer.info(ctx.getSource(),goal.tier()+": "+String.join("; ",goal.milestones().stream().map(Milestone::task).toList()));
                return CommunityServer.info(ctx.getSource(),"ADMIN requires a private code and grants limited /staff tools. /rank shows your progress.");
            }));
            dispatcher.register(CommandManager.literal("rank").executes(ctx->get(ctx.getSource().getServer()).rank(ctx.getSource().getPlayerOrThrow())));
            dispatcher.register(CommandManager.literal("subscribe").executes(ctx->{
                CommunityServer.info(ctx.getSource(),"Planned optional supporter subscriptions (USD/month): Go $50, Plus $75, Pro $100, Ultra $200.");
                return CommunityServer.info(ctx.getSource(),"Checkout is unavailable until the owner sets up Tebex; this command cannot charge you. Permanent cosmetic ranks remain free through achievements or Survival item trades. No game modes, powers, Admin, or OP require a purchase.");
            }));
            dispatcher.register(CommandManager.literal("admincode").then(CommandManager.argument("code",StringArgumentType.greedyString()).executes(ctx->{var p=ctx.getSource().getPlayerOrThrow();var s=get(ctx.getSource().getServer());boolean ok=s.redeem(p.getUuid(),StringArgumentType.getString(ctx,"code"),System.currentTimeMillis());if(ok)s.badge(p);return CommunityServer.say(p,ok?"Free Admin rank unlocked. /staff kick, mute, unmute. This does not grant operator commands.":"Code rejected or temporarily locked. Retry later or ask the owner.");})));
            var staff=CommandManager.literal("staff").requires(Memberships::admin);
            for(String action:List.of("kick","mute","unmute"))staff.then(CommandManager.literal(action).then(CommandManager.argument("player",EntityArgumentType.player()).executes(ctx->moderate(ctx.getSource(),EntityArgumentType.getPlayer(ctx,"player"),action))));
            dispatcher.register(staff);
            var grant=CommandManager.literal("grant").then(CommandManager.argument("player",EntityArgumentType.player()));
            var player=CommandManager.argument("player",EntityArgumentType.player());
            for(var tier:Tier.values()) if(tier!=Tier.FREE)player.then(CommandManager.literal(tier.name().toLowerCase(Locale.ROOT)).then(CommandManager.argument("days",IntegerArgumentType.integer(1,3660)).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgumentType.getPlayer(ctx,"player");var a=s.account(p.getUuid());a.tier=tier;a.expires=System.currentTimeMillis()+IntegerArgumentType.getInteger(ctx,"days")*86_400_000L;s.save();s.badge(p);return CommunityServer.info(ctx.getSource(),"Temporary cosmetic rank override granted. Earned achievement ranks remain permanent.");})));
            dispatcher.register(CommandManager.literal("membership").requires(Memberships::owner)
                .then(CommandManager.literal("grant").then(player))
                .then(CommandManager.literal("revoke").then(CommandManager.argument("player",EntityArgumentType.player()).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgumentType.getPlayer(ctx,"player");var a=s.account(p.getUuid());a.tier=Tier.FREE;a.expires=0;s.save();s.badge(p);return CommunityServer.info(ctx.getSource(),"Temporary override removed. Earned achievement rank remains.");})))
                .then(CommandManager.literal("revokeadmin").then(CommandManager.argument("player",EntityArgumentType.player()).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgumentType.getPlayer(ctx,"player");s.account(p.getUuid()).admin=false;s.save();s.badge(p);return CommunityServer.info(ctx.getSource(),"Admin role revoked. Disable/change the shared code to prevent redemption again.");}))));
        });
    }
}

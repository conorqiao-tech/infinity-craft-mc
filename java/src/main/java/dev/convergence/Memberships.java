package dev.convergence;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.Optional;
import net.minecraft.world.scores.TeamColor;
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
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission.HasCommandLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.storage.LevelResource;

/** Free advancement ranks and optional temporary owner overrides are UUID-bound. */
final class Memberships {
    enum Tier { FREE, GO, PLUS, PRO, ULTRA }
    record Milestone(String task, Identifier advancement) {}
    record RankGoal(Tier tier, List<Milestone> milestones) {}
    static Milestone task(String description,String path) {return new Milestone(description,Identifier.fromNamespaceAndPath("minecraft",path));}
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
        // This UUID-keyed journal owns only the promotion performed for this role.
        boolean adminOpOwned=false, adminOpRevoking=false;
        int adminPreviousOpLevel=-1;
        boolean adminPreviousBypass=false;
        String adminOperatorName="";
    }
    static final class Data { int format=1; Map<String,Account> accounts=new TreeMap<>(); }
    static final class Config {
        boolean adminCodeEnabled=false;
        String adminCodeSha256="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    }
    final MinecraftServer server; final Path file; final Path configFile;
    final Map<UUID,ServerOpListEntry> adminOwnedEntries=new HashMap<>();
    Data data=new Data(); Config config=new Config();
    Memberships(MinecraftServer server,Path file,Path configFile) {
        this.server=server;this.file=file;this.configFile=configFile;
        try {
            if(Files.exists(file)) {
                data=CommunityServer.GSON.fromJson(Files.readString(file),Data.class);
                if(data==null||data.format!=1||data.accounts==null)throw new IllegalArgumentException("Invalid member data");
                for(var entry:data.accounts.entrySet()) {
                    UUID.fromString(entry.getKey());var a=entry.getValue();
                    if(a==null||a.tier==null||a.expires<0||a.attempts<0)throw new IllegalArgumentException("Invalid member");
                    if(a.earned==null)a.earned=Tier.FREE;
                    if(a.adminOperatorName==null)a.adminOperatorName="";
                    if(a.adminOpOwned&&(a.adminPreviousOpLevel < -1 || a.adminPreviousOpLevel > 3
                        ||a.adminOperatorName.isEmpty()||a.adminOperatorName.length()>128
                        ||a.adminOperatorName.codePoints().anyMatch(Character::isISOControl))
                        ||a.adminOpRevoking&&!a.adminOpOwned)throw new IllegalArgumentException("Invalid Admin operator provenance");
                }
            }
            if(Files.exists(configFile))config=CommunityServer.GSON.fromJson(Files.readString(configFile),Config.class);
            else CommunityServer.atomicJson(configFile,config);
            if(config==null||config.adminCodeSha256==null||!config.adminCodeSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid Admin code hash");
        } catch(Exception e) {throw new IllegalStateException("Cannot load membership data/config. Original files were not overwritten.",e);}
    }
    static Memberships get(MinecraftServer server) {return INSTANCES.computeIfAbsent(server,s->new Memberships(s,s.getWorldPath(LevelResource.ROOT).resolve("infinity-memberships.json"),s.getServerDirectory().resolve("config/infinity-memberships.json")));}
    Account account(UUID id) {return data.accounts.computeIfAbsent(id.toString(),key->new Account());}
    Tier tier(UUID id) {
        var player=server.getPlayerList().getPlayer(id);if(player!=null&&operator(player))return Tier.ULTRA;
        var a=account(id);var override=a.expires>System.currentTimeMillis()?a.tier:Tier.FREE;
        Tier earned=permanentTier(id);
        return override.ordinal()>earned.ordinal()?override:earned;
    }
    Tier permanentTier(UUID id) {
        var earned=account(id).earned;var player=server.getPlayerList().getPlayer(id);
        if(player!=null&&RewardTrades.rank(player).ordinal()>earned.ordinal())earned=RewardTrades.rank(player);
        return earned;
    }
    String label(UUID id) {if(account(id).admin)return "ADMIN";var player=server.getPlayerList().getPlayer(id);if(player!=null&&operator(player))return "OP";return tier(id).name();}
    void save() {try {if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".previous"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);CommunityServer.atomicJson(file,data);}catch(Exception e){throw new IllegalStateException("Cannot save memberships",e);}}
    boolean done(ServerPlayer p,Milestone milestone) {
        AdvancementHolder advancement=server.getAdvancements().get(milestone.advancement());
        return advancement!=null&&p.getAdvancements().getOrStartProgress(advancement).isDone();
    }
    void syncAchievements(ServerPlayer p) {
        var a=account(p.getUUID());
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
    private static Account copy(Account account) {return CommunityServer.GSON.fromJson(CommunityServer.GSON.toJson(account),Account.class);}
    private static int opLevel(ServerOpListEntry entry) {return entry==null?-1:entry.permissions().level().id();}
    private NameAndId principal(UUID id) {
        var online=server.getPlayerList().getPlayer(id);
        return online!=null?new NameAndId(online.getGameProfile()):new NameAndId(id,account(id).adminOperatorName);
    }
    private ServerOpListEntry opEntry(UUID id) {return server.getPlayerList().getOps().get(principal(id));}
    private void clearPromotion(UUID id,Account account) {
        account.adminOpOwned=false;account.adminOpRevoking=false;account.adminPreviousOpLevel=-1;
        account.adminPreviousBypass=false;account.adminOperatorName="";
    }
    private boolean ownedPromotion(UUID id,Account account,ServerOpListEntry current) {
        var observed=adminOwnedEntries.get(id);
        return account.adminOpOwned&&opLevel(current)==4&&current.getBypassesPlayerLimit()==account.adminPreviousBypass
            &&(observed==null||observed==current);
    }
    private boolean saveBeforeOperatorChange(UUID id,Account before) {
        try {save();return true;}
        catch(IllegalStateException failure) {
            data.accounts.put(id.toString(),before);
            System.err.println("[Infinity] Admin permission change stopped because membership persistence failed.");
            return false;
        }
    }
    private void writeOperator(NameAndId principal,int level,boolean bypass) throws java.io.IOException {
        var manager=server.getPlayerList();
        if(level<0)manager.deop(principal);
        else manager.op(principal,Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level))),Optional.of(bypass));
        // Vanilla's convenience methods log IO failures. Verify persistence explicitly.
        manager.getOps().save();
    }

    /** Called only after owner authorization or a verified private code. Persist the intent first. */
    boolean grantAdmin(UUID id) {
        var account=account(id);var before=copy(account);var current=opEntry(id);
        var online=server.getPlayerList().getPlayer(id);
        boolean needsPromotion=online!=null&&opLevel(current)<4;
        if(account.adminOpOwned&&!ownedPromotion(id,account,current))clearPromotion(id,account);
        account.admin=true;account.attempts=0;account.lockedUntil=0;account.adminOpRevoking=false;
        if(needsPromotion) {
            account.adminOpOwned=true;account.adminPreviousOpLevel=opLevel(current);
            account.adminPreviousBypass=current!=null&&current.getBypassesPlayerLimit();
            account.adminOperatorName=online.getGameProfile().name();
        }
        if(!saveBeforeOperatorChange(id,before))return false;
        if(needsPromotion) {
            try {writeOperator(new NameAndId(online.getGameProfile()),4,account.adminPreviousBypass);}
            catch(java.io.IOException|RuntimeException failure) {
                // The durable role intent can retry on login, but no failed write leaves a new live OP.
                try {writeOperator(new NameAndId(online.getGameProfile()),opLevel(current),current!=null&&current.getBypassesPlayerLimit());}
                catch(java.io.IOException|RuntimeException rollback) {System.err.println("[Infinity] Operator persistence needs host repair; Admin promotion was not completed.");}
                System.err.println("[Infinity] Admin promotion stopped because operator persistence failed.");
                return false;
            }
        }
        if(account.adminOpOwned&&opLevel(opEntry(id))==4)adminOwnedEntries.put(id,opEntry(id));
        else adminOwnedEntries.remove(id);
        if(online!=null)badge(online);
        return true;
    }

    /** Removes only a role-owned promotion; independent/manual operator grants survive. */
    boolean revokeAdmin(UUID id) {
        var account=account(id);var before=copy(account);var current=opEntry(id);
        boolean undo=ownedPromotion(id,account,current);
        account.admin=false;
        if(undo)account.adminOpRevoking=true;
        else clearPromotion(id,account);
        if(!saveBeforeOperatorChange(id,before))return false;
        if(undo) {
            try {writeOperator(principal(id),account.adminPreviousOpLevel,account.adminPreviousBypass);}
            catch(java.io.IOException|RuntimeException failure) {
                // Keep the persisted revocation journal so restart/login retries it before play.
                System.err.println("[Infinity] Admin revocation remains pending because operator persistence failed.");
                return false;
            }
            clearPromotion(id,account);
            try {save();}
            catch(IllegalStateException failure) {
                // The previous saved journal is sufficient to complete recovery after restart.
                account.adminOpOwned=true;account.adminOpRevoking=true;
                account.adminPreviousOpLevel=before.adminPreviousOpLevel;
                account.adminPreviousBypass=before.adminPreviousBypass;
                account.adminOperatorName=before.adminOperatorName;
                System.err.println("[Infinity] Admin was revoked; final journal cleanup will retry on login.");
                return false;
            }
        }
        adminOwnedEntries.remove(id);
        var online=server.getPlayerList().getPlayer(id);if(online!=null)badge(online);
        return true;
    }

    /** Migrates saved Admin roles and recovers interrupted revocations before a player can act. */
    boolean syncAdminOperator(ServerPlayer player) {
        var id=player.getUUID();var account=account(id);var current=opEntry(id);
        if(!account.admin&&(account.adminOpOwned||account.adminOpRevoking))return revokeAdmin(id);
        if(!account.admin)return true;
        if(opLevel(current)<4)return grantAdmin(id);
        if(account.adminOpOwned) {
            var observed=adminOwnedEntries.get(id);
            if(observed!=null&&observed!=current) {
                var before=copy(account);clearPromotion(id,account);
                if(!saveBeforeOperatorChange(id,before))return false;
                adminOwnedEntries.remove(id);
            } else adminOwnedEntries.put(id,current);
        }
        return true;
    }
    boolean redeem(UUID id,String code,long now) {
        var a=account(id);
        if(!config.adminCodeEnabled||a.lockedUntil>now||code.length()>128)return false;
        if(a.lockedUntil!=0){if(a.attempts>=3)a.attempts=0;a.lockedUntil=0;}
        boolean matches=MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.US_ASCII),config.adminCodeSha256.getBytes(StandardCharsets.US_ASCII));
        if(matches)return grantAdmin(id);
        a.attempts++;a.lockedUntil=now+(a.attempts>=3?15*60_000:10_000);save();return false;
    }
    static boolean owner(CommandSourceStack source) {return source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.OWNERS));}
    static boolean operator(ServerPlayer player) {return owner(player.createCommandSourceStack());}
    static boolean admin(CommandSourceStack source) {return owner(source)||(source.getEntity() instanceof ServerPlayer p&&get(source.getServer()).account(p.getUUID()).admin);}
    /** Gameplay bypass follows actual vanilla OP4; a failed promotion never grants it indirectly. */
    static boolean gameplayBypass(ServerPlayer player) {return operator(player);}
    static boolean unlimitedGameplay(ServerPlayer player) {return operator(player)&&get(player.level().getServer()).account(player.getUUID()).admin;}
    void badge(ServerPlayer p) {
        var board=server.getScoreboard();String label=label(p.getUUID());String name="infinity_"+label.toLowerCase(Locale.ROOT);
        var previous=board.getPlayersTeam(p.getScoreboardName());
        if(previous!=null&&!previous.getName().startsWith("infinity_"))return;
        var team=board.getPlayerTeam(name);if(team==null) {team=board.addPlayerTeam(name);team.setPlayerPrefix(Component.literal("["+label+"] "));team.setColor(Optional.of(switch(label){case "GO"->TeamColor.GREEN;case "PLUS"->TeamColor.AQUA;case "PRO"->TeamColor.LIGHT_PURPLE;case "ULTRA"->TeamColor.GOLD;case "ADMIN","OP"->TeamColor.RED;default->TeamColor.GRAY;}));}
        if(previous!=team){board.addPlayerToTeam(p.getScoreboardName(),team);server.getCommands().sendCommands(p);}
    }
    int rank(ServerPlayer p) {
        syncAchievements(p);var a=account(p.getUUID());
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
        return CommunityServer.say(p,"Your rank: "+label(p.getUUID())+". Permanent: "+permanent+temporary+progress+". All game modes are free; /ranks lists milestones; /trades shows item alternatives.");
    }
    static int moderate(CommandSourceStack source,ServerPlayer target,String action) {
        if(!owner(source)&&(admin(target.createCommandSourceStack())||CommunityServer.staff(target.createCommandSourceStack())))return CommunityServer.info(source,"Staff accounts are protected. Ask the owner to resolve this.");
        var c=CommunityServer.get(source.getServer());
        if(action.equals("kick"))target.connection.disconnect(Component.literal("Removed by Infinity staff. Please review the server rules."));
        else {if(action.equals("mute"))c.data.mutedUntil.put(target.getStringUUID(),System.currentTimeMillis()+600_000);else c.data.mutedUntil.remove(target.getStringUUID());c.save();}
        System.out.println("[Infinity audit] "+source.getTextName()+" used "+action+" on "+target.getScoreboardName());
        return CommunityServer.info(source,"Staff action applied: "+action+". Public-chat mutes last 10 minutes.");
    }
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server->{var s=get(server);for(var entry:new ArrayList<>(s.data.accounts.entrySet()))if(!entry.getValue().admin&&entry.getValue().adminOpOwned)s.revokeAdmin(UUID.fromString(entry.getKey()));});
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{var s=get(server);if(!s.syncAdminOperator(handler.player)){handler.disconnect(Component.literal("Admin permissions could not be saved safely. Ask the host to check server storage."));return;}s.syncAchievements(handler.player);s.badge(handler.player);});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(server.getTickCount()%100==0){var s=get(server);for(var p:server.getPlayerList().getPlayers()){s.syncAdminOperator(p);s.syncAchievements(p);s.badge(p);}}});
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(Commands.literal("ranks").executes(ctx->{
                CommunityServer.info(ctx.getSource(),"All ranks are free, permanent cosmetic badges. Complete all three server advancements listed for a rank, or exchange Survival items with /trades. Your highest permanent rank shows. FREE includes every game mode.");
                for(var goal:GOALS)CommunityServer.info(ctx.getSource(),goal.tier()+": "+String.join("; ",goal.milestones().stream().map(Milestone::task).toList()));
                return CommunityServer.info(ctx.getSource(),"ADMIN requires a private code or owner grant and includes full OP4 commands and gameplay unlocks. AI server changes still need both approvals. /rank shows your progress.");
            }));
            dispatcher.register(Commands.literal("rank").executes(ctx->get(ctx.getSource().getServer()).rank(ctx.getSource().getPlayerOrException())));
            dispatcher.register(Commands.literal("subscribe").executes(ctx->{
                CommunityServer.info(ctx.getSource(),"Planned optional supporter subscriptions (USD/month): Go $50, Plus $75, Pro $100, Ultra $200.");
                return CommunityServer.info(ctx.getSource(),"Checkout is unavailable until the owner sets up Tebex; this command cannot charge you. Permanent cosmetic ranks remain free through achievements or Survival item trades. No game modes, powers, Admin, or OP require a purchase.");
            }));
            dispatcher.register(Commands.literal("admincode").then(Commands.argument("code",StringArgumentType.greedyString()).executes(ctx->{var p=ctx.getSource().getPlayerOrException();var s=get(ctx.getSource().getServer());boolean ok=s.redeem(p.getUUID(),StringArgumentType.getString(ctx,"code"),System.currentTimeMillis());if(ok)s.badge(p);return CommunityServer.say(p,ok?"Admin unlocked: full OP level 4, gameplay unlocks and no Infinity ability cooldowns. AI server changes still require your approval and live Codex approval.":"Code rejected, temporarily locked, or permissions could not be saved. Ask the owner if retrying later does not help.");})));
            var staff=Commands.literal("staff").requires(Memberships::admin);
            for(String action:List.of("kick","mute","unmute"))staff.then(Commands.literal(action).then(Commands.argument("player",EntityArgument.player()).executes(ctx->moderate(ctx.getSource(),EntityArgument.getPlayer(ctx,"player"),action))));
            dispatcher.register(staff);
            var grant=Commands.literal("grant").then(Commands.argument("player",EntityArgument.player()));
            var player=Commands.argument("player",EntityArgument.player());
            for(var tier:Tier.values()) if(tier!=Tier.FREE)player.then(Commands.literal(tier.name().toLowerCase(Locale.ROOT)).then(Commands.argument("days",IntegerArgumentType.integer(1,3660)).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgument.getPlayer(ctx,"player");var a=s.account(p.getUUID());a.tier=tier;a.expires=System.currentTimeMillis()+IntegerArgumentType.getInteger(ctx,"days")*86_400_000L;s.save();s.badge(p);return CommunityServer.info(ctx.getSource(),"Temporary cosmetic rank override granted. Earned achievement ranks remain permanent.");})));
            dispatcher.register(Commands.literal("membership").requires(Memberships::owner)
                .then(Commands.literal("grant").then(player))
                .then(Commands.literal("grantadmin").then(Commands.argument("player",EntityArgument.player()).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgument.getPlayer(ctx,"player");return CommunityServer.info(ctx.getSource(),s.grantAdmin(p.getUUID())?"Admin and full OP4 granted. AI actions still need both approvals.":"Admin grant could not be saved safely. Check server storage.");})))
                .then(Commands.literal("revoke").then(Commands.argument("player",EntityArgument.player()).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgument.getPlayer(ctx,"player");var a=s.account(p.getUUID());a.tier=Tier.FREE;a.expires=0;s.save();s.badge(p);return CommunityServer.info(ctx.getSource(),"Temporary override removed. Earned achievement rank remains.");})))
                .then(Commands.literal("revokeadmin").then(Commands.argument("player",EntityArgument.player()).executes(ctx->{var s=get(ctx.getSource().getServer());var p=EntityArgument.getPlayer(ctx,"player");return CommunityServer.info(ctx.getSource(),s.revokeAdmin(p.getUUID())?"Admin revoked; only its owned OP promotion was removed. Independent OP grants remain. Disable/change the private code to prevent redemption again.":"Admin revocation could not finish safely; inspect server storage before retrying.");}))));
        });
    }
}

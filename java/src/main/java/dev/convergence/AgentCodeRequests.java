package dev.convergence;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/** Durable review requests, never executable patches or shell input. */
final class AgentCodeRequests {
    static final long LIFETIME_MS = 24 * 60 * 60 * 1000L;
    static final int PER_OWNER = 3, TOTAL = 16, HISTORY = 32, MAX_BYTES = 131_072;
    static final Map<MinecraftServer, AgentCodeRequests> INSTANCES = new WeakHashMap<>();
    enum State { PENDING, OWNER_APPROVED, REVIEWING, COMPLETED, CANCELLED, EXPIRED }
    static final class Request {
        String owner, helperId, helperName, text, commit = "";
        State state = State.PENDING;
        long createdAt;
        Request(String owner, String helperId, String helperName, String text, long createdAt) {
            this.owner=owner;this.helperId=helperId;this.helperName=helperName;this.text=text;this.createdAt=createdAt;
        }
        boolean active() { return state==State.PENDING||state==State.OWNER_APPROVED||state==State.REVIEWING; }
        boolean valid() {
            try { UUID.fromString(owner);UUID.fromString(helperId); } catch(RuntimeException e) { return false; }
            return AgentCompanions.validName(helperName)&&validText(text)&&state!=null&&createdAt>0&&commit!=null
                && (state==State.COMPLETED?commit.matches("[a-f0-9]{40}"):commit.isEmpty());
        }
    }
    static final class Data { int format=1;Map<String,Request> requests=new LinkedHashMap<>(); }
    final MinecraftServer server;final Path file;final Clock clock;final Data data;
    AgentCodeRequests(MinecraftServer server,Path file,Clock clock) { this.server=server;this.file=file;this.clock=clock;data=read(file); }
    static AgentCodeRequests get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,s->new AgentCodeRequests(s,s.getSavePath(WorldSavePath.ROOT).resolve("infinity-agent-code-requests.json"),Clock.systemUTC()));
    }
    static boolean validText(String text) {
        return text!=null&&!text.isBlank()&&text.length()<=500&&text.codePoints().noneMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT);
    }
    static Data read(Path file) {
        if(!Files.exists(file))return new Data();
        try {
            if(Files.size(file)>MAX_BYTES)throw new IllegalArgumentException("Code request queue is too large");
            byte[] bytes;
            try(var input=Files.newInputStream(file)){bytes=input.readNBytes(MAX_BYTES+1);}
            if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Code request queue is too large");
            var json=com.google.gson.JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if(!json.isJsonObject()||!json.getAsJsonObject().has("format")||!json.getAsJsonObject().has("requests"))throw new IllegalArgumentException("Missing queue format");
            Data d=CommunityServer.GSON.fromJson(json,Data.class);
            if(d==null||d.format!=1||d.requests==null||d.requests.size()>TOTAL+HISTORY)throw new IllegalArgumentException("Invalid code request queue");
            var owners=new HashMap<String,Integer>();int active=0;
            for(var e:d.requests.entrySet()) {
                UUID.fromString(e.getKey());var q=e.getValue();
                if(q==null||!q.valid())throw new IllegalArgumentException("Invalid code request");
                if(q.active()) { active++;if(owners.merge(q.owner,1,Integer::sum)>PER_OWNER)throw new IllegalArgumentException("Owner code request limit"); }
            }
            if(active>TOTAL||d.requests.size()-active>HISTORY)throw new IllegalArgumentException("Code request limit");return d;
        } catch(Exception e) { throw new IllegalStateException("Cannot read "+file+". Restore its .previous backup; the original was not overwritten.",e); }
    }
    void save() {
        try {
            if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".previous"),StandardCopyOption.REPLACE_EXISTING);
            CommunityServer.atomicJson(file,data);
        } catch(java.io.IOException e) { throw new IllegalStateException("Could not save code review requests.",e); }
    }
    void expire() {
        long now=clock.millis();boolean changed=false;
        for(var q:data.requests.values())if(q.active()&&(now<q.createdAt||now-q.createdAt>=LIFETIME_MS)) {q.state=State.EXPIRED;changed=true;}
        if(changed){trim();save();}
    }
    void trim() {
        var old=data.requests.entrySet().stream().filter(e->!e.getValue().active()).sorted(Comparator.comparingLong(e->e.getValue().createdAt)).map(Map.Entry::getKey).toList();
        for(int i=0;i<old.size()-HISTORY;i++)data.requests.remove(old.get(i));
    }
    boolean eligible(ServerPlayerEntity owner,Request q) {
        if(owner==null||!AgentCompanions.operator(owner.getCommandSource())||!q.owner.equals(owner.getUuidAsString()))return false;
        var agent=AgentCompanions.get(server).data.agents.get(q.helperId);
        return agent!=null&&agent.owner().equals(q.owner)&&agent.name().equals(q.helperName)&&agent.profile()==AgentCompanions.Profile.CLI;
    }
    int propose(ServerPlayerEntity owner,String name,String text) {
        if(!AgentCompanions.operator(owner.getCommandSource()))return AgentCompanions.reply(owner,"Only OP4 can request a code change.");
        var e=AgentCompanions.get(server).owned(owner,name);
        if(e==null||e.getValue().profile()!=AgentCompanions.Profile.CLI)return AgentCompanions.reply(owner,"Choose one of your CLI helpers with /agent profile <name> cli first.");
        if(!validText(text))return AgentCompanions.reply(owner,"Describe the code change in 1–500 plain-text characters. Do not include passwords or API keys.");
        expire();
        if(data.requests.values().stream().filter(Request::active).count()>=TOTAL||data.requests.values().stream().filter(q->q.active()&&q.owner.equals(owner.getUuidAsString())).count()>=PER_OWNER)
            return AgentCompanions.reply(owner,"The code review queue is full. Cancel or finish a request first.");
        String id=UUID.randomUUID().toString();data.requests.put(id,new Request(owner.getUuidAsString(),e.getKey(),name,text.strip(),clock.millis()));trim();save();
        return AgentCompanions.reply(owner,"Code request "+id+": "+text.strip()+". Review /agent code-pending, then /agent code-approve "+id+". Ask Codex here to review, edit and test it. No code changed.");
    }
    int pending(ServerPlayerEntity owner) {
        if(!AgentCompanions.operator(owner.getCommandSource()))return 0;expire();
        var rows=data.requests.entrySet().stream().filter(e->e.getValue().owner.equals(owner.getUuidAsString())).sorted((a,b)->Long.compare(b.getValue().createdAt,a.getValue().createdAt)).limit(8)
            .map(e->e.getKey()+" ["+e.getValue().helperName+", "+e.getValue().state+"]: "+e.getValue().text+(e.getValue().commit.isEmpty()?"":"; commit="+e.getValue().commit)).toList();
        return AgentCompanions.reply(owner,rows.isEmpty()?"No code requests. /agent code <name> <request> creates one.":String.join("\n",rows));
    }
    int approve(ServerPlayerEntity owner,String id) {
        expire();var q=data.requests.get(id);
        if(q==null||q.state!=State.PENDING||!eligible(owner,q))return AgentCompanions.reply(owner,"No pending code request from one of your CLI helpers is eligible.");
        q.state=State.OWNER_APPROVED;save();return AgentCompanions.reply(owner,"Approved request "+id+". Codex must review the request and resulting diff, run tests, and record a commit. No code changed.");
    }
    int cancel(ServerPlayerEntity owner,String id) {
        if(!AgentCompanions.operator(owner.getCommandSource()))return 0;expire();var q=data.requests.get(id);
        if(q==null||!q.active()||!q.owner.equals(owner.getUuidAsString()))return AgentCompanions.reply(owner,"No active code request with that ID belongs to you.");
        q.state=State.CANCELLED;trim();save();return AgentCompanions.reply(owner,"Cancelled "+id+". Tell Codex if a live review is already underway; cancellation cannot undo edits made outside Minecraft.");
    }
    int consolePending(ServerCommandSource source) {
        if(!AgentActions.localConsole(source))return 0;expire();
        var rows=data.requests.entrySet().stream().filter(e->e.getValue().active()).map(e->e.getKey()+" owner="+e.getValue().owner+" helper="+e.getValue().helperName+" status="+e.getValue().state+" request="+e.getValue().text).toList();
        return CommunityServer.info(source,rows.isEmpty()?"No active code requests.":String.join("\n",rows));
    }
    int review(ServerCommandSource source,String id) {
        if(!AgentActions.localConsole(source))return 0;expire();var q=data.requests.get(id);
        if(q==null||q.state!=State.OWNER_APPROVED||!eligible(server.getPlayerManager().getPlayer(UUID.fromString(q.owner)),q))return CommunityServer.info(source,"No eligible owner-approved code request. The owner must be online with OP4 and the original CLI helper.");
        q.state=State.REVIEWING;save();return CommunityServer.info(source,"Reviewing "+id+": "+q.text+". This only records the review; inspect the diff and test source edits in the live Codex session before installing.");
    }
    int complete(ServerCommandSource source,String id,String commit) {
        if(!AgentActions.localConsole(source))return 0;expire();var q=data.requests.get(id);
        if(q==null||q.state!=State.REVIEWING||commit==null||!commit.matches("[a-f0-9]{40}")||!eligible(server.getPlayerManager().getPlayer(UUID.fromString(q.owner)),q))return CommunityServer.info(source,"No eligible review or valid full Git commit. Nothing recorded.");
        q.state=State.COMPLETED;q.commit=commit;trim();save();return CommunityServer.info(source,"Recorded reviewed source commit "+commit+" for "+id+". Minecraft did not edit, build or install code; use the tested upgrade procedure.");
    }
    static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(AgentCodeRequests::get);ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        CommandRegistrationCallback.EVENT.register((d,a,e)->{
            var root=CommandManager.literal("agent").requires(AgentCompanions::operator);
            root.then(CommandManager.literal("code").then(CommandManager.argument("name",StringArgumentType.word()).then(CommandManager.argument("request",StringArgumentType.greedyString()).executes(c->get(c.getSource().getServer()).propose(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"request"))))));
            root.then(CommandManager.literal("code-pending").executes(c->get(c.getSource().getServer()).pending(c.getSource().getPlayerOrThrow())));
            root.then(CommandManager.literal("code-approve").then(CommandManager.argument("id",StringArgumentType.word()).executes(c->get(c.getSource().getServer()).approve(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"id")))));
            root.then(CommandManager.literal("code-cancel").then(CommandManager.argument("id",StringArgumentType.word()).executes(c->get(c.getSource().getServer()).cancel(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"id")))));d.register(root);
            d.register(CommandManager.literal("agent-code-pending").requires(AgentActions::localConsole).executes(c->get(c.getSource().getServer()).consolePending(c.getSource())));
            d.register(CommandManager.literal("agent-code-review").requires(AgentActions::localConsole).then(CommandManager.argument("id",StringArgumentType.word()).executes(c->get(c.getSource().getServer()).review(c.getSource(),StringArgumentType.getString(c,"id")))));
            d.register(CommandManager.literal("agent-code-complete").requires(AgentActions::localConsole).then(CommandManager.argument("id",StringArgumentType.word()).then(CommandManager.argument("commit",StringArgumentType.word()).executes(c->get(c.getSource().getServer()).complete(c.getSource(),StringArgumentType.getString(c,"id"),StringArgumentType.getString(c,"commit"))))));
        });
    }
}

package dev.convergence;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.convergence.mixin.AgentConsoleSourceAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.permission.Permission.Level;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/** Owner-approved proposals; only a fresh local-console review can dispatch a fixed command. */
final class AgentActions {
    static final long LIFETIME_MS = 10 * 60 * 1000L;
    static final int MAX_ACTIVE_PER_OWNER = 3, MAX_ACTIVE = 16, MAX_HISTORY = 64;
    static final Map<MinecraftServer, AgentActions> INSTANCES = new WeakHashMap<>();

    enum Action {
        DAY("time set day"), NIGHT("time set night"), CLEAR("weather clear"),
        RAIN("weather rain"), PLAYERS("list"), SAVE("save-all");

        final String command;
        Action(String command) { this.command = command; }

        static Action fromRequest(String request) {
            if (request == null || request.length() > 120 || request.codePoints().anyMatch(Character::isISOControl)) return null;
            String plain = Normalizer.normalize(request, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
            return switch (plain) {
                case "day", "set day", "make it day", "set time to day" -> DAY;
                case "night", "set night", "make it night", "set time to night" -> NIGHT;
                case "clear weather", "clear the weather", "make weather clear" -> CLEAR;
                case "rain", "make it rain", "set weather to rain" -> RAIN;
                case "list players", "list online players", "who is online" -> PLAYERS;
                case "save world", "save the world", "save all" -> SAVE;
                default -> null;
            };
        }
    }

    enum State { PENDING, OWNER_APPROVED, DISPATCHED, CANCELLED, EXPIRED }

    static final class Proposal {
        String owner;
        Action action;
        State state;
        long createdAt;

        Proposal(String owner, Action action, long createdAt) {
            this.owner = owner; this.action = action; this.createdAt = createdAt; this.state = State.PENDING;
        }
        boolean active() { return state == State.PENDING || state == State.OWNER_APPROVED; }
        boolean valid() {
            try { UUID.fromString(owner); } catch (RuntimeException error) { return false; }
            return action != null && state != null && createdAt > 0;
        }
    }

    static final class Data {
        int format = 1;
        Map<String, Proposal> proposals = new LinkedHashMap<>();
    }

    final MinecraftServer server;
    final Path file;
    final Clock clock;
    final Consumer<String> dispatch;
    final Data data;

    AgentActions(MinecraftServer server, Path file, Clock clock, Consumer<String> dispatch) {
        this.server = server; this.file = file; this.clock = clock; this.dispatch = dispatch; this.data = read(file);
    }

    static AgentActions get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new AgentActions(s,
            s.getSavePath(WorldSavePath.ROOT).resolve("infinity-agent-actions.json"), Clock.systemUTC(),
            command -> s.getCommandManager().parseAndExecute(s.getCommandSource(), command)));
    }

    static Data read(Path file) {
        if (!Files.exists(file)) return new Data();
        try {
            if (Files.size(file) > 65_536) throw new IOException("Action queue is too large");
            Data data = CommunityServer.GSON.fromJson(Files.readString(file), Data.class);
            if (data == null || data.format != 1 || data.proposals == null || data.proposals.size() > MAX_ACTIVE + MAX_HISTORY)
                throw new IOException("Invalid action queue");
            int active = 0;
            Map<String, Integer> perOwner = new LinkedHashMap<>();
            for (var entry : data.proposals.entrySet()) {
                UUID.fromString(entry.getKey());
                Proposal proposal = entry.getValue();
                if (proposal == null || !proposal.valid()) throw new IOException("Invalid action proposal");
                if (proposal.active()) {
                    active++;
                    if (perOwner.merge(proposal.owner, 1, Integer::sum) > MAX_ACTIVE_PER_OWNER) throw new IOException("Owner action limit exceeded");
                }
            }
            if (active > MAX_ACTIVE) throw new IOException("Action limit exceeded");
            return data;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read " + file + ". Restore its .previous backup; the original was not overwritten.", error);
        }
    }

    void save() {
        try {
            if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".previous"), StandardCopyOption.REPLACE_EXISTING);
            CommunityServer.atomicJson(file, data);
        } catch (IOException error) { throw new IllegalStateException("Could not save helper action queue. Check free disk space.", error); }
    }

    void expire() {
        long now = clock.millis(); boolean changed = false;
        for (Proposal proposal : data.proposals.values()) if (proposal.active()
            && (now < proposal.createdAt || now - proposal.createdAt >= LIFETIME_MS)) {
            proposal.state = State.EXPIRED; changed = true;
        }
        if (changed) { trimHistory(); save(); }
    }

    void trimHistory() {
        List<String> terminal = data.proposals.entrySet().stream().filter(entry -> !entry.getValue().active())
            .sorted(Comparator.comparingLong(entry -> entry.getValue().createdAt)).map(Map.Entry::getKey).toList();
        for (int i = 0; i < terminal.size() - MAX_HISTORY; i++) data.proposals.remove(terminal.get(i));
    }

    static boolean localConsole(ServerCommandSource source) {
        return source.getEntity() == null
            && !source.isSilent()
            && source.getPermissions().hasPermission(new Level(PermissionLevel.OWNERS))
            && ((AgentConsoleSourceAccess) source).infinity$getOutput() == source.getServer();
    }

    int suggest(ServerPlayerEntity owner, String request) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can propose a helper action.");
        Action action = Action.fromRequest(request);
        if (action == null) return AgentCompanions.reply(owner, "Supported requests: set day, set night, clear weather, make it rain, list players, save world. Use plain text only.");
        expire();
        long owned = data.proposals.values().stream().filter(p -> p.active() && p.owner.equals(owner.getUuidAsString())).count();
        long active = data.proposals.values().stream().filter(Proposal::active).count();
        if (owned >= MAX_ACTIVE_PER_OWNER || active >= MAX_ACTIVE) return AgentCompanions.reply(owner, "The helper action queue is full. Cancel or wait for a proposal to expire.");
        String id = UUID.randomUUID().toString();
        data.proposals.put(id, new Proposal(owner.getUuidAsString(), action, clock.millis()));
        trimHistory(); save();
        return AgentCompanions.reply(owner, "Proposed " + id + ": /" + action.command + ". Review it with /agent pending, then /agent approve " + id + ". Codex must review it live before it runs.");
    }

    int pending(ServerPlayerEntity owner) {
        expire();
        String uuid = owner.getUuidAsString();
        var lines = data.proposals.entrySet().stream().filter(e -> e.getValue().owner.equals(uuid))
            .sorted((left, right) -> Long.compare(right.getValue().createdAt, left.getValue().createdAt)).limit(8)
            .map(e -> e.getKey() + ": /" + e.getValue().action.command + " (" + e.getValue().state.name().toLowerCase(Locale.ROOT) + ")").toList();
        return AgentCompanions.reply(owner, lines.isEmpty() ? "No helper action proposals. /agent suggest <request> creates one." : String.join("\n", lines));
    }

    int ownerApprove(ServerPlayerEntity owner, String id) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can approve a helper action.");
        expire();
        Proposal proposal = data.proposals.get(id);
        if (proposal == null || !proposal.owner.equals(owner.getUuidAsString()) || proposal.state != State.PENDING)
            return AgentCompanions.reply(owner, "No pending helper action with that ID belongs to you.");
        proposal.state = State.OWNER_APPROVED; save();
        return AgentCompanions.reply(owner, "Owner approved " + id + ": /" + proposal.action.command + ". It will run only if Codex reviews and approves it live before expiry.");
    }

    int cancel(ServerPlayerEntity owner, String id) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can cancel a helper action.");
        expire();
        Proposal proposal = data.proposals.get(id);
        if (proposal == null || !proposal.owner.equals(owner.getUuidAsString()) || !proposal.active())
            return AgentCompanions.reply(owner, "No active helper action with that ID belongs to you.");
        proposal.state = State.CANCELLED; save();
        return AgentCompanions.reply(owner, "Cancelled " + id + ". The command will not run.");
    }

    int consolePending(ServerCommandSource source) {
        if (!localConsole(source)) return 0;
        expire();
        var lines = data.proposals.entrySet().stream().filter(e -> e.getValue().active())
            .map(e -> e.getKey() + " owner=" + e.getValue().owner + " command=/" + e.getValue().action.command
                + " status=" + e.getValue().state.name().toLowerCase(Locale.ROOT)).toList();
        return CommunityServer.info(source, lines.isEmpty() ? "No active helper action proposals." : String.join("\n", lines));
    }

    int codexApprove(ServerCommandSource source, String id) {
        if (!localConsole(source)) return 0;
        expire();
        Proposal proposal = data.proposals.get(id);
        if (proposal == null || proposal.state != State.OWNER_APPROVED)
            return CommunityServer.info(source, "No owner-approved helper action with that ID. Nothing ran.");
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(UUID.fromString(proposal.owner));
        if (owner == null || !AgentCompanions.operator(owner.getCommandSource()))
            return CommunityServer.info(source, "The proposing OP4 owner must still be online. Nothing ran.");
        // Commit the terminal state first. A crash can prevent dispatch, but cannot replay it.
        proposal.state = State.DISPATCHED;
        save();
        String command = proposal.action.command;
        try {
            dispatch.accept(command);
            AgentCompanions.reply(owner, "Codex approved " + id + "; dispatched /" + command + ".");
            return CommunityServer.info(source, "Dispatched " + id + ": /" + command + ". This ID cannot run again.");
        } catch (RuntimeException error) {
            return CommunityServer.info(source, "Dispatch failed after consuming " + id + ". Review the server log; this ID cannot run again.");
        }
    }

    static void attach(LiteralArgumentBuilder<ServerCommandSource> root) {
        root.then(CommandManager.literal("suggest").then(CommandManager.argument("request", StringArgumentType.greedyString())
            .executes(c -> get(c.getSource().getServer()).suggest(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "request")))));
        root.then(CommandManager.literal("pending").executes(c -> get(c.getSource().getServer()).pending(c.getSource().getPlayerOrThrow())));
        root.then(CommandManager.literal("approve").then(CommandManager.argument("id", StringArgumentType.word())
            .executes(c -> get(c.getSource().getServer()).ownerApprove(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "id")))));
        root.then(CommandManager.literal("cancel").then(CommandManager.argument("id", StringArgumentType.word())
            .executes(c -> get(c.getSource().getServer()).cancel(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "id")))));
    }

    static void registerConsole(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("agent-codex-pending").requires(AgentActions::localConsole)
            .executes(c -> get(c.getSource().getServer()).consolePending(c.getSource())));
        dispatcher.register(CommandManager.literal("agent-codex-approve").requires(AgentActions::localConsole)
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(c -> get(c.getSource().getServer()).codexApprove(c.getSource(), StringArgumentType.getString(c, "id")))));
    }

    static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(AgentActions::get);
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
    }
}

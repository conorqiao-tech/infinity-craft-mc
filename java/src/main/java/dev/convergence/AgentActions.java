package dev.convergence;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.convergence.mixin.AgentConsoleSourceAccess;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.permission.Permission.Level;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

/** Exact owner-approved actions; only a fresh local-console review can apply them. */
final class AgentActions {
    static final long LIFETIME_MS = 10 * 60 * 1000L;
    static final int MAX_ACTIVE_PER_OWNER = 3, MAX_ACTIVE = 16, MAX_HISTORY = 64;
    static final int MAX_BYTES = 131_072;
    static final Map<MinecraftServer, AgentActions> INSTANCES = new WeakHashMap<>();

    enum Action {
        DAY("time set day"), NIGHT("time set night"), CLEAR("weather clear"),
        RAIN("weather rain"), PLAYERS("list"), SAVE("save-all"), TARGET(null);

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
        String targetUuid, targetName, targetDimension, targetSession;

        Proposal(String owner, Action action, long createdAt) {
            this.owner = owner; this.action = action; this.createdAt = createdAt; this.state = State.PENDING;
        }
        boolean active() { return state == State.PENDING || state == State.OWNER_APPROVED; }
        boolean valid() {
            try { UUID.fromString(owner); } catch (RuntimeException error) { return false; }
            if (action == null || state == null || createdAt <= 0) return false;
            if (action != Action.TARGET)
                return targetUuid == null && targetName == null && targetDimension == null && targetSession == null;
            try { UUID.fromString(targetUuid); UUID.fromString(targetSession); }
            catch (RuntimeException error) { return false; }
            return validPlayerName(targetName) && targetDimension != null && targetDimension.length() <= 128 && Identifier.tryParse(targetDimension) != null;
        }
        String description() {
            return action == Action.TARGET
                ? "squad target=" + targetName + " uuid=" + targetUuid + " dimension=" + targetDimension
                    + " session=" + targetSession + " (Primitive / Ultimate Finals, at most five minutes)"
                : "/" + action.command;
        }
    }

    static final class Data {
        int format = 2;
        Map<String, Proposal> proposals = new LinkedHashMap<>();
    }

    final MinecraftServer server;
    final Path file;
    final Clock clock;
    final Consumer<String> dispatch;
    final Data data;
    // Entity object identity cannot survive logout, respawn, or restart. Never
    // reconstruct a target session from a persisted UUID or a reused player name.
    record TargetSession(ServerPlayerEntity owner, ServerPlayNetworkHandler ownerConnection,
                         ServerPlayerEntity target, ServerPlayNetworkHandler targetConnection, ServerWorld world,
                         String ownerUuid, String targetUuid, String targetName, String token) {}
    final Map<String, TargetSession> targetSessions = new LinkedHashMap<>();

    AgentActions(MinecraftServer server, Path file, Clock clock, Consumer<String> dispatch) {
        this.server = server; this.file = file; this.clock = clock; this.dispatch = dispatch; this.data = read(file);
        boolean changed = false;
        for (Proposal proposal : data.proposals.values()) if (proposal.active() && proposal.action == Action.TARGET) {
            proposal.state = State.CANCELLED; changed = true;
        }
        if (changed) { trimHistory(); save(); }
    }

    static AgentActions get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new AgentActions(s,
            s.getSavePath(WorldSavePath.ROOT).resolve("infinity-agent-actions.json"), Clock.systemUTC(),
            command -> s.getCommandManager().parseAndExecute(s.getCommandSource(), command)));
    }

    static Data read(Path file) {
        if (!Files.exists(file)) return new Data();
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("Action queue is too large");
            byte[] bytes;
            try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("Action queue is too large");
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var document = com.google.gson.JsonParser.parseString(json);
            if (!document.isJsonObject() || !document.getAsJsonObject().has("format")
                || !document.getAsJsonObject().get("format").isJsonPrimitive()
                || !document.getAsJsonObject().getAsJsonPrimitive("format").isNumber()
                || !java.util.Set.of("1", "2").contains(document.getAsJsonObject().get("format").getAsString()))
                throw new IOException("Missing or invalid action queue format");
            Data data = CommunityServer.GSON.fromJson(document, Data.class);
            if (data == null || (data.format != 1 && data.format != 2) || data.proposals == null || data.proposals.size() > MAX_ACTIVE + MAX_HISTORY)
                throw new IOException("Invalid action queue");
            int active = 0;
            Map<String, Integer> perOwner = new LinkedHashMap<>();
            for (var entry : data.proposals.entrySet()) {
                UUID.fromString(entry.getKey());
                Proposal proposal = entry.getValue();
                if (proposal == null || !proposal.valid() || (data.format == 1 && proposal.action == Action.TARGET))
                    throw new IOException("Invalid action proposal");
                if (proposal.active()) {
                    active++;
                    if (perOwner.merge(proposal.owner, 1, Integer::sum) > MAX_ACTIVE_PER_OWNER) throw new IOException("Owner action limit exceeded");
                }
            }
            if (active > MAX_ACTIVE) throw new IOException("Action limit exceeded");
            data.format = 2;
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
        for (var entry : data.proposals.entrySet()) {
            Proposal proposal = entry.getValue();
            if (!proposal.active()) { targetSessions.remove(entry.getKey()); continue; }
            if (now < proposal.createdAt || now - proposal.createdAt >= LIFETIME_MS) {
                proposal.state = State.EXPIRED; targetSessions.remove(entry.getKey()); changed = true;
            } else if (proposal.action == Action.TARGET && targetProblem(entry.getKey(), proposal) != null) {
                proposal.state = State.CANCELLED; targetSessions.remove(entry.getKey()); changed = true;
            }
        }
        if (changed) { trimHistory(); save(); }
    }

    void trimHistory() {
        List<String> terminal = data.proposals.entrySet().stream().filter(entry -> !entry.getValue().active())
            .sorted(Comparator.comparingLong(entry -> entry.getValue().createdAt)).map(Map.Entry::getKey).toList();
        for (int i = 0; i < terminal.size() - MAX_HISTORY; i++) {
            data.proposals.remove(terminal.get(i)); targetSessions.remove(terminal.get(i));
        }
    }

    static boolean validPlayerName(String name) {
        return name != null && !name.isBlank() && name.length() <= 64
            && name.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
    }

    String targetProblem(String id, Proposal proposal) {
        TargetSession session = targetSessions.get(id);
        if (session == null) return "The original live target session is no longer available.";
        if (!session.ownerUuid.equals(proposal.owner) || !session.targetUuid.equals(proposal.targetUuid)
            || !session.targetName.equals(proposal.targetName) || !session.token.equals(proposal.targetSession)
            || !session.world.getRegistryKey().getValue().toString().equals(proposal.targetDimension))
            return "The proposal no longer matches the exact reviewed target.";
        if (server.getPlayerManager().getPlayer(session.owner.getUuid()) != session.owner
            || server.getPlayerManager().getPlayer(session.target.getUuid()) != session.target
            || session.owner.networkHandler != session.ownerConnection || session.target.networkHandler != session.targetConnection
            || !session.ownerUuid.equals(session.owner.getUuidAsString()) || !session.targetUuid.equals(session.target.getUuidAsString())
            || !session.targetName.equals(session.target.getGameProfile().name()))
            return "The owner or target left their original session.";
        if (!session.owner.isAlive() || !session.target.isAlive()
            || session.owner.getEntityWorld() != session.world || session.target.getEntityWorld() != session.world)
            return "The owner or target died or changed dimension.";
        return AgentCompanions.get(server).targetEligibility(session.owner, session.target);
    }

    boolean queueFull(ServerPlayerEntity owner) {
        long owned = data.proposals.values().stream().filter(p -> p.active() && p.owner.equals(owner.getUuidAsString())).count();
        return owned >= MAX_ACTIVE_PER_OWNER || data.proposals.values().stream().filter(Proposal::active).count() >= MAX_ACTIVE;
    }

    int target(ServerPlayerEntity owner, ServerPlayerEntity target) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can propose a squad target.");
        String reason = AgentCompanions.get(server).targetEligibility(owner, target);
        if (reason != null) return AgentCompanions.reply(owner, "Target refused: " + reason);
        String name = target.getGameProfile().name();
        if (!validPlayerName(name)) return AgentCompanions.reply(owner, "The target's profile name cannot be safely displayed for review.");
        String dimension = target.getEntityWorld().getRegistryKey().getValue().toString();
        if (dimension.length() > 128) return AgentCompanions.reply(owner, "The target's dimension identifier is too long for a bounded review record.");
        expire();
        if (queueFull(owner)) return AgentCompanions.reply(owner, "The helper action queue is full. Cancel or wait for a proposal to expire.");
        String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        Proposal proposal = new Proposal(owner.getUuidAsString(), Action.TARGET, clock.millis());
        proposal.targetUuid = target.getUuidAsString(); proposal.targetName = name;
        proposal.targetDimension = dimension; proposal.targetSession = token;
        targetSessions.put(id, new TargetSession(owner, owner.networkHandler, target, target.networkHandler, target.getEntityWorld(),
            owner.getUuidAsString(), target.getUuidAsString(), name, token));
        data.proposals.put(id, proposal); trimHistory(); save();
        return AgentCompanions.reply(owner, "Proposed " + id + ": " + proposal.description()
            + ". /agent approve " + id + " is your approval. Ask Codex here for the separate live review. Nothing attacks before both approvals; /agent ceasefire stops your squad immediately.");
    }

    int ceasefire(ServerPlayerEntity owner) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can stop this squad.");
        AgentCompanions.get(server).ceasefire(owner);
        boolean changed = false;
        for (var entry : data.proposals.entrySet()) {
            Proposal proposal = entry.getValue();
            if (proposal.active() && proposal.action == Action.TARGET && proposal.owner.equals(owner.getUuidAsString())) {
                proposal.state = State.CANCELLED; targetSessions.remove(entry.getKey()); changed = true;
            }
        }
        if (changed) { trimHistory(); save(); }
        return AgentCompanions.reply(owner, "Ceasefire: your player target is cleared and pending player-target proposals are cancelled.");
    }

    static void invalidatePlayer(ServerPlayerEntity player) {
        AgentActions queue = INSTANCES.get(player.getEntityWorld().getServer());
        if (queue == null) return;
        boolean changed = false;
        for (var entry : queue.data.proposals.entrySet()) {
            Proposal proposal = entry.getValue();
            if (proposal.active() && proposal.action == Action.TARGET
                && (proposal.owner.equals(player.getUuidAsString()) || proposal.targetUuid.equals(player.getUuidAsString()))) {
                proposal.state = State.CANCELLED; queue.targetSessions.remove(entry.getKey()); changed = true;
            }
        }
        if (changed) { queue.trimHistory(); queue.save(); }
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
        if (queueFull(owner)) return AgentCompanions.reply(owner, "The helper action queue is full. Cancel or wait for a proposal to expire.");
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
            .map(e -> e.getKey() + ": " + e.getValue().description() + " (" + e.getValue().state.name().toLowerCase(Locale.ROOT) + ")").toList();
        return AgentCompanions.reply(owner, lines.isEmpty() ? "No helper action proposals. /agent suggest <request> creates one." : String.join("\n", lines));
    }

    int ownerApprove(ServerPlayerEntity owner, String id) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can approve a helper action.");
        expire();
        Proposal proposal = data.proposals.get(id);
        if (proposal == null || !proposal.owner.equals(owner.getUuidAsString()) || proposal.state != State.PENDING)
            return AgentCompanions.reply(owner, "No pending helper action with that ID belongs to you.");
        proposal.state = State.OWNER_APPROVED; save();
        return AgentCompanions.reply(owner, "Owner approved " + id + ": " + proposal.description() + ". It will run only if Codex reviews and approves it live before expiry.");
    }

    int cancel(ServerPlayerEntity owner, String id) {
        if (!AgentCompanions.operator(owner.getCommandSource())) return AgentCompanions.reply(owner, "Only an OP4 owner can cancel a helper action.");
        expire();
        Proposal proposal = data.proposals.get(id);
        if (proposal == null || !proposal.owner.equals(owner.getUuidAsString()) || !proposal.active())
            return AgentCompanions.reply(owner, "No active helper action with that ID belongs to you.");
        proposal.state = State.CANCELLED; targetSessions.remove(id); trimHistory(); save();
        return AgentCompanions.reply(owner, "Cancelled " + id + ". The command will not run.");
    }

    int consolePending(ServerCommandSource source) {
        if (!localConsole(source)) return 0;
        expire();
        var lines = data.proposals.entrySet().stream().filter(e -> e.getValue().active())
            .map(e -> e.getKey() + " owner=" + e.getValue().owner + " action=" + e.getValue().description()
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
        if (proposal.action == Action.TARGET) {
            String reason = targetProblem(id, proposal);
            if (reason != null) {
                proposal.state = State.CANCELLED; targetSessions.remove(id); trimHistory(); save();
                return CommunityServer.info(source, "Target proposal cancelled: " + reason + " Nothing ran.");
            }
            TargetSession session = targetSessions.remove(id);
            // Persist consumption before activation. A restart cannot resume or
            // replay a combat order, even if activation subsequently fails.
            proposal.state = State.DISPATCHED; trimHistory(); save();
            if (!AgentCompanions.get(server).assignPlayerTarget(owner, session.target))
                return CommunityServer.info(source, "Target proposal was consumed, but the live target is no longer eligible. Nothing attacks.");
            AgentCompanions.reply(owner, "Codex approved " + id + ": " + proposal.description() + ". /agent ceasefire stops immediately.");
            return CommunityServer.info(source, "Activated " + id + ": " + proposal.description() + ". This ID cannot run again.");
        }
        // Commit the terminal state first. A crash can prevent dispatch, but cannot replay it.
        proposal.state = State.DISPATCHED;
        trimHistory(); save();
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
        root.then(CommandManager.literal("target").then(CommandManager.argument("player", StringArgumentType.string())
            .executes(c -> {
                String name = StringArgumentType.getString(c, "player");
                var target = name.startsWith("@") ? null : c.getSource().getServer().getPlayerManager().getPlayer(name);
                if (target == null) return AgentCompanions.reply(c.getSource().getPlayerOrThrow(), "Use an exact online player name, not a selector.");
                return get(c.getSource().getServer()).target(c.getSource().getPlayerOrThrow(), target);
            })));
        root.then(CommandManager.literal("ceasefire").executes(c -> get(c.getSource().getServer()).ceasefire(c.getSource().getPlayerOrThrow())));
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
        ServerTickEvents.END_SERVER_TICK.register(server -> { AgentActions queue = INSTANCES.get(server); if (queue != null) queue.expire(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> invalidatePlayer(handler.player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> { if (entity instanceof ServerPlayerEntity player) invalidatePlayer(player); });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> invalidatePlayer(oldPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, from, to) -> invalidatePlayer(player));
    }
}

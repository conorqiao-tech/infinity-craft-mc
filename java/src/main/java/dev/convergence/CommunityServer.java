package dev.convergence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission.HasCommandLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Small server essentials that operate on the Java server for both editions. */
final class CommunityServer {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Map<MinecraftServer, CommunityServer> INSTANCES = new WeakHashMap<>();
    static final int HOME_LIMIT = 3;
    static final int WARMUP = 60;
    static final int COOLDOWN = 200;
    static final int PREPARATION_TIMEOUT = 600;
    final MinecraftServer server;
    final Path file;
    Data data;
    Settings settings = new Settings();
    final Map<UUID, Pending> pending = new HashMap<>();
    final Map<UUID, Request> requests = new HashMap<>();
    final Map<UUID, Integer> cooldowns = new HashMap<>();
    final Map<UUID, Integer> combat = new HashMap<>();
    final Map<UUID, Long> lastChat = new HashMap<>();
    LatestStatusWriter statusWriter;

    record Place(String dimension, double x, double y, double z, float yaw, float pitch) {
        static Place of(ServerPlayer p) {
            return new Place(p.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        }
        boolean valid() {
            return dimension != null && Identifier.tryParse(dimension) != null && Double.isFinite(x) && Double.isFinite(y)
                && Double.isFinite(z) && Math.abs(x) < 30_000_000 && Math.abs(z) < 30_000_000 && y >= -2048 && y <= 2048
                && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }
    /** Runtime-only travel; neither session identities nor chunk leases enter saved data. */
    static final class Pending implements AutoCloseable {
        final ServerPlayer player;
        final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
        final ServerLevel originWorld;
        final GameModes.Mode originMode;
        final Place target, origin;
        final int started, finish;
        final boolean bypass;
        final ServerPlayer hostPlayer;
        final net.minecraft.server.network.ServerGamePacketListenerImpl hostConnection;
        final ServerLevel hostWorld;
        final GameModes.Mode hostMode;
        final FixedDestination fixed;
        final ModeDestinations defaults;
        boolean waitingNotice, committing, closed;

        Pending(ServerPlayer player, Place target, ServerLevel destinationWorld,
                ServerPlayer host, ModeDestinations defaults) {
            this.player = player; connection = player.connection; originWorld = player.level();
            originMode = GameModes.current(player); origin = Place.of(player); this.target = target;
            started = originWorld.getServer().getTickCount(); bypass = Memberships.operator(player);
            finish = started + (bypass ? 0 : WARMUP);
            hostPlayer = host; hostConnection = host == null ? null : host.connection;
            hostWorld = host == null ? null : host.level(); hostMode = host == null ? null : GameModes.current(host);
            this.defaults = defaults;
            fixed = defaults == null ? new FixedDestination(destinationWorld, target, !bypass) : null;
        }
        Place target() { return target; }
        Place origin() { return origin; }
        int finish() { return finish; }
        UUID host() { return hostPlayer == null ? null : hostPlayer.getUUID(); }
        FixedDestination.Status poll() {
            if (fixed != null) return fixed.poll();
            return switch (defaults.poll()) {
                case PENDING -> FixedDestination.Status.PENDING;
                case READY -> FixedDestination.Status.READY;
                case FAILED -> FixedDestination.Status.FAILED;
            };
        }
        Place selected() { return fixed == null ? defaults.selected() : fixed.place(); }
        GameModes.DestinationUnavailable failure() { return fixed == null ? defaults.failure() : fixed.failure(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (fixed != null) fixed.close(); else defaults.close();
        }
    }
    record Request(UUID sender, int expires, ServerPlayer senderPlayer,
                   net.minecraft.server.network.ServerGamePacketListenerImpl senderConnection,
                   ServerLevel senderWorld, GameModes.Mode senderMode, ServerPlayer targetPlayer,
                   net.minecraft.server.network.ServerGamePacketListenerImpl targetConnection,
                   ServerLevel targetWorld, GameModes.Mode targetMode) {
        Request(ServerPlayer sender, ServerPlayer target, int expires) {
            this(sender.getUUID(), expires, sender, sender.connection, sender.level(), GameModes.current(sender),
                target, target.connection, target.level(), GameModes.current(target));
        }
        // An expired fixture may omit its session. An active request never may.
        Request(UUID sender, int expires) { this(sender, expires, null, null, null, null, null, null, null, null); }
    }
    static final class Settings {
        String name = "Infinity Armor";
        List<String> rules = new ArrayList<>(List.of("Respect other players.", "Do not grief, steal, or cheat.", "Follow staff instructions and report bugs."));
    }
    static final class Data {
        int format = 1;
        Place spawn;
        Map<String, Map<String, Place>> homes = new TreeMap<>();
        Map<String, Place> warps = new TreeMap<>();
        Map<String, Long> mutedUntil = new TreeMap<>();
    }

    CommunityServer(MinecraftServer server, Path file) {
        this.server = server; this.file = file; this.data = read(file);
    }
    static CommunityServer get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new CommunityServer(s, s.getWorldPath(LevelResource.ROOT).resolve("infinity-community.json")));
    }
    static Data read(Path file) {
        if (!Files.exists(file)) return new Data();
        try {
            Data d = GSON.fromJson(Files.readString(file), Data.class);
            if (d == null || d.format != 1 || d.homes == null || d.warps == null || d.mutedUntil == null) throw new IOException("Invalid community data");
            if (d.spawn != null && !d.spawn.valid()) throw new IOException("Invalid spawn");
            for (var owner : d.homes.entrySet()) {
                UUID.fromString(owner.getKey());
                validatePlaces(owner.getValue());
            }
            validatePlaces(d.warps);
            for (var entry : d.mutedUntil.entrySet()) { UUID.fromString(entry.getKey()); if (entry.getValue() == null) throw new IOException("Invalid mute"); }
            return d;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + file + ". Restore its .previous backup; the original was not overwritten.", e);
        }
    }
    static void validatePlaces(Map<String, Place> places) throws IOException {
        if (places == null) throw new IOException("Missing places");
        for (var entry : places.entrySet()) if (!validName(entry.getKey()) || entry.getValue() == null || !entry.getValue().valid()) throw new IOException("Invalid location");
    }
    static void atomicJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value));
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
    void save() {
        try {
            if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".previous"), StandardCopyOption.REPLACE_EXISTING);
            atomicJson(file, data);
        } catch (IOException e) { throw new IllegalStateException("Could not save community data. Check free disk space.", e); }
    }
    static boolean validName(String name) { return name != null && name.matches("[a-z0-9_-]{1,24}"); }
    static boolean staff(CommandSourceStack source) { return source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS)); }
    static int say(ServerPlayer p, String message) { p.sendSystemMessage(Component.literal("[Infinity] ").withStyle(ChatFormatting.AQUA).append(Component.literal(message).withStyle(ChatFormatting.WHITE))); return 1; }
    static int info(CommandSourceStack source, String message) { source.sendSuccess(() -> Component.literal(message), false); return 1; }

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var community = get(server);
            Path settings = server.getServerDirectory().resolve("config/infinity-community.json");
            if (Files.exists(settings)) try {
                Settings loaded = GSON.fromJson(Files.readString(settings), Settings.class);
                if (loaded != null && loaded.name != null && loaded.rules != null) community.settings = loaded;
            } catch (Exception e) { throw new IllegalStateException("Invalid community server settings", e); }
            community.writeStatus();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            var community=INSTANCES.remove(server);
            if(community!=null) {
                try { community.clearPending(); }
                finally { community.requests.clear(); if(community.statusWriter!=null)community.statusWriter.close(); }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).tick());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var community = get(server);
            say(handler.player, "Welcome to " + community.settings.name + "! /serverhelp shows the player commands. /rules shows the rules.");
            say(handler.player, "Use /sethome to save your base, /spawn to return to the welcome area, and /tpa <player> to request a visit.");
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> get(server).disconnected(handler.player));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p && amount > 0) {
                var c = get(p.level().getServer());
                if (c.currentPlayer(p)) {
                    var travel = c.pending.get(p.getUUID());
                    if (travel != null && travel.player == p && !travel.bypass && c.cancelPending(p))
                        say(p, "Teleport cancelled because you took damage.");
                    c.combat.put(p.getUUID(), c.server.getTickCount() + COOLDOWN);
                }
            }
            return true;
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, player, parameters) ->
            !AdminStatsMenu.consumeChat(player,message.signedContent()) && get(player.level().getServer()).allowChat(player));
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            dispatcher.register(Commands.literal("serverhelp").executes(c -> info(c.getSource(),
                "Infinity: /hub, /lobbies, /lobby <mode>, /play <mode>, /ranks, /rank, /spawn, /sethome [name], /home [name], /homes, /delhome <name>, /warps, /warp <name>, /tpa <player>, /tpaccept, /tpdeny, /rules. Infinity Menu has gear and power controls. /ai <question> answers server questions; OP4: /agent help. Ordinary teleports take 3 seconds; Admin/OP skips that delay. Player visits still require acceptance.")));
            dispatcher.register(Commands.literal("rules").executes(c -> info(c.getSource(), String.join("\n", get(c.getSource().getServer()).settings.rules))));
            dispatcher.register(Commands.literal("spawn").executes(c -> get(c.getSource().getServer()).spawn(c.getSource().getPlayerOrException())));
            dispatcher.register(Commands.literal("sethome").executes(c -> get(c.getSource().getServer()).setHome(c.getSource().getPlayerOrException(), "home"))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).setHome(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            dispatcher.register(Commands.literal("home").executes(c -> get(c.getSource().getServer()).home(c.getSource().getPlayerOrException(), "home"))
                .then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).home(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            dispatcher.register(Commands.literal("homes").executes(c -> {
                var p = c.getSource().getPlayerOrException(); return say(p, (Memberships.gameplayBypass(p)?"Your homes (Admin/OP has no limit): ":"Your homes (3 max): ") + String.join(", ", get(c.getSource().getServer()).homes(p).keySet()));
            }));
            dispatcher.register(Commands.literal("delhome").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                var p = c.getSource().getPlayerOrException(); var s = get(c.getSource().getServer());
                if (s.homes(p).remove(StringArgumentType.getString(c, "name")) == null) return say(p, "That home does not exist.");
                s.save(); return say(p, "Home deleted.");
            })));
            dispatcher.register(Commands.literal("warps").executes(c -> info(c.getSource(), "Warps: " + String.join(", ", get(c.getSource().getServer()).data.warps.keySet()))));
            dispatcher.register(Commands.literal("warp").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                var s = get(c.getSource().getServer()); return s.queue(c.getSource().getPlayerOrException(), s.data.warps.get(StringArgumentType.getString(c, "name")), null);
            })));
            dispatcher.register(Commands.literal("tpa").then(Commands.argument("player", EntityArgument.player()).executes(c -> get(c.getSource().getServer()).request(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")))));
            dispatcher.register(Commands.literal("tpaccept").executes(c -> get(c.getSource().getServer()).answer(c.getSource().getPlayerOrException(), true)));
            dispatcher.register(Commands.literal("tpdeny").executes(c -> get(c.getSource().getServer()).answer(c.getSource().getPlayerOrException(), false)));
            dispatcher.register(Commands.literal("community").requires(CommunityServer::staff)
                .then(Commands.literal("setspawn").executes(c -> get(c.getSource().getServer()).setSpawn(c.getSource().getPlayerOrException())))
                .then(Commands.literal("buildspawn").executes(c -> get(c.getSource().getServer()).buildSpawn(c.getSource().getPlayerOrException())))
                .then(Commands.literal("setwarp").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    var s = get(c.getSource().getServer()); var p = c.getSource().getPlayerOrException(); String name = StringArgumentType.getString(c, "name");
                    if (!validName(name) || (!Memberships.operator(p)&&!s.data.warps.containsKey(name) && s.data.warps.size() >= 64)) return say(p, "Use 1–24 lowercase letters/numbers, - or _. Maximum 64 warps.");
                    if (!Place.of(p).valid()||(!Memberships.operator(p)&&!safe(p.level(), Place.of(p)))) return say(p, "Stand on safe solid ground first.");
                    s.data.warps.put(name, Place.of(p)); s.save(); return say(p, "Warp saved: " + name);
                })))
                .then(Commands.literal("delwarp").then(Commands.argument("name", StringArgumentType.word()).executes(c -> {
                    var s = get(c.getSource().getServer()); s.data.warps.remove(StringArgumentType.getString(c, "name")); s.save(); return info(c.getSource(), "Warp removed.");
                })))
                .then(Commands.literal("broadcast").then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
                    c.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.literal("[Announcement] " + StringArgumentType.getString(c, "message")).withStyle(ChatFormatting.GOLD), false); return 1;
                })))
                .then(Commands.literal("mute").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(c -> {
                    var s = get(c.getSource().getServer()); var p = EntityArgument.getPlayer(c, "player");
                    s.data.mutedUntil.put(p.getStringUUID(), System.currentTimeMillis() + IntegerArgumentType.getInteger(c, "minutes") * 60_000L); s.save();
                    say(p, "Your public chat was muted by staff."); return info(c.getSource(), "Public chat muted. Private messages are unaffected.");
                }))))
                .then(Commands.literal("unmute").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    var s = get(c.getSource().getServer()); s.data.mutedUntil.remove(EntityArgument.getPlayer(c, "player").getStringUUID()); s.save(); return info(c.getSource(), "Public chat unmuted.");
                }))));
        });
    }

    Map<String, Place> homes(ServerPlayer player) { return data.homes.computeIfAbsent(player.getStringUUID(), id -> new TreeMap<>()); }
    int setHome(ServerPlayer p, String name) {
        if (!Memberships.operator(p)&&GameModes.current(p) == GameModes.Mode.HUB) return say(p, "Use /lobbies or /play from the Hub. Homes are saved in game worlds.");
        if (!validName(name)) return say(p, "Home names use 1–24 lowercase letters/numbers, - or _.");
        if (!Memberships.gameplayBypass(p)&&!homes(p).containsKey(name) && homes(p).size() >= HOME_LIMIT) return say(p, "You have 3 homes. Use /delhome <name> first.");
        if (!Place.of(p).valid()||(!Memberships.operator(p)&&(p.isSpectator() || !p.isAlive() || !safe(p.level(), Place.of(p))))) return say(p, "Stand on safe solid ground to save a home.");
        homes(p).put(name, Place.of(p)); save(); return say(p, "Home saved: " + name);
    }
    int home(ServerPlayer p, String name) { return queue(p, homes(p).get(name), null); }
    int spawn(ServerPlayer p) {
        if (GameModes.current(p) == GameModes.Mode.HUB) return LobbyServer.arrive(p,"main");
        if (GameModes.current(p) != GameModes.Mode.SURVIVAL) {
            // Reject gameplay gates before acquiring any default-search leases.
            var destination = GameModes.world(server, GameModes.current(p));
            if (!queueAllowed(p, destination)) return 0;
            try {
                var defaults = GameModes.prepareDefault(server, GameModes.current(p));
                return install(new Pending(p, null, destination, null, defaults));
            } catch(GameModes.DestinationUnavailable unavailable) {return say(p,unavailable.getMessage());}
        }
        Place spawn = data.spawn;
        if (spawn == null) {
            var point = server.getRespawnData(); var pos = point.pos();
            spawn = new Place(point.dimension().identifier().toString(), pos.getX() + .5, pos.getY(), pos.getZ() + .5, point.yaw(), point.pitch());
        }
        return queue(p, spawn, null);
    }
    int setSpawn(ServerPlayer p) {
        if (GameModes.current(p) != GameModes.Mode.SURVIVAL) return say(p, "Shared spawn belongs to Survival. Use /play survival first.");
        if (!Place.of(p).valid()||(!Memberships.operator(p)&&!safe(p.level(), Place.of(p)))) return say(p, "Stand on safe solid ground first.");
        data.spawn = Place.of(p); save();
        server.setRespawnData(LevelData.RespawnData.of(p.level().dimension(), p.blockPosition(), p.getYRot(), p.getXRot()));
        return say(p, "Shared spawn saved. New players without a bed use this world spawn; /spawn returns here.");
    }
    static boolean safe(ServerLevel world, Place place) {
        if (place == null || !place.valid()) return false;
        BlockPos feet = BlockPos.containing(place.x, place.y, place.z);
        if (!world.isInWorldBounds(feet) || !world.isInWorldBounds(feet.above()) || !world.getWorldBorder().isWithinBounds(feet)) return false;
        world.getChunk(feet.getX() >> 4, feet.getZ() >> 4);
        var floor = world.getBlockState(feet.below());
        return world.getBlockState(feet).isAir() && world.getBlockState(feet.above()).isAir()
            && world.noBlockCollision(null, new AABB(place.x - .3, place.y, place.z - .3, place.x + .3, place.y + 1.8, place.z + .3))
            && floor.isCollisionShapeFullBlock(world, feet.below()) && floor.getFluidState().isEmpty()
            && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CACTUS);
    }
    ServerLevel world(Place place) { return place == null || !place.valid() ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(place.dimension))); }
    int queue(ServerPlayer p, Place place, UUID host) {
        if (!currentPlayer(p)) return 0;
        if (place == null) return say(p, "That location is not set. Ask staff or use /sethome.");
        var destination = world(place);
        if (!queueAllowed(p, destination)) return 0;
        var hostPlayer = host == null ? null : server.getPlayerList().getPlayer(host);
        if (host != null && (hostPlayer == null || !currentPlayer(hostPlayer) || !hostPlayer.isAlive() || hostPlayer.isSpectator()))
            return say(p, "Teleport cancelled: the accepted host is no longer available.");
        return install(new Pending(p, place, destination, hostPlayer, null));
    }
    private boolean queueAllowed(ServerPlayer p, ServerLevel destination) {
        if (!currentPlayer(p)) return false;
        if (destination == null) { say(p, "That destination is invalid or its world is missing."); return false; }
        var previous = pending.get(p.getUUID());
        if (previous != null && previous.player == p && previous.committing) {
            say(p, "Finish your current teleport first."); return false;
        }
        if (Memberships.operator(p)) return true;
        if (GameModes.current(p) == GameModes.Mode.HUB) { say(p, "Use /lobbies or /play from the Hub."); return false; }
        if (GameModes.current(p) == GameModes.Mode.MINIGAMES || GameModes.current(p) == GameModes.Mode.ADVENTURE) {
            say(p, "Use /retry <map> or /play to leave the map."); return false;
        }
        if (!GameModes.allowTeleport(p, destination)) {
            say(p, "Use /play to switch modes first. Inventories stay separate."); return false;
        }
        if (p.isSpectator() || !p.isAlive() || p.isPassenger()) {
            say(p, "You must be alive, outside spectator mode, and off a vehicle to teleport."); return false;
        }
        if (previous != null && previous.player == p) { say(p, "A teleport is already counting down or preparing its destination."); return false; }
        if (cooldowns.getOrDefault(p.getUUID(), 0) > server.getTickCount() || combat.getOrDefault(p.getUUID(), 0) > server.getTickCount()) {
            say(p, "Wait 10 seconds after your last teleport or damage before teleporting."); return false;
        }
        return true;
    }
    private int install(Pending travel) {
        try {
            // Warm invalid terrain is rejected before replacing any active travel.
            var initialReadiness = travel.poll();
            if (initialReadiness == FixedDestination.Status.FAILED) {
                var problem = travel.failure(); travel.close(); return say(travel.player, problem.getMessage());
            }
            var previous = pending.remove(travel.player.getUUID());
            if (previous != null) previous.close();
            GameModes.cancelPending(travel.player); LobbyServer.cancelArrival(travel.player);
            pending.put(travel.player.getUUID(), travel);
            if (!travel.bypass) say(travel.player, "Teleporting in 3 seconds. Stay still; taking damage cancels it.");
            pollPending(travel, initialReadiness);
            return 1;
        } catch (RuntimeException | Error problem) {
            pending.remove(travel.player.getUUID(), travel);
            try { travel.close(); } catch (RuntimeException | Error cleanup) { problem.addSuppressed(cleanup); }
            throw problem;
        }
    }
    private boolean currentPlayer(ServerPlayer p) {
        return p != null && server.getPlayerList().getPlayer(p.getUUID()) == p
            && p.connection != null && p.connection.isAcceptingMessages() && !p.isRemoved();
    }
    private boolean sameSession(ServerPlayer p, net.minecraft.server.network.ServerGamePacketListenerImpl connection,
                                ServerLevel world, GameModes.Mode mode) {
        return currentPlayer(p) && p.connection == connection && p.level() == world && GameModes.current(p) == mode;
    }
    private boolean requestCurrent(Request request) {
        return request.expires > server.getTickCount()
            && sameSession(request.senderPlayer, request.senderConnection, request.senderWorld, request.senderMode)
            && sameSession(request.targetPlayer, request.targetConnection, request.targetWorld, request.targetMode);
    }
    boolean cancelPending(ServerPlayer p) {
        var travel = pending.get(p.getUUID());
        // A stale disconnect and our own native teleport hook must not retire a current commit.
        if (travel == null || travel.player != p || travel.committing) return false;
        if (!pending.remove(p.getUUID(), travel)) return false;
        travel.close(); return true;
    }
    void cancelRequests(ServerPlayer p) {
        requests.values().removeIf(request -> request.senderPlayer == p || request.targetPlayer == p);
        for (var travel : new ArrayList<>(pending.values())) if (travel.hostPlayer == p && !travel.committing)
            finish(travel, "Teleport cancelled: the accepted host left or changed worlds.");
    }
    void disconnected(ServerPlayer p) {
        cancelPending(p); cancelRequests(p);
        var joined = server.getPlayerList().getPlayer(p.getUUID());
        if (joined != null && joined != p) return;
        cooldowns.remove(p.getUUID()); combat.remove(p.getUUID()); lastChat.remove(p.getUUID());
    }
    void clearPending() {
        var old = new ArrayList<>(pending.values()); pending.clear();
        Throwable failure = null;
        for (var travel : old) try { travel.close(); }
        catch (RuntimeException | Error problem) {
            if (failure == null) failure = problem; else failure.addSuppressed(problem);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
    private void finish(Pending travel, String problem) {
        if (!pending.remove(travel.player.getUUID(), travel)) return;
        travel.close();
        if (problem != null && currentPlayer(travel.player)) say(travel.player, problem);
    }
    private void pollPending(Pending travel) {
        pollPending(travel, null);
    }
    private void pollPending(Pending travel, FixedDestination.Status initialReadiness) {
        if (pending.get(travel.player.getUUID()) != travel || travel.closed || travel.committing) return;
        var p = travel.player;
        if (!sameSession(p, travel.connection, travel.originWorld, travel.originMode) || !p.isAlive()) {
            finish(travel, "Teleport cancelled because your player session or world changed."); return;
        }
        if (travel.bypass && !Memberships.operator(p)) {
            finish(travel, "Teleport cancelled because OP access was removed."); return;
        }
        var destination = travel.fixed == null ? GameModes.world(server, travel.originMode) : world(travel.target);
        if (destination == null || !GameModes.allowTeleport(p, destination)) {
            finish(travel, "Teleport cancelled: use /play to switch modes first. Inventories stay separate."); return;
        }
        if (!travel.bypass && (p.isSpectator() || p.isPassenger() || moved(travel.origin, p)
            || combat.getOrDefault(p.getUUID(), 0) > server.getTickCount()
            || cooldowns.getOrDefault(p.getUUID(), 0) > server.getTickCount())) {
            finish(travel, "Teleport cancelled because you moved or can no longer teleport."); return;
        }
        if (travel.hostPlayer != null && (!sameSession(travel.hostPlayer, travel.hostConnection, travel.hostWorld, travel.hostMode)
            || !travel.hostPlayer.isAlive() || travel.hostPlayer.isSpectator())) {
            finish(travel, "Teleport cancelled: the accepted host left or changed worlds."); return;
        }
        if (server.getTickCount() - travel.started >= PREPARATION_TIMEOUT) {
            finish(travel, "The destination is taking too long to prepare. Your position and inventory are unchanged; try again later."); return;
        }
        FixedDestination.Status readiness;
        try { readiness = initialReadiness == null ? travel.poll() : initialReadiness; }
        catch (RuntimeException | Error problem) {
            pending.remove(p.getUUID(), travel);
            try { travel.close(); } catch (RuntimeException | Error cleanup) { problem.addSuppressed(cleanup); }
            throw problem;
        }
        if (readiness == FixedDestination.Status.FAILED) { finish(travel, travel.failure().getMessage()); return; }
        if (server.getTickCount() < travel.finish) return;
        if (readiness == FixedDestination.Status.PENDING) {
            if (!travel.waitingNotice) {
                travel.waitingNotice = true;
                say(p, "Preparing your destination. " + (travel.bypass ? "Your" : "Stay still; your") + " current world and inventory remain active.");
            }
            return;
        }
        var target = travel.selected();
        destination = world(target);
        if (destination == null || !GameModes.allowTeleport(p, destination)) {
            finish(travel, "Teleport cancelled: the prepared destination is no longer available."); return;
        }
        travel.committing = true;
        boolean success = false;
        try {
            if (travel.bypass) p.stopRiding();
            success = p.teleportTo(destination, target.x, target.y, target.z, Set.of(), target.yaw, target.pitch, true);
            if (success && travel.defaults != null) GameModes.rememberDefault(server, travel.originMode, travel.defaults);
        } finally { travel.committing = false; finish(travel, null); }
        if (!currentPlayer(p) || p.connection != travel.connection) return;
        if (!success) { say(p, "Teleport could not complete. Your current mode and items remain active."); return; }
        p.fallDistance = 0; p.setDeltaMovement(Vec3.ZERO);
        if (!travel.bypass) cooldowns.put(p.getUUID(), server.getTickCount() + COOLDOWN);
        say(p, travel.bypass ? "Teleported with OP access." : "Teleported.");
    }
    int request(ServerPlayer sender, ServerPlayer target) {
        if (!currentPlayer(sender) || !currentPlayer(target)) return 0;
        requests.values().removeIf(request -> !requestCurrent(request));
        if (!Memberships.operator(sender)&&GameModes.current(sender) != GameModes.current(target)) return say(sender, "Choose a player in the same game mode, or use /play first.");
        if (sender == target || sender.getUUID().equals(target.getUUID())) return say(sender, "Choose another player.");
        if (!Memberships.operator(sender)&&(sender.isSpectator() || !sender.isAlive())) return say(sender, "You cannot request a teleport now.");
        if (requests.values().stream().anyMatch(r -> r.sender.equals(sender.getUUID()) && r.expires > server.getTickCount())) return say(sender, "Your previous request is still waiting (up to 30 seconds).");
        Request previous = requests.get(target.getUUID());
        if (previous != null && previous.expires > server.getTickCount()) return say(sender, "That player already has a pending request. Try again shortly.");
        requests.put(target.getUUID(), new Request(sender, target, server.getTickCount() + 600));
        say(target, sender.getScoreboardName() + " wants to visit you. /tpaccept or /tpdeny within 30 seconds.");
        return say(sender, "Request sent. Nothing moves until they accept.");
    }
    int answer(ServerPlayer target, boolean accept) {
        if (!currentPlayer(target)) return 0;
        Request request = requests.get(target.getUUID());
        if (request == null) return say(target, "No unexpired request.");
        if (!requestCurrent(request)) {
            requests.remove(target.getUUID(), request);
            return say(target, "No unexpired request from the same player sessions and worlds.");
        }
        if (request.targetPlayer != target || request.targetConnection != target.connection) return 0;
        requests.remove(target.getUUID(), request);
        ServerPlayer sender = request.senderPlayer;
        if (!accept || !target.isAlive() || target.isSpectator()) { say(sender, "Teleport request declined."); return say(target, "Request declined."); }
        say(target, Memberships.gameplayBypass(sender)?"Request accepted. The Admin/OP visitor has no countdown; cold terrain may need preparation.":"Request accepted. The visitor starts a 3-second countdown to your current location.");
        // Consent freezes this exact address; later host movement never retargets it.
        return queue(sender, Place.of(target), target.getUUID());
    }
    boolean allowChat(ServerPlayer player) {
        if(Memberships.operator(player))return true;
        long now = System.currentTimeMillis();
        if (data.mutedUntil.getOrDefault(player.getStringUUID(), 0L) > now) { say(player, "Your public chat is muted. Contact staff if needed."); return false; }
        if (now - lastChat.getOrDefault(player.getUUID(), 0L) < 1500) { say(player, "Please wait a moment between chat messages."); return false; }
        lastChat.put(player.getUUID(), now); return true;
    }
    static boolean moved(Place origin, ServerPlayer player) {
        return !origin.dimension.equals(player.level().dimension().identifier().toString())
            || new Vec3(origin.x, origin.y, origin.z).distanceToSqr(player.position()) > .04;
    }
    void tick() {
        // Native teleport hooks can cancel other jobs; never iterate the live map.
        for (var travel : new ArrayList<>(pending.values())) pollPending(travel);
        requests.values().removeIf(request -> !requestCurrent(request));
        if (server.getTickCount() % 100 == 0) writeStatus();
    }
    void writeStatus() {
        // All game objects are read here. The writer receives only immutable values.
        var snapshot=new LatestStatusWriter.Snapshot(System.currentTimeMillis(),
            server.getPlayerList().getPlayers().stream().map(p -> new LatestStatusWriter.Player(
                p.getScoreboardName(),p.getStringUUID(),GameModes.current(p).name(),Memberships.get(server).label(p.getUUID()))).toList(),
            server.getAverageTickTimeNanos()/1_000_000.0);
        if(statusWriter==null)statusWriter=new LatestStatusWriter(server.getServerDirectory().resolve("community-status.json"));
        statusWriter.submit(snapshot);
    }
    int buildSpawn(ServerPlayer p) {
        if (!p.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return say(p, "Build the welcome plaza in the Overworld.");
        var world = p.level(); BlockPos center = p.blockPosition().below();
        for (BlockPos at : BlockPos.betweenClosed(center.offset(-12, 0, -12), center.offset(12, 5, 12))) {
            if (!world.isInWorldBounds(at) || !world.getWorldBorder().isWithinBounds(at)) return say(p, "The whole plaza must fit inside the world border.");
            var block = world.getBlockState(at);
            if (!(block.isAir() || block.is(Blocks.GRASS_BLOCK) || block.is(Blocks.DIRT) || block.is(Blocks.SHORT_GRASS) || block.is(Blocks.SNOW)))
                return say(p, "Choose a clear 25×25 grassy area. The plaza will not replace builds, trees, chests, or water.");
        }
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            for (int y = 1; y <= 5; y++) world.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            var material = (x % 6 == 0 && z % 6 == 0) ? Blocks.SEA_LANTERN
                : Math.abs(x) <= 1 || Math.abs(z) <= 1 ? Blocks.QUARTZ_BLOCK
                : Math.abs(x) == 12 || Math.abs(z) == 12 ? Blocks.STONE_BRICKS : Blocks.POLISHED_DEEPSLATE;
            world.setBlock(center.offset(x, 0, z), material.defaultBlockState(), 3);
            if ((Math.abs(x) == 12 && Math.abs(z) > 2) || (Math.abs(z) == 12 && Math.abs(x) > 2))
                world.setBlock(center.offset(x, 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
        }
        for (int x : new int[]{-10, 10}) for (int z : new int[]{-10, 10}) {
            for (int y = 1; y <= 3; y++) world.setBlock(center.offset(x, y, z), Blocks.QUARTZ_PILLAR.defaultBlockState(), 3);
            world.setBlock(center.offset(x, 4, z), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        }
        setSpawn(p); return say(p, "Welcome plaza built and spawn saved. Paths lead into survival. Add destinations with /community setwarp <name>.");
    }
}

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
    final MinecraftServer server;
    final Path file;
    Data data;
    Settings settings = new Settings();
    final Map<UUID, Pending> pending = new HashMap<>();
    final Map<UUID, Request> requests = new HashMap<>();
    final Map<UUID, Integer> cooldowns = new HashMap<>();
    final Map<UUID, Integer> combat = new HashMap<>();
    final Map<UUID, Long> lastChat = new HashMap<>();

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
    record Pending(Place target, Place origin, int finish, UUID host) {}
    record Request(UUID sender, int expires) {}
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
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).tick());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var community = get(server);
            say(handler.player, "Welcome to " + community.settings.name + "! /serverhelp shows the player commands. /rules shows the rules.");
            say(handler.player, "Use /sethome to save your base, /spawn to return to the welcome area, and /tpa <player> to request a visit.");
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var c = get(server); UUID id = handler.player.getUUID();
            c.pending.remove(id); c.cooldowns.remove(id); c.combat.remove(id); c.lastChat.remove(id);
            c.requests.remove(id); c.requests.values().removeIf(r -> r.sender.equals(id));
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer p && amount > 0) {
                var c = get(p.level().getServer());
                if (c.pending.remove(p.getUUID()) != null) say(p, "Teleport cancelled because you took damage.");
                c.combat.put(p.getUUID(), c.server.getTickCount() + COOLDOWN);
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
        if (GameModes.current(p) != GameModes.Mode.SURVIVAL) return queue(p, GameModes.defaultPlace(server, GameModes.current(p)), null);
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
        if (place == null) return say(p, "That location is not set. Ask staff or use /sethome.");
        if (Memberships.operator(p)) {
            var destination=world(place);if(destination==null)return say(p,"That destination is invalid or its world is missing.");
            pending.remove(p.getUUID());GameModes.PENDING.remove(p.getUUID());p.stopRiding();
            if(p.teleportTo(destination,place.x,place.y,place.z,Set.of(),place.yaw,place.pitch,true)) {
                p.fallDistance=0;p.setDeltaMovement(Vec3.ZERO);return say(p,"Teleported with OP access.");
            }
            return say(p,"Teleport could not complete.");
        }
        if (GameModes.current(p) == GameModes.Mode.HUB) return say(p, "Use /lobbies or /play from the Hub.");
        if (GameModes.current(p) == GameModes.Mode.MINIGAMES || GameModes.current(p) == GameModes.Mode.ADVENTURE) return say(p, "Use /retry <map> or /play to leave the map.");
        if (world(place) == null || !GameModes.allowTeleport(p, world(place))) return say(p, "Use /play to switch modes first. Inventories stay separate.");
        if (p.isSpectator() || !p.isAlive() || p.isPassenger()) return say(p, "You must be alive, outside spectator mode, and off a vehicle to teleport.");
        if (pending.containsKey(p.getUUID())) return say(p, "A teleport is already counting down.");
        if (cooldowns.getOrDefault(p.getUUID(), 0) > server.getTickCount() || combat.getOrDefault(p.getUUID(), 0) > server.getTickCount()) return say(p, "Wait 10 seconds after your last teleport or damage before teleporting.");
        var world = world(place);
        if (world == null || !safe(world, place)) return say(p, "That destination is unsafe, blocked, or outside the world border. Ask its owner to reset it.");
        pending.put(p.getUUID(), new Pending(place, Place.of(p), server.getTickCount() + WARMUP, host));
        return say(p, "Teleporting in 3 seconds. Stay still; taking damage cancels it.");
    }
    int request(ServerPlayer sender, ServerPlayer target) {
        if (!Memberships.operator(sender)&&GameModes.current(sender) != GameModes.current(target)) return say(sender, "Choose a player in the same game mode, or use /play first.");
        if (sender == target || sender.getUUID().equals(target.getUUID())) return say(sender, "Choose another player.");
        if (!Memberships.operator(sender)&&(sender.isSpectator() || !sender.isAlive())) return say(sender, "You cannot request a teleport now.");
        if (requests.values().stream().anyMatch(r -> r.sender.equals(sender.getUUID()) && r.expires > server.getTickCount())) return say(sender, "Your previous request is still waiting (up to 30 seconds).");
        Request previous = requests.get(target.getUUID());
        if (previous != null && previous.expires > server.getTickCount()) return say(sender, "That player already has a pending request. Try again shortly.");
        requests.put(target.getUUID(), new Request(sender.getUUID(), server.getTickCount() + 600));
        say(target, sender.getScoreboardName() + " wants to visit you. /tpaccept or /tpdeny within 30 seconds.");
        return say(sender, "Request sent. Nothing moves until they accept.");
    }
    int answer(ServerPlayer target, boolean accept) {
        Request request = requests.remove(target.getUUID());
        if (request == null || request.expires <= server.getTickCount()) return say(target, "No unexpired request.");
        ServerPlayer sender = server.getPlayerList().getPlayer(request.sender);
        if (sender == null) return say(target, "That player left the server.");
        if (!accept || !target.isAlive() || target.isSpectator()) { say(sender, "Teleport request declined."); return say(target, "Request declined."); }
        say(target, Memberships.gameplayBypass(sender)?"Request accepted. The Admin/OP visitor teleports immediately if the destination is valid.":"Request accepted. The visitor starts a 3-second countdown to your current location.");
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
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next(); var p = server.getPlayerList().getPlayer(entry.getKey()); var pending = entry.getValue();
            if (p == null) { iterator.remove(); continue; }
            if (!p.isAlive() || p.isSpectator() || p.isPassenger() || moved(pending.origin, p)) { iterator.remove(); say(p, "Teleport cancelled because you moved or can no longer teleport."); continue; }
            if (pending.host != null && server.getPlayerList().getPlayer(pending.host) == null) { iterator.remove(); say(p, "Teleport cancelled: the host left."); continue; }
            if (server.getTickCount() < pending.finish) continue;
            iterator.remove(); var world = world(pending.target);
            if (world == null || !safe(world, pending.target)) { say(p, "Teleport cancelled: the destination became unsafe."); continue; }
            Place target = pending.target;
            if (p.teleportTo(world, target.x, target.y, target.z, Set.of(), target.yaw, target.pitch, true)) {
                p.fallDistance = 0; p.setDeltaMovement(Vec3.ZERO); cooldowns.put(p.getUUID(), server.getTickCount() + COOLDOWN); say(p, "Teleported.");
            }
        }
        requests.values().removeIf(request -> request.expires <= server.getTickCount());
        if (server.getTickCount() % 100 == 0) writeStatus();
    }
    void writeStatus() {
        try {
            atomicJson(server.getServerDirectory().resolve("community-status.json"), Map.of("updated", System.currentTimeMillis(),
                "players", server.getPlayerList().getPlayers().stream().map(p -> Map.of("name", p.getScoreboardName(), "uuid", p.getStringUUID(), "mode", GameModes.current(p).name(), "rank", Memberships.get(server).label(p.getUUID()))).toList(),
                "tick_ms", server.getAverageTickTimeNanos() / 1_000_000.0));
        } catch (IOException e) { System.err.println("[Infinity] Could not update the host panel: " + e.getMessage()); }
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

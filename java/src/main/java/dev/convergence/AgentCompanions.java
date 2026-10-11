package dev.convergence;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.JsonParser;
import dev.convergence.mixin.AgentGoalAccess;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.Permission.Level;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/** Server-controlled vanilla companions: navigation and combat work on either edition. */
public final class AgentCompanions {
    static final String TAG = "infinity_agent";
    static final int LIMIT = 6;
    static final int GLOBAL_LIMIT = 24;
    static final int MAX_BYTES = 65_536;
    static final long PLAYER_TARGET_LIFETIME_MS = 5 * 60_000L;
    static final String HELP = "Open /agent or /agent menu for helper controls. /agent spawn <name>, /agent profile <name> <primitive|regular|ultimate_finals|debug|cli|api>, /agent follow <name>, /agent guard <name>, /agent stay <name>, /agent squad <follow|guard|stay>, /agent status <name>, /agent dismiss <name>, /agent list. Names: 1–24 lowercase letters/numbers, - or _. 6 per OP4 owner; 24 server-wide, including unloaded helpers. Primitive proactively attacks nearby hostile mobs; Regular follows/guards and prioritizes owner threats. Ultimate Finals shares squad focus and flanks hostiles. /agent target <player> proposes an exact player target for Primitive and Ultimate Finals, requiring owner approval and live Codex approval before combat. PvP and team rules apply; /agent ceasefire stops it immediately. Player orders expire after five minutes, session/life/dimension or permission changes, owner-target distance beyond 48 blocks, or no usable aggressive helpers. Follow can pursue beyond the 24-block damage limit and around walls; swings require clear sight. Guard waits outside its 14-block anchor range. Helpers never attack pets. Debug, CLI, and API are passive physical profiles. /agent data <name> shows limited live data; /agent ask <name> <question> asks a helper; /agent code <name> <request> queues a code request for owner and live Codex review. Follow pauses beyond 48 blocks; return nearby to resume. Helpers pause while you are offline, dead, without OP4, or in another dimension; they never teleport or load chunks. Safe server actions: /agent suggest <request>, /agent pending, /agent approve <id>, /agent cancel <id>. An action runs only after your approval and live Codex review.";
    static final Map<MinecraftServer, AgentCompanions> INSTANCES = new WeakHashMap<>();
    final MinecraftServer server;
    final Path file;
    final Data data;
    final Clock clock;
    final Map<UUID, IronGolemEntity> loaded = new HashMap<>();
    final Map<UUID, Integer> lastAttack = new HashMap<>();
    final Map<UUID, PlayerTarget> playerTargets = new HashMap<>();

    /** Exact connected entity and handler identities prevent orders surviving reconnects or respawns. Never persisted. */
    record PlayerTarget(ServerPlayerEntity owner, ServerPlayNetworkHandler ownerConnection, ServerPlayerEntity target,
        ServerPlayNetworkHandler targetConnection, ServerWorld world, long issuedAt, long expiresAt) {}

    enum Mode { FOLLOW, GUARD, STAY }
    enum Profile {
        PRIMITIVE("Primitive", "Ready to attack the nearest hostile mob within 12 blocks; shares an explicitly approved player target with its squad. Never attacks pets.", true),
        REGULAR("Regular", "Follows or guards, attacking nearby hostile mobs.", true),
        ULTIMATE_FINALS("Ultimate Finals", "Shares focus and clear flanking positions with its squad. Player combat requires an exact target plus owner and live Codex approval, with PvP and team rules. Normal golem damage and speed.", true),
        DEBUG("Debug", "Passive helper with read-only status diagnostics. No commands run.", false),
        CLI("CLI", "Saves code-change requests for owner and live Codex review; previews fixed server actions. No shell or automatic edits.", false),
        API("API", "Answers with limited live Minecraft data using optional external AI chat. Replies never run commands.", false);
        final String label, description;
        final boolean combat;
        Profile(String label, String description, boolean combat) { this.label = label; this.description = description; this.combat = combat; }
        String id() { return name().toLowerCase(Locale.ROOT); }
        String label() { return label; }
        String description() { return description; }
        static Profile parse(String value) {
            if (value == null) return null;
            try { return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')); }
            catch (IllegalArgumentException e) { return null; }
        }
    }
    record Agent(String owner, String name, Mode mode, Profile profile, String dimension, double x, double y, double z) {
        Agent(String owner, String name, Mode mode, String dimension, double x, double y, double z) {
            this(owner, name, mode, Profile.REGULAR, dimension, x, y, z);
        }
        Vec3d anchor() { return new Vec3d(x, y, z); }
        Agent mode(Mode mode, IronGolemEntity golem) {
            return new Agent(owner, name, mode, profile, golem.getEntityWorld().getRegistryKey().getValue().toString(), golem.getX(), golem.getY(), golem.getZ());
        }
        Agent profile(Profile value) {
            return new Agent(owner, name, mode, value, dimension, x, y, z);
        }
        boolean valid() {
            try { UUID.fromString(owner); } catch (Exception e) { return false; }
            return validName(name) && mode != null && profile != null
                && dimension != null && Identifier.tryParse(dimension) != null
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000 && y >= -2048 && y <= 2048;
        }
    }
    static final class Data {
        int format = 2;
        Map<String, Agent> agents = new TreeMap<>();
    }
    AgentCompanions(MinecraftServer server, Path file) {
        this(server, file, Clock.systemUTC());
    }
    AgentCompanions(MinecraftServer server, Path file, Clock clock) {
        this.server = server; this.file = file; this.clock = clock; this.data = read(file);
    }
    static AgentCompanions get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new AgentCompanions(s, s.getSavePath(WorldSavePath.ROOT).resolve("infinity-agents.json")));
    }
    static Data read(Path file) {
        if (!java.nio.file.Files.exists(file)) return new Data();
        try {
            if (java.nio.file.Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("Helper roster exceeds " + MAX_BYTES + " bytes");
            byte[] bytes;
            try (var input = java.nio.file.Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Helper roster exceeds " + MAX_BYTES + " bytes");
            var json = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (!json.isJsonObject() || !json.getAsJsonObject().has("format") || !json.getAsJsonObject().get("format").isJsonPrimitive()
                || !json.getAsJsonObject().getAsJsonPrimitive("format").isNumber()
                || !Set.of("1", "2").contains(json.getAsJsonObject().get("format").getAsString()))
                throw new IllegalArgumentException("Missing helper roster format");
            Data data = CommunityServer.GSON.fromJson(json, Data.class);
            if (data == null || (data.format != 1 && data.format != 2) || data.agents == null || data.agents.size() > GLOBAL_LIMIT)
                throw new IllegalArgumentException("Invalid agent data or exceeded server limit");
            if (data.format == 1) {
                // Old rosters had movement modes only. Missing profiles migrate to Regular.
                var records = json.getAsJsonObject().getAsJsonObject("agents");
                for (var entry : data.agents.entrySet()) {
                    if (records.getAsJsonObject(entry.getKey()).has("profile")) throw new IllegalArgumentException("Unexpected profile in old roster format");
                    if (entry.getValue() == null) throw new IllegalArgumentException("Invalid agent record");
                    entry.setValue(entry.getValue().profile(Profile.REGULAR));
                }
                data.format = 2;
            }
            Map<String, Set<String>> names = new HashMap<>();
            for (var entry : data.agents.entrySet()) {
                UUID.fromString(entry.getKey()); Agent agent = entry.getValue();
                if (agent == null || !agent.valid()) throw new IllegalArgumentException("Invalid agent record");
                var owned = names.computeIfAbsent(agent.owner, k -> new HashSet<>());
                if (!owned.add(agent.name) || owned.size() > LIMIT) throw new IllegalArgumentException("Duplicate agent name or exceeded owner limit");
            }
            return data;
        } catch (Exception e) { throw new IllegalStateException("Cannot read " + file + ". Restore its .previous backup; the original was not overwritten.", e); }
    }
    void save() {
        try {
            if (java.nio.file.Files.exists(file)) java.nio.file.Files.copy(file, file.resolveSibling(file.getFileName() + ".previous"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            CommunityServer.atomicJson(file, data);
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not save helper ownership. Check free disk space.", e); }
    }
    static boolean validName(String name) { return name != null && name.matches("[a-z0-9_-]{1,24}"); }
    static boolean operator(ServerCommandSource source) { return source.getPermissions().hasPermission(new Level(PermissionLevel.OWNERS)); }
    static boolean isAgent(Entity entity) { return entity instanceof IronGolemEntity && entity.getCommandTags().contains(TAG); }
    static boolean hostile(LivingEntity entity) {
        return entity instanceof Monster && entity.isAlive() && !entity.isSpectator() && !isAgent(entity)
            && !(entity instanceof TameableEntity pet && pet.isTamed());
    }
    /** Independent damage gate also covers targets forced by commands or another mod. */
    static boolean allowDamage(LivingEntity victim, net.minecraft.entity.damage.DamageSource source) {
        if (!isAgent(source.getAttacker())) return true;
        if (!(source.getAttacker() instanceof IronGolemEntity golem)
            || !(golem.getEntityWorld() instanceof ServerWorld world)) return false;
        var companions = INSTANCES.get(world.getServer());
        var agent = companions == null ? null : companions.data.agents.get(golem.getUuidAsString());
        if (agent == null || !agent.profile.combat) return false;
        var owner = world.getServer().getPlayerManager().getPlayer(UUID.fromString(agent.owner));
        var order = companions.validPlayerTarget(owner);
        if (companions.pauseReason(owner, golem, agent) != null) return false;
        if (victim instanceof ServerPlayerEntity player) {
            return order != null && order.target == player && companions.playerCombatReady(golem, agent, owner, player)
                && golem.getVisibilityCache().canSee(player);
        }
        return hostile(victim);
    }
    static void prepare(IronGolemEntity golem) {
        golem.clearGoalsAndTasks();
        ((AgentGoalAccess) golem).infinity$getTargetSelector().clear(goal -> true);
        golem.setPlayerCreated(true); golem.setPersistent(); golem.setCanPickUpLoot(false);
        golem.setTarget(null); golem.setAttacker(null); golem.setAngryAt(null); golem.setAngerEndTime(0);
        golem.getNavigation().stop();
    }
    void tags(IronGolemEntity golem, Agent agent) {
        new HashSet<>(golem.getCommandTags()).stream().filter(t -> t.startsWith("infinity_owner_") || t.startsWith("infinity_name_") || t.startsWith("infinity_mode_") || t.startsWith("infinity_profile_")).forEach(golem::removeCommandTag);
        golem.addCommandTag(TAG); golem.addCommandTag("infinity_owner_" + agent.owner);
        golem.addCommandTag("infinity_name_" + agent.name); golem.addCommandTag("infinity_mode_" + agent.mode.name().toLowerCase(Locale.ROOT));
        golem.addCommandTag("infinity_profile_" + agent.profile.id());
        golem.setCustomName(Text.literal(agent.name + " [" + agent.profile.label + "]")); golem.setCustomNameVisible(true);
    }
    void load(Entity entity) {
        if (!isAgent(entity)) return;
        var golem = (IronGolemEntity) entity;
        Agent agent = data.agents.get(golem.getUuidAsString());
        if (agent == null) { golem.discard(); return; } // Dismissed while its chunk was unloaded.
        prepare(golem); tags(golem, agent); loaded.put(golem.getUuid(), golem);
    }
    Map.Entry<String, Agent> owned(ServerPlayerEntity owner, String name) {
        return data.agents.entrySet().stream().filter(e -> e.getValue().owner.equals(owner.getUuidAsString()) && e.getValue().name.equals(name)).findFirst().orElse(null);
    }
    int count(ServerPlayerEntity owner) { return (int) data.agents.values().stream().filter(a -> a.owner.equals(owner.getUuidAsString())).count(); }
    static int reply(ServerPlayerEntity owner, String text) { return CommunityServer.say(owner, text); }

    static boolean playerCombatProfile(Profile profile) { return profile == Profile.PRIMITIVE || profile == Profile.ULTIMATE_FINALS; }
    String playerPairEligibility(ServerPlayerEntity owner, ServerPlayerEntity target) {
        if (owner == null || server.getPlayerManager().getPlayer(owner.getUuid()) != owner || owner.isDisconnected()) return "Owner must remain connected.";
        if (!operator(owner.getCommandSource())) return "Owner needs OP4.";
        if (!owner.isAlive() || owner.isRemoved() || owner.isSpectator()) return "Owner must be alive and outside spectator mode.";
        if (target == null || server.getPlayerManager().getPlayer(target.getUuid()) != target || target.isDisconnected()) return "Target must remain connected.";
        if (owner == target || owner.getUuid().equals(target.getUuid())) return "Helpers cannot target their owner.";
        if (!target.isAlive() || target.isRemoved() || (target.getGameMode() != GameMode.SURVIVAL && target.getGameMode() != GameMode.ADVENTURE)) return "Target must be alive in survival or adventure mode.";
        if (owner.getEntityWorld() != target.getEntityWorld()) return "Owner and target must be in the same dimension.";
        if (!owner.shouldDamagePlayer(target) || !target.shouldDamagePlayer(owner)) return "World PvP or team friendly-fire rules forbid this target.";
        if (owner.squaredDistanceTo(target) > 48 * 48) return "Target must remain within 48 blocks of the owner.";
        return null;
    }
    /** Order availability is separate from target distance: a usable squad can pursue or wait inside its leash. */
    boolean playerOrderHelperAvailable(IronGolemEntity golem, Agent agent, ServerPlayerEntity owner) {
        if (owner == null || !playerCombatProfile(agent.profile) || !agent.owner.equals(owner.getUuidAsString()) || pauseReason(owner, golem, agent) != null) return false;
        Vec3d center = agent.mode == Mode.FOLLOW ? owner.getEntityPos() : agent.anchor();
        double leash = agent.mode == Mode.FOLLOW ? 48 : 14;
        return golem.getEntityPos().squaredDistanceTo(center) <= leash * leash;
    }
    boolean playerPursuitReady(IronGolemEntity golem, Agent agent, ServerPlayerEntity owner, ServerPlayerEntity target) {
        if (!playerOrderHelperAvailable(golem, agent, owner) || golem.getEntityWorld() != target.getEntityWorld()) return false;
        Vec3d center = agent.mode == Mode.FOLLOW ? owner.getEntityPos() : agent.anchor();
        double leash = agent.mode == Mode.FOLLOW ? 48 : 14;
        return target.getEntityPos().squaredDistanceTo(center) <= leash * leash;
    }
    boolean playerCombatReady(IronGolemEntity golem, Agent agent, ServerPlayerEntity owner, ServerPlayerEntity target) {
        return playerPursuitReady(golem, agent, owner, target) && golem.squaredDistanceTo(target) <= 24 * 24;
    }
    String targetEligibility(ServerPlayerEntity owner, ServerPlayerEntity target) {
        String reason = playerPairEligibility(owner, target);
        if (reason != null) return reason;
        for (var entry : loaded.entrySet()) {
            var agent = data.agents.get(entry.getKey().toString());
            if (agent != null && playerOrderHelperAvailable(entry.getValue(), agent, owner)) return null;
        }
        return "No active loaded Primitive or Ultimate Finals helper is available inside its movement leash.";
    }
    /** Called only by the final, owner-approved local-console action review. Text replies never call it. */
    boolean assignPlayerTarget(ServerPlayerEntity owner, ServerPlayerEntity target) {
        if (targetEligibility(owner, target) != null) return false;
        ceasefire(owner);
        long now = clock.millis();
        playerTargets.put(owner.getUuid(), new PlayerTarget(owner, owner.networkHandler, target, target.networkHandler, owner.getEntityWorld(), now, now + PLAYER_TARGET_LIFETIME_MS));
        return true;
    }
    PlayerTarget validPlayerTarget(ServerPlayerEntity owner) {
        if (owner == null) return null;
        var order = playerTargets.get(owner.getUuid());
        if (order == null) return null;
        if (order.owner != owner || order.ownerConnection != owner.networkHandler || order.targetConnection != order.target.networkHandler
            || order.world != owner.getEntityWorld() || order.world != order.target.getEntityWorld() || clock.millis() < order.issuedAt || clock.millis() >= order.expiresAt
            || targetEligibility(owner, order.target) != null) {
            ceasefire(owner); return null;
        }
        return order;
    }
    /** Can always stop the caller's own order, even after OP permissions are revoked. */
    void ceasefire(ServerPlayerEntity owner) {
        if (owner == null) return;
        playerTargets.remove(owner.getUuid());
        for (var entry : loaded.entrySet()) {
            var agent = data.agents.get(entry.getKey().toString());
            if (agent != null && agent.owner.equals(owner.getUuidAsString())) halt(entry.getValue());
        }
    }
    void invalidatePlayer(ServerPlayerEntity player) {
        for (var order : new ArrayList<>(playerTargets.values())) if (order.owner.getUuid().equals(player.getUuid()) || order.target.getUuid().equals(player.getUuid())) ceasefire(order.owner);
    }
    String playerTargetStatus(ServerPlayerEntity owner) {
        var order = validPlayerTarget(owner);
        return order == null ? "Player target: none." : "Player target: " + order.target.getGameProfile().name() + " (" + order.target.getUuid() + "), "
            + Math.max(0, (order.expiresAt - clock.millis() + 999) / 1000) + "s remaining; Primitive and Ultimate Finals only.";
    }
    void validatePlayerTargets() {
        for (var order : new ArrayList<>(playerTargets.values())) validPlayerTarget(order.owner);
    }

    /** Only examine already loaded terrain; helpers never add chunk tickets. */
    static Vec3d spawnPlace(ServerPlayerEntity owner) {
        ServerWorld world = owner.getEntityWorld(); BlockPos origin = owner.getBlockPos();
        for (int radius = 2; radius <= 4; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
            BlockPos feet = origin.add(dx, 0, dz);
            if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4) || !world.isInBuildLimit(feet.up(2)) || !world.getWorldBorder().contains(feet)) continue;
            var floor = world.getBlockState(feet.down());
            Box space = new Box(feet.getX() - .2, feet.getY(), feet.getZ() - .2, feet.getX() + 1.2, feet.getY() + 2.7, feet.getZ() + 1.2);
            if (loadedRoom(world, space) && floor.isFullCube(world, feet.down()) && floor.getFluidState().isEmpty() && !floor.isOf(Blocks.MAGMA_BLOCK)
                && world.isBlockSpaceEmpty(null, space) && world.getOtherEntities(owner, space, Entity::isAlive).isEmpty()
                && world.getBlockState(feet).getFluidState().isEmpty()) return Vec3d.ofBottomCenter(feet);
        }
        return null;
    }
    int spawn(ServerPlayerEntity owner, String name) {
        if (!operator(owner.getCommandSource())) return reply(owner, "Only OP4 owners can create helpers.");
        if (!validName(name)) return reply(owner, "Helper names use 1–24 lowercase letters/numbers, - or _.");
        if (!owner.isAlive() || owner.isSpectator()) return reply(owner, "You must be alive and outside spectator mode to spawn a helper.");
        if (owned(owner, name) != null) return reply(owner, "You already have a helper named " + name + ".");
        if (count(owner) >= LIMIT) return reply(owner, "You have " + LIMIT + " helpers, including unloaded helpers. /agent dismiss <name> frees a slot.");
        if (data.agents.size() >= GLOBAL_LIMIT) return reply(owner, "The server has " + GLOBAL_LIMIT + " helpers, including unloaded helpers. Dismiss a helper to free a slot.");
        Vec3d place = spawnPlace(owner);
        if (place == null) return reply(owner, "No clear solid ground nearby for a golem. Move to an open area.");
        IronGolemEntity golem = EntityType.IRON_GOLEM.create(owner.getEntityWorld(), SpawnReason.COMMAND);
        if (golem == null) return reply(owner, "Could not create a helper.");
        golem.setPosition(place); prepare(golem);
        Agent agent = new Agent(owner.getUuidAsString(), name, Mode.FOLLOW, owner.getEntityWorld().getRegistryKey().getValue().toString(), place.x, place.y, place.z);
        data.agents.put(golem.getUuidAsString(), agent); save(); tags(golem, agent);
        if (!owner.getEntityWorld().spawnEntity(golem)) { data.agents.remove(golem.getUuidAsString()); save(); return reply(owner, "Could not spawn a helper here."); }
        loaded.put(golem.getUuid(), golem);
        return reply(owner, name + " is following you. /agent guard " + name + " guards its current area; /agent stay " + name + " pauses it.");
    }
    int mode(ServerPlayerEntity owner, String name, Mode mode) {
        if (!operator(owner.getCommandSource())) return reply(owner, "Only OP4 owners can change helper movement.");
        if (mode == null) return reply(owner, "Choose follow, guard, or stay.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        IronGolemEntity golem = loaded.get(UUID.fromString(entry.getKey()));
        if (golem == null || !golem.isAlive()) return reply(owner, "That helper is unloaded. Return to its area to change its mode.");
        if (golem.getEntityWorld() != owner.getEntityWorld()) return reply(owner, "Return to the helper's dimension to change its mode.");
        Agent agent = entry.getValue().mode(mode, golem); data.agents.put(entry.getKey(), agent); save(); tags(golem, agent); halt(golem);
        validatePlayerTargets();
        return reply(owner, name + " mode: " + mode.name().toLowerCase(Locale.ROOT) + ".");
    }
    int profile(ServerPlayerEntity owner, String name, Profile profile) {
        if (!operator(owner.getCommandSource())) return reply(owner, "Only OP4 owners can change helper profiles.");
        if (profile == null) return reply(owner, "Profiles: primitive, regular, ultimate_finals, debug, cli, api.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        var agent = entry.getValue().profile(profile);
        data.agents.put(entry.getKey(), agent); save();
        var golem = loaded.get(UUID.fromString(entry.getKey()));
        if (golem != null && golem.isAlive()) { tags(golem, agent); halt(golem); }
        validatePlayerTargets();
        return reply(owner, name + " profile: " + profile.label + ". " + profile.description + (golem == null ? " Saved for its next load." : ""));
    }
    int squad(ServerPlayerEntity owner, Mode mode) {
        if (!operator(owner.getCommandSource())) return reply(owner, "Only OP4 owners can control squads.");
        if (mode == null) return reply(owner, "Choose follow, guard, or stay.");
        Map<IronGolemEntity, Agent> changed = new LinkedHashMap<>(); int skipped = 0;
        for (var entry : data.agents.entrySet()) {
            var agent = entry.getValue();
            if (!agent.owner.equals(owner.getUuidAsString())) continue;
            var golem = loaded.get(UUID.fromString(entry.getKey()));
            if (golem == null || !golem.isAlive() || golem.getEntityWorld() != owner.getEntityWorld()) { skipped++; continue; }
            agent = agent.mode(mode, golem); entry.setValue(agent); changed.put(golem, agent);
        }
        if (!changed.isEmpty()) {
            save(); changed.forEach((golem, agent) -> { tags(golem, agent); halt(golem); });
        }
        validatePlayerTargets();
        return reply(owner, "Squad " + mode.name().toLowerCase(Locale.ROOT) + ": " + changed.size() + " changed; " + skipped + " skipped (unloaded or another dimension).");
    }
    int dismiss(ServerPlayerEntity owner, String name) {
        if (!operator(owner.getCommandSource())) return reply(owner, "Only OP4 owners can dismiss helpers.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        data.agents.remove(entry.getKey()); save(); UUID id = UUID.fromString(entry.getKey());
        IronGolemEntity golem = loaded.remove(id); lastAttack.remove(id);
        if (golem != null) golem.discard();
        validatePlayerTargets();
        return reply(owner, name + " dismissed." + (golem == null ? " Its saved entity will be removed when the area next loads." : ""));
    }
    int list(ServerPlayerEntity owner) {
        var agents = data.agents.entrySet().stream().filter(e -> e.getValue().owner.equals(owner.getUuidAsString())).map(e -> {
            var a = e.getValue(); var golem = loaded.get(UUID.fromString(e.getKey()));
            var pause = pauseReason(owner, golem, a);
            return a.name + ": " + a.profile.label + " / " + a.mode.name().toLowerCase(Locale.ROOT) + " (" + a.dimension + ", " + (golem == null ? "unloaded" : Math.round(golem.getHealth()) + "/" + Math.round(golem.getMaxHealth()) + " HP") + ")" + (pause == null ? "" : " — " + pause);
        }).toList();
        return reply(owner, agents.isEmpty() ? "No helpers. /agent spawn <name> creates one (" + LIMIT + " max per owner)." : "Helpers " + agents.size() + "/" + LIMIT + "; server " + data.agents.size() + "/" + GLOBAL_LIMIT + "\n" + String.join("\n", agents));
    }
    int status(ServerPlayerEntity owner, String name) {
        if (!operator(owner.getCommandSource())) return reply(owner, "Only OP4 owners can inspect helper diagnostics.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        var agent = entry.getValue(); var golem = loaded.get(UUID.fromString(entry.getKey())); var pause = pauseReason(owner, golem, agent);
        String target = golem == null || golem.getTarget() == null ? "none" : golem.getTarget().getType().getTranslationKey();
        return reply(owner, name + " [" + agent.profile.label + "]\n" + agent.profile.description + "\nMovement: " + agent.mode.name().toLowerCase(Locale.ROOT)
            + "; state: " + (pause == null ? "active" : pause) + "; combat: " + (playerCombatProfile(agent.profile) ? "hostile mobs or explicitly approved player" : agent.profile.combat ? "hostile mobs only" : "disabled")
            + "\nDimension: " + agent.dimension + "; loaded: " + (golem != null && golem.isAlive()) + "; target: " + target
            + "\nHP: " + (golem == null ? "unloaded" : Math.round(golem.getHealth()) + "/" + Math.round(golem.getMaxHealth()))
            + "; anchor: " + Math.round(agent.x) + ", " + Math.round(agent.y) + ", " + Math.round(agent.z) + "\n" + playerTargetStatus(owner));
    }
    String pauseReason(ServerPlayerEntity owner, IronGolemEntity golem, Agent agent) {
        if (golem == null) return "unloaded";
        if (!golem.isAlive() || golem.isRemoved()) return "helper unavailable";
        if (owner == null) return "owner offline";
        if (!operator(owner.getCommandSource())) return "owner needs OP4";
        if (!owner.isAlive()) return "owner dead";
        if (owner.isSpectator()) return "owner in spectator mode";
        if (owner.getEntityWorld() != golem.getEntityWorld()) return "owner in another dimension";
        if (agent.mode == Mode.STAY) return "staying";
        if (agent.mode == Mode.GUARD && !golem.getEntityWorld().getRegistryKey().getValue().toString().equals(agent.dimension)) return "guard in another dimension";
        if (agent.mode == Mode.FOLLOW && golem.squaredDistanceTo(owner) > 48 * 48) return "owner beyond 48-block follow range";
        return null;
    }
    static void halt(IronGolemEntity golem) {
        golem.setTarget(null); golem.setAttacker(null); golem.setAngryAt(null); golem.setAngerEndTime(0); golem.getNavigation().stop(); golem.stopMovement();
    }
    void tick() {
        validatePlayerTargets();
        if (server.getTicks() % 5 != 0) return;
        for (var entry : new ArrayList<>(loaded.entrySet())) {
            var golem = entry.getValue(); var agent = data.agents.get(entry.getKey().toString());
            if (golem.isRemoved() || !golem.isAlive()) { loaded.remove(entry.getKey()); continue; }
            if (agent == null) { loaded.remove(entry.getKey()); golem.discard(); continue; }
            control(golem, agent, server.getTicks());
        }
    }
    void control(IronGolemEntity golem, Agent agent, int ticks) {
        var owner = server.getPlayerManager().getPlayer(UUID.fromString(agent.owner));
        var order = validPlayerTarget(owner);
        golem.setAttacker(null); golem.setAngryAt(null); golem.setAngerEndTime(0);
        if (pauseReason(owner, golem, agent) != null) { halt(golem); return; }
        ServerWorld world = owner.getEntityWorld();
        // Anchored helpers never fight in a dimension they were moved into by another command.
        if (agent.mode == Mode.GUARD && !world.getRegistryKey().getValue().toString().equals(agent.dimension)) { halt(golem); return; }
        Vec3d center = agent.mode == Mode.FOLLOW ? owner.getEntityPos() : agent.anchor();
        double leash = agent.mode == Mode.FOLLOW ? 48 : 14;
        if (golem.getEntityPos().squaredDistanceTo(center) > leash * leash) {
            golem.setTarget(null);
            if (agent.mode == Mode.GUARD) golem.getNavigation().startMovingTo(center.x, center.y, center.z, 1);
            else halt(golem);
            return;
        }
        double sensing = agent.profile == Profile.PRIMITIVE ? 12 : 10;
        LivingEntity target = order != null && playerCombatProfile(agent.profile)
            ? playerPursuitReady(golem, agent, owner, order.target) ? order.target : null
            : !agent.profile.combat ? null : world.getEntitiesByClass(MobEntity.class, new Box(center, center).expand(sensing), mob -> hostile(mob)
            && mob.getEntityPos().squaredDistanceTo(center) <= sensing * sensing && mob.squaredDistanceTo(golem) <= 24 * 24 && golem.getVisibilityCache().canSee(mob))
            .stream().min(Comparator.comparing((MobEntity mob) -> agent.profile == Profile.PRIMITIVE || agent.mode != Mode.FOLLOW || mob.getTarget() != owner)
                .thenComparingInt(mob -> agent.profile == Profile.ULTIMATE_FINALS ? sharedFocusRank(golem, agent, owner, mob) : 0)
                .thenComparingDouble(mob -> mob.squaredDistanceTo(agent.profile == Profile.ULTIMATE_FINALS ? owner : golem))
                .thenComparing(MobEntity::getUuid)).orElse(null);
        golem.setTarget(target);
        if (target != null) {
            golem.lookAtEntity(target, 30, 30);
            Vec3d flank = agent.profile == Profile.ULTIMATE_FINALS ? flankPoint(golem, agent, owner, target) : null;
            if (flank == null) golem.getNavigation().startMovingTo(target, 1.1);
            else golem.getNavigation().startMovingTo(flank.x, flank.y, flank.z, 1.1);
            if (golem.isInAttackRange(target) && golem.getVisibilityCache().canSee(target) && ticks - lastAttack.getOrDefault(golem.getUuid(), -20) >= 20) {
                lastAttack.put(golem.getUuid(), ticks); golem.tryAttack(world, target);
            }
        } else if (golem.getEntityPos().squaredDistanceTo(center) > (agent.mode == Mode.FOLLOW ? 9 : 4)) {
            golem.getNavigation().startMovingTo(center.x, center.y, center.z, 1);
        } else { golem.getNavigation().stop(); golem.stopMovement(); }
    }
    int sharedFocusRank(IronGolemEntity self, Agent agent, ServerPlayerEntity owner, MobEntity target) {
        for (var entry : loaded.entrySet()) {
            var peer = entry.getValue(); var record = data.agents.get(entry.getKey().toString());
            if (peer != self && record != null && record.owner.equals(agent.owner) && record.profile == Profile.ULTIMATE_FINALS
                && pauseReason(owner, peer, record) == null && peer.getTarget() == target) return 0;
        }
        return 1;
    }
    Vec3d flankPoint(IronGolemEntity self, Agent agent, ServerPlayerEntity owner, LivingEntity target) {
        var order = target instanceof ServerPlayerEntity ? validPlayerTarget(owner) : null;
        var peers = loaded.entrySet().stream().filter(entry -> {
            var record = data.agents.get(entry.getKey().toString());
            var peer = entry.getValue();
            return record != null && record.owner.equals(agent.owner) && record.profile == Profile.ULTIMATE_FINALS
                && pauseReason(owner, peer, record) == null && peer.getTarget() == target
                && (target instanceof ServerPlayerEntity player ? order != null && order.target == player && playerCombatReady(peer, record, owner, player)
                    : hostile(target) && peer.getEntityWorld() == target.getEntityWorld() && peer.squaredDistanceTo(target) <= 24 * 24
                        && peer.getEntityPos().squaredDistanceTo(record.mode == Mode.FOLLOW ? owner.getEntityPos() : record.anchor()) <= (record.mode == Mode.FOLLOW ? 48 * 48 : 14 * 14)
                        && target.getEntityPos().squaredDistanceTo(record.mode == Mode.FOLLOW ? owner.getEntityPos() : record.anchor()) <= 10 * 10);
        }).sorted(Comparator.comparing(entry -> data.agents.get(entry.getKey().toString()).name)).map(Map.Entry::getKey).toList();
        int slot = peers.indexOf(self.getUuid());
        if (slot < 0 || peers.size() < 2) return null;
        double angle = slot * (Math.PI * 2 / peers.size());
        Vec3d point = target.getEntityPos().add(Math.cos(angle) * 1.6, 0, Math.sin(angle) * 1.6);
        BlockPos feet = BlockPos.ofFloored(point); ServerWorld world = owner.getEntityWorld();
        Box room = new Box(point.x - .7, point.y, point.z - .7, point.x + .7, point.y + 2.7, point.z + .7);
        if (!loadedRoom(world, room)) return null;
        for (int x = (int) Math.floor(room.minX); x <= (int) Math.floor(room.maxX); x++) for (int z = (int) Math.floor(room.minZ); z <= (int) Math.floor(room.maxZ); z++) {
            var at = new BlockPos(x, feet.getY(), z); var floor = world.getBlockState(at.down());
            if (!world.getWorldBorder().contains(at) || !floor.isFullCube(world, at.down()) || floor.isOf(Blocks.MAGMA_BLOCK)
                || !floor.getFluidState().isEmpty() || !world.getBlockState(at).getFluidState().isEmpty()) return null;
        }
        return world.isInBuildLimit(feet.up(2))
            && world.isBlockSpaceEmpty(self, room) && world.getOtherEntities(self, room, Entity::isAlive).isEmpty() ? point : null;
    }
    /** Collision shapes can inspect one block outside the requested room. */
    static boolean loadedRoom(ServerWorld world, Box room) {
        for (int x = ((int) Math.floor(room.minX) - 1) >> 4; x <= (((int) Math.floor(room.maxX) + 1) >> 4); x++)
            for (int z = ((int) Math.floor(room.minZ) - 1) >> 4; z <= (((int) Math.floor(room.maxZ) + 1) >> 4); z++)
                if (!world.isChunkLoaded(x, z)) return false;
        return true;
    }
    public static void initialize() {
        AgentActions.initialize();
        AgentChat.initialize();
        AgentCodeRequests.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var helpers = get(server);
            for (ServerWorld world : server.getWorlds()) for (Entity entity : world.iterateEntities()) helpers.load(entity);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> { if (isAgent(entity)) get(world.getServer()).load(entity); });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (isAgent(entity)) { var helpers = get(world.getServer()); helpers.loaded.remove(entity.getUuid()); helpers.lastAttack.remove(entity.getUuid()); }
        });
        ServerPlayerEvents.LEAVE.register(player -> get(player.getEntityWorld().getServer()).invalidatePlayer(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> get(player.getEntityWorld().getServer()).invalidatePlayer(oldPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> get(destination.getServer()).invalidatePlayer(player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) get(player.getEntityWorld().getServer()).invalidatePlayer(player);
            if (isAgent(entity) && entity.getEntityWorld() instanceof ServerWorld world) {
                var helpers = get(world.getServer());
                helpers.loaded.remove(entity.getUuid()); helpers.lastAttack.remove(entity.getUuid());
                if (helpers.data.agents.remove(entity.getUuidAsString()) != null) helpers.save();
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowDamage(entity, source));
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).tick());
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var root = CommandManager.literal("agent").requires(AgentCompanions::operator)
                .executes(c -> c.getSource().getEntity() instanceof ServerPlayerEntity player ? AgentMenu.open(player) : CommunityServer.info(c.getSource(), HELP));
            root.then(CommandManager.literal("help").executes(c -> CommunityServer.info(c.getSource(), HELP)));
            root.then(CommandManager.literal("menu").executes(c -> AgentMenu.open(c.getSource().getPlayerOrThrow())));
            root.then(CommandManager.literal("spawn").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).spawn(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name")))));
            for (Mode mode : Mode.values()) root.then(CommandManager.literal(mode.name().toLowerCase(Locale.ROOT)).then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).mode(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name"), mode))));
            root.then(CommandManager.literal("profile").then(CommandManager.argument("name", StringArgumentType.word())
                .then(CommandManager.argument("profile", StringArgumentType.word()).suggests((c, builder) -> {
                    for (Profile profile : Profile.values()) if (profile.id().startsWith(builder.getRemainingLowerCase())) builder.suggest(profile.id());
                    return builder.buildFuture();
                }).executes(c -> get(c.getSource().getServer()).profile(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name"), Profile.parse(StringArgumentType.getString(c, "profile")))))));
            root.then(CommandManager.literal("status").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).status(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name")))));
            var squad = CommandManager.literal("squad");
            for (Mode mode : Mode.values()) squad.then(CommandManager.literal(mode.name().toLowerCase(Locale.ROOT)).executes(c -> get(c.getSource().getServer()).squad(c.getSource().getPlayerOrThrow(), mode)));
            root.then(squad);
            root.then(CommandManager.literal("dismiss").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).dismiss(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name")))));
            root.then(CommandManager.literal("list").executes(c -> get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow())));
            AgentActions.attach(root);
            dispatcher.register(root);
            AgentActions.registerConsole(dispatcher);
        });
    }
}

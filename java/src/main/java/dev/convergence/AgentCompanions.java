package dev.convergence;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.convergence.mixin.AgentGoalAccess;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Server-controlled vanilla companions: navigation and combat work on either edition. */
public final class AgentCompanions {
    static final String TAG = "infinity_agent";
    static final int LIMIT = 3;
    static final String HELP = "Helpers: /agent spawn <name>, /agent follow <name>, /agent guard <name>, /agent stay <name>, /agent dismiss <name>, /agent list. Names: 1–24 lowercase letters/numbers, - or _. 3 per OP4 owner. Follow pauses beyond 48 blocks; return nearby to resume. Helpers pause while you are offline, dead, without OP4, or in another dimension; they never teleport or load chunks. Safe server actions: /agent suggest <request>, /agent pending, /agent approve <id>, /agent cancel <id>. An action runs only after your approval and live Codex review.";
    static final Map<MinecraftServer, AgentCompanions> INSTANCES = new WeakHashMap<>();
    final MinecraftServer server;
    final Path file;
    final Data data;
    final Map<UUID, IronGolemEntity> loaded = new HashMap<>();
    final Map<UUID, Integer> lastAttack = new HashMap<>();

    enum Mode { FOLLOW, GUARD, STAY }
    record Agent(String owner, String name, Mode mode, String dimension, double x, double y, double z) {
        Vec3d anchor() { return new Vec3d(x, y, z); }
        Agent mode(Mode mode, IronGolemEntity golem) {
            return new Agent(owner, name, mode, golem.getEntityWorld().getRegistryKey().getValue().toString(), golem.getX(), golem.getY(), golem.getZ());
        }
        boolean valid() {
            try { UUID.fromString(owner); } catch (Exception e) { return false; }
            return validName(name) && mode != null && dimension != null && Identifier.tryParse(dimension) != null
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000 && y >= -2048 && y <= 2048;
        }
    }
    static final class Data {
        int format = 1;
        Map<String, Agent> agents = new TreeMap<>();
    }
    AgentCompanions(MinecraftServer server, Path file) {
        this.server = server; this.file = file; this.data = read(file);
    }
    static AgentCompanions get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, s -> new AgentCompanions(s, s.getSavePath(WorldSavePath.ROOT).resolve("infinity-agents.json")));
    }
    static Data read(Path file) {
        if (!java.nio.file.Files.exists(file)) return new Data();
        try {
            Data data = CommunityServer.GSON.fromJson(java.nio.file.Files.readString(file), Data.class);
            if (data == null || data.format != 1 || data.agents == null) throw new IllegalArgumentException("Invalid agent data");
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
        return !isAgent(source.getAttacker()) || hostile(victim);
    }
    static void prepare(IronGolemEntity golem) {
        golem.clearGoalsAndTasks();
        ((AgentGoalAccess) golem).infinity$getTargetSelector().clear(goal -> true);
        golem.setPlayerCreated(true); golem.setPersistent(); golem.setCanPickUpLoot(false);
        golem.setTarget(null); golem.setAttacker(null); golem.setAngryAt(null); golem.setAngerEndTime(0);
        golem.getNavigation().stop();
    }
    void tags(IronGolemEntity golem, Agent agent) {
        new HashSet<>(golem.getCommandTags()).stream().filter(t -> t.startsWith("infinity_owner_") || t.startsWith("infinity_name_") || t.startsWith("infinity_mode_")).forEach(golem::removeCommandTag);
        golem.addCommandTag(TAG); golem.addCommandTag("infinity_owner_" + agent.owner);
        golem.addCommandTag("infinity_name_" + agent.name); golem.addCommandTag("infinity_mode_" + agent.mode.name().toLowerCase(Locale.ROOT));
        golem.setCustomName(Text.literal(agent.name + " [Helper]")); golem.setCustomNameVisible(true);
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

    /** Only examine already loaded terrain; helpers never add chunk tickets. */
    static Vec3d spawnPlace(ServerPlayerEntity owner) {
        ServerWorld world = owner.getEntityWorld(); BlockPos origin = owner.getBlockPos();
        for (int radius = 2; radius <= 4; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
            BlockPos feet = origin.add(dx, 0, dz);
            if (!world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4) || !world.isInBuildLimit(feet.up(2)) || !world.getWorldBorder().contains(feet)) continue;
            var floor = world.getBlockState(feet.down());
            Box space = new Box(feet.getX() - .2, feet.getY(), feet.getZ() - .2, feet.getX() + 1.2, feet.getY() + 2.7, feet.getZ() + 1.2);
            if (floor.isFullCube(world, feet.down()) && floor.getFluidState().isEmpty() && !floor.isOf(Blocks.MAGMA_BLOCK)
                && world.isBlockSpaceEmpty(null, space) && world.getOtherEntities(owner, space, Entity::isAlive).isEmpty()
                && world.getBlockState(feet).getFluidState().isEmpty()) return Vec3d.ofBottomCenter(feet);
        }
        return null;
    }
    int spawn(ServerPlayerEntity owner, String name) {
        if (!validName(name)) return reply(owner, "Helper names use 1–24 lowercase letters/numbers, - or _.");
        if (!owner.isAlive() || owner.isSpectator()) return reply(owner, "You must be alive and outside spectator mode to spawn a helper.");
        if (owned(owner, name) != null) return reply(owner, "You already have a helper named " + name + ".");
        if (count(owner) >= LIMIT) return reply(owner, "You have 3 helpers, including unloaded helpers. /agent dismiss <name> frees a slot.");
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
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        IronGolemEntity golem = loaded.get(UUID.fromString(entry.getKey()));
        if (golem == null || !golem.isAlive()) return reply(owner, "That helper is unloaded. Return to its area to change its mode.");
        if (golem.getEntityWorld() != owner.getEntityWorld()) return reply(owner, "Return to the helper's dimension to change its mode.");
        Agent agent = entry.getValue().mode(mode, golem); data.agents.put(entry.getKey(), agent); save(); tags(golem, agent); halt(golem);
        return reply(owner, name + " mode: " + mode.name().toLowerCase(Locale.ROOT) + ".");
    }
    int dismiss(ServerPlayerEntity owner, String name) {
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        data.agents.remove(entry.getKey()); save(); UUID id = UUID.fromString(entry.getKey());
        IronGolemEntity golem = loaded.remove(id); lastAttack.remove(id);
        if (golem != null) golem.discard();
        return reply(owner, name + " dismissed." + (golem == null ? " Its saved entity will be removed when the area next loads." : ""));
    }
    int list(ServerPlayerEntity owner) {
        var agents = data.agents.entrySet().stream().filter(e -> e.getValue().owner.equals(owner.getUuidAsString())).map(e -> {
            var a = e.getValue(); var golem = loaded.get(UUID.fromString(e.getKey()));
            return a.name + ": " + a.mode.name().toLowerCase(Locale.ROOT) + " (" + a.dimension + ", " + (golem == null ? "unloaded" : Math.round(golem.getHealth()) + "/" + Math.round(golem.getMaxHealth()) + " HP") + ")";
        }).toList();
        return reply(owner, agents.isEmpty() ? "No helpers. /agent spawn <name> creates one (3 max)." : String.join("\n", agents));
    }
    static void halt(IronGolemEntity golem) {
        golem.setTarget(null); golem.setAttacker(null); golem.setAngryAt(null); golem.setAngerEndTime(0); golem.getNavigation().stop(); golem.stopMovement();
    }
    void tick() {
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
        golem.setAttacker(null); golem.setAngryAt(null); golem.setAngerEndTime(0);
        if (owner == null || !owner.getPermissions().hasPermission(new Level(PermissionLevel.OWNERS)) || !owner.isAlive() || owner.isSpectator() || owner.getEntityWorld() != golem.getEntityWorld() || agent.mode == Mode.STAY) { halt(golem); return; }
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
        LivingEntity target = world.getEntitiesByClass(MobEntity.class, new Box(center, center).expand(10), mob -> hostile(mob)
            && mob.getEntityPos().squaredDistanceTo(center) <= 100 && mob.squaredDistanceTo(golem) <= 24 * 24 && golem.getVisibilityCache().canSee(mob))
            .stream().min(Comparator.comparing((MobEntity mob) -> agent.mode != Mode.FOLLOW || mob.getTarget() != owner)
                .thenComparingDouble(mob -> mob.squaredDistanceTo(golem))).orElse(null);
        golem.setTarget(target);
        if (target != null) {
            golem.lookAtEntity(target, 30, 30);
            golem.getNavigation().startMovingTo(target, 1.1);
            if (golem.isInAttackRange(target) && ticks - lastAttack.getOrDefault(golem.getUuid(), -20) >= 20) {
                lastAttack.put(golem.getUuid(), ticks); golem.tryAttack(world, target);
            }
        } else if (golem.getEntityPos().squaredDistanceTo(center) > (agent.mode == Mode.FOLLOW ? 9 : 4)) {
            golem.getNavigation().startMovingTo(center.x, center.y, center.z, 1);
        } else { golem.getNavigation().stop(); golem.stopMovement(); }
    }
    public static void initialize() {
        AgentActions.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var helpers = get(server);
            for (ServerWorld world : server.getWorlds()) for (Entity entity : world.iterateEntities()) helpers.load(entity);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> { if (isAgent(entity)) get(world.getServer()).load(entity); });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (isAgent(entity)) { var helpers = get(world.getServer()); helpers.loaded.remove(entity.getUuid()); helpers.lastAttack.remove(entity.getUuid()); }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
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
                .executes(c -> CommunityServer.info(c.getSource(), HELP));
            root.then(CommandManager.literal("help").executes(c -> CommunityServer.info(c.getSource(), HELP)));
            root.then(CommandManager.literal("spawn").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).spawn(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name")))));
            for (Mode mode : Mode.values()) root.then(CommandManager.literal(mode.name().toLowerCase(Locale.ROOT)).then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).mode(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name"), mode))));
            root.then(CommandManager.literal("dismiss").then(CommandManager.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).dismiss(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name")))));
            root.then(CommandManager.literal("list").executes(c -> get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow())));
            AgentActions.attach(root);
            dispatcher.register(root);
            AgentActions.registerConsole(dispatcher);
        });
    }
}

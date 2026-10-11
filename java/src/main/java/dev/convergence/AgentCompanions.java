package dev.convergence;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.world.entity.EntityTypes;
import com.google.gson.JsonParser;
import dev.convergence.mixin.AgentGoalAccess;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.Permission.HasCommandLevel;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-controlled vanilla companions: navigation and combat work on either edition. */
public final class AgentCompanions {
    static final String TAG = "infinity_agent";
    static final int LIMIT = 6;
    static final int GLOBAL_LIMIT = 24;
    static final int MAX_BYTES = 65_536;
    static final long PLAYER_TARGET_LIFETIME_MS = 5 * 60_000L;
    static final String HELP = "Open /agent or /agent menu for helper controls. /agent spawn <name>, /agent profile <name> <primitive|regular|ultimate_finals|debug|cli|api>, /agent follow <name>, /agent guard <name>, /agent stay <name>, /agent squad <follow|guard|stay>, /agent status <name>, /agent recall <name>, /agent dismiss <name>, /agent list. Names: 1–24 lowercase letters/numbers, - or _. 6 per OP4 owner; 24 server-wide, including unloaded helpers. Primitive proactively attacks nearby hostile mobs; Regular follows/guards and prioritizes owner threats. Ultimate Finals shares squad focus, charges with a real native spear, briefly glides with an equipped elytra, then stops gliding and switches to a real mace for a falling smash. Helpers use native player avatars that show their equipped weapons and elytra. /agent target <player> proposes an exact player target for Primitive and Ultimate Finals, requiring owner approval and live Codex approval before combat. PvP and team rules apply; /agent ceasefire stops it immediately. Player orders expire after five minutes, session/life/dimension or permission changes, owner-target distance beyond 48 blocks, or no usable aggressive helpers. Follow can pursue beyond the 24-block damage limit and around walls; swings require clear sight. Guard waits outside its 14-block anchor range. Helpers never attack pets. Debug, CLI, and API are passive physical profiles. /agent data <name> shows limited live data; /agent ask <name> <question> asks a helper; /agent code <name> <request> queues a code request for owner and live Codex review. Follow pauses beyond 48 blocks; return nearby to resume. Helpers pause while you are offline, dead, without OP4, or in another dimension; they never automatically teleport or load chunks. Bring here in the helper menu (or /agent recall <name>) explicitly moves an already loaded helper beside you, preserves its health, stats and profile, and clears your squad's player-target orders and pending target approvals. Safe server actions: /agent suggest <request>, /agent pending, /agent approve <id>, /agent cancel <id>. An action runs only after your approval and live Codex review.";
    static final Map<MinecraftServer, AgentCompanions> INSTANCES = new WeakHashMap<>();
    /** Player each helper may target. Since 26.1 Mob.get/setTarget re-check canAttack, which refuses players for player-built golems. */
    private static final Map<IronGolem, ServerPlayer> APPROVED_TARGETS = new WeakHashMap<>();
    final MinecraftServer server;
    final Path file;
    final Data data;
    final Clock clock;
    final Map<UUID, IronGolem> loaded = new HashMap<>();
    final Map<UUID, Integer> lastAttack = new HashMap<>();
    final Map<UUID, PlayerTarget> playerTargets = new HashMap<>();
    /** Exists only during one synchronous, explicitly requested transfer. Never grants portal access. */
    private RecallPermit recallPermit;
    private record RecallPermit(ServerPlayer owner, IronGolem helper, Agent agent, TeleportTransition target) {}
    static final int LEAP_COOLDOWN = 80, LEAP_TIMEOUT = 50;
    static final double AIR_SPEED = .42, LEAP_HEIGHT = 3;
    final Map<UUID, Integer> nextLeap = new HashMap<>();
    final Map<UUID, Aerial> aerial = new HashMap<>();
    static final class Aerial {
        final Agent agent; final LivingEntity target; final Vec3 landing; final int started;
        int firstHit = -1;
        Aerial(Agent agent, LivingEntity target, Vec3 landing, int started) {
            this.agent = agent; this.target = target; this.landing = landing; this.started = started;
        }
    }

    /** Exact connected entity and handler identities prevent orders surviving reconnects or respawns. Never persisted. */
    record PlayerTarget(ServerPlayer owner, ServerGamePacketListenerImpl ownerConnection, ServerPlayer target,
        ServerGamePacketListenerImpl targetConnection, ServerLevel world, long issuedAt, long expiresAt) {}

    enum Mode { FOLLOW, GUARD, STAY }
    enum Profile {
        PRIMITIVE("Primitive", "Ready to attack the nearest hostile mob within 12 blocks; shares an explicitly approved player target with its squad. Never attacks pets.", true),
        REGULAR("Regular", "Follows or guards, attacking nearby hostile mobs.", true),
        ULTIMATE_FINALS("Ultimate Finals", "Shares focus, charges with a native spear, briefly glides with an equipped elytra, then stops gliding and switches to a mace smash. Native player avatars show the equipped weapon and elytra. Player combat requires both approvals; PvP and team rules apply.", true),
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
        Vec3 anchor() { return new Vec3(x, y, z); }
        Agent mode(Mode mode, IronGolem golem) {
            return new Agent(owner, name, mode, profile, golem.level().dimension().identifier().toString(), golem.getX(), golem.getY(), golem.getZ());
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
        return INSTANCES.computeIfAbsent(server, s -> new AgentCompanions(s, s.getWorldPath(LevelResource.ROOT).resolve("infinity-agents.json")));
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
    static boolean operator(CommandSourceStack source) { return source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.OWNERS)); }
    static boolean isAgent(Entity entity) { return entity instanceof IronGolem && entity.entityTags().contains(TAG); }
    public static boolean registeredHelper(Entity entity) { return isAgent(entity); }
    public static boolean weaponCombat(Entity entity) {
        if(!isAgent(entity) || !(entity.level() instanceof ServerLevel world))return false;
        var companions=INSTANCES.get(world.getServer());
        var agent=companions==null?null:companions.data.agents.get(entity.getStringUUID());
        return agent!=null && companions.loaded.get(entity.getUUID())==entity && agent.profile==Profile.ULTIMATE_FINALS
            && ( ((LivingEntity)entity).getMainHandItem().is(net.minecraft.world.item.Items.MACE)
                || ((LivingEntity)entity).getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SPEAR));
    }
    private static boolean weaponReach(IronGolem golem,LivingEntity target) {
        if(weaponCombat(golem) && golem.getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SPEAR) && golem.isUsingItem()) {
            // Native charging uses an eye ray, whereas MobEntity's melee box has
            // a different minimum-range dead zone. Recompute the exact native ray;
            // do not reject a legitimate charge or permit a forced distant hit.
            var hits=net.minecraft.world.entity.projectile.ProjectileUtil.getHitEntitiesAlong(golem,golem.getAttackRangeWith(golem.getActiveItem()),
                entity->entity==target,net.minecraft.world.level.ClipContext.Block.COLLIDER);
            return hits.map(left->false,right->right.stream().anyMatch(hit->hit.getEntity()==target));
        }
        return golem.isWithinMeleeAttackRange(target);
    }
    /** Only the exact player chosen by control() from a validated order; damage keeps its separate approval gate. */
    public static boolean approvedPlayerTarget(IronGolem golem, LivingEntity target) {
        return target != null && APPROVED_TARGETS.get(golem) == target;
    }
    public static boolean weaponTarget(Entity attacker,Entity target) {
        return attacker instanceof IronGolem golem && target instanceof LivingEntity living
            && weaponCombat(golem) && living.isAlive() && !living.isRemoved()
            && allowDamage(living,golem.damageSources().mobAttack(golem));
    }
    public static void aimWeapon(LivingEntity entity) {
        if(entity instanceof IronGolem golem && weaponCombat(golem) && golem.getTarget()!=null)AgentWeapons.aim(golem,golem.getTarget());
    }
    static boolean hostile(LivingEntity entity) {
        return entity instanceof Enemy && entity.isAlive() && !entity.isSpectator() && !isAgent(entity)
            && !(entity instanceof TamableAnimal pet && pet.isTame());
    }
    /** Independent damage gate also covers targets forced by commands or another mod. */
    static boolean allowDamage(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
        if (!isAgent(source.getEntity())) return true;
        if (!(source.getEntity() instanceof IronGolem golem)
            || !(golem.level() instanceof ServerLevel world)) return false;
        var companions = INSTANCES.get(world.getServer());
        var agent = companions == null ? null : companions.data.agents.get(golem.getStringUUID());
        if (agent == null || !agent.profile.combat) return false;
        var owner = world.getServer().getPlayerList().getPlayer(UUID.fromString(agent.owner));
        var order = companions.validPlayerTarget(owner);
        if (companions.pauseReason(owner, golem, agent) != null) return false;
        if (!weaponReach(golem,victim) || !golem.getSensing().hasLineOfSight(victim)) return false;
        if (victim instanceof ServerPlayer player) {
            return order != null && order.target == player && companions.playerCombatReady(golem, agent, owner, player)
                && golem.getSensing().hasLineOfSight(player);
        }
        return hostile(victim);
    }
    static void prepare(IronGolem golem) {
        golem.removeFreeWill();
        ((AgentGoalAccess) golem).infinity$getTargetSelector().removeAllGoals(goal -> true);
        golem.setPlayerCreated(true); golem.setPersistenceRequired(); golem.setCanPickUpLoot(false);
        APPROVED_TARGETS.remove(golem); golem.setTarget(null); golem.setLastHurtByMob(null); golem.setPersistentAngerTarget(null); golem.setPersistentAngerEndTime(0);
        golem.getNavigation().stop();
    }
    void tags(IronGolem golem, Agent agent) {
        new HashSet<>(golem.entityTags()).stream().filter(t -> t.startsWith("infinity_owner_") || t.startsWith("infinity_name_") || t.startsWith("infinity_mode_") || t.startsWith("infinity_profile_")).forEach(golem::removeTag);
        golem.addTag(TAG); golem.addTag("infinity_owner_" + agent.owner);
        golem.addTag("infinity_name_" + agent.name); golem.addTag("infinity_mode_" + agent.mode.name().toLowerCase(Locale.ROOT));
        golem.addTag("infinity_profile_" + agent.profile.id());
        golem.setCustomName(Component.literal(agent.name + " [" + agent.profile.label + "]")); golem.setCustomNameVisible(true); AgentAvatars.attach(golem);
    }
    void load(Entity entity) {
        if (!isAgent(entity)) return;
        var golem = (IronGolem) entity;
        Agent agent = data.agents.get(golem.getStringUUID());
        if (agent == null) { golem.discard(); return; } // Dismissed while its chunk was unloaded.
        prepare(golem); tags(golem, agent); loaded.put(golem.getUUID(), golem);
    }
    void unload(Entity entity) {
        // Dismissal, death, chunk unload and dimension change all remove the entity object.
        APPROVED_TARGETS.remove(entity);
        if (loaded.remove(entity.getUUID(), entity)) {
            AgentAvatars.remove((IronGolem)entity);
            aerial.remove(entity.getUUID()); lastAttack.remove(entity.getUUID()); nextLeap.remove(entity.getUUID());
        }
    }
    Map.Entry<String, Agent> owned(ServerPlayer owner, String name) {
        return data.agents.entrySet().stream().filter(e -> e.getValue().owner.equals(owner.getStringUUID()) && e.getValue().name.equals(name)).findFirst().orElse(null);
    }
    int count(ServerPlayer owner) { return (int) data.agents.values().stream().filter(a -> a.owner.equals(owner.getStringUUID())).count(); }
    static int reply(ServerPlayer owner, String text) { return CommunityServer.say(owner, text); }

    static boolean playerCombatProfile(Profile profile) { return profile == Profile.PRIMITIVE || profile == Profile.ULTIMATE_FINALS; }
    String playerPairEligibility(ServerPlayer owner, ServerPlayer target) {
        if (owner == null || server.getPlayerList().getPlayer(owner.getUUID()) != owner || owner.hasDisconnected()) return "Owner must remain connected.";
        if (!operator(owner.createCommandSourceStack())) return "Owner needs OP4.";
        if (!owner.isAlive() || owner.isRemoved() || owner.isSpectator()) return "Owner must be alive and outside spectator mode.";
        if (target == null || server.getPlayerList().getPlayer(target.getUUID()) != target || target.hasDisconnected()) return "Target must remain connected.";
        if (owner == target || owner.getUUID().equals(target.getUUID())) return "Helpers cannot target their owner.";
        if (!target.isAlive() || target.isRemoved() || (target.gameMode() != GameType.SURVIVAL && target.gameMode() != GameType.ADVENTURE)) return "Target must be alive in survival or adventure mode.";
        if (owner.level() != target.level()) return "Owner and target must be in the same dimension.";
        if (!owner.canHarmPlayer(target) || !target.canHarmPlayer(owner)) return "World PvP or team friendly-fire rules forbid this target.";
        if (owner.distanceToSqr(target) > 48 * 48) return "Target must remain within 48 blocks of the owner.";
        return null;
    }
    /** Order availability is separate from target distance: a usable squad can pursue or wait inside its leash. */
    boolean playerOrderHelperAvailable(IronGolem golem, Agent agent, ServerPlayer owner) {
        if (owner == null || !playerCombatProfile(agent.profile) || !agent.owner.equals(owner.getStringUUID()) || pauseReason(owner, golem, agent) != null) return false;
        Vec3 center = agent.mode == Mode.FOLLOW ? owner.position() : agent.anchor();
        double leash = agent.mode == Mode.FOLLOW ? 48 : 14;
        return golem.position().distanceToSqr(center) <= leash * leash;
    }
    boolean playerPursuitReady(IronGolem golem, Agent agent, ServerPlayer owner, ServerPlayer target) {
        if (!playerOrderHelperAvailable(golem, agent, owner) || golem.level() != target.level()) return false;
        Vec3 center = agent.mode == Mode.FOLLOW ? owner.position() : agent.anchor();
        double leash = agent.mode == Mode.FOLLOW ? 48 : 14;
        return target.position().distanceToSqr(center) <= leash * leash;
    }
    boolean playerCombatReady(IronGolem golem, Agent agent, ServerPlayer owner, ServerPlayer target) {
        return playerPursuitReady(golem, agent, owner, target) && golem.distanceToSqr(target) <= 24 * 24;
    }
    String targetEligibility(ServerPlayer owner, ServerPlayer target) {
        String reason = playerPairEligibility(owner, target);
        if (reason != null) return reason;
        for (var entry : loaded.entrySet()) {
            var agent = data.agents.get(entry.getKey().toString());
            if (agent != null && playerOrderHelperAvailable(entry.getValue(), agent, owner)) return null;
        }
        return "No active loaded Primitive or Ultimate Finals helper is available inside its movement leash.";
    }
    /** Called only by the final, owner-approved local-console action review. Text replies never call it. */
    boolean assignPlayerTarget(ServerPlayer owner, ServerPlayer target) {
        if (targetEligibility(owner, target) != null) return false;
        ceasefire(owner);
        long now = clock.millis();
        playerTargets.put(owner.getUUID(), new PlayerTarget(owner, owner.connection, target, target.connection, owner.level(), now, now + PLAYER_TARGET_LIFETIME_MS));
        return true;
    }
    PlayerTarget validPlayerTarget(ServerPlayer owner) {
        if (owner == null) return null;
        var order = playerTargets.get(owner.getUUID());
        if (order == null) return null;
        if (order.owner != owner || order.ownerConnection != owner.connection || order.targetConnection != order.target.connection
            || order.world != owner.level() || order.world != order.target.level() || clock.millis() < order.issuedAt || clock.millis() >= order.expiresAt
            || targetEligibility(owner, order.target) != null) {
            ceasefire(owner); return null;
        }
        return order;
    }
    /** Can always stop the caller's own order, even after OP permissions are revoked. */
    void ceasefire(ServerPlayer owner) {
        if (owner == null) return;
        playerTargets.remove(owner.getUUID());
        for (var entry : loaded.entrySet()) {
            var agent = data.agents.get(entry.getKey().toString());
            if (agent != null && agent.owner.equals(owner.getStringUUID())) halt(entry.getValue());
        }
    }
    void invalidatePlayer(ServerPlayer player) {
        for (var order : new ArrayList<>(playerTargets.values())) if (order.owner.getUUID().equals(player.getUUID()) || order.target.getUUID().equals(player.getUUID())) ceasefire(order.owner);
    }
    String playerTargetStatus(ServerPlayer owner) {
        var order = validPlayerTarget(owner);
        return order == null ? "Player target: none." : "Player target: " + order.target.getGameProfile().name() + " (" + order.target.getUUID() + "), "
            + Math.max(0, (order.expiresAt - clock.millis() + 999) / 1000) + "s remaining; Primitive and Ultimate Finals only.";
    }
    void validatePlayerTargets() {
        for (var order : new ArrayList<>(playerTargets.values())) validPlayerTarget(order.owner);
    }

    /** Only examine already loaded terrain; helpers never add chunk tickets. */
    static Vec3 spawnPlace(ServerPlayer owner) {
        return spawnPlace(owner, null);
    }
    private static Vec3 spawnPlace(ServerPlayer owner, IronGolem helper) {
        ServerLevel world = owner.level(); BlockPos origin = owner.blockPosition();
        for (int radius = 2; radius <= 4; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
            BlockPos feet = origin.offset(dx, 0, dz);
            if (!world.hasChunk(feet.getX() >> 4, feet.getZ() >> 4) || !world.isInWorldBounds(feet.above(2)) || !world.getWorldBorder().isWithinBounds(feet)) continue;
            AABB space = helper == null
                ? new AABB(feet.getX() - .2, feet.getY(), feet.getZ() - .2, feet.getX() + 1.2, feet.getY() + 2.7, feet.getZ() + 1.2)
                : helper.getDimensions(helper.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet));
            var minimum = BlockPos.containing(space.minX, space.minY, space.minZ);
            var maximum = BlockPos.containing(space.maxX, space.maxY, space.maxZ);
            if (!loadedRoom(world, space) || !world.isInWorldBounds(minimum) || !world.isInWorldBounds(maximum)
                || !world.getWorldBorder().isWithinBounds(minimum) || !world.getWorldBorder().isWithinBounds(maximum)) continue;
            boolean supported = true;
            for (var support : BlockPos.betweenClosed(BlockPos.containing(space.minX, feet.getY() - 1, space.minZ),
                    BlockPos.containing(space.maxX - 1e-6, feet.getY() - 1, space.maxZ - 1e-6))) {
                var floor = world.getBlockState(support);
                if (!floor.isCollisionShapeFullBlock(world, support) || !floor.getFluidState().isEmpty() || floor.is(Blocks.MAGMA_BLOCK)) { supported = false; break; }
            }
            if (supported && world.noBlockCollision(null, space) && world.getEntities(helper == null ? owner : helper, space, Entity::isAlive).isEmpty()
                && world.getBlockState(feet).getFluidState().isEmpty()) return Vec3.atBottomCenterOf(feet);
        }
        return null;
    }
    /** ModeEntityMixin may allow only the exact transfer validated by recall, on the server thread. */
    public static boolean allowRecallTeleport(Entity entity, TeleportTransition target) {
        if (!(entity.level() instanceof ServerLevel source)) return false;
        var helpers = INSTANCES.get(source.getServer());
        var permit = helpers == null ? null : helpers.recallPermit;
        return permit != null && source.getServer().isSameThread() && permit.helper == entity && permit.target == target
            && AgentMenu.allowed(permit.owner) && permit.owner.level() == target.newLevel()
            && helpers.loaded.get(entity.getUUID()) == entity && helpers.data.agents.get(entity.getStringUUID()) == permit.agent
            && permit.agent.owner.equals(permit.owner.getStringUUID()) && entity.isAlive() && !entity.isRemoved();
    }
    int recall(ServerPlayer owner, String name) {
        // Check the player's own permissions, not elevated permissions inherited from execute-as.
        if (!AgentMenu.allowed(owner)) return reply(owner, "Bring here requires a living, connected, non-spectator OP4 owner.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        var id = UUID.fromString(entry.getKey());
        var golem = loaded.get(id);
        if (golem == null || !golem.isAlive() || golem.isRemoved() || !isAgent(golem)
            || !(golem.level() instanceof ServerLevel source) || source.getEntity(id) != golem)
            return reply(owner, "That helper is unloaded or unavailable. Return near its saved area in "
                + entry.getValue().dimension + ". Bring here never loads distant chunks.");
        if (golem.isVehicle() || golem.isPassenger()) return reply(owner, "Dismount this helper and remove its passengers before using Bring here.");
        Vec3 place = spawnPlace(owner, golem);
        if (place == null) return reply(owner, "No clear solid ground nearby for a golem. Move to an open area and try Bring here again.");
        if (recallPermit != null || !server.isSameThread()) return reply(owner, "A helper transfer is already in progress. Try again.");
        // A recall cancels this owner's player-target approvals and movement targets before crossing worlds.
        AgentActions.get(server).ceasefire(owner);
        halt(golem); lastAttack.remove(id); nextLeap.remove(id);
        var target = new TeleportTransition(owner.level(), place, Vec3.ZERO, golem.getYRot(), golem.getXRot(), TeleportTransition.DO_NOTHING);
        Entity moved;
        recallPermit = new RecallPermit(owner, golem, entry.getValue(), target);
        try { moved = golem.teleport(target); }
        finally { recallPermit = null; }
        if (!(moved instanceof IronGolem recalled) || !recalled.getUUID().equals(id)
            || recalled.level() != owner.level() || !recalled.isAlive() || recalled.isRemoved())
            return reply(owner, "The helper could not move here. Its roster is preserved; check its status and try again.");
        // Native transfer copies the saved entity (including UUID, health, attributes and admin metadata).
        // Rebind only after it succeeds; an old-world unload cannot remove this new loaded identity.
        var agent = entry.getValue().mode(Mode.FOLLOW, recalled);
        data.agents.put(entry.getKey(), agent); save(); prepare(recalled); tags(recalled, agent); halt(recalled);
        loaded.put(id, recalled);
        return reply(owner, name + " is beside you and following. Its health, stats and profile were kept; player-target orders were cleared.");
    }
    int spawn(ServerPlayer owner, String name) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can create helpers.");
        if (!validName(name)) return reply(owner, "Helper names use 1–24 lowercase letters/numbers, - or _.");
        if (!owner.isAlive() || owner.isSpectator()) return reply(owner, "You must be alive and outside spectator mode to spawn a helper.");
        if (owned(owner, name) != null) return reply(owner, "You already have a helper named " + name + ".");
        if (count(owner) >= LIMIT) return reply(owner, "You have " + LIMIT + " helpers, including unloaded helpers. /agent dismiss <name> frees a slot.");
        if (data.agents.size() >= GLOBAL_LIMIT) return reply(owner, "The server has " + GLOBAL_LIMIT + " helpers, including unloaded helpers. Dismiss a helper to free a slot.");
        Vec3 place = spawnPlace(owner);
        if (place == null) return reply(owner, "No clear solid ground nearby for a golem. Move to an open area.");
        IronGolem golem = EntityTypes.IRON_GOLEM.create(owner.level(), EntitySpawnReason.COMMAND);
        if (golem == null) return reply(owner, "Could not create a helper.");
        golem.setPos(place); prepare(golem);
        Agent agent = new Agent(owner.getStringUUID(), name, Mode.FOLLOW, owner.level().dimension().identifier().toString(), place.x, place.y, place.z);
        data.agents.put(golem.getStringUUID(), agent); save(); tags(golem, agent);
        if (!owner.level().addFreshEntity(golem)) { data.agents.remove(golem.getStringUUID()); save(); return reply(owner, "Could not spawn a helper here."); }
        loaded.put(golem.getUUID(), golem);
        return reply(owner, name + " is following you. /agent guard " + name + " guards its current area; /agent stay " + name + " pauses it.");
    }
    int mode(ServerPlayer owner, String name, Mode mode) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can change helper movement.");
        if (mode == null) return reply(owner, "Choose follow, guard, or stay.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        IronGolem golem = loaded.get(UUID.fromString(entry.getKey()));
        if (golem == null || !golem.isAlive()) return reply(owner, "That helper is unloaded. Return to its area to change its mode.");
        if (golem.level() != owner.level()) return reply(owner, "That helper is in another dimension. Choose Bring here in its menu, or /agent recall " + name + ", before giving movement orders.");
        Agent agent = entry.getValue().mode(mode, golem); data.agents.put(entry.getKey(), agent); save(); tags(golem, agent); halt(golem);
        validatePlayerTargets();
        return reply(owner, name + " mode: " + mode.name().toLowerCase(Locale.ROOT) + ".");
    }
    int profile(ServerPlayer owner, String name, Profile profile) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can change helper profiles.");
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
    int squad(ServerPlayer owner, Mode mode) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can control squads.");
        if (mode == null) return reply(owner, "Choose follow, guard, or stay.");
        Map<IronGolem, Agent> changed = new LinkedHashMap<>(); int skipped = 0;
        for (var entry : data.agents.entrySet()) {
            var agent = entry.getValue();
            if (!agent.owner.equals(owner.getStringUUID())) continue;
            var golem = loaded.get(UUID.fromString(entry.getKey()));
            if (golem == null || !golem.isAlive() || golem.level() != owner.level()) { skipped++; continue; }
            agent = agent.mode(mode, golem); entry.setValue(agent); changed.put(golem, agent);
        }
        if (!changed.isEmpty()) {
            save(); changed.forEach((golem, agent) -> { tags(golem, agent); halt(golem); });
        }
        validatePlayerTargets();
        return reply(owner, "Squad " + mode.name().toLowerCase(Locale.ROOT) + ": " + changed.size() + " changed; " + skipped + " skipped (unloaded or another dimension).");
    }
    int dismiss(ServerPlayer owner, String name) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can dismiss helpers.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        // TreeMap entries are live nodes: deleting a node with two children
        // replaces its key with its successor. Capture identity before removal.
        String key=entry.getKey();UUID id=UUID.fromString(key);
        data.agents.remove(key); save();
        IronGolem golem = loaded.remove(id); lastAttack.remove(id); nextLeap.remove(id); aerial.remove(id);
        if (golem != null) {AgentAvatars.remove(golem);golem.discard();}
        validatePlayerTargets();
        return reply(owner, name + " dismissed." + (golem == null ? " Its saved entity will be removed when the area next loads." : ""));
    }
    int list(ServerPlayer owner) {
        var agents = data.agents.entrySet().stream().filter(e -> e.getValue().owner.equals(owner.getStringUUID())).map(e -> {
            var a = e.getValue(); var golem = loaded.get(UUID.fromString(e.getKey()));
            var pause = pauseReason(owner, golem, a);
            return a.name + ": " + a.profile.label + " / " + a.mode.name().toLowerCase(Locale.ROOT) + " (" + a.dimension + ", " + (golem == null ? "unloaded" : Math.round(golem.getHealth()) + "/" + Math.round(golem.getMaxHealth()) + " HP") + ")" + (pause == null ? "" : " — " + pause);
        }).toList();
        return reply(owner, agents.isEmpty() ? "No helpers. /agent spawn <name> creates one (" + LIMIT + " max per owner)." : "Helpers " + agents.size() + "/" + LIMIT + "; server " + data.agents.size() + "/" + GLOBAL_LIMIT + "\n" + String.join("\n", agents));
    }
    int status(ServerPlayer owner, String name) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can inspect helper diagnostics.");
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        var agent = entry.getValue(); var golem = loaded.get(UUID.fromString(entry.getKey())); var pause = pauseReason(owner, golem, agent);
        String target = golem == null || golem.getTarget() == null ? "none" : golem.getTarget().getType().getDescriptionId();
        return reply(owner, name + " [" + agent.profile.label + "]\n" + agent.profile.description + "\nMovement: " + agent.mode.name().toLowerCase(Locale.ROOT)
            + "; equipment: " + (golem == null ? "unloaded" : golem.getMainHandItem().getHoverName().getString() + (golem.isFallFlying() ? " / gliding" : "")) + "; state: " + (pause == null ? "active" : pause) + "; combat: " + (playerCombatProfile(agent.profile) ? "hostile mobs or explicitly approved player" : agent.profile.combat ? "hostile mobs only" : "disabled")
            + "\nLocation: " + location(golem, agent) + "; target: " + target
            + "\nHP: " + (golem == null ? "unloaded" : Math.round(golem.getHealth()) + "/" + Math.round(golem.getMaxHealth()))
            + "; anchor: " + Math.round(agent.x) + ", " + Math.round(agent.y) + ", " + Math.round(agent.z) + "\n" + playerTargetStatus(owner));
    }
    static String location(IronGolem golem, Agent agent) {
        boolean live = golem != null && golem.isAlive() && !golem.isRemoved();
        return live ? golem.level().dimension().identifier() + " at " + golem.getBlockX() + ", " + golem.getBlockY() + ", " + golem.getBlockZ()
            : "unloaded; saved anchor " + agent.dimension + " at " + Math.round(agent.x) + ", " + Math.round(agent.y) + ", " + Math.round(agent.z);
    }
    String pauseReason(ServerPlayer owner, IronGolem golem, Agent agent) {
        if (golem == null) return "unloaded";
        if (!golem.isAlive() || golem.isRemoved()) return "helper unavailable";
        if (owner == null) return "owner offline";
        if (!operator(owner.createCommandSourceStack())) return "owner needs OP4";
        if (!owner.isAlive()) return "owner dead";
        if (owner.isSpectator()) return "owner in spectator mode";
        if (owner.level() != golem.level()) return "owner in another dimension";
        if (agent.mode == Mode.STAY) return "staying";
        if (agent.mode == Mode.GUARD && !golem.level().dimension().identifier().toString().equals(agent.dimension)) return "guard in another dimension";
        if (agent.mode == Mode.FOLLOW && golem.distanceToSqr(owner) > 48 * 48) return "owner beyond 48-block follow range";
        return null;
    }
    void halt(IronGolem golem) {
        cancelAerial(golem);
        APPROVED_TARGETS.remove(golem); golem.setTarget(null); golem.setLastHurtByMob(null); golem.setPersistentAngerTarget(null); golem.setPersistentAngerEndTime(0); golem.getNavigation().stop(); golem.stopInPlace();
    }
    void tick() {
        validatePlayerTargets();
        for (var id : new ArrayList<>(aerial.keySet())) {
            var golem = loaded.get(id);
            if (golem == null) aerial.remove(id);
            else tickAerial(golem, server.getTickCount());
        }
        if (server.getTickCount() % 5 != 0) return;
        for (var entry : new ArrayList<>(loaded.entrySet())) {
            var golem = entry.getValue(); var agent = data.agents.get(entry.getKey().toString());
            if (golem.isRemoved() || !golem.isAlive()) { aerial.remove(entry.getKey()); loaded.remove(entry.getKey()); continue; }
            if (agent == null) { aerial.remove(entry.getKey()); loaded.remove(entry.getKey()); golem.discard(); continue; }
            control(golem, agent, server.getTickCount());
        }
    }
    void control(IronGolem golem, Agent agent, int ticks) {
        var owner = server.getPlayerList().getPlayer(UUID.fromString(agent.owner));
        var order = validPlayerTarget(owner);
        golem.setLastHurtByMob(null); golem.setPersistentAngerTarget(null); golem.setPersistentAngerEndTime(0);
        if (pauseReason(owner, golem, agent) != null) { halt(golem); return; }
        ServerLevel world = owner.level();
        // Anchored helpers never fight in a dimension they were moved into by another command.
        if (agent.mode == Mode.GUARD && !world.dimension().identifier().toString().equals(agent.dimension)) { halt(golem); return; }
        Vec3 center = agent.mode == Mode.FOLLOW ? owner.position() : agent.anchor();
        double leash = agent.mode == Mode.FOLLOW ? 48 : 14;
        if (golem.position().distanceToSqr(center) > leash * leash) {
            cancelAerial(golem);
            APPROVED_TARGETS.remove(golem); golem.setTarget(null);
            if (agent.mode == Mode.GUARD) golem.getNavigation().moveTo(center.x, center.y, center.z, 1);
            else halt(golem);
            return;
        }
        if (aerial.containsKey(golem.getUUID())) return;
        double sensing = agent.profile == Profile.PRIMITIVE ? 12 : 10;
        LivingEntity target = order != null && playerCombatProfile(agent.profile)
            ? playerPursuitReady(golem, agent, owner, order.target) ? order.target : null
            : !agent.profile.combat ? null : world.getEntitiesOfClass(Mob.class, new AABB(center, center).inflate(sensing), mob -> hostile(mob)
            && mob.position().distanceToSqr(center) <= sensing * sensing && mob.distanceToSqr(golem) <= 24 * 24 && golem.getSensing().hasLineOfSight(mob))
            .stream().min(Comparator.comparing((Mob mob) -> agent.profile == Profile.PRIMITIVE || agent.mode != Mode.FOLLOW || mob.getTarget() != owner)
                .thenComparingInt(mob -> agent.profile == Profile.ULTIMATE_FINALS ? sharedFocusRank(golem, agent, owner, mob) : 0)
                .thenComparingDouble(mob -> mob.distanceToSqr(agent.profile == Profile.ULTIMATE_FINALS ? owner : golem))
                .thenComparing(Mob::getUUID)).orElse(null);
        if (target instanceof ServerPlayer player) APPROVED_TARGETS.put(golem, player);
        else APPROVED_TARGETS.remove(golem);
        golem.setTarget(target);
        if (target != null) {
            golem.lookAt(target, 30, 30);
            if(agent.profile==Profile.ULTIMATE_FINALS) {
                AgentWeapons.spear(golem,target);
                // Spears have a real minimum reach. Use a mace once the target
                // closes inside that reach instead of charging harmlessly at point blank.
                double closeRange=golem.getAttackRangeWith(golem.getActiveItem()).effectiveMinRange(golem)+(golem.getBbWidth()+target.getBbWidth())*.5;
                if(!golem.isWithinMeleeAttackRange(target) && golem.distanceToSqr(target)<closeRange*closeRange) {
                    AgentWeapons.mace(golem,target);
                    golem.getNavigation().moveTo(target,1.1);
                    strike(golem,target,ticks);return;
                }
                var kinetic=golem.getMainHandItem().get(net.minecraft.core.component.DataComponents.KINETIC_WEAPON);
                if(kinetic!=null && golem.getTicksUsingItem()<kinetic.delayTicks() && golem.distanceToSqr(target)>=16) {
                    // Wind up before rushing into the spear's short-range dead zone.
                    golem.getNavigation().stop();return;
                }
                if(kinetic!=null && golem.getTicksUsingItem()>=kinetic.delayTicks() && startAerial(golem,agent,owner,target,ticks))return;
            }
            Vec3 flank = agent.profile == Profile.ULTIMATE_FINALS ? flankPoint(golem, agent, owner, target) : null;
            Vec3 intercept = agent.profile == Profile.ULTIMATE_FINALS ? intercept(golem, agent, owner, target) : null;
            if (flank == null && intercept != null) golem.getNavigation().moveTo(intercept.x, intercept.y, intercept.z, 1.1);
            else if (flank == null) golem.getNavigation().moveTo(target, 1.1);
            else golem.getNavigation().moveTo(flank.x, flank.y, flank.z, 1.1);
            if(agent.profile!=Profile.ULTIMATE_FINALS)strike(golem,target,ticks);
        } else if (golem.position().distanceToSqr(center) > (agent.mode == Mode.FOLLOW ? 9 : 4)) {
            AgentWeapons.stop(golem);
            golem.getNavigation().moveTo(center.x, center.y, center.z, 1);
        } else { AgentWeapons.stop(golem);golem.getNavigation().stop(); golem.stopInPlace(); }
    }

    boolean strike(IronGolem golem, LivingEntity target, int ticks) {
        if (!golem.isWithinMeleeAttackRange(target) || !golem.getSensing().hasLineOfSight(target)
            || ticks - lastAttack.getOrDefault(golem.getUUID(), -20) < 20) return false;
        lastAttack.put(golem.getUUID(), ticks);
        return golem.doHurtTarget((ServerLevel)golem.level(), target);
    }
    boolean insideLeash(Agent agent, ServerPlayer owner, Vec3 point) {
        return point.distanceToSqr(agent.mode == Mode.FOLLOW ? owner.position() : agent.anchor())
            <= (agent.mode == Mode.FOLLOW ? 48 * 48 : 14 * 14);
    }
    boolean tacticTarget(IronGolem golem, Agent agent, ServerPlayer owner, LivingEntity target) {
        if (agent == null || agent.profile != Profile.ULTIMATE_FINALS || pauseReason(owner, golem, agent) != null
            || !target.isAlive() || target.isRemoved() || target.level() != golem.level()
            || !insideLeash(agent, owner, golem.position()) || !insideLeash(agent, owner, target.position())) return false;
        if (target instanceof ServerPlayer player) {
            var order = validPlayerTarget(owner);
            return order != null && order.target == player && playerCombatReady(golem, agent, owner, player);
        }
        return hostile(target) && golem.distanceToSqr(target) <= 24 * 24;
    }
    /** Lead a moving target by at most two blocks; native navigation still resolves obstacles. */
    Vec3 intercept(IronGolem golem, Agent agent, ServerPlayer owner, LivingEntity target) {
        Vec3 velocity = target.getDeltaMovement();
        if (!Double.isFinite(velocity.x) || !Double.isFinite(velocity.z)) return null;
        Vec3 lead = new Vec3(velocity.x, 0, velocity.z).scale(8);
        if (lead.lengthSqr() > 4) lead = lead.normalize().scale(2);
        Vec3 point = target.position().add(lead);
        return insideLeash(agent, owner, point) && landingClear(golem, point) ? point : null;
    }
    boolean landingClear(IronGolem golem, Vec3 point) {
        var world = (ServerLevel)golem.level();
        AABB box = golem.getBoundingBox().move(point.subtract(golem.position()));
        if (!loadedRoom(world, box) || !world.isInWorldBounds(BlockPos.containing(point).above(3))
            || !world.noBlockCollision(golem, box)) return false;
        int y = (int)Math.floor(point.y);
        for (int x = (int)Math.floor(box.minX); x <= (int)Math.floor(box.maxX); x++)
            for (int z = (int)Math.floor(box.minZ); z <= (int)Math.floor(box.maxZ); z++) {
                var feet = new BlockPos(x, y, z); var floor = world.getBlockState(feet.below());
                if (!world.getWorldBorder().isWithinBounds(feet) || !floor.isCollisionShapeFullBlock(world, feet.below())
                    || floor.is(Blocks.MAGMA_BLOCK) || !floor.getFluidState().isEmpty()
                    || !world.getBlockState(feet).getFluidState().isEmpty()) return false;
            }
        return true;
    }
    /** Conservative full-height corridor avoids low ceilings and never inspects unloaded chunks. */
    boolean airClear(IronGolem golem, Agent agent, ServerPlayer owner, Vec3 from, Vec3 to, double extraHeight) {
        var world = (ServerLevel)golem.level();
        int steps = Math.max(1, (int)Math.ceil(from.distanceTo(to) * 2));
        if (steps > 40) return false;
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.lerp(to, i / (double)steps);
            AABB box = golem.getBoundingBox().move(point.subtract(golem.position())).expandTowards(0, extraHeight, 0);
            if (!insideLeash(agent, owner, point) || !insideLeash(agent, owner, point.add(0, extraHeight, 0))
                || !loadedRoom(world, box) || !world.isInWorldBounds(BlockPos.containing(box.minX, box.minY, box.minZ))
                || !world.isInWorldBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))
                || !world.getWorldBorder().isWithinBounds(BlockPos.containing(box.minX, box.minY, box.minZ))
                || !world.getWorldBorder().isWithinBounds(BlockPos.containing(box.maxX, box.maxY, box.maxZ))
                || !world.noBlockCollision(golem, box)) return false;
            for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
                if (!world.getFluidState(at).isEmpty()) return false;
        }
        return true;
    }
    boolean startAerial(IronGolem golem, Agent agent, ServerPlayer owner, LivingEntity target, int ticks) {
        if (!tacticTarget(golem, agent, owner, target) || !golem.onGround() || golem.isInWater()
            || ticks < nextLeap.getOrDefault(golem.getUUID(), 0) || !golem.getSensing().hasLineOfSight(target)
            || Math.abs(target.getY() - golem.getY()) > .25 || golem.distanceToSqr(target) < 16 || golem.distanceToSqr(target) > 49
            || aerial.values().stream().anyMatch(a -> a.agent.owner.equals(agent.owner) && a.target == target)) return false;
        var peers = loaded.values().stream().filter(peer -> {
            var record = data.agents.get(peer.getStringUUID());
            return record != null && record.owner.equals(agent.owner) && tacticTarget(peer, record, owner, target)
                && (peer.getTarget() == null || peer.getTarget() == target);
        }).sorted(Comparator.comparing(peer -> data.agents.get(peer.getStringUUID()).name)).toList();
        if (peers.isEmpty() || peers.get(Math.floorMod(ticks / LEAP_COOLDOWN, peers.size())) != golem) return false;
        Vec3 predicted = intercept(golem, agent, owner, target);
        if (predicted == null) predicted = target.position();
        Vec3 direction = predicted.subtract(golem.position()).multiply(1, 0, 1).normalize();
        Vec3 landing = new Vec3(predicted.x - direction.x * 1.6, golem.getY(), predicted.z - direction.z * 1.6);
        if (!landingClear(golem, landing) || !airClear(golem, agent, owner, golem.position(), landing, LEAP_HEIGHT)) return false;
        nextLeap.put(golem.getUUID(), ticks + LEAP_COOLDOWN);
        AgentWeapons.spear(golem,target);
        aerial.put(golem.getUUID(), new Aerial(agent, target, landing, ticks));
        golem.getNavigation().stop(); golem.setDeltaMovement(direction.x * AIR_SPEED, .62, direction.z * AIR_SPEED);
        golem.setOnGround(false); golem.needsSync = true;
        return true;
    }
    void cancelAerial(IronGolem golem) {
        AgentWeapons.stop(golem);
        if (aerial.remove(golem.getUUID()) != null) {
            golem.getNavigation().stop();
            golem.setDeltaMovement(0, Math.min(0, golem.getDeltaMovement().y), 0); golem.needsSync = true;
        }
    }
    void tickAerial(IronGolem golem, int ticks) {
        var move = aerial.get(golem.getUUID()); if (move == null) return;
        var owner = server.getPlayerList().getPlayer(UUID.fromString(move.agent.owner));
        if (data.agents.get(golem.getStringUUID()) != move.agent || !tacticTarget(golem, move.agent, owner, move.target)
            || ticks - move.started > LEAP_TIMEOUT || !golem.getSensing().hasLineOfSight(move.target)) { cancelAerial(golem); return; }
        if (move.firstHit >= 0) {
            if(golem.onGround())AgentWeapons.spear(golem,move.target);
            golem.getNavigation().moveTo(move.target, 1.1);
            if (ticks - move.firstHit >= 20) { strike(golem, move.target, ticks); cancelAerial(golem); }
            return;
        }
        if (golem.onGround() && ticks > move.started) {
            AgentWeapons.mace(golem,move.target);
            if (strike(golem, move.target, ticks)) move.firstHit = ticks;
            else cancelAerial(golem);
            return;
        }
        AgentWeapons.aim(golem,move.target);
        if(ticks-move.started>=4 && ticks-move.started<7)AgentWeapons.glide(golem);
        if(ticks-move.started>=7) {
            AgentWeapons.mace(golem,move.target);
            if(golem.fallDistance>1.5 && strike(golem,move.target,ticks)) {move.firstHit=ticks;return;}
        }
        Vec3 delta = move.landing.subtract(golem.position()).multiply(1, 0, 1);
        Vec3 horizontal = delta.lengthSqr() > AIR_SPEED * AIR_SPEED ? delta.normalize().scale(AIR_SPEED) : delta;
        double vertical = ticks - move.started >= 7 ? Math.min(golem.getDeltaMovement().y, -.35) : golem.getDeltaMovement().y;
        Vec3 velocity = new Vec3(horizontal.x, vertical, horizontal.z);
        Vec3 next = golem.position().add(velocity);
        // Let vanilla collide with the checked landing floor rather than cancelling just before touchdown.
        next = new Vec3(next.x, Math.max(move.landing.y, next.y), next.z);
        if (!landingClear(golem, move.landing) || !airClear(golem, move.agent, owner, golem.position(), next, 0)) { cancelAerial(golem); return; }
        golem.setDeltaMovement(velocity); golem.needsSync = true;
    }
    int sharedFocusRank(IronGolem self, Agent agent, ServerPlayer owner, Mob target) {
        for (var entry : loaded.entrySet()) {
            var peer = entry.getValue(); var record = data.agents.get(entry.getKey().toString());
            if (peer != self && record != null && record.owner.equals(agent.owner) && record.profile == Profile.ULTIMATE_FINALS
                && pauseReason(owner, peer, record) == null && peer.getTarget() == target) return 0;
        }
        return 1;
    }
    Vec3 flankPoint(IronGolem self, Agent agent, ServerPlayer owner, LivingEntity target) {
        var order = target instanceof ServerPlayer ? validPlayerTarget(owner) : null;
        var peers = loaded.entrySet().stream().filter(entry -> {
            var record = data.agents.get(entry.getKey().toString());
            var peer = entry.getValue();
            return record != null && record.owner.equals(agent.owner) && record.profile == Profile.ULTIMATE_FINALS
                && pauseReason(owner, peer, record) == null && peer.getTarget() == target
                && (target instanceof ServerPlayer player ? order != null && order.target == player && playerCombatReady(peer, record, owner, player)
                    : hostile(target) && peer.level() == target.level() && peer.distanceToSqr(target) <= 24 * 24
                        && peer.position().distanceToSqr(record.mode == Mode.FOLLOW ? owner.position() : record.anchor()) <= (record.mode == Mode.FOLLOW ? 48 * 48 : 14 * 14)
                        && target.position().distanceToSqr(record.mode == Mode.FOLLOW ? owner.position() : record.anchor()) <= 10 * 10);
        }).sorted(Comparator.comparing(entry -> data.agents.get(entry.getKey().toString()).name)).map(Map.Entry::getKey).toList();
        int slot = peers.indexOf(self.getUUID());
        if (slot < 0 || peers.size() < 2) return null;
        double angle = slot * (Math.PI * 2 / peers.size());
        Vec3 point = target.position().add(Math.cos(angle) * 1.6, 0, Math.sin(angle) * 1.6);
        if (!insideLeash(agent, owner, point)) return null;
        BlockPos feet = BlockPos.containing(point); ServerLevel world = owner.level();
        AABB room = new AABB(point.x - .7, point.y, point.z - .7, point.x + .7, point.y + 2.7, point.z + .7);
        if (!loadedRoom(world, room)) return null;
        for (int x = (int) Math.floor(room.minX); x <= (int) Math.floor(room.maxX); x++) for (int z = (int) Math.floor(room.minZ); z <= (int) Math.floor(room.maxZ); z++) {
            var at = new BlockPos(x, feet.getY(), z); var floor = world.getBlockState(at.below());
            if (!world.getWorldBorder().isWithinBounds(at) || !floor.isCollisionShapeFullBlock(world, at.below()) || floor.is(Blocks.MAGMA_BLOCK)
                || !floor.getFluidState().isEmpty() || !world.getBlockState(at).getFluidState().isEmpty()) return null;
        }
        return world.isInWorldBounds(feet.above(2))
            && world.noBlockCollision(self, room) && world.getEntities(self, room, Entity::isAlive).isEmpty() ? point : null;
    }
    /** Collision shapes can inspect one block outside the requested room. */
    static boolean loadedRoom(ServerLevel world, AABB room) {
        for (int x = ((int) Math.floor(room.minX) - 1) >> 4; x <= (((int) Math.floor(room.maxX) + 1) >> 4); x++)
            for (int z = ((int) Math.floor(room.minZ) - 1) >> 4; z <= (((int) Math.floor(room.maxZ) + 1) >> 4); z++)
                if (!world.hasChunk(x, z)) return false;
        return true;
    }
    public static void initialize() {
        AgentActions.initialize();
        AgentChat.initialize();
        AgentCodeRequests.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var helpers = get(server);
            for (ServerLevel world : server.getAllLevels()) for (Entity entity : world.getAllEntities()) helpers.load(entity);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> { if (isAgent(entity)) get(world.getServer()).load(entity); });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (isAgent(entity)) get(world.getServer()).unload(entity);
        });
        ServerPlayerEvents.LEAVE.register(player -> get(player.level().getServer()).invalidatePlayer(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> get(player.level().getServer()).invalidatePlayer(oldPlayer));
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> get(destination.getServer()).invalidatePlayer(player));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) get(player.level().getServer()).invalidatePlayer(player);
            if (isAgent(entity) && entity.level() instanceof ServerLevel world) {
                var helpers = get(world.getServer());
                AgentAvatars.remove((IronGolem)entity);
                helpers.loaded.remove(entity.getUUID()); helpers.lastAttack.remove(entity.getUUID()); helpers.nextLeap.remove(entity.getUUID()); helpers.aerial.remove(entity.getUUID());
                if (helpers.data.agents.remove(entity.getStringUUID()) != null) helpers.save();
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> allowDamage(entity, source));
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).tick());
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            var root = Commands.literal("agent").requires(AgentCompanions::operator)
                .executes(c -> c.getSource().getEntity() instanceof ServerPlayer player ? AgentMenu.open(player) : CommunityServer.info(c.getSource(), HELP));
            root.then(Commands.literal("help").executes(c -> CommunityServer.info(c.getSource(), HELP)));
            root.then(Commands.literal("menu").executes(c -> AgentMenu.open(c.getSource().getPlayerOrException())));
            root.then(Commands.literal("spawn").then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).spawn(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            root.then(Commands.literal("recall").then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).recall(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            for (Mode mode : Mode.values()) root.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).mode(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name"), mode))));
            root.then(Commands.literal("profile").then(Commands.argument("name", StringArgumentType.word())
                .then(Commands.argument("profile", StringArgumentType.word()).suggests((c, builder) -> {
                    for (Profile profile : Profile.values()) if (profile.id().startsWith(builder.getRemainingLowerCase())) builder.suggest(profile.id());
                    return builder.buildFuture();
                }).executes(c -> get(c.getSource().getServer()).profile(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name"), Profile.parse(StringArgumentType.getString(c, "profile")))))));
            root.then(Commands.literal("status").then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).status(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            var squad = Commands.literal("squad");
            for (Mode mode : Mode.values()) squad.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(c -> get(c.getSource().getServer()).squad(c.getSource().getPlayerOrException(), mode)));
            root.then(squad);
            root.then(Commands.literal("dismiss").then(Commands.argument("name", StringArgumentType.word()).executes(c -> get(c.getSource().getServer()).dismiss(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "name")))));
            root.then(Commands.literal("list").executes(c -> get(c.getSource().getServer()).list(c.getSource().getPlayerOrException())));
            AgentActions.attach(root);
            dispatcher.register(root);
            AgentActions.registerConsole(dispatcher);
        });
    }
}

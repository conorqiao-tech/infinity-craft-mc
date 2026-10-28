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
    public static final net.minecraft.world.entity.EntityDimensions HELPER_DIMENSIONS =
        net.minecraft.world.entity.EntityDimensions.scalable(.6F, 1.8F).withEyeHeight(1.62F);
    static final int LIMIT = 50;
    static final int GLOBAL_LIMIT = 50;
    static final int MAX_BYTES = 65_536;
    static final long PLAYER_TARGET_LIFETIME_MS = 5 * 60_000L;
    static final String HELP = "Open /agent or /agent menu for helper controls. /agent spawn <name>, /agent profile <name> <primitive|regular|ultimate_finals|debug|cli|api>, /agent follow <name>, /agent guard <name>, /agent stay <name>, /agent squad <follow|guard|stay>, /agent status <name>, /agent recall <name>, /agent dismiss <name>, /agent list. Names: 1–24 lowercase letters/numbers, - or _. 50 per OP4 owner; 50 server-wide, including unloaded helpers. Primitive proactively attacks nearby hostile mobs; Regular follows/guards and prioritizes owner threats. Ultimate Finals shares squad focus, charges with an Infinity spear backed by native weapon mechanics, briefly glides with an equipped elytra, then stops gliding and switches to its Infinity mace for a falling smash. Close enemies can trigger a checked short mace leap. Regular and Primitive use Infinity swords. Helpers use native player avatars that show their equipped weapons and elytra. /agent target <player> proposes an exact player target for Primitive and Ultimate Finals, requiring owner approval and live Codex approval before combat. PvP and team rules apply; /agent ceasefire stops it immediately. Player orders expire after five minutes, session/life/dimension or permission changes, owner-target distance beyond 48 blocks, or no usable aggressive helpers. Follow can pursue beyond the 24-block damage limit and around walls; swings require clear sight. Guard waits outside its 14-block anchor range. Pets and other non-hostile mobs require an exact confirmed selection in Orders → Choose any nearby target; they are never picked automatically. Debug, CLI, and API are passive physical profiles. /agent data <name> shows limited live data; /agent ask <name> <question> asks a helper; /agent code <name> <request> queues a code request for owner and live Codex review. Follow pauses beyond 48 blocks; return nearby to resume. Helpers pause while you are offline, dead, without OP4, or in another dimension; they never automatically teleport or load chunks. Bring here in the helper menu (or /agent recall <name>) explicitly moves an already loaded helper beside you, preserves its health, stats and profile, and clears your squad's mob and player-target orders and pending target approvals. Safe server actions: /agent suggest <request>, /agent pending, /agent approve <id>, /agent cancel <id>. An action runs only after your approval and live Codex review.";
    static final Map<MinecraftServer, AgentCompanions> INSTANCES = new WeakHashMap<>();
    /** Player each helper may target. Since 26.1 Mob.get/setTarget re-check canAttack, which refuses players for player-built golems. */
    private static final Map<IronGolem, ServerPlayer> APPROVED_TARGETS = new WeakHashMap<>();
    final MinecraftServer server;
    final Path file;
    final Data data;
    final Clock clock;
    // A failed ownership rollback requires manual inspection. Never let a later
    // creation silently overwrite its unresolved saved roster in this runtime.
    boolean creationRecoveryRequired;
    final Map<UUID, IronGolem> loaded = new HashMap<>();
    final Map<UUID, Integer> lastAttack = new HashMap<>();
    final Map<UUID, PlayerTarget> playerTargets = new HashMap<>();
    private final Map<UUID, FormationPlan> formationPlans = new HashMap<>();
    private record FormationPlan(ServerPlayer owner, ServerGamePacketListenerImpl connection, ServerLevel world,
        Vec3 center, float yaw, Formation shape, int tick, List<IronGolem> peers, Map<UUID, Vec3> points) {}
    /** Exists only during one synchronous, explicitly requested transfer. Never grants portal access. */
    private RecallPermit recallPermit;
    private record RecallPermit(ServerPlayer owner, IronGolem helper, Agent agent, TeleportTransition target) {}
    static final int LEAP_COOLDOWN = 80, LEAP_TIMEOUT = 50;
    static final double AIR_SPEED = .42, LEAP_HEIGHT = 3;
    final Map<UUID, Integer> nextLeap = new HashMap<>();
    final Map<UUID, Aerial> aerial = new HashMap<>();
    record ComboFeedback(String phase, String detail, int tick) {
        String text() { return phase + (detail.isBlank() ? "." : ": " + detail); }
    }
    final Map<UUID, ComboFeedback> combatFeedback = new HashMap<>();
    ComboFeedback comboFeedback(UUID id) { return combatFeedback.get(id); }
    private void feedback(IronGolem helper, String phase, String detail, int tick) {
        var before = combatFeedback.get(helper.getUUID());
        if (before == null || !before.phase.equals(phase) || !before.detail.equals(detail))
            combatFeedback.put(helper.getUUID(), new ComboFeedback(phase, detail, tick));
    }
    static final class Aerial {
        final Agent agent; final LivingEntity target; Vec3 landing; final int started;
        int lastPlan;
        String tracking = "Following the checked landing route.";
        int firstHit = -1;
        Aerial(Agent agent, LivingEntity target, Vec3 landing, int started) {
            this.agent = agent; this.target = target; this.landing = landing; this.started = started;
            this.lastPlan = started;
        }
    }

    /** Exact connected entity and handler identities prevent orders surviving reconnects or respawns. Never persisted. */
    record PlayerTarget(ServerPlayer owner, ServerGamePacketListenerImpl ownerConnection, ServerPlayer target,
        ServerGamePacketListenerImpl targetConnection, ServerLevel world, long issuedAt, long expiresAt) {}

    enum Mode { FOLLOW, GUARD, STAY }
    enum Formation { ESCORT, WEDGE, RING }
    static Formation formation(ServerPlayer owner){return Formation.values()[Math.floorMod(GameModes.state(owner).getIntOr("helper_formation",0),Formation.values().length)];}
    static void cycleFormation(ServerPlayer owner){
        if(!AgentMenu.allowed(owner))return;
        GameModes.state(owner).putInt("helper_formation",(formation(owner).ordinal()+1)%Formation.values().length);
        PlayerPersistence.save(owner);
    }
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
        // A failed batch rollback leaves the main roster and .previous as
        // distinct recovery evidence. Nothing may rotate or overwrite either
        // file until the host has inspected them, including automatic callbacks.
        if (creationRecoveryRequired) return;
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
        return agent!=null && companions.loaded.get(entity.getUUID())==entity && agent.profile.combat
            && ( ((LivingEntity)entity).getMainHandItem().is(net.minecraft.world.item.Items.MACE)
                || ((LivingEntity)entity).getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SPEAR)
                || ((LivingEntity)entity).getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SWORD));
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
    /** Native golem target exclusions may yield only to this exact reviewed mob order. */
    public static boolean approvedEntityTarget(IronGolem golem, LivingEntity target) {
        if (!isAgent(golem) || target == null || target instanceof ServerPlayer || !(golem.level() instanceof ServerLevel world)) return false;
        var companions = INSTANCES.get(world.getServer());
        var agent = companions == null ? null : companions.data.agents.get(golem.getStringUUID());
        if (agent == null || !playerCombatProfile(agent.profile) || companions.loaded.get(golem.getUUID()) != golem
            || world.getEntity(golem.getUUID()) != golem) return false;
        var owner = world.getServer().getPlayerList().getPlayer(UUID.fromString(agent.owner));
        return companions.pauseReason(owner, golem, agent) == null && AgentEntityTargets.matches(owner, target)
            && companions.insideLeash(agent, owner, golem.position()) && companions.insideLeash(agent, owner, target.position());
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
        return (hostile(victim) || approvedEntityTarget(golem, victim)) && golem.distanceToSqr(victim) <= 24 * 24
            && companions.insideLeash(agent, owner, golem.position())
            && companions.insideLeash(agent, owner, victim.position());
    }
    static void prepare(IronGolem golem) {
        HelperPowerDefaults.applyRegistered(golem);
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
        HelperPowerDefaults.applyRegistered(golem);
        golem.refreshDimensions();
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
            aerial.remove(entity.getUUID()); lastAttack.remove(entity.getUUID()); nextLeap.remove(entity.getUUID()); combatFeedback.remove(entity.getUUID());
        }
    }
    Map.Entry<String, Agent> owned(ServerPlayer owner, String name) {
        return data.agents.entrySet().stream().filter(e -> e.getValue().owner.equals(owner.getStringUUID()) && e.getValue().name.equals(name)).findFirst().orElse(null);
    }
    int count(ServerPlayer owner) { return (int) data.agents.values().stream().filter(a -> a.owner.equals(owner.getStringUUID())).count(); }
    /** Saved/unloaded identities reserve capacity just like loaded helpers. */
    int remaining(ServerPlayer owner) {
        return creationRecoveryRequired ? 0 : Math.max(0, Math.min(LIMIT - count(owner), GLOBAL_LIMIT - data.agents.size()));
    }
    private boolean rosterWritable(ServerPlayer owner) {
        if (!creationRecoveryRequired) return true;
        reply(owner, "Helper ownership is frozen after a recovery failure. No change was saved. Ask the host to inspect the roster and its .previous recovery copy.");
        return false;
    }
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
        // Reading through an old entity must not stop a replacement session's order.
        if (order.owner != owner) return null;
        if (order.ownerConnection != owner.connection || order.targetConnection != order.target.connection
            || order.world != owner.level() || order.world != order.target.level() || clock.millis() < order.issuedAt || clock.millis() >= order.expiresAt
            || targetEligibility(owner, order.target) != null) {
            ceasefire(owner); return null;
        }
        return order;
    }
    /** Can always stop the caller's own order, even after OP permissions are revoked. */
    void ceasefire(ServerPlayer owner) {
        if (owner == null) return;
        var order = playerTargets.get(owner.getUUID());
        boolean currentOwner = server.getPlayerList().getPlayer(owner.getUUID()) == owner;
        if (!currentOwner && (order == null || order.owner != owner)) return;
        if (order != null && !playerTargets.remove(owner.getUUID(), order)) return;
        AgentEntityTargets.clear(owner);
        for (var entry : loaded.entrySet()) {
            var agent = data.agents.get(entry.getKey().toString());
            if (agent != null && agent.owner.equals(owner.getStringUUID())) halt(entry.getValue());
        }
    }
    void invalidatePlayer(ServerPlayer player) {
        AgentEntityTargets.invalidate(player);
        for (var order : new ArrayList<>(playerTargets.values()))
            if (order.owner == player || order.target == player) ceasefire(order.owner);
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
        var places = spawnPlaces(owner, helper, 1, 4);
        return places.isEmpty() ? null : places.getFirst();
    }
    private static List<Vec3> spawnPlaces(ServerPlayer owner, IronGolem helper, int count, int maxRadius) {
        ServerLevel world = owner.level(); BlockPos origin = owner.blockPosition();
        var places = new ArrayList<Vec3>(); var reserved = new ArrayList<AABB>();
        for (int radius = 2; radius <= maxRadius; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
            BlockPos feet = origin.offset(dx, 0, dz);
            if (!world.hasChunk(feet.getX() >> 4, feet.getZ() >> 4) || !world.isInWorldBounds(feet.above(2)) || !world.getWorldBorder().isWithinBounds(feet)) continue;
            AABB space = helper == null
                ? HELPER_DIMENSIONS.makeBoundingBox(Vec3.atBottomCenterOf(feet))
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
            if (supported && reserved.stream().noneMatch(space::intersects) && world.noBlockCollision(null, space)
                && world.getEntities(helper == null ? owner : helper, space, Entity::isAlive).isEmpty()
                && world.getBlockState(feet).getFluidState().isEmpty()) {
                places.add(Vec3.atBottomCenterOf(feet)); reserved.add(space);
                if (places.size() == count) return List.copyOf(places);
            }
        }
        return List.of(); // Never commit a smaller batch than the owner selected.
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
        if (!rosterWritable(owner)) return 0;
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
        return reply(owner, name + " is beside you and following. Its health, stats and profile were kept; mob and player-target orders were cleared.");
    }
    int spawn(ServerPlayer owner, String name) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can create helpers.");
        if (creationRecoveryRequired) return reply(owner, "Helper creation is blocked after an ownership recovery failure. The host must inspect the saved roster before any more helpers are created.");
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
        if (data.agents.containsKey(golem.getStringUUID())) return reply(owner, "A helper identity could not be reserved. Nothing was created.");
        Agent agent = new Agent(owner.getStringUUID(), name, Mode.FOLLOW, owner.level().dimension().identifier().toString(), place.x, place.y, place.z);
        data.agents.put(golem.getStringUUID(), agent);
        try { save(); }
        catch (RuntimeException failure) {
            // Drop only this new identity so no later save can persist it. The
            // atomic writer left the roster file unchanged; retrying the save
            // here would only rotate .previous again.
            data.agents.remove(golem.getStringUUID(), agent);
            reply(owner, "Could not save helper ownership. Nothing spawned. Check server storage before retrying.");
            return 0;
        }
        tags(golem, agent); HelperPowerDefaults.fillNewSpawn(golem);
        if (!owner.level().addFreshEntity(golem)) { data.agents.remove(golem.getStringUUID()); save(); return reply(owner, "Could not spawn a helper here."); }
        loaded.put(golem.getUUID(), golem);
        return reply(owner, name + " is following you. /agent guard " + name + " guards its current area; /agent stay " + name + " pauses it.");
    }
    /** One explicit menu activation, one bounded native batch, no existing helper mutation. */
    int spawnBatch(ServerPlayer owner, int count, Profile profile) {
        if (owner == null || !server.isSameThread() || owner.level().getServer() != server || !AgentMenu.allowed(owner))
            return 0;
        if (creationRecoveryRequired)
            return batchNotice(owner, "Helper creation is blocked after an ownership recovery failure. Ask the host to inspect the saved roster.", 0);
        if (profile == null || count < 1 || count > remaining(owner))
            return batchNotice(owner, "Choose 1–" + remaining(owner) + " helpers. Saved and unloaded helpers count toward both fifty-helper limits.", 0);
        var world = owner.level(); var connection = owner.connection;
        var places = spawnPlaces(owner, null, count, 16);
        if (places.size() != count)
            return batchNotice(owner, "Not enough clear solid ground within 16 blocks for " + count + " helpers. Nothing was created; move to a larger open area.", 0);
        var names = new HashSet<String>();
        for (var agent : data.agents.values()) if (agent.owner.equals(owner.getStringUUID())) names.add(agent.name);
        var staged = new LinkedHashMap<IronGolem, Agent>();
        int next = 1;
        for (var place : places) {
            while (next <= LIMIT && names.contains("helper-" + next)) next++;
            if (next > LIMIT) return batchNotice(owner, "Your helper names or slots changed. Reopen AI Helpers; nothing was created.", 0);
            String name = "helper-" + next++; names.add(name);
            IronGolem golem = EntityTypes.IRON_GOLEM.create(world, EntitySpawnReason.COMMAND);
            if (golem == null) return batchNotice(owner, "Could not prepare the complete helper batch. Nothing was created.", 0);
            golem.setPos(place); prepare(golem);
            if (data.agents.containsKey(golem.getStringUUID()) || staged.keySet().stream().anyMatch(g -> g.getUUID().equals(golem.getUUID())))
                return batchNotice(owner, "A helper identity could not be reserved. Nothing was created.", 0);
            staged.put(golem, new Agent(owner.getStringUUID(), name, Mode.FOLLOW, profile,
                world.dimension().identifier().toString(), place.x, place.y, place.z));
        }
        // The menu/session guards are repeated at the actual commit boundary.
        if (!AgentMenu.allowed(owner) || owner.connection != connection || owner.level() != world || count > remaining(owner)) return 0;
        for (var entry : staged.entrySet()) data.agents.put(entry.getKey().getStringUUID(), entry.getValue());
        try { save(); }
        catch (RuntimeException failure) {
            for (var golem : staged.keySet()) data.agents.remove(golem.getStringUUID());
            // save() writes atomically only after its .previous copy succeeds;
            // no native entity has entered the world on this failure path.
            return batchNotice(owner, "Could not save helper ownership. Nothing spawned. Check server storage before retrying.", 0);
        }
        try {
            for (var entry : staged.entrySet()) {
                var golem = entry.getKey(); tags(golem, entry.getValue()); HelperPowerDefaults.fillNewSpawn(golem);
                if (!world.addFreshEntity(golem)) throw new IllegalStateException("Native helper insertion was refused");
                loaded.put(golem.getUUID(), golem);
            }
        } catch (RuntimeException failure) {
            // Only this batch's brand-new identities can be removed here.
            // A normal discard is not death/dismissal and produces no drops.
            for (var golem : staged.keySet()) {
                loaded.remove(golem.getUUID(), golem); AgentAvatars.remove(golem); golem.discard();
                data.agents.remove(golem.getStringUUID());
            }
            // The first save already retained the original .previous file.
            // Do not replace that recovery copy with the failed staged batch.
            try { CommunityServer.atomicJson(file, data); }
            catch (java.io.IOException | RuntimeException rollbackFailure) {
                // Fail closed: stop this call instead of returning success or
                // silently retrying a partially recovered ownership save.
                creationRecoveryRequired = true;
                rollbackFailure.addSuppressed(failure);
                throw new IllegalStateException("Helper batch ownership rollback failed; inspect its saved roster before creating more helpers", rollbackFailure);
            }
            return batchNotice(owner, "The complete batch could not spawn. Its new helpers were removed; your existing helpers were kept.", 0);
        }
        return batchNotice(owner, count + " helpers appeared beside you • " + profile.label() + ", following. Your total: " + count(owner) + "/" + LIMIT + ".", count);
    }
    private static int batchNotice(ServerPlayer owner, String text, int result) {
        owner.sendSystemMessage(Component.literal(text)); owner.sendOverlayMessage(Component.literal(text));
        return result;
    }
    int mode(ServerPlayer owner, String name, Mode mode) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can change helper movement.");
        if (!rosterWritable(owner)) return 0;
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
        if (!rosterWritable(owner)) return 0;
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
    /** Explicit menu action resumes the same loaded helper without replacing or healing it. */
    boolean enableCombos(ServerPlayer owner, String name) {
        if (!AgentMenu.allowed(owner)) return false;
        if (!rosterWritable(owner)) return false;
        var entry = owned(owner, name);
        if (entry == null) return false;
        var golem = loaded.get(UUID.fromString(entry.getKey()));
        if (golem == null || !golem.isAlive() || golem.isRemoved() || golem.level() != owner.level()
            || owner.level().getEntity(golem.getUUID()) != golem) return false;
        var agent = entry.getValue().mode(Mode.FOLLOW, golem).profile(Profile.ULTIMATE_FINALS);
        data.agents.put(entry.getKey(), agent); save(); tags(golem, agent); halt(golem);
        validatePlayerTargets();
        return true;
    }
    /** Apply only the exact native helpers reviewed by the separate approved plan queue. */
    int applyReviewedSquadPlan(ServerPlayer owner, Map<UUID, Agent> expectedRecords,
        Map<UUID, IronGolem> expectedEntities, Mode mode, Profile profile, Formation shape) {
        if (owner == null || !server.isSameThread() || owner.level().getServer() != server || !AgentMenu.allowed(owner)
            || creationRecoveryRequired || expectedRecords == null || expectedEntities == null
            || expectedRecords.isEmpty() || expectedRecords.size() > LIMIT
            || !expectedRecords.keySet().equals(expectedEntities.keySet()) || mode == null || shape == null
            || (profile != Profile.REGULAR && profile != Profile.ULTIMATE_FINALS)) return 0;
        var changed = new LinkedHashMap<UUID, Agent>();
        for (var entry : expectedRecords.entrySet()) {
            UUID id = entry.getKey(); var before = entry.getValue(); var helper = expectedEntities.get(id);
            if (id == null || before == null || helper == null || data.agents.get(id.toString()) != before
                || !before.owner.equals(owner.getStringUUID()) || loaded.get(id) != helper
                || !id.equals(helper.getUUID()) || owner.level().getEntity(id) != helper
                || helper.level() != owner.level() || !isAgent(helper) || !helper.isAlive() || helper.isRemoved()) return 0;
            changed.put(id, before.mode(mode, helper).profile(profile));
        }
        changed.forEach((id, record) -> data.agents.put(id.toString(), record));
        try { save(); }
        catch (RuntimeException failure) {
            expectedRecords.forEach((id, record) -> data.agents.put(id.toString(), record));
            return 0;
        }
        changed.forEach((id, record) -> { var helper = expectedEntities.get(id); tags(helper, record); halt(helper); });
        GameModes.state(owner).putInt("helper_formation", shape.ordinal());
        PlayerPersistence.save(owner);
        formationPlans.remove(owner.getUUID());
        validatePlayerTargets();
        return changed.size();
    }
    int squad(ServerPlayer owner, Mode mode) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can control squads.");
        if (!rosterWritable(owner)) return 0;
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
    int squadProfile(ServerPlayer owner, Profile profile) {
        if (!AgentMenu.allowed(owner) || profile == null || !profile.combat) return 0;
        if (!rosterWritable(owner)) return 0;
        int changed = 0;
        for (var entry : data.agents.entrySet()) {
            var agent = entry.getValue();
            if (!agent.owner().equals(owner.getStringUUID()) || agent.profile() == profile) continue;
            agent = agent.profile(profile); entry.setValue(agent); changed++;
            var golem = loaded.get(UUID.fromString(entry.getKey()));
            if (golem != null && golem.isAlive() && !golem.isRemoved()) { tags(golem, agent); halt(golem); }
        }
        if (changed > 0) save();
        validatePlayerTargets();
        return reply(owner, "Squad profile: " + profile.label() + "; " + changed + " changed. Movement stays unchanged. Choose Follow to resume paused helpers. Player combat still requires both approvals.");
    }
    int dismiss(ServerPlayer owner, String name) {
        if (!operator(owner.createCommandSourceStack())) return reply(owner, "Only OP4 owners can dismiss helpers.");
        if (!rosterWritable(owner)) return 0;
        var entry = owned(owner, name);
        if (entry == null) return reply(owner, "No helper named " + name + " belongs to you.");
        // TreeMap entries are live nodes: deleting a node with two children
        // replaces its key with its successor. Capture identity before removal.
        String key=entry.getKey();UUID id=UUID.fromString(key);
        data.agents.remove(key); save();
        IronGolem golem = loaded.remove(id); lastAttack.remove(id); nextLeap.remove(id); aerial.remove(id); combatFeedback.remove(id);
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
            + "; anchor: " + Math.round(agent.x) + ", " + Math.round(agent.y) + ", " + Math.round(agent.z) + "\n" + HelperCombatStatus.summary(owner, entry.getKey(), agent));
    }
    static String location(IronGolem golem, Agent agent) {
        boolean live = golem != null && golem.isAlive() && !golem.isRemoved();
        return live ? golem.level().dimension().identifier() + " at " + golem.getBlockX() + ", " + golem.getBlockY() + ", " + golem.getBlockZ()
            : "unloaded; saved anchor " + agent.dimension + " at " + Math.round(agent.x) + ", " + Math.round(agent.y) + ", " + Math.round(agent.z);
    }
    String brief(ServerPlayer owner,String id,Agent agent){
        var golem=loaded.get(UUID.fromString(id));var pause=pauseReason(owner,golem,agent);
        if(pause!=null)return "Paused: "+pause;
        if(!agent.profile().combat)return "Passive profile • movement and approved tools only";
        if(golem.getTarget()!=null)return "Fighting: "+golem.getTarget().getName().getString();
        return "Ready • no eligible target nearby";
    }
    int recallSquad(ServerPlayer owner){
        if(!operator(owner.createCommandSourceStack())||!owner.isAlive()||owner.isSpectator())return 0;
        if(!rosterWritable(owner))return 0;
        var names=data.agents.values().stream().filter(a->a.owner().equals(owner.getStringUUID())).map(Agent::name).sorted().toList();
        int moved=0,skipped=0;
        for(var name:names){
            var entry=owned(owner,name);var before=entry==null?null:loaded.get(UUID.fromString(entry.getKey()));
            if(before==null||!before.isAlive()||before.isRemoved()){skipped++;continue;}
            recall(owner,name);var after=loaded.get(UUID.fromString(entry.getKey()));
            if(after!=null&&after.level()==owner.level()&&after.distanceToSqr(owner)<36)moved++;else skipped++;
        }
        return reply(owner,"Bring squad here: "+moved+" nearby; "+skipped+" unavailable or without clear landing space. No helpers were replaced.");
    }
    String pauseReason(ServerPlayer owner, IronGolem golem, Agent agent) {
        if (creationRecoveryRequired) return "helper ownership recovery required";
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
        // Plans only serve controls within this tick. Release obsolete owner
        // sessions/helpers even on ticks without the five-tick control pass.
        formationPlans.clear();
        validatePlayerTargets();
        for (var id : new ArrayList<>(aerial.keySet())) {
            var golem = loaded.get(id);
            if (golem == null) { aerial.remove(id); combatFeedback.remove(id); }
            else tickAerial(golem, server.getTickCount());
        }
        if (server.getTickCount() % 5 != 0) return;
        for (var entry : new ArrayList<>(loaded.entrySet())) {
            var golem = entry.getValue(); var agent = data.agents.get(entry.getKey().toString());
            if (golem.isRemoved() || !golem.isAlive()) { aerial.remove(entry.getKey()); loaded.remove(entry.getKey()); combatFeedback.remove(entry.getKey()); continue; }
            if (agent == null) { aerial.remove(entry.getKey()); loaded.remove(entry.getKey()); combatFeedback.remove(entry.getKey()); golem.discard(); continue; }
            control(golem, agent, server.getTickCount());
        }
    }
    void control(IronGolem golem, Agent agent, int ticks) {
        var owner = server.getPlayerList().getPlayer(UUID.fromString(agent.owner));
        var order = validPlayerTarget(owner);
        var chosenEntity = AgentEntityTargets.current(owner);
        golem.setLastHurtByMob(null); golem.setPersistentAngerTarget(null); golem.setPersistentAngerEndTime(0);
        String pause = pauseReason(owner, golem, agent);
        if (pause != null) { halt(golem); feedback(golem, "Paused", pause, ticks); return; }
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
        LivingEntity target;
        if (order != null && playerCombatProfile(agent.profile)) target = playerPursuitReady(golem, agent, owner, order.target) ? order.target : null;
        else if (chosenEntity != null && playerCombatProfile(agent.profile))
            target = approvedEntityTarget(golem, chosenEntity) ? chosenEntity : null;
        else if (!agent.profile.combat) target = null;
        else {
            var candidates = world.getEntitiesOfClass(Mob.class, new AABB(center, center).inflate(sensing), mob -> hostile(mob)
                && mob.position().distanceToSqr(center) <= sensing * sensing && mob.distanceToSqr(golem) <= 24 * 24 && golem.getSensing().hasLineOfSight(mob));
            var focus = agent.profile != Profile.PRIMITIVE && candidates.size() > 1 ? new FocusLookup() : null;
            target = candidates.stream().min(Comparator.comparing((Mob mob) -> agent.profile == Profile.PRIMITIVE || agent.mode != Mode.FOLLOW || mob.getTarget() != owner)
                .thenComparingInt(mob -> focus != null ? sharedFocusRank(golem, agent, owner, mob, focus) : 0)
                .thenComparingDouble(mob -> mob.distanceToSqr(agent.profile == Profile.ULTIMATE_FINALS ? owner : golem))
                .thenComparing(Mob::getUUID)).orElse(null);
            // Acquisition stays local to the owner, but native knockback and
            // pursuit must not erase an already eligible fight at that boundary.
            var current = golem.getTarget();
            if (agent.profile == Profile.ULTIMATE_FINALS && current != null
                && (target == null || !(target instanceof Mob mob && mob.getTarget() == owner))
                && tacticTarget(golem, agent, owner, current) && golem.getSensing().hasLineOfSight(current)) target = current;
        }
        if (target instanceof ServerPlayer player) APPROVED_TARGETS.put(golem, player);
        else APPROVED_TARGETS.remove(golem);
        golem.setTarget(target);
        if (target != null) {
            golem.lookAt(target, 30, 30);
            if(agent.profile==Profile.ULTIMATE_FINALS) {
                // Spears have a real minimum reach. Use a mace once the target
                // closes inside that reach instead of charging harmlessly at point blank.
                if(AgentWeapons.spearTooClose(golem,target)) {
                    AgentWeapons.mace(golem,target);
                    // A chasing mob cannot be expected to reopen the spear's
                    // launch window. Use a checked short mace arc at close range.
                    if (startAerial(golem, agent, owner, target, ticks)) return;
                    var attempt = comboFeedback(golem.getUUID());
                    feedback(golem, "Ground mace", "Target is inside the spear's minimum reach. "
                        + (attempt == null ? "" : attempt.detail()), ticks);
                    golem.getNavigation().moveTo(target,1.1);
                    strike(golem,target,ticks);return;
                }
                AgentWeapons.spear(golem,target);
                var kinetic=golem.getMainHandItem().get(net.minecraft.core.component.DataComponents.KINETIC_WEAPON);
                if(kinetic!=null && golem.getTicksUsingItem()<kinetic.delayTicks() && golem.distanceToSqr(target)>=16) {
                    // Wind up before rushing into the spear's short-range dead zone.
                    feedback(golem, "Charging spear", "Winding up before the approach.", ticks);
                    golem.getNavigation().stop();return;
                }
                if(kinetic!=null && golem.getTicksUsingItem()>=kinetic.delayTicks() && startAerial(golem,agent,owner,target,ticks))return;
            }
            Vec3 flank = agent.profile == Profile.ULTIMATE_FINALS ? flankPoint(golem, agent, owner, target) : null;
            Vec3 intercept = agent.profile == Profile.ULTIMATE_FINALS ? intercept(golem, agent, owner, target) : null;
            if (flank == null && intercept != null) golem.getNavigation().moveTo(intercept.x, intercept.y, intercept.z, 1.1);
            else if (flank == null) golem.getNavigation().moveTo(target, 1.1);
            else golem.getNavigation().moveTo(flank.x, flank.y, flank.z, 1.1);
            if(agent.profile!=Profile.ULTIMATE_FINALS) {
                AgentWeapons.sword(golem, target);
                strike(golem,target,ticks);
            }
        } else {
            var destination=agent.mode==Mode.FOLLOW?formationPoint(golem,agent,owner):center;
            double tolerance=agent.mode==Mode.FOLLOW?.16:4;
            AgentWeapons.stop(golem);
            feedback(golem, "Waiting for hostile", "No eligible target in the movement area.", ticks);
            if (destination == null) {
                golem.getNavigation().stop(); golem.stopInPlace();
                feedback(golem, "Formation slot blocked", "No distinct supported loaded slot is available. Waiting without teleporting or crowding the owner.", ticks);
                return;
            }
            if(golem.position().distanceToSqr(destination)>tolerance) {
                // Native navigation may use a partial route through a passage;
                // never replace a blocked destination with the owner's feet.
                boolean route = loadedRoom(world, new AABB(golem.position(), destination).inflate(1))
                    && golem.getNavigation().moveTo(destination.x,destination.y,destination.z,agent.mode==Mode.FOLLOW?0:1,1);
                if (!route) golem.getNavigation().stop();
                feedback(golem, route ? agent.mode == Mode.FOLLOW ? "Following" : "Returning to guard area" : "Walking route blocked",
                    route ? "Walking by native navigation; no hostile target is selected."
                        : "No walking path is available. Move to reachable ground or explicitly use Bring here.", ticks);
            }
            else {golem.getNavigation().stop();golem.stopInPlace();}
        }
    }

    /** Side/behind coordinates remain deterministic for the current named roster. */
    static Vec3 formationOffset(Formation shape, int index, int count) {
        if (shape == null || count < 1 || count > LIMIT || index < 0 || index >= count)
            throw new IllegalArgumentException("Invalid formation slot");
        double side, behind;
        if (shape == Formation.WEDGE) {
            int row = index / 2; // Integer pairs have equal depth and mirrored sides.
            side = (index % 2 == 0 ? -1 : 1) * (2 + row * 1.25);
            behind = 3 + row * 1.25;
        } else if (shape == Formation.RING) {
            double radius = Math.max(3, count * 2d / (2 * Math.PI));
            double angle = 2 * Math.PI * index / count;
            side = Math.cos(angle) * radius; behind = Math.sin(angle) * radius;
        } else {
            int width = Math.min(7, count), row = index / width;
            int columns = Math.min(width, count - row * width);
            side = (index % width - (columns - 1) / 2d) * 2;
            behind = 2.25 + row * 2;
        }
        return new Vec3(side, 0, behind);
    }
    Vec3 formationPoint(IronGolem self, Agent agent, ServerPlayer owner) {
        if (owner == null || !AgentMenu.allowed(owner) || agent == null || agent.mode != Mode.FOLLOW
            || !agent.owner.equals(owner.getStringUUID()) || data.agents.get(self.getStringUUID()) != agent
            || loaded.get(self.getUUID()) != self || owner.level().getEntity(self.getUUID()) != self
            || pauseReason(owner, self, agent) != null) return null;
        var peers = loaded.entrySet().stream().filter(entry -> {
            var record = data.agents.get(entry.getKey().toString()); var helper = entry.getValue();
            return record != null && record.owner.equals(agent.owner) && record.mode == Mode.FOLLOW
                && owner.level().getEntity(entry.getKey()) == helper && pauseReason(owner, helper, record) == null;
        }).sorted(Comparator.<Map.Entry<UUID, IronGolem>, String>comparing(entry -> data.agents.get(entry.getKey().toString()).name)
            .thenComparing(Map.Entry::getKey)).map(Map.Entry::getValue).toList();
        var shape = formation(owner); var cached = formationPlans.get(owner.getUUID());
        if (cached == null || cached.owner != owner || cached.connection != owner.connection || cached.world != owner.level()
            || cached.tick != server.getTickCount() || !cached.center.equals(owner.position())
            || cached.yaw != owner.getYRot() || cached.shape != shape || !cached.peers.equals(peers)) {
            var positions = new HashMap<UUID, Vec3>(); var reserved = new ArrayList<AABB>();
            reserved.add(owner.getBoundingBox().inflate(.2, 0, .2));
            double yaw = Math.toRadians(owner.getYRot());
            for (int index = 0; index < peers.size(); index++) {
                var helper = peers.get(index); var record = data.agents.get(helper.getStringUUID());
                var offset = formationOffset(shape, index, peers.size());
                var nominal = owner.position().add(Math.cos(yaw) * offset.x + Math.sin(yaw) * offset.z, 0,
                    Math.sin(yaw) * offset.x - Math.cos(yaw) * offset.z);
                var point = supportedFormationSlot(helper, record, owner, nominal, reserved);
                if (point != null) {
                    positions.put(helper.getUUID(), point);
                    reserved.add(helper.getDimensions(helper.getPose()).makeBoundingBox(point).inflate(.3, 0, .3));
                }
            }
            cached = new FormationPlan(owner, owner.connection, owner.level(), owner.position(), owner.getYRot(),
                shape, server.getTickCount(), peers, Map.copyOf(positions));
            formationPlans.put(owner.getUUID(), cached);
        }
        var point = cached.points.get(self.getUUID());
        return point != null && landingClear(self, point) ? point : null;
    }
    private Vec3 supportedFormationSlot(IronGolem helper, Agent agent, ServerPlayer owner, Vec3 nominal, List<AABB> reserved) {
        // A hovering owner has no walking floor at its own Y. Search only the
        // helper's nearby supported levels, then let native navigation walk there.
        int floor = (int)Math.floor(helper.getY()), ownerFloor = (int)Math.floor(owner.getY());
        for (int radius = 0; radius <= 3; radius++)
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                for (int step = 0; step <= 9; step++) {
                    // Prefer the owner's supported level so ordinary stairs can
                    // reach a raised platform. A hovering owner's unsupported
                    // column falls through to the helper's nearby ground levels.
                    int dy = step == 0 ? ownerFloor - floor : step == 1 ? 0
                        : (step % 2 == 0 ? -step / 2 : (step - 1) / 2);
                    // Local alternatives use block centers so a player-width
                    // helper can use a one-block passage even when its ideal
                    // owner-relative slot lies on the passage's block edge.
                    double x = radius == 0 ? nominal.x : Math.floor(nominal.x + dx) + .5;
                    double z = radius == 0 ? nominal.z : Math.floor(nominal.z + dz) + .5;
                    var point = new Vec3(x, floor + dy, z);
                    if (!insideLeash(agent, owner, point) || !landingClear(helper, point)) continue;
                    var space = helper.getDimensions(helper.getPose()).makeBoundingBox(point).inflate(.3, 0, .3);
                    if (reserved.stream().noneMatch(space::intersects)) return point;
                }
            }
        return null; // A blocked member holds; fifty failures never share the owner's position.
    }

    boolean strike(IronGolem golem, LivingEntity target, int ticks) {
        if (!golem.isWithinMeleeAttackRange(target) || !golem.getSensing().hasLineOfSight(target)
            || ticks - lastAttack.getOrDefault(golem.getUUID(), -20) < 20) return false;
        lastAttack.put(golem.getUUID(), ticks);
        boolean hit = golem.doHurtTarget((ServerLevel)golem.level(), target);
        if (hit) golem.swingForAttack(net.minecraft.world.InteractionHand.MAIN_HAND);
        return hit;
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
        return (hostile(target) || approvedEntityTarget(golem, target)) && golem.distanceToSqr(target) <= 24 * 24;
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
    private String leapProblem(IronGolem golem, Agent agent, ServerPlayer owner, LivingEntity target, int ticks) {
        if (!tacticTarget(golem, agent, owner, target)) return "Target or owner is no longer eligible.";
        if (!golem.onGround() || golem.isInWater()) return "A leap needs dry, solid ground.";
        if (ticks < nextLeap.getOrDefault(golem.getUUID(), 0)) return "Aerial cooldown; continuing ground combat.";
        if (ticks - lastAttack.getOrDefault(golem.getUUID(), -20) < 20)
            return "Recovering from the last strike; continuing ground combat.";
        if (!golem.getSensing().hasLineOfSight(target)) return "Target is out of sight.";
        if (Math.abs(target.getY() - golem.getY()) > .25) return "Target is not on level ground; using ground pursuit.";
        if (golem.distanceToSqr(target) > 49)
            return "Approaching the seven-block launch range.";
        return null;
    }
    private record LeapPlan(Vec3 direction, Vec3 landing) {}
    /** Stay outside body collision but inside native melee reach after the descent. */
    double landingOffset(IronGolem golem, LivingEntity target) {
        return (golem.getBbWidth() + target.getBbWidth()) * .5 + .25;
    }
    private LeapPlan leapPlan(IronGolem golem, Agent agent, ServerPlayer owner, LivingEntity target) {
        Vec3 predicted = intercept(golem, agent, owner, target);
        if (predicted == null) predicted = target.position();
        Vec3 direction = predicted.subtract(golem.position()).multiply(1, 0, 1).normalize();
        double offset = landingOffset(golem, target);
        Vec3 landing = new Vec3(predicted.x - direction.x * offset, golem.getY(), predicted.z - direction.z * offset);
        return landingClear(golem, landing) && airClear(golem, agent, owner, golem.position(), landing, LEAP_HEIGHT)
            ? new LeapPlan(direction, landing) : null;
    }
    boolean startAerial(IronGolem golem, Agent agent, ServerPlayer owner, LivingEntity target, int ticks) {
        String problem = leapProblem(golem, agent, owner, target, ticks);
        if (problem != null) { feedback(golem, "Approaching target", problem, ticks); return false; }
        if (aerial.values().stream().anyMatch(a -> a.agent.owner.equals(agent.owner) && a.target == target)) {
            feedback(golem, "Waiting for squad", "A teammate is already using this target's aerial lane.", ticks); return false;
        }
        var peers = loaded.values().stream().filter(peer -> {
            var record = data.agents.get(peer.getStringUUID());
            return record != null && record.owner.equals(agent.owner) && leapProblem(peer, record, owner, target, ticks) == null
                && (peer == golem || readyOpener(peer, target))
                && (peer.getTarget() == null || peer.getTarget() == target);
        }).sorted(Comparator.comparingInt((IronGolem peer) -> nextLeap.getOrDefault(peer.getUUID(), 0))
            .thenComparing(peer -> data.agents.get(peer.getStringUUID()).name)).toList();
        LeapPlan selected = null;
        // Cheap readiness filters run
        // before corridor scans, and a blocked teammate cannot consume the lane.
        for (var peer : peers) {
            var plan = leapPlan(peer, data.agents.get(peer.getStringUUID()), owner, target);
            if (plan == null) continue;
            if (peer != golem) { feedback(golem, "Waiting for squad", "A ready teammate has the next aerial turn.", ticks); return false; }
            selected = plan; break;
        }
        if (selected == null) { feedback(golem, "Approaching target", "Aerial route or landing is blocked; using ground pursuit.", ticks); return false; }
        nextLeap.put(golem.getUUID(), ticks + LEAP_COOLDOWN);
        if (AgentWeapons.spearTooClose(golem, target)) AgentWeapons.mace(golem, target);
        else AgentWeapons.spear(golem,target);
        aerial.put(golem.getUUID(), new Aerial(agent, target, selected.landing, ticks));
        feedback(golem, "Launching", golem.getMainHandItem().is(net.minecraft.world.item.Items.MACE)
            ? "Close mace leap; checked landing reserved." : "Charged spear approach; checked landing reserved.", ticks);
        golem.getNavigation().stop(); golem.setDeltaMovement(selected.direction.x * AIR_SPEED, .62, selected.direction.z * AIR_SPEED);
        golem.setOnGround(false); golem.needsSync = true;
        return true;
    }
    private boolean readyOpener(IronGolem golem, LivingEntity target) {
        if (AgentWeapons.spearTooClose(golem, target)) return true;
        var kinetic = golem.getMainHandItem().get(net.minecraft.core.component.DataComponents.KINETIC_WEAPON);
        return golem.getMainHandItem().is(net.minecraft.world.item.Items.NETHERITE_SPEAR) && golem.isUsingItem()
            && kinetic != null && golem.getTicksUsingItem() >= kinetic.delayTicks();
    }
    void cancelAerial(IronGolem golem) {
        AgentWeapons.stop(golem);
        if (aerial.remove(golem.getUUID()) != null) {
            golem.getNavigation().stop();
            golem.setDeltaMovement(0, Math.min(0, golem.getDeltaMovement().y), 0); golem.needsSync = true;
            feedback(golem, "Recovering", "Aerial attempt ended; ground combat resumes after target checks.", server.getTickCount());
        }
    }
    private void trackLanding(IronGolem golem, Aerial move, ServerPlayer owner, int ticks) {
        if (ticks - move.lastPlan < 5) return;
        move.lastPlan = ticks;
        Vec3 predicted = intercept(golem, move.agent, owner, move.target);
        if (predicted == null) predicted = move.target.position();
        Vec3 direction = predicted.subtract(golem.position()).multiply(1, 0, 1).normalize();
        double offset = landingOffset(golem, move.target);
        Vec3 desired = new Vec3(predicted.x - direction.x * offset, move.landing.y, predicted.z - direction.z * offset);
        Vec3 shift = desired.subtract(move.landing).multiply(1, 0, 1);
        if (shift.lengthSqr() > 4) shift = shift.normalize().scale(2);
        Vec3 candidate = move.landing.add(shift);
        double clearance = Math.max(0, move.landing.y + LEAP_HEIGHT - golem.getY());
        if (insideLeash(move.agent, owner, candidate) && landingClear(golem, candidate)
            && airClear(golem, move.agent, owner, golem.position(), candidate, clearance)) {
            move.landing = candidate;
            move.tracking = "Landing follows the same target within a checked route.";
        } else move.tracking = "Target moved behind unsafe terrain; retaining the last checked landing.";
    }
    void tickAerial(IronGolem golem, int ticks) {
        var move = aerial.get(golem.getUUID()); if (move == null) return;
        var owner = server.getPlayerList().getPlayer(UUID.fromString(move.agent.owner));
        if (data.agents.get(golem.getStringUUID()) != move.agent || !tacticTarget(golem, move.agent, owner, move.target)
            || ticks - move.started > LEAP_TIMEOUT || !golem.getSensing().hasLineOfSight(move.target)) { cancelAerial(golem); return; }
        if (move.firstHit >= 0) {
            if(golem.onGround()) {
                if (AgentWeapons.spearTooClose(golem, move.target)) AgentWeapons.mace(golem, move.target);
                else AgentWeapons.spear(golem,move.target);
            }
            feedback(golem, "Follow-up", "Native weapon reach and the twenty-tick hit cadence still apply.", ticks);
            golem.getNavigation().moveTo(move.target, 1.1);
            if (golem.onGround() && ticks - move.firstHit >= 20) {
                if (golem.getMainHandItem().is(net.minecraft.world.item.Items.MACE)) strike(golem, move.target, ticks);
                // A distant spear follow-up uses native charging contacts, not a
                // forced ordinary melee hit through its minimum-range dead zone.
                cancelAerial(golem);
            }
            return;
        }
        if (golem.onGround() && ticks > move.started) {
            AgentWeapons.mace(golem,move.target);
            if (strike(golem, move.target, ticks)) move.firstHit = ticks;
            else cancelAerial(golem);
            return;
        }
        AgentWeapons.aim(golem,move.target);
        trackLanding(golem, move, owner, ticks);
        if(ticks-move.started>=4 && ticks-move.started<7) {
            AgentWeapons.glide(golem); feedback(golem, "Gliding", move.tracking, ticks);
        }
        if(ticks-move.started>=7) {
            AgentWeapons.mace(golem,move.target);
            feedback(golem, "Mace descent", move.tracking, ticks);
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
    private static final class FocusLookup {
        Set<LivingEntity> targets;
    }
    int sharedFocusRank(IronGolem self, Agent agent, ServerPlayer owner, Mob target, FocusLookup focus) {
        if (focus.targets == null) {
            // Local to this control call: later helpers must see targets selected by
            // earlier helpers in the same tick. No scan for zero/one candidates or
            // when owner-threat priority already decides the comparison.
            focus.targets = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var entry : loaded.entrySet()) {
                var peer = entry.getValue(); var record = data.agents.get(entry.getKey().toString());
                if (peer != self && record != null && record.owner.equals(agent.owner) && record.profile.combat
                    && pauseReason(owner, peer, record) == null) {
                    var peerTarget = peer.getTarget();
                    if (peerTarget != null) focus.targets.add(peerTarget);
                }
            }
        }
        return focus.targets.contains(target) ? 0 : 1;
    }
    Vec3 flankPoint(IronGolem self, Agent agent, ServerPlayer owner, LivingEntity target) {
        var order = target instanceof ServerPlayer ? validPlayerTarget(owner) : null;
        var peers = loaded.entrySet().stream().filter(entry -> {
            var record = data.agents.get(entry.getKey().toString());
            var peer = entry.getValue();
            return record != null && record.owner.equals(agent.owner) && record.profile == Profile.ULTIMATE_FINALS
                && pauseReason(owner, peer, record) == null && peer.getTarget() == target
                && (target instanceof ServerPlayer player ? order != null && order.target == player && playerCombatReady(peer, record, owner, player)
                    : tacticTarget(peer, record, owner, target));
        }).sorted(Comparator.comparing(entry -> data.agents.get(entry.getKey().toString()).name)).map(Map.Entry::getKey).toList();
        int slot = peers.indexOf(self.getUUID());
        if (slot < 0 || peers.size() < 2) return null;
        double angle = slot * (Math.PI * 2 / peers.size());
        double offset = landingOffset(self, target);
        Vec3 point = target.position().add(Math.cos(angle) * offset, 0, Math.sin(angle) * offset);
        if (!insideLeash(agent, owner, point)) return null;
        BlockPos feet = BlockPos.containing(point); ServerLevel world = owner.level();
        AABB room = self.getDimensions(self.getPose()).makeBoundingBox(point);
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
        HelperForms.register();
        AgentEntityTargets.initialize();
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
                helpers.loaded.remove(entity.getUUID()); helpers.lastAttack.remove(entity.getUUID()); helpers.nextLeap.remove(entity.getUUID()); helpers.aerial.remove(entity.getUUID()); helpers.combatFeedback.remove(entity.getUUID());
                if (!helpers.creationRecoveryRequired && helpers.data.agents.remove(entity.getStringUUID()) != null) helpers.save();
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

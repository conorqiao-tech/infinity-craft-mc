package dev.convergence;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.util.Formatting;

public final class GameModes {
    public enum Mode { SURVIVAL, CREATIVE, HARDCORE, MINIGAMES, ADVENTURE, HUB }
    static final Set<UUID> TRANSITIONS=new HashSet<>();
    static final Set<UUID> FIRST_VISITS=new HashSet<>();
    record Switch(Mode mode, String map, CommunityServer.Place origin, int finish) {}
    static final Map<UUID,Switch> PENDING=new HashMap<>();
    record OperatorTransfer(Mode previous,NbtCompound profile) {}
    static final Map<UUID,OperatorTransfer> OPERATOR_TRANSFERS=new HashMap<>();
    public static boolean operator(ServerPlayerEntity player) {return Memberships.operator(player);}
    /** Vanilla /tp and dimension commands still save and restore each world's inventory. */
    public static void beforeOperatorTeleport(ServerPlayerEntity p,ServerWorld destination) {
        if(!operator(p)||TRANSITIONS.contains(p.getUuid())||current(p)==of(destination)||OPERATOR_TRANSFERS.containsKey(p.getUuid()))return;
        var cursor=p.currentScreenHandler.getCursorStack();
        if(!cursor.isEmpty()){p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);p.getInventory().offerOrDrop(cursor);}
        p.closeHandledScreen();p.clearActiveItem();
        OPERATOR_TRANSFERS.put(p.getUuid(),new OperatorTransfer(current(p),capture(p)));
    }
    public static void afterOperatorTeleport(ServerPlayerEntity p,boolean success) {
        var transfer=OPERATOR_TRANSFERS.remove(p.getUuid());if(transfer==null||!success)return;
        var s=state(p);var mode=of(p.getEntityWorld());var profiles=s.getCompoundOrEmpty("profiles");
        var next=profiles.getCompoundOrEmpty(mode.name());profiles.put(transfer.previous().name(),transfer.profile());
        s.putString("active",mode.name());restore(p,next);profiles.remove(mode.name());s.put("profiles",profiles);
        if(mode==Mode.CREATIVE) CreativeGearPicker.onEnter(p);
        PENDING.remove(p.getUuid());ModeMaps.RUNS.remove(p.getUuid());
        var community=CommunityServer.get(p.getEntityWorld().getServer());community.pending.remove(p.getUuid());
        community.server.getPlayerManager().saveAllPlayerData();
    }
    public static NbtCompound state(ServerPlayerEntity p) { return ((ModePlayer)p).infinity$state(); }
    public static Mode current(ServerPlayerEntity p) {
        try { return Mode.valueOf(state(p).getString("active","SURVIVAL")); }
        catch(IllegalArgumentException e) { throw new IllegalStateException("Unknown saved Infinity mode for "+p.getUuid(),e); }
    }
    public static Mode of(World world) {
        String id=world.getRegistryKey().getValue().toString();
        for(Mode mode:Mode.values()) if(id.equals("convergence:"+mode.name().toLowerCase(Locale.ROOT))) return mode;
        return Mode.SURVIVAL;
    }
    static ServerWorld world(MinecraftServer server, Mode mode) {
        return mode==Mode.SURVIVAL?server.getOverworld():server.getWorld(RegistryKey.of(RegistryKeys.WORLD,Identifier.of("convergence",mode.name().toLowerCase(Locale.ROOT))));
    }
    public static boolean allowTeleport(ServerPlayerEntity p, ServerWorld destination) {
        return operator(p)||TRANSITIONS.contains(p.getUuid()) || current(p)==of(destination);
    }
    static GameMode gameMode(ServerPlayerEntity p) {
        return switch(current(p)) {
            case CREATIVE -> GameMode.CREATIVE;
            case MINIGAMES,ADVENTURE,HUB -> GameMode.ADVENTURE;
            case HARDCORE -> state(p).getBoolean("eliminated",false)&&!Memberships.gameplayBypass(p)?GameMode.SPECTATOR:GameMode.SURVIVAL;
            default -> GameMode.SURVIVAL;
        };
    }
    static CommunityServer.Place defaultPlace(MinecraftServer server, Mode mode) {
        var w=world(server,mode);
        if(mode==Mode.HUB) return LobbyServer.place(server,"main");
        if(mode==Mode.MINIGAMES || mode==Mode.ADVENTURE) return mapPlace(server,mode,ModeMaps.defaultMap(mode));
        if(mode==Mode.SURVIVAL) {
            var c=CommunityServer.get(server); if(c.data.spawn!=null && c.world(c.data.spawn)!=null && of(c.world(c.data.spawn))==Mode.SURVIVAL) return c.data.spawn;
            var s=server.getSpawnPoint(); var pos=s.getPos();
            if(s.getDimension().equals(World.OVERWORLD)) return new CommunityServer.Place("minecraft:overworld",pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
        }
        for(int r=0;r<=64;r+=4) for(int x=-r;x<=r;x+=4) for(int z=-r;z<=r;z+=4) {
            w.getChunk(x>>4,z>>4); int y=w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,x,z);
            var p=new CommunityServer.Place(w.getRegistryKey().getValue().toString(),x+.5,y,z+.5,0,0);
            if(CommunityServer.safe(w,p)) return p;
        }
        throw new IllegalStateException("No safe spawn in "+mode+". Ask the host to choose a dry spawn.");
    }
    static CommunityServer.Place mapPlace(MinecraftServer server, Mode mode, String id) {
        var spec=ModeMaps.MAPS.get(id);
        if(spec==null || spec.mode()!=mode || !ModeMaps.available(id))id=ModeMaps.defaultMap(mode);
        if(id==null)throw new IllegalStateException("No verified built-in course is available in "+mode+". Check the map preservation warnings in the server log.");
        var world=world(server,mode);var pos=ModeMaps.start(id);
        return new CommunityServer.Place(world.getRegistryKey().getValue().toString(),pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
    }
    public static TeleportTarget respawnTarget(ServerPlayerEntity p, TeleportTarget vanilla, TeleportTarget.PostDimensionTransition callback) {
        var mode=current(p);
        if(mode==Mode.SURVIVAL || (of(vanilla.world())==mode && mode!=Mode.MINIGAMES && mode!=Mode.ADVENTURE && mode!=Mode.HUB)) return vanilla;
        var place=mode==Mode.MINIGAMES || mode==Mode.ADVENTURE
            ? mapPlace(p.getEntityWorld().getServer(),mode,ModeMaps.selectedMap(p,mode))
            : defaultPlace(p.getEntityWorld().getServer(),mode);
        return new TeleportTarget(world(p.getEntityWorld().getServer(),mode),new Vec3d(place.x(),place.y(),place.z()),Vec3d.ZERO,place.yaw(),place.pitch(),callback);
    }
    static NbtCompound capture(ServerPlayerEntity p) {
        AchievementRewards.beforeModeCapture(p);
        var view=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());
        p.getInventory().writeData(view.getListAppender("inventory",StackWithSlot.CODEC));
        for(var slot:EquipmentSlot.values()) if(slot!=EquipmentSlot.MAINHAND) view.put("equipment_"+slot.getName(),ItemStack.OPTIONAL_CODEC,p.getEquippedStack(slot));
        p.getEnderChestInventory().writeData(view.getListAppender("ender",StackWithSlot.CODEC));
        p.getHungerManager().writeData(view);
        view.putInt("selected",p.getInventory().getSelectedSlot());
        view.putFloat("health",p.getHealth()); view.putFloat("absorption",p.getAbsorptionAmount());
        view.putInt("level",p.experienceLevel); view.putInt("total",p.totalExperience); view.putFloat("xp",p.experienceProgress);
        view.put("effects",StatusEffectInstance.CODEC.listOf(),new ArrayList<>(p.getStatusEffects()));
        view.putNullable("respawn",ServerPlayerEntity.Respawn.CODEC,p.getRespawn());
        view.putString("place",CommunityServer.GSON.toJson(CommunityServer.Place.of(p)));
        return view.getNbt();
    }
    static void restore(ServerPlayerEntity p, NbtCompound profile) {
        AchievementRewards.beforeModeCapture(p);
        var v=NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),profile);
        p.getInventory().clear(); p.getEnderChestInventory().clear(); p.clearStatusEffects();
        p.getInventory().readData(v.getTypedListView("inventory",StackWithSlot.CODEC));
        for(var slot:EquipmentSlot.values()) if(slot!=EquipmentSlot.MAINHAND) p.equipStack(slot,v.read("equipment_"+slot.getName(),ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        p.getEnderChestInventory().readData(v.getTypedListView("ender",StackWithSlot.CODEC));
        if(profile.isEmpty()) { p.getHungerManager().setFoodLevel(20);p.getHungerManager().setSaturationLevel(5); }
        else p.getHungerManager().readData(v);
        p.getInventory().setSelectedSlot(v.getInt("selected",0));
        for(var effect:v.read("effects",StatusEffectInstance.CODEC.listOf()).orElse(List.of())) p.addStatusEffect(effect);
        p.setHealth(Math.max(1,Math.min(p.getMaxHealth(),v.getFloat("health",20))));p.setAbsorptionAmount(v.getFloat("absorption",0));
        p.experienceLevel=v.getInt("level",0);p.totalExperience=v.getInt("total",0);p.experienceProgress=v.getFloat("xp",0);
        p.setExperienceLevel(p.experienceLevel);p.setExperiencePoints((int)(p.experienceProgress*p.getNextLevelExperience()));
        p.setSpawnPoint(v.read("respawn",ServerPlayerEntity.Respawn.CODEC).orElse(null),false);
        p.extinguish();p.fallDistance=0;p.setVelocity(Vec3d.ZERO);p.setAir(p.getMaxAir());p.setFrozenTicks(0);
        p.playerScreenHandler.syncState();p.markHealthDirty();
    }
    static int request(ServerPlayerEntity p, Mode mode, String map) {
        if(CourseSelector.supports(mode) && (ModeMaps.defaultMap(mode)==null || map!=null && !ModeMaps.available(map)))
            return CommunityServer.say(p,"This course is unavailable because its map region could not be verified. Ask the host to check the server log.");
        if(!Memberships.gameplayBypass(p)&&mode==Mode.HARDCORE && state(p).getBoolean("eliminated",false)) return CommunityServer.say(p,"Your Hardcore life has ended. Choose another mode with /play.");
        if(!p.getLeftShoulderNbt().isEmpty() || !p.getRightShoulderNbt().isEmpty()) return CommunityServer.say(p,"Let your shoulder pets dismount before changing modes.");
        if(!p.isAlive() || p.hasVehicle() || p.isSleeping()) return CommunityServer.say(p,"Respawn, wake up, and leave your vehicle first.");
        var c=CommunityServer.get(p.getEntityWorld().getServer());
        if(Memberships.gameplayBypass(p)) {
            if(TRANSITIONS.contains(p.getUuid())||OPERATOR_TRANSFERS.containsKey(p.getUuid())) return CommunityServer.say(p,"Finish your current mode transition first.");
            PENDING.remove(p.getUuid());c.pending.remove(p.getUuid());
            if(current(p)==mode){if(mode==Mode.HUB)return LobbyServer.arrive(p,map==null?"main":map);if(map!=null)ModeMaps.begin(p,map);}
            else switchNow(p,mode,map);
            return CommunityServer.say(p,"OP mode access: "+mode+". /gamemode can change your abilities in any world.");
        }
        boolean leavingEliminatedHardcore=current(p)==Mode.HARDCORE && p.isSpectator()
            && state(p).getBoolean("eliminated",false) && mode!=Mode.HARDCORE;
        if(!leavingEliminatedHardcore && c.combat.getOrDefault(p.getUuid(),0)>c.server.getTicks())
            return CommunityServer.say(p,"Wait 10 seconds after damage before changing modes.");
        if(PENDING.containsKey(p.getUuid())) return CommunityServer.say(p,"A mode change is already counting down.");
        if(current(p)==mode) {
            if(mode==Mode.HUB) return LobbyServer.arrive(p,map==null?"main":map);
            if(map!=null) ModeMaps.begin(p,map);
            return CommunityServer.say(p,"Current mode: "+mode+". /play lists all modes.");
        }
        c.pending.remove(p.getUuid());
        PENDING.put(p.getUuid(),new Switch(mode,map,CommunityServer.Place.of(p),c.server.getTicks()+60));
        return CommunityServer.say(p,"Joining "+mode+" in 3 seconds. Stay still; damage cancels it. Each mode has its own inventory.");
    }
    static void switchNow(ServerPlayerEntity p, Mode mode, String map) {
        var server=p.getEntityWorld().getServer();var current=current(p); if(current==mode)return;
        if(CourseSelector.supports(mode) && (ModeMaps.defaultMap(mode)==null || map!=null && !ModeMaps.available(map))) {
            CommunityServer.say(p,"This course is unavailable because its map region could not be verified. Ask the host to check the server log.");return;
        }
        if(!p.getLeftShoulderNbt().isEmpty() || !p.getRightShoulderNbt().isEmpty() || p.hasVehicle()) { CommunityServer.say(p,"Dismount and let shoulder pets down first.");return; }
        var s=state(p); if(!Memberships.gameplayBypass(p)&&mode==Mode.HARDCORE && s.getBoolean("eliminated",false)) return;
        var profiles=s.getCompoundOrEmpty("profiles"); var next=profiles.getCompoundOrEmpty(mode.name());
        CommunityServer.Place place=null;
        if(mode!=Mode.MINIGAMES && mode!=Mode.ADVENTURE && mode!=Mode.HUB && next.contains("place")) place=CommunityServer.GSON.fromJson(next.getString("place", ""),CommunityServer.Place.class);
        var c=CommunityServer.get(server);
        if(mode==Mode.MINIGAMES || mode==Mode.ADVENTURE) place=mapPlace(server,mode,map==null?ModeMaps.selectedMap(p,mode):map);
        if(place==null || c.world(place)==null || of(c.world(place))!=mode || !CommunityServer.safe(c.world(place),place)) place=defaultPlace(server,mode);
        // Return the carried cursor stack in the old world before capturing its profile.
        // Vanilla can leave that stack on playerScreenHandler when no container is open.
        var cursor=p.currentScreenHandler.getCursorStack();
        if(!cursor.isEmpty()) {p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);p.getInventory().offerOrDrop(cursor);}
        p.closeHandledScreen();p.clearActiveItem();
        if(current!=Mode.HUB||operator(p)) profiles.put(current.name(),capture(p));
        else profiles.remove(Mode.HUB.name());
        s.put("profiles",profiles);
        TRANSITIONS.add(p.getUuid());
        try {
            if(!p.teleport(c.world(place),place.x(),place.y(),place.z(),Set.of(),place.yaw(),place.pitch(),true)) { CommunityServer.say(p,"Mode change could not teleport. Your inventory is unchanged.");return; }
            s.putString("active",mode.name());restore(p,mode==Mode.HUB&&!operator(p)?new NbtCompound():next);p.changeGameMode(gameMode(p));
            if(mode==Mode.CREATIVE) CreativeGearPicker.onEnter(p);
            profiles.remove(mode.name());c.pending.remove(p.getUuid());c.requests.remove(p.getUuid());c.requests.values().removeIf(r->r.sender().equals(p.getUuid()));
            ModeMaps.RUNS.remove(p.getUuid());
            if(mode==Mode.MINIGAMES || mode==Mode.ADVENTURE) ModeMaps.begin(p,map==null?ModeMaps.selectedMap(p,mode):map);
            if(mode==Mode.HUB) LobbyServer.arrive(p,map==null?"main":map);
            server.getPlayerManager().saveAllPlayerData();
            CommunityServer.say(p,"Now playing "+mode+". Your "+current+" inventory is saved.");
        } finally { TRANSITIONS.remove(p.getUuid()); }
    }
    static void death(ServerPlayerEntity p) {
        PENDING.remove(p.getUuid());ModeMaps.RUNS.remove(p.getUuid());
        if(!Memberships.gameplayBypass(p)&&current(p)==Mode.HARDCORE) { state(p).putBoolean("eliminated",true);CommunityServer.say(p,"Your Hardcore life has ended. Respawn to spectate, then /play survival to continue elsewhere."); }
    }
    static boolean routeFirstVisit(ServerPlayerEntity p) {
        if(operator(p)){FIRST_VISITS.remove(p.getUuid());return false;}
        if(!FIRST_VISITS.contains(p.getUuid()) || !p.networkHandler.canInteractWithGame()) return false;
        FIRST_VISITS.remove(p.getUuid());
        switchNow(p,Mode.HUB,"main");
        return true;
    }
    static int menu(ServerPlayerEntity p) {
        CommunityServer.say(p,"Choose a world: /play survival, creative, hardcore, minigames, adventure. /play minigames and /play adventure open course menus. /ranks shows free achievement ranks.");
        for(var mode:Mode.values()) if(mode!=Mode.HUB) p.sendMessage(Text.literal("[ "+mode+" ]").formatted(Formatting.AQUA).styled(style->style.withClickEvent(new ClickEvent.RunCommand("/play "+mode.name().toLowerCase(Locale.ROOT)))),false);
        return 1;
    }
    static String guideText(ServerPlayerEntity p) {
        return switch(current(p)) {
            case HUB -> {
                var closest=LobbyServer.LOBBIES.get("main");double distance=Double.MAX_VALUE;
                for(var lobby:LobbyServer.LOBBIES.values()) {
                    double dx=p.getX()-lobby.center().getX()-.5,dz=p.getZ()-lobby.center().getZ()-.5;
                    double next=dx*dx+dz*dz;
                    if(next<distance){closest=lobby;distance=next;}
                }
                if(distance>14*14 || closest.id().equals("main"))
                    yield "Main Hub: follow the lit paths or use /lobbies for five halls; /lobby <mode> visits a hall, /play <mode> enters a world.";
                yield switch(closest.id()) {
                    case "minigames" -> "Minigames Lobby: right-click the entry sign or use /play minigames to choose a course. /best shows your times; /hub returns.";
                    case "adventure" -> "Adventure Lobby: right-click the entry sign or use /play adventure to choose a map. /hub returns.";
                    default -> closest.label()+": /play "+closest.id()+" enters that world. /lobbies lists other halls; /hub returns to Main Hub.";
                };
            }
            case SURVIVAL -> "Survival: craft and explore; /sethome, /home, /backpack, /trades and /rewards are available. /hub returns to the lobby hub.";
            case CREATIVE -> "Creative: select Infinity Menu to pick weapons, tools, blocks, powers, or AI helpers. A starter sword is added when there is hotbar room. The lobby's INFINITY MENU sign can reopen the menu; /hub returns.";
            case HARDCORE -> Memberships.gameplayBypass(p)
                ? "Hardcore: Admin/OP gameplay access lets you re-enter and keep playing. Every mode keeps its own inventory. /hub returns to the lobbies."
                : state(p).getBoolean("eliminated",false)
                ? "Hardcore life ended: spectate here, or /play survival to continue in another world. /hub visits the lobbies."
                : "Hardcore: one life in this world. /play survival or /hub leaves while keeping other world progress separate.";
            case MINIGAMES -> "Minigames: tap the course-start sign or use /play minigames to choose a course; /retry <map> replays. /best shows your records; /leaderboard <map> shows top times. /hub leaves.";
            case ADVENTURE -> "Adventure maps: tap the course-start sign or use /play adventure to choose a map; /retry <map> restarts your route. /hub returns to the lobbies.";
        };
    }
    static int guide(ServerPlayerEntity p) { return CommunityServer.say(p,guideText(p)); }
    static void register() {
        MinigameRecords.register();
        CourseSelector.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server->{ModeMaps.build(server);LobbyServer.build(server);CourseSelector.installSigns(server);LobbyServer.installMenuSigns(server);System.out.println("[Infinity] Community and crossplay ready.");});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{PENDING.clear();TRANSITIONS.clear();FIRST_VISITS.clear();OPERATOR_TRANSFERS.clear();ModeMaps.RUNS.clear();});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            var p=handler.player;
            if(!operator(p)&&current(p)!=Mode.SURVIVAL) p.changeGameMode(gameMode(p));
            if(of(p.getEntityWorld())!=current(p)) throw new IllegalStateException("Player mode and dimension disagree: "+p.getUuid()+". Restore a matching world/player backup.");
            if(current(p)==Mode.MINIGAMES || current(p)==Mode.ADVENTURE) ModeMaps.begin(p,ModeMaps.selectedMap(p,current(p)));
            if(!operator(p)&&current(p)==Mode.HUB) restore(p,new NbtCompound());
            if(current(p)==Mode.CREATIVE) CreativeGearPicker.onEnter(p);
            var savedPlayer = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("playerdata").resolve(p.getUuidAsString()+".dat");
            // The headless TestServer's mock players are spawned for combat tests;
            // their first-login routing is exercised explicitly in LobbyGameTests.
            if(!(server instanceof net.minecraft.test.TestServer) && current(p)==Mode.SURVIVAL && !java.nio.file.Files.exists(savedPlayer)) FIRST_VISITS.add(p.getUuid());
            CommunityServer.say(p,"Select the Infinity Menu recovery compass for gear, powers, games, and AI helpers. Tap an INFINITY MENU lobby sign if you need the menu again. /hub returns to Main Hub.");
            try {MinigameRecords.sync(p);}catch(IllegalStateException e) {CommunityServer.say(p,"Public minigame records are temporarily unavailable; /best still shows your saved times.");}
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{PENDING.remove(handler.player.getUuid());FIRST_VISITS.remove(handler.player.getUuid());ModeMaps.RUNS.remove(handler.player.getUuid());});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,damage)->{if(entity instanceof ServerPlayerEntity p)death(p);});
        ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->{if(!operator(p)&&current(p)!=Mode.SURVIVAL)p.changeGameMode(gameMode(p));if(current(p)==Mode.MINIGAMES||current(p)==Mode.ADVENTURE)ModeMaps.begin(p,ModeMaps.selectedMap(p,current(p)));if(current(p)==Mode.CREATIVE)CreativeGearPicker.onEnter(p);});
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->{
            if(entity instanceof ServerPlayerEntity p) {
                if(amount>0 && PENDING.remove(p.getUuid())!=null)CommunityServer.say(p,"Mode change cancelled by damage.");
                if(!operator(p)&&(current(p)==Mode.MINIGAMES || current(p)==Mode.ADVENTURE || current(p)==Mode.HUB)) return false;
            }
            return true;
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var p:server.getPlayerManager().getPlayerList()) {
                if(routeFirstVisit(p)) continue;
                var change=PENDING.get(p.getUuid());
                if(change!=null) {
                    var c=CommunityServer.get(server);
                    if(!p.isAlive()||p.hasVehicle()||p.isSleeping()||CommunityServer.moved(change.origin,p)||c.combat.getOrDefault(p.getUuid(),0)>server.getTicks()) {PENDING.remove(p.getUuid());CommunityServer.say(p,"Mode change cancelled. Stay still and avoid damage.");}
                    else if(server.getTicks()>=change.finish) {PENDING.remove(p.getUuid());switchNow(p,change.mode,change.map);}
                }
                if(!operator(p)&&current(p)!=Mode.SURVIVAL && p.getGameMode()!=gameMode(p))p.changeGameMode(gameMode(p));
                ModeMaps.tick(p);
                LobbyServer.tick(p);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(CommandManager.literal("guide").executes(ctx->guide(ctx.getSource().getPlayerOrThrow())));
            var play=CommandManager.literal("play").executes(ctx->menu(ctx.getSource().getPlayerOrThrow()));
            for(var mode:Mode.values())if(mode!=Mode.HUB)play.then(CommandManager.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx->{var p=ctx.getSource().getPlayerOrThrow();return CourseSelector.supports(mode)?CourseSelector.open(p,mode):request(p,mode,null);}));
            dispatcher.register(play);
            for(String root:List.of("minigame","adventure","retry")) {
                var command=CommandManager.literal(root).executes(ctx->{var p=ctx.getSource().getPlayerOrThrow();return root.equals("retry")?CommunityServer.say(p,"/retry <map> restarts a course."):CourseSelector.open(p,root.equals("adventure")?Mode.ADVENTURE:Mode.MINIGAMES);});
                for(var map:ModeMaps.MAPS.values()) if(root.equals("retry") || (root.equals("adventure")== (map.mode()==Mode.ADVENTURE)))command.then(CommandManager.literal(map.id()).executes(ctx->request(ctx.getSource().getPlayerOrThrow(),map.mode(),map.id())));
                dispatcher.register(command);
            }
        });
    }
}

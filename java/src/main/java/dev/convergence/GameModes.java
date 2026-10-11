package dev.convergence;

import java.util.*;
import net.minecraft.util.Prediction;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public final class GameModes {
    public enum Mode { SURVIVAL, CREATIVE, HARDCORE, MINIGAMES, ADVENTURE, HUB }
    static final Set<UUID> TRANSITIONS=new HashSet<>();
    static final Set<UUID> FIRST_VISITS=new HashSet<>();
    record Switch(Mode mode, String map, CommunityServer.Place origin, int finish) {}
    static final Map<UUID,Switch> PENDING=new HashMap<>();
    record OperatorTransfer(Mode previous,CompoundTag profile) {}
    static final Map<UUID,OperatorTransfer> OPERATOR_TRANSFERS=new HashMap<>();
    public static boolean operator(ServerPlayer player) {return Memberships.operator(player);}
    /** Vanilla /tp and dimension commands still save and restore each world's inventory. */
    public static void beforeOperatorTeleport(ServerPlayer p,ServerLevel destination) {
        if(!operator(p)||TRANSITIONS.contains(p.getUUID())||current(p)==of(destination)||OPERATOR_TRANSFERS.containsKey(p.getUUID()))return;
        var cursor=p.containerMenu.getCarried();
        if(!cursor.isEmpty()){p.containerMenu.setCarried(ItemStack.EMPTY);p.getInventory().placeItemBackInInventory(cursor, Prediction.SERVER_ONLY);}
        p.closeContainer();p.stopUsingItem();
        OPERATOR_TRANSFERS.put(p.getUUID(),new OperatorTransfer(current(p),capture(p)));
    }
    public static void afterOperatorTeleport(ServerPlayer p,boolean success) {
        var transfer=OPERATOR_TRANSFERS.remove(p.getUUID());if(transfer==null||!success)return;
        var s=state(p);var mode=of(p.level());var profiles=s.getCompoundOrEmpty("profiles");
        var next=profiles.getCompoundOrEmpty(mode.name());profiles.put(transfer.previous().name(),transfer.profile());
        s.putString("active",mode.name());restore(p,next);profiles.remove(mode.name());s.put("profiles",profiles);
        if(mode==Mode.CREATIVE) CreativeGearPicker.onEnter(p);
        PENDING.remove(p.getUUID());ModeMaps.RUNS.remove(p.getUUID());
        var community=CommunityServer.get(p.level().getServer());community.pending.remove(p.getUUID());
        community.server.getPlayerList().saveAll();
    }
    public static CompoundTag state(ServerPlayer p) { return ((ModePlayer)p).infinity$state(); }
    /** Since 26.1 vanilla stores player saves under players/data rather than playerdata. */
    static java.nio.file.Path savedPlayerFile(MinecraftServer server,UUID id) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(id+".dat");
    }
    public static Mode current(ServerPlayer p) {
        try { return Mode.valueOf(state(p).getStringOr("active","SURVIVAL")); }
        catch(IllegalArgumentException e) { throw new IllegalStateException("Unknown saved Infinity mode for "+p.getUUID(),e); }
    }
    public static Mode of(Level world) {
        String id=world.dimension().identifier().toString();
        for(Mode mode:Mode.values()) if(id.equals("convergence:"+mode.name().toLowerCase(Locale.ROOT))) return mode;
        return Mode.SURVIVAL;
    }
    static ServerLevel world(MinecraftServer server, Mode mode) {
        return mode==Mode.SURVIVAL?server.overworld():server.getLevel(ResourceKey.create(Registries.DIMENSION,Identifier.fromNamespaceAndPath("convergence",mode.name().toLowerCase(Locale.ROOT))));
    }
    public static boolean allowTeleport(ServerPlayer p, ServerLevel destination) {
        return operator(p)||TRANSITIONS.contains(p.getUUID()) || current(p)==of(destination);
    }
    static GameType gameMode(ServerPlayer p) {
        return switch(current(p)) {
            case CREATIVE -> GameType.CREATIVE;
            case MINIGAMES,ADVENTURE,HUB -> GameType.ADVENTURE;
            case HARDCORE -> state(p).getBooleanOr("eliminated",false)&&!Memberships.gameplayBypass(p)?GameType.SPECTATOR:GameType.SURVIVAL;
            default -> GameType.SURVIVAL;
        };
    }
    static CommunityServer.Place defaultPlace(MinecraftServer server, Mode mode) {
        var w=world(server,mode);
        if(mode==Mode.HUB) return LobbyServer.place(server,"main");
        if(mode==Mode.MINIGAMES || mode==Mode.ADVENTURE) return mapPlace(server,mode,ModeMaps.defaultMap(mode));
        if(mode==Mode.SURVIVAL) {
            var c=CommunityServer.get(server); if(c.data.spawn!=null && c.world(c.data.spawn)!=null && of(c.world(c.data.spawn))==Mode.SURVIVAL) return c.data.spawn;
            var s=server.getRespawnData(); var pos=s.pos();
            if(s.dimension().equals(Level.OVERWORLD)) return new CommunityServer.Place("minecraft:overworld",pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
        }
        for(int r=0;r<=64;r+=4) for(int x=-r;x<=r;x+=4) for(int z=-r;z<=r;z+=4) {
            w.getChunk(x>>4,z>>4); int y=w.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            var p=new CommunityServer.Place(w.dimension().identifier().toString(),x+.5,y,z+.5,0,0);
            if(CommunityServer.safe(w,p)) return p;
        }
        throw new IllegalStateException("No safe spawn in "+mode+". Ask the host to choose a dry spawn.");
    }
    static CommunityServer.Place mapPlace(MinecraftServer server, Mode mode, String id) {
        var spec=ModeMaps.MAPS.get(id);
        if(spec==null || spec.mode()!=mode || !ModeMaps.available(id))id=ModeMaps.defaultMap(mode);
        if(id==null)throw new IllegalStateException("No verified built-in course is available in "+mode+". Check the map preservation warnings in the server log.");
        var world=world(server,mode);var pos=ModeMaps.start(id);
        return new CommunityServer.Place(world.dimension().identifier().toString(),pos.getX()+.5,pos.getY(),pos.getZ()+.5,0,0);
    }
    public static TeleportTransition respawnTarget(ServerPlayer p, TeleportTransition vanilla, TeleportTransition.PostTeleportTransition callback) {
        var mode=current(p);
        if(mode==Mode.SURVIVAL || (of(vanilla.newLevel())==mode && mode!=Mode.MINIGAMES && mode!=Mode.ADVENTURE && mode!=Mode.HUB)) return vanilla;
        var place=mode==Mode.MINIGAMES || mode==Mode.ADVENTURE
            ? mapPlace(p.level().getServer(),mode,ModeMaps.selectedMap(p,mode))
            : defaultPlace(p.level().getServer(),mode);
        return new TeleportTransition(world(p.level().getServer(),mode),new Vec3(place.x(),place.y(),place.z()),Vec3.ZERO,place.yaw(),place.pitch(),callback);
    }
    static CompoundTag capture(ServerPlayer p) {
        AchievementRewards.beforeModeCapture(p);
        var view=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.registryAccess());
        p.getInventory().save(view.list("inventory",ItemStackWithSlot.CODEC));
        for(var slot:EquipmentSlot.values()) if(slot!=EquipmentSlot.MAINHAND) view.store("equipment_"+slot.getName(),ItemStack.OPTIONAL_CODEC,p.getItemBySlot(slot));
        p.getEnderChestInventory().storeAsSlots(view.list("ender",ItemStackWithSlot.CODEC));
        p.getFoodData().addAdditionalSaveData(view);
        view.putInt("selected",p.getInventory().getSelectedSlot());
        view.putFloat("health",p.getHealth()); view.putFloat("absorption",p.getAbsorptionAmount());
        view.putInt("level",p.experienceLevel); view.putInt("total",p.totalExperience); view.putFloat("xp",p.experienceProgress);
        view.store("effects",MobEffectInstance.CODEC.listOf(),new ArrayList<>(p.getActiveEffects()));
        view.storeNullable("respawn",ServerPlayer.RespawnConfig.CODEC,p.getRespawnConfig());
        view.putString("place",CommunityServer.GSON.toJson(CommunityServer.Place.of(p)));
        return view.buildResult();
    }
    static void restore(ServerPlayer p, CompoundTag profile) {
        AchievementRewards.beforeModeCapture(p);
        var v=TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),profile);
        p.getInventory().clearContent(); p.getEnderChestInventory().clearContent(); p.removeAllEffects();
        p.getInventory().load(v.listOrEmpty("inventory",ItemStackWithSlot.CODEC));
        for(var slot:EquipmentSlot.values()) if(slot!=EquipmentSlot.MAINHAND) p.setItemSlot(slot,v.read("equipment_"+slot.getName(),ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        p.getEnderChestInventory().fromSlots(v.listOrEmpty("ender",ItemStackWithSlot.CODEC));
        if(profile.isEmpty()) { p.getFoodData().setFoodLevel(20);p.getFoodData().setSaturation(5); }
        else p.getFoodData().readAdditionalSaveData(v);
        p.getInventory().setSelectedSlot(v.getIntOr("selected",0));
        for(var effect:v.read("effects",MobEffectInstance.CODEC.listOf()).orElse(List.of())) p.addEffect(effect);
        p.setHealth(Math.max(1,Math.min(p.getMaxHealth(),v.getFloatOr("health",20))));p.setAbsorptionAmount(v.getFloatOr("absorption",0));
        p.experienceLevel=v.getIntOr("level",0);p.totalExperience=v.getIntOr("total",0);p.experienceProgress=v.getFloatOr("xp",0);
        p.setExperienceLevels(p.experienceLevel);p.setExperiencePoints((int)(p.experienceProgress*p.getXpNeededForNextLevel()));
        p.setRespawnPosition(v.read("respawn",ServerPlayer.RespawnConfig.CODEC).orElse(null),false);
        p.clearFire();p.fallDistance=0;p.setDeltaMovement(Vec3.ZERO);p.setAirSupply(p.getMaxAirSupply());p.setTicksFrozen(0);
        p.inventoryMenu.sendAllDataToRemote();p.resetSentInfo();
    }
    static int request(ServerPlayer p, Mode mode, String map) {
        if(CourseSelector.supports(mode) && (ModeMaps.defaultMap(mode)==null || map!=null && !ModeMaps.available(map)))
            return CommunityServer.say(p,"This course is unavailable because its map region could not be verified. Ask the host to check the server log.");
        if(!Memberships.gameplayBypass(p)&&mode==Mode.HARDCORE && state(p).getBooleanOr("eliminated",false)) return CommunityServer.say(p,"Your Hardcore life has ended. Choose another mode with /play.");
        if(!p.getShoulderEntityLeft().isEmpty() || !p.getShoulderEntityRight().isEmpty()) return CommunityServer.say(p,"Let your shoulder pets dismount before changing modes.");
        if(!p.isAlive() || p.isPassenger() || p.isSleeping()) return CommunityServer.say(p,"Respawn, wake up, and leave your vehicle first.");
        var c=CommunityServer.get(p.level().getServer());
        if(Memberships.gameplayBypass(p)) {
            if(TRANSITIONS.contains(p.getUUID())||OPERATOR_TRANSFERS.containsKey(p.getUUID())) return CommunityServer.say(p,"Finish your current mode transition first.");
            PENDING.remove(p.getUUID());c.pending.remove(p.getUUID());
            if(current(p)==mode){if(mode==Mode.HUB)return LobbyServer.arrive(p,map==null?"main":map);if(map!=null)ModeMaps.begin(p,map);}
            else switchNow(p,mode,map);
            return CommunityServer.say(p,"OP mode access: "+mode+". /gamemode can change your abilities in any world.");
        }
        boolean leavingEliminatedHardcore=current(p)==Mode.HARDCORE && p.isSpectator()
            && state(p).getBooleanOr("eliminated",false) && mode!=Mode.HARDCORE;
        if(!leavingEliminatedHardcore && c.combat.getOrDefault(p.getUUID(),0)>c.server.getTickCount())
            return CommunityServer.say(p,"Wait 10 seconds after damage before changing modes.");
        if(PENDING.containsKey(p.getUUID())) return CommunityServer.say(p,"A mode change is already counting down.");
        if(current(p)==mode) {
            if(mode==Mode.HUB) return LobbyServer.arrive(p,map==null?"main":map);
            if(map!=null) ModeMaps.begin(p,map);
            return CommunityServer.say(p,"Current mode: "+mode+". /play lists all modes.");
        }
        c.pending.remove(p.getUUID());
        PENDING.put(p.getUUID(),new Switch(mode,map,CommunityServer.Place.of(p),c.server.getTickCount()+60));
        return CommunityServer.say(p,"Joining "+mode+" in 3 seconds. Stay still; damage cancels it. Each mode has its own inventory.");
    }
    static void switchNow(ServerPlayer p, Mode mode, String map) {
        var server=p.level().getServer();var current=current(p); if(current==mode)return;
        if(CourseSelector.supports(mode) && (ModeMaps.defaultMap(mode)==null || map!=null && !ModeMaps.available(map))) {
            CommunityServer.say(p,"This course is unavailable because its map region could not be verified. Ask the host to check the server log.");return;
        }
        if(!p.getShoulderEntityLeft().isEmpty() || !p.getShoulderEntityRight().isEmpty() || p.isPassenger()) { CommunityServer.say(p,"Dismount and let shoulder pets down first.");return; }
        var s=state(p); if(!Memberships.gameplayBypass(p)&&mode==Mode.HARDCORE && s.getBooleanOr("eliminated",false)) return;
        var profiles=s.getCompoundOrEmpty("profiles"); var next=profiles.getCompoundOrEmpty(mode.name());
        CommunityServer.Place place=null;
        if(mode!=Mode.MINIGAMES && mode!=Mode.ADVENTURE && mode!=Mode.HUB && next.contains("place")) place=CommunityServer.GSON.fromJson(next.getStringOr("place", ""),CommunityServer.Place.class);
        var c=CommunityServer.get(server);
        if(mode==Mode.MINIGAMES || mode==Mode.ADVENTURE) place=mapPlace(server,mode,map==null?ModeMaps.selectedMap(p,mode):map);
        if(place==null || c.world(place)==null || of(c.world(place))!=mode || !CommunityServer.safe(c.world(place),place)) place=defaultPlace(server,mode);
        // Return the carried cursor stack in the old world before capturing its profile.
        // Vanilla can leave that stack on playerScreenHandler when no container is open.
        var cursor=p.containerMenu.getCarried();
        if(!cursor.isEmpty()) {p.containerMenu.setCarried(ItemStack.EMPTY);p.getInventory().placeItemBackInInventory(cursor, Prediction.SERVER_ONLY);}
        p.closeContainer();p.stopUsingItem();
        if(current!=Mode.HUB||operator(p)) profiles.put(current.name(),capture(p));
        else profiles.remove(Mode.HUB.name());
        s.put("profiles",profiles);
        TRANSITIONS.add(p.getUUID());
        try {
            if(!p.teleportTo(c.world(place),place.x(),place.y(),place.z(),Set.of(),place.yaw(),place.pitch(),true)) { CommunityServer.say(p,"Mode change could not teleport. Your inventory is unchanged.");return; }
            s.putString("active",mode.name());restore(p,mode==Mode.HUB&&!operator(p)?new CompoundTag():next);p.setGameMode(gameMode(p));
            if(mode==Mode.CREATIVE) CreativeGearPicker.onEnter(p);
            profiles.remove(mode.name());c.pending.remove(p.getUUID());c.requests.remove(p.getUUID());c.requests.values().removeIf(r->r.sender().equals(p.getUUID()));
            ModeMaps.RUNS.remove(p.getUUID());
            if(mode==Mode.MINIGAMES || mode==Mode.ADVENTURE) ModeMaps.begin(p,map==null?ModeMaps.selectedMap(p,mode):map);
            if(mode==Mode.HUB) LobbyServer.arrive(p,map==null?"main":map);
            server.getPlayerList().saveAll();
            CommunityServer.say(p,"Now playing "+mode+". Your "+current+" inventory is saved.");
        } finally { TRANSITIONS.remove(p.getUUID()); }
    }
    static void death(ServerPlayer p) {
        PENDING.remove(p.getUUID());ModeMaps.RUNS.remove(p.getUUID());
        if(!Memberships.gameplayBypass(p)&&current(p)==Mode.HARDCORE) { state(p).putBoolean("eliminated",true);CommunityServer.say(p,"Your Hardcore life has ended. Respawn to spectate, then /play survival to continue elsewhere."); }
    }
    static boolean routeFirstVisit(ServerPlayer p) {
        if(operator(p)){FIRST_VISITS.remove(p.getUUID());return false;}
        if(!FIRST_VISITS.contains(p.getUUID()) || !p.connection.hasClientLoaded()) return false;
        FIRST_VISITS.remove(p.getUUID());
        switchNow(p,Mode.HUB,"main");
        return true;
    }
    static int menu(ServerPlayer p) {
        CommunityServer.say(p,"Choose a world: /play survival, creative, hardcore, minigames, adventure. /play minigames and /play adventure open course menus. /ranks shows free achievement ranks.");
        for(var mode:Mode.values()) if(mode!=Mode.HUB) p.sendSystemMessage(Component.literal("[ "+mode+" ]").withStyle(ChatFormatting.AQUA).withStyle(style->style.withClickEvent(new ClickEvent.RunCommand("/play "+mode.name().toLowerCase(Locale.ROOT)))));
        return 1;
    }
    static String guideText(ServerPlayer p) {
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
                : state(p).getBooleanOr("eliminated",false)
                ? "Hardcore life ended: spectate here, or /play survival to continue in another world. /hub visits the lobbies."
                : "Hardcore: one life in this world. /play survival or /hub leaves while keeping other world progress separate.";
            case MINIGAMES -> "Minigames: tap the course-start sign or use /play minigames to choose a course; /retry <map> replays. /best shows your records; /leaderboard <map> shows top times. /hub leaves.";
            case ADVENTURE -> "Adventure maps: tap the course-start sign or use /play adventure to choose a map; /retry <map> restarts your route. /hub returns to the lobbies.";
        };
    }
    static int guide(ServerPlayer p) { return CommunityServer.say(p,guideText(p)); }
    static void register() {
        MinigameRecords.register();
        CourseSelector.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server->{ModeMaps.build(server);LobbyServer.build(server);CourseSelector.installSigns(server);LobbyServer.installMenuSigns(server);System.out.println("[Infinity] Community and crossplay ready.");});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{PENDING.clear();TRANSITIONS.clear();FIRST_VISITS.clear();OPERATOR_TRANSFERS.clear();ModeMaps.RUNS.clear();});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            var p=handler.player;
            if(!operator(p)&&current(p)!=Mode.SURVIVAL) p.setGameMode(gameMode(p));
            if(of(p.level())!=current(p)) throw new IllegalStateException("Player mode and dimension disagree: "+p.getUUID()+". Restore a matching world/player backup.");
            if(current(p)==Mode.MINIGAMES || current(p)==Mode.ADVENTURE) ModeMaps.begin(p,ModeMaps.selectedMap(p,current(p)));
            if(!operator(p)&&current(p)==Mode.HUB) restore(p,new CompoundTag());
            if(current(p)==Mode.CREATIVE) CreativeGearPicker.onEnter(p);
            var savedPlayer = savedPlayerFile(server,p.getUUID());
            // The headless TestServer's mock players are spawned for combat tests;
            // their first-login routing is exercised explicitly in LobbyGameTests.
            if(!(server instanceof net.minecraft.gametest.framework.GameTestServer) && current(p)==Mode.SURVIVAL && !java.nio.file.Files.exists(savedPlayer)) FIRST_VISITS.add(p.getUUID());
            CommunityServer.say(p,"Select the Infinity Menu recovery compass for gear, powers, games, and AI helpers. Tap an INFINITY MENU lobby sign if you need the menu again. /hub returns to Main Hub.");
            try {MinigameRecords.sync(p);}catch(IllegalStateException e) {CommunityServer.say(p,"Public minigame records are temporarily unavailable; /best still shows your saved times.");}
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{PENDING.remove(handler.player.getUUID());FIRST_VISITS.remove(handler.player.getUUID());ModeMaps.RUNS.remove(handler.player.getUUID());});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,damage)->{if(entity instanceof ServerPlayer p)death(p);});
        ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->{if(!operator(p)&&current(p)!=Mode.SURVIVAL)p.setGameMode(gameMode(p));if(current(p)==Mode.MINIGAMES||current(p)==Mode.ADVENTURE)ModeMaps.begin(p,ModeMaps.selectedMap(p,current(p)));if(current(p)==Mode.CREATIVE)CreativeGearPicker.onEnter(p);});
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->{
            if(entity instanceof ServerPlayer p) {
                if(amount>0 && PENDING.remove(p.getUUID())!=null)CommunityServer.say(p,"Mode change cancelled by damage.");
                if(!operator(p)&&(current(p)==Mode.MINIGAMES || current(p)==Mode.ADVENTURE || current(p)==Mode.HUB)) return false;
            }
            return true;
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var p:server.getPlayerList().getPlayers()) {
                if(routeFirstVisit(p)) continue;
                var change=PENDING.get(p.getUUID());
                if(change!=null) {
                    var c=CommunityServer.get(server);
                    if(!p.isAlive()||p.isPassenger()||p.isSleeping()||CommunityServer.moved(change.origin,p)||c.combat.getOrDefault(p.getUUID(),0)>server.getTickCount()) {PENDING.remove(p.getUUID());CommunityServer.say(p,"Mode change cancelled. Stay still and avoid damage.");}
                    else if(server.getTickCount()>=change.finish) {PENDING.remove(p.getUUID());switchNow(p,change.mode,change.map);}
                }
                if(!operator(p)&&current(p)!=Mode.SURVIVAL && p.gameMode()!=gameMode(p))p.setGameMode(gameMode(p));
                ModeMaps.tick(p);
                LobbyServer.tick(p);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(Commands.literal("guide").executes(ctx->guide(ctx.getSource().getPlayerOrException())));
            var play=Commands.literal("play").executes(ctx->menu(ctx.getSource().getPlayerOrException()));
            for(var mode:Mode.values())if(mode!=Mode.HUB)play.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx->{var p=ctx.getSource().getPlayerOrException();return CourseSelector.supports(mode)?CourseSelector.open(p,mode):request(p,mode,null);}));
            dispatcher.register(play);
            for(String root:List.of("minigame","adventure","retry")) {
                var command=Commands.literal(root).executes(ctx->{var p=ctx.getSource().getPlayerOrException();return root.equals("retry")?CommunityServer.say(p,"/retry <map> restarts a course."):CourseSelector.open(p,root.equals("adventure")?Mode.ADVENTURE:Mode.MINIGAMES);});
                for(var map:ModeMaps.MAPS.values()) if(root.equals("retry") || (root.equals("adventure")== (map.mode()==Mode.ADVENTURE)))command.then(Commands.literal(map.id()).executes(ctx->request(ctx.getSource().getPlayerOrException(),map.mode(),map.id())));
                dispatcher.register(command);
            }
        });
    }
}

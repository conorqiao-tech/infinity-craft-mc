package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.DragonBreathParticleEffect;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/** Achievement rewards use their own UUID entitlements, independently of cosmetic ranks. */
final class AchievementRewards {
    record Reward(String id,String name,Identifier advancement,boolean cosmetic) {
        String key() {return (cosmetic?"cosmetic:":"power:")+id;}
    }
    static Reward reward(String id,String name,String advancement,boolean cosmetic) {
        return new Reward(id,name,Identifier.of("minecraft",advancement),cosmetic);
    }
    static final List<Reward> REWARDS=List.of(
        reward("hacks","Flight, Night Vision and Resistance II","nether/all_effects",false),
        reward("fireguard","Fire Resistance","nether/obtain_blaze_rod",false),
        reward("windstep","15 seconds of Speed II and Jump Boost II; 45 second cooldown","adventure/shoot_arrow",false),
        reward("explorer","Speed II, Jump Boost II and Night Vision","adventure/adventuring_time",false),
        reward("aquatic","Water Breathing and Dolphin's Grace","husbandry/tactical_fishing",false),
        reward("nether","Fire Resistance, Resistance I and Night Vision","nether/explore_nether",false),
        reward("wither","Wither aura","nether/summon_wither",true),
        reward("diamond","Diamond sparkle","story/mine_diamond",true),
        reward("dragon","Dragon aura","end/kill_dragon",true),
        reward("explorer","Explorer trail","adventure/adventuring_time",true),
        reward("totem","Totem halo","adventure/totem_of_undying",true),
        reward("emerald","Emerald sparkles","adventure/trade",true),
        reward("ocean","Ocean bubbles","husbandry/tactical_fishing",true),
        reward("nether","Nether embers","nether/obtain_blaze_rod",true));
    static final Map<MinecraftServer,AchievementRewards> INSTANCES=new WeakHashMap<>();
    static final int WIND_TICKS=300,WIND_COOLDOWN=900,PARTICLE_INTERVAL=10,PARTICLE_DISTANCE=24,MAX_VIEWERS=32;
    static final class SavedEffect {int amplifier,maximumDuration;SavedEffect(int amplifier,int maximumDuration){this.amplifier=amplifier;this.maximumDuration=maximumDuration;}}
    static final class Account { Set<String> unlocked=new TreeSet<>(); String cosmetic="off"; long windReadyAt=0; Map<String,SavedEffect> ownedEffects=new TreeMap<>(); }
    static final class Data { int format=1; Map<String,Account> accounts=new TreeMap<>(); }
    record OwnedEffect(StatusEffectInstance instance,int amplifier,int maximumDuration,int appliedTick) {}
    static final class Active {
        String power="off";
        boolean flightOwned,previousAllowFlying,previousFlying;
        float previousFlySpeed;
        int windUntil,windCooldown;
        Vec3d lastTrail;
        final Map<RegistryEntry<StatusEffect>,OwnedEffect> effects=new HashMap<>();
    }
    final MinecraftServer server; final Path file;
    Data data=new Data(); final Map<UUID,Active> active=new HashMap<>();
    boolean dirty=false;
    int nextSaveTick=0;
    AchievementRewards(MinecraftServer server,Path file) {
        this.server=server;this.file=file;
        if(Files.exists(file))try {
            data=CommunityServer.GSON.fromJson(Files.readString(file),Data.class);
            if(data==null||data.format!=1||data.accounts==null)throw new IllegalArgumentException("Invalid reward data");
            for(var entry:data.accounts.entrySet()) {
                UUID.fromString(entry.getKey());var a=entry.getValue();
                if(a!=null&&a.ownedEffects==null)a.ownedEffects=new TreeMap<>();
                if(a==null||a.unlocked==null||a.cosmetic==null||a.windReadyAt<0||a.unlocked.stream().anyMatch(id->REWARDS.stream().noneMatch(r->r.key().equals(id)))
                    ||(!a.cosmetic.equals("off")&&find(a.cosmetic,true)==null))throw new IllegalArgumentException("Invalid reward account");
                for(var effect:a.ownedEffects.entrySet())if(!Registries.STATUS_EFFECT.containsId(Identifier.of(effect.getKey()))||effect.getValue()==null||effect.getValue().amplifier<0||effect.getValue().amplifier>1||effect.getValue().maximumDuration<1||effect.getValue().maximumDuration>400)throw new IllegalArgumentException("Invalid owned reward effect");
            }
        }catch(Exception e){throw new IllegalStateException("Cannot load achievement rewards. Original file was not overwritten.",e);}
    }
    static AchievementRewards get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,s->new AchievementRewards(s,s.getSavePath(WorldSavePath.ROOT).resolve("infinity-rewards.json")));
    }
    static Reward find(String id) {return REWARDS.stream().filter(r->r.id().equals(id)).findFirst().orElse(null);}
    static Reward find(String id,boolean cosmetic) {return REWARDS.stream().filter(r->r.id().equals(id)&&r.cosmetic()==cosmetic).findFirst().orElse(null);}
    static String advancementName(Reward reward) {return switch(reward.advancement().getPath()) {
        case "nether/all_effects" -> "How Did We Get Here?";
        case "nether/obtain_blaze_rod" -> "Into Fire";
        case "adventure/shoot_arrow" -> "Take Aim";
        case "adventure/adventuring_time" -> "Adventuring Time";
        case "husbandry/tactical_fishing" -> "Tactical Fishing";
        case "nether/explore_nether" -> "Hot Tourist Destinations";
        case "nether/summon_wither" -> "Withering Heights";
        case "story/mine_diamond" -> "Diamonds!";
        case "end/kill_dragon" -> "Free the End";
        case "adventure/totem_of_undying" -> "Postmortal";
        case "adventure/trade" -> "What a Deal!";
        default -> reward.advancement().toString();
    };}
    Account account(UUID id) {return data.accounts.computeIfAbsent(id.toString(),key->new Account());}
    Active state(ServerPlayerEntity p) {return active.computeIfAbsent(p.getUuid(),key->new Active());}
    void save() {
        try {
            if(Files.exists(file))Files.copy(file,file.resolveSibling(file.getFileName()+".previous"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            CommunityServer.atomicJson(file,data);
            dirty=false;
            nextSaveTick=0;
        }catch(Exception e){throw new IllegalStateException("Cannot save achievement rewards",e);}
    }
    boolean flush() {
        if(!dirty)return true;
        if(server.getTicks()<nextSaveTick)return false;
        try {save();return true;}
        catch(IllegalStateException error) {
            nextSaveTick=server.getTicks()+100;
            System.err.println("[Infinity rewards] Save failed; changes remain in memory and will retry in five seconds: "+error.getMessage());
            return false;
        }
    }
    boolean unlock(ServerPlayerEntity p,String rewardKey) {
        var reward=REWARDS.stream().filter(r->r.key().equals(rewardKey)).findFirst().orElse(null);
        return reward!=null&&unlock(p,reward.id(),reward.cosmetic());
    }
    boolean unlock(ServerPlayerEntity p,String id,boolean cosmetic) {
        var reward=find(id,cosmetic);if(reward==null)return false;
        if(account(p.getUuid()).unlocked.add(reward.key())){dirty=true;flush();}return true;
    }
    void sync(ServerPlayerEntity p) {
        var a=account(p.getUuid());var newly=new ArrayList<Reward>();
        for(var reward:REWARDS)if(!a.unlocked.contains(reward.key())) {
            var advancement=server.getAdvancementLoader().get(reward.advancement());
            if(advancement!=null&&p.getAdvancementTracker().getProgress(advancement).isDone()){a.unlocked.add(reward.key());newly.add(reward);dirty=true;}
        }
        if(!newly.isEmpty()) {
            flush();for(var reward:newly)CommunityServer.say(p,"Achievement reward unlocked: "+reward.name()+". Use /"+(reward.cosmetic()?"cosmetic ":"power ")+reward.id()+".");
        }
    }
    boolean unlocked(ServerPlayerEntity p,String id) {var reward=find(id);return reward!=null&&unlocked(p,reward);}
    boolean unlocked(ServerPlayerEntity p,Reward reward) {return reward!=null&&(Memberships.operator(p)||account(p.getUuid()).unlocked.contains(reward.key()));}
    boolean eligible(ServerPlayerEntity p,Reward reward) {return unlocked(p,reward)||(!reward.cosmetic()&&RewardTrades.hasPower(p,reward.id()));}
    static boolean allowed(ServerPlayerEntity p) {
        return p.isAlive()&&(Memberships.operator(p)||(!p.isSpectator()&&GameModes.current(p)==GameModes.Mode.SURVIVAL&&p.getGameMode()==GameMode.SURVIVAL));
    }
    boolean inCombat(ServerPlayerEntity p) {return !Memberships.operator(p)&&CommunityServer.get(server).combat.getOrDefault(p.getUuid(),0)>server.getTicks();}
    int list(ServerPlayerEntity p,Boolean cosmetics) {
        sync(p);var a=account(p.getUuid());
        for(var reward:REWARDS)if(cosmetics==null||reward.cosmetic()==cosmetics)
            CommunityServer.say(p,(reward.cosmetic()?"cosmetic ":"power ")+reward.id()+": "+reward.name()+" — "+(eligible(p,reward)?"unlocked":"locked")+" ("+advancementName(reward)+(reward.cosmetic()?"":" or /trades")+")");
        return CommunityServer.say(p,"Rewards are independent of ranks. One power preset at a time; players use Survival, OP4 can use every mode without unlocks. Active: "+state(p).power+". /power off stops it; /cosmetic off hides your cosmetic.");
    }
    boolean cosmetic(ServerPlayerEntity p,String id) {
        sync(p);var reward=find(id,true);
        if(!id.equals("off")&&(reward==null||!unlocked(p,reward))) {CommunityServer.say(p,"That cosmetic is locked. /cosmetics shows its advancement.");return false;}
        if(account(p.getUuid()).cosmetic.equals(id)){CommunityServer.say(p,"Cosmetic: "+id);return true;}
        account(p.getUuid()).cosmetic=id;state(p).lastTrail=null;dirty=true;flush();CommunityServer.say(p,"Cosmetic: "+id);return true;
    }
    boolean power(ServerPlayerEntity p,String id) {
        if(id.equals("off")){clearPowers(p,true);CommunityServer.say(p,"Achievement powers are off.");return true;}
        sync(p);var reward=find(id,false);
        if(reward==null||!eligible(p,reward)){CommunityServer.say(p,"That power is locked. /rewards shows its advancement; /trades lists item costs.");return false;}
        var s=state(p);
        if(id.equals(s.power)&&!id.equals("windstep")){clearPowers(p,true);CommunityServer.say(p,"Power preset is off.");return true;}
        if(!allowed(p)){CommunityServer.say(p,"Achievement powers can only be activated while playing Survival.");return false;}
        if(movement(id)&&inCombat(p)){CommunityServer.say(p,"Wait 10 seconds after damage before activating movement powers.");return false;}
        if(id.equals("windstep")) {
            if(!Memberships.operator(p)&&(s.windCooldown>server.getTicks()||account(p.getUuid()).windReadyAt>System.currentTimeMillis())){CommunityServer.say(p,"Windstep is cooling down.");return false;}
        }
        clearPowers(p,true);s.power=id;
        if(id.equals("windstep")) {
            s.windUntil=server.getTicks()+WIND_TICKS;s.windCooldown=server.getTicks()+WIND_COOLDOWN;
            account(p.getUuid()).windReadyAt=System.currentTimeMillis()+WIND_COOLDOWN*50L;dirty=true;
        }
        apply(p);
        if(dirty){clearPowers(p,true);CommunityServer.say(p,"Power could not start because reward data could not be saved. Try again later.");return false;}
        CommunityServer.say(p,reward.name()+" activated. Existing potion and armor effects are preserved.");return true;
    }
    static boolean movement(String id) {return Set.of("hacks","windstep","explorer","aquatic").contains(id);}
    /** Vanilla mutates visual flags when another effect merges; use the owned lifetime instead. */
    boolean marked(NbtCompound n,OwnedEffect owned) {
        int expected=Math.max(0,owned.maximumDuration()-(server.getTicks()-owned.appliedTick()));
        return n.getInt("amplifier",0)==owned.amplifier()&&Math.abs(n.getInt("duration",0)-expected)<=2;
    }
    NbtCompound stripOwned(NbtCompound n,OwnedEffect owned) {
        NbtCompound hidden=n.contains("hidden_effect")?stripOwned(n.getCompoundOrEmpty("hidden_effect"),owned):null;
        if(marked(n,owned))return hidden;
        var copy=n.copy();if(hidden==null)copy.remove("hidden_effect");else copy.put("hidden_effect",hidden);return copy;
    }
    static String effectId(RegistryEntry<StatusEffect> type) {return Registries.STATUS_EFFECT.getId(type.value()).toString();}
    void restoreClean(ServerPlayerEntity p,RegistryEntry<StatusEffect> type,NbtCompound serialized,NbtCompound clean) {
        if(clean==null)p.removeStatusEffect(type);
        else {
            clean.put("id",serialized.get("id"));var envelope=new NbtCompound();envelope.put("effect",clean);
            var restored=NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),envelope).read("effect",StatusEffectInstance.CODEC)
                .orElseThrow(()->new IllegalStateException("Cannot restore external status effect after reward cleanup"));
            p.setStatusEffect(restored,p);
        }
    }
    NbtCompound serialized(ServerPlayerEntity p,StatusEffectInstance effect) {
        var writer=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());writer.put("effect",StatusEffectInstance.CODEC,effect);
        return writer.getNbt().getCompoundOrEmpty("effect");
    }
    void removeOwned(ServerPlayerEntity p,RegistryEntry<StatusEffect> type) {
        var s=state(p);var owned=s.effects.remove(type);if(owned==null)return;
        if(account(p.getUuid()).ownedEffects.remove(effectId(type))!=null)dirty=true;
        var current=p.getStatusEffect(type);
        // setStatusEffect from another source replaces the instance, so leave that source alone.
        if(current!=owned.instance())return;
        var serialized=serialized(p,current);restoreClean(p,type,serialized,stripOwned(serialized,owned));
    }
    void maintain(ServerPlayerEntity p,RegistryEntry<StatusEffect> type,int amplifier,int duration,int refreshBelow) {
        var s=state(p);var current=p.getStatusEffect(type);var owned=s.effects.get(type);
        if(owned!=null&&current==owned.instance()&&current.getDuration()>refreshBelow)return;
        if(owned!=null&&current==owned.instance()&&stripOwned(serialized(p,current),owned)==null) {
            // Renew a pure reward layer without another JSON write or a remove/add packet pair.
            var renewed=new StatusEffectInstance(type,duration,amplifier,true,false,true);p.setStatusEffect(renewed,p);
            s.effects.put(type,new OwnedEffect(renewed,amplifier,duration,server.getTicks()));return;
        }
        if(owned!=null)removeOwned(p,type);
        if(p.hasStatusEffect(type))return;
        var effect=new StatusEffectInstance(type,duration,amplifier,true,false,true);
        if(p.addStatusEffect(effect)) {
            s.effects.put(type,new OwnedEffect(effect,amplifier,duration,server.getTicks()));
            var prior=account(p.getUuid()).ownedEffects.put(effectId(type),new SavedEffect(amplifier,duration));
            if(prior==null||prior.amplifier!=amplifier||prior.maximumDuration!=duration)dirty=true;
        }
    }
    void flight(ServerPlayerEntity p,boolean enabled,boolean packets) {
        var s=state(p);var abilities=p.getAbilities();
        if(enabled&&!s.flightOwned) {
            s.previousAllowFlying=abilities.allowFlying;s.previousFlying=abilities.flying;s.previousFlySpeed=abilities.getFlySpeed();s.flightOwned=true;
            abilities.allowFlying=true;if(packets)p.sendAbilitiesUpdate();
        }else if(!enabled&&s.flightOwned) {
            s.flightOwned=false;
            if(!p.isCreative()&&!p.isSpectator()) {
                abilities.allowFlying=s.previousAllowFlying;abilities.flying=s.previousAllowFlying&&s.previousFlying;abilities.setFlySpeed(s.previousFlySpeed);p.fallDistance=0;
                if(packets)p.sendAbilitiesUpdate();
            }
        }
    }
    void apply(ServerPlayerEntity p) {
        var s=state(p);if(!allowed(p)||(!s.power.equals("off")&&!eligible(p,find(s.power,false)))){clearPowers(p,true);return;}
        if(s.power.equals("windstep")&&s.windUntil<=server.getTicks()){clearPowers(p,true);return;}
        flight(p,s.power.equals("hacks"),true);
        switch(s.power) {
            case "hacks" -> {maintain(p,StatusEffects.NIGHT_VISION,0,400,240);maintain(p,StatusEffects.RESISTANCE,1,80,40);}
            case "fireguard" -> maintain(p,StatusEffects.FIRE_RESISTANCE,0,80,40);
            case "windstep" -> {int duration=s.windUntil-server.getTicks();maintain(p,StatusEffects.SPEED,1,duration,0);maintain(p,StatusEffects.JUMP_BOOST,1,duration,0);}
            case "explorer" -> {maintain(p,StatusEffects.SPEED,1,80,40);maintain(p,StatusEffects.JUMP_BOOST,1,80,40);maintain(p,StatusEffects.NIGHT_VISION,0,400,240);}
            case "aquatic" -> {maintain(p,StatusEffects.WATER_BREATHING,0,80,40);maintain(p,StatusEffects.DOLPHINS_GRACE,0,80,40);}
            case "nether" -> {maintain(p,StatusEffects.FIRE_RESISTANCE,0,80,40);maintain(p,StatusEffects.RESISTANCE,0,80,40);maintain(p,StatusEffects.NIGHT_VISION,0,400,240);}
        }
        flush();
    }
    void clearPowers(ServerPlayerEntity p,boolean packets) {
        var s=active.get(p.getUuid());if(s==null)return;
        s.power="off";s.windUntil=0;
        for(var type:new ArrayList<>(s.effects.keySet()))removeOwned(p,type);
        flight(p,false,packets);
        flush();
    }
    static void beforeModeCapture(ServerPlayerEntity p) {var rewards=INSTANCES.get(p.getEntityWorld().getServer());if(rewards!=null)rewards.clearPowers(p,true);}
    void leave(ServerPlayerEntity p) {clearPowers(p,false);active.remove(p.getUuid());}
    void joined(ServerPlayerEntity p) {
        clearPowers(p,true);active.remove(p.getUuid());
        var saved=account(p.getUuid()).ownedEffects;
        for(var entry:new ArrayList<>(saved.entrySet())) {
            var type=Registries.STATUS_EFFECT.getEntry(Identifier.of(entry.getKey())).orElseThrow();var current=p.getStatusEffect(type);
            if(current!=null){var encoded=serialized(p,current);restoreClean(p,type,encoded,stripSaved(encoded,entry.getValue()));}
        }
        if(!saved.isEmpty()){saved.clear();dirty=true;flush();}
        // Vanilla saves abilities. A crash must not turn a temporary Survival power into permanent flight.
        if(!Memberships.operator(p)&&!p.isCreative()&&!p.isSpectator()&&p.getAbilities().allowFlying){p.getAbilities().allowFlying=false;p.getAbilities().flying=false;p.sendAbilitiesUpdate();}
        sync(p);
    }
    /** Crash recovery: only recorded short reward layers are eligible; longer external potions remain. */
    static NbtCompound stripSaved(NbtCompound n,SavedEffect owned) {
        NbtCompound hidden=n.contains("hidden_effect")?stripSaved(n.getCompoundOrEmpty("hidden_effect"),owned):null;
        if(n.getInt("amplifier",0)==owned.amplifier&&n.getInt("duration",0)<=owned.maximumDuration)return hidden;
        var copy=n.copy();if(hidden==null)copy.remove("hidden_effect");else copy.put("hidden_effect",hidden);return copy;
    }
    int emit(ServerPlayerEntity p) {
        if(!p.isAlive()||p.isSpectator())return 0;
        var cosmetic=account(p.getUuid()).cosmetic;if(cosmetic.equals("off")||!unlocked(p,find(cosmetic,true)))return 0;
        var s=state(p);var pos=p.getEntityPos();
        if(cosmetic.equals("explorer")&&s.lastTrail!=null&&pos.squaredDistanceTo(s.lastTrail)<.04)return 0;
        s.lastTrail=pos;
        ParticleEffect particle=switch(cosmetic){case "wither"->ParticleTypes.SOUL;case "diamond"->ParticleTypes.END_ROD;case "dragon"->DragonBreathParticleEffect.of(ParticleTypes.DRAGON_BREATH,1);case "explorer"->ParticleTypes.ENCHANT;case "totem"->ParticleTypes.TOTEM_OF_UNDYING;case "ocean"->ParticleTypes.BUBBLE;case "nether"->ParticleTypes.FLAME;default->ParticleTypes.HAPPY_VILLAGER;};
        int viewers=0;
        for(var viewer:server.getPlayerManager().getPlayerList())if(viewer.getEntityWorld()==p.getEntityWorld()&&viewer.squaredDistanceTo(p)<=PARTICLE_DISTANCE*PARTICLE_DISTANCE) {
            p.getEntityWorld().spawnParticles(viewer,particle,false,false,p.getX(),p.getY()+(cosmetic.equals("explorer")?.15:1),p.getZ(),2,.3,.25,.3,.015);
            if(++viewers>=MAX_VIEWERS)break;
        }
        return viewers;
    }
    void tick() {
        flush();
        for(var p:server.getPlayerManager().getPlayerList()) {
            if(server.getTicks()%100==0)sync(p);
            var s=active.get(p.getUuid());
            if(s!=null) {
                if(!allowed(p)){clearPowers(p,true);}
                else {
                    if(inCombat(p)&&movement(s.power))clearPowers(p,true);
                    if(server.getTicks()%20==0)apply(p);
                }
            }
            if(server.getTicks()%PARTICLE_INTERVAL==0)emit(p);
        }
    }
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(AchievementRewards::get);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{var s=INSTANCES.get(server);if(s!=null){for(var p:server.getPlayerManager().getPlayerList())s.clearPowers(p,false);s.nextSaveTick=0;s.flush();}});
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerPlayerEvents.JOIN.register(p->get(p.getEntityWorld().getServer()).joined(p));
        ServerPlayerEvents.LEAVE.register(p->{var s=INSTANCES.get(p.getEntityWorld().getServer());if(s!=null)s.leave(p);});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(entity instanceof ServerPlayerEntity p)get(p.getEntityWorld().getServer()).clearPowers(p,false);});
        ServerPlayerEvents.AFTER_RESPAWN.register((old,p,alive)->{var s=get(p.getEntityWorld().getServer());s.clearPowers(old,false);s.joined(p);});
        ServerTickEvents.END_SERVER_TICK.register(server->get(server).tick());
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(CommandManager.literal("rewards").executes(c->get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow(),null)));
            for(String root:List.of("powers","power")) {
                var command=CommandManager.literal(root).executes(c->get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow(),false));
                command.then(CommandManager.literal("help").executes(c->get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow(),false)));
                for(String id:List.of("hacks","fireguard","windstep","explorer","aquatic","nether","off"))command.then(CommandManager.literal(id).executes(c->get(c.getSource().getServer()).power(c.getSource().getPlayerOrThrow(),id)?1:0));
                dispatcher.register(command);
            }
            dispatcher.register(CommandManager.literal("cosmetics").executes(c->get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow(),true)));
            var cosmetic=CommandManager.literal("cosmetic").executes(c->get(c.getSource().getServer()).list(c.getSource().getPlayerOrThrow(),true));
            for(String id:List.of("wither","diamond","dragon","explorer","totem","emerald","ocean","nether","off"))cosmetic.then(CommandManager.literal(id).executes(c->get(c.getSource().getServer()).cosmetic(c.getSource().getPlayerOrThrow(),id)?1:0));
            dispatcher.register(cosmetic);
        });
    }
}

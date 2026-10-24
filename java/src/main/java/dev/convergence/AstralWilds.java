package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/** Rare Odyssey guardians are ordinary native mobs with a persistent, one-encounter ruin receipt. */
final class AstralWilds {
    static final String TAG="infinity_astral_guardian";
    static final String KIND="infinity_astral_kind_";
    static final String RUIN="infinity_astral_ruin_";
    static final int WORLD_CAP=12, NEARBY_CAP=2, MAX_ENCOUNTERS=4096;
    static final Map<MinecraftServer,Manager> INSTANCES=new WeakHashMap<>();
    enum Variant {
        CINDER("cinderbound_keeper","Cinderbound Keeper",EntityTypes.HUSK,32,ParticleTypes.SMALL_FLAME),
        RIME("rimeglass_ranger","Rimeglass Ranger",EntityTypes.STRAY,24,ParticleTypes.SNOWFLAKE),
        RIFT("rift_skitter","Rift Skitter",EntityTypes.ENDERMITE,16,ParticleTypes.PORTAL);
        final String id,name; final EntityType<? extends Mob> type; final float health; final ParticleOptions particle;
        Variant(String id,String name,EntityType<? extends Mob> type,float health,ParticleOptions particle){this.id=id;this.name=name;this.type=type;this.health=health;this.particle=particle;}
        ResourceKey<LootTable> loot(){return ResourceKey.create(Registries.LOOT_TABLE,Identifier.fromNamespaceAndPath("convergence","astral/"+id));}
    }
    private AstralWilds(){}
    static boolean progressionAllowed(ServerPlayer p){
        var mode=GameModes.current(p);
        return ServerMenu.allowed(p)&&!p.isCreative()&&(mode==GameModes.Mode.SURVIVAL||mode==GameModes.Mode.HARDCORE)&&GameModes.of(p.level())==mode;
    }
    static Variant variant(Entity entity){
        if(!(entity instanceof Mob)||!entity.entityTags().contains(TAG))return null;
        for(var v:Variant.values())if(entity.getType()==v.type&&entity.entityTags().contains(KIND+v.id))return v;
        return null;
    }
    static String ruinKey(Entity entity){return entity.entityTags().stream().filter(s->s.startsWith(RUIN)).map(s->s.substring(RUIN.length())).findFirst().orElse("");}
    static Variant variantAt(ServerLevel world,BlockPos at){
        var biome=world.getBiome(at);
        if(biome.is(BiomeTags.IS_NETHER))return Variant.CINDER;
        if(biome.is(BiomeTags.IS_END))return Variant.RIFT;
        if(biome.is(BiomeTags.IS_OVERWORLD))return biome.value().getBaseTemperature()<.2F?Variant.RIME:Variant.CINDER;
        return null;
    }
    static boolean naturalAllowed(ServerLevel world){return world.getDifficulty()!=Difficulty.PEACEFUL&&world.getGameRules().get(GameRules.SPAWN_MOBS)&&world.getGameRules().get(GameRules.SPAWN_MONSTERS);}
    static Manager get(MinecraftServer server){return INSTANCES.computeIfAbsent(server,Manager::new);}
    static final class Encounters extends SavedData {
        static final SavedDataType<Encounters> TYPE=new SavedDataType<>(Identifier.parse("convergence:astral_encounters"),Encounters::new,CompoundTag.CODEC.xmap(Encounters::new,s->s.data),null);
        final CompoundTag data;
        Encounters(){this(new CompoundTag());} Encounters(CompoundTag data){this.data=data;}
        CompoundTag entry(String key){return data.getCompoundOrEmpty(key);}
        boolean contains(String key){return data.getCompound(key).isPresent();}
        void put(String key,CompoundTag entry){data.put(key,entry);setDirty();}
        int active(String dimension){int n=0;for(String key:data.keySet()){var e=entry(key);if(e.getStringOr("dimension","").equals(dimension)&&!e.getBooleanOr("ended",false))n++;}return n;}
    }
    static final class Manager {
        final MinecraftServer server; final Encounters encounters;
        final Map<UUID,Mob> loaded=new HashMap<>();
        int playerCursor;
        Manager(MinecraftServer server){this.server=server;encounters=server.overworld().getDataStorage().computeIfAbsent(Encounters.TYPE);}
        boolean recorded(Mob mob){
            var e=encounters.entry(ruinKey(mob));
            return variant(mob)!=null&&!e.getBooleanOr("ended",false)&&e.getStringOr("uuid","").equals(mob.getStringUUID())
                &&e.getStringOr("variant","").equals(variant(mob).id)
                &&e.getStringOr("dimension","").equals(mob.level().dimension().identifier().toString());
        }
        /** Loading never re-equips, heals or rewrites an existing entity. Unknown tags are not trusted as receipts. */
        void load(Entity entity){if(entity instanceof Mob mob&&recorded(mob))loaded.put(mob.getUUID(),mob);}
        void unload(Entity entity){
            loaded.remove(entity.getUUID());
            // A normal chunk unload is still a living encounter and must not permit a duplicate.
            // Native terminal removal (including Peaceful despawn and mob conversion) consumes
            // this ruin permanently, but cannot reserve a live-world budget slot forever.
            var reason=entity.getRemovalReason();
            if(entity instanceof Mob mob&&(reason==Entity.RemovalReason.KILLED||reason==Entity.RemovalReason.DISCARDED)&&recorded(mob)){
                var entry=encounters.entry(ruinKey(mob)).copy();entry.putBoolean("ended",true);entry.putString("retired_reason",reason.name());
                encounters.put(ruinKey(mob),entry);server.overworld().getDataStorage().scheduleSave();
            }
        }
        Mob nearest(ServerPlayer p,double range){
            double squared=range*range;Mob nearest=null;
            for(var mob:loaded.values())if(mob.isAlive()&&!mob.isRemoved()&&mob.level()==p.level()&&recorded(mob)){
                double d=mob.distanceToSqr(p);if(d<squared){nearest=mob;squared=d;}
            }
            return nearest;
        }
        int nearby(ServerLevel world,Vec3 point){int n=0;for(var mob:loaded.values())if(mob.level()==world&&mob.isAlive()&&!mob.isRemoved()&&mob.position().distanceToSqr(point)<64*64)n++;return n;}
        /** The receipt remains for unloaded, dead or missing guardians. A missing entity is never a free respawn. */
        Mob spawn(ServerPlayer p,OdysseyJournal.LoadedRuin ruin){
            if(!progressionAllowed(p)||ruin==null||!naturalAllowed(p.level()))return null;
            var world=p.level();String dimension=world.dimension().identifier().toString();
            if(encounters.contains(ruin.key())||encounters.data.keySet().size()>=MAX_ENCOUNTERS||encounters.active(dimension)>=WORLD_CAP||nearby(world,Vec3.atCenterOf(ruin.center()))>=NEARBY_CAP)return null;
            if(world.getChunkSource().getChunkNow(ruin.center().getX()>>4,ruin.center().getZ()>>4)==null)return null;
            var v=variantAt(world,ruin.center());if(v==null)return null;
            var mob=v.type.create(world,EntitySpawnReason.NATURAL);if(mob==null)return null;
            var point=placement(p,mob,ruin.center());if(point==null){mob.discard();return null;}
            mob.setPos(point);mob.setCustomName(Component.literal(v.name));mob.setPersistenceRequired();mob.setCanPickUpLoot(false);
            mob.addTag(TAG);mob.addTag(KIND+v.id);mob.addTag(RUIN+ruin.key());
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(v.health);mob.setHealth(v.health);
            if(v==Variant.CINDER){mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE,-1,0,false,false));mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(5);}
            if(v==Variant.RIME){mob.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));mob.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CHAINMAIL_HELMET));mob.setDropChance(EquipmentSlot.MAINHAND,0);mob.setDropChance(EquipmentSlot.HEAD,0);}
            if(v==Variant.RIFT)mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(4);
            // Record before insertion so entity-load callbacks can attach the exact native UUID.
            var receipt=new CompoundTag();receipt.putString("dimension",dimension);receipt.putString("uuid",mob.getStringUUID());receipt.putString("variant",v.id);receipt.putBoolean("ended",false);
            encounters.put(ruin.key(),receipt);
            if(!world.addFreshEntity(mob)){encounters.data.remove(ruin.key());encounters.setDirty();mob.discard();return null;}
            load(mob);server.overworld().getDataStorage().scheduleSave();return mob;
        }
        static Vec3 placement(ServerPlayer player,Mob mob,BlockPos center){
            var world=player.level();var candidates=new ArrayList<BlockPos>();
            for(int x=-6;x<=6;x+=2)for(int z=-6;z<=6;z+=2)for(int y=3;y>=-3;y--)candidates.add(center.offset(x,y,z));
            candidates.sort(Comparator.comparingDouble(at->at.distSqr(center)));
            for(var at:candidates){
                var point=Vec3.atBottomCenterOf(at);var room=mob.getBoundingBox().move(point.subtract(mob.position()));
                if(!AgentCompanions.loadedRoom(world,room)||!world.isInWorldBounds(at.above(2))||!world.getWorldBorder().isWithinBounds(at)
                    ||player.position().distanceToSqr(point)<36||!world.getBlockState(at.below()).isCollisionShapeFullBlock(world,at.below())
                    ||!world.getBlockState(at.below()).getFluidState().isEmpty()||!world.getBlockState(at).getFluidState().isEmpty()
                    ||world.getBlockState(at.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                    ||!world.noBlockCollision(mob,room)||!world.getEntities(mob,room,Entity::isAlive).isEmpty())continue;
                return point;
            }return null;
        }
        void died(Entity entity,DamageSource source){
            if(!(entity instanceof Mob mob)||!recorded(mob))return;
            var v=variant(mob);var entry=encounters.entry(ruinKey(mob)).copy();entry.putBoolean("ended",true);encounters.put(ruinKey(mob),entry);loaded.remove(mob.getUUID());
            var credited=mob.getKillCredit();
            if(credited instanceof ServerPlayer p&&progressionAllowed(p)&&p.level()==mob.level()&&p.distanceToSqr(mob)<64*64){
                AstralJournal.killed(p,v);
                if(p.level().getGameRules().get(GameRules.MOB_DROPS))mob.dropFromLootTable(p.level(),source,true,v.loot());
            }
            server.overworld().getDataStorage().scheduleSave();
        }
        void tick(){
            int tick=server.getTickCount();
            // A full terrain chunk can precede native entity tracking, so an
            // immediate terminal removal may never emit ENTITY_UNLOAD.
            for(var mob:List.copyOf(loaded.values()))
                if(mob.isRemoved()||!mob.isAlive()||!recorded(mob))unload(mob);
            if(tick%20==0){
                for(var mob:loaded.values()){
                    int viewers=0;var v=variant(mob);
                    if(mob.getHealth()<mob.getMaxHealth()*.5F&&!mob.entityTags().contains("infinity_astral_ward_spent")){
                        if(v==Variant.RIME){mob.addTag("infinity_astral_ward_spent");mob.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,80,0,false,true));}
                        else if(v==Variant.RIFT&&mob.getTarget()!=null&&mob.getTarget().isAlive()){
                            var away=mob.position().subtract(mob.getTarget().position()).multiply(1,0,1);
                            if(away.lengthSqr()>.01){
                                var point=mob.position().add(away.normalize().scale(3));var feet=BlockPos.containing(point);var world=(ServerLevel)mob.level();
                                var room=mob.getBoundingBox().move(point.subtract(mob.position()));
                                if(AgentCompanions.loadedRoom(world,room)&&world.isInWorldBounds(feet.above())&&world.getWorldBorder().isWithinBounds(feet)
                                    &&world.getBlockState(feet.below()).isCollisionShapeFullBlock(world,feet.below())&&world.getBlockState(feet).getFluidState().isEmpty()
                                    &&world.getBlockState(feet.below()).getFluidState().isEmpty()&&!world.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                                    &&world.noBlockCollision(mob,room)&&world.getEntities(mob,room,Entity::isAlive).isEmpty()){
                                    mob.addTag("infinity_astral_ward_spent");mob.teleportTo(point.x,point.y,point.z);
                                }
                            }
                        }
                    }
                    for(var p:((ServerLevel)mob.level()).getPlayers(q->q.isAlive()&&!q.isSpectator()&&q.distanceToSqr(mob)<32*32,8)){
                        if(++viewers>8)break;
                        p.level().sendParticles(p,v.particle,false,false,mob.getX(),mob.getY()+mob.getBbHeight()*.7,mob.getZ(),2,.25,.2,.25,.01);
                        if(progressionAllowed(p)&&p.distanceToSqr(mob)<24*24)AstralJournal.encountered(p);
                    }
                }
            }
            // GameTests drive individual methods explicitly; they must not invent natural encounters around fixtures.
            if(tick%100!=0||server instanceof net.minecraft.gametest.framework.GameTestServer)return;
            var players=server.getPlayerList().getPlayers();if(players.isEmpty())return;
            var p=players.get(Math.floorMod(playerCursor++,players.size()));if(!progressionAllowed(p))return;
            var ruin=OdysseyJournal.loadedRuin(p.level(),p.blockPosition(),24);if(ruin!=null)spawn(p,ruin);
        }
    }
    static void register(){
        ServerLifecycleEvents.SERVER_STARTED.register(server->{var m=get(server);for(var world:server.getAllLevels())for(var entity:world.getAllEntities())m.load(entity);});
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerEntityEvents.ENTITY_LOAD.register((entity,world)->{if(variant(entity)!=null)get(world.getServer()).load(entity);});
        ServerEntityEvents.ENTITY_UNLOAD.register((entity,world)->{if(variant(entity)!=null)get(world.getServer()).unload(entity);});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(variant(entity)!=null&&entity.level() instanceof ServerLevel world)get(world.getServer()).died(entity,source);});
        ServerTickEvents.END_SERVER_TICK.register(server->get(server).tick());
    }
}

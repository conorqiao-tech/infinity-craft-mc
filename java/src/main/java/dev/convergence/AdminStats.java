package dev.convergence;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;

/** OP4 edits use native state and per-instance extended combat attributes. */
public final class AdminStats {
    static final String STORAGE="admin_stats";
    public record Stat(String id,String label,Item icon,double minimum,double maximum,double step,boolean integer,boolean attribute) {}
    public record Value(double base,double effective,boolean edited) {}
    public record Result(boolean success,String message) {}
    public record Adjustment(String id,double before,double after) {}
    public static final int MAX_XP_LEVEL=maximumXpLevel();
    // Client physics/interaction protocols use these native bounds. Extending them
    // only on the server desynchronizes clients and can make collision queries enormous.
    private static final Set<String> PHYSICS = Set.of("scale", "movement_speed", "flying_speed", "gravity", "jump_strength",
        "step_height", "block_interaction_range", "entity_interaction_range", "movement_efficiency", "water_movement_efficiency", "sneaking_speed", "camera_distance", "luck", "follow_range");
    public static boolean expanded(Stat stat) { return stat.attribute() && !PHYSICS.contains(stat.id()); }
    public static String boundsHint(Stat stat) {
        return capacityId(stat.id())!=null ? "Enter the amount directly. Any needed capacity increase is included in the confirmation. 2 points = 1 heart. Damage still consumes hearts."
            : expanded(stat) ? "Normal attribute cap removed. The menu's number is authoritative; client bars may stop growing."
            : stat.attribute() ? "Minecraft movement, size and interaction limits apply on unmodified clients."
            : stat.id().equals("xp_level") ? "XP must fit Minecraft's total experience integer."
            : "Current values continue to change during gameplay.";
    }
    private static Consumer<ServerPlayerEntity> menuOpener;
    private AdminStats() {}

    public static void setMenuOpener(Consumer<ServerPlayerEntity> opener) {menuOpener=opener;}
    private static RegistryEntry.Reference<EntityAttribute> attribute(String id) {
        var key=Identifier.tryParse(id);
        return key==null?null:Registries.ATTRIBUTE.getEntry(key).orElse(null);
    }
    private static String id(RegistryEntry<EntityAttribute> entry) {
        var key=Registries.ATTRIBUTE.getId(entry.value());
        return key.getNamespace().equals("minecraft")?key.getPath():key.toString();
    }
    private static String key(RegistryEntry<EntityAttribute> entry) {return Registries.ATTRIBUTE.getId(entry.value()).toString();}
    private static NbtCompound originals(LivingEntity target) {
        return target instanceof ServerPlayerEntity player?GameModes.state(player).getCompoundOrEmpty(STORAGE):
            target instanceof AdminStatEntity saved?saved.infinity$adminStats():new NbtCompound();
    }
    private static void writeOriginals(LivingEntity target,NbtCompound records) {
        if(target instanceof ServerPlayerEntity player) {
            if(records.isEmpty())GameModes.state(player).remove(STORAGE);else GameModes.state(player).put(STORAGE,records);
        } else if(target instanceof AdminStatEntity saved)saved.infinity$adminStats(records);
    }
    public static boolean helper(LivingEntity target) {
        if(!(target instanceof IronGolemEntity)||!(target.getEntityWorld() instanceof ServerWorld world)||target.isRemoved())return false;
        var agents=AgentCompanions.get(world.getServer());
        return agents.loaded.get(target.getUuid())==target&&agents.data.agents.containsKey(target.getUuidAsString());
    }
    public static String displayName(LivingEntity target) {
        if(helper(target))return "AI " + AgentCompanions.get(((ServerWorld)target.getEntityWorld()).getServer()).data.agents.get(target.getUuidAsString()).name();
        return target.getName().getString();
    }
    public static List<Stat> list(LivingEntity target) {
        var result=new ArrayList<Stat>();
        result.add(new Stat("health","Current health",Items.RED_DYE,0,target.getMaxHealth(),1,false,false));
        if(target instanceof ServerPlayerEntity player) {
        result.add(new Stat("food","Food",Items.COOKED_BEEF,0,Integer.MAX_VALUE,1,true,false));
        result.add(new Stat("saturation","Saturation",Items.GOLDEN_CARROT,0,Float.MAX_VALUE,1,false,false));
        result.add(new Stat("exhaustion","Exhaustion",Items.ROTTEN_FLESH,0,Float.MAX_VALUE,1,false,false));
        result.add(new Stat("xp_level","XP level",Items.EXPERIENCE_BOTTLE,0,MAX_XP_LEVEL,1,true,false));
        }
        result.add(new Stat("absorption","Absorption hearts",Items.GOLDEN_APPLE,0,target.getMaxAbsorption(),1,false,false));
        Registries.ATTRIBUTE.stream().map(Registries.ATTRIBUTE::getEntry)
            .filter(entry->target.getAttributes().hasAttribute(entry)).sorted(Comparator.comparing(AdminStats::id)).forEach(entry->{
                var attr=entry.value();String name=id(entry);
                double min=attr instanceof ClampedEntityAttribute range?range.getMinValue():-Double.MAX_VALUE;
                double max=attr instanceof ClampedEntityAttribute range?range.getMaxValue():Double.MAX_VALUE;
                if(!PHYSICS.contains(name))max=Float.MAX_VALUE;
                double step=name.contains("speed")||name.contains("gravity")||name.contains("resistance")?.01:name.equals("scale")?.1:1;
                Item icon=name.contains("health")?Items.APPLE:name.contains("attack")?Items.IRON_SWORD:
                    name.contains("armor")?Items.IRON_CHESTPLATE:name.contains("speed")?Items.FEATHER:Items.REDSTONE;
                var stat=new Stat(name,name.equals("max_health")?"Health capacity (max health)":name.equals("max_absorption")?"Absorption capacity":Text.translatable(attr.getTranslationKey()).getString(),icon,min,max,step,false,true);
                if(name.equals("max_health"))result.add(1,stat);else result.add(stat);
            });
        return List.copyOf(result);
    }
    public static Stat find(LivingEntity target,String id) {
        String normalized=id.startsWith("minecraft:")?id.substring(10):id;
        return list(target).stream().filter(stat->stat.id().equals(normalized)).findFirst().orElse(null);
    }
    public static Value value(LivingEntity target,Stat stat) {
        double value;
        if(stat.attribute()) {
            var entry=attribute(stat.id());var instance=entry==null?null:target.getAttributeInstance(entry);
            if(instance==null)return new Value(Double.NaN,Double.NaN,false);
            return new Value(instance.getBaseValue(),instance.getValue(),originals(target).contains(key(entry)));
        }
        if(stat.id().equals("health"))value=target.getHealth();
        else if(stat.id().equals("absorption"))value=target.getAbsorptionAmount();
        else if(target instanceof ServerPlayerEntity player)value=switch(stat.id()) {
            case "food"->player.getHungerManager().getFoodLevel();
            case "saturation"->player.getHungerManager().getSaturationLevel();
            case "exhaustion"->hungerData(player).getFloat("foodExhaustionLevel",0);
            case "xp_level"->player.experienceLevel;default->Double.NaN;
        };
        else value=Double.NaN;
        return new Value(value,value,false);
    }
    public static double original(LivingEntity target,Stat stat) {
        var entry=stat.attribute()?attribute(stat.id()):null;
        return entry==null?Double.NaN:originals(target).getCompoundOrEmpty(key(entry)).getDouble("original",Double.NaN);
    }
    /** The movement base shares vanilla abilities' float precision; menus preview that exact value. */
    public static double normalizedValue(Stat stat,double amount) {
        return stat.attribute()?stat.id().equals("movement_speed")?(double)(float)amount:amount:
            stat.integer()?amount:(double)(float)amount;
    }
    public static String capacityId(String id) {
        return switch(id) {case "health"->"max_health";case "absorption"->"max_absorption";default->null;};
    }
    /** Direct vital entry can grow its capacity; no separate capacity edit is required. */
    public static double inputMaximum(Stat stat) {return capacityId(stat.id())!=null?Float.MAX_VALUE:stat.maximum();}
    public static List<Adjustment> planSet(LivingEntity target,String id,double amount) {
        var stat=find(target,id);
        if(stat==null)throw new IllegalArgumentException("Unknown or unsupported stat: "+id);
        if(!Double.isFinite(amount)||amount<stat.minimum()||amount>inputMaximum(stat)||stat.integer()&&amount!=Math.rint(amount))
            throw new IllegalArgumentException("Use "+(stat.integer()?"a whole number":"a finite number")+" from "+stat.minimum()+" to "+inputMaximum(stat)+" for "+stat.label()+".");
        amount=normalizedValue(stat,amount);
        var changes=new ArrayList<Adjustment>();
        String capacity=capacityId(stat.id());
        if(capacity!=null && amount>stat.maximum()) {
            var limit=find(target,capacity);
            if(limit==null)throw new IllegalArgumentException("This target has no editable capacity for "+stat.label()+".");
            var current=target.getAttributeInstance(attribute(capacity));
            // Evaluate a detached copy so equipment/effect modifiers are preserved and
            // a draft never changes the real entity, even with a negative multiplier.
            var probe=new EntityAttributeInstance(attribute(capacity),ignored->{});
            probe.setFrom(current);((AdminAttribute)probe).infinity$expanded(true);
            double base=Math.max(current.getBaseValue(),amount);
            probe.setBaseValue(base);
            if((float)probe.getValue()<amount) {
                double low=base,high=limit.maximum();probe.setBaseValue(high);
                if((float)probe.getValue()<amount)throw new IllegalArgumentException("Equipment or effects prevent this capacity. Nothing changed.");
                for(int i=0;i<128;i++) {
                    double mid=low+(high-low)/2;probe.setBaseValue(mid);
                    if((float)probe.getValue()>=amount)high=mid;else low=mid;
                }
                base=high;
            }
            changes.add(new Adjustment(capacity,current.getBaseValue(),base));
        }
        changes.add(new Adjustment(stat.id(),value(target,stat).base(),amount));
        return List.copyOf(changes);
    }
    private static boolean online(ServerPlayerEntity player) {
        return player!=null&&player.networkHandler!=null&&player.networkHandler.player==player&&!player.isDisconnected()
            &&!player.isRemoved()&&player.getEntityWorld().getServer().getPlayerManager().getPlayer(player.getUuid())==player;
    }
    static boolean allowedSource(ServerCommandSource source) {
        if(!Memberships.owner(source))return false;
        if(source.getEntity()==null)return true; // Server console and trusted server command sources.
        return source.getEntity() instanceof ServerPlayerEntity player&&online(player)&&Memberships.operator(player)
            &&player.getEntityWorld().getServer()==source.getServer();
    }
    private static boolean transitioning(ServerPlayerEntity player) {
        var uuid=player.getUuid();
        return GameModes.TRANSITIONS.contains(uuid)||GameModes.PENDING.containsKey(uuid)||GameModes.OPERATOR_TRANSFERS.containsKey(uuid);
    }
    /** Null means this exact source and live target may be edited now. */
    public static String accessError(ServerCommandSource source,LivingEntity target) {
        if(!allowedSource(source))return "Only an online OP level 4 admin or the server console can edit stats.";
        if(target==null||!(target.getEntityWorld() instanceof ServerWorld world)||world.getServer()!=source.getServer()
            ||(target instanceof ServerPlayerEntity player?!online(player):!helper(target)))return "That player session or AI helper is no longer available. Select a live player or registered loaded helper.";
        if(!target.isAlive())return "That target is dead. Select a living player or AI helper.";
        if(target instanceof ServerPlayerEntity player&&transitioning(player))return "Finish the target player's mode change before editing stats.";
        if(source.getEntity() instanceof ServerPlayerEntity actor&&(!actor.isAlive()||transitioning(actor)))
            return "Finish respawning or changing mode before editing stats.";
        return null;
    }
    public static Result set(ServerCommandSource actor,LivingEntity target,String id,double amount) {
        String error=accessError(actor,target);if(error!=null)return new Result(false,error);
        var stat=find(target,id);if(stat==null)return new Result(false,"Unknown or unsupported stat: "+id);
        List<Adjustment> plan;
        try {plan=planSet(target,id,amount);}catch(IllegalArgumentException invalid){return new Result(false,invalid.getMessage());}
        if(plan.size()>1) {
            var capacity=plan.getFirst();var result=set(actor,target,capacity.id(),capacity.after());
            if(!result.success())return result;
        }
        amount=plan.getLast().after();
        if(stat.attribute()) {
            var entry=attribute(stat.id());var instance=target.getAttributeInstance(entry);var records=originals(target);
            if(!records.contains(key(entry))) {
                var record=new NbtCompound();record.putDouble("original",instance.getBaseValue());
                if(entry.equals(EntityAttributes.MOVEMENT_SPEED)&&target instanceof ServerPlayerEntity player)record.putFloat("original_walk_speed",player.getAbilities().getWalkSpeed());
                records.put(key(entry),record);
            }
            records.getCompoundOrEmpty(key(entry)).putBoolean("expanded",expanded(stat));
            writeOriginals(target,records);
            ((AdminAttribute)instance).infinity$expanded(expanded(stat));
            // Vanilla loads movement speed from abilities, whose storage is a float.
            if(entry.equals(EntityAttributes.MOVEMENT_SPEED)&&target instanceof ServerPlayerEntity player) {player.getAbilities().setWalkSpeed((float)amount);player.sendAbilitiesUpdate();}
            instance.setBaseValue(amount);clampVitals(target);
        } else {
            if(stat.id().equals("health")) {
                target.setHealth((float)Math.min(amount,target.getMaxHealth()));
                if(amount==0)target.onDeath(target.getDamageSources().genericKill());
            } else if(stat.id().equals("absorption"))target.setAbsorptionAmount((float)amount);
            else if(target instanceof ServerPlayerEntity player) {
                var hunger=player.getHungerManager();
                switch(stat.id()) {
                    case "food"->{hunger.setFoodLevel((int)amount);hunger.setSaturationLevel(Math.min(hunger.getSaturationLevel(),(float)amount));}
                    case "saturation"->hunger.setSaturationLevel((float)amount);
                    case "exhaustion"->{var data=hungerData(player);data.putFloat("foodExhaustionLevel",(float)amount);hunger.readData(NbtReadView.create(ErrorReporter.EMPTY,player.getRegistryManager(),data));}
                    case "xp_level"->{player.setExperienceLevel((int)amount);player.setExperiencePoints(0);player.totalExperience=(int)xpAtLevel((int)amount);}
                }
            }
            if(target instanceof ServerPlayerEntity player)player.markHealthDirty();
        }
        audit(actor,target,"set",stat.id(),amount);
        return new Result(true,displayName(target)+": "+stat.label()+" = "+value(target,stat).base()+(stat.attribute()?" base (effective "+value(target,stat).effective()+").":". Normal gameplay continues."));
    }
    public static Result reset(ServerCommandSource actor,LivingEntity target,String id) {
        String error=accessError(actor,target);if(error!=null)return new Result(false,error);
        var stat=find(target,id);if(stat==null||!stat.attribute())return new Result(false,"Only edited attribute bases have a saved original to restore.");
        double before=original(target,stat);
        if(!validOriginal(target,stat))return new Result(false,"No valid saved original for "+stat.label()+". Nothing changed.");
        restore(target,stat);audit(actor,target,"reset",stat.id(),before);
        return new Result(true,"Restored "+displayName(target)+"'s original "+stat.label()+" base: "+before+". Equipment and potion modifiers are preserved.");
    }
    public static Result resetAll(ServerCommandSource actor,LivingEntity target) {
        String error=accessError(actor,target);if(error!=null)return new Result(false,error);
        var edited=list(target).stream().filter(stat->stat.attribute()&&value(target,stat).edited()).toList();
        for(var stat:edited)if(!validOriginal(target,stat))return new Result(false,"Invalid saved original for "+stat.label()+". Nothing changed.");
        for(var stat:edited)restore(target,stat);
        audit(actor,target,"resetall","attributes",edited.size());
        return new Result(true,"Restored "+edited.size()+" original attribute bases for "+displayName(target)+". Current health, food and XP were not restored.");
    }
    private static void restore(LivingEntity target,Stat stat) {
        var entry=attribute(stat.id());var records=originals(target);var record=records.getCompoundOrEmpty(key(entry));
        ((AdminAttribute)target.getAttributeInstance(entry)).infinity$expanded(false);
        target.getAttributeInstance(entry).setBaseValue(record.getDouble("original",entry.value().getDefaultValue()));
        if(entry.equals(EntityAttributes.MOVEMENT_SPEED)&&target instanceof ServerPlayerEntity player) {player.getAbilities().setWalkSpeed(record.getFloat("original_walk_speed",(float)entry.value().getDefaultValue()));player.sendAbilitiesUpdate();}
        records.remove(key(entry));writeOriginals(target,records);
        clampVitals(target);
    }
    /** Custom metadata loads after native attributes; restore flags before restoring high health. */
    public static void restoreExtended(LivingEntity target,net.minecraft.storage.ReadView view) {
        var records=originals(target);
        for(var stat:list(target))if(stat.attribute()) {
            var entry=attribute(stat.id());var instance=target.getAttributeInstance(entry);
            ((AdminAttribute)instance).infinity$expanded(expanded(stat) && records.getCompoundOrEmpty(key(entry)).getBoolean("expanded",false));
        }
        if(view!=null) {
            float health=view.getFloat("Health",target.getHealth());
            if(Float.isFinite(health))target.setHealth(Math.max(0,Math.min(health,target.getMaxHealth())));
            float absorption=view.getFloat("AbsorptionAmount",target.getAbsorptionAmount());
            if(Float.isFinite(absorption))target.setAbsorptionAmount(Math.max(0,Math.min(absorption,target.getMaxAbsorption())));
        }
        clampVitals(target);
    }
    private static boolean validOriginal(LivingEntity target,Stat stat) {
        double before=original(target,stat);
        if(!Double.isFinite(before)||before<stat.minimum()||before>stat.maximum())return false;
        if(stat.id().equals("movement_speed")&&target instanceof ServerPlayerEntity) {
            double walk=originals(target).getCompoundOrEmpty(key(attribute(stat.id()))).getFloat("original_walk_speed",Float.NaN);
            if(!Double.isFinite(walk)||walk<stat.minimum()||walk>stat.maximum())return false;
        }
        return true;
    }
    private static void clampVitals(LivingEntity target) {
        target.setHealth(Math.min(target.getHealth(),target.getMaxHealth()));
        target.setAbsorptionAmount(Math.min(target.getAbsorptionAmount(),target.getMaxAbsorption()));
        if(target instanceof ServerPlayerEntity player)player.markHealthDirty();
    }
    private static NbtCompound hungerData(ServerPlayerEntity target) {
        var view=NbtWriteView.create(ErrorReporter.EMPTY,target.getRegistryManager());target.getHungerManager().writeData(view);return view.getNbt();
    }
    static long xpAtLevel(int level) {
        long n=level;return n<=16?n*n+6*n:n<=31?(5*n*n-81*n+720)/2:(9*n*n-325*n+4440)/2;
    }
    private static int maximumXpLevel() {
        int low=0,high=100_000;
        while(low+1<high) {int mid=(low+high)/2;if(xpAtLevel(mid)<=Integer.MAX_VALUE)low=mid;else high=mid;}
        return low;
    }
    public static String describe(LivingEntity target) {
        return "Stats for "+displayName(target)+": "+String.join("; ",list(target).stream().map(stat->{var v=value(target,stat);return stat.id()+"="+v.base()+(stat.attribute()?" base / "+v.effective()+" effective":"");}).toList())+". Use /adminstats "+(target instanceof ServerPlayerEntity?"<player>":"ai <entity>")+" set <stat> <value>, reset <stat>, or resetall.";
    }
    private static void audit(ServerCommandSource actor,LivingEntity target,String action,String stat,double amount) {
        System.out.println("[Infinity audit] "+actor.getName()+" adminstats "+action+" "+displayName(target)+" "+stat+" "+amount);
    }
    private static int reply(ServerCommandSource source,Result result) {
        if(result.success())source.sendFeedback(()->Text.literal(result.message()),false);else source.sendError(Text.literal(result.message()));return result.success()?1:0;
    }
    private static LivingEntity commandTarget(CommandContext<ServerCommandSource> ctx,String argument) throws CommandSyntaxException {
        var entity=EntityArgumentType.getEntity(ctx,argument);
        return entity instanceof LivingEntity living&&(!argument.equals("helper")||helper(living))?living:null;
    }
    private static int view(ServerCommandSource source,LivingEntity target) {
        String error=accessError(source,target);return reply(source,new Result(error==null,error==null?describe(target):error));
    }
    private static List<Stat> commandStats(CommandContext<ServerCommandSource> ctx,String argument) throws CommandSyntaxException {
        var target=commandTarget(ctx,argument);return target==null?List.of():list(target);
    }
    private static RequiredArgumentBuilder<ServerCommandSource,EntitySelector> targetCommands(String argument,boolean player) {
        return CommandManager.argument(argument,player?EntityArgumentType.player():EntityArgumentType.entity())
            .executes(ctx->view(ctx.getSource(),commandTarget(ctx,argument)))
            .then(CommandManager.literal("view").executes(ctx->view(ctx.getSource(),commandTarget(ctx,argument))))
            .then(CommandManager.literal("set").then(CommandManager.argument("stat",IdentifierArgumentType.identifier())
                .suggests((ctx,builder)->CommandSource.suggestMatching(commandStats(ctx,argument).stream().map(Stat::id),builder))
                .then(CommandManager.argument("value",DoubleArgumentType.doubleArg()).executes(ctx->reply(ctx.getSource(),set(ctx.getSource(),commandTarget(ctx,argument),IdentifierArgumentType.getIdentifier(ctx,"stat").toString(),DoubleArgumentType.getDouble(ctx,"value")))))))
            .then(CommandManager.literal("reset").then(CommandManager.argument("stat",IdentifierArgumentType.identifier())
                .suggests((ctx,builder)->CommandSource.suggestMatching(commandStats(ctx,argument).stream().filter(Stat::attribute).map(Stat::id),builder))
                .executes(ctx->reply(ctx.getSource(),reset(ctx.getSource(),commandTarget(ctx,argument),IdentifierArgumentType.getIdentifier(ctx,"stat").toString())))))
            .then(CommandManager.literal("resetall").executes(ctx->reply(ctx.getSource(),resetAll(ctx.getSource(),commandTarget(ctx,argument)))));
    }
    public static void register() {
        // Vanilla copies all current bases before COPY_FROM. Keep its movement/ability pair consistent
        // without replaying a stale admin override over later vanilla /attribute changes.
        ServerPlayerEvents.COPY_FROM.register((old,player,alive)->{
            var entry=EntityAttributes.MOVEMENT_SPEED;
            if(originals(old).contains(key(entry))) {
                float speed=(float)player.getAttributeBaseValue(entry);
                player.getAbilities().setWalkSpeed(speed);player.getAttributeInstance(entry).setBaseValue(speed);
                player.sendAbilitiesUpdate();
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(
            CommandManager.literal("adminstats").requires(AdminStats::allowedSource).executes(ctx->{
                var player=ctx.getSource().getPlayer();
                if(player==null)return reply(ctx.getSource(),new Result(true,"Use /adminstats <player> to view or edit an online player's stats."));
                String error=accessError(ctx.getSource(),player);if(error!=null)return reply(ctx.getSource(),new Result(false,error));
                if(menuOpener!=null)menuOpener.accept(player);else ctx.getSource().sendFeedback(()->Text.literal(describe(player)),false);return 1;
            }).then(targetCommands("player",true))
              .then(CommandManager.literal("ai").then(targetCommands("helper",false)))));
    }
}

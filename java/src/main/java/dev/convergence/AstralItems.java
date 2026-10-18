package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Native-use relics; no rank changes, automatic terrain generation, flight grants or client code. */
final class AstralItems {
    static final List<String> PATHS=List.of("starshard_lantern","riftspool","keepers_trowel");
    static final Map<String,Item> BASES=Map.of("starshard_lantern",Items.LANTERN,"riftspool",Items.STRING,"keepers_trowel",Items.IRON_HOE);
    static final Map<UUID,Anchor> ANCHORS=new ConcurrentHashMap<>();
    record Anchor(ServerPlayer player,ServerGamePacketListenerImpl connection,ServerLevel world,GameModes.Mode mode,net.minecraft.world.level.GameType nativeMode,Vec3 at,float yaw,float pitch,int expires){
        boolean valid(ServerPlayer p){return p==player&&p.connection==connection&&p.level()==world&&GameModes.current(p)==mode&&p.gameMode.getGameModeForPlayer()==nativeMode&&operable(p)
            &&world.getServer().getTickCount()<expires&&at.distanceToSqr(p.position())<=64*64&&!p.isPassenger()&&!p.isFallFlying();}
    }
    private AstralItems(){}
    static void registerItems(){
        for(String path:PATHS){
            var id=Identifier.fromNamespaceAndPath("convergence",path);
            var props=new Item.Properties().setId(ResourceKey.create(Registries.ITEM,id)).component(DataComponents.ITEM_NAME,GearNames.text(path)).stacksTo(1);
            if(path.equals("riftspool"))props.durability(128);
            BaseComponents.then(props,BASES.get(path),(builder,defaults)->{
                for(TypedDataComponent<?> component:defaults)if(component.type()!=DataComponents.ITEM_NAME&&component.type()!=DataComponents.ITEM_MODEL
                    &&component.type()!=DataComponents.MAX_STACK_SIZE)BaseComponents.copy(builder,component);
            });
            var item=Registry.register(BuiltInRegistries.ITEM,id,new Relic(props));Convergence.ITEMS.put(id.toString(),item);
        }
    }
    static boolean operable(ServerPlayer p){return ServerMenu.allowed(p)&&BackpackStorage.storageAllowed(p)&&GameModes.of(p.level())==GameModes.current(p)
        &&p.containerMenu==p.inventoryMenu&&p.inventoryMenu.getCarried().isEmpty();}
    static String held(ServerPlayer p){String id=Convergence.id(p.getMainHandItem());return id.startsWith("convergence:")?id.substring(12):"";}
    static int plainSlot(ServerPlayer p,Item item){for(int i=0;i<36;i++)if(ItemStack.isSameItemSameComponents(p.getInventory().getItem(i),new ItemStack(item)))return i;return -1;}
    static int use(ServerPlayer p){
        if(!operable(p))return 0;String path=held(p);if(!PATHS.contains(path))return 0;
        var stack=p.getMainHandItem();if((p.getCooldowns().isOnCooldown(stack)&&!Memberships.unlimitedGameplay(p))){CommunityServer.say(p,"This Astral tool is recharging.");return 0;}
        if(path.equals("starshard_lantern"))return lantern(p);
        if(path.equals("riftspool"))return spool(p);
        if(!loadedAim(p))return 0;var hit=p.pick(6,1,false);return hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK?trowel(p,b.getBlockPos()):0;
    }
    static boolean loadedAim(ServerPlayer p){return AgentCompanions.loadedRoom(p.level(),new AABB(p.getEyePosition(),p.getEyePosition().add(p.getViewVector(1).scale(6))).inflate(.1));}
    static int lantern(ServerPlayer p){
        if(!operable(p)||!held(p).equals("starshard_lantern")||(p.getCooldowns().isOnCooldown(p.getMainHandItem())&&!Memberships.unlimitedGameplay(p)))return 0;
        Convergence.effect(p,MobEffects.NIGHT_VISION,0,300);
        var guardian=AstralWilds.get(p.level().getServer()).nearest(p,64);
        if(guardian==null)CommunityServer.say(p,"Starshard Lantern • fifteen seconds of night sight. No loaded Astral guardian within 64 blocks.");
        else{
            var start=p.position().add(0,1,0);var direction=guardian.getBoundingBox().getCenter().subtract(start).normalize();
            for(int i=1;i<=8;i++){var at=start.add(direction.scale(i*.7));p.level().sendParticles(p,ParticleTypes.END_ROD,false,false,at.x,at.y,at.z,1,.02,.02,.02,0);}
            CommunityServer.say(p,"Starshard Lantern • a trail points toward "+AstralWilds.variant(guardian).name+". It is a bearing, not a safe path.");
        }
        p.getCooldowns().addCooldown(p.getMainHandItem(),200);AstralJournal.used(p,"starshard_lantern");return 1;
    }
    static boolean safeAnchor(ServerPlayer p,Anchor a){
        var feet=BlockPos.containing(a.at());var room=p.getBoundingBox().move(a.at().subtract(p.position()));var w=a.world();
        if(!AgentCompanions.loadedRoom(w,room)||!w.isInWorldBounds(feet.above(2))||!w.getWorldBorder().isWithinBounds(room)
            ||!w.getBlockState(feet.below()).isCollisionShapeFullBlock(w,feet.below())||!w.getBlockState(feet.below()).getFluidState().isEmpty()
            ||w.getBlockState(feet.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)||!w.noBlockCollision(p,room)
            ||!w.getEntities(p,room,Entity::isAlive).isEmpty())return false;
        for(int x=(int)Math.floor(room.minX);x<=(int)Math.floor(room.maxX);x++)for(int y=(int)Math.floor(room.minY);y<=(int)Math.floor(room.maxY);y++)for(int z=(int)Math.floor(room.minZ);z<=(int)Math.floor(room.maxZ);z++)
            if(!w.getBlockState(new BlockPos(x,y,z)).getFluidState().isEmpty())return false;
        return true;
    }
    static int spool(ServerPlayer p){
        if(!operable(p)||!held(p).equals("riftspool")||(p.getCooldowns().isOnCooldown(p.getMainHandItem())&&!Memberships.unlimitedGameplay(p)))return 0;
        var previous=ANCHORS.get(p.getUUID());
        if(previous==null){
            var anchor=new Anchor(p,p.connection,p.level(),GameModes.current(p),p.gameMode.getGameModeForPlayer(),p.position(),p.getYRot(),p.getXRot(),p.level().getServer().getTickCount()+400);
            if(p.isPassenger()||p.isFallFlying()||!safeAnchor(p,anchor)){CommunityServer.say(p,"Stand on clear, solid ground before setting a Riftspool anchor.");return 0;}
            ANCHORS.put(p.getUUID(),anchor);p.getCooldowns().addCooldown(p.getMainHandItem(),10);
            CommunityServer.say(p,"Riftspool anchored for twenty seconds. Use again within 64 blocks to return; one plain ender pearl is consumed.");return 1;
        }
        if(!previous.valid(p)||!safeAnchor(p,previous)){
            ANCHORS.remove(p.getUUID(),previous);CommunityServer.say(p,"The old Riftspool anchor expired, became obstructed or changed session. Nothing was consumed.");return 0;
        }
        int pearl=plainSlot(p,Items.ENDER_PEARL);if(pearl<0){CommunityServer.say(p,"Returning needs one plain ender pearl in your inventory. Your anchor remains until it expires.");return 0;}
        var originalWorld=p.level();
        if(!p.teleportTo(previous.world(),previous.at().x,previous.at().y,previous.at().z,Set.of(),previous.yaw(),previous.pitch(),false))return 0;
        // Native teleport and consumption run synchronously on the same server thread, after every safety check.
        if(p.level()!=originalWorld)throw new IllegalStateException("Riftspool crossed a dimension unexpectedly");
        p.getInventory().getItem(pearl).shrink(1);p.getMainHandItem().hurtAndBreak(1,p,InteractionHand.MAIN_HAND);
        ANCHORS.remove(p.getUUID(),previous);p.getCooldowns().addCooldown(Identifier.parse("convergence:riftspool"),100);
        p.inventoryMenu.sendAllDataToRemote();PlayerPersistence.save(p);AstralJournal.used(p,"riftspool");
        CommunityServer.say(p,"Riftspool returned you to your checked anchor. One ender pearl was consumed.");return 1;
    }
    static int trowel(ServerPlayer p,BlockPos at){
        if(!operable(p)||!p.mayBuild()||!held(p).equals("keepers_trowel")||(p.getCooldowns().isOnCooldown(p.getMainHandItem())&&!Memberships.unlimitedGameplay(p)))return 0;
        var w=p.level();if(!w.isInWorldBounds(at)||!w.isLoaded(at)||!w.getWorldBorder().isWithinBounds(at)||!w.mayInteract(p,at)||!p.mayInteract(w,at)
            ||w.getBlockEntity(at)!=null||p.getEyePosition().distanceToSqr(Vec3.atCenterOf(at))>49)return 0;
        if(!loadedAim(p))return 0;var hit=p.pick(6,1,false);if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK||!b.getBlockPos().equals(at))return 0;
        if(!p.mayUseItemAt(at,b.getDirection(),p.getMainHandItem()))return 0;
        var before=w.getBlockState(at);if(!(before.getBlock() instanceof CropBlock crop)||crop.getAge(before)>=crop.getMaxAge())return 0;
        int meal=plainSlot(p,Items.BONE_MEAL);if(meal<0){CommunityServer.say(p,"Keeper's Trowel needs plain bone meal in your inventory. Aim at a growing crop.");return 0;}
        if(!BoneMealItem.growCrop(p.getInventory().getItem(meal),w,at))return 0;
        p.getMainHandItem().hurtAndBreak(1,p,InteractionHand.MAIN_HAND);p.getCooldowns().addCooldown(Identifier.parse("convergence:keepers_trowel"),20);
        w.levelEvent(1505,at,15);p.inventoryMenu.sendAllDataToRemote();PlayerPersistence.save(p);AstralJournal.used(p,"keepers_trowel");return 1;
    }
    static List<ItemStack> kit(){return PATHS.stream().map(s->new ItemStack(Convergence.ITEMS.get("convergence:"+s))).toList();}
    static int creativeKit(ServerPlayer p){
        if(!operable(p)||!ServerMenu.gearAllowed(p))return 0;var items=kit();var slots=new ArrayList<Integer>();
        for(int i=0;i<36;i++)if(p.getInventory().getItem(i).isEmpty())slots.add(i);if(slots.size()<items.size())return 0;
        for(int i=0;i<items.size();i++)p.getInventory().setItem(slots.get(i),items.get(i).copy());
        PlayerPersistence.save(p);p.inventoryMenu.sendAllDataToRemote();return items.size();
    }
    static void registerLifecycle(){
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{var a=ANCHORS.get(handler.player.getUUID());if(a!=null&&a.connection()==handler)ANCHORS.remove(handler.player.getUUID(),a);});
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((p,from,to)->ANCHORS.remove(p.getUUID()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(entity instanceof ServerPlayer p)ANCHORS.remove(p.getUUID());});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->ANCHORS.entrySet().removeIf(e->e.getValue().world().getServer()==server));
        ServerTickEvents.END_SERVER_TICK.register(server->ANCHORS.entrySet().removeIf(e->e.getValue().world().getServer()==server&&!e.getValue().valid(e.getValue().player())));
    }
    private static final class Relic extends Item {
        Relic(Properties properties){super(properties);}
        @Override public InteractionResult use(Level world,Player player,InteractionHand hand){if(hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;if(player instanceof ServerPlayer p)AstralItems.use(p);return InteractionResult.SUCCESS;}
        @Override public InteractionResult useOn(UseOnContext context){if(context.getHand()!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;if(context.getPlayer() instanceof ServerPlayer p){if(held(p).equals("keepers_trowel"))trowel(p,context.getClickedPos());else AstralItems.use(p);}return InteractionResult.SUCCESS;}
        @Override public void onCraftedBy(ItemStack stack,Player player){super.onCraftedBy(stack,player);if(player instanceof ServerPlayer p)AstralJournal.crafted(p,Convergence.id(stack));}
    }
}

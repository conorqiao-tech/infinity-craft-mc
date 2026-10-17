package dev.convergence;

import java.util.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Circuit Keepers' Commons. No calendar commands, generated terrain or external AI access. */
public final class CommonsEvents {
    record Food(String name, Item item, int goal) {}
    static final List<Food> FOODS=List.of(new Food("Wheat",Items.WHEAT,32),new Food("Carrots",Items.CARROT,16),
        new Food("Potatoes",Items.POTATO,16),new Food("Beetroot",Items.BEETROOT,16),new Food("Bread",Items.BREAD,8),
        new Food("Baked potatoes",Items.BAKED_POTATO,8),new Food("Cookies",Items.COOKIE,8),new Food("Pumpkin pie",Items.PUMPKIN_PIE,4));
    static final String KEY="circuit_commons", SAMPLE="commons_test_sample";
    record Quote(ServerPlayer player,ServerGamePacketListenerImpl connection,ServerLevel world,GameModes.Mode mode,
                 int food,int amount,int previous,List<ItemStack> inventory) {}
    record Trial(ServerPlayer player,ServerGamePacketListenerImpl connection,ServerLevel world,BlockPos origin,String token,int expires) {}
    static final Map<UUID,Trial> TRIALS=new HashMap<>();
    static final Map<ServerPlayer,String> TAB_NAMES=new WeakHashMap<>();
    private CommonsEvents() {}

    static CompoundTag state(ServerPlayer p){
        var root=GameModes.state(p);var tag=root.getCompound(KEY).orElse(null);
        if(tag==null){tag=new CompoundTag();root.put(KEY,tag);}return tag;
    }
    static int donated(ServerPlayer p,int i){return Math.clamp(state(p).getIntOr("food_"+i,0),0,FOODS.get(i).goal());}
    static int contribution(ServerPlayer p){int n=0;for(int i=0;i<FOODS.size();i++)n+=donated(p,i);return n;}
    static boolean emptyScreen(ServerPlayer p){return ServerMenu.allowed(p)&&p.containerMenu==p.inventoryMenu&&p.inventoryMenu.getCarried().isEmpty();}
    static boolean harvestAllowed(ServerPlayer p){
        var mode=GameModes.current(p);
        return emptyScreen(p)&&!p.isCreative()&&(mode==GameModes.Mode.SURVIVAL||mode==GameModes.Mode.HARDCORE)&&GameModes.of(p.level())==mode;
    }
    static void say(ServerPlayer p,String text){CommunityServer.say(p,text);}

    /** A derived, monotonic index of saved personal contributions; never an inventory or claim authority. */
    static final class Pantry extends SavedData {
        static final SavedDataType<Pantry> TYPE=new SavedDataType<>(Identifier.parse("convergence:commons_pantry"),Pantry::new,
            CompoundTag.CODEC.xmap(Pantry::new,p->p.data),null);
        final CompoundTag data;
        Pantry(){this(new CompoundTag());} Pantry(CompoundTag data){this.data=data;}
        int total(int i){return Math.clamp(data.getIntOr("total_"+i,0),0,FOODS.get(i).goal());}
        boolean complete(){for(int i=0;i<FOODS.size();i++)if(total(i)<FOODS.get(i).goal())return false;return true;}
        void sync(ServerPlayer p){
            String key=p.getStringUUID();var previous=data.getCompoundOrEmpty(key);boolean changed=false;
            for(int i=0;i<FOODS.size();i++){
                int old=Math.clamp(previous.getIntOr("food_"+i,0),0,FOODS.get(i).goal()),now=donated(p,i);
                if(now>old){data.putInt("total_"+i,Math.min(FOODS.get(i).goal(),total(i)+now-old));previous.putInt("food_"+i,now);changed=true;}
            }
            if(changed){data.put(key,previous);setDirty();}
        }
    }
    static Pantry pantry(ServerPlayer p){return p.level().getServer().overworld().getDataStorage().computeIfAbsent(Pantry.TYPE);}

    static Quote quote(ServerPlayer p,int food){
        if(!harvestAllowed(p)||food<0||food>=FOODS.size())return null;
        int available=0;var snapshot=new ArrayList<ItemStack>();
        for(int i=0;i<36;i++){var s=p.getInventory().getItem(i);snapshot.add(s.copy());if(plain(s,food))available+=s.getCount();}
        int amount=Math.min(available,FOODS.get(food).goal()-donated(p,food));
        return amount<=0?null:new Quote(p,p.connection,p.level(),GameModes.current(p),food,amount,donated(p,food),List.copyOf(snapshot));
    }
    static boolean plain(ItemStack stack,int food){return ItemStack.isSameItemSameComponents(stack,new ItemStack(FOODS.get(food).item()));}
    static int donate(ServerPlayer p,Quote q){
        if(q==null||q.player()!=p||q.connection()!=p.connection||q.world()!=p.level()||q.mode()!=GameModes.current(p)||!harvestAllowed(p))return 0;
        if(donated(p,q.food())!=q.previous())return 0;
        for(int i=0;i<36;i++){
            var a=q.inventory().get(i);var b=p.getInventory().getItem(i);
            if(a.getCount()!=b.getCount()||!ItemStack.isSameItemSameComponents(a,b)){say(p,"Your inventory changed. Review this delivery again; nothing was taken.");return 0;}
        }
        int remaining=q.amount();
        for(int i=0;i<36&&remaining>0;i++){var stack=p.getInventory().getItem(i);if(plain(stack,q.food())){int n=Math.min(stack.getCount(),remaining);stack.shrink(n);remaining-=n;}}
        state(p).putInt("food_"+q.food(),q.previous()+q.amount());
        // Inventory and the authoritative personal receipt share one native player save.
        p.inventoryMenu.sendAllDataToRemote();PlayerPersistence.save(p);
        pantry(p).sync(p);p.level().getServer().overworld().getDataStorage().scheduleSave();
        say(p,"Pantry delivery saved: "+q.amount()+" "+FOODS.get(q.food()).name()+". Thank you, Keeper!");return q.amount();
    }

    static BlockPos anchor(ServerPlayer p){var s=state(p);return new BlockPos(s.getIntOr("x",0),s.getIntOr("y",0),s.getIntOr("z",0));}
    static boolean workshopAllowed(ServerPlayer p){
        var at=anchor(p);
        return CreativeStudio.allowed(p)&&state(p).getStringOr("workshop_world","").equals(p.level().dimension().identifier().toString())
            &&p.position().distanceToSqr(Vec3.atCenterOf(at))<=24*24
            &&AgentCompanions.loadedRoom(p.level(),new AABB(Vec3.atLowerCornerOf(at.offset(-4,-1,-4)),Vec3.atLowerCornerOf(at.offset(5,5,5))))
            &&p.level().getWorldBorder().isWithinBounds(new AABB(Vec3.atLowerCornerOf(at.offset(-4,-1,-4)),Vec3.atLowerCornerOf(at.offset(5,5,5))));
    }
    static int setWorkshop(ServerPlayer p,BlockPos at){
        if(!emptyScreen(p)||!CreativeStudio.allowed(p)||p.position().distanceToSqr(Vec3.atCenterOf(at))>64)return 0;
        stopTrial(p);var s=state(p);s.putString("workshop_world",p.level().dimension().identifier().toString());
        s.putInt("x",at.getX());s.putInt("y",at.getY());s.putInt("z",at.getZ());PlayerPersistence.save(p);
        say(p,"Workshop registered at "+at.toShortString()+". Open Builder Exchange for the circuit and delivery coordinates.");return 1;
    }
    static int kit(ServerPlayer p){
        if(!emptyScreen(p)||!CreativeStudio.allowed(p))return 0;
        return CreativeStudio.deliver(p,List.of(new ItemStack(Items.REDSTONE_BLOCK),new ItemStack(Items.REDSTONE,2),
            new ItemStack(Items.REDSTONE_LAMP),new ItemStack(Items.HOPPER),new ItemStack(Items.BARREL)));
    }
    static boolean signal(ServerPlayer p,BlockPos a){
        return p.level().getBlockState(a.offset(-3,0,0)).is(Blocks.REDSTONE_BLOCK)
            &&poweredWire(p.level().getBlockState(a.offset(-2,0,0)))&&poweredWire(p.level().getBlockState(a.offset(-1,0,0)))
            &&p.level().getBlockState(a).is(Blocks.REDSTONE_LAMP)&&p.level().getBlockState(a).getValue(RedstoneLampBlock.LIT);
    }
    static boolean poweredWire(BlockState s){return s.is(Blocks.REDSTONE_WIRE)&&s.getValue(RedstoneWireBlock.POWER)>0;}
    static int inspectSignal(ServerPlayer p){
        if(!emptyScreen(p)||!workshopAllowed(p))return 0;
        if(!signal(p,anchor(p))){say(p,"Circuit is not powered. Follow the four coordinates in your Exchange menu, including solid blocks below the dust.");return 0;}
        state(p).putBoolean("signal",true);PlayerPersistence.save(p);say(p,"Signal verified! Now build a downward hopper above the barrel for the live delivery trial.");return 1;
    }
    static boolean deliveryShape(ServerLevel w,BlockPos a){
        var h=w.getBlockState(a.offset(3,1,0));
        return h.is(Blocks.HOPPER)&&h.getValue(HopperBlock.FACING)==Direction.DOWN&&h.getValue(HopperBlock.ENABLED)
            &&w.getBlockState(a.offset(3,0,0)).is(Blocks.BARREL);
    }
    static Container container(ServerLevel w,BlockPos pos){return w.getBlockEntity(pos) instanceof Container c?c:null;}
    static int startTrial(ServerPlayer p){
        if(!emptyScreen(p)||!workshopAllowed(p)||TRIALS.containsKey(p.getUUID()))return 0;
        var a=anchor(p);var input=container(p.level(),a.offset(3,1,0));var output=container(p.level(),a.offset(3,0,0));
        if(!signal(p,a)||!deliveryShape(p.level(),a)||input==null||output==null||!input.isEmpty()||!output.isEmpty()){
            say(p,"Keep the lamp lit and place an enabled DOWN hopper over an EMPTY barrel. Empty both containers before testing.");return 0;
        }
        String token=UUID.randomUUID().toString();var sample=new ItemStack(Items.PAPER);var tag=new CompoundTag();tag.putString(SAMPLE,token);
        sample.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));sample.set(DataComponents.CUSTOM_NAME,Component.literal("Exchange test parcel • no trade value"));
        input.setItem(0,sample);input.setChanged();
        TRIALS.put(p.getUUID(),new Trial(p,p.connection,p.level(),a,token,p.level().getServer().getTickCount()+200));
        say(p,"Trial running: the real hopper must deliver its sealed parcel while your circuit stays powered. Stay near the workshop.");return 1;
    }
    static boolean sample(ItemStack item,String token){var data=item.get(DataComponents.CUSTOM_DATA);return data!=null&&data.copyTag().getStringOr(SAMPLE,"").equals(token);}
    static void clean(Trial t){
        for(var at:List.of(t.origin().offset(3,1,0),t.origin().offset(3,0,0))){
            if(!t.world().isLoaded(at))continue;var c=container(t.world(),at);if(c==null)continue;
            for(int i=0;i<c.getContainerSize();i++)if(sample(c.getItem(i),t.token()))c.setItem(i,ItemStack.EMPTY);c.setChanged();
        }
    }
    static void stopTrial(ServerPlayer p){var t=TRIALS.get(p.getUUID());if(t!=null&&t.player()==p&&TRIALS.remove(p.getUUID(),t))clean(t);}
    static void tickTrial(Trial t){
        var p=t.player();
        if(p.connection!=t.connection()||p.level()!=t.world()||!workshopAllowed(p)||!anchor(p).equals(t.origin())
            ||p.level().getServer().getTickCount()>=t.expires()||!signal(p,t.origin())||!deliveryShape(t.world(),t.origin())){
            stopTrial(p);say(p,"Exchange trial stopped. Your saved milestones remain; fix the circuit and retry.");return;
        }
        var out=container(t.world(),t.origin().offset(3,0,0));
        if(out!=null)for(int i=0;i<out.getContainerSize();i++)if(sample(out.getItem(i),t.token())){
            stopTrial(p);state(p).putBoolean("signal",true);state(p).putBoolean("delivery",true);PlayerPersistence.save(p);
            say(p,"Live delivery passed! Claim your DevDay Builder toolkit and circuit banner in Builder Exchange.");return;
        }
    }

    static ItemStack named(Item item,int count,String name,String... lore){
        var stack=new ItemStack(item,count);stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        stack.set(DataComponents.LORE,new ItemLore(Arrays.stream(lore).map(Component::literal).map(c->(Component)c).toList()));return stack;
    }
    static ItemStack banner(ServerPlayer p,boolean harvest){
        var stack=named(harvest?Items.BANNER.green():Items.BANNER.cyan(),1,harvest?"Commons • Golden Harvest":"Commons • Circuit Exchange","Original Circuit Keepers' banner; earned in the Commons.");
        var reg=p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        stack.set(DataComponents.BANNER_PATTERNS,new BannerPatternLayers.Builder()
            .add(reg.getOrThrow(BannerPatterns.STRIPE_BOTTOM),harvest?DyeColor.BROWN:DyeColor.BLACK)
            .add(reg.getOrThrow(BannerPatterns.RHOMBUS_MIDDLE),harvest?DyeColor.YELLOW:DyeColor.WHITE)
            .add(reg.getOrThrow(BannerPatterns.STRIPE_CENTER),harvest?DyeColor.LIME:DyeColor.CYAN).build());return stack;
    }
    static boolean earned(ServerPlayer p,int gift){return switch(gift){
        case 0->state(p).getBooleanOr("signal",false)&&state(p).getBooleanOr("delivery",false);
        case 1->contribution(p)>=16;
        case 2->contribution(p)>=64&&pantry(p).complete();
        case 3->PlayerWorkshop.stamps(p)==8;default->false;};}
    static List<ItemStack> reward(ServerPlayer p,int gift){return switch(gift){
        case 0->List.of(banner(p,false),named(Items.DIAMOND_PICKAXE,1,"Exchange Engineer's Pickaxe"),new ItemStack(Items.REDSTONE,16));
        case 1->List.of(named(Items.IRON_HOE,1,"Commons Field Hoe"),new ItemStack(Items.BONE_MEAL,16),jar(p));
        case 2->List.of(banner(p,true),named(Items.DIAMOND_HOE,1,"Harvest Guardian's Hoe"),new ItemStack(Items.GOLDEN_CARROT,16));
        case 3->List.of(named(Items.COMPASS,1,"Wayfinder's Compass","Native compass: points to world spawn in the Overworld."),banner(p,false),new ItemStack(Items.SPYGLASS));
        default->List.of();};}
    static int claim(ServerPlayer p,int gift){
        if(!emptyScreen(p)||gift<0||gift>3||!earned(p,gift)||state(p).getBooleanOr("gift_"+gift,false)
            ||(gift==0?!CreativeStudio.allowed(p):!harvestAllowed(p)))return 0;
        var items=reward(p,gift);var slots=new ArrayList<Integer>();for(int i=0;i<36;i++)if(p.getInventory().getItem(i).isEmpty())slots.add(i);
        if(slots.size()<items.size()){say(p,"Clear "+items.size()+" inventory slots; your gift remains unclaimed.");return 0;}
        for(int i=0;i<items.size();i++)p.getInventory().setItem(slots.get(i),items.get(i).copy());
        state(p).putBoolean("gift_"+gift,true);if(gift!=1)state(p).putInt("title",gift==0?1:gift==2?2:3);
        PlayerPersistence.save(p);p.inventoryMenu.sendAllDataToRemote();refreshTitle(p);
        say(p,"Commons gift saved! Your earned title can be worn from the event menu.");return items.size();
    }
    static String title(ServerPlayer p){return switch(state(p).getIntOr("title",0)){
        case 1->state(p).getBooleanOr("gift_0",false)?"DevDay Builder":"";
        case 2->state(p).getBooleanOr("gift_2",false)?"Harvest Guardian":"";
        case 3->state(p).getBooleanOr("gift_3",false)?"Wayfinder":"";default->"";};}
    public static Component tabName(ServerPlayer p,Component previous){String title=title(p);return title.isEmpty()?previous:(previous==null?p.getDisplayName():previous).copy().append(Component.literal(" • "+title));}
    static String tabText(ServerPlayer p){var name=p.getTabListDisplayName();return name==null?"":name.getString();}
    static void refreshTitle(ServerPlayer p){
        TAB_NAMES.put(p,tabText(p));
        p.level().getServer().getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,p));
    }
    static void refreshTitleIfChanged(ServerPlayer p){
        if(title(p).isEmpty()&&!TAB_NAMES.containsKey(p))return;
        if(!Objects.equals(TAB_NAMES.get(p),tabText(p)))refreshTitle(p);
    }
    static void title(ServerPlayer p,int title){
        if(!emptyScreen(p))return;
        if(title<0||title>3||title==1&&!state(p).getBooleanOr("gift_0",false)||title==2&&!state(p).getBooleanOr("gift_2",false)||title==3&&!state(p).getBooleanOr("gift_3",false)){
            say(p,"Claim the matching event or Atlas gift before wearing that title.");return;
        }
        state(p).putInt("title",title);PlayerPersistence.save(p);refreshTitle(p);
    }

    static BlockState original(String id){return ((BlockItem)Convergence.ITEMS.get("convergence:"+id)).getBlock().defaultBlockState();}
    /** Two independently composed small blueprints. Placement reuses Studio's exact preview/empty-footprint/undo protections. */
    static Map<BlockPos,BlockState> blueprint(BlockPos at,boolean garden){
        var out=new LinkedHashMap<BlockPos,BlockState>();
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)out.put(at.offset(x,0,z),original(garden?"verdant_mosaic":"copper_circuit"));
        for(int x:new int[]{-4,4})for(int z:new int[]{-4,4}){
            for(int y=1;y<=3;y++)out.put(at.offset(x,y,z),original(garden?"moonstone":"obsidian_lattice"));
            out.put(at.offset(x,4,z),original("sunstone_lamp"));
        }
        for(int x=-4;x<=4;x++){out.put(at.offset(x,4,-4),original("aurora_tiles"));out.put(at.offset(x,4,4),original("aurora_tiles"));}
        if(garden){
            out.put(at,Blocks.WATER.defaultBlockState());
            Block[] crops={Blocks.WHEAT,Blocks.CARROTS,Blocks.POTATOES,Blocks.BEETROOTS};int i=0;
            for(int x:new int[]{-1,1})for(int z:new int[]{-1,1}){
                out.put(at.offset(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                out.put(at.offset(x,1,z),crops[i++].defaultBlockState());
            }
        }
        return Map.copyOf(out);
    }
    static int preview(ServerPlayer p,boolean garden){
        if(!CreativeStudio.allowed(p)||!emptyScreen(p))return 0;
        CreativeStudio.settings(p).putInt("stamp",(garden?CreativeStudio.Stamp.HARVEST_GARDEN:CreativeStudio.Stamp.EXCHANGE).ordinal());
        return CreativeStudio.preview(p);
    }
    static void built(ServerPlayer p,CreativeStudio.Preview preview,int changed){
        if(changed==preview.blocks().size()&&preview.blocks().equals(blueprint(preview.origin(),false)))setWorkshop(p,preview.origin().above());
    }
    static ItemStack jar(ServerPlayer p){
        var jar=named(Items.GLASS_BOTTLE,1,"Seedlight Jar","Use to release an emerald-and-gold firefly spiral.","Personal Harvest Together keepsake; no combat effects.");
        var tag=new CompoundTag();tag.putString("seedlight_owner",p.getStringUUID());jar.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return jar;
    }
    static boolean seedlight(ServerPlayer p,ItemStack jar){
        var data=jar.get(DataComponents.CUSTOM_DATA);
        return jar.is(Items.GLASS_BOTTLE)&&data!=null&&data.copyTag().getStringOr("seedlight_owner","").equals(p.getStringUUID())&&state(p).getBooleanOr("gift_1",false);
    }
    static int releaseSeedlight(ServerPlayer p,ItemStack jar){
        if(!ServerMenu.allowed(p)||!seedlight(p,jar)||!Convergence.ready(p,"seedlight_jar",40))return 0;
        for(int i=0;i<24;i++){double angle=i*Math.PI/6,rad=.35+i/40.0;
            p.level().sendParticles(i%2==0?ParticleTypes.HAPPY_VILLAGER:ParticleTypes.END_ROD,p.getX()+Math.cos(angle)*rad,p.getY()+.2+i/15.0,p.getZ()+Math.sin(angle)*rad,1,0,0,0,0);
        }return 1;
    }
    static void register(){
        CommonsMenu.register();
        UseItemCallback.EVENT.register((player,world,hand)->{
            if(player instanceof ServerPlayer p&&seedlight(p,p.getItemInHand(hand))){releaseSeedlight(p,p.getItemInHand(hand));return InteractionResult.SUCCESS;}
            return InteractionResult.PASS;
        });
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->pantry(handler.player).sync(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{stopTrial(handler.player);TAB_NAMES.remove(handler.player);});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{for(var t:List.copyOf(TRIALS.values()))if(t.world().getServer()==server){clean(t);TRIALS.remove(t.player().getUUID(),t);}});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->TAB_NAMES.keySet().removeIf(p->p.level().getServer()==server));
        ServerTickEvents.END_SERVER_TICK.register(server->{
            for(var t:List.copyOf(TRIALS.values()))if(t.world().getServer()==server)tickTrial(t);
            if(server.getTickCount()%40==0)for(var p:server.getPlayerList().getPlayers())if(ServerMenu.allowed(p)&&!title(p).isEmpty())
                p.level().sendParticles(title(p).equals("Harvest Guardian")?ParticleTypes.HAPPY_VILLAGER:ParticleTypes.END_ROD,p.getX(),p.getY()+1,p.getZ(),3,.3,.4,.3,0);
        });
    }
}

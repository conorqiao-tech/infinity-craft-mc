package dev.convergence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterials;
import net.minecraft.world.item.equipment.trim.TrimPatterns;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Native items and chest controls keep the Studio usable through Geyser. */
final class CreativeStudio {
    enum Brush { PLANE, LINE, SPHERE, CUBE, RING }
    enum Stamp { ARCH, PAVILION, TREE }
    record Palette(String name, Block wall, Block accent, Block light) {}
    static final List<Palette> PALETTES = List.of(
        new Palette("Aurora", Blocks.QUARTZ_BLOCK, Blocks.CONCRETE.cyan(), Blocks.SEA_LANTERN),
        new Palette("Ember", Blocks.POLISHED_BLACKSTONE, Blocks.RED_NETHER_BRICKS, Blocks.SHROOMLIGHT),
        new Palette("Sakura", Blocks.CHERRY_PLANKS, Blocks.CONCRETE.pink(), Blocks.PEARLESCENT_FROGLIGHT),
        new Palette("Clockwork", Blocks.RAW_COPPER_BLOCK, Blocks.DEEPSLATE_BRICKS, Blocks.OCHRE_FROGLIGHT),
        new Palette("Frost", Blocks.PACKED_ICE, Blocks.CONCRETE.blue(), Blocks.SEA_LANTERN));
    record Change(BlockPos pos, BlockState before, BlockState after) {}
    record Edit(ServerPlayer owner, ServerGamePacketListenerImpl connection, ServerLevel world, List<Change> changes) {}
    record Preview(ServerPlayer owner, ServerGamePacketListenerImpl connection, ServerLevel world,
                   BlockPos origin, Map<BlockPos, BlockState> blocks, int expires) {}
    static final Map<UUID, Edit> UNDO = new HashMap<>();
    static final Map<UUID, Preview> PREVIEWS = new HashMap<>();
    static final int BRUSH=10, RADIUS=11, PALETTE=12, BUILDER=14, SCULPTOR=15, KIT=16;
    static final int STAMP=19, PREVIEW=20, UNDO_BUTTON=22, BLINK=23, PARTY=24, TRAIL=25;
    static final int PLANE=28, HOVER=30, SPEED=32, RECALL=34, REMOTE=29;
    static final int STORM=37, EMBER=39, SAKURA=41, CREATIVE=43, BACK=49, CONFIRM=11, CANCEL=15;
    static final int BLUEPRINT_WAND=46, STORM_STAFF=48;

    private CreativeStudio() {}

    static boolean allowed(ServerPlayer p) {
        return ServerMenu.allowed(p) && p.isCreative() && p.mayBuild()
            && (GameModes.of(p.level()) == GameModes.Mode.CREATIVE || GameModes.operator(p));
    }
    static CompoundTag settings(ServerPlayer p) {
        var state=GameModes.state(p);
        var settings=state.getCompound("creative_studio").orElse(null);
        if(settings==null){settings=new CompoundTag();state.put("creative_studio",settings);}
        return settings;
    }
    static int option(ServerPlayer p,String key,int size){return Math.floorMod(settings(p).getIntOr(key,0),size);}
    static Brush brush(ServerPlayer p){return Brush.values()[option(p,"brush",Brush.values().length)];}
    static int radius(ServerPlayer p){return 1+option(p,"radius",3);}
    static Palette palette(ServerPlayer p){return PALETTES.get(option(p,"palette",PALETTES.size()));}
    static List<BlockPos> brushPositions(ServerPlayer p,BlockPos center,Direction face) {
        var shape=brush(p);int r=radius(p);var out=new ArrayList<BlockPos>();
        // Keep the old face tool's center-first ordering and default 3x3 footprint.
        out.add(center.immutable());
        for(int x=-r;x<=r;x++)for(int y=-r;y<=r;y++)for(int z=-r;z<=r;z++){
            if(x==0&&y==0&&z==0)continue;
            int normal=switch(face.getAxis()){case X->x;case Y->y;case Z->z;};
            boolean include=switch(shape){
                case PLANE -> normal==0;
                case LINE -> switch(face.getAxis()){case X -> x==0&&z==0;case Y,Z -> y==0&&z==0;};
                case CUBE -> true;
                case SPHERE -> x*x+y*y+z*z<=r*r;
                case RING -> normal==0&&Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))==r;
            };
            if(include)out.add(center.offset(x,y,z));
        }
        if(shape==Brush.RING)out.removeFirst();
        return out;
    }
    static void record(ServerPlayer p,List<Change> changes){
        if(!changes.isEmpty())UNDO.put(p.getUUID(),new Edit(p,p.connection,p.level(),List.copyOf(changes)));
    }
    static boolean free(ServerPlayer p,BlockPos pos,BlockState state){
        return PoweredTools.editable(p,pos) && p.level().isUnobstructed(state,pos,CollisionContext.empty())
            && p.level().getEntitiesOfClass(LivingEntity.class,new AABB(pos),e->e.isAlive()&&!e.isSpectator()).isEmpty();
    }
    static int undo(ServerPlayer p){
        if(!allowed(p))return 0;
        var edit=UNDO.get(p.getUUID());
        if(edit==null||edit.owner()!=p||edit.connection()!=p.connection||edit.world()!=p.level()){
            Convergence.say(p,"No Studio edit to undo in this world and session.");return 0;
        }
        for(var c:edit.changes())if(!PoweredTools.editable(p,c.pos())||p.level().getBlockState(c.pos())!=c.after()
            ||(!c.before().isAir()&&!free(p,c.pos(),c.before()))){
            Convergence.say(p,"Undo paused: the area changed or is occupied. No blocks were restored.");return 0;
        }
        for(var c:edit.changes())p.level().setBlock(c.pos(),c.before(),3);
        UNDO.remove(p.getUUID());Convergence.say(p,"Restored "+edit.changes().size()+" blocks. No items were created.");return edit.changes().size();
    }
    /** Empty-slot-only delivery is atomic: a full inventory cannot drop or replace old items. */
    static int deliver(ServerPlayer p,List<ItemStack> stacks){
        if(!allowed(p)||p.containerMenu!=p.inventoryMenu||!p.containerMenu.getCarried().isEmpty())return 0;
        var slots=new ArrayList<Integer>();
        for(int i=0;i<36;i++)if(p.getInventory().getItem(i).isEmpty())slots.add(i);
        if(slots.size()<stacks.size()){Convergence.say(p,"Leave "+stacks.size()+" empty inventory slots. Nothing was replaced or dropped.");return 0;}
        for(int i=0;i<stacks.size();i++)p.getInventory().setItem(slots.get(i),stacks.get(i).copy());
        p.inventoryMenu.sendAllDataToRemote();return stacks.size();
    }
    static int kit(ServerPlayer p){
        var palette=palette(p);
        return deliver(p,List.of(new ItemStack(palette.wall().asItem(),64),new ItemStack(palette.accent().asItem(),64),
            new ItemStack(palette.light().asItem(),64),new ItemStack(Convergence.ITEMS.get("convergence:builder_wand")),
            new ItemStack(Convergence.ITEMS.get("convergence:sculptor_wand"))));
    }
    static List<ItemStack> outfit(ServerPlayer p,int style){
        Item[] items=switch(style){case 0->new Item[]{Items.DIAMOND_HELMET,Items.DIAMOND_CHESTPLATE,Items.DIAMOND_LEGGINGS,Items.DIAMOND_BOOTS};
            case 1->new Item[]{Items.NETHERITE_HELMET,Items.NETHERITE_CHESTPLATE,Items.NETHERITE_LEGGINGS,Items.NETHERITE_BOOTS};
            default->new Item[]{Items.LEATHER_HELMET,Items.LEATHER_CHESTPLATE,Items.LEATHER_LEGGINGS,Items.LEATHER_BOOTS};};
        String name=switch(style){case 0->"Storm Sentinel";case 1->"Ember Knight";default->"Sakura Ranger";};
        String[] parts={"Helmet","Chestplate","Leggings","Boots"};
        var material=p.registryAccess().lookupOrThrow(Registries.TRIM_MATERIAL).getOrThrow(switch(style){case 0->TrimMaterials.LAPIS;case 1->TrimMaterials.GOLD;default->TrimMaterials.AMETHYST;});
        var pattern=p.registryAccess().lookupOrThrow(Registries.TRIM_PATTERN).getOrThrow(switch(style){case 0->TrimPatterns.SPIRE;case 1->TrimPatterns.RIB;default->TrimPatterns.WILD;});
        var out=new ArrayList<ItemStack>();
        for(int i=0;i<items.length;i++){
            var stack=new ItemStack(items[i]);stack.set(DataComponents.CUSTOM_NAME,Component.literal(name+" "+parts[i]));
            stack.set(DataComponents.TRIM,new ArmorTrim(material,pattern));
            if(style==2)stack.set(DataComponents.DYED_COLOR,new DyedItemColor(0xF4A6CD));
            out.add(stack);
        }
        return out;
    }
    static ItemStack control(ServerPlayer p,String kind){
        String path=switch(kind){case "blink"->"blink_wand";case "party"->"party_wand";case "blueprint"->"blueprint_wand";default->"plane_remote";};
        var stack=new ItemStack(Convergence.ITEMS.get("convergence:"+path));
        stack.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Creative Studio • Use to activate"))));return stack;
    }
    static String controlKind(ServerPlayer p,ItemStack stack){
        return switch(Convergence.id(stack)){case "convergence:blink_wand"->"blink";case "convergence:party_wand"->"party";
            case "convergence:plane_remote"->"remote";case "convergence:blueprint_wand"->"blueprint";default->"";};
    }
    static int useControl(ServerPlayer p,ItemStack stack){
        if(!allowed(p))return 0;
        return switch(controlKind(p,stack)){
            case "remote"->{if(RcPlanes.ACTIVE.containsKey(p.getUUID()))RcPlanes.hover(p);else RcPlanes.launch(p);yield 1;}
            case "party"->{if(!Convergence.ready(p,"studio_party",10))yield 0;burst(p,24);yield 1;}
            case "blink"->blink(p);
            case "blueprint"->preview(p);
            default->0;
        };
    }
    static int blink(ServerPlayer p){
        if(!allowed(p)||!Convergence.ready(p,"studio_blink",10))return 0;
        var eye=p.getEyePosition();var look=p.getLookAngle();var hit=p.pick(12,1,false);
        double distance=hit.getType()==HitResult.Type.MISS?12:Math.max(0,eye.distanceTo(hit.getLocation())-1);
        for(double d=distance;d>=2;d-=.5){
            var destination=p.position().add(look.scale(d));var box=p.getBoundingBox().move(destination.subtract(p.position()));
            var pos=BlockPos.containing(destination);
            if(!p.level().isInWorldBounds(pos)||!p.level().isLoaded(pos)||!p.level().getWorldBorder().isWithinBounds(box)
                ||!p.level().noCollision(p,box)||!p.level().getFluidState(pos).isEmpty())continue;
            if(p.teleportTo(p.level(),destination.x,destination.y,destination.z,Set.of(),p.getYRot(),p.getXRot(),true)){
                p.fallDistance=0;burst(p,12);return 1;
            }
        }
        Convergence.say(p,"No clear blink destination. Aim at open space.");return 0;
    }
    static void burst(ServerPlayer p,int count){
        var particle=switch(option(p,"palette",PALETTES.size())){case 1->ParticleTypes.SOUL;case 2->ParticleTypes.CHERRY_LEAVES;case 3->ParticleTypes.ENCHANT;default->ParticleTypes.END_ROD;};
        p.level().sendParticles(particle,p.getX(),p.getY()+1,p.getZ(),count,.7,.7,.7,.01);
    }
    static Map<BlockPos,BlockState> stamp(ServerPlayer p,BlockPos origin){
        var palette=palette(p);var blocks=new LinkedHashMap<BlockPos,BlockState>();
        var kind=Stamp.values()[option(p,"stamp",Stamp.values().length)];
        if(kind==Stamp.ARCH){
            for(int y=0;y<4;y++){blocks.put(origin.offset(-2,y,0),palette.wall().defaultBlockState());blocks.put(origin.offset(2,y,0),palette.wall().defaultBlockState());}
            for(int x=-2;x<=2;x++)blocks.put(origin.offset(x,4,0),palette.accent().defaultBlockState());
            blocks.put(origin.offset(0,4,0),palette.light().defaultBlockState());
        }else if(kind==Stamp.PAVILION){
            for(int x:new int[]{-2,2})for(int z:new int[]{-2,2})for(int y=0;y<4;y++)blocks.put(origin.offset(x,y,z),palette.wall().defaultBlockState());
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)blocks.put(origin.offset(x,4,z),palette.accent().defaultBlockState());
            blocks.put(origin.offset(0,4,0),palette.light().defaultBlockState());
        }else{
            for(int y=0;y<4;y++)blocks.put(origin.offset(0,y,0),palette.wall().defaultBlockState());
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)if(Math.abs(x)+Math.abs(z)<=3)blocks.put(origin.offset(x,4,z),palette.accent().defaultBlockState());
            blocks.put(origin.offset(0,5,0),palette.light().defaultBlockState());
        }
        return Map.copyOf(blocks);
    }
    static boolean clearFootprint(ServerPlayer p,Map<BlockPos,BlockState> blocks){
        for(var entry:blocks.entrySet())if(!p.level().getBlockState(entry.getKey()).isAir()||!free(p,entry.getKey(),entry.getValue()))return false;
        return true;
    }
    static int preview(ServerPlayer p){
        if(!allowed(p))return 0;
        var hit=p.pick(p.blockInteractionRange(),1,false);
        if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK){Convergence.say(p,"Aim at a clear ground block within building reach.");return 0;}
        var origin=b.getBlockPos().above();var blocks=stamp(p,origin);
        if(b.getDirection()!=Direction.UP||!clearFootprint(p,blocks)){Convergence.say(p,"The structure needs open space on clear ground. Nothing was changed.");return 0;}
        var preview=new Preview(p,p.connection,p.level(),origin,blocks,p.level().getServer().getTickCount()+600);
        PREVIEWS.put(p.getUUID(),preview);
        for(var pos:blocks.keySet())p.level().sendParticles(ParticleTypes.END_ROD,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,1,0,0,0,0);
        return open(p,preview);
    }
    static int confirm(ServerPlayer p,Preview preview){
        if(!allowed(p)||PREVIEWS.get(p.getUUID())!=preview||preview.owner()!=p||preview.connection()!=p.connection
            ||preview.world()!=p.level()||p.level().getServer().getTickCount()>=preview.expires()
            ||p.position().distanceToSqr(Vec3.atCenterOf(preview.origin()))>64||!clearFootprint(p,preview.blocks())){
            if(PREVIEWS.get(p.getUUID())==preview)PREVIEWS.remove(p.getUUID());
            Convergence.say(p,"Blueprint expired or its area changed. Preview it again.");return 0;
        }
        PREVIEWS.remove(p.getUUID());var changes=new ArrayList<Change>();
        for(var entry:preview.blocks().entrySet()){
            var before=p.level().getBlockState(entry.getKey());
            if(p.level().setBlock(entry.getKey(),entry.getValue(),3))changes.add(new Change(entry.getKey(),before,entry.getValue()));
        }
        record(p,changes);Convergence.say(p,"Built "+changes.size()+" blocks. Studio Undo restores the last edit.");return changes.size();
    }
    static void icon(SimpleContainer view,int slot,Item item,String name,String... lore){
        var stack=new ItemStack(item);stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        stack.set(DataComponents.LORE,new ItemLore(java.util.Arrays.stream(lore).map(Component::literal).map(x->(Component)x).toList()));view.setItem(slot,stack);
    }
    static int open(ServerPlayer p){return open(p,null);}
    private static int open(ServerPlayer p,Preview preview){
        if(!ServerMenu.allowed(p)||p.containerMenu!=p.inventoryMenu||!p.containerMenu.getCarried().isEmpty())return 0;
        var view=new SimpleContainer(54);
        if(preview!=null){
            icon(view,4,Items.STRUCTURE_BLOCK,palette(p).name()+" "+Stamp.values()[option(p,"stamp",Stamp.values().length)],
                preview.blocks().size()+" blocks at "+preview.origin().toShortString(),"Only the shown empty footprint will change.","Confirm within 30 seconds; Undo is available.");
            icon(view,CONFIRM,Items.DYE.lime(),"Confirm building");icon(view,CANCEL,Items.DYE.red(),"Cancel preview");
        }else{
            icon(view,4,Items.NETHER_STAR,"Creative Studio",allowed(p)?"Build, dress up and pilot your own RC plane.":"Choose Play Creative to use these tools.");
            icon(view,BRUSH,Items.PAINTING,"Brush: "+brush(p),"Tap to cycle Plane, Line, Sphere, Cube, Ring.");
            icon(view,RADIUS,Items.SPYGLASS,"Brush radius: "+radius(p),"Radius 1, 2 or 3. At most 343 cells per edit.");
            icon(view,PALETTE,palette(p).accent().asItem(),"Theme: "+palette(p).name(),"Cycles palettes, plane colors and particle styles.");
            icon(view,BUILDER,Items.BLAZE_ROD,"Hold Builder Wand","Put a solid cube block in your offhand.");
            icon(view,SCULPTOR,Items.STICK,"Hold Sculptor Wand","Containers and protected blocks are preserved.");
            icon(view,KIT,Items.CHEST,"Get themed building kit","Five empty inventory slots; nothing is replaced.");
            icon(view,STAMP,Items.STRUCTURE_BLOCK,"Blueprint: "+Stamp.values()[option(p,"stamp",Stamp.values().length)]);
            icon(view,PREVIEW,Items.MAP,"Preview blueprint","Aim at open ground, then review and confirm.");
            icon(view,UNDO_BUTTON,Items.RECOVERY_COMPASS,"Undo last Studio edit","Same world and session. Refuses changed blocks.");
            icon(view,BLINK,Items.AMETHYST_SHARD,"Get Starlight Blink Wand","Use to blink through open space, up to 12 blocks.");
            icon(view,PARTY,Items.FIREWORK_STAR,"Get Aurora Party Wand","Use for a burst of your theme's particles.");
            icon(view,TRAIL,Items.GLOWSTONE_DUST,"Personal trail: "+(settings(p).getBooleanOr("trail",false)?"On":"Off"));
            icon(view,PLANE,Items.PHANTOM_MEMBRANE,"Launch RC plane","Native voxel plane. Look to steer. Five-minute flight.");
            icon(view,REMOTE,Items.ECHO_SHARD,"Get RC Plane Remote","Use to launch or toggle hover. Menus work on iPad.");
            icon(view,HOVER,Items.FEATHER,"Toggle plane hover","Current: "+RcPlanes.status(p));
            icon(view,SPEED,Items.SUGAR,"Plane speed: "+(1+option(p,"speed",3)),"Three speeds; collision stops flight safely.");
            icon(view,RECALL,Items.ENDER_PEARL,"Recall RC plane","Removes only your plane; no blocks or drops.");
            icon(view,STORM,Items.DIAMOND_CHESTPLATE,"Storm Sentinel armor","Full diamond set with blue Spire trim. Four free slots.");
            icon(view,EMBER,Items.NETHERITE_CHESTPLATE,"Ember Knight armor","Full netherite set with gold Rib trim. Four free slots.");
            icon(view,SAKURA,Items.LEATHER_CHESTPLATE,"Sakura Ranger armor","Full pink leather set with amethyst Wild trim.");
            icon(view,CREATIVE,Items.GRASS_BLOCK,"Play Creative");
            icon(view,BLUEPRINT_WAND,Items.PAPER,"Get Infinity Blueprint Wand","Use to preview the selected structure on ground.");
            icon(view,STORM_STAFF,Items.BLAZE_ROD,"Hold Infinity Storm Staff","Storm pulse damages hostile mobs; never players or pets.");
        }
        icon(view,BACK,Items.ARROW,"Back to Infinity Menu");
        p.openMenu(new SimpleMenuProvider((sync,inventory,who)->new Handler(sync,inventory,view,p,preview),Component.literal(preview==null?"Creative Studio":"Review Blueprint")));return 1;
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner;final ServerGamePacketListenerImpl connection;final ServerLevel world;
        final GameModes.Mode mode;final Preview preview;
        Handler(int sync,Inventory inventory,SimpleContainer view,ServerPlayer p,Preview preview){
            super(MenuType.GENERIC_9x6,sync,inventory,view,6);owner=p;connection=p.connection;world=p.level();mode=GameModes.current(p);this.preview=preview;
        }
        @Override public boolean stillValid(Player p){return p==owner&&owner.connection==connection&&owner.containerMenu==this&&ServerMenu.allowed(owner)&&owner.level()==world&&GameModes.current(owner)==mode;}
        @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected){}
        void reopen(){owner.closeContainer();open(owner);}
        @Override public void clicked(int slot,int button,ContainerInput action,Player p){
            if(p!=owner||owner.containerMenu!=this||owner.connection!=connection)return;
            if(!stillValid(p)){owner.closeContainer();return;}
            if((action!=ContainerInput.PICKUP&&action!=ContainerInput.QUICK_MOVE)||button<0||button>1||!getCarried().isEmpty()||slot<0||slot>=54){sendAllDataToRemote();return;}
            if(slot==BACK){owner.closeContainer();ServerMenu.open(owner);return;}
            if(preview!=null){
                if(slot==CONFIRM){owner.closeContainer();confirm(owner,preview);}
                else if(slot==CANCEL){PREVIEWS.remove(owner.getUUID());reopen();}return;
            }
            if(slot==CREATIVE){owner.closeContainer();GameModes.request(owner,GameModes.Mode.CREATIVE,null);return;}
            if(!allowed(owner)){owner.sendOverlayMessage(Component.literal("Choose Play Creative to use the Studio."));return;}
            String key=switch(slot){case BRUSH->"brush";case RADIUS->"radius";case PALETTE->"palette";case STAMP->"stamp";case SPEED->"speed";default->null;};
            if(key!=null){int size=key.equals("brush")?Brush.values().length:key.equals("palette")?PALETTES.size():3;settings(owner).putInt(key,(option(owner,key,size)+1)%size);reopen();return;}
            if(slot==TRAIL){settings(owner).putBoolean("trail",!settings(owner).getBooleanOr("trail",false));reopen();return;}
            if(slot==HOVER){RcPlanes.hover(owner);reopen();return;}
            owner.closeContainer();
            switch(slot){
                case BUILDER->Convergence.holdCreativeItem(owner,"builder_wand");
                case SCULPTOR->Convergence.holdCreativeItem(owner,"sculptor_wand");
                case KIT->kit(owner);
                case PREVIEW->preview(owner);
                case UNDO_BUTTON->undo(owner);
                case BLINK->deliver(owner,List.of(control(owner,"blink")));
                case PARTY->deliver(owner,List.of(control(owner,"party")));
                case REMOTE->deliver(owner,List.of(control(owner,"remote")));
                case BLUEPRINT_WAND->deliver(owner,List.of(control(owner,"blueprint")));
                case STORM_STAFF->Convergence.holdCreativeItem(owner,"storm_staff");
                case PLANE->RcPlanes.launch(owner);
                case RECALL->RcPlanes.recall(owner);
                case STORM->deliver(owner,outfit(owner,0));
                case EMBER->deliver(owner,outfit(owner,1));
                case SAKURA->deliver(owner,outfit(owner,2));
                default->open(owner);
            }
        }
    }
    static void register(){
        UseItemCallback.EVENT.register((p,world,hand)->{
            if(p instanceof ServerPlayer owner&&!controlKind(owner,p.getItemInHand(hand)).isEmpty()){
                if(allowed(owner))useControl(owner,p.getItemInHand(hand));
                else Convergence.say(owner,"This gadget requires Creative Studio access.");return InteractionResult.SUCCESS;
            }return InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(server->{
            PREVIEWS.entrySet().removeIf(e->server.getTickCount()>=e.getValue().expires()||!allowed(e.getValue().owner())||e.getValue().owner().level()!=e.getValue().world());
            UNDO.entrySet().removeIf(e->!allowed(e.getValue().owner())||e.getValue().owner().level()!=e.getValue().world());
            if(server.getTickCount()%10==0)for(var p:server.getPlayerList().getPlayers())if(allowed(p)&&settings(p).getBooleanOr("trail",false))burst(p,2);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{UNDO.remove(handler.player.getUUID());PREVIEWS.remove(handler.player.getUUID());RcPlanes.recall(handler.player);});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{UNDO.clear();PREVIEWS.clear();});
    }
}

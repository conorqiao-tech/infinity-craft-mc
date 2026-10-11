package dev.convergence;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.Component;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.*;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

/** Personal storage lives beside the active inventory in the same vanilla player file.
 * Backpack items are wearable keys/appearance only: copying/trading one never copies storage. */
final class BackpackStorage {
    static final String STORAGE="backpacks", OWNED="wardrobe";
    static final int SIZE=27;
    record Style(String id,String name,int color,String advancement) {}
    static final List<Style> STYLES=List.of(
        new Style("trail","Trail Backpack",0x49B9A5,"story/smelt_iron"),
        new Style("emerald","Emerald Backpack",0x55D86D,"adventure/trade"),
        new Style("dragon","Dragon Backpack",0xAD6FDB,"end/kill_dragon"));
    static Item ITEM;
    static void registerItems() {
        var key=RegistryKey.of(RegistryKeys.ITEM,Identifier.of("convergence","backpack"));
        var settings=new Item.Settings().registryKey(key).maxCount(1);
        for(Component<?> component:Items.LEATHER_CHESTPLATE.getComponents())
            if(component.type()!=DataComponentTypes.ITEM_NAME&&component.type()!=DataComponentTypes.ITEM_MODEL
                &&component.type()!=DataComponentTypes.ATTRIBUTE_MODIFIERS&&component.type()!=DataComponentTypes.MAX_DAMAGE
                &&component.type()!=DataComponentTypes.DAMAGE&&component.type()!=DataComponentTypes.EQUIPPABLE)Convergence.copy(settings,component);
        RegistryKey<EquipmentAsset> asset=RegistryKey.of(RegistryKey.ofRegistry(Identifier.of("minecraft","equipment_asset")),Identifier.of("convergence","backpack"));
        settings.attributeModifiers(AttributeModifiersComponent.builder().build());
        settings.component(DataComponentTypes.EQUIPPABLE,EquippableComponent.builder(EquipmentSlot.CHEST).model(asset).damageOnHurt(false).build());
        settings.component(DataComponentTypes.DYED_COLOR,new DyedColorComponent(STYLES.getFirst().color()));
        ITEM=Registry.register(Registries.ITEM,key.getValue(),new Item(settings) {
            @Override public ActionResult use(World world,PlayerEntity player,Hand hand) {
                if(player instanceof ServerPlayerEntity p)return open(p)>0?ActionResult.SUCCESS:ActionResult.FAIL;
                return ActionResult.SUCCESS;
            }
        });
        Convergence.ITEMS.put("convergence:backpack",ITEM);
    }
    static String modeKey(ServerPlayerEntity p) {return GameModes.current(p).name();}
    static boolean storageAllowed(ServerPlayerEntity p) {
        return p.isAlive()&&!p.isSpectator()&&!GameModes.TRANSITIONS.contains(p.getUuid())&&!GameModes.PENDING.containsKey(p.getUuid())
            &&switch(GameModes.current(p)){case SURVIVAL,HARDCORE,CREATIVE->true;default->false;};
    }
    static NbtCompound owned(ServerPlayerEntity p) {return GameModes.state(p).getCompoundOrEmpty(OWNED);}
    static boolean earned(ServerPlayerEntity p,String key,String advancement) {
        if(Memberships.operator(p)||p.isCreative()&&GameModes.current(p)==GameModes.Mode.CREATIVE)return true;
        if(owned(p).getBoolean(key,false))return true;
        var a=p.getEntityWorld().getServer().getAdvancementLoader().get(Identifier.of("minecraft",advancement));
        if(a!=null&&p.getAdvancementTracker().getProgress(a).isDone()) {
            var s=owned(p);s.putBoolean(key,true);GameModes.state(p).put(OWNED,s);return true;
        }
        return false;
    }
    static boolean backpackEarned(ServerPlayerEntity p) {
        return STYLES.stream().anyMatch(style->earned(p,"backpack:"+style.id(),style.advancement()));
    }
    static Style style(String id){return STYLES.stream().filter(s->s.id().equals(id)).findFirst().orElse(null);}
    static ItemStack appearance(Style style) {
        var stack=ITEM.getDefaultStack();stack.set(DataComponentTypes.DYED_COLOR,new DyedColorComponent(style.color()));
        stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(style.name()));return stack;
    }
    static boolean space(ServerPlayerEntity p,int needed){int n=0;for(int i=0;i<36;i++)if(p.getInventory().getStack(i).isEmpty())n++;return n>=needed;}
    static int claim(ServerPlayerEntity p,String id) {
        var style=style(id);
        if(style==null||!earned(p,"backpack:"+id,style.advancement()))return CommunityServer.say(p,"That backpack is locked. /wardrobe lists its achievement.")*0;
        if(!storageAllowed(p)||p.currentScreenHandler!=p.playerScreenHandler||!p.currentScreenHandler.getCursorStack().isEmpty())return CommunityServer.say(p,"Close containers and play Survival, Hardcore or Creative first.")*0;
        if(!space(p,1))return CommunityServer.say(p,"Make one empty inventory slot first.")*0;
        p.getInventory().insertStack(appearance(style));CommunityServer.say(p,"Claimed "+style.name()+". Equip it in your chest slot; /backpack opens your personal storage. It gives no armor power.");return 1;
    }
    static List<ItemStack> read(ServerPlayerEntity p,String mode) {
        var data=GameModes.state(p).getCompoundOrEmpty(STORAGE).getCompoundOrEmpty(mode);
        if(data.isEmpty())return new ArrayList<>(Collections.nCopies(SIZE,ItemStack.EMPTY));
        var stacks=NbtReadView.create(ErrorReporter.EMPTY,p.getRegistryManager(),data).read("items",ItemStack.OPTIONAL_CODEC.listOf())
            .orElseThrow(()->new IllegalStateException("Invalid saved backpack items; original storage preserved"));
        if(stacks.size()!=SIZE||stacks.stream().anyMatch(s->!s.isEmpty()&&(s.getCount()<1||s.getCount()>s.getMaxCount())))
            throw new IllegalStateException("Invalid saved backpack size/count; original storage preserved");
        return stacks.stream().map(ItemStack::copy).toList();
    }
    static void write(ServerPlayerEntity p,String mode,List<ItemStack> stacks) {
        if(stacks.size()!=SIZE)throw new IllegalArgumentException("Backpack size");
        var view=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());view.put("items",ItemStack.OPTIONAL_CODEC.listOf(),stacks);
        var all=GameModes.state(p).getCompoundOrEmpty(STORAGE);all.put(mode,view.getNbt());GameModes.state(p).put(STORAGE,all);
    }
    static final class Storage extends SimpleInventory {
        final ServerPlayerEntity owner;final String mode;boolean loading=true;
        Storage(ServerPlayerEntity owner) {
            super(SIZE);this.owner=owner;this.mode=modeKey(owner);var saved=read(owner,mode);
            for(int i=0;i<SIZE;i++)super.setStack(i,saved.get(i));loading=false;
        }
        @Override public void markDirty(){super.markDirty();if(!loading){var stacks=new ArrayList<ItemStack>();for(int i=0;i<SIZE;i++)stacks.add(getStack(i));write(owner,mode,stacks);}}
        @Override public boolean canPlayerUse(PlayerEntity p){return p==owner&&storageAllowed(owner)&&modeKey(owner).equals(mode);}
    }
    static int open(ServerPlayerEntity p) {
        if(!storageAllowed(p))return CommunityServer.say(p,"Backpacks are available in Survival, Hardcore and Creative; close them before switching modes.")*0;
        if(!backpackEarned(p))return CommunityServer.say(p,"Smelt iron to earn your first backpack. /wardrobe lists more styles.")*0;
        if(p.currentScreenHandler!=p.playerScreenHandler||!p.currentScreenHandler.getCursorStack().isEmpty())return CommunityServer.say(p,"Close your current container first.")*0;
        try {
            var storage=new Storage(p);
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inventory,player)->new GenericContainerScreenHandler(
                net.minecraft.screen.ScreenHandlerType.GENERIC_9X3,sync,inventory,storage,3) {
                    @Override public void onClosed(PlayerEntity player){super.onClosed(player);storage.markDirty();}
                },Text.literal("Backpack • "+modeKey(p))));
            return 1;
        }catch(IllegalStateException error){System.err.println("[Infinity backpack] "+error.getMessage());CommunityServer.say(p,"Saved backpack data could not be opened. Ask the owner to inspect the log; your saved items were preserved.");return 0;}
    }
    static String armorAdvancement(String set){return set.equals("aurora")?"story/enchant_item":"nether/obtain_blaze_rod";}
    static int armor(ServerPlayerEntity p,String set) {
        if(!Set.of("aurora","ember").contains(set)||!earned(p,"armor:"+set,armorAdvancement(set)))return CommunityServer.say(p,"That armor look is locked. /wardrobe lists its achievement.")*0;
        if(!storageAllowed(p)||p.currentScreenHandler!=p.playerScreenHandler||!p.currentScreenHandler.getCursorStack().isEmpty())return CommunityServer.say(p,"Close containers and play Survival, Hardcore or Creative first.")*0;
        if(!space(p,4))return CommunityServer.say(p,"Make four empty inventory slots for the armor set.")*0;
        for(String piece:List.of("helmet","chestplate","leggings","boots"))p.getInventory().insertStack(Convergence.ITEMS.get("convergence:"+set+"_"+piece).getDefaultStack());
        CommunityServer.say(p,"Claimed "+set+" cosmetic armor. Equip the pieces in armor slots; they replace those slots and grant no combat bonuses.");return 1;
    }
    static int list(ServerPlayerEntity p) {
        for(var style:STYLES)CommunityServer.say(p,"/backpack claim "+style.id()+" — "+style.name()+"; "+(earned(p,"backpack:"+style.id(),style.advancement())?"unlocked":style.advancement()));
        for(String set:List.of("aurora","ember"))CommunityServer.say(p,"/wardrobe "+set+" — wearable cosmetic armor; "+(earned(p,"armor:"+set,armorAdvancement(set))?"unlocked":armorAdvancement(set)));
        return CommunityServer.say(p,"All backpack styles share one personal 27-slot storage per mode. Storage survives death and is saved with your player; it never transfers with the accessory. /backpack opens it.");
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((d,a,e)->{
            var cmd=CommandManager.literal("backpack").executes(c->open(c.getSource().getPlayerOrThrow()));
            cmd.then(CommandManager.literal("open").executes(c->open(c.getSource().getPlayerOrThrow())));
            var claim=CommandManager.literal("claim");for(var s:STYLES)claim.then(CommandManager.literal(s.id()).executes(c->claim(c.getSource().getPlayerOrThrow(),s.id())));cmd.then(claim);d.register(cmd);
            var wardrobe=CommandManager.literal("wardrobe").executes(c->list(c.getSource().getPlayerOrThrow()));
            for(String set:List.of("aurora","ember"))wardrobe.then(CommandManager.literal(set).executes(c->armor(c.getSource().getPlayerOrThrow(),set)));d.register(wardrobe);
        });
        ServerTickEvents.END_SERVER_TICK.register(s->{for(var p:s.getPlayerManager().getPlayerList())if(p.currentScreenHandler instanceof GenericContainerScreenHandler chest&&chest.getInventory() instanceof Storage storage&&!storage.canPlayerUse(p))p.closeHandledScreen();});
    }
}

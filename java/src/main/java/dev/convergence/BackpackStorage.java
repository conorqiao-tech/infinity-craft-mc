package dev.convergence;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

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
        var key=ResourceKey.create(Registries.ITEM,Identifier.fromNamespaceAndPath("convergence","backpack"));
        var settings=new Item.Properties().setId(key).stacksTo(1);
        BaseComponents.then(settings,Items.LEATHER_CHESTPLATE,(builder,defaults)->{
            for(TypedDataComponent<?> component:defaults)
                if(component.type()!=DataComponents.ITEM_NAME&&component.type()!=DataComponents.ITEM_MODEL
                    &&component.type()!=DataComponents.ATTRIBUTE_MODIFIERS&&component.type()!=DataComponents.MAX_DAMAGE
                    &&component.type()!=DataComponents.DAMAGE&&component.type()!=DataComponents.EQUIPPABLE)BaseComponents.copy(builder,component);
        });
        ResourceKey<EquipmentAsset> asset=ResourceKey.create(ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft","equipment_asset")),Identifier.fromNamespaceAndPath("convergence","backpack"));
        settings.attributes(ItemAttributeModifiers.builder().build());
        settings.component(DataComponents.EQUIPPABLE,Equippable.builder(EquipmentSlot.CHEST).setAsset(asset).setDamageOnHurt(false).build());
        settings.component(DataComponents.DYED_COLOR,new DyedItemColor(STYLES.getFirst().color()));
        ITEM=Registry.register(BuiltInRegistries.ITEM,key.identifier(),new Item(settings) {
            @Override public InteractionResult use(Level world,Player player,InteractionHand hand) {
                if(player instanceof ServerPlayer p)return open(p)>0?InteractionResult.SUCCESS:InteractionResult.FAIL;
                return InteractionResult.SUCCESS;
            }
        });
        Convergence.ITEMS.put("convergence:backpack",ITEM);
    }
    static String modeKey(ServerPlayer p) {return GameModes.current(p).name();}
    static boolean storageAllowed(ServerPlayer p) {
        return p.isAlive()&&!p.isSpectator()&&!GameModes.TRANSITIONS.contains(p.getUUID())&&!GameModes.PENDING.containsKey(p.getUUID())
            &&switch(GameModes.current(p)){case SURVIVAL,HARDCORE,CREATIVE->true;default->false;};
    }
    static CompoundTag owned(ServerPlayer p) {return GameModes.state(p).getCompoundOrEmpty(OWNED);}
    static boolean earned(ServerPlayer p,String key,String advancement) {
        if(Memberships.gameplayBypass(p)||p.isCreative()&&GameModes.current(p)==GameModes.Mode.CREATIVE)return true;
        if(owned(p).getBooleanOr(key,false))return true;
        var a=p.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath("minecraft",advancement));
        if(a!=null&&p.getAdvancements().getOrStartProgress(a).isDone()) {
            var s=owned(p);s.putBoolean(key,true);GameModes.state(p).put(OWNED,s);return true;
        }
        return false;
    }
    static boolean backpackEarned(ServerPlayer p) {
        return STYLES.stream().anyMatch(style->earned(p,"backpack:"+style.id(),style.advancement()));
    }
    static Style style(String id){return STYLES.stream().filter(s->s.id().equals(id)).findFirst().orElse(null);}
    static ItemStack appearance(Style style) {
        var stack=ITEM.getDefaultInstance();stack.set(DataComponents.DYED_COLOR,new DyedItemColor(style.color()));
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(style.name()));return stack;
    }
    static boolean space(ServerPlayer p,int needed){int n=0;for(int i=0;i<36;i++)if(p.getInventory().getItem(i).isEmpty())n++;return n>=needed;}
    static int claim(ServerPlayer p,String id) {
        var style=style(id);
        if(style==null||!earned(p,"backpack:"+id,style.advancement()))return CommunityServer.say(p,"That backpack is locked. /wardrobe lists its achievement.")*0;
        if(!storageAllowed(p)||p.containerMenu!=p.inventoryMenu||!p.containerMenu.getCarried().isEmpty())return CommunityServer.say(p,"Close containers and play Survival, Hardcore or Creative first.")*0;
        if(!space(p,1))return CommunityServer.say(p,"Make one empty inventory slot first.")*0;
        p.getInventory().add(appearance(style));CommunityServer.say(p,"Claimed "+style.name()+". Equip it in your chest slot; /backpack opens your personal storage. It gives no armor power.");return 1;
    }
    static List<ItemStack> read(ServerPlayer p,String mode) {
        var data=GameModes.state(p).getCompoundOrEmpty(STORAGE).getCompoundOrEmpty(mode);
        if(data.isEmpty())return new ArrayList<>(Collections.nCopies(SIZE,ItemStack.EMPTY));
        var stacks=TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),data).read("items",ItemStack.OPTIONAL_CODEC.listOf())
            .orElseThrow(()->new IllegalStateException("Invalid saved backpack items; original storage preserved"));
        if(stacks.size()!=SIZE||stacks.stream().anyMatch(s->!s.isEmpty()&&(s.getCount()<1||s.getCount()>s.getMaxStackSize())))
            throw new IllegalStateException("Invalid saved backpack size/count; original storage preserved");
        return stacks.stream().map(ItemStack::copy).toList();
    }
    static void write(ServerPlayer p,String mode,List<ItemStack> stacks) {
        if(stacks.size()!=SIZE)throw new IllegalArgumentException("Backpack size");
        var view=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.registryAccess());view.store("items",ItemStack.OPTIONAL_CODEC.listOf(),stacks);
        var all=GameModes.state(p).getCompoundOrEmpty(STORAGE);all.put(mode,view.buildResult());GameModes.state(p).put(STORAGE,all);
    }
    static final class Storage extends SimpleContainer {
        final ServerPlayer owner;final String mode;boolean loading=true;
        Storage(ServerPlayer owner) {
            super(SIZE);this.owner=owner;this.mode=modeKey(owner);var saved=read(owner,mode);
            for(int i=0;i<SIZE;i++)super.setItem(i,saved.get(i));loading=false;
        }
        @Override public void setChanged(){super.setChanged();if(!loading){var stacks=new ArrayList<ItemStack>();for(int i=0;i<SIZE;i++)stacks.add(getItem(i));write(owner,mode,stacks);}}
        @Override public boolean stillValid(Player p){return p==owner&&storageAllowed(owner)&&modeKey(owner).equals(mode);}
    }
    static int open(ServerPlayer p) {
        if(!storageAllowed(p))return CommunityServer.say(p,"Backpacks are available in Survival, Hardcore and Creative; close them before switching modes.")*0;
        if(!backpackEarned(p))return CommunityServer.say(p,"Smelt iron to earn your first backpack. /wardrobe lists more styles.")*0;
        if(p.containerMenu!=p.inventoryMenu||!p.containerMenu.getCarried().isEmpty())return CommunityServer.say(p,"Close your current container first.")*0;
        try {
            var storage=new Storage(p);
            p.openMenu(new SimpleMenuProvider((sync,inventory,player)->new ChestMenu(
                net.minecraft.world.inventory.MenuType.GENERIC_9x3,sync,inventory,storage,3) {
                    @Override public void removed(Player player){super.removed(player);storage.setChanged();}
                },Component.literal("Backpack • "+modeKey(p))));
            return 1;
        }catch(IllegalStateException error){System.err.println("[Infinity backpack] "+error.getMessage());CommunityServer.say(p,"Saved backpack data could not be opened. Ask the owner to inspect the log; your saved items were preserved.");return 0;}
    }
    static String armorAdvancement(String set){return set.equals("aurora")?"story/enchant_item":"nether/obtain_blaze_rod";}
    static int armor(ServerPlayer p,String set) {
        if(!Set.of("aurora","ember").contains(set)||!earned(p,"armor:"+set,armorAdvancement(set)))return CommunityServer.say(p,"That armor look is locked. /wardrobe lists its achievement.")*0;
        if(!storageAllowed(p)||p.containerMenu!=p.inventoryMenu||!p.containerMenu.getCarried().isEmpty())return CommunityServer.say(p,"Close containers and play Survival, Hardcore or Creative first.")*0;
        if(!space(p,4))return CommunityServer.say(p,"Make four empty inventory slots for the armor set.")*0;
        for(String piece:List.of("helmet","chestplate","leggings","boots"))p.getInventory().add(Convergence.ITEMS.get("convergence:"+set+"_"+piece).getDefaultInstance());
        CommunityServer.say(p,"Claimed "+set+" cosmetic armor. Equip the pieces in armor slots; they replace those slots and grant no combat bonuses.");return 1;
    }
    static int list(ServerPlayer p) {
        for(var style:STYLES)CommunityServer.say(p,"/backpack claim "+style.id()+" — "+style.name()+"; "+(earned(p,"backpack:"+style.id(),style.advancement())?"unlocked":style.advancement()));
        for(String set:List.of("aurora","ember"))CommunityServer.say(p,"/wardrobe "+set+" — wearable cosmetic armor; "+(earned(p,"armor:"+set,armorAdvancement(set))?"unlocked":armorAdvancement(set)));
        return CommunityServer.say(p,"All backpack styles share one personal 27-slot storage per mode. Storage survives death and is saved with your player; it never transfers with the accessory. /backpack opens it.");
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((d,a,e)->{
            var cmd=Commands.literal("backpack").executes(c->open(c.getSource().getPlayerOrException()));
            cmd.then(Commands.literal("open").executes(c->open(c.getSource().getPlayerOrException())));
            var claim=Commands.literal("claim");for(var s:STYLES)claim.then(Commands.literal(s.id()).executes(c->claim(c.getSource().getPlayerOrException(),s.id())));cmd.then(claim);d.register(cmd);
            var wardrobe=Commands.literal("wardrobe").executes(c->list(c.getSource().getPlayerOrException()));
            for(String set:List.of("aurora","ember"))wardrobe.then(Commands.literal(set).executes(c->armor(c.getSource().getPlayerOrException(),set)));d.register(wardrobe);
        });
        ServerTickEvents.END_SERVER_TICK.register(s->{for(var p:s.getPlayerList().getPlayers())if(p.containerMenu instanceof ChestMenu chest&&chest.getContainer() instanceof Storage storage&&!storage.stillValid(p))p.closeContainer();});
    }
}

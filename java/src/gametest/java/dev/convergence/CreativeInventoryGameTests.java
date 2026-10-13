package dev.convergence;

import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import java.util.Collection;
import java.util.HashSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;

/** Server-side registration and contents; a rendered Java client still needs Polymer. */
public class CreativeInventoryGameTests {
    private static final Identifier TAB_ID=Identifier.fromNamespaceAndPath("convergence","powers");

    private ServerPlayer player(GameTestHelper c,String name) {
        var p=new ModeGameTests().player(c,name);
        OperatorGameTests.deop(p);
        p.setGameMode(GameType.CREATIVE);
        return p;
    }

    private CreativeModeTab tab(GameTestHelper c,ServerPlayer player) {
        c.assertTrue(PolymerCreativeModeTabUtils.contains(TAB_ID),"Infinity tab is registered in Polymer's creative registry");
        var tab=PolymerCreativeModeTabUtils.getCreativeModeTabs(player).stream()
            .filter(group->TAB_ID.equals(PolymerCreativeModeTabUtils.getId(group)))
            .findFirst().orElseThrow(()->new IllegalStateException("Infinity tab is absent from player creative groups"));
        c.assertTrue(PolymerCreativeModeTabUtils.isPolymerCreativeModeTab(tab),"Infinity tab can be sent by Polymer's creative protocol");
        return tab;
    }

    private void exactlyOnce(GameTestHelper c,Collection<ItemStack> contents,Item item,String message) {
        c.assertValueEqual(contents.stream().filter(stack->stack.is(item)).count(),1L,message);
    }

    @GameTest public void infinityTabContainsEveryCanonicalItemInDisplayAndSearch(GameTestHelper c) {
        var p=player(c,"creative-tab");
        try {
            var tab=tab(c,p);
            c.assertValueEqual(tab.getDisplayName().getString(),"Infinity Armor","Creative tab has a literal readable title");
            c.assertTrue(tab.getIconItem().is(Convergence.ITEMS.get("convergence:mace")),"Tab icon is the canonical Infinity Mace");
            var contents=PolymerCreativeModeTabUtils.getContentsFor(p,tab);
            c.assertValueEqual(contents.main().size(),Convergence.ITEMS.size(),"Ordinary Creative display has every Infinity item and no admin items");
            c.assertValueEqual(contents.search().size(),Convergence.ITEMS.size(),"Ordinary Creative search has every Infinity item");
            for(var entry:Convergence.ITEMS.entrySet()) {
                exactlyOnce(c,contents.main(),entry.getValue(),"Display contains canonical item exactly once: "+entry.getKey());
                exactlyOnce(c,contents.search(),entry.getValue(),"Search contains canonical item exactly once: "+entry.getKey());
            }
        } finally {OperatorGameTests.deop(p);p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void creativeCatalogueNamesAndModelsRetainCanonicalRoundTrip(GameTestHelper c) {
        var p=player(c,"creative-names");
        try {
            c.assertTrue(Items.DIAMOND.getDefaultInstance().get(DataComponents.ITEM_NAME).getContents() instanceof TranslatableContents,
                "Literal Infinity defaults never replace ordinary Minecraft names");
            var contents=PolymerCreativeModeTabUtils.getContentsFor(p,tab(c,p));
            for(var stack:contents.main()) {
                var id=Identifier.parse(Convergence.id(stack));
                c.assertTrue(stack.get(DataComponents.ITEM_NAME)!=null,"Catalogue item has an explicit name: "+id);
                c.assertFalse(stack.get(DataComponents.ITEM_NAME).getContents() instanceof TranslatableContents,
                    "Catalogue item never depends on an unavailable client language key: "+id);
                c.assertValueEqual(stack.getHoverName().getString(),GearNames.label(id.getPath()),"Catalogue name is the full Infinity label: "+id);
                c.assertValueEqual(stack.get(DataComponents.ITEM_MODEL),id,"Catalogue uses its own item model: "+id);
                var wire=PolymerItemUtils.getPolymerItemStack(stack,p.connection.getPacketContext(),c.getLevel().registryAccess());
                c.assertValueEqual(wire.get(DataComponents.ITEM_NAME).getString(),GearNames.label(id.getPath()),"Wire name stays readable: "+id);
                var returned=PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
                c.assertTrue(ItemStack.isSameItemSameComponents(returned,stack),"Catalogue selection retains the real item and all components: "+id);
            }
        } finally {OperatorGameTests.deop(p);p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void creativeAdminEntriesFollowCurrentNativePermission(GameTestHelper c) {
        var p=player(c,"creative-admin");
        try {
            var tab=tab(c,p);
            var ordinary=PolymerCreativeModeTabUtils.getContentsFor(p,tab);
            for(var item:GearCrates.ADMIN_ITEMS) {
                c.assertFalse(ordinary.main().stream().anyMatch(stack->stack.is(item)),"Ordinary player has no admin display entry");
                c.assertFalse(ordinary.search().stream().anyMatch(stack->stack.is(item)),"Ordinary player has no admin search entry");
            }
            OperatorGameTests.level(p,LevelBasedPermissionSet.GAMEMASTER);
            var permitted=PolymerCreativeModeTabUtils.getContentsFor(p,tab);
            c.assertValueEqual(permitted.main().size(),Convergence.ITEMS.size()+GearCrates.ADMIN_ITEMS.size(),"Native OP2 Creative permission adds all admin items");
            for(var item:GearCrates.ADMIN_ITEMS) {
                exactlyOnce(c,permitted.main(),item,"Permitted display contains each admin item once");
                exactlyOnce(c,permitted.search(),item,"Permitted search contains each admin item once");
            }
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            c.assertValueEqual(PolymerCreativeModeTabUtils.getContentsFor(p,tab).main().size(),permitted.main().size(),"OP4 keeps the same complete Creative catalogue");
            OperatorGameTests.deop(p);
            var revoked=PolymerCreativeModeTabUtils.getContentsFor(p,tab);
            c.assertValueEqual(revoked.main().size(),Convergence.ITEMS.size(),"Deop immediately returns the ordinary catalogue without stale admin entries");
            for(var item:GearCrates.ADMIN_ITEMS)
                c.assertFalse(revoked.search().stream().anyMatch(stack->stack.is(item)),"Deop also removes admin search entries");
        } finally {OperatorGameTests.deop(p);p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void bridgeCatalogueExportsEveryCanonicalItemWithNativeDefaults(GameTestHelper c) {
        // Round-trip the real exported document through its JSON representation.
        var catalogue=com.google.gson.JsonParser.parseString(CrossplaySupport.creativeCatalogue().toString()).getAsJsonObject();
        c.assertValueEqual(catalogue.get("format_version").getAsInt(),1,"Bridge catalogue uses the supported format");
        var entries=catalogue.getAsJsonArray("items");
        c.assertValueEqual(entries.size(),Convergence.ITEMS.size(),"Bridge catalogue includes every canonical Infinity item");
        var seen=new HashSet<String>();
        for(var entry:entries) {
            var definition=entry.getAsJsonObject();
            String path=definition.get("path").getAsString(),key="convergence:"+path;
            c.assertTrue(seen.add(key),"Canonical catalogue item is exported exactly once: "+key);
            c.assertTrue(Convergence.ITEMS.containsKey(key),"Export contains only registered canonical items: "+key);
            var stack=Convergence.ITEMS.get(key).getDefaultInstance();
            var base=CrossplaySupport.BASES.get(path);
            c.assertTrue(base!=null,"Catalogue has an actual bridge base item: "+key);
            c.assertValueEqual(definition.get("base").getAsString(),BuiltInRegistries.ITEM.getKey(base).toString(),"Export names the real vanilla base: "+key);
            c.assertValueEqual(definition.get("name").getAsString(),stack.getHoverName().getString(),"Export retains the canonical readable item name: "+key);
            c.assertValueEqual(definition.get("name").getAsString(),GearNames.label(path),"Export never substitutes a translation key: "+key);
            c.assertValueEqual(definition.get("icon").getAsString(),"convergence_"+path,"Export selects the item's own icon: "+key);
            c.assertValueEqual(definition.get("max_stack_size").getAsInt(),stack.getMaxStackSize(),"Export retains the canonical stack limit: "+key);
            var equippable=stack.get(DataComponents.EQUIPPABLE);
            if(equippable==null)c.assertFalse(definition.has("slot"),"Non-equipment has no invented equipment slot: "+key);
            else c.assertValueEqual(definition.get("slot").getAsString(),equippable.slot().getName(),"Export retains the native equipment slot: "+key);
            Integer damage=stack.get(DataComponents.MAX_DAMAGE);
            if(damage==null)c.assertFalse(definition.has("max_damage"),"Non-damageable items have no invented durability: "+key);
            else c.assertValueEqual(definition.get("max_damage").getAsInt(),damage,"Export retains canonical durability: "+key);
            c.assertValueEqual(definition.get("native_appearance").getAsBoolean(),CrossplaySupport.NATIVE_BEDROCK.contains(path),"Native item behavior exceptions survive catalogue export: "+key);
        }
        c.assertValueEqual(seen,new HashSet<>(Convergence.ITEMS.keySet()),"No Infinity item is omitted from the exported catalogue");
        c.succeed();
    }

    @GameTest public void incomingBridgeCreativeStacksDecodeToCanonicalItemsAtBothCounts(GameTestHelper c) {
        var ops=RegistryOps.create(NbtOps.INSTANCE,c.getLevel().registryAccess());
        for(var entry:CrossplaySupport.BASES.entrySet()) {
            String key="convergence:"+entry.getKey();
            var canonical=Convergence.ITEMS.get(key).getDefaultInstance();
            for(int count:new int[]{1,canonical.getMaxStackSize()}) {
                // This is the vanilla-base identity payload emitted by the pinned
                // bridge, not a Fabric registry ID or a full saved player item.
                var realIdentity=new CompoundTag();
                realIdentity.putString("id",key);realIdentity.putInt("count",1);
                var data=new CompoundTag();data.put("$polymer:stack",realIdentity);
                var incoming=new ItemStack(entry.getValue(),count);
                incoming.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
                var encoded=ItemStack.CODEC.encodeStart(ops,incoming).getOrThrow();
                var decodedWire=ItemStack.CODEC.parse(ops,encoded).getOrThrow();
                c.assertTrue(decodedWire.is(entry.getValue()),"Minecraft codec receives the actual vanilla wire item: "+key);
                c.assertValueEqual(decodedWire.getCount(),count,"Minecraft codec preserves incoming Creative count: "+key);
                var returned=PolymerItemUtils.getRealItemStack(decodedWire,c.getLevel().registryAccess());
                c.assertTrue(ItemStack.isSameItemSameComponents(returned,canonical),"Polymer restores the canonical item and every native default: "+key);
                c.assertValueEqual(returned.getCount(),count,"Polymer keeps count from the Creative wire stack: "+key);
                var expected=canonical.copyWithCount(count);
                c.assertValueEqual(ItemStack.CODEC.encodeStart(ops,returned).getOrThrow(),ItemStack.CODEC.encodeStart(ops,expected).getOrThrow(),
                    "Returned item has exactly the canonical saved components and count: "+key);
                c.assertFalse(returned.getHoverName().getContents() instanceof TranslatableContents,"Returned gear has a readable literal name: "+key);
            }
        }
        c.succeed();
    }

    @GameTest public void nativeCreativeHandlerAcceptsCanonicalCountsAndRejectsSurvivalAndOversize(GameTestHelper c) {
        var p=player(c,"creative-native");
        try {
            var ops=RegistryOps.create(NbtOps.INSTANCE,c.getLevel().registryAccess());
            p.closeContainer();p.getInventory().clearContent();
            for(var entry:CrossplaySupport.BASES.entrySet()) {
                String key="convergence:"+entry.getKey();
                var canonical=Convergence.ITEMS.get(key).getDefaultInstance();
                for(int count:new int[]{1,canonical.getMaxStackSize()}) {
                    var identity=new CompoundTag();identity.putString("id",key);identity.putInt("count",1);
                    var customData=new CompoundTag();customData.put("$polymer:stack",identity);
                    var incoming=new ItemStack(entry.getValue(),count);
                    incoming.set(DataComponents.CUSTOM_DATA,CustomData.of(customData));
                    var wire=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,incoming).getOrThrow()).getOrThrow();
                    var real=PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
                    p.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket((short)36,real));
                    c.assertTrue(ItemStack.matches(p.getInventory().getItem(0),canonical.copyWithCount(count)),
                        "Actual Creative slot packet puts the canonical item and exact valid count in the hotbar: "+key);
                }
                var original=p.getInventory().getItem(0).copy();
                var before=RewardTrades.serialize(p).get("Inventory");
                c.assertTrue(before!=null,"Native player snapshot contains the actual inventory");
                var identity=new CompoundTag();identity.putString("id",key);identity.putInt("count",1);
                var customData=new CompoundTag();customData.put("$polymer:stack",identity);
                var incoming=new ItemStack(entry.getValue(),canonical.getMaxStackSize()+1);
                incoming.set(DataComponents.CUSTOM_DATA,CustomData.of(customData));
                var wire=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,incoming).getOrThrow()).getOrThrow();
                var oversized=PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
                c.assertValueEqual(oversized.getCount(),canonical.getMaxStackSize()+1,"Oversized transfer reaches native validation after Polymer decode: "+key);
                p.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket((short)36,oversized));
                c.assertTrue(ItemStack.matches(p.getInventory().getItem(0),original),"Native Creative handler rejects over-max transfers without replacing owned items: "+key);
                c.assertValueEqual(RewardTrades.serialize(p).get("Inventory"),before,"Rejected oversized item never enters the native persisted inventory: "+key);
            }
            var accepted=p.getInventory().getItem(0).copy();
            var snapshot=RewardTrades.serialize(p);
            var restored=new ServerPlayer(p.level().getServer(),p.level(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"creative-read"),p.clientInformation());
            restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,restored.registryAccess(),snapshot));
            c.assertTrue(ItemStack.matches(restored.getInventory().getItem(0),accepted),"Valid Creative selection survives actual native player save/load");
            for(var stack:restored.getInventory().getNonEquipmentItems())
                c.assertTrue(stack.isEmpty()||stack.getCount()<=stack.getMaxStackSize(),"Saved inventory never contains an oversized grant");
            p.setGameMode(GameType.SURVIVAL);
            var identity=new CompoundTag();identity.putString("id","convergence:helmet");identity.putInt("count",1);
            var customData=new CompoundTag();customData.put("$polymer:stack",identity);
            var incoming=new ItemStack(CrossplaySupport.BASES.get("helmet"));
            incoming.set(DataComponents.CUSTOM_DATA,CustomData.of(customData));
            var wire=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,incoming).getOrThrow()).getOrThrow();
            var real=PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
            var before=RewardTrades.serialize(p).get("Inventory");
            p.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket((short)36,real));
            c.assertTrue(ItemStack.matches(p.getInventory().getItem(0),accepted),"Survival rejects the same legitimate Creative item packet");
            c.assertValueEqual(RewardTrades.serialize(p).get("Inventory"),before,"Survival rejection leaves native persisted inventory unchanged");
        } finally {OperatorGameTests.deop(p);p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }
}

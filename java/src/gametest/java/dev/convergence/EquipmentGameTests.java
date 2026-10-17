package dev.convergence;

import net.minecraft.world.entity.EntityTypes;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

public class EquipmentGameTests {
    @GameTest public void separateConvergenceArmorKeepsOldWingsAndFullSetPowers(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"solid-armor27");
        try {
            var chest=gear("cuirass");var wings=gear("chestplate");
            c.assertFalse(chest.has(DataComponents.GLIDER),"The new solid armor is not a custom glider");
            c.assertTrue(wings.has(DataComponents.GLIDER),"Existing winged chestplate keeps its saved ID and flight component");
            c.assertValueEqual(chest.get(DataComponents.EQUIPPABLE).slot(),EquipmentSlot.CHEST,"Solid armor fits the real chest slot");
            c.assertValueEqual(chest.get(DataComponents.EQUIPPABLE).assetId().orElseThrow().identifier(),CrossplaySupport.id("cuirass"),"Solid armor has a dedicated equipment asset without wings");
            p.setItemSlot(EquipmentSlot.HEAD,gear("helmet"));p.setItemSlot(EquipmentSlot.CHEST,chest);
            p.setItemSlot(EquipmentSlot.LEGS,gear("leggings"));p.setItemSlot(EquipmentSlot.FEET,gear("boots"));
            p.doTick(); // Native equipment modifiers are installed during the player lifecycle tick.
            c.assertValueEqual(p.getArmorValue(),30,"All four real armor pieces contribute their protection");
            Convergence.armor(p);c.assertTrue(p.hasEffect(MobEffects.HEALTH_BOOST)&&p.hasEffect(MobEffects.RESISTANCE),"A full solid set activates the established armor powers");
            var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,c.getLevel().registryAccess());
            for(var item:java.util.List.of(chest,wings))c.assertTrue(ItemStack.isSameItemSameComponents(item,ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,item).getOrThrow()).getOrThrow()),"Both separate items save and reload unchanged");
        } finally {p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void creativeCollectionsDeliverRealItemsAndPreserveMenuGuards(GameTestHelper c) {
        var p=creative(c,"collections27");
        try {
            p.closeContainer();p.getInventory().clearContent();CreativeGearPicker.open(p);
            var all=(CreativeGearPicker.PickerHandler)p.containerMenu;
            all.clicked(CreativeGearPicker.AURORA,0,ContainerInput.QUICK_MOVE,p);
            var aurora=(CreativeGearPicker.PickerHandler)p.containerMenu;
            c.assertValueEqual(aurora.collection,CreativeGearPicker.Collection.AURORA,"Touch transfer opens the Aurora collection");
            c.assertValueEqual(aurora.paths.size(),4,"All four Aurora pieces are visible together");
            aurora.clicked(1,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.getMainHandItem().is(Convergence.ITEMS.get("convergence:aurora_chestplate")),"Collection selection gives the actual Aurora chestplate");
            p.closeContainer();CreativeGearPicker.open(p);var current=(CreativeGearPicker.PickerHandler)p.containerMenu;
            current.clicked(CreativeGearPicker.CONVERGENCE,0,ContainerInput.PICKUP,p);
            var convergence=(CreativeGearPicker.PickerHandler)p.containerMenu;
            c.assertValueEqual(convergence.paths.size(),5,"Convergence includes four solid armor pieces plus the existing flight item");
            convergence.clicked(convergence.paths.indexOf("convergence:cuirass"),0,ContainerInput.PICKUP,p);
            c.assertTrue(p.getMainHandItem().is(Convergence.ITEMS.get("convergence:cuirass")),"Choosing solid armor grants its distinct real ID");
            c.assertTrue(p.getInventory().contains(gear("aurora_chestplate")),"Choosing another item preserves the previous chestplate");
            CreativeGearPicker.open(p);var next=(CreativeGearPicker.PickerHandler)p.containerMenu;
            next.clicked(53,0,ContainerInput.QUICK_MOVE,p);var page=(CreativeGearPicker.PickerHandler)p.containerMenu;
            c.assertValueEqual(page.pageIndex,1,"All 63 registered items remain reachable through pagination");
            page.clicked(CreativeGearPicker.CONVERGENCE,0,ContainerInput.PICKUP,p);
            var stale=(CreativeGearPicker.PickerHandler)p.containerMenu;p.setGameMode(GameType.SURVIVAL);
            var before=p.getMainHandItem().copy();stale.clicked(0,0,ContainerInput.PICKUP,p);
            c.assertTrue(ItemStack.isSameItemSameComponents(before,p.getMainHandItem()),"Permission loss refuses a pending collection selection");
            c.assertTrue(p.containerMenu==p.inventoryMenu,"The denied stale menu closes");
        } finally {p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void namedCollectionBoxesCoverEveryItemOnceAndSurviveNativeSave(GameTestHelper c) {
        var counts=new java.util.HashMap<net.minecraft.world.item.Item,Integer>();
        var boxes=GearCrates.crates();c.assertValueEqual(boxes.size(),7,"Separate five armor sets and two tools/block boxes");
        var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,c.getLevel().registryAccess());
        for(var box:boxes) {
            c.assertTrue(GearCrates.isCrate(box),"Every labeled box is recognized, including Ember's orange box");
            var restored=ItemStack.CODEC.parse(ops,ItemStack.CODEC.encodeStart(ops,box).getOrThrow()).getOrThrow();
            c.assertTrue(ItemStack.isSameItemSameComponents(box,restored),"Labels and real custom contents survive native save/reload");
            box.get(DataComponents.CONTAINER).itemCopies().forEach(item->counts.merge(item.getItem(),1,Integer::sum));
        }
        for(var item:Convergence.ITEMS.values())c.assertValueEqual(counts.getOrDefault(item,0),1,"Every registered gear item occurs in exactly one box");
        c.assertTrue(boxes.get(0).getHoverName().getString().startsWith("Convergence Set"),"Convergence is clearly labeled");
        c.assertTrue(boxes.get(1).getHoverName().getString().startsWith("Aurora Set"),"Aurora is clearly labeled");
        c.assertValueEqual(boxes.get(1).get(DataComponents.CONTAINER).itemCopies().count(),4L,"Aurora contains all four pieces");
        c.succeed();
    }

    @GameTest public void directGearRecognizesWornArmorAndOffhandWithoutDuplicates(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"gear-equipped-retry");p.setGameMode(GameType.CREATIVE);
        try {
            p.closeContainer();p.getInventory().clearContent();
            c.assertValueEqual(GearCrates.giveDirect(p),1,"The original delivery completes");
            var equipped=java.util.Map.of(EquipmentSlot.HEAD,"helmet",EquipmentSlot.CHEST,"cuirass",
                EquipmentSlot.LEGS,"leggings",EquipmentSlot.FEET,"boots",EquipmentSlot.OFFHAND,"shield");
            for(var entry:equipped.entrySet()) {
                var item=Convergence.ITEMS.get("convergence:"+entry.getValue());
                for(int i=0;i<36;i++)if(p.getInventory().getItem(i).is(item)) {
                    var stack=p.getInventory().removeItemNoUpdate(i);
                    stack.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("My saved "+entry.getValue()));
                    p.setItemSlot(entry.getKey(),stack);break;
                }
                c.assertTrue(p.getItemBySlot(entry.getKey()).is(item),"The delivered piece is equipped: "+entry.getValue());
            }
            var before=new java.util.HashMap<net.minecraft.world.item.Item,Integer>();
            for(var item:Convergence.ITEMS.values())before.put(item,ownedCount(p,item));
            c.assertValueEqual(GearCrates.giveDirect(p),1,"Worn armor and offhand gear already satisfy ownership");
            for(var item:Convergence.ITEMS.values())c.assertValueEqual(ownedCount(p,item),before.get(item),"A repeat does not duplicate equipped gear");
            for(int i=0;i<36;i++)if(p.getInventory().getItem(i).isEmpty())p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            c.assertValueEqual(GearCrates.giveDirect(p),1,"A full main inventory needs no free slots when all gear is already owned");
            for(var entry:equipped.entrySet())c.assertValueEqual(p.getItemBySlot(entry.getKey()).getHoverName().getString(),"My saved "+entry.getValue(),"Named worn and offhand stacks remain intact");
            for(var item:Convergence.ITEMS.values())c.assertValueEqual(ownedCount(p,item),before.get(item),"The full-inventory repeat preserves total ownership");
        } finally {p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void solidChestplateCraftingAndNativeEnchantmentsUseItsOwnItem(GameTestHelper c) {
        var input=net.minecraft.world.item.crafting.CraftingInput.of(3,3,java.util.List.of(
            gear("ingot"),ItemStack.EMPTY,gear("ingot"),
            gear("ingot"),new ItemStack(Items.NETHERITE_CHESTPLATE),gear("ingot"),
            gear("ingot"),gear("ingot"),gear("ingot")));
        var recipes=c.getLevel().getServer().getRecipeManager();
        var recipe=recipes.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,input,c.getLevel());
        c.assertTrue(recipe.isPresent(),"Seven Infinity ingots and a real netherite chestplate match the native recipe manager");
        c.assertValueEqual(recipe.orElseThrow().id().identifier(),CrossplaySupport.id("cuirass"),"The upgrade selects the separate solid recipe");
        var result=recipe.orElseThrow().value().assemble(input);
        c.assertTrue(ItemStack.isSameItemSameComponents(result,gear("cuirass")),"Native crafting produces the actual solid chestplate and its equipment components");
        c.assertValueEqual(result.getCount(),1,"The upgrade produces one chestplate");
        c.assertFalse(result.has(DataComponents.GLIDER),"Crafting solid armor never gives flight");
        var wingsInput=net.minecraft.world.item.crafting.CraftingInput.of(3,3,java.util.List.of(
            gear("ingot"),ItemStack.EMPTY,gear("ingot"),gear("ingot"),gear("ingot"),gear("ingot"),gear("ingot"),gear("ingot"),gear("ingot")));
        var wingsRecipe=recipes.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,wingsInput,c.getLevel()).orElseThrow();
        c.assertTrue(ItemStack.isSameItemSameComponents(wingsRecipe.value().assemble(wingsInput),gear("chestplate")),"The original all-ingot wing recipe keeps its real saved ID");
        for(String tag:java.util.List.of("chest_armor","enchantable/armor","enchantable/chest_armor","enchantable/equippable","enchantable/durability","enchantable/vanishing"))
            c.assertTrue(result.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,net.minecraft.resources.Identifier.withDefaultNamespace(tag))),"The crafted chestplate belongs to the native tag: "+tag);
        var enchantments=c.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        for(var key:java.util.List.of(net.minecraft.world.item.enchantment.Enchantments.PROTECTION,net.minecraft.world.item.enchantment.Enchantments.THORNS,net.minecraft.world.item.enchantment.Enchantments.BINDING_CURSE,net.minecraft.world.item.enchantment.Enchantments.VANISHING_CURSE))
            c.assertTrue(enchantments.getOrThrow(key).value().canEnchant(result),"Native chest enchantment accepts the actual crafted item: "+key.identifier());
        c.assertFalse(enchantments.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FEATHER_FALLING).value().canEnchant(result),"Boot-only enchantments do not become chestplate enchantments");
        c.succeed();
    }

    @GameTest(maxTicks=60) public void fullInventoryDoesNotConsumeTheAutomaticGearReceipt(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"gear-retry27");p.setGameMode(GameType.CREATIVE);
        p.closeContainer();for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
        var flag="direct_gear27_"+GameModes.current(p).name().toLowerCase(java.util.Locale.ROOT);
        GameModes.state(p).putBoolean(flag,false);
        c.runAfterDelay(25,()->{
            try {
                c.assertFalse(GameModes.state(p).getBooleanOr(flag,false),"Normal tick delivery must not mark an unsuccessful full-inventory grant complete");
                for(int i=0;i<36;i++)c.assertTrue(p.getInventory().getItem(i).is(Items.DIRT),"Automatic delivery preserves each occupied slot");
                p.closeContainer();p.getInventory().clearContent();
                c.assertValueEqual(GearCrates.giveDirect(p),1,"After making room, the normal inventory action collects every missing piece");
                c.assertTrue(GameModes.state(p).getBooleanOr(flag,false),"Only a completed grant records its receipt");
                c.assertTrue(p.getInventory().contains(gear("cuirass")),"Retry includes the new solid chestplate");c.succeed();
            } finally {p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        });
    }

    ItemStack gear(String path){return new ItemStack(Convergence.ITEMS.get("convergence:"+path));}
    int ownedCount(ServerPlayer p,net.minecraft.world.item.Item item) {
        int count=0;for(int i=0;i<p.getInventory().getContainerSize();i++)if(p.getInventory().getItem(i).is(item))count+=p.getInventory().getItem(i).getCount();return count;
    }
    ServerPlayer creative(GameTestHelper c,String name){
        var p=new ModeGameTests().player(c,name);GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);return p;
    }
    BlockPos target(GameTestHelper c){var at=c.absolutePos(new BlockPos(2,2,2));return new BlockPos(at.getX(),100,at.getZ()+6);}
    void aim(ServerPlayer p,BlockPos target){
        p.setPos(target.getX()+.5,target.getY()-.9,target.getZ()-3);p.setNoGravity(true);
        p.lookAt(Anchor.EYES,Vec3.atCenterOf(target));
    }
    void wall(ServerPlayer p,BlockPos at){
        for(var pos:PoweredTools.plane(at,Direction.NORTH))p.level().setBlock(pos,Blocks.STONE.defaultBlockState(),3);
    }
    @GameTest public void creativeBuilderPlacesRealBlocksWithoutItemsAndProtectsEntities(GameTestHelper c){
        var p=creative(c,"creative-builder");var at=target(c);var world=p.level();wall(p,at);
        aim(p,at);p.setItemInHand(InteractionHand.MAIN_HAND,gear("builder_wand"));p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.QUARTZ_BLOCK,2));
        var occupied=at.north().east();var villager=EntityTypes.VILLAGER.create(world,EntitySpawnReason.COMMAND);
        c.assertTrue(villager!=null,"Villager fixture exists");villager.setNoAi(true);villager.setNoGravity(true);
        villager.setPos(occupied.getX()+.5,occupied.getY(),occupied.getZ()+.5);
        c.assertTrue(world.addFreshEntity(villager),"Villager fixture spawns in the Creative world");
        // Fresh entities become visible to the world's section collision index on the next tick.
        c.runAfterDelay(1,()->{
            c.assertTrue(world.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new net.minecraft.world.phys.AABB(occupied),e->e==villager).contains(villager),
                "Bystander is query-visible before placement; box="+villager.getBoundingBox());
            int placed=PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH);
            c.assertTrue(placed>0&&placed<9,"Builder places a partial valid plane, excluding occupied cells; actual="+placed+", bystander="+villager.getBoundingBox());
            c.assertTrue(world.getBlockState(at.north()).is(Blocks.QUARTZ_BLOCK),"Actual block placed on the hit face");
            c.assertTrue(world.getBlockState(occupied).isAir(),"Occupied cell remains open");
            c.assertTrue(villager.isAlive(),"Builder does not enclose or damage a bystander");
            c.assertValueEqual(p.getOffhandItem().getCount(),2,"Creative offhand blocks are not consumed");c.succeed();
        });
    }
    @GameTest public void creativeSculptorClearsRealPlaneAndPreservesChestContentsAndBedrock(GameTestHelper c){
        var p=creative(c,"creative-sculptor");var at=target(c);var world=p.level();wall(p,at);aim(p,at);
        var chest=at.east();world.setBlock(chest,Blocks.CHEST.defaultBlockState(),3);
        ((ChestBlockEntity)world.getBlockEntity(chest)).setItem(0,new ItemStack(Items.DIAMOND,7));
        var unbreakable=at.west().above();world.setBlock(unbreakable,Blocks.BEDROCK.defaultBlockState(),3);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("sculptor_wand"));
        c.assertValueEqual(PoweredTools.creativeUse(p,"convergence:sculptor_wand",at,Direction.NORTH),7,"Sculptor removes exactly seven editable blocks");
        c.assertTrue(world.getBlockState(at).isAir(),"Center block is actually removed");
        c.assertTrue(world.getBlockState(unbreakable).is(Blocks.BEDROCK),"Unbreakable block is untouched");
        c.assertValueEqual(((ChestBlockEntity)world.getBlockEntity(chest)).getItem(0).getCount(),7,"Chest and stored inventory survive the batch");c.succeed();
    }
    @GameTest public void creativeWandsRejectSurvivalProtectedMapsAndThroughWallTargets(GameTestHelper c){
        var p=creative(c,"creative-boundaries");var at=target(c);wall(p,at);aim(p,at);p.setItemInHand(InteractionHand.MAIN_HAND,gear("sculptor_wand"));
        p.setGameMode(GameType.SURVIVAL);
        c.assertValueEqual(CrossplaySupport.usePower(p,false),0,"Survival cannot activate a Creative wand");
        c.assertTrue(p.level().getBlockState(at).is(Blocks.STONE),"Denied action does not change blocks");
        p.setGameMode(GameType.CREATIVE);var obstruction=at.north(2);p.level().setBlock(obstruction,Blocks.STONE.defaultBlockState(),3);
        c.assertValueEqual(PoweredTools.creativeUse(p,"convergence:sculptor_wand",at,Direction.NORTH),0,"Server raycast refuses a hidden target");
        GameModes.switchNow(p,GameModes.Mode.HUB,null);p.setGameMode(GameType.CREATIVE);p.setItemInHand(InteractionHand.MAIN_HAND,gear("builder_wand"));
        c.assertFalse(PoweredTools.creativeAllowed(p,"convergence:builder_wand"),"Ordinary Creative mode does not grant wand editing in protected lobbies");c.succeed();
    }
    @GameTest public void cosmeticArmorEquipsDifferentModelsWithoutArmorOrInfinityPowers(GameTestHelper c){
        var p=creative(c,"cosmetic-outfits");
        for(String outfit:Set.of("aurora","ember")){
            for(var slot:EquipmentSlot.values())if(slot.isArmor())p.setItemSlot(slot,ItemStack.EMPTY);
            p.setItemSlot(EquipmentSlot.HEAD,gear(outfit+"_helmet"));p.setItemSlot(EquipmentSlot.CHEST,gear(outfit+"_chestplate"));
            p.setItemSlot(EquipmentSlot.LEGS,gear(outfit+"_leggings"));p.setItemSlot(EquipmentSlot.FEET,gear(outfit+"_boots"));
            c.assertValueEqual(p.getArmorValue(),0,"Cosmetic outfit has no armor protection");Convergence.armor(p);
            c.assertFalse(p.hasEffect(MobEffects.RESISTANCE)||p.hasEffect(MobEffects.REGENERATION),"Cosmetic armor does not activate Infinity armor effects");
            var chest=p.getItemBySlot(EquipmentSlot.CHEST);
            c.assertFalse(chest.has(DataComponents.GLIDER),"Cosmetic chest is not an elytra");
            c.assertValueEqual(chest.get(DataComponents.EQUIPPABLE).assetId().orElseThrow().identifier(),CrossplaySupport.id(outfit+"_armor"),"Each outfit resolves its own Java worn artwork");
            c.assertFalse(CrossplaySupport.NATIVE_BEDROCK.contains(outfit+"_chestplate"),"Bedrock selects the item's own static armor attachable instead of changing ordinary leather");
            c.assertValueEqual(chest.getMaxStackSize(),1,"Custom Bedrock wearables are never stackable");
        }
        c.assertFalse(gear("aurora_helmet").get(DataComponents.DYED_COLOR).equals(gear("ember_helmet").get(DataComponents.DYED_COLOR)),"Native dyed fallback distinguishes the outfits");c.succeed();
    }
    @GameTest public void creativeInfinityFarmAndWeaponActionsRemainFunctional(GameTestHelper c){
        var p=creative(c,"creative-powers");var at=target(c);var world=p.level();
        for(var pos:PoweredTools.plane(at,Direction.UP))world.setBlock(pos,Blocks.DIRT.defaultBlockState(),3);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("hoe"));p.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
        c.assertValueEqual(PoweredTools.farm(p,at),18,"Creative farm tills and plants all nine cells without seeds");
        for(var pos:PoweredTools.plane(at,Direction.UP))c.assertTrue(world.getBlockState(pos.above()).is(Blocks.WHEAT),"Creative crop is really planted");
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("sword"));int mode=Convergence.state(p).swordMode;
        c.assertValueEqual(CrossplaySupport.usePower(p,true),1,"Creative players activate weapon controls");
        c.assertValueEqual(Convergence.state(p).swordMode,(mode+1)%3,"Sword alternate control really changes its power preset");
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("spear"));
        c.assertValueEqual(CrossplaySupport.usePower(p,true),1,"Creative spear Dash is accepted");
        c.assertTrue(p.getDeltaMovement().lengthSqr()>.5,"Creative spear applies actual movement velocity");c.succeed();
    }
    @GameTest public void creativeHoldCommandPlacesWeaponInHandAndPreservesOldGear(GameTestHelper c){
        var p=creative(c,"creative-hold");
        var old=new ItemStack(Items.DIAMOND_PICKAXE);
        old.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep this pickaxe"));
        p.setItemInHand(InteractionHand.MAIN_HAND,old);
        p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.SHIELD));
        var dispatcher=c.getLevel().getServer().getCommands().getDispatcher();
        c.assertTrue(dispatcher.getRoot().getChild("convergence").getChild("hold").getChild("sword")!=null,
            "Players can discover the real hold command");
        try {c.assertValueEqual(dispatcher.execute("convergence hold sword",p.createCommandSourceStack()),1,
            "Creative command selects one Infinity weapon server-side");}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException error){throw new AssertionError(error);}
        c.assertValueEqual(p.getMainHandItem().getItem(),Convergence.ITEMS.get("convergence:sword"),"Sword is in selected hand");
        c.assertValueEqual(p.getOffhandItem().getItem(),Items.SHIELD,"Offhand is unaffected");
        c.assertTrue(p.getInventory().contains(old),"Previous named tool stays in inventory");
        c.assertValueEqual(Convergence.holdCreativeItem(p,"spear"),1,"Creative can switch to another weapon");
        c.assertValueEqual(p.getMainHandItem().getItem(),Convergence.ITEMS.get("convergence:spear"),"Spear is in selected hand");
        c.assertTrue(p.getInventory().contains(gear("sword")),"Previous Infinity sword stays in inventory");
        p.setGameMode(GameType.SURVIVAL);
        c.assertValueEqual(Convergence.holdCreativeItem(p,"mace"),0,"Non-operator Survival cannot conjure gear");
        c.assertValueEqual(p.getMainHandItem().getItem(),Convergence.ITEMS.get("convergence:spear"),"Denied command keeps held gear");
        try {
            OperatorGameTests.level(p,net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
            c.assertValueEqual(Convergence.holdCreativeItem(p,"mace"),1,"Level-2 operator has the same gear access as kit");
        } finally {OperatorGameTests.deop(p);}
        c.succeed();
    }
    @GameTest public void creativeHoldWithFullInventoryKeepsUniqueHeldItem(GameTestHelper c){
        var p=creative(c,"creative-full-inventory");
        for(int slot=0;slot<36;slot++)p.getInventory().setItem(slot,new ItemStack(Items.DIRT,64));
        var treasured=new ItemStack(Items.DIAMOND_PICKAXE);
        treasured.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("My special pickaxe"));
        p.setItemInHand(InteractionHand.MAIN_HAND,treasured);
        c.assertValueEqual(Convergence.holdCreativeItem(p,"sword"),0,"Full inventory rejects replacement");
        c.assertTrue(ItemStack.isSameItemSameComponents(p.getMainHandItem(),treasured),"Unique held item is unchanged");
        c.assertFalse(p.getInventory().contains(gear("sword")),"Denied command creates no weapon");
        c.succeed();
    }
    @GameTest public void creativePickerOpensAutomaticallyAndEquipsFromVanillaIcons(GameTestHelper c){
        var p=creative(c,"creative-picker");
        c.assertValueEqual(p.getMainHandItem().getItem(),Convergence.ITEMS.get("convergence:sword"),
            "New Creative profile begins with a sword already held");
        c.assertTrue(CreativeGearPicker.isPicker(p.getInventory().getItem(8)),"Named vanilla compass is in the hotbar");
        c.runAfterDelay(4,()->{
            c.assertTrue(p.containerMenu instanceof CreativeGearPicker.PickerHandler,
                "Picker opens after the Creative mode transition");
            var handler=(CreativeGearPicker.PickerHandler)p.containerMenu;
            int spear=handler.paths.indexOf("convergence:spear");
            c.assertTrue(spear>=0,"Spear appears in the picker");
            c.assertValueEqual(((net.minecraft.world.inventory.AbstractContainerMenu)handler).getSlot(spear).getItem().getItem(),Items.NETHERITE_SPEAR,
                "Picker shows a vanilla Bedrock-safe icon");
            ((net.minecraft.world.inventory.AbstractContainerMenu)handler).clicked(spear,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(p.getMainHandItem().getItem(),Convergence.ITEMS.get("convergence:spear"),
                "Tapping the icon equips the real Infinity spear");
            c.assertTrue(CreativeGearPicker.isPicker(p.getInventory().getItem(8)),
                "Compass stays in the hotbar for another pick");
            c.assertTrue(p.containerMenu.getCarried().isEmpty(),"No preview icon reaches the cursor");
            c.succeed();
        });
    }
    @GameTest public void creativePickerBlocksFakeItemMovesAndFullInventoryLoss(GameTestHelper c){
        var p=creative(c,"creative-picker-safe");
        var old=new ItemStack(Items.DIAMOND_PICKAXE);
        old.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep my tool"));
        p.getInventory().setItem(0,old);
        for(int slot=1;slot<36;slot++)if(slot!=8)p.getInventory().setItem(slot,new ItemStack(Items.DIRT,64));
        c.assertValueEqual(CreativeGearPicker.open(p),1,"Creative can open the vanilla picker");
        var handler=(CreativeGearPicker.PickerHandler)p.containerMenu;
        int sword=handler.paths.indexOf("convergence:sword");
        var icon=((net.minecraft.world.inventory.AbstractContainerMenu)handler).getSlot(sword).getItem().copy();
        ((net.minecraft.world.inventory.AbstractContainerMenu)handler).clicked(sword,0,ContainerInput.THROW,p);
        ((net.minecraft.world.inventory.AbstractContainerMenu)handler).clicked(sword,0,ContainerInput.PICKUP_ALL,p);
        ((net.minecraft.world.inventory.AbstractContainerMenu)handler).clicked(54,0,ContainerInput.PICKUP,p);
        c.assertTrue(ItemStack.isSameItemSameComponents(icon,((net.minecraft.world.inventory.AbstractContainerMenu)handler).getSlot(sword).getItem()),
            "Fake preview icons cannot be thrown, collected, or moved");
        c.assertTrue(((net.minecraft.world.inventory.AbstractContainerMenu)handler).getCarried().isEmpty(),"Picker never puts a fake item on the cursor");
        ((net.minecraft.world.inventory.AbstractContainerMenu)handler).clicked(sword,0,ContainerInput.QUICK_MOVE,p);
        c.assertTrue(ItemStack.isSameItemSameComponents(p.getInventory().getItem(0),old),
            "Full inventory keeps the unique held tool instead of dropping it");
        c.assertFalse(p.getInventory().contains(gear("sword")),"Failed selection creates no weapon");
        c.succeed();
    }
    @GameTest(maxTicks=20) public void creativePickerRecoversWithoutACommandAfterInventoryFills(GameTestHelper c){
        var p=creative(c,"creative-picker-recovery");
        for(int slot=0;slot<36;slot++)p.getInventory().setItem(slot,new ItemStack(Items.DIRT,64));
        c.runAfterDelay(4,()->{
            c.assertFalse(CreativeGearPicker.isPicker(p.getInventory().getItem(8)),
                "A full inventory is never overwritten to add the compass");
            p.closeContainer();
            p.getInventory().setItem(8,ItemStack.EMPTY);
            c.runAfterDelay(2,()->{
                c.assertTrue(CreativeGearPicker.isPicker(p.getInventory().getItem(8)),
                    "The picker returns when the player clears one slot");
                p.getInventory().setSelectedSlot(8);
                c.runAfterDelay(2,()->{
                    c.assertTrue(p.containerMenu instanceof CreativeGearPicker.PickerHandler,
                        "Selecting the restored compass opens the menu without a command");
                    p.getInventory().setItem(0,ItemStack.EMPTY);
                    var handler=(CreativeGearPicker.PickerHandler)p.containerMenu;
                    ((net.minecraft.world.inventory.AbstractContainerMenu)handler).clicked(handler.paths.indexOf("convergence:sword"),0,ContainerInput.PICKUP,p);
                    c.assertValueEqual(p.getMainHandItem().getItem(),Convergence.ITEMS.get("convergence:sword"),
                        "After clearing a hotbar slot, the picker equips a real weapon");
                    c.succeed();
                });
            });
        });
    }
    @GameTest public void creativeInfinityPickaxeUsesActualBlockActionWithoutDrops(GameTestHelper c){
        var p=creative(c,"creative-excavation");var at=target(c);var world=p.level();wall(p,at);aim(p,at);
        p.setItemInHand(InteractionHand.MAIN_HAND,gear("pickaxe"));
        var hit=new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(at),Direction.NORTH,at,false);
        var result=p.getMainHandItem().getItem().useOn(new net.minecraft.world.item.context.UseOnContext(p,InteractionHand.MAIN_HAND,hit));
        c.assertTrue(result==net.minecraft.world.InteractionResult.SUCCESS,"Creative block Use dispatches the mining tool");
        for(var pos:PoweredTools.plane(at,Direction.NORTH))c.assertTrue(world.getBlockState(pos).isAir(),"Creative excavation actually removes all nine blocks");
        c.assertTrue(world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(at).inflate(2),e->true).isEmpty(),"Creative excavation produces no Survival resource drops");
        c.assertValueEqual(p.getMainHandItem().getDamageValue(),0,"Creative tool remains undamaged");c.succeed();
    }
    @GameTest public void decorativeBuildingRegistryUsesOriginalModelsAndLight(GameTestHelper c){
        var seen=new java.util.HashSet<String>();
        for(String path:ExpandedGear.NEW_BLOCKS){
            var block=BuiltInRegistries.BLOCK.getValue(CrossplaySupport.id(path));
            c.assertValueEqual(block.defaultBlockState().getLightEmission(),ExpandedGear.light(path),"Server-side decoration light is real");
            c.assertValueEqual(block.defaultBlockState().getDestroySpeed(c.getLevel(),BlockPos.ZERO),3.0F,"New decoration is mineable with normal hardness");
            c.assertTrue(seen.add(CrossplaySupport.stateIdentifier(CrossplaySupport.BLOCK_STATES.get(path))),"Distinct block visuals are exported for Java and Bedrock");
        }
        c.assertValueEqual(ExpandedGear.NEW_BLOCKS.size(),11,"Six established building materials and five Elemental rune styles are registered");c.succeed();
    }

    @GameTest public void gearBoxesPreserveInventoryContainEveryItemAndPlaceNatively(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"crate-creative");p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        try {
            p.closeContainer();
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,13));
            c.assertValueEqual(GearCrates.give(p),1,"Creative receives the real boxes");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),13,"An occupied slot is preserved");
            var contents=new java.util.HashSet<net.minecraft.world.item.Item>();ItemStack first=null;
            for(int i=0;i<36;i++)if(GearCrates.isCrate(p.getInventory().getItem(i))) {
                var box=p.getInventory().getItem(i);
                box.get(DataComponents.CONTAINER).itemCopies().forEach(s->contents.add(s.getItem()));
                if(first==null)first=box.copy();
            }
            c.assertTrue(contents.containsAll(Convergence.ITEMS.values()),"Boxes cover every registered Convergence item");
            c.assertTrue(first!=null,"There is a placeable box");
            var base=c.absolutePos(new BlockPos(2,2,2));
            c.getLevel().setBlockAndUpdate(base,Blocks.STONE.defaultBlockState());c.getLevel().setBlockAndUpdate(base.above(),Blocks.AIR.defaultBlockState());
            p.setItemInHand(InteractionHand.MAIN_HAND,first);
            p.setPos(base.getX()+4.5,base.getY()+1,base.getZ()+.5);
            var hit=new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(base).add(0,.5,0),Direction.UP,base,false);
            first.getItem().useOn(new net.minecraft.world.item.context.UseOnContext(p,InteractionHand.MAIN_HAND,hit));
            var placed=c.getLevel().getBlockEntity(base.above());
            c.assertTrue(placed instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity,"The real vanilla item places a native storage box");
            c.assertFalse(((net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)placed).getItem(0).isEmpty(),"Placed box retains actual custom contents");
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            c.assertValueEqual(GearCrates.give(p),0,"Full inventory refuses a new set without dropping or overwriting items");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),64,"Full inventory stays intact");
        } finally { p.closeContainer();p.level().getServer().getPlayerList().remove(p); }
        c.succeed();
    }

    @GameTest public void missingJavaPackUsesCompleteNativeArmorAssetsAndRoundTrips(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"armor-fallback");
        try {
            var context=p.connection.getPacketContext();
            c.assertFalse(eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.hasMainPack(context),"Fixture has not accepted Java pack");
            for(String path:java.util.List.of("helmet","cuirass","chestplate","leggings","boots","backpack","aurora_helmet","aurora_chestplate","ember_leggings")) {
                var original=gear(path);var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(original,context,c.getLevel().registryAccess());
                c.assertValueEqual(wire.get(DataComponents.EQUIPPABLE),CrossplaySupport.BASES.get(path).components().get(DataComponents.EQUIPPABLE),"Native wearable asset is complete when Java art is unavailable: "+path);
                var restored=eu.pb4.polymer.core.api.item.PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
                c.assertTrue(ItemStack.isSameItemSameComponents(restored,original),"Client fallback never mutates server equipment: "+path);
            }
        } finally { p.level().getServer().getPlayerList().remove(p); }
        c.succeed();
    }

    @GameTest public void allGearHasLiteralNamesAndDirectInventoryIncludesChestplatesWithoutLoss(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"gear-direct14");p.setGameMode(GameType.CREATIVE);
        try {
            p.closeContainer();p.getInventory().clearContent();
            var cherished=new ItemStack(Items.DIAMOND,13);p.getInventory().setItem(0,cherished);
            c.assertValueEqual(GearCrates.giveDirect(p),1,"Direct gear goes into actual player inventory");
            for(String path:GearCrates.STARTER)c.assertTrue(p.getInventory().contains(gear(path)),"Starter gear is directly accessible: "+path);
            for(String path:ExpandedGear.COSMETIC_ARMOR.keySet())c.assertTrue(p.getInventory().contains(gear(path)),"Every appearance includes its chestplate: "+path);
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),13,"Existing occupied slot is preserved");
            long occupied=p.getInventory().getNonEquipmentItems().stream().filter(stack->!stack.isEmpty()).count();
            c.assertValueEqual(GearCrates.giveDirect(p),1,"Repeated direct grant finds existing gear");
            c.assertValueEqual(p.getInventory().getNonEquipmentItems().stream().filter(stack->!stack.isEmpty()).count(),occupied,"Repeated direct grant creates no duplicate gear");
            var context=p.connection.getPacketContext();
            for(var entry:Convergence.ITEMS.entrySet()) {
                var stack=new ItemStack(entry.getValue());var path=entry.getKey().split(":")[1];
                c.assertValueEqual(stack.getHoverName().getString(),GearNames.label(path),"Server item name is a complete human label");
                stack.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("My own name"));
                var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(stack,context,c.getLevel().registryAccess());
                c.assertValueEqual(wire.get(DataComponents.ITEM_NAME).getString(),GearNames.label(path),"Native wire item has a literal translated name");
                c.assertValueEqual(wire.getHoverName().getString(),"My own name","User anvil name is preserved");
                if(CrossplaySupport.NATIVE_BEDROCK.contains(path)&&stack.has(DataComponents.EQUIPPABLE))
                    c.assertValueEqual(wire.get(DataComponents.EQUIPPABLE),CrossplaySupport.BASES.get(path).components().get(DataComponents.EQUIPPABLE),"Every native wearable uses a complete client asset");
            }
            for(int n=0;n<36;n++)p.getInventory().setItem(n,new ItemStack(Items.DIRT,64));
            c.assertValueEqual(GearCrates.giveDirect(p),0,"Full inventory refuses safely");
            for(int n=0;n<36;n++)c.assertTrue(p.getInventory().getItem(n).is(Items.DIRT)&&p.getInventory().getItem(n).getCount()==64,"Full inventory is untouched");
        } finally {p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void savedLegacyGearNamesAreReadableWithoutChangingTheSavedItem(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"gear-legacy-names");
        try {
            var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,c.getLevel().registryAccess());
            for(var entry:Convergence.ITEMS.entrySet()) {
                String path=entry.getKey().split(":")[1];
                String key=(ExpandedGear.BLOCKS.contains(path)?"block":"item")+".convergence."+path;
                var original=new ItemStack(entry.getValue(),1);
                original.set(DataComponents.ITEM_NAME,net.minecraft.network.chat.Component.translatable(key));
                original.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.translatable(key)
                    .withStyle(net.minecraft.ChatFormatting.AQUA).append(net.minecraft.network.chat.Component.literal(" • treasured")));
                var saved=ItemStack.CODEC.encodeStart(ops,original).getOrThrow();
                var restored=ItemStack.CODEC.parse(ops,saved).getOrThrow();
                var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(restored,p.connection.getPacketContext(),c.getLevel().registryAccess());
                c.assertFalse(wire.get(DataComponents.CUSTOM_NAME).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,
                    "Native name must not depend on the missing client language key: "+key);
                c.assertValueEqual(wire.getHoverName().getString(),GearNames.label(path)+" • treasured","Old translated custom names resolve on native clients: "+path);
                c.assertValueEqual(wire.get(DataComponents.CUSTOM_NAME).getStyle(),original.get(DataComponents.CUSTOM_NAME).getStyle(),"Name colors survive");
                c.assertValueEqual(ItemStack.CODEC.encodeStart(ops,restored).getOrThrow(),saved,"Sending a readable name never rewrites saved items");
                var returned=eu.pb4.polymer.core.api.item.PolymerItemUtils.getRealItemStack(wire,c.getLevel().registryAccess());
                c.assertValueEqual(ItemStack.CODEC.encodeStart(ops,returned).getOrThrow(),saved,"Creative round-trip restores every original saved component and count");
            }
        } finally {p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void readableGearNamesPreservePlayerTextAndOtherLanguages(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"gear-player-names");
        try {
            var stack=gear("sword");
            for(var custom:java.util.List.of(
                net.minecraft.network.chat.Component.literal("My sword ✨").withStyle(net.minecraft.ChatFormatting.GOLD),
                net.minecraft.network.chat.Component.literal("item.convergence.sword"),
                net.minecraft.network.chat.Component.translatable("item.minecraft.diamond_sword"),
                net.minecraft.network.chat.Component.translatable("block.convergence.sword"),
                net.minecraft.network.chat.Component.translatable("item.convergence.moonstone"),
                net.minecraft.network.chat.Component.translatable("item.convergence.not_registered"),
                net.minecraft.network.chat.Component.translatableWithFallback("anothermod.custom", "Other label", "arg"))) {
                stack.set(DataComponents.CUSTOM_NAME,custom);
                var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(stack,p.connection.getPacketContext(),c.getLevel().registryAccess());
                c.assertValueEqual(wire.get(DataComponents.CUSTOM_NAME),custom,"Player text and other translation keys remain intact");
                c.assertValueEqual(stack.get(DataComponents.CUSTOM_NAME),custom,"Authoritative name is untouched");
            }
            var nested=net.minecraft.network.chat.Component.translatable("chat.type.text","Alex",
                net.minecraft.network.chat.Component.translatable("item.convergence.sword").withStyle(net.minecraft.ChatFormatting.AQUA));
            stack.set(DataComponents.CUSTOM_NAME,nested);
            var wire=eu.pb4.polymer.core.api.item.PolymerItemUtils.getPolymerItemStack(stack,p.connection.getPacketContext(),c.getLevel().registryAccess());
            var outer=(net.minecraft.network.chat.contents.TranslatableContents)wire.get(DataComponents.CUSTOM_NAME).getContents();
            var argument=(net.minecraft.network.chat.Component)outer.getArgs()[1];
            c.assertValueEqual(outer.getKey(),"chat.type.text","Vanilla language-sensitive wrapper is retained");
            c.assertFalse(argument.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,"Nested gear label needs no custom language file");
            c.assertValueEqual(argument.getString(),"Infinity Sword","Nested gear label is readable");
            c.assertValueEqual(argument.getStyle(),((net.minecraft.network.chat.Component)((net.minecraft.network.chat.contents.TranslatableContents)nested.getContents()).getArgs()[1]).getStyle(),"Nested style survives");
            c.assertValueEqual(stack.get(DataComponents.CUSTOM_NAME),nested,"Nested saved name is untouched");
        } finally {p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

}

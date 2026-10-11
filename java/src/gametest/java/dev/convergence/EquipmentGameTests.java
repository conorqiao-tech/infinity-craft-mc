package dev.convergence;

import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor;
import net.minecraft.world.GameMode;

public class EquipmentGameTests {
    ItemStack gear(String path){return new ItemStack(Convergence.ITEMS.get("convergence:"+path));}
    ServerPlayerEntity creative(TestContext c,String name){
        var p=new ModeGameTests().player(c,name);GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);return p;
    }
    BlockPos target(TestContext c){var at=c.getAbsolutePos(new BlockPos(2,2,2));return new BlockPos(at.getX(),100,at.getZ()+6);}
    void aim(ServerPlayerEntity p,BlockPos target){
        p.setPosition(target.getX()+.5,target.getY()-.9,target.getZ()-3);p.setNoGravity(true);
        p.lookAt(EntityAnchor.EYES,Vec3d.ofCenter(target));
    }
    void wall(ServerPlayerEntity p,BlockPos at){
        for(var pos:PoweredTools.plane(at,Direction.NORTH))p.getEntityWorld().setBlockState(pos,Blocks.STONE.getDefaultState(),3);
    }
    @GameTest public void creativeBuilderPlacesRealBlocksWithoutItemsAndProtectsEntities(TestContext c){
        var p=creative(c,"creative-builder");var at=target(c);var world=p.getEntityWorld();wall(p,at);
        aim(p,at);p.setStackInHand(Hand.MAIN_HAND,gear("builder_wand"));p.setStackInHand(Hand.OFF_HAND,new ItemStack(Items.QUARTZ_BLOCK,2));
        var occupied=at.north().east();var villager=EntityType.VILLAGER.create(world,SpawnReason.COMMAND);
        c.assertTrue(villager!=null,"Villager fixture exists");villager.setAiDisabled(true);villager.setNoGravity(true);
        villager.setPosition(occupied.getX()+.5,occupied.getY(),occupied.getZ()+.5);
        c.assertTrue(world.spawnEntity(villager),"Villager fixture spawns in the Creative world");
        // Fresh entities become visible to the world's section collision index on the next tick.
        c.waitAndRun(1,()->{
            c.assertTrue(world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class,new net.minecraft.util.math.Box(occupied),e->e==villager).contains(villager),
                "Bystander is query-visible before placement; box="+villager.getBoundingBox());
            int placed=PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH);
            c.assertTrue(placed>0&&placed<9,"Builder places a partial valid plane, excluding occupied cells; actual="+placed+", bystander="+villager.getBoundingBox());
            c.assertTrue(world.getBlockState(at.north()).isOf(Blocks.QUARTZ_BLOCK),"Actual block placed on the hit face");
            c.assertTrue(world.getBlockState(occupied).isAir(),"Occupied cell remains open");
            c.assertTrue(villager.isAlive(),"Builder does not enclose or damage a bystander");
            c.assertEquals(p.getOffHandStack().getCount(),2,"Creative offhand blocks are not consumed");c.complete();
        });
    }
    @GameTest public void creativeSculptorClearsRealPlaneAndPreservesChestContentsAndBedrock(TestContext c){
        var p=creative(c,"creative-sculptor");var at=target(c);var world=p.getEntityWorld();wall(p,at);aim(p,at);
        var chest=at.east();world.setBlockState(chest,Blocks.CHEST.getDefaultState(),3);
        ((ChestBlockEntity)world.getBlockEntity(chest)).setStack(0,new ItemStack(Items.DIAMOND,7));
        var unbreakable=at.west().up();world.setBlockState(unbreakable,Blocks.BEDROCK.getDefaultState(),3);
        p.setStackInHand(Hand.MAIN_HAND,gear("sculptor_wand"));
        c.assertEquals(PoweredTools.creativeUse(p,"convergence:sculptor_wand",at,Direction.NORTH),7,"Sculptor removes exactly seven editable blocks");
        c.assertTrue(world.getBlockState(at).isAir(),"Center block is actually removed");
        c.assertTrue(world.getBlockState(unbreakable).isOf(Blocks.BEDROCK),"Unbreakable block is untouched");
        c.assertEquals(((ChestBlockEntity)world.getBlockEntity(chest)).getStack(0).getCount(),7,"Chest and stored inventory survive the batch");c.complete();
    }
    @GameTest public void creativeWandsRejectSurvivalProtectedMapsAndThroughWallTargets(TestContext c){
        var p=creative(c,"creative-boundaries");var at=target(c);wall(p,at);aim(p,at);p.setStackInHand(Hand.MAIN_HAND,gear("sculptor_wand"));
        p.changeGameMode(GameMode.SURVIVAL);
        c.assertEquals(CrossplaySupport.usePower(p,false),0,"Survival cannot activate a Creative wand");
        c.assertTrue(p.getEntityWorld().getBlockState(at).isOf(Blocks.STONE),"Denied action does not change blocks");
        p.changeGameMode(GameMode.CREATIVE);var obstruction=at.north(2);p.getEntityWorld().setBlockState(obstruction,Blocks.STONE.getDefaultState(),3);
        c.assertEquals(PoweredTools.creativeUse(p,"convergence:sculptor_wand",at,Direction.NORTH),0,"Server raycast refuses a hidden target");
        GameModes.switchNow(p,GameModes.Mode.HUB,null);p.changeGameMode(GameMode.CREATIVE);p.setStackInHand(Hand.MAIN_HAND,gear("builder_wand"));
        c.assertFalse(PoweredTools.creativeAllowed(p,"convergence:builder_wand"),"Ordinary Creative mode does not grant wand editing in protected lobbies");c.complete();
    }
    @GameTest public void cosmeticArmorEquipsDifferentModelsWithoutArmorOrInfinityPowers(TestContext c){
        var p=creative(c,"cosmetic-outfits");
        for(String outfit:Set.of("aurora","ember")){
            for(var slot:EquipmentSlot.values())if(slot.isArmorSlot())p.equipStack(slot,ItemStack.EMPTY);
            p.equipStack(EquipmentSlot.HEAD,gear(outfit+"_helmet"));p.equipStack(EquipmentSlot.CHEST,gear(outfit+"_chestplate"));
            p.equipStack(EquipmentSlot.LEGS,gear(outfit+"_leggings"));p.equipStack(EquipmentSlot.FEET,gear(outfit+"_boots"));
            c.assertEquals(p.getArmor(),0,"Cosmetic outfit has no armor protection");Convergence.armor(p);
            c.assertFalse(p.hasStatusEffect(StatusEffects.RESISTANCE)||p.hasStatusEffect(StatusEffects.REGENERATION),"Cosmetic armor does not activate Infinity armor effects");
            var chest=p.getEquippedStack(EquipmentSlot.CHEST);
            c.assertFalse(chest.contains(DataComponentTypes.GLIDER),"Cosmetic chest is not an elytra");
            c.assertEquals(chest.get(DataComponentTypes.EQUIPPABLE).assetId().orElseThrow().getValue(),CrossplaySupport.id(outfit+"_armor"),"Each outfit resolves its own Java worn artwork");
            c.assertTrue(CrossplaySupport.NATIVE_BEDROCK.contains(outfit+"_chestplate"),"Bedrock retains native wearable controls and dyed leather appearance");
        }
        c.assertFalse(gear("aurora_helmet").get(DataComponentTypes.DYED_COLOR).equals(gear("ember_helmet").get(DataComponentTypes.DYED_COLOR)),"Native dyed fallback distinguishes the outfits");c.complete();
    }
    @GameTest public void creativeInfinityFarmAndWeaponActionsRemainFunctional(TestContext c){
        var p=creative(c,"creative-powers");var at=target(c);var world=p.getEntityWorld();
        for(var pos:PoweredTools.plane(at,Direction.UP))world.setBlockState(pos,Blocks.DIRT.getDefaultState(),3);
        p.setStackInHand(Hand.MAIN_HAND,gear("hoe"));p.setStackInHand(Hand.OFF_HAND,ItemStack.EMPTY);
        c.assertEquals(PoweredTools.farm(p,at),18,"Creative farm tills and plants all nine cells without seeds");
        for(var pos:PoweredTools.plane(at,Direction.UP))c.assertTrue(world.getBlockState(pos.up()).isOf(Blocks.WHEAT),"Creative crop is really planted");
        p.setStackInHand(Hand.MAIN_HAND,gear("sword"));int mode=Convergence.state(p).swordMode;
        c.assertEquals(CrossplaySupport.usePower(p,true),1,"Creative players activate weapon controls");
        c.assertEquals(Convergence.state(p).swordMode,(mode+1)%3,"Sword alternate control really changes its power preset");
        p.setStackInHand(Hand.MAIN_HAND,gear("spear"));
        c.assertEquals(CrossplaySupport.usePower(p,true),1,"Creative spear Dash is accepted");
        c.assertTrue(p.getVelocity().lengthSquared()>.5,"Creative spear applies actual movement velocity");c.complete();
    }
    @GameTest public void creativeInfinityPickaxeUsesActualBlockActionWithoutDrops(TestContext c){
        var p=creative(c,"creative-excavation");var at=target(c);var world=p.getEntityWorld();wall(p,at);aim(p,at);
        p.setStackInHand(Hand.MAIN_HAND,gear("pickaxe"));
        var hit=new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(at),Direction.NORTH,at,false);
        var result=p.getMainHandStack().getItem().useOnBlock(new net.minecraft.item.ItemUsageContext(p,Hand.MAIN_HAND,hit));
        c.assertTrue(result==net.minecraft.util.ActionResult.SUCCESS,"Creative block Use dispatches the mining tool");
        for(var pos:PoweredTools.plane(at,Direction.NORTH))c.assertTrue(world.getBlockState(pos).isAir(),"Creative excavation actually removes all nine blocks");
        c.assertTrue(world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,new net.minecraft.util.math.Box(at).expand(2),e->true).isEmpty(),"Creative excavation produces no Survival resource drops");
        c.assertEquals(p.getMainHandStack().getDamage(),0,"Creative tool remains undamaged");c.complete();
    }
    @GameTest public void decorativeBuildingRegistryUsesOriginalModelsAndLight(TestContext c){
        var seen=new java.util.HashSet<String>();
        for(String path:ExpandedGear.NEW_BLOCKS){
            var block=Registries.BLOCK.get(CrossplaySupport.id(path));
            c.assertEquals(block.getDefaultState().getLuminance(),ExpandedGear.light(path),"Server-side decoration light is real");
            c.assertEquals(block.getDefaultState().getHardness(c.getWorld(),BlockPos.ORIGIN),3.0F,"New decoration is mineable with normal hardness");
            c.assertTrue(seen.add(CrossplaySupport.stateIdentifier(CrossplaySupport.BLOCK_STATES.get(path))),"Distinct block visuals are exported for Java and Bedrock");
        }
        c.assertEquals(ExpandedGear.NEW_BLOCKS.size(),6,"Six original building materials are registered");c.complete();
    }
}

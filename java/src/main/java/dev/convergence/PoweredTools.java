package dev.convergence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.Box;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

final class PoweredTools {
    static final Set<String> CREATIVE_TOOLS=Set.of("convergence:builder_wand","convergence:sculptor_wand");
    static final Set<String> IDS=Set.of("convergence:pickaxe","convergence:axe","convergence:shovel","convergence:hoe");
    static final Set<String> STONE=Set.of("stone","granite","diorite","andesite","deepslate","cobblestone","cobbled_deepslate","tuff","calcite","dripstone_block","netherrack","basalt","smooth_basalt","blackstone","end_stone","obsidian","coal_ore","iron_ore","copper_ore","gold_ore","redstone_ore","lapis_ore","diamond_ore","emerald_ore","deepslate_coal_ore","deepslate_iron_ore","deepslate_copper_ore","deepslate_gold_ore","deepslate_redstone_ore","deepslate_lapis_ore","deepslate_diamond_ore","deepslate_emerald_ore","nether_quartz_ore","nether_gold_ore","ancient_debris","convergence:infinity_block","convergence:polished_infinity","convergence:infinity_bricks","convergence:radiant_infinity","convergence:aurora_tiles","convergence:obsidian_lattice","convergence:copper_circuit","convergence:moonstone","convergence:sunstone_lamp","convergence:verdant_mosaic");
    static final Set<String> SOIL=Set.of("dirt","grass_block","coarse_dirt","rooted_dirt","podzol","mycelium","sand","red_sand","gravel","clay","mud","soul_sand","soul_soil","snow","snow_block","dirt_path");
    static final Set<String> LOGS=Set.of("oak_log","spruce_log","birch_log","jungle_log","acacia_log","dark_oak_log","mangrove_log","cherry_log","pale_oak_log","crimson_stem","warped_stem");
    static final Set<String> TILL=Set.of("dirt","grass_block","coarse_dirt","rooted_dirt","dirt_path");
    static boolean allowed(ServerPlayerEntity p,String id) {
        return IDS.contains(id)&&p.isAlive()&&!p.isSpectator()&&p.canModifyBlocks()&&Convergence.id(p.getMainHandStack()).equals(id);
    }
    static String blockId(ServerPlayerEntity p,BlockPos at) {
        var id=Registries.BLOCK.getId(p.getEntityWorld().getBlockState(at).getBlock());
        if(id.getNamespace().equals("minecraft"))return id.getPath();
        return id.getNamespace().equals("convergence")?id.toString():"";
    }
    static boolean editable(ServerPlayerEntity p,BlockPos at) {
        var w=p.getEntityWorld();
        return w.isInBuildLimit(at)&&w.isPosLoaded(at)&&w.getWorldBorder().contains(at)&&w.canEntityModifyAt(p,at)&&p.canModifyAt(w,at)&&w.getBlockEntity(at)==null;
    }
    static List<BlockPos> plane(BlockPos at,Direction face) {
        List<BlockPos> out=new ArrayList<>();out.add(at.toImmutable());
        for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++) {
            if(a==0&&b==0)continue;
            out.add(switch(face.getAxis()){case Y->at.add(a,0,b);case X->at.add(0,a,b);case Z->at.add(a,b,0);});
        }
        return out;
    }
    static List<BlockPos> logs(ServerPlayerEntity p,BlockPos at) {
        String species=blockId(p,at);List<BlockPos> result=new ArrayList<>();
        if(!LOGS.contains(species))return result;
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();queue.add(at.toImmutable());
        while(!queue.isEmpty()&&result.size()<32) {
            var q=queue.removeFirst();if(!seen.add(q)||Math.max(Math.abs(q.getX()-at.getX()),Math.max(Math.abs(q.getY()-at.getY()),Math.abs(q.getZ()-at.getZ())))>8)continue;
            if(!editable(p,q)||!blockId(p,q).equals(species))continue;
            result.add(q);for(Direction d:Direction.values())queue.add(q.offset(d));
        }
        return result;
    }
    static void aimed(ServerPlayerEntity p,String id) {
        if(!allowed(p,id))return;
        if(p.isSneaking()){combat(p,id);return;}
        var hit=p.raycast(6,1,false);
        if(hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK)use(p,id,b.getBlockPos(),b.getSide());
        else Convergence.say(p,"Aim at a block within 6 blocks.");
    }
    static int use(ServerPlayerEntity p,String id,BlockPos at,Direction face) {
        if(!allowed(p,id))return 0;
        if(p.isSneaking()){combat(p,id);return 0;}
        if(p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(at))>49||!editable(p,at))return 0;
        // Recheck the engine raycast so direct calls cannot reach through a wall.
        var hit=p.raycast(6,1,false);
        if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK||!b.getBlockPos().equals(at))return 0;
        if(id.equals("convergence:hoe")) {
            if(!Convergence.ready(p,"tool_hoe",20))return 0;
            int count=farm(p,blockId(p,at).equals("wheat")?at.down():at);
            Convergence.say(p,"Farm Bloom: "+count+" changes; planting uses wheat seeds");return count;
        }
        Set<String> materials=id.equals("convergence:pickaxe")?STONE:id.equals("convergence:shovel")?SOIL:LOGS;
        if(!materials.contains(blockId(p,at))){Convergence.say(p,"Aim at natural stone/ore, soil, or an unstripped log for this tool.");return 0;}
        if(!Convergence.ready(p,"tool_"+id,30))return 0;
        int count=0;
        for(var q:id.equals("convergence:axe")?logs(p,at):plane(at,face)) {
            if(editable(p,q)&&materials.contains(blockId(p,q))&&p.interactionManager.tryBreakBlock(q))count++;
        }
        Convergence.say(p,(id.equals("convergence:axe")?"Timber":id.equals("convergence:pickaxe")?"Excavation":"Earthmover")+": "+count+" blocks");
        return count;
    }
    static int farm(ServerPlayerEntity p,BlockPos center) {
        int count=0;var w=p.getEntityWorld();
        for(var at:plane(center,Direction.UP)) {
            var above=at.up();if(!editable(p,at)||!editable(p,above))continue;
            var soil=w.getBlockState(at);var crop=w.getBlockState(above);
            if(soil.isOf(Blocks.FARMLAND)&&crop.isOf(Blocks.WHEAT)) {
                CropBlock wheat=(CropBlock)Blocks.WHEAT;
                if(wheat.getAge(crop)<wheat.getMaxAge()&&w.setBlockState(above,wheat.withAge(wheat.getMaxAge()),3))count++;
                continue;
            }
            if(!crop.isAir())continue;
            if(TILL.contains(blockId(p,at))) {
                if(!w.setBlockState(at,Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE,7),3))continue;
                count++;
            }else if(!soil.isOf(Blocks.FARMLAND))continue;
            int seed=-1;
            for(int slot=0;slot<p.getInventory().size();slot++)if(p.getInventory().getStack(slot).isOf(Items.WHEAT_SEEDS)){seed=slot;break;}
            if(!p.isCreative()&&seed<0)continue;
            if(w.setBlockState(above,Blocks.WHEAT.getDefaultState(),3)) {
                if(!p.isCreative())p.getInventory().getStack(seed).decrement(1);
                count++;
            }
        }
        return count;
    }
    static boolean creativeAllowed(ServerPlayerEntity p,String id) {
        return CREATIVE_TOOLS.contains(id)&&p.isAlive()&&p.isCreative()&&p.canModifyBlocks()
            &&Convergence.id(p.getMainHandStack()).equals(id)
            &&(GameModes.of(p.getEntityWorld())==GameModes.Mode.CREATIVE||GameModes.operator(p));
    }
    static void creativeAimed(ServerPlayerEntity p,String id) {
        if(!creativeAllowed(p,id)){Convergence.say(p,"Building wands need Creative mode in the Creative world; OP4 can build in other worlds with /gamemode creative.");return;}
        var hit=p.raycast(p.getBlockInteractionRange(),1,false);
        if(hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK)creativeUse(p,id,b.getBlockPos(),b.getSide());
        else Convergence.say(p,"Aim at a block within 6 blocks. Builder uses a solid block in your offhand; Sculptor clears a 3x3 face.");
    }
    static int creativeUse(ServerPlayerEntity p,String id,BlockPos at,Direction face) {
        if(!creativeAllowed(p,id)){Convergence.say(p,"Building wands require Creative mode in the Creative world, or OP4 Creative access.");return 0;}
        if(p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(at))>Math.pow(p.getBlockInteractionRange()+1,2)||!editable(p,at))return 0;
        var hit=p.raycast(p.getBlockInteractionRange(),1,false);
        if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK||!b.getBlockPos().equals(at)||b.getSide()!=face)return 0;
        var world=p.getEntityWorld();boolean build=id.equals("convergence:builder_wand");
        net.minecraft.block.BlockState material=null;
        if(build) {
            if(!(p.getOffHandStack().getItem() instanceof BlockItem blockItem)||blockItem.getBlock() instanceof BlockEntityProvider) {
                Convergence.say(p,"Builder needs a solid cube block in your offhand. Containers and special blocks are not supported.");return 0;
            }
            material=blockItem.getBlock().getDefaultState();
            if(!material.isFullCube(world,at)||material.getHardness(world,at)<0)return 0;
        }
        if(!Convergence.ready(p,"creative_wand",5))return 0;
        int changed=0;
        for(var pos:plane(build?at.offset(face):at,face)) {
            if(!editable(p,pos))continue;
            var before=world.getBlockState(pos);
            if(build) {
                if(!before.isAir()||!world.canPlace(material,pos,ShapeContext.absent())||!world.getEntitiesByClass(LivingEntity.class,new Box(pos),e->e.isAlive()&&!e.isSpectator()).isEmpty())continue;
                if(world.setBlockState(pos,material,3))changed++;
            } else if(!before.isAir()&&before.getHardness(world,pos)>=0&&!(before.getBlock() instanceof BlockEntityProvider)) {
                if(world.setBlockState(pos,Blocks.AIR.getDefaultState(),3))changed++;
            }
        }
        Convergence.say(p,(build?"Builder":"Sculptor")+": "+changed+" blocks. Containers and protected positions are preserved.");
        return changed;
    }

    static void combat(ServerPlayerEntity p,String id) {
        if(!allowed(p,id))return;
        if(id.equals("convergence:hoe")) {
            if(!Convergence.ready(p,"tool_heal",120))return;
            p.setHealth(p.getMaxHealth());p.extinguish();
            Convergence.effect(p,net.minecraft.entity.effect.StatusEffects.REGENERATION,2,100);
            Convergence.say(p,"Renewal: health restored");return;
        }
        if(!Convergence.ready(p,"tool_combat_"+id,60))return;
        int count=0;
        for(var e:Convergence.monsters(p,8)) {
            var direction=p.getEntityPos().subtract(e.getEntityPos()).normalize();
            if(id.equals("convergence:pickaxe"))e.addVelocity(direction.multiply(0.9).add(0,0.2,0));
            else if(id.equals("convergence:shovel"))e.addVelocity(direction.multiply(-1.3).add(0,0.9,0));
            e.velocityDirty=true;
            if(Convergence.hurt(p,e,id.equals("convergence:axe")?240:id.equals("convergence:pickaxe")?80:40))count++;
            Convergence.sparks(p,e,12);
        }
        Convergence.say(p,(id.equals("convergence:axe")?"Thunder Cleave":id.equals("convergence:pickaxe")?"Gravity Well":"Repulse")+": "+count+" monsters");
    }
}

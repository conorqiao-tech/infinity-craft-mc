package dev.convergence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

final class PoweredTools {
    static final Set<String> CREATIVE_TOOLS=Set.of("convergence:builder_wand","convergence:sculptor_wand");
    static final Set<String> IDS=Set.of("convergence:pickaxe","convergence:axe","convergence:shovel","convergence:hoe");
    static final Set<String> STONE=Set.of("stone","granite","diorite","andesite","deepslate","cobblestone","cobbled_deepslate","tuff","calcite","dripstone_block","netherrack","basalt","smooth_basalt","blackstone","end_stone","obsidian","coal_ore","iron_ore","copper_ore","gold_ore","redstone_ore","lapis_ore","diamond_ore","emerald_ore","deepslate_coal_ore","deepslate_iron_ore","deepslate_copper_ore","deepslate_gold_ore","deepslate_redstone_ore","deepslate_lapis_ore","deepslate_diamond_ore","deepslate_emerald_ore","nether_quartz_ore","nether_gold_ore","ancient_debris","convergence:infinity_block","convergence:polished_infinity","convergence:infinity_bricks","convergence:radiant_infinity","convergence:aurora_tiles","convergence:obsidian_lattice","convergence:copper_circuit","convergence:moonstone","convergence:sunstone_lamp","convergence:verdant_mosaic");
    static final Set<String> SOIL=Set.of("dirt","grass_block","coarse_dirt","rooted_dirt","podzol","mycelium","sand","red_sand","gravel","clay","mud","soul_sand","soul_soil","snow","snow_block","dirt_path");
    static final Set<String> LOGS=Set.of("oak_log","spruce_log","birch_log","jungle_log","acacia_log","dark_oak_log","mangrove_log","cherry_log","pale_oak_log","crimson_stem","warped_stem");
    static final Set<String> TILL=Set.of("dirt","grass_block","coarse_dirt","rooted_dirt","dirt_path");
    static boolean allowed(ServerPlayer p,String id) {
        return IDS.contains(id)&&p.isAlive()&&!p.isSpectator()&&p.mayBuild()&&Convergence.id(p.getMainHandItem()).equals(id);
    }
    static String blockId(ServerPlayer p,BlockPos at) {
        var id=BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(at).getBlock());
        if(id.getNamespace().equals("minecraft"))return id.getPath();
        return id.getNamespace().equals("convergence")?id.toString():"";
    }
    static boolean editable(ServerPlayer p,BlockPos at) {
        var w=p.level();
        return w.isInWorldBounds(at)&&w.isLoaded(at)&&w.getWorldBorder().isWithinBounds(at)&&w.mayInteract(p,at)&&p.mayInteract(w,at)&&w.getBlockEntity(at)==null;
    }
    static List<BlockPos> plane(BlockPos at,Direction face) {
        List<BlockPos> out=new ArrayList<>();out.add(at.immutable());
        for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++) {
            if(a==0&&b==0)continue;
            out.add(switch(face.getAxis()){case Y->at.offset(a,0,b);case X->at.offset(0,a,b);case Z->at.offset(a,b,0);});
        }
        return out;
    }
    static List<BlockPos> logs(ServerPlayer p,BlockPos at) {
        String species=blockId(p,at);List<BlockPos> result=new ArrayList<>();
        if(!LOGS.contains(species))return result;
        var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();queue.add(at.immutable());
        while(!queue.isEmpty()&&result.size()<32) {
            var q=queue.removeFirst();if(!seen.add(q)||Math.max(Math.abs(q.getX()-at.getX()),Math.max(Math.abs(q.getY()-at.getY()),Math.abs(q.getZ()-at.getZ())))>8)continue;
            if(!editable(p,q)||!blockId(p,q).equals(species))continue;
            result.add(q);for(Direction d:Direction.values())queue.add(q.relative(d));
        }
        return result;
    }
    static void aimed(ServerPlayer p,String id) {
        if(!allowed(p,id))return;
        if(p.isShiftKeyDown()){combat(p,id);return;}
        var hit=p.pick(6,1,false);
        if(hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK)use(p,id,b.getBlockPos(),b.getDirection());
        else Convergence.say(p,"Aim at a block within 6 blocks.");
    }
    static int use(ServerPlayer p,String id,BlockPos at,Direction face) {
        if(!allowed(p,id))return 0;
        if(p.isShiftKeyDown()){combat(p,id);return 0;}
        if(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(at))>49||!editable(p,at))return 0;
        // Recheck the engine raycast so direct calls cannot reach through a wall.
        var hit=p.pick(6,1,false);
        if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK||!b.getBlockPos().equals(at))return 0;
        if(id.equals("convergence:hoe")) {
            if(!Convergence.ready(p,"tool_hoe",20))return 0;
            int count=farm(p,blockId(p,at).equals("wheat")?at.below():at);
            Convergence.say(p,"Farm Bloom: "+count+" changes; planting uses wheat seeds");return count;
        }
        Set<String> materials=id.equals("convergence:pickaxe")?STONE:id.equals("convergence:shovel")?SOIL:LOGS;
        if(!materials.contains(blockId(p,at))){Convergence.say(p,"Aim at natural stone/ore, soil, or an unstripped log for this tool.");return 0;}
        if(!Convergence.ready(p,"tool_"+id,30))return 0;
        int count=0;
        for(var q:id.equals("convergence:axe")?logs(p,at):plane(at,face)) {
            if(editable(p,q)&&materials.contains(blockId(p,q))&&p.gameMode.destroyBlock(q))count++;
        }
        Convergence.say(p,(id.equals("convergence:axe")?"Timber":id.equals("convergence:pickaxe")?"Excavation":"Earthmover")+": "+count+" blocks");
        return count;
    }
    static int farm(ServerPlayer p,BlockPos center) {
        int count=0;var w=p.level();
        for(var at:plane(center,Direction.UP)) {
            var above=at.above();if(!editable(p,at)||!editable(p,above))continue;
            var soil=w.getBlockState(at);var crop=w.getBlockState(above);
            if(soil.is(Blocks.FARMLAND)&&crop.is(Blocks.WHEAT)) {
                CropBlock wheat=(CropBlock)Blocks.WHEAT;
                if(wheat.getAge(crop)<wheat.getMaxAge()&&w.setBlock(above,wheat.getStateForAge(wheat.getMaxAge()),3))count++;
                continue;
            }
            if(!crop.isAir())continue;
            if(TILL.contains(blockId(p,at))) {
                if(!w.setBlock(at,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7),3))continue;
                count++;
            }else if(!soil.is(Blocks.FARMLAND))continue;
            int seed=-1;
            for(int slot=0;slot<p.getInventory().getContainerSize();slot++)if(p.getInventory().getItem(slot).is(Items.WHEAT_SEEDS)){seed=slot;break;}
            if(!p.isCreative()&&seed<0)continue;
            if(w.setBlock(above,Blocks.WHEAT.defaultBlockState(),3)) {
                if(!p.isCreative())p.getInventory().getItem(seed).shrink(1);
                count++;
            }
        }
        return count;
    }
    static boolean creativeAllowed(ServerPlayer p,String id) {
        return CREATIVE_TOOLS.contains(id)&&CreativeStudio.allowed(p)
            &&Convergence.id(p.getMainHandItem()).equals(id)
            &&(GameModes.of(p.level())==GameModes.Mode.CREATIVE||GameModes.operator(p));
    }
    static void creativeAimed(ServerPlayer p,String id) {
        if(!creativeAllowed(p,id)){Convergence.say(p,"Building wands need Creative mode in the Creative world; OP4 can build in other worlds with /gamemode creative.");return;}
        var hit=p.pick(p.blockInteractionRange(),1,false);
        if(hit instanceof BlockHitResult b&&hit.getType()==HitResult.Type.BLOCK)creativeUse(p,id,b.getBlockPos(),b.getDirection());
        else Convergence.say(p,"Aim at a block within 6 blocks. Builder uses a solid block in your offhand; Sculptor clears a 3x3 face.");
    }
    static int creativeUse(ServerPlayer p,String id,BlockPos at,Direction face) {
        if(!creativeAllowed(p,id)){Convergence.say(p,"Building wands require Creative mode in the Creative world, or OP4 Creative access.");return 0;}
        if(p.getEyePosition().distanceToSqr(Vec3.atCenterOf(at))>Math.pow(p.blockInteractionRange()+1,2)||!editable(p,at))return 0;
        var hit=p.pick(p.blockInteractionRange(),1,false);
        if(!(hit instanceof BlockHitResult b)||hit.getType()!=HitResult.Type.BLOCK||!b.getBlockPos().equals(at)||b.getDirection()!=face)return 0;
        var world=p.level();boolean build=id.equals("convergence:builder_wand");
        net.minecraft.world.level.block.state.BlockState material=null;
        if(build) {
            if(!(p.getOffhandItem().getItem() instanceof BlockItem blockItem)||blockItem.getBlock() instanceof EntityBlock) {
                Convergence.say(p,"Builder needs a solid cube block in your offhand. Containers and special blocks are not supported.");return 0;
            }
            material=blockItem.getBlock().defaultBlockState();
            if(!material.isCollisionShapeFullBlock(world,at)||material.getDestroySpeed(world,at)<0)return 0;
        }
        if(!Convergence.ready(p,"creative_wand",5))return 0;
        int changed=0;var changes=new ArrayList<CreativeStudio.Change>();
        for(var pos:CreativeStudio.brushPositions(p,build?at.relative(face):at,face)) {
            if(!editable(p,pos))continue;
            var before=world.getBlockState(pos);
            if(build) {
                if(!before.isAir()||!world.isUnobstructed(material,pos,CollisionContext.empty())||!world.getEntitiesOfClass(LivingEntity.class,new AABB(pos),e->e.isAlive()&&!e.isSpectator()).isEmpty())continue;
                if(world.setBlock(pos,material,3)){changed++;changes.add(new CreativeStudio.Change(pos,before,material));}
            } else if(!before.isAir()&&before.getDestroySpeed(world,pos)>=0&&!(before.getBlock() instanceof EntityBlock)) {
                if(world.setBlock(pos,Blocks.AIR.defaultBlockState(),3)){changed++;changes.add(new CreativeStudio.Change(pos,before,Blocks.AIR.defaultBlockState()));}
            }
        }
        CreativeStudio.record(p,changes);
        Convergence.say(p,(build?"Builder":"Sculptor")+": "+changed+" blocks. Studio Undo restores this edit; containers and protected positions are preserved.");
        return changed;
    }

    static void combat(ServerPlayer p,String id) {
        if(!allowed(p,id))return;
        if(id.equals("convergence:hoe")) {
            if(!Convergence.ready(p,"tool_heal",120))return;
            p.setHealth(p.getMaxHealth());p.clearFire();
            Convergence.effect(p,net.minecraft.world.effect.MobEffects.REGENERATION,2,100);
            Convergence.say(p,"Renewal: health restored");return;
        }
        if(!Convergence.ready(p,"tool_combat_"+id,60))return;
        int count=0;
        for(var e:Convergence.monsters(p,8)) {
            var direction=p.position().subtract(e.position()).normalize();
            if(id.equals("convergence:pickaxe"))e.push(direction.scale(0.9).add(0,0.2,0));
            else if(id.equals("convergence:shovel"))e.push(direction.scale(-1.3).add(0,0.9,0));
            e.needsSync=true;
            if(Convergence.hurt(p,e,id.equals("convergence:axe")?240:id.equals("convergence:pickaxe")?80:40))count++;
            Convergence.sparks(p,e,12);
        }
        Convergence.say(p,(id.equals("convergence:axe")?"Thunder Cleave":id.equals("convergence:pickaxe")?"Gravity Well":"Repulse")+": "+count+" monsters");
    }
}

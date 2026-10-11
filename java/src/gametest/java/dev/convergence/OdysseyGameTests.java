package dev.convergence;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

public class OdysseyGameTests {
    static final List<String> THEMES=List.of("plains","forest","desert","badlands","snow","ocean","jungle","swamp","mushroom","cherry","pale_garden","dark_forest","savanna","mountain","caves","nether_wastes","crimson","warped","soul","basalt","end");
    static final List<String> LOOT=List.of("minecraft:written_book","minecraft:totem_of_undying","minecraft:turtle_helmet","minecraft:elytra","minecraft:netherite_spear","minecraft:mace","minecraft:trident","minecraft:redstone_block","minecraft:command_block","minecraft:wind_charge","minecraft:fishing_rod","minecraft:end_crystal","minecraft:obsidian","minecraft:tnt","minecraft:tnt","minecraft:flint_and_steel","convergence:aurora_helmet","convergence:aurora_chestplate","convergence:aurora_leggings","convergence:aurora_boots","convergence:mace","convergence:sword","convergence:spear","convergence:pickaxe","convergence:axe","convergence:shovel","convergence:hoe");
    static final Set<Integer> STACKS=Set.of(7,9,11,12,13,14);

    @GameTest public void allOdysseyTemplatesHaveOneCacheAndExactNativeLoot(GameTestHelper c) {
        var manager=c.getLevel().getStructureTemplateManager();
        var ops=c.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(String theme:THEMES) {
            var template=manager.get(Identifier.parse("convergence:odyssey/"+theme)).orElseThrow();
            var caches=template.filterBlocks(BlockPos.ZERO,new StructurePlaceSettings(),Blocks.CHEST);
            c.assertValueEqual(caches.size(),1,"One storage block in "+theme);
            var items=caches.getFirst().nbt().getListOrEmpty("Items");
            c.assertValueEqual(items.size(),27,"Exact lower chest retains every slot");
            for(int i=0;i<27;i++) {
                var nbt=items.getCompoundOrEmpty(i);
                var item=ItemStack.CODEC.parse(ops,nbt).getOrThrow();
                c.assertValueEqual(nbt.getIntOr("Slot",-1),i,"Original slot retained");
                c.assertValueEqual(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),LOOT.get(i),"Exact original item in slot "+i);
                c.assertValueEqual(item.getCount(),STACKS.contains(i)?64:1,"Original stack count");
                if(i==0) {
                    var book=item.get(DataComponents.WRITTEN_BOOK_CONTENT);
                    c.assertValueEqual(book.title().raw(),"The Odessy ","Original loot book title unchanged");
                    c.assertValueEqual(book.pages().getFirst().raw().getString(),"the chosen person is you… the acient propecy says that…","Original authored prophecy unchanged");
                }
            }
            c.assertValueEqual(template.filterBlocks(BlockPos.ZERO,new StructurePlaceSettings(),Blocks.BARREL).size(),0,"No second storage block");
        }
        c.succeed();
    }

    @GameTest public void odysseyRepeaterRetainsExactAutomaticCommand(GameTestHelper c) {
        for(String theme:THEMES) {
            var t=c.getLevel().getStructureTemplateManager().get(Identifier.parse("convergence:odyssey/"+theme)).orElseThrow();
            var repeaters=t.filterBlocks(BlockPos.ZERO,new StructurePlaceSettings(),Blocks.REPEATING_COMMAND_BLOCK);
            c.assertValueEqual(repeaters.size(),1,"One repeating block");
            var block=repeaters.getFirst();
            c.assertValueEqual(block.nbt().getStringOr("Command",""),"/ban aria","Owner explicitly requested exact command");
            c.assertTrue(block.nbt().getBooleanOr("auto",false),"Owner chose automatic execution");
            c.assertFalse(block.nbt().getBooleanOr("TrackOutput",true),"Do not retain growing command output in each block");
            c.assertValueEqual(block.pos(),new BlockPos(4,1,4),"Repeater stays below original chest");
        }
        c.succeed();
    }

    @GameTest public void placedNativeChestActuallyLoadsEveryOriginalSlot(GameTestHelper c) {
        var at=c.absolutePos(new BlockPos(0,2,0));
        var template=c.getLevel().getStructureTemplateManager().get(Identifier.parse("convergence:odyssey/desert")).orElseThrow();
        c.assertTrue(template.placeInWorld(c.getLevel(),at,at,new StructurePlaceSettings().setIgnoreEntities(true),c.getLevel().getRandom(),2),"Native placement succeeds");
        var chest=(ChestBlockEntity)c.getLevel().getBlockEntity(at.offset(4,6,5));
        c.assertTrue(chest!=null,"Real storage block exists");
        for(int i=0;i<27;i++) {
            var item=chest.getItem(i);
            c.assertValueEqual(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),LOOT.get(i),"Native container slot "+i);
            c.assertValueEqual(item.getCount(),STACKS.contains(i)?64:1,"Native stack count");
        }
        c.succeed();
    }

    @GameTest public void everyNativeBiomeIsEligibleAndHasARealPalette(GameTestHelper c) {
        var tag=TagKey.create(Registries.BIOME,Identifier.parse("convergence:has_structure/odyssey_ruin"));
        var biomes=c.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        for(var biome:biomes.listElements().toList()) {
            if(!biome.key().identifier().getNamespace().equals("minecraft"))continue;
            c.assertTrue(biome.is(tag),"Eligible native biome: "+biome.key().identifier());
            String theme=OdysseyStructure.theme(biome.key().identifier().toString());
            c.assertTrue(c.getLevel().getStructureTemplateManager().get(Identifier.parse("convergence:odyssey/"+theme)).isPresent(),"Native template exists for "+biome.key().identifier());
        }
        var ruins=c.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse("convergence:odyssey_ruin")));
        c.assertTrue(ruins.value() instanceof OdysseyStructure,"Registered real native structure");c.succeed();
    }

    @GameTest public void nativeWorldgenBuildsOnePieceWithSparseRegisteredPlacement(GameTestHelper c) {
        var world=c.getLevel();var registry=world.registryAccess();
        var ruin=registry.lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE,Identifier.parse("convergence:odyssey_ruin")));
        var generator=world.getChunkSource().getGenerator();var random=world.getChunkSource().randomState();
        var climate=random.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
        var context=new net.minecraft.world.level.levelgen.structure.Structure.GenerationContext(registry,generator,generator.getBiomeSource(),climate,random,world.getStructureTemplateManager(),world.getSeed(),new net.minecraft.world.level.ChunkPos(123,456),world,biome->true);
        var stub=ruin.value().findValidGenerationPoint(context).orElseThrow();
        c.assertValueEqual(stub.getPiecesBuilder().build().pieces().size(),1,"Native generation produces one persistent structure piece");
        var set=registry.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,Identifier.parse("convergence:odyssey_ruins"))).value();
        var placement=(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)set.placement();
        c.assertValueEqual(placement.spacing(),40,"Sparse candidate per 40 by 40 chunk region");
        c.assertValueEqual(placement.separation(),16,"Candidates retain native separation");c.succeed();
    }

    @GameTest public void netherFloorRejectsRoofLavaAndShortCavities(GameTestHelper c) {
        BlockState[] blocks=new BlockState[128];Arrays.fill(blocks,Blocks.NETHERRACK.defaultBlockState());
        for(int y=64;y<74;y++)blocks[y]=Blocks.AIR.defaultBlockState();
        for(int y=90;y<94;y++)blocks[y]=Blocks.AIR.defaultBlockState();
        for(int y=100;y<112;y++)blocks[y]=Blocks.LAVA.defaultBlockState();
        for(int y=120;y<128;y++)blocks[y]=Blocks.BEDROCK.defaultBlockState();
        c.assertValueEqual(OdysseyStructure.netherFloor(new NoiseColumn(0,blocks),32,112),64,"Uses full-height dry cavern below roof");
        Arrays.fill(blocks,Blocks.AIR.defaultBlockState());
        c.assertValueEqual(OdysseyStructure.netherFloor(new NoiseColumn(0,blocks),32,112),Integer.MIN_VALUE,"Never floats in empty void");c.succeed();
    }

    @GameTest public void onlyOriginalGeneratedRepeaterCanRunExactOwnerBan(GameTestHelper c) {
        var world=c.getLevel();var at=c.absolutePos(new BlockPos(0,2,0));
        var piece=new OdysseyStructure.Piece(world.getStructureTemplateManager(),at,"plains",net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
        var pos=piece.repeaterPosition();var chunk=world.getChunkAt(pos);
        var ruin=world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();
        var oldStart=chunk.getStartForStructure(ruin);
        var oldReferences=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunk.getReferencesForStructure(ruin));
        var start=new net.minecraft.world.level.levelgen.structure.StructureStart(ruin,chunk.getPos(),0,
            new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
        var admin=new net.minecraft.server.permissions.Permission.HasCommandLevel(net.minecraft.server.permissions.PermissionLevel.ADMINS);
        world.setBlock(pos,Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState(),2);
        var block=(net.minecraft.world.level.block.entity.CommandBlockEntity)world.getBlockEntity(pos);
        var command=block.getCommandBlock();command.setCommand("/ban aria");
        var source=command.createCommandSourceStack(world,net.minecraft.commands.CommandSource.NULL);
        try {
            c.assertFalse(source.permissions().hasPermission(admin),"Native command block stays OP2");
            c.assertTrue(OdysseyStructure.repeatingSource(command,source)==source,"A placed copy outside generation gains no permission");
            chunk.setStartForStructure(ruin,start);chunk.addReferenceForStructure(ruin,chunk.getPos().pack());
            c.assertTrue(OdysseyStructure.repeatingSource(command,source).permissions().hasPermission(admin),"Exact original rotated ruin repeater can run requested ban");
            command.setCommand("/ban someone_else");
            c.assertTrue(OdysseyStructure.repeatingSource(command,source)==source,"Another player cannot be banned using this exception");
            command.setCommand("/op aria");
            c.assertTrue(OdysseyStructure.repeatingSource(command,source)==source,"No other privileged command gains permission");
            command.setCommand("/ban aria");
            var moved=pos.above();world.setBlock(moved,Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState(),2);
            var other=((net.minecraft.world.level.block.entity.CommandBlockEntity)world.getBlockEntity(moved)).getCommandBlock();other.setCommand("/ban aria");
            c.assertFalse(OdysseyStructure.repeatingSource(other,other.createCommandSourceStack(world,net.minecraft.commands.CommandSource.NULL)).permissions().hasPermission(admin),"A different block inside the same ruin is not privileged");
            world.removeBlock(moved,false);
        } finally {
            chunk.setStartForStructure(ruin,oldStart==null?net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START:oldStart);
            chunk.getReferencesForStructure(ruin).clear();chunk.getReferencesForStructure(ruin).addAll(oldReferences);
            world.removeBlock(pos,false);
        }
        c.succeed();
    }

    @GameTest public void generatedPieceSchedulesRepeaterAndRetainsRotationAfterReload(GameTestHelper c) {
        var world=c.getLevel();var at=c.absolutePos(new BlockPos(0,2,0));
        var piece=new OdysseyStructure.Piece(world.getStructureTemplateManager(),at,"warped",net.minecraft.world.level.block.Rotation.CLOCKWISE_180);
        var tag=piece.createTag(net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext.fromLevel(world));
        var loaded=new OdysseyStructure.Piece(world.getStructureTemplateManager(),tag);
        c.assertValueEqual(loaded.repeaterPosition(),piece.repeaterPosition(),"Reloaded piece retains exact rotated repeater position");
        loaded.postProcess(world,world.structureManager(),world.getChunkSource().getGenerator(),world.getRandom(),loaded.getBoundingBox(),net.minecraft.world.level.ChunkPos.containing(at),BlockPos.ZERO);
        c.assertTrue(world.getBlockState(loaded.repeaterPosition()).is(Blocks.REPEATING_COMMAND_BLOCK),"Native piece places repeating block");
        c.assertTrue(world.getBlockTicks().hasScheduledTick(loaded.repeaterPosition(),Blocks.REPEATING_COMMAND_BLOCK),"Generation schedules the first native tick");
        c.succeed();
    }

    @GameTest public void prophecyNeverOverwritesAndRetriesFullInventories(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"odyssey-full");
        try {
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIAMOND,64));
            c.assertFalse(OdysseyProphecy.give(p,false),"Full inventory waits without losing items");
            c.assertFalse(GameModes.state(p).getBooleanOr(OdysseyProphecy.deliveryKey(p),false),"Failed delivery is not marked complete");
            for(int i=0;i<36;i++)c.assertValueEqual(p.getInventory().getItem(i).getCount(),64,"Existing stack preserved");
            p.getInventory().setItem(5,ItemStack.EMPTY);
            c.assertTrue(OdysseyProphecy.give(p,false),"Retry succeeds when a slot is freed");
            c.assertTrue(OdysseyProphecy.isBook(p.getInventory().getItem(5)),"Native readable written book delivered");
            c.assertTrue(OdysseyProphecy.give(p,true),"Recovery does not duplicate an existing copy");
            c.assertValueEqual(p.getInventory().getNonEquipmentItems().stream().filter(OdysseyProphecy::isBook).count(),1L,"Exactly one prophecy");
            p.getInventory().setItem(5,ItemStack.EMPTY);
            c.assertTrue(OdysseyProphecy.give(p,false),"Spawn receipt survives losing a copy");
            c.assertTrue(p.getInventory().getItem(5).isEmpty(),"No repeated automatic free deliveries");
            c.assertTrue(OdysseyProphecy.give(p,true),"Menu can recover a lost copy");
        } finally {c.getLevel().getServer().getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void prophecyIsAvailableThroughNormalMenuWithoutOp(GameTestHelper c) {
        var p=new ModeGameTests().player(c,"odyssey-menu");
        try {
            OperatorGameTests.deop(p);p.getInventory().clearContent();
            ServerMenu.open(p);p.containerMenu.clicked(ServerMenu.PROPHECY,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.containerMenu==p.inventoryMenu,"Prophecy action closes the menu");
            c.assertTrue(p.getInventory().getNonEquipmentItems().stream().anyMatch(OdysseyProphecy::isBook),"Ordinary player receives the readable book");
        } finally {p.closeContainer();c.getLevel().getServer().getPlayerList().remove(p);}
        c.succeed();
    }
}

package dev.convergence;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class PlaygroundGameTests {
    @GameTest public void nativeGadgetsWorkThroughHeldPowerAndRespectCreativeAccess(GameTestHelper c){
        var fixture=new CreativeStudioGameTests();var p=fixture.player(c,"playground-gadgets");
        try{
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,CreativeStudio.control(p,"gust"));
            c.assertValueEqual(CrossplaySupport.usePower(p,false),1,"Native feather gadget works through the menu's actual power dispatch");
            c.assertTrue(p.getDeltaMovement().y>0,"Real player receives the upward gust");
            c.assertValueEqual(CreativeStudio.controlKind(p,new ItemStack(Items.FEATHER)),"","Ordinary feathers keep native behavior");
            p.setGameMode(GameType.SURVIVAL);
            c.assertValueEqual(CreativeStudio.useControl(p,CreativeStudio.control(p,"anchor")),0,"Survival cannot use Creative teleport tools");
        }finally{fixture.cleanup(p);}c.succeed();
    }
    @GameTest public void anchorReturnsSafelyAndRefusesBlockedOrDistantLocations(GameTestHelper c){
        var fixture=new CreativeStudioGameTests();var p=fixture.player(c,"playground-anchor");
        try{
            var origin=p.position();CreativeStudio.setAnchor(p);p.setPos(origin.add(4,0,0));
            c.assertValueEqual(CreativeStudio.returnAnchor(p),1,"Same-world loaded anchor returns the real player");
            c.assertTrue(p.position().distanceToSqr(origin)<.001,"Position restored exactly");
            p.setPos(origin.add(4,0,0));var block=BlockPos.containing(origin);
            p.level().setBlock(block,Blocks.STONE.defaultBlockState(),3);
            c.assertValueEqual(CreativeStudio.returnAnchor(p),0,"Occupied destination cannot trap player");
            c.assertTrue(p.position().distanceToSqr(origin)>1,"Rejected teleport preserves position");
            p.level().removeBlock(block,false);p.setPos(origin.add(49,0,0));
            c.assertValueEqual(CreativeStudio.returnAnchor(p),0,"Distance limit cannot load faraway terrain");
        }finally{fixture.cleanup(p);}c.succeed();
    }
    @GameTest public void tidewardenHasAllFourNativeArmorSlotsAndAtomicDelivery(GameTestHelper c){
        var fixture=new CreativeStudioGameTests();var p=fixture.player(c,"playground-tide");
        try{
            var stacks=CreativeStudio.outfit(p,3);c.assertValueEqual(stacks.size(),4,"All four pieces exist");
            var slots=new java.util.HashSet<net.minecraft.world.entity.EquipmentSlot>();
            for(var stack:stacks){slots.add(stack.get(DataComponents.EQUIPPABLE).slot());c.assertTrue(stack.has(DataComponents.TRIM),"Native trim is present");c.assertTrue(stack.getHoverName().getString().startsWith("Tidewarden"),"Literal readable name");}
            c.assertValueEqual(slots.size(),4,"Helmet, chestplate, leggings and boots use different slots");
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            for(int i=0;i<3;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
            c.assertValueEqual(CreativeStudio.deliver(p,stacks),0,"Three slots cannot partially deliver four pieces");
            for(int i=0;i<3;i++)c.assertTrue(p.getInventory().getItem(i).isEmpty(),"No partial reward or duplication");
        }finally{fixture.cleanup(p);}c.succeed();
    }
    @GameTest public void journalRequiresAnActualGeneratedPieceAndPersistsNativePlayerData(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-discovery");var world=c.getLevel();
        var at=c.absolutePos(new BlockPos(1,2,1));var piece=new OdysseyStructure.Piece(world.getStructureTemplateManager(),at,"plains",net.minecraft.world.level.block.Rotation.NONE);
        var pos=piece.getBoundingBox().getCenter();var chunk=world.getChunkAt(pos);
        var ruin=world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(OdysseyStructure.KEY).value();
        var old=chunk.getStartForStructure(ruin);var refs=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(chunk.getReferencesForStructure(ruin));
        try{
            p.setGameMode(GameType.SURVIVAL);p.setPos(Vec3.atBottomCenterOf(pos));
            c.assertFalse(OdysseyJournal.discover(p),"No structure start means no discovery");
            var start=new net.minecraft.world.level.levelgen.structure.StructureStart(ruin,chunk.getPos(),0,new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
            chunk.setStartForStructure(ruin,start);chunk.addReferenceForStructure(ruin,chunk.getPos().pack());
            c.assertTrue(OdysseyJournal.discover(p),"Standing inside the original generated piece unlocks its realm");
            c.assertFalse(OdysseyJournal.discover(p),"Remaining inside cannot count repeatedly");
            var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess());p.saveWithoutId(output);
            GameModes.state(p).remove(OdysseyJournal.KEY);
            p.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,p.registryAccess(),output.buildResult()));
            c.assertValueEqual(OdysseyJournal.count(p),1,"Native save/load keeps realm progress");
        }finally{chunk.setStartForStructure(ruin,old==null?net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START:old);chunk.getReferencesForStructure(ruin).clear();chunk.getReferencesForStructure(ruin).addAll(refs);p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest public void journalRewardRetriesFullInventoryWithoutDuplicatingGifts(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-gifts");
        try{
            p.setGameMode(GameType.SURVIVAL);OdysseyJournal.record(p,0,p.blockPosition(),"plains");OdysseyJournal.record(p,1,p.blockPosition(),"warped");
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            c.assertValueEqual(OdysseyJournal.claim(p,2),0,"Full inventory refuses complete outfit");
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_2",false),"Gift remains claimable");
            for(int i=0;i<4;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
            c.assertValueEqual(OdysseyJournal.claim(p,2),4,"Exactly four empty slots receive the full set");
            c.assertValueEqual(OdysseyJournal.claim(p,2),0,"Repeated claim does not duplicate gear");
            c.assertFalse(OdysseyJournal.earned(p,4),"Trials reward cannot skip three-realm journey");
        }finally{p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest public void journalMenuPreservesCursorAndRejectsStaleOrForeignClicks(GameTestHelper c){
        var p=new ModeGameTests().player(c,"journal-safe-menu");var other=new ModeGameTests().player(c,"journal-foreign");
        try{
            p.setGameMode(GameType.SURVIVAL);OdysseyJournal.record(p,0,p.blockPosition(),"plains");OdysseyJournal.open(p);
            var menu=p.containerMenu;
            menu.clicked(10,0,ContainerInput.QUICK_MOVE,p);
            c.assertTrue(menu.getCarried().isEmpty(),"Realm icon cannot be stolen into the cursor");
            menu.clicked(19,0,ContainerInput.PICKUP,other);
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_1",false),"Another player cannot claim the owner's gift");
            menu.setCarried(new ItemStack(Items.DIAMOND));menu.clicked(19,0,ContainerInput.PICKUP,p);
            c.assertTrue(menu.getCarried().is(Items.DIAMOND),"Occupied cursor is preserved rather than discarded");
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_1",false),"Busy cursor cannot consume a gift");
            menu.setCarried(ItemStack.EMPTY);p.closeContainer();menu.clicked(19,0,ContainerInput.PICKUP,p);
            c.assertFalse(OdysseyJournal.state(p).getBooleanOr("claimed_1",false),"Closed window cannot act on a delayed click");
        }finally{p.closeContainer();p.level().getServer().getPlayerList().remove(p);other.level().getServer().getPlayerList().remove(other);}c.succeed();
    }
    @GameTest public void expandedLeaderboardRetainsOldAndNewCoursesAfterReload(GameTestHelper c){
        try{
            var file=java.nio.file.Files.createTempDirectory("playground-records-").resolve("records.json");
            var records=new MinigameRecords(file);var id=java.util.UUID.randomUUID();
            records.upsert("parkour",new MinigameRecords.Score(id,"Runner",1800));records.save();
            var upgraded=new MinigameRecords(file);
            c.assertTrue(upgraded.upsert("memory",new MinigameRecords.Score(id,"Runner",2500)),"Memory course is registered in the actual public index");
            c.assertTrue(upgraded.upsert("gatedash",new MinigameRecords.Score(id,"Runner",3000)),"Laser course is registered in the actual public index");
            upgraded.save();var loaded=new MinigameRecords(file);
            c.assertValueEqual(loaded.ordered("parkour").getFirst().millis(),1800L,"Old course survives upgrade");
            c.assertValueEqual(loaded.ordered("memory").getFirst().millis(),2500L,"Memory score survives restart");
            c.assertValueEqual(loaded.ordered("gatedash").getFirst().millis(),3000L,"Laser score survives restart");
        }catch(java.io.IOException e){throw new RuntimeException(e);}c.succeed();
    }
    @GameTest public void memoryCourseRequiresSequenceAndPersistsARealFinish(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-memory");
        try{
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"memory");p.setNoGravity(true);p.setOnGround(true);
            var run=ModeMaps.RUNS.get(p.getUUID());var spec=ModeMaps.MAPS.get("memory");
            int tick=run.colorDeadline+1;
            for(int i=0;i<ModeMaps.COLOR_ROUNDS;i++){
                p.setPos(Vec3.atBottomCenterOf(spec.points().getFirst()));ModeMaps.tick(p,tick++);
                p.setPos(Vec3.atBottomCenterOf(spec.points().get(run.colors[i]+1)));ModeMaps.tick(p,tick++);
            }
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUUID()),"Physical correct pad sequence completes the course");
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").getLongOr("memory",0)>0,"Personal best saved with real player state");
            ModeMaps.begin(p,"memory");run=ModeMaps.RUNS.get(p.getUUID());
            p.setPos(Vec3.atBottomCenterOf(spec.points().get((run.colors[0]+1)%4+1)));ModeMaps.tick(p,run.colorDeadline+1);
            c.assertTrue(ModeMaps.RUNS.get(p.getUUID())!=run,"Wrong first color actually restarts");
        }finally{p.closeContainer();ModeMaps.RUNS.remove(p.getUUID());p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest public void laserGateCourseRejectsRedAndCompletesGreenCheckpoints(GameTestHelper c){
        var p=new ModeGameTests().player(c,"playground-lasers");
        try{
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"gatedash");p.setNoGravity(true);p.setOnGround(true);
            var run=ModeMaps.RUNS.get(p.getUUID());var spec=ModeMaps.MAPS.get("gatedash");
            p.setPos(Vec3.atBottomCenterOf(spec.points().get(1)));ModeMaps.tick(p,run.startedTick+70);
            c.assertTrue(ModeMaps.RUNS.get(p.getUUID())!=run,"A red gate resets the player and timer");
            run=ModeMaps.RUNS.get(p.getUUID());
            for(int i=1;i<spec.points().size();i++){
                p.setPos(Vec3.atBottomCenterOf(spec.points().get(i)));ModeMaps.tick(p,run.startedTick+i);
            }
            c.assertFalse(ModeMaps.RUNS.containsKey(p.getUUID()),"Green checkpoint crossings complete the real course");
            c.assertTrue(GameModes.state(p).getCompoundOrEmpty("scores").getLongOr("gatedash",0)>0,"New game participates in score persistence");
        }finally{p.closeContainer();ModeMaps.RUNS.remove(p.getUUID());p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena") public void helperFormationsUseDistinctSafeGroundAndKeepApprovalGates(GameTestHelper c){
        try(var arena=new AgentTacticsGameTests.Arena(c,"playground-formation")){
            arena.target.discard();var peer=arena.add("beta",arena.start.add(2,0,0));
            AgentCompanions.cycleFormation(arena.owner);
            var a=arena.helpers.formationPoint(arena.golem,arena.record(arena.golem),arena.owner);
            var b=arena.helpers.formationPoint(peer,arena.record(peer),arena.owner);
            c.assertTrue(a.distanceToSqr(b)>4,"Wedge gives different followers distinct slots");
            c.assertTrue(arena.helpers.landingClear(arena.golem,a),"Follower destination has solid clear loaded ground");
            c.assertTrue(arena.helpers.playerTargets.isEmpty(),"Formation does not authorize player combat");
            AgentMenu.open(arena.owner);var menu=arena.owner.containerMenu;
            menu.clicked(AgentMenu.FORMATION,0,ContainerInput.PICKUP,arena.owner);
            c.assertValueEqual(AgentCompanions.formation(arena.owner),AgentCompanions.Formation.RING,"Actual menu cycles saved formation");
            arena.owner.closeContainer();
            var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,arena.owner.registryAccess());arena.owner.saveWithoutId(output);
            GameModes.state(arena.owner).remove("helper_formation");
            arena.owner.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,arena.owner.registryAccess(),output.buildResult()));
            c.assertValueEqual(AgentCompanions.formation(arena.owner),AgentCompanions.Formation.RING,"Formation survives native player save and load");
            OperatorGameTests.deop(arena.owner);AgentCompanions.cycleFormation(arena.owner);
            c.assertValueEqual(AgentCompanions.formation(arena.owner),AgentCompanions.Formation.RING,"Permission loss cannot change helper controls");
        }c.succeed();
    }
}

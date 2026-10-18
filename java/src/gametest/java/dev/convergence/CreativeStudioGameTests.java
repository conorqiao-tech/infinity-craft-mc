package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Exercise native menus, terrain, inventory delivery, entity lifecycle and explosion scheduling. */
public class CreativeStudioGameTests {
    private final java.util.Map<UUID,List<net.minecraft.world.level.ChunkPos>> forced=new java.util.HashMap<>();
    ServerPlayer player(GameTestHelper c,String name){
        var p=new EquipmentGameTests().creative(c,name);
        var at=new EquipmentGameTests().target(c);
        p.setPos(at.getX()+.5,100,at.getZ()+.5);p.setNoGravity(true);p.closeContainer();
        // A direct test teleport has not installed the real client's chunk tickets yet.
        var tickets=new ArrayList<net.minecraft.world.level.ChunkPos>();
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
            var chunk=new net.minecraft.world.level.ChunkPos((at.getX()>>4)+x,(at.getZ()>>4)+z);
            if(!p.level().getForceLoadedChunks().contains(chunk.pack())){p.level().setChunkForced(chunk.x(),chunk.z(),true);tickets.add(chunk);}
            p.level().getChunk(chunk.x(),chunk.z());
        }
        forced.put(p.getUUID(),tickets);
        return p;
    }
    void cleanup(ServerPlayer p){
        p.containerMenu.setCarried(ItemStack.EMPTY);p.closeContainer();RcPlanes.recall(p);
        CreativeStudio.UNDO.remove(p.getUUID());CreativeStudio.PREVIEWS.remove(p.getUUID());
        var tickets=forced.remove(p.getUUID());
        if(tickets!=null)for(var chunk:tickets)GameModes.world(p.level().getServer(),GameModes.Mode.CREATIVE).setChunkForced(chunk.x(),chunk.z(),false);
        OperatorGameTests.deop(p);p.level().getServer().getPlayerList().remove(p);
    }
    CreativeStudio.Preview blueprint(ServerPlayer p,BlockPos at){
        var preview=new CreativeStudio.Preview(p,p.connection,p.level(),at,CreativeStudio.stamp(p,at),p.level().getServer().getTickCount()+600);
        CreativeStudio.PREVIEWS.put(p.getUUID(),preview);return preview;
    }
    private ServerPlayer survivalPlayer(GameTestHelper c,String name){
        var p=new ModeGameTests().player(c,name);
        p.closeContainer();p.setGameMode(GameType.SURVIVAL);GameModes.state(p).putString("active","SURVIVAL");
        p.getInventory().clearContent();p.setNoGravity(true);
        var at=c.absolutePos(new BlockPos(2,180,2));p.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
        return p;
    }
    private BlockPos previewGround(ServerPlayer p){
        var ground=p.blockPosition().offset(0,-1,4);
        for(var pos:BlockPos.betweenClosed(ground.offset(-3,1,-1),ground.offset(3,6,1)))
            p.level().setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        p.level().setBlock(ground,Blocks.STONE.defaultBlockState(),3);
        p.setPos(ground.getX()+.5,ground.getY()+2,ground.getZ()-.5);
        p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,Vec3.atCenterOf(ground));
        return ground;
    }
    @GameTest public void survivalOpBuildsAndUndoesWithNativeWandWithoutChangingWorldOrInventory(GameTestHelper c){
        var p=survivalPlayer(c,"studio-op-build");
        try{
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            c.assertFalse(Memberships.get(p.level().getServer()).account(p.getUUID()).admin,"Manual OP4 does not need a separate membership role");
            c.assertValueEqual(GameModes.of(p.level()),GameModes.Mode.SURVIVAL,"The fixture builds in the actual Survival world");
            c.assertTrue(CreativeStudio.allowed(p),"A living Survival OP4 with native build permission can use Studio");
            var at=p.blockPosition().offset(0,0,4);var tools=new EquipmentGameTests();tools.wall(p,at);tools.aim(p,at);
            var keepsake=new ItemStack(Items.DIAMOND,7);keepsake.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Keep my Survival items"));
            p.getInventory().setItem(6,keepsake.copy());
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Convergence.ITEMS.get("convergence:builder_wand")));
            p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.QUARTZ_BLOCK,3));
            c.assertValueEqual(PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH),9,"The existing native builder places the full face in Survival for OP4");
            c.assertTrue(p.level().getBlockState(at.north()).is(Blocks.QUARTZ_BLOCK),"The real Survival terrain contains the placed block");
            c.assertValueEqual(CreativeStudio.undo(p),9,"The same native undo restores all nine cells");
            c.assertTrue(p.level().getBlockState(at.north()).isAir(),"Undo restores the actual world");
            c.assertTrue(ItemStack.matches(p.getInventory().getItem(6),keepsake),"Authorized tools preserve named Survival possessions");
            c.assertValueEqual(p.getOffhandItem().getCount(),3,"Authorized Studio creation does not consume the offhand material");
            c.assertValueEqual(p.gameMode(),GameType.SURVIVAL,"Building does not silently switch vanilla game mode");
            c.assertValueEqual(GameModes.current(p),GameModes.Mode.SURVIVAL,"Building does not switch world profiles");
            CreativeStudio.settings(p).putInt("brush",CreativeStudio.Brush.CUBE.ordinal());CreativeStudio.settings(p).putInt("radius",2);
            c.assertValueEqual(CreativeStudio.brushPositions(p,at,Direction.NORTH).size(),343,"OP4 retains the existing maximum per-edit resource budget");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void survivalAdminUsesActualStudioMenuAndBlueprintReview(GameTestHelper c){
        var p=survivalPlayer(c,"studio-admin");var members=Memberships.get(p.level().getServer());
        try{
            c.assertTrue(members.grantAdmin(p.getUUID()),"The saved Admin role grants native OP4 first");
            c.assertTrue(CreativeStudio.allowed(p),"Saved Admin has Studio access in Survival");
            c.assertValueEqual(CreativeStudio.open(p),1,"Admin opens the actual Studio chest menu");
            p.containerMenu.clicked(CreativeStudio.PALETTE,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(CreativeStudio.option(p,"palette",CreativeStudio.PALETTES.size()),1,"A real Survival Admin menu action changes the palette");
            p.closeContainer();var ground=previewGround(p);
            c.assertValueEqual(CreativeStudio.preview(p),1,"A real native upward-face raycast opens blueprint review in Survival");
            var preview=CreativeStudio.PREVIEWS.get(p.getUUID());
            c.assertTrue(preview!=null&&p.containerMenu instanceof CreativeStudio.Handler,"Review retains the exact native preview and session");
            p.containerMenu.clicked(CreativeStudio.CONFIRM,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.level().getBlockState(ground.above(5)).is(Blocks.SHROOMLIGHT),"Confirmed native blueprint places the Admin's selected themed lamp");
            c.assertValueEqual(CreativeStudio.undo(p),13,"Survival Admin can undo all thirteen blueprint cells");
            c.assertValueEqual(p.gameMode(),GameType.SURVIVAL,"Menu and blueprint leave the Admin in Survival");
        }finally{members.revokeAdmin(p.getUUID());cleanup(p);}c.succeed();
    }
    @GameTest public void studioAdminOverridePreservesOrdinarySurvivalAdventureAndSpectatorRestrictions(GameTestHelper c){
        var p=survivalPlayer(c,"studio-denied");
        try{
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Convergence.ITEMS.get("convergence:builder_wand")));
            p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.QUARTZ_BLOCK));var at=p.blockPosition().offset(0,0,4);
            p.level().setBlock(at,Blocks.STONE.defaultBlockState(),3);
            c.assertFalse(CreativeStudio.allowed(p),"Ordinary Survival still cannot use Studio building powers");
            c.assertValueEqual(PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH),0,"A real custom wand cannot bypass ordinary Survival permissions");
            c.assertValueEqual(CreativeStudio.deliver(p,List.of(new ItemStack(Items.DIAMOND))),0,"Ordinary Survival cannot claim Studio items");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            for(var mode:List.of(GameType.ADVENTURE,GameType.SPECTATOR)){
                p.setGameMode(mode);
                c.assertFalse(p.mayBuild(),"The native mode withholds general building permission: "+mode);
                c.assertFalse(CreativeStudio.allowed(p),"OP4 does not bypass native Adventure/Spectator build restrictions");
                c.assertValueEqual(PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH),0,"Native tool mutation refuses the restricted mode");
                c.assertValueEqual(CreativeStudio.deliver(p,List.of(new ItemStack(Items.DIAMOND))),0,"Restricted modes cannot claim Studio items");
            }
            c.assertTrue(p.level().getBlockState(at).is(Blocks.STONE),"Every denied action preserves the original world block");
            c.assertTrue(p.level().getBlockState(at.north()).isAir(),"Denied actions never place a new block");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void survivalStudioReviewsRejectRevokedPermissionReplacementSessionsAndStaleMenus(GameTestHelper c){
        var p=survivalPlayer(c,"studio-review-op");var other=survivalPlayer(c,"studio-session");
        try{
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);var ground=previewGround(p);
            c.assertValueEqual(CreativeStudio.preview(p),1,"Authorized Survival OP4 creates a real reviewed blueprint");
            var review=p.containerMenu;var pending=CreativeStudio.PREVIEWS.get(p.getUUID());
            OperatorGameTests.deop(p);review.clicked(CreativeStudio.CONFIRM,0,ContainerInput.PICKUP,p);
            for(var pos:pending.blocks().keySet())c.assertTrue(p.level().getBlockState(pos).isAir(),"Revoked permission cannot confirm any part of a pending blueprint");
            c.assertFalse(CreativeStudio.PREVIEWS.containsKey(p.getUUID()),"Denied confirmation consumes only its own stale preview");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            c.assertValueEqual(CreativeStudio.preview(p),1,"Reauthorized OP4 can prepare a fresh native review");
            pending=CreativeStudio.PREVIEWS.get(p.getUUID());var connection=p.connection;
            try{
                p.connection=other.connection;
                c.assertValueEqual(CreativeStudio.confirm(p,pending),0,"A different native listener cannot use the old session's preview");
            }finally{p.connection=connection;}
            for(var pos:pending.blocks().keySet())c.assertTrue(p.level().getBlockState(pos).isAir(),"Replacement-session rejection leaves the full footprint unchanged");
            p.closeContainer();c.assertValueEqual(CreativeStudio.preview(p),1,"The original session can prepare another native review");
            var stale=p.containerMenu;p.closeContainer();ServerMenu.open(p);var current=p.containerMenu;
            stale.clicked(CreativeStudio.CONFIRM,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.containerMenu==current,"A stale Studio packet cannot close or act through a replacement screen");
            c.assertTrue(p.level().getBlockState(ground.above(5)).isAir(),"Stale menu confirmation never places the blueprint lamp");
        }finally{cleanup(p);cleanup(other);}c.succeed();
    }
    @GameTest public void studioMenuRejectsForeignClicksAndSurvivalMutations(GameTestHelper c){
        var p=player(c,"studio-owner");var other=player(c,"studio-other");
        try{
            c.assertValueEqual(CreativeStudio.open(p),1,"Studio opens as a native chest");
            var menu=p.containerMenu;
            menu.clicked(CreativeStudio.PALETTE,0,ContainerInput.PICKUP,other);
            c.assertTrue(p.containerMenu==menu,"Foreign click cannot close the owner's menu");
            c.assertValueEqual(CreativeStudio.option(p,"palette",5),0,"Foreign click changes nothing");
            menu.clicked(CreativeStudio.KIT,0,ContainerInput.THROW,p);
            c.assertTrue(menu.getCarried().isEmpty(),"Control icons cannot be thrown or collected");
            p.setGameMode(GameType.SURVIVAL);
            menu.clicked(CreativeStudio.PALETTE,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(CreativeStudio.option(p,"palette",5),0,"Changing vanilla game mode revokes building controls immediately");
            c.assertValueEqual(CreativeStudio.deliver(p,List.of(new ItemStack(Items.DIAMOND))),0,"Survival cannot conjure Studio items");
        }finally{cleanup(p);cleanup(other);}c.succeed();
    }
    @GameTest public void studioStalePacketKeepsNewMenuAndInventory(GameTestHelper c){
        var p=player(c,"studio-stale");try{
            CreativeStudio.open(p);var stale=p.containerMenu;p.closeContainer();ServerMenu.open(p);var current=p.containerMenu;
            stale.clicked(CreativeStudio.KIT,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.containerMenu==current,"Old Studio packet cannot close the new Infinity Menu");
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.QUARTZ_BLOCK)),"Stale delivery creates nothing");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void kitDeliveryIsAtomicWhenFourInsteadOfFiveSlotsAreFree(GameTestHelper c){
        var p=player(c,"studio-full");try{
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));
            for(int i=0;i<4;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
            c.assertValueEqual(CreativeStudio.kit(p),0,"Insufficient room rejects the whole kit");
            for(int i=0;i<4;i++)c.assertTrue(p.getInventory().getItem(i).isEmpty(),"No partial kit delivery");
            p.getInventory().setItem(4,ItemStack.EMPTY);
            c.assertValueEqual(CreativeStudio.kit(p),5,"Five free slots receive all five items");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),64,"Native building stack retains its count");
            c.assertTrue(p.getInventory().getItem(3).is(Convergence.ITEMS.get("convergence:builder_wand")),"Actual builder item delivered");
            c.assertValueEqual(p.getInventory().getItem(5).getCount(),64,"Occupied inventory survives");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void brushUndoRestoresTerrainAndRefusesAnotherPlayersEdit(GameTestHelper c){
        var p=player(c,"studio-undo");try{
            var at=p.blockPosition().offset(0,0,4);var tools=new EquipmentGameTests();tools.wall(p,at);tools.aim(p,at);
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Convergence.ITEMS.get("convergence:builder_wand")));
            p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.QUARTZ_BLOCK));
            c.assertValueEqual(PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH),9,"Default native brush builds nine cubes");
            var modified=at.north().east();p.level().setBlock(modified,Blocks.GOLD_BLOCK.defaultBlockState(),3);
            c.assertValueEqual(CreativeStudio.undo(p),0,"Undo refuses the complete edit when a cell has changed");
            c.assertTrue(p.level().getBlockState(at.north()).is(Blocks.QUARTZ_BLOCK),"Refused undo does not partially restore terrain");
            p.level().setBlock(modified,Blocks.QUARTZ_BLOCK.defaultBlockState(),3);
            c.assertValueEqual(CreativeStudio.undo(p),9,"Unchanged edit can be restored");
            c.assertTrue(p.level().getBlockState(at.north()).isAir(),"Actual world terrain is restored");
            c.assertValueEqual(CreativeStudio.undo(p),0,"Undo is consumed once");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void cubeBrushBuildsAndUndoesRealVolume(GameTestHelper c){
        var p=player(c,"studio-volume");try{
            var at=p.blockPosition().offset(0,0,4);var tools=new EquipmentGameTests();tools.aim(p,at);
            // Only the target plane is occupied; the front-facing 3x3x3 cube includes it.
            p.level().setBlock(at,Blocks.STONE.defaultBlockState(),3);
            CreativeStudio.settings(p).putInt("brush",CreativeStudio.Brush.CUBE.ordinal());
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Convergence.ITEMS.get("convergence:builder_wand")));
            p.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.QUARTZ_BLOCK));
            int built=PoweredTools.creativeUse(p,"convergence:builder_wand",at,Direction.NORTH);
            c.assertValueEqual(built,26,"Volume brush preserves its one occupied stone cell");
            c.assertValueEqual(CreativeStudio.undo(p),26,"Entire native volume can be undone");
            c.assertTrue(p.level().getBlockState(at).is(Blocks.STONE),"Existing terrain stays unchanged");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void blueprintRequiresExactCurrentPreviewAndLeavesChangedFootprintAlone(GameTestHelper c){
        var p=player(c,"studio-review");try{
            var at=p.blockPosition().offset(0,0,5);var first=blueprint(p,at);var current=blueprint(p,at);
            c.assertValueEqual(CreativeStudio.confirm(p,first),0,"An earlier preview cannot approve a later blueprint");
            c.assertTrue(CreativeStudio.PREVIEWS.get(p.getUUID())==current,"Rejected stale approval preserves the newer preview");
            c.assertTrue(p.level().getBlockState(at.offset(-2,0,0)).isAir(),"Stale confirmation places nothing");
            current=blueprint(p,at);var changed=current.blocks().keySet().iterator().next();
            p.level().setBlock(changed,Blocks.DIAMOND_BLOCK.defaultBlockState(),3);
            c.assertValueEqual(CreativeStudio.confirm(p,current),0,"An occupied blueprint refuses placement atomically");
            c.assertTrue(p.level().getBlockState(changed).is(Blocks.DIAMOND_BLOCK),"Existing block is preserved");
            p.level().removeBlock(changed,false);var ready=blueprint(p,at);
            c.assertValueEqual(CreativeStudio.confirm(p,ready),13,"Confirmed arch is a real thirteen-block structure");
            c.assertTrue(p.level().getBlockState(at.offset(0,4,0)).is(Blocks.SEA_LANTERN),"Native themed lamp exists");
            c.assertValueEqual(CreativeStudio.undo(p),13,"Blueprint shares the terrain undo workflow");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void studioPreferencesSaveAndCreativeInventoryRemainsSeparated(GameTestHelper c){
        var p=player(c,"studio-save");try{
            CreativeStudio.settings(p).putInt("palette",2);CreativeStudio.settings(p).putBoolean("trail",true);
            CreativeStudio.kit(p);
            var writer=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.registryAccess());p.saveWithoutId(writer);
            var fresh=new ServerPlayer(p.level().getServer(),p.level(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"studio-loaded"),p.clientInformation());
            fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),writer.buildResult()));
            c.assertValueEqual(CreativeStudio.palette(fresh).name(),"Sakura","Palette survives native player serialization");
            c.assertTrue(CreativeStudio.settings(fresh).getBooleanOr("trail",false),"Trail preference persists");
            GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            c.assertFalse(p.getInventory().contains(new ItemStack(Items.CHERRY_PLANKS)),"Creative kit does not enter Survival inventory");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void newArmorUsesFourNativeWearablePiecesAndTrims(GameTestHelper c){
        var p=player(c,"studio-armor");try{
            for(int style=0;style<3;style++){
                var pieces=CreativeStudio.outfit(p,style);c.assertValueEqual(pieces.size(),4,"Full armor set includes chestplate");
                c.assertTrue(pieces.get(1).getHoverName().getString().endsWith("Chestplate"),"Readable literal chestplate name");
                for(var piece:pieces){c.assertTrue(piece.has(DataComponents.EQUIPPABLE),"Native equipment attachment exists");c.assertTrue(piece.has(DataComponents.TRIM),"Native trim is present");}
            }
            c.assertTrue(CreativeStudio.outfit(p,2).getFirst().has(DataComponents.DYED_COLOR),"Sakura leather uses native dye");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void pickerExposesStudioAndNewActualGadgets(GameTestHelper c){
        var p=player(c,"studio-picker");try{
            CreativeGearPicker.open(p);var picker=(CreativeGearPicker.PickerHandler)p.containerMenu;
            c.assertTrue(picker.paths.contains("convergence:plane_remote")&&picker.paths.contains("convergence:storm_staff"),"New registered items appear in inventory picker");
            picker.clicked(CreativeGearPicker.STUDIO,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.containerMenu instanceof CreativeStudio.Handler,"Picker links directly to real Studio controls");
            p.containerMenu.clicked(CreativeStudio.REMOTE,0,ContainerInput.PICKUP,p);
            c.assertTrue(java.util.stream.IntStream.range(0,36).anyMatch(i->p.getInventory().getItem(i).is(Convergence.ITEMS.get("convergence:plane_remote"))),"Touch control delivers the actual registered remote, including its usage lore");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void studioGadgetPowerCannotRunInSurvivalOrSpectator(GameTestHelper c){
        var p=player(c,"studio-controls");try{
            p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Convergence.ITEMS.get("convergence:party_wand")));
            c.assertValueEqual(CrossplaySupport.usePower(p,false),1,"Creative native power dispatch activates a real gadget");
            p.setGameMode(GameType.SURVIVAL);c.assertValueEqual(CrossplaySupport.usePower(p,false),0,"Survival dispatch cannot bypass gadget permissions");
            p.setGameMode(GameType.SPECTATOR);c.assertValueEqual(CrossplaySupport.usePower(p,false),0,"Spectator cannot invoke effects");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest(maxTicks=30) public void planeReallyMovesAndRecallsWithoutTerrainOrDrops(GameTestHelper c){
        var p=player(c,"studio-plane");p.setYRot(0);p.setXRot(0);
        c.assertValueEqual(RcPlanes.launch(p),1,"Plane creates its native entity assembly");
        var plane=RcPlanes.ACTIVE.get(p.getUUID());var start=plane.center;
        c.assertValueEqual(plane.parts.size(),9,"All nine plane parts exist");
        c.assertValueEqual(RcPlanes.launch(p),0,"Owner cannot accidentally stack a second plane");
        c.runAfterDelay(3,()->{
            try{
                c.assertTrue(plane.center.z>start.z,"Normal server lifecycle advances flight");
                c.assertTrue(RcPlanes.ACTIVE.get(p.getUUID())==plane,"Plane remains registered after native ticks: allowed="+CreativeStudio.allowed(p)+", world="+(p.level()==plane.world)+", range="+plane.center.distanceToSqr(p.position())+", parts="+plane.parts.stream().map(e->e.isRemoved()+"/"+e.getRemovalReason()).toList());
                RcPlanes.hover(p);var stopped=plane.center;RcPlanes.tick();
                c.assertValueEqual(plane.center,stopped,"Hover holds the plane in place");
                c.assertValueEqual(RcPlanes.recall(p),1,"Owner recalls the assembly");
                c.assertTrue(plane.parts.stream().allMatch(FallingBlockEntity::isRemoved),"Every native part is removed");
                c.assertTrue(plane.parts.stream().noneMatch(part->p.level().getBlockState(part.blockPosition()).is(part.getBlockState().getBlock())),"Recall produces no terrain blocks");
                c.assertFalse(RcPlanes.ACTIVE.containsKey(p.getUUID()),"Owner slot is released");c.succeed();
            }finally{cleanup(p);}
        });
    }
    @GameTest public void planeObstacleAndOwnerPermissionsStopFlightSafely(GameTestHelper c){
        var p=player(c,"studio-collision");try{
            p.setYRot(0);p.setXRot(0);c.assertValueEqual(RcPlanes.launch(p),1,"Clear fixture launches");var plane=RcPlanes.ACTIVE.get(p.getUUID());
            var next=plane.center.add(0,0,.12);var obstacle=BlockPos.containing(RcPlanes.position(next,RcPlanes.OFFSETS.getFirst(),0));
            p.level().setBlock(obstacle,Blocks.STONE.defaultBlockState(),3);var before=plane.center;RcPlanes.tick();
            c.assertValueEqual(plane.center,before,"Obstacle prevents movement into its block");c.assertTrue(plane.hover,"Obstacle enables hover");
            c.assertTrue(p.level().getBlockState(obstacle).is(Blocks.STONE),"Plane cannot break or replace the obstacle");
            p.level().removeBlock(obstacle,false);p.setGameMode(GameType.SURVIVAL);RcPlanes.tick();
            c.assertFalse(RcPlanes.ACTIVE.containsKey(p.getUUID()),"Revoked Creative access removes plane");
            c.assertTrue(plane.parts.stream().allMatch(FallingBlockEntity::isRemoved),"All parts are cleaned on permission loss");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void orphanPlanePartsCannotFallIntoSavedWorld(GameTestHelper c){
        var part=new FallingBlockEntity(EntityTypes.FALLING_BLOCK,c.getLevel());part.addTag(RcPlanes.TAG);
        c.assertTrue(RcPlanes.tickPart(part),"Orphan is intercepted before vanilla falling tick");
        c.assertTrue(part.isRemoved(),"Unowned or restored part disappears without terrain or item delivery");
        var ordinary=new FallingBlockEntity(EntityTypes.FALLING_BLOCK,c.getLevel());
        c.assertFalse(RcPlanes.tickPart(ordinary),"Ordinary falling blocks retain normal behavior");c.succeed();
    }
    @GameTest(maxTicks=40) public void tntChainQueuesWithoutDeletingUndetonatedCharges(GameTestHelper c){
        var at=c.absolutePos(new BlockPos(2,200,2));var charges=new ArrayList<PrimedTnt>();
        var forced=new ArrayList<net.minecraft.world.level.ChunkPos>();
        for(int i=0;i<5;i++){
            var tnt=new PrimedTnt(EntityTypes.TNT,c.getLevel());tnt.setPos(at.getX()+i*16,200,at.getZ());tnt.setFuse(1);
            var chunk=new net.minecraft.world.level.ChunkPos(tnt.getBlockX()>>4,tnt.getBlockZ()>>4);
            if(!c.getLevel().getForceLoadedChunks().contains(chunk.pack())){c.getLevel().setChunkForced(chunk.x(),chunk.z(),true);forced.add(chunk);}
            c.getLevel().addFreshEntity(tnt);charges.add(tnt);tnt.tick();
        }
        c.assertTrue(charges.stream().filter(PrimedTnt::isRemoved).count()<=2,"One server tick permits at most two detonations");
        c.assertTrue(charges.stream().anyMatch(t->!t.isRemoved()&&t.getFuse()==1),"Remaining live charges are delayed, not deleted");
        c.runAfterDelay(15,()->{
            try{c.assertTrue(charges.stream().allMatch(PrimedTnt::isRemoved),"Normal ticking eventually detonates every queued charge");c.succeed();}
            finally{charges.forEach(PrimedTnt::discard);for(var chunk:forced)c.getLevel().setChunkForced(chunk.x(),chunk.z(),false);}
        });
    }
    @GameTest public void nativeExplosionBoundsOversizedAndNonfinitePower(GameTestHelper c){
        for(float power:new float[]{4,500,Float.POSITIVE_INFINITY,Float.NaN,-5}){
            var explosion=new ServerExplosion(c.getLevel(),null,null,null,new Vec3(0,200,0),power,false,Explosion.BlockInteraction.KEEP);
            c.assertValueEqual(explosion.radius(),Float.isFinite(power)?Math.max(0,Math.min(8,power)):0,"Native constructor bounds excessive work while preserving ordinary blast radius");
        }c.succeed();
    }
    @GameTest public void helperSquadRecallPreservesEachEntityAndEditedHealth(GameTestHelper c){
        var p=new ModeGameTests().player(c,"studio-squad");var helpers=AgentCompanions.get(c.getLevel().getServer());
        var ground=new BlockPos(p.getBlockX(),140,p.getBlockZ());
        for(var pos:BlockPos.betweenClosed(ground.offset(-5,-1,-5),ground.offset(5,3,5)))p.level().setBlock(pos,pos.getY()==139?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
        p.setPos(Vec3.atBottomCenterOf(ground));
        OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
        try{
            c.assertValueEqual(helpers.spawn(p,"first"),1,"First existing helper fixture spawns");
            c.assertValueEqual(helpers.spawn(p,"second"),1,"Second existing helper fixture spawns");
            var ids=helpers.data.agents.entrySet().stream().filter(e->e.getValue().owner().equals(p.getStringUUID())).map(java.util.Map.Entry::getKey).toList();
            var first=helpers.loaded.get(UUID.fromString(ids.getFirst()));first.setHealth(37);
            helpers.recallSquad(p);
            c.assertValueEqual(helpers.count(p),2,"Squad recall does not delete or recreate helpers");
            c.assertTrue(helpers.loaded.get(first.getUUID())==first,"Native helper identity is preserved");
            c.assertValueEqual(first.getHealth(),37f,"Existing edited health is preserved");
            for(var id:ids)c.assertTrue(helpers.brief(p,id,helpers.data.agents.get(id))!=null,"Roster gives a real current status");
        }finally{
            for(String name:List.of("first","second"))if(helpers.owned(p,name)!=null)helpers.dismiss(p,name);
            cleanup(p);
        }c.succeed();
    }
}

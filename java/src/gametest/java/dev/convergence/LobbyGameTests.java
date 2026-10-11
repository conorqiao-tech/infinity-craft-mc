package dev.convergence;

import java.nio.file.Files;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.WorldChunk;

public class LobbyGameTests {
    private ServerPlayerEntity player(TestContext c,String name){return new ModeGameTests().player(c,name);}
    @GameTest public void lobbyMainAndFiveModeAreasAreSafeAndLabeled(TestContext c) {
        var server=c.getWorld().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        c.assertTrue(world!=null,"Dedicated hub dimension loaded");
        c.assertEquals(LobbyServer.LOBBIES.size(),6,"Main plus five mode lobbies");
        for(var lobby:LobbyServer.LOBBIES.values()) {
            var place=LobbyServer.place(server,lobby.id());
            c.assertTrue(CommunityServer.safe(world,place),"Safe lobby floor: "+lobby.id());
            c.assertTrue(world.getBlockState(lobby.center().down()).isOf(Blocks.SEA_LANTERN),"Lit center: "+lobby.id());
            if(!lobby.id().equals("main"))
                c.assertTrue(world.getBlockEntity(lobby.center().add(0,0,-4)) instanceof SignBlockEntity,"Native sign at "+lobby.id());
        }
        c.assertTrue(Files.exists(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("infinity-built-in-lobbies.json")),"Generation marker saved");
        for (var pos : LobbyServer.MENU_SIGNS)
            c.assertTrue(LobbyServer.isMenuSign(world, pos), "Every lobby has an Infinity Menu recovery sign");
        c.assertTrue(Files.exists(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("infinity-course-selector-signs.json")),"Course selector upgrade marker saved");
        for(var spec:ModeMaps.MAPS.values()) {
            var mapWorld=GameModes.world(server,spec.mode());
            c.assertTrue(CourseSelector.selectorSign(mapWorld,CourseSelector.signPos(spec)),"Course selector sign is available at "+spec.id());
        }
        c.complete();
    }
    @GameTest public void courseSelectorUpgradeRecognizesExistingSignsAndRejectsOccupiedSites(TestContext c) {
        var server=c.getWorld().getServer();var world=c.getWorld();
        var occupied=c.getAbsolutePos(new BlockPos(2,5,2));
        var original=world.getBlockState(occupied);
        try {
            var marker=Files.createTempDirectory("selector-upgrade-").resolve("signs.json");
            CourseSelector.installSigns(server,marker);
            c.assertEquals(CourseSelector.installed(marker).size(),ModeMaps.MAPS.size(),"Upgrade records existing selector signs without rebuilding maps");
            var spec=ModeMaps.MAPS.get("parkour");
            var courseWorld=GameModes.world(server,spec.mode());
            var lostSign=CourseSelector.signPos(spec);
            courseWorld.setBlockState(lostSign,Blocks.AIR.getDefaultState());
            CourseSelector.installSigns(server,marker);
            c.assertTrue(CourseSelector.selectorSign(courseWorld,lostSign),"Marker-listed sign is restored after its chunk loses the sign");
            world.setBlockState(occupied,Blocks.CHEST.getDefaultState());
            c.assertFalse(CourseSelector.canPlaceSign(world,occupied),"Occupied selector site is refused");
            c.assertTrue(world.getBlockState(occupied).isOf(Blocks.CHEST),"Occupied block is preserved");
        } catch(java.io.IOException error) {throw new RuntimeException(error);}
        finally {world.setBlockState(occupied,original);}
        c.complete();
    }
    @GameTest public void lobbyRebuildNeverOverwritesExistingBlocks(TestContext c) {
        var server=c.getWorld().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        var pos=new BlockPos(7,81,7);var old=world.getBlockState(pos);
        try {
            world.setBlockState(pos,Blocks.CHEST.getDefaultState());
            LobbyServer.build(server);
            c.assertTrue(world.getBlockState(pos).isOf(Blocks.CHEST),"Saved lobby is not rebuilt on restart");
        } finally {world.setBlockState(pos,old);}
        c.complete();
    }
    @GameTest public void lobbyVisualUpgradeRestoresMissingAccentsAndPreservesPlayerStorage(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        var occupied=LobbyServer.LOBBIES.get("main").center().add(-9,0,-5);
        var missing=LobbyServer.LOBBIES.get("creative").center().add(5,6,-10);
        var oldOccupied=world.getBlockState(occupied);var oldMissing=world.getBlockState(missing);
        try {
            world.setBlockState(occupied,Blocks.CHEST.getDefaultState());
            var chest=(net.minecraft.block.entity.ChestBlockEntity)world.getBlockEntity(occupied);
            chest.setStack(0,new ItemStack(Items.DIAMOND,13));
            world.setBlockState(missing,Blocks.AIR.getDefaultState(),2);
            c.assertTrue(LobbyServer.installDecor(world)>0,"An older lobby receives a missing colored lantern without a rebuild");
            c.assertTrue(world.getBlockState(missing).isOf(Blocks.PEARLESCENT_FROGLIGHT),"Creative's purple gateway receives its matching light");
            c.assertTrue(world.getBlockState(occupied).isOf(Blocks.CHEST),"A player's block at a planned planter is preserved");
            c.assertEquals(chest.getStack(0).getCount(),13,"Player storage contents survive the upgrade");
            c.assertTrue(chest.getStack(0).isOf(Items.DIAMOND),"Stored item type is unchanged");
            c.assertEquals(LobbyServer.installDecor(world),0,"Repeating the upgrade changes nothing, including signs");
        } finally {
            world.setBlockState(occupied,oldOccupied);world.setBlockState(missing,oldMissing);
        }
        c.complete();
    }
    @GameTest public void lobbyChunkLoadDefersRepairsUntilItsCompletedWorldTick(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        var center=LobbyServer.LOBBIES.get("creative").center();
        var accent=center.add(5,6,-10);
        var occupied=center.add(9,0,-5);
        var signPos=center.add(0,0,-4);
        var otherAccent=LobbyServer.LOBBIES.get("main").center().add(5,6,-10);
        var chunk=world.getWorldChunk(accent);
        var originalAccent=chunk.getBlockState(accent);
        var originalOccupied=chunk.getBlockState(occupied);
        var originalOther=world.getBlockState(otherAccent);
        var sign=(SignBlockEntity)chunk.getBlockEntity(signPos);
        var front=sign.getFrontText();var back=sign.getBackText();
        int flags=Block.NOTIFY_LISTENERS|Block.FORCE_STATE;
        // Drain startup work before creating missing blocks, so this event is the only queued repair.
        ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
        try {
            world.setBlockState(accent,Blocks.AIR.getDefaultState(),flags);
            world.setBlockState(otherAccent,Blocks.AIR.getDefaultState(),flags);
            world.setBlockState(occupied,Blocks.CHEST.getDefaultState(),flags);
            var chest=(net.minecraft.block.entity.ChestBlockEntity)chunk.getBlockEntity(occupied);
            chest.setStack(0,new ItemStack(Items.DIAMOND,13));
            sign.setText(front.withMessage(0,Text.literal("Keep my arrival sign")),true);
            sign.setText(back.withMessage(1,Text.literal("Keep this back too")),false);
            int loadedChunks=world.getChunkManager().getLoadedChunkCount();

            // Dispatch the registered native event, not the installer helper. A synchronous
            // repair here can ask for the same still-pending chunk future and freeze a join.
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,chunk);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"Chunk-load callback only queues work while its full-chunk future may be pending");
            c.assertEquals(world.getChunkManager().getLoadedChunkCount(),loadedChunks,"Enqueuing lobby repair does not load chunks");

            ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isOf(Blocks.PEARLESCENT_FROGLIGHT),"The completed captured chunk is repaired after the world tick");
            c.assertTrue(world.getBlockState(otherAccent).isAir(),"A chunk-load repair does not sweep another lobby chunk");
            c.assertTrue(chunk.getBlockState(occupied).isOf(Blocks.CHEST),"Deferred repair preserves player storage");
            c.assertTrue(chunk.getBlockEntity(occupied)==chest,"Deferred repair retains the original chest block entity");
            c.assertTrue(chest.getStack(0).isOf(Items.DIAMOND),"Deferred repair keeps the stored item type");
            c.assertEquals(chest.getStack(0).getCount(),13,"Deferred repair keeps the stored item count");
            c.assertEquals(sign.getFrontText().getMessage(0,false).getString(),"Keep my arrival sign","Deferred repair preserves edited front text");
            c.assertEquals(sign.getBackText().getMessage(1,false).getString(),"Keep this back too","Deferred repair preserves edited back text");
            c.assertEquals(world.getChunkManager().getLoadedChunkCount(),loadedChunks,"Draining one completed repair does not load neighboring chunks");

            world.setBlockState(accent,Blocks.AIR.getDefaultState(),flags);
            ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"A completed queue entry is consumed rather than rebuilding every tick");
        } finally {
            sign.setText(front,true);sign.setText(back,false);
            world.setBlockState(accent,originalAccent,flags);
            world.setBlockState(occupied,originalOccupied,flags);
            world.setBlockState(otherAccent,originalOther,flags);
        }
        c.complete();
    }
    @GameTest public void lobbyChunkRepairRejectsStaleEventsAndCancelsUnloadedChunks(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        var accent=LobbyServer.LOBBIES.get("creative").center().add(5,6,-10);
        var chunk=world.getWorldChunk(accent);
        var original=chunk.getBlockState(accent);
        int flags=Block.NOTIFY_LISTENERS|Block.FORCE_STATE;
        ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
        try {
            world.setBlockState(accent,Blocks.AIR.getDefaultState(),flags);
            // The native chunk has the same position but has never been published by the
            // manager. Preloaded hub fixtures must not hide an event/manager identity bug.
            var stale=new WorldChunk(world,chunk.getPos());
            c.assertTrue(world.getChunkManager().getWorldChunk(chunk.getPos().x,chunk.getPos().z)==chunk,"Fixture has a distinct completed manager chunk");
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,stale);
            ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"An event for another chunk instance cannot repair the resident chunk");
            c.assertTrue(stale.getBlockState(accent).isAir(),"An unpublished event chunk is not mutated");

            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,chunk);
            ServerChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(world,chunk);
            ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"Unloading cancels a queued repair even if a completed instance is still visible");

            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,chunk);
            ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isOf(Blocks.PEARLESCENT_FROGLIGHT),"A fresh load event can repair the chunk after cancellation");
        } finally {world.setBlockState(accent,original,flags);}
        c.complete();
    }
    @GameTest public void lobbyChunkLoadOutsideBuiltPlatformsNeverLoadsItsNeighbors(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        var distant=new ChunkPos(625_000,625_000);
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            c.assertFalse(world.isChunkLoaded(distant.x+x,distant.z+z),"Distant fixture and its neighbors start unloaded");
        var unpublished=new WorldChunk(world,distant);
        ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,unpublished);
        ServerTickEvents.END_WORLD_TICK.invoker().onEndTick(world);
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            c.assertFalse(world.isChunkLoaded(distant.x+x,distant.z+z),"Unknown chunk event does not request this chunk or any neighbor");
        for (var section : unpublished.getSectionArray())
            c.assertTrue(section.isEmpty(),"Unknown chunk event leaves every native chunk section empty");
        c.complete();
    }
    @GameTest public void lobbyVisualUpgradeKeepsArrivalBridgesAndNavigationUnchanged(TestContext c) {
        var server=c.getWorld().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        var routes=Map.copyOf(LobbyServer.MAIN_SIGNS);var entries=Map.copyOf(LobbyServer.ENTRY_SIGNS);
        var floors=new LinkedHashMap<BlockPos,net.minecraft.block.BlockState>();
        var arrivals=new LinkedHashMap<String,CommunityServer.Place>();
        for(var lobby:LobbyServer.LOBBIES.values()) {
            arrivals.put(lobby.id(),LobbyServer.place(server,lobby.id()));
            for(int x=-12;x<=12;x++) for(int z=-12;z<=12;z++) {
                var pos=lobby.center().add(x,-1,z);floors.put(pos,world.getBlockState(pos));
            }
        }
        LobbyServer.installDecor(world);
        for(var floor:floors.entrySet()) c.assertEquals(world.getBlockState(floor.getKey()),floor.getValue(),"Original walking surface is untouched");
        for(var lobby:LobbyServer.LOBBIES.values()) {
            c.assertEquals(LobbyServer.place(server,lobby.id()),arrivals.get(lobby.id()),"Arrival coordinates remain stable: "+lobby.id());
            c.assertTrue(CommunityServer.safe(world,arrivals.get(lobby.id())),"Decor does not obstruct a safe arrival: "+lobby.id());
            for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) for(int y=0;y<=3;y++)
                c.assertTrue(world.getBlockState(lobby.center().add(x,y,z)).isAir(),"Arrival plaza and headroom remain open");
            for(int step=-12;step<=12;step++) for(int side=-2;side<=2;side++) for(int y=0;y<=2;y++) {
                for(var pos:new BlockPos[]{lobby.center().add(step,y,side),lobby.center().add(side,y,step)}) {
                    if(y==0 && (LobbyServer.MAIN_SIGNS.containsKey(pos)||LobbyServer.ENTRY_SIGNS.containsKey(pos))) continue;
                    c.assertTrue(world.getBlockState(pos).isAir(),"Five-wide axial path stays open: "+lobby.id()+" "+pos);
                }
            }
            var flag=lobby.center().add(-5,7,-10);
            c.assertTrue(world.getBlockState(flag).isOf(LobbyServer.palette(lobby.id()).banner()),"Gateway has its themed flag: "+lobby.id());
            c.assertTrue(world.getBlockState(flag).canPlaceAt(world,flag),"Flag has native support, including sea-lantern gateways: "+lobby.id());
        }
        c.assertTrue(world.getBlockState(LobbyServer.LOBBIES.get("main").center().add(0,7,3)).isOf(Blocks.SEA_LANTERN),"Main Hub crown is visible safely above the arrival");
        c.assertEquals(LobbyServer.MAIN_SIGNS,routes,"All five lobby routing signs retain their coordinates");
        c.assertEquals(LobbyServer.ENTRY_SIGNS,entries,"Every world/course entry retains its destination");
        c.complete();
    }
    @GameTest public void lobbyVisualUpgradeKeepsEditedSignsAndRejectsUnknownGround(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        var signPos=LobbyServer.ENTRY_SIGNS.entrySet().stream().filter(entry->entry.getValue().equals("survival")).findFirst().orElseThrow().getKey();
        var sign=(SignBlockEntity)world.getBlockEntity(signPos);
        var front=sign.getFrontText();var back=sign.getBackText();
        var accent=LobbyServer.LOBBIES.get("survival").center().add(5,6,-10);
        var floor=new BlockPos(accent.getX(),80,accent.getZ());
        var oldAccent=world.getBlockState(accent);var oldFloor=world.getBlockState(floor);
        try {
            sign.setText(front.withMessage(0,Text.literal("My custom sign")),true);
            world.setBlockState(accent,Blocks.AIR.getDefaultState(),2);
            world.setBlockState(floor,Blocks.DIAMOND_BLOCK.getDefaultState());
            LobbyServer.installDecor(world);
            c.assertEquals(sign.getFrontText().getMessage(0,false).getString(),"My custom sign","A renamed player sign is never restyled");
            c.assertTrue(world.getBlockState(accent).isAir(),"Decor is skipped above a modified platform instead of claiming player builds");
            c.assertTrue(world.getBlockState(floor).isOf(Blocks.DIAMOND_BLOCK),"Modified floor is never replaced");
            var far=new BlockPos(10_000_000,81,10_000_000);
            c.assertFalse(LobbyServer.loadedSite(world,far),"Unloaded chunks are refused");
            c.assertFalse(world.isChunkLoaded(far.getX()>>4,far.getZ()>>4),"Checking decor safety did not load an unrelated chunk");
            c.assertFalse(LobbyServer.loadedSite(world,new BlockPos(0,100_000,0)),"Out-of-height placements are refused");
        } finally {
            sign.setText(front,true);sign.setText(back,false);
            world.setBlockState(floor,oldFloor);world.setBlockState(accent,oldAccent);
        }
        c.complete();
    }
    @GameTest public void lobbyNavigationSignsAreReadableFromBothSides(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        LobbyServer.installDecor(world);
        var signs=new java.util.LinkedHashSet<BlockPos>(LobbyServer.MAIN_SIGNS.keySet());
        signs.addAll(LobbyServer.ENTRY_SIGNS.keySet());signs.addAll(LobbyServer.MENU_SIGNS);
        for(var pos:signs) {
            var sign=(SignBlockEntity)world.getBlockEntity(pos);
            c.assertTrue(sign.getFrontText().isGlowing() && sign.getBackText().isGlowing(),"Directions glow on both faces");
            for(int line=0;line<4;line++) c.assertEquals(sign.getFrontText().getMessage(line,false).getString(),sign.getBackText().getMessage(line,false).getString(),"Back face provides the same navigation");
            c.assertEquals(sign.getFrontText().getMessage(3,false).getString(),"TAP TO OPEN","Touch-screen players see the intended interaction");
        }
        c.complete();
    }
    @GameTest public void lobbyMenuSignRecoversMenuWithoutReplacingOccupiedBlocks(TestContext c) {
        var server=c.getWorld().getServer();
        var world=GameModes.world(server,GameModes.Mode.HUB);
        var pos=LobbyServer.LOBBIES.get("main").center().add(4,0,-4);
        var p=player(c,"menu-sign");
        try {
            GameModes.FIRST_VISITS.remove(p.getUuid());
            GameModes.switchNow(p,GameModes.Mode.HUB,"main");
            p.getInventory().clear();
            c.assertEquals(LobbyServer.useSign(p,pos),ActionResult.SUCCESS,"Lobby sign opens menu without a command");
            c.assertTrue(p.currentScreenHandler instanceof ServerMenu.Handler,"The sign opens the native Infinity Menu");
            c.assertTrue(java.util.stream.IntStream.range(0,36).anyMatch(slot->ServerMenu.isNavigator(p.getInventory().getStack(slot))),"Lost navigator is restored into an empty slot");
            p.closeHandledScreen();
            world.setBlockState(pos,Blocks.CHEST.getDefaultState());
            LobbyServer.installMenuSigns(server);
            c.assertTrue(world.getBlockState(pos).isOf(Blocks.CHEST),"Installing recovery signs preserves an occupied block");
            c.assertEquals(LobbyServer.useSign(p,pos),ActionResult.PASS,"An unrelated replacement block does not trigger a menu");
        } finally {
            p.closeHandledScreen();
            server.getPlayerManager().remove(p);
            world.setBlockState(pos,Blocks.AIR.getDefaultState());
            LobbyServer.installMenuSigns(server);
        }
        c.complete();
    }
    @GameTest public void lobbyBridgesConnectAllFiveModeHalls(TestContext c) {
        var world=GameModes.world(c.getWorld().getServer(),GameModes.Mode.HUB);
        for(var lobby:LobbyServer.LOBBIES.values()) if(!lobby.id().equals("main")) {
            for(int step=13;step<=35;step++) {
                int x=Math.round(lobby.center().getX()*step/48f),z=Math.round(lobby.center().getZ()*step/48f);
                var floor=world.getBlockState(new BlockPos(x,80,z));
                c.assertTrue(floor.isOf(Blocks.QUARTZ_BLOCK)||floor.isOf(Blocks.SEA_LANTERN),"Bridge to "+lobby.id()+" at "+step);
            }
        }
        c.complete();
    }
    @GameTest public void lobbyKeepsOldProfilesAndDiscardsItsOwnItems(TestContext c) {
        var p=player(c,"hub-profiles");p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,5));
        p.getEnderChestInventory().setStack(0,new ItemStack(Items.EMERALD,3));
        GameModes.FIRST_VISITS.remove(p.getUuid());
        GameModes.switchNow(p,GameModes.Mode.HUB,"creative");
        c.assertEquals(GameModes.current(p),GameModes.Mode.HUB,"Explicit hub mode");
        c.assertTrue(p.getInventory().isEmpty(),"Hub inventory is empty");
        c.assertTrue(p.getEnderChestInventory().isEmpty(),"Hub Ender Chest is empty");
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.getEntityWorld().getServer(),"creative"),p),"Player arrived in selected hall");
        p.getInventory().setStack(0,new ItemStack(Items.NETHERITE_BLOCK,64));
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        c.assertFalse(p.getInventory().contains(new ItemStack(Items.NETHERITE_BLOCK)),"Hub items cannot enter Creative");
        p.getInventory().setStack(0,new ItemStack(Items.GOLD_BLOCK,12));
        GameModes.switchNow(p,GameModes.Mode.HUB,"main");
        c.assertTrue(p.getInventory().isEmpty(),"Hub inventory resets on every visit");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertTrue(p.getInventory().getStack(0).isOf(Items.DIAMOND),"Original Survival item restored");
        c.assertEquals(p.getInventory().getStack(0).getCount(),5,"Original count restored");
        c.assertEquals(p.getEnderChestInventory().getStack(0).getCount(),3,"Original Ender Chest restored");c.complete();
    }
    @GameTest public void lobbyCursorStackStaysWithOldMode(TestContext c) {
        var p=player(c,"hub-cursor");GameModes.FIRST_VISITS.remove(p.getUuid());
        p.playerScreenHandler.setCursorStack(new ItemStack(Items.DIAMOND,7));
        GameModes.switchNow(p,GameModes.Mode.HUB,"main");
        c.assertTrue(p.currentScreenHandler.getCursorStack().isEmpty(),"No diamond stays on cursor in Hub");
        c.assertTrue(p.getInventory().isEmpty(),"No diamond in Hub inventory");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertEquals(p.getInventory().getStack(0).getCount(),7,"Cursor diamonds returned to old profile");c.complete();
    }
    @GameTest public void lobbySignsRouteToHallAndWorldWithWarmup(TestContext c) {
        var p=player(c,"sign-router");GameModes.FIRST_VISITS.remove(p.getUuid());
        GameModes.switchNow(p,GameModes.Mode.HUB,"main");
        var entry=LobbyServer.MAIN_SIGNS.entrySet().stream().filter(e->e.getValue().equals("hardcore")).findFirst().orElseThrow();
        c.assertEquals(LobbyServer.useSign(p,entry.getKey()),ActionResult.SUCCESS,"Main sign is usable");
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.getEntityWorld().getServer(),"hardcore"),p),"Main sign moved inside hub only");
        var hall=LobbyServer.ENTRY_SIGNS.entrySet().stream().filter(e->e.getValue().equals("hardcore")).findFirst().orElseThrow();
        c.assertEquals(LobbyServer.useSign(p,hall.getKey()),ActionResult.SUCCESS,"Hall entry sign is usable");
        c.assertTrue(GameModes.PENDING.containsKey(p.getUuid()),"Entering Hardcore still needs a 3-second countdown");
        c.assertEquals(GameModes.current(p),GameModes.Mode.HUB,"Sign did not skip profile transfer");
        GameModes.PENDING.remove(p.getUuid());c.complete();
    }
    @GameTest public void courseMenusStartEveryMapAndKeepInventoriesSeparate(TestContext c) {
        var p=player(c,"course-selector");GameModes.FIRST_VISITS.remove(p.getUuid());
        p.getInventory().setStack(0,new ItemStack(Items.DIAMOND,5));
        try {
            for(var mode:new GameModes.Mode[]{GameModes.Mode.MINIGAMES,GameModes.Mode.ADVENTURE}) {
                var choices=CourseSelector.courses(mode);
                c.assertEquals(choices.size(),mode==GameModes.Mode.MINIGAMES?6:2,"Every course appears in the native menu");
                for(int index=0;index<choices.size();index++) {
                    var spec=choices.get(index);
                    String hall=mode==GameModes.Mode.MINIGAMES?"minigames":"adventure";
                    GameModes.switchNow(p,GameModes.Mode.HUB,hall);
                    var sign=LobbyServer.ENTRY_SIGNS.entrySet().stream().filter(e->e.getValue().equals(hall)).findFirst().orElseThrow();
                    c.assertEquals(LobbyServer.useSign(p,sign.getKey()),ActionResult.SUCCESS,"Entry sign opens "+hall+" selector");
                    c.assertTrue(p.currentScreenHandler instanceof CourseSelector.Handler,"Vanilla chest selector is open");
                    var menu=(CourseSelector.Handler)p.currentScreenHandler;
                    int slot=CourseSelector.slot(index,choices.size());
                    c.assertEquals(((net.minecraft.screen.ScreenHandler)menu).getSlot(slot).getStack().getName().getString(),spec.title(),"Named course option is visible: "+spec.id());
                    ((net.minecraft.screen.ScreenHandler)menu).onSlotClick(slot,0,SlotActionType.PICKUP,p);
                    var pending=GameModes.PENDING.get(p.getUuid());
                    c.assertTrue(pending!=null && pending.mode()==mode && spec.id().equals(pending.map()),"Selecting "+spec.id()+" keeps the three-second route");
                    c.assertEquals(GameModes.current(p),GameModes.Mode.HUB,"Selection has not teleported before warmup");
                    GameModes.PENDING.remove(p.getUuid());GameModes.switchNow(p,mode,spec.id());
                    c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).map,spec.id(),"Selected course starts: "+spec.id());
                    c.assertTrue(p.getEntityPos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofBottomCenter(ModeMaps.start(spec.id())))<.01,"Player reaches selected start");
                    c.assertEquals(CourseSelector.useLantern(p,CourseSelector.signPos(spec)),ActionResult.SUCCESS,"Selector sign reopens menu: "+spec.id());
                    c.assertTrue(p.currentScreenHandler instanceof CourseSelector.Handler,"Selector sign uses native chest menu");
                    p.closeHandledScreen();
                    c.assertEquals(CourseSelector.useLantern(p,ModeMaps.start(spec.id()).down()),ActionResult.SUCCESS,"Glowing start reopens menu: "+spec.id());
                    c.assertTrue(p.currentScreenHandler instanceof CourseSelector.Handler,"Selector can reopen inside course");
                    var inCourse=(CourseSelector.Handler)p.currentScreenHandler;
                    int next=(index+1)%choices.size();
                    ((net.minecraft.screen.ScreenHandler)inCourse).onSlotClick(CourseSelector.slot(next,choices.size()),0,SlotActionType.PICKUP,p);
                    c.assertEquals(ModeMaps.RUNS.get(p.getUuid()).map,choices.get(next).id(),"In-course menu starts another map in the same mode");
                }
            }
            GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            c.assertTrue(p.getInventory().getStack(0).isOf(Items.DIAMOND),"Survival inventory returns after course selection");
            c.assertEquals(p.getInventory().getStack(0).getCount(),5,"Course icons did not duplicate into inventory");
        } finally {
            GameModes.PENDING.remove(p.getUuid());p.closeHandledScreen();
            if(GameModes.current(p)!=GameModes.Mode.SURVIVAL)GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            p.getEntityWorld().getServer().getPlayerManager().remove(p);
        }
        c.complete();
    }
    @GameTest public void playCommandsOpenReadOnlyCourseMenus(TestContext c) {
        var p=player(c,"play-menu");GameModes.FIRST_VISITS.remove(p.getUuid());
        try {
            var commands=c.getWorld().getServer().getCommandManager().getDispatcher();
            c.assertEquals(commands.execute("play minigames",p.getCommandSource()),1,"/play minigames opens a course menu");
            c.assertTrue(p.currentScreenHandler instanceof CourseSelector.Handler,"Minigame menu uses vanilla chest screen");
            var menu=(CourseSelector.Handler)p.currentScreenHandler;
            c.assertEquals(menu.choices.size(),6,"All six minigames are shown");
            var icon=((net.minecraft.screen.ScreenHandler)menu).getSlot(CourseSelector.slot(1,menu.choices.size())).getStack().copy();
            ((net.minecraft.screen.ScreenHandler)menu).onSlotClick(CourseSelector.slot(1,menu.choices.size()),0,SlotActionType.THROW,p);
            ((net.minecraft.screen.ScreenHandler)menu).onSlotClick(CourseSelector.slot(1,menu.choices.size()),0,SlotActionType.PICKUP_ALL,p);
            c.assertTrue(ItemStack.areItemsAndComponentsEqual(icon,((net.minecraft.screen.ScreenHandler)menu).getSlot(CourseSelector.slot(1,menu.choices.size())).getStack()),"Menu icon cannot be taken");
            c.assertTrue(((net.minecraft.screen.ScreenHandler)menu).getCursorStack().isEmpty() && p.getInventory().isEmpty(),"Invalid clicks create no items");
            p.closeHandledScreen();
            c.assertEquals(commands.execute("play adventure",p.getCommandSource()),1,"/play adventure opens the other menu");
            c.assertTrue(p.currentScreenHandler instanceof CourseSelector.Handler,"Adventure menu uses vanilla chest screen");
            c.assertEquals(((CourseSelector.Handler)p.currentScreenHandler).choices.size(),2,"Both adventure maps are shown");
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException error) {throw new RuntimeException(error);}
        finally {p.closeHandledScreen();p.getEntityWorld().getServer().getPlayerManager().remove(p);}
        c.complete();
    }
    @GameTest public void lobbyVoidFallReturnsToSelectedSafeHall(TestContext c) {
        var p=player(c,"hub-rescue");GameModes.FIRST_VISITS.remove(p.getUuid());
        GameModes.switchNow(p,GameModes.Mode.HUB,"minigames");
        p.setPosition(p.getX(),70,p.getZ());LobbyServer.tick(p);
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.getEntityWorld().getServer(),"minigames"),p),"Falling player returned safely");c.complete();
    }
    @GameTest public void firstVisitRoutesNewPlayerAndHardcoreEliminationPersists(TestContext c) {
        var p=player(c,"hub-welcome");
        GameModes.FIRST_VISITS.add(p.getUuid()); // TestServer spawns mock combat players directly.
        c.assertTrue(GameModes.FIRST_VISITS.contains(p.getUuid()),"New player scheduled for main hub");
        c.assertTrue(GameModes.routeFirstVisit(p),"Ready player routed");
        c.assertEquals(GameModes.current(p),GameModes.Mode.HUB,"First visit begins in hub");
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.getEntityWorld().getServer(),"main"),p),"Arrived at main platform");
        GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);GameModes.death(p);
        GameModes.switchNow(p,GameModes.Mode.HUB,"hardcore");
        c.assertEquals(GameModes.current(p),GameModes.Mode.HUB,"Eliminated player may visit lobby");
        GameModes.request(p,GameModes.Mode.HARDCORE,null);
        c.assertFalse(GameModes.PENDING.containsKey(p.getUuid()),"Lobby cannot reset a Hardcore life");c.complete();
    }
    @GameTest public void guideNamesRealCommandsForTheCurrentHallAndWorld(TestContext c) {
        var p=player(c,"guide-context");
        try {
            GameModes.switchNow(p,GameModes.Mode.HUB,"main");
            c.assertTrue(GameModes.guideText(p).contains("/lobbies"),"Main Hub explains the physical halls");
            for(var lobby:LobbyServer.LOBBIES.values()) if(!lobby.id().equals("main")) {
                LobbyServer.arrive(p,lobby.id());
                c.assertTrue(GameModes.guideText(p).contains("/play "+lobby.id()),"Hall guide enters its matching world: "+lobby.id());
            }
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"parkour");
            c.assertTrue(GameModes.guideText(p).contains("/leaderboard <map>"),"Minigame guide teaches records");
            GameModes.switchNow(p,GameModes.Mode.ADVENTURE,"ruins");
            c.assertTrue(GameModes.guideText(p).contains("/play adventure"),"Adventure guide opens the map selector");
            GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
            c.assertTrue(GameModes.guideText(p).contains("Infinity Menu"),"Creative guide explains the Infinity Menu");
            GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            c.assertTrue(GameModes.guideText(p).contains("/backpack"),"Survival guide names a usable player feature");
            var root=c.getWorld().getServer().getCommandManager().getDispatcher().getRoot();
            c.assertTrue(root.getChild("guide")!=null,"Crossplay text guide is a real command");
        } finally {GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);p.getEntityWorld().getServer().getPlayerManager().remove(p);}c.complete();
    }
}

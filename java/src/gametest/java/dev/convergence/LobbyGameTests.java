package dev.convergence;

import java.nio.file.Files;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class LobbyGameTests {
    private ServerPlayer player(GameTestHelper c,String name){return new ModeGameTests().player(c,name);}
    @GameTest public void firstVisitDetectionReadsTheActualSavedPlayerFile(GameTestHelper c) {
        var p=player(c,"returning-visitor");var server=c.getLevel().getServer();
        try {
            server.getPlayerList().saveAll();
            c.assertTrue(java.nio.file.Files.isRegularFile(GameModes.savedPlayerFile(server,p.getUUID())),"A saved player is recognized as returning, not as a first visit");
            c.assertFalse(java.nio.file.Files.exists(GameModes.savedPlayerFile(server,java.util.UUID.randomUUID())),"An unknown player has no saved file");
        } finally {server.getPlayerList().remove(p);}
        c.succeed();
    }
    @GameTest public void lobbyMainAndFiveModeAreasAreSafeAndLabeled(GameTestHelper c) {
        var server=c.getLevel().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        c.assertTrue(world!=null,"Dedicated hub dimension loaded");
        c.assertValueEqual(LobbyServer.LOBBIES.size(),6,"Main plus five mode lobbies");
        for(var lobby:LobbyServer.LOBBIES.values()) {
            var place=LobbyServer.place(server,lobby.id());
            c.assertTrue(CommunityServer.safe(world,place),"Safe lobby floor: "+lobby.id());
            c.assertTrue(world.getBlockState(lobby.center().below()).is(Blocks.SEA_LANTERN),"Lit center: "+lobby.id());
            if(!lobby.id().equals("main"))
                c.assertTrue(world.getBlockEntity(lobby.center().offset(0,0,-4)) instanceof SignBlockEntity,"Native sign at "+lobby.id());
        }
        c.assertTrue(Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("infinity-built-in-lobbies.json")),"Generation marker saved");
        for (var pos : LobbyServer.MENU_SIGNS)
            c.assertTrue(LobbyServer.isMenuSign(world, pos), "Every lobby has an Infinity Menu recovery sign");
        c.assertTrue(Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("infinity-course-selector-signs.json")),"Course selector upgrade marker saved");
        for(var spec:ModeMaps.MAPS.values()) {
            var mapWorld=GameModes.world(server,spec.mode());
            c.assertTrue(CourseSelector.selectorSign(mapWorld,CourseSelector.signPos(spec)),"Course selector sign is available at "+spec.id());
        }
        c.succeed();
    }
    @GameTest public void courseSelectorUpgradeRecognizesExistingSignsAndRejectsOccupiedSites(GameTestHelper c) {
        var server=c.getLevel().getServer();var world=c.getLevel();
        var occupied=c.absolutePos(new BlockPos(2,5,2));
        var original=world.getBlockState(occupied);
        try {
            var marker=Files.createTempDirectory("selector-upgrade-").resolve("signs.json");
            CourseSelector.installSigns(server,marker);
            c.assertValueEqual(CourseSelector.installed(marker).size(),ModeMaps.MAPS.size(),"Upgrade records existing selector signs without rebuilding maps");
            var spec=ModeMaps.MAPS.get("parkour");
            var courseWorld=GameModes.world(server,spec.mode());
            var lostSign=CourseSelector.signPos(spec);
            courseWorld.setBlockAndUpdate(lostSign,Blocks.AIR.defaultBlockState());
            CourseSelector.installSigns(server,marker);
            c.assertTrue(CourseSelector.selectorSign(courseWorld,lostSign),"Marker-listed sign is restored after its chunk loses the sign");
            world.setBlockAndUpdate(occupied,Blocks.CHEST.defaultBlockState());
            c.assertFalse(CourseSelector.canPlaceSign(world,occupied),"Occupied selector site is refused");
            c.assertTrue(world.getBlockState(occupied).is(Blocks.CHEST),"Occupied block is preserved");
        } catch(java.io.IOException error) {throw new RuntimeException(error);}
        finally {world.setBlockAndUpdate(occupied,original);}
        c.succeed();
    }
    @GameTest public void lobbyRebuildNeverOverwritesExistingBlocks(GameTestHelper c) {
        var server=c.getLevel().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        var pos=new BlockPos(7,81,7);var old=world.getBlockState(pos);
        try {
            world.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
            LobbyServer.build(server);
            c.assertTrue(world.getBlockState(pos).is(Blocks.CHEST),"Saved lobby is not rebuilt on restart");
        } finally {world.setBlockAndUpdate(pos,old);}
        c.succeed();
    }
    @GameTest public void lobbyVisualUpgradeRestoresMissingAccentsAndPreservesPlayerStorage(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        var occupied=LobbyServer.LOBBIES.get("main").center().offset(-9,0,-5);
        var missing=LobbyServer.LOBBIES.get("creative").center().offset(5,6,-10);
        var oldOccupied=world.getBlockState(occupied);var oldMissing=world.getBlockState(missing);
        try {
            world.setBlockAndUpdate(occupied,Blocks.CHEST.defaultBlockState());
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)world.getBlockEntity(occupied);
            chest.setItem(0,new ItemStack(Items.DIAMOND,13));
            world.setBlock(missing,Blocks.AIR.defaultBlockState(),2);
            c.assertTrue(LobbyServer.installDecor(world)>0,"An older lobby receives a missing colored lantern without a rebuild");
            c.assertTrue(world.getBlockState(missing).is(Blocks.PEARLESCENT_FROGLIGHT),"Creative's purple gateway receives its matching light");
            c.assertTrue(world.getBlockState(occupied).is(Blocks.CHEST),"A player's block at a planned planter is preserved");
            c.assertValueEqual(chest.getItem(0).getCount(),13,"Player storage contents survive the upgrade");
            c.assertTrue(chest.getItem(0).is(Items.DIAMOND),"Stored item type is unchanged");
            c.assertValueEqual(LobbyServer.installDecor(world),0,"Repeating the upgrade changes nothing, including signs");
        } finally {
            world.setBlockAndUpdate(occupied,oldOccupied);world.setBlockAndUpdate(missing,oldMissing);
        }
        c.succeed();
    }
    @GameTest public void lobbyChunkLoadDefersRepairsUntilItsCompletedWorldTick(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        var center=LobbyServer.LOBBIES.get("creative").center();
        var accent=center.offset(5,6,-10);
        var occupied=center.offset(9,0,-5);
        var signPos=center.offset(0,0,-4);
        var otherAccent=LobbyServer.LOBBIES.get("main").center().offset(5,6,-10);
        var chunk=world.getChunkAt(accent);
        var originalAccent=chunk.getBlockState(accent);
        var originalOccupied=chunk.getBlockState(occupied);
        var originalOther=world.getBlockState(otherAccent);
        var sign=(SignBlockEntity)chunk.getBlockEntity(signPos);
        var front=sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT);var back=sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK);
        int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
        // Drain startup work before creating missing blocks, so this event is the only queued repair.
        ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
        try {
            world.setBlock(accent,Blocks.AIR.defaultBlockState(),flags);
            world.setBlock(otherAccent,Blocks.AIR.defaultBlockState(),flags);
            world.setBlock(occupied,Blocks.CHEST.defaultBlockState(),flags);
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)chunk.getBlockEntity(occupied);
            chest.setItem(0,new ItemStack(Items.DIAMOND,13));
            sign.setText(front.asMutable().setLine(0,Component.literal("Keep my arrival sign")).asImmutable(),net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            sign.setText(back.asMutable().setLine(1,Component.literal("Keep this back too")).asImmutable(),net.minecraft.world.level.block.entity.SignTextSlot.BACK);
            int loadedChunks=world.getChunkSource().getLoadedChunksCount();

            // Dispatch the registered native event, not the installer helper. A synchronous
            // repair here can ask for the same still-pending chunk future and freeze a join.
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,chunk,false);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"Chunk-load callback only queues work while its full-chunk future may be pending");
            c.assertValueEqual(world.getChunkSource().getLoadedChunksCount(),loadedChunks,"Enqueuing lobby repair does not load chunks");

            ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).is(Blocks.PEARLESCENT_FROGLIGHT),"The completed captured chunk is repaired after the world tick");
            c.assertTrue(world.getBlockState(otherAccent).isAir(),"A chunk-load repair does not sweep another lobby chunk");
            c.assertTrue(chunk.getBlockState(occupied).is(Blocks.CHEST),"Deferred repair preserves player storage");
            c.assertTrue(chunk.getBlockEntity(occupied)==chest,"Deferred repair retains the original chest block entity");
            c.assertTrue(chest.getItem(0).is(Items.DIAMOND),"Deferred repair keeps the stored item type");
            c.assertValueEqual(chest.getItem(0).getCount(),13,"Deferred repair keeps the stored item count");
            c.assertValueEqual(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(0).getString(),"Keep my arrival sign","Deferred repair preserves edited front text");
            c.assertValueEqual(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK).getMessages(false).get(1).getString(),"Keep this back too","Deferred repair preserves edited back text");
            c.assertValueEqual(world.getChunkSource().getLoadedChunksCount(),loadedChunks,"Draining one completed repair does not load neighboring chunks");

            world.setBlock(accent,Blocks.AIR.defaultBlockState(),flags);
            ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"A completed queue entry is consumed rather than rebuilding every tick");
        } finally {
            sign.setText(front,net.minecraft.world.level.block.entity.SignTextSlot.FRONT);sign.setText(back,net.minecraft.world.level.block.entity.SignTextSlot.BACK);
            world.setBlock(accent,originalAccent,flags);
            world.setBlock(occupied,originalOccupied,flags);
            world.setBlock(otherAccent,originalOther,flags);
        }
        c.succeed();
    }
    @GameTest public void lobbyChunkRepairRejectsStaleEventsAndCancelsUnloadedChunks(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        var accent=LobbyServer.LOBBIES.get("creative").center().offset(5,6,-10);
        var chunk=world.getChunkAt(accent);
        var original=chunk.getBlockState(accent);
        int flags=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
        ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
        try {
            world.setBlock(accent,Blocks.AIR.defaultBlockState(),flags);
            // The native chunk has the same position but has never been published by the
            // manager. Preloaded hub fixtures must not hide an event/manager identity bug.
            var stale=new LevelChunk(world,chunk.getPos());
            c.assertTrue(world.getChunkSource().getChunkNow(chunk.getPos().x(),chunk.getPos().z())==chunk,"Fixture has a distinct completed manager chunk");
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,stale,false);
            ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"An event for another chunk instance cannot repair the resident chunk");
            c.assertTrue(stale.getBlockState(accent).isAir(),"An unpublished event chunk is not mutated");

            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,chunk,false);
            ServerChunkEvents.CHUNK_UNLOAD.invoker().onChunkUnload(world,chunk);
            ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).isAir(),"Unloading cancels a queued repair even if a completed instance is still visible");

            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,chunk,false);
            ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
            c.assertTrue(chunk.getBlockState(accent).is(Blocks.PEARLESCENT_FROGLIGHT),"A fresh load event can repair the chunk after cancellation");
        } finally {world.setBlock(accent,original,flags);}
        c.succeed();
    }
    @GameTest public void lobbyChunkLoadOutsideBuiltPlatformsNeverLoadsItsNeighbors(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        var distant=new ChunkPos(625_000,625_000);
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            c.assertFalse(world.hasChunk(distant.x()+x,distant.z()+z),"Distant fixture and its neighbors start unloaded");
        var unpublished=new LevelChunk(world,distant);
        ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(world,unpublished,false);
        ServerTickEvents.END_LEVEL_TICK.invoker().onEndTick(world);
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++)
            c.assertFalse(world.hasChunk(distant.x()+x,distant.z()+z),"Unknown chunk event does not request this chunk or any neighbor");
        for (var section : unpublished.getSections())
            c.assertTrue(section.hasOnlyAir(),"Unknown chunk event leaves every native chunk section empty");
        c.succeed();
    }
    @GameTest public void lobbyVisualUpgradeKeepsArrivalBridgesAndNavigationUnchanged(GameTestHelper c) {
        var server=c.getLevel().getServer();var world=GameModes.world(server,GameModes.Mode.HUB);
        var routes=Map.copyOf(LobbyServer.MAIN_SIGNS);var entries=Map.copyOf(LobbyServer.ENTRY_SIGNS);
        var floors=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var arrivals=new LinkedHashMap<String,CommunityServer.Place>();
        for(var lobby:LobbyServer.LOBBIES.values()) {
            arrivals.put(lobby.id(),LobbyServer.place(server,lobby.id()));
            for(int x=-12;x<=12;x++) for(int z=-12;z<=12;z++) {
                var pos=lobby.center().offset(x,-1,z);floors.put(pos,world.getBlockState(pos));
            }
        }
        LobbyServer.installDecor(world);
        for(var floor:floors.entrySet()) c.assertValueEqual(world.getBlockState(floor.getKey()),floor.getValue(),"Original walking surface is untouched");
        for(var lobby:LobbyServer.LOBBIES.values()) {
            c.assertValueEqual(LobbyServer.place(server,lobby.id()),arrivals.get(lobby.id()),"Arrival coordinates remain stable: "+lobby.id());
            c.assertTrue(CommunityServer.safe(world,arrivals.get(lobby.id())),"Decor does not obstruct a safe arrival: "+lobby.id());
            for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) for(int y=0;y<=3;y++)
                c.assertTrue(world.getBlockState(lobby.center().offset(x,y,z)).isAir(),"Arrival plaza and headroom remain open");
            for(int step=-12;step<=12;step++) for(int side=-2;side<=2;side++) for(int y=0;y<=2;y++) {
                for(var pos:new BlockPos[]{lobby.center().offset(step,y,side),lobby.center().offset(side,y,step)}) {
                    if(y==0 && (LobbyServer.MAIN_SIGNS.containsKey(pos)||LobbyServer.ENTRY_SIGNS.containsKey(pos))) continue;
                    c.assertTrue(world.getBlockState(pos).isAir(),"Five-wide axial path stays open: "+lobby.id()+" "+pos);
                }
            }
            var flag=lobby.center().offset(-5,7,-10);
            c.assertTrue(world.getBlockState(flag).is(LobbyServer.palette(lobby.id()).banner()),"Gateway has its themed flag: "+lobby.id());
            c.assertTrue(world.getBlockState(flag).canSurvive(world,flag),"Flag has native support, including sea-lantern gateways: "+lobby.id());
        }
        c.assertTrue(world.getBlockState(LobbyServer.LOBBIES.get("main").center().offset(0,7,3)).is(Blocks.SEA_LANTERN),"Main Hub crown is visible safely above the arrival");
        c.assertValueEqual(LobbyServer.MAIN_SIGNS,routes,"All five lobby routing signs retain their coordinates");
        c.assertValueEqual(LobbyServer.ENTRY_SIGNS,entries,"Every world/course entry retains its destination");
        c.succeed();
    }
    @GameTest public void lobbyVisualUpgradeKeepsEditedSignsAndRejectsUnknownGround(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        var signPos=LobbyServer.ENTRY_SIGNS.entrySet().stream().filter(entry->entry.getValue().equals("survival")).findFirst().orElseThrow().getKey();
        var sign=(SignBlockEntity)world.getBlockEntity(signPos);
        var front=sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT);var back=sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK);
        var accent=LobbyServer.LOBBIES.get("survival").center().offset(5,6,-10);
        var floor=new BlockPos(accent.getX(),80,accent.getZ());
        var oldAccent=world.getBlockState(accent);var oldFloor=world.getBlockState(floor);
        try {
            sign.setText(front.asMutable().setLine(0,Component.literal("My custom sign")).asImmutable(),net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            world.setBlock(accent,Blocks.AIR.defaultBlockState(),2);
            world.setBlockAndUpdate(floor,Blocks.DIAMOND_BLOCK.defaultBlockState());
            LobbyServer.installDecor(world);
            c.assertValueEqual(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(0).getString(),"My custom sign","A renamed player sign is never restyled");
            c.assertTrue(world.getBlockState(accent).isAir(),"Decor is skipped above a modified platform instead of claiming player builds");
            c.assertTrue(world.getBlockState(floor).is(Blocks.DIAMOND_BLOCK),"Modified floor is never replaced");
            var far=new BlockPos(10_000_000,81,10_000_000);
            c.assertFalse(LobbyServer.loadedSite(world,far),"Unloaded chunks are refused");
            c.assertFalse(world.hasChunk(far.getX()>>4,far.getZ()>>4),"Checking decor safety did not load an unrelated chunk");
            c.assertFalse(LobbyServer.loadedSite(world,new BlockPos(0,100_000,0)),"Out-of-height placements are refused");
        } finally {
            sign.setText(front,net.minecraft.world.level.block.entity.SignTextSlot.FRONT);sign.setText(back,net.minecraft.world.level.block.entity.SignTextSlot.BACK);
            world.setBlockAndUpdate(floor,oldFloor);world.setBlockAndUpdate(accent,oldAccent);
        }
        c.succeed();
    }
    @GameTest public void lobbyNavigationSignsAreReadableFromBothSides(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        LobbyServer.installDecor(world);
        var signs=new java.util.LinkedHashSet<BlockPos>(LobbyServer.MAIN_SIGNS.keySet());
        signs.addAll(LobbyServer.ENTRY_SIGNS.keySet());signs.addAll(LobbyServer.MENU_SIGNS);
        for(var pos:signs) {
            var sign=(SignBlockEntity)world.getBlockEntity(pos);
            c.assertTrue(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).hasGlowingText() && sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK).hasGlowingText(),"Directions glow on both faces");
            for(int line=0;line<4;line++) c.assertValueEqual(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(line).getString(),sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.BACK).getMessages(false).get(line).getString(),"Back face provides the same navigation");
            c.assertValueEqual(sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).get(3).getString(),"TAP TO OPEN","Touch-screen players see the intended interaction");
        }
        c.succeed();
    }
    @GameTest public void lobbyMenuSignRecoversMenuWithoutReplacingOccupiedBlocks(GameTestHelper c) {
        var server=c.getLevel().getServer();
        var world=GameModes.world(server,GameModes.Mode.HUB);
        var pos=LobbyServer.LOBBIES.get("main").center().offset(4,0,-4);
        var p=player(c,"menu-sign");
        try {
            GameModes.FIRST_VISITS.remove(p.getUUID());
            GameModes.switchNow(p,GameModes.Mode.HUB,"main");
            p.getInventory().clearContent();
            c.assertValueEqual(LobbyServer.useSign(p,pos),InteractionResult.SUCCESS,"Lobby sign opens menu without a command");
            c.assertTrue(p.containerMenu instanceof ServerMenu.Handler,"The sign opens the native Infinity Menu");
            c.assertTrue(java.util.stream.IntStream.range(0,36).anyMatch(slot->ServerMenu.isNavigator(p.getInventory().getItem(slot))),"Lost navigator is restored into an empty slot");
            p.closeContainer();
            world.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
            LobbyServer.installMenuSigns(server);
            c.assertTrue(world.getBlockState(pos).is(Blocks.CHEST),"Installing recovery signs preserves an occupied block");
            c.assertValueEqual(LobbyServer.useSign(p,pos),InteractionResult.PASS,"An unrelated replacement block does not trigger a menu");
        } finally {
            p.closeContainer();
            server.getPlayerList().remove(p);
            world.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            LobbyServer.installMenuSigns(server);
        }
        c.succeed();
    }
    @GameTest public void lobbyBridgesConnectAllFiveModeHalls(GameTestHelper c) {
        var world=GameModes.world(c.getLevel().getServer(),GameModes.Mode.HUB);
        for(var lobby:LobbyServer.LOBBIES.values()) if(!lobby.id().equals("main")) {
            for(int step=13;step<=35;step++) {
                int x=Math.round(lobby.center().getX()*step/48f),z=Math.round(lobby.center().getZ()*step/48f);
                var floor=world.getBlockState(new BlockPos(x,80,z));
                c.assertTrue(floor.is(Blocks.QUARTZ_BLOCK)||floor.is(Blocks.SEA_LANTERN),"Bridge to "+lobby.id()+" at "+step);
            }
        }
        c.succeed();
    }
    @GameTest public void lobbyKeepsOldProfilesAndDiscardsItsOwnItems(GameTestHelper c) {
        var p=player(c,"hub-profiles");p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,5));
        p.getEnderChestInventory().setItem(0,new ItemStack(Items.EMERALD,3));
        GameModes.FIRST_VISITS.remove(p.getUUID());
        GameModes.switchNow(p,GameModes.Mode.HUB,"creative");
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.HUB,"Explicit hub mode");
        c.assertTrue(p.getInventory().isEmpty(),"Hub inventory is empty");
        c.assertTrue(p.getEnderChestInventory().isEmpty(),"Hub Ender Chest is empty");
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.level().getServer(),"creative"),p),"Player arrived in selected hall");
        p.getInventory().setItem(0,new ItemStack(Items.NETHERITE_BLOCK,64));
        GameModes.switchNow(p,GameModes.Mode.CREATIVE,null);
        c.assertFalse(p.getInventory().contains(new ItemStack(Items.NETHERITE_BLOCK)),"Hub items cannot enter Creative");
        p.getInventory().setItem(0,new ItemStack(Items.GOLD_BLOCK,12));
        GameModes.switchNow(p,GameModes.Mode.HUB,"main");
        c.assertTrue(p.getInventory().isEmpty(),"Hub inventory resets on every visit");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND),"Original Survival item restored");
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),5,"Original count restored");
        c.assertValueEqual(p.getEnderChestInventory().getItem(0).getCount(),3,"Original Ender Chest restored");c.succeed();
    }
    @GameTest public void lobbyCursorStackStaysWithOldMode(GameTestHelper c) {
        var p=player(c,"hub-cursor");GameModes.FIRST_VISITS.remove(p.getUUID());
        p.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND,7));
        GameModes.switchNow(p,GameModes.Mode.HUB,"main");
        c.assertTrue(p.containerMenu.getCarried().isEmpty(),"No diamond stays on cursor in Hub");
        c.assertTrue(p.getInventory().isEmpty(),"No diamond in Hub inventory");
        GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
        c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Cursor diamonds returned to old profile");c.succeed();
    }
    @GameTest public void lobbySignsRouteToHallAndWorldWithWarmup(GameTestHelper c) {
        var p=player(c,"sign-router");GameModes.FIRST_VISITS.remove(p.getUUID());
        GameModes.switchNow(p,GameModes.Mode.HUB,"main");
        var entry=LobbyServer.MAIN_SIGNS.entrySet().stream().filter(e->e.getValue().equals("hardcore")).findFirst().orElseThrow();
        c.assertValueEqual(LobbyServer.useSign(p,entry.getKey()),InteractionResult.SUCCESS,"Main sign is usable");
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.level().getServer(),"hardcore"),p),"Main sign moved inside hub only");
        var hall=LobbyServer.ENTRY_SIGNS.entrySet().stream().filter(e->e.getValue().equals("hardcore")).findFirst().orElseThrow();
        c.assertValueEqual(LobbyServer.useSign(p,hall.getKey()),InteractionResult.SUCCESS,"Hall entry sign is usable");
        c.assertTrue(GameModes.PENDING.containsKey(p.getUUID()),"Entering Hardcore still needs a 3-second countdown");
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.HUB,"Sign did not skip profile transfer");
        GameModes.PENDING.remove(p.getUUID());c.succeed();
    }
    @GameTest public void courseMenusStartEveryMapAndKeepInventoriesSeparate(GameTestHelper c) {
        var p=player(c,"course-selector");GameModes.FIRST_VISITS.remove(p.getUUID());
        p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,5));
        try {
            for(var mode:new GameModes.Mode[]{GameModes.Mode.MINIGAMES,GameModes.Mode.ADVENTURE}) {
                var choices=CourseSelector.courses(mode);
                c.assertValueEqual(choices.size(),mode==GameModes.Mode.MINIGAMES?6:2,"Every course appears in the native menu");
                for(int index=0;index<choices.size();index++) {
                    var spec=choices.get(index);
                    String hall=mode==GameModes.Mode.MINIGAMES?"minigames":"adventure";
                    GameModes.switchNow(p,GameModes.Mode.HUB,hall);
                    var sign=LobbyServer.ENTRY_SIGNS.entrySet().stream().filter(e->e.getValue().equals(hall)).findFirst().orElseThrow();
                    c.assertValueEqual(LobbyServer.useSign(p,sign.getKey()),InteractionResult.SUCCESS,"Entry sign opens "+hall+" selector");
                    c.assertTrue(p.containerMenu instanceof CourseSelector.Handler,"Vanilla chest selector is open");
                    var menu=(CourseSelector.Handler)p.containerMenu;
                    int slot=CourseSelector.slot(index,choices.size());
                    c.assertValueEqual(((net.minecraft.world.inventory.AbstractContainerMenu)menu).getSlot(slot).getItem().getHoverName().getString(),spec.title(),"Named course option is visible: "+spec.id());
                    ((net.minecraft.world.inventory.AbstractContainerMenu)menu).clicked(slot,0,ContainerInput.PICKUP,p);
                    var pending=GameModes.PENDING.get(p.getUUID());
                    c.assertTrue(pending!=null && pending.mode()==mode && spec.id().equals(pending.map()),"Selecting "+spec.id()+" keeps the three-second route");
                    c.assertValueEqual(GameModes.current(p),GameModes.Mode.HUB,"Selection has not teleported before warmup");
                    GameModes.PENDING.remove(p.getUUID());GameModes.switchNow(p,mode,spec.id());
                    c.assertValueEqual(ModeMaps.RUNS.get(p.getUUID()).map,spec.id(),"Selected course starts: "+spec.id());
                    c.assertTrue(p.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(ModeMaps.start(spec.id())))<.01,"Player reaches selected start");
                    c.assertValueEqual(CourseSelector.useLantern(p,CourseSelector.signPos(spec)),InteractionResult.SUCCESS,"Selector sign reopens menu: "+spec.id());
                    c.assertTrue(p.containerMenu instanceof CourseSelector.Handler,"Selector sign uses native chest menu");
                    p.closeContainer();
                    c.assertValueEqual(CourseSelector.useLantern(p,ModeMaps.start(spec.id()).below()),InteractionResult.SUCCESS,"Glowing start reopens menu: "+spec.id());
                    c.assertTrue(p.containerMenu instanceof CourseSelector.Handler,"Selector can reopen inside course");
                    var inCourse=(CourseSelector.Handler)p.containerMenu;
                    int next=(index+1)%choices.size();
                    ((net.minecraft.world.inventory.AbstractContainerMenu)inCourse).clicked(CourseSelector.slot(next,choices.size()),0,ContainerInput.PICKUP,p);
                    c.assertValueEqual(ModeMaps.RUNS.get(p.getUUID()).map,choices.get(next).id(),"In-course menu starts another map in the same mode");
                }
            }
            GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            c.assertTrue(p.getInventory().getItem(0).is(Items.DIAMOND),"Survival inventory returns after course selection");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),5,"Course icons did not duplicate into inventory");
        } finally {
            GameModes.PENDING.remove(p.getUUID());p.closeContainer();
            if(GameModes.current(p)!=GameModes.Mode.SURVIVAL)GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            p.level().getServer().getPlayerList().remove(p);
        }
        c.succeed();
    }
    @GameTest public void playCommandsOpenReadOnlyCourseMenus(GameTestHelper c) {
        var p=player(c,"play-menu");GameModes.FIRST_VISITS.remove(p.getUUID());
        try {
            var commands=c.getLevel().getServer().getCommands().getDispatcher();
            c.assertValueEqual(commands.execute("play minigames",p.createCommandSourceStack()),1,"/play minigames opens a course menu");
            c.assertTrue(p.containerMenu instanceof CourseSelector.Handler,"Minigame menu uses vanilla chest screen");
            var menu=(CourseSelector.Handler)p.containerMenu;
            c.assertValueEqual(menu.choices.size(),6,"All six minigames are shown");
            var icon=((net.minecraft.world.inventory.AbstractContainerMenu)menu).getSlot(CourseSelector.slot(1,menu.choices.size())).getItem().copy();
            ((net.minecraft.world.inventory.AbstractContainerMenu)menu).clicked(CourseSelector.slot(1,menu.choices.size()),0,ContainerInput.THROW,p);
            ((net.minecraft.world.inventory.AbstractContainerMenu)menu).clicked(CourseSelector.slot(1,menu.choices.size()),0,ContainerInput.PICKUP_ALL,p);
            c.assertTrue(ItemStack.isSameItemSameComponents(icon,((net.minecraft.world.inventory.AbstractContainerMenu)menu).getSlot(CourseSelector.slot(1,menu.choices.size())).getItem()),"Menu icon cannot be taken");
            c.assertTrue(((net.minecraft.world.inventory.AbstractContainerMenu)menu).getCarried().isEmpty() && p.getInventory().isEmpty(),"Invalid clicks create no items");
            p.closeContainer();
            c.assertValueEqual(commands.execute("play adventure",p.createCommandSourceStack()),1,"/play adventure opens the other menu");
            c.assertTrue(p.containerMenu instanceof CourseSelector.Handler,"Adventure menu uses vanilla chest screen");
            c.assertValueEqual(((CourseSelector.Handler)p.containerMenu).choices.size(),2,"Both adventure maps are shown");
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException error) {throw new RuntimeException(error);}
        finally {p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
        c.succeed();
    }
    @GameTest public void lobbyVoidFallReturnsToSelectedSafeHall(GameTestHelper c) {
        var p=player(c,"hub-rescue");GameModes.FIRST_VISITS.remove(p.getUUID());
        GameModes.switchNow(p,GameModes.Mode.HUB,"minigames");
        p.setPos(p.getX(),70,p.getZ());LobbyServer.tick(p);
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.level().getServer(),"minigames"),p),"Falling player returned safely");c.succeed();
    }
    @GameTest public void firstVisitRoutesNewPlayerAndHardcoreEliminationPersists(GameTestHelper c) {
        var p=player(c,"hub-welcome");
        GameModes.FIRST_VISITS.add(p.getUUID()); // TestServer spawns mock combat players directly.
        c.assertTrue(GameModes.FIRST_VISITS.contains(p.getUUID()),"New player scheduled for main hub");
        c.assertTrue(GameModes.routeFirstVisit(p),"Ready player routed");
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.HUB,"First visit begins in hub");
        c.assertFalse(CommunityServer.moved(LobbyServer.place(p.level().getServer(),"main"),p),"Arrived at main platform");
        GameModes.switchNow(p,GameModes.Mode.HARDCORE,null);GameModes.death(p);
        GameModes.switchNow(p,GameModes.Mode.HUB,"hardcore");
        c.assertValueEqual(GameModes.current(p),GameModes.Mode.HUB,"Eliminated player may visit lobby");
        GameModes.request(p,GameModes.Mode.HARDCORE,null);
        c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()),"Lobby cannot reset a Hardcore life");c.succeed();
    }
    @GameTest public void guideNamesRealCommandsForTheCurrentHallAndWorld(GameTestHelper c) {
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
            var root=c.getLevel().getServer().getCommands().getDispatcher().getRoot();
            c.assertTrue(root.getChild("guide")!=null,"Crossplay text guide is a real command");
        } finally {GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);p.level().getServer().getPlayerList().remove(p);}c.succeed();
    }
}

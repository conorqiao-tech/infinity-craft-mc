package dev.convergence;

import java.nio.file.Files;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

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
        c.assertTrue(p.getInventory().isEmpty(),"Hub items cannot enter Creative");
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
            c.assertTrue(GameModes.guideText(p).contains("/adventure ruins or maze"),"Adventure guide lists both maps");
            GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);
            c.assertTrue(GameModes.guideText(p).contains("/backpack"),"Survival guide names a usable player feature");
            var root=c.getWorld().getServer().getCommandManager().getDispatcher().getRoot();
            c.assertTrue(root.getChild("guide")!=null,"Crossplay text guide is a real command");
        } finally {GameModes.switchNow(p,GameModes.Mode.SURVIVAL,null);p.getEntityWorld().getServer().getPlayerManager().remove(p);}c.complete();
    }
}

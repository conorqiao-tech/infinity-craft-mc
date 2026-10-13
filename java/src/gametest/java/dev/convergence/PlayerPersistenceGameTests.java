package dev.convergence;

import java.nio.file.Files;
import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;

public class PlayerPersistenceGameTests {
    @GameTest public void nativeLocalSaveRetainsInventoryModeAndEquipmentWithoutSavingObserver(GameTestHelper c) throws Exception {
        var server=c.getLevel().getServer();
        var player=new ModeGameTests().player(c,"save-participant");
        var observer=new ModeGameTests().player(c,"save-observer");
        try {
            PlayerPersistence.save(observer);
            var before=Files.readAllBytes(GameModes.savedPlayerFile(server,observer.getUUID()));
            GameModes.state(observer).putString("unsaved_observer","must remain in memory");
            player.getInventory().setItem(8,new ItemStack(Items.EMERALD,9));
            player.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Convergence.ITEMS.get("convergence:cuirass")));
            player.setItemSlot(EquipmentSlot.OFFHAND,new ItemStack(Convergence.ITEMS.get("convergence:shield")));
            GameModes.state(player).putString("local_receipt","persist this participant");
            PlayerPersistence.save(player);
            var saved=NbtIo.readCompressed(GameModes.savedPlayerFile(server,player.getUUID()),NbtAccounter.create(64L*1024*1024));
            c.assertValueEqual(saved.getCompoundOrEmpty("InfinityModes").getStringOr("local_receipt",""),"persist this participant","Native file contains participant state");
            var restored=new ServerPlayer(server,player.level(),player.getGameProfile(),player.clientInformation());
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING,player.registryAccess(),saved));
            c.assertValueEqual(restored.getInventory().getItem(8).getCount(),9,"Native inventory survives actual file reload");
            c.assertTrue(restored.getItemBySlot(EquipmentSlot.CHEST).is(Convergence.ITEMS.get("convergence:cuirass")),"Custom equipped chestplate survives reload");
            c.assertTrue(restored.getOffhandItem().is(Convergence.ITEMS.get("convergence:shield")),"Offhand survives reload");
            c.assertTrue(Arrays.equals(before,Files.readAllBytes(GameModes.savedPlayerFile(server,observer.getUUID()))),"Local action never rewrites an unrelated online player's save");
        } finally {server.getPlayerList().remove(player);server.getPlayerList().remove(observer);}
        c.succeed();
    }

    @GameTest public void slowerFinishKeepsNewCourseSelectionInNativePlayerFile(GameTestHelper c) throws Exception {
        var server=c.getLevel().getServer();var p=new ModeGameTests().player(c,"course-choice-save");
        try {
            GameModes.switchNow(p,GameModes.Mode.MINIGAMES,"parkour");
            var scores=GameModes.state(p).getCompoundOrEmpty("scores");scores.putLong("parkour",900);scores.putLong("redlight",500);GameModes.state(p).put("scores",scores);
            PlayerPersistence.save(p);
            ModeMaps.begin(p,"redlight");var run=ModeMaps.RUNS.get(p.getUUID());
            ModeMaps.finish(p,run,run.startedTick+60);
            var saved=NbtIo.readCompressed(GameModes.savedPlayerFile(server,p.getUUID()),NbtAccounter.create(64L*1024*1024));
            c.assertValueEqual(saved.getCompoundOrEmpty("InfinityModes").getStringOr("selected_minigames_map",""),"redlight","A different chosen course persists despite a slower finish");
            c.assertValueEqual(saved.getCompoundOrEmpty("InfinityModes").getCompoundOrEmpty("scores").getLongOr("redlight",0),500L,"The previous faster best is preserved");
        }finally{ModeMaps.RUNS.remove(p.getUUID());server.getPlayerList().remove(p);}
        c.succeed();
    }

    @GameTest public void courseFinishesSaveParticipantsAndSkipUnchangedBests(GameTestHelper c) throws Exception {
        var server=c.getLevel().getServer();
        var first=new ModeGameTests().player(c,"finish-first");
        var second=new ModeGameTests().player(c,"finish-second");
        var observer=new ModeGameTests().player(c,"finish-observer");
        try {
            GameModes.switchNow(first,GameModes.Mode.MINIGAMES,"redlight");
            GameModes.switchNow(second,GameModes.Mode.MINIGAMES,"redlight");
            PlayerPersistence.save(observer);
            var observerBefore=Files.readAllBytes(GameModes.savedPlayerFile(server,observer.getUUID()));
            GameModes.state(observer).putBoolean("unrelated_pending",true);
            var one=ModeMaps.RUNS.get(first.getUUID());var two=ModeMaps.RUNS.get(second.getUUID());
            ModeMaps.finish(first,one,one.startedTick+30);ModeMaps.finish(second,two,two.startedTick+32);
            for(var p:java.util.List.of(first,second)) {
                var disk=NbtIo.readCompressed(GameModes.savedPlayerFile(server,p.getUUID()),NbtAccounter.create(64L*1024*1024));
                c.assertValueEqual(disk.getCompoundOrEmpty("InfinityModes").getCompoundOrEmpty("scores").getLongOr("redlight",0),p==first?1500L:1600L,"Course best persists through native participant save");
            }
            c.assertTrue(Arrays.equals(observerBefore,Files.readAllBytes(GameModes.savedPlayerFile(server,observer.getUUID()))),"Two completions leave the observer's file unchanged");
            var bestFile=Files.readAllBytes(GameModes.savedPlayerFile(server,first.getUUID()));
            GameModes.state(first).putBoolean("pending_after_best",true);
            ModeMaps.begin(first,"redlight");var slower=ModeMaps.RUNS.get(first.getUUID());
            ModeMaps.finish(first,slower,slower.startedTick+60);
            c.assertTrue(Arrays.equals(bestFile,Files.readAllBytes(GameModes.savedPlayerFile(server,first.getUUID()))),"Slower finish does not save unchanged persistent progress again");
            c.assertValueEqual(GameModes.state(first).getCompoundOrEmpty("scores").getLongOr("redlight",0),1500L,"Original best is unchanged");
        } finally {
            for(var p:java.util.List.of(first,second,observer)){ModeMaps.RUNS.remove(p.getUUID());server.getPlayerList().remove(p);}
        }
        c.succeed();
    }
}

package dev.convergence;

import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Real native menus, containers, redstone, saved player state and encoded Bedrock callbacks. */
public class CommonsGameTests {
    ServerPlayer survival(GameTestHelper c,String name){var p=new ModeGameTests().player(c,name);p.closeContainer();p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();return p;}
    void cleanup(ServerPlayer p){CommonsEvents.stopTrial(p);CommonsMenu.ACTIVE.remove(p.getUUID());CommonsMenu.DELIVERIES.remove(p.getUUID());p.closeContainer();p.level().getServer().getPlayerList().remove(p);}
    void reply(org.geysermc.cumulus.form.Form form,String text){try{org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,text);}catch(Exception e){throw new RuntimeException(e);}}
    ServerPlayer reloadDisk(ServerPlayer p){try{
        var saved=net.minecraft.nbt.NbtIo.readCompressed(GameModes.savedPlayerFile(p.level().getServer(),p.getUUID()),net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));
        var fresh=new ServerPlayer(p.level().getServer(),p.level(),p.getGameProfile(),p.clientInformation());
        fresh.load(TagValueInput.create(ProblemReporter.DISCARDING,p.registryAccess(),saved));return fresh;
    }catch(Exception e){throw new RuntimeException(e);}}

    @GameTest public void infinityMenuOpensBothUpdatesAndPermanentAtlas(GameTestHelper c){
        var p=survival(c,"commons-navigation");try{
            ServerMenu.open(p);p.containerMenu.clicked(ServerMenu.COMMONS,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.containerMenu instanceof CommonsMenu.Handler,"Infinity Menu opens real Commons");
            var home=(CommonsMenu.Handler)p.containerMenu;home.clicked(10,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(((CommonsMenu.Handler)p.containerMenu).view.title(),"Infinity Builder Exchange","Builder menu is reachable");
            p.closeContainer();ServerMenu.open(p);p.containerMenu.clicked(ServerMenu.PLAYER_WORKSHOP,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(((CommonsMenu.Handler)p.containerMenu).view.title(),"Expedition Atlas","Permanent features have a direct entry");
            p.closeContainer();CommonsMenu.open(p,CommonsMenu.Page.HARVEST);
            c.assertTrue(((CommonsMenu.Handler)p.containerMenu).view.content().contains("village"),"Shared pantry counts are visible");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void exactDeliveryPreservesNamedFoodAndCannotReplay(GameTestHelper c){
        var p=survival(c,"pantry-plain");try{
            var named=new ItemStack(Items.WHEAT,9);named.set(DataComponents.CUSTOM_NAME,Component.literal("My saved seed collection"));
            p.getInventory().setItem(0,named);p.getInventory().setItem(1,new ItemStack(Items.WHEAT,40));
            var q=CommonsEvents.quote(p,0);c.assertValueEqual(q.amount(),32,"Delivery is capped to the personal category quota");
            c.assertValueEqual(CommonsEvents.donate(p,q),32,"Exact reviewed amount is consumed");
            c.assertValueEqual(p.getInventory().getItem(1).getCount(),8,"Only the offered amount is taken");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),9,"Named food is preserved");
            c.assertValueEqual(CommonsEvents.donate(p,q),0,"Replayed approval cannot consume or credit again");
            c.assertValueEqual(CommonsEvents.donated(p,0),32,"Personal receipt is credited once");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void changedInventoryOrCreativePermissionRefusesDelivery(GameTestHelper c){
        var p=survival(c,"pantry-stale");try{
            p.getInventory().setItem(0,new ItemStack(Items.CARROT,16));var q=CommonsEvents.quote(p,1);
            p.getInventory().setItem(5,new ItemStack(Items.DIAMOND));
            c.assertValueEqual(CommonsEvents.donate(p,q),0,"Even an unrelated changed main slot invalidates the review");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),16,"Refusal leaves food intact");
            q=CommonsEvents.quote(p,1);p.setGameMode(GameType.CREATIVE);
            c.assertValueEqual(CommonsEvents.donate(p,q),0,"Creative cannot redeem a stale Survival review");
            c.assertTrue(CommonsEvents.quote(p,1)==null,"Creative cannot create a donation quote");
            c.assertValueEqual(CommonsEvents.contribution(p),0,"Rejected actions do not change progress");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void twoPlayersSharePantryAndSavedIndexIsIdempotent(GameTestHelper c){
        var p=survival(c,"pantry-a");var other=survival(c,"pantry-b");try{
            var pantry=new CommonsEvents.Pantry();
            p.getInventory().setItem(0,new ItemStack(Items.WHEAT,12));other.getInventory().setItem(0,new ItemStack(Items.WHEAT,20));
            CommonsEvents.donate(p,CommonsEvents.quote(p,0));CommonsEvents.donate(other,CommonsEvents.quote(other,0));
            pantry.sync(p);pantry.sync(p);pantry.sync(other);
            c.assertValueEqual(pantry.total(0),32,"Two unique contributors fill one shared category without double counting");
            var encoded=CommonsEvents.Pantry.TYPE.codec().encodeStart(NbtOps.INSTANCE,pantry).getOrThrow();
            var loaded=CommonsEvents.Pantry.TYPE.codec().parse(NbtOps.INSTANCE,encoded).getOrThrow();
            loaded.sync(p);loaded.sync(other);c.assertValueEqual(loaded.total(0),32,"Saved global index reload and replay are idempotent");
            c.assertValueEqual(CommonsEvents.donated(p,0),12,"Players retain their own distinct receipts");
        }finally{cleanup(p);cleanup(other);}c.succeed();
    }
    @GameTest public void donationAndOneTimeGiftPersistTogetherInNativePlayerSave(GameTestHelper c){
        var p=survival(c,"pantry-save");try{
            p.getInventory().setItem(0,new ItemStack(Items.CARROT,16));CommonsEvents.donate(p,CommonsEvents.quote(p,1));
            c.assertValueEqual(CommonsEvents.claim(p,1),3,"Field kit delivers hoe, meal and usable jar");
            var fresh=reloadDisk(p);
            c.assertValueEqual(CommonsEvents.donated(fresh,1),16,"Donation receipt survives native serialization");
            c.assertTrue(CommonsEvents.state(fresh).getBooleanOr("gift_1",false),"One-time claim flag survives alongside inventory");
            c.assertTrue(java.util.stream.IntStream.range(0,36).anyMatch(i->CommonsEvents.seedlight(fresh,fresh.getInventory().getItem(i))),"Real owned Seedlight Jar survives native reload");
            c.assertValueEqual(CommonsEvents.claim(p,1),0,"Repeated gift claim cannot duplicate items");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void fullInventoryRefusesWholeRewardAndPreservesProgress(GameTestHelper c){
        var p=survival(c,"pantry-full");try{
            p.getInventory().setItem(0,new ItemStack(Items.WHEAT,16));CommonsEvents.donate(p,CommonsEvents.quote(p,0));
            for(int i=0;i<36;i++)p.getInventory().setItem(i,new ItemStack(Items.DIRT,64));p.getInventory().setItem(0,ItemStack.EMPTY);p.getInventory().setItem(1,ItemStack.EMPTY);
            c.assertValueEqual(CommonsEvents.claim(p,1),0,"Two free slots refuse all three gifts atomically");
            c.assertTrue(p.getInventory().getItem(0).isEmpty(),"No partial item delivery");
            c.assertFalse(CommonsEvents.state(p).getBooleanOr("gift_1",false),"Refusal leaves gift unclaimed");
            p.getInventory().setItem(2,ItemStack.EMPTY);c.assertValueEqual(CommonsEvents.claim(p,1),3,"Space permits one complete delivery");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void bedrockConfirmRejectsOldFormsForeignSessionsAndChangedInventory(GameTestHelper c){
        var p=survival(c,"pantry-form");var other=survival(c,"pantry-foreign");try{
            p.getInventory().setItem(0,new ItemStack(Items.WHEAT,32));var q=CommonsEvents.quote(p,0);var forms=new ArrayList<org.geysermc.cumulus.form.Form>();
            java.util.function.Predicate<org.geysermc.cumulus.form.Form> sender=f->{forms.add(f);return true;};
            CommonsMenu.form(p,CommonsMenu.Page.DELIVERY,q,sender);var stale=forms.getLast();
            CommonsMenu.form(p,CommonsMenu.Page.DELIVERY,q,sender);reply(stale,"0");
            c.assertValueEqual(CommonsEvents.contribution(p),0,"Old native form cannot approve a newer review");
            var latest=forms.getLast();reply(latest,"0");c.assertValueEqual(CommonsEvents.contribution(p),32,"Current encoded native confirmation performs actual delivery");
            p.closeContainer();reply(latest,"0");c.assertValueEqual(CommonsEvents.contribution(p),32,"Native callback replay is ignored");
            c.assertValueEqual(CommonsEvents.donate(other,q),0,"Another player cannot use the quote");
        }finally{cleanup(p);cleanup(other);}c.succeed();
    }
    @GameTest public void menuIconsCannotBeTakenAndStaleJavaReviewCannotAct(GameTestHelper c){
        var p=survival(c,"commons-icons");var other=survival(c,"commons-intruder");try{
            CommonsMenu.open(p);var old=p.containerMenu;old.clicked(10,0,ContainerInput.PICKUP,other);
            c.assertTrue(p.containerMenu==old,"Foreign clicks do not close or mutate the menu");
            old.clicked(10,0,ContainerInput.THROW,p);c.assertTrue(old.getCarried().isEmpty(),"No control icons can be collected");
            p.closeContainer();ServerMenu.open(p);var current=p.containerMenu;old.clicked(10,0,ContainerInput.PICKUP,p);
            c.assertTrue(p.containerMenu==current,"Stale Java screen cannot act through a replacement");
        }finally{cleanup(p);cleanup(other);}c.succeed();
    }
    @GameTest(maxTicks=80) public void workshopRequiresActualPoweredDustAndNativeHopperTransfer(GameTestHelper c){
        var fixtures=new CreativeStudioGameTests();var p=fixtures.player(c,"exchange-live");var a=p.blockPosition().offset(0,0,3);
        CommonsEvents.setWorkshop(p,a);var w=p.level();
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)w.setBlock(a.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
        w.setBlock(a.offset(-3,0,0),Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
        var wire=Blocks.REDSTONE_WIRE.defaultBlockState().setValue(RedstoneWireBlock.EAST,net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE).setValue(RedstoneWireBlock.WEST,net.minecraft.world.level.block.state.properties.RedstoneSide.SIDE);
        w.setBlock(a.offset(-2,0,0),wire,3);w.setBlock(a.offset(-1,0,0),wire,3);
        // Native placement computes the lamp's initial LIT value from its real neighbors.
        // Merely injecting its unlit default state is not the player's placement path.
        var context=new net.minecraft.world.item.context.BlockPlaceContext(p,net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Items.REDSTONE_LAMP),new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(a.below()),net.minecraft.core.Direction.UP,a.below(),false));
        w.setBlock(a,Blocks.REDSTONE_LAMP.getStateForPlacement(context),3);w.setBlock(a.offset(3,0,0),Blocks.BARREL.defaultBlockState(),3);w.setBlock(a.offset(3,1,0),Blocks.HOPPER.defaultBlockState(),3);
        c.runAfterDelay(5,()->{
            // A navigator selected by the synthetic login can open its screen on
            // the first normal tick. Real menu actions close that screen first.
            p.closeContainer();
            c.assertValueEqual(CommonsEvents.inspectSignal(p),1,"Real native powered dust lights the actual lamp; workshop="+CommonsEvents.workshopAllowed(p)+", dust="+w.getBlockState(a.offset(-1,0,0))+", lamp="+w.getBlockState(a));
            w.setBlock(a.offset(4,1,0),Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
            c.assertValueEqual(CommonsEvents.startTrial(p),0,"A natively powered/disabled hopper refuses the trial");
            w.removeBlock(a.offset(4,1,0),false);
            c.assertValueEqual(CommonsEvents.startTrial(p),1,"Empty native containers accept a sealed test parcel");
            c.assertFalse(CommonsEvents.state(p).getBooleanOr("delivery",false),"Starting a test does not grant completion");
        });
        c.runAfterDelay(30,()->{try{
            p.closeContainer();
            c.assertTrue(CommonsEvents.state(p).getBooleanOr("delivery",false),"Normal server hopper ticks complete delivery");
            c.assertFalse(CommonsEvents.TRIALS.containsKey(p.getUUID()),"Finished trial releases its session");
            c.assertTrue(CommonsEvents.container(w,a.offset(3,0,0)).isEmpty(),"Only the owned test parcel is cleaned");
            // Normal Creative startup can supply a full toolbox during these
            // thirty ticks. Deliberately free the documented three gift slots.
            for(int i=0;i<3;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
            c.assertValueEqual(CommonsEvents.claim(p,0),3,"A real completed prototype grants the Builder reward");
            c.assertTrue(p.getTabListDisplayName().getString().contains("DevDay Builder"),"Earned personal title reaches native player-list display");
            c.assertValueEqual(CommonsEvents.claim(p,0),0,"Builder gift cannot duplicate");c.succeed();
        }finally{CommonsEvents.stopTrial(p);fixtures.cleanup(p);}});
    }
    @GameTest public void darkOrDisabledWorkshopCannotGrantProgress(GameTestHelper c){
        var fixtures=new CreativeStudioGameTests();var p=fixtures.player(c,"exchange-negative");try{
            CommonsEvents.setWorkshop(p,p.blockPosition());
            c.assertValueEqual(CommonsEvents.inspectSignal(p),0,"An absent circuit cannot pass by pressing a button");
            c.assertValueEqual(CommonsEvents.startTrial(p),0,"An absent or disabled delivery line cannot pass");
            c.assertFalse(CommonsEvents.state(p).getBooleanOr("delivery",false),"Rejected trial cannot unlock gifts");
            p.setGameMode(GameType.SURVIVAL);c.assertValueEqual(CommonsEvents.kit(p),0,"Survival cannot obtain free Creative materials");
        }finally{fixtures.cleanup(p);}c.succeed();
    }
    @GameTest public void newBlueprintsAreOriginalBoundedAndUseReviewedPlacement(GameTestHelper c){
        var fixtures=new CreativeStudioGameTests();var p=fixtures.player(c,"commons-blueprint");try{
            var at=p.blockPosition().offset(0,0,5);var blocks=CommonsEvents.blueprint(at,false);
            c.assertTrue(blocks.size()<160,"Campus is a small bounded edit");
            c.assertTrue(blocks.values().stream().anyMatch(s->s.is(((net.minecraft.world.item.BlockItem)Convergence.ITEMS.get("convergence:copper_circuit")).getBlock())),"Uses original Infinity circuit artwork");
            var preview=new CreativeStudio.Preview(p,p.connection,p.level(),at,blocks,p.level().getServer().getTickCount()+600);CreativeStudio.PREVIEWS.put(p.getUUID(),preview);
            c.assertValueEqual(CreativeStudio.confirm(p,preview),blocks.size(),"Actual reviewed native placement builds all blocks");
            c.assertValueEqual(CommonsEvents.anchor(p),at.above(),"Completed pavilion registers its real workshop origin");
            c.assertValueEqual(CreativeStudio.undo(p),blocks.size(),"Original Studio undo restores the footprint");
            c.assertValueEqual(CommonsEvents.blueprint(at,true).values().stream().filter(s->s.is(Blocks.FARMLAND)).count(),4L,"Garden has four real crop plots");
        }finally{fixtures.cleanup(p);}c.succeed();
    }
    @GameTest public void atlasRecordsActualBiomeOnceAndCreativeCannotSpoofIt(GameTestHelper c){
        var p=survival(c,"atlas-stamp");try{
            c.assertValueEqual(PlayerWorkshop.stamp(p),1,"Actual current biome grants a saved stamp");
            c.assertValueEqual(PlayerWorkshop.stamp(p),0,"Repeated stamp is idempotent");
            c.assertValueEqual(PlayerWorkshop.stamps(p),1,"One biome records one family");
            c.assertValueEqual(PlayerWorkshop.stamps(reloadDisk(p)),1,"Actual native player file retains the Atlas discovery");
            p.setGameMode(GameType.CREATIVE);c.assertValueEqual(PlayerWorkshop.stamp(p),0,"Creative travel cannot stamp Survival progression");
            for(var entry:Map.of("minecraft:plains",0,"minecraft:cherry_grove",1,"minecraft:desert",2,"minecraft:frozen_ocean",4,"minecraft:lush_caves",5,"minecraft:warped_forest",6,"minecraft:end_highlands",7).entrySet())c.assertValueEqual(PlayerWorkshop.category(entry.getKey()),entry.getValue(),"Explicit biome classification: "+entry.getKey());
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void organizerPreservesExactComponentsHotbarAndNestedContents(GameTestHelper c){
        var p=survival(c,"organizer-save");try{
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.getInventory().setItem(9,new ItemStack(Items.STONE,40));p.getInventory().setItem(10,new ItemStack(Items.STONE,40));
            var named=new ItemStack(Items.STONE,5);named.set(DataComponents.CUSTOM_NAME,Component.literal("Memory Stone"));p.getInventory().setItem(11,named);
            var box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD,13))));p.getInventory().setItem(12,box);
            var review=PlayerWorkshop.review(p);c.assertValueEqual(PlayerWorkshop.apply(p,review),1,"Reviewed organizer applies");
            var loaded=reloadDisk(p);
            for(int i=0;i<36;i++){var a=p.getInventory().getItem(i);var b=loaded.getInventory().getItem(i);c.assertTrue(a.getCount()==b.getCount()&&ItemStack.isSameItemSameComponents(a,b),"Every native saved slot retains exact count and components");}
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Hotbar is untouched");
            int ordinary=0,special=0,boxes=0;for(int i=9;i<36;i++){var stack=p.getInventory().getItem(i);if(stack.is(Items.STONE)){if(stack.has(DataComponents.CUSTOM_NAME))special+=stack.getCount();else ordinary+=stack.getCount();}if(stack.is(Items.SHULKER_BOX)){boxes++;c.assertValueEqual(stack.get(DataComponents.CONTAINER).itemCopies().findFirst().orElseThrow().getCount(),13,"Nested contents stay intact");}}
            c.assertValueEqual(ordinary,80,"Ordinary items are conserved when stacks merge");c.assertValueEqual(special,5,"Different names are not merged away");c.assertValueEqual(boxes,1,"Storage item is not duplicated");
            c.assertValueEqual(PlayerWorkshop.apply(p,review),0,"Changed source ordering refuses a stale replay");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void organizerRefusesChangedSlotsAndSeedlightOwnershipIsReal(GameTestHelper c){
        var p=survival(c,"organizer-stale");var other=survival(c,"jar-other");try{
            p.getInventory().setItem(9,new ItemStack(Items.DIRT));var review=PlayerWorkshop.review(p);p.getInventory().setItem(9,new ItemStack(Items.GOLD_INGOT,2));
            c.assertValueEqual(PlayerWorkshop.apply(p,review),0,"Changed inventory cancels organizer");
            c.assertValueEqual(p.getInventory().getItem(9).getCount(),2,"Refusal cannot erase changed items");
            CommonsEvents.state(p).putBoolean("gift_1",true);var jar=CommonsEvents.jar(p);
            c.assertValueEqual(CommonsEvents.releaseSeedlight(p,jar),1,"Earned jar runs the actual particle effect");
            c.assertFalse(CommonsEvents.seedlight(other,jar),"Another account cannot activate the personal jar");
            c.assertFalse(CommonsEvents.seedlight(p,new ItemStack(Items.GLASS_BOTTLE)),"Ordinary glass bottles retain ordinary use");
        }finally{cleanup(p);cleanup(other);}c.succeed();
    }
    @GameTest public void organizerPreviewIsUntakeableAndNeedsSeparateConfirm(GameTestHelper c){
        var p=survival(c,"pack-preview");try{
            p.getInventory().setItem(9,new ItemStack(Items.STONE,40));p.getInventory().setItem(10,new ItemStack(Items.STONE,40));
            CommonsMenu.open(p,CommonsMenu.Page.PACK);var menu=(CommonsMenu.Handler)p.containerMenu;
            c.assertValueEqual(menu.getSlot(9).getItem().getCount(),64,"Actual preview shows merged item arrangement");
            menu.clicked(9,0,ContainerInput.PICKUP,p);menu.clicked(9,0,ContainerInput.QUICK_MOVE,p);
            c.assertTrue(menu.getCarried().isEmpty(),"Preview stack cannot be taken");
            c.assertValueEqual(p.getInventory().getItem(9).getCount(),40,"Clicking preview does not confirm or mutate inventory");
            menu.clicked(45,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(p.getInventory().getItem(9).getCount(),64,"Only explicit confirm applies preview");
            c.assertValueEqual(p.getInventory().getItem(10).getCount(),16,"All remaining items are conserved");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void atlasRewardNeedsAllStampsAndCannotGrantAnotherRank(GameTestHelper c){
        var p=survival(c,"atlas-reward");try{
            c.assertValueEqual(CommonsEvents.claim(p,3),0,"Incomplete Atlas cannot claim");
            CommonsEvents.state(p).putInt("atlas",255);c.assertValueEqual(CommonsEvents.claim(p,3),3,"Complete persisted Atlas unlocks all three native rewards");
            c.assertValueEqual(CommonsEvents.title(p),"Wayfinder","Wayfinder is a real earned personal display title");
            c.assertValueEqual(CommonsEvents.claim(p,3),0,"Atlas gift cannot be claimed twice");
            CommonsEvents.title(p,0);c.assertTrue(CommonsEvents.title(p).isEmpty(),"Hide title removes the cosmetic");
            CommonsEvents.title(p,2);c.assertTrue(CommonsEvents.title(p).isEmpty(),"Unowned Guardian title cannot be selected");
            c.assertFalse(Memberships.operator(p),"Cosmetic rewards never grant OP");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void failedFormDeliveryAndExpiredReviewLeaveFoodUntouched(GameTestHelper c){
        var p=survival(c,"form-expiry");try{
            p.getInventory().setItem(0,new ItemStack(Items.WHEAT,32));var q=CommonsEvents.quote(p,0);
            c.assertValueEqual(CommonsMenu.form(p,CommonsMenu.Page.DELIVERY,q,f->false),0,"Failed native form send reports failure");
            c.assertFalse(CommonsMenu.ACTIVE.containsKey(p.getUUID()),"Failed send releases its session");
            var expired=new CommonsMenu.Ticket(UUID.randomUUID(),p,p.connection,p.level(),GameModes.current(p),p.level().getServer().getTickCount());
            CommonsMenu.ACTIVE.put(p.getUUID(),expired);CommonsMenu.response(expired,()->CommonsEvents.donate(p,q));
            c.assertValueEqual(CommonsEvents.contribution(p),0,"Expired form cannot donate");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),32,"Failure and expiry preserve every offered item");
        }finally{cleanup(p);}c.succeed();
    }
    @GameTest public void earnedTitleRefreshesTeamPrefixWithoutChangingCombatTeam(GameTestHelper c){
        var p=survival(c,"title-team");try{
            var board=p.level().getServer().getScoreboard();var team=board.addPlayerTeam("commons_"+p.getStringUUID().substring(0,6));
            team.setPlayerPrefix(Component.literal("[BLUE] "));board.addPlayerToTeam(p.getScoreboardName(),team);
            CommonsEvents.state(p).putBoolean("gift_0",true);CommonsEvents.title(p,1);
            c.assertTrue(CommonsEvents.TAB_NAMES.get(p).contains("[BLUE]"),"First native display update includes current prefix");
            team.setPlayerPrefix(Component.literal("[GOLD] "));Memberships.get(p.level().getServer()).badge(p);
            c.assertTrue(CommonsEvents.TAB_NAMES.get(p).contains("[GOLD]"),"Badge refresh updates the actual display packet value after prefix changes");
            c.assertTrue(board.getPlayersTeam(p.getScoreboardName())==team,"Personal title never replaces an external combat team");
        }finally{cleanup(p);}c.succeed();
    }
}

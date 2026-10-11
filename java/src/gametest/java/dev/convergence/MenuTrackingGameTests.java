package dev.convergence;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;

/** Native behavior and operation counts for menu controls and RAM-only helper history. */
public class MenuTrackingGameTests {
    private ServerPlayer player(GameTestHelper c,String name) {
        var player=new ModeGameTests().player(c,name);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }
    private void op(ServerPlayer player) {
        player.level().getServer().getPlayerList().op(new NameAndId(player.getGameProfile()),Optional.of(LevelBasedPermissionSet.OWNER),Optional.of(false));
    }
    private void cleanup(ServerPlayer player) {
        MenuTrackingPerformanceProbe.endPicker(player);
        GameModes.PENDING.remove(player.getUUID());
        GameModes.TRANSITIONS.remove(player.getUUID());
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        OperatorGameTests.deop(player);
        player.level().getServer().getPlayerList().remove(player);
    }
    private AgentCompanions.Agent agent(ServerPlayer owner,String name) {
        return new AgentCompanions.Agent(owner.getStringUUID(),name,AgentCompanions.Mode.STAY,AgentCompanions.Profile.API,
            owner.level().dimension().identifier().toString(),owner.getX(),owner.getY(),owner.getZ());
    }

    /** Observe the map consumed by the real tracking method; do not replace its validation logic. */
    static final class ObservedRoster extends TreeMap<String,AgentCompanions.Agent> {
        int lookups,entryVisits;
        @Override public AgentCompanions.Agent get(Object key) { lookups++;return super.get(key); }
        @Override public Set<Map.Entry<String,AgentCompanions.Agent>> entrySet() {
            var entries=super.entrySet();
            return new AbstractSet<>() {
                @Override public int size() { return entries.size(); }
                @Override public Iterator<Map.Entry<String,AgentCompanions.Agent>> iterator() {
                    var iterator=entries.iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return iterator.hasNext(); }
                        @Override public Map.Entry<String,AgentCompanions.Agent> next() { entryVisits++;return iterator.next(); }
                        @Override public void remove() { iterator.remove(); }
                    };
                }
            };
        }
        void resetCounts() { lookups=0;entryVisits=0; }
    }

    @GameTest public void allTrackedHelpersUseDirectIdentityLookupsOnEveryCleanupTick(GameTestHelper c) {
        var server=c.getLevel().getServer();var companions=AgentCompanions.get(server);
        var originalRoster=companions.data.agents;var originalTracking=AgentChat.TRACKING.remove(server);
        var owners=new ArrayList<ServerPlayer>();var roster=new ObservedRoster();
        try {
            companions.data.agents=roster;
            for(int index=0;index<4;index++) {
                var owner=player(c,"track-count-"+index);owners.add(owner);op(owner);
                for(int helper=0;helper<AgentCompanions.LIMIT;helper++)roster.put(UUID.randomUUID().toString(),agent(owner,"helper"+helper));
            }
            AgentChat.track(server,100);
            c.assertValueEqual(AgentChat.TRACKING.get(server).size(),AgentCompanions.GLOBAL_LIMIT,"All four six-helper API rosters are tracked");
            roster.resetCounts();AgentChat.track(server,101);
            c.assertValueEqual(roster.lookups,AgentCompanions.GLOBAL_LIMIT,"A non-sampling native tracking tick performs one direct lookup per helper");
            c.assertValueEqual(roster.entryVisits,0,"Identity cleanup never rescans the saved roster");
            for(var tracker:AgentChat.TRACKING.get(server).values())c.assertValueEqual(tracker.samples.size(),1,"Cleanup does not create extra observations");
        } finally {
            companions.data.agents=originalRoster;
            if(originalTracking==null)AgentChat.TRACKING.remove(server);else AgentChat.TRACKING.put(server,originalTracking);
            for(var owner:owners)cleanup(owner);
        }
        c.succeed();
    }

    @GameTest public void trackingIdentityRejectsReplacementNamesOwnersAndPermissionLoss(GameTestHelper c) {
        var owner=player(c,"track-identity");var other=player(c,"track-other");
        var companions=AgentCompanions.get(c.getLevel().getServer());var originalRoster=companions.data.agents;
        try {
            op(owner);op(other);companions.data.agents=new TreeMap<>();
            String id=UUID.randomUUID().toString();var record=agent(owner,"guide");
            companions.data.agents.put(id,record);
            companions.data.agents.put(UUID.randomUUID().toString(),agent(other,"guide"));
            var token=new AgentChat.Identity(id,record);
            c.assertTrue(token.current(companions,owner,"guide"),"The current exact record is valid");
            c.assertFalse(token.current(companions,other,"guide"),"Another OP4 with the same helper name cannot claim this identity");
            c.assertFalse(token.current(companions,owner,"different"),"A different requested helper name is rejected");
            companions.data.agents.put(id,agent(owner,"guide"));
            c.assertFalse(token.current(companions,owner,"guide"),"Equal record fields do not make a replacement object current");
            companions.data.agents.put(id,record.profile(AgentCompanions.Profile.REGULAR).profile(AgentCompanions.Profile.API));
            c.assertFalse(token.current(companions,owner,"guide"),"Changing away and back to API invalidates the old record");
            String recreatedId=UUID.randomUUID().toString();
            companions.data.agents.remove(id);companions.data.agents.put(recreatedId,agent(owner,"guide"));
            c.assertFalse(token.current(companions,owner,"guide"),"Dismissal and recreation under another saved ID cannot reuse a token");
            c.assertFalse(new AgentChat.Identity(id,null).current(companions,owner,"guide"),"An empty token safely refuses an absent record");
            companions.data.agents.remove(recreatedId);companions.data.agents.put(id,record);OperatorGameTests.deop(owner);
            c.assertFalse(token.current(companions,owner,"guide"),"Current native OP4 permission is still required");
        } finally { companions.data.agents=originalRoster;cleanup(owner);cleanup(other); }
        c.succeed();
    }

    private ItemStack navigator(ServerPlayer owner,Consumer<CompoundTag> marker) {
        var stack=new ItemStack(Items.RECOVERY_COMPASS);var tag=new CompoundTag();
        tag.putString("owner",owner.getStringUUID());marker.accept(tag);
        var extra=new CompoundTag();extra.putString("unrelated","Keep native custom data untouched");tag.put("extra",extra);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    private boolean owned(ItemStack stack,ServerPlayer owner) {
        try {
            var method=ServerMenu.class.getDeclaredMethod("owned",ItemStack.class,ServerPlayer.class);
            method.setAccessible(true);
            return (boolean)method.invoke(null,stack,owner);
        } catch(ReflectiveOperationException failure) {
            throw new AssertionError("Cannot invoke the native private ownership boundary",failure);
        }
    }
    private void checkOwnership(GameTestHelper c,ServerPlayer owner,ItemStack stack,boolean expected,String reason) {
        var data=stack.get(DataComponents.CUSTOM_DATA);var before=data.copyTag();
        var sample=MenuTrackingPerformanceProbe.beginTag(data);
        try {
            c.assertValueEqual(owned(stack,owner),expected,reason);
            c.assertValueEqual(sample.copies,stack.is(Items.RECOVERY_COMPASS)?1:0,"Ownership uses one native tag copy after the item gate");
        } finally { MenuTrackingPerformanceProbe.endTag(data); }
        c.assertTrue(data.copyTag().equals(before),"Ownership reads preserve every custom-data field");
    }
    @GameTest public void navigatorOwnershipCopiesOnceAndKeepsNativeNumericMarkerRules(GameTestHelper c) {
        var owner=player(c,"menu-copy");
        try {
            List<Consumer<CompoundTag>> markers=List.of(
                tag->tag.putByte("infinity_server_menu",(byte)1),tag->tag.putShort("infinity_server_menu",(short)1),
                tag->tag.putInt("infinity_server_menu",1),tag->tag.putLong("infinity_server_menu",1),
                tag->tag.putFloat("infinity_server_menu",1.5F),tag->tag.putDouble("infinity_server_menu",1.75));
            for(var marker:markers)checkOwnership(c,owner,navigator(owner,marker),true,"Every native numeric marker whose int value is one remains accepted");
            checkOwnership(c,owner,navigator(owner,tag->tag.putInt("infinity_server_menu",0)),false,"Zero marker is rejected");
            checkOwnership(c,owner,navigator(owner,tag->tag.putString("infinity_server_menu","1")),false,"A string is not a numeric marker");
            checkOwnership(c,owner,navigator(owner,tag->{tag.putInt("infinity_server_menu",1);tag.putString("owner",UUID.randomUUID().toString());}),false,"A different valid owner UUID is rejected");
            checkOwnership(c,owner,navigator(owner,tag->{tag.putInt("infinity_server_menu",1);tag.putString("owner","invalid");}),false,"Malformed owner UUID is rejected");
            var ordinary=new ItemStack(Items.COMPASS);ordinary.set(DataComponents.CUSTOM_DATA,navigator(owner,tag->tag.putInt("infinity_server_menu",1)).get(DataComponents.CUSTOM_DATA));
            checkOwnership(c,owner,ordinary,false,"A normal compass cannot impersonate a navigator");
            c.assertFalse(owned(new ItemStack(Items.RECOVERY_COMPASS),owner),"Missing custom data is safely rejected");
        } finally { cleanup(owner); }
        c.succeed();
    }

    private void scanCount(GameTestHelper c,MenuTrackingPerformanceProbe.PickerSample sample,int tick,int slots) {
        var work=sample.at(tick);
        c.assertValueEqual(work.scans,1,"The actual registered picker tick scans this player's inventory once");
        c.assertValueEqual(work.slotReads,slots,"The observed native slot reads stop at the first picker or inventory end");
    }
    @GameTest(maxTicks=25) public void pickerScansOnceAndRecoversActualDirectSlotReplacementsWithinOneTick(GameTestHelper c) {
        var player=new EquipmentGameTests().creative(c,"picker-scan");
        for(int slot=0;slot<36;slot++)player.getInventory().setItem(slot,new ItemStack(Items.DIRT,64));
        c.runAfterDelay(6,()->{
            try {
                player.closeContainer();var sample=MenuTrackingPerformanceProbe.beginPicker(player);
                int fullTick=player.level().getServer().getTickCount();
                c.runAfterDelay(2,()->{
                    try {
                        scanCount(c,sample,fullTick+1,36);
                        for(int slot=0;slot<36;slot++)c.assertTrue(player.getInventory().getItem(slot).is(Items.DIRT)&&player.getInventory().getItem(slot).getCount()==64,"A full inventory is untouched");
                        int revision=player.getInventory().getTimesChanged();player.getInventory().setItem(8,ItemStack.EMPTY);
                        c.assertValueEqual(player.getInventory().getTimesChanged(),revision,"The real direct slot mutation does not change the native revision counter");
                        int recoveryTick=player.level().getServer().getTickCount();
                        c.runAfterDelay(1,()->{
                            try {
                                c.assertTrue(CreativeGearPicker.isPicker(player.getInventory().getItem(8)),"A freed slot receives the picker in the first registered tick");
                                scanCount(c,sample,recoveryTick,36);
                                player.getInventory().setItem(8,new ItemStack(Items.DIRT,64));player.getInventory().setItem(5,ItemStack.EMPTY);
                                int replacementTick=player.level().getServer().getTickCount();
                                c.runAfterDelay(1,()->{
                                    try {
                                        c.assertTrue(CreativeGearPicker.isPicker(player.getInventory().getItem(5)),"Replacing the picker and freeing another slot also recovers in one tick");
                                        scanCount(c,sample,replacementTick,36);player.getInventory().setSelectedSlot(5);
                                        c.runAfterDelay(1,()->{
                                            try {
                                                c.assertTrue(player.containerMenu instanceof CreativeGearPicker.PickerHandler,"Selecting the recovered native compass still opens its real menu");
                                                scanCount(c,sample,player.level().getServer().getTickCount()-1,6);
                                                c.succeed();
                                            } finally { cleanup(player); }
                                        });
                                    } catch(Throwable failure) { cleanup(player);throw failure; }
                                });
                            } catch(Throwable failure) { cleanup(player);throw failure; }
                        });
                    } catch(Throwable failure) { cleanup(player);throw failure; }
                });
            } catch(Throwable failure) { cleanup(player);throw failure; }
        });
    }
}

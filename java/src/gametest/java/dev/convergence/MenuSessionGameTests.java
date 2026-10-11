package dev.convergence;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;

/** Real saved reconnects; delayed registered callbacks must not retire fresh controls. */
public class MenuSessionGameTests {
    private static Object field(Class<?> type,String name) {
        try {var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
        catch(ReflectiveOperationException failure) {throw new IllegalStateException("Pinned menu fixture field unavailable: "+name,failure);}
    }
    @SuppressWarnings("unchecked")
    private static Map<UUID,Object> map(Class<?> type,String name) {return (Map<UUID,Object>)field(type,name);}
    @SuppressWarnings("unchecked")
    private static Set<UUID> set(Class<?> type,String name) {return (Set<UUID>)field(type,name);}
    private static void click(ServerPlayer player,int slot) {player.containerMenu.clicked(slot,0,ContainerInput.PICKUP,player);}
    private static void reply(Form form,String payload) {
        try {org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,payload);}
        catch(Exception failure) {throw new IllegalStateException("Native form fixture reply failed",failure);}
    }

    private static final class PrivateScenario extends NativeSessionFixture {
        Joined old,current;
        ServerGamePacketListenerImpl previousConnection;
        final ArrayList<Form> oldForms=new ArrayList<>(),forms=new ArrayList<>();
        PrivateScenario(GameTestHelper c) {super(c);}
        void exact(ServerPlayer player,String id) {
            player.closeContainer();
            c.assertValueEqual(AdminStatsMenu.openStat(player,player,id),1,"The current OP opens the actual stat editor");
            click(player,AdminStatsMenu.EXACT);
            c.assertTrue(player.containerMenu==player.inventoryMenu,"Exact entry closes the actual chest before private input");
        }
        void staleDisconnect() {ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(previousConnection,server);}
        @Override void start() {
            old=join("menu-private",2);previousConnection=old.player.connection;
            OperatorGameTests.level(old.player,LevelBasedPermissionSet.OWNER);
            old.player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));
            exact(old.player,"max_health");
            c.assertValueEqual(BedrockStatsMenu.open(old.player,old.player,"attack_damage",form->{oldForms.add(form);return true;}),1,"The original session opens a real Cumulus input form");
            reconnect(old,replacement->{current=replacement;fresh();});
        }
        void fresh() {
            var p=current.player;var id=p.getUUID();
            c.assertTrue(p!=old.player && p.connection!=previousConnection,"Saved native login created a distinct player and listener");
            c.assertTrue(!map(AdminStatsMenu.class,"INPUTS").containsKey(id),"Actual original-session disconnect cleaned its number input");
            c.assertTrue(!map(BedrockStatsMenu.class,"ACTIVE").containsKey(id),"Actual original-session disconnect cleaned its form");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Native saved login retains the original items");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            exact(p,"max_health");
            var input=map(AdminStatsMenu.class,"INPUTS").get(id);
            c.assertTrue(input!=null,"The replacement owns a fresh private prompt");
            c.assertValueEqual(BedrockStatsMenu.open(p,p,"armor"),1,"Default native form sending stages an actual delayed delivery");
            var ticket=map(BedrockStatsMenu.class,"ACTIVE").get(id);var delivery=map(BedrockStatsMenu.class,"DELIVERIES").get(id);
            c.assertTrue(ticket!=null && delivery!=null,"Fresh form and delivery records exist before the stale callback");
            staleDisconnect();
            c.assertTrue(map(AdminStatsMenu.class,"INPUTS").get(id)==input,"Delayed original disconnect cannot delete the fresh private prompt");
            c.assertTrue(map(BedrockStatsMenu.class,"ACTIVE").get(id)==ticket,"Delayed original disconnect cannot delete the fresh native form");
            c.assertTrue(map(BedrockStatsMenu.class,"DELIVERIES").get(id)==delivery,"Delayed original disconnect cannot cancel the fresh form delivery");
            c.assertTrue(AdminStatsMenu.consumeChat(old.player,"4000"),"Delayed old actor text is consumed rather than public");
            c.assertTrue(map(AdminStatsMenu.class,"INPUTS").get(id)==input,"Old actor text cannot expire the replacement's prompt");
            c.assertValueEqual(BedrockStatsMenu.open(p,p,"attack_damage",form->{forms.add(form);return true;}),1,"The replacement opens a real independently captured form");
            var freshTicket=map(BedrockStatsMenu.class,"ACTIVE").get(id);
            reply(oldForms.getLast(),"[null,\"999\",0]");
            c.assertTrue(map(BedrockStatsMenu.class,"ACTIVE").get(id)==freshTicket,"Old form response cannot consume fresh consent");
            c.assertValueEqual(p.getAttributeBaseValue(Attributes.ATTACK_DAMAGE),1d,"Old form response cannot change the new player's stats");
            c.assertTrue(AdminStatsMenu.consumeChat(p,"5000"),"The replacement's intended exact number remains private");
            c.assertTrue(p.containerMenu instanceof AdminStatsMenu.Handler,"Private input opens its actual staged editor");
            c.assertValueEqual(((AdminStatsMenu.Handler)p.containerMenu).pending,5000d,"The replacement stages its own exact value");
            c.assertValueEqual(p.getMaxHealth(),20f,"Typing alone is not confirmation");
            click(p,AdminStatsMenu.REVIEW);click(p,AdminStatsMenu.CONFIRM);
            c.assertValueEqual(p.getMaxHealth(),5000f,"Current native chest review and confirmation still apply the edit");
            p.closeContainer();
            c.assertTrue(forms.getLast() instanceof CustomForm,"The captured native input form is still pending");
            reply(forms.getLast(),"[null,\"100\",0]");
            c.assertTrue(forms.getLast() instanceof ModalForm,"Current form response opens its separate native review");
            c.assertValueEqual(p.getAttributeBaseValue(Attributes.ATTACK_DAMAGE),1d,"Native form input also waits for confirmation");
            var review=map(BedrockStatsMenu.class,"ACTIVE").get(id);staleDisconnect();
            c.assertTrue(map(BedrockStatsMenu.class,"ACTIVE").get(id)==review,"Delayed old disconnect cannot erase current form confirmation");
            reply(forms.getLast(),"true");
            c.assertValueEqual(p.getAttributeBaseValue(Attributes.ATTACK_DAMAGE),100d,"Current native form confirmation applies exactly the reviewed edit");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"The entire reconnect/edit sequence preserves inventory");
            exact(p,"armor");BedrockStatsMenu.open(p,p,"armor");
            c.assertTrue(map(AdminStatsMenu.class,"INPUTS").containsKey(id),"Current-session number input exists before genuine disconnect");
            c.assertTrue(map(BedrockStatsMenu.class,"ACTIVE").containsKey(id) && map(BedrockStatsMenu.class,"DELIVERIES").containsKey(id),"Current-session form and delivery exist before genuine disconnect");
            current.close();
            c.assertTrue(server.getPlayerList().getPlayer(id)==null,"Actual current-session disconnect removes the native player");
            c.assertTrue(!map(AdminStatsMenu.class,"INPUTS").containsKey(id),"Genuine current disconnect still retires private input");
            c.assertTrue(!map(BedrockStatsMenu.class,"ACTIVE").containsKey(id) && !map(BedrockStatsMenu.class,"DELIVERIES").containsKey(id),"Genuine current disconnect still retires form and delivery");
            succeed();
        }
    }

    private static final class RecoveryScenario extends NativeSessionFixture {
        Joined old,current;
        ServerGamePacketListenerImpl previousConnection;
        RecoveryScenario(GameTestHelper c) {super(c);}
        void staleDisconnect() {ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(previousConnection,server);}
        @Override void start() {
            old=join("menu-recovery",6);previousConnection=old.player.connection;
            reconnect(old,replacement->{current=replacement;ready();});
        }
        void ready() {
            var p=current.player;var id=p.getUUID();var ready=map(ServerMenu.class,"READY_AT").get(id);
            c.assertTrue(ready!=null,"The genuine fresh join schedules navigator readiness");
            staleDisconnect();
            c.assertTrue(map(ServerMenu.class,"READY_AT").get(id)==ready,"Delayed original disconnect cannot erase the fresh join delay");
            later(7,this::navigator);
        }
        void navigator() {
            var p=current.player;var id=p.getUUID();p.closeContainer();
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.getInventory().setSelectedSlot(0);ServerMenu.tickPlayer(p);
            var tool=map(ServerMenu.class,"LAST_TOOL").get(id);c.assertTrue(tool!=null,"Actual menu upkeep records the fresh selected tool");
            for(int slot=0;slot<36;slot++)p.getInventory().setItem(slot,new ItemStack(Items.STONE,64));
            c.assertFalse(ServerMenu.ensureNavigator(p),"An actually full inventory cannot receive a navigator");
            c.assertTrue(set(ServerMenu.class,"FULL_NOTICE").contains(id),"Full-inventory recovery records its one-time notice");
            staleDisconnect();
            c.assertTrue(map(ServerMenu.class,"LAST_TOOL").get(id)==tool,"Old disconnect preserves the replacement's current tool context");
            c.assertTrue(set(ServerMenu.class,"FULL_NOTICE").contains(id),"Old disconnect cannot reset the fresh full-inventory notice");
            for(int slot=0;slot<9;slot++)p.getInventory().setItem(slot,ServerMenu.navigator(p));
            p.getInventory().setSelectedSlot(0);
            c.assertValueEqual(ServerMenu.open(p),1,"All-control hotbar opens the actual Infinity Menu");
            c.assertTrue(map(ServerMenu.class,"SELECTED_CONTROL").containsKey(id),"A held navigator is recorded when no tool can be restored");
            p.closeContainer();var selected=map(ServerMenu.class,"SELECTED_CONTROL").get(id);staleDisconnect();
            c.assertTrue(map(ServerMenu.class,"SELECTED_CONTROL").get(id)==selected,"Old disconnect preserves the fresh selected-control latch");
            ServerMenu.tickPlayer(p);
            c.assertTrue(p.containerMenu==p.inventoryMenu,"A closed fresh menu does not immediately reopen after stale disconnect");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);p.setGameMode(GameType.CREATIVE);
            var destination=GameModes.defaultPlace(server,GameModes.Mode.CREATIVE);var world=GameModes.world(server,GameModes.Mode.CREATIVE);
            int cx=net.minecraft.util.Mth.floor(destination.x())>>4,cz=net.minecraft.util.Mth.floor(destination.z())>>4;
            // Fixture-only warming precedes native cross-world routing; no cold-load timing is claimed.
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)world.getChunk(cx+dx,cz+dz);
            c.assertTrue(p.teleportTo(world,destination.x(),destination.y(),destination.z(),Set.of(),destination.yaw(),destination.pitch(),true),"Actual OP transfer enters Creative with its inventory hooks");
            c.assertValueEqual(GameModes.current(p),GameModes.Mode.CREATIVE,"Native inventory hooks restore the real Creative mode");
            var scheduled=map(CreativeGearPicker.class,"OPEN_AFTER").get(id);
            c.assertTrue(scheduled!=null,"Native Creative entry schedules its real picker opening");
            staleDisconnect();
            c.assertTrue(map(CreativeGearPicker.class,"OPEN_AFTER").get(id)==scheduled,"Old disconnect cannot erase the new Creative opening deadline");
            later(4,this::picker);
        }
        void picker() {
            var p=current.player;var id=p.getUUID();
            c.assertTrue(!map(CreativeGearPicker.class,"OPEN_AFTER").containsKey(id),"Actual ticks consume the scheduled picker deadline");
            c.assertTrue(p.containerMenu instanceof CreativeGearPicker.PickerHandler,"The retained scheduled Creative opening reaches the player");
            p.closeContainer();int slot=-1;
            for(int i=0;i<9;i++)if(CreativeGearPicker.isPicker(p.getInventory().getItem(i))){slot=i;break;}
            c.assertTrue(slot>=0,"Real Creative entry supplies a named picker in the hotbar");p.getInventory().setSelectedSlot(slot);
            c.assertValueEqual(CreativeGearPicker.open(p),1,"Current player opens the actual compass picker");p.closeContainer();
            c.assertTrue(set(CreativeGearPicker.class,"SELECTED").contains(id),"Closing a selected picker retains its reopen-suppression latch");
            staleDisconnect();c.assertTrue(set(CreativeGearPicker.class,"SELECTED").contains(id),"Old disconnect cannot clear the replacement's picker latch");
            later(2,()->{
                c.assertTrue(p.containerMenu==p.inventoryMenu,"Real upkeep does not reopen a closed current picker after stale disconnect");
                c.assertTrue(set(CreativeGearPicker.class,"SELECTED").contains(id),"The current picker latch is demonstrably live before genuine disconnect");
                current.close();c.assertTrue(server.getPlayerList().getPlayer(id)==null,"Genuine current recovery-session disconnect removes the player");
                c.assertTrue(!set(CreativeGearPicker.class,"SELECTED").contains(id),"Current disconnect cleans the demonstrably live picker latch");
                queuedDeparture();
            });
        }
        void queuedDeparture() {
            var queued=join("menu-queued",10);var p=queued.player;var id=p.getUUID();
            c.assertTrue(map(ServerMenu.class,"READY_AT").containsKey(id),"A separate real native join has a pending navigator deadline");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);p.setGameMode(GameType.CREATIVE);
            var destination=GameModes.defaultPlace(server,GameModes.Mode.CREATIVE);var world=GameModes.world(server,GameModes.Mode.CREATIVE);
            c.assertTrue(p.teleportTo(world,destination.x(),destination.y(),destination.z(),Set.of(),destination.yaw(),destination.pitch(),true),"The separate current player actually enters Creative");
            c.assertTrue(map(ServerMenu.class,"READY_AT").containsKey(id) && map(CreativeGearPicker.class,"OPEN_AFTER").containsKey(id),"Both native delayed openings are live before genuine departure");
            queued.close();
            c.assertTrue(server.getPlayerList().getPlayer(id)==null,"Pending-opening departure genuinely removes the native player");
            c.assertTrue(!map(ServerMenu.class,"READY_AT").containsKey(id) && !map(CreativeGearPicker.class,"OPEN_AFTER").containsKey(id),"Current disconnect cancels both demonstrably pending openings");
            var controls=join("menu-controls",13);later(7,()->controlDeparture(controls));
        }
        void controlDeparture(Joined joined) {
            var p=joined.player;var id=p.getUUID();p.closeContainer();
            p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));p.getInventory().setSelectedSlot(0);ServerMenu.tickPlayer(p);
            for(int slot=0;slot<36;slot++)p.getInventory().setItem(slot,new ItemStack(Items.STONE,64));
            c.assertFalse(ServerMenu.ensureNavigator(p),"The separate current player actually has no navigator capacity");
            for(int slot=0;slot<9;slot++)p.getInventory().setItem(slot,ServerMenu.navigator(p));p.getInventory().setSelectedSlot(0);
            c.assertValueEqual(ServerMenu.open(p),1,"The separate current player actually opens the all-control menu");p.closeContainer();
            c.assertTrue(map(ServerMenu.class,"LAST_TOOL").containsKey(id),"Current tool context is live before genuine departure");
            c.assertTrue(map(ServerMenu.class,"SELECTED_CONTROL").containsKey(id),"Current selected-control suppression is live before genuine departure");
            c.assertTrue(set(ServerMenu.class,"FULL_NOTICE").contains(id),"Current full-inventory notice is live before genuine departure");
            joined.close();c.assertTrue(server.getPlayerList().getPlayer(id)==null,"Control-state departure genuinely removes the native player");
            for(String name:new String[]{"LAST_TOOL","SELECTED_CONTROL"})c.assertTrue(!map(ServerMenu.class,name).containsKey(id),"Current disconnect cleans the demonstrably live navigator state: "+name);
            c.assertTrue(!set(ServerMenu.class,"FULL_NOTICE").contains(id),"Current disconnect cleans the demonstrably live full-inventory notice");
            succeed();
        }
    }

    private static final class QueuedInputScenario extends NativeSessionFixture {
        Joined current;
        Object replacement;
        QueuedInputScenario(GameTestHelper c) {super(c);}
        void exact(String stat) {
            var p=current.player;p.closeContainer();
            c.assertValueEqual(AdminStatsMenu.openStat(p,p,stat),1,"The current OP opens a real stat editor for queued input");
            click(p,AdminStatsMenu.EXACT);
            c.assertTrue(p.containerMenu==p.inventoryMenu,"Actual exact entry closes its chest before queueing text");
        }
        @Override void start() {
            current=join("menu-input",4);var p=current.player;OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            exact("max_health");var observed=map(AdminStatsMenu.class,"INPUTS").get(p.getUUID());
            c.assertTrue(observed!=null,"The worker will observe an actual private health prompt");
            queue(this::afterSamePrompt);
            exact("max_health");replacement=map(AdminStatsMenu.class,"INPUTS").get(p.getUUID());
            c.assertTrue(replacement!=null && replacement!=observed && replacement.equals(observed),"Reopening the same real prompt in the same tick creates distinct intent even when record values are equal");
        }
        void queue(Runnable continuation) {
            var p=current.player;
            var consumed=new AtomicBoolean();var dispatched=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
            var worker=new Thread(()->{
                try {
                    consumed.set(AdminStatsMenu.consumeChat(p,"6000"));
                    // FIFO from the same producer observes the actual queued chat callback first.
                    server.execute(()->dispatched.set(true));
                } catch(Throwable error) {failure.set(error);}
            },"menu-input-native-test");worker.setDaemon(true);worker.start();
            try {worker.join(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            c.assertFalse(worker.isAlive(),"Off-thread native input enqueue finishes within its bound while the main thread owns the step");
            if(failure.get()!=null)throw new IllegalStateException("Off-thread input fixture failed",failure.get());
            c.assertTrue(consumed.get(),"The original private number is consumed immediately off-thread");
            later(1,()->awaitDispatch(dispatched,continuation,100));
        }
        void awaitDispatch(AtomicBoolean dispatched,Runnable continuation,int remaining) {
            c.assertTrue(remaining>0,"Actual queued private input dispatch completes within its tick bound");
            // Native GameTest owns every assertion and success callback; the executor only marks dispatch.
            if(dispatched.get())continuation.run();
            else later(1,()->awaitDispatch(dispatched,continuation,remaining-1));
        }
        void afterSamePrompt() {
            var p=current.player;
            c.assertTrue(map(AdminStatsMenu.class,"INPUTS").get(p.getUUID())==replacement,"Queued text cannot consume a distinct prompt with equal record values");
            c.assertTrue(p.containerMenu==p.inventoryMenu,"Queued same-stat text cannot stage replacement intent");
            c.assertValueEqual(p.getMaxHealth(),20f,"Queued same-stat text has no effect before new consent");
            c.assertTrue(AdminStatsMenu.consumeChat(p,"cancel"),"The real current player can cancel its intact replacement prompt");
            exact("max_health");var observed=map(AdminStatsMenu.class,"INPUTS").get(p.getUUID());
            queue(this::afterQueued);
            exact("armor");replacement=map(AdminStatsMenu.class,"INPUTS").get(p.getUUID());
            c.assertTrue(replacement!=null && replacement!=observed,"The same current player has replaced health entry with a distinct armor prompt before queue dispatch");
        }
        void afterQueued() {
            var p=current.player;var id=p.getUUID();
            c.assertTrue(map(AdminStatsMenu.class,"INPUTS").get(id)==replacement,"Actual queued old text preserves the exact fresh prompt");
            c.assertTrue(p.containerMenu==p.inventoryMenu,"Queued old text cannot stage an editor for the replacement prompt");
            c.assertValueEqual(p.getMaxHealth(),20f,"Queued private health text applies no health edit");
            c.assertValueEqual(p.getAttributeBaseValue(Attributes.ARMOR),0d,"Queued old text applies no armor edit");
            c.assertTrue(AdminStatsMenu.consumeChat(p,"42"),"Current intended armor text remains private and usable");
            c.assertTrue(p.containerMenu instanceof AdminStatsMenu.Handler,"Current armor text opens the actual editor");
            var editor=(AdminStatsMenu.Handler)p.containerMenu;
            c.assertValueEqual(editor.statId,"armor","The fresh prompt retains its own stat intent");
            c.assertValueEqual(editor.pending,42d,"The fresh prompt stages only the current intended number");
            click(p,AdminStatsMenu.REVIEW);click(p,AdminStatsMenu.CONFIRM);
            c.assertValueEqual(p.getAttributeBaseValue(Attributes.ARMOR),42d,"Current exact review and confirmation still apply armor");
            c.assertValueEqual(p.getMaxHealth(),20f,"The superseded health intent never changes health");
            succeed();
        }
    }

    @GameTest(maxTicks=1200)
    public void nativeReconnectKeepsPrivateStatPromptsFormsAndCurrentConfirmations(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c,()->new PrivateScenario(c));
    }
    @GameTest(maxTicks=1200)
    public void nativeReconnectKeepsNavigatorAndCreativePickerRecoveryState(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c,()->new RecoveryScenario(c));
    }
    @GameTest(maxTicks=1200)
    public void nativeQueuedPrivateNumberCannotConsumeSamePlayerReplacementPrompt(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c,()->new QueuedInputScenario(c));
    }
}

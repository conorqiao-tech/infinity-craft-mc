package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Actual chest clicks and encoded Cumulus responses retain explicitly selected identities. */
public class GroupStatsMenuGameTests {
    private ServerPlayer player(GameTestHelper c,String name,boolean admin) {
        var p=new ModeGameTests().player(c,name);p.setGameMode(GameType.SURVIVAL);
        if(admin)OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
        return p;
    }
    private void cleanup(ServerPlayer p) {
        AdminStatsMenu.cancelEditors(p);p.containerMenu.setCarried(ItemStack.EMPTY);p.inventoryMenu.setCarried(ItemStack.EMPTY);p.closeContainer();
        GameModes.PENDING.remove(p.getUUID());GameModes.TRANSITIONS.remove(p.getUUID());
        OperatorGameTests.deop(p);p.level().getServer().getPlayerList().remove(p);
    }
    private static void click(ServerPlayer p,int slot) {p.containerMenu.clicked(slot,0,ContainerInput.PICKUP,p);}
    private static GroupStatsMenu.Handler menu(ServerPlayer p) {return (GroupStatsMenu.Handler)p.containerMenu;}
    private void group(ServerPlayer actor,ServerPlayer anchor,String stat) {
        actor.closeContainer();AdminStatsMenu.openStat(actor,anchor,stat);click(actor,AdminStatsMenu.GROUP);
    }
    private void select(ServerPlayer actor,ServerPlayer target) {
        var request=menu(actor).request;int choice=-1;
        for(int i=0;i<request.choices.size();i++)if(request.choices.get(i).player()==target)choice=i;
        if(choice<0)throw new IllegalStateException("Named player missing from the actual matching roster");
        while(menu(actor).page<choice/GroupStatsMenu.PAGE_SIZE)click(actor,GroupStatsMenu.NEXT);
        while(menu(actor).page>choice/GroupStatsMenu.PAGE_SIZE)click(actor,GroupStatsMenu.PREVIOUS);
        click(actor,choice%GroupStatsMenu.PAGE_SIZE);
    }
    private void number(ServerPlayer actor,String text) {click(actor,GroupStatsMenu.ACTION);AdminStatsMenu.consumeChat(actor,text);}
    private static void reply(Form form,String encoded) {
        try {org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,encoded);}
        catch(Exception failure) {throw new RuntimeException(failure);}
    }
    private static final class Capture implements Predicate<Form> {
        final ArrayList<Form> forms=new ArrayList<>();
        @Override public boolean test(Form form) {
            var data=org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            var encoded=com.google.gson.JsonParser.parseString(data).getAsJsonObject();
            if(!encoded.get("title").getAsString().equals(form.title()))throw new IllegalStateException("Native form title did not encode");
            forms.add(form);return true;
        }
        Form last() {return forms.getLast();}
        void click(String prefix) {
            var form=(SimpleForm)last();
            for(int i=0;i<form.buttons().size();i++)if(form.buttons().get(i).text().startsWith(prefix)) {reply(form,Integer.toString(i));return;}
            throw new IllegalStateException("Missing native group button: "+prefix);
        }
        void select(ServerPlayer target) {click("○ "+target.getName().getString()+" • ");}
        void input(String number) {reply(last(),new com.google.gson.Gson().toJson(new Object[]{null,number}));}
    }
    @SuppressWarnings("unchecked") private static Map<UUID,Object> map(Class<?> owner,String name) {
        try {var field=owner.getDeclaredField(name);field.setAccessible(true);return (Map<UUID,Object>)field.get(null);}
        catch(ReflectiveOperationException failure) {throw new RuntimeException(failure);}
    }
    private static void expire(GroupStatsMenu.Request request) {
        try {var field=GroupStatsMenu.Request.class.getDeclaredField("expires");field.setAccessible(true);field.setInt(request,request.actor.world().getServer().getTickCount());}
        catch(ReflectiveOperationException failure) {throw new RuntimeException(failure);}
    }
    private void awaitDispatch(GameTestHelper c,java.util.concurrent.atomic.AtomicBoolean dispatched,Runnable proof,Runnable cleanup,int remaining) {
        try {
            c.assertTrue(remaining>0,"Actual queued native reply dispatch completes within its tick bound");
            if(!dispatched.get()) {c.runAfterDelay(1,()->awaitDispatch(c,dispatched,proof,cleanup,remaining-1));return;}
            proof.run();
        } catch(RuntimeException|Error failure) {cleanup.run();throw failure;}
    }

    @GameTest public void javaNamedSubsetStagesCapacityAndConfirmsOnlyExplicitPlayers(GameTestHelper c) {
        var actor=player(c,"group-java-op",true);var a=player(c,"group-java-a",false);var b=player(c,"group-java-b",false);var excluded=player(c,"group-java-c",false);
        try {
            a.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));
            group(actor,a,"health");
            c.assertTrue(menu(actor).request.selected.isEmpty(),"Even the anchor starts unselected");
            click(actor,GroupStatsMenu.ACTION);c.assertTrue(actor.containerMenu instanceof GroupStatsMenu.Handler,"Zero selected players cannot enter a value");
            select(actor,a);select(actor,b);select(actor,b);select(actor,b);
            c.assertValueEqual(menu(actor).request.targets(),List.of(a,b).stream().sorted(java.util.Comparator.comparing(p->p.getName().getString())).toList(),"Toggling retains exact selected identities, not all matching values");
            number(actor,"5000");var review=menu(actor);
            c.assertValueEqual(review.plan.targets().size(),2,"One exact number creates a two-player plan");
            c.assertTrue(review.plan.changes().stream().allMatch(t->t.changes().size()==2),"Each player's capacity and health are both shown");
            c.assertValueEqual(a.getHealth(),20f,"Typing and preview never heal");c.assertValueEqual(b.getMaxHealth(),20f,"Preview never raises capacity");
            c.assertValueEqual(a.getInventory().getItem(0).getCount(),7,"Selection and preview preserve inventory");
            click(actor,GroupStatsMenu.ACTION);
            c.assertValueEqual(a.getHealth(),5000f,"Confirm changes the first selected player");c.assertValueEqual(b.getHealth(),5000f,"Confirm changes the second selected player");
            c.assertValueEqual(excluded.getHealth(),20f,"Equal unselected values never infer membership");c.assertValueEqual(actor.getHealth(),20f,"Admin is not implicitly selected");
            review.clicked(GroupStatsMenu.ACTION,0,ContainerInput.PICKUP,actor);
            c.assertTrue(actor.containerMenu==actor.inventoryMenu,"Old native chest packets cannot reopen or repeat the batch");
        } finally {cleanup(actor);cleanup(a);cleanup(b);cleanup(excluded);}
        c.succeed();
    }

    @GameTest public void nativeActionDropdownAndEncodedInputReviewOnlyTheNamedSubset(GameTestHelper c) {
        var actor=player(c,"group-touch-op",true);var a=player(c,"group-touch-a",false);var b=player(c,"group-touch-b",false);var excluded=player(c,"group-touch-c",false);var forms=new Capture();
        try {
            BedrockStatsMenu.open(actor,a,"absorption",forms);
            reply(forms.last(),"[null,\"ignored when grouping\",1]");
            c.assertTrue(forms.last() instanceof SimpleForm && forms.last().title().equals("Choose matching players"),"Non-attribute action index one opens named group selection");
            forms.select(a);forms.select(b);forms.click("Enter exact number");
            c.assertTrue(forms.last() instanceof CustomForm,"Native selection has one exact number field");
            var encoded=org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(forms.last()).codec().jsonData(forms.last());
            var content=com.google.gson.JsonParser.parseString(encoded).getAsJsonObject().getAsJsonArray("content");
            c.assertTrue(content.get(0).getAsJsonObject().get("type").getAsString().equals("label") && content.get(1).getAsJsonObject().get("type").getAsString().equals("input"),"Native numeric response indices are label zero, input one");
            forms.input("2500");var review=(SimpleForm)forms.last();
            c.assertTrue(review.buttons().stream().filter(x->x.text().contains("max_absorption: 0 → 2500") && x.text().contains("absorption: 0 → 2500")).count()==2,"Both named before/after capacity plans are visible");
            c.assertValueEqual(a.getAbsorptionAmount(),0f,"Encoded input and review are read only");
            forms.click("Confirm changes");c.assertValueEqual(a.getAbsorptionAmount(),2500f,"Native confirm applies first target");c.assertValueEqual(b.getAbsorptionAmount(),2500f,"Native confirm applies second target");
            c.assertValueEqual(excluded.getMaxAbsorption(),0f,"Unselected equal-value players retain their original capacity");
            int count=forms.forms.size();reply(review,Integer.toString(review.buttons().size()-2));c.assertValueEqual(forms.forms.size(),count,"A replayed native confirm creates no new screen");
            BedrockStatsMenu.open(actor,excluded,"armor",forms);reply(forms.last(),"[null,\"irrelevant\",2]");
            c.assertTrue(forms.last().title().equals("Choose matching players"),"Attribute action index two preserves restore-original at index one");
        } finally {cleanup(actor);cleanup(a);cleanup(b);cleanup(excluded);}
        c.succeed();
    }

    @GameTest public void namedSelectionPaginationRetainsOnlyExplicitUuidsAcrossPages(GameTestHelper c) {
        var players=new ArrayList<ServerPlayer>();var actor=player(c,"group-pages-op",true);players.add(actor);
        try {
            for(int i=0;i<47;i++)players.add(player(c,String.format(java.util.Locale.ROOT,"group-p%02d",i),false));
            var anchor=players.get(1);group(actor,anchor,"armor");
            var request=menu(actor).request;c.assertValueEqual(request.choices.size(),48,"Java roster contains every eligible actual online player");
            var first=request.choices.getFirst().player();var last=request.choices.getLast().player();select(actor,first);select(actor,last);
            c.assertValueEqual(menu(actor).request.selected.size(),2,"Java pages preserve exactly two toggled UUIDs");
            click(actor,GroupStatsMenu.PREVIOUS);c.assertTrue(menu(actor).view.getItem(0).getHoverName().getString().startsWith("Selected:"),"Returning a page retains its named selection");
            actor.closeContainer();var forms=new Capture();GroupStatsMenu.open(actor,anchor,"armor",forms);
            c.assertTrue(((SimpleForm)forms.last()).buttons().stream().noneMatch(b->b.text().startsWith("Enter exact")),"New native group starts empty even after Java selection");
            forms.select(first);forms.click("Next players");forms.click("Next players");forms.click("Next players");forms.select(last);forms.click("Enter exact");forms.input("100");
            var preview=(SimpleForm)forms.last();c.assertTrue(preview.buttons().stream().filter(b->b.text().contains("armor: 0 → 100")).count()==2,"Native pages retain the exact first and last names for review");
            forms.click("Cancel");c.assertTrue(players.stream().allMatch(p->p.getAttributeBaseValue(Attributes.ARMOR)==0),"Browsing every page and cancelling changes no players");
        } finally {for(var p:players)cleanup(p);}
        c.succeed();
    }

    @GameTest public void selectedValueOrSessionDriftCancelsWholeJavaAndNativeReview(GameTestHelper c) {
        var actor=player(c,"group-drift-op",true);var a=player(c,"group-drift-a",false);var b=player(c,"group-drift-b",false);var forms=new Capture();
        try {
            group(actor,a,"armor");select(actor,a);select(actor,b);number(actor,"100");
            b.getAttribute(Attributes.ARMOR).setBaseValue(2);click(actor,GroupStatsMenu.ACTION);
            c.assertValueEqual(a.getAttributeBaseValue(Attributes.ARMOR),0d,"A changed selected value cancels all Java targets");
            b.getAttribute(Attributes.ARMOR).setBaseValue(0);
            GroupStatsMenu.open(actor,a,"armor",forms);forms.select(a);forms.select(b);forms.click("Enter exact");forms.input("100");
            var review=forms.last();var connection=b.connection;
            try {b.connection=a.connection;forms.click("Confirm");}finally {b.connection=connection;}
            c.assertValueEqual(a.getAttributeBaseValue(Attributes.ARMOR),0d,"A changed selected connection cancels every native target");reply(review,"0");
            GroupStatsMenu.open(actor,a,"armor",forms);forms.select(a);forms.click("Enter exact");forms.input("100");a.setGameMode(GameType.CREATIVE);forms.click("Confirm");
            c.assertValueEqual(a.getAttributeBaseValue(Attributes.ARMOR),0d,"Changed native target mode invalidates review");a.setGameMode(GameType.SURVIVAL);
            group(actor,a,"armor");select(actor,a);number(actor,"100");OperatorGameTests.deop(actor);click(actor,GroupStatsMenu.ACTION);
            c.assertValueEqual(a.getAttributeBaseValue(Attributes.ARMOR),0d,"Revoked OP4 cannot confirm group editing");
        } finally {cleanup(actor);cleanup(a);cleanup(b);}
        c.succeed();
    }

    @GameTest public void switchingSingleAndGroupEditorsRetiresEveryOldPromptAndForm(GameTestHelper c) {
        var actor=player(c,"group-switch-op",true);var target=player(c,"group-switch-p",false);var forms=new Capture();
        try {
            AdminStatsMenu.openStat(actor,target,"armor");click(actor,AdminStatsMenu.EXACT);
            GroupStatsMenu.open(actor,target,"armor",forms);
            c.assertFalse(AdminStatsMenu.consumeChat(actor,"400"),"Entering native group selection retires the single private prompt");
            forms.select(target);forms.click("Enter exact");forms.input("200");var oldGroup=forms.last();
            BedrockStatsMenu.open(actor,target,"armor",forms);reply(oldGroup,"1");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),0d,"Single form supersedes an old group confirmation");
            reply(forms.last(),"[null,\"300\",0]");var oldSingle=forms.last();
            GroupStatsMenu.open(actor,target,"armor",forms);reply(oldSingle,"true");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),0d,"Group form supersedes an old single confirmation");
            forms.select(target);forms.click("Enter exact");var oldNumber=forms.last();
            group(actor,target,"armor");select(actor,target);click(actor,GroupStatsMenu.ACTION);
            reply(oldNumber,"[null,\"999\"]");
            c.assertTrue(AdminStatsMenu.consumeChat(actor,"125"),"Stale native input preserves the replacement Java group number prompt");
            c.assertValueEqual(menu(actor).plan.requested(),125d,"Replacement Java intent receives only its own text");
            actor.closeContainer();AdminStatsMenu.openStat(actor,target,"armor");click(actor,AdminStatsMenu.EXACT);
            c.assertTrue(AdminStatsMenu.consumeChat(actor,"50"),"Single editing works normally after leaving group review");
            c.assertTrue(actor.containerMenu instanceof AdminStatsMenu.Handler,"Private text returns to the original single editor");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),0d,"All switching and staging remains read only");
        } finally {cleanup(actor);cleanup(target);}
        c.succeed();
    }

    @GameTest public void malformedNativeInputOccupiedCursorCancellationAndHealthZeroNeverMutate(GameTestHelper c) {
        var actor=player(c,"group-invalid-op",true);var target=player(c,"group-invalid-p",false);var forms=new Capture();
        try {
            GroupStatsMenu.open(actor,target,"health",forms);forms.select(target);forms.click("Enter exact");forms.input("0");
            c.assertTrue(forms.last() instanceof CustomForm,"Batch health zero explains refusal and permits a corrected number");
            c.assertValueEqual(target.getHealth(),20f,"Group health zero never kills a player");forms.input("NaN");
            c.assertTrue(forms.last() instanceof CustomForm,"Nonfinite value stays an unapplied number prompt");
            forms.input("30");var review=forms.last();actor.inventoryMenu.setCarried(new ItemStack(Items.EMERALD));forms.click("Confirm");actor.inventoryMenu.setCarried(ItemStack.EMPTY);
            c.assertValueEqual(target.getHealth(),20f,"Occupied native cursor rejects confirmation");reply(review,"0");
            GroupStatsMenu.open(actor,target,"health",forms);forms.select(target);forms.click("Enter exact");reply(forms.last(),"[null,null]");
            c.assertValueEqual(target.getHealth(),20f,"A malformed encoded numeric response cannot heal or kill");
            GroupStatsMenu.open(actor,target,"health",forms);forms.select(target);forms.click("Enter exact");forms.input("35");forms.click("Cancel");
            c.assertValueEqual(target.getMaxHealth(),20f,"Cancelling the capacity-bearing preview leaves both values unchanged");
            group(actor,target,"health");select(actor,target);click(actor,GroupStatsMenu.ACTION);AdminStatsMenu.consumeChat(actor,"cancel");
            c.assertFalse(AdminStatsMenu.consumeChat(actor,"35"),"Cancelled Java number entry leaves ordinary chat available");
        } finally {cleanup(actor);cleanup(target);}
        c.succeed();
    }

    @GameTest public void originalNinetySecondDeadlineRejectsJavaAndNativeReplies(GameTestHelper c) {
        var actor=player(c,"group-expire-op",true);var target=player(c,"group-expire-p",false);var forms=new Capture();
        try {
            group(actor,target,"armor");select(actor,target);var request=menu(actor).request;click(actor,GroupStatsMenu.ACTION);expire(request);
            c.assertTrue(AdminStatsMenu.consumeChat(actor,"200"),"Expired group numbers remain private");
            c.assertTrue(actor.containerMenu==actor.inventoryMenu,"Expired number cannot open a review");
            GroupStatsMenu.open(actor,target,"armor",forms);forms.select(target);forms.click("Enter exact");forms.input("100");
            var active=(GroupStatsMenu.Request)map(GroupStatsMenu.class,"ACTIVE").get(actor.getUUID());expire(active);forms.click("Confirm");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),0d,"The same original deadline guards native confirmation");
        } finally {cleanup(actor);cleanup(target);}
        c.succeed();
    }

    @GameTest public void nativeDefaultTransportHandoffQueuesOnlyTheCurrentEditor(GameTestHelper c) {
        var actor=player(c,"group-delivery-op",true);var target=player(c,"group-delivery-p",false);
        try {
            BedrockStatsMenu.open(actor,target,"armor");
            Object single=map(BedrockStatsMenu.class,"DELIVERIES").get(actor.getUUID());
            var accessor=single.getClass().getDeclaredMethod("form");accessor.setAccessible(true);var form=(Form)accessor.invoke(single);
            reply(form,"[null,\"ignored\",2]");
            c.assertFalse(map(BedrockStatsMenu.class,"DELIVERIES").containsKey(actor.getUUID()),"The original single queue is retired before handoff");
            c.assertTrue(map(BedrockGroupStatsMenu.class,"DELIVERIES").containsKey(actor.getUUID()),"Production native handoff schedules the real group delivery");
            AdminStatsMenu.openStat(actor,target,"armor");
            c.assertFalse(map(BedrockGroupStatsMenu.class,"DELIVERIES").containsKey(actor.getUUID()),"Opening a Java single editor cancels an undelivered native group form");
            c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),0d,"Queued transport and switching never mutate stats");
        } catch(ReflectiveOperationException failure) {throw new RuntimeException(failure);}
        finally {cleanup(actor);cleanup(target);}
        c.succeed();
    }

    @GameTest public void offThreadQueuedOldNativeReplyCannotReplaceNewGroupIntent(GameTestHelper c) {
        var actor=player(c,"group-queued-op",true);var target=player(c,"group-queued-p",false);var forms=new Capture();
        try {
            GroupStatsMenu.open(actor,target,"armor",forms);forms.select(target);forms.click("Enter exact");forms.input("100");
            var old=(SimpleForm)forms.last();int confirm=old.buttons().size()-2;
            var dispatched=new java.util.concurrent.atomic.AtomicBoolean();
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker=new Thread(()->{try {reply(old,Integer.toString(confirm));actor.level().getServer().execute(()->dispatched.set(true));}
                catch(Throwable error) {failure.set(error);}},"group-stale-form-proof");worker.setDaemon(true);worker.start();worker.join(1000);
            c.assertFalse(worker.isAlive(),"Native callback has dispatched to the actual server queue");
            if(failure.get()!=null)throw new IllegalStateException("Native reply enqueue failed",failure.get());
            GroupStatsMenu.open(actor,target,"armor",forms);var replacement=forms.last();
            var replacementRequest=map(GroupStatsMenu.class,"ACTIVE").get(actor.getUUID());
            var replacementTicket=map(BedrockGroupStatsMenu.class,"ACTIVE").get(actor.getUUID());
            reply(old,"null");
            c.assertTrue(map(GroupStatsMenu.class,"ACTIVE").get(actor.getUUID())==replacementRequest
                && map(BedrockGroupStatsMenu.class,"ACTIVE").get(actor.getUUID())==replacementTicket,
                "Closing an old same-player/same-connection group form cannot retire a replacement Request or Ticket");
            awaitDispatch(c,dispatched,()->{try {
                c.assertTrue(dispatched.get(),"The original native callback actually ran before the assertions");
                c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),0d,"An already queued old confirm cannot mutate after supersession");
                c.assertTrue(forms.last()==replacement,"Queued old response cannot replace the fresh selection form");
                forms.select(target);forms.click("Enter exact");forms.input("150");forms.click("Confirm");
                c.assertValueEqual(target.getAttributeBaseValue(Attributes.ARMOR),150d,"The untouched replacement ticket can complete normally");c.succeed();
            } finally {cleanup(actor);cleanup(target);}},()->{cleanup(actor);cleanup(target);},100);
        } catch(InterruptedException failure) {Thread.currentThread().interrupt();cleanup(actor);cleanup(target);throw new RuntimeException(failure);}
        catch(RuntimeException failure) {cleanup(actor);cleanup(target);throw failure;}
    }

    private static final class ReconnectScenario extends NativeSessionFixture {
        Joined actor,target,other,oldActor;
        final Capture forms=new Capture();
        Form oldReview;
        ReconnectScenario(GameTestHelper c) {super(c);}
        @Override void start() {
            actor=join("group-rejoin-op",2);target=join("group-rejoin-p",5);other=join("group-rejoin-q",8);
            for(var joined:List.of(actor,target,other))joined.player.setGameMode(GameType.SURVIVAL);
            OperatorGameTests.level(actor.player,LevelBasedPermissionSet.OWNER);
            target.player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));
            GroupStatsMenu.open(actor.player,target.player,"armor",forms);forms.select(target.player);forms.select(other.player);forms.click("Enter exact");forms.input("100");oldReview=forms.last();
            reconnect(target,replacement->{target=replacement;afterTarget();});
        }
        void afterTarget() {
            var review=(SimpleForm)oldReview;reply(review,Integer.toString(review.buttons().size()-2));
            c.assertValueEqual(other.player.getAttributeBaseValue(Attributes.ARMOR),0d,"A real selected-player saved reconnect cancels the whole former group");
            c.assertValueEqual(target.player.getAttributeBaseValue(Attributes.ARMOR),0d,"Equal UUID and values cannot substitute a new target instance");
            c.assertValueEqual(target.player.getInventory().getItem(0).getCount(),7,"Saved reconnect preserves the target's items");
            actor.player.closeContainer();GroupStatsMenu.open(actor.player,target.player,"armor");
            var handler=menu(actor.player);int selected=-1;
            for(int i=0;i<handler.request.choices.size();i++)if(handler.request.choices.get(i).player()==target.player)selected=i;
            c.assertTrue(selected>=0,"Fresh roster includes the actual replacement session");click(actor.player,selected);click(actor.player,GroupStatsMenu.ACTION);
            c.assertTrue(map(GroupStatsMenu.class,"INPUTS").containsKey(actor.player.getUUID()),"Actual group private input exists before actor departure");
            oldActor=actor;reconnect(actor,replacement->{actor=replacement;afterActor();});
        }
        void afterActor() {
            var p=actor.player;OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            c.assertFalse(map(GroupStatsMenu.class,"INPUTS").containsKey(p.getUUID()),"Real actor disconnect removes its old private group prompt");
            GroupStatsMenu.open(p,target.player,"armor",forms);var fresh=forms.last();
            var freshRequest=map(GroupStatsMenu.class,"ACTIVE").get(p.getUUID());
            GroupStatsMenu.cancelPending(oldActor.player);BedrockGroupStatsMenu.cancelPending(oldActor.player);
            BedrockStatsMenu.cancelPending(oldActor.player);AdminStatsMenu.cancelInput(oldActor.player);AdminStatsMenu.cancelEditors(oldActor.player);
            net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(oldActor.player.connection,server);
            c.assertTrue(forms.last()==fresh && map(GroupStatsMenu.class,"ACTIVE").get(p.getUUID())==freshRequest
                && map(BedrockGroupStatsMenu.class,"ACTIVE").containsKey(p.getUUID()),"Stale cancellation helpers and delayed old-session disconnect cannot retire the replacement's Request or form");
            var review=(SimpleForm)oldReview;reply(review,Integer.toString(review.buttons().size()-2));
            forms.select(target.player);forms.select(other.player);forms.click("Enter exact");forms.input("125");forms.click("Confirm");
            c.assertValueEqual(target.player.getAttributeBaseValue(Attributes.ARMOR),125d,"Replacement actor can confirm a freshly selected real target session");
            c.assertValueEqual(other.player.getAttributeBaseValue(Attributes.ARMOR),125d,"Replacement review still applies its whole explicit subset");
            c.assertValueEqual(target.player.getInventory().getItem(0).getCount(),7,"Group edits preserve saved target inventory");succeed();
        }
        @Override void cleanup() {for(var joined:players)if(joined.player!=null)AdminStatsMenu.cancelEditors(joined.player);}
    }
    @GameTest(maxTicks=1200) public void nativeSavedTargetAndActorReconnectRejectOldGroupIntent(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c,()->new ReconnectScenario(c));
    }
}

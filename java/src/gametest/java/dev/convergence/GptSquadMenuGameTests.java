package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Explicit UI submission is observed locally; these tests never contact an AI service. */
public class GptSquadMenuGameTests {
    private ServerPlayer player(GameTestHelper c,String name) {
        var player=new ModeGameTests().player(c,name);player.setGameMode(GameType.SURVIVAL);
        OperatorGameTests.level(player,LevelBasedPermissionSet.OWNER);return player;
    }
    private void cleanup(ServerPlayer player) {
        HelperForms.ACTIVE.remove(player.getUUID());HelperForms.DELIVERIES.remove(player.getUUID());
        player.containerMenu.setCarried(ItemStack.EMPTY);player.inventoryMenu.setCarried(ItemStack.EMPTY);player.closeContainer();
        OperatorGameTests.deop(player);player.level().getServer().getPlayerList().remove(player);
    }
    private static void click(ServerPlayer player,int slot) {player.containerMenu.clicked(slot,0,ContainerInput.PICKUP,player);}
    private static void reply(Form form,String value) {
        try {org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,value);}
        catch(Exception failure) {throw new RuntimeException(failure);}
    }
    private static void submit(Form form,String goal) {reply(form,new com.google.gson.Gson().toJson(new Object[]{null,goal}));}
    private static final class Capture implements Predicate<Form> {
        final ArrayList<Form> forms=new ArrayList<>();
        @Override public boolean test(Form form) {
            var json=org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            if(!com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("title").getAsString().equals(form.title()))throw new IllegalStateException("Form did not encode");
            forms.add(form);return true;
        }
        Form last() {return forms.getLast();}
    }
    private static final class Requests implements BiConsumer<ServerPlayer,String> {
        final ArrayList<String> goals=new ArrayList<>();final ServerPlayer expected;
        Requests(ServerPlayer expected) {this.expected=expected;}
        @Override public void accept(ServerPlayer owner,String goal) {
            if(owner!=expected || !owner.level().getServer().isSameThread())throw new IllegalStateException("Request must retain exact owner and server thread");
            goals.add(goal);
        }
    }

    @GameTest public void rosterAndGoalInformationOpenWithoutAnyImplicitPlanningRequest(GameTestHelper c) {
        var owner=player(c,"gpt-menu-view");var requests=new Requests(owner);var forms=new Capture();
        var helpers=AgentCompanions.get(owner.level().getServer());int saved=helpers.data.agents.size();
        int proposals=AgentActions.get(helpers.server).data.proposals.size();
        try {
            AgentMenu.open(owner);
            c.assertTrue(owner.containerMenu.getSlot(AgentMenu.GPT_PLAN).getItem().getHoverName().getString().equals("Ask GPT for a squad plan"),"Java squad advertises the distinct GPT planner");
            click(owner,AgentMenu.GPT_PLAN);
            c.assertTrue(owner.containerMenu instanceof GptSquadMenu.Handler,"The roster opens a goal picker rather than sending a goal");
            click(owner,GptSquadMenu.BACK);owner.closeContainer();
            GptSquadMenu.open(owner,requests);click(owner,GptSquadMenu.INFO);
            c.assertTrue(requests.goals.isEmpty(),"Opening and reading goal information sends nothing");
            click(owner,GptSquadMenu.BACK);owner.closeContainer();
            BedrockAgentMenu.open(owner,forms);var roster=(SimpleForm)forms.last();int choice=-1;
            for(int i=0;i<roster.buttons().size();i++)if(roster.buttons().get(i).text().equals("Ask GPT for a squad plan"))choice=i;
            c.assertTrue(choice>=0,"Native squad advertises the same GPT planning route");reply(roster,Integer.toString(choice));
            c.assertTrue(forms.last() instanceof CustomForm,"Native entry opens a goal field without requesting a plan");reply(forms.last(),"null");
            c.assertValueEqual(helpers.data.agents.size(),saved,"Viewing and cancelling never creates or replaces helpers");
            c.assertValueEqual(AgentActions.get(helpers.server).data.proposals.size(),proposals,"Viewing and cancelling creates no pending orders");
        } finally {cleanup(owner);}
        c.succeed();
    }

    @GameTest public void eachJavaPresetSendsExactlyOneExplicitGoalAndOldWindowsAreInert(GameTestHelper c) {
        var owner=player(c,"gpt-menu-presets");var requests=new Requests(owner);
        try {
            owner.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));
            var slots=new int[]{GptSquadMenu.ESCORT,GptSquadMenu.DEFEND,GptSquadMenu.COMBAT};
            var expected=List.of(GptSquadMenu.ESCORT_GOAL,GptSquadMenu.DEFEND_GOAL,GptSquadMenu.COMBAT_GOAL);
            for(int i=0;i<slots.length;i++) {
                GptSquadMenu.open(owner,requests);var old=(GptSquadMenu.Handler)owner.containerMenu;click(owner,slots[i]);
                c.assertValueEqual(requests.goals.size(),i+1,"One preset activation sends one local request");
                c.assertTrue(owner.containerMenu==owner.inventoryMenu,"Submission closes the goal picker before the asynchronous result");
                old.clicked(slots[i],0,ContainerInput.PICKUP,owner);
                c.assertValueEqual(requests.goals.size(),i+1,"Old native window replay cannot submit again");
            }
            c.assertValueEqual(requests.goals,expected,"All three buttons send their exact displayed goals");
            c.assertValueEqual(owner.getInventory().getItem(0).getCount(),7,"Goal buttons never transfer their icons or modify inventory");
        } finally {cleanup(owner);}
        c.succeed();
    }

    @GameTest public void encodedNativeGoalUsesLabelZeroInputOneAndRejectsMalformedOrStaleReplies(GameTestHelper c) {
        var owner=player(c,"gpt-menu-touch");var requests=new Requests(owner);var forms=new Capture();
        try {
            GptSquadMenu.openBedrock(owner,forms,requests);var form=forms.last();
            var encoded=org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            var fields=com.google.gson.JsonParser.parseString(encoded).getAsJsonObject().getAsJsonArray("content");
            c.assertValueEqual(fields.size(),2,"Goal form has just an information label and a goal field");
            c.assertTrue(fields.get(0).getAsJsonObject().get("type").getAsString().equals("label") && fields.get(1).getAsJsonObject().get("type").getAsString().equals("input"),"Native response indices match actual encoded component order");
            submit(form,"  Escort me safely  ");c.assertValueEqual(requests.goals,List.of("Escort me safely"),"Submit sends the trimmed explicit goal");
            submit(form,"repeat");c.assertValueEqual(requests.goals.size(),1,"Native replay cannot request a second plan");
            for(String malformed:new String[]{"[]","[null,null]","null"}) {
                GptSquadMenu.openBedrock(owner,forms,requests);reply(forms.last(),malformed);
                c.assertValueEqual(requests.goals.size(),1,"Malformed or cancelled native replies send nothing");
            }
            for(String invalid:new String[]{" ","x".repeat(257),"escort\nme","escort\u0000me"}) {
                GptSquadMenu.openBedrock(owner,forms,requests);submit(forms.last(),invalid);
                c.assertValueEqual(requests.goals.size(),1,"Empty, oversized and control-character goals are not submitted even through the mock seam");
            }
            GptSquadMenu.openBedrock(owner,forms,requests);var old=forms.last();
            GptSquadMenu.openBedrock(owner,forms,requests);var current=forms.last();var ticket=HelperForms.ACTIVE.get(owner.getUUID());
            reply(old,"null");submit(old,"stale goal");
            c.assertTrue(HelperForms.ACTIVE.get(owner.getUUID())==ticket,"Old cancellation and submission preserve the current exact ticket");
            submit(current,"Defend here");c.assertValueEqual(requests.goals,List.of("Escort me safely","Defend here"),"The replacement current ticket remains usable");
            String unicode="🛡".repeat(ServerAssistant.MAX_QUESTION);
            GptSquadMenu.openBedrock(owner,forms,requests);submit(forms.last(),unicode);
            c.assertValueEqual(requests.goals.getLast(),unicode,"The boundary counts native Unicode code points rather than UTF-16 units");
            c.assertValueEqual(GptSquadMenu.openBedrock(owner,f->false,requests),0,"A failed native delivery is reported");
            c.assertFalse(HelperForms.ACTIVE.containsKey(owner.getUUID()),"Failed delivery leaves no later executable ticket");
        } finally {cleanup(owner);}
        c.succeed();
    }

    @GameTest public void javaAndNativeGoalsRetainSessionPermissionCursorAndExpiryGuards(GameTestHelper c) {
        var owner=player(c,"gpt-menu-guards");var other=player(c,"gpt-menu-other");var requests=new Requests(owner);var forms=new Capture();
        try {
            GptSquadMenu.open(owner,requests);var handler=(GptSquadMenu.Handler)owner.containerMenu;
            handler.clicked(GptSquadMenu.ESCORT,0,ContainerInput.PICKUP,other);
            handler.setCarried(new ItemStack(Items.EMERALD));click(owner,GptSquadMenu.ESCORT);handler.setCarried(ItemStack.EMPTY);
            c.assertTrue(requests.goals.isEmpty(),"Foreign owner and occupied Java cursor send nothing");
            var field=GptSquadMenu.Handler.class.getDeclaredField("expires");field.setAccessible(true);field.setInt(handler,owner.level().getServer().getTickCount());
            click(owner,GptSquadMenu.ESCORT);c.assertTrue(requests.goals.isEmpty(),"Expired Java goal picker cannot request");
            GptSquadMenu.openBedrock(owner,forms,requests);var ticket=HelperForms.ACTIVE.get(owner.getUUID());
            c.assertValueEqual(ticket.expires(),owner.level().getServer().getTickCount()+1800,"Native goal ticket has the existing ninety-second deadline");
            var expired=new HelperForms.Ticket(ticket.nonce(),ticket.actor(),ticket.context(),owner.level().getServer().getTickCount(),ticket.sender());
            HelperForms.ACTIVE.put(owner.getUUID(),expired);HelperForms.response(expired,()->requests.accept(owner,"must not run"));
            c.assertTrue(requests.goals.isEmpty(),"The native response guard rejects an actually expired ticket");
            GptSquadMenu.openBedrock(owner,forms,requests);owner.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));submit(forms.last(),"Escort");owner.inventoryMenu.setCarried(ItemStack.EMPTY);
            GptSquadMenu.openBedrock(owner,forms,requests);var mode=forms.last();owner.setGameMode(GameType.CREATIVE);submit(mode,"Escort");owner.setGameMode(GameType.SURVIVAL);
            GptSquadMenu.openBedrock(owner,forms,requests);var connection=owner.connection;
            try {owner.connection=other.connection;submit(forms.last(),"Escort");}finally {owner.connection=connection;}
            GptSquadMenu.openBedrock(owner,forms,requests);OperatorGameTests.level(owner,LevelBasedPermissionSet.ADMIN);submit(forms.last(),"Escort");
            c.assertTrue(requests.goals.isEmpty(),"Cursor, mode, exact connection and OP4 checks all block native submission");
        } catch(ReflectiveOperationException failure) {throw new RuntimeException(failure);}
        finally {cleanup(owner);cleanup(other);}
        c.succeed();
    }

    @GameTest public void queuedNativePlannerReplyIsInvalidatedByAnotherHelperScreen(GameTestHelper c) {
        var owner=player(c,"gpt-menu-queued");var requests=new Requests(owner);var forms=new Capture();
        try {
            GptSquadMenu.openBedrock(owner,forms,requests);var old=forms.last();
            var dispatched=new java.util.concurrent.atomic.AtomicBoolean();var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker=new Thread(()->{try {submit(old,"Escorted combat");owner.level().getServer().execute(()->dispatched.set(true));}
                catch(Throwable problem) {failure.set(problem);}},"gpt-menu-queued-proof");worker.setDaemon(true);worker.start();worker.join(1000);
            c.assertFalse(worker.isAlive(),"Native response dispatch enqueues without blocking the server thread");
            if(failure.get()!=null)throw new IllegalStateException("Native planner fixture failed",failure.get());
            BedrockAgentMenu.open(owner,forms);var fresh=forms.last();
            await(c,owner,dispatched,()->{
                c.assertTrue(requests.goals.isEmpty(),"A queued superseded native goal cannot request GPT");
                c.assertTrue(forms.last()==fresh,"Queued old callback leaves the new helper screen intact");
                GptSquadMenu.openBedrock(owner,forms,requests);submit(forms.last(),"Current explicit goal");
                c.assertValueEqual(requests.goals,List.of("Current explicit goal"),"A fresh explicit goal still dispatches exactly once");c.succeed();
            },100);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();cleanup(owner);throw new RuntimeException(interrupted);}
        catch(RuntimeException failure) {cleanup(owner);throw failure;}
    }
    private void await(GameTestHelper c,ServerPlayer owner,java.util.concurrent.atomic.AtomicBoolean dispatched,Runnable proof,int remaining) {
        try {
            c.assertTrue(remaining>0,"Actual queued native goal callback finishes within its tick bound");
            if(!dispatched.get()) {c.runAfterDelay(1,()->await(c,owner,dispatched,proof,remaining-1));return;}
            try {proof.run();}finally {cleanup(owner);}
        } catch(RuntimeException|Error failure) {cleanup(owner);throw failure;}
    }
}

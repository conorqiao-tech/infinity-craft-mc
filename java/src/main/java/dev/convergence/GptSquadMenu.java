package dev.convergence;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;

/** A deliberate goal request can prepare an order; this screen never approves or applies it. */
final class GptSquadMenu {
    static final int INFO=4,ESCORT=11,DEFEND=13,COMBAT=15,BACK=49;
    static final String ESCORT_GOAL="Plan an escort formation for my existing squad. Preserve helper identities, health and edited stats.";
    static final String DEFEND_GOAL="Plan how my existing squad can defend my current area with coordinated positions and shared focus.";
    static final String COMBAT_GOAL="Plan the strongest coordinated combat setup for my existing squad, using Infinity weapons and native spear, gliding and mace combos where supported.";
    static final String INFORMATION="Uses the host's configured GPT planning route and shared AI request budget and usage limits. A disabled or unavailable route sends nothing. "
        +"Only submitting a goal sends it with limited squad facts. The result enters Orders for your approval and separate live Codex review. Nothing executes from this screen.";
    private GptSquadMenu() {}

    static int open(ServerPlayer owner) {
        return CrossplaySupport.bedrock(owner)?openBedrock(owner,HelperForms.sender(owner),GptSquadPlanner::request):open(owner,GptSquadPlanner::request);
    }
    static int open(ServerPlayer owner,BiConsumer<ServerPlayer,String> requester) {
        if(!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner) || !new AdminStatsMenu.Session(owner).valid())return 0;
        HelperForms.ACTIVE.remove(owner.getUUID());HelperForms.DELIVERIES.remove(owner.getUUID());
        var view=new SimpleContainer(54);
        AgentMenu.icon(view,INFO,Items.BOOK,"Ask GPT for a squad plan");AgentMenu.description(view,INFO,INFORMATION);
        AgentMenu.icon(view,ESCORT,Items.SHIELD,"Request an escort plan");AgentMenu.description(view,ESCORT,ESCORT_GOAL);
        AgentMenu.icon(view,DEFEND,Items.IRON_CHESTPLATE,"Request an area defense plan");AgentMenu.description(view,DEFEND,DEFEND_GOAL);
        AgentMenu.icon(view,COMBAT,Items.NETHERITE_SWORD,"Request the strongest combat plan");AgentMenu.description(view,COMBAT,COMBAT_GOAL);
        AgentMenu.icon(view,BACK,Items.ARROW,"Cancel • back to your squad");
        owner.openMenu(new SimpleMenuProvider((sync,inventory,who)->new Handler(sync,inventory,view,owner,requester),Component.literal("Choose a GPT squad goal")));
        return 1;
    }
    static int openBedrock(ServerPlayer owner,Predicate<Form> sender,BiConsumer<ServerPlayer,String> requester) {
        if(!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner) || !new AdminStatsMenu.Session(owner).valid())return 0;
        var ticket=new HelperForms.Ticket(UUID.randomUUID(),new AdminStatsMenu.Session(owner),()->true,
            owner.level().getServer().getTickCount()+1800,sender);
        HelperForms.ACTIVE.put(owner.getUUID(),ticket);HelperForms.DELIVERIES.remove(owner.getUUID());
        var form=CustomForm.builder().title("Ask GPT for a squad plan")
            .label(INFORMATION).input("Squad goal • 1–256 characters, one line","Example: escort me in a defensive formation","");
        form.validResultHandler(result->HelperForms.response(ticket,()->{
            String input=result.asInput(1);String goal=input==null?"":input.strip();
            if(goal.isEmpty() || ServerAssistant.invalid(input)!=null) {
                HelperForms.message(owner,"Enter a squad goal of 1–256 characters on one line without control characters. No request was sent.");openBedrock(owner,sender,requester);return;
            }
            requester.accept(owner,goal);
        })).closedOrInvalidResultHandler(()->cancel(ticket));
        return HelperForms.send(ticket,form.build(),sender)?1:0;
    }
    private static void cancel(HelperForms.Ticket ticket) {
        var server=ticket.actor().world().getServer();
        if(!server.isSameThread()) {server.execute(()->cancel(ticket));return;}
        var id=ticket.actor().player().getUUID();HelperForms.ACTIVE.remove(id,ticket);
        HelperForms.DELIVERIES.computeIfPresent(id,(key,delivery)->delivery.ticket()==ticket?null:delivery);
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner;final AdminStatsMenu.Session session;final int expires;
        final BiConsumer<ServerPlayer,String> requester;boolean used;
        Handler(int sync,Inventory inventory,SimpleContainer view,ServerPlayer owner,BiConsumer<ServerPlayer,String> requester) {
            super(MenuType.GENERIC_9x6,sync,inventory,view,6);this.owner=owner;this.session=new AdminStatsMenu.Session(owner);
            this.expires=owner.level().getServer().getTickCount()+1800;this.requester=requester;
        }
        @Override public boolean stillValid(Player player) {return player==owner && owner.containerMenu==this && !used && session.valid()
            && AgentMenu.allowed(owner) && owner.level().getServer().getTickCount()<expires;}
        @Override public ItemStack quickMoveStack(Player player,int slot) {return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected) {}
        @Override public void clicked(int slot,int button,ContainerInput action,Player player) {
            if(player!=owner || owner.containerMenu!=this || owner.connection!=session.connection())return;
            if(!stillValid(player)) {owner.closeContainer();return;}
            if((action!=ContainerInput.PICKUP && action!=ContainerInput.QUICK_MOVE) || button<0 || button>1 || !getCarried().isEmpty()) {sendAllDataToRemote();return;}
            if(slot==BACK) {used=true;owner.closeContainer();AgentMenu.open(owner);return;}
            String goal=switch(slot) {case ESCORT->ESCORT_GOAL;case DEFEND->DEFEND_GOAL;case COMBAT->COMBAT_GOAL;default->null;};
            if(goal==null)return;
            used=true;owner.closeContainer();requester.accept(owner,goal);
        }
    }
}

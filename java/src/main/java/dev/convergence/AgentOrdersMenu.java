package dev.convergence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Owner approvals are visible in-game; the separate console/Codex gate is unchanged. */
final class AgentOrdersMenu {
    enum Page { MAIN, TARGETS, PENDING, REVIEW }
    static final int TARGET=10,PENDING=12,APPROVE=11,CANCEL=15,BACK=49,PREVIOUS=45,NEXT=53;
    private static final Map<Integer,String> ACTIONS=Map.of(19,"set day",21,"set night",23,"clear weather",25,"make it rain",28,"save world",30,"list players");
    private AgentOrdersMenu() {}
    static int open(ServerPlayerEntity owner) { return show(owner,Page.MAIN,0,null); }
    private static int show(ServerPlayerEntity owner,Page page,int index,String proposalId) {
        if(!AgentMenu.allowed(owner)||owner.currentScreenHandler!=owner.playerScreenHandler||!owner.playerScreenHandler.getCursorStack().isEmpty())return 0;
        var queue=AgentActions.get(owner.getEntityWorld().getServer());queue.expire();
        var view=new SimpleInventory(54);
        var targets=new LinkedHashMap<Integer,AdminStatsMenu.Session>();
        var proposals=new LinkedHashMap<Integer,String>();
        var selected=proposalId==null?null:queue.data.proposals.get(proposalId);
        if(page==Page.REVIEW && (selected==null||!selected.active()||!selected.owner.equals(owner.getUuidAsString())))return show(owner,Page.PENDING,0,null);
        var online=owner.getEntityWorld().getServer().getPlayerManager().getPlayerList().stream()
            .filter(p->p!=owner && p.getEntityWorld()==owner.getEntityWorld()).sorted(java.util.Comparator.comparing(p->p.getName().getString())).toList();
        int pages=page==Page.TARGETS?Math.max(1,(online.size()+44)/45):1;
        int pageIndex=Math.max(0,Math.min(index,pages-1));
        if(page==Page.MAIN) {
            AgentMenu.icon(view,TARGET,Items.PLAYER_HEAD,"Choose player target");
            AgentMenu.description(view,TARGET,"Propose a player for your Primitive or Ultimate Finals squad. Review your approval, then ask Codex here for its separate review. Nothing attacks automatically.");
            AgentMenu.icon(view,PENDING,Items.WRITABLE_BOOK,"Pending orders • approve or cancel");
            for(var entry:ACTIONS.entrySet()) {
                AgentMenu.icon(view,entry.getKey(),Items.PAPER,"Propose: "+entry.getValue());
                AgentMenu.description(view,entry.getKey(),"Queues this exact action. Owner and live Codex approvals are both required before it runs.");
            }
        } else if(page==Page.TARGETS) {
            if(online.isEmpty()) { AgentMenu.icon(view,4,Items.BARRIER,"No other players in this world");AgentMenu.description(view,4,"A live player must join the same world before you can propose targeting them."); }
            for(int i=pageIndex*45,slot=0;i<online.size() && slot<45;i++,slot++) {
                var player=online.get(i);targets.put(slot,new AdminStatsMenu.Session(player));
                AgentMenu.icon(view,slot,Items.PLAYER_HEAD,"Propose target: "+player.getName().getString());
                String reason=AgentCompanions.get(owner.getEntityWorld().getServer()).targetEligibility(owner,player);
                AgentMenu.description(view,slot,reason==null?"Select to queue a proposal. Review and both approvals are required.":"Unavailable: "+reason);
            }
            if(pageIndex>0)AgentMenu.icon(view,PREVIOUS,Items.ARROW,"Previous players");
            if(pageIndex+1<pages)AgentMenu.icon(view,NEXT,Items.ARROW,"Next players");
        } else if(page==Page.PENDING) {
            int slot=0;
            for(var entry:queue.data.proposals.entrySet())if(entry.getValue().owner.equals(owner.getUuidAsString())&&entry.getValue().active()) {
                proposals.put(slot,entry.getKey());AgentMenu.icon(view,slot,Items.PAPER,entry.getValue().state==AgentActions.State.OWNER_APPROVED?"Waiting for Codex review":"Needs your approval");
                AgentMenu.description(view,slot,entry.getValue().description()+" • ID: "+entry.getKey());slot++;
            }
            if(proposals.isEmpty())AgentMenu.icon(view,4,Items.BARRIER,"No pending orders");
        } else {
            AgentMenu.icon(view,4,Items.PAPER,"Review exact order");AgentMenu.description(view,4,selected.description()+" • ID: "+proposalId);
            AgentMenu.icon(view,APPROVE,Items.LIME_DYE,selected.state==AgentActions.State.PENDING?"Approve as owner":"Owner approved • Codex still required");
            AgentMenu.description(view,APPROVE,"This is only your approval. Ask Codex in this chat to review the displayed ID before expiry.");
            AgentMenu.icon(view,CANCEL,Items.RED_DYE,"Cancel this order");
        }
        AgentMenu.icon(view,BACK,Items.ARROW,"Back");
        var snapshot=selected;var state=selected==null?null:selected.state;
        owner.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inventory,who)->
            new Handler(sync,inventory,view,owner,page,pageIndex,proposalId,snapshot,state,targets,proposals),Text.literal("Helper Orders • "+page.name().toLowerCase(java.util.Locale.ROOT))));return 1;
    }
    static final class Handler extends GenericContainerScreenHandler {
        final ServerPlayerEntity owner;final AdminStatsMenu.Session session;final Page page;final int index;
        final String proposalId;final AgentActions.Proposal proposal;final AgentActions.State state;
        final String description;
        final Map<Integer,AdminStatsMenu.Session> targets;final Map<Integer,String> proposals;
        Handler(int sync,PlayerInventory inventory,SimpleInventory view,ServerPlayerEntity owner,Page page,int index,String id,
                AgentActions.Proposal proposal,AgentActions.State state,Map<Integer,AdminStatsMenu.Session> targets,Map<Integer,String> proposals) {
            super(ScreenHandlerType.GENERIC_9X6,sync,inventory,view,6);this.owner=owner;session=new AdminStatsMenu.Session(owner);
            this.page=page;this.index=index;proposalId=id;this.proposal=proposal;this.state=state;this.targets=Map.copyOf(targets);this.proposals=Map.copyOf(proposals);
            description=proposal==null?null:proposal.description();
        }
        @Override public boolean canUse(PlayerEntity player) { return player==owner&&owner.currentScreenHandler==this&&session.valid()&&AgentMenu.allowed(owner); }
        @Override public ItemStack quickMove(PlayerEntity player,int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot,int selected) {}
        private void next(Page page,int index,String id) { owner.closeHandledScreen();show(owner,page,index,id); }
        @Override public void onSlotClick(int slot,int button,SlotActionType action,PlayerEntity player) {
            if(player!=owner||owner.currentScreenHandler!=this)return;
            if(!canUse(player)) { owner.closeHandledScreen();return; }
            if((action!=SlotActionType.PICKUP&&action!=SlotActionType.QUICK_MOVE)||button<0||button>1||!getCursorStack().isEmpty()||slot<0||slot>=54) { sendContentUpdates();return; }
            var queue=AgentActions.get(owner.getEntityWorld().getServer());queue.expire();
            if(slot==BACK) { if(page==Page.MAIN) { owner.closeHandledScreen();AgentMenu.open(owner); } else next(Page.MAIN,0,null);return; }
            if(page==Page.MAIN) {
                if(slot==TARGET)next(Page.TARGETS,0,null);
                else if(slot==PENDING)next(Page.PENDING,0,null);
                else if(ACTIONS.containsKey(slot)) { queue.suggest(owner,ACTIONS.get(slot));next(Page.PENDING,0,null); }
            } else if(page==Page.TARGETS) {
                if((slot==PREVIOUS||slot==NEXT)&&!getSlot(slot).getStack().isEmpty())next(Page.TARGETS,index+(slot==NEXT?1:-1),null);
                else if(targets.containsKey(slot)) {
                    var target=targets.get(slot);
                    if(!target.valid()) { next(Page.TARGETS,index,null);return; }
                    queue.target(owner,target.player());next(Page.PENDING,0,null);
                }
            } else if(page==Page.PENDING && proposals.containsKey(slot))next(Page.REVIEW,0,proposals.get(slot));
            else if(page==Page.REVIEW && (slot==APPROVE||slot==CANCEL)) {
                if(queue.data.proposals.get(proposalId)!=proposal||!proposal.active()||proposal.state!=state||!proposal.owner.equals(owner.getUuidAsString())||!proposal.description().equals(description)) { next(Page.PENDING,0,null);return; }
                if(slot==CANCEL)queue.cancel(owner,proposalId);else if(state==AgentActions.State.PENDING)queue.ownerApprove(owner,proposalId);
                next(Page.PENDING,0,null);
            }
        }
    }
}

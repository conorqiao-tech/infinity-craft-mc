package dev.convergence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** Owner approvals are visible in-game; the separate console/Codex gate is unchanged. */
final class AgentOrdersMenu {
    enum Page { MAIN, TARGETS, PENDING, REVIEW }
    static final int TARGET=10,PENDING=12,APPROVE=11,CANCEL=15,BACK=49,PREVIOUS=45,NEXT=53;
    private static final Map<Integer,String> ACTIONS=Map.of(19,"set day",21,"set night",23,"clear weather",25,"make it rain",28,"save world",30,"list players");
    private AgentOrdersMenu() {}
    static int open(ServerPlayer owner) { return show(owner,Page.MAIN,0,null); }
    private static int show(ServerPlayer owner,Page page,int index,String proposalId) {
        if(!AgentMenu.allowed(owner)||owner.containerMenu!=owner.inventoryMenu||!owner.inventoryMenu.getCarried().isEmpty())return 0;
        var queue=AgentActions.get(owner.level().getServer());queue.expire();
        var view=new SimpleContainer(54);
        var targets=new LinkedHashMap<Integer,AdminStatsMenu.Session>();
        var proposals=new LinkedHashMap<Integer,String>();
        var selected=proposalId==null?null:queue.data.proposals.get(proposalId);
        if(page==Page.REVIEW && (selected==null||!selected.active()||!selected.owner.equals(owner.getStringUUID())))return show(owner,Page.PENDING,0,null);
        var online=owner.level().getServer().getPlayerList().getPlayers().stream()
            .filter(p->p!=owner && p.level()==owner.level()).sorted(java.util.Comparator.comparing(p->p.getName().getString())).toList();
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
                String reason=AgentCompanions.get(owner.level().getServer()).targetEligibility(owner,player);
                AgentMenu.description(view,slot,reason==null?"Select to queue a proposal. Review and both approvals are required.":"Unavailable: "+reason);
            }
            if(pageIndex>0)AgentMenu.icon(view,PREVIOUS,Items.ARROW,"Previous players");
            if(pageIndex+1<pages)AgentMenu.icon(view,NEXT,Items.ARROW,"Next players");
        } else if(page==Page.PENDING) {
            int slot=0;
            for(var entry:queue.data.proposals.entrySet())if(entry.getValue().owner.equals(owner.getStringUUID())&&entry.getValue().active()) {
                proposals.put(slot,entry.getKey());AgentMenu.icon(view,slot,Items.PAPER,entry.getValue().state==AgentActions.State.OWNER_APPROVED?"Waiting for Codex review":"Needs your approval");
                AgentMenu.description(view,slot,entry.getValue().description()+" • ID: "+entry.getKey());slot++;
            }
            if(proposals.isEmpty())AgentMenu.icon(view,4,Items.BARRIER,"No pending orders");
        } else {
            AgentMenu.icon(view,4,Items.PAPER,"Review exact order");AgentMenu.description(view,4,selected.description()+" • ID: "+proposalId);
            AgentMenu.icon(view,APPROVE,Items.DYE.lime(),selected.state==AgentActions.State.PENDING?"Approve as owner":"Owner approved • Codex still required");
            AgentMenu.description(view,APPROVE,"This is only your approval. Ask Codex in this chat to review the displayed ID before expiry.");
            AgentMenu.icon(view,CANCEL,Items.DYE.red(),"Cancel this order");
        }
        AgentMenu.icon(view,BACK,Items.ARROW,"Back");
        var snapshot=selected;var state=selected==null?null:selected.state;
        owner.openMenu(new SimpleMenuProvider((sync,inventory,who)->
            new Handler(sync,inventory,view,owner,page,pageIndex,proposalId,snapshot,state,targets,proposals),Component.literal("Helper Orders • "+page.name().toLowerCase(java.util.Locale.ROOT))));return 1;
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner;final AdminStatsMenu.Session session;final Page page;final int index;
        final String proposalId;final AgentActions.Proposal proposal;final AgentActions.State state;
        final String description;
        final Map<Integer,AdminStatsMenu.Session> targets;final Map<Integer,String> proposals;
        Handler(int sync,Inventory inventory,SimpleContainer view,ServerPlayer owner,Page page,int index,String id,
                AgentActions.Proposal proposal,AgentActions.State state,Map<Integer,AdminStatsMenu.Session> targets,Map<Integer,String> proposals) {
            super(MenuType.GENERIC_9x6,sync,inventory,view,6);this.owner=owner;session=new AdminStatsMenu.Session(owner);
            this.page=page;this.index=index;proposalId=id;this.proposal=proposal;this.state=state;this.targets=Map.copyOf(targets);this.proposals=Map.copyOf(proposals);
            description=proposal==null?null:proposal.description();
        }
        @Override public boolean stillValid(Player player) { return player==owner&&owner.containerMenu==this&&session.valid()&&AgentMenu.allowed(owner); }
        @Override public ItemStack quickMoveStack(Player player,int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot,int selected) {}
        private void next(Page page,int index,String id) { owner.closeContainer();show(owner,page,index,id); }
        @Override public void clicked(int slot,int button,ContainerInput action,Player player) {
            if(player!=owner||owner.containerMenu!=this)return;
            if(!stillValid(player)) { owner.closeContainer();return; }
            if((action!=ContainerInput.PICKUP&&action!=ContainerInput.QUICK_MOVE)||button<0||button>1||!getCarried().isEmpty()||slot<0||slot>=54) { broadcastChanges();return; }
            var queue=AgentActions.get(owner.level().getServer());queue.expire();
            if(slot==BACK) { if(page==Page.MAIN) { owner.closeContainer();AgentMenu.open(owner); } else next(Page.MAIN,0,null);return; }
            if(page==Page.MAIN) {
                if(slot==TARGET)next(Page.TARGETS,0,null);
                else if(slot==PENDING)next(Page.PENDING,0,null);
                else if(ACTIONS.containsKey(slot)) { queue.suggest(owner,ACTIONS.get(slot));next(Page.PENDING,0,null); }
            } else if(page==Page.TARGETS) {
                if((slot==PREVIOUS||slot==NEXT)&&!getSlot(slot).getItem().isEmpty())next(Page.TARGETS,index+(slot==NEXT?1:-1),null);
                else if(targets.containsKey(slot)) {
                    var target=targets.get(slot);
                    if(!target.valid()) { next(Page.TARGETS,index,null);return; }
                    queue.target(owner,target.player());next(Page.PENDING,0,null);
                }
            } else if(page==Page.PENDING && proposals.containsKey(slot))next(Page.REVIEW,0,proposals.get(slot));
            else if(page==Page.REVIEW && (slot==APPROVE||slot==CANCEL)) {
                if(queue.data.proposals.get(proposalId)!=proposal||!proposal.active()||proposal.state!=state||!proposal.owner.equals(owner.getStringUUID())||!proposal.description().equals(description)) { next(Page.PENDING,0,null);return; }
                if(slot==CANCEL)queue.cancel(owner,proposalId);else if(state==AgentActions.State.PENDING)queue.ownerApprove(owner,proposalId);
                next(Page.PENDING,0,null);
            }
        }
    }
}

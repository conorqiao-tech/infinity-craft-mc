package dev.convergence;

import java.io.IOException;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/** Reconciles exact, owner-approved staff applications with independently owned native permissions.
 * No payment, model, player chat, command block or remote source can approve an application here.
 * Memberships owns the durable source journal; the economy owns debits, approvals and refunds.
 */
public final class PurchasedAdminRoles {
    @FunctionalInterface interface OperatorWrite {void run() throws IOException;}
    private static final ThreadLocal<Integer> OWNED_WRITES=ThreadLocal.withInitial(()->0);
    final MinecraftServer server;
    final Memberships members;
    final InfiniEconomy economy;
    PurchasedAdminRoles(MinecraftServer server,Memberships members,InfiniEconomy economy){this.server=server;this.members=members;this.economy=economy;}

    static void ownedOperatorWrite(OperatorWrite write) throws IOException {
        int depth=OWNED_WRITES.get();OWNED_WRITES.set(depth+1);
        try{write.run();}finally{if(depth==0)OWNED_WRITES.remove();else OWNED_WRITES.set(depth);}
    }
    /** Native HEAD hook. Persist owner provenance before vanilla mutates its operator list. */
    public static void beforeNativeOp(PlayerList players,NameAndId target,Optional<LevelBasedPermissionSet> permissions,Optional<Boolean> bypass){
        if(OWNED_WRITES.get()>0)return;
        var members=Memberships.INSTANCES.get(players.getServer());if(members==null)return;
        if(!players.getServer().isSameThread())throw new IllegalStateException("Operator provenance must be changed on the server thread.");
        var level=permissions.orElse(players.getServer().operatorUserPermissions()).level().id();
        boolean bypasses=bypass.orElse(players.getOps().canBypassPlayerLimit(target));
        if(!members.beginExternalOperator(target,level,bypasses))throw new IllegalStateException("Operator change blocked: Admin provenance could not be saved safely.");
    }
    /** An explicit native deop also retires active role sources so periodic sync cannot undo it. */
    public static void beforeNativeDeop(PlayerList players,NameAndId target){
        if(OWNED_WRITES.get()>0)return;
        var members=Memberships.INSTANCES.get(players.getServer());if(members==null)return;
        if(!players.getServer().isSameThread())throw new IllegalStateException("Operator provenance must be changed on the server thread.");
        if(!members.beginExternalOperator(target,-1,false))throw new IllegalStateException("Deop blocked: Admin provenance could not be saved safely.");
    }
    public static void afterNativeOperatorChange(PlayerList players,NameAndId target){
        if(OWNED_WRITES.get()>0)return;
        var members=Memberships.INSTANCES.get(players.getServer());if(members==null)return;
        if(!players.getServer().isSameThread())throw new IllegalStateException("Operator provenance must be changed on the server thread.");
        if(!members.finishExternalOperator(target.id()))throw new IllegalStateException("Operator change awaits durable recovery. Check server storage before retrying.");
    }
    static PurchasedAdminRoles get(MinecraftServer server){return new PurchasedAdminRoles(server,Memberships.get(server),InfiniEconomy.get(server));}
    static boolean beforePlay(ServerPlayer player){
        var roles=get(player.level().getServer());
        return roles.members.duringAdminJoin(player,()->roles.reconcile(player.getUUID()));
    }
    static boolean reconcile(MinecraftServer server){return get(server).reconcileAll();}

    boolean reconcileAll(){
        if(!server.isSameThread())return false;
        var ids=new TreeSet<UUID>();var grouped=new HashMap<UUID,List<InfiniEconomy.StaffRequest>>();
        for(var id:members.data.accounts.keySet())ids.add(UUID.fromString(id));
        for(var request:economy.staffRequests()){ids.add(request.buyer());grouped.computeIfAbsent(request.buyer(),ignored->new ArrayList<>()).add(request);}
        boolean success=true;
        for(var id:ids)if(!reconcile(id,grouped.getOrDefault(id,List.of()))){
            success=false;var online=server.getPlayerList().getPlayer(id);
            if(online!=null)online.connection.disconnect(Component.literal("Admin permissions could not be verified and saved safely. Ask the host to check server storage."));
        }
        return success;
    }
    /** Join and periodic native reconciliation share this exact fail-closed path. */
    boolean reconcile(UUID id){return reconcile(id,economy.staffRequests().stream().filter(r->r.buyer().equals(id)).toList());}
    private boolean reconcile(UUID id,List<InfiniEconomy.StaffRequest> requests){
        if(!server.isSameThread()||!members.recoverExternalOperator(id))return false;
        if(!economy.available())return members.suspendMissingPaidSources(id,Set.of());
        var present=new HashSet<String>();
        for(var original:requests){
            var request=original;if(request.state()!=InfiniEconomy.StaffState.PENDING)present.add(request.id());
            if(economy.staffExpired(request)){
                if(!economy.expireStaff(request.id(),id).success())return false;
                request=economy.staffRequest(request.id());
            }
            switch(request.state()){
                case APPROVED->{
                    if(!members.purchasedAdmin(request,economy.staffEligible(request),false))return false;
                }
                case REVOKING->{
                    // Persist/remove only this source before the ledger can atomically return its cost.
                    if(!members.purchasedAdmin(request,false,true))return false;
                    if(!economy.completeStaffRevocation(request.id(),id).success())return false;
                }
                case DENIED,REVOKED,EXPIRED->{if(!members.purchasedAdmin(request,false,true))return false;}
                case PENDING->{/* A debit is neither approval nor a permission source. */}
            }
        }
        return members.suspendMissingPaidSources(id,present)&&members.syncAdminOperator(id);
    }
}

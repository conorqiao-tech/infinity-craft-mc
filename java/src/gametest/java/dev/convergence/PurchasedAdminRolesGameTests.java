package dev.convergence;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;

/** Exclusive fixtures exercise real native operators and durable files; no live wallets or worlds. */
public class PurchasedAdminRolesGameTests {
    @SuppressWarnings("unchecked")
    static Map<net.minecraft.server.MinecraftServer,InfiniEconomy> economyRegistry(){
        try{var field=InfiniEconomy.class.getDeclaredField("INSTANCES");field.setAccessible(true);return (Map<net.minecraft.server.MinecraftServer,InfiniEconomy>)field.get(null);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Exclusive fixture cannot isolate ledger registry",failure);}
    }
    final class Fixture implements AutoCloseable {
        final GameTestHelper c;final net.minecraft.server.MinecraftServer server;final Path directory,membersFile,configFile,ledgerFile;
        final AtomicLong now=new AtomicLong(1_800_000_000_000L);final List<ServerPlayer> players=new ArrayList<>();
        final Memberships previousMembers;final InfiniEconomy previousEconomy;
        Memberships members;InfiniEconomy economy;
        Fixture(GameTestHelper c){
            this.c=c;server=c.getLevel().getServer();
            try{directory=Files.createTempDirectory("purchased-admin-native-").toRealPath();}catch(IOException failure){throw new IllegalStateException(failure);}
            membersFile=directory.resolve("members.json");configFile=directory.resolve("config.json");ledgerFile=directory.resolve("ledger.json");
            previousMembers=Memberships.INSTANCES.get(server);previousEconomy=economyRegistry().get(server);
            try{reopen();c.assertTrue(economy.available(),"Canonical isolated test ledger must be available before any role flow: "+economy.status());}
            catch(RuntimeException|Error failure){close();throw failure;}
        }
        void reopen(){members=new Memberships(server,membersFile,configFile);economy=new InfiniEconomy(server,ledgerFile,now::get,InfiniEconomy::atomic);Memberships.INSTANCES.put(server,members);economyRegistry().put(server,economy);}
        PurchasedAdminRoles roles(){return new PurchasedAdminRoles(server,members,economy);}
        ServerPlayer player(String name){var p=new ModeGameTests().player(c,name);players.add(p);p.closeContainer();p.setGameMode(GameType.SURVIVAL);return p;}
        String pending(ServerPlayer p,String product){
            c.assertTrue(economy.available(),"Staff fixture ledger is available before funding: "+economy.status());
            c.assertTrue(economy.credit(p.getUUID(),"fund-"+UUID.randomUUID(),100_000).success(),"Exact funded credit succeeds");
            var quote=economy.quote(p,product);c.assertTrue(quote!=null,"Staff offer is available");
            c.assertTrue(economy.purchase(p,quote).success(),"Staff application debit is durable");return quote.nonce().toString();
        }
        void approve(ServerPlayer p,String id){c.assertTrue(economy.approveStaff(server.createCommandSourceStack(),id,p.getUUID()).success(),"Actual local console approves exact ID and UUID");}
        void expire(ServerPlayer p,String id){now.set(economy.staffRequest(id).expiresAt());c.assertTrue(roles().reconcile(p.getUUID()),"Due source reconciles safely");}
        void blockMembers()throws IOException{Files.delete(membersFile);Files.createDirectory(membersFile);Files.writeString(membersFile.resolve("block"),"blocked");}
        void restoreMembers(String json)throws IOException{Files.delete(membersFile.resolve("block"));Files.delete(membersFile);Files.writeString(membersFile,json);}
        ServerPlayer rejoin(ServerPlayer old){
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);new io.netty.channel.embedded.EmbeddedChannel(connection);
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(old.getGameProfile(),false);
            var p=new ServerPlayer(server,c.getLevel(),old.getGameProfile(),cookie.clientInformation());players.add(p);
            server.getPlayerList().placeNewPlayer(connection,p,cookie);
            p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());return p;
        }
        @Override public void close(){
            try {
                for(var p:players){
                    try{PurchasedAdminRoles.ownedOperatorWrite(()->server.getPlayerList().deop(OperatorGameTests.entry(p)));}
                    catch(IOException failure){throw new IllegalStateException(failure);}
                    if(server.getPlayerList().getPlayer(p.getUUID())==p)server.getPlayerList().remove(p);
                }
            } finally {
                if(previousMembers==null)Memberships.INSTANCES.remove(server);else Memberships.INSTANCES.put(server,previousMembers);
                if(previousEconomy==null)economyRegistry().remove(server);else economyRegistry().put(server,previousEconomy);
                try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}catch(IOException failure){throw new IllegalStateException(failure);}
            }
        }
    }
    @GameTest public void pendingDebitNeverGrantsAndPlayerCannotSelfApprove(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-pending");var id=f.pending(p,"admin_pass");
            c.assertTrue(f.roles().reconcile(p.getUUID()),"Unapproved request can reconcile");
            c.assertFalse(Memberships.operator(p)||f.members.account(p.getUUID()).admin,"Debit/PENDING grants neither OP nor Admin");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            c.assertFalse(f.economy.approveStaff(p.createCommandSourceStack(),id,p.getUUID()).success(),"Even native OP4 cannot replace actual local-console staff approval");
            c.assertValueEqual(f.economy.staffRequest(id).state(),InfiniEconomy.StaffState.PENDING,"Rejected self-approval leaves durable request pending");
        }c.succeed();
    }
    @GameTest public void expiryRestoresPriorLowerOpAndBypassAfterRestart(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-prior-op2");f.server.getPlayerList().op(OperatorGameTests.entry(p),Optional.of(LevelBasedPermissionSet.GAMEMASTER),Optional.of(true));
            String id=f.pending(p,"admin_pass");f.approve(p,id);c.assertTrue(f.roles().reconcile(p.getUUID()),"Approved source activates");
            c.assertTrue(Memberships.operator(p)&&f.members.account(p.getUUID()).adminOpOwned,"Paid source owns only its promotion above prior OP2");
            long generation=f.members.account(p.getUUID()).paidAdminSources.get(id).generation;f.reopen();f.expire(p,id);
            var restored=f.server.getPlayerList().getOps().get(OperatorGameTests.entry(p));
            c.assertValueEqual(restored.permissions(),LevelBasedPermissionSet.GAMEMASTER,"Expiry restores exact OP2 baseline");
            c.assertTrue(restored.getBypassesPlayerLimit(),"Original bypass flag survives restart and expiry");
            c.assertFalse(f.members.account(p.getUUID()).admin,"Expired paid source removes only Admin role");
            c.assertValueEqual(f.members.account(p.getUUID()).paidAdminSources.get(id).generation,generation,"Retired receipt keeps exact original grant generation");
            c.assertValueEqual(f.economy.staffRequest(id).state(),InfiniEconomy.StaffState.EXPIRED,"Ledger reaches durable expired state after role removal");
        }c.succeed();
    }
    @GameTest public void preexistingOwnerOpNeverBecomesPaidRoleProperty(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-prior-owner");OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            String id=f.pending(p,"admin_pass");f.approve(p,id);c.assertTrue(f.roles().reconcile(p.getUUID()),"Paid badge can activate alongside preexisting owner OP");
            c.assertFalse(f.members.account(p.getUUID()).adminOpOwned,"Preexisting OP4 is not claimed by purchase");f.reopen();f.expire(p,id);
            c.assertTrue(Memberships.operator(p),"Paid expiry preserves independently owned native OP4");
            c.assertFalse(f.members.account(p.getUUID()).admin,"Paid Admin source itself still expires");
        }c.succeed();
    }
    @GameTest public void laterFreeAdminGrantSurvivesPurchasedExpiryAndReload(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-later-free");String id=f.pending(p,"admin_pass");f.approve(p,id);f.roles().reconcile(p.getUUID());
            long paidGeneration=f.members.account(p.getUUID()).paidAdminSources.get(id).generation;
            c.assertTrue(f.members.grantAdmin(p.getUUID()),"Later private-code/owner source is persisted independently");
            c.assertTrue(f.members.account(p.getUUID()).adminGeneration>paidGeneration,"Later grant has a newer durable generation");f.reopen();f.expire(p,id);
            c.assertTrue(f.members.account(p.getUUID()).freeAdmin&&f.members.account(p.getUUID()).admin&&Memberships.operator(p),"Expiry cannot erase later independent free Admin or its native permissions");
            c.assertTrue(f.members.revokeAdmin(p.getUUID())&&!Memberships.operator(p),"Explicit owner role revoke can remove the remaining owned promotion");
        }c.succeed();
    }
    @GameTest public void laterRawNativeOpIsDurablyIndependentAcrossReload(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-manual-owner");String id=f.pending(p,"admin_pass");f.approve(p,id);f.roles().reconcile(p.getUUID());
            c.assertTrue(f.members.account(p.getUUID()).adminOpOwned,"Paid role initially owns its native promotion");
            OperatorGameTests.level(p,LevelBasedPermissionSet.OWNER);
            c.assertFalse(f.members.account(p.getUUID()).adminOpOwned||f.members.account(p.getUUID()).externalOpPending,"Raw owner op durably relinquishes paid promotion ownership");
            f.reopen();f.expire(p,id);
            c.assertTrue(Memberships.operator(p),"A later native owner grant survives process-style reload then purchase expiry");
            c.assertFalse(f.members.account(p.getUUID()).admin,"Independent OP does not fabricate a permanent Admin source");
        }c.succeed();
    }
    @GameTest public void nativeDeopRetiresPaidSourceInsteadOfBeingUndoneBySync(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-owner-deop");String id=f.pending(p,"admin_lifetime");f.approve(p,id);f.roles().reconcile(p.getUUID());
            OperatorGameTests.deop(p);f.reopen();c.assertTrue(f.roles().reconcile(p.getUUID()),"Paid sync respects saved native deop intent");
            c.assertFalse(Memberships.operator(p)||f.members.account(p.getUUID()).admin,"Lifetime purchase cannot undo explicit native owner deop");
            c.assertTrue(f.members.account(p.getUUID()).paidAdminSources.get(id).retired,"Exact source is durably tombstoned");
        }c.succeed();
    }
    @GameTest public void approvalWhileOfflineWaitsForJoinAndExpiredOfflineCannotRegrant(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-offline");String id=f.pending(p,"admin_pass");f.server.getPlayerList().remove(p);f.approve(p,id);
            c.assertTrue(f.roles().reconcile(p.getUUID()),"Offline approval saves desired entitlement");
            c.assertTrue(f.server.getPlayerList().getOps().get(OperatorGameTests.entry(p))==null,"Offline approval does not blindly write native OP");
            f.reopen();var joined=f.rejoin(p);c.assertTrue(Memberships.operator(joined),"Actual JOIN gate activates approved source before play");
            c.assertTrue(f.members.joiningAdminTargets.isEmpty(),"Actual JOIN clears its temporary exact-player scope before returning");
            f.server.getPlayerList().remove(joined);f.now.set(f.economy.staffRequest(id).expiresAt());f.reopen();var returned=f.rejoin(joined);
            c.assertFalse(Memberships.operator(returned)||f.members.account(p.getUUID()).admin,"Actual JOIN gate expires offline paid role before gameplay");
        }c.succeed();
    }
    @GameTest public void joinPermissionScopeRejectsForeignAndReplacedSessionsAndClearsAfterFailure(GameTestHelper c){
        try(var f=new Fixture(c)){
            var current=f.player("staff-join-scope");String id=f.pending(current,"admin_pass");f.approve(current,id);
            var cookie=net.minecraft.server.network.CommonListenerCookie.createInitial(current.getGameProfile(),false);
            var foreign=new ServerPlayer(f.server,c.getLevel(),current.getGameProfile(),cookie.clientInformation());
            foreign.connection=current.connection;
            c.assertFalse(PurchasedAdminRoles.beforePlay(foreign),"A foreign entity cannot borrow the current handler to activate paid Admin");
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            var channel=new io.netty.channel.embedded.EmbeddedChannel(connection);
            try{
                foreign.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(f.server,connection,foreign,cookie);
                c.assertFalse(PurchasedAdminRoles.beforePlay(foreign),"A new handler cannot replace another published session inside the JOIN permission scope");
                c.assertFalse(Memberships.operator(current)||f.members.account(current.getUUID()).admin,"Rejected sessions cannot promote the genuine current player");
                c.assertTrue(f.members.joiningAdminTargets.isEmpty(),"Rejected sessions leave no joining-player authority behind");
                boolean failed=false;
                try{f.members.duringAdminJoin(current,()->{throw new IllegalStateException("injected JOIN body failure");});}
                catch(IllegalStateException expected){failed=true;}
                c.assertTrue(failed&&f.members.joiningAdminTargets.isEmpty(),"Even an exceptional JOIN body clears its exact-player authority in finally");
            }finally{channel.finishAndReleaseAll();}
        }c.succeed();
    }
    @GameTest public void crashAfterRoleRemovalBeforeRefundCompletesExactlyOnce(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-refund-crash");String id=f.pending(p,"admin_pass");f.approve(p,id);f.roles().reconcile(p.getUUID());long before=f.economy.balance(p.getUUID());
            c.assertTrue(f.economy.revokeStaff(f.server.createCommandSourceStack(),id,p.getUUID()).success(),"Owner saves revoking intent first");
            c.assertTrue(f.members.purchasedAdmin(f.economy.staffRequest(id),false,true),"Native role source is removed before financial acknowledgment");
            c.assertFalse(Memberships.operator(p),"Role removal has occurred");c.assertValueEqual(f.economy.balance(p.getUUID()),before,"Refund is still pending before acknowledgement");
            f.reopen();c.assertTrue(f.roles().reconcile(p.getUUID()),"Crash-style reload retries completion from REVOKING");
            long expected=before+f.economy.staffRequest(id).units();c.assertValueEqual(f.economy.balance(p.getUUID()),expected,"Exact original cost is returned once");
            f.reopen();f.roles().reconcile(p.getUUID());c.assertValueEqual(f.economy.balance(p.getUUID()),expected,"Repeated recovery cannot create a duplicate refund");
        }c.succeed();
    }
    @GameTest public void crashBeforeNativeOwnerWriteReplaysJournalBeforeExpiry(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-owner-journal");String id=f.pending(p,"admin_pass");f.approve(p,id);f.roles().reconcile(p.getUUID());
            // Simulate process loss precisely between the native HEAD journal and the native write.
            PurchasedAdminRoles.beforeNativeOp(f.server.getPlayerList(),OperatorGameTests.entry(p),Optional.of(LevelBasedPermissionSet.OWNER),Optional.of(true));
            c.assertTrue(f.members.account(p.getUUID()).externalOpPending,"Exact external owner intent is durable before native mutation");
            f.reopen();f.expire(p,id);var current=f.server.getPlayerList().getOps().get(OperatorGameTests.entry(p));
            c.assertTrue(Memberships.operator(p)&&current.getBypassesPlayerLimit(),"Recovery executes the exact independent OP4+bypass owner intent");
            c.assertFalse(f.members.account(p.getUUID()).externalOpPending||f.members.account(p.getUUID()).admin,"Journal completes and expiry only removes purchased role");
        }c.succeed();
    }
    @GameTest public void crashBeforeNativeDeopCannotBeUndoneByPaidLifetime(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("staff-deop-journal");String id=f.pending(p,"admin_lifetime");f.approve(p,id);f.roles().reconcile(p.getUUID());
            PurchasedAdminRoles.beforeNativeDeop(f.server.getPlayerList(),OperatorGameTests.entry(p));
            c.assertTrue(Memberships.operator(p),"Native mutation has not happened at crash point");f.reopen();f.roles().reconcile(p.getUUID());
            c.assertFalse(Memberships.operator(p)||f.members.account(p.getUUID()).admin,"Recovered deop wins over still-approved financial receipt");
            c.assertFalse(f.members.account(p.getUUID()).externalOpPending,"Native deop journal finishes durably");
        }c.succeed();
    }
    @GameTest public void provenanceStorageFailureBlocksNativeOwnerMutation(GameTestHelper c)throws Exception{
        try(var f=new Fixture(c)){
            var p=f.player("staff-storage-block");String id=f.pending(p,"admin_pass");f.approve(p,id);f.roles().reconcile(p.getUUID());
            var entry=f.server.getPlayerList().getOps().get(OperatorGameTests.entry(p));String original=Files.readString(f.membersFile);f.blockMembers();boolean rejected=false;
            try{OperatorGameTests.level(p,LevelBasedPermissionSet.GAMEMASTER);}catch(IllegalStateException expected){rejected=true;}
            c.assertTrue(rejected,"Unpersistable native owner provenance rejects before operator mutation");
            c.assertTrue(f.server.getPlayerList().getOps().get(OperatorGameTests.entry(p))==entry,"Failed native op leaves exact original entry unchanged");
            c.assertTrue(f.members.account(p.getUUID()).adminOpOwned,"Failed provenance does not silently relinquish paid ownership");
            f.restoreMembers(original);f.expire(p,id);c.assertFalse(Memberships.operator(p),"After repair expiry still removes the owned paid promotion");
        }c.succeed();
    }
    @GameTest public void unreadableLedgerSuspendsOnlyPaidSourcesAndLaterRestoresApproval(GameTestHelper c)throws Exception{
        try(var f=new Fixture(c)){
            var p=f.player("staff-ledger-corrupt");String id=f.pending(p,"admin_lifetime");f.approve(p,id);f.roles().reconcile(p.getUUID());String ledger=Files.readString(f.ledgerFile);
            Files.writeString(f.ledgerFile,"not a ledger");f.reopen();c.assertFalse(f.economy.available(),"Corrupt ledger is explicitly unavailable, never silently reset");
            c.assertTrue(f.roles().reconcile(p.getUUID()),"Unavailable ledger safely suspends paid source");c.assertFalse(Memberships.operator(p)||f.members.account(p.getUUID()).admin,"Unverifiable paid approval cannot retain native privileges");
            Files.writeString(f.ledgerFile,ledger);f.reopen();f.roles().reconcile(p.getUUID());c.assertTrue(Memberships.operator(p),"Restored exact valid approval can resume its suspended source");
            f.members.grantAdmin(p.getUUID());Files.writeString(f.ledgerFile,"broken again");f.reopen();f.roles().reconcile(p.getUUID());
            c.assertTrue(Memberships.operator(p)&&f.members.account(p.getUUID()).freeAdmin,"Ledger suspension cannot erase independent free/manual Admin");
        }c.succeed();
    }
}

package dev.convergence;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;

/** Isolated native sessions and real durable ledgers; no live wallets, HTTP or payment provider. */
public class InfiniEconomyGameTests {
    final class Fixture implements AutoCloseable {
        final GameTestHelper context;final Path directory,file;final AtomicLong now=new AtomicLong(1_800_000_000_000L);final List<ServerPlayer> players=new ArrayList<>();
        InfiniEconomy economy;
        Fixture(GameTestHelper context){this.context=context;try{directory=Files.createTempDirectory("infini-native-").toRealPath();file=directory.resolve("ledger.json");}catch(IOException failure){throw new IllegalStateException(failure);}economy=reopen();context.assertTrue(economy.available(),"Fresh canonical isolated ledger must be available: "+economy.status());}
        InfiniEconomy reopen(){economy=new InfiniEconomy(context.getLevel().getServer(),file,now::get,InfiniEconomy::atomic);return economy;}
        ServerPlayer player(String name){var p=new ModeGameTests().player(context,name);players.add(p);p.closeContainer();p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();return p;}
        void reply(Form form,String text){try{org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form,text);}catch(Exception failure){throw new IllegalStateException(failure);}}
        @Override public void close(){for(var p:players){InfiniShopMenu.ACTIVE.remove(p.getUUID());InfiniShopMenu.DELIVERIES.remove(p.getUUID());p.closeContainer();p.level().getServer().getPlayerList().remove(p);}try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}catch(IOException failure){throw new IllegalStateException(failure);}}
    }
    @GameTest public void offlineCreditReopensAndRejectsChangedReplay(GameTestHelper c){
        try(var f=new Fixture(c)){
            UUID id=UUID.randomUUID(),foreign=UUID.randomUUID();var first=f.economy.credit(id,"owner-delivery-1",1250);
            c.assertTrue(first.success()&&first.changed(),"Offline UUID receives one durable fractional credit");
            c.assertValueEqual(f.reopen().balanceText(id),"12.5","Integer hundredths reopen without rounding");
            var repeated=f.economy.credit(id,"owner-delivery-1",1250);c.assertTrue(repeated.success()&&!repeated.changed(),"Exact delivery retry is an idempotent success");
            c.assertFalse(f.economy.credit(id,"owner-delivery-1",1251).success(),"Changed amount cannot replay the same delivery");
            c.assertFalse(f.economy.credit(foreign,"owner-delivery-1",1250).success(),"Changed buyer cannot reuse a global event ID");
            c.assertValueEqual(f.economy.balance(id),1250L,"Only the original delivery remains");c.assertValueEqual(f.economy.balance(foreign),0L,"Foreign UUID receives nothing");
        }c.succeed();
    }
    @GameTest public void milestoneRewardsAreOncePerPlayerAndUseWholeInfini(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-milestone");var other=f.player("infini-other");
            c.assertTrue(f.economy.rewardFor(p,"astral:first_ruin",3).success(),"Trusted first milestone grants three whole Infini");
            c.assertTrue(f.economy.rewardFor(other,"astral:first_ruin",3).changed(),"Another authenticated UUID can earn the same milestone");
            f.reopen();c.assertFalse(f.economy.rewardFor(p,"astral:first_ruin",3).changed(),"Reconnect retry cannot duplicate progression currency");
            c.assertFalse(f.economy.rewardFor(p,"astral:first_ruin",4).success(),"A changed reward payload is refused instead of silently overpaying");
            c.assertValueEqual(f.economy.balance(p.getUUID()),300L,"Whole reward amount is converted exactly to hundredths");
            c.assertFalse(f.economy.rewardFor(p,"bad/id",3).success(),"Unstable/unapproved event syntax is rejected");
        }c.succeed();
    }
    @GameTest public void nativeJavaReviewDebitsOnceAndDoesNotChangeRankOrInventory(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-java");p.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));var oldRank=Memberships.get(p.level().getServer()).permanentTier(p.getUUID());boolean oldAdmin=Memberships.get(p.level().getServer()).account(p.getUUID()).admin;
            f.economy.credit(p.getUUID(),"java-start",2000);InfiniShopMenu.open(p,f.economy,null);
            var menu=(InfiniShopMenu.Handler)p.containerMenu;menu.clicked(14,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(((InfiniShopMenu.Handler)p.containerMenu).view.title(),"Confirm exact Infini purchase","A real chest click opens exact permanent Go review");
            c.assertValueEqual(f.economy.balance(p.getUUID()),2000L,"Preview does not charge the wallet");
            var review=(InfiniShopMenu.Handler)p.containerMenu;review.clicked(10,0,ContainerInput.PICKUP,p);review.clicked(10,0,ContainerInput.PICKUP,p);
            c.assertValueEqual(f.economy.balance(p.getUUID()),750L,"12.5 Infini is charged exactly once");
            c.assertValueEqual(f.reopen().activeSupporter(p.getUUID()),InfiniEconomy.Supporter.GO,"Purchase and wallet reopen together");
            c.assertValueEqual(p.getInventory().getItem(0).getCount(),7,"Readonly menu never debits or gives a real inventory item");
            c.assertValueEqual(Memberships.get(p.level().getServer()).permanentTier(p.getUUID()),oldRank,"Supporter purchase leaves earned rank intact");
            c.assertValueEqual(Memberships.get(p.level().getServer()).account(p.getUUID()).admin,oldAdmin,"Cosmetic purchase cannot promote Admin");
            p.closeContainer();c.assertTrue(f.economy.quote(p,"go_pass")==null,"A permanent owner cannot accidentally pay for the same lower temporary tier");
            f.economy.credit(p.getUUID(),"staff-funds",25000);long beforeStaff=f.economy.balance(p.getUUID());
            var staffQuote=f.economy.quote(p,"admin_pass");c.assertTrue(f.economy.purchase(p,staffQuote).success(),"ADMIN price creates a real funded staff request");String requestId=staffQuote.nonce().toString();
            c.assertValueEqual(f.economy.staffRequest(requestId).state(),InfiniEconomy.StaffState.PENDING,"Purchase alone persists PENDING, never APPROVED");
            c.assertValueEqual(Memberships.get(p.level().getServer()).account(p.getUUID()).admin,oldAdmin,"Funding a staff request does not grant native permissions");
            c.assertFalse(f.economy.approveStaff(p.createCommandSourceStack(),requestId,p.getUUID()).success(),"In-game player cannot approve a staff purchase");
            c.assertTrue(f.economy.denyStaff(p.level().getServer().createCommandSourceStack(),requestId,p.getUUID()).success(),"Actual owner console can deny the exact application");
            c.assertValueEqual(f.economy.balance(p.getUUID()),beforeStaff-12500,"Refund waits for role-removal acknowledgement");
            c.assertTrue(f.economy.completeStaffRevocation(requestId,p.getUUID()).success(),"Ungrantable pending application safely completes and returns its cost");
            c.assertValueEqual(f.economy.balance(p.getUUID()),beforeStaff,"Exact original staff cost returns atomically");
            c.assertFalse(f.economy.completeStaffRevocation(requestId,p.getUUID()).changed(),"Replayed role acknowledgement cannot refund again");

        }c.succeed();
    }
    @GameTest public void changedWalletModeAndForeignActorInvalidateQuotes(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-quote");var other=f.player("infini-foreign");f.economy.credit(p.getUUID(),"quote-credit",3000);
            var quote=f.economy.quote(p,"go_pass");f.economy.credit(p.getUUID(),"wallet-change",100);
            c.assertFalse(f.economy.purchase(p,quote).success(),"Any intervening wallet mutation cancels exact offer");
            quote=f.economy.quote(p,"go_pass");p.setGameMode(GameType.CREATIVE);c.assertFalse(f.economy.purchase(p,quote).success(),"Changing native mode cancels an open offer");p.setGameMode(GameType.SURVIVAL);
            quote=f.economy.quote(p,"go_pass");c.assertFalse(f.economy.purchase(other,quote).success(),"A foreign player cannot confirm the owner offer");
            c.assertTrue(f.economy.purchase(p,quote).success(),"Foreign attempt does not consume the original player's valid review");
            c.assertValueEqual(f.economy.balance(p.getUUID()),2350L,"Only one valid confirmation debits currency");
        }c.succeed();
    }
    @GameTest public void spentCreditRefundHoldsCosmeticsWithoutTakingUnrelatedFunds(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-refund");UUID id=p.getUUID();f.economy.credit(id,"a-original",1000);f.economy.credit(id,"b-other",500);
            c.assertTrue(f.economy.purchase(p,f.economy.quote(p,"go_pass")).success(),"Buy a cosmetic pass from the original credit");
            c.assertTrue(f.economy.refund(id,"refund-1","a-original",1000).success(),"Refund atomically removes the original lot and records spent debt");
            c.assertValueEqual(f.economy.balance(id),500L,"The unrelated credit lot is preserved");c.assertValueEqual(f.economy.debt(id),750L,"Spent refunded amount becomes a hold, not a negative spendable balance");
            c.assertValueEqual(f.economy.activeSupporter(id),InfiniEconomy.Supporter.NONE,"Refunded spending does not retain active purchased benefits while unpaid");
            c.assertTrue(f.economy.quote(p,"go_pass")==null,"Outstanding hold blocks new purchases");
            c.assertFalse(f.economy.refund(id,"refund-1","a-original",1000).changed(),"Refund replay cannot create more debt");
            c.assertFalse(f.economy.refund(id,"refund-2","a-original",1).success(),"Refunds cannot exceed the original delivery");
            f.economy.credit(id,"repayment",750);f.reopen();c.assertValueEqual(f.economy.debt(id),0L,"New credit first settles the hold durably");c.assertValueEqual(f.economy.balance(id),500L,"Debt repayment creates no extra spendable currency");
            c.assertValueEqual(f.economy.activeSupporter(id),InfiniEconomy.Supporter.GO,"Repaid cosmetic pass returns for its original term");
        }c.succeed();
    }
    @GameTest public void passesExtendFromExactExpiryAndPermanentTitlesSurviveTime(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-expiry");UUID id=p.getUUID();f.economy.credit(id,"expiry-credit",10000);
            var first=f.economy.quote(p,"go_pass");long firstExpiry=first.expires();f.economy.purchase(p,first);
            f.now.addAndGet(1000);var second=f.economy.quote(p,"go_pass");c.assertValueEqual(second.expires(),firstExpiry+InfiniEconomy.PASS_MILLIS,"Second pass extends the already-paid period instead of losing remaining time");f.economy.purchase(p,second);
            f.now.set(firstExpiry+InfiniEconomy.PASS_MILLIS);c.assertValueEqual(f.economy.activeSupporter(id),InfiniEconomy.Supporter.NONE,"Pass ends at the exact expiry without automatic billing");
            f.economy.purchase(p,f.economy.quote(p,"plus_lifetime"));f.now.addAndGet(InfiniEconomy.PASS_MILLIS*100);f.reopen();
            c.assertValueEqual(f.economy.activeSupporter(id),InfiniEconomy.Supporter.PLUS,"Permanent cosmetic grant survives reconnect and elapsed months");
            c.assertTrue(f.economy.ownedStyle(id,"supporter_go"),"Higher cosmetic tiers include lower styles");f.economy.selectStyle(p,"supporter_go");c.assertValueEqual(f.economy.supporterTitle(id),"Go Supporter","Display choice uses the actual owned cosmetic style");
        }c.succeed();
    }
    @GameTest public void failedWriteCannotPartiallyDebitOrGrant(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-io");UUID id=p.getUUID();f.economy.credit(id,"io-credit",2000);
            var failing=new InfiniEconomy(c.getLevel().getServer(),f.file,f.now::get,(path,bytes)->{if(path.equals(f.file))throw new IOException("injected before primary replace");InfiniEconomy.atomic(path,bytes);});
            c.assertFalse(failing.purchase(p,failing.quote(p,"go_lifetime")).success(),"Failed primary write reports failure");
            c.assertFalse(failing.available(),"Ambiguous/failed storage pauses further mutations");c.assertValueEqual(failing.balance(id),2000L,"Failed save does not publish a balance debit in memory");
            f.reopen();c.assertValueEqual(f.economy.balance(id),2000L,"Authoritative disk retains the full original balance");c.assertValueEqual(f.economy.activeSupporter(id),InfiniEconomy.Supporter.NONE,"No partial entitlement is created");
            c.assertTrue(f.economy.purchase(p,f.economy.quote(p,"go_lifetime")).success(),"A reviewed recovery can make a fresh complete purchase");
        }c.succeed();
    }
    @GameTest public void committedButInterruptedReceiptReopensWithoutDuplicateCurrency(GameTestHelper c){
        try(var f=new Fixture(c)){
            UUID id=UUID.randomUUID();var interrupted=new InfiniEconomy(c.getLevel().getServer(),f.file,f.now::get,(path,bytes)->{InfiniEconomy.atomic(path,bytes);if(path.equals(f.file))throw new IOException("injected after atomic replace");});
            c.assertFalse(interrupted.credit(id,"interrupted",1250).success(),"Interrupted acknowledgement is never presented as a definite success");
            c.assertFalse(interrupted.available(),"Session cannot continue using stale in-memory state");f.reopen();
            c.assertValueEqual(f.economy.balance(id),1250L,"Committed receipt is recovered from the authoritative file");var retry=f.economy.credit(id,"interrupted",1250);c.assertTrue(retry.success()&&!retry.changed(),"Retry acknowledges the recovered receipt without duplicating currency");
        }c.succeed();
    }
    @GameTest public void corruptStorageAndNonConsoleSourcesFailClosed(GameTestHelper c){
        try(var f=new Fixture(c)){
            try{Files.writeString(f.file,"{\"format\":1,\"wallets\":{\"bad-uuid\":{}},\"events\":{}}");}catch(IOException failure){throw new IllegalStateException(failure);}f.reopen();
            c.assertFalse(f.economy.available(),"Corrupt ledger is refused rather than reset to zero");c.assertFalse(f.economy.credit(UUID.randomUUID(),"do-not-reset",100).success(),"Corruption blocks even a new owner's delivery");
            try{c.assertTrue(Files.readString(f.file).contains("bad-uuid"),"Original corrupt bytes remain available for recovery");}catch(IOException failure){throw new IllegalStateException(failure);}
            var p=f.player("infini-console");var console=c.getLevel().getServer().createCommandSourceStack();
            c.assertTrue(AgentActions.localConsole(console),"The actual local server console is trusted");
            for(var source:List.of(p.createCommandSourceStack(),console.withSource(CommandSource.NULL),console.withSuppressedOutput(),c.getLevel().getServer().getFunctions().getGameLoopSender()))c.assertFalse(AgentActions.localConsole(source),"Player, remote, silent and scheduled sources cannot mint currency");
            c.assertValueEqual(InfiniEconomy.parse("7.50"),750L,"Fractional price parses in exact hundredths");
            for(String bad:List.of("7.501","NaN","1e99","-1","999999999999999999999999999"))c.assertValueEqual(InfiniEconomy.parse(bad),-1L,"Malformed, fractional-cent or excessive values are refused");
        }c.succeed();
    }
    @GameTest public void encodedBedrockConfirmRejectsOldFormsAndReplays(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-form");UUID id=p.getUUID();f.economy.credit(id,"form-credit",2000);
            c.assertValueEqual(InfiniShopMenu.form(p,f.economy,null,form->{throw new IllegalStateException("injected native transport failure");}),0,"A native form transport exception refuses delivery without crashing the server");
            c.assertFalse(InfiniShopMenu.ACTIVE.containsKey(id)||InfiniShopMenu.DELIVERIES.containsKey(id),"A failed delivery leaves no spending authority or delayed form");
            c.assertValueEqual(f.economy.balance(id),2000L,"Failed delivery never spends Infini");
            Form[] old=new Form[1],current=new Form[1];var quote=f.economy.quote(p,"go_pass");
            InfiniShopMenu.form(p,f.economy,quote,form->{old[0]=form;return true;});
            InfiniShopMenu.form(p,f.economy,quote,form->{current[0]=form;return true;});
            f.reply(old[0],"0");c.assertValueEqual(f.economy.balance(id),2000L,"An older native form cannot spend after replacement");
            f.reply(current[0],"0");c.assertValueEqual(f.economy.balance(id),1250L,"Current encoded native confirm purchases the exact reviewed 7.5 Infini pass");
            p.closeContainer();f.reply(current[0],"0");c.assertValueEqual(f.economy.balance(id),1250L,"Replaying encoded confirm cannot debit twice");
            quote=f.economy.quote(p,"plus_pass");c.assertTrue(quote==null,"Insufficient balance refuses a new review, without client-side pricing assumptions");
            f.economy.credit(id,"more-funds",1000);quote=f.economy.quote(p,"go_pass");Form[] changed=new Form[1];InfiniShopMenu.form(p,f.economy,quote,form->{changed[0]=form;return true;});f.economy.credit(id,"changed-funds",1);
            f.reply(changed[0],"0");c.assertValueEqual(f.economy.balance(id),2251L,"Wallet mutation invalidates a native form confirmation");
        }c.succeed();
    }
    @GameTest public void cancelledStaffReturnsKeepSplitSourcesAcrossPartialRefunds(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-source-return");UUID id=p.getUUID();
            f.economy.credit(id,"split-a",8000);f.economy.credit(id,"split-b",4500);f.economy.credit(id,"z-unrelated",1700);
            var quote=f.economy.quote(p,"admin_pass");c.assertTrue(f.economy.purchase(p,quote).success(),"Staff purchase consumes exact split funding roots");String request=quote.nonce().toString();
            c.assertTrue(f.economy.cancelStaff(p,request).success(),"Buyer cancels own still-pending application");c.assertTrue(f.economy.completeStaffRevocation(request,id).success(),"Never-granted role safely returns original sources");
            c.assertValueEqual(f.economy.balance(id),14200L,"Cancellation restores exact purchase cost plus untouched cash");
            c.assertTrue(f.economy.refund(id,"split-refund-a","split-a",3000).success(),"Partial refund reclaims its linked unspent return");
            c.assertTrue(f.economy.refund(id,"split-refund-b","split-b",2000).success(),"Second root reclaims only its own linked return");
            c.assertValueEqual(f.economy.balance(id),9200L,"Both partial chargebacks remove exact returned funds");c.assertValueEqual(f.economy.debt(id),0L,"Unspent returned currency never becomes false spent debt");
            c.assertFalse(f.economy.refund(id,"split-refund-a","split-a",3000).changed(),"Partial refund retry cannot reclaim twice");
            f.economy.refund(id,"split-refund-a-rest","split-a",5000);f.economy.refund(id,"split-refund-b-rest","split-b",2500);f.reopen();
            c.assertValueEqual(f.economy.balance(id),1700L,"Unrelated credit survives every original-root refund");c.assertValueEqual(f.economy.debt(id),0L,"No hold remains after reclaiming all original unspent returns");
            c.assertFalse(f.economy.completeStaffRevocation(request,id).changed(),"Repeated adapter acknowledgement cannot resurrect refunded sources");
        }c.succeed();
    }
    @GameTest public void refundBeforeCancellationTracksRepaymentsAndSpentReturns(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-source-debt");UUID id=p.getUUID();f.economy.credit(id,"ordering-a",12500);
            var quote=f.economy.quote(p,"admin_pass");f.economy.purchase(p,quote);String request=quote.nonce().toString();
            f.economy.refund(id,"ordering-first-refund","ordering-a",5000);c.assertValueEqual(f.economy.debt(id),5000L,"Refund of a still-spent root creates real debt");
            f.economy.credit(id,"ordering-b",3000);c.assertValueEqual(f.economy.debt(id),2000L,"Replacement credit repays only its exact amount");
            f.economy.cancelStaff(p,request);c.assertTrue(f.economy.completeStaffRevocation(request,id).success(),"Cancellation first extinguishes remaining original debt and recovers replacement funding");
            c.assertValueEqual(f.economy.balance(id),10500L,"Return equals unrefunded original funding plus actual repayment credit");c.assertValueEqual(f.economy.debt(id),0L,"Original unpaid debt was cancelled, not credited as free cash");
            f.economy.refund(id,"ordering-b-partial","ordering-b",1500);f.economy.refund(id,"ordering-a-rest","ordering-a",7500);
            c.assertValueEqual(f.economy.balance(id),1500L,"Later chargebacks trace returned replacement funds as well as original funds");c.assertValueEqual(f.economy.debt(id),0L,"Unspent replacement funding is reclaimed without false debt");
            c.assertTrue(f.economy.purchase(p,f.economy.quote(p,"go_pass")).success(),"A returned funding root can be spent exactly once on a real cosmetic");
            f.economy.refund(id,"ordering-b-rest","ordering-b",1500);c.assertValueEqual(f.economy.balance(id),0L,"Last chargeback reclaims the remaining unspent return");c.assertValueEqual(f.economy.debt(id),750L,"Only the newly spent return becomes a hold");
            f.economy.credit(id,"ordering-c",750);f.reopen();c.assertValueEqual(f.economy.debt(id),0L,"New repayment durably clears the actual spent hold");c.assertValueEqual(f.economy.balance(id),0L,"Repayment cannot duplicate the returned currency");
            c.assertFalse(f.economy.refund(id,"ordering-b-rest","ordering-b",1500).changed(),"Spent-return chargeback replay is stable after reopen");
            var other=f.player("infini-full-repay");UUID otherId=other.getUUID();f.economy.credit(otherId,"fully-a",12500);var full=f.economy.quote(other,"admin_pass");f.economy.purchase(other,full);
            f.economy.refund(otherId,"fully-a-refund","fully-a",12500);f.economy.credit(otherId,"fully-b",12500);f.economy.cancelStaff(other,full.nonce().toString());f.economy.completeStaffRevocation(full.nonce().toString(),otherId);
            c.assertValueEqual(f.economy.balance(otherId),12500L,"Fully settled original chargeback returns the replacement payer's actual funds");
            f.economy.refund(otherId,"fully-b-refund","fully-b",12500);c.assertValueEqual(f.economy.balance(otherId),0L,"Refunding replacement payer reclaims the still-unspent return");c.assertValueEqual(f.economy.debt(otherId),0L,"Both operation orderings conserve funds without new-payment holds");
        }c.succeed();
    }

    @GameTest public void missingPrimaryAfterInitializationNeverResetsHistory(GameTestHelper c){
        try(var f=new Fixture(c)){
            UUID id=UUID.randomUUID();var first=f.economy.credit(id,"missing-primary-credit",1250);c.assertTrue(first.success(),"First successful credit must be durable");
            Path previous=f.file.resolveSibling(f.file.getFileName()+".previous");byte[] preserved;
            try{c.assertTrue(Files.isRegularFile(previous),"Even first commit must leave the initialized prior-state marker");preserved=Files.readAllBytes(previous);Files.delete(f.file);}catch(IOException failure){throw new IllegalStateException(failure);}
            f.reopen();c.assertFalse(f.economy.available(),"A missing initialized primary is unavailable, not a new empty economy");
            c.assertFalse(f.economy.credit(id,"missing-primary-credit",1250).success(),"Replayed delivery cannot mint after authoritative history disappears");
            c.assertFalse(f.economy.credit(UUID.randomUUID(),"missing-primary-new-credit",100).success(),"New buyers cannot silently bootstrap over missing existing history");
            try{c.assertFalse(Files.exists(f.file),"Blocked operations do not recreate an empty primary");c.assertTrue(Arrays.equals(preserved,Files.readAllBytes(previous)),"Existing recovery bytes are preserved exactly for the owner");}catch(IOException failure){throw new IllegalStateException(failure);}
        }
        try(var f=new Fixture(c)){
            UUID id=UUID.randomUUID();var failed=new InfiniEconomy(c.getLevel().getServer(),f.file,f.now::get,(path,bytes)->{if(path.equals(f.file))throw new IOException("injected before first primary");InfiniEconomy.atomic(path,bytes);});
            c.assertFalse(failed.credit(id,"first-primary-interrupted",750).success(),"Interrupted first primary is not acknowledged as a successful delivery");
            Path previous=f.file.resolveSibling(f.file.getFileName()+".previous");byte[] preserved;
            try{c.assertFalse(Files.exists(f.file),"Interrupted first primary remains absent");preserved=Files.readAllBytes(previous);c.assertTrue(InfiniEconomy.decode(preserved).events.isEmpty(),"Only validated initialized prior state was saved before the interrupted credit");}catch(IOException failure){throw new IllegalStateException(failure);}
            f.reopen();c.assertFalse(f.economy.available(),"Initialized marker makes even interrupted-first-save ambiguity fail closed");
            c.assertFalse(f.economy.credit(id,"first-primary-interrupted",750).success(),"Retry waits for owner recovery rather than guessing empty history");
            try{c.assertTrue(Arrays.equals(preserved,Files.readAllBytes(previous)),"Interrupted-first-save recovery evidence is not overwritten");}catch(IOException failure){throw new IllegalStateException(failure);}
        }c.succeed();
    }

    @GameTest public void fundedRefundAndStaffReturnKeepReservedSeatsAtHistoryLimit(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-capacity");UUID id=p.getUUID(),filler=UUID.randomUUID();var seed=new InfiniEconomy.Data();
            var buyerWallet=new InfiniEconomy.Wallet();buyerWallet.balance=50000;seed.wallets.put(id.toString(),buyerWallet);
            var fillerWallet=new InfiniEconomy.Wallet();fillerWallet.balance=InfiniEconomy.MAX_EVENTS-7;seed.wallets.put(filler.toString(),fillerWallet);
            var funding=new InfiniEconomy.Receipt();funding.kind=InfiniEconomy.Kind.REWARD;funding.uuid=id.toString();funding.units=50000;funding.available=50000;funding.at=f.now.get();seed.events.put("a-funding",funding);
            // One bounded valid seed write, not32,000 fsync transactions. Filler history belongs to a distinct account.
            for(int n=0;n<InfiniEconomy.MAX_EVENTS-7;n++){var event=new InfiniEconomy.Receipt();event.kind=InfiniEconomy.Kind.REWARD;event.uuid=filler.toString();event.units=1;event.available=1;event.at=f.now.get();seed.events.put("seed-"+n,event);}
            try{InfiniEconomy.validate(seed);byte[] bytes=InfiniEconomy.JSON.toJson(seed).getBytes(java.nio.charset.StandardCharsets.UTF_8);c.assertTrue(bytes.length<InfiniEconomy.MAX_BYTES-16384,"Bounded seed leaves actual byte space for all reserved receipts");InfiniEconomy.atomic(f.file,bytes);}catch(IOException failure){throw new IllegalStateException(failure);}f.reopen();
            c.assertTrue(f.economy.available(),"Valid near-limit history reopens without resetting wallets");
            c.assertTrue(f.economy.credit(id,"reserved-credit",100).success(),"A new refundable credit reserves its future full-refund seat");
            var staff=f.economy.quote(p,"admin_pass");c.assertTrue(f.economy.purchase(p,staff).success(),"Funded pending ADMIN request reserves its return seat");String request=staff.nonce().toString();
            c.assertTrue(f.economy.rewardFor(p,"capacity-fill-one",1).success(),"First ordinary reward uses a genuinely unreserved seat");
            c.assertTrue(f.economy.rewardFor(p,"capacity-fill-two",1).success(),"Second ordinary reward reaches the exact reserved capacity");
            long balance=f.economy.balance(id);byte[] before;
            try{before=Files.readAllBytes(f.file);}catch(IOException failure){throw new IllegalStateException(failure);}
            c.assertFalse(f.economy.rewardFor(p,"capacity-no-room",1).success(),"New reward cannot steal an existing obligation's receipt seat");
            c.assertFalse(f.economy.credit(id,"capacity-no-credit",100).success(),"New credit cannot consume seats needed by existing deliveries");
            var cosmetic=f.economy.quote(p,"go_pass");c.assertTrue(cosmetic!=null,"An otherwise affordable review can still be created");c.assertFalse(f.economy.purchase(p,cosmetic).success(),"Unreserved purchase growth refuses before debiting durable funds");
            c.assertFalse(f.economy.refund(id,"reserved-partial","reserved-credit",40).success(),"Partial refund cannot consume the seat still needed for its remaining full refund");
            c.assertTrue(f.economy.available(),"Normal capacity refusal does not pause an existing wallet");c.assertValueEqual(f.economy.balance(id),balance,"Every refused operation leaves saved funds intact");
            try{c.assertTrue(Arrays.equals(before,Files.readAllBytes(f.file)),"Capacity refusals perform no primary ledger writes");}catch(IOException failure){throw new IllegalStateException(failure);}
            c.assertTrue(f.economy.refund(id,"reserved-full","reserved-credit",100).success(),"Full refund swaps its reserved seat for an actual receipt even at the limit");
            c.assertTrue(f.economy.cancelStaff(p,request).success(),"Existing pending request can still start cancellation at the limit");
            c.assertTrue(f.economy.completeStaffRevocation(request,id).success(),"Funded staff return swaps its reserved seat after role removal; no wedge or discarded history");
            c.assertValueEqual(f.economy.balance(id),50200L,"Full original credit refund and staff return conserve buyer cash");
            f.reopen();c.assertTrue(f.economy.available(),"Exactly full completed history remains readable and usable");
            c.assertFalse(f.economy.completeStaffRevocation(request,id).changed(),"Full-history staff replay does not need another receipt or return funds twice");
            c.assertFalse(f.economy.refund(id,"reserved-full","reserved-credit",100).changed(),"Full-history delivery refund replay remains idempotent");
        }c.succeed();
    }

    InfiniEconomy.Wallet byteFiller(InfiniEconomy.Data data,int number,long time){
        UUID id=new UUID(0,number+1L);var wallet=new InfiniEconomy.Wallet();wallet.balance=1;data.wallets.put(id.toString(),wallet);
        String digits=Integer.toString(number),eventId="byte-"+"0".repeat(5-digits.length())+digits+"-"+"x".repeat(85);
        var receipt=new InfiniEconomy.Receipt();receipt.kind=InfiniEconomy.Kind.REWARD;receipt.uuid=id.toString();receipt.units=1;receipt.available=1;receipt.at=time;data.events.put("reward:"+id+":"+eventId,receipt);return wallet;
    }
    @GameTest public void splitSourceReturnAndFullRefundRemainFundedAtByteLimit(GameTestHelper c){
        try(var f=new Fixture(c)){
            var p=f.player("infini-byte-reserve");UUID id=p.getUUID();
            c.assertTrue(f.economy.credit(id,"byte-credit-a",6000).success(),"First original funding credit");c.assertTrue(f.economy.credit(id,"byte-credit-b",6500).success(),"Second original funding credit");
            var staff=f.economy.quote(p,"admin_pass");c.assertTrue(f.economy.purchase(p,staff).success(),"Actual split-source pending staff purchase");String request=staff.nonce().toString();
            InfiniEconomy.Data seed;try{seed=InfiniEconomy.decode(Files.readAllBytes(f.file));}catch(IOException failure){throw new IllegalStateException(failure);}
            long original=InfiniEconomy.JSON.toJson(seed).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+InfiniEconomy.reservedBytes(seed);
            var fillers=new ArrayList<InfiniEconomy.Wallet>();fillers.add(byteFiller(seed,0,f.now.get()));
            long withOne=InfiniEconomy.JSON.toJson(seed).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+InfiniEconomy.reservedBytes(seed),delta=withOne-original;
            int count=(int)((InfiniEconomy.MAX_BYTES-original-256)/delta);c.assertTrue(count>1000&&count<InfiniEconomy.MAX_EVENTS-100,"Byte-bound fixture is large, yet safely below event capacity");
            for(int n=1;n<count;n++)fillers.add(byteFiller(seed,n,f.now.get()));
            long occupied=InfiniEconomy.JSON.toJson(seed).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+InfiniEconomy.reservedBytes(seed),gap=InfiniEconomy.MAX_BYTES-occupied;
            int pad=(int)((gap-8)/"supporter_go".length());c.assertTrue(pad>=0&&pad<fillers.size(),"Bounded preference padding reaches a deterministic byte frontier");
            for(int n=0;n<pad;n++)fillers.get(n).selectedStyle="supporter_go"; // Stored hidden/expired selection confers no owned cosmetic.
            try{InfiniEconomy.validate(seed);byte[] bytes=InfiniEconomy.JSON.toJson(seed).getBytes(java.nio.charset.StandardCharsets.UTF_8);long free=InfiniEconomy.MAX_BYTES-bytes.length-InfiniEconomy.reservedBytes(seed);c.assertTrue(free>=0&&free<32,"Canonical ledger plus future reservations sits within32bytes of its bound");InfiniEconomy.atomic(f.file,bytes);}catch(IOException failure){throw new IllegalStateException(failure);}f.reopen();
            c.assertTrue(f.economy.available(),"Valid byte-reserved history opens normally");c.assertFalse(f.economy.credit(id,"byte-growth-refused",100).success(),"New funding cannot steal future RETURN or full-refund bytes");c.assertTrue(f.economy.available(),"Byte-capacity refusal is a normal refusal, not a paused wallet");
            // A partial refund needs another receipt without releasing its final full-refund reserve.
            c.assertFalse(f.economy.refund(id,"byte-partial-refused","byte-credit-a",1).success(),"Partial refund cannot consume the remaining obligation budget");
            c.assertTrue(f.economy.cancelStaff(p,request).success(),"Pending cancellation can persist its journal even at the byte frontier");
            c.assertTrue(f.economy.completeStaffRevocation(request,id).success(),"Actual split-source RETURN fits the bytes reserved for its origins");c.assertValueEqual(f.economy.balance(id),12500L,"Original split-source funding returns exactly");
            c.assertTrue(f.economy.refund(id,"byte-a-full","byte-credit-a",6000).success(),"First original full refund fits reserved bytes");
            c.assertTrue(f.economy.refund(id,"byte-b-full","byte-credit-b",6500).success(),"Second original full refund also fits reserved bytes");
            f.reopen();c.assertTrue(f.economy.available(),"Completed near-byte-limit ledger reopens");c.assertValueEqual(f.economy.balance(id),0L,"All original returned funds are reclaimed exactly");c.assertValueEqual(f.economy.debt(id),0L,"Unspent split-source returns do not create false debt");
        }c.succeed();
    }

}

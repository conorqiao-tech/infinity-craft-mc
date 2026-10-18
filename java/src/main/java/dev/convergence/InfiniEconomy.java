package dev.convergence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;

/** Local Infini currency. One atomic ledger owns balances, cosmetic grants and staff requests.
 * A paid staff request cannot grant permissions: a separate owner approval and role adapter must do that.
 * There is deliberately no HTTP checkout, automatic billing, direct operator grant or inventory debit.
 */
public final class InfiniEconomy {
    public record Result(boolean success, boolean changed, String message) {}
    public enum Supporter { NONE, GO, PLUS, PRO, ULTRA }
    public enum Term { PASS, LIFETIME }
    public record Product(String id,String title,Supporter tier,Term term,long units,long usdCents,boolean enabled,String styleId,boolean staff) {}
    record Catalogue(int schema,String pricesStatus,boolean paymentsEnabled,long usdPerInfiniCents,long unitsPerInfini,List<Product> products) {}
    enum Kind { CREDIT, REWARD, PURCHASE, REFUND, RETURN }
    public enum StaffState { PENDING, APPROVED, REVOKING, DENIED, REVOKED, EXPIRED }
    public record StaffRequest(String id,UUID buyer,Term term,long units,long purchasedAt,long approvedAt,long expiresAt,StaffState state,StaffState outcome,boolean refund) {}
    static final class Staff { String buyer,product;Term term;long units,purchasedAt,approvedAt,expiresAt;StaffState state=StaffState.PENDING,outcome;boolean refund; }
    static final Gson JSON=new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    static final long MAX_UNITS=9_000_000_000_000_000L, PASS_MILLIS=30L*24*60*60*1000, MAX_BYTES=16L*1024*1024;
    static final int MAX_EVENTS=32_000;
    static final Pattern EVENT_ID=Pattern.compile("[A-Za-z0-9._:-]{1,96}"),EVENT_KEY=Pattern.compile("[A-Za-z0-9._:-]{1,256}");
    static final Catalogue CATALOGUE=loadCatalogue();
    private static final Map<MinecraftServer,InfiniEconomy> INSTANCES=new WeakHashMap<>();
    static final class Wallet { long balance,debt,revision; String selectedStyle=""; }
    static final class Receipt {
        Kind kind; String uuid,reference="",product=""; long units,at,available,refunded,expires,debt;
        TreeMap<String,Long> allocations=new TreeMap<>(),origins=new TreeMap<>(),replacements=new TreeMap<>();
        Supporter tier=Supporter.NONE; Term term; boolean staff;
        boolean same(Kind k,UUID id,long amount,String ref,String sku){return kind==k&&uuid.equals(id.toString())&&units==amount&&reference.equals(ref)&&product.equals(sku);}
    }
    static final class Data { int format=2;long revision;TreeMap<String,Wallet> wallets=new TreeMap<>();TreeMap<String,Receipt> events=new TreeMap<>();TreeMap<String,Staff> staff=new TreeMap<>(); }
    @FunctionalInterface interface Store { void write(Path file,byte[] bytes) throws IOException; }
    record Quote(UUID nonce,ServerPlayer player,ServerGamePacketListenerImpl connection,ServerLevel world,GameModes.Mode mode,net.minecraft.world.level.GameType nativeMode,
                 long walletRevision,Product product,long expires,int created) {}
    private final MinecraftServer server;
    private final Path file;
    private final LongSupplier clock;
    private final Store store;
    private Data data=new Data();
    private byte[] expected;
    private String unavailable="";
    private final Map<UUID,Quote> quotes=new HashMap<>();

    InfiniEconomy(MinecraftServer server,Path file,LongSupplier clock,Store store){
        this.server=server;this.file=file.toAbsolutePath().normalize();this.clock=clock;this.store=store;
        try{expected=read(this.file);if(expected==null&&read(previousFile())!=null)throw new IOException("Primary ledger is missing after initialization");if(expected!=null)data=decode(expected);}catch(Exception failure){unavailable="Ledger is unreadable; the owner must restore or review it. It was not reset.";}
    }
    public static InfiniEconomy get(MinecraftServer server){synchronized(INSTANCES){return INSTANCES.computeIfAbsent(server,s->new InfiniEconomy(s,s.getWorldPath(LevelResource.ROOT).resolve("infinity-economy.json"),System::currentTimeMillis,InfiniEconomy::atomic));}}
    static Catalogue loadCatalogue(){
        try(var input=InfiniEconomy.class.getResourceAsStream("/assets/convergence/infini_catalogue.json")){
            if(input==null)throw new IOException("Missing Infini catalogue");
            var c=JSON.fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),Catalogue.class);
            if(c.schema()!=1||!"CONFIGURED".equals(c.pricesStatus())||c.paymentsEnabled()||c.usdPerInfiniCents()!=200||c.unitsPerInfini()!=100||c.products()==null)throw new IOException("Unsupported checkout configuration");
            var ids=new HashSet<String>();
            for(var p:c.products())if(p==null||p.id()==null||!EVENT_ID.matcher(p.id()).matches()||!ids.add(p.id())||p.title()==null||p.tier()==null||(!p.staff()&&p.tier()==Supporter.NONE)||(p.staff()&&p.tier()!=Supporter.NONE)||p.term()==null||p.units()<=0||p.units()>MAX_UNITS||Math.multiplyExact(p.usdCents(),c.unitsPerInfini())!=Math.multiplyExact(p.units(),c.usdPerInfiniCents())||(!p.staff()&&!style(p.tier()).equals(p.styleId()))||(p.staff()&&!"".equals(p.styleId())))throw new IOException("Invalid cosmetic product");
            return c;
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    static String style(Supporter tier){return "supporter_"+tier.name().toLowerCase(Locale.ROOT);}
    public List<Product> products(){return CATALOGUE.products().stream().filter(Product::enabled).toList();}
    Product product(String id){return products().stream().filter(p->p.id().equals(id)).findFirst().orElse(null);}
    public boolean available(){return unavailable.isEmpty();}
    public String status(){return available()?"Local Infini ready. USD prices are configured; checkout is not connected.":unavailable;}
    private Wallet wallet(Data d,UUID id){return d.wallets.computeIfAbsent(id.toString(),ignored->new Wallet());}
    private Wallet wallet(UUID id){return data.wallets.getOrDefault(id.toString(),new Wallet());}
    /** Integer hundredths: 100 ledger units = 1 Infini. Never a floating-point balance. */
    public long balance(UUID id){return wallet(id).balance;}
    public String balanceText(UUID id){return format(balance(id));}
    public static String format(long units){return BigDecimal.valueOf(units,2).stripTrailingZeros().toPlainString();}
    static long parse(String amount){if(amount==null||!amount.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,2})?"))return -1;try{long units=new BigDecimal(amount).movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact();return units>0&&units<=MAX_UNITS?units:-1;}catch(ArithmeticException|NumberFormatException failure){return -1;}}
    public long debt(UUID id){return wallet(id).debt;}
    public Supporter activeSupporter(UUID id){
        if(!available()||debt(id)>0)return Supporter.NONE;
        Supporter result=Supporter.NONE;long now=clock.getAsLong();
        for(var r:data.events.values())if(r.kind==Kind.PURCHASE&&r.uuid.equals(id.toString())&&(r.term==Term.LIFETIME||r.expires>now)&&r.tier.ordinal()>result.ordinal())result=r.tier;
        return result;
    }
    Supporter lifetimeSupporter(UUID id){Supporter result=Supporter.NONE;for(var r:data.events.values())if(r.kind==Kind.PURCHASE&&r.uuid.equals(id.toString())&&r.term==Term.LIFETIME&&r.tier.ordinal()>result.ordinal())result=r.tier;return result;}
    public boolean ownedStyle(UUID id,String style){
        for(var tier:Supporter.values())if(tier!=Supporter.NONE&&style(tier).equals(style))return activeSupporter(id).ordinal()>=tier.ordinal();
        return false;
    }
    public String selectedStyle(UUID id){String style=wallet(id).selectedStyle;return ownedStyle(id,style)?style:"";}
    public String supporterTitle(UUID id){
        String selected=selectedStyle(id);if(selected.isEmpty())return "";
        for(var tier:Supporter.values())if(style(tier).equals(selected))return tier.name().charAt(0)+tier.name().substring(1).toLowerCase(Locale.ROOT)+" Supporter";
        return "";
    }
    public Result selectStyle(ServerPlayer p,String style){
        if(!actor(p))return no("Reopen the menu from your current session.");
        if(!style.isEmpty()&&!ownedStyle(p.getUUID(),style))return no("That cosmetic style is not active on your account.");
        if(wallet(p.getUUID()).selectedStyle.equals(style))return yes(false,"Cosmetic selection is unchanged.");
        var next=copy();wallet(next,p.getUUID()).selectedStyle=style;return commit(next,p.getUUID(),"Cosmetic display saved.");
    }
    private StaffRequest snapshot(String id,Staff r){return new StaffRequest(id,UUID.fromString(r.buyer),r.term,r.units,r.purchasedAt,r.approvedAt,r.expiresAt,r.state,r.outcome,r.refund);}
    public List<StaffRequest> staffRequests(){return data.staff.entrySet().stream().map(e->snapshot(e.getKey(),e.getValue())).toList();}
    public StaffRequest staffRequest(String id){var request=data.staff.get(id);return request==null?null:snapshot(id,request);}
    public boolean staffEligible(StaffRequest request){
        if(!available()||request==null||!Objects.equals(request,staffRequest(request.id()))||debt(request.buyer())>0)return false;
        return request.state()==StaffState.APPROVED&&(request.term()==Term.LIFETIME||clock.getAsLong()<request.expiresAt());
    }
    public boolean staffExpired(StaffRequest request){return request!=null&&request.state()==StaffState.APPROVED&&request.term()==Term.PASS&&clock.getAsLong()>=request.expiresAt();}
    private Staff exactStaff(String id,UUID buyer){var request=data.staff.get(id);return request!=null&&buyer!=null&&request.buyer.equals(buyer.toString())?request:null;}
    /** Approval is an exact durable desired-role record, not an operator grant. Offline buyers wait for native sync. */
    public Result approveStaff(net.minecraft.commands.CommandSourceStack source,String id,UUID buyer){
        if(source.getServer()!=server||!AgentActions.localConsole(source))return no("Only the actual local owner console can approve this exact staff application.");
        var previous=exactStaff(id,buyer);if(previous==null)return no("Request ID and buyer UUID do not match.");
        if(previous.state==StaffState.APPROVED)return yes(false,"This exact staff application already has owner approval.");
        if(previous.state!=StaffState.PENDING||debt(buyer)>0)return no("Only a pending application without a refund hold can be approved.");
        var next=copy();var request=next.staff.get(id);request.state=StaffState.APPROVED;request.approvedAt=clock.getAsLong();
        try{request.expiresAt=request.term==Term.LIFETIME?0:Math.addExact(request.approvedAt,PASS_MILLIS);}catch(ArithmeticException failure){return no("Approval expiry exceeds safe storage.");}
        return commit(next,buyer,"Owner approval saved for application "+id+" and UUID "+buyer+". Native role sync must verify its own provenance before granting any permissions.");
    }
    public Result denyStaff(net.minecraft.commands.CommandSourceStack source,String id,UUID buyer){
        if(source.getServer()!=server||!AgentActions.localConsole(source))return no("Only the actual local owner console can deny/refund a staff application.");
        return stopStaff(id,buyer,StaffState.DENIED,true);
    }
    public Result revokeStaff(net.minecraft.commands.CommandSourceStack source,String id,UUID buyer){
        if(source.getServer()!=server||!AgentActions.localConsole(source))return no("Only the actual local owner console can revoke/refund a staff application.");
        return stopStaff(id,buyer,StaffState.REVOKED,true);
    }
    /** Called by the trusted role adapter when its native grant reaches its original approval expiry. */
    Result expireStaff(String id,UUID buyer){var request=staffRequest(id);if(request==null||!staffExpired(request))return no("Staff application is not due to expire.");return stopStaff(id,buyer,StaffState.EXPIRED,false);}
    Result cancelStaff(ServerPlayer player,String id){
        if(!actor(player))return no("Reopen the menu in your current session.");var request=exactStaff(id,player.getUUID());
        if(request==null||request.state!=StaffState.PENDING)return no("Only your unapproved pending application can be cancelled here.");
        return stopStaff(id,player.getUUID(),StaffState.DENIED,true);
    }
    private Result stopStaff(String id,UUID buyer,StaffState outcome,boolean refund){
        if(!server.isSameThread()||!available())return no(status());var previous=exactStaff(id,buyer);
        if(previous==null)return no("Request ID and buyer UUID do not match.");
        if(previous.state==outcome)return yes(false,"That exact staff decision is already complete.");
        if(previous.state==StaffState.REVOKING)return previous.outcome==outcome&&previous.refund==refund?yes(false,"Revocation/refund is already waiting for safe native role removal."):no("A different exact staff decision is already being recovered.");
        if(previous.state!=StaffState.PENDING&&previous.state!=StaffState.APPROVED)return no("That staff application is already final.");
        var next=copy();var request=next.staff.get(id);request.state=StaffState.REVOKING;request.outcome=outcome;request.refund=refund;
        return commit(next,buyer,"Staff stop intent saved. A refund becomes spendable only after the role adapter confirms safe removal of this purchase's owned permissions.");
    }
    /** Trusted role adapter ONLY, after removal is durably verified. Safe to retry after any crash. */
    Result completeStaffRevocation(String id,UUID buyer){
        if(!server.isSameThread()||!available())return no(status());var previous=exactStaff(id,buyer);
        if(previous==null)return no("Request ID and buyer UUID do not match.");
        if(previous.state==StaffState.DENIED||previous.state==StaffState.REVOKED||previous.state==StaffState.EXPIRED)return yes(false,"Staff decision/refund was already completed.");
        if(previous.state!=StaffState.REVOKING)return no("An exact durable staff stop intent is required first.");
        var next=copy();var request=next.staff.get(id);request.state=request.outcome;
        if(request.refund){
            var receipt=new Receipt();receipt.kind=Kind.RETURN;receipt.uuid=buyer.toString();receipt.units=request.units;receipt.reference="purchase:"+id;receipt.at=clock.getAsLong();
            try{var purchase=next.events.get(receipt.reference);var cash=releaseSources(next,buyer,purchase.allocations);next.events.put("staff-return:"+id,receipt);applyCash(next,buyer,receipt,cash);}
            catch(ArithmeticException|IllegalStateException failure){return no("Returned credit provenance could not be reconciled; native role remains revoked.");}
        }
        return commit(next,buyer,request.refund?"Staff application closed and its exact Infini cost returned once (refund holds settle first).":"Purchased staff term expired. Other independent staff/OP grants remain under native provenance checks.");
    }
    /** Server code only: a stable first-time milestone ID yields one durable grant per UUID. */
    public static Result reward(ServerPlayer player,String eventId,long wholeInfini){return get(player.level().getServer()).rewardFor(player,eventId,wholeInfini);}
    Result rewardFor(ServerPlayer player,String eventId,long wholeInfini){
        if(!actor(player))return no("Reward waits for an active server session.");
        try{return credit(player.getUUID(),"reward:"+player.getUUID()+":"+eventId,eventId,Math.multiplyExact(wholeInfini,100),Kind.REWARD);}catch(ArithmeticException failure){return no("Reward exceeds safe integer storage.");}
    }
    Result credit(UUID id,String eventId,long units){return credit(id,"external:"+eventId,eventId,units,Kind.CREDIT);}
    private Result credit(UUID id,String key,String eventId,long units,Kind kind){
        if(!server.isSameThread()||!available())return no(available()?"Run delivery on the server thread.":status());
        if(id==null||!validEvent(eventId)||units<=0||units>MAX_UNITS)return no("Use a stable event ID, authenticated UUID and positive Infini amount with at most two decimals.");
        var previous=data.events.get(key);if(previous!=null)return previous.same(kind,id,units,"","")?yes(false,"This exact credit was already saved."):no("Event ID already belongs to a different delivery. Nothing changed.");
        var next=copy();long previousDebt=wallet(next,id).debt;
        var r=new Receipt();r.kind=kind;r.uuid=id.toString();r.units=units;r.at=clock.getAsLong();next.events.put(key,r);
        try{applyCash(next,id,r,Map.of(key,units));}catch(ArithmeticException|IllegalStateException failure){return no("Amount exceeds safe integer storage. Nothing changed.");}
        long paid=previousDebt-wallet(next,id).debt;
        return commit(next,id,paid==0?format(units)+" Infini credited.":format(units)+" Infini credited; "+format(paid)+" repaid the refund hold.");
    }
    Result refund(UUID id,String eventId,String originalCredit,long units){
        if(!server.isSameThread()||!available())return no(available()?"Run refunds on the server thread.":status());
        if(id==null||!validEvent(eventId)||!validEvent(originalCredit)||units<=0||units>MAX_UNITS)return no("Use exact delivery/refund IDs, UUID and positive Infini amount with at most two decimals.");
        String key="external:"+eventId,ref="external:"+originalCredit;var existing=data.events.get(key);
        if(existing!=null)return existing.same(Kind.REFUND,id,units,ref,"")?yes(false,"This exact refund was already saved."):no("Event ID already belongs to a different delivery. Nothing changed.");
        var original=data.events.get(ref);
        if(original==null||original.kind!=Kind.CREDIT||!original.uuid.equals(id.toString())||units>original.units-original.refunded)return no("Refund must reference that UUID's existing unrefunded console credit.");
        var next=copy();var r=next.events.get(ref);var w=wallet(next,id);long remaining=units;
        long own=Math.min(remaining,r.available);r.available-=own;remaining-=own;w.balance-=own;
        // A cancelled staff purchase returns its original funding roots. Reclaim only this credit's unspent descendants.
        for(var returned:next.events.values())if(remaining>0&&returned.kind==Kind.RETURN&&returned.uuid.equals(id.toString())){
            long linked=returned.origins.getOrDefault(ref,0L),take=Math.min(remaining,linked);if(take==0)continue;
            returned.available-=take;remaining-=take;w.balance-=take;putRemaining(returned.origins,ref,linked-take);
        }
        try{r.refunded+=units;r.debt=Math.addExact(r.debt,remaining);w.debt=Math.addExact(w.debt,remaining);if(w.debt>MAX_UNITS)throw new ArithmeticException();}catch(ArithmeticException failure){return no("Refund exceeds safe integer storage. Nothing changed.");}
        var receipt=new Receipt();receipt.kind=Kind.REFUND;receipt.uuid=id.toString();receipt.units=units;receipt.reference=ref;receipt.at=clock.getAsLong();next.events.put(key,receipt);
        return commit(next,id,"Refund saved. Unspent Infini removed; spent refunded funds create a cosmetic/spending hold until repaid. Earned ranks remain unchanged.");
    }
    Quote quote(ServerPlayer p,String productId){
        quotes.remove(p.getUUID());if(!actor(p)||!available()||debt(p.getUUID())>0)return null;
        var item=product(productId);if(item==null||balance(p.getUUID())<item.units()||(!item.staff()&&lifetimeSupporter(p.getUUID()).ordinal()>=item.tier().ordinal())||(item.staff()&&staffRequests().stream().anyMatch(r->r.buyer().equals(p.getUUID())&&(r.state()==StaffState.PENDING||r.state()==StaffState.APPROVED||r.state()==StaffState.REVOKING))))return null;
        long expires=0;if(item.term()==Term.PASS&&!item.staff()){long base=clock.getAsLong();for(var r:data.events.values())if(r.kind==Kind.PURCHASE&&r.uuid.equals(p.getUUID().toString())&&r.tier==item.tier()&&r.term==Term.PASS)base=Math.max(base,r.expires);try{expires=Math.addExact(base,PASS_MILLIS);}catch(ArithmeticException failure){return null;}}
        var quote=new Quote(UUID.randomUUID(),p,p.connection,p.level(),GameModes.current(p),p.gameMode.getGameModeForPlayer(),wallet(p.getUUID()).revision,item,expires,server.getTickCount());quotes.put(p.getUUID(),quote);return quote;
    }
    Result purchase(ServerPlayer p,Quote q){
        if(q==null||quotes.get(p.getUUID())!=q)return no("That review is no longer current. Nothing was charged.");
        // Foreign responses cannot consume another player's pending review.
        if(q.player()!=p||p.connection!=q.connection())return no("This is a different player session.");
        quotes.remove(p.getUUID(),q);
        int elapsed=server.getTickCount()-q.created();
        if(!actor(p)||!CommonsEvents.emptyScreen(p)||p.level()!=q.world()||GameModes.current(p)!=q.mode()||p.gameMode.getGameModeForPlayer()!=q.nativeMode()||elapsed<0||elapsed>=1800||wallet(p.getUUID()).revision!=q.walletRevision()||!Objects.equals(product(q.product().id()),q.product())||debt(p.getUUID())>0||balance(p.getUUID())<q.product().units()||(!q.product().staff()&&lifetimeSupporter(p.getUUID()).ordinal()>=q.product().tier().ordinal()))return no("Your session, wallet or offer changed. Reopen the shop; nothing was charged.");
        var next=copy();var w=wallet(next,p.getUUID());long remaining=q.product().units();var sources=new TreeMap<String,Long>();
        for(var entry:next.events.entrySet()){
            var r=entry.getValue();if(!r.uuid.equals(p.getUUID().toString())||(r.kind!=Kind.CREDIT&&r.kind!=Kind.REWARD&&r.kind!=Kind.RETURN))continue;
            long debit=Math.min(remaining,r.available);if(debit==0)continue;
            if(r.kind==Kind.RETURN){long split=debit;for(String origin:new ArrayList<>(r.origins.keySet())){long take=Math.min(split,r.origins.get(origin));if(take==0)continue;sources.merge(origin,take,Math::addExact);putRemaining(r.origins,origin,r.origins.get(origin)-take);split-=take;if(split==0)break;}if(split!=0)return no("Returned credit provenance does not match its available balance.");}
            else sources.merge(entry.getKey(),debit,Math::addExact);
            r.available-=debit;remaining-=debit;if(remaining==0)break;
        }
        if(remaining!=0)return no("Credit receipts do not match the wallet. Ask the owner to review storage.");
        w.balance-=q.product().units();var receipt=new Receipt();receipt.kind=Kind.PURCHASE;receipt.uuid=p.getUUID().toString();receipt.product=q.product().id();receipt.units=q.product().units();receipt.at=clock.getAsLong();receipt.expires=q.expires();receipt.term=q.product().term();receipt.tier=q.product().tier();receipt.staff=q.product().staff();receipt.allocations=sources;next.events.put("purchase:"+q.nonce(),receipt);
        if(q.product().staff()){var request=new Staff();request.buyer=p.getUUID().toString();request.product=q.product().id();request.term=q.product().term();request.units=q.product().units();request.purchasedAt=receipt.at;next.staff.put(q.nonce().toString(),request);}else w.selectedStyle=q.product().styleId();
        return commit(next,p.getUUID(),(q.product().staff()?"Staff application "+q.nonce()+" saved PENDING OWNER APPROVAL. No operator rights were granted. ":"Purchased ")+q.product().title()+" for "+format(q.product().units())+" Infini. No USD charge or automatic renewal.");
    }
    record Funding(String root,long units) {}
    static void putRemaining(Map<String,Long> map,String key,long value){if(value==0)map.remove(key);else map.put(key,value);}
    /** Release the precise roots consumed by a reversed purchase, including later funding that already repaid a chargeback.
     * Iterative traversal avoids stack growth across a long chain of credits/refunds. Each consumed replacement is removed.
     */
    private TreeMap<String,Long> releaseSources(Data next,UUID buyer,Map<String,Long> sources){
        var cash=new TreeMap<String,Long>();var queue=new ArrayDeque<Funding>();for(var e:sources.entrySet())queue.addLast(new Funding(e.getKey(),e.getValue()));
        var w=wallet(next,buyer);int traversals=0;
        while(!queue.isEmpty()){
            if(++traversals>MAX_EVENTS*8)throw new IllegalStateException("Excessive funding graph");var funding=queue.removeFirst();var root=next.events.get(funding.root());long remaining=funding.units();
            if(root==null||!root.uuid.equals(buyer.toString())||(root.kind!=Kind.CREDIT&&root.kind!=Kind.REWARD))throw new IllegalStateException("Unknown root credit");
            long unpaid=Math.min(remaining,root.debt);root.debt-=unpaid;w.debt-=unpaid;remaining-=unpaid;
            for(String replacement:new ArrayList<>(root.replacements.keySet())){long amount=root.replacements.get(replacement),take=Math.min(remaining,amount);if(take==0)break;putRemaining(root.replacements,replacement,amount-take);queue.addLast(new Funding(replacement,take));remaining-=take;}
            if(remaining>0)cash.merge(funding.root(),remaining,Math::addExact);
        }
        return cash;
    }
    /** Cash settles exact outstanding source debts first; the replacement root is retained for any later purchase reversal. */
    private void applyCash(Data next,UUID buyer,Receipt destination,Map<String,Long> cash){
        var w=wallet(next,buyer);
        for(var funding:cash.entrySet()){
            long remaining=funding.getValue();
            for(var entry:next.events.entrySet()){
                var owing=entry.getValue();if(remaining==0)break;if(owing.kind!=Kind.CREDIT||owing.debt==0||!owing.uuid.equals(buyer.toString()))continue;
                long paid=Math.min(remaining,owing.debt);owing.debt-=paid;w.debt-=paid;remaining-=paid;
                if(!entry.getKey().equals(funding.getKey()))owing.replacements.merge(funding.getKey(),paid,Math::addExact);
            }
            if(remaining>0){destination.available=Math.addExact(destination.available,remaining);w.balance=Math.addExact(w.balance,remaining);if(destination.kind==Kind.RETURN)destination.origins.merge(funding.getKey(),remaining,Math::addExact);}
        }
        if(w.balance>MAX_UNITS||w.debt>MAX_UNITS)throw new ArithmeticException("Unsafe wallet total");
    }
    boolean actor(ServerPlayer p){return server.isSameThread()&&p!=null&&p.level().getServer()==server&&server.getPlayerList().getPlayer(p.getUUID())==p&&p.connection!=null&&ServerMenu.allowed(p);}
    static boolean validEvent(String id){return id!=null&&EVENT_ID.matcher(id).matches();}
    static Result no(String message){return new Result(false,false,message);}
    static Result yes(boolean changed,String message){return new Result(true,changed,message);}
    private Data copy(){return JSON.fromJson(JSON.toJson(data),Data.class);}
    private Path previousFile(){return file.resolveSibling(file.getFileName()+".previous");}
    /** Each funded obligation owns a future receipt seat; ordinary growth cannot consume it. */
    static long reservedEvents(Data state){
        long usage=state.events.size();
        for(var credit:state.events.values())if(credit!=null&&credit.kind==Kind.CREDIT&&credit.refunded<credit.units)usage++;
        for(var entry:state.staff.entrySet()){var request=entry.getValue();if(request!=null&&(request.state==StaffState.PENDING||request.state==StaffState.APPROVED||request.state==StaffState.REVOKING)&&!state.events.containsKey("staff-return:"+entry.getKey()))usage++;}
        return usage;
    }
    record RootSize(long count,int longestKey) {}
    /** Fixed reserves cover the complete future receipt plus bounded numeric/state growth.
     * Variable RETURN entries (origins plus replacement edges) are at most the buyer's funding-root count:
     * the greedy transfer exhausts a cash source or debt destination at every entry. Each key uses at most
     * that buyer's longest actual UTF-8 root key; 64 bytes covers JSON indentation/punctuation and a long.
     * Stage reductions let approval/revocation metadata consume its previously reserved space.
     */
    static long reservedBytes(Data state){
        long reserved=0;var sizes=new HashMap<String,RootSize>();
        for(var entry:state.events.entrySet()){
            var receipt=entry.getValue();if(!root(receipt))continue;int keyBytes=entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            var previous=sizes.getOrDefault(receipt.uuid,new RootSize(0,0));sizes.put(receipt.uuid,new RootSize(previous.count()+1,Math.max(previous.longestKey(),keyBytes)));
            if(receipt.kind==Kind.CREDIT&&receipt.refunded<receipt.units)reserved=Math.addExact(reserved,1024L+keyBytes);
        }
        for(var entry:state.staff.entrySet()){
            var request=entry.getValue();if(request==null||state.events.containsKey("staff-return:"+entry.getKey()))continue;
            long fixed=switch(request.state){case PENDING->4096;case APPROVED->3072;case REVOKING->2048;default->0;};if(fixed==0)continue;
            var size=sizes.getOrDefault(request.buyer,new RootSize(0,0));reserved=Math.addExact(reserved,Math.addExact(fixed,Math.multiplyExact(size.count(),size.longestKey()+64L)));
        }
        return reserved;
    }
    private Result commit(Data next,UUID id,String message){
        if(!server.isSameThread()||!available())return no(status());
        if(reservedEvents(next)>MAX_EVENTS)return no("Infini history capacity is reserved for existing refunds and staff returns. This new operation was refused; your saved wallet remains available.");
        try{
            next.revision=Math.addExact(data.revision,1);wallet(next,id).revision=Math.addExact(wallet(next,id).revision,1);
            byte[] bytes=JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
            if(Math.addExact(bytes.length,reservedBytes(next))>MAX_BYTES)return no("Infini byte capacity is reserved for existing refunds and staff returns. This new operation was refused; no saved funds changed.");
            validate(next);
            if(!Arrays.equals(expected,read(file)))throw new IOException("Ledger changed outside this session");
            // Even the first successful transaction leaves a durable initialized marker before publishing its primary.
            // A missing primary at the next startup must never be confused with a brand new wallet history.
            if(expected==null&&read(previousFile())!=null)throw new IOException("Ledger initialized outside this session");
            byte[] previous=expected==null?JSON.toJson(data).getBytes(StandardCharsets.UTF_8):expected;
            store.write(previousFile(),previous);if(!Arrays.equals(previous,read(previousFile())))throw new IOException("Previous ledger initialization/readback failed");
            store.write(file,bytes);if(!Arrays.equals(bytes,read(file)))throw new IOException("Ledger readback did not match");
            data=next;expected=bytes;return yes(true,message);
        }catch(Exception failure){unavailable="Infini storage is paused after a failed save. No further spending or credits will run until the owner reviews/reopens the ledger.";quotes.clear();return no(unavailable);}
    }
    static Data decode(byte[] bytes)throws IOException{try{var parsed=JSON.fromJson(new String(bytes,StandardCharsets.UTF_8),Data.class);validate(parsed);return parsed;}catch(Exception failure){throw new IOException("Invalid Infini ledger",failure);}}
    static boolean root(Receipt receipt){return receipt!=null&&(receipt.kind==Kind.CREDIT||receipt.kind==Kind.REWARD);}
    static long sum(Map<String,Long> allocations)throws IOException{long result=0;for(var e:allocations.entrySet()){if(e.getKey()==null||e.getValue()==null||e.getValue()<=0||e.getValue()>MAX_UNITS)throw new IOException("Invalid funding allocation");result=Math.addExact(result,e.getValue());}return result;}
    static void validate(Data d)throws IOException{
        if(d==null||d.format!=2||d.revision<0||d.wallets==null||d.events==null||d.staff==null||d.events.size()>MAX_EVENTS||d.staff.size()>MAX_EVENTS||reservedEvents(d)>MAX_EVENTS)throw new IOException("Unsupported ledger shape");
        var balances=new HashMap<String,Long>();var debts=new HashMap<String,Long>();var refunds=new HashMap<String,Long>();var cashRoots=new HashMap<String,Long>();var equity=new HashMap<String,BigInteger>();
        var indegree=new HashMap<String,Integer>();
        try{
            for(var entry:d.wallets.entrySet()){
                if(!UUID.fromString(entry.getKey()).toString().equals(entry.getKey()))throw new IOException("Non-canonical wallet UUID");var w=entry.getValue();
                if(w==null||w.balance<0||w.balance>MAX_UNITS||w.debt<0||w.debt>MAX_UNITS||w.revision<0||w.selectedStyle==null||!w.selectedStyle.isEmpty()&&Arrays.stream(Supporter.values()).noneMatch(t->t!=Supporter.NONE&&style(t).equals(w.selectedStyle)))throw new IOException("Invalid wallet");
            }
            for(var entry:d.events.entrySet()){
                String key=entry.getKey();var r=entry.getValue();
                if(!EVENT_KEY.matcher(key).matches()||r==null||r.kind==null||r.uuid==null||!d.wallets.containsKey(r.uuid)||r.units<=0||r.units>MAX_UNITS||r.at<0||r.reference==null||r.product==null||r.tier==null||r.available<0||r.available>r.units||r.refunded<0||r.refunded>r.units||r.debt<0||r.debt>MAX_UNITS||r.allocations==null||r.origins==null||r.replacements==null)throw new IOException("Invalid event receipt");
                long signed=(r.kind==Kind.PURCHASE||r.kind==Kind.REFUND)?-r.units:r.units;equity.merge(r.uuid,BigInteger.valueOf(signed),BigInteger::add);
                if(root(r)){
                    if(r.available>r.units-r.refunded||!r.reference.isEmpty()||!r.product.isEmpty()||r.tier!=Supporter.NONE||!r.origins.isEmpty()||!r.allocations.isEmpty()||r.kind==Kind.REWARD&&(r.refunded!=0||r.debt!=0))throw new IOException("Invalid root credit");
                    if(Math.addExact(sum(r.replacements),r.debt)>r.refunded)throw new IOException("Debt replacement exceeds original chargebacks");balances.merge(r.uuid,r.available,Math::addExact);debts.merge(r.uuid,r.debt,Math::addExact);cashRoots.merge(key,r.available,Math::addExact);indegree.putIfAbsent(key,0);
                    for(var replacement:r.replacements.entrySet()){var funding=d.events.get(replacement.getKey());if(!root(funding)||!funding.uuid.equals(r.uuid)||replacement.getKey().equals(key))throw new IOException("Invalid debt replacement root");indegree.merge(replacement.getKey(),1,Integer::sum);}
                }else{
                    if(r.debt!=0||!r.replacements.isEmpty())throw new IOException("Non-credit receipt carries debt");
                    if(r.kind==Kind.PURCHASE){
                        if(r.available!=0||r.refunded!=0||!r.origins.isEmpty()||(!r.staff&&r.tier==Supporter.NONE)||(r.staff&&r.tier!=Supporter.NONE)||r.term==null||r.product.isEmpty()||!r.staff&&r.term==Term.PASS&&r.expires<=r.at||r.term==Term.LIFETIME&&r.expires!=0||sum(r.allocations)!=r.units)throw new IOException("Invalid funded purchase");
                        for(String source:r.allocations.keySet())if(!root(d.events.get(source))||!d.events.get(source).uuid.equals(r.uuid))throw new IOException("Purchase funding belongs to another account");
                        if(r.staff&&(!key.startsWith("purchase:")||!d.staff.containsKey(key.substring("purchase:".length()))))throw new IOException("Staff purchase lacks matching application");
                    }else if(r.kind==Kind.RETURN){
                        if(r.refunded!=0||!r.allocations.isEmpty()||sum(r.origins)!=r.available||!r.reference.startsWith("purchase:"))throw new IOException("Invalid returned funding");
                        var original=d.events.get(r.reference);String id=r.reference.substring("purchase:".length());var request=d.staff.get(id);
                        if(original==null||original.kind!=Kind.PURCHASE||!original.staff||!original.uuid.equals(r.uuid)||original.units!=r.units||!key.equals("staff-return:"+id)||request==null||!request.refund||request.state==StaffState.REVOKING||request.state==StaffState.PENDING||request.state==StaffState.APPROVED)throw new IOException("Invalid returned staff purchase");
                        for(var origin:r.origins.entrySet()){var funding=d.events.get(origin.getKey());if(!root(funding)||!funding.uuid.equals(r.uuid))throw new IOException("Returned funding belongs to another account");cashRoots.merge(origin.getKey(),origin.getValue(),Math::addExact);}
                        balances.merge(r.uuid,r.available,Math::addExact);
                    }else if(r.kind==Kind.REFUND){
                        var original=d.events.get(r.reference);if(original==null||original.kind!=Kind.CREDIT||!original.uuid.equals(r.uuid)||r.available!=0||r.refunded!=0||!r.origins.isEmpty()||!r.allocations.isEmpty()||!r.product.isEmpty()||r.tier!=Supporter.NONE)throw new IOException("Invalid refund reference");refunds.merge(r.reference,r.units,Math::addExact);
                    }
                }
            }
            // A funding root may move between returned lots, but can never duplicate or remain spendable after its own chargeback.
            for(var entry:d.events.entrySet())if(root(entry.getValue())){
                var r=entry.getValue();long cash=cashRoots.getOrDefault(entry.getKey(),0L);
                if(cash>r.units-r.refunded||cash>0&&r.debt>0||r.refunded!=refunds.getOrDefault(entry.getKey(),0L))throw new IOException("Refunded root still backs duplicate/unpaid cash");
            }
            // Iterative topological validation rejects replacement cycles without recursion on untrusted saved data.
            var ready=new ArrayDeque<String>();for(var e:indegree.entrySet())if(e.getValue()==0)ready.add(e.getKey());int seen=0;
            while(!ready.isEmpty()){String key=ready.removeFirst();seen++;for(String next:d.events.get(key).replacements.keySet()){int degree=indegree.merge(next,-1,Integer::sum);if(degree==0)ready.addLast(next);}}
            if(seen!=indegree.size())throw new IOException("Cyclic refund replacement funding");
            for(var entry:d.staff.entrySet()){
                var r=entry.getValue();var purchase=d.events.get("purchase:"+entry.getKey());
                if(r==null||!UUID.fromString(entry.getKey()).toString().equals(entry.getKey())||r.buyer==null||r.state==null||r.term==null||purchase==null||!purchase.staff||!purchase.uuid.equals(r.buyer)||!purchase.product.equals(r.product)||purchase.units!=r.units||purchase.term!=r.term||purchase.at!=r.purchasedAt||r.state==StaffState.PENDING&&(r.approvedAt!=0||r.expiresAt!=0)||r.state==StaffState.APPROVED&&(r.approvedAt<=0||r.term==Term.PASS&&r.expiresAt<=r.approvedAt)||r.term==Term.LIFETIME&&r.expiresAt!=0||r.state==StaffState.REVOKING&&(r.outcome==null||r.outcome==StaffState.PENDING||r.outcome==StaffState.APPROVED||r.outcome==StaffState.REVOKING))throw new IOException("Invalid staff application");
                boolean terminal=r.state==StaffState.DENIED||r.state==StaffState.REVOKED||r.state==StaffState.EXPIRED;
                if((r.state==StaffState.PENDING||r.state==StaffState.APPROVED)&&(r.outcome!=null||r.refund)||terminal&&(r.outcome!=r.state||r.refund!=d.events.containsKey("staff-return:"+entry.getKey())))throw new IOException("Staff outcome/refund journal mismatch");
            }
            for(var entry:d.wallets.entrySet()){
                var w=entry.getValue();if(w.balance!=balances.getOrDefault(entry.getKey(),0L)||w.debt!=debts.getOrDefault(entry.getKey(),0L)||!BigInteger.valueOf(w.balance).subtract(BigInteger.valueOf(w.debt)).equals(equity.getOrDefault(entry.getKey(),BigInteger.ZERO)))throw new IOException("Wallet balance/debt is not conserved by receipts");
            }
            if(Math.addExact(JSON.toJson(d).getBytes(StandardCharsets.UTF_8).length,reservedBytes(d))>MAX_BYTES)throw new IOException("Ledger does not reserve byte capacity for funded obligations");
        }catch(IllegalArgumentException|ArithmeticException failure){throw new IOException("Invalid ledger arithmetic or identity",failure);}
    }
    static void safePath(Path path)throws IOException{
        for(Path p=path.toAbsolutePath();p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symbolic-link ledger path refused");
    }
    static byte[] read(Path file)throws IOException{
        safePath(file);BasicFileAttributes attributes;
        try{attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException absent){return null;}
        // Access-denied, broken mounts and every other IO failure remain failures, not apparent absence.
        if(!attributes.isRegularFile()||attributes.size()>MAX_BYTES)throw new IOException("Ledger is not a bounded regular file");
        try(var input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=input.readNBytes((int)MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException("Ledger grew beyond its read bound");return bytes;
        }
    }
    static void atomic(Path file,byte[] bytes)throws IOException{
        safePath(file);var parent=file.getParent();if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("Ledger directory is unavailable");
        Path temp=Files.createTempFile(parent,".infini-",".tmp");try{
            try{Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            Files.write(temp,bytes,StandardOpenOption.TRUNCATE_EXISTING);try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            if(!Arrays.equals(bytes,Files.readAllBytes(temp)))throw new IOException("Temporary ledger readback failed");
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var directory=FileChannel.open(parent,StandardOpenOption.READ)){directory.force(true);}
        }finally{Files.deleteIfExists(temp);}
    }
    static int message(net.minecraft.commands.CommandSourceStack source,Result result){source.sendSuccess(()->Component.literal(result.message()),false);return result.success()?1:0;}
    static UUID uuid(String value){try{var id=UUID.fromString(value);return id.toString().equals(value.toLowerCase(Locale.ROOT))?id:null;}catch(IllegalArgumentException failure){return null;}}
    public static void register(){
        InfiniShopMenu.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{synchronized(INSTANCES){var economy=INSTANCES.get(server);if(economy!=null){var quote=economy.quotes.get(handler.player.getUUID());if(quote!=null&&quote.connection()==handler)economy.quotes.remove(handler.player.getUUID(),quote);}}});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(server.getTickCount()%20!=0)return;synchronized(INSTANCES){var economy=INSTANCES.get(server);if(economy!=null)economy.quotes.entrySet().removeIf(e->{var q=e.getValue();int age=server.getTickCount()-q.created();return age<0||age>=1800||server.getPlayerList().getPlayer(e.getKey())!=q.player()||q.player().connection!=q.connection();});}});

        ServerLifecycleEvents.SERVER_STOPPED.register(server->{synchronized(INSTANCES){INSTANCES.remove(server);}});
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->{
            dispatcher.register(Commands.literal("infini").executes(c->InfiniShopMenu.open(c.getSource().getPlayerOrException())));
            var console=Commands.literal("infini-console").requires(AgentActions::localConsole)
                .executes(c->CommunityServer.info(c.getSource(),"Local console only. credit <authenticated-uuid> <unique-event-id> <decimal-infini>; refund <uuid> <unique-refund-id> <original-credit-id> <decimal-infini>. No checkout is connected."));
            console.then(Commands.literal("credit").then(Commands.argument("uuid",StringArgumentType.word()).then(Commands.argument("event",StringArgumentType.word()).then(Commands.argument("units",StringArgumentType.word()).executes(c->message(c.getSource(),get(c.getSource().getServer()).credit(uuid(StringArgumentType.getString(c,"uuid")),StringArgumentType.getString(c,"event"),parse(StringArgumentType.getString(c,"units")))))))));
            console.then(Commands.literal("refund").then(Commands.argument("uuid",StringArgumentType.word()).then(Commands.argument("event",StringArgumentType.word()).then(Commands.argument("original",StringArgumentType.word()).then(Commands.argument("units",StringArgumentType.word()).executes(c->message(c.getSource(),get(c.getSource().getServer()).refund(uuid(StringArgumentType.getString(c,"uuid")),StringArgumentType.getString(c,"event"),StringArgumentType.getString(c,"original"),parse(StringArgumentType.getString(c,"units"))))))))));
            console.then(Commands.literal("staff-list").executes(c->{var economy=get(c.getSource().getServer());for(var request:economy.staffRequests())CommunityServer.info(c.getSource(),request.id()+" • buyer "+request.buyer()+" • "+request.state()+" • "+request.term()+" • "+format(request.units())+" Infini • expiry "+request.expiresAt());return 1;}));
            for(String action:List.of("approve","deny","revoke"))console.then(Commands.literal("staff-"+action).then(Commands.argument("request",StringArgumentType.word()).then(Commands.argument("uuid",StringArgumentType.word()).executes(c->{var economy=get(c.getSource().getServer());String request=StringArgumentType.getString(c,"request");UUID buyer=uuid(StringArgumentType.getString(c,"uuid"));return message(c.getSource(),switch(action){case "approve"->economy.approveStaff(c.getSource(),request,buyer);case "deny"->economy.denyStaff(c.getSource(),request,buyer);default->economy.revokeStaff(c.getSource(),request,buyer);});}))));
            dispatcher.register(console);
        });
    }
}

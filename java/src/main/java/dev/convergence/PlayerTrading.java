package dev.convergence;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;

/** Offers remain in their owner's inventory; a journal joins the two player-file saves. */
final class PlayerTrading {
    static final String RECEIPTS="playerTradeReceipts";
    static final int MAX_OFFERS=18,DISTANCE=8,LIFETIME=2400;
    static final Map<MinecraftServer,PlayerTrading> INSTANCES=new WeakHashMap<>();
    record Offer(int slot,ItemStack original,int count) {
        ItemStack offered() {return original.copyWithCount(count);}
    }
    static final class Session {
        final UUID id=UUID.randomUUID();
        final ServerPlayerEntity first,second;
        final Map<UUID,LinkedHashMap<Integer,Offer>> offers=new HashMap<>();
        final Set<UUID> confirmed=new HashSet<>();
        int expires,version=1;boolean accepted,closing;
        Session(ServerPlayerEntity first,ServerPlayerEntity second,int expires) {
            this.first=first;this.second=second;this.expires=expires;
            offers.put(first.getUuid(),new LinkedHashMap<>());offers.put(second.getUuid(),new LinkedHashMap<>());
        }
        ServerPlayerEntity other(ServerPlayerEntity p) {return p==first?second:first;}
    }
    final MinecraftServer server;final Storage storage;
    final Map<UUID,Session> sessions=new HashMap<>();
    final Set<UUID> locked=new HashSet<>();
    PlayerTrading(MinecraftServer server,Storage storage) {this.server=server;this.storage=storage;}
    static PlayerTrading get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,s->new PlayerTrading(s,new Storage(s,
            s.getSavePath(WorldSavePath.ROOT).resolve("infinity-player-trades"),s.getSavePath(WorldSavePath.PLAYERDATA))));
    }
    static boolean receipt(NbtCompound n,UUID id) {
        return n.getCompoundOrEmpty("InfinityModes").getCompoundOrEmpty(RECEIPTS).getBoolean(id.toString(),false);
    }
    static class Storage {
        final MinecraftServer server;final Path journals,players;
        Storage(MinecraftServer server,Path journals,Path players) {this.server=server;this.journals=journals;this.players=players;}
        void safeDirectory(Path directory) throws IOException {
            if(Files.isSymbolicLink(directory))throw new IOException("Trade storage directory is a symbolic link");
            Files.createDirectories(directory);
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Trade storage is not a directory");
        }
        Path journal(UUID id) {return journals.resolve(id+".nbt");}
        Path player(UUID id) {return players.resolve(id+".dat");}
        NbtCompound read(Path file) throws IOException {
            if(Files.isSymbolicLink(file))throw new IOException("Trade storage file is a symbolic link");
            return NbtIo.readCompressed(file,NbtSizeTracker.of(64L*1024*1024));
        }
        NbtCompound readPlayer(UUID id) throws IOException {return read(player(id));}
        void atomic(Path target,NbtCompound data) throws IOException {
            safeDirectory(target.getParent());
            if(Files.isSymbolicLink(target))throw new IOException("Trade storage file is a symbolic link");
            var temporary=Files.createTempFile(target.getParent(),".trade-",".tmp");
            try {
                NbtIo.writeCompressed(data,temporary);
                try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }finally {Files.deleteIfExists(temporary);}
        }
        void savePlayers(ServerPlayerEntity first,ServerPlayerEntity second) throws IOException {server.getPlayerManager().saveAllPlayerData();}
        void prepare(UUID id,NbtCompound data) throws IOException {atomic(journal(id),data);}
        void remove(UUID id) throws IOException {Files.deleteIfExists(journal(id));}
        void restoreFile(UUID id,NbtCompound snapshot) throws IOException {
            safeDirectory(players);var target=player(id);
            if(Files.isSymbolicLink(target)||Files.isSymbolicLink(players.resolve(id+".dat_old")))throw new IOException("Unsafe player recovery path");
            var temporary=Files.createTempFile(players,".trade-recovery-",".dat");
            try {
                NbtIo.writeCompressed(snapshot,temporary);
                try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                Util.backupAndReplace(target,temporary,players.resolve(id+".dat_old"));
                if(!matches(snapshot,readPlayer(id)))throw new IOException("Player trade recovery did not save");
            }finally {Files.deleteIfExists(temporary);}
        }
        void recover() throws IOException {
            safeDirectory(journals);safeDirectory(players);
            try(var entries=Files.list(journals)) {
                for(var file:entries.sorted().toList()) {
                    String name=file.getFileName().toString();
                    if(Files.isSymbolicLink(file))throw new IOException("Symbolic link in player trade journals");
                    if(name.startsWith(".trade-")&&name.endsWith(".tmp"))continue;
                    if(!name.endsWith(".nbt"))throw new IOException("Unexpected file in player trade journals");
                    var data=read(file);UUID id=UUID.fromString(data.getString("id",""));
                    if(!file.equals(journal(id))||data.getInt("format",0)!=1)throw new IOException("Invalid player trade journal");
                    String phase=data.getString("phase","");
                    if(!phase.equals("forward")&&!phase.equals("rollback"))throw new IOException("Invalid player trade recovery phase");
                    var first=UUID.fromString(data.getString("first",""));var second=UUID.fromString(data.getString("second",""));
                    if(first.equals(second))throw new IOException("Duplicate player in trade journal");
                    recoverPlayer(data,id,first,"First",phase);recoverPlayer(data,id,second,"Second",phase);
                    remove(id);
                }
            }catch(IllegalArgumentException e){throw new IOException("Invalid player trade journal identity",e);}
        }
        void recoverPlayer(NbtCompound data,UUID transaction,UUID participant,String suffix,String phase) throws IOException {
            var before=data.getCompound("before"+suffix).orElseThrow(()->new IOException("Missing trade before snapshot"));
            var after=data.getCompound("after"+suffix).orElseThrow(()->new IOException("Missing trade after snapshot"));
            if(!before.contains("Inventory")||!after.contains("Inventory")||!identity(before,participant)||!identity(after,participant)
                ||receipt(before,transaction)||!receipt(after,transaction))
                throw new IOException("Invalid trade player snapshots");
            var file=player(participant);
            NbtCompound current=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?readPlayer(participant):null;
            if(phase.equals("forward")) {
                if(current==null||!receipt(current,transaction))restoreFile(participant,after);
            }else if(current==null||receipt(current,transaction))restoreFile(participant,before);
        }
    }
    static boolean identity(NbtCompound snapshot,UUID expected) {
        var bits=snapshot.getIntArray("UUID").orElse(new int[0]);if(bits.length!=4)return false;
        return expected.equals(new UUID(((long)bits[0]<<32)|(bits[1]&0xffffffffL),((long)bits[2]<<32)|(bits[3]&0xffffffffL)));
    }
    static boolean matches(NbtCompound expected,NbtCompound actual) {
        return Objects.equals(expected.get("Inventory"),actual.get("Inventory"))&&Objects.equals(expected.get("InfinityModes"),actual.get("InfinityModes"));
    }
    boolean eligible(ServerPlayerEntity p) {
        boolean screen=p.currentScreenHandler==p.playerScreenHandler
            ||p.currentScreenHandler instanceof ReviewHandler review&&review.owner==this&&review.session==sessions.get(p.getUuid());
        return !locked.contains(p.getUuid())&&p.isAlive()&&GameModes.current(p)==GameModes.Mode.SURVIVAL&&p.getGameMode()==GameMode.SURVIVAL
            &&screen&&p.currentScreenHandler.getCursorStack().isEmpty()&&!GameModes.PENDING.containsKey(p.getUuid())
            &&!GameModes.TRANSITIONS.contains(p.getUuid())&&!GameModes.OPERATOR_TRANSFERS.containsKey(p.getUuid())
            &&CommunityServer.get(server).combat.getOrDefault(p.getUuid(),0)<=server.getTicks();
    }
    boolean valid(Session s) {
        return eligible(s.first)&&eligible(s.second)&&s.first.getEntityWorld()==s.second.getEntityWorld()
            &&s.first.squaredDistanceTo(s.second)<=DISTANCE*DISTANCE&&server.getTicks()<s.expires
            &&server.getPlayerManager().getPlayer(s.first.getUuid())==s.first&&server.getPlayerManager().getPlayer(s.second.getUuid())==s.second;
    }
    boolean request(ServerPlayerEntity sender,ServerPlayerEntity target) {
        if(sender==target||sessions.containsKey(sender.getUuid())||sessions.containsKey(target.getUuid())||!eligible(sender)||!eligible(target)
            ||sender.getEntityWorld()!=target.getEntityWorld()||sender.squaredDistanceTo(target)>DISTANCE*DISTANCE) {
            CommunityServer.say(sender,"Both players must be nearby in Survival, outside combat, with other storage closed and empty cursors. Finish any existing trade first.");return false;
        }
        var s=new Session(sender,target,server.getTicks()+LIFETIME);sessions.put(sender.getUuid(),s);sessions.put(target.getUuid(),s);
        CommunityServer.say(sender,"Trade requested. No items move until both players confirm the same offers.");
        CommunityServer.say(target,sender.getNameForScoreboard()+" wants to trade. Use /ptrade accept or /ptrade cancel.");return true;
    }
    boolean accept(ServerPlayerEntity p) {
        var s=sessions.get(p.getUuid());
        if(s==null||s.second!=p||s.accepted||!valid(s)){CommunityServer.say(p,"No valid incoming trade. Close other storage and use /ptrade <player>.");return false;}
        s.accepted=true;show(s.first);show(s.second);return true;
    }
    boolean offer(ServerPlayerEntity p,int slot,int count) {
        var s=sessions.get(p.getUuid());
        if(s==null||!s.accepted||!valid(s)||slot<0||slot>=PlayerInventory.MAIN_SIZE){CommunityServer.say(p,"Accept a valid nearby trade before offering inventory slots.");return false;}
        var stack=p.getInventory().getStack(slot);var offered=s.offers.get(p.getUuid());
        if(stack.isEmpty()||count<1||count>stack.getCount()||stack.getCount()>stack.getMaxCount()
            ||count>stack.getMaxCount()||!offered.containsKey(slot)&&offered.size()>=MAX_OFFERS) {
            CommunityServer.say(p,"Choose a nonempty main-inventory slot and a valid count. A trade supports 18 offered slots per player.");return false;
        }
        offered.put(slot,new Offer(slot,stack.copy(),count));s.confirmed.clear();s.version++;reopen(s);
        CommunityServer.say(s.other(p),"The offer changed. Both players must review and confirm again.");return true;
    }
    boolean remove(ServerPlayerEntity p,int slot) {
        var s=sessions.get(p.getUuid());if(s==null||!s.accepted||s.offers.get(p.getUuid()).remove(slot)==null)return false;
        s.confirmed.clear();s.version++;reopen(s);CommunityServer.say(s.other(p),"The offer changed. Confirmations were cleared.");return true;
    }
    boolean offeredItemsMatch(Session s) {
        for(var p:List.of(s.first,s.second))for(var offer:s.offers.get(p.getUuid()).values()) {
            var current=p.getInventory().getStack(offer.slot());
            if(current.getCount()!=offer.original().getCount()||!ItemStack.areItemsAndComponentsEqual(current,offer.original()))return false;
        }
        return true;
    }
    static List<ItemStack> inventory(ServerPlayerEntity p) {return p.getInventory().getMainStacks().stream().map(ItemStack::copy).toList();}
    static void apply(ServerPlayerEntity p,List<ItemStack> inventory) {for(int i=0;i<inventory.size();i++)p.getInventory().setStack(i,inventory.get(i).copy());p.getInventory().markDirty();}
    static boolean insert(List<ItemStack> inventory,ItemStack offered) {
        var remaining=offered.copy();
        for(var stack:inventory)if(!stack.isEmpty()&&ItemStack.areItemsAndComponentsEqual(stack,remaining)) {
            int move=Math.min(remaining.getCount(),Math.max(0,stack.getMaxCount()-stack.getCount()));stack.increment(move);remaining.decrement(move);
            if(remaining.isEmpty())return true;
        }
        for(int i=0;i<inventory.size();i++)if(inventory.get(i).isEmpty()) {
            int move=Math.min(remaining.getCount(),remaining.getMaxCount());inventory.set(i,remaining.copyWithCount(move));remaining.decrement(move);
            if(remaining.isEmpty())return true;
        }
        return remaining.isEmpty();
    }
    static List<ItemStack> simulate(Session s,ServerPlayerEntity p) {
        var result=new ArrayList<>(inventory(p));
        for(var offer:s.offers.get(p.getUuid()).values())result.get(offer.slot()).decrement(offer.count());
        for(var offer:s.offers.get(s.other(p).getUuid()).values())if(!insert(result,offer.offered()))return null;
        return result;
    }
    boolean confirm(ServerPlayerEntity p,int version) {
        var s=sessions.get(p.getUuid());
        if(s==null||!s.accepted||!valid(s)||!offeredItemsMatch(s)){if(s!=null)cancel(s,"Trade conditions or offered items changed. No items were moved.");return false;}
        if(version!=s.version){CommunityServer.say(p,"That confirmation is for an old offer. Review revision "+s.version+" and confirm again.");return false;}
        if(s.offers.get(s.first.getUuid()).isEmpty()&&s.offers.get(s.second.getUuid()).isEmpty()){CommunityServer.say(p,"Add an offer before confirming.");return false;}
        s.confirmed.add(p.getUuid());refresh(s);
        if(s.confirmed.size()<2){CommunityServer.say(p,"You confirmed. Waiting for the other player's confirmation.");return true;}
        var first=simulate(s,s.first);var second=simulate(s,s.second);
        if(first==null||second==null){s.confirmed.clear();refresh(s);CommunityServer.say(s.first,"Trade needs more empty inventory space. No items moved; review and confirm again.");CommunityServer.say(s.second,"Trade needs more empty inventory space. No items moved; review and confirm again.");return false;}
        return commit(s,first,second);
    }
    void mark(ServerPlayerEntity p,UUID id) {
        var state=GameModes.state(p);var receipts=state.getCompoundOrEmpty(RECEIPTS).copy();receipts.putBoolean(id.toString(),true);state.put(RECEIPTS,receipts);
    }
    boolean commit(Session s,List<ItemStack> afterFirst,List<ItemStack> afterSecond) {
        var originalFirst=inventory(s.first);var originalSecond=inventory(s.second);
        var stateFirst=GameModes.state(s.first).copy();var stateSecond=GameModes.state(s.second).copy();
        var beforeFirst=RewardTrades.serialize(s.first);var beforeSecond=RewardTrades.serialize(s.second);
        var journal=new NbtCompound();journal.putInt("format",1);journal.putString("id",s.id.toString());journal.putString("phase","forward");
        journal.putString("first",s.first.getUuidAsString());journal.putString("second",s.second.getUuidAsString());
        journal.put("beforeFirst",beforeFirst);journal.put("beforeSecond",beforeSecond);
        try {
            // Build after images without permitting a tick, packet, or player save between them.
            apply(s.first,afterFirst);apply(s.second,afterSecond);mark(s.first,s.id);mark(s.second,s.id);
            var expectedFirst=RewardTrades.serialize(s.first);var expectedSecond=RewardTrades.serialize(s.second);
            journal.put("afterFirst",expectedFirst);journal.put("afterSecond",expectedSecond);
            apply(s.first,originalFirst);apply(s.second,originalSecond);((ModePlayer)s.first).infinity$state(stateFirst.copy());((ModePlayer)s.second).infinity$state(stateSecond.copy());
            storage.prepare(s.id,journal);
            apply(s.first,afterFirst);apply(s.second,afterSecond);mark(s.first,s.id);mark(s.second,s.id);
            storage.savePlayers(s.first,s.second);
            if(!matches(expectedFirst,storage.readPlayer(s.first.getUuid()))||!matches(expectedSecond,storage.readPlayer(s.second.getUuid())))
                throw new IOException("Player trade saves could not be verified");
            try{storage.remove(s.id);}catch(IOException ignored){ /* Receipts make startup recovery idempotent. */ }
            RewardTrades.syncInventory(s.first);RewardTrades.syncInventory(s.second);cancel(s,"Trade completed. Both players received the agreed items.");return true;
        }catch(Exception failure) {
            boolean prepared=Files.exists(storage.journal(s.id),LinkOption.NOFOLLOW_LINKS);boolean rollback=!prepared;
            if(prepared)try{journal.putString("phase","rollback");storage.prepare(s.id,journal);rollback=true;}catch(Exception ignored){ }
            if(rollback) {
                apply(s.first,originalFirst);apply(s.second,originalSecond);((ModePlayer)s.first).infinity$state(stateFirst);((ModePlayer)s.second).infinity$state(stateSecond);
                boolean durable=false;
                try {
                    storage.savePlayers(s.first,s.second);
                    if(matches(beforeFirst,storage.readPlayer(s.first.getUuid()))&&matches(beforeSecond,storage.readPlayer(s.second.getUuid()))) {durable=true;storage.remove(s.id);}
                }catch(Exception ignored){ /* Rollback journal repairs the matching player files before next login. */ }
                RewardTrades.syncInventory(s.first);RewardTrades.syncInventory(s.second);cancel(s,"Trade could not be saved. Your original inventories were restored. Ask the owner to check storage.");
                if(prepared&&!durable)lock(s);return false;
            }
            // A durable forward journal cannot safely be revoked when storage is unavailable.
            // Keep its matching after images and prevent more gameplay until startup recovery.
            apply(s.first,afterFirst);apply(s.second,afterSecond);mark(s.first,s.id);mark(s.second,s.id);
            cancel(s,"Trade storage needs recovery. Both players must reconnect after the owner restarts the server.");lock(s);
            System.err.println("[Infinity] Player trade recovery required: "+s.id+" ("+failure.getClass().getSimpleName()+")");return false;
        }
    }
    void lock(Session s) {
        for(var p:List.of(s.first,s.second)){locked.add(p.getUuid());p.networkHandler.disconnect(Text.literal("Trade storage recovery required. Ask the owner to restart the server."));}
    }
    static ItemStack icon(net.minecraft.item.Item item,String name) {var stack=new ItemStack(item);stack.set(DataComponentTypes.CUSTOM_NAME,Text.literal(name));return stack;}
    void fill(Session s,ServerPlayerEntity viewer,SimpleInventory view) {
        view.clear();int index=0;for(var offer:s.offers.get(viewer.getUuid()).values())view.setStack(index++,offer.offered());
        index=27;for(var offer:s.offers.get(s.other(viewer).getUuid()).values())view.setStack(index++,offer.offered());
        view.setStack(18,icon(Items.PAPER,"Your offer (rows 1-2)"));view.setStack(26,icon(Items.PAPER,s.other(viewer).getNameForScoreboard()+"'s offer (rows 4-5)"));
        view.setStack(20,icon(s.confirmed.contains(viewer.getUuid())?Items.LIME_WOOL:Items.YELLOW_WOOL,"You: "+(s.confirmed.contains(viewer.getUuid())?"confirmed":"reviewing")));
        view.setStack(22,icon(s.confirmed.contains(s.other(viewer).getUuid())?Items.LIME_WOOL:Items.YELLOW_WOOL,"Partner: "+(s.confirmed.contains(s.other(viewer).getUuid())?"confirmed":"reviewing")));
        view.setStack(49,icon(Items.LIME_WOOL,"Confirm offer revision "+s.version));view.setStack(53,icon(Items.RED_WOOL,"Cancel trade"));
    }
    void refresh(Session s) {
        for(var p:List.of(s.first,s.second))if(p.currentScreenHandler instanceof ReviewHandler handler&&handler.session==s){fill(s,p,handler.view);handler.syncState();}
    }
    void reopen(Session s) {
        s.closing=true;
        try{for(var p:List.of(s.first,s.second))if(p.currentScreenHandler instanceof ReviewHandler handler&&handler.session==s)p.closeHandledScreen();}
        finally{s.closing=false;}
        show(s.first);show(s.second);
    }
    boolean show(ServerPlayerEntity p) {
        var s=sessions.get(p.getUuid());if(s==null||!s.accepted||!valid(s))return false;
        if(p.currentScreenHandler instanceof ReviewHandler){refresh(s);return true;}
        var view=new SimpleInventory(54);fill(s,p,view);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync,inventory,who)->new ReviewHandler(sync,inventory,view,this,s),Text.literal("Trade with "+s.other(p).getNameForScoreboard())));
        CommunityServer.say(p,"Rows 1-2: your offer. Rows 4-5: partner's offer. /ptrade offer <slot 1-36> <count>; /ptrade remove <slot>. Green Confirm or /ptrade confirm "+s.version+"; red Cancel.");return true;
    }
    static final class ReviewHandler extends GenericContainerScreenHandler {
        final PlayerTrading owner;final Session session;final SimpleInventory view;final int version;
        ReviewHandler(int sync,PlayerInventory inventory,SimpleInventory view,PlayerTrading owner,Session session) {
            super(ScreenHandlerType.GENERIC_9X6,sync,inventory,view,6);this.view=view;this.owner=owner;this.session=session;version=session.version;
        }
        @Override public boolean canUse(PlayerEntity p) {return p instanceof ServerPlayerEntity sp&&owner.sessions.get(sp.getUuid())==session&&owner.valid(session);}
        @Override public ItemStack quickMove(PlayerEntity p,int slot) {return ItemStack.EMPTY;}
        @Override public void selectBundleStack(int slot,int selected) { }
        @Override public void onSlotClick(int slot,int button,SlotActionType action,PlayerEntity p) {
            if(!(p instanceof ServerPlayerEntity sp)||owner.sessions.get(sp.getUuid())!=session)return;
            if(action==SlotActionType.PICKUP&&slot==49)owner.confirm(sp,version);
            else if(action==SlotActionType.PICKUP&&slot==53)owner.cancel(session,"Trade canceled. No items were moved.");
            else syncState();
        }
        @Override public void onClosed(PlayerEntity p) {
            super.onClosed(p);if(!session.closing)owner.cancel(session,"Trade review closed. No items were moved.");
        }
    }
    void cancel(Session s,String message) {
        if(s.closing)return;s.closing=true;sessions.remove(s.first.getUuid(),s);sessions.remove(s.second.getUuid(),s);
        for(var p:List.of(s.first,s.second)){if(p.currentScreenHandler instanceof ReviewHandler handler&&handler.session==s)p.closeHandledScreen();CommunityServer.say(p,message);}
    }
    void tick() {
        for(var s:new HashSet<>(sessions.values()))if(!valid(s)||s.accepted&&!offeredItemsMatch(s))cancel(s,"Trade canceled because a player moved, left Survival, entered combat, or changed an offered item. No items were moved.");
    }
    static void recover(MinecraftServer server) {
        try{get(server).storage.recover();}
        catch(Exception e){throw new IllegalStateException("Player trade recovery failed. Keep infinity-player-trades and playerdata intact; stop the server and restore a matching world backup or fix storage before players join.",e);}
    }
    void departing(ServerPlayerEntity p) {var s=sessions.get(p.getUuid());if(s!=null)cancel(s,"A player left or died. Trade canceled without moving items.");}
    static void leave(ServerPlayerEntity p) {var manager=INSTANCES.get(p.getEntityWorld().getServer());if(manager!=null)manager.departing(p);}
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerTrading::recover);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{var manager=INSTANCES.get(server);if(manager!=null)for(var session:new HashSet<>(manager.sessions.values()))manager.cancel(session,"Server stopping. Trade canceled.");});
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server->get(server).tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->leave(handler.player));
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(get(server).locked.contains(handler.player.getUuid()))handler.disconnect(Text.literal("Trade recovery requires a server restart."));});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(entity instanceof ServerPlayerEntity p)leave(p);});
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            var command=CommandManager.literal("ptrade").executes(c->CommunityServer.info(c.getSource(),"/ptrade <player>, accept, offer <slot 1-36> <count>, remove <slot>, review, confirm <revision>, cancel. Both players must confirm the current offers."));
            command.then(CommandManager.argument("player",EntityArgumentType.player()).executes(c->get(c.getSource().getServer()).request(c.getSource().getPlayerOrThrow(),EntityArgumentType.getPlayer(c,"player"))?1:0));
            command.then(CommandManager.literal("accept").executes(c->get(c.getSource().getServer()).accept(c.getSource().getPlayerOrThrow())?1:0));
            command.then(CommandManager.literal("offer").then(CommandManager.argument("slot",IntegerArgumentType.integer(1,36)).then(CommandManager.argument("count",IntegerArgumentType.integer(1,64)).executes(c->get(c.getSource().getServer()).offer(c.getSource().getPlayerOrThrow(),IntegerArgumentType.getInteger(c,"slot")-1,IntegerArgumentType.getInteger(c,"count"))?1:0))));
            command.then(CommandManager.literal("remove").then(CommandManager.argument("slot",IntegerArgumentType.integer(1,36)).executes(c->get(c.getSource().getServer()).remove(c.getSource().getPlayerOrThrow(),IntegerArgumentType.getInteger(c,"slot")-1)?1:0)));
            command.then(CommandManager.literal("confirm").executes(c->{var s=get(c.getSource().getServer()).sessions.get(c.getSource().getPlayerOrThrow().getUuid());return CommunityServer.info(c.getSource(),s==null?"No active trade.":"Review the chest and use /ptrade confirm "+s.version);})
                .then(CommandManager.argument("revision",IntegerArgumentType.integer(1)).executes(c->get(c.getSource().getServer()).confirm(c.getSource().getPlayerOrThrow(),IntegerArgumentType.getInteger(c,"revision"))?1:0)));
            command.then(CommandManager.literal("review").executes(c->get(c.getSource().getServer()).show(c.getSource().getPlayerOrThrow())?1:0));
            command.then(CommandManager.literal("cancel").executes(c->{var manager=get(c.getSource().getServer());var s=manager.sessions.get(c.getSource().getPlayerOrThrow().getUuid());if(s!=null)manager.cancel(s,"Trade canceled. No items were moved.");return 1;}));
            dispatcher.register(command);
        });
    }
}

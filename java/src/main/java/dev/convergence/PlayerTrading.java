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
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

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
        final ServerPlayer first,second;
        final Map<UUID,LinkedHashMap<Integer,Offer>> offers=new HashMap<>();
        final Set<UUID> confirmed=new HashSet<>();
        int expires,version=1;boolean accepted,closing;
        Session(ServerPlayer first,ServerPlayer second,int expires) {
            this.first=first;this.second=second;this.expires=expires;
            offers.put(first.getUUID(),new LinkedHashMap<>());offers.put(second.getUUID(),new LinkedHashMap<>());
        }
        ServerPlayer other(ServerPlayer p) {return p==first?second:first;}
    }
    final MinecraftServer server;final Storage storage;
    final Map<UUID,Session> sessions=new HashMap<>();
    final Set<UUID> locked=new HashSet<>();
    PlayerTrading(MinecraftServer server,Storage storage) {this.server=server;this.storage=storage;}
    static PlayerTrading get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,s->new PlayerTrading(s,new Storage(s,
            s.getWorldPath(LevelResource.ROOT).resolve("infinity-player-trades"),s.getWorldPath(LevelResource.PLAYER_DATA_DIR))));
    }
    static boolean receipt(CompoundTag n,UUID id) {
        return n.getCompoundOrEmpty("InfinityModes").getCompoundOrEmpty(RECEIPTS).getBooleanOr(id.toString(),false);
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
        CompoundTag read(Path file) throws IOException {
            if(Files.isSymbolicLink(file))throw new IOException("Trade storage file is a symbolic link");
            return NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));
        }
        CompoundTag readPlayer(UUID id) throws IOException {return read(player(id));}
        void atomic(Path target,CompoundTag data) throws IOException {
            safeDirectory(target.getParent());
            if(Files.isSymbolicLink(target))throw new IOException("Trade storage file is a symbolic link");
            var temporary=Files.createTempFile(target.getParent(),".trade-",".tmp");
            try {
                NbtIo.writeCompressed(data,temporary);
                try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }finally {Files.deleteIfExists(temporary);}
        }
        void savePlayers(ServerPlayer first,ServerPlayer second) throws IOException {server.getPlayerList().saveAll();}
        void prepare(UUID id,CompoundTag data) throws IOException {atomic(journal(id),data);}
        void remove(UUID id) throws IOException {Files.deleteIfExists(journal(id));}
        void restoreFile(UUID id,CompoundTag snapshot) throws IOException {
            safeDirectory(players);var target=player(id);
            if(Files.isSymbolicLink(target)||Files.isSymbolicLink(players.resolve(id+".dat_old")))throw new IOException("Unsafe player recovery path");
            var temporary=Files.createTempFile(players,".trade-recovery-",".dat");
            try {
                NbtIo.writeCompressed(snapshot,temporary);
                try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
                Util.safeReplaceFile(target,temporary,players.resolve(id+".dat_old"));
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
                    var data=read(file);UUID id=UUID.fromString(data.getStringOr("id",""));
                    if(!file.equals(journal(id))||data.getIntOr("format",0)!=1)throw new IOException("Invalid player trade journal");
                    String phase=data.getStringOr("phase","");
                    if(!phase.equals("forward")&&!phase.equals("rollback"))throw new IOException("Invalid player trade recovery phase");
                    var first=UUID.fromString(data.getStringOr("first",""));var second=UUID.fromString(data.getStringOr("second",""));
                    if(first.equals(second))throw new IOException("Duplicate player in trade journal");
                    recoverPlayer(data,id,first,"First",phase);recoverPlayer(data,id,second,"Second",phase);
                    remove(id);
                }
            }catch(IllegalArgumentException e){throw new IOException("Invalid player trade journal identity",e);}
        }
        void recoverPlayer(CompoundTag data,UUID transaction,UUID participant,String suffix,String phase) throws IOException {
            var before=data.getCompound("before"+suffix).orElseThrow(()->new IOException("Missing trade before snapshot"));
            var after=data.getCompound("after"+suffix).orElseThrow(()->new IOException("Missing trade after snapshot"));
            if(!before.contains("Inventory")||!after.contains("Inventory")||!identity(before,participant)||!identity(after,participant)
                ||receipt(before,transaction)||!receipt(after,transaction))
                throw new IOException("Invalid trade player snapshots");
            var file=player(participant);
            CompoundTag current=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?readPlayer(participant):null;
            if(phase.equals("forward")) {
                if(current==null||!receipt(current,transaction))restoreFile(participant,after);
            }else if(current==null||receipt(current,transaction))restoreFile(participant,before);
        }
    }
    static boolean identity(CompoundTag snapshot,UUID expected) {
        var bits=snapshot.getIntArray("UUID").orElse(new int[0]);if(bits.length!=4)return false;
        return expected.equals(new UUID(((long)bits[0]<<32)|(bits[1]&0xffffffffL),((long)bits[2]<<32)|(bits[3]&0xffffffffL)));
    }
    static boolean matches(CompoundTag expected,CompoundTag actual) {
        return Objects.equals(expected.get("Inventory"),actual.get("Inventory"))&&Objects.equals(expected.get("InfinityModes"),actual.get("InfinityModes"));
    }
    boolean eligible(ServerPlayer p) {
        boolean screen=p.containerMenu==p.inventoryMenu
            ||p.containerMenu instanceof ReviewHandler review&&review.owner==this&&review.session==sessions.get(p.getUUID());
        return !locked.contains(p.getUUID())&&p.isAlive()&&GameModes.current(p)==GameModes.Mode.SURVIVAL&&p.gameMode()==GameType.SURVIVAL
            &&screen&&p.containerMenu.getCarried().isEmpty()&&!GameModes.PENDING.containsKey(p.getUUID())
            &&!GameModes.TRANSITIONS.contains(p.getUUID())&&!GameModes.OPERATOR_TRANSFERS.containsKey(p.getUUID())
            &&CommunityServer.get(server).combat.getOrDefault(p.getUUID(),0)<=server.getTickCount();
    }
    boolean valid(Session s) {
        return eligible(s.first)&&eligible(s.second)&&s.first.level()==s.second.level()
            &&s.first.distanceToSqr(s.second)<=DISTANCE*DISTANCE&&server.getTickCount()<s.expires
            &&server.getPlayerList().getPlayer(s.first.getUUID())==s.first&&server.getPlayerList().getPlayer(s.second.getUUID())==s.second;
    }
    boolean request(ServerPlayer sender,ServerPlayer target) {
        if(sender==target||sessions.containsKey(sender.getUUID())||sessions.containsKey(target.getUUID())||!eligible(sender)||!eligible(target)
            ||sender.level()!=target.level()||sender.distanceToSqr(target)>DISTANCE*DISTANCE) {
            CommunityServer.say(sender,"Both players must be nearby in Survival, outside combat, with other storage closed and empty cursors. Finish any existing trade first.");return false;
        }
        var s=new Session(sender,target,server.getTickCount()+LIFETIME);sessions.put(sender.getUUID(),s);sessions.put(target.getUUID(),s);
        CommunityServer.say(sender,"Trade requested. No items move until both players confirm the same offers.");
        CommunityServer.say(target,sender.getScoreboardName()+" wants to trade. Use /ptrade accept or /ptrade cancel.");return true;
    }
    boolean accept(ServerPlayer p) {
        var s=sessions.get(p.getUUID());
        if(s==null||s.second!=p||s.accepted||!valid(s)){CommunityServer.say(p,"No valid incoming trade. Close other storage and use /ptrade <player>.");return false;}
        s.accepted=true;show(s.first);show(s.second);return true;
    }
    boolean offer(ServerPlayer p,int slot,int count) {
        var s=sessions.get(p.getUUID());
        if(s==null||!s.accepted||!valid(s)||slot<0||slot>=Inventory.INVENTORY_SIZE){CommunityServer.say(p,"Accept a valid nearby trade before offering inventory slots.");return false;}
        var stack=p.getInventory().getItem(slot);var offered=s.offers.get(p.getUUID());
        if(stack.isEmpty()||count<1||count>stack.getCount()||stack.getCount()>stack.getMaxStackSize()
            ||count>stack.getMaxStackSize()||!offered.containsKey(slot)&&offered.size()>=MAX_OFFERS) {
            CommunityServer.say(p,"Choose a nonempty main-inventory slot and a valid count. A trade supports 18 offered slots per player.");return false;
        }
        offered.put(slot,new Offer(slot,stack.copy(),count));s.confirmed.clear();s.version++;reopen(s);
        CommunityServer.say(s.other(p),"The offer changed. Both players must review and confirm again.");return true;
    }
    boolean remove(ServerPlayer p,int slot) {
        var s=sessions.get(p.getUUID());if(s==null||!s.accepted||s.offers.get(p.getUUID()).remove(slot)==null)return false;
        s.confirmed.clear();s.version++;reopen(s);CommunityServer.say(s.other(p),"The offer changed. Confirmations were cleared.");return true;
    }
    boolean offeredItemsMatch(Session s) {
        for(var p:List.of(s.first,s.second))for(var offer:s.offers.get(p.getUUID()).values()) {
            var current=p.getInventory().getItem(offer.slot());
            if(current.getCount()!=offer.original().getCount()||!ItemStack.isSameItemSameComponents(current,offer.original()))return false;
        }
        return true;
    }
    static List<ItemStack> inventory(ServerPlayer p) {return p.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList();}
    static void apply(ServerPlayer p,List<ItemStack> inventory) {for(int i=0;i<inventory.size();i++)p.getInventory().setItem(i,inventory.get(i).copy());p.getInventory().setChanged();}
    static boolean insert(List<ItemStack> inventory,ItemStack offered) {
        var remaining=offered.copy();
        for(var stack:inventory)if(!stack.isEmpty()&&ItemStack.isSameItemSameComponents(stack,remaining)) {
            int move=Math.min(remaining.getCount(),Math.max(0,stack.getMaxStackSize()-stack.getCount()));stack.grow(move);remaining.shrink(move);
            if(remaining.isEmpty())return true;
        }
        for(int i=0;i<inventory.size();i++)if(inventory.get(i).isEmpty()) {
            int move=Math.min(remaining.getCount(),remaining.getMaxStackSize());inventory.set(i,remaining.copyWithCount(move));remaining.shrink(move);
            if(remaining.isEmpty())return true;
        }
        return remaining.isEmpty();
    }
    static List<ItemStack> simulate(Session s,ServerPlayer p) {
        var result=new ArrayList<>(inventory(p));
        for(var offer:s.offers.get(p.getUUID()).values())result.get(offer.slot()).shrink(offer.count());
        for(var offer:s.offers.get(s.other(p).getUUID()).values())if(!insert(result,offer.offered()))return null;
        return result;
    }
    boolean confirm(ServerPlayer p,int version) {
        var s=sessions.get(p.getUUID());
        if(s==null||!s.accepted||!valid(s)||!offeredItemsMatch(s)){if(s!=null)cancel(s,"Trade conditions or offered items changed. No items were moved.");return false;}
        if(version!=s.version){CommunityServer.say(p,"That confirmation is for an old offer. Review revision "+s.version+" and confirm again.");return false;}
        if(s.offers.get(s.first.getUUID()).isEmpty()&&s.offers.get(s.second.getUUID()).isEmpty()){CommunityServer.say(p,"Add an offer before confirming.");return false;}
        s.confirmed.add(p.getUUID());refresh(s);
        if(s.confirmed.size()<2){CommunityServer.say(p,"You confirmed. Waiting for the other player's confirmation.");return true;}
        var first=simulate(s,s.first);var second=simulate(s,s.second);
        if(first==null||second==null){s.confirmed.clear();refresh(s);CommunityServer.say(s.first,"Trade needs more empty inventory space. No items moved; review and confirm again.");CommunityServer.say(s.second,"Trade needs more empty inventory space. No items moved; review and confirm again.");return false;}
        return commit(s,first,second);
    }
    void mark(ServerPlayer p,UUID id) {
        var state=GameModes.state(p);var receipts=state.getCompoundOrEmpty(RECEIPTS).copy();receipts.putBoolean(id.toString(),true);state.put(RECEIPTS,receipts);
    }
    boolean commit(Session s,List<ItemStack> afterFirst,List<ItemStack> afterSecond) {
        var originalFirst=inventory(s.first);var originalSecond=inventory(s.second);
        var stateFirst=GameModes.state(s.first).copy();var stateSecond=GameModes.state(s.second).copy();
        var beforeFirst=RewardTrades.serialize(s.first);var beforeSecond=RewardTrades.serialize(s.second);
        var journal=new CompoundTag();journal.putInt("format",1);journal.putString("id",s.id.toString());journal.putString("phase","forward");
        journal.putString("first",s.first.getStringUUID());journal.putString("second",s.second.getStringUUID());
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
            if(!matches(expectedFirst,storage.readPlayer(s.first.getUUID()))||!matches(expectedSecond,storage.readPlayer(s.second.getUUID())))
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
                    if(matches(beforeFirst,storage.readPlayer(s.first.getUUID()))&&matches(beforeSecond,storage.readPlayer(s.second.getUUID()))) {durable=true;storage.remove(s.id);}
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
        for(var p:List.of(s.first,s.second)){locked.add(p.getUUID());p.connection.disconnect(Component.literal("Trade storage recovery required. Ask the owner to restart the server."));}
    }
    static ItemStack icon(net.minecraft.world.item.Item item,String name) {var stack=new ItemStack(item);stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));return stack;}
    void fill(Session s,ServerPlayer viewer,SimpleContainer view) {
        view.clearContent();int index=0;for(var offer:s.offers.get(viewer.getUUID()).values())view.setItem(index++,offer.offered());
        index=27;for(var offer:s.offers.get(s.other(viewer).getUUID()).values())view.setItem(index++,offer.offered());
        view.setItem(18,icon(Items.PAPER,"Your offer (rows 1-2)"));view.setItem(26,icon(Items.PAPER,s.other(viewer).getScoreboardName()+"'s offer (rows 4-5)"));
        view.setItem(20,icon(s.confirmed.contains(viewer.getUUID())?Items.WOOL.lime():Items.WOOL.yellow(),"You: "+(s.confirmed.contains(viewer.getUUID())?"confirmed":"reviewing")));
        view.setItem(22,icon(s.confirmed.contains(s.other(viewer).getUUID())?Items.WOOL.lime():Items.WOOL.yellow(),"Partner: "+(s.confirmed.contains(s.other(viewer).getUUID())?"confirmed":"reviewing")));
        view.setItem(49,icon(Items.WOOL.lime(),"Confirm offer revision "+s.version));view.setItem(53,icon(Items.WOOL.red(),"Cancel trade"));
    }
    void refresh(Session s) {
        for(var p:List.of(s.first,s.second))if(p.containerMenu instanceof ReviewHandler handler&&handler.session==s){fill(s,p,handler.view);handler.sendAllDataToRemote();}
    }
    void reopen(Session s) {
        s.closing=true;
        try{for(var p:List.of(s.first,s.second))if(p.containerMenu instanceof ReviewHandler handler&&handler.session==s)p.closeContainer();}
        finally{s.closing=false;}
        show(s.first);show(s.second);
    }
    boolean show(ServerPlayer p) {
        var s=sessions.get(p.getUUID());if(s==null||!s.accepted||!valid(s))return false;
        if(p.containerMenu instanceof ReviewHandler){refresh(s);return true;}
        var view=new SimpleContainer(54);fill(s,p,view);
        p.openMenu(new SimpleMenuProvider((sync,inventory,who)->new ReviewHandler(sync,inventory,view,this,s),Component.literal("Trade with "+s.other(p).getScoreboardName())));
        CommunityServer.say(p,"Rows 1-2: your offer. Rows 4-5: partner's offer. /ptrade offer <slot 1-36> <count>; /ptrade remove <slot>. Green Confirm or /ptrade confirm "+s.version+"; red Cancel.");return true;
    }
    static final class ReviewHandler extends ChestMenu {
        final PlayerTrading owner;final Session session;final SimpleContainer view;final int version;
        ReviewHandler(int sync,Inventory inventory,SimpleContainer view,PlayerTrading owner,Session session) {
            super(MenuType.GENERIC_9x6,sync,inventory,view,6);this.view=view;this.owner=owner;this.session=session;version=session.version;
        }
        @Override public boolean stillValid(Player p) {return p instanceof ServerPlayer sp&&owner.sessions.get(sp.getUUID())==session&&owner.valid(session);}
        @Override public ItemStack quickMoveStack(Player p,int slot) {return ItemStack.EMPTY;}
        @Override public void setSelectedBundleItemIndex(int slot,int selected) { }
        @Override public void clicked(int slot,int button,ContainerInput action,Player p) {
            if(!(p instanceof ServerPlayer sp)||owner.sessions.get(sp.getUUID())!=session)return;
            if(action==ContainerInput.PICKUP&&slot==49)owner.confirm(sp,version);
            else if(action==ContainerInput.PICKUP&&slot==53)owner.cancel(session,"Trade canceled. No items were moved.");
            else sendAllDataToRemote();
        }
        @Override public void removed(Player p) {
            super.removed(p);if(!session.closing)owner.cancel(session,"Trade review closed. No items were moved.");
        }
    }
    void cancel(Session s,String message) {
        if(s.closing)return;s.closing=true;sessions.remove(s.first.getUUID(),s);sessions.remove(s.second.getUUID(),s);
        for(var p:List.of(s.first,s.second)){if(p.containerMenu instanceof ReviewHandler handler&&handler.session==s)p.closeContainer();CommunityServer.say(p,message);}
    }
    void tick() {
        for(var s:new HashSet<>(sessions.values()))if(!valid(s)||s.accepted&&!offeredItemsMatch(s))cancel(s,"Trade canceled because a player moved, left Survival, entered combat, or changed an offered item. No items were moved.");
    }
    static void recover(MinecraftServer server) {
        try{get(server).storage.recover();}
        catch(Exception e){throw new IllegalStateException("Player trade recovery failed. Keep infinity-player-trades and playerdata intact; stop the server and restore a matching world backup or fix storage before players join.",e);}
    }
    void departing(ServerPlayer p) {var s=sessions.get(p.getUUID());if(s!=null)cancel(s,"A player left or died. Trade canceled without moving items.");}
    static void leave(ServerPlayer p) {var manager=INSTANCES.get(p.level().getServer());if(manager!=null)manager.departing(p);}
    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(PlayerTrading::recover);
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{var manager=INSTANCES.get(server);if(manager!=null)for(var session:new HashSet<>(manager.sessions.values()))manager.cancel(session,"Server stopping. Trade canceled.");});
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        ServerTickEvents.END_SERVER_TICK.register(server->get(server).tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->leave(handler.player));
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(get(server).locked.contains(handler.player.getUUID()))handler.disconnect(Component.literal("Trade recovery requires a server restart."));});
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(entity instanceof ServerPlayer p)leave(p);});
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            var command=Commands.literal("ptrade").executes(c->CommunityServer.info(c.getSource(),"/ptrade <player>, accept, offer <slot 1-36> <count>, remove <slot>, review, confirm <revision>, cancel. Both players must confirm the current offers."));
            command.then(Commands.argument("player",EntityArgument.player()).executes(c->get(c.getSource().getServer()).request(c.getSource().getPlayerOrException(),EntityArgument.getPlayer(c,"player"))?1:0));
            command.then(Commands.literal("accept").executes(c->get(c.getSource().getServer()).accept(c.getSource().getPlayerOrException())?1:0));
            command.then(Commands.literal("offer").then(Commands.argument("slot",IntegerArgumentType.integer(1,36)).then(Commands.argument("count",IntegerArgumentType.integer(1,64)).executes(c->get(c.getSource().getServer()).offer(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"slot")-1,IntegerArgumentType.getInteger(c,"count"))?1:0))));
            command.then(Commands.literal("remove").then(Commands.argument("slot",IntegerArgumentType.integer(1,36)).executes(c->get(c.getSource().getServer()).remove(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"slot")-1)?1:0)));
            command.then(Commands.literal("confirm").executes(c->{var s=get(c.getSource().getServer()).sessions.get(c.getSource().getPlayerOrException().getUUID());return CommunityServer.info(c.getSource(),s==null?"No active trade.":"Review the chest and use /ptrade confirm "+s.version);})
                .then(Commands.argument("revision",IntegerArgumentType.integer(1)).executes(c->get(c.getSource().getServer()).confirm(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"revision"))?1:0)));
            command.then(Commands.literal("review").executes(c->get(c.getSource().getServer()).show(c.getSource().getPlayerOrException())?1:0));
            command.then(Commands.literal("cancel").executes(c->{var manager=get(c.getSource().getServer());var s=manager.sessions.get(c.getSource().getPlayerOrException().getUUID());if(s!=null)manager.cancel(s,"Trade canceled. No items were moved.");return 1;}));
            dispatcher.register(command);
        });
    }
}

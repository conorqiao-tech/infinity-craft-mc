package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Actual saved logins and native player files; no substituted persistence or invented reconnect. */
public class TradeSessionGameTests {
    private static final class NativeStore extends PlayerTrading.Storage {
        int pairSaves, prepared;
        NativeStore(MinecraftServer server,Path journals) {
            super(server,journals,server.getWorldPath(LevelResource.PLAYER_DATA_DIR));
        }
        @Override void savePlayers(ServerPlayer first,ServerPlayer second) throws IOException {
            pairSaves++;super.savePlayers(first,second);
        }
        @Override void prepare(UUID id,CompoundTag data) throws IOException {prepared++;super.prepare(id,data);}
    }

    private static int count(ServerPlayer p,Item item) {
        return p.getInventory().getNonEquipmentItems().stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static List<ItemStack> display(AbstractContainerMenu menu) {
        var result=new ArrayList<ItemStack>();
        for(var slot:menu.slots)result.add(slot.getItem().copy());
        return result;
    }

    private static final class Scenario extends NativeSessionFixture {
        final PlayerTrading previous,manager;
        final NativeStore storage;
        Joined owner,partner,observer;
        ServerPlayer obsolete;
        PlayerTrading.Session prior,fresh;
        AbstractContainerMenu obsoleteMenu,ownerMenu,partnerMenu;
        ItemStack namedGift;
        CompoundTag beforeOwner,beforePartner;
        byte[] ownerFile,partnerFile,observerFile;
        List<ItemStack> ownerDisplay,partnerDisplay;
        Map<Integer,PlayerTrading.Offer> ownerOffers,partnerOffers;
        Set<UUID> confirmations;
        int revision;

        Scenario(GameTestHelper c) {
            super(c);
            try {storage=new NativeStore(server,Files.createTempDirectory("infinity-native-session-trades-"));}
            catch(IOException failure){throw new IllegalStateException(failure);}
            previous=PlayerTrading.get(server);manager=new PlayerTrading(server,storage);
            PlayerTrading.INSTANCES.put(server,manager);
        }

        @Override void start() {
            owner=join("trade-owner",2);partner=join("trade-partner",5);observer=join("trade-observer",12);
            for(var joined:List.of(owner,partner,observer)) {
                joined.player.setGameMode(GameType.SURVIVAL);
                c.assertValueEqual(GameModes.current(joined.player),GameModes.Mode.SURVIVAL,"Native trade fixture uses the real Survival profile");
                c.assertFalse(Memberships.gameplayBypass(joined.player),"Native trade fixture has no OP or Admin bypass");
                GameModes.state(joined.player).putString("session_trade_preserved","native reconnect retains this state");
            }
            namedGift=new ItemStack(Items.DIAMOND,9);namedGift.set(DataComponents.CUSTOM_NAME,Component.literal("Reconnect gift"));
            owner.player.getInventory().setItem(0,namedGift.copy());partner.player.getInventory().setItem(0,new ItemStack(Items.EMERALD,7));
            PlayerPersistence.save(observer.player);observerFile=file(observer.player);
            GameModes.state(observer.player).putBoolean("observer_unsaved_marker",true);
            openTrade(owner.player,partner.player);
            prior=manager.sessions.get(owner.player.getUUID());obsolete=owner.player;obsoleteMenu=obsolete.containerMenu;
            c.assertTrue(manager.confirm(partner.player,prior.version),"Original partner explicitly confirms the original revision");
            c.assertFalse(prior.confirmed.contains(obsolete.getUUID()),"Original sender has not supplied consent");
            reconnect(owner,replacement->{
                owner=replacement;
                c.assertTrue(owner.player!=obsolete&&owner.player.connection!=obsolete.connection,"Saved native reconnect creates a replacement player and handler");
                c.assertTrue(manager.sessions.isEmpty(),"Genuine original departure clears the original trade");
                c.assertValueEqual(count(owner.player,Items.DIAMOND),9,"Native saved reconnect retains every unescrowed diamond");
                c.assertValueEqual(GameModes.state(owner.player).getStringOr("session_trade_preserved",""),"native reconnect retains this state","Actual saved login retains unrelated player state");
                openTrade(owner.player,partner.player);fresh=manager.sessions.get(owner.player.getUUID());
                c.assertTrue(fresh!=prior&&!fresh.id.equals(prior.id),"Replacement participants create a distinct transaction");
                c.assertValueEqual(fresh.version,prior.version,"The real new offer revision deliberately matches the old revision");
                c.assertTrue(manager.confirm(partner.player,fresh.version),"The current partner supplies only its own fresh consent");
                snapshot();staleActors();finishExchange();departure();succeed();
            });
        }

        void command(ServerPlayer actor,String command) {
            try {server.getCommands().getDispatcher().execute(command,actor.createCommandSourceStack());}
            catch(com.mojang.brigadier.exceptions.CommandSyntaxException failure){throw new IllegalStateException(failure);}
        }
        void openTrade(ServerPlayer first,ServerPlayer second) {
            c.assertTrue(manager.request(first,second),"Current connected nearby players request a trade");
            c.assertTrue(manager.accept(second),"The exact current recipient accepts");
            c.assertTrue(manager.offer(first,0,4),"Current sender offers four named diamonds without escrow");
            c.assertTrue(manager.offer(second,0,3),"Current partner offers three emeralds without escrow");
            c.assertTrue(first.containerMenu instanceof PlayerTrading.ReviewHandler&&second.containerMenu instanceof PlayerTrading.ReviewHandler,"Both native review menus are open");
        }
        byte[] file(ServerPlayer p) {
            try{return Files.readAllBytes(GameModes.savedPlayerFile(server,p.getUUID()));}
            catch(IOException failure){throw new IllegalStateException(failure);}
        }
        CompoundTag saved(ServerPlayer p) {
            try{return storage.readPlayer(p.getUUID());}
            catch(IOException failure){throw new IllegalStateException(failure);}
        }
        void snapshot() {
            // These are the real native files. The counting trade store has not saved either pair.
            PlayerPersistence.save(owner.player);PlayerPersistence.save(partner.player);
            beforeOwner=RewardTrades.serialize(owner.player);beforePartner=RewardTrades.serialize(partner.player);
            ownerFile=file(owner.player);partnerFile=file(partner.player);revision=fresh.version;
            ownerMenu=owner.player.containerMenu;partnerMenu=partner.player.containerMenu;
            ownerDisplay=display(ownerMenu);partnerDisplay=display(partnerMenu);
            ownerOffers=new HashMap<>(fresh.offers.get(owner.player.getUUID()));partnerOffers=new HashMap<>(fresh.offers.get(partner.player.getUUID()));
            confirmations=new HashSet<>(fresh.confirmed);
            c.assertValueEqual(confirmations,Set.of(partner.player.getUUID()),"Only the current partner's consent is present before stale attempts");
        }
        void unchanged(String label) {
            c.assertTrue(manager.sessions.size()==2&&manager.sessions.get(owner.player.getUUID())==fresh&&manager.sessions.get(partner.player.getUUID())==fresh,label+": the replacement session remains exact");
            c.assertTrue(fresh.accepted&&!fresh.closing&&manager.valid(fresh),label+": the replacement trade remains valid and open");
            c.assertValueEqual(fresh.version,revision,label+": the offer revision is unchanged");
            c.assertValueEqual(fresh.offers.get(owner.player.getUUID()),ownerOffers,label+": sender offers are unchanged");
            c.assertValueEqual(fresh.offers.get(partner.player.getUUID()),partnerOffers,label+": partner offers are unchanged");
            c.assertValueEqual(fresh.confirmed,confirmations,label+": no obsolete identity supplies fresh consent");
            c.assertTrue(owner.player.containerMenu==ownerMenu&&partner.player.containerMenu==partnerMenu,label+": both native review instances stay open");
            for(int slot=0;slot<ownerDisplay.size();slot++)c.assertTrue(ItemStack.matches(ownerDisplay.get(slot),ownerMenu.slots.get(slot).getItem()),label+": sender review slot remains unchanged");
            for(int slot=0;slot<partnerDisplay.size();slot++)c.assertTrue(ItemStack.matches(partnerDisplay.get(slot),partnerMenu.slots.get(slot).getItem()),label+": partner review slot remains unchanged");
            c.assertTrue(PlayerTrading.matches(beforeOwner,RewardTrades.serialize(owner.player))&&PlayerTrading.matches(beforePartner,RewardTrades.serialize(partner.player)),label+": complete inventory and InfinityModes remain unchanged");
            c.assertTrue(ownerMenu.getCarried().isEmpty()&&partnerMenu.getCarried().isEmpty(),label+": native cursors stay empty");
            c.assertTrue(Arrays.equals(ownerFile,file(owner.player))&&Arrays.equals(partnerFile,file(partner.player)),label+": actual participant files are byte unchanged");
            c.assertTrue(Arrays.equals(observerFile,file(observer.player)),label+": the unrelated observer file is byte unchanged");
            c.assertValueEqual(storage.pairSaves,0,label+": no trade pair save runs");
            c.assertValueEqual(storage.prepared,0,label+": no trade journal is prepared");
        }
        void staleActors() {
            c.assertFalse(manager.eligible(obsolete),"The obsolete object is not a current connected actor");
            c.assertFalse(manager.accept(obsolete),"Obsolete accept cannot accept the current participant's trade");unchanged("Obsolete accept");
            c.assertFalse(manager.offer(obsolete,0,1),"Obsolete offer is rejected");unchanged("Obsolete offer");
            c.assertFalse(manager.remove(obsolete,0),"Obsolete remove is rejected");unchanged("Obsolete remove");
            c.assertFalse(manager.show(obsolete),"Obsolete review cannot open a fresh menu");unchanged("Obsolete review");
            c.assertFalse(manager.confirm(obsolete,prior.version),"Matching old revision cannot count as replacement-player consent");unchanged("Obsolete confirmation");
            c.assertFalse(manager.cancelFor(obsolete),"Obsolete actor cancellation is rejected");unchanged("Obsolete actor cancellation");
            for(var command:List.of("ptrade offer 1 1","ptrade remove 1","ptrade review","ptrade confirm "+prior.version,"ptrade cancel")) {
                command(obsolete,command);unchanged("Registered old-source command "+command);
            }
            c.assertFalse(obsoleteMenu.stillValid(obsolete),"Old native window cannot validate against the replacement session");
            c.assertFalse(ownerMenu.stillValid(obsolete),"Fresh native window rejects an obsolete same-UUID player");
            obsoleteMenu.clicked(49,0,ContainerInput.PICKUP,obsolete);unchanged("Old native window confirmation");
            ownerMenu.clicked(49,0,ContainerInput.PICKUP,obsolete);unchanged("Fresh native window with obsolete confirmer");
            ownerMenu.clicked(53,0,ContainerInput.PICKUP,obsolete);unchanged("Fresh native window with obsolete canceler");
            ownerMenu.removed(obsolete);unchanged("Obsolete actor cannot close a fresh native window");
            manager.departing(obsolete);unchanged("Delayed direct original departure");
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(obsolete.connection,server);unchanged("Registered delayed original disconnect");
            ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(obsolete,obsolete.damageSources().genericKill());unchanged("Registered delayed original death");
            owner.drain();partner.drain();observer.drain();
        }
        void finishExchange() {
            c.assertTrue(manager.confirm(owner.player,revision),"Exact current sender consent completes the agreed replacement transaction");
            c.assertTrue(manager.sessions.isEmpty(),"Completed exchange closes both participant sessions");
            c.assertValueEqual(storage.pairSaves,1,"A successful exchange saves exactly one native participant pair");
            c.assertValueEqual(storage.prepared,1,"A successful exchange prepares exactly one durable journal");
            c.assertValueEqual(count(owner.player,Items.DIAMOND),5,"Current sender loses only its four offered diamonds");
            c.assertValueEqual(count(owner.player,Items.EMERALD),3,"Current sender receives exactly three emeralds");
            c.assertValueEqual(count(partner.player,Items.DIAMOND),4,"Current partner receives exactly four diamonds");
            c.assertValueEqual(count(partner.player,Items.EMERALD),4,"Current partner loses only its three offered emeralds");
            c.assertTrue(partner.player.getInventory().getNonEquipmentItems().stream().filter(s->s.is(Items.DIAMOND)).allMatch(s->ItemStack.isSameItemSameComponents(s,namedGift)),"Native successful trade preserves all gifted item components");
            for(var joined:List.of(owner,partner)) {
                var disk=saved(joined.player);
                c.assertTrue(PlayerTrading.receipt(disk,fresh.id),"Each actual native participant file contains this transaction receipt");
                c.assertTrue(PlayerTrading.matches(RewardTrades.serialize(joined.player),disk),"Actual native participant file matches complete committed inventory and mode state");
                c.assertValueEqual(disk.getCompoundOrEmpty("InfinityModes").getStringOr("session_trade_preserved",""),"native reconnect retains this state","Commit retains unrelated player state");
            }
            c.assertFalse(Files.exists(storage.journal(fresh.id)),"Verified native pair saves remove the durable journal");
            c.assertFalse(manager.confirm(owner.player,revision),"Repeated current sender confirmation cannot repeat the exchange");
            c.assertFalse(manager.confirm(partner.player,revision),"Repeated current partner confirmation cannot repeat the exchange");
            c.assertFalse(manager.confirm(obsolete,revision),"Repeated obsolete confirmation cannot repeat the exchange");
            c.assertFalse(manager.request(obsolete,partner.player),"An obsolete object cannot create a new invitation once the UUID is no longer trading");
            c.assertValueEqual(storage.pairSaves,1,"Repeated and obsolete operations create no additional native pair save");
            c.assertValueEqual(count(owner.player,Items.DIAMOND)+count(partner.player,Items.DIAMOND),9,"Repeated operations conserve the global diamond count");
            c.assertValueEqual(count(owner.player,Items.EMERALD)+count(partner.player,Items.EMERALD),7,"Repeated operations conserve the global emerald count");
            c.assertTrue(Arrays.equals(observerFile,file(observer.player)),"Successful participant persistence never rewrites the observer's native file");
        }
        void departure() {
            c.assertTrue(manager.request(owner.player,partner.player)&&manager.accept(partner.player),"Current players can start a separate trade after completing the first");
            c.assertTrue(manager.offer(owner.player,0,2),"The separate unconfirmed trade retains its actual source items");
            var beforeFirst=RewardTrades.serialize(owner.player);var beforeSecond=RewardTrades.serialize(partner.player);
            // Do not pre-close the review: this executes the genuine native disconnect path.
            partner.wire.connection.disconnect(Component.literal("Trade fixture genuine departure"));partner.wire.connection.handleDisconnection();
            c.assertTrue(server.getPlayerList().getPlayer(partner.player.getUUID())==null,"Genuine disconnect removes the exact current native player");
            c.assertTrue(manager.sessions.isEmpty(),"Genuine current departure cancels the active replacement trade");
            c.assertTrue(owner.player.containerMenu==owner.player.inventoryMenu,"Actual departure closes the remaining current review");
            c.assertTrue(PlayerTrading.matches(beforeFirst,RewardTrades.serialize(owner.player))&&PlayerTrading.matches(beforeSecond,RewardTrades.serialize(partner.player)),"Genuine departure moves no inventory or InfinityModes state");
            c.assertTrue(PlayerTrading.matches(beforeSecond,saved(partner.player)),"Native departing participant file retains all unescrowed items and mode state");
            c.assertFalse(manager.request(owner.player,partner.player),"An exact but disconnected target cannot receive a fresh trade invitation");
            c.assertValueEqual(storage.pairSaves,1,"Real cancellation never prepares a second inventory transaction");
            c.assertTrue(Arrays.equals(observerFile,file(observer.player)),"Genuine departure does not save the unrelated observer");
        }
        @Override void cleanup() {
            try {for(var session:new HashSet<>(manager.sessions.values()))manager.cancel(session,"Native trade fixture finished");}
            finally {
                PlayerTrading.INSTANCES.put(server,previous);
                try(var files=Files.walk(storage.journals)) {for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
                catch(IOException failure){throw new IllegalStateException(failure);}
            }
        }
    }

    @GameTest(maxTicks=1200)
    public void nativeReconnectCannotSupplyFreshTradeConsentOrCancelReplacement(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c,()->new Scenario(c));
    }
}

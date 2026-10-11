package dev.convergence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

/** A payment and its receipt share the same vanilla player-file replacement. */
final class RewardTrades {
    static final String RECEIPTS="tradeReceipts";
    record Cost(Item item,int count,String label) {}
    record Trade(String id,Memberships.Tier rank,String power,List<Cost> costs) {
        String label() {return rank==null?"power "+power:"rank "+rank;}
        String costText() {return String.join(" + ",costs.stream().map(c->c.count()+" "+c.label()).toList());}
    }
    static Cost cost(Item item,int count,String label) {return new Cost(item,count,label);}
    static Trade rank(String id,Memberships.Tier tier,Cost... costs) {return new Trade(id,tier,null,List.of(costs));}
    static Trade power(String id,Cost... costs) {return new Trade(id,null,id,List.of(costs));}
    static final List<Trade> TRADES=List.of(
        rank("rank-go",Memberships.Tier.GO,cost(Items.IRON_INGOT,16,"iron ingots"),cost(Items.EMERALD,8,"emeralds")),
        rank("rank-plus",Memberships.Tier.PLUS,cost(Items.DIAMOND,8,"diamonds"),cost(Items.EMERALD,16,"emeralds")),
        rank("rank-pro",Memberships.Tier.PRO,cost(Items.ENDER_PEARL,16,"ender pearls"),cost(Items.BLAZE_ROD,8,"blaze rods"),cost(Items.EMERALD,32,"emeralds")),
        rank("rank-ultra",Memberships.Tier.ULTRA,cost(Items.NETHER_STAR,1,"nether star"),cost(Items.DIAMOND,16,"diamonds"),cost(Items.EMERALD,64,"emeralds")),
        power("hacks",cost(Items.NETHER_STAR,1,"nether star"),cost(Items.DRAGON_BREATH,1,"dragon breath"),cost(Items.DIAMOND,64,"diamonds")),
        power("explorer",cost(Items.EMERALD,32,"emeralds"),cost(Items.ENDER_PEARL,4,"ender pearls")),
        power("aquatic",cost(Items.PRISMARINE_SHARD,16,"prismarine shards"),cost(Items.EMERALD,8,"emeralds")),
        power("nether",cost(Items.BLAZE_ROD,8,"blaze rods"),cost(Items.GOLD_INGOT,16,"gold ingots"),cost(Items.EMERALD,16,"emeralds")),
        power("fireguard",cost(Items.BLAZE_ROD,4,"blaze rods"),cost(Items.EMERALD,8,"emeralds")),
        power("windstep",cost(Items.ARROW,32,"arrows"),cost(Items.EMERALD,8,"emeralds")));
    static Trade find(String id) {return TRADES.stream().filter(t->t.id().equals(id)).findFirst().orElse(null);}
    static boolean receipt(CompoundTag state,String id) {return !state.getCompoundOrEmpty(RECEIPTS).getStringOr(id,"").isEmpty();}
    static Memberships.Tier rank(ServerPlayer p) {
        var tier=Memberships.Tier.FREE;
        for(var trade:TRADES)if(trade.rank()!=null&&receipt(GameModes.state(p),trade.id())&&trade.rank().ordinal()>tier.ordinal())tier=trade.rank();
        return tier;
    }
    static boolean hasPower(ServerPlayer p,String id) {
        var trade=find(id);return trade!=null&&trade.power()!=null&&receipt(GameModes.state(p),id);
    }
    static boolean alreadyUnlocked(ServerPlayer p,Trade trade) {
        if(trade.rank()!=null) {
            var members=Memberships.get(p.level().getServer());members.syncAchievements(p);
            return Math.max(members.account(p.getUUID()).earned.ordinal(),rank(p).ordinal())>=trade.rank().ordinal();
        }
        var rewards=AchievementRewards.get(p.level().getServer());rewards.sync(p);
        var reward=AchievementRewards.find(trade.power(),false);
        return hasPower(p,trade.power())||(reward!=null&&rewards.unlocked(p,reward));
    }
    static boolean normal(ItemStack stack,Cost cost) {
        return !stack.isEmpty()&&ItemStack.isSameItemSameComponents(stack,new ItemStack(cost.item()));
    }
    static int count(ServerPlayer p,Cost cost) {
        int found=0;for(var stack:p.getInventory().getNonEquipmentItems())if(normal(stack,cost))found+=stack.getCount();return found;
    }
    static String blocked(ServerPlayer p) {
        var server=p.level().getServer();
        if(!p.isAlive()||GameModes.current(p)!=GameModes.Mode.SURVIVAL||p.gameMode()!=GameType.SURVIVAL)return "Trades can only be confirmed while playing Survival.";
        if(CommunityServer.get(server).combat.getOrDefault(p.getUUID(),0)>server.getTickCount())return "Wait 10 seconds after damage before trading.";
        if(GameModes.PENDING.containsKey(p.getUUID())||GameModes.TRANSITIONS.contains(p.getUUID()))return "Finish or cancel your mode change before trading.";
        return null;
    }
    interface PlayerStore {
        void save(ServerPlayer p) throws IOException;
        CompoundTag read(ServerPlayer p) throws IOException;
    }
    static final PlayerStore VANILLA=new PlayerStore() {
        public void save(ServerPlayer p) {p.level().getServer().getPlayerList().saveAll();}
        public CompoundTag read(ServerPlayer p) throws IOException {
            Path file=p.level().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(p.getStringUUID()+".dat");
            return NbtIo.readCompressed(file,NbtAccounter.create(64L*1024*1024));
        }
    };
    static CompoundTag serialize(ServerPlayer p) {
        var writer=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,p.registryAccess());p.saveWithoutId(writer);return writer.buildResult();
    }
    static void syncInventory(ServerPlayer p) {
        p.getInventory().setChanged();p.inventoryMenu.sendAllDataToRemote();p.containerMenu.broadcastChanges();
    }
    static boolean purchase(ServerPlayer p,String id) {return purchase(p,id,VANILLA);}
    static boolean purchase(ServerPlayer p,String id,PlayerStore store) {
        var trade=find(id);
        if(trade==null){CommunityServer.say(p,"Unknown trade. /trades lists the exact IDs and costs.");return false;}
        if(Memberships.operator(p)){CommunityServer.say(p,"OP4 already has access to every power and cosmetic without trading. Use /power or /cosmetic; /membership grants cosmetic ranks. No items were taken.");return true;}
        var blocked=blocked(p);if(blocked!=null){CommunityServer.say(p,blocked);return false;}
        if(alreadyUnlocked(p,trade)){CommunityServer.say(p,trade.label()+" is already permanently unlocked. No items were taken.");return false;}
        for(var cost:trade.costs())if(count(p,cost)<cost.count()) {
            CommunityServer.say(p,"Not enough ordinary inventory items. "+trade.id()+" costs "+trade.costText()+". No items were taken.");return false;
        }
        var original=p.getInventory().getNonEquipmentItems().stream().map(ItemStack::copy).toList();
        var originalState=GameModes.state(p).copy();
        String token=UUID.randomUUID().toString();
        try {
            for(var cost:trade.costs()) {
                int remaining=cost.count();
                for(int slot=0;slot<p.getInventory().getNonEquipmentItems().size()&&remaining>0;slot++) {
                    var stack=p.getInventory().getItem(slot);if(!normal(stack,cost))continue;
                    int debit=Math.min(remaining,stack.getCount());stack.shrink(debit);remaining-=debit;
                }
                if(remaining!=0)throw new IllegalStateException("Trade inventory changed during debit");
            }
            var state=GameModes.state(p);var receipts=state.getCompoundOrEmpty(RECEIPTS).copy();receipts.putString(id,token);state.put(RECEIPTS,receipts);
            var expected=serialize(p);
            // Vanilla catches save IO errors. Success means the disk contains this exact receipt
            // and the same paid inventory/profile state, not merely that save returned.
            store.save(p);var saved=store.read(p);
            if(!token.equals(saved.getCompoundOrEmpty("InfinityModes").getCompoundOrEmpty(RECEIPTS).getStringOr(id,""))
                ||!Objects.equals(expected.get("Inventory"),saved.get("Inventory"))
                ||!Objects.equals(expected.get("InfinityModes"),saved.get("InfinityModes")))
                throw new IOException("Player trade receipt/inventory was not durably saved");
        }catch(Exception failure) {
            for(int slot=0;slot<original.size();slot++)p.getInventory().setItem(slot,original.get(slot));
            ((ModePlayer)p).infinity$state(originalState);syncInventory(p);
            // Do not manually replace a world player file or its vanilla recovery backup.
            // If a save completed before verification failed, its debit and receipt still agree.
            try {store.save(p);}catch(Exception ignored) { /* Retain the restored live state when storage is unavailable. */ }
            System.err.println("[Infinity] Trade "+id+" rolled back for "+p.getUUID()+": "+failure.getClass().getSimpleName());
            CommunityServer.say(p,"Trade could not be saved. Your inventory was restored and no active unlock was granted. Ask the owner to check storage before retrying.");
            return false;
        }
        syncInventory(p);Memberships.get(p.level().getServer()).badge(p);
        CommunityServer.say(p,"Trade complete: "+trade.label()+" permanently unlocked for "+trade.costText()+". "+(trade.power()==null?"/rank shows your rank.":"/power "+trade.power()+" activates it."));
        return true;
    }
    static int preview(ServerPlayer p,String id) {
        var trade=find(id);if(trade==null)return CommunityServer.say(p,"Unknown trade. Use /trades.");
        CommunityServer.say(p,trade.id()+" permanently unlocks "+trade.label()+" for "+trade.costText()+".");
        return CommunityServer.say(p,"Only ordinary main-inventory/hotbar items count. To pay in Survival, type /trade "+id+" confirm.");
    }
    static int list(ServerPlayer p) {
        CommunityServer.say(p,"Permanent unlocks: achievements or item trades. Ranks and powers are separate; powers do not require a rank.");
        for(var trade:TRADES)CommunityServer.say(p,trade.id()+" = "+trade.label()+": "+trade.costText()+".");
        return CommunityServer.say(p,"/trade <id> previews; /trade <id> confirm pays. Survival only, outside combat. Named/modified items, armor, offhand and Ender Chest items are never payment.");
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(Commands.literal("trades").executes(c->list(c.getSource().getPlayerOrException())));
            var command=Commands.literal("trade").executes(c->list(c.getSource().getPlayerOrException()));
            for(var trade:TRADES)command.then(Commands.literal(trade.id())
                .executes(c->preview(c.getSource().getPlayerOrException(),trade.id()))
                .then(Commands.literal("confirm").executes(c->purchase(c.getSource().getPlayerOrException(),trade.id())?1:0)));
            dispatcher.register(command);
        });
    }
}

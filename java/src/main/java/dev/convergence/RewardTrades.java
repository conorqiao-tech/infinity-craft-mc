package dev.convergence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.GameMode;

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
    static boolean receipt(NbtCompound state,String id) {return !state.getCompoundOrEmpty(RECEIPTS).getString(id,"").isEmpty();}
    static Memberships.Tier rank(ServerPlayerEntity p) {
        var tier=Memberships.Tier.FREE;
        for(var trade:TRADES)if(trade.rank()!=null&&receipt(GameModes.state(p),trade.id())&&trade.rank().ordinal()>tier.ordinal())tier=trade.rank();
        return tier;
    }
    static boolean hasPower(ServerPlayerEntity p,String id) {
        var trade=find(id);return trade!=null&&trade.power()!=null&&receipt(GameModes.state(p),id);
    }
    static boolean alreadyUnlocked(ServerPlayerEntity p,Trade trade) {
        if(trade.rank()!=null) {
            var members=Memberships.get(p.getEntityWorld().getServer());members.syncAchievements(p);
            return Math.max(members.account(p.getUuid()).earned.ordinal(),rank(p).ordinal())>=trade.rank().ordinal();
        }
        var rewards=AchievementRewards.get(p.getEntityWorld().getServer());rewards.sync(p);
        var reward=AchievementRewards.find(trade.power(),false);
        return hasPower(p,trade.power())||(reward!=null&&rewards.unlocked(p,reward));
    }
    static boolean normal(ItemStack stack,Cost cost) {
        return !stack.isEmpty()&&ItemStack.areItemsAndComponentsEqual(stack,new ItemStack(cost.item()));
    }
    static int count(ServerPlayerEntity p,Cost cost) {
        int found=0;for(var stack:p.getInventory().getMainStacks())if(normal(stack,cost))found+=stack.getCount();return found;
    }
    static String blocked(ServerPlayerEntity p) {
        var server=p.getEntityWorld().getServer();
        if(!p.isAlive()||GameModes.current(p)!=GameModes.Mode.SURVIVAL||p.getGameMode()!=GameMode.SURVIVAL)return "Trades can only be confirmed while playing Survival.";
        if(CommunityServer.get(server).combat.getOrDefault(p.getUuid(),0)>server.getTicks())return "Wait 10 seconds after damage before trading.";
        if(GameModes.PENDING.containsKey(p.getUuid())||GameModes.TRANSITIONS.contains(p.getUuid()))return "Finish or cancel your mode change before trading.";
        return null;
    }
    interface PlayerStore {
        void save(ServerPlayerEntity p) throws IOException;
        NbtCompound read(ServerPlayerEntity p) throws IOException;
    }
    static final PlayerStore VANILLA=new PlayerStore() {
        public void save(ServerPlayerEntity p) {p.getEntityWorld().getServer().getPlayerManager().saveAllPlayerData();}
        public NbtCompound read(ServerPlayerEntity p) throws IOException {
            Path file=p.getEntityWorld().getServer().getSavePath(WorldSavePath.PLAYERDATA).resolve(p.getUuidAsString()+".dat");
            return NbtIo.readCompressed(file,NbtSizeTracker.of(64L*1024*1024));
        }
    };
    static NbtCompound serialize(ServerPlayerEntity p) {
        var writer=NbtWriteView.create(ErrorReporter.EMPTY,p.getRegistryManager());p.writeData(writer);return writer.getNbt();
    }
    static void syncInventory(ServerPlayerEntity p) {
        p.getInventory().markDirty();p.playerScreenHandler.syncState();p.currentScreenHandler.sendContentUpdates();
    }
    static boolean purchase(ServerPlayerEntity p,String id) {return purchase(p,id,VANILLA);}
    static boolean purchase(ServerPlayerEntity p,String id,PlayerStore store) {
        var trade=find(id);
        if(trade==null){CommunityServer.say(p,"Unknown trade. /trades lists the exact IDs and costs.");return false;}
        if(Memberships.operator(p)){CommunityServer.say(p,"OP4 already has access to every power and cosmetic without trading. Use /power or /cosmetic; /membership grants cosmetic ranks. No items were taken.");return true;}
        var blocked=blocked(p);if(blocked!=null){CommunityServer.say(p,blocked);return false;}
        if(alreadyUnlocked(p,trade)){CommunityServer.say(p,trade.label()+" is already permanently unlocked. No items were taken.");return false;}
        for(var cost:trade.costs())if(count(p,cost)<cost.count()) {
            CommunityServer.say(p,"Not enough ordinary inventory items. "+trade.id()+" costs "+trade.costText()+". No items were taken.");return false;
        }
        var original=p.getInventory().getMainStacks().stream().map(ItemStack::copy).toList();
        var originalState=GameModes.state(p).copy();
        String token=UUID.randomUUID().toString();
        try {
            for(var cost:trade.costs()) {
                int remaining=cost.count();
                for(int slot=0;slot<p.getInventory().getMainStacks().size()&&remaining>0;slot++) {
                    var stack=p.getInventory().getStack(slot);if(!normal(stack,cost))continue;
                    int debit=Math.min(remaining,stack.getCount());stack.decrement(debit);remaining-=debit;
                }
                if(remaining!=0)throw new IllegalStateException("Trade inventory changed during debit");
            }
            var state=GameModes.state(p);var receipts=state.getCompoundOrEmpty(RECEIPTS).copy();receipts.putString(id,token);state.put(RECEIPTS,receipts);
            var expected=serialize(p);
            // Vanilla catches save IO errors. Success means the disk contains this exact receipt
            // and the same paid inventory/profile state, not merely that save returned.
            store.save(p);var saved=store.read(p);
            if(!token.equals(saved.getCompoundOrEmpty("InfinityModes").getCompoundOrEmpty(RECEIPTS).getString(id,""))
                ||!Objects.equals(expected.get("Inventory"),saved.get("Inventory"))
                ||!Objects.equals(expected.get("InfinityModes"),saved.get("InfinityModes")))
                throw new IOException("Player trade receipt/inventory was not durably saved");
        }catch(Exception failure) {
            for(int slot=0;slot<original.size();slot++)p.getInventory().setStack(slot,original.get(slot));
            ((ModePlayer)p).infinity$state(originalState);syncInventory(p);
            // Do not manually replace a world player file or its vanilla recovery backup.
            // If a save completed before verification failed, its debit and receipt still agree.
            try {store.save(p);}catch(Exception ignored) { /* Retain the restored live state when storage is unavailable. */ }
            System.err.println("[Infinity] Trade "+id+" rolled back for "+p.getUuid()+": "+failure.getClass().getSimpleName());
            CommunityServer.say(p,"Trade could not be saved. Your inventory was restored and no active unlock was granted. Ask the owner to check storage before retrying.");
            return false;
        }
        syncInventory(p);Memberships.get(p.getEntityWorld().getServer()).badge(p);
        CommunityServer.say(p,"Trade complete: "+trade.label()+" permanently unlocked for "+trade.costText()+". "+(trade.power()==null?"/rank shows your rank.":"/power "+trade.power()+" activates it."));
        return true;
    }
    static int preview(ServerPlayerEntity p,String id) {
        var trade=find(id);if(trade==null)return CommunityServer.say(p,"Unknown trade. Use /trades.");
        CommunityServer.say(p,trade.id()+" permanently unlocks "+trade.label()+" for "+trade.costText()+".");
        return CommunityServer.say(p,"Only ordinary main-inventory/hotbar items count. To pay in Survival, type /trade "+id+" confirm.");
    }
    static int list(ServerPlayerEntity p) {
        CommunityServer.say(p,"Permanent unlocks: achievements or item trades. Ranks and powers are separate; powers do not require a rank.");
        for(var trade:TRADES)CommunityServer.say(p,trade.id()+" = "+trade.label()+": "+trade.costText()+".");
        return CommunityServer.say(p,"/trade <id> previews; /trade <id> confirm pays. Survival only, outside combat. Named/modified items, armor, offhand and Ender Chest items are never payment.");
    }
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->{
            dispatcher.register(CommandManager.literal("trades").executes(c->list(c.getSource().getPlayerOrThrow())));
            var command=CommandManager.literal("trade").executes(c->list(c.getSource().getPlayerOrThrow()));
            for(var trade:TRADES)command.then(CommandManager.literal(trade.id())
                .executes(c->preview(c.getSource().getPlayerOrThrow(),trade.id()))
                .then(CommandManager.literal("confirm").executes(c->purchase(c.getSource().getPlayerOrThrow(),trade.id())?1:0)));
            dispatcher.register(command);
        });
    }
}

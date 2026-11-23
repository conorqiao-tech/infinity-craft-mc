package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Optional, personal Infinity constellation. Existing player progress remains authoritative. */
final class InfinityJourney {
    static final String KEY = "infinity_journey";
    static final int ALL = 63;
    static final List<String> NAMES = List.of("Wayfinder", "Odyssey", "Guardian", "Maker", "Challenger", "Neighbor");
    static final List<String> REQUIREMENTS = List.of(
        "Stamp three different biome families in the Expedition Atlas.",
        "Discover an Odyssey circuit ruin in Survival or Hardcore.",
        "Defeat an Astral guardian near a real Odyssey ruin.",
        "Complete the powered circuit and live delivery in Builder Exchange.",
        "Finish any Infinity minigame or adventure course.",
        "Deliver at least sixteen food items to the Commons pantry.");
    enum Page { JOURNEY, STYLES, RELAY, SURVEY, DEFENDER, TIDEWATCH, SIGNAL_FLAGS, APIARY, CHIMES, STAR_LOOM, KITE, HALF_MOON, EMBER_PEAKS, BANNER_CONCERT }
    record Choice(Item icon, String label, String bedrockTexture, Runnable action) {
        Choice(Item icon, String label, Runnable action) { this(icon, label, null, action); }
    }
    record View(String title, String content, List<Choice> choices, List<Integer> slots) {
        View(String title, String content, List<Choice> choices) { this(title, content, choices, List.of()); }
        int javaSlot(int choice) { return slots.isEmpty() ? 27 + choice : slots.get(choice); }
        int choiceAt(int slot) { return slots.isEmpty() ? slot - 27 : slots.indexOf(slot); }
    }
    record Ticket(UUID nonce, ServerPlayer player, ServerGamePacketListenerImpl connection,
                  ServerLevel world, GameModes.Mode mode, GameType nativeMode, long screenEpoch, int expires) {
        boolean valid() {
            return player.connection == connection && player.level() == world
                && GameModes.current(player) == mode && player.gameMode.getGameModeForPlayer() == nativeMode
                && ((ScreenEpochAccess) player).infinity$screenEpoch() == screenEpoch
                && emptyScreen(player) && world.getServer().getTickCount() < expires;
        }
    }
    record Delivery(Ticket ticket, Form form, int due) {}
    static final Map<UUID, Ticket> ACTIVE = new ConcurrentHashMap<>();
    static final Map<UUID, Delivery> DELIVERIES = new ConcurrentHashMap<>();
    private InfinityJourney() {}

    static boolean emptyScreen(ServerPlayer p) {
        return ServerMenu.allowed(p) && p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty();
    }
    static CompoundTag state(ServerPlayer p) { return GameModes.state(p).getCompoundOrEmpty(KEY); }
    /** Preserve unknown or malformed future data instead of replacing it with a new compound. */
    static boolean writable(ServerPlayer p) {
        var root = GameModes.state(p);
        if (!root.contains(KEY)) return true;
        var saved = root.getCompound(KEY).orElse(null);
        if (saved == null || !(saved.get("format") instanceof IntTag) || saved.getIntOr("format", -1) != 1
            || saved.contains("attuned") && !(saved.get("attuned") instanceof IntTag)
            || saved.contains("style") && !(saved.get("style") instanceof IntTag)
            || saved.contains("hide_others") && !(saved.get("hide_others") instanceof ByteTag)) return false;
        int done = saved.getIntOr("attuned", 0), selected = saved.getIntOr("style", 0);
        byte hidden = saved.getByteOr("hide_others", (byte) 0);
        return done >= 0 && done <= ALL && selected >= 0 && selected <= 7 && (hidden == 0 || hidden == 1);
    }

    /** Read existing saved progress only; no chunk loads, achievements or player-state writes. */
    static int eligible(ServerPlayer p) {
        var root = GameModes.state(p);
        var commons = root.getCompoundOrEmpty(CommonsEvents.KEY);
        var odyssey = root.getCompoundOrEmpty(OdysseyJournal.KEY);
        var astral = root.getCompoundOrEmpty(AstralJournal.KEY);
        var scores = root.getCompoundOrEmpty("scores");
        int result = 0;
        if (Integer.bitCount(commons.getIntOr("atlas", 0) & 255) >= 3) result |= 1;
        if ((odyssey.getIntOr("realms", 0) & 7) != 0) result |= 2;
        if ((astral.getIntOr("kills", 0) & 7) != 0) result |= 4;
        if (commons.getBooleanOr("signal", false) && commons.getBooleanOr("delivery", false)) result |= 8;
        for (String id : List.of("parkour", "sprint", "dropper", "redlight", "crystalhunt", "colorrush", "memory", "gatedash", "ruins", "maze")) {
            long time = scores.getLongOr(id, 0);
            if (time > 0 && time < Long.MAX_VALUE) { result |= 16; break; }
        }
        int food = 0;
        for (int i = 0; i < CommonsEvents.FOODS.size(); i++)
            food += Math.clamp(commons.getIntOr("food_" + i, 0), 0, CommonsEvents.FOODS.get(i).goal());
        if (food >= 16) result |= 32;
        return result;
    }
    static int attuned(ServerPlayer p) { return writable(p) ? state(p).getIntOr("attuned", 0) & ALL & eligible(p) : 0; }
    static int style(ServerPlayer p) {
        int style = state(p).getIntOr("style", 0);
        if (style < 1 || style > 7) return 0;
        int done = attuned(p);
        if (style >= 1 && style <= 6 && (done & (1 << (style - 1))) != 0) return style;
        return style == 7 && done == ALL ? style : 0;
    }
    static int visibleStyle(ServerPlayer p) {
        return !ServerMenu.allowed(p) || p.isInvisible() || p.isSpectator() ? 0 : style(p);
    }
    static boolean hidesOthers(ServerPlayer p) { return writable(p) && state(p).getBooleanOr("hide_others", false); }
    static boolean maySee(ServerPlayer viewer, ServerPlayer wearer) { return viewer == wearer || !hidesOthers(viewer); }
    static int hideOthers(ServerPlayer p, boolean hide) {
        if (!emptyScreen(p) || !writable(p)) return 0;
        var next = state(p).copy(); next.putInt("format", 1); next.putBoolean("hide_others", hide);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p); return 1;
    }
    static int attune(ServerPlayer p, int index) {
        if (!emptyScreen(p) || !writable(p) || index < 0 || index >= NAMES.size() || (eligible(p) & (1 << index)) == 0) return 0;
        var next = state(p).copy();
        next.putInt("format", 1);
        next.putInt("attuned", (next.getIntOr("attuned", 0) & ALL) | (1 << index));
        next.putInt("style", index + 1);
        GameModes.state(p).put(KEY, next);
        PlayerPersistence.save(p);
        CommunityServer.say(p, "Infinity Journey • " + NAMES.get(index) + " attuned. Its optional trail is on.");
        return 1;
    }
    static int select(ServerPlayer p, int selected) {
        if (!emptyScreen(p) || !writable(p) || selected < 0 || selected > 7) return 0;
        int done = attuned(p);
        if (selected >= 1 && selected <= 6 && (done & (1 << (selected - 1))) == 0) return 0;
        if (selected == 7 && done != ALL) return 0;
        var next = state(p).copy(); next.putInt("format", 1); next.putInt("style", selected);
        GameModes.state(p).put(KEY, next); PlayerPersistence.save(p); return 1;
    }

    /** Original geometric pattern composed from native banner layers, so no client pack is required. */
    static ItemStack emblem(ServerPlayer p, boolean preview) {
        var stack = CommonsEvents.named(Items.BANNER.purple(), 1, "Infinity • Constellation Sigil",
            "Six paths, one shared constellation.", preview ? "Menu preview; nothing is added to your inventory." : "Earned through the six Infinity Journey paths.");
        var reg = p.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        stack.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers.Builder()
            .add(reg.getOrThrow(BannerPatterns.STRIPE_BOTTOM), DyeColor.BLACK)
            .add(reg.getOrThrow(BannerPatterns.RHOMBUS_MIDDLE), DyeColor.CYAN)
            .add(reg.getOrThrow(BannerPatterns.STRIPE_CENTER), DyeColor.WHITE).build());
        return stack;
    }
    /** A repeatable cosmetic gift; no slot is replaced and no one-time currency ledger is involved. */
    static int giveSigil(ServerPlayer p) {
        if (!emptyScreen(p) || !writable(p) || attuned(p) != ALL) return 0;
        int slot = -1;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) { slot = i; break; }
        if (slot < 0) return 0;
        p.getInventory().setItem(slot, emblem(p, false));
        PlayerPersistence.save(p); p.inventoryMenu.sendAllDataToRemote();
        CommunityServer.say(p, "Infinity Journey • your Constellation Sigil was added to an empty slot.");
        return 1;
    }
    static View view(ServerPlayer p, Page page) {
        if (page == Page.BANNER_CONCERT) return BannerConcert.view(p);
        if (page == Page.STAR_LOOM) return StarLoom.gallery(p);
        var chart = StarLoom.Chart.forPage(page);
        if (chart != null) return StarLoom.board(p, chart);
        if (page == Page.CHIMES) return InfinityChimes.view(p);
        if (page == Page.APIARY) return InfinityApiary.view(p);
        if (page == Page.RELAY) return InfinityRelay.view(p);
        if (page == Page.SURVEY) return InfinityOreSurvey.view(p);
        if (page == Page.SIGNAL_FLAGS) return TidewatchFlags.view(p);
        if (page == Page.TIDEWATCH) return InfinityTidewatch.view(p);
        if (page == Page.DEFENDER) return InfinityDefender.view(p);
        var choices = new ArrayList<Choice>();
        int ready = eligible(p), done = attuned(p);
        var body = new StringBuilder();
        if (!writable(p)) body.append("Saved Infinity Journey data needs host review. Existing data is preserved and changes are unavailable.\n\n");
        if (page == Page.JOURNEY) {
            body.append("Six paths connect the Circuit Keepers' world: explore, discover, defend, build, play and share. Your existing progress counts. Attune each completed path to earn its optional trail; all six unlock the Constellation trail and a repeatable sigil banner for an empty slot. No blocks or existing items are replaced.\n\n");
            for (int i = 0; i < NAMES.size(); i++) body.append((done & (1 << i)) != 0 ? "✓ " : (ready & (1 << i)) != 0 ? "◇ " : "○ ")
                .append(NAMES.get(i)).append(" • ").append(REQUIREMENTS.get(i)).append('\n');
            choices.add(new Choice(Items.FILLED_MAP, "Wayfinder • Expedition Atlas", () -> CommonsMenu.open(p, CommonsMenu.Page.ATLAS, null)));
            choices.add(new Choice(Items.WRITTEN_BOOK, "Odyssey • ruins and journal", () -> OdysseyJournal.open(p)));
            choices.add(new Choice(Items.ECHO_SHARD, "Guardian • Astral Wilds", () -> AstralJournal.open(p)));
            choices.add(new Choice(Items.REDSTONE_LAMP, "Maker • Builder Exchange", () -> CommonsMenu.open(p, CommonsMenu.Page.BUILDER, null)));
            choices.add(new Choice(Items.SLIME_BALL, "Challenger • minigame courses", () -> CourseSelector.open(p, GameModes.Mode.MINIGAMES)));
            choices.add(new Choice(Items.WHEAT, "Neighbor • Commons pantry", () -> CommonsMenu.open(p, CommonsMenu.Page.HARVEST, null)));
            choices.add(new Choice(Items.END_ROD, "Attune paths and choose a trail", () -> open(p, Page.STYLES)));
            choices.add(new Choice(Items.REDSTONE_TORCH, "Infinity Circuit Relay • all courses", () -> open(p, Page.RELAY)));
            choices.add(new Choice(Items.DIAMOND_ORE, "Infinity Ore Survey • mining", () -> open(p, Page.SURVEY)));
            choices.add(new Choice(Items.IRON_SWORD, "Infinity Defender • hostile encounters", () -> open(p, Page.DEFENDER)));
            choices.add(new Choice(Items.TROPICAL_FISH, "Infinity Tidewatch • fish field journal", () -> open(p, Page.TIDEWATCH)));
            choices.add(new Choice(Items.HONEYCOMB, "Infinity Apiary • inspect a hive", InfinityApiary.COMB_TEXTURE, () -> open(p, Page.APIARY)));
            choices.add(new Choice(Items.NOTE_BLOCK, "Infinity Chimes • personal score", InfinityChimes.BLOCK_TEXTURE, () -> open(p, Page.CHIMES)));
            choices.add(new Choice(Items.NETHER_STAR, "Infinity Star Loom • constellation puzzles", "textures/items/nether_star", () -> open(p, Page.STAR_LOOM)));
            choices.add(new Choice(Items.ARROW, "Back to Infinity Menu", () -> ServerMenu.open(p)));
            return new View("Infinity Journey • Six Paths", body.toString(), List.copyOf(choices));
        }
        body.append("Choose a completed path to attune it and wear its trail. Completed paths are checked again when chosen. Trails are cosmetic, can be turned off, and are hidden while you are invisible. You can also hide other players' Journey trails.\n\n");
        for (int i = 0; i < NAMES.size(); i++) {
            final int index = i;
            final boolean wasReady = (ready & (1 << i)) != 0;
            String label = NAMES.get(i) + " • " + ((done & (1 << i)) != 0 ? "attuned" : (ready & (1 << i)) != 0 ? "ready to attune" : "locked");
            body.append(label).append("\n").append(REQUIREMENTS.get(i)).append('\n');
            choices.add(new Choice(Items.AMETHYST_SHARD, label, () -> {
                if (!wasReady || attune(p, index) == 0) CommunityServer.say(p, "Complete that path and reopen the Journey; nothing changed.");
                open(p, Page.STYLES);
            }));
        }
        choices.add(new Choice(Items.NETHER_STAR, "Wear Constellation • " + (done == ALL ? "unlocked" : "attune all six"), () -> {
            if (done != ALL || select(p, 7) == 0) CommunityServer.say(p, "Attune all six paths and reopen the Journey; nothing changed.");
            open(p, Page.STYLES);
        }));
        choices.add(new Choice(Items.GLASS_BOTTLE, "Turn my Infinity trail off", () -> { select(p, 0); open(p, Page.STYLES); }));
        choices.add(new Choice(Items.BANNER.purple(), "Get a Constellation Sigil • " + (done == ALL ? "unlocked" : "attune all six"), () -> {
            if (done != ALL || giveSigil(p) == 0) CommunityServer.say(p, "Attune all six paths, clear a slot and reopen the Journey; nothing was replaced.");
            open(p, Page.STYLES);
        }));
        boolean hideAtView = hidesOthers(p);
        choices.add(new Choice(Items.ENDER_EYE, (hideAtView ? "Show" : "Hide") + " other players' Journey trails", () -> {
            if (hidesOthers(p) != hideAtView || hideOthers(p, !hideAtView) == 0) CommunityServer.say(p, "Your trail preference changed; reopen the Journey.");
            open(p, Page.STYLES);
        }));
        choices.add(new Choice(Items.ARROW, "Back to the six paths", () -> open(p, Page.JOURNEY)));
        return new View("Infinity Journey • Trails", body.toString(), List.copyOf(choices));
    }
    static int open(ServerPlayer p) { return open(p, Page.JOURNEY); }
    static int open(ServerPlayer p, Page page) {
        if (!emptyScreen(p)) return 0;
        if (CrossplaySupport.bedrock(p)) return form(p, page, form -> {
            var ticket = ACTIVE.get(p.getUUID());
            if (ticket == null) return false;
            DELIVERIES.put(p.getUUID(), new Delivery(ticket, form, p.level().getServer().getTickCount() + 3));
            return true;
        });
        var view = view(p, page); var chest = new SimpleContainer(54);
        var cards = CommonsMenu.wrap(view.content());
        for (int i = 0; i < cards.size() && i < 8; i++)
            CreativeStudio.icon(chest, i, Items.WRITTEN_BOOK, view.title() + " • " + (i + 1), cards.get(i).toArray(String[]::new));
        chest.setItem(17, emblem(p, true));
        if (page == Page.SIGNAL_FLAGS) {
            for (var design : TidewatchFlags.Design.values()) chest.setItem(9 + design.ordinal(), design.banner(p));
        }
        var chart = StarLoom.Chart.forPage(page);
        if (chart != null) StarLoom.drawChart(chest, p, chart);
        for (int i = 0; i < view.choices().size(); i++) {
            var choice = view.choices().get(i); CreativeStudio.icon(chest, view.javaSlot(i), choice.icon(), choice.label());
        }
        p.openMenu(new SimpleMenuProvider((sync, inv, who) -> new Handler(sync, inv, chest, p, view), Component.literal(view.title())));
        return 1;
    }
    static final class Handler extends ChestMenu {
        final ServerPlayer owner; final ServerGamePacketListenerImpl connection; final ServerLevel world;
        final GameModes.Mode mode; final GameType nativeMode; final View view;
        Handler(int sync, Inventory inv, SimpleContainer chest, ServerPlayer p, View view) {
            super(MenuType.GENERIC_9x6, sync, inv, chest, 6); owner = p; connection = p.connection;
            world = p.level(); mode = GameModes.current(p); nativeMode = p.gameMode.getGameModeForPlayer(); this.view = view;
        }
        @Override public boolean stillValid(Player p) {
            return p == owner && owner.connection == connection && owner.containerMenu == this && ServerMenu.allowed(owner)
                && owner.level() == world && GameModes.current(owner) == mode && owner.gameMode.getGameModeForPlayer() == nativeMode;
        }
        @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) {}
        @Override public void clicked(int slot, int button, ContainerInput action, Player p) {
            if (p != owner || owner.connection != connection || owner.containerMenu != this) return;
            if (!stillValid(p)) { owner.closeContainer(); return; }
            int index = view.choiceAt(slot);
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE) || button < 0 || button > 1
                || !getCarried().isEmpty() || index < 0 || index >= view.choices().size()) { sendAllDataToRemote(); return; }
            owner.closeContainer(); view.choices().get(index).action().run();
        }
    }
    static int form(ServerPlayer p, Page page, Predicate<Form> sender) {
        if (!emptyScreen(p)) return 0;
        var view = view(p, page);
        var ticket = new Ticket(UUID.randomUUID(), p, p.connection, p.level(), GameModes.current(p),
            p.gameMode.getGameModeForPlayer(), ((ScreenEpochAccess) p).infinity$screenEpoch(),
            p.level().getServer().getTickCount() + 1800);
        ACTIVE.put(p.getUUID(), ticket);
        var form = SimpleForm.builder().title(view.title()).content(view.content());
        for (var choice : view.choices()) {
            if (choice.bedrockTexture() == null) form.button(choice.label());
            else form.button(choice.label(), org.geysermc.cumulus.util.FormImage.Type.PATH, choice.bedrockTexture());
        }
        form.validResultHandler(r -> response(ticket, () -> {
            int i = r.clickedButtonId(); if (i >= 0 && i < view.choices().size()) view.choices().get(i).action().run();
        })).closedOrInvalidResultHandler(() -> response(ticket, () -> {}));
        try { if (sender.test(form.build())) return 1; } catch (RuntimeException unavailable) { /* Refuse below. */ }
        ACTIVE.remove(p.getUUID(), ticket); DELIVERIES.remove(p.getUUID()); return 0;
    }
    static void response(Ticket ticket, Runnable action) {
        var server = ticket.world().getServer();
        if (!server.isSameThread()) { server.execute(() -> response(ticket, action)); return; }
        if (ACTIVE.remove(ticket.player().getUUID(), ticket) && ticket.valid()) action.run();
    }
    static SimpleParticleType particle(int style) {
        return switch (style) {
            case 1 -> ParticleTypes.HAPPY_VILLAGER; case 2 -> ParticleTypes.ENCHANT;
            case 3 -> ParticleTypes.SOUL; case 4 -> ParticleTypes.ELECTRIC_SPARK;
            case 5 -> ParticleTypes.TOTEM_OF_UNDYING; case 6 -> ParticleTypes.WAX_ON;
            default -> ParticleTypes.END_ROD;
        };
    }
    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            InfinityChimes.disconnected(handler);
            var ticket = ACTIVE.get(handler.player.getUUID());
            if (ticket != null && ticket.connection() == handler) { ACTIVE.remove(handler.player.getUUID(), ticket); DELIVERIES.remove(handler.player.getUUID()); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            InfinityChimes.stopped(server);
            ACTIVE.entrySet().removeIf(e -> e.getValue().world().getServer() == server);
            DELIVERIES.entrySet().removeIf(e -> e.getValue().ticket().world().getServer() == server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            InfinityChimes.tick(server);
            for (var entry : DELIVERIES.entrySet()) {
                var delivery = entry.getValue();
                if (delivery.ticket().world().getServer() != server || server.getTickCount() < delivery.due()) continue;
                if (!DELIVERIES.remove(entry.getKey(), delivery) || ACTIVE.get(entry.getKey()) != delivery.ticket()) continue;
                var api = org.geysermc.floodgate.api.FloodgateApi.getInstance();
                try {
                    if (delivery.ticket().valid() && api != null && api.sendForm(entry.getKey(), delivery.form())) continue;
                } catch (RuntimeException unavailable) { /* Refuse below. */ }
                ACTIVE.remove(entry.getKey(), delivery.ticket());
                CommunityServer.say(delivery.ticket().player(), "The Infinity Journey form could not open. Reopen it from Infinity Menu.");
            }
            ACTIVE.entrySet().removeIf(e -> e.getValue().world().getServer() == server && !e.getValue().valid());
            if (server.getTickCount() % 10 != 0) return;
            for (var p : server.getPlayerList().getPlayers()) {
                int selected = visibleStyle(p);
                if (selected == 0) continue;
                double angle = server.getTickCount() * .08, x = p.getX() + Math.cos(angle) * .65, z = p.getZ() + Math.sin(angle) * .65;
                for (var viewer : p.level().getPlayers(q -> q.isAlive() && !q.isSpectator() && maySee(q, p) && q.distanceToSqr(p) < 32 * 32, 8)) {
                    p.level().sendParticles(viewer, particle(selected), false, false, x, p.getY() + 1, z, 1, .02, .02, .02, 0);
                    if (selected == 7) p.level().sendParticles(viewer, ParticleTypes.ENCHANT, false, false,
                        p.getX() * 2 - x, p.getY() + .7, p.getZ() * 2 - z, 1, .02, .02, .02, 0);
                }
            }
        });
    }
}

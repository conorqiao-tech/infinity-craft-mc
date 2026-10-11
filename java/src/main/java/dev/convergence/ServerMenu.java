package dev.convergence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/** Vanilla controls and chest icons work on both Java and touch-screen Geyser clients. */
final class ServerMenu {
    static final int GEAR = 10, KIT = 12, BUILDING = 14, HELPERS = 16;
    static final int POWER = 19, ALTERNATE = 21, SWAP = 23, HELP = 25;
    static final int GAMES = 28, ADVENTURE = 30, BACKPACK = 32, INFO = 34;
    static final int SURVIVAL = 37, CREATIVE = 39, HARDCORE = 41, HUB = 43;
    static final int ADMIN = 47;
    static final int SELF_STATS = 51, CRATES = 46, ARMOR = 48;
    static final int INVENTORY_GEAR = 50, RANKS = 52;
    static final int PROPHECY = 6;
    static final int PREVIOUS = 45, BACK = 49, NEXT = 53, PAGE_SIZE = 45;
    private static final String MARKER = "infinity_server_menu";
    private static final String OWNER = "owner";
    private static final Map<UUID, Selection> LAST_TOOL = new HashMap<>();
    private static final Map<UUID, Integer> READY_AT = new HashMap<>();
    private static final Map<UUID, Integer> SELECTED_CONTROL = new HashMap<>();
    private static final Set<UUID> FULL_NOTICE = new HashSet<>();
    enum Page { MAIN, GEAR, ARMOR, RANKS, HELP, INFO }
    private record Selection(GameModes.Mode mode, int slot) {}

    private ServerMenu() {}

    static boolean isNavigator(ItemStack stack) {
        if (!stack.is(Items.RECOVERY_COMPASS)) return false;
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        var nbt = data.copyTag();
        if (nbt.getIntOr(MARKER, 0) != 1) return false;
        try { UUID.fromString(nbt.getStringOr(OWNER, "")); return true; }
        catch (IllegalArgumentException badOwner) { return false; }
    }

    private static boolean owned(ItemStack stack, ServerPlayer player) {
        return isNavigator(stack) && stack.get(DataComponents.CUSTOM_DATA).copyTag()
            .getStringOr(OWNER, "").equals(player.getStringUUID());
    }

    private static boolean menuControl(ItemStack stack) {
        return isNavigator(stack) || CreativeGearPicker.isPicker(stack);
    }

    static ItemStack navigator(ServerPlayer player) {
        var stack = new ItemStack(Items.RECOVERY_COMPASS);
        var marker = new CompoundTag();
        marker.putInt(MARKER, 1);
        marker.putString(OWNER, player.getStringUUID());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Infinity Menu"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Select or use to open weapons, powers and helpers."),
            Component.literal("Your held tool is restored before the menu opens."),
            Component.literal("Personal control item; a lost copy is replaced."))));
        return stack;
    }

    static boolean allowed(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && !player.isSpectator()
            && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
            && !GameModes.TRANSITIONS.contains(player.getUUID())
            && !GameModes.PENDING.containsKey(player.getUUID())
            && !GameModes.OPERATOR_TRANSFERS.containsKey(player.getUUID());
    }

    static boolean gearAllowed(ServerPlayer player) {
        return allowed(player) && (player.isCreative() || Memberships.gameplayBypass(player) || player.createCommandSourceStack().permissions()
            .hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)));
    }

    /** Add only to empty slots, after a mode's inventory has finished restoring. */
    static boolean ensureNavigator(ServerPlayer player) {
        if (!allowed(player) || player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty()) return false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
            if (owned(inventory.getItem(slot), player)) { FULL_NOTICE.remove(player.getUUID()); return true; }
        int slot = inventory.getItem(8).isEmpty() ? 8 : -1;
        if (slot < 0) for (int index = 0; index < 9; index++)
            if (inventory.getItem(index).isEmpty()) { slot = index; break; }
        if (slot < 0) slot = inventory.getFreeSlot();
        if (slot < 0) {
            if (FULL_NOTICE.add(player.getUUID())) CommunityServer.say(player,
                "Clear an inventory slot for your Infinity Menu. The Main Hub menu sign or /menu also opens it.");
            return false;
        }
        inventory.setItem(slot, navigator(player));
        player.inventoryMenu.sendAllDataToRemote();
        FULL_NOTICE.remove(player.getUUID());
        CommunityServer.say(player, "Select the Infinity Menu compass for gear, powers, games and AI Helpers.");
        return true;
    }

    /** Restore the last tool before taking the action-context snapshot. */
    private static void restoreTool(ServerPlayer player) {
        var inventory = player.getInventory();
        if (!owned(player.getMainHandItem(), player)) return;
        var selection = LAST_TOOL.get(player.getUUID());
        int slot = selection != null && selection.mode() == GameModes.current(player)
            && !menuControl(inventory.getItem(selection.slot())) ? selection.slot() : -1;
        if (slot < 0) for (int index = 0; index < 9; index++)
            if (!inventory.getItem(index).isEmpty() && !menuControl(inventory.getItem(index))) { slot = index; break; }
        if (slot < 0) for (int index = 0; index < 9; index++)
            if (!menuControl(inventory.getItem(index))) { slot = index; break; }
        if (slot >= 0) {
            inventory.setSelectedSlot(slot);
            player.connection.send(new ClientboundSetHeldSlotPacket(slot));
            player.inventoryMenu.sendAllDataToRemote();
        }
    }

    static int open(ServerPlayer player) { return open(player, Page.MAIN, 0); }

    private static void icon(SimpleContainer view, int slot, Item item, String label, String... lore) {
        var stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        stack.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lore).map(Component::literal).map(t -> (Component)t).toList()));
        view.setItem(slot, stack);
    }

    private static String label(String path) {
        return GearNames.label(path);
    }

    /** Short tooltip cards avoid a single help tooltip extending beyond small iPad screens. */
    private static void information(SimpleContainer view, String title, String text) {
        var lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > 43) {
                lines.add(line.toString()); line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        for (int start = 0, card = 0; start < lines.size() && card < PAGE_SIZE; start += 6, card++) {
            icon(view, card, Items.BOOK, title + " • " + (card + 1),
                lines.subList(start, Math.min(start + 6, lines.size())).toArray(String[]::new));
        }
    }

    private static int open(ServerPlayer player, Page page, int index) {
        if (!allowed(player)) return 0;
        if (player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty()) {
            CommunityServer.say(player, "Close your current screen and empty the cursor before opening Infinity Menu.");
            return 0;
        }
        restoreTool(player);
        // If every hotbar slot is a control, there is no tool slot to restore.
        // Leave a held control selected, but do not reopen it every tick after closing.
        if (owned(player.getMainHandItem(), player))
            SELECTED_CONTROL.put(player.getUUID(), player.getInventory().getSelectedSlot());
        else SELECTED_CONTROL.remove(player.getUUID());
        var paths = Convergence.ITEMS.keySet().stream().sorted().toList();
        int pageIndex = Math.max(0, Math.min(index, Math.max(0, (paths.size() - 1) / PAGE_SIZE)));
        var view = new SimpleContainer(54);
        String title = "Infinity Menu";
        if (page == Page.MAIN) {
            icon(view, 4, Items.RECOVERY_COMPASS, "Your tools, worlds and helpers",
                "Held: " + player.getMainHandItem().getHoverName().getString(), "Tap an icon. No /convergence command needed.");
            icon(view,PROPHECY,Items.WRITTEN_BOOK,"The Odyssey • your prophecy","Receive or recover your starting prophecy book.","Discover rare biome-themed ruins across three realms.");
            icon(view, GEAR, Items.NETHERITE_SWORD, "Weapons, tools and blocks", "Choose any Infinity item.", "Requires Creative, Admin or OP2.", "Your old items are preserved.");
            icon(view, KIT, Items.CHEST, "Full Infinity kit", "All gear plus rockets and seeds.", "Requires Creative, Admin or OP2.");
            icon(view, BUILDING, Items.QUARTZ_BLOCK, "Building kit", "Building blocks and both wands.", "Requires Creative, Admin or OP4.");
            icon(view, HELPERS, Items.IRON_GOLEM_SPAWN_EGG, "AI Helpers • open your squad",
                "OP4: tap Create a helper in the squad menu.", "Choose a mode, Follow, Guard or Stay.", "Server-changing proposals still require both approvals.");
            icon(view, POWER, Items.BLAZE_POWDER, "Use held power", "Uses your held Infinity weapon or tool.", "The menu closes so you can see the result.");
            icon(view, ALTERNATE, Items.ENDER_PEARL, "Use alternate power", "The same as sneak + use on your held tool.", "Also casts Ward while holding the Infinity shield.");
            icon(view, SWAP, Items.SHIELD, "Swap main hand and offhand", "Works with any held equipment.", "Useful for Bedrock shields, spears and building blocks.");
            icon(view, HELP, Items.BOOK, "How weapons and tools work", "Read the controls in short help cards.");
            icon(view, GAMES, Items.SLIME_BALL, "Minigames", "Choose from the course menu.");
            icon(view, ADVENTURE, Items.MAP, "Adventure maps", "Choose an adventure course.");
            icon(view, BACKPACK, Items.LEATHER_CHESTPLATE, "Open backpack", "Your existing unlock and mode rules still apply.");
            icon(view, INFO, Items.OAK_SIGN, "Server and joining information", "Java and Bedrock share this server.");
            icon(view, SURVIVAL, Items.IRON_PICKAXE, "Play Survival", "Separate inventory; ordinary travel checks apply.");
            icon(view, CREATIVE, Items.GRASS_BLOCK, "Play Creative", "Build and choose Infinity gear.");
            icon(view, HARDCORE, Items.IRON_SWORD, "Play Hardcore", Memberships.gameplayBypass(player)
                ? "Admin/OP can re-enter; separate inventory." : "One life; separate inventory.");
            icon(view, HUB, Items.NETHER_STAR, "Main Hub", "Return to the main lobby.");
            icon(view, ADMIN, Items.COMMAND_BLOCK, "Admin editor • players and AI",
                "Admin/OP4: edit players or loaded AI helpers.",
                "Review health, speed, damage and more before applying.", "Base stats and equipment bonuses stay separate.");
            icon(view,SELF_STATS,Items.APPLE,"My stats • quick edit","OP4: jump straight to your own health and attributes.");
            icon(view,CRATES,Items.DYED_SHULKER_BOX.purple(),"Convergence Set • inventory boxes","Places the complete set into two labeled gear boxes.","Place and open the boxes to take items. Requires Creative or gear permission.");
            icon(view,ARMOR,Items.LEATHER_CHESTPLATE,"Armor sets • includes chestplates","Claim Aurora or Ember with all four pieces.","The Infinity winged chestplate uses an elytra on Bedrock.");
            icon(view,INVENTORY_GEAR,Items.NETHERITE_CHESTPLATE,"Armor & tools → inventory","Adds real equipment directly to empty inventory slots.","Keeps your existing items; Creative, Admin or OP2 required.");
            icon(view,RANKS,Items.EMERALD,"Ranks & subscriptions","Your badge, permanent rank and all milestone requirements.");
        } else if (page == Page.GEAR) {
            title = "Infinity Gear • " + (pageIndex + 1);
            for (int slot = 0, pathIndex = pageIndex * PAGE_SIZE; slot < PAGE_SIZE && pathIndex < paths.size(); slot++, pathIndex++) {
                String id = paths.get(pathIndex);
                String path = id.substring("convergence:".length());
                var item = CrossplaySupport.BASES.get(path);
                if (item == null) throw new IllegalStateException("No vanilla menu icon for " + id);
                icon(view, slot, item, label(path), gearAllowed(player)
                    ? "Tap to equip this item; your held item is kept." : "Requires Creative, Admin or OP2.");
            }
            if (pageIndex > 0) icon(view, PREVIOUS, Items.ARROW, "Previous gear page");
            if ((pageIndex + 1) * PAGE_SIZE < paths.size()) icon(view, NEXT, Items.ARROW, "Next gear page");
        } else if (page == Page.ARMOR) {
            title="Infinity Armor Sets";
            icon(view,10,Items.LEATHER_CHESTPLATE,"Aurora • all four pieces","Helmet, chestplate, leggings and boots.","Earn Enchanter or use Admin access. Clear four inventory slots.");
            icon(view,12,Items.LEATHER_CHESTPLATE,"Ember • all four pieces","Helmet, chestplate, leggings and boots.","Earn Into Fire or use Admin access. Clear four inventory slots.");
            icon(view,14,Items.ELYTRA,"Infinity winged chestplate","Uses native elytra artwork and flight on Bedrock.","Choose Aurora or Ember above for a chestplate appearance.","Requires Creative, Admin or OP2.");
        } else if(page==Page.RANKS) {
            title="Ranks & subscriptions";var memberships=Memberships.get(player.level().getServer());
            memberships.syncAchievements(player);
            icon(view,4,Items.PLAYER_HEAD,"Your badge: "+memberships.label(player.getUUID()),"Permanent rank: "+memberships.permanentTier(player.getUUID()),"Admin and OP4 are owner roles; viewing this menu does not grant them.");
            icon(view,9,Items.STONE,"FREE","Every game mode is included. No payment required.");
            int slot=11;
            for(var goal:Memberships.GOALS) {
                var lines=new ArrayList<String>();
                for(var milestone:goal.milestones())lines.add((memberships.done(player,milestone)?"✓ ":"• ")+milestone.task());
                lines.add("Complete all three, or use the Survival item trade.");
                icon(view,slot,Items.EMERALD,goal.tier().name(),lines.toArray(String[]::new));slot+=2;
            }
            icon(view,28,Items.GOLD_INGOT,"Optional USD/month plans","Go $50 • Plus $75 • Pro $100 • Ultra $200","Checkout is off until the owner connects Tebex.");
            icon(view,30,Items.COMMAND_BLOCK,"ADMIN / OP4","Free owner-controlled access. Cannot be purchased or earned by viewing a menu.");
        } else if (page == Page.HELP) {
            title = "Infinity Controls";
            information(view, "Controls", Convergence.helpText());
        } else {
            title = "Infinity Server Info";
            information(view, "Server", CrossplaySupport.serverInfoText());
        }
        icon(view, BACK, Items.BARRIER, page == Page.MAIN ? "Close menu" : "Back to Infinity Menu");
        player.openMenu(new SimpleMenuProvider((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, page, pageIndex, paths), Component.literal(title)));
        return 1;
    }

    static final class Handler extends ChestMenu {
        final ServerPlayer owner;
        final ServerGamePacketListenerImpl ownerConnection;
        final Page page;
        final int pageIndex;
        final List<String> paths;
        final GameModes.Mode mode;
        final net.minecraft.server.level.ServerLevel world;
        final int heldSlot;
        final ItemStack held, offhand;

        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner,
                Page page, int pageIndex, List<String> paths) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            this.owner = owner; this.page = page; this.pageIndex = pageIndex; this.paths = List.copyOf(paths);
            this.ownerConnection = owner.connection;
            this.mode = GameModes.current(owner); this.world = owner.level();
            this.heldSlot = inventory.getSelectedSlot();
            this.held = owner.getMainHandItem().copy(); this.offhand = owner.getOffhandItem().copy();
        }

        @Override public boolean stillValid(Player player) {
            return player == owner && owner.connection == ownerConnection && allowed(owner) && owner.containerMenu == this
                && GameModes.current(owner) == mode && owner.level() == world;
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) {}

        private void navigate(Page next, int index) { owner.closeContainer(); open(owner, next, index); }

        /** A refusal stays visible inside the chest; chat can be obscured by its screen. */
        private void locked(int slot, String reason, String... hints) {
            ItemStack icon = getSlot(slot).getItem().copy();
            String name = icon.getHoverName().getString();
            if (!name.startsWith("Locked • ")) name = "Locked • " + name;
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            var lines = new ArrayList<Component>();
            lines.add(Component.literal(reason));
            for (String hint : hints) lines.add(Component.literal(hint));
            icon.set(DataComponents.LORE, new ItemLore(lines));
            getSlot(slot).setByPlayer(icon);
            broadcastChanges();
            owner.sendOverlayMessage(Component.literal(reason));
        }

        private boolean unchangedEquipment() {
            return owner.getInventory().getSelectedSlot() == heldSlot
                && ItemStack.isSameItemSameComponents(held, owner.getMainHandItem())
                && held.getCount() == owner.getMainHandItem().getCount()
                && ItemStack.isSameItemSameComponents(offhand, owner.getOffhandItem())
                && offhand.getCount() == owner.getOffhandItem().getCount();
        }

        @Override public void clicked(int slot, int button, ContainerInput action, Player player) {
            // A packet for a closed or replaced screen must never close or act through the new one.
            if (player != owner || owner.containerMenu != this || owner.connection != ownerConnection) return;
            if (!stillValid(player)) { owner.closeContainer(); return; }
            if (!getCarried().isEmpty() || (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)
                || button < 0 || button > 1 || slot < 0 || slot >= 54) { sendAllDataToRemote(); return; }
            if (slot == BACK) {
                if (page == Page.MAIN) owner.closeContainer(); else navigate(Page.MAIN, 0);
                return;
            }
            if (page == Page.GEAR) {
                if (slot == PREVIOUS && pageIndex > 0) { navigate(Page.GEAR, pageIndex - 1); return; }
                if (slot == NEXT && (pageIndex + 1) * PAGE_SIZE < paths.size()) { navigate(Page.GEAR, pageIndex + 1); return; }
                int index = pageIndex * PAGE_SIZE + slot;
                if (slot < PAGE_SIZE && index < paths.size()) {
                    if (!gearAllowed(owner)) {
                        locked(slot, "Creative, Admin or OP2 required.", "Back -> Play Creative to receive gear.", "Your existing items stay in place.");
                        return;
                    }
                    String path = paths.get(index).substring("convergence:".length());
                    owner.closeContainer();
                    Convergence.holdCreativeItem(owner, path);
                }
                return;
            }
            if(page==Page.ARMOR) {
                if(slot==10 || slot==12) { owner.closeContainer();BackpackStorage.armor(owner,slot==10?"aurora":"ember"); }
                else if(slot==14) { owner.closeContainer();Convergence.holdCreativeItem(owner,"chestplate"); }
                return;
            }
            if (page != Page.MAIN) return;
            if(slot==PROPHECY){owner.closeContainer();OdysseyProphecy.give(owner,true);return;}
            if (slot == GEAR) { navigate(Page.GEAR, 0); return; }
            if (slot == ARMOR) { navigate(Page.ARMOR, 0); return; }
            if (slot == HELP) { navigate(Page.HELP, 0); return; }
            if (slot == INFO) { navigate(Page.INFO, 0); return; }
            if(slot==RANKS){navigate(Page.RANKS,0);return;}
            if(slot==INVENTORY_GEAR){owner.closeContainer();GearCrates.giveDirect(owner);return;}
            if (slot == CRATES) {
                if(!gearAllowed(owner)) { locked(slot,"Creative or gear permission required.");return; }
                owner.closeContainer();GearCrates.give(owner);return;
            }
            if (slot == ADMIN || slot == SELF_STATS) {
                if (!Memberships.operator(owner)) {
                    locked(slot, "Admin or OP level 4 required.", "Only a current operator can edit player stats.");
                    return;
                }
                owner.closeContainer();
                if(slot==SELF_STATS)AdminStatsMenu.openStats(owner,owner,0);else AdminStatsMenu.open(owner);return;
            }
            if (slot == HELPERS) {
                if (!AgentMenu.allowed(owner)) {
                    locked(slot, "OP level 4 required.", "Ask the owner for Admin or OP4.", "Admin grants OP4 after its role is saved.");
                    return;
                }
                owner.closeContainer(); AgentMenu.open(owner); return;
            }
            if (slot == KIT || slot == BUILDING) {
                boolean permitted = slot == KIT ? gearAllowed(owner) : owner.isCreative() || Memberships.gameplayBypass(owner);
                if (!permitted) {
                    locked(slot, slot == KIT ? "Creative, Admin or OP2 required." : "Creative, Admin or OP4 required.", "Choose Play Creative from the main menu.");
                    return;
                }
                owner.closeContainer();
                if (slot == KIT) Convergence.giveKit(owner); else Convergence.giveBuildingKit(owner);
                return;
            }
            if (slot == POWER || slot == ALTERNATE || slot == SWAP) {
                if (!unchangedEquipment()) {
                    owner.closeContainer();
                    CommunityServer.say(owner, "Your held equipment changed. Reopen Infinity Menu to review the current tool.");
                    return;
                }
                owner.closeContainer();
                if (slot == SWAP) CrossplaySupport.swapHands(owner);
                else if (CrossplaySupport.usePower(owner, slot == ALTERNATE) == 0)
                    CommunityServer.say(owner, "Hold an Infinity weapon or tool, then select Infinity Menu again to use its power.");
                return;
            }
            if (slot == GAMES || slot == ADVENTURE) {
                owner.closeContainer(); CourseSelector.open(owner, slot == GAMES ? GameModes.Mode.MINIGAMES : GameModes.Mode.ADVENTURE); return;
            }
            if (slot == BACKPACK) { owner.closeContainer(); BackpackStorage.open(owner); return; }
            GameModes.Mode next = switch (slot) {
                case SURVIVAL -> GameModes.Mode.SURVIVAL;
                case CREATIVE -> GameModes.Mode.CREATIVE;
                case HARDCORE -> GameModes.Mode.HARDCORE;
                case HUB -> GameModes.Mode.HUB;
                default -> null;
            };
            if (next != null) { owner.closeContainer(); GameModes.request(owner, next, next == GameModes.Mode.HUB ? "main" : null); }
        }
    }

    /** Runs after mode restoration. Selection alone works even without Bedrock's Use button. */
    static void tickPlayer(ServerPlayer player) {
        if (!allowed(player)) return;
        int tick = player.level().getServer().getTickCount();
        Integer ready = READY_AT.get(player.getUUID());
        if (ready != null && tick < ready) return;
        if (ready != null) READY_AT.remove(player.getUUID());
        if (player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty()) return;
        if (!menuControl(player.getMainHandItem())) LAST_TOOL.put(player.getUUID(),
            new Selection(GameModes.current(player), player.getInventory().getSelectedSlot()));
        if (ready != null || tick % 20 == 0) ensureNavigator(player);
        if (!owned(player.getMainHandItem(), player)) SELECTED_CONTROL.remove(player.getUUID());
        else if (!java.util.Objects.equals(SELECTED_CONTROL.get(player.getUUID()), player.getInventory().getSelectedSlot())) open(player);
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
            dispatcher.register(Commands.literal("menu").executes(c -> open(c.getSource().getPlayerOrException()))));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LAST_TOOL.remove(handler.player.getUUID()); SELECTED_CONTROL.remove(handler.player.getUUID());
            READY_AT.put(handler.player.getUUID(), server.getTickCount() + 5);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> {
            LAST_TOOL.remove(player.getUUID()); SELECTED_CONTROL.remove(player.getUUID());
            READY_AT.put(player.getUUID(), player.level().getServer().getTickCount() + 5);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { for (var player : server.getPlayerList().getPlayers()) tickPlayer(player); });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && owned(player.getItemInHand(hand), serverPlayer)) {
                open(serverPlayer); return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ItemEntity item && isNavigator(item.getItem())) entity.discard();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID(); LAST_TOOL.remove(id); READY_AT.remove(id); FULL_NOTICE.remove(id); SELECTED_CONTROL.remove(id);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LAST_TOOL.clear(); READY_AT.clear(); FULL_NOTICE.clear(); SELECTED_CONTROL.clear(); });
    }
}

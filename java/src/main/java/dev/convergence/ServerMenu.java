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
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

/** Vanilla controls and chest icons work on both Java and touch-screen Geyser clients. */
final class ServerMenu {
    static final int GEAR = 10, KIT = 12, BUILDING = 14, HELPERS = 16;
    static final int POWER = 19, ALTERNATE = 21, SWAP = 23, HELP = 25;
    static final int GAMES = 28, ADVENTURE = 30, BACKPACK = 32, INFO = 34;
    static final int SURVIVAL = 37, CREATIVE = 39, HARDCORE = 41, HUB = 43;
    static final int ADMIN = 47;
    static final int SELF_STATS = 51, CRATES = 46, ARMOR = 48;
    static final int PREVIOUS = 45, BACK = 49, NEXT = 53, PAGE_SIZE = 45;
    private static final String MARKER = "infinity_server_menu";
    private static final String OWNER = "owner";
    private static final Map<UUID, Selection> LAST_TOOL = new HashMap<>();
    private static final Map<UUID, Integer> READY_AT = new HashMap<>();
    private static final Map<UUID, Integer> SELECTED_CONTROL = new HashMap<>();
    private static final Set<UUID> FULL_NOTICE = new HashSet<>();
    enum Page { MAIN, GEAR, ARMOR, HELP, INFO }
    private record Selection(GameModes.Mode mode, int slot) {}

    private ServerMenu() {}

    static boolean isNavigator(ItemStack stack) {
        if (!stack.isOf(Items.RECOVERY_COMPASS)) return false;
        var data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return false;
        var nbt = data.copyNbt();
        if (nbt.getInt(MARKER, 0) != 1) return false;
        try { UUID.fromString(nbt.getString(OWNER, "")); return true; }
        catch (IllegalArgumentException badOwner) { return false; }
    }

    private static boolean owned(ItemStack stack, ServerPlayerEntity player) {
        return isNavigator(stack) && stack.get(DataComponentTypes.CUSTOM_DATA).copyNbt()
            .getString(OWNER, "").equals(player.getUuidAsString());
    }

    private static boolean menuControl(ItemStack stack) {
        return isNavigator(stack) || CreativeGearPicker.isPicker(stack);
    }

    static ItemStack navigator(ServerPlayerEntity player) {
        var stack = new ItemStack(Items.RECOVERY_COMPASS);
        var marker = new NbtCompound();
        marker.putInt(MARKER, 1);
        marker.putString(OWNER, player.getUuidAsString());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(marker));
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Infinity Menu"));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.literal("Select or use to open weapons, powers and helpers."),
            Text.literal("Your held tool is restored before the menu opens."),
            Text.literal("Personal control item; a lost copy is replaced."))));
        return stack;
    }

    static boolean allowed(ServerPlayerEntity player) {
        return player.isAlive() && !player.isRemoved() && !player.isDisconnected() && !player.isSpectator()
            && player.getEntityWorld().getServer().getPlayerManager().getPlayer(player.getUuid()) == player
            && !GameModes.TRANSITIONS.contains(player.getUuid())
            && !GameModes.PENDING.containsKey(player.getUuid())
            && !GameModes.OPERATOR_TRANSFERS.containsKey(player.getUuid());
    }

    static boolean gearAllowed(ServerPlayerEntity player) {
        return allowed(player) && (player.isCreative() || Memberships.gameplayBypass(player) || player.getCommandSource().getPermissions()
            .hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS)));
    }

    /** Add only to empty slots, after a mode's inventory has finished restoring. */
    static boolean ensureNavigator(ServerPlayerEntity player) {
        if (!allowed(player) || player.currentScreenHandler != player.playerScreenHandler
            || !player.currentScreenHandler.getCursorStack().isEmpty()) return false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++)
            if (owned(inventory.getStack(slot), player)) { FULL_NOTICE.remove(player.getUuid()); return true; }
        int slot = inventory.getStack(8).isEmpty() ? 8 : -1;
        if (slot < 0) for (int index = 0; index < 9; index++)
            if (inventory.getStack(index).isEmpty()) { slot = index; break; }
        if (slot < 0) slot = inventory.getEmptySlot();
        if (slot < 0) {
            if (FULL_NOTICE.add(player.getUuid())) CommunityServer.say(player,
                "Clear an inventory slot for your Infinity Menu. The Main Hub menu sign or /menu also opens it.");
            return false;
        }
        inventory.setStack(slot, navigator(player));
        player.playerScreenHandler.syncState();
        FULL_NOTICE.remove(player.getUuid());
        CommunityServer.say(player, "Select the Infinity Menu compass for gear, powers, games and AI Helpers.");
        return true;
    }

    /** Restore the last tool before taking the action-context snapshot. */
    private static void restoreTool(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        if (!owned(player.getMainHandStack(), player)) return;
        var selection = LAST_TOOL.get(player.getUuid());
        int slot = selection != null && selection.mode() == GameModes.current(player)
            && !menuControl(inventory.getStack(selection.slot())) ? selection.slot() : -1;
        if (slot < 0) for (int index = 0; index < 9; index++)
            if (!inventory.getStack(index).isEmpty() && !menuControl(inventory.getStack(index))) { slot = index; break; }
        if (slot < 0) for (int index = 0; index < 9; index++)
            if (!menuControl(inventory.getStack(index))) { slot = index; break; }
        if (slot >= 0) {
            inventory.setSelectedSlot(slot);
            player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(slot));
            player.playerScreenHandler.syncState();
        }
    }

    static int open(ServerPlayerEntity player) { return open(player, Page.MAIN, 0); }

    private static void icon(SimpleInventory view, int slot, Item item, String label, String... lore) {
        var stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(label));
        stack.set(DataComponentTypes.LORE, new LoreComponent(java.util.Arrays.stream(lore).map(Text::literal).map(t -> (Text)t).toList()));
        view.setStack(slot, stack);
    }

    private static String label(String path) {
        var result = new StringBuilder("Infinity ");
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return result.toString().trim();
    }

    /** Short tooltip cards avoid a single help tooltip extending beyond small iPad screens. */
    private static void information(SimpleInventory view, String title, String text) {
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

    private static int open(ServerPlayerEntity player, Page page, int index) {
        if (!allowed(player)) return 0;
        if (player.currentScreenHandler != player.playerScreenHandler
            || !player.currentScreenHandler.getCursorStack().isEmpty()) {
            CommunityServer.say(player, "Close your current screen and empty the cursor before opening Infinity Menu.");
            return 0;
        }
        restoreTool(player);
        // If every hotbar slot is a control, there is no tool slot to restore.
        // Leave a held control selected, but do not reopen it every tick after closing.
        if (owned(player.getMainHandStack(), player))
            SELECTED_CONTROL.put(player.getUuid(), player.getInventory().getSelectedSlot());
        else SELECTED_CONTROL.remove(player.getUuid());
        var paths = Convergence.ITEMS.keySet().stream().sorted().toList();
        int pageIndex = Math.max(0, Math.min(index, Math.max(0, (paths.size() - 1) / PAGE_SIZE)));
        var view = new SimpleInventory(54);
        String title = "Infinity Menu";
        if (page == Page.MAIN) {
            icon(view, 4, Items.RECOVERY_COMPASS, "Your tools, worlds and helpers",
                "Held: " + player.getMainHandStack().getName().getString(), "Tap an icon. No /convergence command needed.");
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
            icon(view,CRATES,Items.PURPLE_SHULKER_BOX,"Convergence Set • inventory boxes","Places the complete set into two labeled gear boxes.","Place and open the boxes to take items. Requires Creative or gear permission.");
            icon(view,ARMOR,Items.LEATHER_CHESTPLATE,"Armor sets • includes chestplates","Claim Aurora or Ember with all four pieces.","The Infinity winged chestplate uses an elytra on Bedrock.");
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
        } else if (page == Page.HELP) {
            title = "Infinity Controls";
            information(view, "Controls", Convergence.helpText());
        } else {
            title = "Infinity Server Info";
            information(view, "Server", CrossplaySupport.serverInfoText());
        }
        icon(view, BACK, Items.BARRIER, page == Page.MAIN ? "Close menu" : "Back to Infinity Menu");
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, page, pageIndex, paths), Text.literal(title)));
        return 1;
    }

    static final class Handler extends GenericContainerScreenHandler {
        final ServerPlayerEntity owner;
        final ServerPlayNetworkHandler ownerConnection;
        final Page page;
        final int pageIndex;
        final List<String> paths;
        final GameModes.Mode mode;
        final net.minecraft.server.world.ServerWorld world;
        final int heldSlot;
        final ItemStack held, offhand;

        Handler(int sync, PlayerInventory inventory, SimpleInventory view, ServerPlayerEntity owner,
                Page page, int pageIndex, List<String> paths) {
            super(ScreenHandlerType.GENERIC_9X6, sync, inventory, view, 6);
            this.owner = owner; this.page = page; this.pageIndex = pageIndex; this.paths = List.copyOf(paths);
            this.ownerConnection = owner.networkHandler;
            this.mode = GameModes.current(owner); this.world = owner.getEntityWorld();
            this.heldSlot = inventory.getSelectedSlot();
            this.held = owner.getMainHandStack().copy(); this.offhand = owner.getOffHandStack().copy();
        }

        @Override public boolean canUse(PlayerEntity player) {
            return player == owner && owner.networkHandler == ownerConnection && allowed(owner) && owner.currentScreenHandler == this
                && GameModes.current(owner) == mode && owner.getEntityWorld() == world;
        }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot, int selected) {}

        private void navigate(Page next, int index) { owner.closeHandledScreen(); open(owner, next, index); }

        /** A refusal stays visible inside the chest; chat can be obscured by its screen. */
        private void locked(int slot, String reason, String... hints) {
            ItemStack icon = getSlot(slot).getStack().copy();
            String name = icon.getName().getString();
            if (!name.startsWith("Locked • ")) name = "Locked • " + name;
            icon.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
            var lines = new ArrayList<Text>();
            lines.add(Text.literal(reason));
            for (String hint : hints) lines.add(Text.literal(hint));
            icon.set(DataComponentTypes.LORE, new LoreComponent(lines));
            getSlot(slot).setStack(icon);
            sendContentUpdates();
            owner.sendMessage(Text.literal(reason), true);
        }

        private boolean unchangedEquipment() {
            return owner.getInventory().getSelectedSlot() == heldSlot
                && ItemStack.areItemsAndComponentsEqual(held, owner.getMainHandStack())
                && held.getCount() == owner.getMainHandStack().getCount()
                && ItemStack.areItemsAndComponentsEqual(offhand, owner.getOffHandStack())
                && offhand.getCount() == owner.getOffHandStack().getCount();
        }

        @Override public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            // A packet for a closed or replaced screen must never close or act through the new one.
            if (player != owner || owner.currentScreenHandler != this || owner.networkHandler != ownerConnection) return;
            if (!canUse(player)) { owner.closeHandledScreen(); return; }
            if (!getCursorStack().isEmpty() || (action != SlotActionType.PICKUP && action != SlotActionType.QUICK_MOVE)
                || button < 0 || button > 1 || slot < 0 || slot >= 54) { syncState(); return; }
            if (slot == BACK) {
                if (page == Page.MAIN) owner.closeHandledScreen(); else navigate(Page.MAIN, 0);
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
                    owner.closeHandledScreen();
                    Convergence.holdCreativeItem(owner, path);
                }
                return;
            }
            if(page==Page.ARMOR) {
                if(slot==10 || slot==12) { owner.closeHandledScreen();BackpackStorage.armor(owner,slot==10?"aurora":"ember"); }
                else if(slot==14) { owner.closeHandledScreen();Convergence.holdCreativeItem(owner,"chestplate"); }
                return;
            }
            if (page != Page.MAIN) return;
            if (slot == GEAR) { navigate(Page.GEAR, 0); return; }
            if (slot == ARMOR) { navigate(Page.ARMOR, 0); return; }
            if (slot == HELP) { navigate(Page.HELP, 0); return; }
            if (slot == INFO) { navigate(Page.INFO, 0); return; }
            if (slot == CRATES) {
                if(!gearAllowed(owner)) { locked(slot,"Creative or gear permission required.");return; }
                owner.closeHandledScreen();GearCrates.give(owner);return;
            }
            if (slot == ADMIN || slot == SELF_STATS) {
                if (!Memberships.operator(owner)) {
                    locked(slot, "Admin or OP level 4 required.", "Only a current operator can edit player stats.");
                    return;
                }
                owner.closeHandledScreen();
                if(slot==SELF_STATS)AdminStatsMenu.openStats(owner,owner,0);else AdminStatsMenu.open(owner);return;
            }
            if (slot == HELPERS) {
                if (!AgentMenu.allowed(owner)) {
                    locked(slot, "OP level 4 required.", "Ask the owner for Admin or OP4.", "Admin grants OP4 after its role is saved.");
                    return;
                }
                owner.closeHandledScreen(); AgentMenu.open(owner); return;
            }
            if (slot == KIT || slot == BUILDING) {
                boolean permitted = slot == KIT ? gearAllowed(owner) : owner.isCreative() || Memberships.gameplayBypass(owner);
                if (!permitted) {
                    locked(slot, slot == KIT ? "Creative, Admin or OP2 required." : "Creative, Admin or OP4 required.", "Choose Play Creative from the main menu.");
                    return;
                }
                owner.closeHandledScreen();
                if (slot == KIT) Convergence.giveKit(owner); else Convergence.giveBuildingKit(owner);
                return;
            }
            if (slot == POWER || slot == ALTERNATE || slot == SWAP) {
                if (!unchangedEquipment()) {
                    owner.closeHandledScreen();
                    CommunityServer.say(owner, "Your held equipment changed. Reopen Infinity Menu to review the current tool.");
                    return;
                }
                owner.closeHandledScreen();
                if (slot == SWAP) CrossplaySupport.swapHands(owner);
                else if (CrossplaySupport.usePower(owner, slot == ALTERNATE) == 0)
                    CommunityServer.say(owner, "Hold an Infinity weapon or tool, then select Infinity Menu again to use its power.");
                return;
            }
            if (slot == GAMES || slot == ADVENTURE) {
                owner.closeHandledScreen(); CourseSelector.open(owner, slot == GAMES ? GameModes.Mode.MINIGAMES : GameModes.Mode.ADVENTURE); return;
            }
            if (slot == BACKPACK) { owner.closeHandledScreen(); BackpackStorage.open(owner); return; }
            GameModes.Mode next = switch (slot) {
                case SURVIVAL -> GameModes.Mode.SURVIVAL;
                case CREATIVE -> GameModes.Mode.CREATIVE;
                case HARDCORE -> GameModes.Mode.HARDCORE;
                case HUB -> GameModes.Mode.HUB;
                default -> null;
            };
            if (next != null) { owner.closeHandledScreen(); GameModes.request(owner, next, next == GameModes.Mode.HUB ? "main" : null); }
        }
    }

    /** Runs after mode restoration. Selection alone works even without Bedrock's Use button. */
    static void tickPlayer(ServerPlayerEntity player) {
        if (!allowed(player)) return;
        int tick = player.getEntityWorld().getServer().getTicks();
        Integer ready = READY_AT.get(player.getUuid());
        if (ready != null && tick < ready) return;
        if (ready != null) READY_AT.remove(player.getUuid());
        if (player.currentScreenHandler != player.playerScreenHandler
            || !player.currentScreenHandler.getCursorStack().isEmpty()) return;
        if (!menuControl(player.getMainHandStack())) LAST_TOOL.put(player.getUuid(),
            new Selection(GameModes.current(player), player.getInventory().getSelectedSlot()));
        if (ready != null || tick % 20 == 0) ensureNavigator(player);
        if (!owned(player.getMainHandStack(), player)) SELECTED_CONTROL.remove(player.getUuid());
        else if (!java.util.Objects.equals(SELECTED_CONTROL.get(player.getUuid()), player.getInventory().getSelectedSlot())) open(player);
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
            dispatcher.register(CommandManager.literal("menu").executes(c -> open(c.getSource().getPlayerOrThrow()))));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            LAST_TOOL.remove(handler.player.getUuid()); SELECTED_CONTROL.remove(handler.player.getUuid());
            READY_AT.put(handler.player.getUuid(), server.getTicks() + 5);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((old, player, alive) -> {
            LAST_TOOL.remove(player.getUuid()); SELECTED_CONTROL.remove(player.getUuid());
            READY_AT.put(player.getUuid(), player.getEntityWorld().getServer().getTicks() + 5);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { for (var player : server.getPlayerManager().getPlayerList()) tickPlayer(player); });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && owned(player.getStackInHand(hand), serverPlayer)) {
                open(serverPlayer); return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ItemEntity item && isNavigator(item.getStack())) entity.discard();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid(); LAST_TOOL.remove(id); READY_AT.remove(id); FULL_NOTICE.remove(id); SELECTED_CONTROL.remove(id);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LAST_TOOL.clear(); READY_AT.clear(); FULL_NOTICE.clear(); SELECTED_CONTROL.clear(); });
    }
}

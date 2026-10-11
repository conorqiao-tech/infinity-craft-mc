package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

/** Server-owned vanilla icons: editing works on Java and Geyser without transferring items. */
final class AdminStatsMenu {
    static final int PAGE_SIZE = 45, PREVIOUS = 45, BACK = 49, NEXT = 53;
    static final int MINUS_LARGE = 19, MINUS_MEDIUM = 20, MINUS_SMALL = 21;
    static final int PLUS_SMALL = 23, PLUS_MEDIUM = 24, PLUS_LARGE = 25;
    static final int MINIMUM = 28, MAXIMUM = 34, RESET = 37, REVIEW = 40, RESET_ALL = 47;
    static final int RELATED_HEALTH = 31;
    static final int EXACT = 16;
    static final int CONFIRM = 10, CANCEL = 16, PREVIEW_START = 18, PREVIEW_SIZE = 27;
    enum Page { PLAYERS, STATS, EDIT, CONFIRM }
    enum Operation { SET, RESET, RESET_ALL }
    private record Input(Session actor, TargetSession target, String stat, double pending, int expires) {}
    private static final Map<UUID,Input> INPUTS = new ConcurrentHashMap<>();
    record Change(String id, double before, double after) {}
    record Session(ServerPlayerEntity player, ServerPlayNetworkHandler connection, ServerWorld world, GameModes.Mode mode, GameMode vanillaMode) {
        Session(ServerPlayerEntity player) { this(player, player.networkHandler, player.getEntityWorld(), GameModes.current(player), player.getGameMode()); }
        boolean valid() {
            return connection != null && player.networkHandler == connection && connection.player == player && player.getEntityWorld() == world
                && GameModes.current(player) == mode && player.getGameMode() == vanillaMode && player.isAlive() && !player.isRemoved() && !player.isDisconnected()
                && world.getServer().getPlayerManager().getPlayer(player.getUuid()) == player
                && !GameModes.TRANSITIONS.contains(player.getUuid()) && !GameModes.PENDING.containsKey(player.getUuid())
                && !GameModes.OPERATOR_TRANSFERS.containsKey(player.getUuid());
        }
    }
    record TargetSession(LivingEntity entity, ServerWorld world, Session playerSession, AgentCompanions.Agent agent) {
        TargetSession(LivingEntity entity) {
            this(entity, (ServerWorld)entity.getEntityWorld(), entity instanceof ServerPlayerEntity player ? new Session(player) : null,
                entity instanceof ServerPlayerEntity ? null : helperRecord(entity));
        }
        boolean valid() {
            if (entity.getEntityWorld() != world || !entity.isAlive() || entity.isRemoved()) return false;
            return playerSession != null ? playerSession.valid() : agent != null && AdminStats.helper(entity)
                && AgentCompanions.get(world.getServer()).data.agents.get(entity.getUuidAsString()) == agent;
        }
    }

    private static AgentCompanions.Agent helperRecord(LivingEntity entity) {
        return AdminStats.helper(entity) ? AgentCompanions.get(((ServerWorld)entity.getEntityWorld()).getServer())
            .data.agents.get(entity.getUuidAsString()) : null;
    }

    private static String helperOwner(TargetSession target) {
        var owner = target.world().getServer().getPlayerManager().getPlayer(UUID.fromString(target.agent().owner()));
        return owner != null ? owner.getName().getString() : "offline owner " + target.agent().owner().substring(0, 8);
    }

    private static Item targetIcon(LivingEntity target) { return target instanceof ServerPlayerEntity ? Items.PLAYER_HEAD : Items.IRON_GOLEM_SPAWN_EGG; }
    private static String targetKind(LivingEntity target) { return target instanceof ServerPlayerEntity ? "player" : "AI helper"; }

    private AdminStatsMenu() {}

    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->INPUTS.remove(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server->INPUTS.entrySet().removeIf(entry->{
            var input=entry.getValue();
            if(input.actor().world().getServer()!=server)return false;
            if(server.getTicks()<input.expires() && input.actor().valid() && input.target().valid() && allowed(input.actor().player()))return false;
            message(input.actor().player(),"Exact-number entry expired or the player/AI session changed. Reopen the stat editor.");return true;
        }));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->INPUTS.entrySet().removeIf(e->e.getValue().actor().world().getServer()==server));
    }

    /** Consumed before normal chat handling, so an entered value is never broadcast. */
    static boolean consumeChat(ServerPlayerEntity actor,String text) {
        var input=INPUTS.get(actor.getUuid());
        if(input==null)return false;
        var server=actor.getEntityWorld().getServer();
        if(!server.isOnThread()) { server.execute(()->consumeChat(actor,text)); return true; }
        if(input.actor().player()!=actor || !input.actor().valid() || !input.target().valid() || !allowed(actor)
                || server.getTicks()>=input.expires() || AdminStats.accessError(actor.getCommandSource(),input.target().entity())!=null) {
            INPUTS.remove(actor.getUuid(),input);message(actor,"This edit expired or its session changed. Reopen the editor.");return true;
        }
        if(text.trim().equalsIgnoreCase("cancel")) {
            INPUTS.remove(actor.getUuid(),input);message(actor,"Exact-number entry cancelled. Nothing changed.");return true;
        }
        var stat=AdminStats.find(input.target().entity(),input.stat());
        double number;
        try { number=Double.parseDouble(text.trim()); }
        catch(NumberFormatException invalid) { message(actor,"Enter a number such as 5000, or type cancel. Nothing changed.");return true; }
        if(stat==null || !Double.isFinite(number) || number<stat.minimum() || number>stat.maximum() || stat.integer() && number!=Math.rint(number)) {
            message(actor,"Use "+(stat!=null&&stat.integer()?"a whole":"a finite")+" number within this stat's range, or type cancel. Nothing changed.");return true;
        }
        if(actor.currentScreenHandler!=actor.playerScreenHandler || !actor.currentScreenHandler.getCursorStack().isEmpty()) {
            message(actor,"Close your current screen and enter the number again, or type cancel.");return true;
        }
        INPUTS.remove(actor.getUuid(),input);
        show(actor,input.target().entity(),Page.EDIT,0,input.stat(),AdminStats.normalizedValue(stat,number),Operation.SET,List.of());
        return true;
    }

    private static boolean exactMaximum(AdminStats.Stat stat) { return stat.maximum()>=Integer.MAX_VALUE; }

    static boolean allowed(ServerPlayerEntity actor) {
        return new Session(actor).valid() && Memberships.operator(actor);
    }

    static int open(ServerPlayerEntity actor) {
        return show(actor, null, Page.PLAYERS, 0, null, 0, Operation.SET, List.of());
    }

    static int openStats(ServerPlayerEntity actor, LivingEntity target, int page) {
        return show(actor, target, Page.STATS, page, null, 0, Operation.SET, List.of());
    }

    static int openStat(ServerPlayerEntity actor, LivingEntity target, String id) {
        var stat = AdminStats.find(target, id);
        if (stat == null) { message(actor, "That statistic is no longer available."); return 0; }
        return show(actor, target, Page.EDIT, 0, stat.id(), AdminStats.value(target, stat).base(), Operation.SET, List.of());
    }

    static String number(double value) {
        if (!Double.isFinite(value)) return "Invalid value";
        String plain = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.length() <= 22 ? plain : Double.toString(value);
    }

    private static void message(ServerPlayerEntity actor, String text) { actor.sendMessage(Text.literal(text), false); }

    private static void icon(SimpleInventory view, int slot, Item item, String name, String... descriptions) {
        var stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        var lore = new ArrayList<Text>();
        for (String description : descriptions) {
            var line = new StringBuilder();
            for (String word : description.split("\\s+")) {
                if (!line.isEmpty() && line.length() + word.length() + 1 > 43) {
                    lore.add(Text.literal(line.toString())); line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            if (!line.isEmpty()) lore.add(Text.literal(line.toString()));
        }
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        view.setStack(slot, stack);
    }

    private static int show(ServerPlayerEntity actor, LivingEntity target, Page page, int index,
            String statId, double pending, Operation operation, List<Change> changes) {
        if (!allowed(actor)) { message(actor, "Player and AI editing requires an online OP4 admin who has finished respawning or changing mode."); return 0; }
        INPUTS.remove(actor.getUuid());
        if (actor.currentScreenHandler != actor.playerScreenHandler || !actor.currentScreenHandler.getCursorStack().isEmpty()) {
            message(actor, "Close your current screen and empty the cursor before editing players or AI helpers."); return 0;
        }
        if (target != null) {
            String error = AdminStats.accessError(actor.getCommandSource(), target);
            if (error != null) { message(actor, error); return 0; }
        }
        var targets = new ArrayList<>(actor.getEntityWorld().getServer().getPlayerManager().getPlayerList().stream()
            .sorted(Comparator.comparing((ServerPlayerEntity p) -> p != actor)
                .thenComparing(p -> p.getName().getString().toLowerCase(Locale.ROOT)).thenComparing(ServerPlayerEntity::getUuid))
            .map(TargetSession::new).toList());
        AgentCompanions.get(actor.getEntityWorld().getServer()).loaded.values().stream().map(TargetSession::new)
            .filter(TargetSession::valid).sorted(Comparator.comparing((TargetSession t) -> t.agent().name())
                .thenComparing(t -> t.agent().owner()).thenComparing(t -> t.entity().getUuid())).forEach(targets::add);
        List<AdminStats.Stat> stats = target == null ? List.of() : AdminStats.list(target);
        var selected = statId == null ? null : AdminStats.find(target, statId);
        if (page == Page.EDIT && selected == null) { message(actor, "That statistic is no longer available."); return 0; }
        int count = page == Page.PLAYERS ? targets.size() : page == Page.CONFIRM ? changes.size() : stats.size();
        int size = page == Page.CONFIRM ? PREVIEW_SIZE : PAGE_SIZE;
        int pageIndex = Math.max(0, Math.min(index, Math.max(0, (count - 1) / size)));
        var view = new SimpleInventory(54);
        String title = switch (page) {
            case PLAYERS -> "Choose player or AI";
            case STATS -> "Edit " + AdminStats.displayName(target);
            case EDIT -> selected.label();
            case CONFIRM -> "Review • " + AdminStats.displayName(target);
        };
        if (page == Page.PLAYERS) {
            for (int slot = 0, i = pageIndex * size; slot < size && i < count; slot++, i++) {
                var choice = targets.get(i); var entity = choice.entity();
                if (choice.playerSession() != null) icon(view, slot, Items.PLAYER_HEAD,
                    (entity == actor ? "Yourself • " : "Player • ") + entity.getName().getString(),
                    "Health, hunger, XP and all supported player attributes.", "Changes require a review and confirmation.");
                else icon(view, slot, Items.IRON_GOLEM_SPAWN_EGG, "AI • " + choice.agent().name() + " • " + helperOwner(choice),
                    "Owner ID: " + choice.agent().owner(), choice.agent().profile().label() + " • " + choice.agent().mode().name(),
                    "Health, absorption and all supported AI attributes.", "Loaded helpers only; no chunks are loaded by this menu.");
            }
        } else if (page == Page.STATS) {
            for (int slot = 0, i = pageIndex * size; slot < size && i < count; slot++, i++) {
                var stat = stats.get(i); var value = AdminStats.value(target, stat);
                icon(view, slot, stat.icon(), stat.label() + " • " + number(value.base()),
                    "Effective: " + number(value.effective()), exactMaximum(stat) ? "Enter any supported finite value with Exact number." : "Range: " + number(stat.minimum()) + " to " + number(stat.maximum()),
                    AdminStats.boundsHint(stat),
                    stat.id().equals("health") ? "To go above this range, edit Health capacity first. 2 health points = 1 heart."
                        : stat.id().equals("max_health") ? "Raise the health limit here, then fill Current health. 2 health points = 1 heart." : "",
                    value.edited() ? "Edited by an admin; original value can be restored." : "Select to prepare an edit.");
            }
            icon(view, RESET_ALL, Items.MILK_BUCKET, "Restore edited attributes", "Review every original attribute value before restoring it.",
                target instanceof ServerPlayerEntity ? "Health, absorption, hunger and XP are one-time edits and are not reset."
                    : "Health and absorption are one-time edits and are not reset.");
        } else if (page == Page.EDIT) {
            var value = AdminStats.value(target, selected);
            icon(view, 4, targetIcon(target), "Editing " + AdminStats.displayName(target), "Stat: " + selected.id());
            icon(view, 13, selected.icon(), "Current: " + number(value.base()), "Effective: " + number(value.effective()),
                selected.id().equals("health") || selected.id().equals("max_health") ? "2 health points = 1 heart." : "",
                "Equipment and effects can change the effective value.");
            icon(view, 22, Items.PAPER, "Pending: " + number(pending), "Nothing changes until Review, then Confirm.");
            icon(view, EXACT, Items.WRITABLE_BOOK, "Enter exact number", "Type the value privately in chat. No command needed.", "Then review and confirm the edit.");
            int[] minus = {MINUS_SMALL, MINUS_MEDIUM, MINUS_LARGE}, plus = {PLUS_SMALL, PLUS_MEDIUM, PLUS_LARGE};
            for (int i = 0, multiplier = 1; i < 3; i++, multiplier *= 10) {
                String step = number(selected.step() * multiplier);
                icon(view, minus[i], Items.RED_DYE, "Subtract " + step);
                icon(view, plus[i], Items.LIME_DYE, "Add " + step);
            }
            icon(view, MINIMUM, Items.REDSTONE, "Minimum: " + number(selected.minimum()), "Prepare the minimum allowed by Minecraft.");
            icon(view, MAXIMUM, Items.GLOWSTONE_DUST, exactMaximum(selected) ? "Choose your own value" : (selected.id().equals("health") ? "Fill to capacity: " : "Maximum: ") + number(selected.maximum()),
                selected.id().equals("health") ? "Prepare a full heal up to the current health capacity. Review and Confirm still required."
                    : exactMaximum(selected) ? "Enter an exact number instead of jumping to the numeric storage ceiling." : AdminStats.boundsHint(selected));
            if (selected.id().equals("health") && AdminStats.find(target, "max_health") != null)
                icon(view, RELATED_HEALTH, Items.APPLE, "Raise health capacity", "Want more than " + number(selected.maximum()) + " health? Edit the maximum here first.",
                    "Opens Health capacity without changing any values. Pending edits are discarded.");
            else if (selected.id().equals("max_health"))
                icon(view, RELATED_HEALTH, Items.RED_DYE, "Edit current health", "After confirming the capacity, choose Fill to capacity here to heal.",
                    "Opens Current health without changing any values. Pending edits are discarded.");
            else if(selected.id().equals("absorption") && AdminStats.find(target,"max_absorption")!=null)
                icon(view,RELATED_HEALTH,Items.GOLDEN_APPLE,"Raise absorption capacity","Edit the capacity first, then set your absorption hearts. Pending edits are discarded.");
            else if(selected.id().equals("max_absorption"))
                icon(view,RELATED_HEALTH,Items.GOLDEN_APPLE,"Edit absorption hearts","After confirming the capacity, set the current absorption amount. Pending edits are discarded.");
            icon(view, REVIEW, Items.EMERALD, "Review change", selected.id().equals("health") && pending == 0
                ? "WARNING: setting health to zero kills this " + targetKind(target) + "." : "Check the target and exact values before applying.");
            if (selected.attribute()) icon(view, RESET, Items.MILK_BUCKET, "Restore original attribute", value.edited()
                ? "Review the value saved before the first admin edit." : "No saved admin edit to restore for this attribute.");
            else icon(view, 37, Items.BOOK, "One-time value edit", "This value changes immediately after confirmation and has no saved reset value.");
        } else {
            boolean lethal = changes.stream().anyMatch(c -> c.id().equals("health") && c.after() == 0);
            icon(view, 4, targetIcon(target), AdminStats.displayName(target) + " • " + changes.size() + " change(s)",
                "Target ID: " + target.getUuidAsString(), lethal ? "WARNING: health zero kills this " + targetKind(target) + "." : "Apply exactly the changes shown below.");
            icon(view, CONFIRM, lethal ? Items.RED_DYE : Items.LIME_DYE, lethal ? "Confirm • KILL this " + targetKind(target) : "Confirm changes",
                "Target: " + AdminStats.displayName(target), "Any changed value, player session or AI profile cancels this review.");
            icon(view, CANCEL, Items.BARRIER, "Cancel • keep current values");
            for (int slot = PREVIEW_START, i = pageIndex * size; slot < PREVIEW_START + size && i < count; slot++, i++) {
                var change = changes.get(i); var stat = AdminStats.find(target, change.id());
                icon(view, slot, stat == null ? Items.PAPER : stat.icon(), stat == null ? change.id() : stat.label(),
                    "Before: " + number(change.before()), "After: " + number(change.after()));
            }
        }
        if (page != Page.EDIT) {
            if (pageIndex > 0) icon(view, PREVIOUS, Items.ARROW, "Previous page");
            if ((pageIndex + 1) * size < count) icon(view, NEXT, Items.ARROW, "Next page");
        }
        icon(view, BACK, Items.ARROW, page == Page.PLAYERS ? "Back to Infinity Menu" : "Back • discard pending changes");
        actor.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, inventory, who) ->
            new Handler(sync, inventory, view, actor, target, page, pageIndex, statId, pending, operation, changes, targets, stats), Text.literal(title)));
        return 1;
    }

    static final class Handler extends GenericContainerScreenHandler {
        final ServerPlayerEntity owner;
        final Session actorSession;
        final TargetSession targetSession;
        final Page page;
        final int pageIndex;
        final String statId;
        double pending;
        final SimpleInventory view;
        final Operation operation;
        final List<Change> changes;
        final List<TargetSession> targets;
        final List<AdminStats.Stat> stats;

        Handler(int sync, PlayerInventory inventory, SimpleInventory view, ServerPlayerEntity actor, LivingEntity target,
                Page page, int pageIndex, String statId, double pending, Operation operation, List<Change> changes,
                List<TargetSession> targets, List<AdminStats.Stat> stats) {
            super(ScreenHandlerType.GENERIC_9X6, sync, inventory, view, 6);
            owner = actor; actorSession = new Session(actor); targetSession = target == null ? null : new TargetSession(target);
            this.page = page; this.pageIndex = pageIndex; this.statId = statId; this.pending = pending;
            this.view = view;
            this.operation = operation; this.changes = List.copyOf(changes); this.targets = List.copyOf(targets); this.stats = List.copyOf(stats);
        }

        @Override public boolean canUse(PlayerEntity player) {
            return player == owner && owner.currentScreenHandler == this && actorSession.valid() && allowed(owner)
                && (targetSession == null || targetSession.valid() && AdminStats.accessError(owner.getCommandSource(), targetSession.entity()) == null);
        }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot, int selected) {}

        private void show(Page next, int index, String id, double value, Operation op, List<Change> preview) {
            owner.closeHandledScreen();
            AdminStatsMenu.show(owner, targetSession == null ? null : targetSession.entity(), next, index, id, value, op, preview);
        }

        private void fail(String reason) { owner.closeHandledScreen(); message(owner, reason); }

        private void exact() {
            var input=new Input(actorSession,targetSession,statId,pending,owner.getEntityWorld().getServer().getTicks()+1800);
            owner.closeHandledScreen();INPUTS.put(owner.getUuid(),input);
            message(owner,"Enter the exact value for "+AdminStats.find(targetSession.entity(),statId).label()+" in chat (example: 5000), or type cancel. This input is private and expires in 90 seconds. Review and Confirm are still required.");
        }

        private void adjust(double value) {
            pending=value;
            icon(view,22,Items.PAPER,"Pending: "+number(pending),"Nothing changes until Review, then Confirm.");
            icon(view,REVIEW,Items.EMERALD,"Review change",statId.equals("health")&&pending==0
                ? "WARNING: setting health to zero kills this "+targetKind(targetSession.entity())+"." : "Check the target and exact values before applying.");
            sendContentUpdates();
        }

        private List<Change> restorations(boolean all) {
            var result = new ArrayList<Change>();
            for (var stat : AdminStats.list(targetSession.entity())) {
                var value = AdminStats.value(targetSession.entity(), stat);
                if (value.edited() && (all || stat.id().equals(statId)))
                    result.add(new Change(stat.id(), value.base(), AdminStats.original(targetSession.entity(), stat)));
            }
            return List.copyOf(result);
        }

        private void reviewReset(boolean all) {
            var preview = restorations(all);
            if (preview.isEmpty()) { owner.sendMessage(Text.literal("No saved admin edits to restore."), true); return; }
            show(Page.CONFIRM, 0, statId, pending, all ? Operation.RESET_ALL : Operation.RESET, preview);
        }

        private void apply() {
            var target = targetSession.entity();
            for (var change : changes) {
                var stat = AdminStats.find(target, change.id());
                if (stat == null || Double.compare(AdminStats.value(target, stat).base(), change.before()) != 0) {
                    fail("A value changed during review. Open the editor and review the updated values."); return;
                }
                if (operation == Operation.SET && (!Double.isFinite(pending)
                        || pending < stat.minimum() || pending > stat.maximum()
                        || stat.integer() && pending != Math.rint(pending)
                        || Double.compare(AdminStats.normalizedValue(stat, pending), change.after()) != 0)) {
                    fail("The allowed value changed during review. Open the editor and review the updated range."); return;
                }
            }
            if (operation != Operation.SET && !changes.equals(restorations(operation == Operation.RESET_ALL))) {
                fail("The saved originals changed during review. Review the restore again."); return;
            }
            owner.closeHandledScreen();
            var result = switch (operation) {
                case SET -> AdminStats.set(owner.getCommandSource(), target, statId, pending);
                case RESET -> AdminStats.reset(owner.getCommandSource(), target, statId);
                case RESET_ALL -> AdminStats.resetAll(owner.getCommandSource(), target);
            };
            message(owner, result.message());
            if (result.success() && allowed(owner) && targetSession.valid()) openStats(owner, target, 0);
        }

        @Override public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            if (player != owner || owner.currentScreenHandler != this || owner.networkHandler != actorSession.connection()) return;
            if (!canUse(player)) { fail("This player, AI helper or permission changed. Reopen the admin editor."); return; }
            if ((action != SlotActionType.PICKUP && action != SlotActionType.QUICK_MOVE)
                    || button < 0 || button > 1 || !getCursorStack().isEmpty() || slot < 0 || slot >= 54) {
                sendContentUpdates(); return;
            }
            if (slot == BACK) {
                owner.closeHandledScreen();
                if (page == Page.PLAYERS) ServerMenu.open(owner);
                else if (page == Page.STATS) open(owner);
                else openStats(owner, targetSession.entity(), 0);
            } else if (page != Page.EDIT && (slot == PREVIOUS || slot == NEXT) && !getSlot(slot).getStack().isEmpty()) {
                show(page, pageIndex + (slot == NEXT ? 1 : -1), statId, pending, operation, changes);
            } else if (page == Page.PLAYERS && slot < PAGE_SIZE && pageIndex * PAGE_SIZE + slot < targets.size()) {
                var selected = targets.get(pageIndex * PAGE_SIZE + slot);
                if (!selected.valid()) { fail("That player or AI helper changed. Choose the target again."); return; }
                owner.closeHandledScreen(); openStats(owner, selected.entity(), 0);
            } else if (page == Page.STATS) {
                if (slot == RESET_ALL) reviewReset(true);
                else if (slot < PAGE_SIZE && pageIndex * PAGE_SIZE + slot < stats.size()) {
                    owner.closeHandledScreen(); openStat(owner, targetSession.entity(), stats.get(pageIndex * PAGE_SIZE + slot).id());
                }
            } else if (page == Page.EDIT) {
                var stat = AdminStats.find(targetSession.entity(), statId);
                if (stat == null) { fail("That statistic is no longer available."); return; }
                int multiplier = switch (slot) {
                    case MINUS_LARGE -> -100; case MINUS_MEDIUM -> -10; case MINUS_SMALL -> -1;
                    case PLUS_SMALL -> 1; case PLUS_MEDIUM -> 10; case PLUS_LARGE -> 100; default -> 0;
                };
                if(slot==EXACT || slot==MAXIMUM && exactMaximum(stat))exact();
                else if (multiplier != 0 || slot == MINIMUM || slot == MAXIMUM) {
                    double next = slot == MINIMUM ? stat.minimum() : slot == MAXIMUM ? stat.maximum()
                        : Math.max(stat.minimum(), Math.min(stat.maximum(), pending + multiplier * stat.step()));
                    adjust(AdminStats.normalizedValue(stat, next));
                } else if (slot == REVIEW) {
                    var current = AdminStats.value(targetSession.entity(), stat);
                    show(Page.CONFIRM, 0, statId, pending, Operation.SET, List.of(new Change(statId, current.base(), pending)));
                } else if (slot == RESET) reviewReset(false);
                else if (slot == RELATED_HEALTH && List.of("health","max_health","absorption","max_absorption").contains(statId)) {
                    owner.closeHandledScreen();
                    openStat(owner,targetSession.entity(),switch(statId) { case "health"->"max_health";case "max_health"->"health";case "absorption"->"max_absorption";default->"absorption"; });
                }
            } else if (page == Page.CONFIRM) {
                if (slot == CONFIRM) apply();
                else if (slot == CANCEL) { owner.closeHandledScreen(); openStats(owner, targetSession.entity(), 0); }
            }
        }
    }
}

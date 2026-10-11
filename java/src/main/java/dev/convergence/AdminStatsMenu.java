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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;

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
    record Session(ServerPlayer player, ServerGamePacketListenerImpl connection, ServerLevel world, GameModes.Mode mode, GameType vanillaMode) {
        Session(ServerPlayer player) { this(player, player.connection, player.level(), GameModes.current(player), player.gameMode()); }
        boolean valid() {
            return connection != null && player.connection == connection && connection.player == player && player.level() == world
                && GameModes.current(player) == mode && player.gameMode() == vanillaMode && player.isAlive() && !player.isRemoved() && !player.hasDisconnected()
                && world.getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && !GameModes.TRANSITIONS.contains(player.getUUID()) && !GameModes.PENDING.containsKey(player.getUUID())
                && !GameModes.OPERATOR_TRANSFERS.containsKey(player.getUUID());
        }
    }
    record TargetSession(LivingEntity entity, ServerLevel world, Session playerSession, AgentCompanions.Agent agent) {
        TargetSession(LivingEntity entity) {
            this(entity, (ServerLevel)entity.level(), entity instanceof ServerPlayer player ? new Session(player) : null,
                entity instanceof ServerPlayer ? null : helperRecord(entity));
        }
        boolean valid() {
            if (entity.level() != world || !entity.isAlive() || entity.isRemoved()) return false;
            return playerSession != null ? playerSession.valid() : agent != null && AdminStats.helper(entity)
                && AgentCompanions.get(world.getServer()).data.agents.get(entity.getStringUUID()) == agent;
        }
    }

    private static AgentCompanions.Agent helperRecord(LivingEntity entity) {
        return AdminStats.helper(entity) ? AgentCompanions.get(((ServerLevel)entity.level()).getServer())
            .data.agents.get(entity.getStringUUID()) : null;
    }

    private static String helperOwner(TargetSession target) {
        var owner = target.world().getServer().getPlayerList().getPlayer(UUID.fromString(target.agent().owner()));
        return owner != null ? owner.getName().getString() : "offline owner " + target.agent().owner().substring(0, 8);
    }

    private static Item targetIcon(LivingEntity target) { return target instanceof ServerPlayer ? Items.PLAYER_HEAD : Items.IRON_GOLEM_SPAWN_EGG; }
    private static String targetKind(LivingEntity target) { return target instanceof ServerPlayer ? "player" : "AI helper"; }

    private AdminStatsMenu() {}

    static void register() {
        BedrockStatsMenu.register();
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->INPUTS.remove(handler.player.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server->INPUTS.entrySet().removeIf(entry->{
            var input=entry.getValue();
            if(input.actor().world().getServer()!=server)return false;
            if(server.getTickCount()<input.expires() && input.actor().valid() && input.target().valid() && allowed(input.actor().player()))return false;
            message(input.actor().player(),"Exact-number entry expired or the player/AI session changed. Reopen the stat editor.");return true;
        }));
        ServerLifecycleEvents.SERVER_STOPPED.register(server->INPUTS.entrySet().removeIf(e->e.getValue().actor().world().getServer()==server));
    }

    /** Consumed before normal chat handling, so an entered value is never broadcast. */
    static boolean consumeChat(ServerPlayer actor,String text) {
        var input=INPUTS.get(actor.getUUID());
        if(input==null)return false;
        var server=actor.level().getServer();
        if(!server.isSameThread()) { server.execute(()->consumeChat(actor,text)); return true; }
        if(input.actor().player()!=actor || !input.actor().valid() || !input.target().valid() || !allowed(actor)
                || server.getTickCount()>=input.expires() || AdminStats.accessError(actor.createCommandSourceStack(),input.target().entity())!=null) {
            INPUTS.remove(actor.getUUID(),input);message(actor,"This edit expired or its session changed. Reopen the editor.");return true;
        }
        if(text.trim().equalsIgnoreCase("cancel")) {
            INPUTS.remove(actor.getUUID(),input);message(actor,"Exact-number entry cancelled. Nothing changed.");return true;
        }
        var stat=AdminStats.find(input.target().entity(),input.stat());
        double number;
        try { number=Double.parseDouble(text.trim()); }
        catch(NumberFormatException invalid) { message(actor,"Enter a number such as 5000, or type cancel. Nothing changed.");return true; }
        if(stat==null || !Double.isFinite(number) || number<stat.minimum() || number>AdminStats.inputMaximum(stat) || stat.integer() && number!=Math.rint(number)) {
            message(actor,"Use "+(stat!=null&&stat.integer()?"a whole":"a finite")+" number within this stat's range, or type cancel. Nothing changed.");return true;
        }
        if(actor.containerMenu!=actor.inventoryMenu || !actor.containerMenu.getCarried().isEmpty()) {
            message(actor,"Close your current screen and enter the number again, or type cancel.");return true;
        }
        INPUTS.remove(actor.getUUID(),input);
        show(actor,input.target().entity(),Page.EDIT,0,input.stat(),AdminStats.normalizedValue(stat,number),Operation.SET,List.of());
        return true;
    }

    private static boolean exactMaximum(AdminStats.Stat stat) { return stat.maximum()>=Integer.MAX_VALUE; }

    static boolean allowed(ServerPlayer actor) {
        return new Session(actor).valid() && Memberships.operator(actor);
    }

    static int open(ServerPlayer actor) {
        return show(actor, null, Page.PLAYERS, 0, null, 0, Operation.SET, List.of());
    }

    static int openStats(ServerPlayer actor, LivingEntity target, int page) {
        return show(actor, target, Page.STATS, page, null, 0, Operation.SET, List.of());
    }

    static int openStat(ServerPlayer actor, LivingEntity target, String id) {
        var stat = AdminStats.find(target, id);
        if (stat == null) { message(actor, "That statistic is no longer available."); return 0; }
        return show(actor, target, Page.EDIT, 0, stat.id(), AdminStats.value(target, stat).base(), Operation.SET, List.of());
    }

    static String number(double value) {
        if (!Double.isFinite(value)) return "Invalid value";
        String plain = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.length() <= 22 ? plain : Double.toString(value);
    }

    private static void message(ServerPlayer actor, String text) { actor.sendSystemMessage(Component.literal(text)); }

    private static void icon(SimpleContainer view, int slot, Item item, String name, String... descriptions) {
        var stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        var lore = new ArrayList<Component>();
        for (String description : descriptions) {
            var line = new StringBuilder();
            for (String word : description.split("\\s+")) {
                if (!line.isEmpty() && line.length() + word.length() + 1 > 43) {
                    lore.add(Component.literal(line.toString())); line.setLength(0);
                }
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
            if (!line.isEmpty()) lore.add(Component.literal(line.toString()));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        view.setItem(slot, stack);
    }

    private static int show(ServerPlayer actor, LivingEntity target, Page page, int index,
            String statId, double pending, Operation operation, List<Change> changes) {
        if (!allowed(actor)) { message(actor, "Player and AI editing requires an online OP4 admin who has finished respawning or changing mode."); return 0; }
        INPUTS.remove(actor.getUUID());
        if (actor.containerMenu != actor.inventoryMenu || !actor.containerMenu.getCarried().isEmpty()) {
            message(actor, "Close your current screen and empty the cursor before editing players or AI helpers."); return 0;
        }
        if (target != null) {
            String error = AdminStats.accessError(actor.createCommandSourceStack(), target);
            if (error != null) { message(actor, error); return 0; }
        }
        if (CrossplaySupport.bedrock(actor)) return BedrockStatsMenu.open(actor, target, statId);
        var targets = new ArrayList<>(actor.level().getServer().getPlayerList().getPlayers().stream()
            .sorted(Comparator.comparing((ServerPlayer p) -> p != actor)
                .thenComparing(p -> p.getName().getString().toLowerCase(Locale.ROOT)).thenComparing(ServerPlayer::getUUID))
            .map(TargetSession::new).toList());
        AgentCompanions.get(actor.level().getServer()).loaded.values().stream().map(TargetSession::new)
            .filter(TargetSession::valid).sorted(Comparator.comparing((TargetSession t) -> t.agent().name())
                .thenComparing(t -> t.agent().owner()).thenComparing(t -> t.entity().getUUID())).forEach(targets::add);
        List<AdminStats.Stat> stats = target == null ? List.of() : AdminStats.list(target);
        var selected = statId == null ? null : AdminStats.find(target, statId);
        if (page == Page.EDIT && selected == null) { message(actor, "That statistic is no longer available."); return 0; }
        int count = page == Page.PLAYERS ? targets.size() : page == Page.CONFIRM ? changes.size() : stats.size();
        int size = page == Page.CONFIRM ? PREVIEW_SIZE : PAGE_SIZE;
        int pageIndex = Math.max(0, Math.min(index, Math.max(0, (count - 1) / size)));
        var view = new SimpleContainer(54);
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
                    stat.id().equals("health") ? "Enter your health directly; any needed capacity increase joins the review."
                        : stat.id().equals("max_health") ? "Raise the health limit here, then fill Current health. 2 health points = 1 heart." : "",
                    value.edited() ? "Edited by an admin; original value can be restored." : "Select to prepare an edit.");
            }
            icon(view, RESET_ALL, Items.MILK_BUCKET, "Restore edited attributes", "Review every original attribute value before restoring it.",
                target instanceof ServerPlayer ? "Health, absorption, hunger and XP are one-time edits and are not reset."
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
                icon(view, minus[i], Items.DYE.red(), "Subtract " + step);
                icon(view, plus[i], Items.DYE.lime(), "Add " + step);
            }
            icon(view, MINIMUM, Items.REDSTONE, "Minimum: " + number(selected.minimum()), "Prepare the minimum allowed by Minecraft.");
            icon(view, MAXIMUM, Items.GLOWSTONE_DUST, exactMaximum(selected) ? "Choose your own value" : (selected.id().equals("health") ? "Fill to capacity: " : "Maximum: ") + number(selected.maximum()),
                selected.id().equals("health") ? "Prepare a full heal up to the current health capacity. Review and Confirm still required."
                    : exactMaximum(selected) ? "Enter an exact number instead of jumping to the numeric storage ceiling." : AdminStats.boundsHint(selected));
            if (selected.id().equals("health") && AdminStats.find(target, "max_health") != null)
                icon(view, RELATED_HEALTH, Items.APPLE, "Edit health capacity separately", "Optional: exact health entry raises capacity automatically when needed.",
                    "Opens Health capacity without changing any values. Pending edits are discarded.");
            else if (selected.id().equals("max_health"))
                icon(view, RELATED_HEALTH, Items.DYE.red(), "Edit current health", "After confirming the capacity, choose Fill to capacity here to heal.",
                    "Opens Current health without changing any values. Pending edits are discarded.");
            else if(selected.id().equals("absorption") && AdminStats.find(target,"max_absorption")!=null)
                icon(view,RELATED_HEALTH,Items.GOLDEN_APPLE,"Edit absorption capacity separately","Optional: exact absorption entry raises capacity automatically when needed.");
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
                "Target ID: " + target.getStringUUID(), lethal ? "WARNING: health zero kills this " + targetKind(target) + "." : "Apply exactly the changes shown below.");
            icon(view, CONFIRM, lethal ? Items.DYE.red() : Items.DYE.lime(), lethal ? "Confirm • KILL this " + targetKind(target) : "Confirm changes",
                "Target: " + AdminStats.displayName(target), operation == Operation.SET && changes.stream().allMatch(c -> !AdminStats.find(target,c.id()).attribute())
                    ? "Sets the exact reviewed amount, even if gameplay changes the current value."
                    : "Changed attribute bases cancel this review.", "Changed permissions, sessions or allowed ranges always cancel.");
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
        actor.openMenu(new SimpleMenuProvider((sync, inventory, who) ->
            new Handler(sync, inventory, view, actor, target, page, pageIndex, statId, pending, operation, changes, targets, stats), Component.literal(title)));
        return 1;
    }

    /** Vitals are an absolute assignment: regeneration must not cancel the approved heal. */
    static String reviewError(LivingEntity target, Operation operation, double pending, List<Change> changes) {
        if(changes.isEmpty())return "No reviewed changes are available. Reopen the editor.";
        if(operation==Operation.SET) {
            List<Change> expected;
            try {expected=plannedChanges(target,changes.getLast().id(),pending);}
            catch(IllegalArgumentException invalid){return invalid.getMessage();}
            if(expected.size()!=changes.size())return "The capacity changed during review. Review the updated changes.";
            for(int i=0;i<expected.size();i++)
                if(!expected.get(i).id().equals(changes.get(i).id()) || Double.compare(expected.get(i).after(),changes.get(i).after())!=0)
                    return "The capacity or allowed range changed during review. Review the updated changes.";
        }
        for (var change : changes) {
            var stat=AdminStats.find(target,change.id());
            if(stat==null || (operation!=Operation.SET || stat.attribute())
                    && Double.compare(AdminStats.value(target,stat).base(),change.before())!=0)
                return "The attribute changed during review. Reopen its editor and review the updated value.";
            if(operation==Operation.SET && (!Double.isFinite(change.after()) || change.after()<stat.minimum() || change.after()>AdminStats.inputMaximum(stat)
                    || stat.integer() && change.after()!=Math.rint(change.after())
                    || Double.compare(AdminStats.normalizedValue(stat,change.after()),change.after())!=0))
                return "The allowed range changed during review. Reopen the editor and review the updated range.";
        }
        return null;
    }

    static List<Change> plannedChanges(LivingEntity target,String id,double amount) {
        return AdminStats.planSet(target,id,amount).stream().map(c->new Change(c.id(),c.before(),c.after())).toList();
    }

    static final class Handler extends ChestMenu {
        final ServerPlayer owner;
        final Session actorSession;
        final TargetSession targetSession;
        final Page page;
        final int pageIndex;
        final String statId;
        double pending;
        final SimpleContainer view;
        final Operation operation;
        final List<Change> changes;
        final List<TargetSession> targets;
        final List<AdminStats.Stat> stats;

        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer actor, LivingEntity target,
                Page page, int pageIndex, String statId, double pending, Operation operation, List<Change> changes,
                List<TargetSession> targets, List<AdminStats.Stat> stats) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            owner = actor; actorSession = new Session(actor); targetSession = target == null ? null : new TargetSession(target);
            this.page = page; this.pageIndex = pageIndex; this.statId = statId; this.pending = pending;
            this.view = view;
            this.operation = operation; this.changes = List.copyOf(changes); this.targets = List.copyOf(targets); this.stats = List.copyOf(stats);
        }

        @Override public boolean stillValid(Player player) {
            return player == owner && owner.containerMenu == this && actorSession.valid() && allowed(owner)
                && (targetSession == null || targetSession.valid() && AdminStats.accessError(owner.createCommandSourceStack(), targetSession.entity()) == null);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) {}

        private void show(Page next, int index, String id, double value, Operation op, List<Change> preview) {
            owner.closeContainer();
            AdminStatsMenu.show(owner, targetSession == null ? null : targetSession.entity(), next, index, id, value, op, preview);
        }

        private void fail(String reason) { owner.closeContainer(); message(owner, reason); }

        private void exact() {
            var input=new Input(actorSession,targetSession,statId,pending,owner.level().getServer().getTickCount()+1800);
            owner.closeContainer();INPUTS.put(owner.getUUID(),input);
            message(owner,"Enter the exact value for "+AdminStats.find(targetSession.entity(),statId).label()+" in chat (example: 5000), or type cancel. This input is private and expires in 90 seconds. Review and Confirm are still required.");
        }

        private void adjust(double value) {
            pending=value;
            icon(view,22,Items.PAPER,"Pending: "+number(pending),"Nothing changes until Review, then Confirm.");
            icon(view,REVIEW,Items.EMERALD,"Review change",statId.equals("health")&&pending==0
                ? "WARNING: setting health to zero kills this "+targetKind(targetSession.entity())+"." : "Check the target and exact values before applying.");
            broadcastChanges();
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
            if (preview.isEmpty()) { owner.sendOverlayMessage(Component.literal("No saved admin edits to restore.")); return; }
            show(Page.CONFIRM, 0, statId, pending, all ? Operation.RESET_ALL : Operation.RESET, preview);
        }

        private void apply() {
            var target = targetSession.entity();
            String error=reviewError(target,operation,pending,changes);
            if(error!=null) { fail(error);return; }
            if (operation != Operation.SET && !changes.equals(restorations(operation == Operation.RESET_ALL))) {
                fail("The saved originals changed during review. Review the restore again."); return;
            }
            owner.closeContainer();
            var result = switch (operation) {
                case SET -> AdminStats.set(owner.createCommandSourceStack(), target, statId, pending);
                case RESET -> AdminStats.reset(owner.createCommandSourceStack(), target, statId);
                case RESET_ALL -> AdminStats.resetAll(owner.createCommandSourceStack(), target);
            };
            message(owner, result.message());
            if (result.success() && allowed(owner) && targetSession.valid()) openStats(owner, target, 0);
        }

        @Override public void clicked(int slot, int button, ContainerInput action, Player player) {
            if (player != owner || owner.containerMenu != this || owner.connection != actorSession.connection()) return;
            if (!stillValid(player)) { fail("This player, AI helper or permission changed. Reopen the admin editor."); return; }
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)
                    || button < 0 || button > 1 || !getCarried().isEmpty() || slot < 0 || slot >= 54) {
                broadcastChanges(); return;
            }
            if (slot == BACK) {
                owner.closeContainer();
                if (page == Page.PLAYERS) ServerMenu.open(owner);
                else if (page == Page.STATS) open(owner);
                else openStats(owner, targetSession.entity(), 0);
            } else if (page != Page.EDIT && (slot == PREVIOUS || slot == NEXT) && !getSlot(slot).getItem().isEmpty()) {
                show(page, pageIndex + (slot == NEXT ? 1 : -1), statId, pending, operation, changes);
            } else if (page == Page.PLAYERS && slot < PAGE_SIZE && pageIndex * PAGE_SIZE + slot < targets.size()) {
                var selected = targets.get(pageIndex * PAGE_SIZE + slot);
                if (!selected.valid()) { fail("That player or AI helper changed. Choose the target again."); return; }
                owner.closeContainer(); openStats(owner, selected.entity(), 0);
            } else if (page == Page.STATS) {
                if (slot == RESET_ALL) reviewReset(true);
                else if (slot < PAGE_SIZE && pageIndex * PAGE_SIZE + slot < stats.size()) {
                    owner.closeContainer(); openStat(owner, targetSession.entity(), stats.get(pageIndex * PAGE_SIZE + slot).id());
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
                        : Math.max(stat.minimum(), Math.min(AdminStats.inputMaximum(stat), pending + multiplier * stat.step()));
                    adjust(AdminStats.normalizedValue(stat, next));
                } else if (slot == REVIEW) {
                    try {show(Page.CONFIRM, 0, statId, pending, Operation.SET, plannedChanges(targetSession.entity(),statId,pending));}
                    catch(IllegalArgumentException invalid){fail(invalid.getMessage());}
                } else if (slot == RESET) reviewReset(false);
                else if (slot == RELATED_HEALTH && List.of("health","max_health","absorption","max_absorption").contains(statId)) {
                    owner.closeContainer();
                    openStat(owner,targetSession.entity(),switch(statId) { case "health"->"max_health";case "max_health"->"health";case "absorption"->"max_absorption";default->"absorption"; });
                }
            } else if (page == Page.CONFIRM) {
                if (slot == CONFIRM) apply();
                else if (slot == CANCEL) { owner.closeContainer(); openStats(owner, targetSession.entity(), 0); }
            }
        }
    }
}

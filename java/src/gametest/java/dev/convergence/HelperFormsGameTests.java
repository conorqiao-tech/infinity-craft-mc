package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;

/** Decode native touch replies and exercise delayed/session-bound delivery without calling an AI service. */
public class HelperFormsGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var p = new ModeGameTests().player(c, name);
        p.closeContainer(); p.setGameMode(GameType.SURVIVAL);
        permission(p, LevelBasedPermissionSet.OWNER);
        return p;
    }
    private void permission(ServerPlayer p, LevelBasedPermissionSet level) {
        var list = p.level().getServer().getPlayerList();
        list.deop(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
        list.op(new net.minecraft.server.players.NameAndId(p.getGameProfile()), java.util.Optional.of(level), java.util.Optional.of(false));
    }
    private void cleanup(ServerPlayer p) {
        HelperForms.ACTIVE.remove(p.getUUID()); HelperForms.DELIVERIES.remove(p.getUUID());
        p.containerMenu.setCarried(ItemStack.EMPTY); p.closeContainer();
        p.level().getServer().getPlayerList().deop(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
        p.level().getServer().getPlayerList().remove(p);
    }
    private void reply(Form form, String value) {
        try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private int show(ServerPlayer p, AtomicInteger changes, Predicate<Form> sender) {
        return HelperForms.show(p, "Helper test", "One explicit action", List.of(new HelperForms.Choice("Apply", changes::incrementAndGet)), () -> true, sender);
    }

    @GameTest public void nativeHelperResponseIsSingleUseAndSharedAcrossReplacementPages(GameTestHelper c) {
        var p = player(c, "helper-form-once");
        try {
            var forms = new ArrayList<Form>(); var changes = new AtomicInteger();
            Predicate<Form> sender = f -> { forms.add(f); return true; };
            c.assertValueEqual(show(p, changes, sender), 1, "A valid OP4 screen opens"); var old = forms.getLast();
            show(p, changes, sender); var current = forms.getLast();
            reply(old, "0"); c.assertValueEqual(changes.get(), 0, "Superseded helper or order page cannot act");
            reply(current, "0"); c.assertValueEqual(changes.get(), 1, "Actual encoded current touch reply runs once");
            reply(current, "0"); c.assertValueEqual(changes.get(), 1, "Replayed response cannot run twice");
            show(p, changes, sender); reply(forms.getLast(), "null");
            c.assertValueEqual(changes.get(), 1, "Closing the native screen does not run its first button");
            c.assertFalse(HelperForms.ACTIVE.containsKey(p.getUUID()), "Close releases the exact ticket");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void nativeHelperRepliesRecheckPermissionModeCursorAndExactConnection(GameTestHelper c) {
        var p = player(c, "helper-form-gates"); var other = player(c, "helper-form-other");
        try {
            var forms = new ArrayList<Form>(); var changes = new AtomicInteger();
            Predicate<Form> sender = f -> { forms.add(f); return true; };
            show(p, changes, sender); permission(p, LevelBasedPermissionSet.ADMIN); reply(forms.getLast(), "0");
            c.assertValueEqual(changes.get(), 0, "Losing OP4 blocks the open screen");
            c.assertValueEqual(show(p, changes, sender), 0, "OP3 cannot open a replacement screen");
            permission(p, LevelBasedPermissionSet.OWNER); show(p, changes, sender);
            p.setGameMode(GameType.CREATIVE); reply(forms.getLast(), "0");
            c.assertValueEqual(changes.get(), 0, "Changing vanilla game mode invalidates the old reply");
            show(p, changes, sender); p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 3)); reply(forms.getLast(), "0");
            c.assertValueEqual(changes.get(), 0, "Occupied cursor cannot activate a helper action");
            c.assertValueEqual(p.inventoryMenu.getCarried().getCount(), 3, "Refusal never consumes cursor items");
            p.inventoryMenu.setCarried(ItemStack.EMPTY); show(p, changes, sender);
            var connection = p.connection;
            try { p.connection = other.connection; reply(forms.getLast(), "0"); } finally { p.connection = connection; }
            c.assertValueEqual(changes.get(), 0, "A different exact connection cannot reuse the old review");
            show(p, changes, sender); ServerMenu.open(p); reply(forms.getLast(), "0");
            c.assertValueEqual(changes.get(), 0, "A native reply cannot act through an open inventory screen");
        } finally { cleanup(p); cleanup(other); }
        c.succeed();
    }

    @GameTest public void nativeHelperContextExpiryAndSendFailuresLeaveNoAction(GameTestHelper c) {
        var p = player(c, "helper-form-expiry");
        try {
            var forms = new ArrayList<Form>(); var changes = new AtomicInteger(); var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
            Predicate<Form> sender = f -> { forms.add(f); return true; };
            HelperForms.show(p, "Context", "Exact helper", List.of(new HelperForms.Choice("Act", changes::incrementAndGet)), valid::get, sender);
            valid.set(false); reply(forms.getLast(), "0");
            c.assertValueEqual(changes.get(), 0, "Changed helper/proposal context refuses the exact displayed action");
            show(p, changes, sender); var before = HelperForms.ACTIVE.get(p.getUUID());
            var expired = new HelperForms.Ticket(before.nonce(), before.actor(), before.context(), p.level().getServer().getTickCount(), before.sender());
            HelperForms.ACTIVE.put(p.getUUID(), expired); HelperForms.response(expired, changes::incrementAndGet);
            c.assertValueEqual(changes.get(), 0, "Expiry does not depend on periodic cleanup having run");
            c.assertValueEqual(show(p, changes, f -> false), 0, "Send failure is reported synchronously");
            c.assertFalse(HelperForms.ACTIVE.containsKey(p.getUUID()), "Failed send retires its ticket");
            c.assertFalse(HelperForms.DELIVERIES.containsKey(p.getUUID()), "Failed send leaves no delayed delivery");
            show(p, changes, sender); reply(forms.getLast(), "999");
            c.assertValueEqual(changes.get(), 0, "Malformed/out-of-range touch response cannot act");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void delayedNativeHelperDeliveryRechecksTheSessionBeforeSending(GameTestHelper c) {
        var p = player(c, "helper-form-delay");
        try {
            var changes = new AtomicInteger();
            c.assertValueEqual(show(p, changes, HelperForms.sender(p)), 1, "Production sender queues the screen");
            var delivery = HelperForms.DELIVERIES.get(p.getUUID());
            c.assertTrue(delivery != null && delivery.due() == p.level().getServer().getTickCount() + 3, "Chest-to-form transfer waits three real ticks");
            permission(p, LevelBasedPermissionSet.ADMIN);
            HelperForms.DELIVERIES.put(p.getUUID(), new HelperForms.Delivery(delivery.ticket(), delivery.form(), p.level().getServer().getTickCount()));
            HelperForms.tick(p.level().getServer());
            c.assertFalse(HelperForms.ACTIVE.containsKey(p.getUUID()), "Revoked delivery retires its ticket without sending");
            c.assertFalse(HelperForms.DELIVERIES.containsKey(p.getUUID()), "Revoked delivery is not retried forever");
            c.assertValueEqual(changes.get(), 0, "Queued delivery never runs a gameplay action");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void closingStaleContextDoesNotReopenAndThrownDeliveryRetiresOnlyItsTicket(GameTestHelper c) {
        var p = player(c, "helper-form-close");
        try {
            var forms = new ArrayList<Form>(); var changes = new AtomicInteger();
            var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
            Predicate<Form> sender = f -> { forms.add(f); return true; };
            HelperForms.show(p, "Context", "Exact helper", List.of(new HelperForms.Choice("Act", changes::incrementAndGet)), valid::get, sender);
            valid.set(false); int before = forms.size(); reply(forms.getLast(), "null");
            c.assertValueEqual(forms.size(), before, "Closing a changed screen must not open a recovery screen over the world");
            c.assertFalse(HelperForms.ACTIVE.containsKey(p.getUUID()), "Cancellation retires the exact stale ticket");
            c.assertValueEqual(show(p, changes, f -> { throw new IllegalStateException("sender fixture"); }), 0,
                "A throwing initial sender is a visible send failure rather than a server exception");
            c.assertFalse(HelperForms.ACTIVE.containsKey(p.getUUID()), "Throwing sender leaves no active ticket");
            show(p, changes, sender); var old = HelperForms.ACTIVE.get(p.getUUID()); var oldForm = forms.getLast();
            show(p, changes, sender); var current = HelperForms.ACTIVE.get(p.getUUID());
            c.assertFalse(HelperForms.send(old, oldForm, f -> { throw new IllegalStateException("delayed fixture"); }),
                "The same guarded send handles delayed Floodgate failures");
            c.assertTrue(HelperForms.ACTIVE.get(p.getUUID()) == current, "Failure of an old delivery cannot retire the replacement ticket");
            reply(forms.getLast(), "0");
            c.assertValueEqual(changes.get(), 1, "A replacement screen remains usable after the older delivery failed");
        } finally { cleanup(p); }
        c.succeed();
    }

    @GameTest public void networkThreadReplyRunsOnlyOnTheServerThread(GameTestHelper c) {
        var p = player(c, "helper-form-thread"); var forms = new ArrayList<Form>();
        var changes = new AtomicInteger(); var onServer = new java.util.concurrent.atomic.AtomicBoolean();
        HelperForms.show(p, "Thread", "One action", List.of(new HelperForms.Choice("Act", () -> {
            onServer.set(p.level().getServer().isSameThread()); changes.incrementAndGet();
        })), () -> true, f -> { forms.add(f); return true; });
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { reply(forms.getLast(), "0"); } catch (Throwable e) { failure.set(e); } }, "helper-form-test-response");
        thread.setDaemon(true); thread.start();
        try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        c.assertFalse(thread.isAlive(), "Response callback enqueues without blocking on the server thread");
        c.assertValueEqual(changes.get(), 0, "Network callback cannot mutate gameplay inline");
        c.runAfterDelay(2, () -> {
            try {
                c.assertTrue(failure.get() == null, "Encoded network response decoded without error");
                c.assertValueEqual(changes.get(), 1, "Scheduled action runs once");
                c.assertTrue(onServer.get(), "Gameplay action runs on the actual server thread");
                c.succeed();
            } finally { cleanup(p); }
        });
    }
}

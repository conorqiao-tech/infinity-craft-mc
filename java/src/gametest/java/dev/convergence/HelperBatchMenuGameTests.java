package dev.convergence;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Real Java clicks and encoded native touch replies exercise one-use batch creation. */
public class HelperBatchMenuGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), NativeSessionFixture.nativeProfileName(name));
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        operator(player, LevelBasedPermissionSet.OWNER);
        var feet = c.absolutePos(new BlockPos(3, 20, 3));
        for (var at : BlockPos.betweenClosed(feet.offset(-18, -1, -18), feet.offset(18, 4, 18)))
            c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        player.setPos(Vec3.atBottomCenterOf(feet)); player.setNoGravity(true);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.closeContainer();
        return player;
    }
    private void operator(ServerPlayer player, LevelBasedPermissionSet level) {
        var list = player.level().getServer().getPlayerList();
        list.deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        list.op(new net.minecraft.server.players.NameAndId(player.getGameProfile()), java.util.Optional.of(level), java.util.Optional.of(false));
    }
    private void cleanup(ServerPlayer player) {
        HelperForms.ACTIVE.remove(player.getUUID()); HelperForms.DELIVERIES.remove(player.getUUID());
        player.containerMenu.setCarried(ItemStack.EMPTY); player.inventoryMenu.setCarried(ItemStack.EMPTY); player.closeContainer();
        operator(player, LevelBasedPermissionSet.OWNER);
        var helpers = AgentCompanions.get(player.level().getServer());
        for (var name : helpers.data.agents.values().stream().filter(a -> a.owner().equals(player.getStringUUID())).map(AgentCompanions.Agent::name).toList())
            helpers.dismiss(player, name);
        helpers.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        helpers.server.getPlayerList().remove(player);
    }
    private static void click(ServerPlayer owner, int slot) { owner.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, owner); }
    private static HelperBatchMenu.Handler batch(ServerPlayer owner) { return (HelperBatchMenu.Handler) owner.containerMenu; }
    private static void reply(Form form, String json) {
        try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form, json); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static void submit(Form form, String amount, int profile) {
        reply(form, new com.google.gson.Gson().toJson(new Object[]{null, amount, profile}));
    }
    private static final class Capture implements Predicate<Form> {
        final ArrayList<Form> forms = new ArrayList<>();
        @Override public boolean test(Form form) {
            var json = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            var encoded = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            String type = form instanceof CustomForm ? "custom_form" : "form";
            if (!encoded.get("type").getAsString().equals(type) || !encoded.get("title").getAsString().equals(form.title()))
                throw new IllegalStateException("Native helper batch form did not encode");
            forms.add(form); return true;
        }
        Form last() { return forms.getLast(); }
        boolean has(String prefix) { return ((SimpleForm) last()).buttons().stream().anyMatch(b -> b.text().startsWith(prefix)); }
        void click(String prefix) {
            var form = (SimpleForm)last();
            for (int i = 0; i < form.buttons().size(); i++) if (form.buttons().get(i).text().startsWith(prefix)) {
                reply(form, Integer.toString(i)); return;
            }
            throw new IllegalStateException("Missing batch button " + prefix);
        }
    }

    @GameTest public void javaOneActivationSpawnsFiftySelectedProfilesAndOldPacketsCannotRepeat(GameTestHelper c) {
        var owner = player(c, "batch-java-fifty"); var helpers = AgentCompanions.get(owner.level().getServer());
        ServerPlayer other = null;
        int proposals = AgentActions.get(helpers.server).data.proposals.size();
        try {
            AgentMenu.open(owner); click(owner, AgentMenu.BATCH_CREATE);
            c.assertTrue(owner.containerMenu instanceof HelperBatchMenu.Handler, "Roster has a direct batch route");
            c.assertValueEqual(helpers.count(owner), 0, "Opening the batch screen creates nothing");
            click(owner, HelperBatchMenu.PRESET_MAX);
            click(owner, HelperBatchMenu.PROFILE_FIRST + AgentCompanions.Profile.ULTIMATE_FINALS.ordinal());
            c.assertValueEqual(batch(owner).amount, 50, "Maximum is the full current capacity");
            var old = batch(owner); click(owner, HelperBatchMenu.SPAWN);
            c.assertValueEqual(helpers.count(owner), 50, "One activation creates all fifty native helpers");
            c.assertTrue(helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID()))
                .allMatch(a -> a.profile() == AgentCompanions.Profile.ULTIMATE_FINALS), "Every new record has the explicitly selected profile");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Successful creation reveals the world");
            old.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 50, "An old window packet cannot create another batch");
            AgentMenu.open(owner); var fullRoster = (AgentMenu.Handler)owner.containerMenu;
            c.assertTrue(fullRoster.view.getItem(AgentMenu.BATCH_CREATE).is(Items.BARRIER)
                && !fullRoster.view.getItem(AgentMenu.BATCH_CREATE).getHoverName().getString().startsWith("Create"),
                "The full Java roster shows a capacity barrier rather than advertising batch creation");
            click(owner, AgentMenu.BATCH_CREATE);
            c.assertTrue(owner.containerMenu == fullRoster && helpers.count(owner) == 50,
                "A disabled batch click keeps the current Java roster open and creates nothing");
            owner.closeContainer();
            var forms = new Capture(); BedrockAgentMenu.open(owner, forms);
            c.assertFalse(forms.has("Create"), "A full native roster offers no creation action");
            BedrockAgentMenu.openBatch(owner, forms);
            c.assertTrue(forms.last() instanceof SimpleForm && !forms.has("Create") && helpers.count(owner) == 50,
                "Directly reopening a full batch route returns to the roster without creation actions");
            other = player(c, "batch-global-full");
            c.assertValueEqual(helpers.remaining(other), 0, "Another owner's fifty helpers consume the global capacity");
            AgentMenu.open(other); var blockedRoster = (AgentMenu.Handler)other.containerMenu;
            c.assertTrue(blockedRoster.view.getItem(AgentMenu.BATCH_CREATE).is(Items.BARRIER)
                && !blockedRoster.view.getItem(AgentMenu.helperSlot(0)).getHoverName().getString().startsWith("Create"),
                "Global capacity also disables Java batch and single-helper creation tiles");
            for (int slot = 0; slot < blockedRoster.view.getContainerSize(); slot++)
                c.assertFalse(!blockedRoster.view.getItem(slot).isEmpty()
                    && blockedRoster.view.getItem(slot).getHoverName().getString().startsWith("Create"),
                    "A globally full Java roster never advertises a creation route");
            click(other, AgentMenu.BATCH_CREATE); click(other, AgentMenu.helperSlot(0));
            c.assertTrue(other.containerMenu == blockedRoster && helpers.count(other) == 0,
                "Global-capacity barriers keep the roster open without attempting single or batch creation");
            helpers.dismiss(owner, "helper-50");
            click(other, AgentMenu.helperSlot(0));
            c.assertTrue(other.containerMenu instanceof AgentMenu.Handler && helpers.count(other) == 0
                && ((AgentMenu.Handler)other.containerMenu).view.getItem(AgentMenu.helperSlot(0)).getHoverName().getString().startsWith("Create"),
                "A displayed single-helper barrier only reveals newly free capacity on its first tap");
            var positiveRoster = other.containerMenu;
            blockedRoster.clicked(AgentMenu.helperSlot(0), 0, ContainerInput.PICKUP, other);
            c.assertTrue(other.containerMenu == positiveRoster && helpers.count(other) == 0,
                "Duplicate packets from the old barrier window cannot create after its refresh");
            c.assertValueEqual(helpers.spawnBatch(owner, 1, AgentCompanions.Profile.REGULAR), 1,
                "An explicit owner action fills the released global slot again");
            click(other, AgentMenu.BATCH_CREATE); var batchBarrier = (AgentMenu.Handler)other.containerMenu;
            c.assertTrue(batchBarrier.view.getItem(AgentMenu.BATCH_CREATE).is(Items.BARRIER),
                "A newly full capacity refreshes a previously enabled control into a barrier");
            helpers.dismiss(owner, "helper-50");
            click(other, AgentMenu.BATCH_CREATE);
            c.assertTrue(other.containerMenu instanceof AgentMenu.Handler && helpers.count(other) == 0
                && ((AgentMenu.Handler)other.containerMenu).view.getItem(AgentMenu.BATCH_CREATE).getHoverName().getString().startsWith("Create"),
                "A displayed batch barrier only reveals capacity and does not open quantity controls");
            batchBarrier.clicked(AgentMenu.BATCH_CREATE, 0, ContainerInput.PICKUP, other);
            c.assertTrue(other.containerMenu instanceof AgentMenu.Handler && helpers.count(other) == 0,
                "Duplicate old barrier packets cannot open a newly enabled batch action");
            click(other, AgentMenu.BATCH_CREATE);
            c.assertTrue(other.containerMenu instanceof HelperBatchMenu.Handler,
                "A later explicit activation of the new green control can open batch choices");
            other.closeContainer();
            c.assertValueEqual(helpers.spawnBatch(owner, 1, AgentCompanions.Profile.REGULAR), 1,
                "The owner can explicitly fill capacity before the final native full-roster check");
            other.closeContainer(); BedrockAgentMenu.open(other, forms);
            c.assertFalse(forms.has("Create"), "The native empty roster offers neither creation route when the global cap is full");
            c.assertValueEqual(AgentActions.get(helpers.server).data.proposals.size(), proposals, "Batch creation cannot approve AI actions");
        } finally { cleanup(owner); if (other != null) cleanup(other); }
        c.succeed();
    }

    @GameTest public void javaEveryQuantityIsReachableAndCancelLeavesAllHelperSlotsEmpty(GameTestHelper c) {
        var owner = player(c, "batch-java-count"); var helpers = AgentCompanions.get(owner.level().getServer());
        try {
            HelperBatchMenu.open(owner);
            for (int expected = 1; expected <= 50; expected++) {
                c.assertValueEqual(batch(owner).amount, expected, "Single increments reach every valid integer quantity");
                click(owner, HelperBatchMenu.PLUS_ONE);
            }
            c.assertValueEqual(batch(owner).amount, 50, "Quantity cannot exceed the reviewed capacity");
            click(owner, HelperBatchMenu.MINUS_TEN); click(owner, HelperBatchMenu.MINUS_ONE);
            c.assertValueEqual(batch(owner).amount, 39, "Both decrement sizes change the chosen amount");
            click(owner, HelperBatchMenu.PRESET_TWENTY_FIVE); click(owner, HelperBatchMenu.PRESET_ONE);
            click(owner, HelperBatchMenu.MINUS_ONE);
            c.assertValueEqual(batch(owner).amount, 1, "Minimum stays one");
            var old = batch(owner); click(owner, HelperBatchMenu.BACK);
            c.assertValueEqual(helpers.count(owner), 0, "Quantity/profile browsing and cancelling never spawn");
            old.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 0, "Cancelled Java windows cannot be replayed");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void javaBatchRejectsChangedCapacityRecordsAndNativeMode(GameTestHelper c) {
        var owner = player(c, "batch-java-stale"); var helpers = AgentCompanions.get(owner.level().getServer());
        try {
            HelperBatchMenu.open(owner); var capacity = batch(owner);
            helpers.spawn(owner, "existing");
            capacity.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 1, "A changed capacity invalidates the exact reviewed batch");
            HelperBatchMenu.open(owner); var identity = batch(owner);
            helpers.profile(owner, "existing", AgentCompanions.Profile.DEBUG);
            identity.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 1, "A replaced owned record invalidates an old same-capacity request");
            HelperBatchMenu.open(owner); var mode = batch(owner); owner.setGameMode(GameType.CREATIVE);
            mode.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 1, "Changing native mode cannot reuse a reviewed request");
            owner.setGameMode(GameType.SURVIVAL);
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void javaBatchRejectsForeignConnectionPermissionAndOccupiedCursor(GameTestHelper c) {
        var owner = player(c, "batch-java-safe"); var other = player(c, "batch-java-other");
        var helpers = AgentCompanions.get(owner.level().getServer());
        try {
            HelperBatchMenu.open(owner); var screen = batch(owner);
            screen.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, other);
            c.assertTrue(owner.containerMenu == screen && helpers.count(owner) == 0, "Another player cannot click the owner's screen");
            screen.setCarried(new ItemStack(Items.DIAMOND)); click(owner, HelperBatchMenu.SPAWN);
            c.assertValueEqual(helpers.count(owner), 0, "A carried stack prevents batch activation");
            screen.setCarried(ItemStack.EMPTY); var connection = owner.connection;
            try { owner.connection = other.connection; screen.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner); }
            finally { owner.connection = connection; }
            c.assertValueEqual(helpers.count(owner), 0, "A replaced native connection cannot activate old clicks");
            HelperBatchMenu.open(owner); screen = batch(owner); operator(owner, LevelBasedPermissionSet.ADMIN);
            screen.clicked(HelperBatchMenu.SPAWN, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 0, "Revoked OP4 blocks previously displayed batch controls");
        } finally { cleanup(owner); cleanup(other); }
        c.succeed();
    }

    @GameTest public void nativeTouchBatchEncodesIntegerAndProfileAndCreatesOnlyOnce(GameTestHelper c) {
        var owner = player(c, "batch-touch-create"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            BedrockAgentMenu.open(owner, forms); forms.click("Create helpers in a batch");
            c.assertTrue(forms.last() instanceof CustomForm, "The batch screen is an actual native quantity/profile form");
            c.assertValueEqual(helpers.count(owner), 0, "Opening the native quantity form does not spawn");
            var form = forms.last(); submit(form, "7", 1);
            c.assertValueEqual(helpers.count(owner), 7, "One submitted native form creates the requested batch");
            c.assertTrue(helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID()))
                .allMatch(a -> a.profile() == AgentCompanions.Profile.ULTIMATE_FINALS), "The encoded dropdown selects every new helper's profile");
            submit(form, "7", 1);
            c.assertValueEqual(helpers.count(owner), 7, "A native response replay cannot add another batch");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void nativeTouchRejectsInvalidQuantityAndOldCancelCannotClearNewTicket(GameTestHelper c) {
        var owner = player(c, "batch-touch-input"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            for (String amount : new String[]{"0", "51", "-1", "1.5", "NaN", "999999999999999999999"}) {
                BedrockAgentMenu.openBatch(owner, forms); submit(forms.last(), amount, 0);
                c.assertValueEqual(helpers.count(owner), 0, "Invalid whole-number quantities never spawn: " + amount);
            }
            for (String malformed : new String[]{"[]", "[null,\"3\"]", "[null,null,0]", "[null,\"3\",99]"}) {
                BedrockAgentMenu.openBatch(owner, forms); reply(forms.last(), malformed);
                c.assertValueEqual(helpers.count(owner), 0, "Malformed native form responses never spawn: " + malformed);
            }
            BedrockAgentMenu.openBatch(owner, forms); var old = forms.last();
            BedrockAgentMenu.openBatch(owner, forms); var current = forms.last();
            var ticket = HelperForms.ACTIVE.get(owner.getUUID()); reply(old, "null");
            c.assertTrue(HelperForms.ACTIVE.get(owner.getUUID()) == ticket, "Cancelling a superseded form preserves the new exact ticket");
            submit(old, "5", 0); c.assertValueEqual(helpers.count(owner), 0, "Superseded valid responses cannot spawn");
            reply(current, "null"); c.assertValueEqual(helpers.count(owner), 0, "Cancelling the current form leaves the roster empty");
            c.assertFalse(HelperForms.ACTIVE.containsKey(owner.getUUID()), "Cancellation clears its own active ticket");
            c.assertValueEqual(BedrockAgentMenu.openBatch(owner, form -> false), 0, "Failed native form delivery is reported");
            c.assertFalse(HelperForms.ACTIVE.containsKey(owner.getUUID()), "Failed delivery cannot leave a later executable ticket");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void nativeTouchBatchRechecksCapacityCursorModeAndPermission(GameTestHelper c) {
        var owner = player(c, "batch-touch-safe"); var other = player(c, "batch-touch-other");
        var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            BedrockAgentMenu.openBatch(owner, forms); var capacity = forms.last(); helpers.spawn(other, "foreign");
            submit(capacity, "5", 0);
            c.assertValueEqual(helpers.count(owner), 0, "Another owner's use of global capacity invalidates a reviewed batch");
            BedrockAgentMenu.openBatch(owner, forms); var cursor = forms.last(); owner.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            submit(cursor, "5", 0); owner.inventoryMenu.setCarried(ItemStack.EMPTY);
            c.assertValueEqual(helpers.count(owner), 0, "An occupied native cursor blocks the queued response");
            BedrockAgentMenu.openBatch(owner, forms); var mode = forms.last(); owner.setGameMode(GameType.CREATIVE);
            submit(mode, "5", 0); owner.setGameMode(GameType.SURVIVAL); owner.getInventory().clearContent();
            c.assertValueEqual(helpers.count(owner), 0, "Native mode changes invalidate the form session");
            BedrockAgentMenu.openBatch(owner, forms); var denied = forms.last(); operator(owner, LevelBasedPermissionSet.ADMIN);
            submit(denied, "5", 0);
            c.assertValueEqual(helpers.count(owner), 0, "Revoked OP4 refuses old quantity forms");
        } finally { cleanup(owner); cleanup(other); }
        c.succeed();
    }

    @GameTest public void nativeTouchBatchRejectsReplacedConnectionAndChangedWorld(GameTestHelper c) {
        var owner = player(c, "batch-touch-world"); var other = player(c, "batch-touch-conn");
        var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        var originalWorld = owner.level(); var position = owner.position();
        try {
            BedrockAgentMenu.openBatch(owner, forms); var old = forms.last(); var connection = owner.connection;
            try { owner.connection = other.connection; submit(old, "3", 0); }
            finally { owner.connection = connection; }
            c.assertValueEqual(helpers.count(owner), 0, "A replaced connection cannot submit an owner's form");
            BedrockAgentMenu.openBatch(owner, forms); old = forms.last();
            owner.teleportTo(helpers.server.getLevel(net.minecraft.world.level.Level.NETHER), 0, 100, 0, java.util.Set.of(), 0, 0, true);
            submit(old, "3", 0);
            c.assertValueEqual(helpers.count(owner), 0, "A changed dimension cannot submit an old world's batch");
        } finally {
            owner.teleportTo(originalWorld, position.x, position.y, position.z, java.util.Set.of(), 0, 0, true);
            cleanup(owner); cleanup(other);
        }
        c.succeed();
    }
}

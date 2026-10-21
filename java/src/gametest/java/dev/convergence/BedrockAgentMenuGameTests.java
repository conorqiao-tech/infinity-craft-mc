package dev.convergence;

import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** Encoded native touch callbacks drive real helper entities and existing gated mutations. */
public class BedrockAgentMenuGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), NativeSessionFixture.nativeProfileName(name));
        var data = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, data.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, data);
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        operator(player, LevelBasedPermissionSet.OWNER);
        var feet = c.absolutePos(new BlockPos(3, 20, 3));
        for (var at : BlockPos.betweenClosed(feet.offset(-8, -1, -8), feet.offset(8, 4, 8)))
            c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        player.setPos(Vec3.atBottomCenterOf(feet));
        player.setNoGravity(true);
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.closeContainer();
        return player;
    }

    private void operator(ServerPlayer player, LevelBasedPermissionSet level) {
        var manager = player.level().getServer().getPlayerList();
        manager.deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        manager.op(new net.minecraft.server.players.NameAndId(player.getGameProfile()), java.util.Optional.of(level), java.util.Optional.of(false));
    }

    private void cleanup(ServerPlayer player) {
        HelperForms.ACTIVE.remove(player.getUUID());
        HelperForms.DELIVERIES.remove(player.getUUID());
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.closeContainer();
        operator(player, LevelBasedPermissionSet.OWNER);
        var helpers = AgentCompanions.get(player.level().getServer());
        for (var name : helpers.data.agents.values().stream().filter(a -> a.owner().equals(player.getStringUUID())).map(AgentCompanions.Agent::name).toList())
            helpers.dismiss(player, name);
        helpers.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        helpers.server.getPlayerList().remove(player);
    }

    private static void reply(Form form, String json) {
        try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form, json); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private static final class Capture implements Predicate<Form> {
        final ArrayList<Form> forms = new ArrayList<>();
        @Override public boolean test(Form form) {
            // Serialize the actual form consumed by Geyser, then exercise its
            // registered response handler rather than calling menu actions.
            String json = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            var wire = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!wire.get("type").getAsString().equals("form") || !wire.get("title").getAsString().equals(form.title()))
                throw new IllegalStateException("The actual touch form did not encode");
            forms.add(form); return true;
        }
        SimpleForm last() { return (SimpleForm)forms.getLast(); }
        boolean has(String prefix) { return last().buttons().stream().anyMatch(b -> b.text().startsWith(prefix)); }
        void click(String prefix) {
            var form = last();
            for (int i = 0; i < form.buttons().size(); i++) if (form.buttons().get(i).text().startsWith(prefix)) {
                reply(form, Integer.toString(i)); return;
            }
            throw new IllegalStateException("No touch button: " + prefix + " in " + form.title());
        }
    }

    @GameTest public void emptyTouchRosterCreatesOnlyAfterExplicitProfileAndRevealsRealHelper(GameTestHelper c) {
        var owner = player(c, "touch-create"); var helpers = AgentCompanions.get(owner.level().getServer());
        var forms = new Capture(); int proposals = AgentActions.get(helpers.server).data.proposals.size();
        try {
            c.assertValueEqual(BedrockAgentMenu.open(owner, forms), 1, "OP4 opens a real native form");
            c.assertValueEqual(helpers.count(owner), 0, "Opening controls does not create a helper");
            c.assertTrue(forms.last().content().contains("No helpers yet"), "The empty state explains the next step");
            c.assertFalse(forms.has("Squad") || forms.has("Follow") || forms.has("Bring"), "Zero helpers do not offer silent no-op movement controls");
            forms.click("Create your first helper");
            c.assertValueEqual(helpers.count(owner), 0, "Choosing Create opens the explicit profile decision before spawning");
            c.assertTrue(forms.has("Create: Regular") && forms.has("Create: Ultimate Finals"), "Both ordinary and combo profiles are easy to find");
            var profileForm = forms.last(); int screens = forms.forms.size();
            forms.click("Create: Ultimate Finals");
            var entry = helpers.owned(owner, "helper-1");
            c.assertTrue(entry != null, "A real named helper was saved");
            var entity = helpers.loaded.get(UUID.fromString(entry.getKey()));
            c.assertTrue(entity != null && entity.isAlive() && !entity.isInvisible() && entity.isCustomNameVisible(), "A live visible named helper exists");
            c.assertTrue(entity.level() == owner.level() && entity.distanceToSqr(owner) <= 32, "It appears beside its owner");
            c.assertValueEqual(entry.getValue().profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "The explicit chosen combat profile is applied");
            c.assertValueEqual(forms.forms.size(), screens, "Success does not cover the helper with another form");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "No simulated chest remains over the world");
            reply(profileForm, "1");
            c.assertValueEqual(helpers.count(owner), 1, "A replay cannot spawn another helper");
            c.assertValueEqual(AgentActions.get(helpers.server).data.proposals.size(), proposals, "Creation never approves or executes an AI order");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void nativeHelperDetailAndSquadControlsChangeRealProfilesMovementAndFormation(GameTestHelper c) {
        var owner = player(c, "touch-controls"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            helpers.spawn(owner, "alpha"); helpers.spawn(owner, "beta");
            var id = helpers.owned(owner, "alpha").getKey(); var entity = helpers.loaded.get(UUID.fromString(id));
            entity.setHealth(37);
            BedrockAgentMenu.open(owner, forms); forms.click("alpha •");
            c.assertTrue(forms.last().content().contains("Regular"), "Selected helper profile is visible inside the form");
            forms.click("Stay / pause");
            c.assertValueEqual(helpers.data.agents.get(id).mode(), AgentCompanions.Mode.STAY, "Native button pauses the actual helper");
            c.assertTrue(forms.last().content().toLowerCase(java.util.Locale.ROOT).contains("stay"), "The pause is visible without reading hidden chat");
            forms.click("Choose profile"); forms.click("Choose: Ultimate Finals");
            c.assertValueEqual(helpers.data.agents.get(id).profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "The profile screen changes the existing helper");
            c.assertTrue(forms.last().content().contains("spear") && forms.last().content().contains("mace"), "The actual combo requirements remain readable");
            forms.click("Follow me");
            c.assertValueEqual(helpers.data.agents.get(id).mode(), AgentCompanions.Mode.FOLLOW, "Follow resumes Stay helpers");
            c.assertValueEqual(entity.getHealth(), 37F, "Movement and profiles keep edited health");
            forms.click("Back to your squad"); forms.click("Squad controls");
            forms.click("Squad profile: Primitive");
            c.assertTrue(helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID()))
                .allMatch(a -> a.profile() == AgentCompanions.Profile.PRIMITIVE), "Squad profile applies to both saved helpers");
            forms.click("Squad: guard");
            c.assertTrue(helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID()))
                .allMatch(a -> a.mode() == AgentCompanions.Mode.GUARD), "Native squad movement reaches both actual helpers");
            var oldFormation = AgentCompanions.formation(owner); forms.click("Cycle follow formation");
            c.assertTrue(AgentCompanions.formation(owner) != oldFormation, "Formation cycles the owner's saved setting");
            forms.click("Ceasefire");
            c.assertTrue(forms.last().content().contains("pending player-target orders cleared"), "Ceasefire feedback describes its exact scope");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void nativeRecallMovesExistingEntityWithoutReplacingStatsOrRequiringCommands(GameTestHelper c) {
        var owner = player(c, "touch-recall"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            helpers.spawn(owner, "traveler");
            var id = helpers.owned(owner, "traveler").getKey(); var entity = helpers.loaded.get(UUID.fromString(id));
            entity.setHealth(41); helpers.mode(owner, "traveler", AgentCompanions.Mode.STAY);
            entity.setPos(owner.position().add(12, 0, 0));
            BedrockAgentMenu.open(owner, forms); forms.click("traveler •");
            int screens = forms.forms.size(); forms.click("Bring this helper here");
            var after = helpers.loaded.get(UUID.fromString(id));
            c.assertTrue(after != null && after.getStringUUID().equals(id) && after.distanceToSqr(owner) < 36, "Recall brings the same identity beside its owner");
            c.assertValueEqual(after.getHealth(), 41F, "Recall keeps actual native health");
            c.assertValueEqual(helpers.data.agents.get(id).mode(), AgentCompanions.Mode.FOLLOW, "Recall resumes Follow");
            c.assertValueEqual(helpers.count(owner), 1, "No replacement helper was spawned");
            c.assertValueEqual(forms.forms.size(), screens, "Successful single-helper recall reveals the world");
            BedrockAgentMenu.open(owner, forms); forms.click("Squad controls"); forms.click("Bring loaded squad here");
            c.assertTrue(forms.last().content().contains("helpers now nearby"), "Squad recall gives visible form feedback");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void dismissRequiresExactReviewAndCannotRemoveRecreatedSameNameHelper(GameTestHelper c) {
        var owner = player(c, "touch-dismiss"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            helpers.spawn(owner, "kept"); var oldId = helpers.owned(owner, "kept").getKey();
            BedrockAgentMenu.open(owner, forms); forms.click("kept •"); forms.click("Dismiss helper");
            c.assertValueEqual(helpers.count(owner), 1, "Opening dismiss review does not remove the helper");
            forms.click("Keep this helper");
            c.assertTrue(helpers.data.agents.containsKey(oldId), "Cancel preserves the exact helper");
            forms.click("Dismiss helper"); var oldReview = forms.last();
            helpers.dismiss(owner, "kept"); helpers.spawn(owner, "kept");
            var replacement = helpers.owned(owner, "kept").getKey();
            reply(oldReview, "0");
            c.assertTrue(!replacement.equals(oldId) && helpers.data.agents.containsKey(replacement), "Stale confirmation cannot dismiss a replacement with the same name");
            BedrockAgentMenu.open(owner, forms); forms.click("kept •"); forms.click("Dismiss helper"); forms.click("Confirm dismiss");
            c.assertValueEqual(helpers.count(owner), 0, "A fresh exact confirmation dismisses the real helper");
            c.assertTrue(forms.last().content().contains("was dismissed"), "The roster displays success feedback");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void staleProfileIdentityAndOtherOwnersCannotBeChangedThroughOldForms(GameTestHelper c) {
        var owner = player(c, "touch-identity"); var other = player(c, "touch-other");
        var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            helpers.spawn(owner, "own"); helpers.spawn(other, "foreign");
            BedrockAgentMenu.open(owner, forms);
            c.assertFalse(forms.has("foreign •"), "Another OP's helper is absent from this roster");
            forms.click("own •"); forms.click("Choose profile"); var stale = forms.last();
            helpers.profile(owner, "own", AgentCompanions.Profile.DEBUG);
            helpers.profile(owner, "own", AgentCompanions.Profile.REGULAR);
            reply(stale, "1");
            c.assertValueEqual(helpers.owned(owner, "own").getValue().profile(), AgentCompanions.Profile.REGULAR, "Changed-away-and-back records invalidate the old choice");
            c.assertValueEqual(helpers.owned(other, "foreign").getValue().profile(), AgentCompanions.Profile.REGULAR, "Foreign saved records stay unchanged");
        } finally { cleanup(owner); cleanup(other); }
        c.succeed();
    }

    @GameTest public void unavailableHelpersOmitMovementAndRecallButKeepSavedProfileControls(GameTestHelper c) {
        var owner = player(c, "touch-unloaded"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        String id = null; net.minecraft.world.entity.animal.golem.IronGolem entity = null;
        try {
            helpers.spawn(owner, "saved"); id = helpers.owned(owner, "saved").getKey(); entity = helpers.loaded.remove(UUID.fromString(id));
            BedrockAgentMenu.open(owner, forms); forms.click("saved •");
            c.assertFalse(forms.has("Follow me") || forms.has("Guard") || forms.has("Stay") || forms.has("Bring"), "Unloaded helper cannot receive a silent zero-effect movement action");
            c.assertTrue(forms.last().content().contains("Unloaded"), "The form explains how to load the helper");
            forms.click("Choose profile"); forms.click("Choose: Debug");
            c.assertValueEqual(helpers.data.agents.get(id).profile(), AgentCompanions.Profile.DEBUG, "Passive profile is saved without forcing entity load");
        } finally {
            if (id != null && entity != null) helpers.loaded.put(UUID.fromString(id), entity);
            cleanup(owner);
        }
        c.succeed();
    }

    @GameTest public void failedSpawnStaysVisibleAndFailedSendCreatesNothing(GameTestHelper c) {
        var owner = player(c, "touch-failure"); var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            var origin = owner.blockPosition();
            for (var at : BlockPos.betweenClosed(origin.offset(-6, -1, -6), origin.offset(6, -1, 6)))
                owner.level().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            BedrockAgentMenu.open(owner, forms); forms.click("Create your first helper"); forms.click("Create: Regular");
            c.assertValueEqual(helpers.count(owner), 0, "No solid ground refuses the real entity spawn");
            c.assertTrue(forms.last().content().contains("No helper appeared") && forms.last().content().contains("clear solid ground"), "Failure is explained inside the form");
            c.assertValueEqual(BedrockAgentMenu.open(owner, form -> false), 0, "Transport failure is reported");
            c.assertFalse(HelperForms.ACTIVE.containsKey(owner.getUUID()), "Failed delivery leaves no active action ticket");
            c.assertValueEqual(helpers.count(owner), 0, "Failed send never creates a helper");
        } finally { cleanup(owner); }
        c.succeed();
    }

    @GameTest public void touchCallbacksRecheckPermissionConnectionModeCursorAndReplay(GameTestHelper c) {
        var owner = player(c, "touch-security"); var other = player(c, "touch-session");
        var helpers = AgentCompanions.get(owner.level().getServer()); var forms = new Capture();
        try {
            operator(owner, LevelBasedPermissionSet.ADMIN);
            c.assertValueEqual(BedrockAgentMenu.open(owner, forms), 0, "OP3 cannot open OP4 controls");
            operator(owner, LevelBasedPermissionSet.OWNER);
            BedrockAgentMenu.open(owner, forms); forms.click("Create your first helper"); var old = forms.last();
            BedrockAgentMenu.open(owner, forms); reply(old, "0");
            c.assertValueEqual(helpers.count(owner), 0, "Superseded profile form cannot spawn");
            forms.click("Create your first helper"); operator(owner, LevelBasedPermissionSet.ADMIN); forms.click("Create: Regular");
            c.assertValueEqual(helpers.count(owner), 0, "Revoked OP4 blocks an already displayed action");
            operator(owner, LevelBasedPermissionSet.OWNER);
            BedrockAgentMenu.open(owner, forms); forms.click("Create your first helper"); owner.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
            forms.click("Create: Regular"); owner.inventoryMenu.setCarried(ItemStack.EMPTY);
            c.assertValueEqual(helpers.count(owner), 0, "Occupied cursor refuses mutation");
            BedrockAgentMenu.open(owner, forms); forms.click("Create your first helper"); owner.setGameMode(GameType.CREATIVE);
            forms.click("Create: Regular"); owner.setGameMode(GameType.SURVIVAL); owner.getInventory().clearContent();
            c.assertValueEqual(helpers.count(owner), 0, "Changed native mode invalidates the old form");
            BedrockAgentMenu.open(owner, forms); forms.click("Create your first helper"); var originalConnection = owner.connection;
            try { owner.connection = other.connection; forms.click("Create: Regular"); }
            finally { owner.connection = originalConnection; }
            c.assertValueEqual(helpers.count(owner), 0, "Another connection cannot use this owner's form");
        } finally { cleanup(owner); cleanup(other); }
        c.succeed();
    }

    @GameTest public void merelyBrowsingDoesNotAskAiAndExplicitLocalAskNeverApprovesCommands(GameTestHelper c) {
        var owner = player(c, "touch-ask"); var server = owner.level().getServer(); var helpers = AgentCompanions.get(server);
        var originalService = ServerAssistant.AI.remove(server); var forms = new Capture();
        int proposals = AgentActions.get(server).data.proposals.size();
        try {
            helpers.spawn(owner, "guide");
            BedrockAgentMenu.open(owner, forms); forms.click("guide •"); forms.click("Refresh live status");
            var session = ServerAssistant.SESSIONS.get(server);
            c.assertTrue(session == null || !session.readyAt.containsKey(owner.getUUID()), "Opening and refreshing status do not submit any AI question");
            c.assertTrue(forms.has("Ask helper"), "Local fallback is labeled accurately");
            int screens = forms.forms.size(); forms.click("Ask helper");
            c.assertTrue(ServerAssistant.SESSIONS.get(server).readyAt.containsKey(owner.getUUID()), "Only explicit Ask invokes existing local guidance");
            c.assertValueEqual(forms.forms.size(), screens, "The form closes so its answer is visible");
            c.assertValueEqual(AgentActions.get(server).data.proposals.size(), proposals, "Ask never approves or executes commands");
        } finally { if (originalService != null) ServerAssistant.AI.put(server, originalService); cleanup(owner); }
        c.succeed();
    }
}

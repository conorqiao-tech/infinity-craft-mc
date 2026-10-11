package dev.convergence;

import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.block.Blocks;

public class CommunityGameTests {
    private CommunityServer fresh(GameTestHelper c) {
        try { return new CommunityServer(c.getLevel().getServer(), Files.createTempDirectory("infinity-community-test-").resolve("state.json")); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
    private ServerPlayer player(GameTestHelper c, String name, int x) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var data = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var p = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, data.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, data);
        p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        var feet = c.absolutePos(new BlockPos(x, 2, 2));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            c.getLevel().setBlockAndUpdate(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet.offset(dx, 0, dz), Blocks.AIR.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet.offset(dx, 1, dz), Blocks.AIR.defaultBlockState());
        }
        p.setPos(feet.getX() + .5, feet.getY(), feet.getZ() + .5); p.setNoGravity(true);
        return p;
    }
    @GameTest public void communityHomesPersistAndRemainPrivate(GameTestHelper c) {
        var s = fresh(c); var p = player(c, "home-owner", 2); var other = player(c, "home-other", 5);
        for (String name : new String[]{"base", "mine", "farm", "fourth"}) s.setHome(p, name);
        c.assertValueEqual(s.homes(p).size(), 3, "Home limit enforced");
        c.assertTrue(s.homes(other).isEmpty(), "Another UUID cannot see these homes");
        var reloaded = CommunityServer.read(s.file);
        c.assertValueEqual(reloaded.homes.get(p.getStringUUID()).size(), 3, "Homes survive restart");
        c.assertTrue(Files.exists(s.file.resolveSibling("state.json.previous")), "Previous save retained");
        c.assertFalse(CommunityServer.validName("../escape"), "Home is a label, not a path"); c.succeed();
    }
    @GameTest public void communityUnsafeLocationsRefused(GameTestHelper c) {
        var p = player(c, "safe-places", 2); var place = CommunityServer.Place.of(p);
        c.assertTrue(CommunityServer.safe(c.getLevel(), place), "Solid floor and air are safe");
        c.getLevel().setBlockAndUpdate(p.blockPosition().above(), Blocks.STONE.defaultBlockState());
        c.assertFalse(CommunityServer.safe(c.getLevel(), place), "Blocked head space is rejected");
        c.getLevel().setBlockAndUpdate(p.blockPosition().above(), Blocks.AIR.defaultBlockState());
        c.getLevel().setBlockAndUpdate(p.blockPosition().below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        c.assertFalse(CommunityServer.safe(c.getLevel(), place), "Damaging floor is rejected");
        c.assertFalse(new CommunityServer.Place("minecraft:overworld", Double.NaN, 70, 0, 0, 0).valid(), "NaN rejected");
        c.succeed();
    }
    @GameTest public void communityRequestsNeedConsentAndExpire(GameTestHelper c) {
        var s = fresh(c); var visitor = player(c, "visitor", 2); var host = player(c, "host", 5);
        s.request(visitor, host);
        c.assertTrue(s.pending.isEmpty(), "Request alone never teleports");
        s.answer(host, false); c.assertTrue(s.pending.isEmpty(), "Denial never teleports");
        s.request(visitor, host); s.answer(host, true);
        c.assertTrue(s.pending.containsKey(visitor.getUUID()), "Consent queues the visitor");
        c.assertValueEqual(s.pending.get(visitor.getUUID()).host(), host.getUUID(), "Accepted host tracked");
        s.pending.clear(); s.requests.put(host.getUUID(), new CommunityServer.Request(visitor.getUUID(), c.getLevel().getServer().getTickCount() - 1));
        s.answer(host, true); c.assertTrue(s.pending.isEmpty(), "Expired requests cannot be accepted"); c.succeed();
    }
    @GameTest public void communityTeleportMovementCancellationAndCompletion(GameTestHelper c) {
        var s = fresh(c); var p = player(c, "traveler", 2); var target = player(c, "destination", 5);
        var destination = CommunityServer.Place.of(target);
        s.queue(p, destination, null); p.setPos(p.getX() + 1, p.getY(), p.getZ()); s.tick();
        c.assertTrue(s.pending.isEmpty(), "Movement cancels countdown");
        s.queue(p, destination, null);
        var pending = s.pending.get(p.getUUID());
        c.assertTrue(pending != null, "New countdown can start after movement cancellation");
        s.pending.put(p.getUUID(), new CommunityServer.Pending(destination, pending.origin(), c.getLevel().getServer().getTickCount(), null));
        s.tick();
        c.assertTrue(p.position().distanceToSqr(target.position()) < .01, "Real server teleport reaches destination");
        s.queue(p, destination, null); c.assertTrue(s.pending.isEmpty(), "Cooldown prevents immediate repeat"); c.succeed();
    }
    @GameTest public void communityStaffCommandsAreRestricted(GameTestHelper c) {
        var source = c.getLevel().getServer().createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS);
        var root = c.getLevel().getServer().getCommands().getDispatcher().getRoot();
        c.assertFalse(root.getChild("community").canUse(source), "Staff command root rejects ordinary players");
        c.assertTrue(root.getChild("home").canUse(source), "Ordinary players can use homes");
        c.assertTrue(root.getChild("tpa").canUse(source), "Ordinary players can request teleports");
        c.assertTrue(root.getChild("community").canUse(source.withPermission(PermissionSet.ALL_PERMISSIONS)), "Operators can administer community commands"); c.succeed();
    }
    @GameTest public void communityChatMuteSurvivesReload(GameTestHelper c) {
        var s = fresh(c); var p = player(c, "chat-user", 2);
        c.assertTrue(s.allowChat(p), "First message passes");
        c.assertFalse(s.allowChat(p), "Rapid chat is throttled");
        s.data.mutedUntil.put(p.getStringUUID(), System.currentTimeMillis() + 60_000); s.save();
        var reloaded = new CommunityServer(s.server, s.file);
        c.assertFalse(reloaded.allowChat(p), "Mute survives a restart");
        reloaded.data.mutedUntil.put(p.getStringUUID(), System.currentTimeMillis() - 1);
        c.assertTrue(reloaded.allowChat(p), "Expired mute lifts automatically"); c.succeed();
    }
    @GameTest public void communityCorruptSaveFailsWithoutOverwriting(GameTestHelper c) {
        var s = fresh(c);
        try {
            Files.writeString(s.file, "{not valid json"); boolean failed = false;
            try { CommunityServer.read(s.file); } catch (IllegalStateException expected) { failed = true; }
            c.assertTrue(failed, "Corrupt data fails visibly");
            c.assertValueEqual(Files.readString(s.file), "{not valid json", "Corrupt original retained for recovery");
        } catch (java.io.IOException e) { throw new RuntimeException(e); } c.succeed();
    }
    @GameTest public void communityPlazaRefusesToReplaceAChest(GameTestHelper c) {
        var s = fresh(c); var p = player(c, "builder", 2); var chest = p.blockPosition().offset(2, 0, 0);
        c.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState()); s.buildSpawn(p);
        c.assertTrue(c.getLevel().getBlockState(chest).is(Blocks.CHEST), "Build command preserves occupied areas");
        c.assertTrue(s.data.spawn == null, "Failed build does not change spawn"); c.succeed();
    }
    @GameTest public void communityPlazaBuildsAndSetsSpawn(GameTestHelper c) {
        var s = fresh(c); var p = player(c, "plaza-builder", 2);
        var center = p.blockPosition().offset(0, 120, 0); var oldSpawn = s.server.getRespawnData();
        try {
            for (BlockPos at : BlockPos.betweenClosed(center.offset(-12, 0, -12), center.offset(12, 5, 12)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == center.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            p.setPos(center.getX() + .5, center.getY() + 1, center.getZ() + .5);
            s.buildSpawn(p);
            c.assertTrue(s.data.spawn != null, "Successful plaza saves the shared spawn");
            c.assertTrue(c.getLevel().getBlockState(center).is(Blocks.SEA_LANTERN), "Center is lit");
            c.assertTrue(c.getLevel().getBlockState(center.offset(1, 0, 3)).is(Blocks.QUARTZ_BLOCK), "Walkway is built");
            c.assertTrue(c.getLevel().getBlockState(center.offset(10, 4, 10)).is(Blocks.SEA_LANTERN), "Corner lamps are built");
            c.assertTrue(CommunityServer.safe(c.getLevel(), s.data.spawn), "New spawn is a safe teleport destination");
        } finally { s.server.setRespawnData(oldSpawn); }
        c.succeed();
    }
}

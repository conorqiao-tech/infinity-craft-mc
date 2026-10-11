package dev.convergence;

import java.nio.file.Files;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public class CommunityGameTests {
    private CommunityServer fresh(TestContext c) {
        try { return new CommunityServer(c.getWorld().getServer(), Files.createTempDirectory("infinity-community-test-").resolve("state.json")); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
    private ServerPlayerEntity player(TestContext c, String name, int x) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var data = net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);
        var p = new ServerPlayerEntity(c.getWorld().getServer(), c.getWorld(), profile, data.syncedOptions());
        var connection = new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, p, data);
        p.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        var feet = c.getAbsolutePos(new BlockPos(x, 2, 2));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            c.getWorld().setBlockState(feet.add(dx, -1, dz), Blocks.STONE.getDefaultState());
            c.getWorld().setBlockState(feet.add(dx, 0, dz), Blocks.AIR.getDefaultState());
            c.getWorld().setBlockState(feet.add(dx, 1, dz), Blocks.AIR.getDefaultState());
        }
        p.setPosition(feet.getX() + .5, feet.getY(), feet.getZ() + .5); p.setNoGravity(true);
        return p;
    }
    @GameTest public void communityHomesPersistAndRemainPrivate(TestContext c) {
        var s = fresh(c); var p = player(c, "home-owner", 2); var other = player(c, "home-other", 5);
        for (String name : new String[]{"base", "mine", "farm", "fourth"}) s.setHome(p, name);
        c.assertEquals(s.homes(p).size(), 3, "Home limit enforced");
        c.assertTrue(s.homes(other).isEmpty(), "Another UUID cannot see these homes");
        var reloaded = CommunityServer.read(s.file);
        c.assertEquals(reloaded.homes.get(p.getUuidAsString()).size(), 3, "Homes survive restart");
        c.assertTrue(Files.exists(s.file.resolveSibling("state.json.previous")), "Previous save retained");
        c.assertFalse(CommunityServer.validName("../escape"), "Home is a label, not a path"); c.complete();
    }
    @GameTest public void communityUnsafeLocationsRefused(TestContext c) {
        var p = player(c, "safe-places", 2); var place = CommunityServer.Place.of(p);
        c.assertTrue(CommunityServer.safe(c.getWorld(), place), "Solid floor and air are safe");
        c.getWorld().setBlockState(p.getBlockPos().up(), Blocks.STONE.getDefaultState());
        c.assertFalse(CommunityServer.safe(c.getWorld(), place), "Blocked head space is rejected");
        c.getWorld().setBlockState(p.getBlockPos().up(), Blocks.AIR.getDefaultState());
        c.getWorld().setBlockState(p.getBlockPos().down(), Blocks.MAGMA_BLOCK.getDefaultState());
        c.assertFalse(CommunityServer.safe(c.getWorld(), place), "Damaging floor is rejected");
        c.assertFalse(new CommunityServer.Place("minecraft:overworld", Double.NaN, 70, 0, 0, 0).valid(), "NaN rejected");
        c.complete();
    }
    @GameTest public void communityRequestsNeedConsentAndExpire(TestContext c) {
        var s = fresh(c); var visitor = player(c, "visitor", 2); var host = player(c, "host", 5);
        s.request(visitor, host);
        c.assertTrue(s.pending.isEmpty(), "Request alone never teleports");
        s.answer(host, false); c.assertTrue(s.pending.isEmpty(), "Denial never teleports");
        s.request(visitor, host); s.answer(host, true);
        c.assertTrue(s.pending.containsKey(visitor.getUuid()), "Consent queues the visitor");
        c.assertEquals(s.pending.get(visitor.getUuid()).host(), host.getUuid(), "Accepted host tracked");
        s.pending.clear(); s.requests.put(host.getUuid(), new CommunityServer.Request(visitor.getUuid(), c.getWorld().getServer().getTicks() - 1));
        s.answer(host, true); c.assertTrue(s.pending.isEmpty(), "Expired requests cannot be accepted"); c.complete();
    }
    @GameTest public void communityTeleportMovementCancellationAndCompletion(TestContext c) {
        var s = fresh(c); var p = player(c, "traveler", 2); var target = player(c, "destination", 5);
        var destination = CommunityServer.Place.of(target);
        s.queue(p, destination, null); p.setPosition(p.getX() + 1, p.getY(), p.getZ()); s.tick();
        c.assertTrue(s.pending.isEmpty(), "Movement cancels countdown");
        s.queue(p, destination, null);
        var pending = s.pending.get(p.getUuid());
        c.assertTrue(pending != null, "New countdown can start after movement cancellation");
        s.pending.put(p.getUuid(), new CommunityServer.Pending(destination, pending.origin(), c.getWorld().getServer().getTicks(), null));
        s.tick();
        c.assertTrue(p.getEntityPos().squaredDistanceTo(target.getEntityPos()) < .01, "Real server teleport reaches destination");
        s.queue(p, destination, null); c.assertTrue(s.pending.isEmpty(), "Cooldown prevents immediate repeat"); c.complete();
    }
    @GameTest public void communityStaffCommandsAreRestricted(TestContext c) {
        var source = c.getWorld().getServer().getCommandSource().withPermissions(PermissionPredicate.NONE);
        var root = c.getWorld().getServer().getCommandManager().getDispatcher().getRoot();
        c.assertFalse(root.getChild("community").canUse(source), "Staff command root rejects ordinary players");
        c.assertTrue(root.getChild("home").canUse(source), "Ordinary players can use homes");
        c.assertTrue(root.getChild("tpa").canUse(source), "Ordinary players can request teleports");
        c.assertTrue(root.getChild("community").canUse(source.withPermissions(PermissionPredicate.ALL)), "Operators can administer community commands"); c.complete();
    }
    @GameTest public void communityChatMuteSurvivesReload(TestContext c) {
        var s = fresh(c); var p = player(c, "chat-user", 2);
        c.assertTrue(s.allowChat(p), "First message passes");
        c.assertFalse(s.allowChat(p), "Rapid chat is throttled");
        s.data.mutedUntil.put(p.getUuidAsString(), System.currentTimeMillis() + 60_000); s.save();
        var reloaded = new CommunityServer(s.server, s.file);
        c.assertFalse(reloaded.allowChat(p), "Mute survives a restart");
        reloaded.data.mutedUntil.put(p.getUuidAsString(), System.currentTimeMillis() - 1);
        c.assertTrue(reloaded.allowChat(p), "Expired mute lifts automatically"); c.complete();
    }
    @GameTest public void communityCorruptSaveFailsWithoutOverwriting(TestContext c) {
        var s = fresh(c);
        try {
            Files.writeString(s.file, "{not valid json"); boolean failed = false;
            try { CommunityServer.read(s.file); } catch (IllegalStateException expected) { failed = true; }
            c.assertTrue(failed, "Corrupt data fails visibly");
            c.assertEquals(Files.readString(s.file), "{not valid json", "Corrupt original retained for recovery");
        } catch (java.io.IOException e) { throw new RuntimeException(e); } c.complete();
    }
    @GameTest public void communityPlazaRefusesToReplaceAChest(TestContext c) {
        var s = fresh(c); var p = player(c, "builder", 2); var chest = p.getBlockPos().add(2, 0, 0);
        c.getWorld().setBlockState(chest, Blocks.CHEST.getDefaultState()); s.buildSpawn(p);
        c.assertTrue(c.getWorld().getBlockState(chest).isOf(Blocks.CHEST), "Build command preserves occupied areas");
        c.assertTrue(s.data.spawn == null, "Failed build does not change spawn"); c.complete();
    }
    @GameTest public void communityPlazaBuildsAndSetsSpawn(TestContext c) {
        var s = fresh(c); var p = player(c, "plaza-builder", 2);
        var center = p.getBlockPos().add(0, 120, 0); var oldSpawn = s.server.getSpawnPoint();
        try {
            for (BlockPos at : BlockPos.iterate(center.add(-12, 0, -12), center.add(12, 5, 12)))
                c.getWorld().setBlockState(at, at.getY() == center.getY() ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.AIR.getDefaultState());
            p.setPosition(center.getX() + .5, center.getY() + 1, center.getZ() + .5);
            s.buildSpawn(p);
            c.assertTrue(s.data.spawn != null, "Successful plaza saves the shared spawn");
            c.assertTrue(c.getWorld().getBlockState(center).isOf(Blocks.SEA_LANTERN), "Center is lit");
            c.assertTrue(c.getWorld().getBlockState(center.add(1, 0, 3)).isOf(Blocks.QUARTZ_BLOCK), "Walkway is built");
            c.assertTrue(c.getWorld().getBlockState(center.add(10, 4, 10)).isOf(Blocks.SEA_LANTERN), "Corner lamps are built");
            c.assertTrue(CommunityServer.safe(c.getWorld(), s.data.spawn), "New spawn is a safe teleport destination");
        } finally { s.server.setSpawnPoint(oldSpawn); }
        c.complete();
    }
}

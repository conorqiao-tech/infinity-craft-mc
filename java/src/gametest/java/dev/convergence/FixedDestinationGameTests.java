package dev.convergence;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Exclusive runtime; each synchronous case owns distinct, explicitly warm terrain. */
public class FixedDestinationGameTests {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final class Fixture implements AutoCloseable {
        final ServerLevel world;
        final BlockPos feet;
        final CommunityServer.Place target;
        final List<ChunkPos> halo;
        final TicketType type;
        final TicketStorage storage;
        final TicketType unrelated = new TicketType(23419, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        DestinationChunksProbe.Sample sample;
        boolean unrelatedAdded;

        Fixture(GameTestHelper c, int region) {
            world = GameModes.world(c.getLevel().getServer(), GameModes.Mode.CREATIVE);
            feet = new ChunkPos(region, region).getBlockAt(15, world.getMaxY() - 4, 15);
            target = new CommunityServer.Place(world.dimension().identifier().toString(),
                feet.getX() + .5, feet.getY(), feet.getZ() + .5, 37, -12);
            halo = List.of(new ChunkPos(region, region), new ChunkPos(region + 1, region),
                new ChunkPos(region, region + 1), new ChunkPos(region + 1, region + 1));
            try {
                var ticket = DestinationChunks.class.getDeclaredField("TICKET"); ticket.setAccessible(true);
                type = (TicketType) ticket.get(null);
                var tickets = world.getChunkSource().getClass().getDeclaredField("ticketStorage"); tickets.setAccessible(true);
                storage = (TicketStorage) tickets.get(world.getChunkSource());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            try {
                // All synchronous fixture loading happens before scoped observations.
                for (var at : halo) {
                    var chunk = world.getChunk(at.x(), at.z());
                    c.assertTrue(world.getChunkSource().getChunkNow(at.x(), at.z()) == chunk,
                        "Fixture halo is an actual published native FULL chunk");
                    c.assertValueEqual(owned(at).size(), 0, "Distinct fixture chunks have no inherited destination ticket");
                }
                for (var at : List.of(feet.below(), feet, feet.above())) original.put(at, world.getBlockState(at));
                repair();
                c.assertTrue(CommunityServer.safe(world, target), "The owned native floor and headspace are actually safe");
                unrelatedAdded = true;
                world.getChunkSource().addTicketWithRadius(unrelated, halo.getFirst(), 0);
                var watched = new LinkedHashSet<>(halo);
                watched.add(ChunkPos.containing(BlockPos.containing(29_999_999.5, target.y(), target.z())));
                sample = DestinationChunksProbe.begin(world.getServer(), world.getChunkSource(), type, watched);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        void repair() {
            world.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), FLAGS);
            world.setBlock(feet, Blocks.AIR.defaultBlockState(), FLAGS);
            world.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), FLAGS);
        }
        List<Ticket> owned(ChunkPos at) { return tickets(at, type); }
        List<Ticket> tickets(ChunkPos at, TicketType requested) {
            var values = storage.getTickets(ChunkPos.pack(at.x(), at.z()));
            return values == null ? List.of() : values.stream().filter(ticket -> ticket.getType().equals(requested)).toList();
        }
        FixedDestination.Status poll(FixedDestination destination) { return sample.measure(destination::poll); }
        void retained(GameTestHelper c, int expected) {
            for (var at : halo) c.assertValueEqual(owned(at).size(), expected, "Exact collision halo owns the expected real native tickets");
        }
        void nonblocking(GameTestHelper c) {
            c.assertValueEqual(sample.starts.size(), 0, "Explicitly warm selectors never initiate native loading");
            c.assertValueEqual(sample.blockingFutureCalls, 0, "Selector polls make zero calls to native getChunkFuture");
        }
        @Override public void close() {
            try { for (var entry : original.entrySet()) world.setBlock(entry.getKey(), entry.getValue(), FLAGS); }
            finally {
                try { if (unrelatedAdded) world.getChunkSource().removeTicketWithRadius(unrelated, halo.getFirst(), 0); }
                finally { if (sample != null) DestinationChunksProbe.end(sample); }
            }
        }
    }

    @GameTest public void exactWarmDestinationRevalidatesRefusesFallbackAndRetainsOperatorBypass(GameTestHelper c) {
        try (var fixture = new Fixture(c, 16)) {
            for (boolean floor : List.of(true, false)) {
                try (var destination = new FixedDestination(fixture.world, fixture.target, true)) {
                    c.assertValueEqual(fixture.poll(destination), FixedDestination.Status.READY, "Exact safe native destination is immediately ready");
                    c.assertTrue(destination.place() == fixture.target, "The original exact target, including rotation, is retained");
                    fixture.retained(c, 1);
                    fixture.world.setBlock(floor ? fixture.feet.below() : fixture.feet.above(),
                        floor ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), FLAGS);
                    c.assertValueEqual(fixture.poll(destination), FixedDestination.Status.FAILED, "A READY target is refused after its actual floor or headspace changes");
                    c.assertTrue(destination.place() == null && destination.failure() != null, "An obstructed exact target exposes no fallback");
                    var failure = destination.failure();
                    try (var bypass = new FixedDestination(fixture.world, fixture.target, false)) {
                        c.assertValueEqual(fixture.poll(bypass), FixedDestination.Status.READY, "Privileged behavior accepts this same actual blocked target");
                        c.assertTrue(bypass.place() == fixture.target, "Bypass does not replace or move the exact address");
                    }
                    fixture.retained(c, 1);
                    fixture.repair();
                    c.assertValueEqual(fixture.poll(destination), FixedDestination.Status.FAILED, "Repair cannot revive a terminal failed request");
                    c.assertTrue(destination.failure() == failure && destination.place() == null, "The original refusal remains terminal");
                } finally { fixture.repair(); }
                fixture.retained(c, 0);
                try (var fresh = new FixedDestination(fixture.world, fixture.target, true)) {
                    c.assertValueEqual(fixture.poll(fresh), FixedDestination.Status.READY, "A new request recovers after real terrain repair");
                    c.assertTrue(fresh.place() == fixture.target, "Recovery remains at the original exact address");
                }
                fixture.retained(c, 0);
            }
            fixture.nonblocking(c);
        }
        c.succeed();
    }

    @GameTest public void invalidTargetsRejectBeforeLeasesAndNormalClosePreservesUnrelatedTickets(GameTestHelper c) throws ReflectiveOperationException {
        try (var fixture = new Fixture(c, 24)) {
            var at = fixture.target;
            var leases = FixedDestination.class.getDeclaredField("leases"); leases.setAccessible(true);
            var invalid = Arrays.asList(null,
                new CommunityServer.Place(null, at.x(), at.y(), at.z(), 0, 0),
                new CommunityServer.Place("bad dimension", at.x(), at.y(), at.z(), 0, 0),
                new CommunityServer.Place("minecraft:overworld", at.x(), at.y(), at.z(), 0, 0),
                new CommunityServer.Place(at.dimension(), Double.NaN, at.y(), at.z(), 0, 0),
                new CommunityServer.Place(at.dimension(), at.x(), Double.POSITIVE_INFINITY, at.z(), 0, 0),
                new CommunityServer.Place(at.dimension(), at.x(), at.y(), Double.NEGATIVE_INFINITY, 0, 0),
                new CommunityServer.Place(at.dimension(), at.x(), at.y(), at.z(), Float.NaN, 0),
                new CommunityServer.Place(at.dimension(), at.x(), at.y(), at.z(), 0, Float.POSITIVE_INFINITY));
            for (var target : invalid) for (boolean safe : List.of(true, false))
                try (var destination = new FixedDestination(fixture.world, target, safe)) {
                    c.assertValueEqual(fixture.poll(destination), FixedDestination.Status.FAILED, "Invalid or mismatched addresses are refused even with privileged behavior");
                    c.assertTrue(((Map<?, ?>) leases.get(destination)).isEmpty(), "Invalid targets are rejected before any chunk lease exists");
                    c.assertTrue(destination.place() == null && destination.failure() != null, "Invalid requests expose only a refusal");
                }
            var outside = new CommunityServer.Place(at.dimension(), 29_999_999.5, at.y(), at.z(), 0, 0);
            var outsideChunk = ChunkPos.containing(BlockPos.containing(outside.x(), outside.y(), outside.z()));
            c.assertTrue(outside.valid() && !fixture.world.getWorldBorder().isWithinBounds(BlockPos.containing(outside.x(), outside.y(), outside.z())),
                "The native default border rejects this otherwise valid address without a border mutation");
            c.assertTrue(fixture.world.getChunkSource().getChunkNow(outsideChunk.x(), outsideChunk.z()) == null,
                "The out-of-border native fixture starts genuinely unloaded");
            c.assertValueEqual(fixture.owned(outsideChunk).size(), 0, "The remote address starts without a destination ticket");
            for (var target : List.of(outside,
                new CommunityServer.Place(at.dimension(), at.x(), fixture.world.getMaxY(), at.z(), 0, 0),
                new CommunityServer.Place(at.dimension(), at.x(), fixture.world.getMinY() - 1, at.z(), 0, 0)))
                try (var destination = new FixedDestination(fixture.world, target, true)) {
                    c.assertValueEqual(fixture.poll(destination), FixedDestination.Status.FAILED, "Native bounds and border fail before terrain acquisition");
                    c.assertTrue(((Map<?, ?>) leases.get(destination)).isEmpty(), "Out-of-bounds targets create no chunk lease");
                }
            c.assertTrue(fixture.world.getChunkSource().getChunkNow(outsideChunk.x(), outsideChunk.z()) == null,
                "Refusing the remote address never publishes its native chunk");
            c.assertValueEqual(fixture.owned(outsideChunk).size(), 0, "Refusing the remote address never creates a native ticket");
            fixture.retained(c, 0);
            var unrelated = fixture.tickets(fixture.halo.getFirst(), fixture.unrelated);
            c.assertValueEqual(unrelated.size(), 1, "Fixture owns one distinct real native retention ticket");
            try (var normal = new FixedDestination(fixture.world, at, true)) {
                c.assertValueEqual(fixture.poll(normal), FixedDestination.Status.READY, "Valid exact preparation still works after all refusals");
                fixture.retained(c, 1);
                normal.close(); normal.close();
                c.assertTrue(normal.place() == null && normal.status() == FixedDestination.Status.FAILED, "Repeated close hides the old ready address");
                fixture.retained(c, 0);
                c.assertValueEqual(fixture.tickets(fixture.halo.getFirst(), fixture.unrelated), unrelated, "Closing destination leases preserves the same unrelated native ticket");
            }
            fixture.nonblocking(c);
        }
        c.succeed();
    }
}

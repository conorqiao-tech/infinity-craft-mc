package dev.convergence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

/** This phased case runs in its own runtime: stop cleanup is deliberately server-wide. */
public class DestinationChunksGameTests {
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper context;
        final ServerLevel world;
        final MinecraftServer server;
        final TicketStorage storage;
        final TicketType type;
        final TicketType unrelated = new TicketType(23041, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final ChunkPos warm, canceled, loadingAtClear, queuedAtClear;
        final List<ChunkPos> cold = new ArrayList<>();
        final List<DestinationChunks.Lease> leases = new ArrayList<>();
        final LinkedHashSet<ChunkPos> watched = new LinkedHashSet<>();
        final DestinationChunksProbe.Sample sample;
        boolean closed;
        Fixture(GameTestHelper context) {
            this.context = context; world = context.getLevel(); server = world.getServer();
            try {
                var typeField = DestinationChunks.class.getDeclaredField("TICKET"); typeField.setAccessible(true);
                type = (TicketType)typeField.get(null);
                var storageField = world.getChunkSource().getClass().getDeclaredField("ticketStorage"); storageField.setAccessible(true);
                storage = (TicketStorage)storageField.get(world.getChunkSource());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Pinned native ticket fixture is unavailable", failure); }
            warm = ChunkPos.containing(context.absolutePos(new BlockPos(1, 1, 1)));
            canceled = remote(0);
            for (int i = 1; i <= 6; i++) cold.add(remote(i));
            loadingAtClear = remote(7); queuedAtClear = remote(8);
            watched.add(canceled); watched.addAll(cold); watched.add(loadingAtClear); watched.add(queuedAtClear);
            context.assertTrue(world.getChunkSource().getChunkNow(warm.x(), warm.z()) != null,
                "The native test structure already has an actual published FULL chunk");
            context.assertValueEqual(owned(warm).size(), 0, "The exclusive fixture starts without destination leases");
            for (var position : watched) {
                context.assertTrue(world.getChunkSource().getChunkNow(position.x(), position.z()) == null,
                    "Every remote lease fixture starts genuinely cold: " + position);
                context.assertValueEqual(owned(position).size(), 0, "Remote fixture has no inherited destination ticket");
            }
            watched.add(warm);
            sample = DestinationChunksProbe.begin(server, world.getChunkSource(), type, watched);
            world.getChunkSource().addTicketWithRadius(unrelated, warm, 0);
        }
        ChunkPos remote(int index) { return new ChunkPos(warm.x() + 512 + index * 4, warm.z() + 512); }
        List<Ticket> owned(ChunkPos position) {
            var tickets = storage.getTickets(ChunkPos.pack(position.x(), position.z()));
            return tickets == null ? List.of() : tickets.stream().filter(ticket -> ticket.getType().equals(type)).toList();
        }
        boolean unrelatedPresent() {
            var tickets = storage.getTickets(ChunkPos.pack(warm.x(), warm.z()));
            return tickets != null && tickets.stream().anyMatch(ticket -> ticket.getType().equals(unrelated));
        }
        DestinationChunks.Lease acquire(ChunkPos position) {
            var lease = sample.measure(() -> DestinationChunks.acquire(world, position)); leases.add(lease); return lease;
        }
        boolean ready(DestinationChunks.Lease lease) { return sample.measure(lease::ready); }
        String failure(DestinationChunks.Lease lease) { return sample.measure(lease::failure); }
        void tick() { sample.measure(() -> { DestinationChunks.tick(server); return null; }); }
        void close(DestinationChunks.Lease lease) { sample.measure(() -> { lease.close(); return null; }); }
        void clear() { sample.measure(() -> { DestinationChunks.clear(server); return null; }); }
        void boundedCalls() {
            var perTick = new HashMap<Integer, Integer>();
            for (var start : sample.starts) {
                context.assertValueEqual(start.radius, 0, "Only the requested center receives a FULL loading lease");
                context.assertTrue(!start.position.equals(canceled) && !start.position.equals(queuedAtClear),
                    "Canceled or cleared queued entries never start native generation");
                int count = perTick.merge(start.tick, 1, Integer::sum);
                context.assertTrue(count <= 4, "No actual server tick starts more than four new native destination loads");
            }
            context.assertValueEqual(sample.blockingFutureCalls, 0, "Lease operations never invoke native getChunkFuture/managedBlock");
        }
        @Override public void close() {
            if (closed) return; closed = true;
            try {
                for (var lease : leases) close(lease);
                clear();
            } finally {
                try { world.getChunkSource().removeTicketWithRadius(unrelated, warm, 0); }
                finally { DestinationChunksProbe.end(sample); }
            }
        }
    }

    private static long ticksLeft(Ticket ticket) {
        try { var field = Ticket.class.getDeclaredField("ticksLeft"); field.setAccessible(true); return field.getLong(ticket); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Pinned native ticket countdown is unavailable", failure); }
    }

    @GameTest(maxTicks=280)
    public void nativeFullLeasesRemainNonblockingBoundedSharedAndClean(GameTestHelper c) {
        var fixture = new Fixture(c); boolean scheduled = false;
        try {
            var first = fixture.acquire(fixture.warm);
            c.assertTrue(fixture.ready(first), "An already-published native FULL destination is immediately ready");
            c.assertTrue(fixture.failure(first) == null, "Retaining a warm chunk has no preparation failure");
            c.assertValueEqual(fixture.owned(fixture.warm).size(), 1, "The first lease adds one real native ticket");
            var ticket = fixture.owned(fixture.warm).getFirst();
            // Advance only our own native ticket countdown. This checks native
            // duplicate-ticket renewal without waiting 1,200 game ticks.
            for (int i = 0; i < 100; i++) ticket.decreaseTicksLeft();
            c.assertTrue(ticksLeft(ticket) < fixture.type.timeout(), "The owned native ticket has an aged countdown");
            var second = fixture.acquire(fixture.warm);
            c.assertTrue(fixture.ready(first) && fixture.ready(second), "Overlapping users share the actual published FULL chunk");
            c.assertValueEqual(fixture.owned(fixture.warm).size(), 1, "Overlapping users do not duplicate the native ticket");
            c.assertTrue(fixture.owned(fixture.warm).getFirst() == ticket, "Renewal resets the same native ticket object");
            c.assertValueEqual(ticksLeft(ticket), fixture.type.timeout(), "The native duplicate insertion renews the full retention timeout");
            fixture.close(first);
            c.assertValueEqual(fixture.owned(fixture.warm).size(), 1, "Closing one owner cannot release another owner's native ticket");
            c.assertTrue(fixture.ready(second), "The remaining shared owner stays ready");
            fixture.close(second); fixture.close(second);
            c.assertValueEqual(fixture.owned(fixture.warm).size(), 0, "Only the last close removes the native ticket; repeated close is harmless");
            c.assertTrue(fixture.unrelatedPresent(), "Lease close preserves an unrelated native retention ticket");
            var canceled = fixture.acquire(fixture.canceled); fixture.close(canceled);
            var coldLeases = new ArrayList<DestinationChunks.Lease>();
            for (var position : fixture.cold) {
                var lease = fixture.acquire(position); coldLeases.add(lease);
                c.assertFalse(fixture.ready(lease), "Cold acquire returns pending before native generation begins");
                c.assertTrue(fixture.failure(lease) == null, "A queued cold lease is not a failed destination");
                c.assertValueEqual(fixture.owned(position).size(), 0, "Queued work has not acquired a native loading ticket");
            }
            c.assertValueEqual(fixture.sample.starts.size(), 0, "Acquire and warm sharing never initiate synchronous native generation");
            int startedTick = fixture.server.getTickCount(); fixture.tick();
            c.assertValueEqual(fixture.sample.starts.size(), 4, "The first actual tick starts exactly four genuinely cold FIFO entries");
            for (int i = 0; i < 4; i++) {
                c.assertValueEqual(fixture.sample.starts.get(i).position, fixture.cold.get(i), "Native loading preserves FIFO order after skipping the canceled entry");
                c.assertValueEqual(fixture.sample.starts.get(i).tick, startedTick, "Load observations come from the real current server tick");
            }
            fixture.tick();
            c.assertValueEqual(fixture.sample.starts.size(), 4, "Calling tick again cannot reset the same actual tick's four-load budget");
            fixture.boundedCalls();
            c.runAfterDelay(1, () -> awaitFull(c, fixture, coldLeases, 240)); scheduled = true;
        } finally { if (!scheduled) fixture.close(); }
    }

    private void awaitFull(GameTestHelper c, Fixture fixture, List<DestinationChunks.Lease> coldLeases, int remaining) {
        boolean scheduled = false;
        try {
            fixture.tick(); fixture.boundedCalls(); boolean ready = true;
            for (var lease : coldLeases) {
                c.assertTrue(fixture.failure(lease) == null, "Native cold preparation succeeds: " + fixture.failure(lease));
                ready &= fixture.ready(lease);
            }
            if (!ready) {
                c.assertTrue(remaining > 0, "Real FULL generation finishes inside the bounded native fixture window; native futures="
                    +fixture.sample.starts.stream().map(start -> start.position+":"+start.future.isDone()+":"+start.future.getNow(null)).toList()
                    +"; published="+fixture.cold.stream().map(pos -> pos+":"+(fixture.world.getChunkSource().getChunkNow(pos.x(),pos.z())!=null)).toList());
                c.runAfterDelay(1, () -> awaitFull(c, fixture, coldLeases, remaining - 1)); scheduled = true; return;
            }
            c.assertTrue(fixture.sample.starts.size() >= 4 && fixture.sample.starts.size() <= 6,
                "Native cold work starts once per needed center; published neighbors may be retained directly");
            for (var position : fixture.cold) {
                c.assertTrue(fixture.world.getChunkSource().getChunkNow(position.x(), position.z()) != null,
                    "Readiness requires a real published FULL center: " + position);
                c.assertValueEqual(fixture.owned(position).size(), 1, "Every ready cold destination retains exactly one native ticket");
            }
            c.assertValueEqual(fixture.owned(fixture.canceled).size(), 0, "Canceled queued work never acquired a ticket");
            for (var start : fixture.sample.starts) {
                c.assertTrue(start.future != null && start.future.isDone() && !start.future.isCancelled(),
                    "The actual native range future completes without cancellation");
                var result = start.future.getNow(null);
                c.assertTrue(result instanceof ChunkResult<?> chunkResult && chunkResult.isSuccess(),
                    "A ready cold lease follows successful native ChunkResult completion");
            }
            var shared = fixture.acquire(fixture.cold.getFirst()); fixture.close(coldLeases.getFirst());
            c.assertTrue(fixture.ready(shared), "A second cold-destination owner survives the original owner's close");
            c.assertValueEqual(fixture.owned(fixture.cold.getFirst()).size(), 1, "One native ticket serves overlapping completed cold leases");
            var loading = fixture.acquire(fixture.loadingAtClear); fixture.tick();
            c.assertValueEqual(fixture.sample.starts.stream().filter(start -> start.position.equals(fixture.loadingAtClear)).count(), 1L,
                "The stop-cleanup fixture really starts its own native load");
            c.assertValueEqual(fixture.owned(fixture.loadingAtClear).size(), 1, "In-flight cleanup owns a real native loading ticket");
            var loadingStart=fixture.sample.starts.stream().filter(start -> start.position.equals(fixture.loadingAtClear)).findFirst().orElseThrow();
            c.assertTrue(loadingStart.future!=null&&!loadingStart.future.isDone(),
                "Stop cleanup observes an actual still-incomplete native load, not just a started request");
            c.assertFalse(fixture.ready(loading),"The actual in-flight lease is pending immediately before cleanup");
            var queued = fixture.acquire(fixture.queuedAtClear);
            c.assertFalse(fixture.ready(queued), "Stop cleanup includes an unstarted queued destination");
            fixture.clear();
            c.assertFalse(fixture.ready(shared), "Server cleanup invalidates already-ready shared leases");
            c.assertFalse(fixture.ready(loading), "Server cleanup invalidates an in-flight lease");
            c.assertFalse(fixture.ready(queued), "Server cleanup invalidates queued work");
            c.assertTrue(fixture.failure(shared) != null && fixture.failure(loading) != null && fixture.failure(queued) != null,
                "Cleared leases report a terminal closed preparation instead of remaining pending");
            for (var position : fixture.watched) c.assertValueEqual(fixture.owned(position).size(), 0,
                "Server cleanup releases every owned native destination ticket: " + position);
            for (var start : fixture.sample.starts) c.assertTrue(start.future != null && !start.future.isCancelled(),
                "Clear drops its references without canceling shared native generation work");
            c.assertTrue(fixture.unrelatedPresent(), "Server cleanup preserves unrelated native ticket types");
            fixture.boundedCalls();
            var fresh = fixture.acquire(fixture.cold.getFirst());
            c.assertTrue(fixture.ready(fresh), "A cleared manager can retain a still-published FULL chunk with a fresh lease");
            fixture.close(fresh);
            c.assertValueEqual(fixture.owned(fixture.cold.getFirst()).size(), 0, "The fresh pool has balanced native ticket cleanup");
            fixture.close(); c.succeed();
        } finally { if (!scheduled) fixture.close(); }
    }
}

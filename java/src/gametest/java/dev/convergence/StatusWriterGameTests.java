package dev.convergence;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Real staging files and atomic commits, with gates confined to the background prepare phase. */
public class StatusWriterGameTests {
    static final class Fixture implements AutoCloseable {
        final Path directory=Files.createTempDirectory("infinity-status-test-");
        final Path target=directory.resolve("community-status.json");
        final List<LatestStatusWriter> writers=new ArrayList<>();
        final List<CountDownLatch> releases=new ArrayList<>();
        boolean finished;
        Fixture() throws IOException {}
        LatestStatusWriter writer(LatestStatusWriter.Prepare prepare,AtomicInteger warnings) {
            var writer=new LatestStatusWriter(target,prepare,error->warnings.incrementAndGet());writers.add(writer);return writer;
        }
        CountDownLatch release() { var release=new CountDownLatch(1);releases.add(release);return release; }
        @Override public void close() { releases.forEach(CountDownLatch::countDown);writers.forEach(LatestStatusWriter::close); }
        void finish(GameTestHelper c) throws IOException {
            c.assertTrue(writers.stream().noneMatch(writer->writer.worker.isAlive()),"All owned writer threads stop before fixture cleanup");
            try(var files=Files.list(directory)) {
                c.assertTrue(files.noneMatch(path->path.getFileName().toString().endsWith(".tmp")),"Every successful, failed and cancelled staging file is cleaned");
            }
            Files.deleteIfExists(target);Files.delete(directory);finished=true;c.succeed();
        }
    }
    @FunctionalInterface interface Checks { void run() throws IOException; }
    void watch(GameTestHelper c,Fixture fixture,Checks checks) {
        c.onEachTick(()->{
            if(fixture.finished)return;
            try {
                c.assertTrue(c.getTick()<190,"The asynchronous writer completes without blocking a test tick");checks.run();
            } catch(IOException error) { fixture.close();throw new IllegalStateException("Status fixture I/O failed",error); }
            catch(RuntimeException|Error error) { fixture.close();throw error; }
        });
    }
    static void prepare(Path temporary,LatestStatusWriter.Snapshot snapshot) throws IOException {
        Files.writeString(temporary,CommunityServer.GSON.toJson(snapshot));
    }
    static void gate(CountDownLatch release) {
        // An old prepared write deliberately survives shutdown's interruption so
        // the test reaches the final closed check after the replacement commits.
        boolean interrupted=false;
        while(true)try { release.await();break; }catch(InterruptedException error) { interrupted=true; }
        if(interrupted)Thread.currentThread().interrupt();
    }
    static LatestStatusWriter.Snapshot snapshot(long updated,List<LatestStatusWriter.Player> players) {
        return new LatestStatusWriter.Snapshot(updated,players,12.5);
    }
    static long updated(Path target) throws IOException {
        return Files.exists(target)?JsonParser.parseString(Files.readString(target)).getAsJsonObject().get("updated").getAsLong():-1;
    }
    static long temporaryCount(Path directory) throws IOException {
        try(var files=Files.list(directory)) { return files.filter(path->path.getFileName().toString().endsWith(".tmp")).count(); }
    }

    @GameTest(maxTicks=200) public void blockedStatusWritesLeaveTicksResponsiveAndOnlyPrepareTheNewestPendingSnapshot(GameTestHelper c) throws IOException {
        var fixture=new Fixture();var entered=new CountDownLatch(1);var release=fixture.release();
        var prepared=new AtomicInteger();var warnings=new AtomicInteger();
        var players=new ArrayList<>(List.of(new LatestStatusWriter.Player("Original","saved-uuid","CREATIVE","Ultra")));
        var writer=fixture.writer((temporary,snapshot)->{
            prepare(temporary,snapshot);
            if(prepared.incrementAndGet()==1) { entered.countDown();gate(release); }
        },warnings);
        c.assertTrue(writer.worker.isDaemon(),"The single owned status worker is a daemon");
        writer.submit(snapshot(1,players));
        int[] stage={0},startTick={0};
        watch(c,fixture,()->{
            if(stage[0]==0&&entered.getCount()==0) {
                for(int i=2;i<=1000;i++)c.assertTrue(writer.submit(snapshot(i,players)),"Submission accepts the latest immutable snapshot while prepare is blocked");
                players.clear();
                c.assertValueEqual(writer.pendingCount(),1,"A thousand submissions retain just one pending snapshot");
                startTick[0]=c.getLevel().getServer().getTickCount();stage[0]=1;
            } else if(stage[0]==1&&c.getLevel().getServer().getTickCount()>=startTick[0]+3) {
                c.assertValueEqual(prepared.get(),1,"The blocked writer neither starts extra workers nor prepares queued stale snapshots");
                c.assertFalse(Files.exists(fixture.target),"The prepared file is not visible before its atomic commit");
                release.countDown();stage[0]=2;
            } else if(stage[0]==2&&updated(fixture.target)==1000) {
                c.assertValueEqual(prepared.get(),2,"Only the in-flight and newest snapshots are prepared");
                c.assertValueEqual(warnings.get(),0,"A blocked healthy writer produces no failures");
                var status=JsonParser.parseString(Files.readString(fixture.target)).getAsJsonObject();
                c.assertValueEqual(status.keySet(),java.util.Set.of("updated","players","tick_ms"),"The dashboard schema is unchanged");
                c.assertValueEqual(status.get("tick_ms").getAsDouble(),12.5,"Captured tick duration is preserved");
                var player=status.getAsJsonArray("players").get(0).getAsJsonObject();
                c.assertValueEqual(player.keySet(),java.util.Set.of("name","uuid","mode","rank"),"Player schema is unchanged");
                c.assertValueEqual(player.get("name").getAsString(),"Original","Mutating the source list cannot change submitted player snapshots");
                writer.close();stage[0]=3;
            } else if(stage[0]==3&&!writer.worker.isAlive())fixture.finish(c);
        });
    }

    @GameTest(maxTicks=200) public void statusWriteFailuresCleanStagingRetryAndWarnOnceUntilRecovery(GameTestHelper c) throws IOException {
        var fixture=new Fixture();var attempts=new AtomicInteger();var warnings=new AtomicInteger();
        var writer=fixture.writer((temporary,snapshot)->{
            prepare(temporary,snapshot);attempts.incrementAndGet();
            if(snapshot.updated()<=2||snapshot.updated()==4)throw new IOException("Controlled prepare failure");
        },warnings);
        writer.submit(snapshot(1,List.of()));int[] stage={0};
        watch(c,fixture,()->{
            boolean waiting=writer.worker.getState()==Thread.State.WAITING;
            if(stage[0]==0&&attempts.get()==1&&waiting) {
                c.assertValueEqual(warnings.get(),1,"The first real I/O failure is reported");
                c.assertValueEqual(temporaryCount(fixture.directory),0L,"A failed prepare does not leak its real staging file");
                writer.submit(snapshot(2,List.of()));stage[0]=1;
            } else if(stage[0]==1&&attempts.get()==2&&waiting) {
                c.assertValueEqual(warnings.get(),1,"Persistent failures warn once rather than on every submitted snapshot");
                writer.submit(snapshot(3,List.of()));stage[0]=2;
            } else if(stage[0]==2&&updated(fixture.target)==3&&waiting) {
                c.assertValueEqual(attempts.get(),3,"The next snapshot retries and commits after prepare recovers");
                writer.submit(snapshot(4,List.of()));stage[0]=3;
            } else if(stage[0]==3&&attempts.get()==4&&waiting) {
                c.assertValueEqual(warnings.get(),2,"A new failure after successful recovery is reported");
                c.assertValueEqual(updated(fixture.target),3L,"A failed replacement preserves the last complete status");
                writer.submit(snapshot(5,List.of()));stage[0]=4;
            } else if(stage[0]==4&&updated(fixture.target)==5) {
                writer.close();stage[0]=5;
            } else if(stage[0]==5&&!writer.worker.isAlive())fixture.finish(c);
        });
    }

    @GameTest(maxTicks=200) public void closedPreparedWriterCannotOverwriteTheReplacementServerStatus(GameTestHelper c) throws IOException {
        var fixture=new Fixture();var entered=new CountDownLatch(1);var release=fixture.release();var warnings=new AtomicInteger();
        var oldTemporary=new AtomicReference<Path>();var newTemporary=new AtomicReference<Path>();
        var old=fixture.writer((temporary,snapshot)->{prepare(temporary,snapshot);oldTemporary.set(temporary);entered.countDown();gate(release);},warnings);
        old.submit(snapshot(1,List.of()));int[] stage={0};LatestStatusWriter[] replacement={null};
        watch(c,fixture,()->{
            if(stage[0]==0&&entered.getCount()==0) {
                old.close();
                c.assertFalse(old.submit(snapshot(2,List.of())),"A stopped server cannot enqueue another status");
                c.assertValueEqual(old.pendingCount(),0,"Shutdown clears its pending snapshot without waiting for blocked prepare");
                replacement[0]=fixture.writer((temporary,snapshot)->{newTemporary.set(temporary);prepare(temporary,snapshot);},warnings);
                replacement[0].submit(snapshot(99,List.of()));stage[0]=1;
            } else if(stage[0]==1&&updated(fixture.target)==99) {
                c.assertTrue(Files.exists(oldTemporary.get()),"The old server still has a prepared snapshot when the replacement commits");
                c.assertFalse(oldTemporary.get().equals(newTemporary.get()),"Restarted writers never share a staging path");
                release.countDown();stage[0]=2;
            } else if(stage[0]==2&&!old.worker.isAlive()) {
                c.assertValueEqual(updated(fixture.target),99L,"A closed writer cannot commit after the replacement server begins");
                c.assertFalse(Files.exists(oldTemporary.get()),"The discarded old snapshot is cleaned");
                c.assertValueEqual(warnings.get(),0,"Normal shutdown is not reported as an I/O failure");
                replacement[0].close();stage[0]=3;
            } else if(stage[0]==3&&!replacement[0].worker.isAlive())fixture.finish(c);
        });
    }
}

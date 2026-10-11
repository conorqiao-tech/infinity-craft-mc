package dev.convergence;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** One status write in progress and one replaceable pending snapshot per server. */
final class LatestStatusWriter implements AutoCloseable {
    record Player(String name,String uuid,String mode,String rank) {}
    record Snapshot(long updated,List<Player> players,double tick_ms) {
        Snapshot { players=List.copyOf(players); }
    }
    @FunctionalInterface interface Prepare {
        void write(Path temporary,Snapshot snapshot) throws IOException;
    }
    private final Path target;
    private final Prepare prepare;
    private final Consumer<IOException> warning;
    private final Object pendingGuard=new Object(),commitGuard=new Object();
    private Snapshot pending;
    private volatile boolean closed;
    private boolean warned;
    final Thread worker;

    LatestStatusWriter(Path target) {
        this(target,(temporary,snapshot)->Files.writeString(temporary,CommunityServer.GSON.toJson(snapshot)),
            error->LoggerFactory.getLogger("Infinity status").warn("Could not update host-panel status. The next snapshot will retry.",error));
    }
    LatestStatusWriter(Path target,Prepare prepare,Consumer<IOException> warning) {
        this.target=target.toAbsolutePath();this.prepare=Objects.requireNonNull(prepare);this.warning=Objects.requireNonNull(warning);
        worker=new Thread(this::run,"Infinity status writer");worker.setDaemon(true);worker.start();
    }
    boolean submit(Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        synchronized(pendingGuard) {
            if(closed)return false;
            pending=snapshot;pendingGuard.notifyAll();return true;
        }
    }
    int pendingCount() { synchronized(pendingGuard) { return pending==null?0:1; } }
    private Snapshot next() throws InterruptedException {
        synchronized(pendingGuard) {
            while(!closed&&pending==null)pendingGuard.wait();
            if(closed)return null;
            var snapshot=pending;pending=null;return snapshot;
        }
    }
    private void warn(IOException error) {
        if(!warned) { warned=true;warning.accept(error); }
    }
    private void run() {
        while(!closed) {
            Snapshot snapshot;
            try { snapshot=next(); }
            catch(InterruptedException error) {
                if(closed)return;
                Thread.currentThread().interrupt();throw new IllegalStateException("Status writer was unexpectedly interrupted",error);
            }
            if(snapshot==null)return;
            Path temporary=null;
            try {
                Files.createDirectories(target.getParent());
                // A stopped writer can never share the next server's staging file.
                temporary=Files.createTempFile(target.getParent(),"."+target.getFileName()+".",".tmp");
                prepare.write(temporary,snapshot);
                // Submission uses a different guard and never waits for disk I/O.
                // Only shutdown and the final atomic rename share this narrow guard.
                synchronized(commitGuard) {
                    if(closed)return;
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }
                warned=false;
            } catch(IOException error) {
                if(!(closed&&error instanceof ClosedByInterruptException))warn(error);
            } finally {
                if(temporary!=null)try { Files.deleteIfExists(temporary); }
                catch(IOException error) { warn(error); }
            }
        }
    }
    @Override public void close() {
        synchronized(commitGuard) { closed=true; }
        synchronized(pendingGuard) { pending=null;pendingGuard.notifyAll(); }
        worker.interrupt();
    }
}

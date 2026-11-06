package dev.convergence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Helper ownership files. A replacement writes and fsyncs a temporary file,
 * then renames it atomically. Durability is PROVEN only when the directory is
 * also synced; where directory sync is unavailable the replacement still
 * happens but stays UNPROVEN, and callers must not let it authorize deletion.
 * Byte checks detect some substitutions, but do not exclude another writer or
 * prove that a roster was unchanged before the first check. PROVEN describes
 * this low-level storage sequence under the trusted-writer assumption only.
 * A failure reports whether this operation may have changed the target.
 */
final class HelperPersistence {
    // The roster parser uses the same bound; never hash an unbounded file.
    static final int MAX_EVIDENCE_BYTES = 65_536;
    /** Filesystem steps. Production uses NATIVE; tests may fail one exact real step. */
    interface Io {
        void copy(Path from, Path to) throws IOException;
        void writeDurably(Path path, byte[] bytes) throws IOException;
        void atomicMove(Path from, Path to) throws IOException;
        void delete(Path path) throws IOException;
        void syncFile(Path path) throws IOException;
        void syncDirectory(Path directory) throws IOException;
    }
    /** Preserve existing POSIX mode bits, or use owner-only mode for a new target. */
    private static java.nio.file.attribute.FileAttribute<?>[] stagingAttributes(Path temporary) throws IOException {
        Path parent = temporary.toAbsolutePath().getParent();
        if (Files.getFileAttributeView(parent, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null)
            return new java.nio.file.attribute.FileAttribute<?>[0];
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        String name = temporary.getFileName().toString();
        if (name.endsWith(".tmp")) {
            Path target = temporary.resolveSibling(name.substring(0, name.length()-4));
            try {
                var view = Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view != null) {
                    var attributes = view.readAttributes();
                    if (attributes.isRegularFile()) permissions = attributes.permissions();
                }
            } catch (java.nio.file.NoSuchFileException absent) {
                // A genuinely new roster or marker starts owner-only on POSIX.
            }
        }
        return new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(permissions)};
    }
    static final Io NATIVE = new Io() {
        // Never follow a linked source or truncate a pre-existing destination.
        // This bounded protection does not exclude later replacement by another writer.
        @Override public void copy(Path from, Path to) throws IOException {
            var basic = Files.readAttributes(from, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!basic.isRegularFile()) throw new IOException("Helper copy source is not a regular file");
            var posix = Files.getFileAttributeView(from, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            java.nio.file.attribute.FileAttribute<?>[] attributes = posix == null ? new java.nio.file.attribute.FileAttribute<?>[0]
                : new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(posix.readAttributes().permissions())};
            try (var input = Files.newInputStream(from, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var output = java.nio.channels.Channels.newOutputStream(Files.newByteChannel(to,
                     java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes))) {
                input.transferTo(output);
            }
        }
        @Override public void writeDurably(Path path, byte[] bytes) throws IOException {
            // The earlier presence checks are not a lock: another writer may
            // create evidence or a symlink before this open. CREATE_NEW makes
            // absence and creation one operation and never follows an existing
            // symlink. It does not prevent a later swap before atomicMove.
            try (var channel = FileChannel.open(path, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), stagingAttributes(path))) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }
        // No copy-and-delete fallback: a non-atomic move can leave a partial target.
        @Override public void atomicMove(Path from, Path to) throws IOException {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        @Override public void delete(Path path) throws IOException { Files.delete(path); }
        @Override public void syncFile(Path path) throws IOException {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        }
        // Supported on Linux and macOS; Windows refuses to open a directory this way.
        @Override public void syncDirectory(Path directory) throws IOException {
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
    };

    enum Durability { PROVEN, UNPROVEN }
    /** {@code mayHaveChanged} is false only when the target path was never touched. */
    static final class Failure extends IOException {
        final boolean mayHaveChanged;
        Failure(String message, boolean mayHaveChanged, Throwable cause) { super(message, cause); this.mayHaveChanged = mayHaveChanged; }
    }

    final Io io;
    HelperPersistence(Io io) { this.io = io; }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 is unavailable", e); }
    }

    /** Detect changed regular-file bytes; this cannot exclude a concurrent writer. */
    private static byte[] digest(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Helper evidence is not a regular file");
        var hash = sha256();
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192]; int count, total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_EVIDENCE_BYTES) throw new IOException("Helper evidence exceeds the roster size limit");
                hash.update(buffer, 0, count);
            }
        }
        return hash.digest();
    }

    private static void requireDigest(Path file, byte[] expected) throws IOException {
        if (!MessageDigest.isEqual(digest(file), expected)) throw new IOException("Helper evidence bytes changed");
    }

    static Path previousFile(Path target) { return target.resolveSibling(target.getFileName() + ".previous"); }
    static Path temporaryFile(Path target) { return target.resolveSibling(target.getFileName() + ".tmp"); }
    static Path previousTemporaryFile(Path target) { return temporaryFile(previousFile(target)); }
    static Path previousPublishTemporaryFile(Path target) { return target.resolveSibling(target.getFileName() + ".previous.publish.tmp"); }
    /**
     * Present while a write that adds removal proof is in flight, and after any
     * failed or unproven ownership save. Never overwritten; removed only after
     * a guarded roster write is proven durable.
     */
    static Path recoveryMarker(Path roster) { return roster.resolveSibling(roster.getFileName() + ".recovery-required"); }
    static Path recoveryTemporary(Path roster) { return temporaryFile(recoveryMarker(roster)); }
    /** Independent evidence retained until removal of the original guard is synced. */
    static Path retirementMarker(Path roster) { return roster.resolveSibling(roster.getFileName() + ".recovery-retiring"); }
    static Path retirementTemporary(Path roster) { return temporaryFile(retirementMarker(roster)); }

    /** With {@code requireProof}, storage that cannot sync its directory refuses before the rename. */
    Durability replace(Path target, byte[] bytes, boolean keepPrevious, boolean requireProof) throws Failure {
        Path directory = target.toAbsolutePath().getParent();
        Path temporary = temporaryFile(target);
        boolean backupStaged = false, backupNeeded, guardPresent = false;
        byte[] intendedDigest, previousDigest = null;
        try {
            if (bytes.length > MAX_EVIDENCE_BYTES) throw new IOException("Helper evidence exceeds the roster size limit");
            intendedDigest = sha256().digest(bytes);
            Files.createDirectories(directory);
            boolean exists = keepPrevious && Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (keepPrevious && !exists && !Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Whether the roster exists cannot be determined");
            backupNeeded = keepPrevious && exists;
            if (keepPrevious) {
                Path marker = recoveryMarker(target);
                guardPresent = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
                if ((!guardPresent && !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS))
                    || (guardPresent && !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Recovery guard state cannot be determined");
                if (guardEvidenceChanged(target, guardPresent))
                    throw new IOException("Competing recovery evidence requires inspection");
            }
        } catch (IOException | RuntimeException e) {
            throw new Failure("Could not prepare " + target.getFileName() + "; it was not touched", false, e);
        }
        if (backupNeeded) {
            if (unfinishedPreviousCopy(target) || unsafePreviousFile(target))
                throw new Failure("Previous roster evidence requires inspection", false, null);
        }
        try { io.writeDurably(temporary, bytes); }
        catch (IOException | RuntimeException e) {
            throw new Failure("Could not prepare " + target.getFileName() + "; it was not touched", false, e);
        }
        if (backupNeeded) {
            Path backupTemporary = previousTemporaryFile(target);
            try {
                previousDigest = digest(target);
                io.copy(target, backupTemporary); io.syncFile(backupTemporary);
                requireDigest(backupTemporary, previousDigest);
                requireDigest(target, previousDigest);
                backupStaged = true;
            }
            catch (IOException | RuntimeException e) {
                throw new Failure("Could not stage the previous roster; the roster and prior backup were not replaced", false, e);
            }
        }
        // Probe before touching the target, so an unsupported directory sync
        // is known before the rename rather than mistaken for a failed one.
        boolean provable;
        try { io.syncDirectory(directory); provable = true; }
        catch (IOException | RuntimeException e) {
            if (requireProof) throw new Failure("Directory sync is unavailable for " + target.getFileName() + "; it was not touched", false, e);
            provable = false;
        }
        if (keepPrevious && guardEvidenceChanged(target, guardPresent))
            throw new Failure("Recovery evidence changed before the roster was replaced", false, null);
        try {
            requireDigest(temporary, intendedDigest);
            if (backupStaged) {
                requireDigest(target, previousDigest);
                requireDigest(previousTemporaryFile(target), previousDigest);
            }
        } catch (IOException | RuntimeException e) {
            throw new Failure("Staged helper evidence changed before replacement", false, e);
        }
        try { io.atomicMove(temporary, target); }
        catch (AtomicMoveNotSupportedException e) {
            throw new Failure("Atomic rename is unavailable for " + target.getFileName() + "; it was not touched", false, e);
        } catch (IOException | RuntimeException e) {
            throw new Failure("Could not prove whether " + target.getFileName() + " was replaced", true, e);
        }
        try {
            requireDigest(target, intendedDigest);
            if (backupStaged) requireDigest(previousTemporaryFile(target), previousDigest);
        } catch (IOException | RuntimeException e) {
            throw new Failure("Published helper evidence changed during replacement", true, e);
        }
        if (provable) {
            try { io.syncDirectory(directory); }
            catch (IOException | RuntimeException e) {
                throw new Failure(target.getFileName() + " was renamed but its directory could not be synced", true, e);
            }
        }
        // Keep the prior .previous untouched until the roster was replaced.
        // If backup promotion fails, the roster has already changed, so the
        // caller must treat the entire operation as uncertain and freeze.
        if (backupStaged) {
            if (competingBackupEvidence(target, false, guardPresent))
                throw new Failure("The roster was replaced but backup evidence changed before promotion", true, null);
            if (!provable) {
                try {
                    io.atomicMove(previousTemporaryFile(target), previousFile(target));
                    requireDigest(target, intendedDigest); requireDigest(previousFile(target), previousDigest);
                }
                catch (IOException | RuntimeException e) {
                    throw new Failure("The roster was replaced but its previous copy could not be proven", true, e);
                }
                return Durability.UNPROVEN;
            }
            // Keep the staged prior roster as a witness while a separate copy
            // is promoted and synced. A crash during promotion leaves a note.
            try {
                io.copy(previousTemporaryFile(target), previousPublishTemporaryFile(target));
                io.syncFile(previousPublishTemporaryFile(target)); io.syncDirectory(directory);
                requireDigest(previousTemporaryFile(target), previousDigest);
                requireDigest(previousPublishTemporaryFile(target), previousDigest);
                if (competingBackupEvidence(target, true, guardPresent))
                    throw new IOException("Backup evidence changed during promotion");
                io.atomicMove(previousPublishTemporaryFile(target), previousFile(target)); io.syncDirectory(directory);
                requireDigest(target, intendedDigest);
                requireDigest(previousTemporaryFile(target), previousDigest);
                requireDigest(previousFile(target), previousDigest);
                if (competingBackupEvidence(target, false, guardPresent))
                    throw new IOException("Backup evidence changed after promotion");
                io.delete(previousTemporaryFile(target)); io.syncDirectory(directory);
            } catch (IOException | RuntimeException e) {
                throw new Failure("The roster was replaced but its previous copy could not be proven", true, e);
            }
        }
        try {
            requireDigest(target, intendedDigest);
            if (backupStaged) requireDigest(previousFile(target), previousDigest);
        } catch (IOException | RuntimeException e) {
            throw new Failure("Published helper evidence changed before completion", true, e);
        }
        return provable ? Durability.PROVEN : Durability.UNPROVEN;
    }

    /** Makes an already present roster durable as read, or reports that it cannot. */
    boolean proveExisting(Path target) {
        try {
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return true;
            io.syncFile(target); io.syncDirectory(target.toAbsolutePath().getParent());
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }

    /**
     * Why a roster that is not provably present must not be read as a fresh,
     * empty world, or null when it is present or nothing shows a prior roster.
     * Existence that cannot be determined counts as possibly present. Only
     * reads paths: never restores, renames or deletes anything.
     */
    static String absentRosterProblem(Path roster) {
        if (Files.exists(roster, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.notExists(roster, LinkOption.NOFOLLOW_LINKS))
            return "whether " + roster.getFileName() + " exists cannot be determined";
        for (Path evidence : new Path[]{previousFile(roster), recoveryMarker(roster), recoveryTemporary(roster),
                retirementMarker(roster), retirementTemporary(roster), temporaryFile(roster), previousTemporaryFile(roster),
                previousPublishTemporaryFile(roster)})
            if (!Files.notExists(evidence, LinkOption.NOFOLLOW_LINKS))
                return roster.getFileName() + " is missing but " + evidence.getFileName() + " may exist";
        return null;
    }

    /** A leftover temporary roster may contain an interrupted write; never overwrite it. */
    static boolean unfinishedRosterWrite(Path roster) {
        try { return !Files.notExists(temporaryFile(roster), LinkOption.NOFOLLOW_LINKS) || unfinishedPreviousCopy(roster); }
        catch (SecurityException denied) { return true; }
    }

    static boolean unfinishedPreviousCopy(Path roster) {
        try { return !Files.notExists(previousTemporaryFile(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(previousPublishTemporaryFile(roster), LinkOption.NOFOLLOW_LINKS); }
        catch (SecurityException denied) { return true; }
    }

    /** Bounded checks only; they do not exclude a concurrent or untrusted writer. */
    private static boolean competingBackupEvidence(Path roster, boolean publishExpected, boolean guardPresent) {
        try {
            Path publish = previousPublishTemporaryFile(roster);
            return !Files.isRegularFile(previousTemporaryFile(roster), LinkOption.NOFOLLOW_LINKS)
                || (publishExpected ? !Files.isRegularFile(publish, LinkOption.NOFOLLOW_LINKS) : !Files.notExists(publish, LinkOption.NOFOLLOW_LINKS))
                || !Files.notExists(temporaryFile(roster), LinkOption.NOFOLLOW_LINKS)
                || guardEvidenceChanged(roster, guardPresent)
                || unsafePreviousFile(roster);
        } catch (SecurityException denied) { return true; }
    }

    /** Presence/type checks are not inode compare-and-swap or writer exclusion. */
    private static boolean guardEvidenceChanged(Path roster, boolean guardPresent) {
        try {
            Path marker = recoveryMarker(roster);
            return (guardPresent ? !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) : !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS))
                || !Files.notExists(recoveryTemporary(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(retirementMarker(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(retirementTemporary(roster), LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException denied) { return true; }
    }

    static boolean unsafePreviousFile(Path roster) {
        try {
            Path previous = previousFile(roster);
            return !Files.notExists(previous, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException denied) { return true; }
    }

    /** An unfinished marker write is evidence too. Never overwrite or ignore it. */
    private static boolean recoveryNotePresent(Path roster) {
        try {
            return !Files.notExists(recoveryMarker(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(recoveryTemporary(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(retirementMarker(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(retirementTemporary(roster), LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException denied) { return true; }
    }

    static boolean recoveryRequired(Path roster) {
        return unfinishedRosterWrite(roster) || unsafePreviousFile(roster) || recoveryNotePresent(roster);
    }

    /** Check for a regular marker and no competing temporary note; this is not a writer-exclusion proof. */
    static boolean guardIntact(Path roster) {
        try {
            return !unfinishedRosterWrite(roster) && !unsafePreviousFile(roster)
                && Files.isRegularFile(recoveryMarker(roster), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(recoveryTemporary(roster), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(retirementMarker(roster), LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(retirementTemporary(roster), LinkOption.NOFOLLOW_LINKS);
        } catch (SecurityException denied) { return false; }
    }

    /** Best-effort note after a failure; never overwrites an existing marker. */
    void markRecoveryRequired(Path roster, String reason) throws Failure {
        if (recoveryNotePresent(roster)) return;
        replace(recoveryMarker(roster), (reason + "\n").getBytes(StandardCharsets.UTF_8), false, false);
    }

    /**
     * Establishes a provably durable marker before a write that adds removal
     * proof. A clean failure ({@code mayHaveChanged == false}) left no marker;
     * any other failure may have left one. An existing marker is never reused.
     */
    void beginGuard(Path roster, String reason) throws Failure {
        if (recoveryRequired(roster)) throw new Failure("Recovery evidence already exists beside " + roster, true, null);
        replace(recoveryMarker(roster), (reason + "\n").getBytes(StandardCharsets.UTF_8), false, true);
    }

    /**
     * Retires the marker only after the guarded roster write was proven durable.
     * Establish a second durable note before deleting the first. If the first
     * deletion or its directory sync fails, that note remains for restart. Only
     * after that sync succeeds may the second note be deleted. This does not
     * establish exclusive writer ownership or native entity-save durability.
     */
    void endGuard(Path roster) throws Failure {
        Path marker = recoveryMarker(roster), retiring = retirementMarker(roster), directory = marker.toAbsolutePath().getParent();
        try {
            if (!guardIntact(roster)) throw new IOException("Recovery guard is missing or has competing temporary evidence");
            replace(retiring, "Guard retirement is in progress; preserve the roster and matching world for review.\n".getBytes(StandardCharsets.UTF_8), false, true);
            if (unfinishedRosterWrite(roster) || unsafePreviousFile(roster)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(retiring, LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(recoveryTemporary(roster), LinkOption.NOFOLLOW_LINKS)
                || !Files.notExists(retirementTemporary(roster), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Recovery evidence changed during guard retirement");
            io.delete(marker); io.syncDirectory(directory);
            io.delete(retiring); io.syncDirectory(directory);
        }
        catch (IOException | RuntimeException e) { throw new Failure("Could not complete recovery guard retirement", true, e); }
    }
}

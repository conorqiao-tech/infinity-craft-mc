"""A startup backup must check the lease it borrows from its launcher."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community
import server as launcher


@unittest.skipUnless(os.name == "posix", "requires POSIX advisory leases")
class BorrowedBackupLeaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        (self.world / "level.dat").write_bytes(b"synthetic level")
        (self.world / "region").mkdir()
        (self.world / "region/r.0.0.mca").write_bytes(b"synthetic region")
        self.props = {"level-name": "world"}

    def lease(self):
        descriptor = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(descriptor, str(os.getpid()).encode("ascii"))
        return descriptor

    def holder(self):
        code = ("import fcntl,sys; f=open(sys.argv[1],'w'); "
                "fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB); "
                "print('LOCKED',flush=True); sys.stdin.readline()")
        child = subprocess.Popen([sys.executable, "-u", "-c", code, str(self.root / "launcher.lock")],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(child.stdout.readline().strip(), "LOCKED")
        self.addCleanup(self.stop_holder, child)
        return child

    def stop_holder(self, child):
        if child.poll() is None:
            child.stdin.write("\n")
            child.stdin.flush()
            _, error = child.communicate(timeout=10)
            self.assertEqual(child.returncode, 0, error)

    def other_process_can_lock(self):
        code = ("import fcntl,sys; f=open(sys.argv[1],'rb'); "
                "\ntry: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)"
                "\nexcept BlockingIOError: sys.exit(42)\nelse: sys.exit(0)")
        return subprocess.run([sys.executable, "-c", code, str(self.root / "launcher.lock")],
                              timeout=10).returncode == 0

    def test_claim_without_lease_is_refused_while_another_launcher_holds_it(self):
        self.holder()
        with patch.object(community, "world_folder", wraps=community.world_folder) as world:
            with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                community.backup(self.root, self.props, locked=True)
        world.assert_not_called()
        self.assertFalse((self.root / "backups").exists())

    def test_invalid_or_unrelated_descriptor_is_refused_before_world_lookup(self):
        (self.root / "launcher.lock").write_bytes(b"synthetic lock")
        other = os.open(self.world / "level.dat", os.O_RDONLY)
        try:
            with patch.object(community, "world_folder", wraps=community.world_folder) as world:
                with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                    community.backup(self.root, self.props, locked=True, lease_fd=other)
            world.assert_not_called()
        finally:
            os.close(other)
        self.assertFalse((self.root / "backups").exists())

    def test_descriptor_cannot_borrow_a_lock_held_by_another_process(self):
        self.holder()
        other = os.open(self.root / "launcher.lock", os.O_RDONLY)
        try:
            with patch.object(community, "world_folder", wraps=community.world_folder) as world:
                with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                    community.backup(self.root, self.props, locked=True, lease_fd=other)
            world.assert_not_called()
        finally:
            os.close(other)
        self.assertFalse((self.root / "backups").exists())

    def test_borrowed_descriptor_stays_held_while_archiving(self):
        descriptor = self.lease()
        real_write = community.write_backup_file
        observed = []
        def write(*args, **kwargs):
            observed.append(self.other_process_can_lock())
            return real_write(*args, **kwargs)
        try:
            with patch.object(community, "write_backup_file", side_effect=write):
                name = community.backup(self.root, self.props, locked=True, lease_fd=descriptor)
            self.assertTrue(observed)
            self.assertEqual(set(observed), {False})
            self.assertFalse(self.other_process_can_lock())
            os.fstat(descriptor)  # Backup must not close the caller's descriptor.
            with zipfile.ZipFile(self.root / "backups" / name) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read("world/region/r.0.0.mca"), b"synthetic region")
        finally:
            os.close(descriptor)
        self.assertTrue(self.other_process_can_lock())

    def test_borrowed_lock_substitution_before_publication_is_refused(self):
        descriptor = self.lease()
        old, replacement = self.root / "old-lock", self.root / "launcher.lock"
        real_sync = community.sync_file
        def substitute(stream):
            real_sync(stream)
            replacement.rename(old)
            replacement.write_bytes(b"synthetic replacement")
        try:
            with patch.object(community, "sync_file", side_effect=substitute):
                with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                    community.backup(self.root, self.props, locked=True, lease_fd=descriptor)
            self.assertTrue(old.is_file())
            self.assertEqual(replacement.read_bytes(), b"synthetic replacement")
            self.assertEqual(list((self.root / "backups").glob("*.zip")), [])
        finally:
            os.close(descriptor)


if __name__ == "__main__":
    unittest.main()

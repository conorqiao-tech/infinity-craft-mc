"""Interrupted synthetic recovery must borrow the launcher's lease."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == "posix", "requires POSIX advisory locks")
class BorrowedRecoveryLeaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        (self.world / "session.lock").write_bytes(b"synthetic native lock")
        (self.world / "level.dat").write_bytes(b"synthetic level")
        self.relative = "dimensions/minecraft/overworld/region/r.0.0.mca"
        self.saved = self.root / "backups/Terrain-Refresh-synthetic/terrain" / self.relative
        self.saved.parent.mkdir(parents=True)
        self.saved.write_bytes(b"synthetic old terrain")
        self.target = self.world / self.relative
        self.journal = self.root / refresh.JOURNAL
        self.journal.write_text(json.dumps({"format": 1, "state": "moving", "world": "world",
            "rollback": "Terrain-Refresh-synthetic", "moved_paths": [self.relative], "kept_files": {}}))
        self.journal_before = self.journal.read_bytes()

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

    def assert_unchanged(self):
        self.assertEqual(self.journal.read_bytes(), self.journal_before)
        self.assertEqual(self.saved.read_bytes(), b"synthetic old terrain")
        self.assertFalse(self.target.exists())

    def blocked(self, path, method):
        code = ("import fcntl,sys; f=open(sys.argv[1],'r+b'); "
                "\ntry: getattr(fcntl,sys.argv[2])(f,fcntl.LOCK_EX|fcntl.LOCK_NB)"
                "\nexcept BlockingIOError: sys.exit(42)\nelse: sys.exit(0)")
        return subprocess.run([sys.executable, '-c', code, str(path), method], timeout=10).returncode == 42

    def test_recovery_without_lease_refuses_while_another_launcher_holds_it(self):
        self.holder()
        with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
            refresh.recover(self.root)
        self.assert_unchanged()

    def test_recovery_cannot_borrow_a_lease_held_by_another_process(self):
        self.holder()
        descriptor = os.open(self.root / "launcher.lock", os.O_RDONLY)
        try:
            with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                refresh.recover(self.root, lease_fd=descriptor)
            self.assert_unchanged()
        finally:
            os.close(descriptor)

    def test_recovery_holds_both_cooperative_locks_during_a_move(self):
        descriptor = self.lease()
        original = refresh.move
        observed = []
        def move(source, target):
            observed.append((self.blocked(self.root / "launcher.lock", "flock"),
                             self.blocked(self.world / "session.lock", "lockf")))
            return original(source, target)
        try:
            with patch.object(refresh, "move", side_effect=move):
                refresh.recover(self.root, lease_fd=descriptor)
            self.assertEqual(observed, [(True, True)])
            self.assertEqual(self.target.read_bytes(), b"synthetic old terrain")
            self.assertFalse(self.journal.exists())
            os.fstat(descriptor)
        finally:
            os.close(descriptor)

    def test_observed_lease_swap_before_first_move_keeps_journal_and_terrain(self):
        descriptor = self.lease()
        original = community.acquire_world_session_lock
        def acquire(world):
            native = original(world)
            actor = ("from pathlib import Path; import os,sys; r=Path(sys.argv[1]); "
                     "os.rename(r/'launcher.lock',r/'original-lock'); "
                     "(r/'launcher.lock').write_bytes(b'synthetic replacement')")
            subprocess.run([sys.executable, '-c', actor, str(self.root)], check=True, timeout=10)
            return native
        try:
            with patch.object(community, "acquire_world_session_lock", side_effect=acquire):
                with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                    refresh.recover(self.root, lease_fd=descriptor)
            self.assert_unchanged()
            self.assertEqual((self.root / "launcher.lock").read_bytes(), b"synthetic replacement")
            self.assertTrue((self.root / "original-lock").exists())
        finally:
            os.close(descriptor)

    def test_observed_swap_before_journal_retirement_keeps_recovery_evidence(self):
        descriptor = self.lease()
        original = refresh.atomic_json
        def write(path, record):
            original(path, record)
            if Path(path).name == "MANIFEST.json":
                actor = ("from pathlib import Path; import os,sys; r=Path(sys.argv[1]); "
                         "os.rename(r/'launcher.lock',r/'original-lock'); "
                         "(r/'launcher.lock').write_bytes(b'synthetic replacement')")
                subprocess.run([sys.executable, '-c', actor, str(self.root)], check=True, timeout=10)
        try:
            with patch.object(refresh, "atomic_json", side_effect=write):
                with self.assertRaisesRegex(ValueError, "launcher.*lease|lease.*launcher"):
                    refresh.recover(self.root, lease_fd=descriptor)
            self.assertEqual(self.journal.read_bytes(), self.journal_before)
            self.assertEqual(self.target.read_bytes(), b"synthetic old terrain")
            self.assertTrue((self.root / "original-lock").exists())
            self.assertEqual((self.root / "launcher.lock").read_bytes(), b"synthetic replacement")
        finally:
            os.close(descriptor)


if __name__ == "__main__":
    unittest.main()

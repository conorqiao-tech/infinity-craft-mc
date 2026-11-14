"""Recovery readers must not release their process's native world lock."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == "posix", "requires POSIX native and launcher locks")
class RecoveryDigestIsolationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        self.lock = self.world / "session.lock"
        self.lock.write_bytes(b"synthetic equal bytes")
        (self.world / "level.dat").write_bytes(b"synthetic level")
        self.kept = self.world / "data/part/kept.bin"
        self.kept.parent.mkdir(parents=True)
        self.kept.write_bytes(b"synthetic equal bytes")
        self.saved = self.root / "backups/Terrain-Refresh-test/terrain/data/part"
        self.saved.mkdir(parents=True)
        (self.saved / "old.bin").write_bytes(b"synthetic archived bytes")
        self.journal = self.root / refresh.JOURNAL
        self.journal.write_text(json.dumps({"format": 1, "state": "moving", "world": "world",
            "rollback": "Terrain-Refresh-test", "moved_paths": ["data/part"],
            "kept_files": {"data/part/kept.bin": hashlib.sha256(self.kept.read_bytes()).hexdigest()}}))
        self.journal_before = self.journal.read_bytes()
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    def native_contender(self):
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try: fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError: sys.exit(42)\n"
                "else: sys.exit(0)")
        return subprocess.run([sys.executable, "-c", code, str(self.lock)], timeout=10).returncode

    def assert_preserved(self):
        self.assertEqual(self.journal.read_bytes(), self.journal_before)
        self.assertEqual(self.kept.read_bytes(), b"synthetic equal bytes")
        self.assertEqual((self.saved / "old.bin").read_bytes(), b"synthetic archived bytes")

    def exercise_swap(self, actor_code, expected_reader_status):
        original_digest = refresh._recovery_digest
        actual_run = subprocess.run
        actual_reader = refresh._run_digest_reader
        reader_status = []
        observations = []
        original = self.kept.with_name("kept-original.bin")

        def read_after_swap(path, info):
            self.assertEqual(path, self.kept)
            observations.append(self.native_contender())
            actual_run([sys.executable, "-c", actor_code, str(self.kept), str(self.lock)],
                       check=True, timeout=10)
            def capture(*args, **kwargs):
                result = actual_reader(*args, **kwargs)
                reader_status.append(result[1])
                return result
            try:
                with patch.object(refresh, "_run_digest_reader", side_effect=capture):
                    return original_digest(path, info)
            finally:
                self.kept.unlink()
                original.rename(self.kept)
                observations.append(self.native_contender())

        with patch.object(refresh, "_recovery_digest", side_effect=read_after_swap):
            with self.assertRaisesRegex(ValueError, "host review"):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(reader_status, [expected_reader_status])
        self.assertEqual(observations, [42, 42])
        self.assertEqual(self.lock.stat().st_nlink, 1)
        self.assert_preserved()

    def test_native_lock_hardlink_swap_does_not_release_parent_lock(self):
        actor = ("import os,sys;from pathlib import Path;p=Path(sys.argv[1]);"
                 "p.rename(p.with_name('kept-original.bin'));os.link(sys.argv[2],p)")
        self.exercise_swap(actor, 21)

    def test_native_lock_symlink_swap_is_refused_without_releasing_lock(self):
        actor = ("import os,sys;from pathlib import Path;p=Path(sys.argv[1]);"
                 "p.rename(p.with_name('kept-original.bin'));os.symlink(sys.argv[2],p)")
        self.exercise_swap(actor, 23)

    def test_reader_timeout_is_a_review_failure(self):
        info = self.kept.lstat()
        with patch.object(refresh, "_run_digest_reader", side_effect=subprocess.TimeoutExpired("reader", 120)):
            with self.assertRaisesRegex(ValueError, "host review"):
                refresh._recovery_digest(self.kept, info)
        self.assert_preserved()

    def test_changed_file_after_lstat_is_rejected(self):
        info = self.kept.lstat()
        self.kept.write_bytes(b"different synthetic content")
        with self.assertRaisesRegex(ValueError, "host review"):
            refresh._recovery_digest(self.kept, info)
        self.assertEqual(self.journal.read_bytes(), self.journal_before)
        self.assertEqual((self.saved / "old.bin").read_bytes(), b"synthetic archived bytes")

    def test_recorded_kept_file_recovers_normally(self):
        refresh.recover(self.root, lease_fd=self.lease)
        self.assertFalse(self.journal.exists())
        self.assertEqual((self.world / "data/part/old.bin").read_bytes(), b"synthetic archived bytes")


if __name__ == "__main__":
    unittest.main()

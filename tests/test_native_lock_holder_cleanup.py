"""Focused tests for native lock holder pipe identity and cleanup."""

import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community


@unittest.skipUnless(
    os.name == "posix" and sys.platform in ("darwin", "linux"),
    "requires supported POSIX pipes and locks",
)
class NativeLockHolderCleanupTests(unittest.TestCase):
    def test_pipe_identity_distinguishes_endpoints_with_identical_fstat(self):
        import fcntl

        read_fd, write_fd = os.pipe()
        try:
            metadata = os.fstat(read_fd)
            with patch.object(community.os, "fstat", return_value=metadata):
                read_identity = community.WorldSessionLock._pipe_identity(read_fd)
                write_identity = community.WorldSessionLock._pipe_identity(write_fd)

            self.assertNotEqual(
                fcntl.fcntl(read_fd, fcntl.F_GETFL) & os.O_ACCMODE,
                fcntl.fcntl(write_fd, fcntl.F_GETFL) & os.O_ACCMODE,
            )
            self.assertNotEqual(read_identity, write_identity)
        finally:
            os.close(read_fd)
            os.close(write_fd)

    def test_dispose_attempts_all_cleanup_and_reraises_interruptions(self):
        for interruption in (KeyboardInterrupt("stop cleanup"), SystemExit(23)):
            with self.subTest(interruption=type(interruption).__name__):
                lease = community.WorldSessionLock(Path("."))
                attempts = []

                class FakeHolder:
                    def __init__(self):
                        self.killed = False
                        self.waited = False

                    def poll(self):
                        return None

                    def kill(self):
                        self.killed = True

                    def wait(self, timeout=None):
                        self.waited = True
                        return 0

                holder = FakeHolder()
                lease._process = holder

                def close_pipe(lock, attribute):
                    attempts.append(attribute)
                    if attribute == "_writer":
                        raise interruption

                with patch.object(
                    community.WorldSessionLock, "_close_pipe", close_pipe
                ):
                    with self.assertRaises(type(interruption)) as raised:
                        lease._dispose()

                self.assertIs(raised.exception, interruption)
                self.assertEqual(
                    attempts,
                    ["_writer", "_reader", "_reply_writer", "_reply_reader"],
                )
                self.assertTrue(holder.killed)
                self.assertTrue(holder.waited)


if __name__ == "__main__":
    unittest.main()

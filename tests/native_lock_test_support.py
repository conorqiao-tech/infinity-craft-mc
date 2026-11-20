"""Fault controls for disposable native lock tests."""
import os
import signal
import time
from unittest.mock import patch

import community


_acquire_world_session_lock = community.acquire_world_session_lock


def acquire_unlockable_holder(world):
    block = '''
        import signal
        def unlock_for_test(signum, frame):
            fcntl.lockf(fd, fcntl.LOCK_UN)
            with open(path + ".test-unlocked", "x"):
                pass
        signal.signal(signal.SIGUSR1, unlock_for_test)
'''
    ready = '        print("ready", *identity, flush=True)'
    script = community._WORLD_SESSION_LOCK_HOLDER
    assert script.count(ready) == 1
    with patch.object(community, "_WORLD_SESSION_LOCK_HOLDER", script.replace(ready, block + ready)):
        return _acquire_world_session_lock(world)


def unlock_holder_for_test(world, lease):
    marker = world / "session.lock.test-unlocked"
    assert not marker.exists()
    os.kill(lease._process.pid, signal.SIGUSR1)
    deadline = time.monotonic() + 5
    while not marker.exists() and time.monotonic() < deadline:
        time.sleep(0.01)
    assert marker.is_file(), "the synthetic holder did not acknowledge unlock"

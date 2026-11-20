"""Synthetic same-process native-lock re-entry must not open another inode."""
import os
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(community.SESSION_LOCK_AVAILABLE and community._EXCLUSIVE_RENAME is not None,
                     'requires supported POSIX native locks and exclusive rename')
class NativeLockReentryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'world'
        self.world.mkdir()
        (self.world / 'session.lock').write_bytes(b'synthetic lock')

    def contender(self):
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        return subprocess.run([sys.executable, '-I', '-S', '-c', code, str(self.world/'session.lock')],
                              timeout=10).returncode

    def test_second_acquire_is_refused_before_open_and_first_lock_survives(self):
        first = community.acquire_world_session_lock(self.world)
        try:
            self.assertEqual(self.contender(), 42)
            with patch.object(community.os, 'open', wraps=os.open) as opened:
                with self.assertRaisesRegex(ValueError, 'already active'):
                    community.acquire_world_session_lock(self.world)
                opened.assert_not_called()
            community.verify_world_session_lock(self.world, first)
            self.assertEqual(self.contender(), 42)
            with self.assertRaises(TypeError):
                os.close(first)
        finally:
            first.close()
        self.assertEqual(self.contender(), 0)
        again = community.acquire_world_session_lock(self.world)
        try:
            self.assertEqual(self.contender(), 42)
        finally:
            again.close()
        again.close()
        self.assertEqual(self.contender(), 0)

    def test_different_world_is_refused_before_open_while_one_is_active(self):
        other = self.root/'other'
        other.mkdir()
        first = community.acquire_world_session_lock(self.world)
        try:
            with patch.object(community.os, 'open', wraps=os.open) as opened:
                with self.assertRaisesRegex(ValueError, 'already active'):
                    community.acquire_world_session_lock(other)
                opened.assert_not_called()
            self.assertFalse((other/'session.lock').exists())
            self.assertEqual(self.contender(), 42)
        finally:
            first.close()

    def test_second_module_name_shares_reservation(self):
        name = 'synthetic_second_community_for_lock_test'
        spec = importlib.util.spec_from_file_location(name, community.__file__)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        self.addCleanup(sys.modules.pop, name, None)
        spec.loader.exec_module(module)
        first = community.acquire_world_session_lock(self.world)
        try:
            with patch.object(module.os, 'open', wraps=os.open) as opened:
                with self.assertRaisesRegex(ValueError, 'already active'):
                    module.acquire_world_session_lock(self.world)
                opened.assert_not_called()
            self.assertEqual(self.contender(), 42)
        finally:
            first.close()
        second = module.acquire_world_session_lock(self.world)
        try:
            self.assertEqual(self.contender(), 42)
        finally:
            second.close()
        self.assertEqual(self.contender(), 0)

    def test_concurrent_acquisition_is_refused_during_holder_start(self):
        entered, proceed = threading.Event(), threading.Event()
        original = community.WorldSessionLock._start
        seen = []
        result = []
        def slow_start(lease):
            seen.append(lease)
            entered.set()
            if not proceed.wait(10):
                raise RuntimeError('synthetic startup checkpoint timed out')
            return original(lease)
        def first():
            try:
                result.append(community.acquire_world_session_lock(self.world))
            except BaseException as error:
                result.append(error)
        with patch.object(community.WorldSessionLock, '_start', slow_start):
            thread = threading.Thread(target=first)
            thread.start()
            try:
                self.assertTrue(entered.wait(10))
                with self.assertRaisesRegex(ValueError, 'already active'):
                    community.acquire_world_session_lock(self.world)
                self.assertEqual(len(seen), 1)
            finally:
                proceed.set()
                thread.join(15)
        self.assertFalse(thread.is_alive())
        self.assertEqual(len(result), 1)
        if isinstance(result[0], BaseException):
            raise result[0]
        try:
            self.assertEqual(self.contender(), 42)
        finally:
            result[0].close()
        self.assertEqual(self.contender(), 0)

    def test_failed_open_or_observation_allows_later_acquisition(self):
        script = community._WORLD_SESSION_LOCK_HOLDER
        source = script.replace('fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)',
                                'raise PermissionError("synthetic open failure")')
        self.assertNotEqual(source, script)
        with patch.object(community, '_WORLD_SESSION_LOCK_HOLDER', source):
            with self.assertRaisesRegex(ValueError, 'cannot be opened'):
                community.acquire_world_session_lock(self.world)
        with patch.object(community, 'verify_world_session_lock', side_effect=ValueError('synthetic observer failure')):
            with self.assertRaisesRegex(ValueError, 'observer failure'):
                community.acquire_world_session_lock(self.world)
        lease = community.acquire_world_session_lock(self.world)
        try:
            self.assertEqual(self.contender(), 42)
        finally:
            lease.close()
        self.assertEqual(self.contender(), 0)

    def test_recovery_refuses_reentry_before_moving_synthetic_terrain(self):
        root = self.root/'synthetic-server'
        world = root/'fabric/world'
        world.mkdir(parents=True)
        (world/'session.lock').write_bytes(b'synthetic lock')
        (world/'level.dat').write_bytes(b'synthetic level')
        saved = root/'backups/Terrain-Refresh-synthetic/terrain/data/old.bin'
        saved.parent.mkdir(parents=True)
        saved.write_bytes(b'synthetic archived terrain')
        (world/'data').mkdir()
        journal = root/refresh.JOURNAL
        journal.write_text(json.dumps({'format':1,'state':'moving','world':'world',
                                      'rollback':'Terrain-Refresh-synthetic',
                                      'moved_paths':['data/old.bin'],'kept_files':{}}))
        before = journal.read_bytes()
        launcher_fd = launcher.acquire_launcher_lock(launcher.parse_args([]), root)
        os.write(launcher_fd, str(os.getpid()).encode())
        native = None
        try:
            native = community.acquire_world_session_lock(world)
            with self.assertRaisesRegex(ValueError, 'already active'):
                refresh.recover(root, lease_fd=launcher_fd)
            self.assertEqual(journal.read_bytes(), before)
            self.assertEqual(saved.read_bytes(), b'synthetic archived terrain')
            self.assertFalse((world/'data/old.bin').exists())
            community.verify_world_session_lock(world, native)
            native.close()
            native = None
            refresh.recover(root, lease_fd=launcher_fd)
            self.assertEqual((world/'data/old.bin').read_bytes(), b'synthetic archived terrain')
        finally:
            if native is not None:
                native.close()
            os.close(launcher_fd)

    def test_uncertain_close_poisoning_is_confined_to_a_subprocess(self):
        code = r'''
import os,sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,sys.argv[1]);import community
world=Path(sys.argv[2]);lease=community.acquire_world_session_lock(world);fd=lease._writer
real_close=os.close
def fail(target):
    if target==fd:raise OSError('synthetic uncertain close')
    return real_close(target)
with patch.object(community.os,'close',side_effect=fail):
    try:lease.close()
    except community.WorldSessionLockUncertain:pass
    else:raise AssertionError('uncertain close accepted')
    try:lease.close()
    except community.WorldSessionLockUncertain:pass
    else:raise AssertionError('second close hid uncertainty')
with patch.object(community.os,'open',wraps=os.open) as opened:
    try:community.acquire_world_session_lock(world)
    except community.WorldSessionLockUncertain:pass
    else:raise AssertionError('uncertain reservation released')
    opened.assert_not_called()
assert community._backup_error_requires_review(community.WorldSessionLockUncertain('uncertain'))
real_close(fd)
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.world)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_reused_descriptor_is_not_closed_and_poisoning_is_retained(self):
        code = r'''
import os,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1]);import community
world=Path(sys.argv[2]);lease=community.acquire_world_session_lock(world);fd=lease._writer
os.close(fd)
other=world/'synthetic-other';other.write_bytes(b'untouched')
replacement=os.open(other,os.O_RDONLY)
if replacement!=fd:os.dup2(replacement,fd);os.close(replacement)
try:
    try:lease.close()
    except community.WorldSessionLockUncertain:pass
    else:raise AssertionError('reused descriptor accepted')
    assert os.read(fd,9)==b'untouched'
    try:community.acquire_world_session_lock(world)
    except community.WorldSessionLockUncertain:pass
    else:raise AssertionError('uncertain descriptor was forgotten')
finally:os.close(fd)
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.world)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_interrupted_spawn_poisoning_is_confined_to_a_subprocess(self):
        code = r'''
import os,sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,sys.argv[1]);import community
world=Path(sys.argv[2])
with patch.object(community.subprocess,'Popen',side_effect=KeyboardInterrupt):
    try:community.acquire_world_session_lock(world)
    except KeyboardInterrupt:pass
    else:raise AssertionError('interrupt swallowed')
with patch.object(community.os,'open',wraps=os.open) as opened:
    try:community.acquire_world_session_lock(world)
    except community.WorldSessionLockUncertain:pass
    else:raise AssertionError('uncertain spawn was forgotten')
    opened.assert_not_called()
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.world)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_child_without_a_start_grant_never_opens_the_lock(self):
        code = r'''
import subprocess,sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,sys.argv[1]);import community
root=Path(sys.argv[2]);world=root/'fabric/world';world.mkdir(parents=True)
(world/'level.dat').write_bytes(b'synthetic level')
real_popen=subprocess.Popen;seen=[]
def spawn(*args,**kwargs):
    child=real_popen(*args,**kwargs)
    seen.append(child)
    raise OSError('synthetic interruption after spawn, before handle handoff')
try:
    with patch.object(community.subprocess,'Popen',side_effect=spawn):
        try:community.backup(root,{'level-name':'world'})
        except community.WorldSessionLockUncertain:pass
        else:raise AssertionError('uncertain startup accepted')
    assert len(seen)==1 and seen[0].wait(timeout=10)!=0
    assert not (world/'session.lock').exists()
    assert (root/'launcher.lock').is_file()
    assert not list((root/'backups').glob('*.zip'))
finally:
    for child in seen:
        if child.poll() is None:child.kill()
        child.wait(timeout=5)
        child.stdout.close()
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.root / 'interrupted-spawn')], capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_pipe_identification_failure_closes_unshared_ends(self):
        code = r'''
import errno
import os
from pathlib import Path
import sys
from unittest.mock import patch

sys.path.insert(0, sys.argv[1])
import community

root = Path(sys.argv[2])
world = root / "fabric/world"
world.mkdir(parents=True)
(world / "level.dat").write_bytes(b"synthetic level")
replacement = root / "synthetic replacement"
replacement.write_bytes(b"keep replacement")
case = sys.argv[3]
failed_end = int(case != "reader")
original_pipe = os.pipe
original_fstat = os.fstat
descriptors = []
injected = False

def create():
    pair = original_pipe()
    descriptors.extend(pair)
    return pair

def inspect(descriptor):
    global injected
    if descriptors and descriptor == descriptors[failed_end] and not injected:
        injected = True
        if case == "replacement":
            reused = os.open(replacement, os.O_RDONLY)
            try:
                os.dup2(reused, descriptor)
            finally:
                os.close(reused)
        raise OSError(errno.EIO, "synthetic pipe identification failure")
    return original_fstat(descriptor)

with patch.object(community.os, "pipe", create), patch.object(community.os, "fstat", inspect), \
        patch.object(community.subprocess, "Popen", side_effect=AssertionError("holder was spawned")):
    try:
        community.backup(root, {"level-name": "world"})
    except community.WorldSessionLockUncertain:
        pass
    else:
        raise AssertionError("unconfirmed holder setup accepted")

assert injected and len(descriptors) == 2
assert (root / "launcher.lock").is_file()
assert not (world / "session.lock").exists()
assert not list((root / "backups").glob("*.zip"))
for index, descriptor in enumerate(descriptors):
    if case == "replacement" and index == failed_end:
        assert os.read(descriptor, 16) == b"keep replacement"
        os.close(descriptor)
        continue
    try:
        original_fstat(descriptor)
    except OSError as error:
        assert error.errno == errno.EBADF
    else:
        raise AssertionError("unshared pipe descriptor was left open")
'''
        for case in ('reader', 'writer', 'replacement'):
            with self.subTest(case=case):
                result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                         str(Path(community.__file__).parent),
                                         str(self.root / case), case],
                                        capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_lock_observers_require_replies_when_child_exit_status_can_be_lost(self):
        code = r'''
import fcntl,os,signal,sys,zipfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,sys.argv[1]);import community
root=Path(sys.argv[2]);world=root/'fabric/world';world.mkdir(parents=True)
(world/'level.dat').write_bytes(b'synthetic level')
signal.signal(signal.SIGCHLD,signal.SIG_IGN)
flag=os.open(root/'launcher.lock',os.O_CREAT|os.O_EXCL|os.O_RDWR,0o600)
try:
    fcntl.flock(flag,fcntl.LOCK_EX|fcntl.LOCK_NB)
    community.verify_borrowed_launcher_lease(root,flag)
    native=community.acquire_world_session_lock(world)
    try:
        community.verify_world_session_lock(world,native)
        with patch.object(community,'_WORLD_SESSION_LOCK_OBSERVER','raise SystemExit(17)'):
            try:community.verify_world_session_lock(world,native)
            except ValueError:pass
            else:raise AssertionError('native observation accepted an empty reply')
    finally:native.close()
    with patch.object(community,'_LAUNCHER_LEASE_OBSERVER','raise SystemExit(17)'):
        try:community.verify_borrowed_launcher_lease(root,flag)
        except ValueError:pass
        else:raise AssertionError('launcher observation accepted an empty reply')
    fcntl.flock(flag,fcntl.LOCK_SH|fcntl.LOCK_NB)
    try:community.verify_borrowed_launcher_lease(root,flag)
    except ValueError:pass
    else:raise AssertionError('launcher observation accepted a shared lease')
    fcntl.flock(flag,fcntl.LOCK_EX|fcntl.LOCK_NB)
    archive=community.backup(root,{'level-name':'world'},locked=True,lease_fd=flag)
    with zipfile.ZipFile(root/'backups'/archive) as backup:
        assert backup.testzip() is None and backup.read('world/level.dat')==b'synthetic level'
    assert (root/'launcher.lock').is_file()
finally:os.close(flag)
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.root / 'reaped-observer')], capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_fork_hook_refuses_before_opening_native_lock(self):
        code = r'''
import os,sys
from pathlib import Path
del os.register_at_fork
sys.path.insert(0,sys.argv[1]);import community
assert not community.SESSION_LOCK_AVAILABLE
world=Path(sys.argv[2])
try:community.acquire_world_session_lock(world)
except ValueError:pass
else:raise AssertionError('missing fork hooks accepted')
assert not (world/'session.lock').exists()
'''
        other = self.root/'without-fork-hook'
        other.mkdir()
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(other)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_uncertain_close_after_publication_keeps_archive_and_marker(self):
        code = r'''
import os,sys,zipfile
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,sys.argv[1]);import community
root=Path(sys.argv[2]);world=root/'fabric/world';world.mkdir(parents=True)
(world/'session.lock').write_bytes(b'synthetic lock');(world/'level.dat').write_bytes(b'synthetic level')
actual_acquire=community.acquire_world_session_lock;actual_close=os.close;tracked=[]
def acquire(path):
    lease=actual_acquire(path);tracked.append(lease._writer);return lease
def close(fd):
    if tracked and fd==tracked[0]:raise OSError('synthetic uncertain close')
    return actual_close(fd)
with patch.object(community,'acquire_world_session_lock',side_effect=acquire),\
     patch.object(community.os,'close',side_effect=close):
    try:community.backup(root,{'level-name':'world'})
    except community.WorldSessionLockUncertain as e:assert 'backup may already exist' in str(e)
    else:raise AssertionError('uncertain close accepted')
assert (root/'launcher.lock').is_file()
archives=list((root/'backups').glob('*.zip'));assert len(archives)==1
with zipfile.ZipFile(archives[0]) as archive:assert archive.testzip() is None
assert (world/'level.dat').read_bytes()==b'synthetic level'
try:community.acquire_world_session_lock(world)
except community.WorldSessionLockUncertain:pass
else:raise AssertionError('uncertain close was forgotten')
actual_close(tracked[0])
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.root/'synthetic-root')], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_poisoned_gate_keeps_new_manual_backup_marker(self):
        code = r'''
import sys
from pathlib import Path
sys.path.insert(0,sys.argv[1]);import community
root=Path(sys.argv[2]);world=root/'fabric/world';world.mkdir(parents=True)
(world/'session.lock').write_bytes(b'synthetic lock');(world/'level.dat').write_bytes(b'synthetic level')
community._WORLD_LOCK_STATE['active']=community._WORLD_LOCK_STATE['poisoned']
try:community.backup(root,{'level-name':'world'})
except community.WorldSessionLockUncertain:pass
else:raise AssertionError('poisoned gate accepted')
assert (root/'launcher.lock').is_file()
assert not list((root/'backups').glob('*.zip'))
assert (world/'level.dat').read_bytes()==b'synthetic level'
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.root/'synthetic-root')], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(hasattr(os, 'fork') and hasattr(os, 'register_at_fork'), 'requires fork hooks')
    def test_fork_with_active_lease_refuses_a_child_acquisition(self):
        code = r'''
import os,sys
from pathlib import Path
sys.path.insert(0,sys.argv[1]);import community
world=Path(sys.argv[2]);lease=community.acquire_world_session_lock(world)
try:
    pid=os.fork()
    if pid==0:
        try:
            try:lease.close()
            except ValueError:pass
            else:os._exit(7)
            try:community.acquire_world_session_lock(world)
            except community.WorldSessionLockUncertain:os._exit(0)
            os._exit(9)
        except BaseException:os._exit(8)
    _,status=os.waitpid(pid,0)
    assert os.waitstatus_to_exitcode(status)==0,status
    community.verify_world_session_lock(world,lease)
finally:lease.close()
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.world)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(hasattr(os, 'fork') and hasattr(os, 'register_at_fork'), 'requires fork hooks')
    def test_fork_resets_a_mutex_held_by_another_thread(self):
        code = r'''
import os,sys,threading
from pathlib import Path
sys.path.insert(0,sys.argv[1]);import community
world=Path(sys.argv[2]);entered=threading.Event();release=threading.Event()
def hold():
    with community._WORLD_LOCK_STATE['guard']:
        entered.set();release.wait(10)
thread=threading.Thread(target=hold);thread.start();assert entered.wait(5)
try:
    pid=os.fork()
    if pid==0:
        try:
            lease=community.acquire_world_session_lock(world);lease.close();os._exit(0)
        except BaseException:os._exit(9)
    _,status=os.waitpid(pid,0);assert os.waitstatus_to_exitcode(status)==0,status
finally:release.set();thread.join(5)
assert not thread.is_alive()
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(Path(community.__file__).parent),
                                 str(self.world)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()

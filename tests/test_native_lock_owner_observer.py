"""Detect loss or downgrade of a synthetic native world record lock."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import json

if os.name == 'posix':
    import fcntl

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import server as launcher
import world_refresh as refresh
from native_lock_test_support import acquire_unlockable_holder, unlock_holder_for_test


@unittest.skipUnless(sys.platform in ('darwin', 'linux') and os.name == 'posix', 'requires POSIX record locks')
class NativeLockOwnerObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.world=self.root/'fabric/world'
        self.world.mkdir(parents=True)
        (self.world/'session.lock').write_bytes(b'synthetic native lock')
        (self.world/'level.dat').write_bytes(b'synthetic level')
        (self.root/'fabric/server.properties').write_text('level-name=world\n')
        self.holders=[]
        self.addCleanup(self.stop_holders)

    def stop_holders(self):
        for child in self.holders:
            if child.poll() is None:
                child.stdin.write('\n');child.stdin.flush()
                _,err=child.communicate(timeout=10)
                self.assertEqual(child.returncode,0,err)

    def holder(self):
        code=("import fcntl,sys;f=open(sys.argv[1],'r+b');"
              "fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB);"
              "print('LOCKED',flush=True);sys.stdin.readline()")
        child=subprocess.Popen([sys.executable,'-u','-c',code,str(self.world/'session.lock')],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.holders.append(child)
        self.assertEqual(child.stdout.readline().strip(),'LOCKED')
        return child

    def test_backup_refuses_when_other_process_acquires_lost_native_lock(self):
        def acquire(world):
            lease = acquire_unlockable_holder(world)
            try:
                unlock_holder_for_test(world, lease)
                self.holder()
            except BaseException:
                lease.close()
                raise
            return lease
        with patch.object(community, 'acquire_world_session_lock', side_effect=acquire):
            with self.assertRaisesRegex(ValueError, 'world lock|Minecraft'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(list((self.root/'backups').glob('*.zip')), [])
        self.assertEqual((self.world/'level.dat').read_bytes(), b'synthetic level')

    def test_missing_native_lock_is_rejected_while_holder_still_exists(self):
        lease = acquire_unlockable_holder(self.world)
        try:
            unlock_holder_for_test(self.world, lease)
            self.assertIsNone(lease._process.poll())
            with self.assertRaisesRegex(ValueError, 'world lock|Minecraft'):
                community.verify_world_session_lock(self.world, lease)
        finally:
            lease.close()

    def assert_wrong_lock_coverage_is_rejected(self, command):
        marker = '        print("ready", *identity, flush=True)'
        script = community._WORLD_SESSION_LOCK_HOLDER
        self.assertEqual(script.count(marker), 1)
        script = script.replace(marker, '        ' + command + '\n' + marker)
        with patch.object(community, '_WORLD_SESSION_LOCK_HOLDER', script):
            with self.assertRaisesRegex(ValueError, 'world lock|Minecraft'):
                community.acquire_world_session_lock(self.world)

    def test_shared_downgrade_is_rejected(self):
        self.assert_wrong_lock_coverage_is_rejected('fcntl.lockf(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)')

    def test_partially_unlocked_range_is_rejected(self):
        self.assert_wrong_lock_coverage_is_rejected('fcntl.lockf(fd, fcntl.LOCK_UN, 1, 5, os.SEEK_SET)')

    def test_parent_descriptor_close_keeps_native_holder_lock_held(self):
        descriptor=community.acquire_world_session_lock(self.world)
        try:
            other = os.open(self.world / 'session.lock', os.O_RDONLY | os.O_NOFOLLOW)
            os.close(other)
            community.verify_world_session_lock(self.world,descriptor)
            code=("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                  "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                  "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
            result=subprocess.run([sys.executable,'-c',code,str(self.world/'session.lock')],timeout=10)
            self.assertEqual(result.returncode,42)
        finally:descriptor.close()

    def test_observer_timeout_fails_closed_without_releasing_native_lock(self):
        descriptor=community.acquire_world_session_lock(self.world)
        try:
            with patch.object(community.subprocess,'run',side_effect=subprocess.TimeoutExpired('observer',5)):
                with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                    community.verify_world_session_lock(self.world,descriptor)
            code=("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                  "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                  "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
            self.assertEqual(subprocess.run([sys.executable,'-c',code,str(self.world/'session.lock')],timeout=10).returncode,42)
        finally:descriptor.close()

    def test_observer_rejects_substituted_inode_without_releasing_other_record_lock(self):
        descriptor=community.acquire_world_session_lock(self.world)
        decoy=self.world/'synthetic-decoy.lock'
        decoy.write_bytes(b'synthetic decoy')
        decoy_fd=os.open(decoy,os.O_RDWR)
        fcntl.lockf(decoy_fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
        original=self.world/'original-session.lock'
        real_run=subprocess.run
        invoked=[]
        def swap_then_observe(*args,**kwargs):
            if not invoked:
                code=("import os,sys;from pathlib import Path;w=Path(sys.argv[1]);"
                      "os.rename(w/'session.lock',w/'original-session.lock');"
                      "os.rename(w/'synthetic-decoy.lock',w/'session.lock')")
                real_run([sys.executable,'-c',code,str(self.world)],check=True,timeout=10)
                invoked.append(True)
            return real_run(*args,**kwargs)
        try:
            with patch.object(community.subprocess,'run',side_effect=swap_then_observe):
                with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                    community.verify_world_session_lock(self.world,descriptor)
            self.assertEqual(invoked,[True])
            code=("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                  "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                  "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
            for path in (original,self.world/'session.lock'):
                self.assertEqual(real_run([sys.executable,'-c',code,str(path)],timeout=10).returncode,42)
        finally:
            os.close(decoy_fd)
            descriptor.close()

    def test_recovery_refuses_lost_native_lock_before_moving_anything(self):
        (self.world/'data').mkdir()
        saved=self.root/'backups/Terrain-Refresh-synthetic/terrain/data/old.bin'
        saved.parent.mkdir(parents=True);saved.write_bytes(b'synthetic archived bytes')
        journal=self.root/refresh.JOURNAL
        journal.write_text(json.dumps({'format':1,'state':'moving','world':'world',
            'rollback':'Terrain-Refresh-synthetic','moved_paths':['data/old.bin'],'kept_files':{}}))
        before=journal.read_bytes()
        lease=launcher.acquire_launcher_lock(launcher.parse_args([]),self.root)
        os.write(lease,str(os.getpid()).encode())
        def acquire(world):
            native = acquire_unlockable_holder(world)
            try:
                unlock_holder_for_test(world, native)
                self.holder()
            except BaseException:
                native.close()
                raise
            return native
        try:
            with patch.object(community,'acquire_world_session_lock',side_effect=acquire):
                with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                    refresh.recover(self.root,lease_fd=lease)
            self.assertEqual(journal.read_bytes(),before)
            self.assertEqual(saved.read_bytes(),b'synthetic archived bytes')
            self.assertFalse((self.world/'data/old.bin').exists())
        finally:os.close(lease)


if __name__=='__main__':unittest.main()

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

    def lose_lock(self,descriptor):
        other=os.open(self.world/'session.lock',os.O_RDONLY)
        os.close(other)
        os.fstat(descriptor)

    def test_backup_refuses_when_other_process_acquires_lost_native_lock(self):
        real=community.acquire_world_session_lock
        def acquire(world):
            descriptor=real(world)
            self.lose_lock(descriptor)
            self.holder()
            return descriptor
        with patch.object(community,'acquire_world_session_lock',side_effect=acquire):
            with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                community.backup(self.root,{'level-name':'world'})
        self.assertEqual(list((self.root/'backups').glob('*.zip')),[])
        self.assertEqual((self.world/'level.dat').read_bytes(),b'synthetic level')

    def test_missing_native_lock_is_rejected_while_descriptor_still_exists(self):
        descriptor=community.acquire_world_session_lock(self.world)
        try:
            self.lose_lock(descriptor)
            with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                community.verify_world_session_lock(self.world,descriptor)
        finally:os.close(descriptor)

    def test_shared_downgrade_is_rejected(self):
        descriptor=community.acquire_world_session_lock(self.world)
        try:
            fcntl.lockf(descriptor,fcntl.LOCK_SH|fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                community.verify_world_session_lock(self.world,descriptor)
        finally:os.close(descriptor)

    def test_partially_unlocked_range_is_rejected(self):
        descriptor=community.acquire_world_session_lock(self.world)
        try:
            fcntl.lockf(descriptor,fcntl.LOCK_UN,1,5,os.SEEK_SET)
            with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                community.verify_world_session_lock(self.world,descriptor)
        finally:os.close(descriptor)

    def test_normal_observer_keeps_native_record_lock_held(self):
        descriptor=community.acquire_world_session_lock(self.world)
        try:
            community.verify_world_session_lock(self.world,descriptor)
            code=("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                  "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                  "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
            result=subprocess.run([sys.executable,'-c',code,str(self.world/'session.lock')],timeout=10)
            self.assertEqual(result.returncode,42)
        finally:os.close(descriptor)

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
        finally:os.close(descriptor)

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
            os.close(descriptor)

    def test_recovery_refuses_lost_native_lock_before_moving_anything(self):
        saved=self.root/'backups/Terrain-Refresh-synthetic/terrain/data/old.bin'
        saved.parent.mkdir(parents=True);saved.write_bytes(b'synthetic archived bytes')
        journal=self.root/refresh.JOURNAL
        journal.write_text(json.dumps({'format':1,'state':'moving','world':'world',
            'rollback':'Terrain-Refresh-synthetic','moved_paths':['data/old.bin'],'kept_files':{}}))
        before=journal.read_bytes()
        lease=launcher.acquire_launcher_lock(launcher.parse_args([]),self.root)
        os.write(lease,str(os.getpid()).encode())
        real=community.acquire_world_session_lock
        def acquire(world):
            descriptor=real(world)
            self.lose_lock(descriptor)
            self.holder()
            return descriptor
        try:
            with patch.object(community,'acquire_world_session_lock',side_effect=acquire):
                with self.assertRaisesRegex(ValueError,'world lock|Minecraft'):
                    refresh.recover(self.root,lease_fd=lease)
            self.assertEqual(journal.read_bytes(),before)
            self.assertEqual(saved.read_bytes(),b'synthetic archived bytes')
            self.assertFalse((self.world/'data/old.bin').exists())
        finally:os.close(lease)


if __name__=='__main__':unittest.main()

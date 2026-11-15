"""Recovery records must not replace or delete unrelated synthetic evidence."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX advisory locks')
class RecoveryRecordPreservationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.victim = self.world / 'synthetic-important.bin'
        self.victim.write_bytes(b'unrelated synthetic world bytes')
        self.rollback = self.root / 'backups/Terrain-Refresh-synthetic'
        saved = self.rollback / 'terrain/region'
        saved.mkdir(parents=True)
        (saved / 'old.bin').write_bytes(b'synthetic archived terrain')
        self.manifest = self.rollback / 'MANIFEST.json'
        self.manifest.write_bytes(b'prior synthetic manifest evidence')
        self.policy = self.root / refresh.POLICY
        self.policy.write_text(json.dumps({'format': 1, 'enabled': False,
                                          'refresh_next_start': True, 'last_release': 'user choice'}))
        self.policy_before = self.policy.read_bytes()
        self.record = {'format': 1, 'state': 'moving', 'world': 'world',
                       'rollback': self.rollback.name, 'moved_paths': ['region'], 'kept_files': {},
                       'new_policy': {'last_release': 'synthetic release'}}
        self.journal = self.root / refresh.JOURNAL
        self.journal.write_text(json.dumps(self.record))
        self.journal_before = self.journal.read_bytes()
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    def recover(self):
        messages = []
        refresh.recover(self.root, emit=messages.append, lease_fd=self.lease)
        return messages

    def receipts(self):
        return list(self.rollback.glob('RECOVERY-*.json'))

    def assert_old_records(self):
        self.assertEqual(self.manifest.read_bytes(), b'prior synthetic manifest evidence')
        self.assertEqual(self.policy.read_bytes(), self.policy_before)

    def test_moving_recovery_preserves_manifest_and_writes_additive_receipt(self):
        messages = self.recover()
        self.assert_old_records()
        receipt, = self.receipts()
        value = json.loads(receipt.read_text())
        self.assertEqual(value['recovery_state'], 'rolled_back')
        self.assertEqual(value['source_journal_sha256'], hashlib.sha256(self.journal_before).hexdigest())
        self.assertEqual(value['phase'], 'before_journal_retirement')
        self.assertFalse(value['recovery_writes_existing_manifest_or_policy'])
        self.assertEqual(receipt.stat().st_mode & 0o777, 0o600)
        self.assertIn(receipt.name, messages[0])
        self.assertEqual((self.world / 'region/old.bin').read_bytes(), b'synthetic archived terrain')
        self.assertFalse(self.journal.exists())

    def test_committed_recovery_keeps_the_user_policy_and_manifest(self):
        self.record['state'] = 'committed'
        self.journal.write_text(json.dumps(self.record))
        messages = self.recover()
        self.assert_old_records()
        receipt, = self.receipts()
        self.assertEqual(json.loads(receipt.read_text())['recovery_state'], 'committed')
        self.assertIn('was left unchanged', messages[0])
        self.assertFalse((self.world / 'region').exists())
        self.assertEqual((self.rollback / 'terrain/region/old.bin').read_bytes(), b'synthetic archived terrain')

    def test_committed_recovery_does_not_create_an_absent_policy(self):
        self.record['state'] = 'committed'
        self.journal.write_text(json.dumps(self.record))
        self.policy.unlink()
        self.recover()
        self.assertFalse(self.policy.exists())
        self.assertEqual(self.manifest.read_bytes(), b'prior synthetic manifest evidence')
        receipt, = self.receipts()
        self.assertEqual(json.loads(receipt.read_text())['recovery_state'], 'committed')

    def attack_receipt(self, mode):
        actual_replace = os.replace
        actual_publish = community.publish_backup_no_replace
        observed = []
        contender = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                     "try:getattr(fcntl,sys.argv[2])(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                     "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        def attack(stage, final, publish):
            values = [subprocess.run([sys.executable, '-I', '-S', '-c', contender, str(path), kind],
                                     timeout=10).returncode
                      for path, kind in ((self.root / 'launcher.lock', 'flock'),
                                         (self.world / 'session.lock', 'lockf'))]
            self.assertEqual(values, [42, 42])
            if mode == 'after':
                publish()
            script = ("import os,sys;"
                      + ("os.replace(sys.argv[1],sys.argv[3]);" if mode == 'before' else '')
                      + "os.replace(sys.argv[2],sys.argv[1])")
            subprocess.run([sys.executable, '-I', '-S', '-c', script, str(stage), str(self.victim),
                            str(self.root / 'synthetic-held-stage')], check=True, timeout=10)
            observed.append((stage, final))
            if mode == 'before':
                publish()
        def replace(source, destination, *args, **kwargs):
            source, destination = Path(source), Path(destination)
            if destination == self.manifest and '.refresh-tmp-' in source.name and not observed:
                return attack(source, destination, lambda: actual_replace(source, destination, *args, **kwargs))
            return actual_replace(source, destination, *args, **kwargs)
        def publish(source, destination, **kwargs):
            source, destination = Path(source), Path(destination)
            if destination.name.startswith('RECOVERY-') and not observed:
                return attack(self.rollback / source.name, self.rollback / destination.name,
                              lambda: actual_publish(source, destination, **kwargs))
            return actual_publish(source, destination, **kwargs)
        with patch.object(os, 'replace', side_effect=replace), \
             patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaisesRegex(ValueError, 'receipt|review'):
                self.recover()
        self.assertEqual(len(observed), 1)
        stage, final = observed[0]
        self.assert_old_records()
        self.assertTrue(self.journal.exists())
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(self.victim.exists())  # The synthetic actor moved it.
        preserved = final if mode == 'before' else stage
        self.assertEqual(preserved.read_bytes(), b'unrelated synthetic world bytes')
        if mode == 'before':
            self.assertTrue((self.root / 'synthetic-held-stage').is_file())

    def test_substituted_stage_before_publication_is_retained(self):
        self.attack_receipt('before')

    def test_recreated_stage_after_publication_is_not_deleted(self):
        self.attack_receipt('after')

    def test_receipt_destination_collision_keeps_both_entries(self):
        token = '0' * 32
        existing = self.rollback / ('RECOVERY-' + token + '.json')
        existing.write_bytes(b'prior synthetic receipt')
        with patch.object(refresh.uuid, 'uuid4', return_value=SimpleNamespace(hex=token)):
            with self.assertRaisesRegex(ValueError, 'receipt|review'):
                self.recover()
        self.assertEqual(existing.read_bytes(), b'prior synthetic receipt')
        self.assertTrue((self.rollback / ('.RECOVERY-' + token + '.partial')).is_file())
        self.assert_old_records()
        self.assertTrue(self.journal.exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_partial_write_failure_retains_stage_and_original_evidence(self):
        token = '2' * 32
        stage = self.rollback / ('.RECOVERY-' + token + '.partial')
        actual_open, actual_write = os.open, os.write
        descriptor = []
        def observe_open(path, flags, *args, **kwargs):
            result = actual_open(path, flags, *args, **kwargs)
            if path == stage.name:
                descriptor.append(result)
            return result
        def fail_write(fd, value):
            if descriptor and fd == descriptor[0]:
                actual_write(fd, value[:8])
                raise OSError('synthetic partial receipt write')
            return actual_write(fd, value)
        with patch.object(refresh.uuid, 'uuid4', return_value=SimpleNamespace(hex=token)), \
             patch.object(os, 'open', side_effect=observe_open), \
             patch.object(os, 'write', side_effect=fail_write):
            with self.assertRaisesRegex(ValueError, 'receipt|review'):
                self.recover()
        self.assertEqual(len(stage.read_bytes()), 8)
        self.assertEqual(self.receipts(), [])
        self.assert_old_records()
        self.assertTrue(self.journal.exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_in_place_receipt_change_is_refused_and_retained(self):
        actual_flush = community.flush_descriptor
        changed = []
        def flush(fd):
            actual_flush(fd)
            candidates = list(self.rollback.glob('.RECOVERY-*.partial'))
            if candidates and not changed:
                script = ("from pathlib import Path;import sys;p=Path(sys.argv[1]);"
                          "data=p.read_bytes();p.write_bytes(b'X'+data[1:])")
                subprocess.run([sys.executable, '-I', '-S', '-c', script, str(candidates[0])],
                               check=True, timeout=10)
                changed.append(True)
        with patch.object(community, 'flush_descriptor', side_effect=flush):
            with self.assertRaisesRegex(ValueError, 'receipt|review'):
                self.recover()
        self.assertEqual(changed, [True])
        receipt, = self.receipts()
        self.assertTrue(receipt.read_bytes().startswith(b'X'))
        self.assert_old_records()
        self.assertTrue(self.journal.exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_symlink_substitution_does_not_change_target_mode(self):
        self.victim.chmod(0o644)
        actual = community.publish_backup_no_replace
        observed = []
        def publish(source, destination, **kwargs):
            if Path(destination).name.startswith('RECOVERY-'):
                stage = self.rollback / Path(source).name
                actor = ("import os,sys;os.replace(sys.argv[1],sys.argv[2]);"
                         "os.symlink(sys.argv[3],sys.argv[1])")
                subprocess.run([sys.executable, '-I', '-S', '-c', actor, str(stage),
                                str(self.root / 'synthetic-held-stage'), str(self.victim)],
                               check=True, timeout=10)
                observed.append(True)
            return actual(source, destination, **kwargs)
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaisesRegex(ValueError, 'receipt|review'):
                self.recover()
        self.assertEqual(observed, [True])
        self.assertEqual(self.victim.stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.victim.read_bytes(), b'unrelated synthetic world bytes')
        receipt, = self.receipts()
        self.assertTrue(receipt.is_symlink())
        self.assertTrue((self.root / 'synthetic-held-stage').is_file())
        self.assert_old_records()
        self.assertTrue(self.journal.exists())

    def test_interrupt_survives_receipt_descriptor_close_failure(self):
        actual_open, actual_close = os.open, os.close
        actual_publish = community.publish_backup_no_replace
        descriptor = []
        def observe_open(path, flags, *args, **kwargs):
            result = actual_open(path, flags, *args, **kwargs)
            if isinstance(path, str) and path.startswith('.RECOVERY-'):
                descriptor.append(result)
            return result
        def publish(source, destination, **kwargs):
            result = actual_publish(source, destination, **kwargs)
            if Path(destination).name.startswith('RECOVERY-'):
                raise KeyboardInterrupt()
            return result
        def close(fd):
            actual_close(fd)
            if descriptor and fd == descriptor[0]:
                raise OSError('synthetic receipt close failure')
        with patch.object(os, 'open', side_effect=observe_open), \
             patch.object(os, 'close', side_effect=close), \
             patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaises(KeyboardInterrupt) as caught:
                self.recover()
        self.assertIsInstance(caught.exception.__cause__, OSError)
        self.assertEqual(len(self.receipts()), 1)
        self.assert_old_records()
        self.assertTrue(self.journal.exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_stage_lock_alias_is_not_opened_by_lock_owner(self):
        token = '1' * 32
        stage = self.rollback / ('.RECOVERY-' + token + '.partial')
        actual_open = os.open
        observed = []
        contender = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                     "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                     "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        def open_with_alias(path, flags, *args, **kwargs):
            if path == stage.name and flags & os.O_EXCL:
                os.link(self.world / 'session.lock', stage)
                try:
                    return actual_open(path, flags, *args, **kwargs)
                except FileExistsError:
                    observed.append(subprocess.run([sys.executable, '-I', '-S', '-c', contender,
                                                    str(self.world / 'session.lock')], timeout=10).returncode)
                    raise
            return actual_open(path, flags, *args, **kwargs)
        try:
            with patch.object(refresh.uuid, 'uuid4', return_value=SimpleNamespace(hex=token)), \
                 patch.object(os, 'open', side_effect=open_with_alias):
                with self.assertRaisesRegex(ValueError, 'receipt|review'):
                    self.recover()
            self.assertEqual(observed, [42])
            self.assertEqual(stage.stat().st_ino, (self.world / 'session.lock').stat().st_ino)
            self.assert_old_records()
            self.assertTrue(self.journal.exists())
        finally:
            if stage.exists():
                stage.unlink()  # Only the synthetic alias created by this test.


if __name__ == '__main__':
    unittest.main()

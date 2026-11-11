"""Synthetic checks for backup staging and publication identity."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX backup locks')
class BackupArchivePublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / 'fabric' / 'world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        (self.world / 'region').mkdir()
        (self.world / 'region' / 'r.0.0.mca').write_bytes(b'synthetic region')
        self.external = self.root / 'synthetic-external'
        self.external.write_bytes(b'preserve external file')
        self.external.chmod(0o644)

    def swap_after_archive_close(self, kind, fail_sync=False):
        real_sync = community.sync_file
        observations = []
        held = self.root / 'held-original.synthetic'
        actor = '''
import fcntl,json,os,sys
from pathlib import Path
partial,held,native,lease,target=map(Path,sys.argv[1:6]); kind=sys.argv[6]
observed={}
for name,path,method in [('native',native,'record'),('launcher',lease,'flock')]:
    with path.open('r+b') as f:
        try:
            if method == 'record': fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
            else: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: observed[name]=True
        else: observed[name]=False
os.replace(partial,held)
if kind == 'regular': partial.write_bytes(b'synthetic replacement')
elif kind == 'native-hardlink': os.link(native,partial)
else: os.symlink(target,partial)
print(json.dumps(observed))
'''

        def swap(stream):
            partial, = (self.root / 'backups').glob('*.partial')
            result = subprocess.run([sys.executable, '-I', '-S', '-c', actor, str(partial), str(held),
                                     str(self.world / 'session.lock'), str(self.root / 'launcher.lock'),
                                     str(self.external), kind], check=True, capture_output=True, text=True, timeout=10)
            observations.append(json.loads(result.stdout))
            if fail_sync:
                raise OSError('synthetic archive sync failure after substitution')
            real_sync(stream)

        with patch.object(community, 'sync_file', side_effect=swap):
            with self.assertRaises((ValueError, RuntimeError, OSError)):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(observations, [{'native': True, 'launcher': True}])
        partial, = (self.root / 'backups').glob('*.partial')
        if kind == 'regular':
            self.assertEqual(partial.read_bytes(), b'synthetic replacement')
        elif kind == 'native-hardlink':
            self.assertEqual(partial.stat().st_ino, (self.world / 'session.lock').stat().st_ino)
        else:
            self.assertTrue(partial.is_symlink())
        self.assertEqual(self.external.read_bytes(), b'preserve external file')
        self.assertEqual(self.external.stat().st_mode & 0o777, 0o644)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        with zipfile.ZipFile(held) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')
        self.assertEqual((self.world / 'region' / 'r.0.0.mca').read_bytes(), b'synthetic region')

    def test_regular_partial_substitution_retains_evidence(self):
        self.swap_after_archive_close('regular')

    def test_symlink_partial_substitution_retains_evidence(self):
        self.swap_after_archive_close('symlink')

    def test_native_lock_alias_substitution_does_not_release_parent_lock(self):
        real_verify = community.verify_backup_archive_entry
        observed = []
        def verify(*args, **kwargs):
            try:
                return real_verify(*args, **kwargs)
            except community.BackupArchiveUncertain:
                code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                        "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                        "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
                result = subprocess.run([sys.executable, '-c', code, str(self.world / 'session.lock')], timeout=10)
                observed.append(result.returncode)
                raise
        with patch.object(community, 'verify_backup_archive_entry', side_effect=verify):
            self.swap_after_archive_close('native-hardlink')
        self.assertEqual(observed, [42])

    def test_substitution_during_sync_failure_is_not_deleted(self):
        self.swap_after_archive_close('regular', fail_sync=True)

    def test_failed_archive_is_not_unlinked_after_cleanup_check(self):
        victim = self.world / 'region/r.0.0.mca'
        held = self.root / 'held-original.synthetic'
        real_unlink = Path.unlink
        attempts = []

        def swap_before_unlink(path, *args, **kwargs):
            if path.suffix == '.partial':
                script = ("import os,sys;os.replace(sys.argv[1],sys.argv[2]);"
                          "os.replace(sys.argv[3],sys.argv[1])")
                subprocess.run([sys.executable, '-I', '-S', '-c', script,
                                str(path), str(held), str(victim)], check=True, timeout=10)
                attempts.append(True)
            return real_unlink(path, *args, **kwargs)

        with patch.object(community, 'sync_file', side_effect=OSError('synthetic archive flush failure')):
            with patch.object(Path, 'unlink', swap_before_unlink):
                with self.assertRaisesRegex(OSError, 'synthetic archive flush failure'):
                    community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(attempts, [])
        self.assertEqual(victim.read_bytes(), b'synthetic region')
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertEqual(len(list((self.root / 'backups').glob('*.partial'))), 1)
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_late_substitution_during_rename_retains_published_evidence(self):
        real_publish = community.publish_backup_no_replace
        held = self.root / 'held-original.synthetic'
        observations = []

        def late_swap(source, destination):
            if Path(source).suffix == '.partial':
                script = ("import os,sys;from pathlib import Path;"
                          "os.replace(sys.argv[1],sys.argv[2]);"
                          "Path(sys.argv[1]).write_bytes(b'late synthetic substitute')")
                subprocess.run([sys.executable, '-I', '-S', '-c', script, str(source), str(held)],
                               check=True, timeout=10)
                observations.append(True)
            return real_publish(source, destination)

        with patch.object(community, 'publish_backup_no_replace', side_effect=late_swap):
            with self.assertRaises((ValueError, RuntimeError, OSError)):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(observations, [True])
        self.assertTrue((self.root / 'launcher.lock').exists())
        published, = (self.root / 'backups').glob('*.zip')
        self.assertEqual(published.read_bytes(), b'late synthetic substitute')
        with zipfile.ZipFile(held) as archive:
            self.assertIsNone(archive.testzip())

    def test_late_world_file_substitution_is_moved_not_unlinked(self):
        real_publish = community.publish_backup_no_replace
        held = self.root / 'held-original.synthetic'
        victim = self.world / 'region/r.0.0.mca'

        def late_swap(source, destination):
            script = ("import os,sys;os.replace(sys.argv[1],sys.argv[2]);"
                      "os.replace(sys.argv[3],sys.argv[1])")
            subprocess.run([sys.executable, '-I', '-S', '-c', script, str(source), str(held), str(victim)],
                           check=True, timeout=10)
            return real_publish(source, destination)

        with patch.object(community, 'publish_backup_no_replace', side_effect=late_swap):
            with self.assertRaises(community.BackupArchiveUncertain):
                community.backup(self.root, {'level-name': 'world'})
        self.assertFalse(victim.exists())  # The synthetic actor moved it before publication.
        published, = (self.root / 'backups').glob('*.zip')
        self.assertEqual(published.read_bytes(), b'synthetic region')
        self.assertTrue((self.root / 'launcher.lock').exists())
        with zipfile.ZipFile(held) as archive:
            self.assertIsNone(archive.testzip())

    def test_existing_destination_is_not_overwritten(self):
        real_publish = community.publish_backup_no_replace
        created = []

        def collide(source, destination):
            Path(destination).write_bytes(b'preserve existing synthetic backup')
            created.append(Path(destination))
            return real_publish(source, destination)

        with patch.object(community, 'publish_backup_no_replace', side_effect=collide):
            with self.assertRaises(FileExistsError):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(len(created), 1)
        self.assertEqual(created[0].read_bytes(), b'preserve existing synthetic backup')
        self.assertTrue((self.root / 'launcher.lock').exists())
        partial, = (self.root / 'backups').glob('*.partial')
        with zipfile.ZipFile(partial) as archive:
            self.assertIsNone(archive.testzip())

    def test_unsupported_exclusive_rename_retains_evidence(self):
        with patch.object(community, 'publish_backup_no_replace', side_effect=OSError('synthetic unsupported rename')):
            with self.assertRaisesRegex(OSError, 'unsupported rename'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))
        partial, = (self.root / 'backups').glob('*.partial')
        with zipfile.ZipFile(partial) as archive:
            self.assertIsNone(archive.testzip())


if __name__ == '__main__':
    unittest.main()

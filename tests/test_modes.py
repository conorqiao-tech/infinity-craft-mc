import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community

class ModeLauncherTests(unittest.TestCase):
    def test_membership_grants_are_validated_for_console_use(self):
        self.assertEqual(community.membership_command('grant', '.Bedrock_Player', 'ultra', 30), 'membership grant .Bedrock_Player ultra 30')
        self.assertEqual(community.membership_command('revokeadmin', 'Owner'), 'membership revokeadmin Owner')
        for values in [('grant','@a','ultra',30),('grant','Player\nstop','pro',30),('grant','Player','admin',30),('grant','Player','go',True),('grant','Player','go',0),('grant','Player','go',4000),('grant','Player','go\nop',30),('op','Player','go',30)]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                community.membership_command(*values)

    def test_backup_includes_dimension_and_player_mode_state(self):
        import tempfile, zipfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            world = root / 'fabric/world'
            player = world / 'playerdata/player.dat'
            dimension = world / 'dimensions/convergence/creative/region/r.0.0.mca'
            for file, data in [(world/'level.dat',b'level'),(player,b'player with profiles'),(dimension,b'creative blocks'),(world/'infinity-memberships.json',b'{"accounts":{}}')]:
                file.parent.mkdir(parents=True, exist_ok=True);file.write_bytes(data)
            name=community.backup(root,{'level-name':'world'})
            with zipfile.ZipFile(root/'backups'/name) as archive:
                self.assertEqual(archive.read('world/playerdata/player.dat'),b'player with profiles')
                self.assertEqual(archive.read('world/dimensions/convergence/creative/region/r.0.0.mca'),b'creative blocks')
                self.assertIn('world/infinity-memberships.json',archive.namelist())

if __name__=='__main__': unittest.main()

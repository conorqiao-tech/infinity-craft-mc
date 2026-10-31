#!/usr/bin/env python3
"""Test the real launcher in an isolated loopback world using existing owner EULA acceptance."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import struct
import sys
import threading
import time
import uuid
import gzip
from verify_owned_equipment import catalogue_item_count_pairs

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server'))
import server as launcher
import community
import world_refresh

PROVENANCE_PREFIX = 'World preservation provenance: '
# Code that runs in this smoke besides the launcher modules in server/*.py.
SOURCE_INPUTS = ('tools/smoke_fullstack.py', 'tools/verify_owned_equipment.py', 'tests/test_terrain_storage.py')
SHA256 = re.compile('[0-9a-f]{64}')
NOT_PACKAGED = ('The Java executable and isolated Fabric library files observed at readiness and shutdown '
                'are recorded only; this does not establish which libraries were loaded or hash the full Java runtime. '
                'They are not part of the package and are not all redistributable.')


def lock_entries(lock):
    """Return (path, pinned SHA-256) for every download and bundled file; refuse unsafe or duplicate paths."""
    entries, seen = [], set()
    for entry in lock['downloads'] + lock['bundled']:
        path, expected = entry.get('path'), entry.get('sha256')
        parts = PurePosixPath(path).parts if isinstance(path, str) else ()
        if (not parts or path.startswith('/') or '\\' in path or '..' in parts
                or PurePosixPath(*parts).as_posix() != path or path in seen
                or not isinstance(expected, str) or not SHA256.fullmatch(expected)):
            raise RuntimeError('Unsafe, duplicate or unpinned dependency lock entry: ' + repr(path))
        seen.add(path)
        entries.append((path, expected))
    return entries


def pinned(base, path, expected):
    file = base / path
    if file.is_symlink() or not file.is_file() or not file.resolve().is_relative_to(base.resolve()):
        raise RuntimeError('Missing or unsafe dependency file: ' + path)
    actual = world_refresh.digest(file)
    if actual != expected:
        raise RuntimeError('Dependency file differs from its locked SHA-256: ' + path)
    return actual


def tested_inputs(root):
    """Hash the exact candidate: both locks, every pinned lock file and the code this smoke runs."""
    server = root / 'server'
    lock_bytes = (server / 'dependencies.lock.json').read_bytes()
    files = {'server/dependencies.lock.json': hashlib.sha256(lock_bytes).hexdigest(),
             'server/runtime.lock.json': world_refresh.digest(server / 'runtime.lock.json')}
    for path, expected in lock_entries(json.loads(lock_bytes)):
        files['server/' + path] = pinned(server, path, expected)
    for file in sorted(server.glob('*.py')):
        files['server/' + file.name] = world_refresh.digest(file)
    for name in SOURCE_INPUTS:
        files[name] = world_refresh.digest(root / name)
    return lock_bytes, files


def library_hashes(source):
    folder = source / 'fabric/libraries'
    if folder.is_symlink():
        raise RuntimeError('Fabric library directory cannot be a symbolic link')
    hashes = {}
    if folder.is_dir():
        for p in sorted(folder.rglob('*')):
            if p.is_symlink():
                raise RuntimeError('Fabric library files cannot be symbolic links')
            if p.is_file():
                hashes[p.relative_to(source).as_posix()] = world_refresh.digest(p)
    return hashes


def provenance_line(files, java_sha256, java_version, libraries, runs):
    """One canonical JSON record; it never includes the EULA, private settings or local paths."""
    record = {'format': 1, 'kind': 'world-preservation', 'runs': runs, 'files': files,
              'java': {'executable_sha256': java_sha256, 'version': java_version}, 'libraries': libraries, 'not_packaged': NOT_PACKAGED}
    return PROVENANCE_PREFIX + json.dumps(record, sort_keys=True, separators=(',', ':'))


def verify_cycle_inputs(source_root, isolated_root, locked, lock_bytes, tested, java_executable, java_sha256, source_libraries):
    """Fail before a run or after shutdown if a tested artifact or source changed."""
    if tested_inputs(source_root) != (lock_bytes, tested) or library_hashes(source_root / 'server') != source_libraries:
        raise RuntimeError('Candidate files changed during the smoke; rerun it')
    for path, expected in locked:
        pinned(isolated_root, path, expected)
    if ((isolated_root / 'dependencies.lock.json').read_bytes() != lock_bytes
            or world_refresh.digest(isolated_root / 'runtime.lock.json') != tested['server/runtime.lock.json']
            or world_refresh.digest(java_executable) != java_sha256):
        raise RuntimeError('Isolated locks or Java changed during the smoke; rerun it')


def verify_runtime_libraries(root, copied, expected=None):
    """Record actual isolated library files, without claiming which were loaded."""
    current = library_hashes(root)
    if any(current.get(name) != digest for name, digest in copied.items()):
        raise RuntimeError('A copied Fabric library changed in the isolated runtime')
    if expected is not None and current != expected:
        raise RuntimeError('Isolated Fabric libraries changed between readiness and shutdown or restarts')
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--terrain-refresh', action='store_true',
                        help='Check that a legacy refresh policy is not acted on and restarts keep a disposable world')
    args = parser.parse_args()
    source = ROOT / 'server'
    if launcher.properties(source / 'fabric/eula.txt').get('eula') != 'true':
        raise RuntimeError('Full-stack check requires existing owner EULA acceptance; this test never accepts it.')
    root = ROOT / ('research/terrain-refresh-smoke' if args.terrain_refresh else 'research/fullstack-smoke')
    if args.terrain_refresh:
        # Each invocation creates a separate disposable save; never reset a prior
        # test or the playable world just to simplify the fixture.
        root = root / uuid.uuid4().hex
    root.mkdir(parents=True, exist_ok=True)
    # Hash the candidate once, then require every isolated copy to match those exact bytes.
    lock_bytes, tested = tested_inputs(ROOT)
    locked = lock_entries(json.loads(lock_bytes))
    for path, expected in locked:
        file = root / path;file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / path, file)
        pinned(root, path, expected)
    (root / 'dependencies.lock.json').write_bytes(lock_bytes)
    for name in ['runtime.lock.json','fabric/eula.txt']:
        shutil.copy2(source / name, root / name)
    if world_refresh.digest(root / 'runtime.lock.json') != tested['server/runtime.lock.json']:
        raise RuntimeError('runtime.lock.json changed while it was copied')
    java_executable = Path(shutil.which(args.java) or args.java).resolve()
    if not java_executable.is_file():
        raise RuntimeError('Java executable not found: ' + args.java)
    java_sha256 = world_refresh.digest(java_executable)
    version = subprocess.run([str(java_executable), '-version'], capture_output=True, text=True, timeout=15, check=True)
    version_lines = (version.stderr + '\n' + version.stdout).splitlines()
    version_lines = [line.strip() for line in version_lines
                     if line.startswith('openjdk version ') or line.startswith('java version ')]
    if not version_lines:
        raise RuntimeError('Java version is unavailable')
    java_version = version_lines[0][:500]
    source_libraries = library_hashes(source)
    if (source / 'fabric/libraries').is_dir():
        shutil.copytree(source / 'fabric/libraries', root / 'fabric/libraries', dirs_exist_ok=True)
    verify_runtime_libraries(root, source_libraries)
    observed_libraries = []
    preserved = {}
    policy_bytes = None
    preservation_checks = []

    # Explicit checks, not asserts, so `python -O` cannot skip them before the marker.
    def unchanged_policy():
        if list((root / 'backups').glob('Terrain-Refresh-*')) or (root / world_refresh.JOURNAL).exists():
            raise RuntimeError('Ordinary start regenerated terrain')
        if policy_bytes is None or (root / world_refresh.POLICY).read_bytes() != policy_bytes:
            raise RuntimeError('Legacy refresh policy was rewritten')

    for attempt in range(3 if args.terrain_refresh else 2):
        if args.terrain_refresh:
            verify_cycle_inputs(ROOT, root, locked, lock_bytes, tested, java_executable, java_sha256, source_libraries)
            if observed_libraries:
                verify_runtime_libraries(root, source_libraries, observed_libraries[0])
        if args.terrain_refresh and attempt == 1:
            world = root / 'fabric/world'
            (world / 'players/data').mkdir(parents=True, exist_ok=True)
            # This native stats fixture belongs to no authenticated player.
            sentinel = world / 'players/stats/11111111-1111-1111-1111-111111111111.json'
            sentinel.parent.mkdir(parents=True, exist_ok=True)
            sentinel.write_text('{"stats":{"minecraft:custom":{"minecraft:jump":37}},"DataVersion":5023}')
            sys.path.insert(0, str(ROOT / 'tests'))
            from test_terrain_storage import nbt
            player_id = '11111111-1111-1111-1111-111111111111'
            (world / 'players/data' / (player_id + '.dat')).write_bytes(gzip.compress(nbt({
                'Dimension': 'convergence:creative', 'Pos': [528.5, 161.0, 528.5],
                'DataVersion': 5023, 'Inventory': [{'id': 'minecraft:diamond', 'count': 37}],
                'InfinityModes': {'active': 'CREATIVE', 'profiles': {'HUB': {'place': json.dumps({
                    'dimension': 'convergence:hub', 'x': 0.5, 'y': 81, 'z': 0.5})},
                    'MINIGAMES': {'place': json.dumps({'dimension': 'convergence:minigames',
                        'x': 0.5, 'y': 81, 'z': 0.5})}}}})))
            (world / 'infinity-agents.json').write_text(json.dumps({'format': 2, 'agents': {
                '22222222-2222-2222-2222-222222222222': {'owner': player_id, 'name': 'refresh-check',
                 'mode': 'FOLLOW', 'profile': 'REGULAR', 'dimension': 'convergence:creative',
                 'x': 256.5, 'y': 161, 'z': 256.5}}}))
            preserved = {p.relative_to(world).as_posix(): world_refresh.digest(p)
                         for p in (world / 'players').rglob('*') if p.is_file()}
            # A legacy opt-in with a pending request must not be acted on or rewritten.
            world_refresh.atomic_json(root / world_refresh.POLICY,
                                      dict(format=1, enabled=True, last_release='previous', refresh_next_start=True))
            policy_bytes = (root / world_refresh.POLICY).read_bytes()
        with socket.socket() as tcp, socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
            tcp.bind(('127.0.0.1',0));udp.bind(('127.0.0.1',0));java_port=tcp.getsockname()[1];bedrock_port=udp.getsockname()[1]
        options=launcher.parse_args(['--java',args.java,'--bind','127.0.0.1','--java-port',str(java_port),'--bedrock-port',str(bedrock_port),'--memory','2G'])
        options.community=community.settings({'server_name':'Infinity Armor Test'})
        stop=threading.Event(); ready=[]; console=[]
        def on_ready(addresses):
            if args.terrain_refresh:
                verify_cycle_inputs(ROOT, root, locked, lock_bytes, tested, java_executable, java_sha256, source_libraries)
                observed = verify_runtime_libraries(root, source_libraries, observed_libraries[0] if observed_libraries else None)
                observed_libraries.append(observed)
            ready.append(True)
            if launcher.properties(root / 'fabric/server.properties').get('online-mode') != 'true':
                raise RuntimeError('Java online authentication was not enabled')
            magic=bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
            packet=b'\x01'+struct.pack('>q',int(time.time()*1000))+magic+struct.pack('>q',12345678)
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as client:
                client.settimeout(5);client.sendto(packet,('127.0.0.1',bedrock_port));data,_=client.recvfrom(4096)
            if not data or data[0] != 0x1c:
                raise RuntimeError('Bedrock status response was invalid')
            if not (root/'fabric/crossplay-export/infinity-items.json').is_file():
                raise RuntimeError('Crossplay item export is missing')
            creative = launcher.verify_creative_ready(root)
            catalogue = json.loads((root/'fabric/crossplay-export/infinity-catalogue.json').read_text())
            canonical_paths = {path for path, count in catalogue_item_count_pairs(catalogue)}
            if type(creative['items']) is not int or creative['items'] != len(canonical_paths):
                raise RuntimeError('Creative catalogue did not cover the canonical items')
            print('Full-stack Infinity Creative catalogue ready, run', attempt + 1)
            if not (root/'fabric/world/infinity-built-in-maps.json').is_file():
                raise RuntimeError('Built-in maps were not saved')
            if not (root/'fabric/world/infinity-built-in-lobbies.json').is_file():
                raise RuntimeError('Built-in lobbies were not saved')
            # A saved hub is loaded lazily on a real player's first join. Startup
            # alone misses callbacks that wait recursively on the loading chunk.
            send=console[0]
            send('execute in convergence:hub run forceload add -64 -64 64 64')
            marker='INFINITY_COLD_LOBBY_OK_'+str(attempt)
            command=('execute in convergence:hub if loaded -10 81 -10 '
                     'if loaded 58 81 58 if loaded -58 81 10 if loaded 10 81 -58 '
                     'unless block -12 80 -12 minecraft:air unless block 60 80 60 minecraft:air '
                     'run say '+marker)
            deadline=time.monotonic()+25
            while time.monotonic()<deadline:
                send(command)
                if '[Server] '+marker in (root/'fabric/launcher.log').read_text():
                    break
                time.sleep(.25)
            else:
                raise RuntimeError('Cold lobby chunks did not load while the server remained responsive')
            send('execute in convergence:hub run forceload remove -64 -64 64 64')
            print('Full-stack cold lobby load remained responsive, run',attempt+1)
            if args.terrain_refresh:
                send('execute in convergence:creative run forceload add 0 0')
                if attempt:
                    send('execute in convergence:creative run forceload add 250 250 279 279')
                    send('execute in minecraft:the_end run forceload add 0 0')
                    # Exercise native gravity/damage for over 200 ticks, not
                    # just the instant the preserved entities are loaded.
                    time.sleep(11)
                    marker = 'INFINITY_TERRAIN_RETENTION_OK_' + str(attempt)
                    deadline = time.monotonic() + 25
                    while time.monotonic() < deadline:
                        send('execute in convergence:creative if block 8 160 8 minecraft:diamond_block'
                             + ' if block 256 160 256 minecraft:stone'
                             + ' if entity @e[type=minecraft:iron_golem,tag=infinity_agent]'
                             + ' if entity @e[type=minecraft:wolf,tag=refresh_pet]'
                             + ' run execute in minecraft:the_end if block 8 80 8 minecraft:end_portal run say ' + marker)
                        if '[Server] ' + marker in (root / 'fabric/launcher.log').read_text():
                            break
                        time.sleep(.25)
                    else:
                        raise RuntimeError('Saved build, helper, pet or End portal was not kept across restart')
                    world = root / 'fabric/world'
                    if not preserved or any(world_refresh.digest(world / name) != value for name, value in preserved.items()):
                        raise RuntimeError('Offline player files changed')
                    unchanged_policy()
                    preservation_checks.append(attempt)
                    send('execute in convergence:creative run forceload remove 250 250 279 279')
                    send('execute in minecraft:the_end run forceload remove 0 0')
                else:
                    send('execute in convergence:creative run forceload add 250 250 279 279')
                    send('execute in convergence:creative run fill 250 160 250 279 160 279 minecraft:stone')
                    send('execute in convergence:creative run summon minecraft:iron_golem 256.5 161 256.5 {UUID:[I;572662306,572662306,572662306,572662306],Tags:["refresh_seed"],NoAI:1b,PersistenceRequired:1b}')
                    send('execute in convergence:creative run summon minecraft:wolf 272.5 161 272.5 {UUID:[I;858993459,858993459,858993459,858993459],Owner:[I;286331153,286331153,286331153,286331153],Tags:["refresh_pet"],Sitting:1b,NoAI:1b,PersistenceRequired:1b}')
                    marker = 'INFINITY_TERRAIN_HELPER_SEED_OK'
                    deadline = time.monotonic() + 25
                    while time.monotonic() < deadline:
                        send('execute in convergence:creative run tag @e[tag=refresh_seed,type=minecraft:iron_golem] add infinity_agent')
                        send('execute in convergence:creative if entity @e[tag=infinity_agent,type=minecraft:iron_golem] if entity @e[tag=refresh_pet,type=minecraft:wolf] run say ' + marker)
                        if '[Server] ' + marker in (root / 'fabric/launcher.log').read_text():
                            break
                        time.sleep(.25)
                    else:
                        raise RuntimeError('Disposable helper/pet chunks never became entity-ticking')
                    send('execute in convergence:creative run forceload remove 250 250 279 279')
                    send('execute in minecraft:the_end run forceload add 0 0')
                    send('execute in minecraft:the_end run setblock 8 80 8 minecraft:end_portal')
                    send('execute in minecraft:the_end run forceload remove 0 0')
                send('execute in convergence:creative run setblock 8 160 8 minecraft:diamond_block')
                marker = 'INFINITY_TERRAIN_SEED_OK_' + str(attempt)
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline:
                    send('execute in convergence:creative if block 8 160 8 minecraft:diamond_block run say ' + marker)
                    if '[Server] ' + marker in (root / 'fabric/launcher.log').read_text():
                        break
                    time.sleep(.25)
                else:
                    raise RuntimeError('Disposable terrain seed failed')
                send('execute in convergence:creative run forceload remove 0 0')
            stop.set()
        launcher.run_server(options,stop,on_ready,root,console=False,on_console_ready=console.append)
        if args.terrain_refresh:
            if len(observed_libraries) != attempt + 1:
                raise RuntimeError('World-preservation run did not reach readiness')
            verify_cycle_inputs(ROOT, root, locked, lock_bytes, tested, java_executable, java_sha256, source_libraries)
            verify_runtime_libraries(root, source_libraries, observed_libraries[0])
        if not ready or (root/'launcher.lock').exists():
            raise RuntimeError('Server failed to reach readiness or release its launcher lock')
        if not (root/'fabric/world/level.dat').is_file():
            raise RuntimeError('World level.dat was not saved')
        log=(root/'fabric/launcher.log').read_text()
        for mode in ['creative','hardcore','minigames','adventure','hub']:
            if 'convergence:'+mode not in log:
                raise RuntimeError('Production dimension not saved: '+mode)
        if 'ERROR' in log:
            raise RuntimeError('Fabric launcher log contains errors')
        print('Full-stack startup and clean shutdown passed, run',attempt+1)
    if not community.backups(root):
        raise RuntimeError('Restart did not create a stopped-world backup')
    if args.terrain_refresh:
        # Both restarts with the legacy policy must have completed every in-game check.
        if preservation_checks != [1, 2]:
            raise RuntimeError('World-preservation checks did not run on both restarts')
        unchanged_policy()
        print(provenance_line(tested, java_sha256, java_version, observed_libraries[0], 3))
        print('World preservation passed: with a legacy enabled/pending refresh policy, no terrain was regenerated and the policy was left unchanged; '
              'the placed block, stone floor, helper, pet and End portal remained across two restarts; offline player files stayed byte-identical.')
        print('World preservation test root:', root)
    print('Full-stack lifecycle passed: real Fabric + Geyser, isolated mode worlds, authenticated configuration, UDP response, restart, backup, and clean shutdown. No player login tested.')

if __name__=='__main__':main()

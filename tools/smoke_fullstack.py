#!/usr/bin/env python3
"""Test the real launcher in an isolated loopback world using existing owner EULA acceptance."""
import argparse
import json
from pathlib import Path
import shutil
import socket
import struct
import sys
import threading
import time
import uuid
import gzip

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server'))
import server as launcher
import community
import world_refresh


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--terrain-refresh', action='store_true',
                        help='Check owner opt-in regeneration and same-release retention in a disposable world')
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
    lock = json.loads((source / 'dependencies.lock.json').read_text())
    for entry in lock['downloads'] + lock['bundled']:
        file = root / entry['path'];file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / entry['path'], file)
    for name in ['dependencies.lock.json','runtime.lock.json','fabric/eula.txt']:
        shutil.copy2(source / name, root / name)
    if (source / 'fabric/libraries').is_dir():
        shutil.copytree(source / 'fabric/libraries', root / 'fabric/libraries', dirs_exist_ok=True)
    preserved = {}
    for attempt in range(3 if args.terrain_refresh else 2):
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
            world_refresh.atomic_json(root / world_refresh.POLICY,
                                      dict(format=1, enabled=True, last_release='previous', refresh_next_start=False))
        with socket.socket() as tcp, socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
            tcp.bind(('127.0.0.1',0));udp.bind(('127.0.0.1',0));java_port=tcp.getsockname()[1];bedrock_port=udp.getsockname()[1]
        options=launcher.parse_args(['--java',args.java,'--bind','127.0.0.1','--java-port',str(java_port),'--bedrock-port',str(bedrock_port),'--memory','2G'])
        options.community=community.settings({'server_name':'Infinity Armor Test'})
        stop=threading.Event(); ready=[]; console=[]
        def on_ready(addresses):
            ready.append(True)
            assert launcher.properties(root / 'fabric/server.properties')['online-mode']=='true'
            magic=bytes.fromhex('00ffff00fefefefefdfdfdfd12345678')
            packet=b'\x01'+struct.pack('>q',int(time.time()*1000))+magic+struct.pack('>q',12345678)
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as client:
                client.settimeout(5);client.sendto(packet,('127.0.0.1',bedrock_port));data,_=client.recvfrom(4096)
            assert data[0]==0x1c
            assert (root/'fabric/crossplay-export/infinity-items.json').is_file()
            assert (root/'fabric/world/infinity-built-in-maps.json').is_file()
            assert (root/'fabric/world/infinity-built-in-lobbies.json').is_file()
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
                expected = 'minecraft:air' if attempt == 1 else 'minecraft:diamond_block'
                if attempt:
                    send('execute in convergence:creative run forceload add 250 250 279 279')
                    send('execute in minecraft:the_end run forceload add 0 0')
                    # Exercise native gravity/damage for over 200 ticks, not
                    # just the instant the preserved entities are loaded.
                    time.sleep(11)
                    marker = 'INFINITY_TERRAIN_RETENTION_OK_' + str(attempt)
                    deadline = time.monotonic() + 25
                    while time.monotonic() < deadline:
                        send('execute in convergence:creative if block 8 160 8 ' + expected
                             + ' if block 256 160 256 minecraft:stone'
                             + ' if entity @e[type=minecraft:iron_golem,tag=infinity_agent]'
                             + ' if entity @e[type=minecraft:wolf,tag=refresh_pet]'
                             + ' run execute in minecraft:the_end if block 8 80 8 minecraft:end_portal run say ' + marker)
                        if '[Server] ' + marker in (root / 'fabric/launcher.log').read_text():
                            break
                        time.sleep(.25)
                    else:
                        raise RuntimeError('Regeneration or same-release terrain retention failed')
                    world = root / 'fabric/world'
                    assert all(world_refresh.digest(world / name) == value for name, value in preserved.items())
                    archives = list((root / 'backups').glob('Terrain-Refresh-*'))
                    assert len(archives) == 1, 'Ordinary restart repeated regeneration'
                    manifest = json.loads((archives[0] / 'MANIFEST.json').read_text())
                    assert manifest['state'] == 'committed'
                    assert all(world_refresh.digest(world / name) == value for name, value in manifest['preserved_files'].items()
                               if name.startswith('players/'))
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
        assert ready and not (root/'launcher.lock').exists()
        assert (root/'fabric/world/level.dat').is_file()
        log=(root/'fabric/launcher.log').read_text()
        for mode in ['creative','hardcore','minigames','adventure','hub']:
            assert 'convergence:'+mode in log, 'Production dimension not saved: '+mode
        assert 'ERROR' not in log
        print('Full-stack startup and clean shutdown passed, run',attempt+1)
    assert community.backups(root), 'Restart did not create a stopped-world backup'
    if args.terrain_refresh:
        print('Terrain refresh passed: unprotected block erased once; player NBT/inventory and stats retained; helper, pet, supporting floor and End portal retained; full hub retained and partial courses reconciled; next same-release restart retained the new block. Test root:', root)
    print('Full-stack lifecycle passed: real Fabric + Geyser, isolated mode worlds, authenticated configuration, UDP response, restart, backup, and clean shutdown. No player login tested.')

if __name__=='__main__':main()

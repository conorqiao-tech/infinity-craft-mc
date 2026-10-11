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

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server'))
import server as launcher
import community


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    source = ROOT / 'server'
    if launcher.properties(source / 'fabric/eula.txt').get('eula') != 'true':
        raise RuntimeError('Full-stack check requires existing owner EULA acceptance; this test never accepts it.')
    root = ROOT / 'research/fullstack-smoke'
    root.mkdir(parents=True, exist_ok=True)
    lock = json.loads((source / 'dependencies.lock.json').read_text())
    for entry in lock['downloads'] + lock['bundled']:
        file = root / entry['path'];file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / entry['path'], file)
    for name in ['dependencies.lock.json','runtime.lock.json','fabric/eula.txt']:
        shutil.copy2(source / name, root / name)
    if (source / 'fabric/libraries').is_dir():
        shutil.copytree(source / 'fabric/libraries', root / 'fabric/libraries', dirs_exist_ok=True)
    for attempt in range(2):
        with socket.socket() as tcp, socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
            tcp.bind(('127.0.0.1',0));udp.bind(('127.0.0.1',0));java_port=tcp.getsockname()[1];bedrock_port=udp.getsockname()[1]
        options=launcher.parse_args(['--java',args.java,'--bind','127.0.0.1','--java-port',str(java_port),'--bedrock-port',str(bedrock_port),'--memory','2G'])
        options.community=community.settings({'server_name':'Infinity Armor Test'})
        stop=threading.Event(); ready=[]
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
            stop.set()
        launcher.run_server(options,stop,on_ready,root,console=False)
        assert ready and not (root/'launcher.lock').exists()
        assert (root/'fabric/world/level.dat').is_file()
        log=(root/'fabric/launcher.log').read_text()
        for mode in ['creative','hardcore','minigames','adventure','hub']:
            assert 'convergence:'+mode in log, 'Production dimension not saved: '+mode
        assert 'ERROR' not in log
        print('Full-stack startup and clean shutdown passed, run',attempt+1)
    assert community.backups(root), 'Restart did not create a stopped-world backup'
    print('Full-stack lifecycle passed: real Fabric + Geyser, isolated mode worlds, authenticated configuration, UDP response, restart, backup, and clean shutdown. No player login tested.')

if __name__=='__main__':main()

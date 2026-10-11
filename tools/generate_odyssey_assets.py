#!/usr/bin/env python3
"""Build deterministic native structure templates from the owner's sanitized blueprint."""
import argparse
import gzip
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BLUEPRINT = ROOT / 'artwork/odyssey/blueprint.json'
TARGET = ROOT / 'java/src/main/resources/data/convergence/structure/odyssey'

def kind(value):
    if isinstance(value, bool): return 1
    if isinstance(value, int): return 3
    if isinstance(value, str): return 8
    if isinstance(value, list): return 9
    if isinstance(value, dict): return 10
    raise TypeError(type(value))

def string(value):
    data=value.encode('utf-8')
    return struct.pack('>H',len(data))+data

def payload(value):
    t=kind(value)
    if t==1: return struct.pack('>b',value)
    if t==3: return struct.pack('>i',value)
    if t==8: return string(value)
    if t==9:
        child=kind(value[0]) if value else 10
        assert all(kind(v)==child for v in value)
        return bytes([child])+struct.pack('>i',len(value))+b''.join(payload(v) for v in value)
    return b''.join(bytes([kind(v)])+string(k)+payload(v) for k,v in value.items())+b'\x00'

def template(blueprint,theme):
    shell,accent,glow,core=blueprint['themes'][theme]
    swaps={'minecraft:command_block':shell,'minecraft:redstone_block':accent,
           'minecraft:glowstone':glow,'minecraft:sea_lantern':core}
    palette=[]; blocks=[]
    for entry in blueprint['blocks']:
        state=dict(entry['state']); original=state['Name']
        if original in swaps: state={'Name':swaps[original]}
        # Keep the explicitly requested repeating command below the cache.
        nbt=None
        if original=='minecraft:repeating_command_block':
            state={'Name':original,'Properties':{'facing':'up','conditional':'false'}}
            nbt={'id':'minecraft:command_block','Command':blueprint['repeating_command'],
                 'auto':bool(blueprint['repeating_auto']),'TrackOutput':False,'UpdateLastExecution':True}
        elif original=='minecraft:chest':
            state={'Name':original,'Properties':{'facing':'south','type':'single','waterlogged':'false'}}
            nbt={'id':'minecraft:chest','Items':blueprint['chest_items']}
        # 26.3's native BlockState codec uses id/properties, unlike the captured 1.21.11 palette.
        state={'id':state['Name'],**({'properties':state['Properties']} if 'Properties' in state else {})}
        if state not in palette: palette.append(state)
        block={'pos':entry['pos'],'state':palette.index(state)}
        if nbt is not None: block['nbt']=nbt
        blocks.append(block)
    assert sum(palette[b['state']]['id']=='minecraft:chest' for b in blocks)==1
    root={'DataVersion':5023,'size':blueprint['size'],'palette':palette,'blocks':blocks,'entities':[]}
    return gzip.compress(b'\x0a\x00\x00'+payload(root),mtime=0)

def verify():
    blueprint=json.loads(BLUEPRINT.read_text())
    for theme in blueprint['themes']:
        path=TARGET/(theme+'.nbt')
        if not path.is_file() or path.read_bytes()!=template(blueprint,theme):
            raise RuntimeError('Stale Odyssey template: '+theme)

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--check',action='store_true'); args=parser.parse_args()
    blueprint=json.loads(BLUEPRINT.read_text())
    if args.check:
        verify()
    for theme in blueprint['themes']:
        path=TARGET/(theme+'.nbt'); data=template(blueprint,theme)
        if not args.check:
            path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(data)
    print(('Verified' if args.check else 'Generated')+f" {len(blueprint['themes'])} Odyssey templates with one chest each.")

if __name__=='__main__': main()

#!/usr/bin/env python3
"""Build review-only standalone landscape packs; never install them."""
import argparse
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'artwork/infinity-landscape'
OUTPUTS = (('java', 'Infinity-Landscape-Java-preview.zip'),
           ('bedrock', 'Infinity-Landscape-Bedrock-preview.mcpack'))

def files_for(edition):
    directory = ART / edition
    if not directory.is_dir() or directory.is_symlink(): raise ValueError('Missing regular preview source directory')
    files = sorted(p for p in directory.rglob('*') if p.is_file())
    if any(p.is_symlink() or any(parent.is_symlink() for parent in p.parents if parent != directory.parent) for p in files):
        raise ValueError('Linked preview source is not supported')
    expected_files = 75 if edition == 'java' else 74  # Java also supplies cluster mipmap metadata.
    if len(files) != expected_files:
        raise ValueError(f'Expected {expected_files} preview files for {edition}')
    manifest = directory / ('pack.mcmeta' if edition == 'java' else 'manifest.json')
    json.loads(manifest.read_text())
    return [(p.relative_to(directory).as_posix(), p.read_bytes()) for p in files]

def build(output):
    if not output.is_dir() or output.is_symlink(): raise ValueError('Output must be an existing, non-symlink directory')
    sources = [(edition, name, files_for(edition)) for edition, name in OUTPUTS]
    if any((output/name).exists() or (output/name).is_symlink() for _, name, _ in sources):
        raise FileExistsError('Preview output already exists; choose an empty directory')
    for _, name, files in sources:
        with (output/name).open('xb') as target, zipfile.ZipFile(target, 'w') as archive:
            for path, data in files:
                info = zipfile.ZipInfo(path, (2026, 11, 15, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
    return [output/name for _, name, _ in sources]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='existing private review directory')
    args = parser.parse_args()
    for path in build(args.output): print(path)

if __name__ == '__main__': main()

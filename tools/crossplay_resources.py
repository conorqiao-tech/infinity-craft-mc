"""Preserve vanilla equipment assets when preparing crossplay resources."""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile


ELYTRA_OVERRIDES = frozenset({
    "attachables/elytra.json",
    "textures/items/elytra.png",
    "textures/items/broken_elytra.png",
    "textures/models/armor/elytra.png",
    "textures/entity/elytra.png",
})


def vanilla_equipment_override(name):
    return (name in ELYTRA_OVERRIDES
            or re.fullmatch(r"textures/models/armor/(netherite|leather)_[12]\.png", name) is not None
            or re.fullmatch(r"textures/items/(netherite|leather)_(helmet|chestplate|leggings|boots)\.png", name) is not None)


def resource_version(version):
    match = re.fullmatch(r"([0-9]+)\.([0-9]+)\.[0-9]+-explore\.([0-9]+)", version)
    if not match:
        raise ValueError("Expected an Infinity exploration release version")
    # Use the actual release minor, rather than the old hard-coded 12. This
    # repair advances explore.39 from [2,12,39] to [2,13,39]; later releases keep
    # increasing naturally without changing the mod or refreshing terrain.
    return [int(value) for value in match.groups()]


def repair_pack(source, destination, version):
    """Produce a resource-only repair, preserving every other entry's bytes."""
    source, destination = Path(source), Path(destination)
    with zipfile.ZipFile(source) as archive:
        records = archive.infolist()
        if len(records) > 4096 or sum(item.file_size for item in records) > 128 * 1024 * 1024:
            raise ValueError("Resource pack exceeds the bounded repair size")
        seen, files = set(), {}
        for item in records:
            path = PurePosixPath(item.filename)
            if (path.is_absolute() or ".." in path.parts or not path.parts
                    or item.is_dir() or stat.S_ISLNK(item.external_attr >> 16)
                    or item.filename in seen):
                raise ValueError("Unsafe or duplicate resource entry")
            seen.add(item.filename)
            if not vanilla_equipment_override(item.filename):
                files[item.filename] = archive.read(item)
        if archive.testzip() is not None:
            raise ValueError("Corrupt resource pack")
    manifest = json.loads(files["manifest.json"])
    if (not isinstance(manifest.get("modules"), list) or not manifest["modules"]
            or any(module.get("type") != "resources" for module in manifest["modules"])
            or manifest.get("dependencies")):
        raise ValueError("Expected a standalone crossplay resource pack")
    revision = resource_version(version)
    manifest["header"]["version"] = revision
    for module in manifest["modules"]:
        module["version"] = revision
    files["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".partial")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as output:
            for name, data in sorted(files.items()):
                item = zipfile.ZipInfo(name, (2026, 9, 22, 0, 0, 0))
                item.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(item, data)
        with zipfile.ZipFile(temporary) as output:
            if output.testzip() is not None:
                raise ValueError("Repaired archive verification failed")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return {"removed": sorted(seen - set(files)), "version": revision, "entries": len(files)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    print(json.dumps(repair_pack(args.input, args.output, args.version)))


if __name__ == "__main__":
    main()

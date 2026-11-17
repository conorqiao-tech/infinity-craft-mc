"""Read-only verification of a backup's embedded source-digest manifest.

This checks archive content against a manifest stored in the same ZIP. It is
not a signature, a trusted-writer boundary, or proof of an atomic snapshot.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import zipfile

if __name__ != "__main__":
    raise ImportError("Run verify_backup.py as a separate process; do not import it into a launcher.")

INFO_NAME = "BACKUP_INFO.json"
MAX_INFO_BYTES = 64 * 1024 * 1024
SERVER_FILES = {"server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json"}


class MissingDigestManifest(ValueError):
    """An older archive has no recorded source digests to compare."""


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("The backup manifest has duplicate JSON keys.")
        result[key] = value
    return result


def _path_name(value, *, directory):
    if not isinstance(value, str) or not value or "\0" in value or value.startswith("/"):
        raise ValueError("The backup manifest contains an invalid entry name.")
    if value.endswith("/") != directory:
        raise ValueError("The backup manifest has a mismatched entry type.")
    pieces = value[:-1].split("/") if directory else value.split("/")
    if any(piece in ("", ".", "..") for piece in pieces):
        raise ValueError("The backup manifest contains an invalid entry name.")
    if value == INFO_NAME:
        raise ValueError("The backup manifest overlaps its own metadata.")
    return value


def _regular_identity(info):
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError("The archive must be a regular file with one link.")
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def _entry_type(info, *, directory):
    if info.is_dir() != directory:
        raise ValueError("A backup entry has the wrong type.")
    if info.create_system != 3:
        raise ValueError("A backup entry has unsupported file metadata.")
    mode_type = stat.S_IFMT(info.external_attr >> 16)
    allowed = (0, stat.S_IFDIR) if directory else (0, stat.S_IFREG)
    if mode_type not in allowed:
        raise ValueError("A backup entry is not a regular file or directory.")


def _check_local_header(fd, info):
    """Reject local metadata inconsistent with the central ZIP entry."""
    fixed = os.pread(fd, 30, info.header_offset)
    if len(fixed) != 30:
        raise ValueError("A backup local header is incomplete.")
    signature, _version, flags, method, local_time, local_date, crc, packed, size, name_len, extra_len = struct.unpack(
        "<4s5H3I2H", fixed)
    year, month, day, hour, minute, second = info.date_time
    expected_time = (hour << 11) | (minute << 5) | (second // 2)
    expected_date = ((year - 1980) << 9) | (month << 5) | day
    if (signature != b"PK\x03\x04" or flags != info.flag_bits or flags & 8
            or method != info.compress_type or crc != info.CRC
            or local_time != expected_time or local_date != expected_date):
        raise ValueError("A backup local header does not match its central entry.")
    extra = os.pread(fd, extra_len, info.header_offset + 30 + name_len)
    if len(extra) != extra_len:
        raise ValueError("A backup local header is incomplete.")
    position = 0
    zip64 = None
    while position < len(extra):
        if position + 4 > len(extra):
            raise ValueError("A backup local header has malformed extra data.")
        kind, length = struct.unpack_from("<HH", extra, position)
        position += 4
        if position + length > len(extra):
            raise ValueError("A backup local header has malformed extra data.")
        if kind == 1:
            if zip64 is not None:
                raise ValueError("A backup local header has duplicate ZIP64 data.")
            zip64 = extra[position:position + length]
        position += length
    if packed == 0xffffffff or size == 0xffffffff:
        if packed != 0xffffffff or size != 0xffffffff or zip64 is None or len(zip64) < 16:
            raise ValueError("A backup local header has incomplete ZIP64 sizes.")
        size, packed = struct.unpack_from("<QQ", zip64)
    if size != info.file_size or packed != info.compress_size:
        raise ValueError("A backup local header has inconsistent sizes.")


def _verify_backup(path):
    """Verify a named archive without extracting or modifying it."""
    if os.name != "posix" or not hasattr(os, "O_NOFOLLOW"):
        raise ValueError("This system cannot safely open an archive for verification.")
    path = Path(path)
    before = _regular_identity(os.stat(path, follow_symlinks=False))
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_NONBLOCK", 0))
    try:
        with os.fdopen(fd, "rb") as stream:
            fd = None
            if _regular_identity(os.fstat(stream.fileno())) != before:
                raise ValueError("The archive changed while it was opened.")
            with zipfile.ZipFile(stream) as archive:
                entries = archive.infolist()
                by_name = {entry.filename: entry for entry in entries}
                if len(by_name) != len(entries):
                    raise ValueError("The backup has duplicate entry names.")
                if INFO_NAME not in by_name:
                    raise MissingDigestManifest("This older backup has no digest manifest; retain it for separate review.")
                info = by_name[INFO_NAME]
                _entry_type(info, directory=False)
                if info.file_size > MAX_INFO_BYTES:
                    raise ValueError("The backup manifest exceeds the supported limit.")
                info_data = json.loads(archive.read(info).decode("utf-8"), object_pairs_hook=_unique_object)
                if not isinstance(info_data, dict):
                    raise ValueError("The backup information has an unsupported format.")
                if "digest_manifest" not in info_data:
                    raise MissingDigestManifest("This older backup has no digest manifest; retain it for separate review.")
                for entry in entries:
                    _check_local_header(stream.fileno(), entry)
                if (set(info_data) != {"format", "created_utc", "world", "digest_manifest"}
                        or type(info_data["format"]) is not int or info_data["format"] != 1
                        or not isinstance(info_data["created_utc"], str)):
                    raise ValueError("The backup information has an unsupported format.")
                world = _path_name(info_data["world"], directory=False)
                if world.startswith(INFO_NAME + "/"):
                    raise ValueError("The recorded world overlaps backup metadata.")
                manifest = info_data["digest_manifest"]
                if (not isinstance(manifest, dict)
                        or set(manifest) != {"format", "algorithm", "files", "empty_directories"}
                        or type(manifest["format"]) is not int or manifest["format"] != 1
                        or manifest["algorithm"] != "sha256"
                        or not isinstance(manifest["files"], list)
                        or not isinstance(manifest["empty_directories"], list)):
                    raise ValueError("The backup digest manifest has an unsupported format.")
                expected = {}
                for item in manifest["files"]:
                    if (not isinstance(item, dict) or set(item) != {"path", "sha256"}
                            or not isinstance(item["sha256"], str)
                            or re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is None):
                        raise ValueError("The backup digest manifest has an invalid file record.")
                    name = _path_name(item["path"], directory=False)
                    if not name.startswith(world + "/") and name not in SERVER_FILES:
                        raise ValueError("A backup file is outside the recorded world and server files.")
                    if name in expected:
                        raise ValueError("The backup digest manifest has duplicate entries.")
                    expected[name] = item["sha256"]
                directories = set()
                for value in manifest["empty_directories"]:
                    name = _path_name(value, directory=True)
                    if not name.startswith(world + "/"):
                        raise ValueError("A backup directory is outside the recorded world.")
                    if name in directories or name in expected:
                        raise ValueError("The backup digest manifest has duplicate entries.")
                    directories.add(name)
                # A file cannot also be an ancestor of another recorded entry.
                for name in set(expected) | directories:
                    parts = name.rstrip("/").split("/")
                    if any("/".join(parts[:i]) in expected for i in range(1, len(parts))):
                        raise ValueError("A backup file overlaps another recorded entry.")
                    if any("/".join(parts[:i]) + "/" in directories for i in range(1, len(parts))):
                        raise ValueError("A recorded empty directory contains another entry.")
                    if name.endswith("/") and name[:-1] in expected:
                        raise ValueError("A backup file overlaps a recorded directory.")
                if set(by_name) != set(expected) | directories | {INFO_NAME}:
                    raise ValueError("The backup entries do not match the digest manifest.")
                for name in directories:
                    item = by_name[name]
                    _entry_type(item, directory=True)
                    if item.file_size != 0 or archive.read(item) != b"":
                        raise ValueError("A backup directory entry is not empty.")
                for name, expected_digest in expected.items():
                    item = by_name[name]
                    _entry_type(item, directory=False)
                    digest = hashlib.sha256()
                    with archive.open(item) as data:
                        while True:
                            block = data.read(1024 * 1024)
                            if not block:
                                break
                            digest.update(block)
                    if digest.hexdigest() != expected_digest:
                        raise ValueError("A backup file does not match its recorded digest.")
            if (_regular_identity(os.fstat(stream.fileno())) != before
                    or _regular_identity(os.stat(path, follow_symlinks=False)) != before):
                raise ValueError("The archive changed while it was being verified.")
    finally:
        if fd is not None:
            os.close(fd)
    return {"status": "digest_check_passed", "files": len(expected), "empty_directories": len(directories)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="Path to a stopped-world backup ZIP")
    args = parser.parse_args(argv)
    try:
        result = _verify_backup(args.archive)
    except MissingDigestManifest as error:
        print(str(error), file=sys.stderr)
        return 2
    except Exception as error:
        print("Backup verification failed: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())

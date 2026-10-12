#!/usr/bin/env python3
"""Create an immutable fixture JAR in the selected installed game namespace."""
import argparse
import io
import json
import os
from pathlib import Path
import stat
import tomllib
import zipfile

from artifact_checks import VerificationError
from class_relocation import MAX_CLASS, NEW, LEGACY, FABRIC_LEGACY, relocate_class, relocate_name
from hmcl_pack import publish
from native_bundle import strict_object

MAX_CLASSES = 512
MAX_TOTAL = 32 * 1024 * 1024


def regular_bytes(path, maximum):
    initial = path.lstat()
    if not stat.S_ISREG(initial.st_mode) or not 0 < initial.st_size <= maximum:
        raise VerificationError("fixture_relocation_input")
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or (before.st_dev, before.st_ino) != (initial.st_dev, initial.st_ino):
            raise VerificationError("fixture_relocation_input")
        value = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
        if len(value) != before.st_size or len(value) > maximum or (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise VerificationError("fixture_relocation_input_changed")
        return value


def relocated_metadata(value, destination=NEW):
    try:
        metadata = json.loads(value, object_pairs_hook=strict_object)
    except (ValueError, UnicodeError) as failure:
        raise VerificationError("fixture_relocation_metadata") from failure
    if not isinstance(metadata, dict) or metadata.get("id") not in {"p2wlan_local_game_test", "p2wlan_integrated_test"}:
        raise VerificationError("fixture_relocation_metadata")
    entrypoints = metadata.get("entrypoints")
    if not isinstance(entrypoints, dict) or not entrypoints:
        raise VerificationError("fixture_relocation_metadata")
    for entries in entrypoints.values():
        if not isinstance(entries, list) or not entries or any(not isinstance(entry, str) for entry in entries):
            raise VerificationError("fixture_relocation_metadata")
    metadata["entrypoints"] = {kind: [relocate_name(entry, destination) for entry in entries] for kind, entries in entrypoints.items()}
    return (json.dumps(metadata, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def forge_metadata(value):
    try:
        item = tomllib.loads(value.decode('utf-8'))
    except (ValueError, UnicodeError) as failure:
        raise VerificationError('fixture_relocation_metadata') from failure
    mods = item.get('mods')
    if item.get('modLoader') != 'javafml' or item.get('loaderVersion') != '[47,48)' \
            or not isinstance(mods, list) or len(mods) != 1 \
            or not isinstance(mods[0], dict) \
            or mods[0].get('modId') not in {'p2wlan_local_game_test', 'p2wlan_integrated_test'}:
        raise VerificationError('fixture_relocation_metadata')
    return value


def forge_pack_metadata(value):
    try:
        item = json.loads(value.decode('utf-8'), object_pairs_hook=strict_object)
    except (ValueError, UnicodeError) as failure:
        raise VerificationError('fixture_relocation_pack_metadata') from failure
    expected = {'pack': {'description': 'P2WLAN isolated gameplay fixture', 'pack_format': 15}}
    if json.dumps(item, sort_keys=True) != json.dumps(expected, sort_keys=True):
        raise VerificationError('fixture_relocation_pack_metadata')
    return value


def build(classes, metadata, minecraft="1.21.1", loader="fabric", pack_metadata=None):
    if minecraft not in {"1.20.1", "1.21.1"}:
        raise VerificationError("fixture_relocation_game_version")
    if loader not in {'fabric', 'forge'} or loader == 'forge' and minecraft != '1.20.1':
        raise VerificationError('fixture_relocation_loader_version')
    if (loader == 'forge') != (pack_metadata is not None):
        raise VerificationError('fixture_relocation_pack_metadata_required' if loader == 'forge' else 'fixture_relocation_pack_metadata_unexpected')
    destination = LEGACY if loader == 'forge' else FABRIC_LEGACY if minecraft == "1.20.1" else NEW
    raw = regular_bytes(metadata, 65536)
    metadata_path = 'META-INF/mods.toml' if loader == 'forge' else 'fabric.mod.json'
    members = {metadata_path: forge_metadata(raw) if loader == 'forge' else relocated_metadata(raw, destination)}
    if pack_metadata is not None:
        members['pack.mcmeta'] = forge_pack_metadata(regular_bytes(pack_metadata, 65536))
    count = 0
    total = sum(map(len, members.values()))
    for root in classes:
        if root.is_symlink() or not root.is_dir():
            raise VerificationError("fixture_relocation_directory")
        pending = [(root, 0)]
        scanned = 0
        while pending:
            directory, depth = pending.pop()
            if depth > 32:
                raise VerificationError("fixture_relocation_capacity")
            with os.scandir(directory) as entries:
                for entry in entries:
                    scanned += 1
                    if scanned > 2048 or entry.is_symlink():
                        raise VerificationError("fixture_relocation_capacity")
                    path = Path(entry.path)
                    if entry.is_dir(follow_symlinks=False):
                        pending.append((path, depth + 1))
                    elif path.suffix == ".class":
                        count += 1
                        if count > MAX_CLASSES:
                            raise VerificationError("fixture_relocation_capacity")
                        name = relocate_name(path.relative_to(root).as_posix(), destination)
                        if name in members or not name.startswith((f"io/p2wlan/mc/{loader}/fixture/", destination + "fixture/")):
                            raise VerificationError("fixture_relocation_class_owner")
                        value = relocate_class(regular_bytes(path, MAX_CLASS), destination)
                        total += len(value)
                        if total > MAX_TOTAL:
                            raise VerificationError("fixture_relocation_capacity")
                        members[name] = value
    if not count:
        raise VerificationError("fixture_relocation_empty")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, value in sorted(members.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | 0o644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, value)
    value = output.getvalue()
    if len(value) > MAX_TOTAL:
        raise VerificationError("fixture_relocation_capacity")
    return value, count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", action="append", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--pack-metadata", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--minecraft", choices=("1.20.1", "1.21.1"), default="1.21.1")
    parser.add_argument("--loader", choices=('fabric', 'forge'), default='fabric')
    args = parser.parse_args()
    try:
        value, count = build(args.classes, args.metadata, args.minecraft, args.loader, args.pack_metadata)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        publish(args.output, value)
    except (OSError, ValueError, TypeError, KeyError) as failure:
        parser.exit(1, "FAIL " + (str(failure) if isinstance(failure, VerificationError) else "FIXTURE_RELOCATION_IO") + "\n")
    print("PASS installed fixture relocation:", args.loader, args.minecraft, count, "classes")


if __name__ == "__main__":
    main()

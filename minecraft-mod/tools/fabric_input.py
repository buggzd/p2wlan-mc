#!/usr/bin/env python3
"""Append unchanged canonical Bootstrap classes after Fabric game remapping."""
import argparse
import hashlib
import importlib
import io
from pathlib import Path
import subprocess
import zipfile

from artifact_checks import VerificationError, bind_sources, classfile
from fixture_relocation import regular_bytes as bounded_bytes
from universal_jar import (BOOTSTRAP, BOOTSTRAP_CLASSES, CONFIGS, archive_bytes,
                           constant_symbols, encoded, json_value, members, publish,
                           regular_bytes)

VERIFIERS = {'1.16.5': 'verify_fabric_1165_jar', '1.18.2': 'verify_fabric_1182_jar', '1.20.1': 'verify_fabric_legacy_jar',
             '1.21.1': 'verify_mod_jar'}


def build_bytes(mapped, bootstrap, minecraft, development=False):
    if minecraft not in VERIFIERS:
        raise VerificationError('fabric_input_game_profile')
    files = members(mapped)
    if any(name.startswith(BOOTSTRAP) for name in files):
        raise VerificationError('fabric_input_existing_bootstrap')
    if len(bootstrap) > 1024 * 1024:
        raise VerificationError('fabric_input_bootstrap_size')
    canonical = members(bootstrap)
    if set(canonical) != BOOTSTRAP_CLASSES | set(CONFIGS) | {'META-INF/MANIFEST.MF'}:
        raise VerificationError('fabric_input_bootstrap_layout')
    for name, expected in CONFIGS.items():
        if encoded(json_value(canonical[name])) != encoded(expected):
            raise VerificationError('fabric_input_bootstrap_config')
    with zipfile.ZipFile(io.BytesIO(bootstrap)) as archive:
        for name in BOOTSTRAP_CLASSES:
            constant_symbols(classfile(archive, name, 61))
            files[name] = canonical[name]
    value = archive_bytes(files)
    natives = importlib.import_module(VERIFIERS[minecraft]).verify(io.BytesIO(value), development)
    bind_sources(natives, development)
    return value


def build(remapped, bootstrap, output, minecraft, development=False):
    if output.resolve() in {remapped.resolve(), bootstrap.resolve()}:
        raise VerificationError('fabric_input_output_role')
    value = build_bytes(regular_bytes(remapped), bounded_bytes(bootstrap, 1024 * 1024), minecraft, development)
    output.parent.mkdir(parents=True, exist_ok=True)
    publish(output, value)
    return hashlib.sha256(value).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--remapped', type=Path, required=True)
    parser.add_argument('--bootstrap', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--minecraft', choices=tuple(VERIFIERS), required=True)
    parser.add_argument('--development', action='store_true')
    args = parser.parse_args()
    try:
        checksum = build(args.remapped, args.bootstrap, args.output, args.minecraft, args.development)
    except (OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as failure:
        parser.exit(1, 'FAIL ' + (str(failure) if isinstance(failure, VerificationError) else 'FABRIC_INPUT_IO') + '\n')
    print('PASS Fabric input canonical Bootstrap/source binding:', args.minecraft, checksum)


if __name__ == '__main__': main()

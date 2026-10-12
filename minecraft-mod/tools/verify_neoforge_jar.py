#!/usr/bin/env python3
"""Verify the NeoForge baseline archive and source-bound portable components."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tomllib
import zipfile

from artifact_checks import (MAX_BINARY, PLATFORMS, VerificationError, bind_sources,
                             classfile, entries, executable_name,
                             metadata, native_manifest, read)
from verify_mod_jar import COMMON, TRANSPORT

MAIN = 'io/p2wlan/mc/neoforge/NeoForgeP2wlan.class'
from jarjar_artifact import WORLD_COMMON, portable

SERVER_MIXIN = 'io/p2wlan/mc/game/server/mixin/ServerConnectionMixin.class'
DEDICATED_CLASSES = (MAIN, SERVER_MIXIN, 'io/p2wlan/mc/game/server/WorldCommands.class',
                     'io/p2wlan/mc/neoforge/LocalNativeResources.class')
CLIENT_MIXIN_CONFIG = {'required': True, 'package': 'io.p2wlan.mc.game.mixin', 'compatibilityLevel': 'JAVA_21',
                       'client': ['ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin', 'MultiplayerListAccessor', 'MultiplayerScreenMixin'],
                       'injectors': {'defaultRequire': 1}}
CLIENT = 'io/p2wlan/mc/neoforge/client/NeoForgeClient.class'
CLIENT_MIXINS = tuple('io/p2wlan/mc/game/mixin/' + name + '.class' for name in CLIENT_MIXIN_CONFIG['client'])
MIXIN_CONFIG = {'required': True, 'package': 'io.p2wlan.mc.game.server.mixin',
                'compatibilityLevel': 'JAVA_21', 'mixins': ['ServerConnectionMixin'],
                'injectors': {'defaultRequire': 1}}


def descriptor(archive):
    info = tomllib.loads(read(archive, 'META-INF/neoforge.mods.toml', 65536).decode('utf-8'))
    mods = info.get('mods')
    if info.get('modLoader') != 'javafml' or info.get('loaderVersion') != '[4,)' \
            or not isinstance(mods, list) or len(mods) != 1 \
            or mods[0].get('modId') != 'p2wlan' or mods[0].get('version') != '0.1.0':
        raise VerificationError('neoforge_mod_contract')
    expected = [{'modId': 'neoforge', 'type': 'required', 'versionRange': '[21.1.256]', 'ordering': 'NONE', 'side': 'BOTH'},
                {'modId': 'minecraft', 'type': 'required', 'versionRange': '[1.21.1]', 'ordering': 'NONE', 'side': 'BOTH'}]
    if info.get('dependencies') != {'p2wlan': expected} or info.get('mixins') != [{'config': 'p2wlan.neoforge.mixins.json'}, {'config': 'p2wlan.neoforge.client.mixins.json'}]:
        raise VerificationError('neoforge_dependency_contract')
    return mods[0]['version']



def verify(path, development=False):
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        if any('/fixture/' in name or 'fixture' in name.lower() and name.endswith('.toml') for name in names):
            raise VerificationError('neoforge_test_fixture_in_artifact')
        version = descriptor(archive)
        if metadata(archive, 'p2wlan.neoforge.mixins.json') != MIXIN_CONFIG:
            raise VerificationError('neoforge_mixin_contract')
        if metadata(archive, 'p2wlan.neoforge.client.mixins.json') != CLIENT_MIXIN_CONFIG:
            raise VerificationError('neoforge_client_mixin_contract')
        for name in (CLIENT, *CLIENT_MIXINS):
            classfile(archive, name, 65)
        for name in DEDICATED_CLASSES:
            value = classfile(archive, name, 65)
            if any(prefix in value for prefix in (b'net/minecraft/client/', b'net/neoforged/neoforge/client/', b'io/p2wlan/mc/neoforge/client/', b'io/p2wlan/mc/game/client/')):
                raise VerificationError('mod_dedicated_client_reference')
        return portable(archive, version, development)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--development', action='store_true')
    args = parser.parse_args()
    try:
        natives = verify(args.archive, args.development)
        bind_sources(natives, args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"NeoForge archive failed: {str(error) if isinstance(error, VerificationError) else 'MOD_ARCHIVE_IO'}\n")
    with args.archive.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    print('PASS NeoForge artifact structure/source binding: ' + ', '.join(item['verification-platform'] for item in natives))
    print(json.dumps({'schema': 1, 'artifact_sha256': checksum, 'source_revision': natives[0]['source-revision'],
                      'version': natives[0]['version'], 'minecraft': '1.21.1', 'loader': 'neoforge', 'loader_version': '21.1.256',
                      'verification': 'archive-structure/source-binding',
                      'native_targets': [item['verification-platform'] for item in natives]}, sort_keys=True))


if __name__ == '__main__':
    main()

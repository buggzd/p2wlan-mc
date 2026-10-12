#!/usr/bin/env python3
"""Verify pinned Forge metadata, shared game code and source-bound JarJar bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tomllib
import zipfile

from artifact_checks import VerificationError, bind_sources, classfile, entries, metadata, read
from jarjar_artifact import portable

MAIN = 'io/p2wlan/mc/forge/ForgeP2wlan.class'
CLIENT = 'io/p2wlan/mc/forge/client/ForgeClient.class'
SERVER_MIXIN = 'io/p2wlan/mc/game/server/mixin/ServerConnectionMixin.class'
DEDICATED_CLASSES = (MAIN, SERVER_MIXIN, 'io/p2wlan/mc/game/server/WorldCommands.class',
                     'io/p2wlan/mc/forge/LocalNativeResources.class')
MIXIN_CONFIG = {'required': True, 'minVersion': '0.8.5', 'package': 'io.p2wlan.mc.game.server.mixin',
                'compatibilityLevel': 'JAVA_17', 'mixins': ['ServerConnectionMixin'], 'injectors': {'defaultRequire': 1}}
CLIENT_MIXIN_CONFIG = {'required': True, 'minVersion': '0.8.5', 'package': 'io.p2wlan.mc.game.mixin', 'compatibilityLevel': 'JAVA_17',
                       'client': ['ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin', 'MultiplayerListAccessor', 'MultiplayerScreenMixin'],
                       'injectors': {'defaultRequire': 1}}
CLIENT_MIXINS = tuple('io/p2wlan/mc/game/mixin/' + name + '.class' for name in CLIENT_MIXIN_CONFIG['client'])
PACK_METADATA = {'pack': {'description': 'P2WLAN', 'pack_format': 48,
                          'supported_formats': {'min_inclusive': 34, 'max_inclusive': 48}}}
BUILD_HARNESSES = ('io/p2wlan/mc/forge/LocalNativeResourcesTests', 'io/p2wlan/mc/forge/LauncherPolicyTests')


def descriptor(archive):
    value = tomllib.loads(read(archive, 'META-INF/mods.toml', 65536).decode('utf-8'))
    if value.get('modLoader') != 'javafml' or value.get('loaderVersion') != '[52,)' \
            or value.get('mods') is None or not isinstance(value['mods'], list) or len(value['mods']) != 1 \
            or value['mods'][0].get('modId') != 'p2wlan' or value['mods'][0].get('version') != '0.1.0':
        raise VerificationError('forge_mod_contract')
    expected = [{'modId': 'forge', 'mandatory': True, 'versionRange': '[52.1.16]', 'ordering': 'NONE', 'side': 'BOTH'},
                {'modId': 'minecraft', 'mandatory': True, 'versionRange': '[1.21.1]', 'ordering': 'NONE', 'side': 'BOTH'}]
    if value.get('dependencies') != {'p2wlan': expected}:
        raise VerificationError('forge_dependency_contract')
    manifest = read(archive, 'META-INF/MANIFEST.MF', 8192)
    if manifest.splitlines().count(b'MixinConfigs: p2wlan.forge.mixins.json,p2wlan.forge.client.mixins.json') != 1:
        raise VerificationError('forge_mixin_manifest')
    return value['mods'][0]['version']


def verify(path, development=False):
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        if any('/fixture/' in name or name.startswith('net/minecraftforge/launcher/p2wlan/')
               or 'fixture' in name.lower() and name.endswith('.toml')
               or any(name == harness + '.class' or name.startswith(harness + '$') for harness in BUILD_HARNESSES)
               for name in names):
            raise VerificationError('forge_test_fixture_in_artifact')
        if any(name.startswith('com/sun/jna/') for name in names):
            raise VerificationError('forge_duplicate_bootstrap_jna')
        version = descriptor(archive)
        if json.dumps(metadata(archive, 'pack.mcmeta'), sort_keys=True) != json.dumps(PACK_METADATA, sort_keys=True):
            raise VerificationError('forge_pack_metadata')
        for name, expected in [('p2wlan.forge.mixins.json', MIXIN_CONFIG), ('p2wlan.forge.client.mixins.json', CLIENT_MIXIN_CONFIG)]:
            if json.dumps(metadata(archive, name), sort_keys=True) != json.dumps(expected, sort_keys=True):
                raise VerificationError('forge_mixin_contract')
        for name in (CLIENT, *CLIENT_MIXINS):
            classfile(archive, name, 61)
        for name in DEDICATED_CLASSES:
            value = classfile(archive, name, 61)
            if any(prefix in value for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/', b'io/p2wlan/mc/forge/client/', b'io/p2wlan/mc/game/client/')):
                raise VerificationError('mod_dedicated_client_reference')
        return portable(archive, version, development, 'forge')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--development', action='store_true')
    args = parser.parse_args()
    try:
        native = verify(args.archive, args.development)
        bind_sources(native, args.development)
        binding = native[0]
    except (VerificationError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, 'FAIL ' + str(error) + '\n')
    print('PASS Forge archive: Minecraft 1.21.1 / Forge 52.1.16; shared portable/game code and verified native resources')
    print('archive-sha256=' + hashlib.sha256(args.archive.read_bytes()).hexdigest())
    print('source-revision=' + binding['source-revision'])
    print('source-sha256=' + binding['source-sha256'])
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

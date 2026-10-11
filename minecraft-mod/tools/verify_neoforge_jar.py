#!/usr/bin/env python3
"""Verify the NeoForge baseline archive and source-bound portable components."""
import argparse
import hashlib
import io
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
WORLD_COMMON = tuple('io/p2wlan/mc/common/' + name + '.class' for name in
                     ('WorldSessions', 'WorldSessions$World', 'WorldHost', 'WorldHost$Target', 'WorldHost$TargetFactory'))
SERVER_MIXIN = 'io/p2wlan/mc/neoforge/mixin/ServerConnectionMixin.class'
DEDICATED_CLASSES = (MAIN, SERVER_MIXIN, 'io/p2wlan/mc/neoforge/WorldCommands.class',
                     'io/p2wlan/mc/neoforge/LocalNativeResources.class')
CLIENT_MIXIN_CONFIG = {'required': True, 'package': 'io.p2wlan.mc.game.mixin', 'compatibilityLevel': 'JAVA_21',
                       'client': ['ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin', 'MultiplayerListAccessor', 'MultiplayerScreenMixin'],
                       'injectors': {'defaultRequire': 1}}
CLIENT = 'io/p2wlan/mc/neoforge/client/NeoForgeClient.class'
CLIENT_MIXINS = tuple('io/p2wlan/mc/game/mixin/' + name + '.class' for name in CLIENT_MIXIN_CONFIG['client'])
MIXIN_CONFIG = {'required': True, 'package': 'io.p2wlan.mc.neoforge.mixin',
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


def portable(archive, version, development):
    jars = metadata(archive, 'META-INF/jarjar/metadata.json')
    jars = jars.get('jars') if isinstance(jars, dict) else None
    if not isinstance(jars, list) or len(jars) != 3:
        raise VerificationError('neoforge_nested_resources_missing')
    seen = set()
    common_count = transport_count = 0
    natives = []
    identities = set()
    for item in jars:
        if not isinstance(item, dict) or set(item) != {'identifier', 'version', 'path', 'isObfuscated'}:
            raise VerificationError('neoforge_jarjar_metadata')
        identifier = item['identifier']
        if not isinstance(identifier, dict) or set(identifier) != {'group', 'artifact'} \
                or identifier['group'] != 'io.p2wlan.mc' or identifier['artifact'] not in {'common', 'netty-transport', 'native-bundle'} \
                or identifier['artifact'] in identities or item['isObfuscated'] is not False \
                or item['version'] != {'range': '[0.1.0,)', 'artifactVersion': '0.1.0'}:
            raise VerificationError('neoforge_jarjar_metadata')
        identities.add(identifier['artifact'])
        path = item.get('path') if isinstance(item, dict) else None
        if not isinstance(path, str) or not path.startswith('META-INF/jarjar/') or path in seen:
            raise VerificationError('neoforge_nested_resource_invalid')
        seen.add(path)
        with zipfile.ZipFile(io.BytesIO(read(archive, path, MAX_BINARY))) as child:
            names = entries(child)
            if identifier['artifact'] == 'native-bundle':
                raw_manifest = read(child, 'META-INF/MANIFEST.MF', 4096)
                pairs = [line.split(b': ', 1) for line in raw_manifest.splitlines() if line]
                if any(len(pair) != 2 for pair in pairs) or len(pairs) != 2 or dict(pairs) != {
                        b'Manifest-Version': b'1.0', b'Automatic-Module-Name': b'io.p2wlan.mc.nativebundle'}:
                    raise VerificationError('neoforge_native_module_identity')
            if (identifier['artifact'] == 'common') != (COMMON in names) \
                    or (identifier['artifact'] == 'netty-transport') != (TRANSPORT[0] in names):
                raise VerificationError('neoforge_jarjar_component_identity')
            if 'com/sun/jna/Native.class' in names:
                raise VerificationError('neoforge_duplicate_bootstrap_jna')
            if COMMON in names:
                common_count += 1
                for name in WORLD_COMMON:
                    classfile(child, name, 61)
            if TRANSPORT[0] in names:
                transport_count += 1
                for name in TRANSPORT:
                    classfile(child, name, 61)
            if COMMON in names or TRANSPORT[0] in names:
                for name in names:
                    if name.endswith('.class'):
                        value = classfile(child, name, 61)
                        if any(prefix in value for prefix in (b'net/minecraft/', b'net/fabricmc/', b'net/neoforged/', b'net/minecraftforge/')):
                            raise VerificationError('mod_common_game_dependency')
            manifests = sorted(name for name in names if name.startswith('p2wlan/native/') and name.endswith('/manifest.properties'))
            resources = [native_manifest(child, name, version, development) for name in manifests]
            admitted = set(manifests) | {f"p2wlan/native/{item['verification-platform']}/{executable_name(item['verification-platform'])}" for item in resources}
            if any(name.startswith('p2wlan/native/') and not name.endswith('/') and name not in admitted for name in names):
                raise VerificationError('mod_native_resource_unexpected')
            natives.extend(resources)
    if seen != {name for name in entries(archive) if name.startswith('META-INF/jarjar/') and name.endswith('.jar')}:
        raise VerificationError('neoforge_unlisted_nested_resource')
    if (common_count, transport_count) != (1, 1):
        raise VerificationError('neoforge_portable_resources_ambiguous')
    platforms = [item['verification-platform'] for item in natives]
    if not natives or len(platforms) > len(PLATFORMS) or len(set(platforms)) != len(platforms):
        raise VerificationError('mod_native_resources_ambiguous')
    return natives


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

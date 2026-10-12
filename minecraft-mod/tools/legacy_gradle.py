#!/usr/bin/env python3
"""Pinned Java 17/Gradle 8.8 launcher and Forge 1.20.1 Universal-input checks."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
import zipfile

from forge_gradle import bounded_file, publish

PROJECT = Path(__file__).resolve().parents[1]
GRADLE_SHA256 = 'a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612'
DISTRIBUTION = b'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip'
SERVER_REFMAP = 'p2wlan-forge-1.20.1-server-refmap.json'
CLIENT_REFMAP = 'p2wlan-forge-1.20.1-client-refmap.json'
SERVER_CONFIG = 'p2wlan.forge.1.20.1.mixins.json'
CLIENT_CONFIG = 'p2wlan.forge.1.20.1.client.mixins.json'
SERVER_TARGETS = {'io/p2wlan/mc/game/server/mixin/ServerConnectionMixin': {
    'startTcpServerListener': 'Lnet/minecraft/server/network/ServerConnectionListener;m_9711_(Ljava/net/InetAddress;I)V'}}
CLIENT_TARGETS = {
    'io/p2wlan/mc/game/mixin/ClientConnectionMixin': {
        'connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;':
            'Lnet/minecraft/network/Connection;m_290025_(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;'},
    'io/p2wlan/mc/game/mixin/IntegratedServerMixin': {'isPublished': 'Lnet/minecraft/client/server/IntegratedServer;m_6992_()Z'},
    'io/p2wlan/mc/game/mixin/MultiplayerListMixin': {
        'refreshEntries': 'Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;m_99780_()V'},
    'io/p2wlan/mc/game/mixin/MultiplayerListAccessor': {'refreshEntries': 'm_99780_()V'},
    'io/p2wlan/mc/game/mixin/MultiplayerScreenMixin': {
        'tick': 'Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;m_86600_()V',
        'joinSelectedServer()V': 'Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;m_99729_()V'},
}


def java17(environment):
    home = environment.get('P2WLAN_LEGACY_JAVA_HOME')
    if not home and sys.platform == 'darwin':
        home = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True, timeout=5).strip()
    if not home:
        home = environment.get('JAVA_HOME')
    selected = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if home else None
    if selected is None:
        found = shutil.which('java', path=environment.get('PATH'))
        selected = Path(found) if found else None
    if selected is None or not selected.is_absolute() or not selected.is_file() \
            or home and (not Path(home).is_absolute() or not Path(home).is_dir()):
        raise ValueError('LEGACY_JAVA17_REQUIRED')
    result = subprocess.run([str(selected), '-XshowSettings:properties', '-version'],
                            env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=True)
    if re.findall(r'^\s*java\.specification\.version\s*=\s*(\S+)\s*$', result.stderr, re.MULTILINE) != ['17']:
        raise ValueError('LEGACY_JAVA17_REQUIRED')
    return selected, Path(home) if home else selected.parent.parent


def command(environment):
    java, home = java17(environment)
    properties = bounded_file(PROJECT / 'java/forge-legacy/gradle/wrapper/gradle-wrapper.properties', 8192)
    lines = properties.splitlines()
    if lines.count(('distributionSha256Sum=' + GRADLE_SHA256).encode()) != 1 \
            or sum(line.startswith(b'distributionSha256Sum=') for line in lines) != 1 \
            or lines.count(DISTRIBUTION) != 1 or sum(line.startswith(b'distributionUrl=') for line in lines) != 1:
        raise ValueError('LEGACY_GRADLE_DISTRIBUTION_IDENTITY')
    local = environment.get('P2WLAN_LEGACY_GRADLE_ZIP')
    if local:
        archive = Path(local)
        if not archive.is_absolute() or archive.is_symlink() or not archive.is_file() \
                or archive.stat().st_size > 512 * 1024 * 1024:
            raise ValueError('LEGACY_GRADLE_DISTRIBUTION_INVALID')
        digest = hashlib.sha256()
        with archive.open('rb') as stream:
            while chunk := stream.read(65536):
                digest.update(chunk)
        if digest.hexdigest() != GRADLE_SHA256:
            raise ValueError('LEGACY_GRADLE_DISTRIBUTION_IDENTITY')
        properties = b'\n'.join(b'distributionUrl=' + archive.as_uri().encode() if line.startswith(b'distributionUrl=') else line
                                for line in lines) + b'\n'
    directory = PROJECT / ('java/forge-legacy/build/bootstrap-local' if local else 'java/forge-legacy/build/bootstrap-official') / 'gradle/wrapper'
    directory.mkdir(parents=True, exist_ok=True)
    publish(directory / 'gradle-wrapper.jar', bounded_file(PROJECT / 'java/gradle/wrapper/gradle-wrapper.jar', 128 * 1024))
    publish(directory / 'gradle-wrapper.properties', properties)
    return [str(java), '-Dorg.gradle.java.installations.paths=' + str(home),
            '-Dorg.gradle.java.installations.auto-download=false', '-jar', str(directory / 'gradle-wrapper.jar')]


def refmap(value, expected):
    from artifact_checks import VerificationError
    # Exact AP0.8.5/official1.20.1 output: include injector keys, target owners,
    # descriptors and the owner-free @Invoker result, not just SRG suffixes.
    if value != {'mappings': expected, 'data': {'searge': expected}}:
        raise VerificationError('legacy_forge_refmap_contract')


def field_names(value):
    """Read actual field declarations; a matching unrelated UTF8 is insufficient."""
    from artifact_checks import VerificationError
    def invalid():
        raise VerificationError('legacy_forge_class_structure')
    if len(value) < 10 or len(value) > 1024 * 1024 or value[:4] != b'\xca\xfe\xba\xbe':
        invalid()
    constants = {}
    position, index = 10, 1
    count = int.from_bytes(value[8:10], 'big')
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
             15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        if position >= len(value): invalid()
        tag = value[position]; position += 1
        if tag == 1:
            if position + 2 > len(value): invalid()
            size = int.from_bytes(value[position:position + 2], 'big'); position += 2
            if position + size > len(value): invalid()
            constants[index] = value[position:position + size]
        else:
            size = sizes.get(tag)
            if size is None or position + size > len(value) or tag in (5, 6) and index + 1 >= count: invalid()
            if tag in (5, 6): index += 1
        position += size; index += 1
    if position + 8 > len(value): invalid()
    position += 8 + int.from_bytes(value[position + 6:position + 8], 'big') * 2
    if position + 2 > len(value): invalid()
    count = int.from_bytes(value[position:position + 2], 'big'); position += 2
    names = set()
    for _ in range(count):
        if position + 8 > len(value): invalid()
        name = constants.get(int.from_bytes(value[position + 2:position + 4], 'big'))
        if name is None or name in names: invalid()
        names.add(name)
        attributes = int.from_bytes(value[position + 6:position + 8], 'big'); position += 8
        for _ in range(attributes):
            if position + 6 > len(value): invalid()
            position += 6 + int.from_bytes(value[position + 2:position + 6], 'big')
            if position > len(value): invalid()
    return names


def verify(path, development=False):
    from artifact_checks import VerificationError, classfile, entries, metadata, read
    from jarjar_artifact import portable
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        descriptor = tomllib.loads(read(archive, 'META-INF/mods.toml', 65536).decode('utf-8'))
        expected = [{'modId': 'forge', 'mandatory': True, 'versionRange': '[47.3.2]', 'ordering': 'NONE', 'side': 'BOTH'},
                    {'modId': 'minecraft', 'mandatory': True, 'versionRange': '[1.20.1]', 'ordering': 'NONE', 'side': 'BOTH'}]
        if descriptor.get('modLoader') != 'javafml' or descriptor.get('loaderVersion') != '[47,)' \
                or descriptor.get('dependencies') != {'p2wlan': expected} \
                or len(descriptor.get('mods', [])) != 1 or descriptor['mods'][0].get('modId') != 'p2wlan' \
                or descriptor['mods'][0].get('version') != '0.1.0':
            raise VerificationError('legacy_forge_mod_contract')
        manifest = read(archive, 'META-INF/MANIFEST.MF', 8192).replace(b'\r\n ', b'').replace(b'\n ', b'')
        if manifest.splitlines().count(('MixinConfigs: ' + SERVER_CONFIG + ',' + CLIENT_CONFIG).encode()) != 1:
            raise VerificationError('legacy_forge_mixin_manifest')
        for name, package, key, classes, mapping in [
                (SERVER_CONFIG, 'io.p2wlan.mc.game.server.mixin', 'mixins', ['ServerConnectionMixin'], SERVER_REFMAP),
                (CLIENT_CONFIG, 'io.p2wlan.mc.game.mixin', 'client', [name.rsplit('/', 1)[-1] for name in CLIENT_TARGETS], CLIENT_REFMAP)]:
            if metadata(archive, name) != {'required': True, 'minVersion': '0.8.5', 'package': package,
                                          'compatibilityLevel': 'JAVA_17', 'refmap': mapping, key: classes,
                                          'injectors': {'defaultRequire': 1}}:
                raise VerificationError('legacy_forge_mixin_contract')
        refmap(metadata(archive, SERVER_REFMAP), SERVER_TARGETS)
        refmap(metadata(archive, CLIENT_REFMAP), CLIENT_TARGETS)
        if metadata(archive, 'pack.mcmeta') != {'pack': {'description': 'P2WLAN', 'pack_format': 15}}:
            raise VerificationError('legacy_forge_pack_contract')
        dedicated = ('io/p2wlan/mc/forge/ForgeP2wlan', 'io/p2wlan/mc/forge/ForgeRuntime',
                     'io/p2wlan/mc/forge/legacy/ForgeServerEvents', 'io/p2wlan/mc/forge/legacy/LocalNativeResources',
                     'io/p2wlan/mc/game/server/WorldCommands', *SERVER_TARGETS)
        for name in (*dedicated, 'io/p2wlan/mc/forge/legacy/ForgeClientEvents', *CLIENT_TARGETS):
            value = classfile(archive, name + '.class', 61)
            if name in dedicated and any(prefix in value for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/',
                                                                         b'io/p2wlan/mc/game/client/')):
                raise VerificationError('mod_dedicated_client_reference')
        for name in names:
            if name.endswith('.class'):
                classfile(archive, name, 61)
            if name.startswith(('com/sun/jna/', 'io/p2wlan/mc/forge/client/')) \
                    or name in {'io/p2wlan/mc/forge/ForgeServerEvents.class', 'io/p2wlan/mc/forge/LocalNativeResources.class'} \
                    or '/fixture/' in name or name.endswith('Tests.class'):
                raise VerificationError('legacy_forge_class_owner')
        for owner, field, original in [('MultiplayerListMixin', b'f_99766_', b'screen'),
                                       ('MultiplayerScreenMixin', b'f_99673_', b'serverSelectionList')]:
            declared = field_names(classfile(archive, 'io/p2wlan/mc/game/mixin/' + owner + '.class', 61))
            if field not in declared or original in declared:
                raise VerificationError('legacy_forge_shadow_srg')
        return portable(archive, '0.1.0', development, 'legacy_forge')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    run = sub.add_parser('run')
    run.add_argument('tasks', nargs=argparse.REMAINDER)
    check = sub.add_parser('verify')
    check.add_argument('archive', type=Path)
    check.add_argument('--development', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'run':
            if not args.tasks:
                parser.error('Specify Gradle tasks')
            environment = os.environ.copy()
            return subprocess.call([*command(environment), '--no-daemon', *args.tasks],
                                   cwd=PROJECT / 'java/forge-legacy', env=environment)
        from artifact_checks import bind_sources
        natives = verify(args.archive, args.development)
        bind_sources(natives, args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, (str(error) if isinstance(error, ValueError) else 'LEGACY_FORGE_BUILD_IO') + '\n')
    print('PASS Forge 1.20.1/47.3.2 Universal input: Java 17, SRG Mixin refmaps/shadows, three portable JarJar owners')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

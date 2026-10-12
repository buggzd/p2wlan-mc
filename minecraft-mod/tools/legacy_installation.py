#!/usr/bin/env python3
"""Checked official 1.20.1 production installations for local game fixtures."""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shlex
import shutil
import subprocess
import urllib.error
import urllib.request
import zipfile

from legacy_gradle import java17
from fixture_relocation import regular_bytes
from game_fixture_process import FixtureProcess
from hmcl_pack import publish
from native_bundle import strict_object

PROJECT = Path(__file__).resolve().parents[1]
MAIN = 'cpw.mods.bootstraplauncher.BootstrapLauncher'
MC = '1.20.1'
MCP = '1.20.1-20230612.114412'
VERSION = {'url': 'https://piston-meta.mojang.com/v1/packages/760a390e336b2b6202ec5906757cee3a929a7c85/1.20.1.json',
           'size': 34974, 'sha1': '760a390e336b2b6202ec5906757cee3a929a7c85'}
INSTALLERS = {
    'forge': {'version': '47.3.2', 'group': 'net/minecraftforge', 'size': 6021470,
              'sha256': 'a07f8b84d58fa9fa76def80a796302db946f6039511154a83875464a79c98683',
              'url': 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.2/forge-1.20.1-47.3.2-installer.jar'},
    'neoforge': {'version': '47.1.106', 'group': 'net/neoforged', 'size': 7846890,
                 'sha256': 'c1ea1c3be532c4444004efd7f9cc71e1590d010ff44845b93effba61e3bd1526',
                 'url': 'https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar'},
}
MAX_FILE = 128 * 1024 * 1024


def digest(path, algorithm='sha1'):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_FILE:
        raise ValueError('LEGACY_RUNTIME_FILE_INVALID')
    value = hashlib.new(algorithm)
    count = 0
    with path.open('rb') as stream:
        while chunk := stream.read(65536):
            count += len(chunk)
            if count > MAX_FILE: raise ValueError('LEGACY_RUNTIME_FILE_INVALID')
            value.update(chunk)
    return value.hexdigest()


def checked(path, record):
    key = 'sha256' if 'sha256' in record else 'sha1'
    return path.is_file() and not path.is_symlink() and path.stat().st_size == record['size'] \
        and digest(path, key) == record[key]


def relative(root, name):
    parts = PurePosixPath(name).parts
    if not parts or name.startswith('/') or any(p in ('..', '.') for p in parts) or '\\' in name:
        raise ValueError('LEGACY_RUNTIME_PATH_INVALID')
    result = root.joinpath(*parts)
    if any(p.is_symlink() for p in [root, result, *result.parents] if p == root or root in p.parents):
        raise ValueError('LEGACY_RUNTIME_PATH_INVALID')
    return result


def owned_root(path):
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('LEGACY_RUNTIME_PATH_INVALID')
    return path


def cache_index():
    pending = [(p, 0) for p in (Path.home() / '.gradle/caches', PROJECT / '.evidence') if p.is_dir() and not p.is_symlink()]
    indexed = {}; scanned = 0; path_bytes = 0
    while pending:
        directory, depth = pending.pop()
        if depth > 32: raise ValueError('LEGACY_RUNTIME_CACHE_CAPACITY')
        with os.scandir(directory) as entries:
            for entry in entries:
                scanned += 1
                if scanned > 250000: raise ValueError('LEGACY_RUNTIME_CACHE_CAPACITY')
                if entry.is_symlink(): continue
                path = Path(entry.path)
                if entry.is_dir(follow_symlinks=False):
                    pending.append((path, depth + 1))
                    if len(pending) > 4096: raise ValueError('LEGACY_RUNTIME_CACHE_CAPACITY')
                elif entry.is_file(follow_symlinks=False):
                    path_bytes += len(os.fsencode(path))
                    if path_bytes > 64 * 1024 * 1024: raise ValueError('LEGACY_RUNTIME_CACHE_CAPACITY')
                    indexed.setdefault(path.name, []).append(path)
    return indexed


def ensure(path, record, indexed, offline, aliases=()):
    if checked(path, record): return
    if path.is_symlink(): raise ValueError('LEGACY_RUNTIME_PATH_INVALID')
    path.parent.mkdir(parents=True, exist_ok=True)
    for candidate in indexed.get(path.name, []) + [p for n in aliases for p in indexed.get(n, [])]:
        if checked(candidate, record):
            shutil.copyfile(candidate, path)
            if checked(path, record): return
            raise ValueError('LEGACY_RUNTIME_CACHE_CHANGED')
    if offline: raise ValueError('LEGACY_RUNTIME_OFFLINE_INPUT_MISSING:' + path.name)
    url = record['url']
    urls = [url]
    if url.startswith('https://maven.neoforged.net/releases/'):
        # Official Maven hostname's TLS-valid CDN CNAME; every byte remains pinned.
        urls.append(url.replace('maven.neoforged.net', '1755624453.rsc.cdn77.org', 1))
    temporary = relative(path.parent, path.name + '.download')
    try:
        for source in urls:
            try:
                with urllib.request.urlopen(source, timeout=30) as response, temporary.open('wb') as output:
                    count = 0
                    while chunk := response.read(65536):
                        count += len(chunk)
                        if count > record['size'] or count > MAX_FILE: raise ValueError('LEGACY_RUNTIME_DOWNLOAD_SIZE')
                        output.write(chunk)
                if not checked(temporary, record): raise ValueError('LEGACY_RUNTIME_DOWNLOAD_IDENTITY')
                temporary.replace(path)
                return
            except (urllib.error.URLError, TimeoutError, OSError):
                if source == urls[-1]: raise ValueError('LEGACY_RUNTIME_DOWNLOAD_FAILED:' + path.name) from None
    finally:
        temporary.unlink(missing_ok=True)


def host():
    system = {'darwin': 'osx', 'linux': 'linux'}.get(__import__('sys').platform)
    arch = platform.machine().lower()
    arch = 'arm64' if arch in ('arm64', 'aarch64') else 'x86_64' if arch in ('x86_64', 'amd64') else arch
    if system is None or arch not in ('arm64', 'x86_64') or arch == 'arm64' and system != 'osx':
        raise ValueError('LEGACY_RUNTIME_PLATFORM_UNSUPPORTED')
    return system, arch


def allowed(value):
    system, arch = host()
    rules = value.get('rules')
    if not rules: return True
    result = False
    for rule in rules:
        os_rule = rule.get('os', {})
        if os_rule.get('name', system) != system or 'arch' in os_rule and not re.fullmatch(os_rule['arch'], arch): continue
        if 'version' in os_rule and not re.search(os_rule['version'], platform.release()): continue
        if any(expected is not False for expected in rule.get('features', {}).values()): continue
        result = rule['action'] == 'allow'
    return result


def selected_libraries(metadata):
    _, arch = host()
    result = {}
    for library in metadata['libraries']:
        if not allowed(library): continue
        name = library['name']
        if ':natives-' in name and ('-arm64' in name) != (arch == 'arm64'): continue
        if name.endswith(':natives-windows-x86'): continue
        record = library['downloads']['artifact']
        result[name] = record
    return result


def metadata(sdk, loader):
    pin = INSTALLERS[loader]
    installer = sdk / 'installer.jar'
    if not checked(installer, pin) or not checked(sdk / 'minecraft.json', VERSION):
        raise ValueError('LEGACY_RUNTIME_METADATA_IDENTITY')
    with zipfile.ZipFile(installer) as archive:
        profile = json.loads(archive.read('install_profile.json'))
        version = json.loads(archive.read('version.json'))
        args = shlex.split(archive.read('data/unix_args.txt').decode())
    vanilla = json.loads((sdk / 'minecraft.json').read_text())
    if version['inheritsFrom'] != MC or version['mainClass'] != MAIN:
        raise ValueError('LEGACY_RUNTIME_PROFILE_INVALID')
    return profile, version, vanilla, args


def inputs(sdk, profile, version, vanilla):
    records = {}
    for library in profile['libraries'] + version['libraries']:
        record = library['downloads']['artifact']
        if record.get('url') and record.get('size'): records['libraries/' + record['path']] = record
    for record in selected_libraries(vanilla).values(): records['libraries/' + record['path']] = record
    for side in ('client', 'server'):
        records[f'libraries/net/minecraft/{side}/{MCP}/{side}-{MCP}-mappings.txt'] = vanilla['downloads'][side + '_mappings']
    records['versions/1.20.1/1.20.1.jar'] = vanilla['downloads']['client']
    records['libraries/net/minecraft/server/1.20.1/server-1.20.1.jar'] = vanilla['downloads']['server']
    records['assets/indexes/' + vanilla['assetIndex']['id'] + '.json'] = vanilla['assetIndex']
    logging = vanilla['logging']['client']['file']
    records['assets/log_configs/' + logging['id']] = logging
    return [(relative(sdk, path), record) for path, record in records.items()]


def asset_inputs(sdk, vanilla):
    index = json.loads((sdk / ('assets/indexes/' + vanilla['assetIndex']['id'] + '.json')).read_text())
    objects = index['objects']
    if len(objects) > 10000: raise ValueError('LEGACY_RUNTIME_ASSET_CAPACITY')
    records = {}
    for record in objects.values():
        value = record['hash']
        if not re.fullmatch('[0-9a-f]{40}', value): raise ValueError('LEGACY_RUNTIME_ASSET_IDENTITY')
        records[value] = {'sha1': value, 'size': record['size'], 'url': 'https://resources.download.minecraft.net/' + value[:2] + '/' + value}
    return [(sdk / 'assets/objects' / value[:2] / value, record) for value, record in records.items()]


def outputs(sdk, profile):
    result = []
    for side in ('client', 'server'):
        for name in ('MC_SLIM', 'MC_EXTRA', 'PATCHED'):
            coordinate = profile['data'][name][side].strip('[]').split(':')
            group, artifact, version, classifier = coordinate
            path = sdk / 'libraries' / group.replace('.', '/') / artifact / version / f'{artifact}-{version}-{classifier}.jar'
            expected = profile['data'][name + '_SHA'][side].strip("'")
            if digest(path) != expected: raise ValueError('LEGACY_RUNTIME_PROCESSOR_IDENTITY')
            result.append(path)
    return result


def natives(sdk, vanilla, verify=False):
    destination = sdk / 'natives'
    destination.mkdir(exist_ok=True)
    names = set()
    for name, record in selected_libraries(vanilla).items():
        if ':natives-' not in name: continue
        with zipfile.ZipFile(relative(sdk, 'libraries/' + record['path'])) as archive:
            for member in archive.infolist():
                if member.is_dir() or member.filename.startswith('META-INF/'): continue
                if member.file_size > 32 * 1024 * 1024: raise ValueError('LEGACY_RUNTIME_NATIVE_CAPACITY')
                target = relative(destination, member.filename)
                data = archive.read(member)
                if target in names: raise ValueError('LEGACY_RUNTIME_NATIVE_COLLISION')
                names.add(target)
                if verify:
                    if not target.is_file() or target.is_symlink() or target.read_bytes() != data:
                        raise ValueError('LEGACY_RUNTIME_NATIVE_IDENTITY')
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)


def class_owner(value):
    if len(value) < 24 or value[:8] != b'\xca\xfe\xba\xbe\x00\x00\x00=':
        raise ValueError('LEGACY_RUNTIME_FIXTURE_CLASS')
    constants = {}; position = 10; index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < int.from_bytes(value[8:10], 'big'):
        if position >= len(value): raise ValueError('LEGACY_RUNTIME_FIXTURE_CLASS')
        tag = value[position]; position += 1
        if tag == 1:
            size = int.from_bytes(value[position:position + 2], 'big'); position += 2
            constants[index] = value[position:position + size]
        else:
            size = sizes.get(tag)
            if size is None: raise ValueError('LEGACY_RUNTIME_FIXTURE_CLASS')
            if tag == 7: constants[index] = int.from_bytes(value[position:position + 2], 'big')
        position += size
        if position > len(value): raise ValueError('LEGACY_RUNTIME_FIXTURE_CLASS')
        index += 2 if tag in (5, 6) else 1
    try:
        return constants[constants[int.from_bytes(value[position + 2:position + 4], 'big')]].decode('ascii')
    except (KeyError, AttributeError, UnicodeError): raise ValueError('LEGACY_RUNTIME_FIXTURE_CLASS') from None


def fixture_snapshot(job, label):
    path = relative(job, 'build/forge/installed-fixtures/' + label + '-srg.jar')
    value = regular_bytes(path, 32 * 1024 * 1024)
    source = 'integratedFixture' if label == 'IntegratedFixture' else 'localGameFixture'
    resources = PROJECT / ('java/adapters/forge-1.20.1/src/' + source + '/resources')
    expected = {name: regular_bytes(resources / name, 65536) for name in ('META-INF/mods.toml', 'pack.mcmeta')}
    forge = {'InstalledArchiveChecks', label}
    game = {'GameFixtureData', 'InstalledArtifact', 'GameFixtureApi'}
    if label == 'LocalGameFixture': forge |= {'LocalGameClientEvents', 'LocalGameClientEvents$Layers'}; game |= {'GameServerChecks', 'GameClientChecks'}
    else: game.add('IntegratedHostChecks')
    owners = {'io/p2wlan/mc/forge/fixture/' + name for name in forge} | {'io/p2wlan/mc/game/v1_20_1/fixture/' + name for name in game}
    directories = {'META-INF/'} | {owner[:i + 1] for owner in owners for i, char in enumerate(owner) if char == '/'}
    with zipfile.ZipFile(io.BytesIO(value)) as archive:
        members = archive.infolist(); names = {m.filename for m in members}
        if len(members) > 32 or len(names) != len(members) or sum(m.file_size for m in members) > 32 * 1024 * 1024:
            raise ValueError('LEGACY_RUNTIME_FIXTURE_CAPACITY')
        for name, metadata in expected.items():
            member = archive.getinfo(name) if name in names else None
            if member is None or member.file_size > 65536 or member.flag_bits & 1 \
                    or member.external_attr >> 16 & 0o170000 not in {0, 0o100000} or member.is_dir() \
                    or archive.read(name) != metadata:
                raise ValueError('LEGACY_RUNTIME_FIXTURE_PACK_METADATA' if name == 'pack.mcmeta' else 'LEGACY_RUNTIME_FIXTURE_METADATA')
        classes = set()
        for member in members:
            if member.filename in expected: continue
            if member.is_dir() and member.filename in directories and member.file_size == 0: continue
            owner = member.filename.removesuffix('.class')
            if not member.filename.endswith('.class') or owner not in owners:
                raise ValueError('LEGACY_RUNTIME_FIXTURE_OWNER')
            if member.file_size > 1024 * 1024 or class_owner(archive.read(member)) != owner:
                raise ValueError('LEGACY_RUNTIME_FIXTURE_CLASS')
            classes.add(owner)
        if not owners <= classes: raise ValueError('LEGACY_RUNTIME_FIXTURE_OWNER')
    return value, {'size': len(value), 'sha256': hashlib.sha256(value).hexdigest(), 'classes': sorted(classes)}


def srg_records(sdk):
    records = {}
    for side in ('client', 'server'):
        try:
            value = regular_bytes(relative(sdk, f'libraries/net/minecraft/{side}/{MCP}/{side}-{MCP}-srg.jar'), MAX_FILE)
        except (OSError, ValueError): raise ValueError('LEGACY_RUNTIME_SRG_IDENTITY') from None
        records[side] = {'size': len(value), 'sha256': hashlib.sha256(value).hexdigest()}
    return records


def plan(job, loader, fixtures, srgs):
    pin = INSTALLERS[loader]
    return {'schema': 1, 'minecraft': MC, 'loader': loader, 'loaderVersion': pin['version'],
            'installerSHA256': pin['sha256'], 'minecraftSHA1': VERSION['sha1'], 'platform': list(host()), 'fixtures': fixtures, 'srgs': srgs}


def read_plan(job, loader):
    try:
        value = json.loads(regular_bytes(relative(job, 'legacy-runtime.json'), 8192), object_pairs_hook=strict_object)
    except (OSError, ValueError): raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY') from None
    if not isinstance(value, dict) or type(value.get('schema')) is not int or not isinstance(value.get('fixtures'), dict):
        raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY')
    fixtures = value['fixtures']
    if set(fixtures) not in ({'LocalGameFixture'}, {'LocalGameFixture', 'IntegratedFixture'}):
        raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY')
    for record in fixtures.values():
        if not isinstance(record, dict) or set(record) != {'size', 'sha256', 'classes'} or type(record['size']) is not int \
                or not 0 < record['size'] <= 32 * 1024 * 1024 or not isinstance(record['sha256'], str) \
                or not re.fullmatch('[0-9a-f]{64}', record['sha256']) or not isinstance(record['classes'], list) \
                or not 1 <= len(record['classes']) <= 32 or any(not isinstance(v, str) for v in record['classes']):
            raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY')
    srgs = value.get('srgs')
    if not isinstance(srgs, dict) or set(srgs) != {'client', 'server'}:
        raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY')
    for record in srgs.values():
        if not isinstance(record, dict) or set(record) != {'size', 'sha256'} or type(record['size']) is not int \
                or not 0 < record['size'] <= MAX_FILE or not isinstance(record['sha256'], str) \
                or not re.fullmatch('[0-9a-f]{64}', record['sha256']):
            raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY')
    if value != plan(job, loader, fixtures, srgs): raise ValueError('LEGACY_RUNTIME_MANIFEST_IDENTITY')
    return fixtures, srgs


def prepare(job: Path, loader: str, env: dict, offline: bool) -> None:
    if loader not in INSTALLERS: raise ValueError('LEGACY_RUNTIME_LOADER_INVALID')
    java, _ = java17(env)
    job = owned_root(job); sdk = owned_root(job / 'legacy-sdk' / loader)
    sdk.mkdir(parents=True, exist_ok=True)
    fixtures = {'LocalGameFixture': fixture_snapshot(job, 'LocalGameFixture')[1]}
    if relative(job, 'build/forge/installed-fixtures/IntegratedFixture-srg.jar').exists():
        fixtures['IntegratedFixture'] = fixture_snapshot(job, 'IntegratedFixture')[1]
    indexed = cache_index()
    pin = INSTALLERS[loader]
    ensure(sdk / 'installer.jar', pin, indexed, offline, ('forge-1.20.1-' + pin['version'] + '-installer.jar',))
    ensure(sdk / 'minecraft.json', VERSION, indexed, offline, ('minecraft-1.20.1.json', '1.20.1.json'))
    profile, version, vanilla, _ = metadata(sdk, loader)
    for path, record in inputs(sdk, profile, version, vanilla):
        aliases = ('client.jar',) if path.name == '1.20.1.jar' else ('minecraft-server.jar', 'server.jar') if path.name == 'server-1.20.1.jar' else ('client_mappings.txt', 'server_mappings.txt') if path.name.endswith('-mappings.txt') else ()
        ensure(path, record, indexed, offline, aliases)
    relative(sdk, 'versions/1.20.1/1.20.1.json').write_bytes((sdk / 'minecraft.json').read_bytes())
    # This isolated installer-only profile has no accounts or user launcher data.
    relative(sdk, 'launcher_profiles.json').write_text('{"profiles":{}}\n')
    for role in ('Server', 'Client'):
        child = FixtureProcess([str(java), '-Djava.awt.headless=true', '-jar', str(sdk / 'installer.jar'),
                                '--install' + role, str(sdk), '--offline'], sdk, env, sdk / ('install-' + role.lower() + '.log'))
        try:
            try: result = child.process.wait(timeout=240)
            except subprocess.TimeoutExpired: raise ValueError('LEGACY_RUNTIME_INSTALL_TIMEOUT') from None
        finally: child.close()
        if result: raise ValueError('LEGACY_RUNTIME_INSTALL_FAILED:' + role.lower())
    outputs(sdk, profile)
    srgs = srg_records(sdk)
    for path, record in asset_inputs(sdk, vanilla): ensure(path, record, indexed, offline)
    natives(sdk, vanilla)
    for label, record in fixtures.items():
        if fixture_snapshot(job, label)[1] != record: raise ValueError('LEGACY_RUNTIME_FIXTURE_CHANGED')
    if srg_records(sdk) != srgs: raise ValueError('LEGACY_RUNTIME_SRG_IDENTITY')
    publish(relative(job, 'legacy-runtime.json'), (json.dumps(plan(job, loader, fixtures, srgs), sort_keys=True) + '\n').encode())


def expand(arguments, values):
    result = []
    for argument in arguments:
        if isinstance(argument, dict):
            if not allowed(argument): continue
            argument = argument['value']
        for item in argument if isinstance(argument, list) else [argument]:
            item = re.sub(r'\$\{([^}]+)\}', lambda match: values[match[1]], item)
            result.append(item)
    return result


def command(job, loader, role, integrated, development, archive_sha, env) -> list[str]:
    if loader not in INSTALLERS or role not in ('client', 'server'): raise ValueError('LEGACY_RUNTIME_PROFILE_INVALID')
    job = owned_root(job); sdk = owned_root(job / 'legacy-sdk' / loader)
    fixtures, captured_srgs = read_plan(job, loader)
    profile, version, vanilla, server_args = metadata(sdk, loader)
    for path, record in inputs(sdk, profile, version, vanilla) + asset_inputs(sdk, vanilla):
        if not checked(path, record): raise ValueError('LEGACY_RUNTIME_INPUT_IDENTITY:' + path.name)
    outputs(sdk, profile); natives(sdk, vanilla, verify=True)
    if srg_records(sdk) != captured_srgs: raise ValueError('LEGACY_RUNTIME_SRG_IDENTITY')
    installed = relative(job, role + '/mods/p2wlan.jar')
    if not isinstance(archive_sha, str) or not re.fullmatch('[0-9a-f]{64}', archive_sha) or digest(installed, 'sha256') != archive_sha:
        raise ValueError('GAME_INSTALLED_ARCHIVE_IDENTITY')
    fixture_name = 'IntegratedFixture' if integrated and role == 'server' else 'LocalGameFixture'
    value, record = fixture_snapshot(job, fixture_name)
    if fixtures.get(fixture_name) != record: raise ValueError('LEGACY_RUNTIME_FIXTURE_IDENTITY')
    target = relative(job, role + '/mods/' + fixture_name + '.jar'); target.parent.mkdir(parents=True, exist_ok=True)
    publish(target, value)
    java, _ = java17(env)
    properties = ['-Xmx2G', '-Dp2wlan.gameFixture.config=' + str(job / (role + '.json')),
                  '-Dp2wlan.gameFixture.installedArchive=' + str(installed),
                  '-Dp2wlan.gameFixture.installedSha256=' + archive_sha, '-Dp2wlan.gameFixture.installedUniversal=true']
    if development: properties.append('-Dp2wlan.allowDevelopmentNative=true')
    if role == 'server' and not integrated:
        args = [a.replace('libraries/', str(sdk / 'libraries') + '/') for a in server_args]
        args = [a.replace('-DlibraryDirectory=libraries', '-DlibraryDirectory=' + str(sdk / 'libraries')) for a in args]
        return [str(java), *properties, *args, 'nogui']
    libraries = selected_libraries(vanilla)
    for library in version['libraries']: libraries[library['name']] = library['downloads']['artifact']
    paths = [relative(sdk, 'libraries/' + r['path']) for r in libraries.values()]
    paths.append(sdk / 'libraries/net/minecraft/client' / MCP / ('client-' + MCP + '-extra.jar'))
    values = {'auth_player_name': 'IntegratedHost' if role == 'server' else 'GuestFixture', 'version_name': version['id'],
              'game_directory': str(job / role), 'assets_root': str(sdk / 'assets'), 'assets_index_name': vanilla['assetIndex']['id'],
              'auth_uuid': '00000000000000000000000000000001' if role == 'server' else '00000000000000000000000000000002',
              'auth_access_token': '0', 'clientid': '', 'auth_xuid': '', 'user_type': 'legacy', 'version_type': 'release',
              'natives_directory': str(sdk / 'natives'), 'launcher_name': 'p2wlan-local-fixture', 'launcher_version': '1',
              'classpath': os.pathsep.join(map(str, paths)), 'library_directory': str(sdk / 'libraries'), 'classpath_separator': os.pathsep}
    logging = vanilla['logging']['client']
    properties.append(logging['argument'].replace('${path}', str(sdk / 'assets/log_configs' / logging['file']['id'])))
    return [str(java), *properties, *expand(vanilla['arguments']['jvm'] + version['arguments']['jvm'], values),
            MAIN, *expand(vanilla['arguments']['game'] + version['arguments']['game'], values)]

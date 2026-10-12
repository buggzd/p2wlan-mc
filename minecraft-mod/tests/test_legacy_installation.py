"""Official production installation identities and isolated launch policies."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import legacy_installation as runtime


class LegacyInstallationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()

    def test_installer_profiles_are_exact_and_source_pinned(self):
        self.assertEqual({k: v['version'] for k, v in runtime.INSTALLERS.items()},
                         {'forge': '47.3.2', 'neoforge': '47.1.106'})
        self.assertEqual(runtime.VERSION['sha1'], '760a390e336b2b6202ec5906757cee3a929a7c85')
        for value in runtime.INSTALLERS.values():
            self.assertEqual(len(value['sha256']), 64)
            self.assertTrue(value['url'].endswith('-installer.jar'))

    def test_checked_cache_requires_size_hash_and_regular_file(self):
        payload = b'exact input'
        record = {'size': len(payload), 'sha1': hashlib.sha1(payload).hexdigest()}
        cached = self.root / 'cache/input.jar'; cached.parent.mkdir(); cached.write_bytes(payload)
        target = self.root / 'sdk/input.jar'
        runtime.ensure(target, record, {'input.jar': [cached]}, True)
        self.assertEqual(target.read_bytes(), payload)
        target.unlink(); cached.write_bytes(b'other input')
        with self.assertRaisesRegex(ValueError, 'OFFLINE_INPUT_MISSING'):
            runtime.ensure(target, record, {'input.jar': [cached]}, True)
        target.symlink_to(cached)
        with self.assertRaisesRegex(ValueError, 'PATH_INVALID'):
            runtime.ensure(target, record, {}, True)

    def test_owned_paths_reject_escape_and_symlink_ancestors(self):
        for value in ('../escape.jar', '/tmp/escape.jar', 'a/../../b', 'a\\b'):
            with self.assertRaisesRegex(ValueError, 'PATH_INVALID'):
                runtime.relative(self.root, value)
        (self.root / 'link').symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'PATH_INVALID'):
            runtime.relative(self.root, 'link/input.jar')

    def test_macos_native_selection_is_one_architecture_and_rules_preserved(self):
        def library(name, system):
            return {'name': name, 'rules': [{'action': 'allow', 'os': {'name': system}}],
                    'downloads': {'artifact': {'path': name}}}
        libraries = [library('org.lwjgl:lwjgl:3.3.1', 'osx'),
                     library('org.lwjgl:lwjgl:3.3.1:natives-macos', 'osx'),
                     library('org.lwjgl:lwjgl:3.3.1:natives-macos-arm64', 'osx'),
                     library('org.lwjgl:lwjgl:3.3.1:natives-linux', 'linux')]
        with patch.object(runtime, 'host', return_value=('osx', 'arm64')):
            selected = runtime.selected_libraries({'libraries': libraries})
            self.assertEqual(len(selected), 2)
            self.assertIn('org.lwjgl:lwjgl:3.3.1:natives-macos-arm64', selected)
            self.assertEqual(runtime.expand([{'rules': [{'action': 'allow', 'os': {'name': 'osx'}}],
                                               'value': '-XstartOnFirstThread'},
                                              {'rules': [{'action': 'allow', 'features': {'is_demo_user': True}}],
                                               'value': '--demo'}, '${classpath}'], {'classpath': 'private-sdk'}),
                             ['-XstartOnFirstThread', 'private-sdk'])

    def test_native_extraction_revalidates_original_archive_bytes(self):
        archive = self.root / 'libraries/native.jar'; archive.parent.mkdir()
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr('META-INF/MANIFEST.MF', 'ignored')
            output.writestr('liblwjgl.dylib', b'exact native bytes')
        metadata = {'libraries': [{'name': 'org.lwjgl:lwjgl:3.3.1:natives-macos-arm64',
                                  'downloads': {'artifact': {'path': 'native.jar'}}}]}
        with patch.object(runtime, 'host', return_value=('osx', 'arm64')):
            runtime.natives(self.root, metadata)
            runtime.natives(self.root, metadata, verify=True)
            (self.root / 'natives/liblwjgl.dylib').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'NATIVE_IDENTITY'):
                runtime.natives(self.root, metadata, verify=True)

    def prepare_launch(self, loader='forge'):
        pin = runtime.INSTALLERS[loader]
        manifest = {'schema': 1, 'minecraft': '1.20.1', 'loader': loader,
                    'loaderVersion': pin['version'], 'installerSHA256': pin['sha256'],
                    'minecraftSHA1': runtime.VERSION['sha1'], 'platform': ['osx', 'arm64']}
        for role in ('client', 'server'):
            installed = self.root / role / 'mods/p2wlan.jar'; installed.parent.mkdir(parents=True)
            installed.write_bytes(b'unchanged universal bytes')
        fixtures = self.root / 'build/forge/installed-fixtures'; fixtures.mkdir(parents=True)
        for name in ('LocalGameFixture', 'IntegratedFixture'):
            (fixtures / (name + '-srg.jar')).write_bytes(name.encode())
        manifest['fixtures'] = {name: {'size': len(name), 'sha256': hashlib.sha256(name.encode()).hexdigest(),
                                      'classes': ['expected/fixture']} for name in ('LocalGameFixture', 'IntegratedFixture')}
        sdk = self.root / 'legacy-sdk' / loader
        for side in ('client', 'server'):
            srg = sdk / f'libraries/net/minecraft/{side}/{runtime.MCP}/{side}-{runtime.MCP}-srg.jar'
            srg.parent.mkdir(parents=True); srg.write_bytes(('checked ' + side + ' SRG bytes').encode())
        manifest['srgs'] = runtime.srg_records(sdk)
        (self.root / 'legacy-runtime.json').write_text(json.dumps(manifest))
        version = {'id': '1.20.1-forge-' + pin['version'], 'libraries': [],
                   'arguments': {'jvm': ['-DlibraryDirectory=${library_directory}'],
                                 'game': ['--launchTarget', 'forgeclient', '--fml.forgeVersion', pin['version']]}}
        vanilla = {'libraries': [], 'assetIndex': {'id': '5'},
                   'logging': {'client': {'argument': '-Dlog4j.configurationFile=${path}', 'file': {'id': 'client-1.12.xml'}}},
                   'arguments': {'jvm': ['-XstartOnFirstThread', '-cp', '${classpath}'],
                                 'game': ['--username', '${auth_player_name}', '--gameDir', '${game_directory}', '--assetsDir', '${assets_root}']}}
        args = ['-p', 'libraries/bootstrap.jar:libraries/asm.jar', '-DlibraryDirectory=libraries',
                '-DlegacyClassPath=libraries/loader.jar:libraries/server-extra.jar',
                runtime.MAIN, '--launchTarget', 'forgeserver']
        return version, vanilla, args

    def launch_patches(self, metadata):
        from contextlib import ExitStack
        stack = ExitStack(); self.addCleanup(stack.close)
        stack.enter_context(patch.object(runtime, 'host', return_value=('osx', 'arm64')))
        stack.enter_context(patch.object(runtime, 'metadata', return_value=({}, *metadata)))
        stack.enter_context(patch.object(runtime, 'inputs', return_value=[]))
        stack.enter_context(patch.object(runtime, 'asset_inputs', return_value=[]))
        stack.enter_context(patch.object(runtime, 'outputs', return_value=[]))
        stack.enter_context(patch.object(runtime, 'natives'))
        stack.enter_context(patch.object(runtime, 'java17', return_value=(Path('/jdk17/bin/java'), Path('/jdk17'))))
        def snapshot(job, label):
            value = (job / ('build/forge/installed-fixtures/' + label + '-srg.jar')).read_bytes()
            return value, {'size': len(value), 'sha256': hashlib.sha256(value).hexdigest(), 'classes': ['expected/fixture']}
        stack.enter_context(patch.object(runtime, 'fixture_snapshot', side_effect=snapshot))

    def test_role_commands_use_production_targets_unchanged_mod_and_srg_fixture(self):
        metadata = self.prepare_launch(); self.launch_patches(metadata)
        sha = hashlib.sha256(b'unchanged universal bytes').hexdigest()
        for role, integrated, fixture, target in [('server', False, 'LocalGameFixture', 'forgeserver'),
                                                   ('server', True, 'IntegratedFixture', 'forgeclient'),
                                                   ('client', True, 'LocalGameFixture', 'forgeclient')]:
            command = runtime.command(self.root, 'forge', role, integrated, True, sha, {})
            self.assertEqual(command[0], '/jdk17/bin/java')
            self.assertIn(runtime.MAIN, command)
            self.assertEqual(command[command.index('--launchTarget') + 1], target)
            self.assertIn('-Dp2wlan.allowDevelopmentNative=true', command)
            self.assertIn('-Dp2wlan.gameFixture.installedUniversal=true', command)
            self.assertIn('-Dp2wlan.gameFixture.config=' + str(self.root / (role + '.json')), command)
            self.assertEqual((self.root / role / 'mods/p2wlan.jar').read_bytes(), b'unchanged universal bytes')
            self.assertEqual((self.root / role / ('mods/' + fixture + '.jar')).read_bytes(), fixture.encode())
            self.assertFalse(any('userdev' in arg or '/src/' in arg or '/classes/' in arg for arg in command))
            if target == 'forgeclient':
                self.assertEqual(command[command.index('--username') + 1], 'IntegratedHost' if role == 'server' else 'GuestFixture')
                self.assertIn('-XstartOnFirstThread', command)
                self.assertIn('-extra.jar', command[command.index('-cp') + 1])
            else:
                self.assertTrue(command[command.index('-p') + 1].startswith(str(self.root / 'legacy-sdk/forge/libraries')))

    def test_archive_or_manifest_mutation_fails_before_launch(self):
        metadata = self.prepare_launch('neoforge'); self.launch_patches(metadata)
        with self.assertRaisesRegex(ValueError, 'INSTALLED_ARCHIVE_IDENTITY'):
            runtime.command(self.root, 'neoforge', 'client', False, False, '0' * 64, {})
        manifest_path = self.root / 'legacy-runtime.json'
        manifest = json.loads(manifest_path.read_text()); manifest['loaderVersion'] = '47.1.105'
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'MANIFEST_IDENTITY'):
            runtime.command(self.root, 'neoforge', 'client', False, False, '0' * 64, {})

    def test_sdk_input_mutation_is_rejected_before_fixture_copy(self):
        metadata = self.prepare_launch(); self.launch_patches(metadata)
        path = self.root / 'sdk-library.jar'; path.write_bytes(b'changed')
        expected = {'size': 7, 'sha1': hashlib.sha1(b'correct').hexdigest()}
        with patch.object(runtime, 'inputs', return_value=[(path, expected)]):
            with self.assertRaisesRegex(ValueError, 'INPUT_IDENTITY'):
                runtime.command(self.root, 'forge', 'client', False, False, '0' * 64, {})
        self.assertFalse((self.root / 'client/mods/LocalGameFixture.jar').exists())

    def test_captured_fixture_mutation_and_existing_target_are_rejected(self):
        metadata = self.prepare_launch(); self.launch_patches(metadata)
        sha = hashlib.sha256(b'unchanged universal bytes').hexdigest()
        fixture = self.root / 'build/forge/installed-fixtures/LocalGameFixture-srg.jar'
        fixture.write_bytes(b'changed fixture after prepare')
        with self.assertRaisesRegex(ValueError, 'FIXTURE_IDENTITY'):
            runtime.command(self.root, 'forge', 'client', False, False, sha, {})
        fixture.write_bytes(b'LocalGameFixture')
        target = self.root / 'client/mods/LocalGameFixture.jar'; target.write_bytes(b'existing mod')
        with self.assertRaisesRegex(ValueError, 'pack_output_exists'):
            runtime.command(self.root, 'forge', 'client', False, False, sha, {})
        self.assertEqual(target.read_bytes(), b'existing mod')

    def test_manifest_duplicate_boolean_schema_and_excess_size_are_rejected(self):
        self.prepare_launch()
        manifest = self.root / 'legacy-runtime.json'
        original = manifest.read_text()
        with patch.object(runtime, 'host', return_value=('osx', 'arm64')):
            for value in (original.replace('"schema": 1', '"schema": true'),
                          original.replace('"schema": 1', '"schema": 1, "schema": 1'), ' ' * 8193):
                manifest.write_text(value)
                with self.assertRaises(ValueError): runtime.read_plan(self.root, 'forge')

    def test_sdk_root_symlink_is_rejected_before_java_or_installer(self):
        real = self.root / 'real'; real.mkdir()
        (self.root / 'legacy-sdk').symlink_to(real, target_is_directory=True)
        with patch.object(runtime, 'java17', return_value=(Path('/jdk17/bin/java'), Path('/jdk17'))):
            with self.assertRaisesRegex(ValueError, 'PATH_INVALID'):
                runtime.prepare(self.root, 'forge', {}, True)

    def test_fixture_archive_rejects_production_classes_or_disguised_owners(self):
        def bytecode(owner):
            name = owner.encode()
            pool = b'\x01' + len(name).to_bytes(2, 'big') + name + b'\x07\x00\x01'
            return b'\xca\xfe\xba\xbe\x00\x00\x00=\x00\x03' + pool + b'\x00\x21\x00\x02' + b'\x00' * 12
        roots = ['io/p2wlan/mc/forge/fixture/' + name for name in
                 ('InstalledArchiveChecks', 'IntegratedFixture')]
        roots += ['io/p2wlan/mc/game/v1_20_1/fixture/' + name for name in
                  ('GameFixtureData', 'InstalledArtifact', 'GameFixtureApi', 'IntegratedHostChecks')]
        fixture = self.root / 'build/forge/installed-fixtures/IntegratedFixture-srg.jar'
        fixture.parent.mkdir(parents=True)
        metadata = runtime.PROJECT / 'java/adapters/forge-1.20.1/src/integratedFixture/resources/META-INF/mods.toml'
        pack = runtime.PROJECT / 'java/adapters/forge-1.20.1/src/integratedFixture/resources/pack.mcmeta'
        def write(extra=None, disguise=False, pack_value=None, omit_pack=False, metadata_mode=None):
            with zipfile.ZipFile(fixture, 'w') as archive:
                archive.writestr('META-INF/mods.toml', metadata.read_bytes())
                if not omit_pack:
                    entry = zipfile.ZipInfo('pack.mcmeta')
                    if metadata_mode is not None: entry.external_attr = metadata_mode << 16
                    archive.writestr(entry, pack.read_bytes() if pack_value is None else pack_value)
                for owner in roots:
                    archive.writestr(owner + '.class', bytecode('io/p2wlan/mc/common/AgentLifecycle' if disguise else owner))
                if extra: archive.writestr(extra + '.class', bytecode(extra))
        write(); runtime.fixture_snapshot(self.root, 'IntegratedFixture')
        write(extra='io/p2wlan/mc/common/AgentLifecycle')
        with self.assertRaisesRegex(ValueError, 'FIXTURE_OWNER'):
            runtime.fixture_snapshot(self.root, 'IntegratedFixture')
        write(disguise=True)
        with self.assertRaisesRegex(ValueError, 'FIXTURE_CLASS'):
            runtime.fixture_snapshot(self.root, 'IntegratedFixture')
        write(omit_pack=True)
        with self.assertRaisesRegex(ValueError, 'FIXTURE_PACK_METADATA'):
            runtime.fixture_snapshot(self.root, 'IntegratedFixture')
        write(pack_value=pack.read_bytes().replace(b'15', b'48'))
        with self.assertRaisesRegex(ValueError, 'FIXTURE_PACK_METADATA'):
            runtime.fixture_snapshot(self.root, 'IntegratedFixture')
        write(pack_value=json.dumps(json.loads(pack.read_bytes())).encode())
        with self.assertRaisesRegex(ValueError, 'FIXTURE_PACK_METADATA'):
            runtime.fixture_snapshot(self.root, 'IntegratedFixture')
        for mode in (0o120777, 0o040755):
            write(metadata_mode=mode)
            with self.subTest(mode=mode), self.assertRaisesRegex(ValueError, 'FIXTURE_PACK_METADATA'):
                runtime.fixture_snapshot(self.root, 'IntegratedFixture')

    def test_client_and_server_srg_mutation_fails_before_launch_or_fixture_install(self):
        metadata = self.prepare_launch(); self.launch_patches(metadata)
        sha = hashlib.sha256(b'unchanged universal bytes').hexdigest()
        sdk = self.root / 'legacy-sdk/forge'
        for side in ('client', 'server'):
            srg = sdk / f'libraries/net/minecraft/{side}/{runtime.MCP}/{side}-{runtime.MCP}-srg.jar'
            original = srg.read_bytes(); srg.write_bytes(bytes([original[0] ^ 1]) + original[1:])
            with self.subTest(side=side), self.assertRaisesRegex(ValueError, 'LEGACY_RUNTIME_SRG_IDENTITY'):
                runtime.command(self.root, 'forge', 'client', False, False, sha, {})
            self.assertFalse((self.root / 'client/mods/LocalGameFixture.jar').exists())
            srg.write_bytes(original)
        runtime.command(self.root, 'forge', 'client', False, False, sha, {})

    def test_srg_manifest_requires_both_exact_hash_and_size_records(self):
        self.prepare_launch()
        manifest = self.root / 'legacy-runtime.json'; original = json.loads(manifest.read_text())
        changed = []
        from copy import deepcopy
        value = deepcopy(original); del value['srgs']['server']; changed.append(value)
        value = deepcopy(original); value['srgs']['client']['size'] = True; changed.append(value)
        value = deepcopy(original); value['srgs']['server']['sha256'] = 'not-a-hash'; changed.append(value)
        value = deepcopy(original); value['srgs']['client']['unexpected'] = 1; changed.append(value)
        with patch.object(runtime, 'host', return_value=('osx', 'arm64')):
            for value in changed:
                manifest.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'LEGACY_RUNTIME_MANIFEST_IDENTITY'):
                    runtime.read_plan(self.root, 'forge')


if __name__ == '__main__': unittest.main()

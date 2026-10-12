"""Legacy build identity, Java-runtime and SRG Mixin mapping failure policies."""
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import legacy_gradle as legacy
from artifact_checks import VerificationError
from test_mod_jar import bytecode, jar


class LegacyGradleTests(unittest.TestCase):
    @staticmethod
    def field_fixture(field, original, name_indices=(1,)):
        def utf8(value):
            return b'\x01' + len(value).to_bytes(2, 'big') + value
        pool = utf8(field) + utf8(original) + utf8(b'Ljava/lang/Object;')
        declarations = b''.join(b'\x00\x02' + index.to_bytes(2, 'big') + b'\x00\x03\x00\x00' for index in name_indices)
        return b'\xca\xfe\xba\xbe\x00\x00\x00\x3d\x00\x04' + pool + b'\x00' * 8 \
            + len(name_indices).to_bytes(2, 'big') + declarations + b'\x00' * 4

    def archive_fixture(self):
        dedicated = ('io/p2wlan/mc/forge/ForgeP2wlan', 'io/p2wlan/mc/forge/ForgeRuntime',
                     'io/p2wlan/mc/forge/legacy/ForgeServerEvents', 'io/p2wlan/mc/forge/legacy/LocalNativeResources',
                     'io/p2wlan/mc/game/server/WorldCommands', *legacy.SERVER_TARGETS)
        top = {name + '.class': bytecode(61) for name in (*dedicated, 'io/p2wlan/mc/forge/legacy/ForgeClientEvents', *legacy.CLIENT_TARGETS)}
        top['META-INF/mods.toml'] = '''modLoader="javafml"
loaderVersion="[47,)"
[[mods]]
modId="p2wlan"
version="0.1.0"
[[dependencies.p2wlan]]
modId="forge"
mandatory=true
versionRange="[47.3.2]"
ordering="NONE"
side="BOTH"
[[dependencies.p2wlan]]
modId="minecraft"
mandatory=true
versionRange="[1.20.1]"
ordering="NONE"
side="BOTH"
'''
        top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\nMixinConfigs: ' + legacy.SERVER_CONFIG + ',' + legacy.CLIENT_CONFIG + '\r\n\r\n'
        top['pack.mcmeta'] = json.dumps({'pack': {'description': 'P2WLAN', 'pack_format': 15}})
        for path, package, key, targets, mapping in [
                (legacy.SERVER_CONFIG, 'io.p2wlan.mc.game.server.mixin', 'mixins', legacy.SERVER_TARGETS, legacy.SERVER_REFMAP),
                (legacy.CLIENT_CONFIG, 'io.p2wlan.mc.game.mixin', 'client', legacy.CLIENT_TARGETS, legacy.CLIENT_REFMAP)]:
            top[path] = json.dumps({'required': True, 'minVersion': '0.8.5', 'package': package,
                                   'compatibilityLevel': 'JAVA_17', 'refmap': mapping,
                                   key: [name.rsplit('/', 1)[-1] for name in targets], 'injectors': {'defaultRequire': 1}})
            top[mapping] = json.dumps({'mappings': targets, 'data': {'searge': targets}})
        for owner, field, original in legacy.SHADOW_FIELDS:
            top['io/p2wlan/mc/game/' + owner + '.class'] = self.field_fixture(field, original)
        return top

    def check_archive(self, top):
        # Only the class/refmap leaf policy is under test here; no native or
        # gameplay claim follows from these synthetic class declarations.
        with patch('jarjar_artifact.portable', return_value=['synthetic portable owner']):
            return legacy.verify(io.BytesIO(jar(top)), True)

    def prepare(self, root, checksum=None):
        jar = root / 'java/gradle/wrapper/gradle-wrapper.jar'
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b'test wrapper, not executable')
        props = root / 'java/forge-legacy/gradle/wrapper/gradle-wrapper.properties'
        props.parent.mkdir(parents=True)
        props.write_bytes(legacy.DISTRIBUTION + b'\n' + ('distributionSha256Sum=' + (checksum or legacy.GRADLE_SHA256) + '\n').encode())
        return props

    def test_wrapper_distribution_is_exact_and_create_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            props = self.prepare(root)
            with patch.object(legacy, 'PROJECT', root), patch.object(legacy, 'java17', return_value=(root / 'jdk17/bin/java', root / 'jdk17')):
                selected = legacy.command({})
                target = Path(selected[-1])
                self.assertEqual(target.with_suffix('.properties').read_bytes(), props.read_bytes())
                self.assertIn('-Dorg.gradle.java.installations.paths=' + str(root / 'jdk17'), selected)
                self.assertIn('-Dorg.gradle.java.installations.auto-download=false', selected)
                self.assertEqual(legacy.command({}), selected)
                target.write_bytes(b'tampered wrapper')
                with self.assertRaisesRegex(ValueError, 'FORGE_BOOTSTRAP_INPUT_CHANGED'):
                    legacy.command({})
                self.assertEqual(target.read_bytes(), b'tampered wrapper')
                original = props.read_bytes()
                for value in [b'distributionUrl=https://example.com/gradle.zip\n', original + original]:
                    props.write_bytes(value)
                    with self.assertRaisesRegex(ValueError, 'LEGACY_GRADLE_DISTRIBUTION_IDENTITY'):
                        legacy.command({})

    def test_local_zip_requires_absolute_regular_checksum_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = b'test distribution, not executable'
            checksum = hashlib.sha256(data).hexdigest()
            self.prepare(root, checksum)
            archive = root / 'fixture distribution.zip'
            archive.write_bytes(data)
            with patch.object(legacy, 'PROJECT', root), patch.object(legacy, 'GRADLE_SHA256', checksum), \
                    patch.object(legacy, 'java17', return_value=(root / 'jdk17/bin/java', root / 'jdk17')):
                for path in ['relative.zip', str(root / 'absent.zip')]:
                    with self.assertRaisesRegex(ValueError, 'LEGACY_GRADLE_DISTRIBUTION_INVALID'):
                        legacy.command({'P2WLAN_LEGACY_GRADLE_ZIP': path})
                selected = legacy.command({'P2WLAN_LEGACY_GRADLE_ZIP': str(archive)})
                self.assertIn('bootstrap-local', str(selected[-1]))
                self.assertIn(archive.as_uri(), Path(selected[-1]).with_suffix('.properties').read_text())
                archive.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError, 'LEGACY_GRADLE_DISTRIBUTION_IDENTITY'):
                    legacy.command({'P2WLAN_LEGACY_GRADLE_ZIP': str(archive)})
                linked = root / 'link.zip'; linked.symlink_to(archive)
                with self.assertRaisesRegex(ValueError, 'LEGACY_GRADLE_DISTRIBUTION_INVALID'):
                    legacy.command({'P2WLAN_LEGACY_GRADLE_ZIP': str(linked)})

    def test_java17_is_checked_before_launch_and_explicit_home_wins(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / 'jdk17'; (home / 'bin').mkdir(parents=True)
            (home / 'bin/java').touch()
            env = {'P2WLAN_LEGACY_JAVA_HOME': str(home), 'JAVA_HOME': str(root / 'jdk21')}
            result = subprocess.CompletedProcess([], 0, '', '    java.specification.version = 17\n')
            with patch.object(legacy.os, 'name', 'posix'), patch.object(legacy.subprocess, 'run', return_value=result) as run:
                self.assertEqual(legacy.java17(env), (home / 'bin/java', home))
                self.assertEqual(run.call_args.args[0][0], str(home / 'bin/java'))
                self.assertEqual(run.call_args.kwargs['timeout'], 5)
                for version in ['21', '1.8', '17\n    java.specification.version = 17']:
                    result.stderr = '    java.specification.version = ' + version + '\n'
                    with self.assertRaisesRegex(ValueError, 'LEGACY_JAVA17_REQUIRED'):
                        legacy.java17(env)
                for value in ['relative', str(root / 'missing')]:
                    with self.assertRaisesRegex(ValueError, 'LEGACY_JAVA17_REQUIRED'):
                        legacy.java17(env | {'P2WLAN_LEGACY_JAVA_HOME': value})

    def test_srg_refmaps_require_all_selected_owners_and_targets(self):
        maps = deepcopy(legacy.CLIENT_TARGETS)
        value = {'mappings': maps, 'data': {'searge': deepcopy(maps)}}
        legacy.refmap(value, legacy.CLIENT_TARGETS)
        variants = []
        changed = deepcopy(value); changed['data'] = {}; variants.append(changed)
        changed = deepcopy(value); changed['mappings'].pop(next(iter(maps))); variants.append(changed)
        changed = deepcopy(value); changed['mappings']['unexpected/Mixin'] = {}; variants.append(changed)
        changed = deepcopy(value); changed['mappings'][next(iter(maps))]['0'] = 'connect()V'; changed['data']['searge'] = deepcopy(changed['mappings']); variants.append(changed)
        changed = deepcopy(value); changed['mappings'][next(iter(maps))]['extra'] = 'unmapped()V'; changed['data']['searge'] = deepcopy(changed['mappings']); variants.append(changed)
        changed = deepcopy(value)
        owner = next(iter(maps)); key = next(iter(maps[owner]))
        changed['mappings'][owner][key] = maps[owner][key].replace('Lnet/minecraft/network/Connection;', 'Lnet/minecraft/Unrelated;')
        changed['data']['searge'] = deepcopy(changed['mappings']); variants.append(changed)
        for changed in variants:
            with self.assertRaises(VerificationError):
                legacy.refmap(changed, legacy.CLIENT_TARGETS)

    def test_server_login_srg_refmaps_classes_and_connection_shadow_are_exact(self):
        self.assertEqual(self.check_archive(self.archive_fixture()), ['synthetic portable owner'])
        top = self.archive_fixture(); top[legacy.MODERN_SERVER_MIXIN] = bytecode(61)
        with self.assertRaisesRegex(VerificationError, 'legacy_forge_class_owner'):
            self.check_archive(top)
        for owner, targets in legacy.SERVER_TARGETS.items():
            for key in targets:
                for change in (None, 'Lnet/minecraft/Unrelated;wrong()V'):
                    maps = deepcopy(legacy.SERVER_TARGETS)
                    if change is None:
                        del maps[owner][key]
                    else:
                        maps[owner][key] = change
                    top = self.archive_fixture()
                    top[legacy.SERVER_REFMAP] = json.dumps({'mappings': maps, 'data': {'searge': maps}})
                    with self.subTest(owner=owner, key=key, change=change), self.assertRaisesRegex(VerificationError, 'legacy_forge_refmap_contract'):
                        self.check_archive(top)
        for owner in legacy.SERVER_TARGETS:
            top = self.archive_fixture(); del top[owner + '.class']
            with self.subTest(owner=owner), self.assertRaisesRegex(VerificationError, 'mod_archive_member_invalid'):
                self.check_archive(top)
        for indices in [(2,), (1, 2)]:
            top = self.archive_fixture()
            top['io/p2wlan/mc/game/server/mixin/ServerLoginMixin.class'] = self.field_fixture(b'f_10013_', b'connection', indices)
            with self.subTest(indices=indices), self.assertRaisesRegex(VerificationError, 'legacy_forge_shadow_srg'):
                self.check_archive(top)
        for classes in [['ServerConnectionMixin'], [name.rsplit('/', 1)[-1] for name in legacy.SERVER_TARGETS] + ['UnknownMixin']]:
            top = self.archive_fixture()
            config = json.loads(top[legacy.SERVER_CONFIG]); config['mixins'] = classes
            top[legacy.SERVER_CONFIG] = json.dumps(config)
            with self.assertRaisesRegex(VerificationError, 'legacy_forge_mixin_contract'):
                self.check_archive(top)

    def test_shadow_field_reads_declaration_not_unused_constant(self):
        def fixture(name_index):
            return self.field_fixture(b'f_99766_', b'screen', (name_index,))
        self.assertEqual(legacy.field_names(fixture(1)), {b'f_99766_'})
        self.assertEqual(legacy.field_names(fixture(2)), {b'screen'})
        for value in [fixture(1)[:-5], b'bad', fixture(9)]:
            with self.assertRaises(VerificationError):
                legacy.field_names(value)

    def test_run_entry_point_selects_only_legacy_build_graph(self):
        with patch.object(sys, 'argv', ['legacy_gradle.py', 'run', ':forge:jarJar', '--offline']), \
                patch.object(legacy, 'command', return_value=['java17', '-jar', 'legacy-wrapper.jar']), \
                patch.object(legacy.subprocess, 'call', return_value=7) as run:
            self.assertEqual(legacy.main(), 7)
            self.assertEqual(run.call_args.args[0], ['java17', '-jar', 'legacy-wrapper.jar', '--no-daemon', ':forge:jarJar', '--offline'])
            self.assertEqual(run.call_args.kwargs['cwd'], legacy.PROJECT / 'java/forge-legacy')


if __name__ == '__main__':
    unittest.main()

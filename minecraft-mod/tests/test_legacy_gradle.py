"""Legacy build identity, Java-runtime and SRG Mixin mapping failure policies."""
from copy import deepcopy
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import legacy_gradle as legacy
from artifact_checks import VerificationError


class LegacyGradleTests(unittest.TestCase):
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

    def test_shadow_field_reads_declaration_not_unused_constant(self):
        def utf8(value):
            return b'\x01' + len(value).to_bytes(2, 'big') + value
        def fixture(name_index):
            pool = utf8(b'f_99766_') + utf8(b'screen') + utf8(b'Ljava/lang/Object;')
            # Three UTF8s, no interfaces, one field and no field attributes.
            return b'\xca\xfe\xba\xbe\x00\x00\x00\x3d\x00\x04' + pool + b'\x00' * 8 + b'\x00\x01' \
                + b'\x00\x02' + name_index.to_bytes(2, 'big') + b'\x00\x03\x00\x00'
        self.assertEqual(legacy.field_names(fixture(1)), {b'f_99766_'})
        self.assertEqual(legacy.field_names(fixture(2)), {b'screen'})
        for value in [fixture(1)[:-1], b'bad', fixture(9)]:
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

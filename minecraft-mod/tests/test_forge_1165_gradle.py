"""Forge 1.16.5 bootstrap identity and compile-only SDK boundaries."""
import hashlib
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import forge_1165_gradle as sdk


class Forge1165GradleTests(unittest.TestCase):
    def prepare(self, root, checksum=None):
        wrapper = root / 'java/gradle/wrapper/gradle-wrapper.jar'
        wrapper.parent.mkdir(parents=True)
        wrapper.write_bytes(b'fixture wrapper; never executed')
        properties = root / sdk.ROOT / 'gradle/wrapper/gradle-wrapper.properties'
        properties.parent.mkdir(parents=True)
        properties.write_bytes(sdk.DISTRIBUTION + b'\n' + ('distributionSha256Sum=' + (checksum or sdk.GRADLE_SHA256) + '\n').encode())
        return properties

    def test_pinned_distribution_and_create_only_wrapper(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); properties = self.prepare(root)
            with patch.object(sdk, 'PROJECT', root), patch.object(sdk, 'java17', return_value=(root / 'jdk17/bin/java', root / 'jdk17')):
                selected = sdk.command({})
                target = Path(selected[selected.index('-jar') + 1])
                self.assertEqual(target.with_suffix('.properties').read_bytes(), properties.read_bytes())
                self.assertIn('/forge-1165/build/bootstrap-official/', str(target))
                self.assertEqual(sdk.command({}), selected)
                original = properties.read_bytes()
                for invalid in [original + original, original.replace(b'gradle-8.4-bin.zip', b'gradle-9.3.1-bin.zip')]:
                    properties.write_bytes(invalid)
                    with self.assertRaisesRegex(ValueError, 'FORGE1165_GRADLE_DISTRIBUTION_IDENTITY'): sdk.command({})
                properties.write_bytes(original)
                target.write_bytes(b'tampered')
                with self.assertRaisesRegex(ValueError, 'FORGE_BOOTSTRAP_INPUT_CHANGED'): sdk.command({})
                self.assertEqual(target.read_bytes(), b'tampered')

    def test_local_distribution_requires_regular_absolute_checksum(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); payload = b'fixture distribution; never executed'
            checksum = hashlib.sha256(payload).hexdigest(); self.prepare(root, checksum)
            archive = root / 'official distribution.zip'; archive.write_bytes(payload)
            with patch.object(sdk, 'PROJECT', root), patch.object(sdk, 'GRADLE_SHA256', checksum), \
                    patch.object(sdk, 'java17', return_value=(root / 'jdk17/bin/java', root / 'jdk17')):
                for invalid in ['relative.zip', str(root / 'missing.zip')]:
                    with self.assertRaisesRegex(ValueError, 'FORGE1165_GRADLE_DISTRIBUTION_INVALID'):
                        sdk.command({'P2WLAN_FORGE1165_GRADLE_ZIP': invalid})
                selected = sdk.command({'P2WLAN_FORGE1165_GRADLE_ZIP': str(archive)})
                self.assertIn(archive.as_uri(), Path(selected[selected.index('-jar') + 1]).with_suffix('.properties').read_text())
                archive.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError, 'FORGE1165_GRADLE_DISTRIBUTION_IDENTITY'):
                    sdk.command({'P2WLAN_FORGE1165_GRADLE_ZIP': str(archive)})
                linked = root / 'linked.zip'; linked.symlink_to(archive)
                with self.assertRaisesRegex(ValueError, 'FORGE1165_GRADLE_DISTRIBUTION_INVALID'):
                    sdk.command({'P2WLAN_FORGE1165_GRADLE_ZIP': str(linked)})

    def test_explicit_sdk_java_home_reuses_verified_java17_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); self.prepare(root)
            environment = {'P2WLAN_FORGE1165_JAVA_HOME': str(root / 'sdk17'), 'JAVA_HOME': str(root / 'modern21')}
            with patch.object(sdk, 'PROJECT', root), patch.object(sdk, 'java17', return_value=(root / 'sdk17/bin/java', root / 'sdk17')) as selected:
                sdk.command(environment)
                self.assertEqual(selected.call_args.args[0]['P2WLAN_LEGACY_JAVA_HOME'], environment['P2WLAN_FORGE1165_JAVA_HOME'])
                self.assertNotIn('P2WLAN_LEGACY_JAVA_HOME', environment)

    def test_entry_point_selects_exact_sdk_actions(self):
        with patch.object(sys, 'argv', ['forge_1165_gradle.py', 'compile', '--offline']), \
                patch.object(sdk, 'command', return_value=['java17', '-jar', 'sdk-wrapper.jar']), \
                patch.object(sdk.subprocess, 'call', return_value=9) as launch:
            self.assertEqual(sdk.main(), 9)
            self.assertEqual(launch.call_args.args[0], ['java17', '-jar', 'sdk-wrapper.jar', '--no-daemon', ':forge:compileSdk', '--offline'])
            self.assertEqual(launch.call_args.kwargs['cwd'], sdk.PROJECT / 'java/forge-1165')
        with patch.object(sys, 'argv', ['forge_1165_gradle.py', 'compile', ':forge:runClient']), \
                patch.object(sdk, 'command') as prepare:
            with self.assertRaises(SystemExit): sdk.main()
            prepare.assert_not_called()
        for action, expected in {
                'build': [':forge:reobfJar'],
                'fixtures': [':forge:reobfInstalledLocalGameFixture', ':forge:reobfInstalledIntegratedFixture',
                             '-PlocalGameFixture', '-PintegratedGameFixture']}.items():
            with patch.object(sys, 'argv', ['forge_1165_gradle.py', action]), \
                    patch.object(sdk, 'command', return_value=['java17', '-jar', 'sdk-wrapper.jar']), \
                    patch.object(sdk.subprocess, 'call', return_value=0) as launch:
                self.assertEqual(sdk.main(), 0)
                self.assertEqual(launch.call_args.args[0], ['java17', '-jar', 'sdk-wrapper.jar', '--no-daemon', *expected])

    def test_mcp_java8_is_a_separate_checked_toolchain(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); self.prepare(root)
            mcp = root / 'jdk8'; (mcp / 'bin').mkdir(parents=True); (mcp / 'bin/java').touch(); (mcp / 'bin/javac').touch()
            (mcp / 'lib').mkdir(); (mcp / 'lib/tools.jar').touch()
            result = subprocess.CompletedProcess([], 0, '', '    java.specification.version = 1.8\n')
            with patch.object(sdk, 'PROJECT', root), patch.object(sdk, 'java17', return_value=(root / 'sdk17/bin/java', root / 'sdk17')), \
                    patch.object(sdk.os, 'name', 'posix'), patch.object(sdk.subprocess, 'run', return_value=result):
                selected = sdk.command({'P2WLAN_FORGE1165_MCP_JAVA_HOME': str(mcp)})
                self.assertEqual(selected[0], str(root / 'sdk17/bin/java'))
                self.assertIn('-Porg.gradle.java.installations.paths=' + str(root / 'sdk17') + ',' + str(mcp), selected)
                result.stderr = '    java.specification.version = 17\n'
                with self.assertRaisesRegex(ValueError, 'FORGE1165_MCP_JAVA8_REQUIRED'):
                    sdk.command({'P2WLAN_FORGE1165_MCP_JAVA_HOME': str(mcp)})


if __name__ == '__main__':
    unittest.main()

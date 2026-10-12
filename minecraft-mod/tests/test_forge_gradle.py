"""Pinned bootstrap publication/input checks; real Gradle/game runs are separate."""
import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import forge_gradle as forge
import java


class ForgeGradleTests(unittest.TestCase):
    def prepare(self, root, checksum=None):
        jar = root / 'java/gradle/wrapper/gradle-wrapper.jar'
        properties = root / 'java/forge/gradle/wrapper/gradle-wrapper.properties'
        jar.parent.mkdir(parents=True)
        properties.parent.mkdir(parents=True)
        jar.write_bytes(b'fixture wrapper, not executable')
        properties.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip\n'
                              + 'distributionSha256Sum=' + (checksum or forge.GRADLE_SHA256) + '\n')
        return jar, properties

    def test_create_only_copy_exact_reuse_and_tamper_refusal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, properties = self.prepare(root)
            with patch.object(forge, 'PROJECT', root):
                first = forge.command({'JAVA_HOME': str(root / 'jdk21')})
                copied = Path(first[-1])
                self.assertEqual(copied.read_bytes(), jar.read_bytes())
                self.assertEqual(copied.with_suffix('.properties').read_bytes(), properties.read_bytes())
                self.assertEqual(forge.command({'JAVA_HOME': str(root / 'jdk21')}), first)
                copied.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError, 'FORGE_BOOTSTRAP_INPUT_CHANGED'):
                    forge.command({'JAVA_HOME': str(root / 'jdk21')})
                self.assertEqual(copied.read_bytes(), b'changed')

    def test_bad_wrapper_link_size_and_distribution_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, properties = self.prepare(root)
            with patch.object(forge, 'PROJECT', root):
                properties.write_text('distributionSha256Sum=unverified\n')
                with self.assertRaisesRegex(ValueError, 'FORGE_GRADLE_DISTRIBUTION_IDENTITY'):
                    forge.command({})
                properties.write_text('distributionSha256Sum=' + forge.GRADLE_SHA256 + '\n')
                jar.write_bytes(b'x' * (128 * 1024 + 1))
                with self.assertRaisesRegex(ValueError, 'FORGE_BOOTSTRAP_INPUT_INVALID'):
                    forge.command({})
                jar.unlink(); jar.symlink_to(properties)
                with self.assertRaisesRegex(ValueError, 'FORGE_BOOTSTRAP_INPUT_INVALID'):
                    forge.command({})

    def test_optional_local_zip_requires_absolute_regular_exact_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = b'fixture distribution, not Gradle'
            digest = hashlib.sha256(data).hexdigest()
            self.prepare(root, digest)
            archive = root / 'gradle fixture.zip'; archive.write_bytes(data)
            with patch.object(forge, 'PROJECT', root), patch.object(forge, 'GRADLE_SHA256', digest):
                for value in ('relative.zip', str(root / 'absent.zip')):
                    with self.assertRaisesRegex(ValueError, 'FORGE_GRADLE_DISTRIBUTION_INVALID'):
                        forge.command({'P2WLAN_FORGE_GRADLE_ZIP': value})
                chosen = forge.command({'JAVA_HOME': str(root / 'jdk21'), 'P2WLAN_FORGE_GRADLE_ZIP': str(archive)})
                properties = Path(chosen[-1]).with_suffix('.properties').read_text()
                self.assertIn(archive.as_uri(), properties)
                self.assertIn('bootstrap-local', str(chosen[-1]))
                archive.write_bytes(b'changed')
                with self.assertRaisesRegex(ValueError, 'FORGE_GRADLE_DISTRIBUTION_IDENTITY'):
                    forge.command({'P2WLAN_FORGE_GRADLE_ZIP': str(archive)})

    def test_helper_jdks_are_build_only_explicit_absolute_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); self.prepare(root)
            homes = [root / name for name in ('jdk21', 'jdk25', 'jdk8')]
            for home in homes: home.mkdir()
            env = {'JAVA_HOME': str(homes[0]), 'P2WLAN_FORGE_JAVA25_HOME': str(homes[1]), 'P2WLAN_FORGE_JAVA8_HOME': str(homes[2])}
            with patch.object(forge, 'PROJECT', root):
                selected = forge.command(env)
                self.assertIn('-Dorg.gradle.java.installations.paths=' + ','.join(map(str, homes)), selected)
                for value in ('relative', str(root / 'missing')):
                    with self.assertRaisesRegex(ValueError, 'FORGE_JAVA_TOOLCHAIN_CONFIG'):
                        forge.command(env | {'P2WLAN_FORGE_JAVA8_HOME': value})

    def test_publish_concurrent_identical_bytes_and_link_refusal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / 'wrapper.jar'
            with ThreadPoolExecutor(max_workers=4) as pool:
                list(pool.map(lambda unused: forge.publish(target, b'bounded fixture'), range(4)))
            self.assertEqual(target.read_bytes(), b'bounded fixture')
            self.assertEqual(list(root.iterdir()), [target])
            linked = root / 'link.jar'; linked.symlink_to(target)
            with self.assertRaisesRegex(ValueError, 'FORGE_BOOTSTRAP_INPUT_INVALID'):
                forge.publish(linked, b'bounded fixture')

    def test_forge_cli_uses_separate_build_graph_and_checked_run_tasks(self):
        for selection, task in [('forge-build', ':forge:build'), ('forge-server', ':forge:runServer'), ('forge-client', ':forge:runClient')]:
            with patch.object(sys, 'argv', ['java.py', selection, '--offline']), \
                 patch.object(java.subprocess, 'check_output', return_value='/fixture/jdk21'), \
                 patch.object(forge, 'command', return_value=['java21', '-jar', 'forge9-wrapper.jar']), \
                 patch.object(java.subprocess, 'call', return_value=7) as call:
                self.assertEqual(java.main(), 7)
                args, kwargs = call.call_args
                self.assertEqual(args[0][:3], ['java21', '-jar', 'forge9-wrapper.jar'])
                self.assertIn(task, args[0])
                self.assertEqual(kwargs['cwd'], java.PROJECT / 'java/forge')
                self.assertIn('--offline', args[0])
                if selection != 'forge-build':
                    self.assertLess(args[0].index(':forge:verifyModJar'), args[0].index(task))


if __name__ == '__main__':
    unittest.main()

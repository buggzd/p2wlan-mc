import importlib.util
import json
import tempfile
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'tools'))
spec = importlib.util.spec_from_file_location('neoforge_fixture', Path(__file__).with_name('neoforge_server_fixture.py'))
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)


class NeoForgeFixtureTests(unittest.TestCase):
    def test_consent_precedes_every_filesystem_or_process_effect(self):
        with patch('sys.argv', ['fixture']), patch.object(fixture.tempfile, 'mkdtemp') as create, \
                patch.object(fixture, 'FixtureProcess') as process, patch.object(fixture.Path, 'mkdir') as mkdir:
            with self.assertRaises(SystemExit) as error: fixture.main()
            self.assertEqual(error.exception.code, 2)
            create.assert_not_called(); process.assert_not_called(); mkdir.assert_not_called()

    def test_build_and_server_use_the_same_isolated_source_check_and_scope(self):
        job = Path('/fixture-owned-job')
        command = fixture.gradle_command(job, 'a' * 32, [':neoforge:runServerFixture'], True, True)
        self.assertIn('-PneoforgeAdapter', command)
        self.assertIn('-PserverBootFixture', command)
        self.assertIn('-PdevelopmentNative', command)
        self.assertIn('--offline', command)
        self.assertIn('-PserverFixtureDirectory=/fixture-owned-job/game', command)
        self.assertIn('-Dp2wlan.fixture.outputRoot=/fixture-owned-job/build', command)
        self.assertFalse(any('fabricAdapter' in arg or 'online-mode=true' in arg for arg in command))
        plain = fixture.gradle_command(job, 'a' * 32, [':neoforge:verifyModJar'], False, False)
        self.assertNotIn('-PdevelopmentNative', plain)

    def test_installed_run_disables_development_mod_and_legacy_library_fallback(self):
        command = fixture.gradle_command(Path('/fixture-job'), 'a' * 32, [':neoforge:runServerFixture'], True, True, True)
        self.assertIn('-PserverInstalledFixture', command)
        self.assertIn('--project-cache-dir', command)
        build = (PROJECT / 'java/adapters/neoforge-1.21.1/build.gradle').read_text()
        self.assertIn('if (!installedFixture)', build)
        self.assertIn('loadedMods = installedFixture ? [neoForge.mods.p2wlan_server_boot_test]', build)
        self.assertNotIn("jarJar('net.java.dev.jna", build)

    def test_exact_result_binds_loader_installation_assertions_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.json'
            data = {'schema': 1, 'run': 'a' * 32, 'status': 'pass', 'reason': 'OK', 'checks': 32,
                    'minecraft': '1.21.1', 'loader': 'neoforge', 'loader_version': '21.1.256',
                    'installation': 'jarjar', 'helper_reason': None, 'helper_cleanup': True,
                    'fixture_wrote_eula': False, 'cases': sorted(fixture.CASES)}
            path.write_text(json.dumps(data))
            self.assertEqual(fixture.verify_result(path, 'a' * 32, True), data)
            with self.assertRaises(ValueError): fixture.verify_result(path, 'a' * 32, False)
            for field, value in [('run', 'b' * 32), ('loader', 'fabric'), ('loader_version', '21.1.1'),
                                 ('checks', True), ('checks', 31), ('helper_cleanup', False),
                                 ('helper_reason', 'RESOURCE_NOT_LOCAL'), ('cases', []), ('extra', True)]:
                path.write_text(json.dumps(data | {field: value}))
                with self.assertRaises(ValueError): fixture.verify_result(path, 'a' * 32, True)
            path.write_text(json.dumps(data).replace('"schema": 1', '"schema": 1, "schema": 1'))
            with self.assertRaises(ValueError): fixture.verify_result(path, 'a' * 32, True)

    def test_process_exit_failure_and_timeout_always_use_owned_cleanup(self):
        for effect in (None, fixture.subprocess.TimeoutExpired('fixture', 5)):
            child = Mock()
            child.process.wait.return_value = 2
            child.process.wait.side_effect = effect
            with patch.object(fixture, 'FixtureProcess', return_value=child):
                with self.assertRaises((ValueError, fixture.subprocess.TimeoutExpired)):
                    fixture.run(['fixture'], {}, Path('/fixture-owned-job/process.log'), 5)
            child.process.wait.assert_called_once_with(timeout=5)
            child.close.assert_called_once_with()

    def test_development_libraries_are_explicit_without_changing_common_code(self):
        build = (PROJECT / 'java/adapters/neoforge-1.21.1/build.gradle').read_text()
        for module in ('common', 'netty-transport', 'native-bundle'):
            self.assertIn(f"additionalRuntimeClasspath project(':{module}')", build)
            self.assertIn(f"jarJar project(':{module}')", build)

    def test_fixture_stays_outside_production_and_run_requires_archive_check(self):
        build = (PROJECT / 'java/adapters/neoforge-1.21.1/build.gradle').read_text()
        self.assertIn("providers.gradleProperty('serverBootFixture').isPresent()", build)
        self.assertIn("tasks.named('verifyModJar')", build)
        self.assertIn("'runServerFixture'", build)
        self.assertIn("sourceSets.create('serverFixture')", build)
        self.assertNotIn('sourceSets.main.java.srcDir', build)
        common = (PROJECT / 'java/adapters/neoforge-1.21.1/src/main/java/io/p2wlan/mc/neoforge/NeoForgeP2wlan.java').read_text()
        self.assertNotIn('net.minecraft.client', common)
        self.assertNotIn('fixture', common)
        self.assertIn('new WorldSessions<>(AGENT)', common)
        self.assertIn('network::startTcpServerListener', common)


if __name__ == '__main__': unittest.main()

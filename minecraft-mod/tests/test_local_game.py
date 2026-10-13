import importlib.util
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from local_game import wait_game, adapter_project, game_environment, java_directory, CASES, CHECKS, INTEGRATED_SERVER_CASES, ROUNDS, archive_sha256, bounded_json, external_archive, gradle_command, install_archive, launch_game, main, prepare, prepare_seed_binary, server_settings, verify_ready, verify_result


class LocalGameContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / "result.json"
        self.run = "a" * 32

    def result(self, role):
        return {"schema": 1, "run": self.run, "role": role, "status": "pass", "reason": "OK", "checks": CHECKS[role],
                "minecraft": "1.21.1", "helper_cleanup": True, "world_cleanup": True, "joins": 1, "rounds": ROUNDS,
                "cases": sorted(CASES[role])}

    def test_loader_selection_uses_exact_independent_adapter_and_run_paths(self):
        job=Path('/fixture-job')
        command=gradle_command(job,[':neoforge:runLocalGameClient'],True,True,'neoforge')
        self.assertIn('-PneoforgeAdapter',command)
        self.assertNotIn('-PfabricAdapter',command)
        self.assertIn('-PlocalGameServerDirectory=/fixture-job/server',command)
        self.assertIn('-PlocalGameClientDirectory=/fixture-job/client',command)
        self.assertIn(':neoforge:runLocalGameClient',command)

    def test_forge_uses_gradle_9_source_set_profiles_and_sanitized_environment(self):
        for before, after in ((":forge:runLocalGameServer", ":forge:runLocalGameFixtureServer"),
                              (":forge:runLocalGameClient", ":forge:runLocalGameFixtureClient"),
                              (":forge:runIntegratedHost", ":forge:runIntegratedFixtureClient")):
            with patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), \
                 patch('local_game.game_environment', return_value={}):
                args = gradle_command(self.root, [before], True, True, 'forge', integrated=True)
                self.assertEqual(args[:3], ['java21', '-jar', 'forge9-wrapper'])
                self.assertIn(after, args)
                self.assertFalse(any(flag in args for flag in ('-PforgeAdapter', '-PneoforgeAdapter', '-PfabricAdapter')))
                self.assertIn('-PintegratedGameFixture', args)
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict('os.environ', {'P2WLAN_FORGE_ASSETS': temporary}), \
                 patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), \
                 patch('local_game.game_environment', return_value={}):
                args = gradle_command(self.root, [':forge:localGameClientFixtureClasses'], False, False, 'forge')
                self.assertIn('-PforgeAssetDirectory=' + temporary, args)
                self.assertIn(':forge:localGameFixtureClasses', args)
                self.assertNotIn(':forge:localGameClientFixtureClasses', args)
        build = (PROJECT / 'java/adapters/forge-1.21.1/build.gradle').read_text()
        self.assertIn("net.minecraftforge.launcher.p2wlan.P2wlanAssetsLauncher", build)
        client_launch = build.split("if (name.endsWith('Client')) {", 1)[1]
        self.assertIn("if (System.getProperty('os.name').startsWith('Mac')) jvmArgs '-XstartOnFirstThread'", client_launch)
        self.assertNotIn('GLFW_CHECK_THREAD0', build)
        self.assertIn("--mixin.config=p2wlan.forge.mixins.json", build)
        self.assertIn("--mixin.config=p2wlan.forge.client.mixins.json", build)
        self.assertNotIn('failOnNoDiscoveredTests', build)
        self.assertIn("sourceSets.create('resourcePolicy')", build)
        self.assertEqual(java_directory('forge'), PROJECT / 'java/forge')
        self.assertEqual(java_directory('fabric'), PROJECT / 'java')
        variables = {'P2WLAN_FORGE_GRADLE_ZIP': '/fixture/gradle.zip', 'P2WLAN_FORGE_JAVA25_HOME': '/fixture/jdk25', 'P2WLAN_FORGE_JAVA8_HOME': '/fixture/jdk8',
                     'P2WLAN_TOKEN': 'excluded', 'CONTROL_ADMIN_TOKEN': 'excluded', 'JAVA_TOOL_OPTIONS': 'excluded'}
        with patch.dict('os.environ', variables, clear=True), \
             patch('local_game.subprocess.check_output', return_value='/fixture/jdk21'):
            env = game_environment('forge')
            self.assertEqual(env['P2WLAN_FORGE_GRADLE_ZIP'], '/fixture/gradle.zip')
            self.assertEqual(env['P2WLAN_FORGE_JAVA25_HOME'], '/fixture/jdk25')
            for name in ('P2WLAN_TOKEN', 'CONTROL_ADMIN_TOKEN', 'JAVA_TOOL_OPTIONS'):
                self.assertNotIn(name, env)
            self.assertNotIn('P2WLAN_FORGE_GRADLE_ZIP', game_environment('fabric'))

    def installed_source(self):
        archive = self.root / 'build/forge/libs/p2wlan-forge-1.21.1-0.1.0.jar'
        archive.parent.mkdir(parents=True)
        archive.write_bytes(b'isolated synthetic archive bytes')
        for role in CASES:
            (self.root / role).mkdir()
        return archive

    def test_installed_archive_hash_requires_bounded_regular_bytes(self):
        payload = b'fixture'
        self.path.write_bytes(payload)
        self.assertEqual(archive_sha256(self.path), hashlib.sha256(payload).hexdigest())
        link = self.root / 'linked.jar'
        link.symlink_to(self.path)
        for path in (self.root / 'missing.jar', self.root, link):
            with self.subTest(path=path.name), self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_INVALID$'):
                archive_sha256(path)
        self.path.write_bytes(b'')
        with self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_INVALID$'):
            archive_sha256(self.path)
        self.path.write_bytes(b'123456789')
        with patch('local_game.MAX_INSTALLED_ARCHIVE', 8), self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_INVALID$'):
            archive_sha256(self.path)
        # A file that grows after its stat still cannot exceed the read budget.
        self.path.write_bytes(b'12345678')
        with patch('local_game.MAX_INSTALLED_ARCHIVE', 8), patch('local_game.Path.open', return_value=io.BytesIO(b'123456789')), \
                self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_INVALID$'):
            archive_sha256(self.path)

    def test_installed_archive_copies_are_private_and_create_only(self):
        archive = self.installed_source()
        payload = archive.read_bytes()
        expected = hashlib.sha256(payload).hexdigest()
        self.assertEqual(install_archive(self.root), expected)
        for role in CASES:
            mods = self.root / role / 'mods'
            target = mods / 'p2wlan.jar'
            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual(mods.stat().st_mode & 0o777, 0o700)
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        with self.assertRaises(FileExistsError):
            install_archive(self.root)
        for role in CASES:
            self.assertEqual((self.root / role / 'mods/p2wlan.jar').read_bytes(), payload)

    def test_install_detects_copy_mutation_and_late_source_replacement(self):
        for mutation in ('copy', 'source'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                job = Path(temporary)
                archive = job / 'build/forge/libs/p2wlan-forge-1.21.1-0.1.0.jar'
                archive.parent.mkdir(parents=True)
                archive.write_bytes(b'original fixture bytes')
                for role in CASES:
                    (job / role).mkdir()
                def hash_at_boundary(path):
                    if mutation == 'copy' and path == job / 'server/mods/p2wlan.jar':
                        path.write_bytes(b'changed copy bytes')
                    value = archive_sha256(path)
                    if mutation == 'source' and path == job / 'client/mods/p2wlan.jar':
                        # Both copied archives are unchanged; the selected
                        # source is replaced before final installation commit.
                        archive.write_bytes(b'changed source bytes')
                    return value
                with patch('local_game.archive_sha256', side_effect=hash_at_boundary), \
                        self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_CHANGED$'):
                    install_archive(job)

    def test_installed_run_tasks_bind_captured_digest_and_exact_role_copy(self):
        archive = self.installed_source()
        captured = install_archive(self.root)
        archive.write_bytes(b'changed build source after installation')
        for task, role, integrated in ((':forge:runLocalGameServer', 'server', False),
                                       (':forge:runLocalGameClient', 'client', False),
                                       (':forge:runIntegratedHost', 'server', True),
                                       (':forge:runLocalGameClient', 'client', True)):
            with self.subTest(task=task, integrated=integrated), patch.dict('os.environ', {}, clear=True), \
                    patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), \
                    patch('local_game.game_environment', return_value={}):
                command = gradle_command(self.root, [task], True, True, 'forge', integrated, installed=True, archive_sha=captured)
            self.assertIn('-PinstalledGameFixture', command)
            self.assertIn('-PlocalGameInstalledArchive=' + str(self.root / role / 'mods/p2wlan.jar'), command)
            self.assertIn('-PlocalGameInstalledSha256=' + captured, command)
            self.assertEqual('-PintegratedGameFixture' in command, integrated)
        for role, task in (('server', ':forge:runLocalGameServer'), ('client', ':forge:runLocalGameClient')):
            target = self.root / role / 'mods/p2wlan.jar'
            target.write_bytes(b'changed installed copy')
            with self.subTest(role=role), patch.dict('os.environ', {}, clear=True), \
                    patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), \
                    patch('local_game.game_environment', return_value={}), \
                    self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_CHANGED$'):
                gradle_command(self.root, [task], True, True, 'forge', installed=True, archive_sha=captured)

    def test_installed_run_requires_captured_digest_but_class_build_does_not(self):
        for task in (':forge:runLocalGameServer', ':forge:runLocalGameClient', ':forge:runIntegratedHost',
                     ':forge:runLocalGameFixtureServer', ':forge:runLocalGameFixtureClient', ':forge:runIntegratedFixtureClient'):
            with self.subTest(task=task), patch.dict('os.environ', {}, clear=True), \
                    patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), \
                    patch('local_game.game_environment', return_value={}), \
                    self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_[A-Z_]+$'):
                gradle_command(self.root, [task], True, True, 'forge', installed=True)
        with patch.dict('os.environ', {}, clear=True), \
                patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), \
                patch('local_game.game_environment', return_value={}):
            command = gradle_command(self.root, [':forge:localGameFixtureClasses'], True, True, 'forge', installed=True)
        self.assertIn(':forge:localGameFixtureClasses', command)
        self.assertFalse(any(value.startswith('-PlocalGameInstalledSha256=') for value in command))
        for loader in ('fabric', 'neoforge'):
            with self.subTest(loader=loader), self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_LOADER$'):
                gradle_command(self.root, [f':{loader}:runLocalGameClient'], True, True, loader, installed=True)

    def test_external_same_archive_runs_each_loader_with_exact_role_copy(self):
        archive = self.installed_source()
        captured = install_archive(self.root, archive, archive.read_bytes())
        for loader in ('fabric', 'neoforge', 'forge'):
            for role, task in (('server', 'runLocalGameServer'), ('client', 'runLocalGameClient'), ('server', 'runIntegratedHost')):
                with self.subTest(loader=loader, task=task), patch.dict('os.environ', {}, clear=True), \
                        patch('forge_gradle.command', return_value=['java21', '-jar', 'forge9-wrapper']), patch('local_game.game_environment', return_value={}):
                    command = gradle_command(self.root, [f':{loader}:{task}'], True, True, loader,
                                             integrated=task == 'runIntegratedHost', archive_sha=captured, installed_mod=archive)
                target = self.root / role / 'mods/p2wlan.jar'
                self.assertIn('-PinstalledModArchive=' + str(target), command)
                self.assertIn('-PlocalGameInstalledArchive=' + str(target), command)
                self.assertIn('-PlocalGameInstalledSha256=' + captured, command)
        for loader in ('fabric', 'neoforge', 'forge'):
            with self.subTest(loader=loader), patch('forge_gradle.command', return_value=['java21']), \
                    patch('local_game.game_environment', return_value={}), self.assertRaisesRegex(ValueError, 'GAME_INSTALLED_ARCHIVE_CHANGED'):
                gradle_command(self.root, [f':{loader}:runLocalGameClient'], False, False, loader, installed_mod=archive)

    def test_external_verifier_receives_the_exact_bounded_snapshot(self):
        self.path.write_bytes(b'wrapper contract snapshot')
        expected = hashlib.sha256(self.path.read_bytes()).hexdigest()
        with patch('universal_jar.verify', return_value={'artifact_sha256': expected, 'minecraft': ['1.20.1', '1.21.1']}) as verify:
            self.assertEqual(external_archive(self.path, True), self.path.read_bytes())
            self.assertEqual(verify.call_args.args[0].getvalue(), self.path.read_bytes())
            self.assertTrue(verify.call_args.args[1])
            self.assertEqual(external_archive(self.path, True, '1.20.1'), self.path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'GAME_INSTALLED_ARCHIVE_INVALID'):
            external_archive(Path('relative.jar'), True)
        with patch('universal_jar.verify', side_effect=ValueError('private exception payload')), \
                self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_VERIFICATION$'):
            external_archive(self.path, True)
        with patch('universal_jar.verify', return_value={'artifact_sha256': '0' * 64}), \
                self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_CHANGED$'):
            external_archive(self.path, True)

    def test_external_archive_must_include_the_selected_game_version(self):
        self.path.write_bytes(b'version-bound archive snapshot')
        expected = hashlib.sha256(self.path.read_bytes()).hexdigest()
        for versions in (['1.21.1'], '1.20.1', None):
            with self.subTest(versions=versions), \
                    patch('universal_jar.verify', return_value={'artifact_sha256': expected, 'minecraft': versions}), \
                    self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_VERSION$'):
                external_archive(self.path, True, '1.20.1')

    def test_old_profiles_use_installed_archive_java17_and_their_exact_role_copy(self):
        archive = self.installed_source()
        captured = install_archive(self.root, archive, archive.read_bytes())
        for loader in ('fabric', 'forge', 'neoforge'):
            project = 'fabric-legacy' if loader == 'fabric' else 'forge'
            self.assertEqual(adapter_project(loader, '1.20.1'), project)
            self.assertEqual(java_directory(loader, '1.20.1'), PROJECT / ('java' if loader == 'fabric' else 'java/forge-legacy'))
            for role, task in (('server', 'runLocalGameServer'), ('client', 'runLocalGameClient'), ('server', 'runIntegratedHost')):
                with self.subTest(loader=loader, task=task), patch('legacy_gradle.command', return_value=['java17', 'gradle88']), \
                        patch('local_game.game_environment', return_value={}):
                    args = gradle_command(self.root, [f':{loader}:{task}'], True, True, loader,
                                          integrated=task == 'runIntegratedHost', installed_mod=archive, archive_sha=captured, minecraft='1.20.1')
                self.assertIn('-PinstalledModArchive=' + str(self.root / role / 'mods/p2wlan.jar'), args)
                self.assertIn('-PlocalGameInstalledSha256=' + captured, args)
                self.assertNotIn('-PlegacyNeoForge', args)
                self.assertEqual('-PfabricLegacyAdapter' in args, loader == 'fabric')
                self.assertFalse(any(value.startswith(f':{loader}:') for value in args) if project != loader else False)
            with patch.dict('os.environ', {'P2WLAN_LEGACY_JAVA_HOME': '/fixture/jdk17', 'P2WLAN_TOKEN': 'excluded'}, clear=True), \
                    patch('local_game.sys.platform', 'darwin'), patch('local_game.subprocess.check_output', return_value='/fixture/jdk') as home:
                env = game_environment(loader, '1.20.1')
            self.assertEqual(home.call_args.args[0][-1], '21' if loader == 'fabric' else '17')
            self.assertEqual(env['P2WLAN_LEGACY_JAVA_HOME'], '/fixture/jdk17')
            self.assertNotIn('P2WLAN_TOKEN', env)
        (self.root / 'client/mods/p2wlan.jar').write_bytes(b'changed role copy')
        with patch('legacy_gradle.command', return_value=['java17']), patch('local_game.game_environment', return_value={}), \
                self.assertRaisesRegex(ValueError, '^GAME_INSTALLED_ARCHIVE_CHANGED$'):
            gradle_command(self.root, [':forge:runLocalGameClient'], True, True, 'forge', installed_mod=archive, archive_sha=captured, minecraft='1.20.1')

    def test_old_source_mode_is_rejected_before_building_or_creating_a_job(self):
        for loader in ('fabric', 'forge', 'neoforge'):
            with self.subTest(loader=loader), patch('local_game.subprocess.run') as run, \
                    self.assertRaisesRegex(ValueError, '^GAME_LEGACY_REQUIRES_UNIVERSAL_ARCHIVE$'):
                prepare(self.root, {}, True, True, loader, minecraft='1.20.1')
            run.assert_not_called()
        with patch('sys.argv', ['local_game.py', '--minecraft', '1.20.1', '--accept-eula', '--offline-mode']), \
                patch('sys.stderr', new=io.StringIO()), patch('local_game.game_environment') as env, self.assertRaises(SystemExit):
            main()
        env.assert_not_called()

    def test_game_evidence_version_and_integrated_counts_remain_exact(self):
        for role in CASES:
            for integrated in (False, True):
                value = self.result(role) | {'minecraft': '1.20.1'}
                if integrated and role == 'server':
                    value |= {'checks': 80, 'cases': sorted(INTEGRATED_SERVER_CASES)}
                self.path.write_text(json.dumps(value))
                self.assertEqual(verify_result(self.path, self.run, role, integrated, '1.20.1'), value)
                with self.assertRaises(ValueError):
                    verify_result(self.path, self.run, role, integrated)
        with self.assertRaisesRegex(ValueError, '^GAME_PROFILE_UNSUPPORTED$'):
            verify_result(self.path, self.run, 'server', minecraft='1.20.2')

    def test_external_prepare_uses_installed_native_and_never_builds_local_bundle(self):
        from types import SimpleNamespace
        archive = self.installed_source()
        child = SimpleNamespace(process=SimpleNamespace(wait=lambda timeout: 0), close=lambda: None)
        with patch('local_game.shutil.which', return_value='/fixture/go'), \
                patch('local_game.external_archive', return_value=archive.read_bytes()), \
                patch('local_game.prepare_seed_binary') as seed, patch('local_game.subprocess.run') as run, \
                patch('local_game.FixtureProcess', return_value=child) as build:
            captured = prepare(self.root, {}, True, True, 'fabric', True, installed_mod=archive)
        self.assertEqual(captured, hashlib.sha256(archive.read_bytes()).hexdigest())
        seed.assert_called_once_with(self.root, archive.read_bytes())
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0][0], 'go')
        self.assertIn(':fabric:relocateLocalGameClient', build.call_args.args[0])
        self.assertIn(':fabric:relocateIntegratedHost', build.call_args.args[0])

    def test_old_fabric_prepare_builds_only_old_fixture_tasks_and_binds_selected_version(self):
        from types import SimpleNamespace
        archive = self.installed_source()
        child = SimpleNamespace(process=SimpleNamespace(wait=lambda timeout: 0), close=lambda: None)
        with patch('local_game.shutil.which', return_value='/fixture/go'), \
                patch('local_game.external_archive', return_value=archive.read_bytes()) as verify, \
                patch('local_game.prepare_seed_binary'), patch('local_game.subprocess.run') as run, \
                patch('local_game.FixtureProcess', return_value=child) as build:
            prepare(self.root, {}, True, True, 'fabric', True, installed_mod=archive, minecraft='1.20.1')
        verify.assert_called_once_with(archive, True, '1.20.1', 'fabric')
        self.assertEqual(run.call_count, 1)
        tasks = {value for value in build.call_args.args[0] if value.startswith(':')}
        self.assertEqual(tasks, {':fabric-legacy:verifyModJar', ':fabric-legacy:localGameFixtureClasses',
                                 ':fabric-legacy:localGameClientFixtureClasses', ':fabric-legacy:integratedFixtureClasses',
                                 ':fabric-legacy:relocateLocalGameServer', ':fabric-legacy:relocateLocalGameClient', ':fabric-legacy:relocateIntegratedHost'})
        self.assertEqual(build.call_args.args[1], PROJECT / 'java')

    def test_old_fml_prepare_builds_srg_fixtures_then_prepares_the_selected_production_sdk(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        for loader in ('forge', 'neoforge'):
            with tempfile.TemporaryDirectory() as directory:
                job = Path(directory)
                archive = job / 'universal.jar'; archive.write_bytes(b'bound archive snapshot')
                for role in CASES: (job / role).mkdir()
                stages = []
                child = SimpleNamespace(process=SimpleNamespace(wait=lambda timeout: 0), close=lambda: stages.append('build_closed'))
                sdk_prepare = Mock(side_effect=lambda *args: stages.append('sdk'))
                with patch('local_game.shutil.which', return_value='/fixture/go'), \
                        patch('local_game.external_archive', return_value=archive.read_bytes()), \
                        patch('local_game.prepare_seed_binary'), patch('local_game.subprocess.run'), \
                        patch('legacy_gradle.command', return_value=['java17', 'gradle88']), \
                        patch('local_game.game_environment', return_value={}), \
                        patch('local_game.FixtureProcess', return_value=child) as build, \
                        patch.dict('sys.modules', {'legacy_installation': SimpleNamespace(prepare=sdk_prepare)}):
                    prepare(job, {}, True, True, loader, True, installed_mod=archive, minecraft='1.20.1')
                tasks = {value for value in build.call_args.args[0] if value.startswith(':')}
                self.assertEqual(tasks, {':forge:verifyModJar', ':forge:reobfInstalledLocalGameFixture', ':forge:reobfInstalledIntegratedFixture'})
                self.assertEqual(build.call_args.args[1], PROJECT / 'java/forge-legacy')
                sdk_prepare.assert_called_once_with(job, loader, {}, True)
                self.assertEqual(stages, ['build_closed', 'sdk'])

    def test_old_fml_games_launch_only_the_production_java_command_in_the_exact_role(self):
        from unittest.mock import Mock
        sdk_command = Mock(return_value=['checked-java17', 'production-bootstrap'])
        from types import SimpleNamespace
        for loader in ('forge', 'neoforge'):
            for role in CASES:
                for integrated in (False, True):
                    with self.subTest(loader=loader, role=role, integrated=integrated), \
                            patch.dict('sys.modules', {'legacy_installation': SimpleNamespace(command=sdk_command)}), \
                            patch('local_game.gradle_command') as gradle, patch('local_game.FixtureProcess') as launch:
                        launch_game(self.root, role, {}, True, True, loader, integrated, False, 'a' * 64, self.path, '1.20.1')
                    gradle.assert_not_called()
                    sdk_command.assert_called_with(self.root, loader, role, integrated, True, 'a' * 64, {})
                    self.assertEqual(launch.call_args.args, (['checked-java17', 'production-bootstrap'], self.root / role, {}, self.root / (role + '.log')))

    def test_1182_exact_pairs_use_installed_archives_sdk_paths_and_complete_assertions(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        self.assertEqual(adapter_project('fabric', '1.18.2'), 'fabric-1182')
        self.assertEqual(adapter_project('forge', '1.18.2'), 'forge')
        self.assertEqual(java_directory('forge', '1.18.2'), PROJECT / 'java/forge-1182')
        self.assertEqual(java_directory('fabric', '1.18.2'), PROJECT / 'java')
        with self.assertRaisesRegex(ValueError, 'GAME_PROFILE_UNSUPPORTED'):
            adapter_project('neoforge', '1.18.2')
        for loader in ('fabric', 'forge'):
            with self.subTest(loader=loader), self.assertRaisesRegex(ValueError, 'GAME_LEGACY_REQUIRES_UNIVERSAL_ARCHIVE'):
                gradle_command(self.root, [f':{loader}:runLocalGameClient'], True, True, loader, minecraft='1.18.2')
            with self.subTest(loader=loader), patch('local_game.subprocess.run') as run, self.assertRaisesRegex(ValueError, 'GAME_LEGACY_REQUIRES_UNIVERSAL_ARCHIVE'):
                prepare(self.root, {}, True, True, loader, minecraft='1.18.2')
            run.assert_not_called()
        for role in CASES:
            for integrated in (False, True):
                value = self.result(role) | {'minecraft': '1.18.2'}
                if integrated and role == 'server':
                    value |= {'checks': 80, 'cases': sorted(INTEGRATED_SERVER_CASES)}
                self.path.write_text(json.dumps(value))
                self.assertEqual(verify_result(self.path, self.run, role, integrated, '1.18.2'), value)
                self.path.write_text(json.dumps(value | {'checks': value['checks'] - 1}))
                with self.assertRaises(ValueError):
                    verify_result(self.path, self.run, role, integrated, '1.18.2')
        sdk_command = Mock(return_value=['java17', 'production18'])
        with patch.dict('sys.modules', {'legacy_installation': SimpleNamespace(command=sdk_command)}), \
                patch('local_game.gradle_command') as gradle, patch('local_game.FixtureProcess'):
            launch_game(self.root, 'client', {}, True, True, 'forge', True, False, 'a' * 64, self.path, '1.18.2')
        gradle.assert_not_called()
        sdk_command.assert_called_once_with(self.root, 'forge', 'client', True, True, 'a' * 64, {}, minecraft='1.18.2')

    def test_1182_installed_archive_requires_the_exact_loader_pin_as_well_as_game_version(self):
        payload = b'checked universal snapshot'
        self.path.write_bytes(payload)
        info = {'artifact_sha256': hashlib.sha256(payload).hexdigest(), 'minecraft': ['1.18.2'],
                'loaders': {'1.18.2': {'fabric': '0.16.14', 'forge': '40.3.12'}}}
        with patch('universal_jar.verify', return_value=info):
            for loader in ('fabric', 'forge'):
                self.assertEqual(external_archive(self.path, True, '1.18.2', loader), payload)
            with self.assertRaisesRegex(ValueError, 'GAME_PROFILE_UNSUPPORTED'):
                external_archive(self.path, True, '1.18.2', 'neoforge')
        for info in [info | {'minecraft': ['1.20.1']}, info | {'loaders': {}},
                     info | {'loaders': {'1.18.2': {'forge': '40.3.11'}}}]:
            with patch('universal_jar.verify', return_value=info), self.assertRaisesRegex(ValueError, 'GAME_INSTALLED_ARCHIVE_(VERSION|LOADER)'):
                external_archive(self.path, True, '1.18.2', 'forge')

    def test_1182_prepare_is_installed_and_routes_each_sdk_without_other_adapter_fallbacks(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        archive = self.installed_source()
        child = SimpleNamespace(process=SimpleNamespace(wait=lambda timeout: 0), close=lambda: None)
        for loader in ('fabric', 'forge'):
            # The private copies are newly created for every fixture run.
            for role in CASES:
                target = self.root / role / 'mods'
                if target.exists():
                    import shutil
                    shutil.rmtree(target)
            sdk_prepare = Mock()
            with patch('local_game.shutil.which', return_value='/fixture/go'), \
                    patch('local_game.external_archive', return_value=archive.read_bytes()) as verify, \
                    patch('local_game.prepare_seed_binary'), patch('local_game.subprocess.run'), \
                    patch('forge_1182_gradle.command', return_value=['java17', 'gradle88']), \
                    patch('local_game.game_environment', return_value={}), patch('local_game.FixtureProcess', return_value=child) as build, \
                    patch.dict('sys.modules', {'legacy_installation': SimpleNamespace(prepare=sdk_prepare)}):
                prepare(self.root, {}, True, True, loader, True, installed_mod=archive, minecraft='1.18.2')
            verify.assert_called_once_with(archive, True, '1.18.2', loader)
            tasks = {value for value in build.call_args.args[0] if value.startswith(':')}
            if loader == 'forge':
                self.assertEqual(tasks, {':forge:verifyModJar', ':forge:reobfInstalledLocalGameFixture', ':forge:reobfInstalledIntegratedFixture'})
                sdk_prepare.assert_called_once_with(self.root, 'forge', {}, True, minecraft='1.18.2')
                self.assertEqual(build.call_args.args[1], PROJECT / 'java/forge-1182')
            else:
                self.assertIn('-Pfabric1182Adapter', build.call_args.args[0])
                self.assertTrue(all(task.startswith(':fabric-1182:') for task in tasks))
                self.assertIn(':fabric-1182:relocateLocalGameClient', tasks)
                sdk_prepare.assert_not_called()

    def test_seed_image_comes_only_from_verified_native_child_bytes(self):
        def packaged(binary, checksum=None):
            child = io.BytesIO()
            with zipfile.ZipFile(child, 'w') as archive:
                archive.writestr('p2wlan/native/macos-aarch64/manifest.properties',
                                 'executable-size=' + str(len(binary)) + '\nexecutable-sha256=' + (checksum or hashlib.sha256(binary).hexdigest()) + '\n')
                archive.writestr('p2wlan/native/macos-aarch64/p2wlan-mc-agent', binary)
            outer = io.BytesIO()
            with zipfile.ZipFile(outer, 'w') as archive:
                archive.writestr('META-INF/jarjar/native.jar', child.getvalue())
            return outer.getvalue()
        with patch('local_game.host_platform', return_value=('macos', 'aarch64')):
            binary = prepare_seed_binary(self.root, packaged(b'fixture executable bytes'))
            self.assertEqual(binary.read_bytes(), b'fixture executable bytes')
            self.assertEqual(binary.stat().st_mode & 0o777, 0o500)
            with self.assertRaisesRegex(ValueError, 'GAME_INSTALLED_NATIVE_CHANGED'):
                prepare_seed_binary(self.root, packaged(b'changed executable', '0' * 64))

    def test_failed_game_process_is_not_masked_by_its_live_peer_deadline(self):
        from types import SimpleNamespace
        failed = SimpleNamespace(process=SimpleNamespace(poll=lambda: 1))
        live = SimpleNamespace(process=SimpleNamespace(poll=lambda: None))
        with self.assertRaisesRegex(ValueError, 'GAME_PROCESS_EXIT'), patch('local_game.time.sleep') as sleep:
            wait_game([failed, live], self.root, self.run)
        sleep.assert_not_called()

    def test_inflight_results_reject_malformed_or_foreign_evidence_with_fixed_codes(self):
        from types import SimpleNamespace
        live = SimpleNamespace(process=SimpleNamespace(poll=lambda: None))
        private = "fixture-private@example.com /private/profile credential-value"
        for role in CASES:
            path = self.root / (role + "-result.json")
            failed = self.result(role) | {"status": "fail", "reason": "PLAY_DEADLINE_PLAY", "checks": 0, "rounds": 0}
            values = [None, [], 1, private, {}, failed | {"schema": True}, failed | {"schema": 2},
                      failed | {"run": "b" * 32, "reason": private}, failed | {"role": "other", "reason": private},
                      failed | {"status": private}, failed | {"reason": private}, failed | {"reason": None},
                      failed | {"reason": "FAIL\nPRIVATE"}, failed | {"reason": "A" * 118}, failed | {"extra": private}]
            payloads = [json.dumps(value) for value in values] + ["{", '{"schema":1,"schema":1}']
            for payload in payloads:
                with self.subTest(role=role, payload=payload):
                    path.write_text(payload)
                    with patch('local_game.time.sleep') as sleep, self.assertRaises(ValueError) as failure:
                        wait_game([live], self.root, self.run)
                    self.assertEqual(str(failure.exception), "GAME_RESULT_CONTRACT_" + role.upper())
                    self.assertNotIn(private, str(failure.exception))
                    sleep.assert_not_called()
            path.unlink()

    def test_inflight_failure_reports_only_current_bounded_fixture_reason(self):
        from types import SimpleNamespace
        live = SimpleNamespace(process=SimpleNamespace(poll=lambda: None))
        for reason in ("NOT_COMPLETED", "PLAY_DEADLINE_PLAY", "DISPLAY_SETTINGS_" + "A" * 100):
            failed = self.result("client") | {"status": "fail", "reason": reason, "checks": 0, "rounds": 0,
                                             "helper_cleanup": False, "cases": []}
            (self.root / "client-result.json").write_text(json.dumps(failed))
            with self.subTest(reason=reason), patch('local_game.time.sleep') as sleep, self.assertRaises(ValueError) as failure:
                wait_game([live], self.root, self.run)
            self.assertEqual(str(failure.exception), "GAME_FAILED_" + reason)
            sleep.assert_not_called()

    def test_both_exact_run_bound_gameplay_and_cleanup_results_required(self):
        for role in CASES:
            value = self.result(role)
            self.path.write_text(json.dumps(value))
            self.assertEqual(verify_result(self.path, self.run, role), value)
            for change in ({"schema": True}, {"run": "b" * 32}, {"role": "other"}, {"status": "fail"}, {"reason": "DEADLINE_JOIN"},
                           {"checks": 0}, {"checks": True}, {"checks": CHECKS[role] - 1}, {"minecraft": "1.21"},
                           {"helper_cleanup": False}, {"world_cleanup": 1}, {"joins": 0}, {"joins": True}, {"rounds": 19},
                           {"cases": []}, {"cases": [None]}, {"cases": ["world_host"] * len(CASES[role])}, {"extra": "secret"}):
                with self.subTest(role=role, change=change), self.assertRaises(ValueError):
                    self.path.write_text(json.dumps(value | change))
                    verify_result(self.path, self.run, role)

    def test_integrated_requires_exact_host_pause_and_cleanup_evidence(self):
        host = self.result("server") | {"checks": 80, "cases": sorted(INTEGRATED_SERVER_CASES)}
        self.path.write_text(json.dumps(host))
        self.assertEqual(verify_result(self.path, self.run, "server", integrated=True), host)
        with self.assertRaises(ValueError):
            verify_result(self.path, self.run, "server")
        for change in ({"checks": 76}, {"checks": 78}, {"checks": True}, {"cases": sorted(CASES["server"])},
                       {"cases": sorted(INTEGRATED_SERVER_CASES - {"unhost_pause"})}, {"world_cleanup": False}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.path.write_text(json.dumps(host | change))
                verify_result(self.path, self.run, "server", integrated=True)
        # An integrated host must not change the guest's existing assertions.
        guest = self.result("client")
        self.path.write_text(json.dumps(guest))
        self.assertEqual(verify_result(self.path, self.run, "client", integrated=True), guest)
        self.path.write_text(json.dumps(self.result("server")))
        with self.assertRaises(ValueError):
            verify_result(self.path, self.run, "server", integrated=True)

    def test_integrated_run_and_common_fixture_data_remain_loader_isolated(self):
        for loader in ("fabric", "neoforge"):
            with self.subTest(loader=loader):
                command = gradle_command(self.root, [f":{loader}:runIntegratedHost"], True, True, loader, integrated=True)
                self.assertIn("-PintegratedGameFixture", command)
                self.assertIn("-PlocalGameFixture", command)
                self.assertIn(f"-P{loader}Adapter", command)
                ordinary = gradle_command(self.root, [f":{loader}:runLocalGameServer"], False, False, loader)
                self.assertNotIn("-PintegratedGameFixture", ordinary)
                build = (PROJECT / f"java/adapters/{loader}-1.21.1/build.gradle").read_text()
                self.assertIn("INTEGRATED_FIXTURE_REQUIRES_LOCAL_GAME_FIXTURE", build)
                self.assertEqual(build.count("game-1.21.1/src/fixtureCommon/java"), 2)
        data = list((PROJECT / "java/game-1.21.1/src").rglob("GameFixtureData.java"))
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0].relative_to(PROJECT).parts[3], "fixtureCommon")

    def test_missing_oversized_duplicate_and_symlink_evidence_rejected(self):
        with self.assertRaises(ValueError):
            bounded_json(self.path)
        self.path.write_text(" " * 8193)
        with self.assertRaises(ValueError):
            bounded_json(self.path)
        self.path.write_text('{"schema":1,"schema":1}')
        with self.assertRaises(ValueError):
            bounded_json(self.path)
        link = self.root / "link.json"
        link.symlink_to(self.path)
        with self.assertRaises(ValueError):
            bounded_json(link)

    def test_readiness_is_structured_and_does_not_mean_gameplay_pass(self):
        value = {"schema": 1, "run": self.run, "peer": "fixture-peer"}
        self.path.write_text(json.dumps(value))
        self.assertEqual(verify_ready(self.path, self.run), value)
        with self.assertRaises(ValueError):
            verify_result(self.path, self.run, "server")
        for change in ({"schema": True}, {"run": "b" * 32}, {"peer": ""}, {"peer": "x" * 129}, {"peer": None}, {"extra": True}):
            self.path.write_text(json.dumps(value | change))
            with self.assertRaises(ValueError):
                verify_ready(self.path, self.run)

    def test_consent_and_offline_auth_are_explicit_before_any_build_or_config(self):
        for args in ([], ["--accept-eula"], ["--offline-mode"]):
            with patch("sys.argv", ["local_game.py", *args]), patch("sys.stderr", new=io.StringIO()), \
                    patch("local_game.prepare") as prepare, self.assertRaises(SystemExit) as failure:
                main()
            self.assertEqual(failure.exception.code, 2)
            prepare.assert_not_called()

    def test_cleanup_failure_preserves_only_a_fixed_original_failure_code(self):
        for original, reported in (("GAME_PROCESS_DEADLINE", True), ("private exception payload", False)):
            error = ValueError("GAME_PROCESS_CLEANUP")
            error.__context__ = ValueError(original)
            output, diagnostics = io.StringIO(), io.StringIO()
            with self.subTest(original=original), patch("local_game.PROJECT", self.root), patch("sys.platform", "linux"), \
                    patch("sys.argv", ["local_game.py", "--integrated", "--accept-eula", "--offline-mode"]), \
                    patch("local_game.environment", return_value={}), patch("local_game.prepare"), \
                    patch("local_game.exercise", side_effect=error), patch("sys.stdout", new=output), patch("sys.stderr", new=diagnostics):
                self.assertEqual(main(), 1)
            self.assertIn("FAIL local Minecraft: GAME_PROCESS_CLEANUP", diagnostics.getvalue())
            self.assertEqual("Original execution failure:" in diagnostics.getvalue(), reported)
            self.assertEqual(original in diagnostics.getvalue(), reported)

    def test_test_server_is_loopback_offline_and_contains_a_real_flat_world(self):
        settings = dict(line.split("=", 1) for line in server_settings(25565).splitlines())
        self.assertEqual(settings["server-ip"], "127.0.0.1")
        self.assertEqual(settings["online-mode"], "false")
        self.assertEqual(settings["enable-rcon"], "false")
        self.assertEqual(settings["enable-query"], "false")
        self.assertEqual(settings["server-port"], "25565")
        self.assertEqual(json.loads(settings["generator-settings"])["layers"][-1]["block"], "minecraft:grass_block")
        for value in (0, 65536, True, "25565"):
            with self.assertRaises(ValueError):
                server_settings(value)

    def test_loom_game_directories_are_relative_and_config_paths_absolute(self):
        command = gradle_command(self.root, [":fabric:runLocalGameClient"], True, True)
        self.assertIn("-PlocalGameFixture", command)
        self.assertIn("--offline", command)
        self.assertIn("-PdevelopmentNative", command)
        from local_game import ADAPTER
        for role in CASES:
            key = "-PlocalGame" + role.title() + "Directory="
            directory = Path(next(value[len(key):] for value in command if value.startswith(key)))
            self.assertFalse(directory.is_absolute())
            self.assertEqual((ADAPTER / directory).resolve(), (self.root / role).resolve())
            key = "-PlocalGame" + role.title() + "Config="
            config = Path(next(value[len(key):] for value in command if value.startswith(key)))
            self.assertTrue(config.is_absolute())
            self.assertEqual(config, self.root / (role + ".json"))


if __name__ == "__main__":
    unittest.main()

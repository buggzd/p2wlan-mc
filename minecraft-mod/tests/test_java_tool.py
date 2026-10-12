import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("java_tool", PROJECT / "tools/java.py")
java = importlib.util.module_from_spec(spec)
spec.loader.exec_module(java)


def artifact(name):
    return {"reason": "compiler-artifact", "target": {"name": name}, "executable": "fixture/" + name}


class JavaToolTests(unittest.TestCase):
    def test_universal_route_uses_one_assembly_entry_and_preserves_options(self):
        with patch("sys.argv", ["java.py", "universal-build", "--development", "--offline"]), \
                patch.object(java.subprocess, "call", return_value=0) as run:
            self.assertEqual(java.main(), 0)
            self.assertEqual(run.call_args.args[0], [java.sys.executable, str(PROJECT / "tools/universal_build.py"), "--development", "--offline"])

    def test_actual_cargo_artifacts_are_required_not_guessed_target_paths(self):
        messages = [artifact("frame_probe"), artifact("agent_probe"), artifact("world_guard_probe")]
        self.assertEqual(java.probe_artifacts(messages),
                         {"frame_probe": "fixture/frame_probe", "agent_probe": "fixture/agent_probe", "world_guard_probe": "fixture/world_guard_probe"})
        for items in ([], [artifact("frame_probe")], messages + [artifact("frame_probe")],
                      messages + [artifact("unexpected")]):
            with self.assertRaises(ValueError):
                java.probe_artifacts(items)

    def test_client_run_verifies_artifact_and_forwards_explicit_development_property(self):
        with patch("sys.argv", ["java.py", "fabric-client", "-PdevelopmentNative", "--offline"]), \
                patch.object(java.subprocess, "check_output", return_value="fixture-jdk\n"), \
                patch.object(java.subprocess, "call", return_value=0) as run:
            self.assertEqual(java.main(), 0)
            command = run.call_args.args[0]
            self.assertLess(command.index(":fabric:verifyModJar"), command.index(":fabric:runClient"))
            self.assertIn("-PfabricAdapter", command)
            self.assertIn("-PdevelopmentNative", command)
            self.assertIn("--offline", command)

    def test_neoforge_entries_preserve_loader_and_archive_gates(self):
        for entry, task in [('neoforge-build', ':neoforge:build'), ('neoforge-server', ':neoforge:runServer')]:
            with patch('sys.argv', ['java.py', entry, '-PdevelopmentNative', '--offline']), \
                    patch.object(java.subprocess, 'check_output', return_value='fixture-jdk\n'), \
                    patch.object(java.subprocess, 'call', return_value=0) as run:
                self.assertEqual(java.main(), 0)
                arguments = run.call_args.args[0]
                self.assertIn('-PneoforgeAdapter', arguments)
                self.assertIn(task, arguments)
                self.assertIn('-PdevelopmentNative', arguments)
                self.assertNotIn('-PfabricAdapter', arguments)
                if entry == 'neoforge-server':
                    self.assertLess(arguments.index(':neoforge:verifyModJar'), arguments.index(task))

    def test_hmcl_pack_forwards_an_explicit_target_and_does_not_launch_a_game(self):
        with patch("sys.argv", ["java.py", "hmcl-pack", "-PhmclPlatform=windows-x86_64", "--offline"]), \
                patch.object(java.subprocess, "check_output", return_value="fixture-jdk\n"), \
                patch.object(java.subprocess, "call", return_value=0) as run:
            self.assertEqual(java.main(), 0)
            command = run.call_args.args[0]
            self.assertIn("-PfabricAdapter", command)
            self.assertIn(":fabric:buildHmclPack", command)
            self.assertIn("-PhmclPlatform=windows-x86_64", command)
            self.assertFalse(any("runClient" in item or "runServer" in item for item in command))

    def test_windows_native_harness_is_explicit_and_never_launches_a_game(self):
        with patch("sys.argv", ["java.py", "windows-native-test", "--offline"]), \
                patch.object(java.subprocess, "check_output", return_value="fixture-jdk\n"), \
                patch.object(java.subprocess, "call", return_value=1) as run:
            self.assertEqual(java.main(), 1)
            command = run.call_args.args[0]
            self.assertIn(":common:windowsNativePolicyTest", command)
            self.assertIn(":common:windowsNativeFilesystemTest", command)
            self.assertIn("--offline", command)
            self.assertFalse(any("fabric" in item or "runClient" in item or "runServer" in item for item in command))

    def test_multiloader_hmcl_routes_keep_the_correct_build_and_target(self):
        target = '-PhmclPlatform=windows-x86_64'
        for loader in ('neoforge', 'forge'):
            with self.subTest(loader=loader), patch('sys.argv', ['java.py', loader + '-hmcl-pack', target, '--offline']), \
                    patch.object(java.subprocess, 'check_output', return_value='/fixture/jdk21\n'), \
                    patch.dict('sys.modules', {'forge_gradle': SimpleNamespace(command=lambda env: ['/fixture/java21', '-jar', '/fixture/gradle9.jar'])}), \
                    patch.object(java.subprocess, 'call', return_value=0) as run:
                self.assertEqual(java.main(), 0)
                command = run.call_args.args[0]
                self.assertIn(':' + loader + ':buildHmclPack', command)
                self.assertIn(target, command)
                self.assertIn('--offline', command)
                self.assertFalse(any('runClient' in item or 'runServer' in item for item in command))
                self.assertNotIn('-PfabricAdapter', command)
                if loader == 'forge':
                    self.assertEqual(command[:3], ['/fixture/java21', '-jar', '/fixture/gradle9.jar'])
                    self.assertEqual(run.call_args.kwargs['cwd'], PROJECT / 'java/forge')
                else:
                    self.assertIn('-PneoforgeAdapter', command)
                    self.assertEqual(run.call_args.kwargs['cwd'], PROJECT / 'java')

    def test_account_commands_do_not_configure_or_launch_minecraft(self):
        for command, expected in (("account-test", ":account-cli:accountTest"),
                                  ("account-build", ":account-cli:verifyAccountDistribution")):
            with patch("sys.argv", ["java.py", command, "--offline"]), \
                    patch.object(java.subprocess, "check_output", return_value="fixture-jdk\n"), \
                    patch.object(java.subprocess, "call", return_value=0) as run:
                self.assertEqual(java.main(), 0)
                arguments = run.call_args.args[0]
                self.assertIn(expected, arguments)
                self.assertIn("--offline", arguments)
                self.assertFalse(any("fabric" in item or "Loom" in item for item in arguments))

    def test_shared_netty_harnesses_run_without_configuring_a_loader(self):
        with patch("sys.argv", ["java.py", "test", "--offline"]), \
                patch.object(java.subprocess, "check_output", return_value="fixture-jdk\n"), \
                patch.object(java.subprocess, "call", return_value=0) as run:
            self.assertEqual(java.main(), 0)
            arguments = run.call_args.args[0]
            self.assertIn(":netty-transport:check", arguments)
            self.assertIn(":common:worldSessionsTest", arguments)
            self.assertFalse(any("fabricAdapter" in item or "neoforgeAdapter" in item for item in arguments))

    def test_presentation_regressions_run_with_the_standard_java_test_entry(self):
        with patch("sys.argv", ["java.py", "test", "--offline"]), \
                patch.object(java.subprocess, "check_output", return_value="fixture-jdk\n"), \
                patch.object(java.subprocess, "call", return_value=0) as run:
            self.assertEqual(java.main(), 0)
            self.assertIn(":common:screenPresentationTest", run.call_args.args[0])

    def test_windows_uses_the_tracked_batch_wrapper(self):
        with patch("sys.argv", ["java.py", "test", "--offline"]), \
                patch.object(java.sys, "platform", "win32"), \
                patch.object(java.subprocess, "call", return_value=0) as run:
            self.assertEqual(java.main(), 0)
            self.assertEqual(Path(run.call_args.args[0][0]).name, "gradlew.bat")

    def test_compiler_diagnostics_are_not_lost_while_capturing_json_artifacts(self):
        output = json.dumps({"reason": "compiler-message", "message": {"rendered": "fixture diagnostic\n"}})
        with patch("sys.stderr", new_callable=io.StringIO) as stream:
            messages = java.cargo_messages(output)
            self.assertEqual(stream.getvalue(), "fixture diagnostic\n")
            self.assertEqual(len(messages), 1)


if __name__ == "__main__":
    unittest.main()

import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("java_tool", PROJECT / "tools/java.py")
java = importlib.util.module_from_spec(spec)
spec.loader.exec_module(java)


def artifact(name):
    return {"reason": "compiler-artifact", "target": {"name": name}, "executable": "fixture/" + name}


class JavaToolTests(unittest.TestCase):
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

    def test_compiler_diagnostics_are_not_lost_while_capturing_json_artifacts(self):
        output = json.dumps({"reason": "compiler-message", "message": {"rendered": "fixture diagnostic\n"}})
        with patch("sys.stderr", new_callable=io.StringIO) as stream:
            messages = java.cargo_messages(output)
            self.assertEqual(stream.getvalue(), "fixture diagnostic\n")
            self.assertEqual(len(messages), 1)


if __name__ == "__main__":
    unittest.main()

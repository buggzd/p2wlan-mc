import importlib.util
from pathlib import Path
import sys
import tomllib
import unittest

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
spec = importlib.util.spec_from_file_location("native_tool", PROJECT / "tools/native.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeCommandTests(unittest.TestCase):
    def test_formatter_is_limited_to_mod_workspace_not_path_dependencies(self):
        command = native.build_command("fmt", ["--all", "--", "--check"])
        self.assertNotIn("--all", command)
        manifest = tomllib.loads((PROJECT / "native/Cargo.toml").read_text())
        selected = [command[i + 1] for i, part in enumerate(command) if part == "--package"]
        expected = [tomllib.loads((PROJECT / "native" / member / "Cargo.toml").read_text())["package"]["name"]
                    for member in manifest["workspace"]["members"]]
        self.assertEqual(selected, expected)
        self.assertNotIn("p2wlan-mc-runtime", selected)
        self.assertEqual(command[-2:], ["--", "--check"])

    def test_external_manifest_and_formatter_arguments_are_rejected(self):
        for command in ("fmt", "check", "build", "test", "clippy"):
            for arguments in (["--manifest-path", "Cargo.toml"], ["--manifest-path=Cargo.toml"]):
                with self.subTest(command=command, arguments=arguments):
                    with self.assertRaises(ValueError):
                        native.build_command(command, arguments)
        for arguments in (["--package", "p2pnet-daemon"], ["--config-path", "outside"], ["--verbose"]):
            with self.assertRaises(ValueError):
                native.build_command("fmt", arguments)

    def test_other_commands_always_select_the_independent_manifest(self):
        command = native.build_command("test", ["--workspace", "--offline"])
        self.assertEqual(command, ["cargo", "test", "--manifest-path",
                                  str(PROJECT / "native/Cargo.toml"), "--workspace", "--offline"])


if __name__ == "__main__":
    unittest.main()

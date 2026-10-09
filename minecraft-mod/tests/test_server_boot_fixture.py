import importlib.util
import json
import io
from contextlib import redirect_stdout
from unittest.mock import patch
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("server_boot_fixture", Path(__file__).with_name("server_boot_fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class ServerBootFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.identity = "a" * 32
        self.path = self.root / "result.json"
        self.result = {"schema": 1, "run": self.identity, "status": "pass", "reason": "OK", "checks": 29,
                       "minecraft": "1.21.1", "helper_cleanup": True, "fixture_wrote_eula": False,
                       "cases": sorted(fixture.CASES)}
        self.eula = self.root / "eula.txt"
        self.eula.write_text("# manually accepted fixture terms\neula=true\n")
        self.properties = self.root / "server.properties"
        self.properties.write_text("server-ip=127.0.0.1\nserver-port=25565\nonline-mode=true\nenable-rcon=false\nenable-query=false\nenable-status=true\n")

    def test_manual_eula_and_loopback_online_configuration_required_not_modified(self):
        original = self.eula.read_bytes()
        self.assertEqual(fixture.preflight(self.root), original)
        self.assertEqual(self.eula.read_bytes(), original)
        self.eula.write_text("eula=false\n")
        with self.assertRaisesRegex(ValueError, "manual_eula_required"):
            fixture.preflight(self.root)
        self.assertEqual(self.eula.read_text(), "eula=false\n")

    def test_unsafe_ambiguous_or_missing_settings_fail_before_game_launch(self):
        original = self.properties.read_text()
        for old, new in (("127.0.0.1", "0.0.0.0"), ("online-mode=true", "online-mode=false"),
                         ("enable-rcon=false", "enable-rcon=true"), ("enable-query=false", "enable-query=true"),
                         ("server-port=25565", "server-port=0"), ("enable-status=true", "enable-status=false")):
            self.properties.write_text(original.replace(old, new))
            with self.assertRaisesRegex(ValueError, "isolated_online_config_required"):
                fixture.preflight(self.root)
        self.properties.write_text(original + "server-port=25566\n")
        with self.assertRaisesRegex(ValueError, "config_invalid"):
            fixture.preflight(self.root)
        self.eula.unlink()
        with self.assertRaisesRegex(ValueError, "config_missing"):
            fixture.preflight(self.root)

    def test_exact_nonzero_structured_result_not_logs_or_gradle_exit(self):
        self.path.write_text(json.dumps(self.result))
        self.assertEqual(fixture.verify_result(self.path, self.identity), self.result)
        for field, value in (("schema", True), ("checks", 0), ("checks", True), ("checks", 28),
                             ("run", "b" * 32), ("status", "fail"), ("reason", "FIXTURE_EXECUTION"),
                             ("minecraft", "1.21"), ("helper_cleanup", False), ("fixture_wrote_eula", True),
                             ("cases", []), ("cases", [0]), ("cases", sorted(fixture.CASES) + ["world_bootstrap"])):
            data = self.result | {field: value}
            self.path.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "result_contract"):
                fixture.verify_result(self.path, self.identity)
        self.path.write_text(json.dumps(self.result | {"unexpected": "fixture"}))
        with self.assertRaisesRegex(ValueError, "result_contract"):
            fixture.verify_result(self.path, self.identity)

    def test_duplicate_json_keys_and_symlink_inputs_fail_closed(self):
        self.path.write_text(json.dumps(self.result).replace('"schema": 1', '"schema": 1, "schema": 1'))
        with self.assertRaises(ValueError):
            fixture.verify_result(self.path, self.identity)
        self.path.unlink(); self.path.symlink_to(self.eula)
        with self.assertRaisesRegex(ValueError, "result_missing"):
            fixture.verify_result(self.path, self.identity)
        self.eula.unlink(); self.eula.symlink_to(self.properties)
        with self.assertRaisesRegex(ValueError, "config_missing"):
            fixture.preflight(self.root)

    def test_existing_run_directory_is_resolved_exactly_by_loom(self):
        adapter = self.root / "java/adapters/fabric-1.21.1"
        adapter.mkdir(parents=True)
        observed = []
        with patch.object(fixture, "PROJECT", self.root), \
                patch("sys.argv", ["server_boot_fixture.py", "--run-directory", str(self.root), "--offline", "--development"]), \
                patch.object(fixture.subprocess, "check_output", return_value="fixture-jdk\n"), \
                patch.object(fixture, "run_bounded", side_effect=lambda command, *args: observed.append(command) or 0), \
                patch.object(fixture, "verify_result", return_value={"checks": 29}), redirect_stdout(io.StringIO()):
            self.assertEqual(fixture.main(), 0)
        directory = Path(next(value.split("=", 1)[1] for value in observed[0] if value.startswith("-PserverFixtureDirectory=")))
        self.assertFalse(directory.is_absolute())
        self.assertEqual((adapter / directory).resolve(), self.root.resolve())

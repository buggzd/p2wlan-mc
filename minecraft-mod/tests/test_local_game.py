import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from local_game import CASES, CHECKS, ROUNDS, bounded_json, gradle_command, main, server_settings, verify_ready, verify_result


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

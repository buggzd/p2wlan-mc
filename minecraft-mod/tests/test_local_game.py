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
from local_game import CASES, CHECKS, INTEGRATED_SERVER_CASES, ROUNDS, bounded_json, gradle_command, main, server_settings, verify_ready, verify_result


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

    def test_integrated_requires_exact_host_pause_and_cleanup_evidence(self):
        host = self.result("server") | {"checks": 77, "cases": sorted(INTEGRATED_SERVER_CASES)}
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

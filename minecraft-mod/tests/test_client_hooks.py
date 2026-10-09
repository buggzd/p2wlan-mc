import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
spec = importlib.util.spec_from_file_location("client_hooks", PROJECT / "tools/client_hooks.py")
hooks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hooks)


class ClientHookContractTests(unittest.TestCase):
    def fixture(self):
        return {"schema": 1, "run": "a" * 32, "status": "pass", "reason": "OK", "checks": hooks.CHECKS,
                "minecraft": "1.21.1", "helper_cleanup": True, "eula_accepted": False, "cases": sorted(hooks.CASES)}

    def check(self, content, identity="a" * 32):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.json"
            path.write_text(json.dumps(content), encoding="utf-8")
            return hooks.verify_result(path, identity)

    def test_fresh_complete_result_is_required_not_log_output_or_gradle_exit(self):
        self.assertEqual(self.check(self.fixture())["checks"], 55)
        for change in ({"run": "b" * 32}, {"schema": True}, {"status": "fail"}, {"reason": "FIXTURE_ASSERTION"},
                       {"checks": 0}, {"checks": 54}, {"checks": True}, {"minecraft": "1.21"},
                       {"helper_cleanup": False}, {"helper_cleanup": 1}, {"eula_accepted": True},
                       {"cases": []}, {"cases": ["owned_login"] * len(hooks.CASES)}, {"cases": [None]}, {"extra": True}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.check(self.fixture() | change)

    def test_missing_oversized_duplicate_or_symlink_result_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.json"
            with self.assertRaises(ValueError):
                hooks.verify_result(path, "a" * 32)
            path.write_text(" " * 8193)
            with self.assertRaises(ValueError):
                hooks.verify_result(path, "a" * 32)
            path.write_text('{"schema":1,"schema":1}')
            with self.assertRaises(ValueError):
                hooks.verify_result(path, "a" * 32)
            link = Path(temporary) / "link.json"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                hooks.verify_result(link, "a" * 32)

    def test_diagnostics_drain_without_unbounded_memory_or_disk_growth(self):
        target = io.BytesIO()
        hooks.drain_diagnostics(io.BytesIO(b"x" * 4096), target, budget=128)
        self.assertLessEqual(len(target.getvalue()), 128)
        self.assertTrue(target.getvalue().endswith(b"[CLIENT_FIXTURE_DIAGNOSTICS_TRUNCATED]\n"))
        target = io.BytesIO()
        hooks.drain_diagnostics(io.BytesIO(b"fixture"), target)
        self.assertEqual(target.getvalue(), b"fixture")

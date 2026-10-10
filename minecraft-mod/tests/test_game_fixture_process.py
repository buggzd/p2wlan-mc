import os
from pathlib import Path
import sys
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from game_fixture_process import FixtureProcess


@unittest.skipUnless(os.name == "posix", "fixture requires owned POSIX process groups")
class FixtureProcessTests(unittest.TestCase):
    def test_large_diagnostics_are_drained_with_a_fixed_disk_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log = root / "process.log"
            child = FixtureProcess([sys.executable, "-c", "import sys; sys.stdout.write('x' * (2 * 1024 * 1024))"], root, os.environ.copy(), log)
            try:
                self.assertEqual(child.process.wait(timeout=5), 0)
            finally:
                child.close()
            self.assertLessEqual(log.stat().st_size, 1024 * 1024)
            self.assertTrue(log.read_bytes().endswith(b"[CLIENT_FIXTURE_DIAGNOSTICS_TRUNCATED]\n"))
            child.close()

    def test_close_terminates_the_owned_process_and_finishes_the_reader(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            child = FixtureProcess([sys.executable, "-c", "import time; time.sleep(60)"], root, os.environ.copy(), root / "process.log")
            child.close()
            self.assertIsNotNone(child.process.returncode)
            self.assertFalse(child.reader.is_alive())
            self.assertTrue(child.output.closed)
            child.close()

    def test_start_failure_closes_its_create_only_diagnostic_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log = root / "process.log"
            with self.assertRaises(OSError):
                FixtureProcess([str(root / "missing-command")], root, os.environ.copy(), log)
            self.assertEqual(log.read_bytes(), b"")
            with self.assertRaises(FileExistsError):
                FixtureProcess([sys.executable, "-c", "pass"], root, os.environ.copy(), log)


if __name__ == "__main__":
    unittest.main()

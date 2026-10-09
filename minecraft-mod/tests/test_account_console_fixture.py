import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("account_console_fixture", Path(__file__).with_name("account_console_fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


@unittest.skipUnless(os.name == "posix", "real terminal fixture requires POSIX")
class AccountConsoleFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "fixture-injected", "JAVA_OPTS": "fixture-injected"}), \
                patch.object(fixture.subprocess, "check_output", return_value="fixture-jdk\n"):
            self.console = fixture.ConsoleFixture(self.root, self.root, True)

    def fake(self, script):
        return patch.object(self.console, "command", return_value=[sys.executable, "-c", script])

    def test_credentials_not_arguments_and_java_injection_removed(self):
        command = self.console.command("login", self.root / "profile")
        self.assertEqual(command[-1], "--allow-development-native")
        self.assertNotIn("--allow-development-native", self.console.command("status", self.root / "profile", False))
        self.assertNotIn("JAVA_TOOL_OPTIONS", self.console.env)
        self.assertNotIn("fixture-injected", self.console.env["JAVA_OPTS"])
        self.assertEqual(len(command), 5)

    def test_real_terminal_mask_and_exact_child_cleanup_are_required(self):
        script = "import sys,termios; a=termios.tcgetattr(0); a[3]&=~termios.ECHO; termios.tcsetattr(0,termios.TCSANOW,a); print('Password: ',end='',flush=True); sys.stdin.readline(); print('DONE',flush=True)"
        with self.fake(script):
            code, text = self.console.invoke("login", self.root / "unused", (("Password: ", "fixture-secret", True),))
        self.assertEqual(code, 0)
        self.assertNotIn("fixture-secret", text)
        self.assertIn("DONE", text)
        self.assertEqual(list(self.console.cache.iterdir()), [])

    def test_secret_is_not_sent_while_terminal_echo_is_enabled(self):
        marker = self.root / "input-received"
        script = "import sys,pathlib; print('Password: ',end='',flush=True); sys.stdin.readline(); pathlib.Path(" + repr(str(marker)) + ").write_text('unexpected')"
        # Deterministic advancing deadline; no native process/network is involved.
        base = time.monotonic()
        ticks = iter(base + index * 0.1 for index in range(1000))
        with self.fake(script), patch.object(fixture.time, "monotonic", side_effect=lambda: next(ticks)):
            with self.assertRaisesRegex(fixture.FixtureError, "PASSWORD_ECHO_ENABLED"):
                self.console.invoke("login", self.root / "unused", (("Password: ", "fixture-secret", True),))
        self.assertFalse(marker.exists())

    def test_child_diagnostics_capacity_is_fail_closed(self):
        with self.fake("import sys; sys.stdout.write('x'*65537); sys.stdout.flush()"):
            with self.assertRaisesRegex(fixture.FixtureError, "CONSOLE_OUTPUT_CAPACITY"):
                self.console.invoke("status", self.root / "unused")

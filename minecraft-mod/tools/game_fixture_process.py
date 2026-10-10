"""At most three owned fixture groups/readers; bounded diagnostics and cleanup."""
import os
import signal
import time
import subprocess
import threading

from client_hooks import drain_diagnostics, stop_group


class FixtureProcess:
    def __init__(self, command, cwd, env, log):
        self.root = log.parent
        self.output = log.open("xb")
        self.failures = []
        try:
            self.process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        except BaseException:
            self.output.close()
            raise
        self.reader = threading.Thread(target=self._read, daemon=True, name="p2wlan-game-fixture-log")
        self.reader.start()
        self.closed = False

    def _read(self):
        try:
            drain_diagnostics(self.process.stdout, self.output)
        except OSError:
            self.failures.append("GAME_DIAGNOSTIC_IO")

    def group_alive(self):
        try:
            os.killpg(self.process.pid, 0)
            return True
        except ProcessLookupError:
            return False

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            stop_group(self.process)
            self.reader.join(timeout=2)
            if self.reader.is_alive() or self.group_alive():
                # Parent exit/EOF alone does not prove descendant exit. Never
                # release/delete a run whose exact owned group is still alive.
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                deadline = time.monotonic() + 3
                while self.group_alive() and time.monotonic() < deadline:
                    time.sleep(0.05)
                self.reader.join(timeout=3)
            if self.reader.is_alive() or self.group_alive():
                raise ValueError("GAME_PROCESS_GROUP_CLEANUP")
            if self.failures:
                raise ValueError(self.failures[0])
        except BaseException:
            (self.root / "cleanup-uncertain").touch(exist_ok=True)
            raise
        finally:
            if not self.reader.is_alive():
                self.process.stdout.close()
                self.output.close()

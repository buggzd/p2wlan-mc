#!/usr/bin/env python3
"""Opt-in real Control + installed Java console/packaged native acceptance.

No Minecraft/world/account terms are accepted. Synthetic credentials stay in
private temporary files/memory; no transcript or runtime identifiers are saved.
"""
import argparse
import asyncio
import errno
import json
import os
from pathlib import Path
import pty
import re
import secrets
import select
import signal
import subprocess
import sys
import tempfile
import termios
import time
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from control_smoke import Agent, available_port, environment, healthy, terminate
from verify_account_dist import verify, verify_zip
from artifact_checks import bind_sources, read, MAX_BINARY


class FixtureError(Exception):
    pass


class ConsoleFixture:
    def __init__(self, distribution, root, development):
        self.launcher = distribution / "bin/p2wlan-account"
        self.root, self.development = root, development
        self.cache = root / "extractions"
        self.cache.mkdir(mode=0o700)
        self.deadline = time.monotonic() + 300
        self.checks = 0
        self.env = environment()
        for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            self.env.pop(key, None)
        self.env["JAVA_OPTS"] = "-Djava.io.tmpdir=" + str(self.cache)
        if sys.platform == "darwin":
            self.env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True, timeout=5).strip()

    def check(self, value, code):
        self.checks += 1
        if not value:
            raise FixtureError(code)

    def command(self, action, profile, development=None):
        command = [str(self.launcher), action, "--profile-dir", str(profile)]
        if self.development if development is None else development:
            command.append("--allow-development-native")
        return command

    @staticmethod
    def alive_group(process):
        try:
            os.killpg(process.pid, 0)
            return True
        except ProcessLookupError:
            return False

    def cleanup(self, process):
        if self.alive_group(process):
            os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=23)
        except subprocess.TimeoutExpired:
            if self.alive_group(process):
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=3)
        if self.alive_group(process):
            os.killpg(process.pid, signal.SIGKILL)

    def invoke(self, action, profile, inputs=(), development=None, terminal=True, after_input=None):
        deadline = min(self.deadline, time.monotonic() + 72)
        master, slave = pty.openpty()
        os.set_blocking(master, False)
        process = None
        output = bytearray()
        eof = False
        def pump():
            nonlocal eof
            if time.monotonic() >= deadline:
                raise FixtureError("CONSOLE_DEADLINE")
            if eof:
                time.sleep(0.01)
                return
            if select.select([master], [], [], 0.05)[0]:
                try:
                    block = os.read(master, 4096)
                except OSError as error:
                    if error.errno != errno.EIO:
                        raise
                    block = b""
                if not block:
                    eof = True
                elif len(output) + len(block) > 65536:
                    raise FixtureError("CONSOLE_OUTPUT_CAPACITY")
                else:
                    output.extend(block)
        try:
            process = subprocess.Popen(self.command(action, profile, development), cwd=self.root, env=self.env,
                         stdin=slave if terminal else subprocess.DEVNULL, stdout=slave, stderr=slave, start_new_session=True)
            os.close(slave); slave = None
            cursor = 0
            for prompt, value, hidden in inputs:
                marker = prompt.encode("ascii")
                while output.find(marker, cursor) < 0:
                    if process.poll() is not None:
                        raise FixtureError("CONSOLE_PROMPT_MISSING")
                    pump()
                cursor = output.index(marker, cursor) + len(marker)
                # Prompt text is a UI assertion, NOT native state/log control.
                # Secret submission requires the kernel terminal ECHO flag off.
                mask_deadline = min(deadline, time.monotonic() + 2)
                while hidden and termios.tcgetattr(master)[3] & termios.ECHO:
                    if time.monotonic() >= mask_deadline:
                        raise FixtureError("PASSWORD_ECHO_ENABLED")
                    pump()
                if hidden:
                    self.check(not termios.tcgetattr(master)[3] & termios.ECHO, "PASSWORD_MASK")
                os.write(master, b"\x04" if value is None else value.encode("utf-8") + b"\n")
            if after_input:
                after_input(process)
            while process.poll() is None or not eof:
                pump()
            code = process.wait(timeout=1)
            text = output.decode("utf-8", errors="strict").replace("\r\n", "\n")
            for _, value, hidden in inputs:
                if hidden and value:
                    self.check(value not in text, "PASSWORD_LEAK")
            self.check(not self.alive_group(process), "HELPER_SURVIVED_CLI")
            self.check(not any(self.cache.iterdir()), "EXTRACTION_NOT_CLEANED")
            return code, text
        finally:
            if process is not None:
                self.cleanup(process)
            os.close(master)
            if slave is not None:
                os.close(slave)

    def expect(self, action, profile, receipt, inputs=(), success=True, **options):
        code, text = self.invoke(action, profile, inputs, **options)
        self.check((code == 0) == success, "CLI_EXIT_STATUS_" + action.replace("-", "_").upper())
        self.check(receipt in text.splitlines(), "CLI_RECEIPT_" + action.replace("-", "_").upper())
        return text


def account_inputs(server, identifier, password):
    return (("Control base URL: ", server, False), ("Account: ", identifier, False), ("Password: ", password, True))


async def busy_profile(fixture, agent_binary, profile):
    held = await Agent.launch(agent_binary, profile)
    try:
        await asyncio.to_thread(fixture.expect, "status", profile, "ERROR UNEXPECTED_EXIT", success=False)
    finally:
        await terminate(held.process)
    fixture.expect("status", profile, "STATUS AUTHENTICATED")


def interruption(fixture, profile):
    import socket
    import threading
    received, stop = threading.Event(), threading.Event()
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0)); listener.listen(1); listener.settimeout(1)
    def receive():
        try:
            with listener.accept()[0] as connection:
                deadline = time.monotonic() + 3
                data = bytearray()
                while b"\r\n\r\n" not in data:
                    connection.settimeout(max(0.001, deadline - time.monotonic()))
                    if time.monotonic() >= deadline or len(data) >= 4096:
                        return
                    block = connection.recv(min(1024, 4096 - len(data)))
                    if not block:
                        return
                    data.extend(block)
                header, body = bytes(data).split(b"\r\n\r\n", 1)
                lines = header.split(b"\r\n")
                lengths = [line.split(b":", 1)[1].strip() for line in lines[1:] if line.lower().startswith(b"content-length:")]
                if lines[0] != b"POST /api/v1/register HTTP/1.1" or len(lengths) != 1 or not lengths[0].isdigit():
                    return
                length = int(lengths[0])
                if not 0 < length <= 2048:
                    return
                while len(body) < length:
                    connection.settimeout(max(0.001, deadline - time.monotonic()))
                    if time.monotonic() >= deadline:
                        return
                    block = connection.recv(length - len(body))
                    if not block:
                        return
                    body += block
                # Actual bounded HTTP mutation input, not a native log string.
                # Private synthetic body is neither logged nor persisted.
                received.set(); stop.wait(5)
        except OSError:
            pass  # The typed fixture result is MUTATION_NOT_EXERCISED below.
    worker = threading.Thread(target=receive, daemon=True)
    worker.start()
    def cancel(process):
        fixture.check(received.wait(2), "MUTATION_NOT_EXERCISED")
        process.send_signal(signal.SIGTERM)
    try:
        code, _ = fixture.invoke("register", profile,
                account_inputs("http://127.0.0.1:" + str(listener.getsockname()[1]), "fixture@example.com", secrets.token_hex(16)),
                after_input=cancel)
        fixture.check(code != 0, "INTERRUPTION_EXIT")
        fixture.check(not (profile / "account.json").exists(), "INTERRUPTION_PERSISTENCE")
    finally:
        stop.set(); listener.close(); worker.join(timeout=4)
        fixture.check(not worker.is_alive(), "HTTP_FIXTURE_CLEANUP")


def exercise(fixture, distribution, root, server):
    first, second = root / "server/config/p2wlan-private", root / "client/config/p2wlan-private"
    cancelled = root / "cancelled/config/p2wlan-private"
    fixture.expect("status", first, "STATUS SIGNED_OUT")
    fixture.expect("rooms", first, "ERROR REMOTE_REJECTED AUTH_REQUIRED", success=False)
    fixture.expect("login", cancelled, "ERROR CONSOLE_REQUIRED", success=False, terminal=False)
    fixture.check(not cancelled.exists(), "NO_CONSOLE_STARTED_NATIVE")
    fixture.expect("login", cancelled, "ERROR INPUT_CANCELLED", (("Control base URL: ", None, False),), success=False)
    fixture.check(not cancelled.exists(), "EARLY_EOF_STARTED_NATIVE")
    fixture.expect("login", cancelled, "ERROR INPUT_CANCELLED",
                   account_inputs(server, "cancelled@example.com", None), success=False)
    fixture.check(not cancelled.exists(), "PASSWORD_EOF_STARTED_NATIVE")
    if fixture.development:
        fixture.expect("status", cancelled, "ERROR DEVELOPMENT_DISABLED", success=False, development=False)
        fixture.check(not cancelled.exists(), "DEVELOPMENT_DENIAL_STARTED_NATIVE")
    accounts = [(secrets.token_hex(8) + "@example.com", secrets.token_hex(16)) for _ in range(2)]
    for path, (identifier, password) in zip((first, second), accounts):
        fixture.expect("register", path, "REGISTER_OK", account_inputs(server, identifier, password))
        fixture.expect("status", path, "STATUS AUTHENTICATED")
        fixture.check(path.stat().st_mode & 0o777 == 0o700, "PROFILE_DIRECTORY_PRIVACY")
        saved = path / "account.json"
        fixture.check(saved.stat().st_mode & 0o777 == 0o600, "PROFILE_FILE_PRIVACY")
        fixture.check(saved.stat().st_size <= 65536 and json.loads(saved.read_bytes())["token"], "PROFILE_PERSISTENCE")
    room_password = secrets.token_hex(16)
    code, text = fixture.invoke("create-room", first,
                (("Room name: ", "fixture room", False), ("Room password (empty for none): ", room_password, True)))
    matches = re.findall(r"^ROOM_CREATED ([0-9]{8})$", text, re.MULTILINE)
    fixture.check(code == 0 and len(matches) == 1, "ROOM_CREATION")
    room = matches[0]
    fixture.expect("rooms", first, "ROOM " + room)
    fixture.expect("join-room", second, "ROOM_JOINED " + room,
                (("Eight-digit room code: ", room, False), ("Room password (empty for none): ", room_password, True)))
    fixture.expect("rooms", second, "ROOM " + room)
    # The lock holder is the actual executable from the verified native jar,
    # not a simulated file lock or a second writer of credential JSON.
    with zipfile.ZipFile(distribution / "lib/native-bundle-0.1.0.jar") as archive:
        binaries = [name for name in archive.namelist() if name.endswith("/p2wlan-mc-agent")]
        fixture.check(len(binaries) == 1, "FIXTURE_NATIVE_PLATFORM")
        binary = root / "lock-holder"
        binary.write_bytes(read(archive, binaries[0], MAX_BINARY)); binary.chmod(0o500)
    asyncio.run(busy_profile(fixture, binary, first))
    token = json.loads((first / "account.json").read_bytes())["token"]
    transcript = fixture.expect("rooms", first, "ROOM " + room)
    fixture.check(token not in transcript, "TOKEN_LEAK")
    fixture.expect("logout", first, "LOGOUT_OK")
    fixture.check(json.loads((first / "account.json").read_bytes()) is None, "LOGOUT_PERSISTENCE")
    fixture.expect("status", first, "STATUS SIGNED_OUT")
    fixture.expect("login", first, "LOGIN_OK", account_inputs(server, *accounts[0]))
    fixture.expect("rooms", first, "ROOM " + room)
    interruption(fixture, root / "interrupted/config/p2wlan-private")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, required=True)
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--control", type=Path, required=True)
    parser.add_argument("--development", action="store_true")
    args = parser.parse_args()
    control = None
    try:
        if os.name != "posix" or args.control.is_symlink() or not args.control.is_file() or not os.access(args.control, os.X_OK):
            raise FixtureError("FIXTURE_PLATFORM_OR_CONTROL")
        distribution = args.distribution.resolve()
        natives = verify(distribution, args.development)
        verify_zip(args.zip, distribution)
        bind_sources(natives, args.development)
        with tempfile.TemporaryDirectory(prefix="account-console-", dir=PROJECT / "native/target") as temporary:
            root = Path(temporary); root.chmod(0o700)
            fixture = ConsoleFixture(distribution, root, args.development)
            port = available_port()
            env = environment() | {"CONTROL_BIND": f"127.0.0.1:{port}", "DB_PATH": str(root / "control.db"),
                                  "JWT_SECRET": secrets.token_hex(32), "LOG_UPLOAD_DIR": str(root / "support")}
            control = subprocess.Popen([str(args.control.resolve())], cwd=root, env=env, stdin=subprocess.DEVNULL,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            try:
                deadline = time.monotonic() + 15
                while not healthy(port):
                    if control.poll() is not None or time.monotonic() >= deadline:
                        raise FixtureError("CONTROL_STARTUP")
                    time.sleep(0.05)
                exercise(fixture, distribution, root, f"http://127.0.0.1:{port}")
            finally:
                fixture.cleanup(control)
            fixture.check(not fixture.alive_group(control), "CONTROL_GROUP_CLEANUP")
            print(f"PASS installed account console: {fixture.checks} explicit checks against real Control/packaged native/PTY")
    except KeyboardInterrupt:
        print("FAIL account console fixture: FIXTURE_CANCELLED")
        return 130
    except (FixtureError, OSError, ValueError, subprocess.SubprocessError, asyncio.TimeoutError, asyncio.IncompleteReadError) as error:
        print("FAIL account console fixture: " + (str(error) if isinstance(error, FixtureError) else "FIXTURE_IO"))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

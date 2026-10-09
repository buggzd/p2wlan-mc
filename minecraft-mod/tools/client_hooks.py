#!/usr/bin/env python3
"""Run the isolated real Minecraft client hook fixture, not gameplay acceptance."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import uuid

from native_bundle import strict_object

PROJECT = Path(__file__).resolve().parents[1]
CASES = {"transformed_hooks", "owned_login", "ordinary_login", "scope_mismatch", "cancellation", "retirement_policy", "helper_cleanup"}
FIELDS = {"schema", "run", "status", "reason", "checks", "minecraft", "helper_cleanup", "eula_accepted", "cases"}
CHECKS = 61


def verify_result(path, identity):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8192:
        raise ValueError("client_fixture_output_missing_or_invalid")
    result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    if not isinstance(result, dict) or set(result) != FIELDS or type(result["schema"]) is not int or result["schema"] != 1 \
            or result["run"] != identity or result["status"] != "pass" or result["reason"] != "OK" \
            or type(result["checks"]) is not int or result["checks"] != CHECKS or result["minecraft"] != "1.21.1" \
            or result["helper_cleanup"] is not True or result["eula_accepted"] is not False \
            or not isinstance(result["cases"], list) or any(not isinstance(value, str) for value in result["cases"]) \
            or len(result["cases"]) != len(CASES) or set(result["cases"]) != CASES:
        raise ValueError("client_fixture_contract")
    return result


def stop_group(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)


def drain_diagnostics(source, target, budget=1024 * 1024):
    """One draining reader; retain at most 1 MiB, explicitly mark truncation."""
    marker = b"\n[CLIENT_FIXTURE_DIAGNOSTICS_TRUNCATED]\n"
    remaining = budget - len(marker)
    truncated = False
    while chunk := source.read(8192):
        kept = chunk[:remaining]
        target.write(kept)
        remaining -= len(kept)
        truncated |= len(kept) < len(chunk)
    if truncated:
        target.write(marker)


def run_bounded(command, cwd, env, log, timeout=240):
    """One owned process group, one bounded reader; no log control/retries."""
    with log.open("wb") as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        failures = []
        def read():
            try:
                drain_diagnostics(process.stdout, output)
            except OSError:
                failures.append("client_fixture_diagnostic_io")
        reader = threading.Thread(target=read, daemon=True, name="p2wlan-fixture-diagnostics")
        reader.start()
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            stop_group(process)
            raise ValueError("client_fixture_deadline")
        except BaseException:
            stop_group(process)
            raise
        finally:
            reader.join(timeout=2)
            if reader.is_alive():
                stop_group(process)
                reader.join(timeout=2)
            if reader.is_alive() or failures:
                raise ValueError("client_fixture_diagnostic_cleanup")
            process.stdout.close()
        return code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--development", action="store_true", help="explicit opt-in for an uncommitted native build")
    args = parser.parse_args()
    if sys.platform not in {"darwin", "linux"}:
        parser.error("client_fixture_platform_unverified")
    native = [sys.executable, str(PROJECT / "tools/native_bundle.py")]
    if args.offline:
        native.append("--offline")
    if args.development:
        native.append("--development")
    # Native generation/build is serialized before any Loom preparation.
    status = subprocess.call(native, cwd=PROJECT)
    if status:
        return status
    env = os.environ.copy()
    if sys.platform == "darwin":
        env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    root = PROJECT / "java/adapters/fabric-1.21.1/build/client-hook-fixture"
    root.mkdir(parents=True, exist_ok=True)
    identity = uuid.uuid4().hex
    job = Path(tempfile.mkdtemp(prefix=identity + "-", dir=root))
    game = job / "game"
    game.mkdir(mode=0o700)
    result = job / "result.json"
    command = [str(PROJECT / "java/gradlew"), "--no-daemon", "-PfabricAdapter", "-PclientHookFixture",
               "-PclientFixtureDirectory=" + os.path.relpath(game.resolve(), (PROJECT / "java/adapters/fabric-1.21.1").resolve()), "-PclientFixtureRun=" + identity,
               "-PclientFixtureResult=" + str(result), ":fabric:verifyModJar", ":fabric:runClientFixture"]
    if args.offline:
        command.append("--offline")
    if args.development:
        command.append("-PdevelopmentNative")
    try:
        code = run_bounded(command, PROJECT / "java", env, job / "process.log")
        if code:
            raise ValueError("client_fixture_process_failed")
        checked = verify_result(result, identity)
        # A fresh client-only run cannot inherit or accept server terms.
        if list(game.rglob("eula.txt")) or list(game.rglob("level.dat")):
            raise ValueError("client_fixture_unexpected_world_or_eula")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print("FAIL isolated client fixture:", str(error) if isinstance(error, ValueError) else "client_fixture_io", file=sys.stderr)
        print("Local diagnostic directory:", job, file=sys.stderr)
        return 1
    print("PASS real Minecraft client hooks:", checked["checks"], "checks; helper cleanup; no world/EULA or gameplay acceptance")
    print("Local structured result:", result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Opt-in dedicated-world/bind/status fixture with a preexisting accepted EULA."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
from client_hooks import run_bounded
from native_bundle import strict_object

CASES = {"world_bootstrap", "transformed_bind", "protected_status", "ordinary_status", "revocation", "helper_cleanup", "world_cleanup"}
FIELDS = {"schema", "run", "status", "reason", "checks", "minecraft", "helper_cleanup", "fixture_wrote_eula", "cases"}


def properties(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
        raise ValueError("server_fixture_config_missing")
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in result:
            raise ValueError("server_fixture_config_invalid")
        result[key] = value
    return result


def preflight(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("server_fixture_run_directory")
    eula = directory / "eula.txt"
    if properties(eula).get("eula") != "true":
        raise ValueError("server_fixture_manual_eula_required")
    config = properties(directory / "server.properties")
    port = config.get("server-port", "")
    if config.get("server-ip") != "127.0.0.1" or config.get("online-mode") != "true" \
            or config.get("enable-rcon") != "false" or config.get("enable-query") != "false" \
            or config.get("enable-status") != "true" or not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError("server_fixture_isolated_online_config_required")
    return eula.read_bytes()


def verify_result(path, identity):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8192:
        raise ValueError("server_fixture_result_missing")
    result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    if not isinstance(result, dict) or set(result) != FIELDS or type(result["schema"]) is not int or result["schema"] != 1 \
            or result["run"] != identity or result["status"] != "pass" or result["reason"] != "OK" \
            or type(result["checks"]) is not int or result["checks"] != 29 or result["minecraft"] != "1.21.1" \
            or result["helper_cleanup"] is not True or result["fixture_wrote_eula"] is not False \
            or not isinstance(result["cases"], list) or len(result["cases"]) != len(CASES) \
            or any(not isinstance(value, str) for value in result["cases"]) or set(result["cases"]) != CASES:
        raise ValueError("server_fixture_result_contract")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-directory", type=Path, required=True,
                        help="explicit disposable Minecraft server directory; its world will be loaded/saved")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        if os.name != "posix":
            raise ValueError("server_fixture_platform_unverified")
        original_eula = preflight(args.run_directory)
        game = args.run_directory.resolve()
        root = PROJECT / "java/adapters/fabric-1.21.1/build/server-boot-fixture"
        root.mkdir(parents=True, exist_ok=True)
        identity = uuid.uuid4().hex
        job = Path(tempfile.mkdtemp(prefix=identity + "-", dir=root))
        result = job / "result.json"
        command = [str(PROJECT / "java/gradlew"), "--no-daemon", "-PfabricAdapter", "-PserverBootFixture",
                   "-PserverFixtureDirectory=" + os.path.relpath(game, (PROJECT / "java/adapters/fabric-1.21.1").resolve()), "-PserverFixtureRun=" + identity,
                   "-PserverFixtureResult=" + str(result), ":fabric:verifyModJar", ":fabric:runServerFixture"]
        if args.offline:
            command.append("--offline")
        if args.development:
            command.append("-PdevelopmentNative")
        env = os.environ.copy()
        for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            env.pop(key, None)
        if sys.platform == "darwin":
            env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True, timeout=5).strip()
        if run_bounded(command, PROJECT / "java", env, job / "process.log"):
            raise ValueError("server_fixture_process_failed")
        checked = verify_result(result, identity)
        if (game / "eula.txt").read_bytes() != original_eula:
            raise ValueError("server_fixture_eula_changed")
        print("PASS actual Minecraft dedicated world/bind/status:", checked["checks"], "checks; world/helper cleanup; NOT remote login/play")
    except KeyboardInterrupt:
        print("FAIL dedicated world fixture: CANCELLED")
        return 130
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("FAIL dedicated world fixture:", str(error) if isinstance(error, ValueError) else "FIXTURE_IO")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

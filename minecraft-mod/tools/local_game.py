#!/usr/bin/env python3
"""Opt-in real local Control + Minecraft UI/client + dedicated-world acceptance."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

from control_smoke import Agent, FixtureError, available_port, environment, healthy, terminate
from game_fixture_process import FixtureProcess
from native_bundle import strict_object

PROJECT = Path(__file__).resolve().parents[1]
ADAPTER = PROJECT / "java/adapters/fabric-1.21.1"
ROUNDS = 20
CASES = {
    "server": {"world_host", "vanilla_login", "sustained_duplex", "player_disconnect", "world_cleanup", "helper_cleanup"},
    "client": {"account_buttons", "register_login", "room_join", "runtime_peer", "discovery", "vanilla_play", "sustained_duplex", "disconnect", "room_stop", "session_retained", "vanilla_browser", "telemetry", "helper_cleanup", "display_preferences", "latency_statistics", "menu_logo", "latency_hud"},
}
CHECKS = {"server": 68, "client": 54}
FIELDS = {"schema", "run", "role", "status", "reason", "checks", "minecraft", "helper_cleanup", "world_cleanup", "joins", "rounds", "cases"}


def bounded_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8192:
        raise ValueError("GAME_RESULT_MISSING_OR_INVALID")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)


def verify_result(path, identity, role):
    value = bounded_json(path)
    if role not in CASES or not isinstance(value, dict) or set(value) != FIELDS \
            or type(value["schema"]) is not int or value["schema"] != 1 or value["run"] != identity or value["role"] != role \
            or value["status"] != "pass" or value["reason"] != "OK" or value["minecraft"] != "1.21.1" \
            or type(value["checks"]) is not int or value["checks"] != CHECKS[role] \
            or value["helper_cleanup"] is not True or value["world_cleanup"] is not True \
            or type(value["joins"]) is not int or value["joins"] != 1 \
            or type(value["rounds"]) is not int or value["rounds"] != ROUNDS \
            or not isinstance(value["cases"], list) or any(not isinstance(item, str) for item in value["cases"]) \
            or len(value["cases"]) != len(CASES[role]) or set(value["cases"]) != CASES[role]:
        raise ValueError("GAME_RESULT_CONTRACT_" + role.upper())
    return value


def verify_ready(path, identity):
    value = bounded_json(path)
    if not isinstance(value, dict) or set(value) != {"schema", "run", "peer"} \
            or type(value["schema"]) is not int or value["schema"] != 1 or value["run"] != identity \
            or not isinstance(value["peer"], str) or not 1 <= len(value["peer"].encode()) <= 128:
        raise ValueError("GAME_SERVER_READY_CONTRACT")
    return value


def private_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False)


def gradle_command(job, task, offline, development):
    command = [str(PROJECT / "java/gradlew"), "--no-daemon", "-I", str(job / "build-output.gradle"),
               "-Dp2wlan.fixture.outputRoot=" + str(job / "build"), "--project-cache-dir", str(job / "gradle-cache"), "-PfabricAdapter", "-PlocalGameFixture",
               "-PlocalGameServerDirectory=" + os.path.relpath(job / "server", ADAPTER),
               "-PlocalGameClientDirectory=" + os.path.relpath(job / "client", ADAPTER),
               "-PlocalGameServerConfig=" + str(job / "server.json"),
               "-PlocalGameClientConfig=" + str(job / "client.json"), *task]
    if offline:
        command.append("--offline")
    if development:
        command.append("-PdevelopmentNative")
    return command


async def seed(binary, profile, control):
    agent = await Agent.launch(binary, profile)
    try:
        account = await agent.request("register", {"server": control, "identifier": "host-" + secrets.token_hex(8) + "@example.com", "password": secrets.token_hex(16)})
        if account.get("authenticated") is not True:
            raise FixtureError("GAME_HOST_ACCOUNT")
        password = secrets.token_hex(16)
        room = (await agent.request("create_room", {"name": "Local Minecraft fixture", "password": password}))["room"]
        await agent.shutdown()
        return room["code"], password
    finally:
        await terminate(agent.process)


def wait_ready(server, job, identity):
    deadline = time.monotonic() + 120
    path = job / "server-ready.json"
    while not path.exists():
        if server.process.poll() is not None or time.monotonic() >= deadline:
            raise ValueError("GAME_SERVER_STARTUP")
        time.sleep(0.1)
    verify_ready(path, identity)


def wait_game(processes, job, identity):
    deadline = time.monotonic() + 240
    while any(child.process.poll() is None for child in processes):
        for role in CASES:
            result = job / (role + "-result.json")
            if result.exists() and bounded_json(result).get("status") != "pass":
                raise ValueError("GAME_FAILED_" + str(bounded_json(result).get("reason", "INVALID")))
        if time.monotonic() >= deadline:
            raise ValueError("GAME_PROCESS_DEADLINE")
        time.sleep(0.1)
    for child in processes:
        if child.process.returncode != 0:
            raise ValueError("GAME_PROCESS_EXIT")
    return {role: verify_result(job / (role + "-result.json"), identity, role) for role in CASES}


def prepare(job, env, offline, development):
    if not shutil.which("go", path=env.get("PATH")):
        raise ValueError("GAME_GO_REQUIRED_ON_PATH")
    (job / "build-output.gradle").write_text("allprojects { layout.buildDirectory.set(new File(System.getProperty('p2wlan.fixture.outputRoot'), project.name)) }\n", encoding="utf-8")
    # Use the unchanged bundle entrypoint/lock/gates, but redirect only ignored
    # output. Never overwrite an active JVM or an older Windows release bundle.
    script = "import sys; from pathlib import Path; sys.path.insert(0, sys.argv.pop(1)); import native_bundle; native_bundle.OUTPUT = Path(sys.argv.pop(1)); native_bundle.main()"
    command = [sys.executable, "-c", script, str(PROJECT / "tools"), str(job / "build/native-bundle/generatedResources")]
    if offline:
        command.append("--offline")
    if development:
        command.append("--development")
    subprocess.run(command, cwd=PROJECT, env=env, check=True, timeout=1800)
    # Build the real, unchanged local Control; never contact a user deployment.
    subprocess.run(["go", "build", "-o", str(job / "control"), "."], cwd=PROJECT.parent / "server",
                   env=env | {"CGO_ENABLED": "0"}, check=True, timeout=300)
    build = FixtureProcess(gradle_command(job, [":fabric:verifyModJar", ":fabric:localGameFixtureClasses", ":fabric:localGameClientFixtureClasses"], offline, development), PROJECT / "java", env, job / "build.log")
    try:
        if build.process.wait(timeout=240):
            raise ValueError("GAME_FIXTURE_BUILD")
    finally:
        build.close()


def exercise(job, env, offline, development):
    identity = uuid.uuid4().hex
    port = available_port()
    control_url = "http://127.0.0.1:" + str(port)
    children = []
    try:
        control_env = env | {"CONTROL_BIND": "127.0.0.1:" + str(port), "DB_PATH": str(job / "control.db"),
                            "JWT_SECRET": secrets.token_hex(32), "LOG_UPLOAD_DIR": str(job / "support")}
        control = FixtureProcess([str(job / "control")], job, control_env, job / "control.log")
        children.append(control)
        deadline = time.monotonic() + 15
        while not healthy(port):
            if control.process.poll() is not None or time.monotonic() >= deadline:
                raise ValueError("GAME_CONTROL_STARTUP")
            time.sleep(0.1)
        room, password = asyncio.run(seed(PROJECT / "native/target/release/p2wlan-mc-agent", job / "server/config/p2wlan-private", control_url))
        common = {"schema": 1, "run": identity, "control": control_url, "room": room, "room_password": password}
        private_json(job / "server.json", common | {"role": "server", "identifier": "", "password": ""})
        private_json(job / "client.json", common | {"role": "client", "identifier": "player-" + secrets.token_hex(8) + "@example.com", "password": secrets.token_hex(16)})
        server = FixtureProcess(gradle_command(job, [":fabric:runLocalGameServer"], offline, development), PROJECT / "java", env, job / "server.log")
        children.append(server); wait_ready(server, job, identity)
        client = FixtureProcess(gradle_command(job, [":fabric:runLocalGameClient"], offline, development), PROJECT / "java", env, job / "client.log")
        children.append(client)
        return wait_game([server, client], job, identity)
    finally:
        errors = []
        for child in reversed(children):
            try:
                child.close()
            except (OSError, ValueError, subprocess.SubprocessError):
                errors.append("GAME_PROCESS_CLEANUP")
        if errors:
            (job / "cleanup-uncertain").touch(exist_ok=True)
            raise ValueError(errors[0])


def server_settings(port):
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("GAME_SERVER_PORT")
    flat = {"layers": [{"height": 1, "block": "minecraft:bedrock"}, {"height": 2, "block": "minecraft:dirt"},
                       {"height": 1, "block": "minecraft:grass_block"}], "biome": "minecraft:plains", "lakes": False, "features": False}
    settings = {"server-ip": "127.0.0.1", "server-port": port, "online-mode": "false", "enforce-secure-profile": "false",
                "enable-rcon": "false", "enable-query": "false", "view-distance": 3, "simulation-distance": 3,
                "max-players": 2, "spawn-protection": 0, "gamemode": "creative", "difficulty": "peaceful",
                "level-type": "minecraft:flat", "generator-settings": json.dumps(flat, separators=(",", ":")), "generate-structures": "false"}
    return "".join(f"{key}={value}\n" for key, value in settings.items())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true", help="explicitly accept Minecraft server terms for this disposable local world")
    parser.add_argument("--offline-mode", action="store_true", help="explicitly use offline Minecraft authentication ONLY on a fresh loopback test server")
    parser.add_argument("--offline", action="store_true", help="use cached Cargo/Gradle dependencies")
    parser.add_argument("--development", action="store_true", help="explicitly permit source-bound development native resources")
    args = parser.parse_args()
    if not args.accept_eula or not args.offline_mode:
        parser.error("explicit --accept-eula and --offline-mode are required; no user world/config is modified")
    if sys.platform not in {"darwin", "linux"}:
        parser.error("local gameplay fixture is not verified on this platform")
    env = environment()
    for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    if sys.platform == "darwin":
        env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True, timeout=5).strip()
    parent = ADAPTER / "build/local-game-fixture"
    parent.mkdir(parents=True, exist_ok=True)
    job = Path(tempfile.mkdtemp(prefix="run-", dir=parent)).resolve()
    os.chmod(job, 0o700)
    for role in CASES:
        (job / role).mkdir(mode=0o700)
    eula = job / "server/eula.txt"
    eula.write_text("eula=true\n", encoding="utf-8")
    original_eula = eula.read_bytes()
    (job / "server/server.properties").write_text(server_settings(available_port()), encoding="utf-8")
    try:
        prepare(job, env, args.offline, args.development)
        checked = exercise(job, env, args.offline, args.development)
        if eula.read_bytes() != original_eula:
            raise ValueError("GAME_EULA_CHANGED")
        print("PASS real local Minecraft: UI register/login, room join, encrypted service discovery, Vanilla play,")
        print("20 ordered C2S commands + S2C game-state/message replies over at least 18 seconds, disconnect/room-stop and both helpers/world cleanup.")
        print("Assertions:", checked["server"]["checks"], "server;", checked["client"]["checks"], "client. NOT Windows/public-network/multi-loader acceptance.")
    except KeyboardInterrupt:
        print("FAIL local Minecraft: CANCELLED", file=sys.stderr)
        return 130
    except (OSError, ValueError, FixtureError, subprocess.SubprocessError, asyncio.TimeoutError) as error:
        print("FAIL local Minecraft:", str(error) if isinstance(error, (ValueError, FixtureError)) else type(error).__name__, file=sys.stderr)
        return 1
    finally:
        print("Private local diagnostics:", job)
        # These are newly created disposable test trees, never a user install.
        # Groups are closed above before deleting profiles/temporary credentials.
        if not (job / "cleanup-uncertain").exists():
            for role in CASES:
                shutil.rmtree(job / role)
                (job / (role + ".json")).unlink(missing_ok=True)
            for path in job.glob("control.db*"):
                path.unlink()
            (job / "server-ready.json").unlink(missing_ok=True)
            (job / "control").unlink(missing_ok=True)
        else:
            print("Private test state retained: process cleanup could not be confirmed.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

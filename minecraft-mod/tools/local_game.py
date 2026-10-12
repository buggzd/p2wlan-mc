#!/usr/bin/env python3
"""Opt-in local Control + real Minecraft clients/dedicated or integrated world."""
import argparse
import asyncio
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import io
import zipfile

from control_smoke import Agent, FixtureError, available_port, environment, healthy, terminate
from game_fixture_process import FixtureProcess
from native_bundle import MAX_BINARY, executable_name, host_platform, strict_object

PROJECT = Path(__file__).resolve().parents[1]
ADAPTER = PROJECT / "java/adapters/fabric-1.21.1"
ROUNDS = 20
CASES = {
    "server": {"world_host", "vanilla_login", "sustained_duplex", "player_disconnect", "world_cleanup", "helper_cleanup"},
    "client": {"account_buttons", "register_login", "room_join", "runtime_peer", "discovery", "vanilla_play", "sustained_duplex", "disconnect", "room_stop", "session_retained", "vanilla_browser", "telemetry", "helper_cleanup", "display_preferences", "latency_statistics", "menu_logo", "latency_hud", "startup_diagnostics"},
}
CHECKS = {"server": 68, "client": 65}
INTEGRATED_SERVER_CASES = {"integrated_world", "no_vanilla_lan", "shared_pause", "vanilla_login", "sustained_duplex", "remote_disconnect", "unhost_pause", "world_cleanup", "helper_cleanup"}
FIELDS = {"schema", "run", "role", "status", "reason", "checks", "minecraft", "helper_cleanup", "world_cleanup", "joins", "rounds", "cases"}
MAX_INSTALLED_ARCHIVE = MAX_BINARY + 16 * 1024 * 1024


def bounded_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8192:
        raise ValueError("GAME_RESULT_MISSING_OR_INVALID")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)


def verify_result(path, identity, role, integrated=False):
    value = bounded_json(path)
    cases = INTEGRATED_SERVER_CASES if integrated and role == "server" else CASES.get(role, set())
    checks = 77 if integrated and role == "server" else CHECKS.get(role)
    if role not in CASES or not isinstance(value, dict) or set(value) != FIELDS \
            or type(value["schema"]) is not int or value["schema"] != 1 or value["run"] != identity or value["role"] != role \
            or value["status"] != "pass" or value["reason"] != "OK" or value["minecraft"] != "1.21.1" \
            or type(value["checks"]) is not int or value["checks"] != checks \
            or value["helper_cleanup"] is not True or value["world_cleanup"] is not True \
            or type(value["joins"]) is not int or value["joins"] != 1 \
            or type(value["rounds"]) is not int or value["rounds"] != ROUNDS \
            or not isinstance(value["cases"], list) or any(not isinstance(item, str) for item in value["cases"]) \
            or len(value["cases"]) != len(cases) or set(value["cases"]) != cases:
        raise ValueError("GAME_RESULT_CONTRACT_" + role.upper())
    return value


def verify_ready(path, identity):
    value = bounded_json(path)
    if not isinstance(value, dict) or set(value) != {"schema", "run", "peer"} \
            or type(value["schema"]) is not int or value["schema"] != 1 or value["run"] != identity \
            or not isinstance(value["peer"], str) or not 1 <= len(value["peer"].encode()) <= 128:
        raise ValueError("GAME_SERVER_READY_CONTRACT")
    return value


def verify_inflight_result(path, identity, role):
    failure = "GAME_RESULT_CONTRACT_" + role.upper()
    try:
        value = bounded_json(path)
    except (OSError, ValueError):
        raise ValueError(failure) from None
    # GameFixtureData emits a fixed stage (at most 16 characters), one
    # separator and an error code of at most 100 characters. Never report
    # unchecked result text, including a result from another run or role.
    if not isinstance(value, dict) or set(value) != FIELDS \
            or type(value["schema"]) is not int or value["schema"] != 1 \
            or value["run"] != identity or value["role"] != role \
            or value["status"] not in ("pass", "fail") \
            or not isinstance(value["reason"], str) or re.fullmatch(r"[A-Z][A-Z0-9_]{0,116}", value["reason"]) is None:
        raise ValueError(failure)
    return value


def private_json(path, value):
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False)


def java_directory(loader):
    return PROJECT / ("java/forge" if loader == "forge" else "java")


def game_environment(loader):
    env = environment()
    for key in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    if loader == "forge":
        # Build-only paths, not Control/Relay/account configuration. All other
        # P2WLAN environment values remain excluded by the shared sanitizer.
        for key in ("P2WLAN_FORGE_GRADLE_ZIP", "P2WLAN_FORGE_JAVA25_HOME", "P2WLAN_FORGE_JAVA8_HOME", "P2WLAN_FORGE_ASSETS"):
            if key in os.environ: env[key] = os.environ[key]
    if sys.platform == "darwin":
        env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True, timeout=5).strip()
    return env


def archive_sha256(path):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_INSTALLED_ARCHIVE:
        raise ValueError("GAME_INSTALLED_ARCHIVE_INVALID")
    digest = hashlib.sha256()
    count = 0
    with path.open("rb") as stream:
        while chunk := stream.read(32768):
            count += len(chunk)
            if count > MAX_INSTALLED_ARCHIVE:
                raise ValueError("GAME_INSTALLED_ARCHIVE_INVALID")
            digest.update(chunk)
    return digest.hexdigest()


def install_archive(job, archive=None, payload=None):
    archive = archive or job / "build/forge/libs/p2wlan-forge-1.21.1-0.1.0.jar"
    expected = archive_sha256(archive)
    if payload is not None and hashlib.sha256(payload).hexdigest() != expected:
        raise ValueError("GAME_INSTALLED_ARCHIVE_CHANGED")
    for role in CASES:
        mods = job / role / "mods"
        mods.mkdir(mode=0o700)
        target = mods / "p2wlan.jar"
        with (io.BytesIO(payload) if payload is not None else archive.open("rb")) as source, target.open("xb") as destination:
            os.chmod(target, 0o600)
            count = 0
            while chunk := source.read(32768):
                count += len(chunk)
                if count > MAX_INSTALLED_ARCHIVE:
                    raise ValueError("GAME_INSTALLED_ARCHIVE_INVALID")
                destination.write(chunk)
        if archive_sha256(target) != expected:
            raise ValueError("GAME_INSTALLED_ARCHIVE_CHANGED")
    if archive_sha256(archive) != expected:
        raise ValueError("GAME_INSTALLED_ARCHIVE_CHANGED")
    return expected


def external_archive(path, development):
    from universal_jar import regular_bytes, verify
    if not path.is_absolute():
        raise ValueError("GAME_INSTALLED_ARCHIVE_INVALID")
    try:
        payload = regular_bytes(path)
        info = verify(io.BytesIO(payload), development)
    except (OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile) as failure:
        raise ValueError("GAME_INSTALLED_ARCHIVE_VERIFICATION") from failure
    if info["artifact_sha256"] != hashlib.sha256(payload).hexdigest():
        raise ValueError("GAME_INSTALLED_ARCHIVE_CHANGED")
    return payload


def prepare_seed_binary(job, payload):
    from artifact_checks import read
    from universal_jar import CORE_PATHS
    platform = "-".join(host_platform())
    prefix = "p2wlan/native/" + platform + "/"
    with zipfile.ZipFile(io.BytesIO(payload)) as outer:
        nested = read(outer, CORE_PATHS["native"], MAX_BINARY)
    with zipfile.ZipFile(io.BytesIO(nested)) as archive:
        fields = dict(line.split("=", 1) for line in read(archive, prefix + "manifest.properties", 4096).decode("ascii").splitlines())
        binary = read(archive, prefix + executable_name(platform), MAX_BINARY)
    if len(binary) != int(fields["executable-size"]) or hashlib.sha256(binary).hexdigest() != fields["executable-sha256"]:
        raise ValueError("GAME_INSTALLED_NATIVE_CHANGED")
    directory = job / "seed-native"; directory.mkdir(mode=0o700)
    target = directory / executable_name(platform)
    with target.open("xb") as output:
        os.chmod(target, 0o600)
        output.write(binary)
    os.chmod(target, 0o500)
    return target


def gradle_command(job, task, offline, development, loader="fabric", integrated=False, installed=False, archive_sha=None, installed_mod=None):
    external = installed_mod is not None
    installed = installed or external
    if installed and loader != "forge" and not external:
        raise ValueError("GAME_INSTALLED_LOADER")
    adapter = PROJECT / f"java/adapters/{loader}-1.21.1"
    directory = (lambda role: os.path.relpath(job / role, adapter)) if loader == "fabric" else (lambda role: str(job / role))
    if loader == "forge":
        from forge_gradle import command as forge_command
        launcher = forge_command(game_environment(loader))
        assets = os.environ.get("P2WLAN_FORGE_ASSETS")
        if assets:
            asset_path = Path(assets)
            if not asset_path.is_absolute() or not asset_path.is_dir():
                raise ValueError("FORGE_ASSET_DIRECTORY")
            launcher.append("-PforgeAssetDirectory=" + str(asset_path))
        replacements = {":forge:localGameClientFixtureClasses": ":forge:localGameFixtureClasses",
                        ":forge:runLocalGameServer": ":forge:runLocalGameFixtureServer",
                        ":forge:runLocalGameClient": ":forge:runLocalGameFixtureClient",
                        ":forge:runIntegratedHost": ":forge:runIntegratedFixtureClient"}
        task = [replacements.get(value, value) for value in task]
    else:
        launcher = [str(PROJECT / "java/gradlew")]
    command = [*launcher, "--no-daemon", "-I", str(job / "build-output.gradle"),
               "-Dp2wlan.fixture.outputRoot=" + str(job / "build"), "--project-cache-dir", str(job / "gradle-cache"), "-PlocalGameFixture",
               "-PlocalGameServerDirectory=" + directory("server"),
               "-PlocalGameClientDirectory=" + directory("client"),
               "-PlocalGameServerConfig=" + str(job / "server.json"),
               "-PlocalGameClientConfig=" + str(job / "client.json"), *task]
    if loader != "forge":
        command.append(f"-P{loader}Adapter")
    if integrated:
        command.append("-PintegratedGameFixture")
    if installed:
        role = "client" if any(value in task for value in (f":{loader}:runLocalGameClient", ":forge:runLocalGameFixtureClient")) else "server"
        archive = job / role / "mods/p2wlan.jar"
        command.extend(["-PinstalledGameFixture", "-PlocalGameInstalledArchive=" + str(archive)])
        if external:
            command.append("-PinstalledModArchive=" + str(archive))
        if any(value.startswith(f":{loader}:run") for value in task) and archive_sha is None:
            raise ValueError("GAME_INSTALLED_ARCHIVE_CHANGED")
        if archive_sha is not None:
            if re.fullmatch(r"[a-f0-9]{64}", archive_sha) is None or archive_sha256(archive) != archive_sha:
                raise ValueError("GAME_INSTALLED_ARCHIVE_CHANGED")
            command.append("-PlocalGameInstalledSha256=" + archive_sha)
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


def wait_game(processes, job, identity, integrated=False):
    deadline = time.monotonic() + 240
    while any(child.process.poll() is None for child in processes):
        # A failed peer must not be hidden behind the other world's play
        # deadline; exact group cleanup still belongs to exercise's finally.
        if any(child.process.poll() not in (None, 0) for child in processes):
            raise ValueError("GAME_PROCESS_EXIT")
        for role in CASES:
            result = job / (role + "-result.json")
            if result.exists():
                value = verify_inflight_result(result, identity, role)
                if value["status"] == "fail":
                    raise ValueError("GAME_FAILED_" + value["reason"])
        if time.monotonic() >= deadline:
            raise ValueError("GAME_PROCESS_DEADLINE")
        time.sleep(0.1)
    for child in processes:
        if child.process.returncode != 0:
            raise ValueError("GAME_PROCESS_EXIT")
    return {role: verify_result(job / (role + "-result.json"), identity, role, integrated) for role in CASES}


def prepare(job, env, offline, development, loader="fabric", integrated=False, installed=False, installed_mod=None):
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
    captured = None
    if installed_mod is None:
        subprocess.run(command, cwd=PROJECT, env=env, check=True, timeout=1800)
    else:
        payload = external_archive(installed_mod, development)
        captured = install_archive(job, installed_mod, payload)
        prepare_seed_binary(job, payload)
    # Build the real, unchanged local Control; never contact a user deployment.
    subprocess.run(["go", "build", "-o", str(job / "control"), "."], cwd=PROJECT.parent / "server",
                   env=env | {"CGO_ENABLED": "0"}, check=True, timeout=300)
    tasks = [f":{loader}:verifyModJar", f":{loader}:localGameFixtureClasses", f":{loader}:localGameClientFixtureClasses"]
    if integrated:
        tasks.append(f":{loader}:integratedFixtureClasses")
    if installed_mod is not None and loader == "fabric":
        tasks += [":fabric:relocateLocalGameServer", ":fabric:relocateLocalGameClient"]
        if integrated: tasks.append(":fabric:relocateIntegratedHost")
    build = FixtureProcess(gradle_command(job, tasks, offline, development, loader, integrated, installed, captured, installed_mod), java_directory(loader), env, job / "build.log")
    try:
        if build.process.wait(timeout=240):
            raise ValueError("GAME_FIXTURE_BUILD")
    finally:
        build.close()
    return captured if installed_mod is not None else install_archive(job) if installed else None


def exercise(job, env, offline, development, loader="fabric", integrated=False, installed=False, archive_sha=None, installed_mod=None):
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
        binary = job / "seed-native" / executable_name("-".join(host_platform())) if installed_mod is not None else PROJECT / "native/target/release/p2wlan-mc-agent"
        room, password = asyncio.run(seed(binary, job / "server/config/p2wlan-private", control_url))
        common = {"schema": 1, "run": identity, "control": control_url, "room": room, "room_password": password}
        private_json(job / "server.json", common | {"role": "server", "identifier": "", "password": ""})
        private_json(job / "client.json", common | {"role": "client", "identifier": "player-" + secrets.token_hex(8) + "@example.com", "password": secrets.token_hex(16)})
        server = FixtureProcess(gradle_command(job, [f":{loader}:runIntegratedHost" if integrated else f":{loader}:runLocalGameServer"], offline, development, loader, integrated, installed, archive_sha, installed_mod), java_directory(loader), env, job / "server.log")
        children.append(server); wait_ready(server, job, identity)
        client = FixtureProcess(gradle_command(job, [f":{loader}:runLocalGameClient"], offline, development, loader, integrated, installed, archive_sha, installed_mod), java_directory(loader), env, job / "client.log")
        children.append(client)
        return wait_game([server, client], job, identity, integrated)
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
    parser.add_argument("--loader", choices=("fabric", "neoforge", "forge"), default="fabric")
    parser.add_argument("--integrated", action="store_true", help="two real client JVMs with a fresh singleplayer host world")
    parser.add_argument("--installed", action="store_true", help="Forge: load the checked JarJar archive with production classpath fallbacks excluded")
    parser.add_argument("--installed-mod", type=Path, help="load this source-bound Universal archive on the selected loader; no production source/classpath fallback")
    parser.add_argument("--accept-eula", action="store_true", help="explicitly accept Minecraft server terms for this disposable local world")
    parser.add_argument("--offline-mode", action="store_true", help="explicitly use offline Minecraft authentication ONLY on a fresh loopback test server")
    parser.add_argument("--offline", action="store_true", help="use cached Cargo/Gradle dependencies")
    parser.add_argument("--development", action="store_true", help="explicitly permit source-bound development native resources")
    args = parser.parse_args()
    if args.installed_mod is not None and (args.installed or not args.installed_mod.is_absolute()):
        parser.error("--installed-mod requires an absolute archive and cannot be combined with --installed")
    if args.installed and args.loader != "forge":
        parser.error("--installed requires --loader forge")
    if not args.accept_eula or not args.offline_mode:
        parser.error("explicit --accept-eula and --offline-mode are required; no user world/config is modified")
    if sys.platform not in {"darwin", "linux"}:
        parser.error("local gameplay fixture is not verified on this platform")
    env = game_environment(args.loader)
    parent = PROJECT / f"java/adapters/{args.loader}-1.21.1/build/local-game-fixture"
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
        archive_sha = prepare(job, env, args.offline, args.development, args.loader, args.integrated, args.installed, args.installed_mod)
        checked = exercise(job, env, args.offline, args.development, args.loader, args.integrated, args.installed, archive_sha, args.installed_mod)
        if eula.read_bytes() != original_eula:
            raise ValueError("GAME_EULA_CHANGED")
        print("PASS real local Minecraft (" + args.loader + "): UI register/login, room join, encrypted service discovery, Vanilla play,")
        print("20 ordered C2S commands + S2C game-state/message replies over at least 18 seconds, disconnect/room-stop and both helpers/world cleanup.")
        print("Host mode:", "integrated" if args.integrated else "dedicated")
        print("Installation:", "universal" if args.installed_mod is not None else "jarjar" if args.installed else "source")
        print("Assertions:", checked["server"]["checks"], "host;", checked["client"]["checks"], "guest. Scope: local Minecraft 1.21.1 on this host platform.")
    except KeyboardInterrupt:
        print("FAIL local Minecraft: CANCELLED", file=sys.stderr)
        return 130
    except (OSError, ValueError, FixtureError, subprocess.SubprocessError, asyncio.TimeoutError, zipfile.BadZipFile) as error:
        print("FAIL local Minecraft:", str(error) if isinstance(error, (ValueError, FixtureError)) else type(error).__name__, file=sys.stderr)
        # A finally/cleanup failure must retain the original execution reason.
        # Only fixed reason codes are printed, never exception text/credentials.
        original = error.__context__
        if isinstance(original, (ValueError, FixtureError)) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,159}", str(original)):
            print("Original execution failure:", str(original), file=sys.stderr)
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

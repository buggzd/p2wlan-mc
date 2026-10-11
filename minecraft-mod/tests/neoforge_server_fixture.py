#!/usr/bin/env python3
"""Build/verify and run one disposable NeoForge offline dedicated-world fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import uuid

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'tools'))
from game_fixture_process import FixtureProcess
from server_boot_fixture import CASES, FIELDS as WORLD_FIELDS
from native_bundle import strict_object

FIELDS = WORLD_FIELDS | {"loader", "loader_version", "installation", "helper_reason"}


def verify_result(path, identity, installed=False):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8192:
        raise ValueError("NEOFORGE_FIXTURE_RESULT_MISSING")
    result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    if not isinstance(result, dict) or set(result) != FIELDS or type(result["schema"]) is not int or result["schema"] != 1 \
            or result["run"] != identity or result["status"] != "pass" or result["reason"] != "OK" \
            or type(result["checks"]) is not int or result["checks"] != 32 or result["minecraft"] != "1.21.1" \
            or result["loader"] != "neoforge" or result["loader_version"] != "21.1.256" \
            or result["installation"] != ("jarjar" if installed else "development") \
            or result["helper_reason"] is not None or result["helper_cleanup"] is not True or result["fixture_wrote_eula"] is not False \
            or not isinstance(result["cases"], list) or len(result["cases"]) != len(CASES) \
            or any(not isinstance(value, str) for value in result["cases"]) or set(result["cases"]) != CASES:
        raise ValueError("NEOFORGE_FIXTURE_RESULT_CONTRACT")
    return result


def reserve_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def gradle_command(job, identity, tasks, offline, development, installed=False):
    command = [str(PROJECT / 'java/gradlew'), '--no-daemon', '--init-script', str(job / 'build-output.gradle'),
               '-Dp2wlan.fixture.outputRoot=' + str(job / 'build'), '--project-cache-dir', str(job / 'gradle-cache'),
               '-PneoforgeAdapter', '-PserverBootFixture',
               '-PserverFixtureDirectory=' + str(job / 'game'), '-PserverFixtureRun=' + identity,
               '-PserverFixtureResult=' + str(job / 'result.json'), *tasks]
    if installed: command.append('-PserverInstalledFixture')
    if offline: command.append('--offline')
    if development: command.append('-PdevelopmentNative')
    return command


def run(command, env, log, timeout):
    child = FixtureProcess(command, PROJECT / 'java', env, log)
    try:
        if child.process.wait(timeout=timeout) != 0:
            raise ValueError('NEOFORGE_FIXTURE_PROCESS_FAILED')
    finally:
        child.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true', help='consent for this new disposable Minecraft test world only')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--development', action='store_true')
    parser.add_argument('--installed', action='store_true', help='load the checked production JAR and its JarJar libraries from mods, without development-library fallbacks')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('NEOFORGE_FIXTURE_EXPLICIT_EULA_REQUIRED')
    if os.name != 'posix':
        parser.error('NEOFORGE_FIXTURE_PLATFORM_UNVERIFIED')
    root = PROJECT / 'java/adapters/neoforge-1.21.1/build/server-boot-fixture'
    root.mkdir(parents=True, exist_ok=True)
    if list(root.glob('*/cleanup-uncertain')):
        parser.error('NEOFORGE_FIXTURE_PREVIOUS_CLEANUP_UNCERTAIN')
    job = Path(tempfile.mkdtemp(prefix='run-', dir=root)).resolve()
    job.chmod(0o700)
    identity = uuid.uuid4().hex
    env = os.environ.copy()
    for key in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    try:
        if sys.platform == 'darwin':
            env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True, timeout=5).strip()
        (job / 'build-output.gradle').write_text("allprojects { layout.buildDirectory.set(new File(System.getProperty('p2wlan.fixture.outputRoot'), project.name)) }\n")
        script = 'import sys; from pathlib import Path; sys.path.insert(0,sys.argv.pop(1)); import native_bundle; native_bundle.OUTPUT=Path(sys.argv.pop(1)); native_bundle.main()'
        command = [sys.executable, '-c', script, str(PROJECT / 'tools'), str(job / 'build/native-bundle/generatedResources')]
        if args.offline: command.append('--offline')
        if args.development: command.append('--development')
        run(command, env, job / 'native-build.log', 1800)
        game = job / 'game'; game.mkdir(mode=0o700)
        (game / 'eula.txt').write_text('eula=true\n')
        (game / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=' + str(reserve_port())
                + '\nonline-mode=false\nenable-status=true\nenable-query=false\nenable-rcon=false\n'
                + 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n'
                + 'max-players=2\ngamemode=creative\ndifficulty=peaceful\nspawn-protection=0\n')
        original_eula = (game / 'eula.txt').read_bytes()
        build = gradle_command(job, identity, [':neoforge:verifyModJar', ':neoforge:serverFixtureClasses'], args.offline, args.development, args.installed)
        run(build, env, job / 'build.log', 900)
        if args.installed:
            archive = job / 'build/neoforge/libs/p2wlan-neoforge-1.21.1-0.1.0.jar'
            mods = game / 'mods'; mods.mkdir(mode=0o700)
            destination = mods / archive.name
            # The create-new isolated build was checked above; copy only that
            # archive and verify the copied bytes before game discovery.
            with archive.open('rb') as source, destination.open('xb') as target:
                shutil.copyfileobj(source, target, length=32768)
            with archive.open('rb') as source, destination.open('rb') as target:
                if hashlib.file_digest(source, 'sha256').digest() != hashlib.file_digest(target, 'sha256').digest():
                    raise ValueError('NEOFORGE_FIXTURE_INSTALLED_ARCHIVE_CHANGED')
        start = gradle_command(job, identity, [':neoforge:runServerFixture'], args.offline, args.development, args.installed)
        run(start, env, job / 'game.log', 240)
        result = verify_result(job / 'result.json', identity, args.installed)
        if (game / 'eula.txt').read_bytes() != original_eula:
            raise ValueError('NEOFORGE_FIXTURE_EULA_CHANGED')
        print('PASS actual NeoForge 21.1.256 / Minecraft 1.21.1 dedicated world:', result['checks'], result['installation'], 'checks; protected/ordinary status, revocation, world/helper cleanup')
        return 0
    except KeyboardInterrupt:
        print('FAIL NeoForge dedicated fixture: CANCELLED'); return 130
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print('FAIL NeoForge dedicated fixture:', str(error) if isinstance(error, ValueError) else 'FIXTURE_IO'); return 1
    finally:
        # Only create-new tool-owned state after exact process-group cleanup.
        # Bounded logs, build artifacts and structured results remain ignored.
        if not (job / 'cleanup-uncertain').exists() and (job / 'game').is_dir():
            shutil.rmtree(job / 'game')


if __name__ == '__main__':
    raise SystemExit(main())

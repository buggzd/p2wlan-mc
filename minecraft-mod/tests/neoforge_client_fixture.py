#!/usr/bin/env python3
"""Run shared exact-game client hooks through the pinned NeoForge adapter."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / 'tools'))
from client_hooks import verify_result
from neoforge_server_fixture import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--development', action='store_true')
    args = parser.parse_args()
    if os.name != 'posix': parser.error('NEOFORGE_CLIENT_PLATFORM_UNVERIFIED')
    root = PROJECT / 'java/adapters/neoforge-1.21.1/build/client-hook-fixture'
    root.mkdir(parents=True, exist_ok=True)
    if list(root.glob('*/cleanup-uncertain')): parser.error('NEOFORGE_CLIENT_PREVIOUS_CLEANUP_UNCERTAIN')
    job = Path(tempfile.mkdtemp(prefix='run-', dir=root)).resolve(); job.chmod(0o700)
    identity = uuid.uuid4().hex
    env = os.environ.copy()
    for key in ('JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'): env.pop(key, None)
    if sys.platform == 'darwin': env['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True, timeout=5).strip()
    try:
        (job / 'build-output.gradle').write_text("allprojects { layout.buildDirectory.set(new File(System.getProperty('p2wlan.fixture.outputRoot'), project.name)) }\n")
        script = 'import sys; from pathlib import Path; sys.path.insert(0,sys.argv.pop(1)); import native_bundle; native_bundle.OUTPUT=Path(sys.argv.pop(1)); native_bundle.main()'
        native = [sys.executable, '-c', script, str(PROJECT / 'tools'), str(job / 'build/native-bundle/generatedResources')]
        if args.offline: native.append('--offline')
        if args.development: native.append('--development')
        run(native, env, job / 'native-build.log', 1800)
        (job / 'game').mkdir(mode=0o700)
        base = [str(PROJECT / 'java/gradlew'), '--no-daemon', '-I', str(job / 'build-output.gradle'),
                '-Dp2wlan.fixture.outputRoot=' + str(job / 'build'), '--project-cache-dir', str(job / 'gradle-cache'),
                '-PneoforgeAdapter', '-PclientHookFixture', '-PclientFixtureDirectory=' + str(job / 'game'),
                '-PclientFixtureRun=' + identity, '-PclientFixtureResult=' + str(job / 'result.json')]
        if args.offline: base.append('--offline')
        if args.development: base.append('-PdevelopmentNative')
        run(base + [':neoforge:verifyModJar', ':neoforge:clientFixtureClasses'], env, job / 'build.log', 900)
        run(base + [':neoforge:runClientFixture'], env, job / 'game.log', 240)
        result = verify_result(job / 'result.json', identity)
        print('PASS actual NeoForge client hooks:', result['checks'], 'checks; scoped/ordinary Vanilla login bytes, retirement, cancellation, helper cleanup')
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print('FAIL NeoForge client fixture:', str(error) if isinstance(error, ValueError) else 'FIXTURE_IO'); return 1
    finally:
        if not (job / 'cleanup-uncertain').exists() and (job / 'game').exists(): shutil.rmtree(job / 'game')

if __name__ == '__main__': raise SystemExit(main())

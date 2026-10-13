#!/usr/bin/env python3
"""Compile the isolated official Forge 1.16.5 SDK with pinned Gradle and Java 17."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess

from forge_gradle import bounded_file, publish
from legacy_gradle import java17

GRADLE_SHA256 = '3e1af3ae886920c3ac87f7a91f816c0c7c436f276a6eefdb3da152100fef72ae'
DISTRIBUTION = b'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.4-bin.zip'

PROJECT = Path(__file__).resolve().parents[1]
ROOT = Path('java/forge-1165')
ACTIONS = {
    'compile': [':forge:compileSdk'],
    'build': [':forge:reobfJar'],
    'fixtures': [':forge:reobfInstalledLocalGameFixture', ':forge:reobfInstalledIntegratedFixture',
                 '-PlocalGameFixture', '-PintegratedGameFixture'],
}


def command(environment):
    selected_environment = dict(environment)
    if 'P2WLAN_FORGE1165_JAVA_HOME' in selected_environment:
        selected_environment['P2WLAN_LEGACY_JAVA_HOME'] = selected_environment['P2WLAN_FORGE1165_JAVA_HOME']
    java, home = java17(selected_environment)
    paths = [str(home)]
    mcp_home = environment.get('P2WLAN_FORGE1165_MCP_JAVA_HOME')
    if mcp_home:
        mcp = Path(mcp_home)
        executable = mcp / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        compiler = mcp / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
        if not mcp.is_absolute() or not mcp.is_dir() or not executable.is_file() \
                or not compiler.is_file() or not (mcp / 'lib/tools.jar').is_file():
            raise ValueError('FORGE1165_MCP_JAVA8_REQUIRED')
        result = subprocess.run([str(executable), '-XshowSettings:properties', '-version'],
                                env=selected_environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=True)
        if re.findall(r'^\s*java\.specification\.version\s*=\s*(\S+)\s*$', result.stderr, re.MULTILINE) != ['1.8']:
            raise ValueError('FORGE1165_MCP_JAVA8_REQUIRED')
        paths.append(str(mcp))
    properties = bounded_file(PROJECT / ROOT / 'gradle/wrapper/gradle-wrapper.properties', 8192)
    lines = properties.splitlines()
    if lines.count(('distributionSha256Sum=' + GRADLE_SHA256).encode()) != 1 \
            or sum(line.startswith(b'distributionSha256Sum=') for line in lines) != 1 \
            or lines.count(DISTRIBUTION) != 1 or sum(line.startswith(b'distributionUrl=') for line in lines) != 1:
        raise ValueError('FORGE1165_GRADLE_DISTRIBUTION_IDENTITY')
    local = environment.get('P2WLAN_FORGE1165_GRADLE_ZIP')
    if local:
        archive = Path(local)
        if not archive.is_absolute() or archive.is_symlink() or not archive.is_file() \
                or archive.stat().st_size > 512 * 1024 * 1024:
            raise ValueError('FORGE1165_GRADLE_DISTRIBUTION_INVALID')
        digest = hashlib.sha256()
        with archive.open('rb') as stream:
            while chunk := stream.read(65536): digest.update(chunk)
        if digest.hexdigest() != GRADLE_SHA256:
            raise ValueError('FORGE1165_GRADLE_DISTRIBUTION_IDENTITY')
        properties = b'\n'.join(b'distributionUrl=' + archive.as_uri().encode() if line.startswith(b'distributionUrl=') else line
                                for line in lines) + b'\n'
    directory = PROJECT / ROOT / ('build/bootstrap-local' if local else 'build/bootstrap-official') / 'gradle/wrapper'
    directory.mkdir(parents=True, exist_ok=True)
    publish(directory / 'gradle-wrapper.jar', bounded_file(PROJECT / 'java/gradle/wrapper/gradle-wrapper.jar', 128 * 1024))
    publish(directory / 'gradle-wrapper.properties', properties)
    return [str(java), '-jar', str(directory / 'gradle-wrapper.jar'),
            '-Porg.gradle.java.installations.paths=' + ','.join(paths),
            '-Porg.gradle.java.installations.auto-download=false']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=ACTIONS)
    args, extra = parser.parse_known_args()
    if any(not argument.startswith('-') for argument in extra):
        parser.error('FORGE1165_ONLY_SDK_OPTIONS')
    try:
        selected = command(dict(os.environ))
        return subprocess.call([*selected, '--no-daemon', *ACTIONS[args.action], *extra], cwd=PROJECT / ROOT)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())

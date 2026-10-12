"""Pinned Forge Gradle 9 bootstrap; portable/Fabric/NeoForge stay on Gradle 8."""
import hashlib
import os
from pathlib import Path
import shutil
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
GRADLE_SHA256 = 'b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06'


def bounded_file(path, maximum):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ValueError('FORGE_BOOTSTRAP_INPUT_INVALID')
    with path.open('rb') as stream:
        value = stream.read(maximum + 1)
    if len(value) > maximum:
        raise ValueError('FORGE_BOOTSTRAP_INPUT_INVALID')
    return value


def publish(path, value):
    """Create-only, byte-identical reuse; never replace another running wrapper."""
    if path.exists() or path.is_symlink():
        if bounded_file(path, len(value)) != value:
            raise ValueError('FORGE_BOOTSTRAP_INPUT_CHANGED')
        return
    descriptor, temporary = tempfile.mkstemp(prefix='wrapper-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(value)
        try:
            os.link(temporary, path)
        except FileExistsError:
            if bounded_file(path, len(value)) != value:
                raise ValueError('FORGE_BOOTSTRAP_INPUT_CHANGED')
    finally:
        Path(temporary).unlink()


def command(environment):
    properties = bounded_file(PROJECT / 'java/forge/gradle/wrapper/gradle-wrapper.properties', 8192)
    if properties.splitlines().count(('distributionSha256Sum=' + GRADLE_SHA256).encode()) != 1 \
            or sum(line.startswith(b'distributionSha256Sum=') for line in properties.splitlines()) != 1:
        raise ValueError('FORGE_GRADLE_DISTRIBUTION_IDENTITY')
    local = environment.get('P2WLAN_FORGE_GRADLE_ZIP')
    if local:
        archive = Path(local)
        if not archive.is_absolute() or archive.is_symlink() or not archive.is_file() or archive.stat().st_size > 512 * 1024 * 1024:
            raise ValueError('FORGE_GRADLE_DISTRIBUTION_INVALID')
        digest = hashlib.sha256()
        with archive.open('rb') as stream:
            while chunk := stream.read(65536):
                digest.update(chunk)
        if digest.hexdigest() != GRADLE_SHA256:
            raise ValueError('FORGE_GRADLE_DISTRIBUTION_IDENTITY')
        properties = b'\n'.join(b'distributionUrl=' + archive.as_uri().encode() if line.startswith(b'distributionUrl=') else line
                                for line in properties.splitlines()) + b'\n'
    # Exactly two bootstrap slots. A changed local input fails rather than
    # replacing files used by active JVMs or accumulating old launchers.
    directory = PROJECT / ('java/forge/build/bootstrap-local' if local else 'java/forge/build/bootstrap-official') / 'gradle/wrapper'
    directory.mkdir(parents=True, exist_ok=True)
    jar = bounded_file(PROJECT / 'java/gradle/wrapper/gradle-wrapper.jar', 128 * 1024)
    publish(directory / 'gradle-wrapper.jar', jar)
    publish(directory / 'gradle-wrapper.properties', properties)
    java = str(Path(environment['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if environment.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise ValueError('FORGE_JAVA_REQUIRED')
    selected = [java, '-jar', str(directory / 'gradle-wrapper.jar')]
    helpers = [environment[name] for name in ('P2WLAN_FORGE_JAVA25_HOME', 'P2WLAN_FORGE_JAVA8_HOME', 'P2WLAN_LEGACY_JAVA_HOME') if environment.get(name)]
    if helpers:
        homes = [environment.get('JAVA_HOME', ''), *helpers]
        if any(not home or not Path(home).is_absolute() or not Path(home).is_dir() for home in homes):
            raise ValueError('FORGE_JAVA_TOOLCHAIN_CONFIG')
        selected.append('-Dorg.gradle.java.installations.paths=' + ','.join(homes))
    return selected

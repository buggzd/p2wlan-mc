#!/usr/bin/env python3
"""Build checked loader inputs and assemble one Minecraft Universal archive."""
import argparse
import importlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from artifact_checks import VerificationError
from native import PROJECT
import minecraft_profiles as registry


def commands(environment, offline=False, development=False, build_root=None):
    sources = registry.sdk_inputs()
    options = ["--no-daemon"]
    if offline:
        options.append("--offline")
    if development:
        options.append("-PdevelopmentNative")
    wrapper = PROJECT / "java" / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    selected = [
        (PROJECT / "java", [str(wrapper), *options, ":universal-bootstrap:check", ":universal-bootstrap:jar"]),
    ]
    caches = ['standard-cache']
    if ('1.16.5', 'forge') in registry.PROFILES:
        selected.append((PROJECT / 'java/legacy-bootstrap', [str(wrapper), *options, ':jar']))
        caches.append('legacy-bootstrap-cache')
    for source in sources.values():
        launcher = importlib.import_module(source.wrapper_module).command(environment) if source.wrapper_module else [str(wrapper)]
        arguments = [*launcher, *options]
        if source.selector is not None:
            arguments.append(source.selector)
        selected.append((PROJECT / source.directory, [*arguments, f':{source.project}:verifyModJar']))
        caches.append(source.cache_directory)
    if build_root is not None:
        for (_, arguments), cache in zip(selected, caches):
            arguments += ["-I", str(build_root / "outputs.gradle"), "-Dp2wlan.universal.outputRoot=" + str(build_root),
                          "--project-cache-dir", str(build_root / cache)]
    return selected


def prepare_inputs(directory):
    from native_bundle import OUTPUT, PLATFORMS, executable_name
    from universal_jar import regular_bytes
    aliases = ''.join(f"    if (name == '{source.project}' && rootProject.name == '{source.root_project}') name = '{source.output_directory}'\n"
                      for source in registry.sdk_inputs().values() if source.project != source.output_directory)
    if ('1.16.5', 'forge') in registry.PROFILES:
        aliases += "    if (name == 'p2wlan-legacy-bootstrap') name = 'legacy-bootstrap'\n"
    (directory / "outputs.gradle").write_text(
        "allprojects {\n"
        "    def name = project.name\n"
        + aliases +
        "    layout.buildDirectory.set(new File(System.getProperty('p2wlan.universal.outputRoot'), name))\n"
        "}\n",
        encoding="utf-8")
    count = 0
    for platform in sorted(PLATFORMS):
        source = OUTPUT / "p2wlan/native" / platform
        if not source.exists():
            continue
        if source.is_symlink() or not source.is_dir():
            raise VerificationError("universal_native_input_directory")
        destination = directory / "native-bundle/generatedResources/p2wlan/native" / platform
        destination.mkdir(parents=True)
        for name in ("manifest.properties", executable_name(platform)):
            (destination / name).write_bytes(regular_bytes(source / name))
        count += 1
    if count == 0:
        raise VerificationError("universal_native_input_missing")


def build(output, environment, offline=False, development=False):
    from universal_jar import build_inputs, publish, regular_bytes, verify
    parent = PROJECT / "java/universal-bootstrap/build"
    parent.mkdir(parents=True, exist_ok=True)
    # A fresh output tree excludes deleted classes without touching test worlds,
    # existing archives or the verified original native resource tree.
    with tempfile.TemporaryDirectory(prefix="inputs-", dir=parent) as workspace:
        inputs = Path(workspace)
        prepare_inputs(inputs)
        for directory, arguments in commands(environment, offline, development, inputs):
            subprocess.run(arguments, cwd=directory, env=environment, check=True, timeout=1200)
        sources = {role: regular_bytes(inputs / source.output_directory / 'libs' / source.archive_name('0.1.0'))
                   for role, source in registry.sdk_inputs().items()}
        bootstrap = inputs / "universal-bootstrap/libs/p2wlan-universal-bootstrap-0.1.0.jar"
        carrier = {'legacy_bootstrap': regular_bytes(inputs / 'legacy-bootstrap/libs/p2wlan-legacy-bootstrap-0.1.0.jar')} \
            if ('1.16.5', 'forge') in registry.PROFILES else {}
        value = build_inputs(sources, regular_bytes(bootstrap), development, **carrier)
    # The relocated input stays in memory. Verified publication creates a new
    # artifact or reuses identical bytes without changing an existing result.
    import io
    metadata = verify(io.BytesIO(value), development)
    output.parent.mkdir(parents=True, exist_ok=True)
    publish(output, value)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--development", action="store_true")
    parser.add_argument("--output", type=Path, default=PROJECT / "java/universal-bootstrap/build/distributions/p2wlan-universal.jar")
    args = parser.parse_args()
    environment = os.environ.copy()
    if sys.platform == "darwin":
        environment["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True, timeout=5).strip()
    try:
        metadata = build(args.output, environment, args.offline, args.development)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Universal build failed: {str(error) if isinstance(error, VerificationError) else 'UNIVERSAL_BUILD_IO'}\n")
    import json
    print("PASS Universal archive structure/source binding")
    print(json.dumps(metadata, sort_keys=True))


if __name__ == "__main__":
    main()

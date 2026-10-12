#!/usr/bin/env python3
"""Build checked loader inputs and assemble one Minecraft Universal archive."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from artifact_checks import VerificationError
from class_relocation import relocate_fabric
from native import PROJECT


def commands(environment, offline=False, development=False, build_root=None):
    from forge_gradle import command
    from legacy_gradle import command as legacy_command
    options = ["--no-daemon"]
    if offline:
        options.append("--offline")
    if development:
        options.append("-PdevelopmentNative")
    wrapper = PROJECT / "java" / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    selected = [
        (PROJECT / "java", [str(wrapper), *options, ":universal-bootstrap:check", ":universal-bootstrap:jar"]),
        (PROJECT / "java", [str(wrapper), *options, "-PfabricAdapter", ":fabric:verifyModJar"]),
        (PROJECT / "java", [str(wrapper), *options, "-PneoforgeAdapter", ":neoforge:verifyModJar"]),
        (PROJECT / "java/forge", [*command(environment), *options, ":forge:verifyModJar"]),
        (PROJECT / "java/forge-legacy", [*legacy_command(environment), *options, ":forge:verifyModJar"]),
        (PROJECT / "java", [str(wrapper), *options, "-PfabricLegacyAdapter", ":fabric-legacy:verifyModJar"]),
    ]
    if build_root is not None:
        for directory, arguments in selected:
            cache = 'legacy-forge-cache' if directory.name == 'forge-legacy' else 'forge-cache' if directory.name == 'forge' else 'standard-cache'
            arguments += ["-I", str(build_root / "outputs.gradle"), "-Dp2wlan.universal.outputRoot=" + str(build_root),
                          "--project-cache-dir", str(build_root / cache)]
    return selected


def prepare_inputs(directory):
    from native_bundle import OUTPUT, PLATFORMS, executable_name
    from universal_jar import regular_bytes
    (directory / "outputs.gradle").write_text(
        "allprojects {\n"
        "    def name = project.name\n"
        "    if (name == 'forge' && rootProject.name == 'p2wlan-minecraft-forge-legacy') name = 'forge-legacy'\n"
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
    from universal_jar import build_bytes, publish, regular_bytes, verify
    parent = PROJECT / "java/universal-bootstrap/build"
    parent.mkdir(parents=True, exist_ok=True)
    # A fresh output tree excludes deleted classes without touching test worlds,
    # existing archives or the verified original native resource tree.
    with tempfile.TemporaryDirectory(prefix="inputs-", dir=parent) as workspace:
        inputs = Path(workspace)
        prepare_inputs(inputs)
        for directory, arguments in commands(environment, offline, development, inputs):
            subprocess.run(arguments, cwd=directory, env=environment, check=True, timeout=1200)
        fabric = inputs / "fabric/libs/p2wlan-fabric-1.21.1-0.1.0.jar"
        neoforge = inputs / "neoforge/libs/p2wlan-neoforge-1.21.1-0.1.0.jar"
        forge = inputs / "forge/libs/p2wlan-forge-1.21.1-0.1.0.jar"
        legacy_forge = inputs / "forge-legacy/libs/p2wlan-forge-1.20.1-0.1.0.jar"
        legacy_fabric = inputs / "fabric-legacy/libs/p2wlan-fabric-1.20.1-0.1.0.jar"
        bootstrap = inputs / "universal-bootstrap/libs/p2wlan-universal-bootstrap-0.1.0.jar"
        relocated = relocate_fabric(fabric, development)
        value = build_bytes(regular_bytes(fabric), regular_bytes(neoforge), regular_bytes(forge), relocated,
                            regular_bytes(bootstrap), development, legacy_forge=regular_bytes(legacy_forge), legacy_fabric=regular_bytes(legacy_fabric))
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

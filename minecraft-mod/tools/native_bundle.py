#!/usr/bin/env python3
"""Build, verify and package a source-bound native agent as ignored Java resources."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib

from native import PROJECT, build_command, prepare
from native_execution import select, check_method, check_windows_pe, ExecutionError

ROOT = PROJECT.parent
OUTPUT = PROJECT / "java/native-bundle/build/generatedResources"
MAX_BINARY = 128 * 1024 * 1024
UPSTREAM = ("daemon", "tun", "crypto", "wireguard", "nat", "netbind", "relay")
INPUT_SUFFIXES = {".rs", ".toml", ".lock", ".py", ".ps1"}
PLATFORMS = {"macos-aarch64", "macos-x86_64", "linux-aarch64", "linux-x86_64", "windows-x86_64"}


class BundleError(ValueError):
    pass


def source_digest(root=ROOT):
    """Hash names and exact input bytes, including new uncommitted source files.

    Include whole upstream dependency source trees; never hash ignored generated
    copies in target, which are outputs rather than authority for the build.
    """
    mod = root / "minecraft-mod"
    inputs = {root / "Cargo.toml", mod / "native/Cargo.toml", mod / "native/Cargo.lock"}
    trees = [root / "client" / name for name in UPSTREAM]
    trees += [mod / "native" / name for name in ("bridge", "runtime-port", "protocol", "gateway", "agent", "private-windows", "runtime-overlay")]
    for tree in trees:
        inputs.update(path for path in tree.rglob("*") if path.suffix in INPUT_SUFFIXES)
    inputs.update(path for path in (mod / "tools").glob("*.py"))
    digest = hashlib.sha256()
    for path in sorted(inputs):
        if path.is_symlink() or not path.is_file():
            raise BundleError("native_input_not_regular")
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(relative).to_bytes(8, "big") + relative)
        digest.update(len(content).to_bytes(8, "big") + content)
    return digest.hexdigest()


def build_environment(environment, root, home):
    env = environment.copy()
    encoded = env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    flags = ([] if encoded == "" else encoded.split("\x1f")) if encoded is not None else env.get("RUSTFLAGS", "").split()
    env.pop("RUSTFLAGS", None)
    # Prefixes are compile-only input. Neither usernames nor local source paths
    # may become public error locations/string literals in the release asset.
    # Rust uses the last matching prefix; the specific repo rule follows home.
    flags += [f"--remap-path-prefix={home}=build-home", f"--remap-path-prefix={root}=p2wlan-source"]
    env["CARGO_ENCODED_RUSTFLAGS"] = "\x1f".join(flags)
    env.pop("CARGO_BUILD_TARGET", None)
    return env


def check_private_paths(binary, prefixes):
    needles = [str(prefix).encode("utf-8") for prefix in prefixes]
    tail = b""
    with binary.open("rb") as stream:
        while block := stream.read(32768):
            content = tail + block
            if any(needle in content for needle in needles):
                raise BundleError("native_binary_private_build_path")
            tail = content[-max(map(len, needles)):]


def host_platform():
    system = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}.get(platform.system())
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64", "AMD64": "x86_64"}.get(platform.machine())
    name = f"{system}-{arch}"
    if name not in PLATFORMS:
        raise BundleError("native_platform_not_supported")
    return system, arch


def executable_name(platform_name):
    if platform_name not in PLATFORMS:
        raise BundleError("native_platform_not_supported")
    return "p2wlan-mc-agent.exe" if platform_name.startswith("windows-") else "p2wlan-mc-agent"


def select_agent(output):
    candidates = []
    for line in output.splitlines():
        item = json.loads(line)
        if item.get("reason") == "compiler-message":
            rendered = item.get("message", {}).get("rendered")
            if rendered:
                print(rendered, end="", file=sys.stderr)
        if item.get("reason") == "compiler-artifact" and item.get("executable"):
            if item.get("target", {}).get("name") != "p2wlan-mc-agent":
                raise BundleError("native_artifact_unexpected")
            candidates.append(Path(item["executable"]))
    if len(candidates) != 1 or not candidates[0].is_absolute():
        raise BundleError("native_artifact_ambiguous")
    return candidates[0]


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BundleError("native_build_info_duplicate")
        result[key] = value
    return result


def check_info(raw, expected):
    if len(raw) > 4096:
        raise BundleError("native_build_info_size")
    actual = json.loads(raw, object_pairs_hook=strict_object)
    if actual != expected or any(type(actual.get(key)) is not type(value) for key, value in expected.items()):
        raise BundleError("native_build_info_mismatch")


def manifest(info, binary, verification="native-build-info"):
    check_method(info["os"] + "-" + info["arch"], verification)
    if binary.is_symlink() or not binary.is_file():
        raise BundleError("native_binary_not_regular")
    size = binary.stat().st_size
    if not 0 < size <= MAX_BINARY:
        raise BundleError("native_binary_size")
    with binary.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    fields = {
        "schema": "2", "protocol": str(info["protocol"]), "version": info["version"],
        "source-revision": info["source_revision"], "source-sha256": info["source_sha256"],
        "build-kind": info["build_kind"], "os": info["os"], "arch": info["arch"],
        "executable-sha256": checksum, "executable-size": str(size),
        "verification-platform": f'{info["os"]}-{info["arch"]}',
        "verification-method": verification,
    }
    if any(not re.fullmatch(r"[a-z0-9._-]+", value) for value in fields.values()):
        raise BundleError("native_manifest_value")
    return "".join(f"{key}={value}\n" for key, value in fields.items()).encode("ascii")


def publish(binary, metadata, destination):
    if destination.is_symlink() or destination.exists() and not destination.is_dir():
        raise BundleError("native_output_not_directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="bundle-", dir=destination.parent))
    backup = destination.with_name(destination.name + ".previous")
    try:
        fields = dict(line.split("=", 1) for line in metadata.decode("ascii").splitlines())
        copied = temporary / executable_name(fields["os"] + "-" + fields["arch"])
        shutil.copyfile(binary, copied)
        with copied.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        if str(copied.stat().st_size) != fields.get("executable-size") or checksum != fields.get("executable-sha256"):
            raise BundleError("native_copy_mismatch")
        (temporary / "manifest.properties").write_bytes(metadata)
        if backup.exists() or backup.is_symlink():
            raise BundleError("native_output_previous_exists")
        if destination.exists():
            destination.rename(backup)
        try:
            temporary.rename(destination)
        except OSError:
            if backup.exists():
                backup.rename(destination)
            raise
        if backup.exists():
            shutil.rmtree(backup)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def verified_info(execution, binary):
    if not execution.wine:
        return execution.run_info(binary, None)
    # Capacity one: a previous uncertain Wine cleanup refuses another prefix
    # rather than accumulating directories/background services across retries.
    prefix = PROJECT / "native/target/wine-build-info-prefix"
    try:
        prefix.mkdir(mode=0o700)
    except FileExistsError as error:
        raise BundleError("native_wine_prefix_capacity") from error
    identity = prefix.lstat()
    uncertain = False
    try:
        return execution.run_info(binary, prefix)
    except ExecutionError as error:
        uncertain = error.code == "native_wine_cleanup_failed"
        raise
    finally:
        if not uncertain:
            actual = prefix.lstat()
            if prefix.is_symlink() or (actual.st_dev, actual.st_ino) != (identity.st_dev, identity.st_ino):
                raise BundleError("native_wine_prefix_identity_changed")
            # Only this create-new, tool-owned generated tree after confirmed
            # process stop. Never the user's profile or an uncertain instance.
            shutil.rmtree(prefix)

def build(development=False, offline=False, target=None, wine=None):
    execution = select("-".join(host_platform()), target, wine)
    system, arch = execution.platform.split("-", 1)
    status = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT)
    if status and not development:
        raise BundleError("native_release_requires_clean_checkout")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    digest = source_digest()
    version = tomllib.loads((PROJECT / "native/Cargo.toml").read_text())["workspace"]["package"]["version"]
    expected = {"schema": 1, "protocol": 1, "version": version, "source_revision": revision,
                "source_sha256": digest, "build_kind": "development" if development else "release", "os": system, "arch": arch}
    env = execution.build_environment(build_environment(os.environ, ROOT, Path.home()))
    env.update(P2WLAN_MC_SOURCE_REVISION=revision, P2WLAN_MC_SOURCE_SHA256=digest,
               P2WLAN_MC_BUILD_KIND=expected["build_kind"])
    prepare()
    arguments = ["-p", "p2wlan-mc-agent", "--bin", "p2wlan-mc-agent", "--release", "--locked", "--message-format=json"]
    if execution.cargo_target:
        arguments.extend(["--target", execution.cargo_target])
    if offline:
        arguments.append("--offline")
    result = subprocess.run(build_command("build", arguments), cwd=PROJECT, env=env, text=True, stdout=subprocess.PIPE, timeout=1800)
    if result.returncode:
        raise BundleError("native_build_failed")
    binary = select_agent(result.stdout)
    if binary.is_symlink() or not binary.is_file() or not 0 < binary.stat().st_size <= MAX_BINARY:
        raise BundleError("native_binary_invalid")
    check_private_paths(binary, (ROOT, Path.home()))
    if system == "windows":
        check_windows_pe(binary)
    before = manifest(expected, binary, execution.method)
    check_info(verified_info(execution, binary), expected)
    if manifest(expected, binary, execution.method) != before:
        raise BundleError("native_binary_changed_during_verification")
    if source_digest() != digest or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != revision:
        raise BundleError("native_inputs_changed_during_build")
    if not development and subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT):
        raise BundleError("native_release_checkout_changed")
    destination = OUTPUT / "p2wlan/native" / f"{system}-{arch}"
    publish(binary, before, destination)
    print(f"Native resource verified: {system}-{arch} ({expected['build_kind']}; {execution.method})")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--development", action="store_true", help="Label a package as development; permit uncommitted source")
    parser.add_argument("--offline", action="store_true", help="Use only the local Cargo dependency cache")
    parser.add_argument("--target", choices=sorted(PLATFORMS), help="Explicit target; foreign Windows requires a Wine runner")
    parser.add_argument("--wine", type=Path, help="Absolute trusted Wine executable path for Windows x86-64 build-info execution")
    args = parser.parse_args()
    lock = PROJECT / "native/target/native-bundle.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    try:
        with lock.open("x"):
            try:
                build(args.development, args.offline, args.target, args.wine)
            finally:
                lock.unlink()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        # Never print local paths or child logs as bundle metadata.
        parser.exit(1, f"Native bundle failed: {type(error).__name__}: {error.args[0] if isinstance(error, (BundleError, ExecutionError)) else 'BUILD_IO'}\n")


if __name__ == "__main__":
    main()

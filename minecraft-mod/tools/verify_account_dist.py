#!/usr/bin/env python3
"""Check the game-independent account console distribution and matching zip."""
import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path
import zipfile
from artifact_checks import MAX_BINARY, VerificationError, bind_sources, classfile, entries, native_manifest, read, PLATFORMS, executable_name

VERSION = "0.1.0"
MAIN = "io/p2wlan/mc/account/AccountTool"
COMMON = "io/p2wlan/mc/common/BundledAgent.class"
LIBRARIES = {f"account-cli-{VERSION}.jar", f"common-{VERSION}.jar", f"native-bundle-{VERSION}.jar", "gson-2.10.1.jar"}
PREFIXES = (b"net/minecraft/", b"net/fabricmc/", b"net/neoforged/", b"net/minecraftforge/")


def regular(path, maximum=MAX_BINARY):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise VerificationError("account_distribution_file")


def verify(directory, development=False):
    if directory.is_symlink() or not directory.is_dir() or {path.name for path in directory.iterdir()} != {"bin", "lib"}:
        raise VerificationError("account_distribution_layout")
    for name in ("bin", "lib"):
        if (directory / name).is_symlink() or not (directory / name).is_dir():
            raise VerificationError("account_distribution_layout")
    if {path.name for path in (directory / "lib").iterdir()} != LIBRARIES \
            or {path.name for path in (directory / "bin").iterdir()} != {"p2wlan-account", "p2wlan-account.bat"}:
        raise VerificationError("account_distribution_layout")
    for filename in LIBRARIES:
        path = directory / "lib" / filename
        regular(path)
        with zipfile.ZipFile(path) as archive:
            names = entries(archive)
            if filename in {f"account-cli-{VERSION}.jar", f"common-{VERSION}.jar"}:
                expected = MAIN + ".class" if filename.startswith("account-cli-") else COMMON
                if expected not in names or any("Tests.class" in name or "/fixture/" in name for name in names):
                    raise VerificationError("account_distribution_classes")
                for name in names:
                    if name.endswith(".class") and any(value in classfile(archive, name, 61) for value in PREFIXES):
                        raise VerificationError("account_distribution_game_dependency")
            if filename.startswith("native-bundle-"):
                manifests = sorted(name for name in names if name.startswith("p2wlan/native/") and name.endswith("/manifest.properties"))
                natives = [native_manifest(archive, name, VERSION, development) for name in manifests]
                admitted = set(manifests) | {f"p2wlan/native/{item['verification-platform']}/{executable_name(item['verification-platform'])}" for item in natives}
                if not 1 <= len(natives) <= len(PLATFORMS) or len({item["verification-platform"] for item in natives}) != len(natives) \
                        or any(name.startswith("p2wlan/native/") and not name.endswith("/") and name not in admitted for name in names):
                    raise VerificationError("account_distribution_native")
    for name in ("p2wlan-account", "p2wlan-account.bat"):
        path = directory / "bin" / name
        regular(path, 65536)
        content = path.read_bytes()
        if MAIN.replace("/", ".").encode() not in content or any(library.encode() not in content for library in LIBRARIES):
            raise VerificationError("account_distribution_launcher")
        if name == "p2wlan-account" and os.name == "posix" and not os.access(path, os.X_OK):
            raise VerificationError("account_distribution_launcher")
    return natives


def checksum(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_zip(path, directory):
    regular(path)
    root = f"p2wlan-account-{VERSION}/"
    files = {path.relative_to(directory).as_posix(): path for path in directory.rglob("*") if path.is_file()}
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        if any(item.external_attr >> 16 & 0o170000 not in {0, 0o100000, 0o040000} for item in archive.infolist()):
            raise VerificationError("account_distribution_zip_file")
        if any(not name.startswith(root) for name in names) \
                or {name.removeprefix(root) for name in names if not name.endswith("/")} != set(files):
            raise VerificationError("account_distribution_zip_layout")
        for relative, installed in files.items():
            item = archive.getinfo(root + relative)
            if item.file_size != installed.stat().st_size or relative == "bin/p2wlan-account" and not item.external_attr >> 16 & 0o111:
                raise VerificationError("account_distribution_zip_file")
            with archive.open(item) as source, installed.open("rb") as target:
                if checksum(source) != checksum(target):
                    raise VerificationError("account_distribution_zip_checksum")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        natives = verify(args.directory, args.development)
        verify_zip(args.zip, args.directory)
        bind_sources(natives, args.development)
        with args.zip.open("rb") as stream:
            digest = checksum(stream)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, "Account distribution failed: " + (str(error) if isinstance(error, VerificationError) else "ACCOUNT_ARCHIVE_IO") + "\n")
    print("PASS account distribution structure/zip/source binding")
    print(json.dumps({"schema": 1, "artifact_sha256": digest, "source_revision": natives[0]["source-revision"],
                      "version": VERSION, "build_mode": "development" if args.development else "release",
                      "verification": "archive-structure/source-binding",
                      "native_targets": [item["verification-platform"] for item in natives]}, sort_keys=True))


if __name__ == "__main__":
    main()

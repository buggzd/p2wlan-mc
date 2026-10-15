#!/usr/bin/env python3
"""Collect a verified Universal JAR, HMCL packs, and checksums in one directory."""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import urllib.request
import zipfile

import hmcl_pack as pack
import minecraft_profiles as registry
from native import PROJECT


def api_bytes(selected, cache, download=False):
    name = f"fabric-api-{selected.api_version}.jar"
    path = cache / name
    if path.exists() or path.is_symlink():
        value = pack.regular_bytes(path, pack.MAX_API)
    else:
        if not download:
            raise pack.VerificationError("distribution_fabric_api_missing")
        # The registry owns both the exact Maven version and SHA-256. Downloads
        # have one bounded attempt; incomplete/unverified bytes are never cached.
        url = selected.api.artifact_url or f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{selected.api_version}/{name}"
        with urllib.request.urlopen(url, timeout=60) as response:
            value = response.read(pack.MAX_API + 1)
        pack.verify_api(value, "fabric", selected.minecraft)
        cache.mkdir(parents=True, exist_ok=True)
        pack.publish(path, value)
    pack.verify_api(value, "fabric", selected.minecraft)
    return value


def record(path, value):
    return {"file": path.name, "size": len(value), "sha256": pack.checksum(value)}


def build(mod, output, platforms, api_cache, download=False):
    if not platforms or len(set(platforms)) != len(platforms) or set(platforms) - pack.PLATFORMS:
        raise pack.VerificationError("distribution_platform_selection")
    value = pack.regular_bytes(mod, pack.MAX_UNIVERSAL_MOD)
    info = pack.verify_universal(value)
    if set(platforms) - set(info["native_targets"]):
        raise pack.VerificationError("distribution_native_target_missing")
    # Snapshot and validate dependencies before publishing any distribution file.
    apis = {selected.minecraft: api_bytes(selected, api_cache, download)
            for selected in registry.PROFILES.values() if selected.api is not None}
    if output.is_symlink() or output.exists() and not output.is_dir():
        raise pack.VerificationError("distribution_output_directory")
    admitted = {"p2wlan-universal.jar", "BUILD-METADATA.json", "SHA256SUMS"}
    admitted.update(f"p2wlan-hmcl-{minecraft}-{loader}-{platform}.zip"
                    for minecraft, loader in registry.PROFILES for platform in platforms)
    if output.exists() and any(path.name not in admitted for path in output.iterdir()):
        raise pack.VerificationError("distribution_output_contains_unexpected_files")
    output.mkdir(parents=True, exist_ok=True)
    jar = output / "p2wlan-universal.jar"
    pack.publish(jar, value)
    files = [record(jar, value)]
    for minecraft, loader in sorted(registry.PROFILES):
        for platform in sorted(platforms):
            payload = pack.build_bytes(value, apis.get(minecraft) if loader == "fabric" else None,
                                       platform, loader, minecraft, True)
            path = output / f"p2wlan-hmcl-{minecraft}-{loader}-{platform}.zip"
            pack.publish(path, payload)
            files.append(record(path, payload))
    # Recheck the current source binding after the final ZIP before publishing
    # metadata; a source edit cannot receive a complete distribution manifest.
    if pack.verify_universal(value) != info:
        raise pack.VerificationError("distribution_source_changed")
    metadata = {"schema": 1, "mod_version": pack.VERSION,
                "source_revision": info["source_revision"], "native_source_sha256": info["native_source_sha256"],
                "native_targets": info["native_targets"], "native_verification_methods": info["native_verification_methods"],
                "minecraft": info["minecraft"], "loaders": info["loaders"],
                "hmcl_platforms": sorted(platforms), "files": sorted(files, key=lambda item: item["file"])}
    manifest = pack.encoded(metadata)
    path = output / "BUILD-METADATA.json"
    pack.publish(path, manifest)
    files.append(record(path, manifest))
    checksums = "".join(f"{item['sha256']}  {item['file']}\n" for item in sorted(files, key=lambda item: item["file"]))
    pack.publish(output / "SHA256SUMS", checksums.encode("ascii"))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mod", type=Path, required=True, help="Verified release Universal JAR")
    parser.add_argument("--output", type=Path, default=PROJECT / "dist")
    parser.add_argument("--platform", action="append", choices=sorted(pack.PLATFORMS),
                        help="HMCL pack target; repeat for multiple targets (default: windows-x86_64)")
    parser.add_argument("--fabric-api-cache", type=Path, default=PROJECT / "java/build/fabric-api")
    parser.add_argument("--download-fabric-api", action="store_true", help="Fetch missing pinned Fabric API artifacts from their official Maven/Modrinth locations")
    args = parser.parse_args()
    try:
        info = build(args.mod, args.output, args.platform or ["windows-x86_64"],
                     args.fabric_api_cache, args.download_fabric_api)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, "Distribution failed: " + (str(error) if isinstance(error, pack.VerificationError) else "DISTRIBUTION_IO") + "\n")
    print(f"PASS distribution: {len(info['files'])} artifacts; HMCL targets: {', '.join(info['hmcl_platforms'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

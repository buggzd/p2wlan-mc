#!/usr/bin/env python3
"""Verify Fabric archive structure and its exact source-bound native resources."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

from artifact_checks import MAX_BINARY, VerificationError, entries, read, classfile, metadata, native_manifest, bind_sources, PLATFORMS, executable_name, jna_payloads

MAIN = "io/p2wlan/mc/fabric/FabricP2wlan.class"
CLIENT = "io/p2wlan/mc/fabric/client/FabricP2wlanClient.class"
COMMON = "io/p2wlan/mc/common/BundledAgent.class"
SERVER_MIXIN = "io/p2wlan/mc/fabric/mixin/ServerNetworkIoMixin.class"
MIXIN_CONFIG = {"required": True, "package": "io.p2wlan.mc.fabric.mixin", "compatibilityLevel": "JAVA_21",
                "mixins": ["ServerNetworkIoMixin"], "injectors": {"defaultRequire": 1},
                "refmap": "p2wlan-fabric-1.21.1-refmap.json"}
BIND_MAPPING = {"io/p2wlan/mc/fabric/mixin/ServerNetworkIoMixin": {"bind": "Lnet/minecraft/class_3242;method_14354(Ljava/net/InetAddress;I)V"}}
REFMAP = {"mappings": BIND_MAPPING, "data": {"named:intermediary": BIND_MAPPING}}
CLIENT_MIXIN = "io/p2wlan/mc/fabric/mixin/ClientConnectionMixin.class"
INTEGRATED_MIXIN = "io/p2wlan/mc/fabric/mixin/IntegratedServerMixin.class"
CLIENT_MIXIN_CONFIG = {"required": True, "package": "io.p2wlan.mc.fabric.mixin", "compatibilityLevel": "JAVA_21",
                       "client": ["ClientConnectionMixin", "IntegratedServerMixin"], "injectors": {"defaultRequire": 1},
                       "refmap": "client-p2wlan-fabric-1.21.1-refmap.json"}
CONNECT_MAPPING = {"io/p2wlan/mc/fabric/mixin/ClientConnectionMixin": {
    "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;":
    "Lnet/minecraft/class_2535;method_52271(Ljava/net/InetSocketAddress;ZLnet/minecraft/class_2535;)Lio/netty/channel/ChannelFuture;"}}
INTEGRATED_MAPPING = {"io/p2wlan/mc/fabric/mixin/IntegratedServerMixin": {"isRemote": "Lnet/minecraft/class_1132;method_3860()Z"}}
CLIENT_MAPPING = CONNECT_MAPPING | INTEGRATED_MAPPING
CLIENT_REFMAP = {"mappings": CLIENT_MAPPING, "data": {"named:intermediary": CLIENT_MAPPING}}
MIXINS = ["p2wlan.mixins.json", {"config": "p2wlan.client.mixins.json", "environment": "client"}]

def verify(path, development=False):
    with zipfile.ZipFile(path) as archive:
        entries(archive)
        if any(name.startswith("io/p2wlan/mc/fabric/fixture/") for name in archive.namelist()):
            raise VerificationError("mod_test_fixture_present")
        info = metadata(archive)
        depends = info.get("depends")
        if type(info.get("schemaVersion")) is not int or info.get("schemaVersion") != 1 or not isinstance(depends, dict) or info.get("id") != "p2wlan" or info.get("version") != "0.1.0" \
                or info.get("environment") != "*" or info.get("entrypoints") != {
                    "main": [MAIN.removesuffix(".class").replace("/", ".")],
                    "client": [CLIENT.removesuffix(".class").replace("/", ".")]} \
                or depends.get("minecraft") != "1.21.1" or depends.get("java") != ">=21" or info.get("mixins") != MIXINS:
            raise VerificationError("mod_fabric_contract")
        if metadata(archive, "p2wlan.mixins.json") != MIXIN_CONFIG:
            raise VerificationError("mod_mixin_contract")
        if metadata(archive, MIXIN_CONFIG["refmap"]) != REFMAP:
            raise VerificationError("mod_mixin_refmap")
        if metadata(archive, "p2wlan.client.mixins.json") != CLIENT_MIXIN_CONFIG:
            raise VerificationError("mod_client_mixin_contract")
        if metadata(archive, CLIENT_MIXIN_CONFIG["refmap"]) != CLIENT_REFMAP:
            raise VerificationError("mod_client_mixin_refmap")
        classfile(archive, CLIENT_MIXIN, 65)
        classfile(archive, INTEGRATED_MIXIN, 65)
        server_mixin = classfile(archive, SERVER_MIXIN, 65)
        if b"net/minecraft/client/" in server_mixin or b"io/p2wlan/mc/fabric/client/" in server_mixin:
            raise VerificationError("mod_dedicated_client_reference")
        main = classfile(archive, MAIN, 65)
        classfile(archive, CLIENT, 65)
        if any(value in main for value in (b"net/minecraft/client/", b"net/fabricmc/fabric/api/client/", b"io/p2wlan/mc/fabric/client/")):
            raise VerificationError("mod_dedicated_client_reference")
        children = info.get("jars")
        if not isinstance(children, list) or not 1 <= len(children) <= 8:
            raise VerificationError("mod_nested_resources_missing")
        common_count = 0
        jna_count = 0
        natives = []
        child_names = set()
        for item in children:
            if not isinstance(item, dict) or set(item) != {"file"} or not isinstance(item["file"], str) \
                    or not item["file"].startswith("META-INF/jars/") or item["file"] in child_names:
                raise VerificationError("mod_nested_resource_invalid")
            child_names.add(item["file"])
            with zipfile.ZipFile(io.BytesIO(read(archive, item["file"], MAX_BINARY))) as child:
                names = entries(child)
                if "com/sun/jna/Native.class" in names:
                    jna_payloads(child)
                    jna_count += 1
                if COMMON in names:
                    common_count += 1
                    for name in names:
                        if name.endswith(".class"):
                            value = classfile(child, name, 61)
                            if any(prefix in value for prefix in (b"net/minecraft/", b"net/fabricmc/", b"net/neoforged/", b"net/minecraftforge/")):
                                raise VerificationError("mod_common_game_dependency")
                manifests = sorted(name for name in names if name.startswith("p2wlan/native/") and name.endswith("/manifest.properties"))
                resources = [native_manifest(child, name, info["version"], development) for name in manifests]
                admitted = set(manifests) | {f"p2wlan/native/{item['verification-platform']}/{executable_name(item['verification-platform'])}" for item in resources}
                if any(name.startswith("p2wlan/native/") and not name.endswith("/") and name not in admitted for name in names):
                    raise VerificationError("mod_native_resource_unexpected")
                natives.extend(resources)
        platforms = [item["verification-platform"] for item in natives]
        if jna_count != 1:
            raise VerificationError("mod_jna_resources_ambiguous")
        if common_count != 1 or not natives or len(platforms) > len(PLATFORMS) or len(set(platforms)) != len(platforms):
            raise VerificationError("mod_native_resources_ambiguous")
        return natives


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        natives = verify(args.archive, args.development)
        bind_sources(natives, args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"Mod archive failed: {str(error) if isinstance(error, VerificationError) else 'MOD_ARCHIVE_IO'}\n")
    with args.archive.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    print("PASS Fabric artifact structure/source binding: " + ", ".join(item["verification-platform"] for item in natives))
    # Public release metadata, not device/game acceptance or a signature. No
    # local archive path, username, host identity or runtime account is emitted.
    print(json.dumps({"schema": 1, "artifact_sha256": checksum, "source_revision": natives[0]["source-revision"],
                      "version": natives[0]["version"], "minecraft": "1.21.1",
                      "build_mode": "development" if args.development else "release",
                      "verification": "archive-structure/source-binding",
                      "native_targets": [item["verification-platform"] for item in natives]}, sort_keys=True))


if __name__ == "__main__":
    main()

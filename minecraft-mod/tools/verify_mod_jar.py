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
from fabric_artifact import COMMON, TRANSPORT, portable
WORLD_COMMANDS = "io/p2wlan/mc/game/server/WorldCommands.class"
SERVER_MIXIN = "io/p2wlan/mc/game/server/mixin/ServerConnectionMixin.class"
MIXIN_CONFIG = {"required": True, "package": "io.p2wlan.mc.game.server.mixin", "compatibilityLevel": "JAVA_17",
                "mixins": ["ServerConnectionMixin"], "injectors": {"defaultRequire": 1},
                "refmap": "p2wlan-fabric-1.21.1-refmap.json"}
BIND_MAPPING = {"io/p2wlan/mc/game/server/mixin/ServerConnectionMixin": {"startTcpServerListener": "Lnet/minecraft/class_3242;method_14354(Ljava/net/InetAddress;I)V"}}
REFMAP = {"mappings": BIND_MAPPING, "data": {"named:intermediary": BIND_MAPPING}}
CLIENT_MIXIN = "io/p2wlan/mc/game/mixin/ClientConnectionMixin.class"
INTEGRATED_MIXIN = "io/p2wlan/mc/game/mixin/IntegratedServerMixin.class"
CLIENT_MIXIN_CONFIG = {"required": True, "package": "io.p2wlan.mc.game.mixin", "compatibilityLevel": "JAVA_17",
                       "client": ["ClientConnectionMixin", "IntegratedServerMixin", "MultiplayerListMixin", "MultiplayerListAccessor", "MultiplayerScreenMixin"], "injectors": {"defaultRequire": 1},
                       "refmap": "client-p2wlan-fabric-1.21.1-refmap.json"}
CONNECT_MAPPING = {"io/p2wlan/mc/game/mixin/ClientConnectionMixin": {
    "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;":
    "Lnet/minecraft/class_2535;method_52271(Ljava/net/InetSocketAddress;ZLnet/minecraft/class_2535;)Lio/netty/channel/ChannelFuture;"}}
INTEGRATED_MAPPING = {"io/p2wlan/mc/game/mixin/IntegratedServerMixin": {"isPublished": "Lnet/minecraft/class_1132;method_3860()Z"}}
LAN_MAPPING = {
    "io/p2wlan/mc/game/mixin/MultiplayerListMixin": {"refreshEntries": "Lnet/minecraft/class_4267;method_20131()V"},
    "io/p2wlan/mc/game/mixin/MultiplayerListAccessor": {"refreshEntries": "method_20131()V"},
    "io/p2wlan/mc/game/mixin/MultiplayerScreenMixin": {"joinSelectedServer()V": "Lnet/minecraft/class_500;method_2536()V", "tick": "Lnet/minecraft/class_500;method_25393()V"},
}
LAN_MIXINS = tuple(name + ".class" for name in LAN_MAPPING)
CLIENT_MAPPING = CONNECT_MAPPING | INTEGRATED_MAPPING | LAN_MAPPING
CLIENT_REFMAP = {"mappings": CLIENT_MAPPING, "data": {"named:intermediary": CLIENT_MAPPING}}
MIXINS = ["p2wlan.mixins.json", {"config": "p2wlan.client.mixins.json", "environment": "client"}]

def verify(path, development=False):
    from verify_fabric_legacy_jar import LOGIN_MIXIN
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        if LOGIN_MIXIN in names:
            raise VerificationError("mod_unselected_legacy_fabric_mixin")
        if any("/fixture/" in name for name in archive.namelist()):
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
        classfile(archive, CLIENT_MIXIN, 61)
        classfile(archive, INTEGRATED_MIXIN, 61)
        for name in LAN_MIXINS:
            classfile(archive, name, 61)
        commands = classfile(archive, WORLD_COMMANDS, 61)
        if any(prefix in commands for prefix in (b"net/minecraft/client/", b"io/p2wlan/mc/game/client/")):
            raise VerificationError("mod_dedicated_client_reference")
        server_mixin = classfile(archive, SERVER_MIXIN, 61)
        if b"net/minecraft/client/" in server_mixin or any(prefix in server_mixin for prefix in (b"io/p2wlan/mc/fabric/client/", b"io/p2wlan/mc/game/client/")):
            raise VerificationError("mod_dedicated_client_reference")
        main = classfile(archive, MAIN, 61)
        classfile(archive, CLIENT, 61)
        if any(value in main for value in (b"net/minecraft/client/", b"net/fabricmc/fabric/api/client/", b"io/p2wlan/mc/fabric/client/", b"io/p2wlan/mc/game/client/")):
            raise VerificationError("mod_dedicated_client_reference")
        return portable(archive, info, development)

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
                      "native_targets": [item["verification-platform"] for item in natives],
                      "native_verification_methods": [{"platform": item["verification-platform"], "method": item["verification-method"]} for item in natives]}, sort_keys=True))


if __name__ == "__main__":
    main()

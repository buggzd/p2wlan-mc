#!/usr/bin/env python3
"""Verify the Java 17 Fabric 1.20.1 input and source-bound shared resources."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile

from artifact_checks import MAX_BINARY, VerificationError, bind_sources, classfile, entries, metadata, read
from fabric_artifact import COMMON, TRANSPORT, portable
from forge_gradle import bounded_file
from jarjar_artifact import WORLD_COMMON

MAIN = "io/p2wlan/mc/fabric/FabricP2wlan.class"
CLIENT = "io/p2wlan/mc/fabric/client/FabricP2wlanClient.class"
WORLD = "io/p2wlan/mc/fabric/world/FabricWorlds.class"
SERVER = "io/p2wlan/mc/fabric/legacy/FabricServerEvents.class"
CLIENT_EVENTS = "io/p2wlan/mc/fabric/legacy/FabricClientEvents.class"
LOGIN_MIXIN = "io/p2wlan/mc/game/mixin/FabricLoginNetworkingMixin.class"
WORLD_COMMANDS = "io/p2wlan/mc/game/server/WorldCommands.class"
SERVER_MIXIN = "io/p2wlan/mc/game/server/mixin/ServerConnectionMixin.class"
MODERN_SERVER_MIXIN = "io/p2wlan/mc/game/server/mixin/ServerConfigurationMixin.class"
SERVER_MIXINS = tuple("io/p2wlan/mc/game/server/mixin/" + name + ".class" for name in
                      ("ServerConnectionMixin", "ServerLoginConnectionMixin", "ServerLoginMixin"))
SERVER_REFMAP = "p2wlan-fabric-1.20.1-refmap.json"
CLIENT_REFMAP = "client-p2wlan-fabric-1.20.1-refmap.json"
MIXINS = ["p2wlan.mixins.json", {"config": "p2wlan.client.mixins.json", "environment": "client"}]
DEPENDS = {"fabricloader": "=0.16.14", "minecraft": "1.20.1", "java": ">=17", "fabric-api": "=0.92.12+1.20.1"}

# Exact bindings produced by the pinned Minecraft 1.20.1 Loom annotation processor.
MIXIN_CONFIG = {'compatibilityLevel': 'JAVA_17',
 'injectors': {'defaultRequire': 1},
 'mixins': ['ServerConnectionMixin', 'ServerLoginConnectionMixin', 'ServerLoginMixin'],
 'package': 'io.p2wlan.mc.game.server.mixin',
 'refmap': 'p2wlan-fabric-1.20.1-refmap.json',
 'required': True}
CLIENT_MIXIN_CONFIG = {'client': ['ClientConnectionMixin',
            'IntegratedServerMixin',
            'MultiplayerListMixin',
            'MultiplayerListAccessor',
            'MultiplayerScreenMixin',
            'FabricLoginNetworkingMixin'],
 'compatibilityLevel': 'JAVA_17',
 'injectors': {'defaultRequire': 1},
 'package': 'io.p2wlan.mc.game.mixin',
 'refmap': 'client-p2wlan-fabric-1.20.1-refmap.json',
 'required': True}
SERVER_MAPPING = {
    'io/p2wlan/mc/game/server/mixin/ServerConnectionMixin': {'startTcpServerListener': 'Lnet/minecraft/class_3242;method_14354(Ljava/net/InetAddress;I)V'},
    'io/p2wlan/mc/game/server/mixin/ServerLoginConnectionMixin': {'channel': 'field_11651:Lio/netty/channel/Channel;'},
    'io/p2wlan/mc/game/server/mixin/ServerLoginMixin': {
        'handleHello': 'Lnet/minecraft/class_3248;method_12641(Lnet/minecraft/class_2915;)V',
        'handleAcceptedLogin': 'Lnet/minecraft/class_3248;method_14384()V',
        'placeNewPlayer': 'Lnet/minecraft/class_3248;method_33800(Lnet/minecraft/class_3222;)V',
        'Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z': 'Lnet/minecraft/server/MinecraftServer;method_3828()Z'},
}
REFMAP = {'mappings': SERVER_MAPPING, 'data': {'named:intermediary': SERVER_MAPPING}}
CLIENT_MAPPING = {'io/p2wlan/mc/game/mixin/ClientConnectionMixin': {'connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;': 'Lnet/minecraft/class_2535;method_52271(Ljava/net/InetSocketAddress;ZLnet/minecraft/class_2535;)Lio/netty/channel/ChannelFuture;'},
 'io/p2wlan/mc/game/mixin/IntegratedServerMixin': {'isPublished': 'Lnet/minecraft/class_1132;method_3860()Z'},
 'io/p2wlan/mc/game/mixin/MultiplayerListAccessor': {'refreshEntries': 'method_20131()V'},
 'io/p2wlan/mc/game/mixin/MultiplayerListMixin': {'refreshEntries': 'Lnet/minecraft/class_4267;method_20131()V'},
 'io/p2wlan/mc/game/mixin/MultiplayerScreenMixin': {'joinSelectedServer()V': 'Lnet/minecraft/class_500;method_2536()V',
                                                    'tick': 'Lnet/minecraft/class_500;method_25393()V'}}
CLIENT_REFMAP_VALUE = {'data': {'named:intermediary': {'io/p2wlan/mc/game/mixin/ClientConnectionMixin': {'connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;': 'Lnet/minecraft/class_2535;method_52271(Ljava/net/InetSocketAddress;ZLnet/minecraft/class_2535;)Lio/netty/channel/ChannelFuture;'},
                                 'io/p2wlan/mc/game/mixin/IntegratedServerMixin': {'isPublished': 'Lnet/minecraft/class_1132;method_3860()Z'},
                                 'io/p2wlan/mc/game/mixin/MultiplayerListAccessor': {'refreshEntries': 'method_20131()V'},
                                 'io/p2wlan/mc/game/mixin/MultiplayerListMixin': {'refreshEntries': 'Lnet/minecraft/class_4267;method_20131()V'},
                                 'io/p2wlan/mc/game/mixin/MultiplayerScreenMixin': {'joinSelectedServer()V': 'Lnet/minecraft/class_500;method_2536()V',
                                                                                    'tick': 'Lnet/minecraft/class_500;method_25393()V'}}},
 'mappings': {'io/p2wlan/mc/game/mixin/ClientConnectionMixin': {'connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;': 'Lnet/minecraft/class_2535;method_52271(Ljava/net/InetSocketAddress;ZLnet/minecraft/class_2535;)Lio/netty/channel/ChannelFuture;'},
              'io/p2wlan/mc/game/mixin/IntegratedServerMixin': {'isPublished': 'Lnet/minecraft/class_1132;method_3860()Z'},
              'io/p2wlan/mc/game/mixin/MultiplayerListAccessor': {'refreshEntries': 'method_20131()V'},
              'io/p2wlan/mc/game/mixin/MultiplayerListMixin': {'refreshEntries': 'Lnet/minecraft/class_4267;method_20131()V'},
              'io/p2wlan/mc/game/mixin/MultiplayerScreenMixin': {'joinSelectedServer()V': 'Lnet/minecraft/class_500;method_2536()V',
                                                                 'tick': 'Lnet/minecraft/class_500;method_25393()V'}}}


def same_json(value, expected):
    # Keep JSON booleans/floats distinct from integer schema and capacity fields.
    return json.dumps(value, sort_keys=True) == json.dumps(expected, sort_keys=True)


def verify(path, development=False):
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        info = metadata(archive)
        if type(info.get("schemaVersion")) is not int or info.get("schemaVersion") != 1 \
                or info.get("id") != "p2wlan" or info.get("version") != "0.1.0" or info.get("environment") != "*" \
                or not same_json(info.get("depends"), DEPENDS) or not same_json(info.get("mixins"), MIXINS) \
                or info.get("entrypoints") != {"main": [MAIN[:-6].replace("/", ".")], "client": [CLIENT[:-6].replace("/", ".")]}:
            raise VerificationError("legacy_fabric_mod_contract")
        for config, expected, mapping, bindings in [
                ("p2wlan.mixins.json", MIXIN_CONFIG, SERVER_REFMAP, REFMAP),
                ("p2wlan.client.mixins.json", CLIENT_MIXIN_CONFIG, CLIENT_REFMAP, CLIENT_REFMAP_VALUE)]:
            if not same_json(metadata(archive, config), expected):
                raise VerificationError("legacy_fabric_mixin_contract")
            if not same_json(metadata(archive, mapping), bindings):
                raise VerificationError("legacy_fabric_mixin_refmap")
        dedicated = (MAIN, WORLD, SERVER, WORLD_COMMANDS, *SERVER_MIXINS,
                     MAIN[:-6] + "$ServerEvents.class", MAIN[:-6] + "$Binding.class")
        required = (*dedicated, CLIENT, CLIENT_EVENTS, LOGIN_MIXIN, WORLD[:-6] + "$IntegratedHooks.class",
                    "io/p2wlan/mc/bootstrap/UniversalEnvironment.class", "io/p2wlan/mc/bootstrap/BootstrapFailure.class",
                    "io/p2wlan/mc/game/client/ClientPlatform.class", "io/p2wlan/mc/game/client/GameUi.class",
                    "io/p2wlan/mc/game/client/connect/GameLogin.class", *(name + ".class" for name in CLIENT_MAPPING))
        for name in required:
            value = classfile(archive, name, 61)
            if name in dedicated and any(prefix in value for prefix in (b"net/minecraft/client/", b"net/fabricmc/fabric/api/client/",
                                                                        b"io/p2wlan/mc/fabric/client/", b"io/p2wlan/mc/game/client/")):
                raise VerificationError("mod_dedicated_client_reference")
        for name in names:
            if name == MODERN_SERVER_MIXIN:
                raise VerificationError("legacy_fabric_class_owner")
            if "/fixture/" in name or name.endswith("Tests.class"):
                raise VerificationError("mod_test_fixture_present")
            if name.startswith(("META-INF/versions/", "com/sun/jna/", "io/p2wlan/mc/common/", "io/p2wlan/mc/transport/", "p2wlan/native/",
                                "io/p2wlan/mc/fabric/current/")):
                raise VerificationError("legacy_fabric_class_owner")
            if name.endswith(".class"):
                classfile(archive, name, 61)
                if not name.startswith(("io/p2wlan/mc/fabric/", "io/p2wlan/mc/game/", "io/p2wlan/mc/bootstrap/")):
                    raise VerificationError("legacy_fabric_class_owner")
        if b"(Lnet/minecraft/class_310;IIIII)V" not in classfile(
                archive, "io/p2wlan/mc/game/mixin/MultiplayerListMixin.class", 61):
            raise VerificationError("legacy_fabric_list_constructor")
        natives = portable(archive, info, development)
        children = {item["file"] for item in info["jars"]}
        if len(children) != 4 or children != {name for name in names if name.startswith("META-INF/jars/") and name.endswith(".jar")}:
            raise VerificationError("legacy_fabric_unlisted_nested_resource")
        for name in children:
            with zipfile.ZipFile(io.BytesIO(read(archive, name, MAX_BINARY))) as child:
                members = entries(child)
                classes = {item for item in members if item.endswith(".class")}
                common = COMMON in members
                transport = TRANSPORT[0] in members
                jna = "com/sun/jna/Native.class" in members
                native = any(item.startswith("p2wlan/native/") and item.endswith("/manifest.properties") for item in members)
                if sum((common, transport, jna, native)) != 1:
                    raise VerificationError("legacy_fabric_nested_class_owner")
                if native and classes or common and any(not item.startswith("io/p2wlan/mc/common/") for item in classes) \
                        or transport and any(not item.startswith("io/p2wlan/mc/transport/") for item in classes):
                    raise VerificationError("legacy_fabric_nested_class_owner")
                if common:
                    for world in WORLD_COMMON:
                        classfile(child, world, 61)
        return natives


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("verify")
    check.add_argument("archive", type=Path)
    check.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        value = bounded_file(args.archive, MAX_BINARY)
        natives = verify(io.BytesIO(value), args.development)
        bind_sources(natives, args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, (str(error) if isinstance(error, VerificationError) else "LEGACY_FABRIC_ARCHIVE_IO") + "\n")
    print("PASS Fabric 1.20.1/0.16.14 input: Java 17, exact Mixin bindings and shared resource/source policy")
    print(json.dumps({"schema": 1, "minecraft": "1.20.1", "artifact_sha256": hashlib.sha256(value).hexdigest(),
                      "source_revision": natives[0]["source-revision"], "native_source_sha256": natives[0]["source-sha256"],
                      "verification": "archive-structure/source-binding"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

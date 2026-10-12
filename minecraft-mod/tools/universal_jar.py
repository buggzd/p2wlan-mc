#!/usr/bin/env python3
"""Assemble/check one source-bound three-loader Minecraft 1.21.1 archive."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import zipfile

from artifact_checks import (MAX_BINARY, VerificationError, bind_sources, classfile,
                             entries, jna_payloads, metadata, native_manifest, read)
import verify_mod_jar as fabric_contract
import verify_neoforge_jar as neo_contract
import verify_forge_jar as forge_contract

MINECRAFT = "1.21.1"
VERSION = "0.1.0"
MAX_ARCHIVE = MAX_BINARY + 16 * 1024 * 1024
GAME = "io/p2wlan/mc/game/"
FABRIC_GAME = "io/p2wlan/mc/fabric/game/"
BOOTSTRAP = "io/p2wlan/mc/bootstrap/"
GAME_COUNT = 43
INDEX = "META-INF/p2wlan/universal.json"
SERVER_CONFIG = "p2wlan.dispatch.json"
CLIENT_CONFIG = "p2wlan.dispatch.client.json"
FABRIC_SERVER_CONFIG = "p2wlan.dispatch.fabric.json"
FABRIC_CLIENT_CONFIG = "p2wlan.dispatch.fabric.client.json"
SERVER_REFMAP = "p2wlan-universal-fabric-server-refmap.json"
CLIENT_REFMAP = "p2wlan-universal-fabric-client-refmap.json"
JNA = "META-INF/jars/jna.jar"
CORE_PATHS = {"common": "META-INF/jarjar/common.jar", "transport": "META-INF/jarjar/transport.jar",
              "native": "META-INF/jarjar/native.jar"}
CORE_IDS = {"common": "p2wlan_common", "transport": "p2wlan_transport", "native": "p2wlan_native"}
RESOURCES = {"fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "pack.mcmeta",
             "META-INF/MANIFEST.MF", "META-INF/jarjar/metadata.json", SERVER_CONFIG, CLIENT_CONFIG,
             FABRIC_SERVER_CONFIG, FABRIC_CLIENT_CONFIG, SERVER_REFMAP, CLIENT_REFMAP, *CORE_PATHS.values(), JNA}
PINS = {"fabric": "0.16.14", "neoforge": "21.1.256", "forge": "52.1.16"}
CONFIG_BASE = {"required": True, "minVersion": "0.8.7",
               "compatibilityLevel": "JAVA_21", "mixins": [], "injectors": {"defaultRequire": 1}}
CONFIGS = {SERVER_CONFIG: CONFIG_BASE | {"package": "io.p2wlan.mc.game.server.mixin", "plugin": "io.p2wlan.mc.bootstrap.ServerMixinPlugin"},
           CLIENT_CONFIG: CONFIG_BASE | {"package": "io.p2wlan.mc.game.mixin", "plugin": "io.p2wlan.mc.bootstrap.ClientMixinPlugin"},
           FABRIC_SERVER_CONFIG: CONFIG_BASE | {"package": "io.p2wlan.mc.fabric.game.server.mixin", "plugin": "io.p2wlan.mc.bootstrap.ServerMixinPlugin"},
           FABRIC_CLIENT_CONFIG: CONFIG_BASE | {"package": "io.p2wlan.mc.fabric.game.mixin", "plugin": "io.p2wlan.mc.bootstrap.ClientMixinPlugin"}}
PLUGIN_CLASSES = {value["plugin"].replace(".", "/") + ".class" for value in CONFIGS.values()}
LOADER_CLASSES = {
    "fabric": frozenset(("io/p2wlan/mc/fabric/FabricP2wlan.class",
                          "io/p2wlan/mc/fabric/client/FabricP2wlanClient.class",
                          "io/p2wlan/mc/fabric/world/FabricWorlds.class",
                          "io/p2wlan/mc/fabric/world/FabricWorlds$IntegratedHooks.class")),
    "neoforge": frozenset(("io/p2wlan/mc/neoforge/LocalNativeResources.class",
                            "io/p2wlan/mc/neoforge/NeoForgeP2wlan.class",
                            "io/p2wlan/mc/neoforge/client/NeoForgeClient.class",
                            "io/p2wlan/mc/neoforge/client/NeoForgeClient$Events.class")),
    "forge": frozenset(("io/p2wlan/mc/forge/LocalNativeResources.class",
                         "io/p2wlan/mc/forge/ForgeP2wlan.class",
                         "io/p2wlan/mc/forge/client/ForgeClient.class",
                         "io/p2wlan/mc/forge/client/ForgeClient$Events.class")),
}
BOOTSTRAP_CLASSES = frozenset(BOOTSTRAP + name + ".class" for name in (
    "BootstrapFailure", "BootstrapFailure$Code", "ServerMixinPlugin", "ClientMixinPlugin", "ClientMixinPlugin$1",
    "UniversalEnvironment", "UniversalEnvironment$Loader", "UniversalEnvironment$Family", "UniversalEnvironment$Side",
    "UniversalEnvironment$Selection", "UniversalEnvironment$ResourceLookup", "UniversalEnvironment$1"))
NATIVE_FIELDS = {"source-revision", "source-sha256", "build-kind", "version"}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def members(value):
    if not isinstance(value, bytes) or not 0 < len(value) <= MAX_ARCHIVE:
        raise VerificationError("universal_archive_size")
    with zipfile.ZipFile(io.BytesIO(value)) as archive:
        names = entries(archive)
        if sum(item.file_size for item in archive.infolist()) > MAX_ARCHIVE:
            raise VerificationError("universal_archive_size")
        if any(item.flag_bits & 1 or item.external_attr >> 16 & 0o170000 not in {0, stat.S_IFREG, stat.S_IFDIR}
               for item in archive.infolist()):
            raise VerificationError("universal_archive_file_type")
        return {name: read(archive, name, MAX_BINARY) for name in names if not name.endswith("/")}


def archive_bytes(files):
    if len(files) > 512 or sum(map(len, files.values())) > MAX_ARCHIVE:
        raise VerificationError("universal_archive_size")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, value in sorted(files.items()):
            item = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            item.create_system = 3
            item.external_attr = (stat.S_IFREG | 0o644) << 16
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, value)
    value = output.getvalue()
    if len(value) > MAX_ARCHIVE:
        raise VerificationError("universal_archive_size")
    return value


def json_value(value):
    from native_bundle import strict_object
    return json.loads(value, object_pairs_hook=strict_object)


def changed_name(name):
    return name.replace(GAME, FABRIC_GAME).replace(GAME.replace("/", "."), FABRIC_GAME.replace("/", "."))


def changed_keys(value):
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            name = changed_name(key)
            if name in result:
                raise VerificationError("universal_relocation_key_collision")
            result[name] = changed_keys(item)
        return result
    if isinstance(value, list):
        return [changed_keys(item) for item in value]
    return value


def expected_relocation(source):
    from class_relocation import relocate_class
    result = {}
    refmaps = {fabric_contract.MIXIN_CONFIG["refmap"]: SERVER_REFMAP,
               fabric_contract.CLIENT_MIXIN_CONFIG["refmap"]: CLIENT_REFMAP}
    for name, value in source.items():
        target = changed_name(name) if name.endswith(".class") else refmaps.get(name, name)
        if target in result:
            raise VerificationError("universal_relocation_collision")
        if name.endswith(".class"):
            value = relocate_class(value)
        elif name in ("p2wlan.mixins.json", "p2wlan.client.mixins.json"):
            config = json_value(value)
            config["package"] = changed_name(config["package"] + ".").rstrip(".")
            config["refmap"] = refmaps[config["refmap"]]
            value = encoded(config)
        elif name in refmaps:
            value = encoded(changed_keys(json_value(value)))
        result[target] = value
    return result


def match_relocation(source, relocated):
    expected = expected_relocation(source)
    if set(expected) != set(relocated):
        raise VerificationError("universal_relocation_layout")
    json_names = {"p2wlan.mixins.json", "p2wlan.client.mixins.json", SERVER_REFMAP, CLIENT_REFMAP}
    for name, value in expected.items():
        actual = encoded(json_value(relocated[name])) if name in json_names else relocated[name]
        if actual != value:
            raise VerificationError("universal_relocation_changed")
    return expected


def classes(files, prefix):
    return {name: value for name, value in files.items() if name.startswith(prefix) and name.endswith(".class")}


def reject_test(name):
    base = name.rsplit("/", 1)[-1].split("$", 1)[0]
    if "fixture" in name.lower() or base.endswith(("Tests.class", "Test.class", "Tests", "Test")) \
            or name.startswith("net/minecraftforge/launcher/p2wlan/"):
        raise VerificationError("universal_test_payload")


def loader_classes(files, loader):
    prefix = f"io/p2wlan/mc/{loader}/"
    result = {}
    for name, value in files.items():
        reject_test(name)
        if name.endswith(".class"):
            if not name.startswith((prefix, GAME)):
                raise VerificationError("universal_source_class_owner")
            if name.startswith(prefix):
                if name not in LOADER_CLASSES[loader]:
                    raise VerificationError("universal_loader_class_catalog")
                result[name] = value
    if set(result) != LOADER_CLASSES[loader]:
        raise VerificationError("universal_loader_class_catalog")
    return result


def components(files, loader):
    if loader == "fabric":
        paths = [item["file"] for item in json_value(files["fabric.mod.json"])["jars"]]
    else:
        paths = [item["path"] for item in json_value(files["META-INF/jarjar/metadata.json"])["jars"]]
    result = {}
    for path in paths:
        body = members(files[path])
        component = "common" if fabric_contract.COMMON in body else "transport" if fabric_contract.TRANSPORT[0] in body \
            else "jna" if "com/sun/jna/Native.class" in body else "native" if any(name.startswith("p2wlan/native/") for name in body) else None
        if component is None or component in result:
            raise VerificationError("universal_component_ambiguous")
        result[component] = (files[path], body)
    expected = {"common", "transport", "native", "jna"} if loader == "fabric" else {"common", "transport", "native"}
    if set(result) != expected:
        raise VerificationError("universal_component_missing")
    return result


def compare_components(all_components):
    for component in ("common", "transport"):
        expected = classes(all_components["forge"][component][1], "")
        if any(classes(values[component][1], "") != expected for values in all_components.values()):
            raise VerificationError("universal_core_changed")
    expected = {name: value for name, value in all_components["forge"]["native"][1].items() if name.startswith("p2wlan/native/")}
    if any({name: value for name, value in values["native"][1].items() if name.startswith("p2wlan/native/")} != expected
           for values in all_components.values()):
        raise VerificationError("universal_native_changed")


def nested_descriptor(component):
    return {"schemaVersion": 1, "id": CORE_IDS[component], "version": VERSION,
            "name": "P2WLAN " + component, "environment": "*"}


def fabric_descriptor(source):
    value = json_value(source["fabric.mod.json"])
    value["mixins"] = [FABRIC_SERVER_CONFIG, {"config": FABRIC_CLIENT_CONFIG, "environment": "client"}]
    value["jars"] = [{"file": name} for name in (*CORE_PATHS.values(), JNA)]
    return value


def neo_descriptor(value):
    text = value.decode("utf-8")
    for before, after in (("p2wlan.neoforge.mixins.json", SERVER_CONFIG), ("p2wlan.neoforge.client.mixins.json", CLIENT_CONFIG)):
        if text.count('config="' + before + '"') != 1:
            raise VerificationError("universal_neoforge_mixin_contract")
        text = text.replace('config="' + before + '"', 'config="' + after + '"')
    return text.encode("utf-8")


def records(files):
    return [{"path": name, "size": len(value), "sha256": digest(value)} for name, value in sorted(files.items())]


def native_summary(natives):
    if not natives or any({key: item[key] for key in NATIVE_FIELDS} != {key: natives[0][key] for key in NATIVE_FIELDS} for item in natives):
        raise VerificationError("universal_native_identity")
    return {"source_revision": natives[0]["source-revision"], "native_source_sha256": natives[0]["source-sha256"],
            "native_targets": sorted(item["verification-platform"] for item in natives),
            "native_verification_methods": [{"platform": item["verification-platform"], "method": item["verification-method"]}
                                            for item in sorted(natives, key=lambda item: item["verification-platform"])]}


def build_bytes(fabric, neoforge, forge, relocated_fabric, bootstrap, development=False):
    inputs = {"fabric": fabric, "neoforge": neoforge, "forge": forge,
              "relocated_fabric": relocated_fabric, "bootstrap": bootstrap}
    source = {}
    verifiers = {"fabric": fabric_contract.verify, "neoforge": neo_contract.verify, "forge": forge_contract.verify}
    native_sets = []
    for loader, verifier in verifiers.items():
        natives = verifier(io.BytesIO(inputs[loader]), development)
        bind_sources(natives, development)
        native_sets.append(natives)
        source[loader] = members(inputs[loader])
        loader_classes(source[loader], loader)
    if any(value != native_sets[0] for value in native_sets[1:]):
        raise VerificationError("universal_native_identity")
    game = classes(source["forge"], GAME)
    if len(game) != GAME_COUNT or classes(source["neoforge"], GAME) != game \
            or set(classes(source["fabric"], GAME)) != set(game):
        raise VerificationError("universal_game_changed")
    relocated = match_relocation(source["fabric"], members(relocated_fabric))
    all_components = {loader: components(files, loader) for loader, files in source.items()}
    compare_components(all_components)
    boot = members(bootstrap)
    if set(boot) - {"META-INF/MANIFEST.MF", *CONFIGS} != BOOTSTRAP_CLASSES:
        raise VerificationError("universal_bootstrap_layout")
    for name, config in CONFIGS.items():
        if name not in boot or encoded(json_value(boot[name])) != encoded(config):
            raise VerificationError("universal_dispatch_contract")
    files = dict(game)
    for loader in ("forge", "neoforge"):
        files.update(loader_classes(source[loader], loader))
    files.update(classes(relocated, "io/p2wlan/mc/fabric/"))
    for name, value in boot.items():
        reject_test(name)
        if name.endswith(".class"):
            with zipfile.ZipFile(io.BytesIO(bootstrap)) as archive: classfile(archive, name, 61)
            if name in files: raise VerificationError("universal_class_collision")
            files[name] = value
    for name, value in CONFIGS.items(): files[name] = encoded(value)
    for name in (SERVER_REFMAP, CLIENT_REFMAP): files[name] = relocated[name]
    files["fabric.mod.json"] = encoded(fabric_descriptor(source["fabric"]))
    files["META-INF/neoforge.mods.toml"] = neo_descriptor(source["neoforge"]["META-INF/neoforge.mods.toml"])
    files["META-INF/mods.toml"] = source["forge"]["META-INF/mods.toml"]
    files["pack.mcmeta"] = source["forge"]["pack.mcmeta"]
    files["META-INF/MANIFEST.MF"] = ("Manifest-Version: 1.0\r\nMixinConfigs: " + SERVER_CONFIG + "," + CLIENT_CONFIG + "\r\n\r\n").encode()
    jarjar = json_value(source["forge"]["META-INF/jarjar/metadata.json"])
    names = {"common": "common", "netty-transport": "transport", "native-bundle": "native"}
    for item in jarjar["jars"]: item["path"] = CORE_PATHS[names[item["identifier"]["artifact"]]]
    files["META-INF/jarjar/metadata.json"] = encoded(jarjar)
    for component, path in CORE_PATHS.items():
        child = dict(all_components["forge"][component][1])
        if "fabric.mod.json" in child: raise VerificationError("universal_core_descriptor_exists")
        child["fabric.mod.json"] = encoded(nested_descriptor(component))
        files[path] = archive_bytes(child)
    files[JNA] = all_components["fabric"]["jna"][0]
    summary = {"schema": 1, "minecraft": MINECRAFT, "version": VERSION, "loaders": PINS,
               "build_mode": "development" if development else "release", **native_summary(native_sets[0]),
               "inputs": {name: {"size": len(value), "sha256": digest(value)} for name, value in sorted(inputs.items())},
               "files": records(files), "verification": "archive-structure/source-binding"}
    files[INDEX] = encoded(summary)
    value = archive_bytes(files)
    verify(io.BytesIO(value), development)
    return value


def verify(source, development=False):
    if isinstance(source, (str, Path)):
        value = regular_bytes(Path(source))
    else:
        source.seek(0)
        value = source.read(MAX_ARCHIVE + 1)
        if not isinstance(value, bytes) or not 0 < len(value) <= MAX_ARCHIVE:
            raise VerificationError("universal_archive_size")
    with zipfile.ZipFile(io.BytesIO(value)) as archive:
        names = entries(archive)
        if INDEX not in names or sum(item.file_size for item in archive.infolist()) > MAX_ARCHIVE:
            raise VerificationError("universal_archive_layout")
        if any(item.is_dir() or item.flag_bits & 1 or item.external_attr >> 16 & 0o170000 not in {0, stat.S_IFREG} for item in archive.infolist()):
            raise VerificationError("universal_archive_file_type")
        info = metadata(archive, INDEX)
        fields = {"schema", "minecraft", "version", "loaders", "build_mode", "source_revision", "native_source_sha256",
                  "native_targets", "native_verification_methods", "inputs", "files", "verification"}
        if set(info) != fields or encoded(info.get("schema")) != encoded(1) or info["minecraft"] != MINECRAFT \
                or info["version"] != VERSION or info["loaders"] != PINS or info["verification"] != "archive-structure/source-binding" \
                or info["build_mode"] != ("development" if development else "release"):
            raise VerificationError("universal_index_contract")
        sources = info["inputs"]
        if not isinstance(sources, dict) or set(sources) != {"fabric", "neoforge", "forge", "relocated_fabric", "bootstrap"}:
            raise VerificationError("universal_input_identity")
        import re
        for identity in sources.values():
            if not isinstance(identity, dict) or set(identity) != {"size", "sha256"} or type(identity["size"]) is not int \
                    or not 0 < identity["size"] <= MAX_ARCHIVE or not isinstance(identity["sha256"], str) \
                    or re.fullmatch("[a-f0-9]{64}", identity["sha256"]) is None:
                raise VerificationError("universal_input_identity")
        files = {name: read(archive, name, MAX_BINARY) for name in names if name != INDEX}
        if {name for name in files if not name.endswith(".class")} != RESOURCES:
            raise VerificationError("universal_archive_layout")
        if encoded(info["files"]) != encoded(records(files)):
            raise VerificationError("universal_payload_changed")
        for name in names: reject_test(name)
        if len(classes(files, GAME)) != GAME_COUNT or len(classes(files, FABRIC_GAME)) != GAME_COUNT:
            raise VerificationError("universal_game_layout")
        for name in names:
            if name.endswith(".class"):
                if not name.startswith((GAME, "io/p2wlan/mc/fabric/", "io/p2wlan/mc/neoforge/", "io/p2wlan/mc/forge/", BOOTSTRAP)):
                    raise VerificationError("universal_class_owner")
                classfile(archive, name, 61 if name.startswith(BOOTSTRAP) else 65)
        non_game = {name for name in names if name.endswith(".class") and not name.startswith((GAME, FABRIC_GAME))}
        admitted = BOOTSTRAP_CLASSES | frozenset().union(*LOADER_CLASSES.values())
        if non_game != admitted:
            raise VerificationError("universal_loader_class_catalog")
        if set(classes(files, BOOTSTRAP)) != BOOTSTRAP_CLASSES: raise VerificationError("universal_bootstrap_layout")
        for name in (fabric_contract.MAIN, fabric_contract.CLIENT, neo_contract.MAIN, neo_contract.CLIENT,
                     forge_contract.MAIN, forge_contract.CLIENT, forge_contract.SERVER_MIXIN,
                     changed_name(fabric_contract.SERVER_MIXIN), *fabric_contract.LAN_MIXINS,
                     *map(changed_name, fabric_contract.LAN_MIXINS), fabric_contract.CLIENT_MIXIN,
                     changed_name(fabric_contract.CLIENT_MIXIN), fabric_contract.INTEGRATED_MIXIN,
                     changed_name(fabric_contract.INTEGRATED_MIXIN)):
            classfile(archive, name, 65)
        for name in (fabric_contract.MAIN, neo_contract.MAIN, forge_contract.MAIN,
                     forge_contract.SERVER_MIXIN, changed_name(fabric_contract.SERVER_MIXIN),
                     fabric_contract.WORLD_COMMANDS, changed_name(fabric_contract.WORLD_COMMANDS)):
            body = classfile(archive, name, 65)
            if any(prefix in body for prefix in (b"net/minecraft/client/", b"io/p2wlan/mc/game/client/",
                                                 b"io/p2wlan/mc/fabric/game/client/", b"io/p2wlan/mc/fabric/client/",
                                                 b"io/p2wlan/mc/neoforge/client/", b"io/p2wlan/mc/forge/client/")):
                raise VerificationError("universal_dedicated_client_reference")
        for name, config in CONFIGS.items():
            if encoded(metadata(archive, name)) != encoded(config): raise VerificationError("universal_dispatch_contract")
        expected_mf = ("Manifest-Version: 1.0\r\nMixinConfigs: " + SERVER_CONFIG + "," + CLIENT_CONFIG + "\r\n\r\n").encode()
        if read(archive, "META-INF/MANIFEST.MF", 8192) != expected_mf: raise VerificationError("universal_mixin_manifest")
        fabric = metadata(archive, "fabric.mod.json")
        if type(fabric.get("schemaVersion")) is not int or fabric.get("schemaVersion") != 1 or fabric.get("environment") != "*" \
                or fabric.get("id") != "p2wlan" or fabric.get("version") != VERSION or fabric.get("depends") != {
                "fabricloader": ">=0.16.14", "minecraft": MINECRAFT, "java": ">=21", "fabric-api": "*"} \
                or fabric.get("entrypoints") != {"main": ["io.p2wlan.mc.fabric.FabricP2wlan"], "client": ["io.p2wlan.mc.fabric.client.FabricP2wlanClient"]} \
                or fabric.get("mixins") != [FABRIC_SERVER_CONFIG, {"config": FABRIC_CLIENT_CONFIG, "environment": "client"}] \
                or fabric.get("jars") != [{"file": name} for name in (*CORE_PATHS.values(), JNA)]:
            raise VerificationError("universal_fabric_contract")
        import tomllib
        neo = tomllib.loads(read(archive, "META-INF/neoforge.mods.toml", 65536).decode())
        if neo.get("mixins") != [{"config": SERVER_CONFIG}, {"config": CLIENT_CONFIG}]: raise VerificationError("universal_neoforge_mixin_contract")
        for loader, config, language in (("neoforge", neo, "[4,)"), ("forge", tomllib.loads(read(archive, "META-INF/mods.toml", 65536).decode()), "[52,)")):
            if config.get("modLoader") != "javafml" or config.get("loaderVersion") != language \
                    or len(config.get("mods", [])) != 1 or config["mods"][0].get("modId") != "p2wlan" or config["mods"][0].get("version") != VERSION:
                raise VerificationError("universal_loader_contract")
            dependencies = config.get("dependencies", {}).get("p2wlan")
            expected = [{"modId": loader, "versionRange": "[" + PINS[loader] + "]", "ordering": "NONE", "side": "BOTH",
                         **({"type": "required"} if loader == "neoforge" else {"mandatory": True})},
                        {"modId": "minecraft", "versionRange": "[1.21.1]", "ordering": "NONE", "side": "BOTH",
                         **({"type": "required"} if loader == "neoforge" else {"mandatory": True})}]
            if encoded(dependencies) != encoded(expected): raise VerificationError("universal_dependency_contract")
        if encoded(metadata(archive, "pack.mcmeta")) != encoded(forge_contract.PACK_METADATA): raise VerificationError("universal_pack_metadata")
        for name, expected in ((SERVER_REFMAP, fabric_contract.REFMAP), (CLIENT_REFMAP, fabric_contract.CLIENT_REFMAP)):
            if encoded(metadata(archive, name)) != encoded(changed_keys(expected)): raise VerificationError("universal_refmap_contract")
        jarjar = metadata(archive, "META-INF/jarjar/metadata.json")
        expected_paths = set(CORE_PATHS.values())
        if {item.get("path") for item in jarjar.get("jars", [])} != expected_paths:
            raise VerificationError("universal_jarjar_contract")
        if {name for name in names if name.endswith(".jar")} != expected_paths | {JNA}:
            raise VerificationError("universal_nested_layout")
        # Existing JarJar policy checks coordinates, one class owner, native
        # module identity and every manifest/executable. Extra Fabric descriptors
        # do not change the single physical package/resource owner.
        natives = forge_contract.portable(archive, VERSION, development, "universal")
        for component, path in CORE_PATHS.items():
            with zipfile.ZipFile(io.BytesIO(read(archive, path, MAX_BINARY))) as child:
                if encoded(metadata(child)) != encoded(nested_descriptor(component)):
                    raise VerificationError("universal_nested_fabric_contract")
                for name in entries(child): reject_test(name)
        with zipfile.ZipFile(io.BytesIO(read(archive, JNA, MAX_BINARY))) as child: jna_payloads(child)
        expected = native_summary(natives)
        if any(encoded(info[key]) != encoded(value) for key, value in expected.items()): raise VerificationError("universal_native_identity")
        bind_sources(natives, development)
        return info | {"artifact_sha256": digest(value)}


def regular_bytes(path):
    initial = path.lstat()
    if not stat.S_ISREG(initial.st_mode) or not 0 < initial.st_size <= MAX_ARCHIVE:
        raise VerificationError("universal_input_file")
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_BINARY", 0))
    with os.fdopen(fd, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or (before.st_dev, before.st_ino) != (initial.st_dev, initial.st_ino):
            raise VerificationError("universal_input_file")
        value = stream.read(MAX_ARCHIVE + 1); after = os.fstat(stream.fileno())
        if len(value) != before.st_size or len(value) > MAX_ARCHIVE or (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise VerificationError("universal_input_changed")
        return value


def publish(output, value):
    fd, temporary = tempfile.mkstemp(prefix=".p2wlan-universal-", suffix=".tmp", dir=output.parent)
    identity = os.fstat(fd)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(value); stream.flush(); os.fsync(stream.fileno())
        actual = os.lstat(temporary)
        if (actual.st_dev, actual.st_ino) != (identity.st_dev, identity.st_ino) or not stat.S_ISREG(actual.st_mode):
            raise VerificationError("universal_staging_changed")
        try: os.link(temporary, output)
        except FileExistsError:
            if regular_bytes(output) != value: raise VerificationError("universal_output_exists")
    finally:
        actual = os.lstat(temporary)
        if (actual.st_dev, actual.st_ino) != (identity.st_dev, identity.st_ino) or not stat.S_ISREG(actual.st_mode):
            raise VerificationError("universal_staging_changed")
        os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    for name in ("fabric", "neoforge", "forge", "relocated-fabric", "bootstrap", "output"):
        build.add_argument("--" + name, type=Path, required=True)
    check = commands.add_parser("verify"); check.add_argument("archive", type=Path)
    for command in (build, check): command.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "build":
            value = build_bytes(*(regular_bytes(getattr(args, name)) for name in ("fabric", "neoforge", "forge", "relocated_fabric", "bootstrap")), args.development)
            publish(args.output, value)
        else: value = regular_bytes(args.archive)
        result = verify(io.BytesIO(value), args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, "Universal archive failed: " + (str(error) if isinstance(error, VerificationError) else "UNIVERSAL_IO") + "\n")
    print("PASS Universal archive structure/source binding: Minecraft 1.21.1 / Fabric 0.16.14 / NeoForge 21.1.256 / Forge 52.1.16")
    print(json.dumps(result | {"artifact_sha256": digest(value)}, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()

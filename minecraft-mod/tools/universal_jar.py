#!/usr/bin/env python3
"""Assemble/check one source-bound archive from exact Minecraft SDK inputs."""
from __future__ import annotations

import argparse
import hashlib
import io
import importlib
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
import legacy_gradle as legacy_forge_contract
from class_relocation import LEGACY, FABRIC_LEGACY, relocate_class, relocate_name
from universal_manifest import mixin_manifest
from minecraft_profiles import MINECRAFT_VERSIONS, PROFILES, SDK_INPUTS, sdk_inputs, forge_profiles, forge_range, fml_range, game_range
from universal_profile_sources import bindings, dispatch_configs
import legacy_core_index
import verify_legacy_bootstrap
import universal_jna

MINECRAFT = list(MINECRAFT_VERSIONS)
VERSION = "0.1.0"
MAX_ARCHIVE = MAX_BINARY + 16 * 1024 * 1024
MAX_INDEX = 128 * 1024
MAX_ENTRIES = 1024
GAME = "io/p2wlan/mc/game/"
FABRIC_GAME = "io/p2wlan/mc/fabric/game/"
BOOTSTRAP = "io/p2wlan/mc/bootstrap/"
GAME_TYPES = ('client/ClientDisplaySettings',
 'client/ClientPlatform',
 'client/ClientPlatform$Binding',
 'client/ClientPlatform$IntegratedHooks',
 'client/LatencyHud',
 'client/connect/GameJoins',
 'client/connect/GameJoins$Attempt',
 'client/connect/GameJoins$Handoff',
 'client/connect/GameJoins$Phase',
 'client/connect/GameJoins$Registration',
 'client/connect/GameJoins$Status',
 'client/connect/MinecraftConnect',
 'client/lan/ClientNetworkView',
 'client/lan/ClientNetworkView$Pending',
 'client/GameUi',
 'client/connect/GameLogin',
 'client/lan/VanillaLanList',
 'client/lan/VanillaLanScreen',
 'client/lan/VirtualLanEntry',
 'client/screen/AccountScreen',
 'client/screen/AgentScreen',
 'client/screen/AgentScreen$Action',
 'client/screen/AgentScreen$Label',
 'client/screen/AgentScreen$ReplyAction',
 'client/screen/AgentScreen$TaskAction',
 'client/screen/DiagnosticsScreen',
 'client/screen/DisplaySettingsScreen',
 'client/screen/DisplaySettingsScreen$1',
 'client/screen/JoinGameScreen',
 'client/screen/LatencyStatsScreen',
 'client/screen/LocalPanelScreen',
 'client/screen/LogoButton',
 'client/screen/MenuLogo',
 'client/screen/PasswordField',
 'client/screen/PixelButton',
 'client/screen/RoomFormScreen',
 'client/screen/RoomsScreen',
 'client/screen/RuntimeScreen',
 'mixin/ClientConnectionMixin',
 'mixin/IntegratedServerMixin',
 'mixin/MultiplayerListAccessor',
 'mixin/MultiplayerListMixin',
 'mixin/MultiplayerScreenMixin',
 'server/WorldCommands',
 'server/GameCommandApi',
 'server/mixin/ServerConnectionMixin',
 'server/mixin/ServerLoginConnectionMixin',
 'server/mixin/ServerLoginMixin')
GAME_CLASSES = frozenset(GAME + name + ".class" for name in GAME_TYPES)
GAME_COUNT = len(GAME_CLASSES)
GAME_1182_EXTRAS = frozenset(GAME + name + '.class' for name in
                            ('GameText', 'client/GameUi$TooltipButton', 'client/connect/MinecraftConnect$1'))
GAME_1165_EXTRAS = GAME_1182_EXTRAS | {GAME + 'mixin/LanNarrationMixin.class', GAME + 'server/mixin/ConsoleSourceMixin.class'}
FORGE_1182_GAME = SDK_INPUTS['forge_1182'].binding.class_prefix
FABRIC_1182_GAME = SDK_INPUTS['fabric_1182'].binding.class_prefix
MODERN_GAME_EXTRAS = frozenset({GAME + 'server/mixin/ServerConfigurationMixin.class'})
from verify_fabric_legacy_jar import LOGIN_MIXIN as LEGACY_FABRIC_LOGIN_MIXIN
LEGACY_FABRIC_GAME_EXTRAS = frozenset({LEGACY_FABRIC_LOGIN_MIXIN})
INDEX = "META-INF/p2wlan/universal.json"
SERVER_CONFIG = "p2wlan.dispatch.json"
CLIENT_CONFIG = "p2wlan.dispatch.client.json"
FABRIC_SERVER_CONFIG = "p2wlan.dispatch.fabric.json"
FABRIC_CLIENT_CONFIG = "p2wlan.dispatch.fabric.client.json"
LEGACY_SERVER_CONFIG = "p2wlan.dispatch.1201.json"
LEGACY_CLIENT_CONFIG = "p2wlan.dispatch.1201.client.json"
LEGACY_FABRIC_SERVER_CONFIG = "p2wlan.dispatch.fabric.1201.json"
LEGACY_FABRIC_CLIENT_CONFIG = "p2wlan.dispatch.fabric.1201.client.json"
SERVER_REFMAP = "p2wlan-universal-fabric-server-refmap.json"
CLIENT_REFMAP = "p2wlan-universal-fabric-client-refmap.json"
LEGACY_FABRIC_SERVER_REFMAP = "p2wlan-universal-fabric-1.20.1-server-refmap.json"
LEGACY_FABRIC_CLIENT_REFMAP = "p2wlan-universal-fabric-1.20.1-client-refmap.json"
LEGACY_FORGE_SERVER_REFMAP = "p2wlan-universal-forge-1.20.1-server-refmap.json"
LEGACY_FORGE_CLIENT_REFMAP = "p2wlan-universal-forge-1.20.1-client-refmap.json"
JNA = "META-INF/jars/jna.jar"
CORE_PATHS = {"common": "META-INF/jarjar/common.jar", "transport": "META-INF/jarjar/transport.jar",
              "native": "META-INF/jarjar/native.jar"}
CORE_IDS = {"common": "p2wlan_common", "transport": "p2wlan_transport", "native": "p2wlan_native"}
RESOURCES = {"fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "pack.mcmeta",
             "META-INF/MANIFEST.MF", "META-INF/jarjar/metadata.json", SERVER_CONFIG, CLIENT_CONFIG,
             FABRIC_SERVER_CONFIG, FABRIC_CLIENT_CONFIG, LEGACY_SERVER_CONFIG, LEGACY_CLIENT_CONFIG,
             LEGACY_FABRIC_SERVER_CONFIG, LEGACY_FABRIC_CLIENT_CONFIG, SERVER_REFMAP, CLIENT_REFMAP,
             LEGACY_FABRIC_SERVER_REFMAP, LEGACY_FABRIC_CLIENT_REFMAP, LEGACY_FORGE_SERVER_REFMAP,
             LEGACY_FORGE_CLIENT_REFMAP, *CORE_PATHS.values(), JNA,
             *dispatch_configs(), *[refmap for item in bindings()
                                    for refmap in (item.mixins.server_refmap, item.mixins.client_refmap) if refmap]}
PINS = {version: {item.loader: item.version for item in PROFILES.values() if item.minecraft == version}
        for version in MINECRAFT}
FORGE_RANGE = forge_range()
FML_RANGE = fml_range()
GAME_RANGE = game_range()
_BINDINGS = sorted(bindings(), key=lambda item: tuple(map(int, item.minecraft.split('.'))), reverse=True)
FABRIC_MIXINS = [config for item in _BINDINGS if item.family == 'fabric'
                 for config in (item.server_config, {'config': item.client_config, 'environment': 'client'})]
FML_CONFIGS = tuple(config for item in _BINDINGS if item.family == 'fml'
                    for config in (item.server_config, item.client_config))
FABRIC_DEPENDS = {'fabricloader': '=' + PROFILES[('1.21.1', 'fabric')].version,
                  'minecraft': [version for version in MINECRAFT if (version, 'fabric') in PROFILES], 'java': '>=17',
                  'fabric': ['=' + PROFILES[(version, 'fabric')].api_version for version in MINECRAFT if (version, 'fabric') in PROFILES]}
PACK_METADATA = {'pack': {'description': 'P2WLAN', 'pack_format': min(
    source.fixture_pack_format for source in sdk_inputs().values() if source.fixture_pack_format is not None),
    'supported_formats': {'min_inclusive': min(source.fixture_pack_format for source in sdk_inputs().values()
                                               if source.fixture_pack_format is not None), 'max_inclusive': 48}}}
CONFIGS = dispatch_configs()
PLUGIN_CLASSES = {value["plugin"].replace(".", "/") + ".class" for value in CONFIGS.values()}
FABRIC_NEUTRAL = frozenset(("io/p2wlan/mc/fabric/FabricP2wlan.class", "io/p2wlan/mc/fabric/FabricP2wlan$ServerEvents.class",
                            "io/p2wlan/mc/fabric/FabricP2wlan$Binding.class", "io/p2wlan/mc/fabric/client/FabricP2wlanClient.class",
                            "io/p2wlan/mc/fabric/world/FabricWorlds.class", "io/p2wlan/mc/fabric/world/FabricWorlds$IntegratedHooks.class"))
FORGE_NEUTRAL = frozenset("io/p2wlan/mc/forge/" + name + ".class" for name in
                        ("ForgeP2wlan", "ForgeP2wlan$Factories", "ForgeP2wlan$Code", "ForgeP2wlan$StartupError", "ForgeRuntime"))
LOADER_CLASSES = {
    "fabric": FABRIC_NEUTRAL | frozenset(("io/p2wlan/mc/fabric/current/FabricServerEvents.class", "io/p2wlan/mc/fabric/current/FabricClientEvents.class")),
    "neoforge": frozenset(("io/p2wlan/mc/neoforge/LocalNativeResources.class",
                            "io/p2wlan/mc/neoforge/NeoForgeP2wlan.class",
                            "io/p2wlan/mc/neoforge/client/NeoForgeClient.class",
                            "io/p2wlan/mc/neoforge/client/NeoForgeClient$Events.class")),
    "forge": FORGE_NEUTRAL | frozenset(("io/p2wlan/mc/forge/LocalNativeResources.class", "io/p2wlan/mc/forge/ForgeServerEvents.class",
                                         "io/p2wlan/mc/forge/client/ForgeClient.class")),
}
LEGACY_LOADER_CLASSES = {
    "fabric": FABRIC_NEUTRAL | frozenset(("io/p2wlan/mc/fabric/legacy/FabricServerEvents.class", "io/p2wlan/mc/fabric/legacy/FabricClientEvents.class")),
    "forge": FORGE_NEUTRAL | frozenset("io/p2wlan/mc/forge/legacy/" + name + ".class" for name in
                                     ("LocalNativeResources", "ForgeServerEvents", "ForgeClientEvents")),
}
OLDER_LOADER_CLASSES = {
    'fabric_1182': FABRIC_NEUTRAL | frozenset('io/p2wlan/mc/fabric/v1_18_2/' + name + '.class'
                                             for name in ('FabricServerEvents', 'FabricClientEvents')),
    'forge_1182': FORGE_NEUTRAL | frozenset('io/p2wlan/mc/forge/v1182/' + name + '.class'
                                          for name in ('LocalNativeResources', 'ForgeServerEvents', 'ForgeClientEvents')),
    'fabric_1165': FABRIC_NEUTRAL | frozenset('io/p2wlan/mc/fabric/v1_16_5/' + name + '.class'
                                             for name in ('FabricServerEvents', 'FabricClientEvents')),
    'forge_1165': FORGE_NEUTRAL | frozenset('io/p2wlan/mc/forge/v1165/' + name + '.class'
                                          for name in ('LocalNativeResources', 'ForgeServerEvents', 'ForgeClientEvents')),
}
BOOTSTRAP_CLASSES = frozenset(BOOTSTRAP + name + ".class" for name in (
    "BootstrapFailure", "BootstrapFailure$Code", "ServerMixinPlugin", "ClientMixinPlugin", "ClientMixinPlugin$1",
    "UniversalEnvironment", "UniversalProfiles", "UniversalEnvironment$Loader", "UniversalEnvironment$Family", "UniversalEnvironment$Side",
    "UniversalEnvironment$2",
    "UniversalEnvironment$Selection", "UniversalEnvironment$Binding", "UniversalEnvironment$ResourceLookup", "UniversalEnvironment$1"))
NATIVE_FIELDS = {"source-revision", "source-sha256", "build-kind", "version"}


def legacy_enabled():
    return ('1.16.5', 'forge') in PROFILES


def carrier_members(source):
    if not legacy_enabled():
        if source is not None: raise VerificationError('universal_legacy_bootstrap_unexpected')
        return {}
    if not isinstance(source, bytes) or not 0 < len(source) <= 1024 * 1024:
        raise VerificationError('universal_legacy_bootstrap_required')
    return verify_legacy_bootstrap.verify(io.BytesIO(source))


def role_game_classes(role):
    selected = SDK_INPUTS[role]
    if selected.minecraft in ('1.16.5', '1.18.2'):
        extra = GAME + 'mixin/' + ('FabricLoginNetworkingMixin' if selected.loader == 'fabric' else 'ClientShutdownMixin') + '.class'
        return GAME_CLASSES | (GAME_1165_EXTRAS if selected.minecraft == '1.16.5' else GAME_1182_EXTRAS) | {extra}
    return GAME_CLASSES | (LEGACY_FABRIC_GAME_EXTRAS if role == 'legacy_fabric'
                           else frozenset() if selected.minecraft == '1.20.1' else MODERN_GAME_EXTRAS)


def role_loader_classes(role):
    if role in OLDER_LOADER_CLASSES:
        return OLDER_LOADER_CLASSES[role]
    selected = SDK_INPUTS[role]
    return (LEGACY_LOADER_CLASSES if selected.minecraft == '1.20.1' else LOADER_CLASSES)[selected.loader]


def game_catalog():
    return frozenset(relocate_name(name, source.binding.class_prefix) if source.binding.class_prefix != GAME else name
                     for role, source in sdk_inputs().items() for name in role_game_classes(role))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def members(value):
    if not isinstance(value, bytes) or not 0 < len(value) <= MAX_ARCHIVE:
        raise VerificationError("universal_archive_size")
    with zipfile.ZipFile(io.BytesIO(value)) as archive:
        names = entries(archive, maximum=MAX_ENTRIES)
        if sum(item.file_size for item in archive.infolist()) > MAX_ARCHIVE:
            raise VerificationError("universal_archive_size")
        if any(item.flag_bits & 1 or item.external_attr >> 16 & 0o170000 not in {0, stat.S_IFREG, stat.S_IFDIR}
               for item in archive.infolist()):
            raise VerificationError("universal_archive_file_type")
        return {name: read(archive, name, MAX_BINARY) for name in names if not name.endswith("/")}


def archive_bytes(files):
    if len(files) > MAX_ENTRIES or sum(map(len, files.values())) > MAX_ARCHIVE:
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


def changed_name(name, destination=FABRIC_GAME):
    return relocate_name(name, destination)


def changed_keys(value, destination=FABRIC_GAME):
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            name = changed_name(key, destination)
            if name in result:
                raise VerificationError("universal_relocation_key_collision")
            result[name] = changed_keys(item, destination)
        return result
    if isinstance(value, list):
        return [changed_keys(item, destination) for item in value]
    return value


def expected_relocation(source, legacy=False, *, destination=None, refmaps=None):
    result = {}
    destination = destination or (FABRIC_LEGACY if legacy else FABRIC_GAME)
    refmaps = refmaps or ({"p2wlan-fabric-1.20.1-refmap.json": LEGACY_FABRIC_SERVER_REFMAP,
               "client-p2wlan-fabric-1.20.1-refmap.json": LEGACY_FABRIC_CLIENT_REFMAP} if legacy else {
                   fabric_contract.MIXIN_CONFIG["refmap"]: SERVER_REFMAP,
                   fabric_contract.CLIENT_MIXIN_CONFIG["refmap"]: CLIENT_REFMAP})
    for name, value in source.items():
        target = changed_name(name, destination) if name.endswith(".class") else refmaps.get(name, name)
        if target in result:
            raise VerificationError("universal_relocation_collision")
        if name.endswith(".class") and not name.startswith(BOOTSTRAP):
            value = relocate_class(value, destination)
        elif name in ("p2wlan.mixins.json", "p2wlan.client.mixins.json"):
            config = json_value(value)
            config["package"] = changed_name(config["package"] + ".", destination).rstrip(".")
            config["refmap"] = refmaps[config["refmap"]]
            value = encoded(config)
        elif name in refmaps:
            value = encoded(changed_keys(json_value(value), destination))
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
    embedded = tuple(source.binding.class_prefix for source in SDK_INPUTS.values()
                     if source.binding.class_prefix not in {GAME, FABRIC_GAME})
    return {name: value for name, value in files.items() if name.startswith(prefix) and name.endswith(".class")
            and not (prefix in {GAME, FABRIC_GAME} and name.startswith(embedded))}


def reject_test(name):
    base = name.rsplit("/", 1)[-1].split("$", 1)[0]
    if "fixture" in name.lower() or base.endswith(("Tests.class", "Test.class", "Tests", "Test")) \
            or name.startswith("net/minecraftforge/launcher/p2wlan/"):
        raise VerificationError("universal_test_payload")


def loader_classes(files, loader, legacy=False, role=None):
    prefix = f"io/p2wlan/mc/{loader}/"
    catalog = role_loader_classes(role) if role else (LEGACY_LOADER_CLASSES if legacy else LOADER_CLASSES)[loader]
    result = {}
    for name, value in files.items():
        reject_test(name)
        if name.endswith(".class"):
            if not name.startswith((prefix, GAME, BOOTSTRAP)):
                raise VerificationError("universal_source_class_owner")
            if name.startswith(prefix):
                if name not in catalog:
                    raise VerificationError("universal_loader_class_catalog")
                result[name] = value
    if set(result) != catalog:
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
    value["depends"] = FABRIC_DEPENDS
    value["mixins"] = FABRIC_MIXINS
    value["jars"] = [{"file": name} for name in (*CORE_PATHS.values(), JNA)]
    return value


def neo_descriptor(value):
    text = value.decode("utf-8")
    for before, after in (("p2wlan.neoforge.mixins.json", SERVER_CONFIG), ("p2wlan.neoforge.client.mixins.json", CLIENT_CONFIG)):
        if text.count('config="' + before + '"') != 1:
            raise VerificationError("universal_neoforge_mixin_contract")
        text = text.replace('config="' + before + '"', 'config="' + after + '"')
    return text.encode("utf-8")


def forge_descriptor(value):
    text = value.decode("utf-8")
    for before, after in (("[52,)", FML_RANGE), ("[52.1.16]", FORGE_RANGE), ("[1.21.1]", GAME_RANGE)):
        if text.count('"' + before + '"') != 1:
            raise VerificationError("universal_forge_descriptor_contract")
        text = text.replace('"' + before + '"', '"' + after + '"')
    return text.encode("utf-8")


def legacy_forge_files(source):
    files = {relocate_name(name, LEGACY): value if name.startswith(BOOTSTRAP) else relocate_class(value, LEGACY)
             for name, value in source.items() if name.endswith('.class')}
    for before, after in ((legacy_forge_contract.SERVER_REFMAP, LEGACY_FORGE_SERVER_REFMAP),
                          (legacy_forge_contract.CLIENT_REFMAP, LEGACY_FORGE_CLIENT_REFMAP)):
        files[after] = encoded(changed_keys(json_value(source[before]), LEGACY))
    return files


def older_files(source, role):
    selected = SDK_INPUTS[role]
    contract = older_contract(role)
    destination = selected.binding.class_prefix
    if selected.loader == 'fabric':
        return expected_relocation(source, destination=destination,
                                   refmaps={contract.SERVER_REFMAP: selected.binding.mixins.server_refmap,
                                            contract.CLIENT_REFMAP: selected.binding.mixins.client_refmap})
    files = {relocate_name(name, destination): value if name.startswith(BOOTSTRAP) else relocate_class(value, destination)
             for name, value in source.items() if name.endswith('.class')}
    for before, after in ((contract.SERVER_REFMAP, selected.binding.mixins.server_refmap),
                          (contract.CLIENT_REFMAP, selected.binding.mixins.client_refmap)):
        files[after] = encoded(changed_keys(json_value(source[before]), destination))
    return files


def older_contract(role):
    selected = SDK_INPUTS[role]
    if role not in OLDER_LOADER_CLASSES:
        raise VerificationError('universal_input_identity')
    version = selected.minecraft.replace('.', '')
    return importlib.import_module(f'verify_{selected.loader}_{version}_jar')


def add_classes(files, additions):
    for name, value in additions.items():
        if name in files and value != files[name]:
            raise VerificationError("universal_class_collision")
        files[name] = value


def constant_symbols(value, enum_parameters=False):
    """Preserve every CP symbol, including unused entries, across index reorder."""
    from collections import Counter
    if not 10 <= len(value) <= 1024 * 1024 or value[:4] != b'\xca\xfe\xba\xbe':
        raise VerificationError('universal_neutral_class_structure')
    constants, position, index = {}, 10, 1
    count = int.from_bytes(value[8:10], 'big')
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
             15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        if position >= len(value): raise VerificationError('universal_neutral_class_structure')
        tag = value[position]; position += 1
        if tag == 1:
            if position + 2 > len(value): raise VerificationError('universal_neutral_class_structure')
            size = int.from_bytes(value[position:position + 2], 'big'); position += 2
        else:
            size = sizes.get(tag)
        if size is None or position + size > len(value) or tag in (5, 6) and index + 1 >= count:
            raise VerificationError('universal_neutral_class_structure')
        constants[index] = (tag, value[position:position + size])
        position += size; index += 2 if tag in (5, 6) else 1
    # ASM preserves decoded standard attributes, but its reader can ignore
    # trailing bytes. Require exact member/attribute boundaries first.
    def take(size):
        nonlocal position
        if position + size > len(value): raise VerificationError('universal_neutral_class_structure')
        body = value[position:position + size]; position += size
        return body
    def attributes():
        for _ in range(int.from_bytes(take(2), 'big')):
            name = int.from_bytes(take(2), 'big')
            if name not in constants or constants[name][0] != 1: raise VerificationError('universal_neutral_class_structure')
            take(int.from_bytes(take(4), 'big'))
    take(6)
    take(int.from_bytes(take(2), 'big') * 2)
    for _ in range(2):
        for _ in range(int.from_bytes(take(2), 'big')):
            take(6); attributes()
    attributes()
    if position != len(value): raise VerificationError('universal_neutral_class_structure')
    cache = {}
    def resolve(key, depth=0):
        if key in cache: return cache[key]
        if depth > 16 or key not in constants: raise VerificationError('universal_neutral_class_structure')
        tag, body = constants[key]
        number = lambda data: int.from_bytes(data, 'big')
        if tag in (1, 3, 4, 5, 6): symbol = (tag, body)
        elif tag in (7, 8, 16, 19, 20): symbol = (tag, resolve(number(body), depth + 1))
        elif tag in (9, 10, 11, 12): symbol = (tag, resolve(number(body[:2]), depth + 1), resolve(number(body[2:]), depth + 1))
        elif tag == 15: symbol = (tag, body[0], resolve(number(body[1:]), depth + 1))
        else: symbol = (tag, number(body[:2]), resolve(number(body[2:]), depth + 1))
        cache[key] = symbol
        return symbol
    return Counter(resolve(key) for key, entry in constants.items()
                   if not (enum_parameters and entry == (1, b'MethodParameters')))


NEUTRAL_COMPARATOR = r'''import java.nio.file.*;
import java.util.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
public final class ForgeNeutral {
    static final String PREFIX = "io/p2wlan/mc/forge/ForgeP2wlan";
    static void noUnknown(List<Attribute> attrs) {
        if (attrs != null && !attrs.isEmpty()) throw new IllegalArgumentException("UNKNOWN_ATTRIBUTE");
    }
    static byte[] canonical(byte[] bytes, String expected) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        if (!node.name.equals(expected) || node.version != Opcodes.V17) throw new IllegalArgumentException("CLASS_IDENTITY");
        noUnknown(node.attrs);
        for (FieldNode field : node.fields) noUnknown(field.attrs);
        if (node.recordComponents != null) for (RecordComponentNode record : node.recordComponents) noUnknown(record.attrs);
        for (MethodNode method : node.methods) {
            noUnknown(method.attrs);
            if (expected.equals(PREFIX + "$Code") && method.parameters != null) {
                boolean valueOf = method.name.equals("valueOf") && method.desc.equals("(Ljava/lang/String;)L" + expected + ";")
                    && method.parameters.size() == 1 && method.parameters.get(0).name == null
                    && method.parameters.get(0).access == Opcodes.ACC_MANDATED;
                boolean constructor = method.name.equals("<init>") && method.desc.equals("(Ljava/lang/String;I)V")
                    && method.parameters.size() == 2 && method.parameters.stream().allMatch(p -> p.name == null && p.access == Opcodes.ACC_SYNTHETIC);
                if (valueOf || constructor) method.parameters = null;
            }
        }
        ClassWriter output = new ClassWriter(0);
        node.accept(output);
        return output.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        boolean equal = Arrays.equals(canonical(Files.readAllBytes(Path.of(args[0])), args[2]),
                                     canonical(Files.readAllBytes(Path.of(args[1])), args[2]));
        if (!equal) throw new IllegalArgumentException("SEMANTICS_DIFFER");
    }
}
'''


def neutral_equivalent(name, left, right):
    """Only the five Forge entry classes permit these measured javac differences."""
    if name not in FORGE_NEUTRAL:
        raise VerificationError('universal_neutral_class_catalog')
    if left == right: return
    enum_parameters = name == 'io/p2wlan/mc/forge/ForgeP2wlan$Code.class'
    if constant_symbols(left, enum_parameters) != constant_symbols(right, enum_parameters):
        raise VerificationError('universal_neutral_entry_changed')
    from legacy_gradle import java17
    java, _ = java17(os.environ.copy())
    javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
    if not javac.is_file(): raise VerificationError('universal_neutral_javac17_required')
    parent = Path(__file__).resolve().parents[1] / 'java/forge-legacy/build'
    parent.mkdir(parents=True, exist_ok=True)
    exports = ['--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED',
               '--add-exports=java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED']
    with tempfile.TemporaryDirectory(prefix='neutral-comparator-', dir=parent) as directory:
        root = Path(directory)
        (root / 'ForgeNeutral.java').write_text(NEUTRAL_COMPARATOR, encoding='utf-8')
        (root / 'left.class').write_bytes(left); (root / 'right.class').write_bytes(right)
        try:
            subprocess.run([str(javac), *exports, '-encoding', 'UTF-8', '-d', str(root), str(root / 'ForgeNeutral.java')],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20, check=True)
            subprocess.run([str(java), *exports, '-cp', str(root), 'ForgeNeutral', str(root / 'left.class'),
                            str(root / 'right.class'), name.removesuffix('.class')],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=True)
        except subprocess.SubprocessError as error:
            raise VerificationError('universal_neutral_entry_changed') from error


def records(files):
    return [{"path": name, "size": len(value), "sha256": digest(value)} for name, value in sorted(files.items())]


def native_summary(natives):
    if not natives or any({key: item[key] for key in NATIVE_FIELDS} != {key: natives[0][key] for key in NATIVE_FIELDS} for item in natives):
        raise VerificationError("universal_native_identity")
    return {"source_revision": natives[0]["source-revision"], "native_source_sha256": natives[0]["source-sha256"],
            "native_targets": sorted(item["verification-platform"] for item in natives),
            "native_verification_methods": [{"platform": item["verification-platform"], "method": item["verification-method"]}
                                            for item in sorted(natives, key=lambda item: item["verification-platform"])]}


def build_inputs(sources, bootstrap, development=False, *, legacy_bootstrap=None):
    """Accept exact SDK roles; one role can serve multiple installed loaders."""
    required = set(sdk_inputs())
    if not isinstance(sources, dict) or set(sources) != required:
        raise VerificationError('universal_input_identity')
    if any(not isinstance(value, bytes) or not 0 < len(value) <= MAX_ARCHIVE for value in sources.values()):
        raise VerificationError('universal_archive_size')
    from class_relocation import relocate_fabric
    parent = Path(__file__).resolve().parents[1] / 'java/universal-bootstrap/build'
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='fabric-input-', dir=parent) as directory:
        source = Path(directory) / 'fabric.jar'
        source.write_bytes(sources['fabric'])
        relocated = relocate_fabric(source, development)
    return assemble_roles(sources, relocated, bootstrap, development, legacy_bootstrap=legacy_bootstrap)


def build_bytes(fabric, neoforge, forge, relocated_fabric, bootstrap, development=False, *, legacy_forge, legacy_fabric,
                forge_1182=None, fabric_1182=None, forge_1165=None, fabric_1165=None, legacy_bootstrap=None):
    sources = {'fabric': fabric, 'neoforge': neoforge, 'forge': forge,
               'legacy_forge': legacy_forge, 'legacy_fabric': legacy_fabric}
    if forge_1182 is not None: sources['forge_1182'] = forge_1182
    if fabric_1182 is not None: sources['fabric_1182'] = fabric_1182
    if forge_1165 is not None: sources['forge_1165'] = forge_1165
    if fabric_1165 is not None: sources['fabric_1165'] = fabric_1165
    return assemble_roles(sources, relocated_fabric, bootstrap, development, legacy_bootstrap=legacy_bootstrap)


def assemble_roles(sources, relocated_fabric, bootstrap, development=False, *, legacy_bootstrap=None):
    import verify_fabric_legacy_jar as legacy_fabric_contract
    if set(sources) != set(sdk_inputs()):
        raise VerificationError('universal_input_identity')
    inputs = dict(sources) | {"relocated_fabric": relocated_fabric, "bootstrap": bootstrap}
    carrier = carrier_members(legacy_bootstrap)
    if carrier: inputs['legacy_bootstrap'] = legacy_bootstrap
    source = {}
    verifiers = {"fabric": (fabric_contract.verify, "fabric", False), "neoforge": (neo_contract.verify, "neoforge", False),
                 "forge": (forge_contract.verify, "forge", False), "legacy_forge": (legacy_forge_contract.verify, "forge", True),
                 "legacy_fabric": (legacy_fabric_contract.verify, "fabric", True)}
    verifiers.update({role: (older_contract(role).verify, SDK_INPUTS[role].loader, True)
                      for role in sources if role in OLDER_LOADER_CLASSES})
    native_sets = []
    for key in sources:
        verifier, loader, legacy = verifiers[key]
        natives = verifier(io.BytesIO(inputs[key]), development)
        if key != 'forge_1165':
            bind_sources(natives, development)
            native_sets.append(natives)
        source[key] = members(inputs[key])
        loader_classes(source[key], loader, legacy, key)
        if {name for name in source[key] if name.startswith(GAME) and name.endswith('.class')} != role_game_classes(key):
            raise VerificationError("universal_game_changed")
    if any(value != native_sets[0] for value in native_sets[1:]):
        raise VerificationError("universal_native_identity")
    game = classes(source["forge"], GAME)
    if classes(source["neoforge"], GAME) != game:
        raise VerificationError("universal_game_changed")
    relocated = match_relocation(source["fabric"], members(relocated_fabric))
    old_forge = legacy_forge_files(source["legacy_forge"])
    old_fabric = expected_relocation(source["legacy_fabric"], legacy=True)
    older = {role: older_files(source[role], role) for role in OLDER_LOADER_CLASSES if role in source}
    all_components = {key: components(files, verifiers[key][1]) for key, files in source.items() if key != 'forge_1165'}
    compare_components(all_components)
    boot = members(bootstrap)
    if set(boot) - {"META-INF/MANIFEST.MF", *CONFIGS} != BOOTSTRAP_CLASSES:
        raise VerificationError("universal_bootstrap_layout")
    for name, config in CONFIGS.items():
        if name not in boot or encoded(json_value(boot[name])) != encoded(config):
            raise VerificationError("universal_dispatch_contract")
    for key in source:
        embedded = classes(source[key], BOOTSTRAP)
        if embedded and embedded != classes(boot, BOOTSTRAP):
            raise VerificationError("universal_embedded_bootstrap_changed")
    for name in FORGE_NEUTRAL:
        neutral_equivalent(name, source['forge'][name], old_forge[name])
    for name in FABRIC_NEUTRAL:
        if relocated[name] != old_fabric[name]:
            raise VerificationError('universal_neutral_entry_changed')
    for role, mapped in older.items():
        for name in FORGE_NEUTRAL if SDK_INPUTS[role].loader == 'forge' else FABRIC_NEUTRAL:
            if SDK_INPUTS[role].loader == 'forge': neutral_equivalent(name, source['forge'][name], mapped[name])
            elif relocated[name] != mapped[name]: raise VerificationError('universal_neutral_entry_changed')
    files = dict(game)
    for loader in ("forge", "neoforge"):
        files.update(loader_classes(source[loader], loader))
    # Both SDKs were checked; the Java-17 Forge application entry is emitted once.
    files.update({name: old_forge[name] for name in FORGE_NEUTRAL})
    files.update(classes(relocated, "io/p2wlan/mc/fabric/"))
    add_classes(files, {name: value for name, value in old_forge.items() if name.endswith('.class') and name not in FORGE_NEUTRAL and not name.startswith(BOOTSTRAP)})
    add_classes(files, {name: value for name, value in old_fabric.items() if name.endswith('.class') and name not in FABRIC_NEUTRAL and not name.startswith(BOOTSTRAP)})
    for role, mapped in older.items():
        neutral = FORGE_NEUTRAL if SDK_INPUTS[role].loader == 'forge' else FABRIC_NEUTRAL
        add_classes(files, {name: value for name, value in mapped.items()
                            if name.endswith('.class') and name not in neutral and not name.startswith(BOOTSTRAP)})
    for name, value in boot.items():
        reject_test(name)
        if name.endswith(".class"):
            with zipfile.ZipFile(io.BytesIO(bootstrap)) as archive: classfile(archive, name, 61)
            if name in files: raise VerificationError("universal_class_collision")
            files[name] = value
    for name, value in CONFIGS.items(): files[name] = encoded(value)
    for name in (SERVER_REFMAP, CLIENT_REFMAP): files[name] = relocated[name]
    for name in (LEGACY_FORGE_SERVER_REFMAP, LEGACY_FORGE_CLIENT_REFMAP): files[name] = old_forge[name]
    for name in (LEGACY_FABRIC_SERVER_REFMAP, LEGACY_FABRIC_CLIENT_REFMAP): files[name] = old_fabric[name]
    for role, mapped in older.items():
        for name in (SDK_INPUTS[role].binding.mixins.server_refmap, SDK_INPUTS[role].binding.mixins.client_refmap):
            files[name] = mapped[name]
    files["fabric.mod.json"] = encoded(fabric_descriptor(source["fabric"]))
    files["META-INF/neoforge.mods.toml"] = neo_descriptor(source["neoforge"]["META-INF/neoforge.mods.toml"])
    files["META-INF/mods.toml"] = forge_descriptor(source["forge"]["META-INF/mods.toml"])
    files["pack.mcmeta"] = encoded(PACK_METADATA)
    files["META-INF/MANIFEST.MF"] = mixin_manifest(FML_CONFIGS)
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
    if carrier:
        files[JNA] = universal_jna.isolate(files[JNA])
        add_classes(files, carrier)
        from universal_module import NAME, descriptor
        files[NAME] = descriptor()
        files[legacy_core_index.INDEX] = legacy_core_index.index_bytes(
            files, native_sets[0][0]['source-revision'], native_sets[0][0]['source-sha256'])
    summary = {"schema": 2, "minecraft": MINECRAFT, "version": VERSION, "loaders": PINS,
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
        names = entries(archive, maximum=MAX_ENTRIES)
        if INDEX not in names or sum(item.file_size for item in archive.infolist()) > MAX_ARCHIVE:
            raise VerificationError("universal_archive_layout")
        if any(item.is_dir() or item.flag_bits & 1 or item.external_attr >> 16 & 0o170000 not in {0, stat.S_IFREG} for item in archive.infolist()):
            raise VerificationError("universal_archive_file_type")
        try:
            info = json_value(read(archive, INDEX, MAX_INDEX))
        except (ValueError, UnicodeError) as failure:
            if isinstance(failure, VerificationError): raise
            raise VerificationError('universal_index_invalid') from failure
        fields = {"schema", "minecraft", "version", "loaders", "build_mode", "source_revision", "native_source_sha256",
                  "native_targets", "native_verification_methods", "inputs", "files", "verification"}
        if not isinstance(info, dict) or set(info) != fields or encoded(info.get("schema")) != encoded(2) or info["minecraft"] != MINECRAFT \
                or info["version"] != VERSION or info["loaders"] != PINS or info["verification"] != "archive-structure/source-binding" \
                or info["build_mode"] != ("development" if development else "release"):
            raise VerificationError("universal_index_contract")
        sources = info["inputs"]
        if not isinstance(sources, dict) or set(sources) != set(sdk_inputs()) | {'relocated_fabric', 'bootstrap'} \
                | ({'legacy_bootstrap'} if legacy_enabled() else set()):
            raise VerificationError("universal_input_identity")
        import re
        for identity in sources.values():
            if not isinstance(identity, dict) or set(identity) != {"size", "sha256"} or type(identity["size"]) is not int \
                    or not 0 < identity["size"] <= MAX_ARCHIVE or not isinstance(identity["sha256"], str) \
                    or re.fullmatch("[a-f0-9]{64}", identity["sha256"]) is None:
                raise VerificationError("universal_input_identity")
        files = {name: read(archive, name, MAX_BINARY) for name in names if name != INDEX}
        resources = RESOURCES | ({legacy_core_index.INDEX, legacy_core_index.SERVICE} if legacy_enabled() else set())
        if {name for name in files if not name.endswith(".class")} != resources:
            raise VerificationError("universal_archive_layout")
        if encoded(info["files"]) != encoded(records(files)):
            raise VerificationError("universal_payload_changed")
        for name in names: reject_test(name)
        expected_game = game_catalog()
        actual_game = {name for name in names if name.endswith('.class') and name.startswith((GAME, FABRIC_GAME))}
        if actual_game != expected_game:
            raise VerificationError("universal_game_layout")
        for name in names:
            if name.endswith(".class"):
                if name == 'module-info.class' and legacy_enabled():
                    from universal_module import descriptor
                    if read(archive, name, 4096) != descriptor():
                        raise VerificationError('universal_service_module')
                    continue
                if not name.startswith((GAME, "io/p2wlan/mc/fabric/", "io/p2wlan/mc/neoforge/", "io/p2wlan/mc/forge/", BOOTSTRAP,
                                        *((verify_legacy_bootstrap.PREFIX,) if legacy_enabled() else ()))):
                    raise VerificationError("universal_class_owner")
                classfile(archive, name, 61)
        non_game = {name for name in names if name.endswith(".class") and not name.startswith((GAME, FABRIC_GAME))}
        admitted = BOOTSTRAP_CLASSES | frozenset().union(*(role_loader_classes(role) for role in sdk_inputs())) \
            | (verify_legacy_bootstrap.CLASSES | {'module-info.class'} if legacy_enabled() else frozenset())
        if non_game != admitted:
            raise VerificationError("universal_loader_class_catalog")
        if set(classes(files, BOOTSTRAP)) != BOOTSTRAP_CLASSES: raise VerificationError("universal_bootstrap_layout")
        for name in (fabric_contract.MAIN, fabric_contract.CLIENT, neo_contract.MAIN, neo_contract.CLIENT,
                     forge_contract.MAIN, forge_contract.CLIENT, forge_contract.SERVER_MIXIN,
                     changed_name(fabric_contract.SERVER_MIXIN), *fabric_contract.LAN_MIXINS,
                     *map(changed_name, fabric_contract.LAN_MIXINS), fabric_contract.CLIENT_MIXIN,
                     changed_name(fabric_contract.CLIENT_MIXIN), fabric_contract.INTEGRATED_MIXIN,
                     changed_name(fabric_contract.INTEGRATED_MIXIN)):
            classfile(archive, name, 61)
        for name in (fabric_contract.MAIN, neo_contract.MAIN, forge_contract.MAIN,
                     forge_contract.SERVER_MIXIN, changed_name(fabric_contract.SERVER_MIXIN),
                     fabric_contract.WORLD_COMMANDS, changed_name(fabric_contract.WORLD_COMMANDS)):
            body = classfile(archive, name, 61)
            if any(prefix in body for prefix in (b"net/minecraft/client/", b"io/p2wlan/mc/game/client/",
                                                 b"io/p2wlan/mc/fabric/game/client/", b"io/p2wlan/mc/fabric/client/",
                                                 b"io/p2wlan/mc/neoforge/client/", b"io/p2wlan/mc/forge/client/")):
                raise VerificationError("universal_dedicated_client_reference")
        for name in ("io/p2wlan/mc/fabric/current/FabricServerEvents.class", "io/p2wlan/mc/fabric/legacy/FabricServerEvents.class",
                     "io/p2wlan/mc/fabric/world/FabricWorlds.class", "io/p2wlan/mc/forge/ForgeRuntime.class",
                     "io/p2wlan/mc/forge/ForgeServerEvents.class", "io/p2wlan/mc/forge/legacy/ForgeServerEvents.class",
                     *[relocate_name(path, destination) for destination in (LEGACY, FABRIC_LEGACY)
                       for path in (fabric_contract.SERVER_MIXIN, fabric_contract.WORLD_COMMANDS)]):
            body = classfile(archive, name, 61)
            if any(prefix in body for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/', b'net/fabricmc/fabric/api/client/',
                                                 b'io/p2wlan/mc/game/client/', b'io/p2wlan/mc/game/v1_20_1/client/',
                                                 b'io/p2wlan/mc/fabric/game/client/', b'io/p2wlan/mc/fabric/game/v1_20_1/client/')):
                raise VerificationError('universal_dedicated_client_reference')
        for name, config in CONFIGS.items():
            if encoded(metadata(archive, name)) != encoded(config): raise VerificationError("universal_dispatch_contract")
        expected_mf = mixin_manifest(FML_CONFIGS)
        if read(archive, "META-INF/MANIFEST.MF", 8192) != expected_mf: raise VerificationError("universal_mixin_manifest")
        fabric = metadata(archive, "fabric.mod.json")
        if type(fabric.get("schemaVersion")) is not int or fabric.get("schemaVersion") != 1 or fabric.get("environment") != "*" \
                or fabric.get("id") != "p2wlan" or fabric.get("version") != VERSION or fabric.get("depends") != FABRIC_DEPENDS \
                or fabric.get("entrypoints") != {"main": ["io.p2wlan.mc.fabric.FabricP2wlan"], "client": ["io.p2wlan.mc.fabric.client.FabricP2wlanClient"]} \
                or fabric.get("mixins") != FABRIC_MIXINS \
                or fabric.get("jars") != [{"file": name} for name in (*CORE_PATHS.values(), JNA)]:
            raise VerificationError("universal_fabric_contract")
        import tomllib
        neo = tomllib.loads(read(archive, "META-INF/neoforge.mods.toml", 65536).decode())
        if neo.get("mixins") != [{"config": SERVER_CONFIG}, {"config": CLIENT_CONFIG}]: raise VerificationError("universal_neoforge_mixin_contract")
        for loader, config, language in (("neoforge", neo, "[4,)"), ("forge", tomllib.loads(read(archive, "META-INF/mods.toml", 65536).decode()), FML_RANGE)):
            if config.get("modLoader") != "javafml" or config.get("loaderVersion") != language \
                    or len(config.get("mods", [])) != 1 or config["mods"][0].get("modId") != "p2wlan" or config["mods"][0].get("version") != VERSION:
                raise VerificationError("universal_loader_contract")
            dependencies = config.get("dependencies", {}).get("p2wlan")
            expected = [{"modId": loader, "versionRange": "[21.1.256]" if loader == 'neoforge' else FORGE_RANGE, "ordering": "NONE", "side": "BOTH",
                         **({"type": "required"} if loader == "neoforge" else {"mandatory": True})},
                        {"modId": "minecraft", "versionRange": "[1.21.1]" if loader == 'neoforge' else GAME_RANGE, "ordering": "NONE", "side": "BOTH",
                         **({"type": "required"} if loader == "neoforge" else {"mandatory": True})}]
            if encoded(dependencies) != encoded(expected): raise VerificationError("universal_dependency_contract")
        if encoded(metadata(archive, "pack.mcmeta")) != encoded(PACK_METADATA):
            raise VerificationError("universal_pack_metadata")
        for name, expected in ((SERVER_REFMAP, fabric_contract.REFMAP), (CLIENT_REFMAP, fabric_contract.CLIENT_REFMAP)):
            if encoded(metadata(archive, name)) != encoded(changed_keys(expected)): raise VerificationError("universal_refmap_contract")
        import verify_fabric_legacy_jar as legacy_fabric_contract
        for name, expected in ((LEGACY_FABRIC_SERVER_REFMAP, legacy_fabric_contract.REFMAP),
                               (LEGACY_FABRIC_CLIENT_REFMAP, legacy_fabric_contract.CLIENT_REFMAP_VALUE)):
            if encoded(metadata(archive, name)) != encoded(changed_keys(expected, FABRIC_LEGACY)):
                raise VerificationError('universal_legacy_fabric_refmap_contract')
        for name, targets in ((LEGACY_FORGE_SERVER_REFMAP, legacy_forge_contract.SERVER_TARGETS),
                              (LEGACY_FORGE_CLIENT_REFMAP, legacy_forge_contract.CLIENT_TARGETS)):
            legacy_forge_contract.refmap(metadata(archive, name), changed_keys(targets, LEGACY))
        for owner, field, original in [('MultiplayerListMixin', b'f_99766_', b'screen'),
                                       ('MultiplayerScreenMixin', b'f_99673_', b'serverSelectionList')]:
            declared = legacy_forge_contract.field_names(classfile(archive, LEGACY + 'mixin/' + owner + '.class', 61))
            if field not in declared or original in declared:
                raise VerificationError('universal_legacy_forge_shadow_srg')
        declared = legacy_forge_contract.field_names(classfile(archive, LEGACY + 'server/mixin/ServerLoginMixin.class', 61))
        if b'f_10013_' not in declared or b'connection' in declared:
            raise VerificationError('universal_legacy_forge_shadow_srg')
        for role in sdk_inputs():
            if role not in OLDER_LOADER_CLASSES: continue
            contract = older_contract(role)
            selected = SDK_INPUTS[role]
            destination = selected.binding.class_prefix
            for refmap, expected in ((selected.binding.mixins.server_refmap,
                                     contract.REFMAP if selected.loader == 'fabric' else contract.SERVER_TARGETS),
                                    (selected.binding.mixins.client_refmap,
                                     contract.CLIENT_REFMAP_VALUE if selected.loader == 'fabric' else contract.CLIENT_TARGETS)):
                refmap_value = metadata(archive, refmap)
                expected = changed_keys(expected, destination)
                if selected.loader == 'forge': contract.refmap(refmap_value, expected)
                elif encoded(refmap_value) != encoded(expected):
                    raise VerificationError('universal_fabric' + selected.minecraft.replace('.', '') + '_refmap_contract')
            for owner, field, original in contract.SHADOW_FIELDS:
                declared = legacy_forge_contract.field_names(classfile(archive, destination + owner + '.class', 61))
                if field not in declared or original in declared:
                    raise VerificationError('universal_' + selected.minecraft.replace('.', '') + '_shadow_contract')
            if selected.loader == 'forge':
                contract.client_activation(classfile(archive, destination + 'mixin/ClientConnectionMixin.class', 61))
            for name in role_game_classes(role):
                if name == GAME + 'GameText.class' or name.startswith(GAME + 'server/'):
                    body = classfile(archive, relocate_name(name, destination), 61)
                    if any(prefix in body for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/',
                                                         (destination + 'client/').encode())):
                        raise VerificationError('universal_dedicated_client_reference')
            for name in role_loader_classes(role) - FABRIC_NEUTRAL - FORGE_NEUTRAL:
                if name.rsplit('/', 1)[-1] == 'ForgeClientEvents.class' or name.rsplit('/', 1)[-1] == 'FabricClientEvents.class': continue
                body = classfile(archive, name, 61)
                if any(prefix in body for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/',
                                                     (destination + 'client/').encode())):
                    raise VerificationError('universal_dedicated_client_reference')
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
        jna = read(archive, JNA, MAX_BINARY)
        if legacy_enabled():
            universal_jna.verify(jna)
            carried = {name: files[name] for name in verify_legacy_bootstrap.CLASSES | {legacy_core_index.SERVICE}}
            carried['META-INF/MANIFEST.MF'] = files['META-INF/MANIFEST.MF']
            verify_legacy_bootstrap.verify(io.BytesIO(archive_bytes(carried)))
            index = read(archive, legacy_core_index.INDEX, legacy_core_index.MAX_INDEX)
            legacy_core_index.index_values(index)
            if index != legacy_core_index.index_bytes(files, info['source_revision'], info['native_source_sha256']):
                raise VerificationError('universal_legacy_core_identity')
        else:
            with zipfile.ZipFile(io.BytesIO(jna)) as child: jna_payloads(child)
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
    for name in (*[role.replace('_', '-') for role in sdk_inputs()], "relocated-fabric", "bootstrap", "output"):
        build.add_argument("--" + name, type=Path, required=True)
    build.add_argument('--legacy-bootstrap', type=Path, required=legacy_enabled())
    check = commands.add_parser("verify"); check.add_argument("archive", type=Path)
    for command in (build, check): command.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "build":
            value = assemble_roles({role: regular_bytes(getattr(args, role)) for role in sdk_inputs()},
                                   regular_bytes(args.relocated_fabric), regular_bytes(args.bootstrap), args.development,
                                   legacy_bootstrap=regular_bytes(args.legacy_bootstrap) if args.legacy_bootstrap is not None else None)
            publish(args.output, value)
        else: value = regular_bytes(args.archive)
        result = verify(io.BytesIO(value), args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, "Universal archive failed: " + (str(error) if isinstance(error, VerificationError) else "UNIVERSAL_IO") + "\n")
    print("PASS Universal archive structure/source binding: Minecraft " + '/'.join(MINECRAFT) + " with exact loader/version adapters")
    print(json.dumps(result | {"artifact_sha256": digest(value)}, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()

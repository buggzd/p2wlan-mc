"""Synthetic package contracts; these tests do not execute a game loader."""
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
import universal_jar as universal
import test_mod_jar as fabric_fixtures
import test_neoforge_jar as neo_fixtures
import test_forge_jar as forge_fixtures

GAME_TYPES = (
    "client/ClientDisplaySettings", "client/ClientPlatform", "client/ClientPlatform$Binding", "client/ClientPlatform$IntegratedHooks",
    "client/LatencyHud", "client/connect/GameJoins", "client/connect/GameJoins$Attempt", "client/connect/GameJoins$Handoff",
    "client/connect/GameJoins$Phase", "client/connect/GameJoins$Registration", "client/connect/GameJoins$Status",
    "client/connect/MinecraftConnect", "client/lan/ClientNetworkView", "client/lan/ClientNetworkView$Pending",
    "client/GameUi", "client/connect/GameLogin", "client/lan/VanillaLanList", "client/lan/VanillaLanScreen", "client/lan/VirtualLanEntry",
    "client/screen/AccountScreen", "client/screen/AgentScreen", "client/screen/AgentScreen$Action",
    "client/screen/AgentScreen$Label", "client/screen/AgentScreen$ReplyAction", "client/screen/AgentScreen$TaskAction",
    "client/screen/DiagnosticsScreen", "client/screen/DisplaySettingsScreen", "client/screen/DisplaySettingsScreen$1",
    "client/screen/JoinGameScreen", "client/screen/LatencyStatsScreen", "client/screen/LocalPanelScreen", "client/screen/LogoButton",
    "client/screen/MenuLogo", "client/screen/PasswordField", "client/screen/PixelButton", "client/screen/RoomFormScreen",
    "client/screen/RoomsScreen", "client/screen/RuntimeScreen", "mixin/ClientConnectionMixin", "mixin/IntegratedServerMixin",
    "mixin/MultiplayerListAccessor", "mixin/MultiplayerListMixin", "mixin/MultiplayerScreenMixin",
    "server/WorldCommands", "server/GameCommandApi", "server/mixin/ServerConnectionMixin",
    "server/mixin/ServerLoginConnectionMixin", "server/mixin/ServerLoginMixin",
)


def bytecode(name, major=61, marker="named", neutral=False, fields=()):
    # Minimal classfile with actual UTF8/Class constants and an opaque class
    # body. Relocation must update descriptors/dotted names without changing
    # constant indices; no instruction execution is claimed here.
    text = (name.removesuffix(".class"), "java/lang/Object", marker,
            "Ljava/lang/Object;" if neutral else "Lio/p2wlan/mc/game/server/WorldCommands;",
            "java.lang.Object" if neutral else "io.p2wlan.mc.game.server.WorldCommands")
    constants = []
    for value in text:
        raw = value.encode(); constants.append(b"\x01" + struct.pack(">H", len(raw)) + raw)
    constants.extend((b"\x07\x00\x01", b"\x07\x00\x02"))
    declarations = b''
    for index, field in enumerate(fields, 8):
        raw = field.encode(); constants.append(b'\x01' + struct.pack('>H', len(raw)) + raw)
        declarations += struct.pack('>4H', 2, index, 4, 0)
    return b"\xca\xfe\xba\xbe\x00\x00" + struct.pack(">HH", major, 8 + len(fields)) + b"".join(constants) \
        + struct.pack('>5H', 0x21, 6, 7, 0, len(fields)) + declarations + struct.pack('>2H', 0, 0)


class UniversalJarTests(unittest.TestCase):
    def setUp(self):
        binding = fabric_fixtures.jna_fixture_binding(); binding.start(); self.addCleanup(binding.stop)
        binding = patch.object(universal, "bind_sources"); self.bind = binding.start(); self.addCleanup(binding.stop)

    def source(self, loader, release=False):
        if loader == "fabric": top = fabric_fixtures.ModJarTests.fixture(self)[0]
        elif loader == "neoforge": top = neo_fixtures.NeoForgeJarTests.fixture(self)
        else: top = forge_fixtures.ForgeJarTests.fixture(self)
        for name in tuple(top):
            if name.endswith(".class"):
                top[name] = bytecode(name, marker="fabric" if loader == "fabric" else "named")
        top.update({universal.GAME + name + ".class": bytecode(universal.GAME + name + ".class", marker="fabric" if loader == "fabric" else "named") for name in GAME_TYPES + ("server/mixin/ServerConfigurationMixin",)})
        top.update({name: bytecode(name, marker=loader, neutral=name in universal.FABRIC_NEUTRAL | universal.FORGE_NEUTRAL)
                    for name in universal.LOADER_CLASSES[loader]})
        if loader == "fabric":
            path = "META-INF/jars/common.jar"; child = universal.members(top[path])
            child.update({name: fabric_fixtures.bytecode(61) for name in universal.neo_contract.WORLD_COMMON})
            top[path] = universal.archive_bytes(child)
            descriptor = json.loads(top["fabric.mod.json"])
            descriptor["depends"].update({"fabricloader": ">=0.16.14", "fabric-api": "*"})
            top["fabric.mod.json"] = universal.encoded(descriptor)
        if release:
            native = "META-INF/jars/native.jar" if loader == "fabric" else "META-INF/jarjar/native.jar"
            child = universal.members(top[native])
            for name in child:
                if name.endswith("/manifest.properties"):
                    child[name] = child[name].decode().replace("build-kind=development", "build-kind=release").encode()
            top[native] = universal.archive_bytes(child)
        # Synthetic nested ZIP helpers use wall-clock timestamps. Canonicalize
        # their headers so identical fixture inputs really have identical bytes.
        for name in tuple(top):
            if name.endswith('.jar'): top[name] = universal.archive_bytes(universal.members(top[name]))
        return universal.archive_bytes({name: value.encode() if isinstance(value, str) else value for name, value in top.items()})

    def legacy_source(self, loader, release=False):
        top = universal.members(self.source(loader, release))
        top.pop(universal.GAME + "server/mixin/ServerConfigurationMixin.class")
        for name in universal.LOADER_CLASSES[loader]:
            top.pop(name)
        top.update({name: bytecode(name, marker=loader, neutral=name in universal.FABRIC_NEUTRAL | universal.FORGE_NEUTRAL)
                    for name in universal.LEGACY_LOADER_CLASSES[loader]})
        if loader == 'forge':
            contract = universal.legacy_forge_contract
            top['META-INF/mods.toml'] = top['META-INF/mods.toml'].replace(b'[52,)', b'[47,)').replace(b'[52.1.16]', b'[47.3.2]').replace(b'[1.21.1]', b'[1.20.1]')
            for name in ['p2wlan.forge.mixins.json', 'p2wlan.forge.client.mixins.json']:
                top.pop(name)
            for name, package, key, types, refmap in [
                    (contract.SERVER_CONFIG, 'io.p2wlan.mc.game.server.mixin', 'mixins', ['ServerConnectionMixin', 'ServerLoginConnectionMixin', 'ServerLoginMixin'], contract.SERVER_REFMAP),
                    (contract.CLIENT_CONFIG, 'io.p2wlan.mc.game.mixin', 'client', [name.rsplit('/', 1)[-1] for name in contract.CLIENT_TARGETS], contract.CLIENT_REFMAP)]:
                top[name] = universal.encoded({'required': True, 'minVersion': '0.8.5', 'package': package, 'compatibilityLevel': 'JAVA_17',
                                               'refmap': refmap, key: types, 'injectors': {'defaultRequire': 1}})
            for name, targets in [(contract.SERVER_REFMAP, contract.SERVER_TARGETS), (contract.CLIENT_REFMAP, contract.CLIENT_TARGETS)]:
                top[name] = universal.encoded({'mappings': targets, 'data': {'searge': targets}})
            top['META-INF/MANIFEST.MF'] = ('Manifest-Version: 1.0\r\nMixinConfigs: ' + contract.SERVER_CONFIG + ',' + contract.CLIENT_CONFIG + '\r\n\r\n').encode()
            top['pack.mcmeta'] = universal.encoded({'pack': {'description': 'P2WLAN', 'pack_format': 15}})
            for owner, field in [('mixin/MultiplayerListMixin', 'f_99766_'), ('mixin/MultiplayerScreenMixin', 'f_99673_'),
                                 ('server/mixin/ServerLoginMixin', 'f_10013_')]:
                name = universal.GAME + owner + '.class'
                top[name] = bytecode(name, fields=(field,))
        else:
            import verify_fabric_legacy_jar as contract
            top[contract.LOGIN_MIXIN] = bytecode(contract.LOGIN_MIXIN, marker='fabric20-api-login')
            descriptor = json.loads(top['fabric.mod.json'])
            descriptor['depends'] = {'fabricloader': '=0.16.14', 'minecraft': '1.20.1', 'java': '>=17', 'fabric-api': '=0.92.12+1.20.1'}
            top['fabric.mod.json'] = universal.encoded(descriptor)
            for name in [universal.fabric_contract.MIXIN_CONFIG['refmap'], universal.fabric_contract.CLIENT_MIXIN_CONFIG['refmap']]:
                top.pop(name)
            for name, config in [('p2wlan.mixins.json', contract.MIXIN_CONFIG), ('p2wlan.client.mixins.json', contract.CLIENT_MIXIN_CONFIG)]:
                top[name] = universal.encoded(config)
            top[contract.MIXIN_CONFIG['refmap']] = universal.encoded(contract.REFMAP)
            top[contract.CLIENT_MIXIN_CONFIG['refmap']] = universal.encoded(contract.CLIENT_REFMAP_VALUE)
            name = universal.GAME + 'mixin/MultiplayerListMixin.class'
            top[name] = bytecode(name, marker='(Lnet/minecraft/class_310;IIIII)V')
            top.update({name: bytecode(name) for name in universal.BOOTSTRAP_CLASSES})
        return universal.archive_bytes(top)

    def inputs(self, release=False):
        sources = [self.source(loader, release) for loader in ("fabric", "neoforge", "forge")]
        relocated = universal.archive_bytes(universal.expected_relocation(universal.members(sources[0])))
        boot = {name: bytecode(name, 61) for name in universal.BOOTSTRAP_CLASSES}
        boot.update({name: universal.encoded(value) for name, value in universal.CONFIGS.items()})
        return [*sources, relocated, universal.archive_bytes(boot), self.legacy_source('forge', release), self.legacy_source('fabric', release),
                self.older_source('forge', release), self.older_source('fabric', release), self.older_source('fabric', release, '1.16.5'), self.older_source('forge', release, '1.16.5'), self.carrier()]

    def carrier(self):
        contract = universal.verify_legacy_bootstrap
        files = {name: bytecode(name, neutral=True) for name in contract.CLASSES}
        files[contract.SERVICE] = contract.SERVICE_BYTES
        files['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\r\n\r\n'
        return universal.archive_bytes(files)

    def older_source(self, loader, release=False, minecraft='1.18.2'):
        role = loader + '_' + minecraft.replace('.', '')
        contract = universal.older_contract(role)
        top = universal.members(self.legacy_source(loader, release))
        for name in tuple(top):
            if name.endswith('.class') and name.startswith((universal.GAME, 'io/p2wlan/mc/' + loader + '/')):
                top.pop(name)
        neutral = universal.FORGE_NEUTRAL if loader == 'forge' else universal.FABRIC_NEUTRAL
        top.update({name: bytecode(name, marker=loader, neutral=name in neutral) for name in universal.role_loader_classes(role)})
        top.update({name: bytecode(name, marker=role) for name in universal.role_game_classes(role)})
        if loader == 'forge':
            old = universal.legacy_forge_contract
            pin = universal.SDK_INPUTS[role].loader_pin.version
            top['META-INF/mods.toml'] = top['META-INF/mods.toml'].replace(b'[47,)', ('[' + pin.split('.')[0] + ',)').encode()).replace(b'[47.3.2]', ('[' + pin + ']').encode()).replace(b'[1.20.1]', ('[' + minecraft + ']').encode())
            for name in (old.SERVER_CONFIG, old.CLIENT_CONFIG, old.SERVER_REFMAP, old.CLIENT_REFMAP): top.pop(name)
            top[contract.SERVER_CONFIG] = universal.encoded(contract.MIXIN_CONFIG)
            top[contract.CLIENT_CONFIG] = universal.encoded(contract.CLIENT_MIXIN_CONFIG)
            for name, targets in ((contract.SERVER_REFMAP, contract.SERVER_TARGETS), (contract.CLIENT_REFMAP, contract.CLIENT_TARGETS)):
                top[name] = universal.encoded({'mappings': targets, 'data': {'searge': targets}})
            top['META-INF/MANIFEST.MF'] = ('Manifest-Version: 1.0\r\nMixinConfigs: ' + contract.SERVER_CONFIG + ',' + contract.CLIENT_CONFIG + '\r\n\r\n').encode()
            top['pack.mcmeta'] = universal.encoded({'pack': {'description': 'P2WLAN', 'pack_format': universal.SDK_INPUTS[role].fixture_pack_format}})
        else:
            import verify_fabric_legacy_jar as old
            descriptor = json.loads(top['fabric.mod.json']); descriptor['depends'] = contract.DEPENDS
            top['fabric.mod.json'] = universal.encoded(descriptor)
            for name in (old.SERVER_REFMAP, old.CLIENT_REFMAP): top.pop(name)
            top['p2wlan.mixins.json'] = universal.encoded(contract.MIXIN_CONFIG)
            top['p2wlan.client.mixins.json'] = universal.encoded(contract.CLIENT_MIXIN_CONFIG)
            top[contract.SERVER_REFMAP] = universal.encoded(contract.REFMAP)
            top[contract.CLIENT_REFMAP] = universal.encoded(contract.CLIENT_REFMAP_VALUE)
        for owner, field, _ in contract.SHADOW_FIELDS:
            name = universal.GAME + owner + '.class'
            marker = '(Lnet/minecraft/class_310;IIIII)V' if loader == 'fabric' and owner == 'mixin/MultiplayerListMixin' else role
            top[name] = bytecode(name, marker=marker, fields=(field.decode(),))
        if loader == 'forge':
            top[contract.ACTIVATION_MIXIN] = bytecode(contract.ACTIVATION_MIXIN,
                    marker=' '.join(token.decode() for token in sorted(contract.ACTIVATION_TOKENS)), fields=('activationHandler',))
        if role == 'forge_1165':
            top = {name: data for name, data in top.items() if name.endswith('.class') and not name.startswith(universal.BOOTSTRAP)
                   or name in {'META-INF/MANIFEST.MF', 'META-INF/mods.toml', 'pack.mcmeta', contract.SERVER_CONFIG,
                               contract.CLIENT_CONFIG, contract.SERVER_REFMAP, contract.CLIENT_REFMAP}}
        return universal.archive_bytes(top)

    def assemble(self, inputs, development=True):
        return universal.build_bytes(*inputs[:5], development=development, legacy_forge=inputs[5], legacy_fabric=inputs[6],
                                     forge_1182=inputs[7], fabric_1182=inputs[8], fabric_1165=inputs[9], forge_1165=inputs[10], legacy_bootstrap=inputs[11])

    def build(self, release=False):
        return self.assemble(self.inputs(release), development=not release)

    def mutate(self, value, change, rehash=False):
        files = universal.members(value); change(files)
        if rehash:
            info = json.loads(files[universal.INDEX])
            info["files"] = universal.records({name: body for name, body in files.items() if name != universal.INDEX})
            files[universal.INDEX] = universal.encoded(info)
        return universal.archive_bytes(files)

    def check(self, value, development=True):
        return universal.verify(io.BytesIO(value), development)

    def test_deterministic_one_outer_archive_and_one_physical_core_registration(self):
        value = self.build(); self.assertEqual(value, self.build())
        info = self.check(value)
        self.assertEqual(info["artifact_sha256"], universal.digest(value))
        self.assertEqual(info["loaders"], universal.PINS)
        files = universal.members(value)
        self.assertEqual(len(universal.classes(files, universal.GAME)), 49)
        self.assertEqual(len(universal.classes(files, universal.FABRIC_GAME)), 49)
        self.assertEqual({name for name in files if name.endswith(".jar")}, set(universal.CORE_PATHS.values()) | {universal.JNA})
        fabric = json.loads(files["fabric.mod.json"])
        self.assertEqual({item["file"] for item in fabric["jars"]}, set(universal.CORE_PATHS.values()) | {universal.JNA})
        jarjar = json.loads(files["META-INF/jarjar/metadata.json"])
        self.assertEqual({item["path"] for item in jarjar["jars"]}, set(universal.CORE_PATHS.values()))
        for component, path in universal.CORE_PATHS.items():
            child = universal.members(files[path]); descriptor = json.loads(child["fabric.mod.json"])
            self.assertEqual(descriptor["id"], "p2wlan_" + component)
            self.assertFalse(any(name.startswith("com/sun/jna/") for name in child))
        self.assertIn(b"io.p2wlan.mc.nativebundle", universal.members(files[universal.CORE_PATHS["native"]])["META-INF/MANIFEST.MF"])

    def test_sixteen_narrow_dispatch_configs_cannot_capture_ordinary_game_or_core_packages(self):
        files = universal.members(self.build())
        expected = {"io.p2wlan.mc.game.server.mixin", "io.p2wlan.mc.game.mixin",
                    "io.p2wlan.mc.fabric.game.server.mixin", "io.p2wlan.mc.fabric.game.mixin",
                    "io.p2wlan.mc.game.v1_20_1.server.mixin", "io.p2wlan.mc.game.v1_20_1.mixin",
                    "io.p2wlan.mc.fabric.game.v1_20_1.server.mixin", "io.p2wlan.mc.fabric.game.v1_20_1.mixin",
                    "io.p2wlan.mc.game.v1_18_2.server.mixin", "io.p2wlan.mc.game.v1_18_2.mixin",
                    "io.p2wlan.mc.fabric.game.v1_18_2.server.mixin", "io.p2wlan.mc.fabric.game.v1_18_2.mixin",
                    "io.p2wlan.mc.fabric.game.v1_16_5.server.mixin", "io.p2wlan.mc.fabric.game.v1_16_5.mixin",
                    "io.p2wlan.mc.game.v1_16_5.server.mixin", "io.p2wlan.mc.game.v1_16_5.mixin"}
        self.assertEqual({json.loads(files[name])["package"] for name in universal.CONFIGS}, expected)
        self.assertEqual(len(universal.CONFIGS), 16)
        self.assertTrue(all(json.loads(files[name])["mixins"] == [] for name in universal.CONFIGS))
        fabric = json.loads(files["fabric.mod.json"])
        self.assertEqual(fabric["mixins"], universal.FABRIC_MIXINS)
        self.assertNotIn(universal.FABRIC_SERVER_CONFIG.encode(), files["META-INF/MANIFEST.MF"])

    def test_four_version_catalogs_use_one_neutral_entry_and_one_core(self):
        inputs = self.inputs(); files = universal.members(self.assemble(inputs))
        self.assertEqual(set(self.check(universal.archive_bytes(files))['minecraft']), {'1.16.5', '1.18.2', '1.20.1', '1.21.1'})
        for destination in (universal.GAME, universal.FABRIC_GAME, universal.LEGACY, universal.FABRIC_LEGACY):
            catalog = universal.GAME_CLASSES | (universal.LEGACY_FABRIC_GAME_EXTRAS if destination == universal.FABRIC_LEGACY
                                                 else universal.MODERN_GAME_EXTRAS if destination in (universal.GAME, universal.FABRIC_GAME) else frozenset())
            expected = catalog if destination == universal.GAME else {universal.relocate_name(name, destination) for name in catalog}
            self.assertEqual(set(universal.classes(files, destination)), set(expected))
        self.assertEqual(sum(len(universal.classes(files, destination)) for destination in
                             (universal.GAME, universal.FABRIC_GAME, universal.LEGACY, universal.FABRIC_LEGACY)), 195)
        for role, destination in (('forge_1182', universal.FORGE_1182_GAME), ('fabric_1182', universal.FABRIC_1182_GAME)):
            expected = {universal.relocate_name(name, destination) for name in universal.role_game_classes(role)}
            self.assertEqual(set(universal.classes(files, destination)), expected)
        self.assertEqual(sum(len(universal.classes(files, destination)) for destination in
                             (universal.GAME, universal.FABRIC_GAME, universal.LEGACY, universal.FABRIC_LEGACY,
                              universal.FORGE_1182_GAME, universal.FABRIC_1182_GAME)), 299)
        sixteen = universal.SDK_INPUTS['fabric_1165'].binding.class_prefix
        self.assertEqual(set(universal.classes(files, sixteen)),
                         {universal.relocate_name(name, sixteen) for name in universal.role_game_classes('fabric_1165')})
        self.assertEqual(len(universal.game_catalog()), 405)
        self.assertEqual(self.check(universal.archive_bytes(files))['loaders']['1.16.5'], {'fabric': '0.16.14', 'forge': '36.2.42'})
        old_forge = universal.members(inputs[5])
        for name in universal.FORGE_NEUTRAL:
            self.assertEqual(files[name], old_forge[name])
        for name in (universal.LEGACY_FORGE_SERVER_REFMAP, universal.LEGACY_FORGE_CLIENT_REFMAP):
            self.assertEqual(json.loads(files[name])['data'].keys(), {'searge'})
        for name in (universal.LEGACY_FABRIC_SERVER_REFMAP, universal.LEGACY_FABRIC_CLIENT_REFMAP):
            self.assertEqual(json.loads(files[name])['data'].keys(), {'named:intermediary'})
        self.assertTrue(all(len(line) <= 72 for line in files['META-INF/MANIFEST.MF'].splitlines()))
        self.assertTrue(all(name.encode() in files['META-INF/MANIFEST.MF'].replace(b'\r\n ', b'') for name in universal.FML_CONFIGS))

    def test_legacy_catalog_and_refmaps_fail_closed_even_after_index_rehash(self):
        value = self.build()
        def changed_class(files):
            name = universal.LEGACY + 'client/GameUi.class'
            files[universal.LEGACY + 'client/UnownedGameUi.class'] = files.pop(name)
        def wrong_context(files):
            name = universal.LEGACY_FORGE_SERVER_REFMAP
            data = json.loads(files[name]); data['data'] = {'named:intermediary': data['mappings']}
            files[name] = universal.encoded(data)
        def wrong_target(files):
            name = universal.LEGACY_FORGE_CLIENT_REFMAP
            files[name] = files[name].replace(b'm_99780_', b'refreshEntries')
        for change in (changed_class, wrong_context, wrong_target):
            with self.assertRaises(universal.VerificationError):
                self.check(self.mutate(value, change, True))

    def test_1182_requires_both_sdk_inputs_and_admits_only_fabric_and_forge(self):
        inputs = self.inputs()
        roles = ('fabric', 'neoforge', 'forge', 'legacy_forge', 'legacy_fabric', 'forge_1182', 'fabric_1182', 'fabric_1165', 'forge_1165')
        selected = {role: inputs[index] for role, index in zip(roles, (0, 1, 2, 5, 6, 7, 8, 9, 10))}
        self.assertEqual(set(selected), set(universal.sdk_inputs()))
        for missing in ('forge_1182', 'fabric_1182', 'fabric_1165', 'forge_1165'):
            sources = dict(selected); del sources[missing]
            with self.subTest(missing=missing), self.assertRaisesRegex(universal.VerificationError, 'universal_input_identity'):
                universal.build_inputs(sources, inputs[4], True, legacy_bootstrap=inputs[11])
        sources = dict(selected); sources['neoforge_1182'] = inputs[7]
        with self.assertRaisesRegex(universal.VerificationError, 'universal_input_identity'):
            universal.build_inputs(sources, inputs[4], True, legacy_bootstrap=inputs[11])
        self.assertEqual(universal.PINS['1.18.2'], {'fabric': '0.16.14', 'forge': '40.3.12'})
        self.assertEqual(self.check(self.build())['loaders']['1.18.2'], universal.PINS['1.18.2'])

    def test_older_source_mapping_and_shadow_mutations_fail_before_assembly(self):
        import verify_forge_1182_jar as forge18
        import verify_fabric_1182_jar as fabric18
        import verify_fabric_1165_jar as fabric16
        for index, contract in ((7, forge18), (8, fabric18), (9, fabric16)):
            inputs = self.inputs(); files = universal.members(inputs[index])
            mapping = json.loads(files[contract.CLIENT_REFMAP]); mapping['data'] = {}
            files[contract.CLIENT_REFMAP] = universal.encoded(mapping); inputs[index] = universal.archive_bytes(files)
            with self.subTest(index=index, mutation='refmap'), self.assertRaises(universal.VerificationError): self.assemble(inputs)
            for owner, _, original in contract.SHADOW_FIELDS:
                inputs = self.inputs(); files = universal.members(inputs[index]); name = universal.GAME + owner + '.class'
                marker = '(Lnet/minecraft/class_310;IIIII)V' if index in (8, 9) and owner == 'mixin/MultiplayerListMixin' else 'old shadow'
                files[name] = bytecode(name, marker=marker, fields=(original.decode(),))
                inputs[index] = universal.archive_bytes(files)
                with self.subTest(index=index, owner=owner), self.assertRaisesRegex(universal.VerificationError, 'forge1182_shadow_srg|fabric(?:1182|1165)_shadow_intermediary'):
                    self.assemble(inputs)

    def test_older_output_catalog_refmaps_and_actual_shadow_fields_fail_after_rehash(self):
        import verify_forge_1182_jar as forge18
        import verify_fabric_1182_jar as fabric18
        import verify_fabric_1165_jar as fabric16
        value = self.build()
        for role, destination, contract in (('forge_1182', universal.FORGE_1182_GAME, forge18),
                                            ('fabric_1182', universal.FABRIC_1182_GAME, fabric18),
                                            ('fabric_1165', universal.SDK_INPUTS['fabric_1165'].binding.class_prefix, fabric16)):
            source = universal.SDK_INPUTS[role]
            for refmap in (source.binding.mixins.server_refmap, source.binding.mixins.client_refmap):
                def changed(files):
                    data = json.loads(files[refmap]); data['data'] = {}; files[refmap] = universal.encoded(data)
                with self.subTest(role=role, refmap=refmap), self.assertRaises(universal.VerificationError):
                    self.check(self.mutate(value, changed, True))
                def changed_target(files):
                    data = json.loads(files[refmap]); owner = next(iter(data['mappings'])); selector = next(iter(data['mappings'][owner]))
                    data['mappings'][owner][selector] = 'Lnet/minecraft/Unrelated;wrong()V'
                    data['data'] = {key: data['mappings'] for key in data['data']}; files[refmap] = universal.encoded(data)
                with self.subTest(role=role, target=refmap), self.assertRaises(universal.VerificationError):
                    self.check(self.mutate(value, changed_target, True))
            for owner, _, original in contract.SHADOW_FIELDS:
                raw = universal.GAME + owner + '.class'; name = universal.relocate_name(raw, destination)
                replacement = universal.relocate_class(bytecode(raw, fields=(original.decode(),)), destination)
                with self.subTest(role=role, shadow=owner), self.assertRaises(universal.VerificationError):
                    self.check(self.mutate(value, lambda files: files.update({name: replacement}), True))
            name = destination + 'client/GameUi.class'
            def changed_owner(files): files[destination + 'client/UnownedGameUi.class'] = files.pop(name)
            with self.subTest(role=role, mutation='owner'), self.assertRaisesRegex(universal.VerificationError, 'universal_game_layout'):
                self.check(self.mutate(value, changed_owner, True))

    def test_1182_forge_activation_cannot_be_replaced_by_fabric_marker_after_rehash(self):
        import verify_forge_1182_jar as forge18
        value = self.build(); destination = universal.FORGE_1182_GAME
        name = universal.relocate_name(forge18.ACTIVATION_MIXIN, destination)
        marker = universal.relocate_class(bytecode(forge18.ACTIVATION_MIXIN, marker='Fabric identity marker'), destination)
        with self.assertRaisesRegex(universal.VerificationError, 'forge1182_client_activation_contract'):
            self.check(self.mutate(value, lambda files: files.update({name: marker}), True))

    def test_forge16_requires_exact_locator_and_has_no_separate_portable_owner(self):
        inputs = self.inputs()
        with self.assertRaisesRegex(universal.VerificationError, 'legacy_bootstrap_required'):
            universal.build_bytes(*inputs[:5], True, legacy_forge=inputs[5], legacy_fabric=inputs[6],
                                  forge_1182=inputs[7], fabric_1182=inputs[8], fabric_1165=inputs[9], forge_1165=inputs[10])
        for extra in ('META-INF/jars/common.jar', 'p2wlan/native/fake/manifest.properties'):
            changed = list(inputs); files = universal.members(changed[10]); files[extra] = b'unknown portable source'
            changed[10] = universal.archive_bytes(files)
            with self.subTest(extra=extra), self.assertRaises(universal.VerificationError):
                self.assemble(changed)
        changed = list(inputs); files = universal.members(changed[11])
        files[universal.legacy_core_index.SERVICE] = b'unknown.LegacyLocator\n'
        changed[11] = universal.archive_bytes(files)
        with self.assertRaisesRegex(universal.VerificationError, 'legacy_bootstrap_service'):
            self.assemble(changed)

    def test_locator_index_and_private_jni_cannot_be_rehashed_into_validity(self):
        value = self.build(); files = universal.members(value)
        self.assertGreater(len(files), 512)
        self.assertLessEqual(len(files), universal.MAX_ENTRIES)
        self.assertEqual(files[universal.legacy_core_index.SERVICE], universal.verify_legacy_bootstrap.SERVICE_BYTES)
        self.assertEqual({name for name in files if name.startswith(universal.verify_legacy_bootstrap.PREFIX)},
                         universal.verify_legacy_bootstrap.CLASSES)
        index = universal.legacy_core_index.index_values(files[universal.legacy_core_index.INDEX])
        for owner, path in universal.legacy_core_index.OWNERS.items():
            self.assertEqual(index[owner + '.sha256'], universal.digest(files[path]))
        def bad_index(files):
            path = universal.legacy_core_index.INDEX
            files[path] = files[path].replace(index['common.sha256'].encode(), b'0' * 64)
        with self.assertRaisesRegex(universal.VerificationError, 'legacy_core_identity'):
            self.check(self.mutate(value, bad_index, True))
        def bad_jni(files):
            members = universal.members(files[universal.JNA])
            name = 'p2wlan/jna/win32-x86-64/jnidispatch.dll'
            self.assertIn(name, members)
            self.assertNotIn('com/sun/jna/win32-x86-64/jnidispatch.dll', members)
            members[name] = b'changed JNI payload'
            files[universal.JNA] = universal.archive_bytes(members)
        with self.assertRaisesRegex(universal.VerificationError, 'universal_jna_resource_identity'):
            self.check(self.mutate(value, bad_jni, True))
        with self.assertRaisesRegex(universal.VerificationError, 'universal_archive_size'):
            universal.archive_bytes({str(index): b'x' for index in range(universal.MAX_ENTRIES + 1)})

    def test_service_descriptor_cannot_be_renamed_or_replaced_after_rehash(self):
        import universal_module
        value = self.build(); files = universal.members(value)
        self.assertEqual(files[universal_module.NAME], universal_module.descriptor())
        for replacement in (bytecode(universal_module.NAME),
                            universal_module.descriptor().replace(b'p2wlan.bootstrap', b'p2wlan.broadcast')):
            with self.subTest(replacement=replacement[:16]), self.assertRaisesRegex(
                    universal.VerificationError, 'universal_service_module'):
                self.check(self.mutate(value, lambda files: files.update({universal_module.NAME: replacement}), True))
        with self.assertRaisesRegex(universal.VerificationError, 'universal_loader_class_catalog'):
            self.check(self.mutate(value, lambda files: files.pop(universal_module.NAME), True))

    def test_fabric20_login_mixin_is_required_only_in_its_exact_source_profile(self):
        name = universal.LEGACY_FABRIC_LOGIN_MIXIN
        self.assertEqual(universal.LEGACY_FABRIC_GAME_EXTRAS, {name})
        self.assertNotIn(name, universal.GAME_CLASSES)
        inputs = self.inputs(); old = universal.members(inputs[6]); del old[name]
        inputs[6] = universal.archive_bytes(old)
        with self.assertRaisesRegex(universal.VerificationError, 'mod_archive_member_invalid'):
            self.assemble(inputs)
        for index in (0, 1, 2, 5):
            inputs = self.inputs(); source = universal.members(inputs[index]); source[name] = bytecode(name)
            inputs[index] = universal.archive_bytes(source)
            with self.subTest(index=index), self.assertRaises(universal.VerificationError):
                self.assemble(inputs)

    def test_fabric20_login_mixin_cannot_move_or_duplicate_into_an_inactive_namespace(self):
        value = self.build()
        name = universal.relocate_name(universal.LEGACY_FABRIC_LOGIN_MIXIN, universal.FABRIC_LEGACY)
        forbidden = [universal.LEGACY_FABRIC_LOGIN_MIXIN, *[
            universal.relocate_name(universal.LEGACY_FABRIC_LOGIN_MIXIN, destination)
            for destination in (universal.FABRIC_GAME, universal.LEGACY)]]
        forbidden.append(name.replace('FabricLoginNetworkingMixin', 'UnlistedLoginNetworkingMixin'))
        for target in forbidden:
            for remove in (True, False):
                def changed(files):
                    files[target] = files[name]
                    if remove: del files[name]
                with self.subTest(target=target, remove=remove), self.assertRaisesRegex(universal.VerificationError, 'universal_game_layout'):
                    self.check(self.mutate(value, changed, True))

    def test_fabric20_api_login_mixin_adds_no_mc_refmap_keys(self):
        value = self.build()
        name = universal.relocate_name(universal.LEGACY_FABRIC_LOGIN_MIXIN, universal.FABRIC_LEGACY).removesuffix('.class')
        def changed(files):
            refmap = json.loads(files[universal.LEGACY_FABRIC_CLIENT_REFMAP])
            refmap['mappings'][name] = {}
            files[universal.LEGACY_FABRIC_CLIENT_REFMAP] = universal.encoded(refmap)
        with self.assertRaisesRegex(universal.VerificationError, 'universal_legacy_fabric_refmap_contract'):
            self.check(self.mutate(value, changed, True))

    def test_embedded_bootstrap_is_canonical_and_neutral_entry_changes_are_rejected(self):
        for index in (6, 8):
            inputs = self.inputs(); source = universal.members(inputs[index])
            name = next(iter(universal.BOOTSTRAP_CLASSES)); source[name] += b'changed'
            inputs[index] = universal.archive_bytes(source)
            with self.subTest(index=index), self.assertRaisesRegex(universal.VerificationError, 'universal_embedded_bootstrap_changed'):
                self.assemble(inputs)
        for index, catalog in ((5, universal.FORGE_NEUTRAL), (6, universal.FABRIC_NEUTRAL),
                               (7, universal.FORGE_NEUTRAL), (8, universal.FABRIC_NEUTRAL)):
            inputs = self.inputs(); source = universal.members(inputs[index])
            name = next(iter(catalog)); source[name] = bytecode(name, marker='different application entry', neutral=True)
            inputs[index] = universal.archive_bytes(source)
            with self.assertRaisesRegex(universal.VerificationError, 'universal_neutral_entry_changed'):
                self.assemble(inputs)

    def test_neutral_normalization_preserves_code_and_only_allows_cp_index_order(self):
        name = 'io/p2wlan/mc/forge/ForgeP2wlan.class'
        left = bytecode(name, neutral=True)
        def utf8(value):
            body = value.encode(); return b'\x01' + struct.pack('>H', len(body)) + body
        first = utf8(name.removesuffix('.class')); second = utf8('java/lang/Object')
        third = utf8('named'); fourth = utf8('Ljava/lang/Object;')
        start = 10 + len(first) + len(second)
        right = left[:start] + fourth + third + left[start + len(third) + len(fourth):]
        self.assertNotEqual(left, right)
        self.assertEqual(universal.constant_symbols(left), universal.constant_symbols(right))
        universal.neutral_equivalent(name, left, right)
        # An access change keeps the CP inventory but changes the decoded class.
        changed = right[:-14] + struct.pack('>H', 0x31) + right[-12:]
        with self.assertRaisesRegex(universal.VerificationError, 'universal_neutral_entry_changed'):
            universal.neutral_equivalent(name, left, changed)
        with self.assertRaisesRegex(universal.VerificationError, 'universal_neutral_class_structure'):
            universal.neutral_equivalent(name, left, right + b'unowned trailing payload')
        with self.assertRaisesRegex(universal.VerificationError, 'universal_neutral_class_catalog'):
            universal.neutral_equivalent(universal.GAME + 'client/GameUi.class', left, right)

    def test_game_and_core_byte_identity_cannot_diverge_between_fml_inputs(self):
        for component in ("game", "common", "transport"):
            inputs = self.inputs(); files = universal.members(inputs[1])
            if component == "game":
                name = next(iter(universal.classes(files, universal.GAME))); files[name] += b"changed"
            else:
                path = universal.CORE_PATHS[component]; child = universal.members(files[path])
                name = next(name for name in child if name.endswith(".class")); child[name] += b"changed"
                files[path] = universal.archive_bytes(child)
            inputs[1] = universal.archive_bytes(files)
            with self.subTest(component=component), self.assertRaisesRegex(universal.VerificationError, "universal_(game|core)_changed"):
                self.assemble(inputs)

    def test_native_consistency_includes_actual_executable_not_only_source_identity(self):
        inputs = self.inputs(); files = universal.members(inputs[1]); path = universal.CORE_PATHS["native"]
        child = universal.members(files[path]); manifest = next(name for name in child if name.endswith("/manifest.properties"))
        binary = next(name for name in child if name.startswith("p2wlan/native/") and not name.endswith("/manifest.properties"))
        old = child[binary]; new = b"different native fixture bytes"
        child[binary] = new
        child[manifest] = child[manifest].decode().replace(universal.digest(old), universal.digest(new)).replace("executable-size=" + str(len(old)), "executable-size=" + str(len(new))).encode()
        files[path] = universal.archive_bytes(child); inputs[1] = universal.archive_bytes(files)
        with self.assertRaisesRegex(universal.VerificationError, "universal_native_identity"):
            self.assemble(inputs)

    def test_received_fabric_relocation_must_equal_exact_source_transform(self):
        for mutation in ("body", "path", "refmap", "adapter"):
            inputs = self.inputs(); files = universal.members(inputs[3])
            if mutation in ("body", "adapter"):
                name = next(iter(universal.classes(files, universal.FABRIC_GAME))) if mutation == "body" else universal.fabric_contract.MAIN
                files[name] += b"untrusted bytes"
            elif mutation == "path":
                name = next(iter(universal.classes(files, universal.FABRIC_GAME))); files[universal.changed_name(name) + "extra"] = files.pop(name)
            else: files[universal.SERVER_REFMAP] = b'{}'
            inputs[3] = universal.archive_bytes(files)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(universal.VerificationError, "universal_relocation_(changed|layout)"):
                self.assemble(inputs)

    def test_bootstrap_cannot_add_wrong_namespace_tests_or_wide_dispatch(self):
        for mutation in ("owner", "test", "package", "missing_plugin"):
            inputs = self.inputs(); files = universal.members(inputs[4])
            if mutation == "owner": files["rogue/Bootstrap.class"] = bytecode("rogue/Bootstrap.class", 61)
            elif mutation == "test": files[universal.BOOTSTRAP + "BootstrapTests.class"] = bytecode(universal.BOOTSTRAP + "BootstrapTests.class", 61)
            elif mutation == "missing_plugin": files.pop(next(iter(universal.PLUGIN_CLASSES)))
            else:
                config = json.loads(files[universal.SERVER_CONFIG]); config["package"] = "io.p2wlan.mc"
                files[universal.SERVER_CONFIG] = universal.encoded(config)
            inputs[4] = universal.archive_bytes(files)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(universal.VerificationError, "universal_(bootstrap_layout|test_payload|dispatch_contract)"):
                self.assemble(inputs)

    def test_sources_cannot_smuggle_unknown_classes_fixtures_or_slim_forge(self):
        for mutation in ("owner", "test", "slim"):
            inputs = self.inputs(); files = universal.members(inputs[2])
            if mutation == "owner": files["rogue/Bootstrap.class"] = bytecode("rogue/Bootstrap.class")
            elif mutation == "test": files["io/p2wlan/mc/forge/ExtraTests.class"] = bytecode("io/p2wlan/mc/forge/ExtraTests.class")
            else: files = {name: body for name, body in files.items() if not name.startswith("META-INF/jarjar/")}
            inputs[2] = universal.archive_bytes(files)
            with self.subTest(mutation=mutation), self.assertRaises(universal.VerificationError):
                self.assemble(inputs)

    def test_current_loader_catalog_rejects_deleted_network_and_world_owners(self):
        cases = (("fabric", "io/p2wlan/mc/fabric/client/FabricGameJoins.class"),
                 ("fabric", "io/p2wlan/mc/fabric/client/screen/AccountScreen.class"),
                 ("fabric", "io/p2wlan/mc/fabric/world/FabricWorlds$World.class"),
                 ("neoforge", "io/p2wlan/mc/neoforge/WorldCommands.class"),
                 ("neoforge", "io/p2wlan/mc/neoforge/mixin/ServerConnectionMixin.class"),
                 ("forge", "io/p2wlan/mc/forge/client/ForgeClient$LegacyWorld.class"))
        for loader, name in cases:
            inputs = self.inputs(); index = ("fabric", "neoforge", "forge").index(loader)
            files = universal.members(inputs[index]); files[name] = bytecode(name)
            inputs[index] = universal.archive_bytes(files)
            with self.subTest(loader=loader, name=name), self.assertRaisesRegex(universal.VerificationError, "universal_loader_class_catalog"):
                self.assemble(inputs)
        for loader, catalog in universal.LOADER_CLASSES.items():
            inputs = self.inputs(); index = ("fabric", "neoforge", "forge").index(loader)
            files = universal.members(inputs[index]); name = next(name for name in catalog if "$" in name)
            del files[name]; inputs[index] = universal.archive_bytes(files)
            with self.subTest(loader=loader, missing=name), self.assertRaisesRegex(universal.VerificationError, "universal_loader_class_catalog"):
                self.assemble(inputs)

    def test_bootstrap_catalog_rejects_unknown_outer_and_nested_families(self):
        for name in (universal.BOOTSTRAP + "LegacyRuntime.class", universal.BOOTSTRAP + "ServerMixinPlugin$OldOwner.class",
                     universal.BOOTSTRAP + "UniversalEnvironment$Unexpected.class"):
            inputs = self.inputs(); files = universal.members(inputs[4]); files[name] = bytecode(name, 61)
            inputs[4] = universal.archive_bytes(files)
            with self.subTest(name=name), self.assertRaisesRegex(universal.VerificationError, "universal_bootstrap_layout"):
                self.assemble(inputs)
        value = self.build()
        for name in ("io/p2wlan/mc/fabric/client/FabricGameJoins.class", universal.BOOTSTRAP + "ServerMixinPlugin$OldOwner.class"):
            with self.subTest(name=name), self.assertRaisesRegex(universal.VerificationError, "universal_loader_class_catalog"):
                self.check(self.mutate(value, lambda files: files.update({name: bytecode(name, 61)}), True))

    def test_output_extra_payload_and_structural_changes_fail_after_index_rehash(self):
        value = self.build()
        changes = (
            lambda files: files.update({"config/private.json": b"not admitted"}),
            lambda files: files.update({"io/p2wlan/mc/common/BundledAgent.class": bytecode("io/p2wlan/mc/common/BundledAgent.class", 61)}),
            lambda files: files.update({"io/p2wlan/mc/game/fixture/GameClientChecks.class": bytecode("io/p2wlan/mc/game/fixture/GameClientChecks.class")}),
            lambda files: files.update({"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nMixinConfigs: p2wlan.forge.mixins.json\r\n\r\n"}),
            lambda files: files.update({universal.SERVER_REFMAP: b"{}"}),
        )
        for index, change in enumerate(changes):
            with self.subTest(index=index), self.assertRaises(universal.VerificationError):
                self.check(self.mutate(value, change, True))

    def test_loader_dependency_and_native_module_contracts_are_pinned(self):
        value = self.build()
        for name, before, after in (("META-INF/mods.toml", universal.FORGE_RANGE.encode(), b"[47,)"),
                                   ("META-INF/neoforge.mods.toml", b"[21.1.256]", b"[21.1,)")):
            with self.subTest(name=name), self.assertRaisesRegex(universal.VerificationError, "universal_dependency_contract"):
                self.check(self.mutate(value, lambda files: files.update({name: files[name].replace(before, after)}), True))
        def changed(files):
            path = universal.CORE_PATHS["native"]; child = universal.members(files[path])
            child["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\nAutomatic-Module-Name: wrong\r\n\r\n"
            files[path] = universal.archive_bytes(child)
        with self.assertRaisesRegex(universal.VerificationError, "universal_native_module_identity"):
            self.check(self.mutate(value, changed, True))

    def test_jarjar_never_registers_jna_and_all_fabric_library_ids_are_exact(self):
        value = self.build()
        def registered(files):
            name = "META-INF/jarjar/metadata.json"; data = json.loads(files[name]); data["jars"].append(data["jars"][0] | {"path": universal.JNA})
            files[name] = universal.encoded(data)
        with self.assertRaisesRegex(universal.VerificationError, "universal_jarjar_contract"):
            self.check(self.mutate(value, registered, True))
        for component, path in universal.CORE_PATHS.items():
            def changed(files):
                child = universal.members(files[path]); child["fabric.mod.json"] = universal.encoded(universal.nested_descriptor(component) | {"id": "wrong"})
                files[path] = universal.archive_bytes(child)
            with self.subTest(component=component), self.assertRaisesRegex(universal.VerificationError, "universal_nested_fabric_contract"):
                self.check(self.mutate(value, changed, True))

    def test_release_policy_and_source_change_are_not_bypassed(self):
        with self.assertRaisesRegex(universal.VerificationError, "mod_development_native_disabled"):
            self.assemble(self.inputs(), development=False)
        released = self.build(release=True); self.assertEqual(self.check(released, False)["build_mode"], "release")
        self.bind.side_effect = [None, None, None, universal.VerificationError("mod_native_sources_changed_rebuild_bundle")]
        with self.assertRaisesRegex(universal.VerificationError, "mod_native_sources_changed_rebuild_bundle"):
            self.build()

    def test_index_input_identities_are_bounded_and_not_free_text(self):
        value = self.build()
        for mutation in ({"size": True}, {"size": 0}, {"sha256": "private credential text"}, {"extra": True}):
            def changed(files):
                info = json.loads(files[universal.INDEX]); info["inputs"]["bootstrap"].update(mutation)
                files[universal.INDEX] = universal.encoded(info)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(universal.VerificationError, "universal_input_identity"):
                self.check(self.mutate(value, changed))

    def test_expanded_index_has_a_fixed_bound_and_rejects_duplicate_input_keys(self):
        value = self.build(); original = universal.members(value)[universal.INDEX]
        self.assertGreater(len(original), 65536)
        self.assertLessEqual(len(original), universal.MAX_INDEX)
        def enlarged(files): files[universal.INDEX] = b' ' * universal.MAX_INDEX + files[universal.INDEX]
        with self.assertRaisesRegex(universal.VerificationError, 'mod_archive_member_invalid'):
            self.check(self.mutate(value, enlarged))
        def duplicated(files):
            info = json.loads(files[universal.INDEX]); section = json.dumps(info['inputs'])
            body = json.dumps(info); self.assertEqual(body.count(section), 1)
            duplicate = section[:-1] + ', "fabric": ' + json.dumps(info['inputs']['fabric']) + '}'
            files[universal.INDEX] = body.replace(section, duplicate).encode()
        with self.assertRaises(universal.VerificationError):
            self.check(self.mutate(value, duplicated))

    def test_snapshot_bounds_and_create_only_atomic_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); path = root / "universal.jar"; value = self.build()
            universal.publish(path, value); universal.publish(path, value)
            self.assertEqual(universal.regular_bytes(path), value)
            self.assertEqual(universal.verify(path, True)["artifact_sha256"], universal.digest(value))
            with self.assertRaisesRegex(universal.VerificationError, "universal_output_exists"):
                universal.publish(path, b"changed")
            self.assertEqual(path.read_bytes(), value)
            link = root / "linked.jar"; link.symlink_to(path)
            with self.assertRaisesRegex(universal.VerificationError, "universal_input_file"):
                universal.regular_bytes(link)
            with patch.object(universal, "MAX_ARCHIVE", len(value) - 1), self.assertRaisesRegex(universal.VerificationError, "universal_input_file"):
                universal.regular_bytes(path)
            self.assertEqual(sorted(item.name for item in root.iterdir()), ["linked.jar", "universal.jar"])

    def test_cli_build_and_verify_report_actual_published_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); names = ("fabric", "neoforge", "forge", "relocated_fabric", "bootstrap", "legacy_forge", "legacy_fabric", "forge_1182", "fabric_1182", "fabric_1165", "forge_1165", "legacy_bootstrap")
            flags = []
            for name, body in zip(names, self.inputs()):
                path = root / (name + ".jar"); path.write_bytes(body)
                flags.extend(["--" + name.replace("_", "-"), str(path)])
            output = root / "universal.jar"
            with patch("sys.argv", ["universal_jar.py", "build", *flags, "--output", str(output), "--development"]), \
                    patch("sys.stdout", new=io.StringIO()) as status:
                universal.main()
            metadata = json.loads(status.getvalue().splitlines()[1])
            self.assertEqual(metadata["artifact_sha256"], universal.digest(output.read_bytes()))
            with patch("sys.argv", ["universal_jar.py", "verify", str(output), "--development"]), \
                    patch("sys.stdout", new=io.StringIO()) as status:
                universal.main()
            self.assertEqual(json.loads(status.getvalue().splitlines()[1])["artifact_sha256"], metadata["artifact_sha256"])


if __name__ == "__main__":
    unittest.main()

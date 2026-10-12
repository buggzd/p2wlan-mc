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
    "client/lan/VanillaLanList", "client/lan/VanillaLanScreen", "client/lan/VirtualLanEntry",
    "client/screen/AccountScreen", "client/screen/AgentScreen", "client/screen/AgentScreen$Action",
    "client/screen/AgentScreen$Label", "client/screen/AgentScreen$ReplyAction", "client/screen/AgentScreen$TaskAction",
    "client/screen/DiagnosticsScreen", "client/screen/DisplaySettingsScreen", "client/screen/DisplaySettingsScreen$1",
    "client/screen/JoinGameScreen", "client/screen/LatencyStatsScreen", "client/screen/LocalPanelScreen", "client/screen/LogoButton",
    "client/screen/MenuLogo", "client/screen/PasswordField", "client/screen/PixelButton", "client/screen/RoomFormScreen",
    "client/screen/RoomsScreen", "client/screen/RuntimeScreen", "mixin/ClientConnectionMixin", "mixin/IntegratedServerMixin",
    "mixin/MultiplayerListAccessor", "mixin/MultiplayerListMixin", "mixin/MultiplayerScreenMixin",
    "server/WorldCommands", "server/mixin/ServerConnectionMixin",
)


def bytecode(name, major=65, marker="named"):
    # Minimal classfile with actual UTF8/Class constants and an opaque class
    # body. Relocation must update descriptors/dotted names without changing
    # constant indices; no instruction execution is claimed here.
    text = (name.removesuffix(".class"), "java/lang/Object", marker,
            "Lio/p2wlan/mc/game/server/WorldCommands;", "io.p2wlan.mc.game.server.WorldCommands")
    constants = []
    for value in text:
        raw = value.encode(); constants.append(b"\x01" + struct.pack(">H", len(raw)) + raw)
    constants.extend((b"\x07\x00\x01", b"\x07\x00\x02"))
    return b"\xca\xfe\xba\xbe\x00\x00" + struct.pack(">HH", major, 8) + b"".join(constants) + struct.pack(">7H", 0x21, 6, 7, 0, 0, 0, 0)


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
        top.update({universal.GAME + name + ".class": bytecode(universal.GAME + name + ".class", marker="fabric" if loader == "fabric" else "named") for name in GAME_TYPES})
        top.update({name: bytecode(name, marker=loader) for name in universal.LOADER_CLASSES[loader]})
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
        return universal.archive_bytes({name: value.encode() if isinstance(value, str) else value for name, value in top.items()})

    def inputs(self, release=False):
        sources = [self.source(loader, release) for loader in ("fabric", "neoforge", "forge")]
        relocated = universal.archive_bytes(universal.expected_relocation(universal.members(sources[0])))
        boot = {name: bytecode(name, 61) for name in universal.BOOTSTRAP_CLASSES}
        boot.update({name: universal.encoded(value) for name, value in universal.CONFIGS.items()})
        return [*sources, relocated, universal.archive_bytes(boot)]

    def build(self, release=False):
        return universal.build_bytes(*self.inputs(release), development=not release)

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
        self.assertEqual(info["loaders"], {"fabric": "0.16.14", "neoforge": "21.1.256", "forge": "52.1.16"})
        files = universal.members(value)
        self.assertEqual(len(universal.classes(files, universal.GAME)), 43)
        self.assertEqual(len(universal.classes(files, universal.FABRIC_GAME)), 43)
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

    def test_four_narrow_dispatch_configs_cannot_capture_ordinary_game_or_core_packages(self):
        files = universal.members(self.build())
        expected = {"io.p2wlan.mc.game.server.mixin", "io.p2wlan.mc.game.mixin",
                    "io.p2wlan.mc.fabric.game.server.mixin", "io.p2wlan.mc.fabric.game.mixin"}
        self.assertEqual({json.loads(files[name])["package"] for name in universal.CONFIGS}, expected)
        self.assertTrue(all(json.loads(files[name])["mixins"] == [] for name in universal.CONFIGS))
        fabric = json.loads(files["fabric.mod.json"])
        self.assertEqual(fabric["mixins"], [universal.FABRIC_SERVER_CONFIG, {"config": universal.FABRIC_CLIENT_CONFIG, "environment": "client"}])
        self.assertNotIn(universal.FABRIC_SERVER_CONFIG.encode(), files["META-INF/MANIFEST.MF"])

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
                universal.build_bytes(*inputs, development=True)

    def test_native_consistency_includes_actual_executable_not_only_source_identity(self):
        inputs = self.inputs(); files = universal.members(inputs[1]); path = universal.CORE_PATHS["native"]
        child = universal.members(files[path]); manifest = next(name for name in child if name.endswith("/manifest.properties"))
        binary = next(name for name in child if name.startswith("p2wlan/native/") and not name.endswith("/manifest.properties"))
        old = child[binary]; new = b"different native fixture bytes"
        child[binary] = new
        child[manifest] = child[manifest].decode().replace(universal.digest(old), universal.digest(new)).replace("executable-size=" + str(len(old)), "executable-size=" + str(len(new))).encode()
        files[path] = universal.archive_bytes(child); inputs[1] = universal.archive_bytes(files)
        with self.assertRaisesRegex(universal.VerificationError, "universal_native_identity"):
            universal.build_bytes(*inputs, development=True)

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
                universal.build_bytes(*inputs, development=True)

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
                universal.build_bytes(*inputs, development=True)

    def test_sources_cannot_smuggle_unknown_classes_fixtures_or_slim_forge(self):
        for mutation in ("owner", "test", "slim"):
            inputs = self.inputs(); files = universal.members(inputs[2])
            if mutation == "owner": files["rogue/Bootstrap.class"] = bytecode("rogue/Bootstrap.class")
            elif mutation == "test": files["io/p2wlan/mc/forge/ExtraTests.class"] = bytecode("io/p2wlan/mc/forge/ExtraTests.class")
            else: files = {name: body for name, body in files.items() if not name.startswith("META-INF/jarjar/")}
            inputs[2] = universal.archive_bytes(files)
            with self.subTest(mutation=mutation), self.assertRaises(universal.VerificationError):
                universal.build_bytes(*inputs, development=True)

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
                universal.build_bytes(*inputs, development=True)
        for loader, catalog in universal.LOADER_CLASSES.items():
            inputs = self.inputs(); index = ("fabric", "neoforge", "forge").index(loader)
            files = universal.members(inputs[index]); name = next(name for name in catalog if "$" in name)
            del files[name]; inputs[index] = universal.archive_bytes(files)
            with self.subTest(loader=loader, missing=name), self.assertRaisesRegex(universal.VerificationError, "universal_loader_class_catalog"):
                universal.build_bytes(*inputs, development=True)

    def test_bootstrap_catalog_rejects_unknown_outer_and_nested_families(self):
        for name in (universal.BOOTSTRAP + "LegacyRuntime.class", universal.BOOTSTRAP + "ServerMixinPlugin$OldOwner.class",
                     universal.BOOTSTRAP + "UniversalEnvironment$Unexpected.class"):
            inputs = self.inputs(); files = universal.members(inputs[4]); files[name] = bytecode(name, 61)
            inputs[4] = universal.archive_bytes(files)
            with self.subTest(name=name), self.assertRaisesRegex(universal.VerificationError, "universal_bootstrap_layout"):
                universal.build_bytes(*inputs, development=True)
        value = self.build()
        for name in ("io/p2wlan/mc/fabric/client/FabricGameJoins.class", universal.BOOTSTRAP + "ServerMixinPlugin$OldOwner.class"):
            with self.subTest(name=name), self.assertRaisesRegex(universal.VerificationError, "universal_loader_class_catalog"):
                self.check(self.mutate(value, lambda files: files.update({name: bytecode(name, 61 if name.startswith(universal.BOOTSTRAP) else 65)}), True))

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
        for name, before, after in (("META-INF/mods.toml", b"[52.1.16]", b"[52,)"),
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
            universal.build_bytes(*self.inputs(), development=False)
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
            root = Path(temporary); names = ("fabric", "neoforge", "forge", "relocated_fabric", "bootstrap")
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

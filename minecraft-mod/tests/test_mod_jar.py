import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
import zipfile
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
spec = importlib.util.spec_from_file_location("verify_mod", PROJECT / "tools/verify_mod_jar.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def jar(values):
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        for name, value in values.items():
            archive.writestr(name, value)
    return data.getvalue()


def bytecode(major):
    return b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big") + b"fixture"


JNA_FIXTURE = {"com/sun/jna/Native.class": bytecode(52),
               "com/sun/jna/win32-x86-64/jnidispatch.dll": b"synthetic JNI payload"}


def jna_fixture_binding():
    return patch("artifact_checks.JNA_PAYLOADS", {name: hashlib.sha256(value).hexdigest() for name, value in JNA_FIXTURE.items()})


class ModJarTests(unittest.TestCase):
    def setUp(self):
        self.binding = jna_fixture_binding()
        self.binding.start()
        self.addCleanup(self.binding.stop)

    def fixture(self, binary=b"fixture", platform="macos-aarch64"):
        prefix = f"p2wlan/native/{platform}/"
        system, arch = platform.split("-", 1)
        manifest = {"schema": "2", "protocol": "1", "version": "0.1.0", "source-revision": "a" * 40,
                    "source-sha256": "b" * 64, "build-kind": "development", "os": system, "arch": arch,
                    "executable-sha256": hashlib.sha256(binary).hexdigest(), "executable-size": str(len(binary)),
                    "verification-platform": platform, "verification-method": "native-build-info"}
        common = {verify.COMMON: bytecode(61)}
        native = {prefix + "manifest.properties": "".join(f"{key}={value}\n" for key, value in manifest.items()),
                  prefix + verify.executable_name(platform): binary}
        metadata = {"schemaVersion": 1, "id": "p2wlan", "version": "0.1.0", "environment": "*",
                    "entrypoints": {"main": [verify.MAIN.removesuffix(".class").replace("/", ".")],
                                    "client": [verify.CLIENT.removesuffix(".class").replace("/", ".")]},
                    "depends": {"minecraft": "1.21.1", "java": ">=21"}, "mixins": verify.MIXINS,
                    "jars": [{"file": "META-INF/jars/common.jar"}, {"file": "META-INF/jars/native.jar"}, {"file": "META-INF/jars/jna.jar"}, {"file": "META-INF/jars/transport.jar"}]}
        top = {"fabric.mod.json": json.dumps(metadata), "p2wlan.mixins.json": json.dumps(verify.MIXIN_CONFIG),
               verify.MIXIN_CONFIG["refmap"]: json.dumps(verify.REFMAP), verify.SERVER_MIXIN: bytecode(65), verify.MAIN: bytecode(65), verify.CLIENT: bytecode(65),
               "p2wlan.client.mixins.json": json.dumps(verify.CLIENT_MIXIN_CONFIG),
               verify.CLIENT_MIXIN_CONFIG["refmap"]: json.dumps(verify.CLIENT_REFMAP), verify.CLIENT_MIXIN: bytecode(65), verify.INTEGRATED_MIXIN: bytecode(65),
               "META-INF/jars/common.jar": jar(common), "META-INF/jars/native.jar": jar(native),
               "META-INF/jars/jna.jar": jar(JNA_FIXTURE),
               "META-INF/jars/transport.jar": jar({name: bytecode(61) for name in verify.TRANSPORT})}
        top.update({name: bytecode(65) for name in verify.LAN_MIXINS})
        return top, metadata, common, native

    def check(self, top, development=True):
        return verify.verify(io.BytesIO(jar(top)), development)

    def test_shared_transport_required_complete_portable_and_unique(self):
        top, metadata, _, _ = self.fixture()
        metadata["jars"] = metadata["jars"][:-1]
        top["fabric.mod.json"] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, "mod_shared_transport_resources_ambiguous"):
            self.check(top)
        for missing in verify.TRANSPORT:
            top, _, _, _ = self.fixture()
            top["META-INF/jars/transport.jar"] = jar({name: bytecode(61) for name in verify.TRANSPORT if name != missing})
            with self.assertRaises(verify.VerificationError):
                self.check(top)
        for prefix in (b"net/minecraft/", b"net/fabricmc/", b"net/neoforged/", b"net/minecraftforge/"):
            top, _, _, _ = self.fixture()
            top["META-INF/jars/transport.jar"] = jar({name: bytecode(61) + prefix for name in verify.TRANSPORT})
            with self.assertRaisesRegex(verify.VerificationError, "mod_common_game_dependency"):
                self.check(top)
        top, metadata, _, _ = self.fixture()
        metadata["jars"].append({"file": "META-INF/jars/duplicate-transport.jar"})
        top["fabric.mod.json"] = json.dumps(metadata)
        top["META-INF/jars/duplicate-transport.jar"] = top["META-INF/jars/transport.jar"]
        with self.assertRaisesRegex(verify.VerificationError, "mod_shared_transport_resources_ambiguous"):
            self.check(top)

    def test_shared_version_fixtures_cannot_enter_production(self):
        top, _, _, _ = self.fixture()
        top['io/p2wlan/mc/game/fixture/GameClientChecks.class'] = bytecode(65)
        with self.assertRaisesRegex(verify.VerificationError, 'mod_test_fixture_present'):
            self.check(top)

    def test_complete_nested_artifact_and_development_policy(self):
        top, _, _, _ = self.fixture()
        self.assertEqual(self.check(top)[0]["verification-platform"], "macos-aarch64")
        with self.assertRaisesRegex(verify.VerificationError, "mod_development_native_disabled"):
            self.check(top, False)

    def test_verification_method_is_explicit_and_wine_does_not_masquerade_as_native(self):
        for platform in ("windows-x86_64", "macos-aarch64"):
            top, _, _, native = self.fixture(platform=platform)
            key = f"p2wlan/native/{platform}/manifest.properties"
            for method in ("wine-build-info", "not-executed"):
                changed = native.copy()
                changed[key] = changed[key].replace("verification-method=native-build-info", "verification-method=" + method)
                top["META-INF/jars/native.jar"] = jar(changed)
                if method == "wine-build-info" and platform == "windows-x86_64":
                    self.assertEqual(self.check(top)[0]["verification-method"], method)
                else:
                    with self.assertRaisesRegex(verify.VerificationError, "mod_native_verification_method"):
                        self.check(top)

    def test_jna_is_required_and_critical_windows_payload_is_byte_bound(self):
        top, metadata, _, _ = self.fixture()
        metadata["jars"] = metadata["jars"][:2]
        top["fabric.mod.json"] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, "mod_jna_resources_ambiguous"):
            self.check(top)
        for missing in JNA_FIXTURE:
            top, _, _, _ = self.fixture()
            for values in ({name: value for name, value in JNA_FIXTURE.items() if name != missing},
                           JNA_FIXTURE | {missing: b"altered"}):
                top["META-INF/jars/jna.jar"] = jar(values)
                with self.assertRaisesRegex(verify.VerificationError, "mod_jna_"):
                    self.check(top)

    def test_windows_resources_have_one_exact_exe_name_and_no_stray_programs(self):
        top, _, _, native = self.fixture(platform="windows-x86_64")
        self.assertEqual(self.check(top)[0]["verification-platform"], "windows-x86_64")
        prefix = "p2wlan/native/windows-x86_64/"
        native[prefix + "p2wlan-mc-agent"] = native.pop(prefix + "p2wlan-mc-agent.exe")
        top["META-INF/jars/native.jar"] = jar(native)
        with self.assertRaisesRegex(verify.VerificationError, "mod_native_size"):
            self.check(top)
        native[prefix + "p2wlan-mc-agent.exe"] = native[prefix + "p2wlan-mc-agent"]
        top["META-INF/jars/native.jar"] = jar(native)
        with self.assertRaisesRegex(verify.VerificationError, "mod_native_resource_unexpected"):
            self.check(top)

    def test_all_recognised_platforms_are_bounded_and_can_share_one_native_jar(self):
        top, _, _, _ = self.fixture()
        natives = {}
        for platform in sorted(verify.PLATFORMS):
            _, _, _, native = self.fixture(platform=platform)
            natives.update(native)
        top["META-INF/jars/native.jar"] = jar(natives)
        self.assertEqual({item["verification-platform"] for item in self.check(top)}, verify.PLATFORMS)

    def test_native_resource_required_and_checksum_checked(self):
        top, _, _, native = self.fixture()
        top["META-INF/jars/native.jar"] = jar({})
        with self.assertRaisesRegex(verify.VerificationError, "mod_native_resources_ambiguous"):
            self.check(top)
        native["p2wlan/native/macos-aarch64/p2wlan-mc-agent"] = b"changed"
        top["META-INF/jars/native.jar"] = jar(native)
        with self.assertRaisesRegex(verify.VerificationError, "mod_native_checksum"):
            self.check(top)
        native["p2wlan/native/macos-aarch64/p2wlan-mc-agent"] = b"size changed"
        top["META-INF/jars/native.jar"] = jar(native)
        with self.assertRaisesRegex(verify.VerificationError, "mod_native_size"):
            self.check(top)

    def test_common_bytecode_is_loader_and_game_independent(self):
        top, _, common, _ = self.fixture()
        common[verify.COMMON] = bytecode(65)
        top["META-INF/jars/common.jar"] = jar(common)
        with self.assertRaisesRegex(verify.VerificationError, "mod_class_version"):
            self.check(top)
        for prefix in (b"net/minecraft/", b"net/fabricmc/", b"net/neoforged/", b"net/minecraftforge/"):
            common[verify.COMMON] = bytecode(61) + prefix
            top["META-INF/jars/common.jar"] = jar(common)
            with self.assertRaisesRegex(verify.VerificationError, "mod_common_game_dependency"):
                self.check(top)

    def test_dedicated_bootstrap_and_supported_version_are_exact(self):
        top, metadata, _, _ = self.fixture()
        top[verify.MAIN] += b"io/p2wlan/mc/fabric/client/"
        with self.assertRaisesRegex(verify.VerificationError, "mod_dedicated_client_reference"):
            self.check(top)
        top[verify.MAIN] = bytecode(65)
        metadata["depends"]["minecraft"] = ">=1.21.1"
        top["fabric.mod.json"] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, "mod_fabric_contract"):
            self.check(top)

    def test_required_mixin_is_packaged_exactly_and_dedicated_safe(self):
        top, _, _, _ = self.fixture()
        del top[verify.SERVER_MIXIN]
        with self.assertRaisesRegex(verify.VerificationError, "mod_archive_member_invalid"):
            self.check(top)
        top, _, _, _ = self.fixture()
        top[verify.SERVER_MIXIN] += b"net/minecraft/client/"
        with self.assertRaisesRegex(verify.VerificationError, "mod_dedicated_client_reference"):
            self.check(top)
        for change in ({"required": False}, {"mixins": []}, {"injectors": {"defaultRequire": 0}}):
            top, _, _, _ = self.fixture()
            top["p2wlan.mixins.json"] = json.dumps(verify.MIXIN_CONFIG | change)
            with self.assertRaisesRegex(verify.VerificationError, "mod_mixin_contract"):
                self.check(top)

    def test_required_refmap_is_nonempty_and_bound_to_exact_game_method(self):
        for mapping in ({}, {"mappings": {}, "data": {}}, {"mappings": verify.BIND_MAPPING, "data": {}}):
            top, _, _, _ = self.fixture()
            top[verify.MIXIN_CONFIG["refmap"]] = json.dumps(mapping)
            with self.assertRaisesRegex(verify.VerificationError, "mod_mixin_refmap"):
                self.check(top)

    def test_isolated_client_fixture_cannot_leak_into_production_archive(self):
        top, _, _, _ = self.fixture()
        top["io/p2wlan/mc/fabric/fixture/ClientHookFixture.class"] = bytecode(65)
        with self.assertRaisesRegex(verify.VerificationError, "mod_test_fixture_present"):
            self.check(top)

    def test_integrated_pause_hook_is_required_and_exactly_mapped(self):
        top, _, _, _ = self.fixture()
        del top[verify.INTEGRATED_MIXIN]
        with self.assertRaises(verify.VerificationError):
            self.check(top)
        top, _, _, _ = self.fixture()
        config = verify.CLIENT_MIXIN_CONFIG | {"client": ["ClientConnectionMixin"]}
        top["p2wlan.client.mixins.json"] = json.dumps(config)
        with self.assertRaisesRegex(verify.VerificationError, "mod_client_mixin_contract"):
            self.check(top)
        for mapping in ({"mappings": verify.CONNECT_MAPPING, "data": {"named:intermediary": verify.CONNECT_MAPPING}},
                        {"mappings": verify.CLIENT_MAPPING | {"io/p2wlan/mc/game/mixin/IntegratedServerMixin": {"isPublished": "Lnet/minecraft/class_1132;method_3860(I)Z"}}, "data": {"named:intermediary": verify.CLIENT_MAPPING}}):
            top, _, _, _ = self.fixture()
            top[verify.CLIENT_MIXIN_CONFIG["refmap"]] = json.dumps(mapping)
            with self.assertRaisesRegex(verify.VerificationError, "mod_client_mixin_refmap"):
                self.check(top)

    def test_vanilla_browser_hooks_are_present_and_java_21(self):
        for name in verify.LAN_MIXINS:
            with self.subTest(name=name):
                top, _, _, _ = self.fixture()
                del top[name]
                with self.assertRaisesRegex(verify.VerificationError, "mod_archive_member_invalid"):
                    self.check(top)
                top, _, _, _ = self.fixture()
                top[name] = bytecode(61)
                with self.assertRaises(verify.VerificationError):
                    self.check(top)
        for name in verify.LAN_MAPPING:
            with self.subTest(mapping=name):
                top, _, _, _ = self.fixture()
                mapping = verify.CLIENT_MAPPING.copy()
                del mapping[name]
                top[verify.CLIENT_MIXIN_CONFIG["refmap"]] = json.dumps({
                    "mappings": mapping, "data": {"named:intermediary": mapping}})
                with self.assertRaisesRegex(verify.VerificationError, "mod_client_mixin_refmap"):
                    self.check(top)

    def test_client_hook_is_required_client_only_and_exactly_mapped(self):
        top, metadata, _, _ = self.fixture()
        metadata["mixins"] = ["p2wlan.mixins.json", {"config": "p2wlan.client.mixins.json", "environment": "*"}]
        top["fabric.mod.json"] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, "mod_fabric_contract"):
            self.check(top)
        for change in ({"required": False}, {"client": []}, {"injectors": {"defaultRequire": 0}}):
            top, _, _, _ = self.fixture()
            top["p2wlan.client.mixins.json"] = json.dumps(verify.CLIENT_MIXIN_CONFIG | change)
            with self.assertRaisesRegex(verify.VerificationError, "mod_client_mixin_contract"):
                self.check(top)
        top, _, _, _ = self.fixture()
        del top[verify.CLIENT_MIXIN]
        with self.assertRaisesRegex(verify.VerificationError, "mod_archive_member_invalid"):
            self.check(top)
        top, _, _, _ = self.fixture()
        top[verify.CLIENT_MIXIN_CONFIG["refmap"]] = json.dumps(verify.REFMAP)
        with self.assertRaisesRegex(verify.VerificationError, "mod_client_mixin_refmap"):
            self.check(top)

    def test_malformed_metadata_types_fail_as_typed_contract_errors(self):
        for change in ({"schemaVersion": True}, {"depends": "not an object"}):
            top, metadata, _, _ = self.fixture()
            top["fabric.mod.json"] = json.dumps(metadata | change)
            with self.assertRaisesRegex(verify.VerificationError, "mod_fabric_contract"):
                self.check(top)

    def test_ambiguous_nested_resource_and_invalid_archive_names_are_rejected(self):
        top, metadata, _, _ = self.fixture()
        metadata["jars"].append(metadata["jars"][1])
        top["fabric.mod.json"] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, "mod_nested_resource_invalid"):
            self.check(top)
        with zipfile.ZipFile(io.BytesIO(jar({"../escape": b"x"}))) as archive:
            with self.assertRaisesRegex(verify.VerificationError, "mod_archive_entry_invalid"):
                verify.entries(archive)


if __name__ == "__main__":
    unittest.main()

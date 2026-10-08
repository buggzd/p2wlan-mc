import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
import zipfile

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


class ModJarTests(unittest.TestCase):
    def fixture(self, binary=b"fixture"):
        prefix = "p2wlan/native/macos-aarch64/"
        manifest = {"schema": "1", "protocol": "1", "version": "0.1.0", "source-revision": "a" * 40,
                    "source-sha256": "b" * 64, "build-kind": "development", "os": "macos", "arch": "aarch64",
                    "executable-sha256": hashlib.sha256(binary).hexdigest(), "executable-size": str(len(binary)),
                    "verification-platform": "macos-aarch64"}
        common = {verify.COMMON: bytecode(61)}
        native = {prefix + "manifest.properties": "".join(f"{key}={value}\n" for key, value in manifest.items()),
                  prefix + "p2wlan-mc-agent": binary}
        metadata = {"schemaVersion": 1, "id": "p2wlan", "version": "0.1.0", "environment": "*",
                    "entrypoints": {"main": [verify.MAIN.removesuffix(".class").replace("/", ".")],
                                    "client": [verify.CLIENT.removesuffix(".class").replace("/", ".")]},
                    "depends": {"minecraft": "1.21.1", "java": ">=21"}, "mixins": ["p2wlan.mixins.json"],
                    "jars": [{"file": "META-INF/jars/common.jar"}, {"file": "META-INF/jars/native.jar"}]}
        top = {"fabric.mod.json": json.dumps(metadata), "p2wlan.mixins.json": json.dumps(verify.MIXIN_CONFIG),
               verify.MIXIN_CONFIG["refmap"]: json.dumps(verify.REFMAP), verify.SERVER_MIXIN: bytecode(65), verify.MAIN: bytecode(65), verify.CLIENT: bytecode(65),
               "META-INF/jars/common.jar": jar(common), "META-INF/jars/native.jar": jar(native)}
        return top, metadata, common, native

    def check(self, top, development=True):
        return verify.verify(io.BytesIO(jar(top)), development)

    def test_complete_nested_artifact_and_development_policy(self):
        top, _, _, _ = self.fixture()
        self.assertEqual(self.check(top)[0]["verification-platform"], "macos-aarch64")
        with self.assertRaisesRegex(verify.VerificationError, "mod_development_native_disabled"):
            self.check(top, False)

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

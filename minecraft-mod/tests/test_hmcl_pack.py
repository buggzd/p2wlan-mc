import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
import hmcl_pack as pack
import test_mod_jar as fixtures


class HmclPackTests(unittest.TestCase):
    def setUp(self):
        self.api = fixtures.jar({"fabric.mod.json": json.dumps({"id": "fabric-api", "version": pack.API_VERSION})})
        # Synthetic archives test the contract only. The production checksum has
        # no CLI override, and an actual Maven artifact is required by Gradle.
        self.api_digest = patch.object(pack, "API_SHA256", pack.checksum(self.api))
        self.api_digest.start()
        self.addCleanup(self.api_digest.stop)
        self.binding = patch.object(pack, "bind_sources")
        self.bind = self.binding.start()
        self.addCleanup(self.binding.stop)

    def mod(self, platform="windows-x86_64", development=False):
        top, _, _, native = fixtures.ModJarTests().fixture(platform=platform)
        prefix = f"p2wlan/native/{platform}/"
        if not development:
            native[prefix + "manifest.properties"] = native[prefix + "manifest.properties"].replace(
                "build-kind=development\n", "build-kind=release\n")
            top["META-INF/jars/native.jar"] = fixtures.jar(native)
        return fixtures.jar(top)

    def build(self, platform="windows-x86_64"):
        return pack.build_bytes(self.mod(platform), self.api, platform)

    def payloads(self, value):
        with zipfile.ZipFile(io.BytesIO(value)) as archive:
            return {name: archive.read(name) for name in archive.namelist()}

    def check(self, values):
        return pack.verify_zip(io.BytesIO(fixtures.jar(values)), "windows-x86_64")

    def test_pack_is_deterministic_source_bound_and_has_no_game_or_profile(self):
        value = self.build()
        self.assertEqual(value, self.build())
        values = self.payloads(value)
        self.assertEqual(set(values), pack.NAMES)
        manifest = json.loads(values["manifest.json"])
        self.assertEqual(manifest["files"], [])
        self.assertEqual(manifest["overrides"], "overrides")
        self.assertEqual(manifest["minecraft"]["modLoaders"], [{"id": "fabric-0.16.14", "primary": True}])
        info = self.check(values)
        self.assertEqual(info["platform"], "windows-x86_64")
        self.assertEqual(info["source_revision"], "a" * 40)
        self.assertEqual(info["native_targets"], ["windows-x86_64"])
        self.assertEqual(values[pack.GUIDE], values["overrides/" + pack.GUIDE])
        self.assertIn("HTTP 可以使用", values[pack.GUIDE].decode("utf-8"))
        self.assertNotIn(b"http://", values[pack.GUIDE])
        self.assertGreaterEqual(self.bind.call_count, 5)
        self.assertTrue(all(call.args[1] is False for call in self.bind.call_args_list))

    def test_target_must_exist_and_mac_artifact_cannot_be_delivered_as_windows(self):
        with self.assertRaisesRegex(pack.VerificationError, "pack_target_native_missing"):
            pack.build_bytes(self.mod("macos-aarch64"), self.api, "windows-x86_64")
        for bad in ("windows-x86", "windows-aarch64", "", None, [], {}):
            with self.assertRaisesRegex(pack.VerificationError, "pack_platform_unsupported"):
                pack.launcher_manifest(bad)
        value = self.build("macos-aarch64")
        with self.assertRaisesRegex(pack.VerificationError, "pack_target_mismatch"):
            pack.verify_zip(io.BytesIO(value), "windows-x86_64")

    def test_development_artifacts_and_wrong_api_are_rejected(self):
        with self.assertRaisesRegex(pack.VerificationError, "mod_development_native_disabled"):
            pack.build_bytes(self.mod(development=True), self.api, "windows-x86_64")
        for api in (b"", b"changed", self.api + b"changed"):
            with self.assertRaisesRegex(pack.VerificationError, "pack_fabric_api_checksum"):
                pack.build_bytes(self.mod(), api, "windows-x86_64")
        with self.assertRaisesRegex(pack.VerificationError, "pack_mod_size"):
            pack.build_bytes(b"", self.api, "windows-x86_64")

    def test_changed_source_is_rechecked_before_publication(self):
        self.bind.side_effect = [None, pack.VerificationError("mod_native_sources_changed_rebuild_bundle")]
        with self.assertRaisesRegex(pack.VerificationError, "mod_native_sources_changed_rebuild_bundle"):
            self.build()
        self.assertEqual(self.bind.call_count, 2)

    def test_exact_layout_rejects_profiles_game_files_and_path_traversal(self):
        original = self.payloads(self.build())
        for name in ("overrides/config/p2wlan-private/profile.json", "overrides/logs/latest.log",
                     "overrides/versions/1.21.1/1.21.1.jar", "overrides/eula.txt", "../mod.jar"):
            with self.assertRaises(pack.VerificationError):
                self.check(original | {name: b"not admitted"})
        for name in pack.NAMES:
            values = original.copy()
            del values[name]
            with self.assertRaisesRegex(pack.VerificationError, "pack_archive_layout"):
                self.check(values)

    def test_metadata_and_payloads_cannot_be_tampered_with(self):
        original = self.payloads(self.build())
        for change in ({"schema": True}, {"source_revision": "c" * 40}, {"native_targets": []}, {"extra": "value"}):
            values = original.copy()
            values[pack.INDEX] = pack.encoded(json.loads(values[pack.INDEX]) | change)
            with self.assertRaisesRegex(pack.VerificationError, "pack_metadata_mismatch"):
                self.check(values)
        for name in pack.PAYLOADS:
            values = original.copy()
            values[name] += b"changed"
            with self.assertRaises(pack.VerificationError):
                self.check(values)

    def test_manifest_and_guide_are_checked_even_if_checksums_are_rewritten(self):
        original = self.payloads(self.build())
        for name in ("manifest.json", pack.GUIDE, "overrides/" + pack.GUIDE):
            values = original.copy()
            values[name] = b"{}" if name == "manifest.json" else b"unverified instructions"
            info = json.loads(values[pack.INDEX])
            info["files"] = pack.file_records(values)
            values[pack.INDEX] = pack.encoded(info)
            reason = "pack_launcher_manifest" if name == "manifest.json" else "pack_install_guide"
            with self.assertRaisesRegex(pack.VerificationError, reason):
                self.check(values)

    def test_zip_links_and_duplicate_members_are_rejected(self):
        values = self.payloads(self.build())
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name, value in values.items():
                item = zipfile.ZipInfo(name)
                item.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(item, value)
        with self.assertRaisesRegex(pack.VerificationError, "pack_archive_file_type"):
            pack.verify_zip(io.BytesIO(output.getvalue()))
        with zipfile.ZipFile(output, "a") as archive:
            with self.assertWarns(UserWarning):
                archive.writestr("manifest.json", b"{}")
        with self.assertRaisesRegex(pack.VerificationError, "mod_archive_entries"):
            pack.verify_zip(io.BytesIO(output.getvalue()))

    def test_inputs_are_bounded_regular_snapshots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "input.jar"
            path.write_bytes(b"fixture")
            self.assertEqual(pack.regular_bytes(path, 7), b"fixture")
            with self.assertRaisesRegex(pack.VerificationError, "pack_input_file"):
                pack.regular_bytes(path, 6)
            link = root / "link.jar"
            link.symlink_to(path)
            with self.assertRaisesRegex(pack.VerificationError, "pack_input_file"):
                pack.regular_bytes(link, 7)
            path.write_bytes(b"")
            with self.assertRaisesRegex(pack.VerificationError, "pack_input_file"):
                pack.regular_bytes(path, 7)
            if hasattr(os, "mkfifo"):
                fifo = root / "not-a-file"
                os.mkfifo(fifo)
                with self.assertRaisesRegex(pack.VerificationError, "pack_input_file"):
                    pack.regular_bytes(fifo, 7)

    def test_atomic_publication_is_idempotent_and_never_overwrites_user_files(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "pack.zip"
            value = self.build()
            pack.publish(output, value)
            pack.publish(output, value)
            self.assertEqual(output.read_bytes(), value)
            self.assertEqual(output.stat().st_nlink, 1)
            with self.assertRaisesRegex(pack.VerificationError, "pack_output_exists"):
                pack.publish(output, b"not the same pack")
            self.assertEqual(output.read_bytes(), value)
            self.assertEqual([path.name for path in output.parent.iterdir()], ["pack.zip"])
            alias = output.parent / "alias.zip"
            alias.symlink_to(output)
            with self.assertRaisesRegex(pack.VerificationError, "pack_input_file"):
                pack.publish(alias, value)
            self.assertEqual(output.read_bytes(), value)
            self.assertEqual({path.name for path in output.parent.iterdir()}, {"pack.zip", "alias.zip"})

    def test_pack_pins_match_the_actual_adapter_and_metadata(self):
        build = (PROJECT / "java/adapters/fabric-1.21.1/build.gradle").read_text()
        metadata = json.loads((PROJECT / "java/adapters/fabric-1.21.1/src/main/resources/fabric.mod.json").read_text())
        self.assertIn("minecraft 'com.mojang:minecraft:" + pack.MINECRAFT + "'", build)
        self.assertIn("fabric-loader:" + pack.LOADER + "'", build)
        self.assertIn("def fabricApiVersion = '" + pack.API_VERSION + "'", build)
        self.assertEqual(metadata["depends"]["minecraft"], pack.MINECRAFT)
        self.assertEqual(metadata["depends"]["fabricloader"], ">=" + pack.LOADER)


if __name__ == "__main__":
    unittest.main()

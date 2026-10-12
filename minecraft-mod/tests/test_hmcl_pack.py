import io
import json
from dataclasses import FrozenInstanceError, replace
import os
from pathlib import Path
import stat
import sys
import tempfile
from types import MappingProxyType
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
import hmcl_pack as pack
import test_mod_jar as fixtures
import test_neoforge_jar as neoforge_fixtures
import test_forge_jar as forge_fixtures


class HmclPackTests(unittest.TestCase):
    def setUp(self):
        self.jna_binding = fixtures.jna_fixture_binding()
        self.jna_binding.start()
        self.addCleanup(self.jna_binding.stop)
        self.api = fixtures.jar({"fabric.mod.json": json.dumps({"id": "fabric-api", "version": pack.API_VERSION})})
        # Synthetic archives test the contract only. The production checksum has
        # no CLI override, and an actual Maven artifact is required by Gradle.
        self.original_profiles = pack.PROFILES
        synthetic = dict(pack.PROFILES)
        synthetic[pack.MINECRAFT, "fabric"] = replace(pack.profile(), api_sha256=pack.checksum(self.api))
        self.api_digest = patch.object(pack, "PROFILES", MappingProxyType(synthetic))
        self.api_digest.start()
        self.addCleanup(self.api_digest.stop)
        self.binding = patch.object(pack, "bind_sources")
        self.bind = self.binding.start()
        self.addCleanup(self.binding.stop)

    def mod(self, platform="windows-x86_64", development=False, loader="fabric"):
        top, _, _, native = fixtures.ModJarTests().fixture(platform=platform)
        prefix = f"p2wlan/native/{platform}/"
        if not development:
            native[prefix + "manifest.properties"] = native[prefix + "manifest.properties"].replace(
                "build-kind=development\n", "build-kind=release\n")
        if loader == "fabric":
            top["META-INF/jars/native.jar"] = fixtures.jar(native)
        else:
            factory = neoforge_fixtures.NeoForgeJarTests if loader == "neoforge" else forge_fixtures.ForgeJarTests
            top = factory.fixture(self)
            native["META-INF/MANIFEST.MF"] = "Manifest-Version: 1.0\r\nAutomatic-Module-Name: io.p2wlan.mc.nativebundle\r\n\r\n"
            top["META-INF/jarjar/native.jar"] = fixtures.jar(native)
        return fixtures.jar(top)

    def build(self, platform="windows-x86_64", loader="fabric"):
        return pack.build_bytes(self.mod(platform, loader=loader), self.api if loader == "fabric" else None, platform, loader)

    def payloads(self, value):
        with zipfile.ZipFile(io.BytesIO(value)) as archive:
            return {name: archive.read(name) for name in archive.namelist()}

    def check(self, values, loader=None):
        return pack.verify_zip(io.BytesIO(fixtures.jar(values)), "windows-x86_64", loader)

    def test_all_loader_profiles_have_exact_deterministic_payloads_and_manifest(self):
        versions = {"fabric": "0.16.14", "neoforge": "21.1.256", "forge": "52.1.16"}
        for loader, version in versions.items():
            with self.subTest(loader=loader):
                value = self.build(loader=loader)
                self.assertEqual(value, self.build(loader=loader))
                values = self.payloads(value)
                mod = f"overrides/mods/p2wlan-{loader}-1.21.1-0.1.0.jar"
                expected = {"manifest.json", pack.INDEX, pack.GUIDE, "overrides/" + pack.GUIDE, mod}
                if loader == "fabric": expected.add(pack.API)
                self.assertEqual(set(values), expected)
                self.assertEqual(values[mod], self.mod(loader=loader))
                manifest = json.loads(values["manifest.json"])
                self.assertEqual(set(manifest), {"manifestType", "manifestVersion", "name", "version", "author", "minecraft", "files", "overrides"})
                self.assertEqual(manifest["minecraft"], {"version": "1.21.1", "modLoaders": [{"id": loader + "-" + version, "primary": True}]})
                self.assertEqual(manifest["files"], [])
                info = self.check(values, loader)
                self.assertEqual(info["loader"], {"id": loader, "version": version})
                self.assertEqual(info["native_targets"], ["windows-x86_64"])
                self.assertEqual(values[pack.GUIDE], values["overrides/" + pack.GUIDE])
                self.assertEqual("Fabric API" in values[pack.GUIDE].decode(), loader == "fabric")

    def test_profiles_are_immutable_and_only_audited_combinations_exist(self):
        self.assertEqual(set(pack.PROFILES), {("1.21.1", "fabric"), ("1.21.1", "neoforge"), ("1.21.1", "forge")})
        self.assertEqual(self.original_profiles["1.21.1", "fabric"].api_sha256, pack.API_SHA256)
        with self.assertRaises(TypeError):
            pack.PROFILES["1.21.4", "forge"] = pack.profile("forge")
        with self.assertRaises(FrozenInstanceError):
            pack.profile("forge").version = "54.1.14"
        for loader in ("unknown", "", None, [], {}):
            with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "pack_loader_unsupported"):
                pack.launcher_manifest("windows-x86_64", loader)

    def test_wrong_loader_incomplete_forge_and_unneeded_fabric_api_are_rejected(self):
        for loader in ("fabric", "neoforge", "forge"):
            for actual in ("fabric", "neoforge", "forge"):
                if actual == loader: continue
                with self.subTest(loader=loader, actual=actual), self.assertRaises(pack.VerificationError):
                    pack.build_bytes(self.mod(loader=actual), self.api if loader == "fabric" else None, "windows-x86_64", loader)
        for loader in ("neoforge", "forge"):
            for api in (self.api, b""):
                with self.subTest(loader=loader, api_size=len(api)), self.assertRaisesRegex(pack.VerificationError, "pack_fabric_api_unexpected"):
                    pack.build_bytes(self.mod(loader=loader), api, "windows-x86_64", loader)
        with zipfile.ZipFile(io.BytesIO(self.mod(loader="forge"))) as archive:
            slim = {name: archive.read(name) for name in archive.namelist() if not name.startswith("META-INF/jarjar/")}
        with self.assertRaises(pack.VerificationError):
            pack.build_bytes(fixtures.jar(slim), None, "windows-x86_64", "forge")

    def test_all_profiles_require_release_native_target_and_source_revalidation(self):
        for loader in ("fabric", "neoforge", "forge"):
            api = self.api if loader == "fabric" else None
            with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "mod_development_native_disabled"):
                pack.build_bytes(self.mod(development=True, loader=loader), api, "windows-x86_64", loader)
            with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "pack_target_native_missing"):
                pack.build_bytes(self.mod(platform="macos-aarch64", loader=loader), api, "windows-x86_64", loader)
            with patch.object(pack, "bind_sources", side_effect=[None, pack.VerificationError("mod_native_sources_changed_rebuild_bundle")]) as binding, \
                    self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "mod_native_sources_changed_rebuild_bundle"):
                self.build(loader=loader)
            self.assertEqual(binding.call_count, 2)

    def test_loader_selection_and_manifest_stay_bound_after_index_rehash(self):
        for loader in ("fabric", "neoforge", "forge"):
            values = self.payloads(self.build(loader=loader))
            wrong = "fabric" if loader != "fabric" else "forge"
            with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "pack_loader_mismatch"):
                self.check(values, wrong)
            for change in ({"minecraft": "1.21.4"}, {"loader": {"id": loader, "version": "unverified"}}, {"loader": {"id": loader, "version": pack.profile(loader).version, "extra": True}}):
                mutated = values.copy(); mutated[pack.INDEX] = pack.encoded(json.loads(values[pack.INDEX]) | change)
                with self.subTest(loader=loader, change=change), self.assertRaisesRegex(pack.VerificationError, "pack_metadata_mismatch"):
                    self.check(mutated)
            mutated = values.copy()
            manifest = json.loads(mutated["manifest.json"])
            manifest["minecraft"]["modLoaders"][0]["id"] = wrong + "-" + pack.profile(wrong).version
            mutated["manifest.json"] = pack.encoded(manifest)
            index = json.loads(mutated[pack.INDEX]); index["files"] = pack.file_records(mutated, loader)
            mutated[pack.INDEX] = pack.encoded(index)
            with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "pack_launcher_manifest"):
                self.check(mutated)
            mutated = values.copy()
            mutated[pack.GUIDE] = b"unverified loader instructions"
            mutated["overrides/" + pack.GUIDE] = mutated[pack.GUIDE]
            index = json.loads(mutated[pack.INDEX]); index["files"] = pack.file_records(mutated, loader)
            mutated[pack.INDEX] = pack.encoded(index)
            with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "pack_install_guide"):
                self.check(mutated)
            if loader != "fabric":
                with self.subTest(loader=loader), self.assertRaisesRegex(pack.VerificationError, "pack_archive_layout"):
                    self.check(values | {pack.API: self.api})

    def test_cli_defaults_and_loader_specific_build_and_verify(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            api = root / "fabric-api.jar"; api.write_bytes(self.api)
            for loader in ("fabric", "neoforge", "forge"):
                mod = root / (loader + ".jar"); mod.write_bytes(self.mod(loader=loader))
                output = root / (loader + ".zip")
                selection = [] if loader == "fabric" else ["--loader", loader]
                arguments = ["hmcl_pack.py", "build", "--mod", str(mod), "--platform", "windows-x86_64", "--output", str(output), *selection]
                if loader == "fabric": arguments.extend(["--fabric-api", str(api)])
                with self.subTest(loader=loader), patch("sys.argv", arguments), patch("sys.stdout", new=io.StringIO()) as status:
                    pack.main()
                self.assertEqual(json.loads(status.getvalue().splitlines()[1])["loader"]["id"], loader)
                with patch("sys.argv", ["hmcl_pack.py", "verify", str(output), "--platform", "windows-x86_64", *selection]), \
                        patch("sys.stdout", new=io.StringIO()) as status:
                    pack.main()
                self.assertIn("PASS HMCL ZIP structure/source binding: " + loader, status.getvalue())
                if loader != "fabric":
                    with patch("sys.argv", ["hmcl_pack.py", "verify", str(output), "--platform", "windows-x86_64"]), \
                            patch("sys.stderr", new=io.StringIO()) as error, self.assertRaises(SystemExit) as stopped:
                        pack.main()
                    self.assertEqual(stopped.exception.code, 1)
                    self.assertIn("pack_loader_mismatch", error.getvalue())
        for loader, supplied in (("fabric", False), ("neoforge", True), ("forge", True)):
            arguments = ["hmcl_pack.py", "build", "--mod", "fixture.jar", "--platform", "windows-x86_64", "--output", "fixture.zip", "--loader", loader]
            if supplied: arguments.extend(["--fabric-api", "fixture-api.jar"])
            with self.subTest(loader=loader), patch("sys.argv", arguments), patch("sys.stderr", new=io.StringIO()), \
                    patch.object(pack, "regular_bytes") as snapshot, self.assertRaises(SystemExit) as stopped:
                pack.main()
            self.assertEqual(stopped.exception.code, 2)
            snapshot.assert_not_called()

    def test_wine_build_info_provenance_is_preserved_in_the_verified_zip(self):
        top, _, _, native = fixtures.ModJarTests().fixture(platform="windows-x86_64")
        key = "p2wlan/native/windows-x86_64/manifest.properties"
        native[key] = native[key].replace("build-kind=development", "build-kind=release").replace("native-build-info", "wine-build-info")
        top["META-INF/jars/native.jar"] = fixtures.jar(native)
        archive = pack.build_bytes(fixtures.jar(top), self.api, "windows-x86_64")
        index = pack.verify_zip(io.BytesIO(archive), "windows-x86_64")
        self.assertEqual(index["native_verification_methods"], [{"platform": "windows-x86_64", "method": "wine-build-info"}])
        guide = pack.guide("windows-x86_64").decode()
        self.assertIn("wine-build-info 表示在 Wine 中执行构建身份核验", guide)
        self.assertIn("Windows/HMCL 与跨机器联机待验证", guide)
        self.assertIn("Intel/AMD 64 位", pack.guide("windows-x86_64").decode())

    def test_guide_uses_saved_login_and_vanilla_multiplayer_flow(self):
        for loader in ("fabric", "neoforge", "forge"):
            guide = pack.guide("windows-x86_64", loader).decode("utf-8")
            for text in ("保留登录会话和地址", "点击房间名称", "Minecraft 多人游戏", "断开房间", "悬停",
                         "P2WLAN 路径的实测往返时间", "最近 60 秒", "最多 256 个成功 RTT 样本", "未测得", "MTU",
                         "HTTP 明文传输", "Windows/HMCL 与跨机器联机待验证", "wine-build-info 表示在 Wine 中",
                         "专服及内置世界", "共享本地世界", "停止共享", "朋友连接同一房间"):
                with self.subTest(loader=loader, text=text): self.assertIn(text, guide)
            self.assertNotIn("刷新服务列表", guide)
            self.assertNotIn("node-", guide)

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
        guide = values[pack.GUIDE].decode("utf-8")
        self.assertIn("Control 支持 HTTPS 和 HTTP", guide)
        self.assertIn("HTTP 明文传输账号、房间和信令", guide)
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

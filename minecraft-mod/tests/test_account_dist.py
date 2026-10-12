import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
import verify_account_dist as verify
import test_mod_jar as fixtures


class AccountDistributionTests(unittest.TestCase):
    def setUp(self):
        self.jna_binding = fixtures.jna_fixture_binding()
        self.jna_binding.start()
        self.addCleanup(self.jna_binding.stop)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / "installed"
        (self.directory / "bin").mkdir(parents=True)
        (self.directory / "lib").mkdir()
        _, _, self.common, self.native = fixtures.ModJarTests().fixture()
        self.cli = {verify.MAIN + ".class": fixtures.bytecode(61)}
        for filename, values in (("account-cli-0.1.0.jar", self.cli), ("common-0.1.0.jar", self.common),
                                 ("native-bundle-0.1.0.jar", self.native), ("gson-2.10.1.jar", {"META-INF/MANIFEST.MF": b"fixture"}),
                                 ("jna-5.14.0.jar", fixtures.JNA_FIXTURE)):
            self.write(filename, values)
        script = verify.MAIN.replace("/", ".") + " " + " ".join(sorted(verify.LIBRARIES))
        for name in ("p2wlan-account", "p2wlan-account.bat"):
            path = self.directory / "bin" / name
            path.write_text(script)
            path.chmod(0o755)
        self.archive = self.root / "distribution.zip"
        self.zip()

    def write(self, name, values):
        (self.directory / "lib" / name).write_bytes(fixtures.jar(values))

    def zip(self):
        with zipfile.ZipFile(self.archive, "w") as archive:
            for path in sorted(self.directory.rglob("*")):
                if path.is_file():
                    archive.write(path, "p2wlan-account-0.1.0/" + path.relative_to(self.directory).as_posix())

    def test_complete_native_distribution_and_development_opt_in(self):
        self.assertEqual(verify.verify(self.directory, True)[0]["verification-platform"], "macos-aarch64")
        verify.verify_zip(self.archive, self.directory)
        with self.assertRaisesRegex(verify.VerificationError, "mod_development_native_disabled"):
            verify.verify(self.directory)

    def test_console_runtime_dependencies_match_its_independent_distribution_contract(self):
        source = (PROJECT / 'java/account-cli/build.gradle').read_text()
        self.assertIn("implementation project(':common')", source)
        for coordinate, filename in (('com.google.code.gson:gson:2.10.1', 'gson-2.10.1.jar'),
                                     ('net.java.dev.jna:jna:5.14.0', 'jna-5.14.0.jar')):
            self.assertIn("runtimeOnly '" + coordinate + "'", source)
            self.assertIn(filename, verify.LIBRARIES)

    def test_jna_payload_and_standalone_dependency_are_required(self):
        self.write("jna-5.14.0.jar", {"META-INF/MANIFEST.MF": b"empty dependency"})
        with self.assertRaisesRegex(verify.VerificationError, "mod_jna_payload"):
            verify.verify(self.directory, True)
        (self.directory / "lib/jna-5.14.0.jar").unlink()
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_layout"):
            verify.verify(self.directory, True)

    def test_windows_native_name_and_zip_are_exact(self):
        _, _, _, native = fixtures.ModJarTests().fixture(platform="windows-x86_64")
        self.write("native-bundle-0.1.0.jar", native)
        self.assertEqual(verify.verify(self.directory, True)[0]["verification-platform"], "windows-x86_64")
        self.zip()
        verify.verify_zip(self.archive, self.directory)
        native["p2wlan/native/windows-x86_64/p2wlan-mc-agent"] = b"stray"
        self.write("native-bundle-0.1.0.jar", native)
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_native"):
            verify.verify(self.directory, True)

    def test_all_recognised_platforms_share_a_bounded_distribution(self):
        natives = {}
        for platform in sorted(verify.PLATFORMS):
            _, _, _, native = fixtures.ModJarTests().fixture(platform=platform)
            natives.update(native)
        self.write("native-bundle-0.1.0.jar", natives)
        self.assertEqual({item["verification-platform"] for item in verify.verify(self.directory, True)}, verify.PLATFORMS)

    def test_no_empty_stray_or_tampered_native_resources(self):
        self.write("native-bundle-0.1.0.jar", {})
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_native"):
            verify.verify(self.directory, True)
        self.native["p2wlan/native/unexpected/agent"] = b"fixture"
        self.write("native-bundle-0.1.0.jar", self.native)
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_native"):
            verify.verify(self.directory, True)
        del self.native["p2wlan/native/unexpected/agent"]
        self.native["p2wlan/native/macos-aarch64/p2wlan-mc-agent"] = b"changed"
        self.write("native-bundle-0.1.0.jar", self.native)
        with self.assertRaisesRegex(verify.VerificationError, "mod_native_checksum"):
            verify.verify(self.directory, True)

    def test_all_console_common_classes_are_game_independent_java17(self):
        for prefix in verify.PREFIXES:
            self.cli[verify.MAIN + ".class"] = fixtures.bytecode(61) + prefix
            self.write("account-cli-0.1.0.jar", self.cli)
            with self.assertRaisesRegex(verify.VerificationError, "account_distribution_game_dependency"):
                verify.verify(self.directory, True)
        self.cli[verify.MAIN + ".class"] = fixtures.bytecode(65)
        self.write("account-cli-0.1.0.jar", self.cli)
        with self.assertRaisesRegex(verify.VerificationError, "mod_class_version"):
            verify.verify(self.directory, True)
        self.cli[verify.MAIN + ".class"] = fixtures.bytecode(61)
        self.cli["io/p2wlan/mc/account/AccountToolTests.class"] = fixtures.bytecode(61)
        self.write("account-cli-0.1.0.jar", self.cli)
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_classes"):
            verify.verify(self.directory, True)

    def test_extra_dependencies_links_and_broken_launcher_are_rejected(self):
        extra = self.directory / "lib" / "minecraft.jar"
        extra.write_bytes(b"fixture")
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_layout"):
            verify.verify(self.directory, True)
        extra.unlink()
        script = self.directory / "bin" / "p2wlan-account"
        script.unlink(); script.symlink_to(self.directory / "bin" / "p2wlan-account.bat")
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_file"):
            verify.verify(self.directory, True)
        script.unlink(); script.write_text("unexpected launcher"); script.chmod(0o755)
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_launcher"):
            verify.verify(self.directory, True)

    def test_zip_must_match_installed_files_and_executable_permission(self):
        script = self.directory / "bin" / "p2wlan-account"
        script.write_text(script.read_text() + " changed")
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_zip_file"):
            verify.verify_zip(self.archive, self.directory)
        self.zip()
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("p2wlan-account-0.1.0/extra", b"fixture")
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_zip_layout"):
            verify.verify_zip(self.archive, self.directory)
        script.chmod(0o600); self.zip()
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_zip_file"):
            verify.verify_zip(self.archive, self.directory)

    def test_zip_cannot_include_symlink_entries(self):
        with zipfile.ZipFile(self.archive, "a") as archive:
            entry = zipfile.ZipInfo("p2wlan-account-0.1.0/link/")
            entry.create_system = 3; entry.external_attr = 0o120777 << 16
            archive.writestr(entry, b"target")
        with self.assertRaisesRegex(verify.VerificationError, "account_distribution_zip_file"):
            verify.verify_zip(self.archive, self.directory)

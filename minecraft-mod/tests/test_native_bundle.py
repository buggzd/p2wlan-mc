import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
spec = importlib.util.spec_from_file_location("native_bundle", PROJECT / "tools/native_bundle.py")
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class NativeBundleTests(unittest.TestCase):
    def info(self):
        return {"schema": 1, "protocol": 1, "version": "0.1.0", "source_revision": "a" * 40,
                "source_sha256": "b" * 64, "build_kind": "development", "os": "macos", "arch": "aarch64"}

    def test_artifacts_are_cargo_reported_and_unambiguous(self):
        item = {"reason": "compiler-artifact", "target": {"name": "p2wlan-mc-agent"}, "executable": "/fixture/agent"}
        self.assertEqual(bundle.select_agent(json.dumps(item)), Path("/fixture/agent"))
        for output in ("", json.dumps(item) + "\n" + json.dumps(item), json.dumps(item | {"executable": "relative"}),
                       json.dumps(item | {"target": {"name": "other"}})):
            with self.assertRaises(bundle.BundleError):
                bundle.select_agent(output)

    def test_exact_build_attestation_required(self):
        info = self.info()
        bundle.check_info(json.dumps(info).encode(), info)
        for value in (info | {"source_sha256": "c" * 64}, info | {"schema": True}, info | {"extra": "x"}):
            with self.assertRaises(bundle.BundleError):
                bundle.check_info(json.dumps(value).encode(), info)
        with self.assertRaises(bundle.BundleError):
            bundle.check_info(b'{"schema":1,"schema":1}', info)
        with self.assertRaises(bundle.BundleError):
            bundle.check_info(b" " * 4097, info)

    def test_manifest_binds_bytes_provenance_and_verified_platform(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "agent"
            binary.write_bytes(b"fixture executable")
            data = bundle.manifest(self.info(), binary)
            fields = dict(line.split("=", 1) for line in data.decode("ascii").splitlines())
            self.assertEqual(fields["executable-sha256"], hashlib.sha256(binary.read_bytes()).hexdigest())
            self.assertEqual(fields["executable-size"], "18")
            self.assertEqual(fields["verification-platform"], "macos-aarch64")
            self.assertEqual(fields["source-revision"], "a" * 40)
            self.assertEqual(fields["build-kind"], "development")
            self.assertIn(b"verification-platform=linux-x86_64\n", bundle.manifest(self.info() | {"os": "linux", "arch": "x86_64"}, binary))
            link = Path(directory) / "link"
            link.symlink_to(binary)
            with self.assertRaises(bundle.BundleError):
                bundle.manifest(self.info(), link)
            binary.write_bytes(b"")
            with self.assertRaises(bundle.BundleError):
                bundle.manifest(self.info(), binary)

    def test_publication_replaces_complete_platform_not_other_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "agent"
            binary.write_bytes(b"fixture")
            destination = root / "resources/macos-aarch64"
            bundle.publish(binary, bundle.manifest(self.info(), binary), destination)
            self.assertEqual((destination / "p2wlan-mc-agent").read_bytes(), b"fixture")
            other = root / "resources/linux-x86_64"
            other.mkdir()
            binary.write_bytes(b"replacement")
            metadata = bundle.manifest(self.info(), binary)
            bundle.publish(binary, metadata, destination)
            self.assertEqual((destination / "manifest.properties").read_bytes(), metadata)
            self.assertTrue(other.is_dir())
            self.assertFalse(destination.with_name(destination.name + ".previous").exists())

    def test_windows_publishes_exe_and_rejects_unknown_platform_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "agent.exe"
            binary.write_bytes(b"fixture")
            info = self.info() | {"os": "windows", "arch": "x86_64"}
            destination = root / "resources/windows-x86_64"
            bundle.publish(binary, bundle.manifest(info, binary), destination)
            self.assertEqual({path.name for path in destination.iterdir()}, {"p2wlan-mc-agent.exe", "manifest.properties"})
            self.assertEqual((destination / "p2wlan-mc-agent.exe").read_bytes(), b"fixture")
        for platform in ("windows-x86", "windows-aarch64", "unexpected"):
            with self.assertRaises(bundle.BundleError):
                bundle.executable_name(platform)

    def test_platforms_fail_closed(self):
        with patch.object(bundle.platform, "system", return_value="Darwin"), patch.object(bundle.platform, "machine", return_value="arm64"):
            self.assertEqual(bundle.host_platform(), ("macos", "aarch64"))
        with patch.object(bundle.platform, "system", return_value="Windows"), patch.object(bundle.platform, "machine", return_value="AMD64"):
            self.assertEqual(bundle.host_platform(), ("windows", "x86_64"))
        for cpu in ("x86", "arm64"):
            with patch.object(bundle.platform, "system", return_value="Windows"), patch.object(bundle.platform, "machine", return_value=cpu):
                with self.assertRaises(bundle.BundleError):
                    bundle.host_platform()

    def test_build_paths_are_remapped_without_losing_caller_flags(self):
        env = bundle.build_environment({"RUSTFLAGS": "-C opt-level=2", "CARGO_BUILD_TARGET": "foreign"}, "/fixture/repo", "/fixture/home")
        self.assertEqual(env["CARGO_ENCODED_RUSTFLAGS"].split("\x1f"), ["-C", "opt-level=2", "--remap-path-prefix=/fixture/home=build-home", "--remap-path-prefix=/fixture/repo=p2wlan-source"])
        self.assertNotIn("CARGO_BUILD_TARGET", env)
        self.assertNotIn("RUSTFLAGS", env)
        env = bundle.build_environment({"CARGO_ENCODED_RUSTFLAGS": "--cfg\x1ffixture"}, "/fixture/repo", "/fixture/home")
        self.assertTrue(env["CARGO_ENCODED_RUSTFLAGS"].startswith("--cfg\x1ffixture\x1f"))
        env = bundle.build_environment({"CARGO_ENCODED_RUSTFLAGS": "", "RUSTFLAGS": "--cfg ignored"}, "/fixture/repo", "/fixture/home")
        self.assertEqual(len(env["CARGO_ENCODED_RUSTFLAGS"].split("\x1f")), 2)

    def test_private_prefix_check_includes_block_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "agent"
            binary.write_bytes(b"x" * 32765 + b"/fixture/private-source" + b"x")
            with self.assertRaisesRegex(bundle.BundleError, "native_binary_private_build_path"):
                bundle.check_private_paths(binary, ["/fixture/private-source"])
            binary.write_bytes(b"p2wlan-source/source.rs build-home/.cargo/registry")
            bundle.check_private_paths(binary, ["/fixture/private-source"])

    def test_digest_ignores_generated_outputs_but_includes_new_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("Cargo.toml", "minecraft-mod/native/Cargo.toml", "minecraft-mod/native/Cargo.lock"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture")
            before = bundle.source_digest(root)
            generated = root / "minecraft-mod/native/target/generated.rs"
            generated.parent.mkdir()
            generated.write_text("ignored")
            self.assertEqual(bundle.source_digest(root), before)
            source = root / "minecraft-mod/native/agent/src/new.rs"
            source.parent.mkdir(parents=True)
            source.write_text("new")
            self.assertNotEqual(bundle.source_digest(root), before)
            windows = root / "minecraft-mod/native/private-windows/src/security.rs"
            windows.parent.mkdir(parents=True)
            windows.write_text("windows permission input")
            windows_digest = bundle.source_digest(root)
            windows.write_text("changed permission input")
            self.assertNotEqual(bundle.source_digest(root), windows_digest)
            asset = root / "client/daemon/src/route/windows/icmp_echo.ps1"
            asset.parent.mkdir(parents=True)
            before_asset = bundle.source_digest(root)
            asset.write_text("compile-time Windows input")
            asset_digest = bundle.source_digest(root)
            self.assertNotEqual(asset_digest, before_asset)
            asset.write_text("changed compile-time input")
            self.assertNotEqual(bundle.source_digest(root), asset_digest)
            source.unlink()
            source.symlink_to(generated)
            with self.assertRaises(bundle.BundleError):
                bundle.source_digest(root)


if __name__ == "__main__":
    unittest.main()

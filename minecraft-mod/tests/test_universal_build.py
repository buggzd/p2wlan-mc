import importlib.util
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("universal_build", TOOLS / "universal_build.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class UniversalBuildTests(unittest.TestCase):
    def test_native_inputs_are_copied_into_a_fresh_tree_with_their_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "resources/p2wlan/native/macos-aarch64"
            source.mkdir(parents=True)
            (source / "manifest.properties").write_bytes(b"fixture manifest\n")
            (source / "p2wlan-mc-agent").write_bytes(b"fixture executable")
            native = SimpleNamespace(OUTPUT=root / "resources", PLATFORMS={"macos-aarch64"}, executable_name=lambda _: "p2wlan-mc-agent")
            from universal_jar import regular_bytes
            with patch.dict(sys.modules, {"native_bundle": native}), \
                    patch.dict(sys.modules, {"universal_jar": SimpleNamespace(regular_bytes=regular_bytes)}):
                fresh = root / "fresh"
                fresh.mkdir()
                builder.prepare_inputs(fresh)
                output = fresh / "native-bundle/generatedResources/p2wlan/native/macos-aarch64"
                for path in source.iterdir():
                    self.assertEqual((output / path.name).read_bytes(), path.read_bytes())
                (source / "p2wlan-mc-agent").unlink()
                (source / "p2wlan-mc-agent").symlink_to(source / "manifest.properties")
                another = root / "another"
                another.mkdir()
                with self.assertRaises(ValueError):
                    builder.prepare_inputs(another)

    def test_checked_inputs_run_in_separate_gradle_graphs_with_explicit_options(self):
        with patch.dict(sys.modules, {"forge_gradle": SimpleNamespace(command=lambda env: ["java21", "-jar", "forge-wrapper.jar"]),
                                     "legacy_gradle": SimpleNamespace(command=lambda env: ["java17", "-jar", "legacy-wrapper.jar"])}):
            selected = builder.commands({}, offline=True, development=True, build_root=Path('/generated/inputs'))
        self.assertEqual(len(selected), 6)
        self.assertEqual(selected[3][0], builder.PROJECT / "java/forge")
        self.assertEqual(selected[4][0], builder.PROJECT / "java/forge-legacy")
        self.assertEqual(selected[4][1][:3], ['java17', '-jar', 'legacy-wrapper.jar'])
        self.assertIn('-PfabricLegacyAdapter', selected[5][1])
        self.assertIn(':fabric-legacy:verifyModJar', selected[5][1])
        for _, command in selected:
            self.assertIn("--offline", command)
            self.assertIn("-PdevelopmentNative", command)
            self.assertIn("-Dp2wlan.universal.outputRoot=/generated/inputs", command)
            self.assertIn("--project-cache-dir", command)
            self.assertFalse(any("runClient" in item or "runServer" in item for item in command))
            self.assertFalse(any(item.endswith(":clean") for item in command))
        self.assertIn(":universal-bootstrap:check", selected[0][1])
        for (_, command), loader in zip(selected[1:4], ("fabric", "neoforge", "forge")):
            self.assertIn(":" + loader + ":verifyModJar", command)
            for other in {"fabric", "neoforge", "forge"} - {loader}:
                self.assertNotIn("-P" + other + "Adapter", command)

    def test_only_verified_bytes_are_published_and_existing_results_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            output = project / "dist/p2wlan-universal.jar"
            output.parent.mkdir()
            def publish(path, value):
                if path.is_symlink() or path.exists() and path.read_bytes() != value:
                    raise ValueError("output_exists")
                if not path.exists():
                    path.write_bytes(value)
            assembled = SimpleNamespace(build_bytes=lambda *args, **kwargs: b"verified archive", regular_bytes=lambda *args: b"input archive",
                                        verify=lambda *args: {"artifact_sha256": "fixture"}, publish=publish)
            with patch.object(builder, "PROJECT", project), patch.object(builder, "commands", return_value=[]), \
                    patch.object(builder, "prepare_inputs"), \
                    patch.object(builder, "relocate_fabric", return_value=b"relocated input"), \
                    patch.dict(sys.modules, {"universal_jar": assembled}):
                self.assertEqual(builder.build(output, {}), {"artifact_sha256": "fixture"})
                self.assertEqual(output.read_bytes(), b"verified archive")
                builder.build(output, {})
                assembled.build_bytes = lambda *args, **kwargs: b"different archive"
                with self.assertRaisesRegex(ValueError, "output_exists"):
                    builder.build(output, {})
                self.assertEqual(output.read_bytes(), b"verified archive")
                assembled.verify = lambda *args: (_ for _ in ()).throw(ValueError("source changed"))
                with self.assertRaises(ValueError):
                    builder.build(output, {})
                self.assertEqual(output.read_bytes(), b"verified archive")
                outside = project / "outside.jar"
                outside.write_bytes(b"untouched")
                output.unlink()
                output.symlink_to(outside)
                assembled.verify = lambda *args: {}
                with self.assertRaisesRegex(ValueError, "output_exists"):
                    builder.build(output, {})
                self.assertEqual(outside.read_bytes(), b"untouched")
            self.assertFalse(list(output.parent.glob(".p2wlan-universal-*.tmp")))


if __name__ == "__main__":
    unittest.main()

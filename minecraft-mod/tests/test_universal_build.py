import importlib.util
from pathlib import Path
import sys
import tempfile
from types import MappingProxyType, SimpleNamespace
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
                                     "legacy_gradle": SimpleNamespace(command=lambda env: ["java17", "-jar", "legacy-wrapper.jar"]),
                                     'forge_1182_gradle': SimpleNamespace(command=lambda env: ['java17', '-jar', 'forge18-wrapper.jar']),
                                     'forge_1165_gradle': SimpleNamespace(command=lambda env: ['java17', '-jar', 'forge16-wrapper.jar'])}):
            selected = builder.commands({}, offline=True, development=True, build_root=Path('/generated/inputs'))
        self.assertEqual(len(selected), 11)
        self.assertEqual(selected[1][0], builder.PROJECT / 'java/legacy-bootstrap')
        self.assertIn(':jar', selected[1][1])
        self.assertEqual(selected[4][0], builder.PROJECT / "java/forge")
        self.assertEqual(selected[5][0], builder.PROJECT / "java/forge-legacy")
        self.assertEqual(selected[5][1][:3], ['java17', '-jar', 'legacy-wrapper.jar'])
        self.assertIn('-PfabricLegacyAdapter', selected[6][1])
        self.assertIn(':fabric-legacy:verifyModJar', selected[6][1])
        self.assertIn('-Pfabric1165Adapter', selected[-2][1])
        self.assertIn(':fabric-1165:verifyModJar', selected[-2][1])
        self.assertEqual(selected[-1][0], builder.PROJECT / 'java/forge-1165')
        self.assertEqual(selected[-1][1][:3], ['java17', '-jar', 'forge16-wrapper.jar'])
        self.assertIn(':forge:verifyModJar', selected[-1][1])
        for _, command in selected:
            self.assertIn("--offline", command)
            self.assertIn("-PdevelopmentNative", command)
            self.assertIn("-Dp2wlan.universal.outputRoot=/generated/inputs", command)
            self.assertIn("--project-cache-dir", command)
            self.assertFalse(any("runClient" in item or "runServer" in item for item in command))
            self.assertFalse(any(item.endswith(":clean") for item in command))
        self.assertIn(":universal-bootstrap:check", selected[0][1])
        for (_, command), loader in zip(selected[2:5], ("fabric", "neoforge", "forge")):
            self.assertIn(":" + loader + ":verifyModJar", command)
            for other in {"fabric", "neoforge", "forge"} - {loader}:
                self.assertNotIn("-P" + other + "Adapter", command)

    def test_commands_follow_exact_publication_roles_and_do_not_infer_removed_pairs(self):
        modules = {'forge_gradle': SimpleNamespace(command=lambda env: ['forge-wrapper']),
                   'legacy_gradle': SimpleNamespace(command=lambda env: ['legacy-wrapper']),
                   'forge_1182_gradle': SimpleNamespace(command=lambda env: ['forge1182-wrapper']),
                   'forge_1165_gradle': SimpleNamespace(command=lambda env: ['forge1165-wrapper'])}
        with patch.dict(sys.modules, modules):
            baseline_profiles = {key: item for key, item in builder.registry.PROFILES.items() if key[0] not in {'1.18.2', '1.16.5'}}
            with patch.object(builder.registry, 'PROFILES', MappingProxyType(baseline_profiles)):
                baseline = builder.commands({})
            self.assertEqual(len(baseline), 6)
            self.assertFalse(any('1182' in str(item) for item in baseline))
            candidate = builder.registry.SDK_INPUTS['forge_1165']
            self.assertIn(candidate.role, builder.registry.sdk_inputs())
            sparse = {key: item for key, item in builder.registry.PROFILES.items()
                      if key != (candidate.minecraft, candidate.loader)}
            with patch.object(builder.registry, 'PROFILES', MappingProxyType(sparse)):
                self.assertNotIn(candidate.role, builder.registry.sdk_inputs())
                excluded = builder.commands({})
            self.assertEqual(len(excluded), 9)
            self.assertFalse(any(directory.name in ('forge-1165', 'legacy-bootstrap') for directory, _ in excluded))
            selected = builder.commands({}, build_root=Path('/generated/inputs'))
            self.assertEqual(len(selected), 11)
            self.assertEqual(selected[1][0], builder.PROJECT / 'java/legacy-bootstrap')
            self.assertIn(':jar', selected[1][1])
            self.assertEqual(selected[1][1][-1], '/generated/inputs/legacy-bootstrap-cache')
            self.assertEqual(selected[-1][0], builder.PROJECT / 'java/forge-1165')
            self.assertEqual(selected[-1][1][0], 'forge1165-wrapper')
            self.assertIn(':forge:verifyModJar', selected[-1][1])
            self.assertEqual(selected[-1][1][-1], '/generated/inputs/forge-1165-cache')

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
            assembled = SimpleNamespace(build_inputs=lambda *args, **kwargs: b"verified archive", regular_bytes=lambda *args: b"input archive",
                                        verify=lambda *args: {"artifact_sha256": "fixture"}, publish=publish)
            with patch.object(builder, "PROJECT", project), patch.object(builder, "commands", return_value=[]), \
                    patch.object(builder, "prepare_inputs"), \
                    patch.dict(sys.modules, {"universal_jar": assembled}):
                self.assertEqual(builder.build(output, {}), {"artifact_sha256": "fixture"})
                self.assertEqual(output.read_bytes(), b"verified archive")
                builder.build(output, {})
                assembled.build_inputs = lambda *args, **kwargs: b"different archive"
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

    def test_archive_read_paths_and_named_assembly_inputs_come_from_the_sdk_roles(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            snapshots = []
            calls = []
            def snapshot(path):
                snapshots.append(path)
                return path.name.encode()
            def assemble(sources, bootstrap, development, *, legacy_bootstrap):
                calls.append((sources, bootstrap, development, legacy_bootstrap))
                return b'verified archive'
            assembled = SimpleNamespace(build_inputs=assemble, regular_bytes=snapshot,
                                        verify=lambda *args: {}, publish=lambda *args: None)
            with patch.object(builder, 'PROJECT', project), patch.object(builder, 'commands', return_value=[]), \
                    patch.object(builder, 'prepare_inputs'), patch.dict(sys.modules, {'universal_jar': assembled}):
                builder.build(project / 'result.jar', {}, development=True)
            sources, bootstrap, development, carrier = calls[0]
            self.assertEqual(tuple(sources), tuple(builder.registry.sdk_inputs()))
            self.assertEqual(len(sources), 9)
            self.assertTrue(development)
            self.assertEqual(bootstrap, b'p2wlan-universal-bootstrap-0.1.0.jar')
            self.assertEqual(carrier, b'p2wlan-legacy-bootstrap-0.1.0.jar')
            self.assertEqual(len(snapshots), 11)
            self.assertEqual(snapshots[-2].parts[-3:], ('legacy-bootstrap', 'libs', 'p2wlan-legacy-bootstrap-0.1.0.jar'))
            self.assertEqual(snapshots[-1].parts[-3:], ('universal-bootstrap', 'libs', 'p2wlan-universal-bootstrap-0.1.0.jar'))
            self.assertEqual(len(set(sources) | {'relocated_fabric', 'bootstrap', 'legacy_bootstrap'}), 12)
            for (role, source), path in zip(builder.registry.sdk_inputs().items(), snapshots):
                self.assertEqual(path.parts[-3:], (source.output_directory, 'libs', source.archive_name('0.1.0')))
                self.assertEqual(sources[role], path.name.encode())


if __name__ == "__main__":
    unittest.main()

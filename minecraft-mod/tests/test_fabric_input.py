"""Post-remap Bootstrap assembly preserves exact input payloads and guards."""
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import fabric_input as assembler
from artifact_checks import VerificationError
import test_mod_jar as modern
import test_fabric_legacy_jar as legacy
from test_universal_jar import bytecode


class FabricInputTests(unittest.TestCase):
    def setUp(self):
        binding = modern.jna_fixture_binding(); binding.start(); self.addCleanup(binding.stop)
        binding = patch.object(assembler, 'bind_sources'); self.bind = binding.start(); self.addCleanup(binding.stop)

    def inputs(self, minecraft='1.21.1'):
        files = legacy.LegacyFabricJarTests().fixture()[0] if minecraft == '1.20.1' else modern.ModJarTests().fixture()[0]
        files = {name: value.encode() if isinstance(value, str) else value for name, value in files.items()
                 if not name.startswith(assembler.BOOTSTRAP)}
        canonical = {name: bytecode(name, neutral=True) for name in assembler.BOOTSTRAP_CLASSES}
        canonical.update({name: assembler.encoded(value) for name, value in assembler.CONFIGS.items()})
        canonical['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\r\n\r\n'
        return files, canonical

    def test_both_profiles_append_only_exact_canonical_classes(self):
        for minecraft in ('1.20.1', '1.21.1'):
            files, canonical = self.inputs(minecraft)
            original = deepcopy(files)
            result = assembler.members(assembler.build_bytes(assembler.archive_bytes(files),
                                      assembler.archive_bytes(canonical), minecraft, True))
            self.assertEqual(set(result) - set(files), assembler.BOOTSTRAP_CLASSES)
            self.assertEqual({name: result[name] for name in files}, original)
            self.assertEqual({name: result[name] for name in assembler.BOOTSTRAP_CLASSES},
                             {name: canonical[name] for name in assembler.BOOTSTRAP_CLASSES})
        self.assertEqual(self.bind.call_count, 2)
        self.assertTrue(all(call.args[1] is True for call in self.bind.call_args_list))

    def test_existing_bootstrap_is_rejected_even_if_identical(self):
        files, canonical = self.inputs()
        owner = next(iter(assembler.BOOTSTRAP_CLASSES))
        files[owner] = canonical[owner]
        with self.assertRaisesRegex(VerificationError, 'existing_bootstrap'):
            assembler.build_bytes(assembler.archive_bytes(files), assembler.archive_bytes(canonical), '1.21.1', True)
        self.bind.assert_not_called()

    def test_canonical_catalog_and_dispatch_configs_are_closed(self):
        files, canonical = self.inputs()
        owner = next(iter(assembler.BOOTSTRAP_CLASSES))
        for altered in ({n: v for n, v in canonical.items() if n != owner},
                        canonical | {'io/p2wlan/mc/bootstrap/Extra.class': canonical[owner]},
                        canonical | {'unlisted.json': b'{}'}):
            with self.assertRaisesRegex(VerificationError, 'bootstrap_layout'):
                assembler.build_bytes(assembler.archive_bytes(files), assembler.archive_bytes(altered), '1.21.1', True)
        name = next(iter(assembler.CONFIGS))
        altered = canonical | {name: assembler.encoded(assembler.CONFIGS[name] | {'required': False})}
        with self.assertRaisesRegex(VerificationError, 'bootstrap_config'):
            assembler.build_bytes(assembler.archive_bytes(files), assembler.archive_bytes(altered), '1.21.1', True)

    def test_duplicate_entries_and_malformed_bootstrap_classes_fail(self):
        files, canonical = self.inputs()
        duplicate = io.BytesIO()
        with zipfile.ZipFile(duplicate, 'w') as archive:
            archive.writestr('fabric.mod.json', files['fabric.mod.json'])
            with self.assertWarns(UserWarning): archive.writestr('fabric.mod.json', files['fabric.mod.json'])
        with self.assertRaisesRegex(VerificationError, 'archive_entries'):
            assembler.build_bytes(duplicate.getvalue(), assembler.archive_bytes(canonical), '1.21.1', True)
        owner = next(iter(assembler.BOOTSTRAP_CLASSES))
        for changed in (canonical[owner] + b'trailing bytes', bytecode(owner, major=65, neutral=True)):
            with self.assertRaises(VerificationError):
                assembler.build_bytes(assembler.archive_bytes(files), assembler.archive_bytes(canonical | {owner: changed}), '1.21.1', True)

    def test_profile_and_development_native_are_never_implicit(self):
        files, canonical = self.inputs()
        mapped = assembler.archive_bytes(files); bootstrap = assembler.archive_bytes(canonical)
        with self.assertRaisesRegex(VerificationError, 'game_profile'):
            assembler.build_bytes(mapped, bootstrap, '1.20.2', True)
        with self.assertRaisesRegex(VerificationError, 'legacy_fabric_mod_contract'):
            assembler.build_bytes(mapped, bootstrap, '1.20.1', True)
        with self.assertRaisesRegex(VerificationError, 'development_native_disabled'):
            assembler.build_bytes(mapped, bootstrap, '1.21.1')

    def test_current_source_binding_is_required_before_publication(self):
        files, canonical = self.inputs()
        self.bind.side_effect = VerificationError('mod_native_sources_changed_rebuild_bundle')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mapped = root / 'mapped.jar'; mapped.write_bytes(assembler.archive_bytes(files))
            bootstrap = root / 'bootstrap.jar'; bootstrap.write_bytes(assembler.archive_bytes(canonical))
            output = root / 'final.jar'
            with self.assertRaisesRegex(VerificationError, 'sources_changed'):
                assembler.build(mapped, bootstrap, output, '1.21.1', True)
            self.assertFalse(output.exists())

    def test_final_output_is_create_only_and_source_jars_are_unchanged(self):
        files, canonical = self.inputs()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mapped = root / 'mapped.jar'; mapped.write_bytes(assembler.archive_bytes(files))
            bootstrap = root / 'bootstrap.jar'; bootstrap.write_bytes(assembler.archive_bytes(canonical))
            snapshots = mapped.read_bytes(), bootstrap.read_bytes()
            output = root / 'final.jar'
            sha = assembler.build(mapped, bootstrap, output, '1.21.1', True)
            self.assertEqual(assembler.build(mapped, bootstrap, output, '1.21.1', True), sha)
            output.write_bytes(b'existing different artifact')
            with self.assertRaisesRegex(VerificationError, 'output_exists'):
                assembler.build(mapped, bootstrap, output, '1.21.1', True)
            self.assertEqual(output.read_bytes(), b'existing different artifact')
            for source in (mapped, bootstrap):
                with self.assertRaisesRegex(VerificationError, 'output_role'):
                    assembler.build(mapped, bootstrap, source, '1.21.1', True)
            self.assertEqual((mapped.read_bytes(), bootstrap.read_bytes()), snapshots)
            self.assertFalse(list(root.glob('.p2wlan-universal-*.tmp')))

    def test_build_graphs_use_final_provider_for_all_production_consumers(self):
        project = Path(__file__).resolve().parents[1]
        for minecraft in ('1.20.1', '1.21.1'):
            value = (project / ('java/adapters/fabric-' + minecraft + '/build.gradle')).read_text()
            self.assertIn("implementation project(':universal-bootstrap')", value)
            self.assertNotIn("project(':universal-bootstrap').sourceSets.main.output.classesDirs", value)
            self.assertIn("destinationDirectory = layout.buildDirectory.dir('remapped')", value)
            self.assertIn("dependsOn mappedJar, ':universal-bootstrap:jar'", value)
            self.assertIn('assemble.dependsOn finalizeFabricInput', value)
            self.assertIn('if (!externalInstalled) dependsOn finalizeFabricInput', value)
            verify = value.split("tasks.register('verifyModJar', Exec)", 1)[1].split('check.dependsOn', 1)[0]
            self.assertIn('finalJar.get().asFile', verify)
            self.assertNotIn("tasks.named('remapJar')", verify)
            if minecraft == '1.21.1':
                pack = value.split("tasks.register('buildHmclPack', Exec)", 1)[1]
                self.assertIn("'--mod', finalJar.get().asFile", pack)


if __name__ == '__main__': unittest.main()

import hashlib
import io
from pathlib import Path
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from artifact_checks import VerificationError
import legacy_core_index as index
from legacy_bootstrap_sources import java_source
import verify_legacy_bootstrap as carrier


def archive(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as target:
        for name, value in files.items():
            target.writestr(name, value)
    return output.getvalue()


class LegacyBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.revision = '1' * 40
        self.native_sha = '2' * 64
        self.files = {path: archive({'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\n\n'})
                      for path in index.OWNERS.values()}
        native = {'p2wlan/native/macos-aarch64/manifest.properties':
                  f'source-revision={self.revision}\nsource-sha256={self.native_sha}\n'.encode()}
        self.files[index.OWNERS['native']] = archive(native)
        self.carrier = {name: b'\xca\xfe\xba\xbe\0\0\0\x3d' + b'fixture' for name in carrier.CLASSES}
        self.carrier.update({index.SERVICE: carrier.SERVICE_BYTES, 'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\n\n'})

    def test_exact_carrier_catalog_and_spi(self):
        actual = carrier.verify(io.BytesIO(archive(self.carrier)))
        self.assertEqual(set(actual), carrier.CLASSES | {index.SERVICE})
        self.assertEqual(len(carrier.CLASSES), 30)
        self.assertIn(carrier.PREFIX + 'LegacySource.class', carrier.CLASSES)
        self.assertIn(carrier.PREFIX + 'LegacyForge40Factory.class', carrier.CLASSES)
        self.assertIn(carrier.PREFIX + 'LegacyForge47Factory.class', carrier.CLASSES)
        self.assertIn(carrier.PREFIX + 'LegacyForge52Factory.class', carrier.CLASSES)
        self.assertIn(carrier.PREFIX + 'LegacyForge52Factory$GameMetadata.class', carrier.CLASSES)
        self.assertEqual(index.SERVICE, 'META-INF/services/net.minecraftforge.forgespi.locating.IModLocator')

    def test_rejects_bundled_sdk_and_alternative_spi(self):
        for name in ('net/minecraftforge/forgespi/locating/IModLocator.class',
                     'META-INF/services/cpw.mods.modlauncher.api.ITransformationService',
                     'io/p2wlan/mc/common/WorldSessions.class'):
            with self.subTest(name=name), self.assertRaisesRegex(VerificationError, 'legacy_bootstrap_catalog'):
                carrier.verify(io.BytesIO(archive(self.carrier | {name: b'foreign'})))

    def test_rejects_missing_class_or_duplicate_service(self):
        missing = self.carrier.copy(); missing.pop(next(iter(carrier.CLASSES)))
        for files in (missing, self.carrier | {index.SERVICE: carrier.SERVICE_BYTES * 2}):
            with self.assertRaises(VerificationError): carrier.verify(io.BytesIO(archive(files)))

    def test_rejects_wrong_class_version_and_network_instantiation(self):
        name = carrier.PREFIX + 'LegacyModLocator.class'
        for value in (b'\xca\xfe\xba\xbe\0\0\0\x41fixture', self.carrier[name] + b'java/net/Socket'):
            with self.assertRaises(VerificationError):
                carrier.verify(io.BytesIO(archive(self.carrier | {name: value})))

    def test_index_binds_only_four_canonical_nested_owners(self):
        raw = index.index_bytes(self.files, self.revision, self.native_sha)
        fields = index.index_values(raw)
        for owner, path in index.OWNERS.items():
            self.assertEqual(fields[owner + '.path'], path)
            self.assertEqual(fields[owner + '.size'], str(len(self.files[path])))
            self.assertEqual(fields[owner + '.sha256'], hashlib.sha256(self.files[path]).hexdigest())
        self.assertEqual(len(fields), 20)

    def test_index_requires_complete_source_identity(self):
        for revision, digest in (('1' * 39, self.native_sha), (self.revision, '2' * 63)):
            with self.assertRaisesRegex(VerificationError, 'legacy_core_source_identity'):
                index.index_bytes(self.files, revision, digest)
        with self.assertRaisesRegex(VerificationError, 'legacy_core_source_identity'):
            index.index_bytes(self.files, '3' * 40, self.native_sha)

    def test_rejects_index_tuple_paths_sizes_digest_and_duplicates(self):
        raw = index.index_bytes(self.files, self.revision, self.native_sha)
        cases = [('minecraft=1.16.5', 'minecraft=1.16.4'), ('java=17', 'java=21'),
                 ('common.path=META-INF/jarjar/common.jar', 'common.path=META-INF/jars/common.jar'),
                 ('common.size=' + str(len(self.files[index.OWNERS['common']])), 'common.size=0'),
                 ('common.sha256=' + hashlib.sha256(self.files[index.OWNERS['common']]).hexdigest(), 'common.sha256=broken')]
        for old, new in cases:
            with self.subTest(new=new), self.assertRaisesRegex(VerificationError, 'legacy_core_index_contract'):
                index.index_values(raw.replace(old.encode(), new.encode()))
        with self.assertRaisesRegex(VerificationError, 'legacy_core_index_contract'):
            index.index_values(raw + b'schema=1\n')

    def test_owner_and_resource_inventory_share_generated_source(self):
        source = java_source()
        for path in index.OWNERS.values(): self.assertIn('"' + path + '"', source)
        self.assertEqual(len(index.resource_names()), 36)
        for resource in index.resource_names(): self.assertIn('"' + resource + '"', source)


if __name__ == '__main__':
    unittest.main()

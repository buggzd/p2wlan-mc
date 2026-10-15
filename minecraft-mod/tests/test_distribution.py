"""Distribution checks exercise actual HMCL ZIP construction with synthetic JAR bytes."""
from dataclasses import replace
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
from types import MappingProxyType
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1] / 'tools'
sys.path.insert(0, str(TOOLS))
import distribution
import hmcl_pack as pack
import minecraft_profiles as registry


class DistributionTests(unittest.TestCase):
    def fixture(self, directory):
        root = Path(directory)
        mod = root / 'input.jar'
        mod.write_bytes(b'checked universal fixture')
        cache = root / 'apis'
        cache.mkdir()
        profiles = dict(registry.PROFILES)
        for key, selected in profiles.items():
            if selected.api is None:
                continue
            value = io.BytesIO()
            with zipfile.ZipFile(value, 'w') as archive:
                archive.writestr('fabric.mod.json', json.dumps({'id': selected.api.mod_id, 'version': selected.api_version}))
            value = value.getvalue()
            profiles[key] = replace(selected, api=replace(selected.api, artifact_sha256=pack.checksum(value)))
            (cache / f'fabric-api-{selected.api_version}.jar').write_bytes(value)
        metadata = {'schema': 2, 'artifact_sha256': pack.checksum(mod.read_bytes()),
                    'source_revision': 'a' * 40, 'native_source_sha256': 'b' * 64,
                    'native_targets': ['linux-x86_64', 'windows-x86_64'],
                    'native_verification_methods': [{'platform': platform, 'method': 'native-build-info'}
                                                    for platform in ['linux-x86_64', 'windows-x86_64']],
                    'minecraft': list(registry.MINECRAFT_VERSIONS),
                    'loaders': {minecraft: {loader: selected.version for (game, loader), selected in profiles.items() if game == minecraft}
                                for minecraft in registry.MINECRAFT_VERSIONS}, 'inputs': {}}
        self.enterContext(patch.object(registry, 'PROFILES', MappingProxyType(profiles)))
        self.enterContext(patch.object(pack, 'verify_universal', return_value=metadata))
        return root, mod, cache

    def test_distribution_contains_only_player_artifacts_bound_to_exact_checksums(self):
        with tempfile.TemporaryDirectory() as directory:
            root, mod, cache = self.fixture(directory)
            output = root / 'dist'
            info = distribution.build(mod, output, ['windows-x86_64'], cache)
            self.assertEqual(len(info['files']), 11)
            self.assertEqual(info['hmcl_platforms'], ['windows-x86_64'])
            self.assertEqual(info['native_targets'], ['linux-x86_64', 'windows-x86_64'])
            checksums = dict(line.split('  ', 1)[::-1] for line in (output / 'SHA256SUMS').read_text().splitlines())
            self.assertEqual(set(checksums) | {'SHA256SUMS'}, {path.name for path in output.iterdir()})
            for name, checksum in checksums.items():
                self.assertEqual(hashlib.sha256((output / name).read_bytes()).hexdigest(), checksum)
            for minecraft, loader in registry.PROFILES:
                path = output / f'p2wlan-hmcl-{minecraft}-{loader}-windows-x86_64.zip'
                checked = pack.verify_zip(path, 'windows-x86_64', loader, minecraft, True)
                self.assertEqual(checked['source_revision'], info['source_revision'])
            before = {path.name: path.read_bytes() for path in output.iterdir()}
            distribution.build(mod, output, ['windows-x86_64'], cache)
            self.assertEqual(before, {path.name: path.read_bytes() for path in output.iterdir()})

    def test_missing_or_repeated_native_target_refuses_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root, mod, cache = self.fixture(directory)
            for targets in ([], ['macos-aarch64'], ['windows-x86_64'] * 2):
                with self.subTest(targets=targets), self.assertRaises(pack.VerificationError):
                    distribution.build(mod, root / 'dist', targets, cache)
                self.assertFalse((root / 'dist').exists())

    def test_corrupt_dependency_and_existing_unrelated_file_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root, mod, cache = self.fixture(directory)
            selected = registry.profile('1.16.5', 'fabric')
            path = cache / f'fabric-api-{selected.api_version}.jar'
            original = path.read_bytes()
            path.write_bytes(b'corrupt')
            with self.assertRaisesRegex(pack.VerificationError, 'fabric_api_checksum'):
                distribution.build(mod, root / 'dist', ['windows-x86_64'], cache, True)
            self.assertEqual(path.read_bytes(), b'corrupt')
            self.assertFalse((root / 'dist').exists())
            path.write_bytes(original)
            output = root / 'dist'
            output.mkdir()
            (output / 'old-debug.jar').write_bytes(b'preserved')
            with self.assertRaisesRegex(pack.VerificationError, 'unexpected_files'):
                distribution.build(mod, output, ['windows-x86_64'], cache)
            self.assertEqual(list(output.iterdir()), [output / 'old-debug.jar'])

    def test_missing_dependency_download_is_bounded_and_never_caches_unverified_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root, _, cache = self.fixture(directory)
            selected = registry.profile('1.21.1', 'fabric')
            path = cache / f'fabric-api-{selected.api_version}.jar'
            value = path.read_bytes()
            path.unlink()
            with self.assertRaisesRegex(pack.VerificationError, 'fabric_api_missing'):
                distribution.api_bytes(selected, cache)
            with patch.object(distribution.urllib.request, 'urlopen', return_value=io.BytesIO(b'corrupt')) as request:
                with self.assertRaisesRegex(pack.VerificationError, 'fabric_api_checksum'):
                    distribution.api_bytes(selected, cache, True)
                self.assertEqual(request.call_args.kwargs['timeout'], 60)
            self.assertFalse(path.exists())
            with patch.object(distribution.urllib.request, 'urlopen', return_value=io.BytesIO(value)):
                self.assertEqual(distribution.api_bytes(selected, cache, True), value)
            self.assertEqual(path.read_bytes(), value)

    def test_legacy_full_installation_downloads_follow_registry_artifact_urls(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, cache = self.fixture(directory)
            for minecraft in ('1.16.5', '1.18.2'):
                selected = registry.profile(minecraft, 'fabric')
                path = cache / f'fabric-api-{selected.api_version}.jar'
                value = path.read_bytes()
                path.unlink()
                with self.subTest(minecraft=minecraft), patch.object(
                        distribution.urllib.request, 'urlopen', return_value=io.BytesIO(value)) as request:
                    distribution.api_bytes(selected, cache, True)
                    self.assertEqual(request.call_args.args[0], selected.api.artifact_url)
                    self.assertTrue(selected.api.artifact_url.startswith('https://cdn.modrinth.com/'))
                self.assertEqual(path.read_bytes(), value)


if __name__ == '__main__':
    unittest.main()

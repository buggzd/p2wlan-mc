import copy
import io
from pathlib import Path
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import verify_neoforge_jar as verify
import test_mod_jar as fixtures
from test_mod_jar import bytecode, jar, jna_fixture_binding


class NeoForgeJarTests(unittest.TestCase):
    def setUp(self):
        self.binding = jna_fixture_binding()
        self.binding.start(); self.addCleanup(self.binding.stop)

    def fixture(self):
        base, _, _, _ = fixtures.ModJarTests.fixture(self)
        descriptor = '''modLoader="javafml"
loaderVersion="[4,)"
license="MIT"
[[mods]]
modId="p2wlan"
version="0.1.0"
[[dependencies.p2wlan]]
modId="neoforge"
type="required"
versionRange="[21.1.256]"
ordering="NONE"
side="BOTH"
[[dependencies.p2wlan]]
modId="minecraft"
type="required"
versionRange="[1.21.1]"
ordering="NONE"
side="BOTH"
[[mixins]]
config="p2wlan.neoforge.mixins.json"
'''
        import json
        top = {'META-INF/neoforge.mods.toml': descriptor, **{name: bytecode(65) for name in verify.DEDICATED_CLASSES},
               'p2wlan.neoforge.mixins.json': json.dumps(verify.MIXIN_CONFIG)}
        children = []
        for name in ('common', 'transport', 'native'):
            path = f'META-INF/jarjar/{name}.jar'
            value = base[f'META-INF/jars/{name}.jar']
            if name == 'common':
                with zipfile.ZipFile(io.BytesIO(value)) as archive:
                    value = jar({member: archive.read(member) for member in archive.namelist()} |
                                {member: bytecode(61) for member in verify.WORLD_COMMON})
            if name == 'native':
                with zipfile.ZipFile(io.BytesIO(value)) as archive:
                    value = jar({member: archive.read(member) for member in archive.namelist()} | {'META-INF/MANIFEST.MF':
                                'Manifest-Version: 1.0\r\nAutomatic-Module-Name: io.p2wlan.mc.nativebundle\r\n\r\n'})
            top[path] = value
            artifact = {'common': 'common', 'transport': 'netty-transport', 'native': 'native-bundle'}[name]
            children.append({'path': path, 'identifier': {'group': 'io.p2wlan.mc', 'artifact': artifact},
                             'version': {'range': '[0.1.0,)', 'artifactVersion': '0.1.0'}, 'isObfuscated': False})
        top['META-INF/jarjar/metadata.json'] = json.dumps({'jars': children})
        return top

    def check(self, top, development=True):
        return verify.verify(io.BytesIO(jar(top)), development)

    def test_complete_and_development_opt_in(self):
        top = self.fixture()
        self.assertEqual(self.check(top)[0]['verification-platform'], 'macos-aarch64')
        with self.assertRaisesRegex(verify.VerificationError, 'mod_development_native_disabled'):
            self.check(top, False)

    def test_loader_game_mixin_and_entrypoint_contracts(self):
        for before, after in [('javafml', 'bad'), ('[4,)', '[1,)'), ('[1.21.1]', '[1.21,)'), ('[21.1.256]', '[21.1,)')]:
            top = self.fixture()
            top['META-INF/neoforge.mods.toml'] = top['META-INF/neoforge.mods.toml'].replace(before, after)
            with self.assertRaises(verify.VerificationError): self.check(top)
        for name in verify.DEDICATED_CLASSES:
            top = self.fixture(); top[name] = bytecode(61)
            with self.assertRaises(verify.VerificationError): self.check(top)
            for prefix in (b'net/minecraft/client/', b'net/neoforged/neoforge/client/', b'io/p2wlan/mc/neoforge/client/'):
                top = self.fixture(); top[name] = bytecode(65) + prefix
                with self.assertRaisesRegex(verify.VerificationError, 'mod_dedicated_client_reference'): self.check(top)
        top = self.fixture(); top['p2wlan.neoforge.mixins.json'] = '{}'
        with self.assertRaisesRegex(verify.VerificationError, 'neoforge_mixin_contract'): self.check(top)

    def test_jarjar_coordinates_ranges_and_resource_module_are_fixed(self):
        import json
        for change in ({'identifier': {'group': 'other', 'artifact': 'common'}},
                       {'identifier': {'group': 'io.p2wlan.mc', 'artifact': 'unknown'}},
                       {'version': {'range': '[0.0.0,)', 'artifactVersion': '0.1.0'}},
                       {'isObfuscated': True}, {'unexpected': True}):
            top = self.fixture(); meta = json.loads(top['META-INF/jarjar/metadata.json'])
            meta['jars'][0].update(change); top['META-INF/jarjar/metadata.json'] = json.dumps(meta)
            with self.assertRaisesRegex(verify.VerificationError, 'neoforge_jarjar_metadata'): self.check(top)
        for replacement in ('', 'Manifest-Version: 1.0\nAutomatic-Module-Name: other\n',
                            'Manifest-Version: 1.0\nAutomatic-Module-Name: io.p2wlan.mc.nativebundle\n unexpected-fold\n'):
            top = self.fixture()
            with zipfile.ZipFile(io.BytesIO(top['META-INF/jarjar/native.jar'])) as archive:
                top['META-INF/jarjar/native.jar'] = jar({name: archive.read(name) for name in archive.namelist()} |
                                                      {'META-INF/MANIFEST.MF': replacement})
            with self.assertRaisesRegex(verify.VerificationError, 'neoforge_native_module_identity'): self.check(top)

    def test_nested_members_complete_unique_and_portable(self):
        import json
        for name in ('common', 'transport', 'native'):
            top = self.fixture(); top[f'META-INF/jarjar/{name}.jar'] = jar({})
            with self.assertRaises(verify.VerificationError): self.check(top)
        top = self.fixture(); top['META-INF/jarjar/unlisted.jar'] = jar({})
        with self.assertRaisesRegex(verify.VerificationError, 'neoforge_unlisted_nested_resource'): self.check(top)
        top = self.fixture(); meta = json.loads(top['META-INF/jarjar/metadata.json'])
        meta['jars'][2] = copy.deepcopy(meta['jars'][0]); top['META-INF/jarjar/metadata.json'] = json.dumps(meta)
        with self.assertRaisesRegex(verify.VerificationError, 'neoforge_jarjar_metadata'): self.check(top)
        for prefix in (b'net/minecraft/', b'net/fabricmc/', b'net/neoforged/', b'net/minecraftforge/'):
            top = self.fixture(); top['META-INF/jarjar/transport.jar'] = jar({name: bytecode(61) + prefix for name in verify.TRANSPORT})
            with self.assertRaisesRegex(verify.VerificationError, 'mod_common_game_dependency'): self.check(top)
        for member in verify.WORLD_COMMON:
            top = self.fixture()
            with zipfile.ZipFile(io.BytesIO(top['META-INF/jarjar/common.jar'])) as archive:
                top['META-INF/jarjar/common.jar'] = jar({name: archive.read(name) for name in archive.namelist() if name != member})
            with self.assertRaises(verify.VerificationError): self.check(top)
        top = self.fixture(); top['META-INF/jarjar/common.jar'] = jar({verify.COMMON: bytecode(61)} | fixtures.JNA_FIXTURE)
        with self.assertRaisesRegex(verify.VerificationError, 'neoforge_duplicate_bootstrap_jna'): self.check(top)
        top = self.fixture(); top['io/p2wlan/mc/neoforge/fixture/ServerBootFixture.class'] = bytecode(65)
        with self.assertRaisesRegex(verify.VerificationError, 'neoforge_test_fixture_in_artifact'): self.check(top)


if __name__ == '__main__':
    unittest.main()

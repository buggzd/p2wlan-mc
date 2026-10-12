"""Forge packaging negative contracts; synthetic bytes, not game acceptance."""
import io
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import verify_forge_jar as verify
from test_mod_jar import bytecode, jar, jna_fixture_binding
import test_neoforge_jar as fixtures


class ForgeJarTests(unittest.TestCase):
    def setUp(self):
        binding = jna_fixture_binding()
        binding.start(); self.addCleanup(binding.stop)

    def fixture(self):
        original = fixtures.NeoForgeJarTests.fixture(self)
        top = {name: value for name, value in original.items() if name.startswith('META-INF/jarjar/')}
        top |= {name: bytecode(61) for name in (*verify.DEDICATED_CLASSES, verify.CLIENT, *verify.CLIENT_MIXINS)}
        top['META-INF/mods.toml'] = original['META-INF/neoforge.mods.toml'].split('[[mixins]]')[0] \
                .replace('[4,)', '[52,)').replace('neoforge', 'forge').replace('type="required"', 'mandatory=true').replace('[21.1.256]', '[52.1.16]')
        top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\nMixinConfigs: p2wlan.forge.mixins.json,p2wlan.forge.client.mixins.json\r\n\r\n'
        top['p2wlan.forge.mixins.json'] = json.dumps(verify.MIXIN_CONFIG)
        top['p2wlan.forge.client.mixins.json'] = json.dumps(verify.CLIENT_MIXIN_CONFIG)
        top['pack.mcmeta'] = json.dumps({'pack': {'description': 'P2WLAN', 'pack_format': 48,
                                                'supported_formats': {'min_inclusive': 34, 'max_inclusive': 48}}})
        return top

    def check(self, top, development=True):
        return verify.verify(io.BytesIO(jar(top)), development)

    def test_complete_shared_artifact_and_development_policy(self):
        top = self.fixture()
        self.assertEqual(self.check(top)[0]['verification-platform'], 'macos-aarch64')
        with self.assertRaisesRegex(verify.VerificationError, 'mod_development_native_disabled'):
            self.check(top, False)

    def test_exact_loader_game_version_manifest_and_mixin_configs(self):
        for before, after in [('javafml', 'unknown'), ('[52,)', '[4,)'), ('[52.1.16]', '[52,)'), ('[1.21.1]', '[1.21,)')]:
            top = self.fixture(); top['META-INF/mods.toml'] = top['META-INF/mods.toml'].replace(before, after)
            with self.assertRaises(verify.VerificationError): self.check(top)
        top = self.fixture(); top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\n'
        with self.assertRaisesRegex(verify.VerificationError, 'forge_mixin_manifest'): self.check(top)
        for member in ('p2wlan.forge.mixins.json', 'p2wlan.forge.client.mixins.json'):
            top = self.fixture(); top[member] = '{}'
            with self.assertRaisesRegex(verify.VerificationError, 'forge_mixin_contract'): self.check(top)
            for minimum in (None, '0.8', '0.8.6', '0.8.8'):
                top = self.fixture(); config = json.loads(top[member])
                if minimum is None: del config['minVersion']
                else: config['minVersion'] = minimum
                top[member] = json.dumps(config)
                with self.subTest(member=member, minimum=minimum), self.assertRaisesRegex(verify.VerificationError, 'forge_mixin_contract'):
                    self.check(top)
            for changed in ({'required': 1}, {'injectors': {'defaultRequire': 1.0}}):
                top = self.fixture(); top[member] = json.dumps(json.loads(top[member]) | changed)
                with self.subTest(member=member, changed=changed), self.assertRaisesRegex(verify.VerificationError, 'forge_mixin_contract'):
                    self.check(top)

    def test_exact_resource_and_data_pack_metadata_required(self):
        top = self.fixture(); del top['pack.mcmeta']
        with self.assertRaises(verify.VerificationError): self.check(top)
        for changed in ({}, {'description': 'Fixture'}, {'pack_format': 34}, {'pack_format': 47}, {'pack_format': 48.0},
                        {'supported_formats': {'min_inclusive': 33, 'max_inclusive': 48}},
                        {'supported_formats': {'min_inclusive': 34.0, 'max_inclusive': 48}},
                        {'supported_formats': {'min_inclusive': 34, 'max_inclusive': 49}}):
            top = self.fixture(); pack = json.loads(top['pack.mcmeta'])['pack']
            top['pack.mcmeta'] = json.dumps({'pack': pack | changed} if changed else {})
            with self.subTest(changed=changed), self.assertRaisesRegex(verify.VerificationError, 'forge_pack_metadata'):
                self.check(top)
        top = self.fixture(); pack = json.loads(top['pack.mcmeta'])['pack']; del pack['supported_formats']
        top['pack.mcmeta'] = json.dumps({'pack': pack})
        with self.assertRaisesRegex(verify.VerificationError, 'forge_pack_metadata'): self.check(top)

    def test_all_required_game_classes_are_java_17_and_dedicated_is_client_free(self):
        for member in (*verify.DEDICATED_CLASSES, verify.CLIENT, *verify.CLIENT_MIXINS):
            for mutation in ('missing', 'wrong_bytecode'):
                top = self.fixture()
                if mutation == 'missing': del top[member]
                else: top[member] = bytecode(65)
                with self.assertRaises(verify.VerificationError): self.check(top)
        for member in verify.DEDICATED_CLASSES:
            for prefix in (b'net/minecraft/client/Minecraft', b'net/minecraftforge/client/event/ScreenEvent', b'io/p2wlan/mc/game/client/ClientPlatform'):
                top = self.fixture(); top[member] += prefix
                with self.assertRaisesRegex(verify.VerificationError, 'mod_dedicated_client_reference'): self.check(top)

    def test_server_login_mixin_inventory_is_exact(self):
        for classes in [['ServerConnectionMixin'], verify.MIXIN_CONFIG['mixins'] + ['UnknownMixin']]:
            top = self.fixture()
            top['p2wlan.forge.mixins.json'] = json.dumps(verify.MIXIN_CONFIG | {'mixins': classes})
            with self.assertRaisesRegex(verify.VerificationError, 'forge_mixin_contract'):
                self.check(top)

    def test_jarjar_identity_component_count_and_unlisted_jar_fail(self):
        top = self.fixture(); top['META-INF/jarjar/rogue.jar'] = jar({'anything': b'x'})
        with self.assertRaisesRegex(verify.VerificationError, 'forge_unlisted_nested_resource'): self.check(top)
        top = self.fixture(); value = json.loads(top['META-INF/jarjar/metadata.json']); value['jars'].pop()
        top['META-INF/jarjar/metadata.json'] = json.dumps(value)
        with self.assertRaisesRegex(verify.VerificationError, 'forge_nested_resources_missing'): self.check(top)
        top = self.fixture(); value = json.loads(top['META-INF/jarjar/metadata.json']); value['jars'][0]['identifier']['artifact'] = 'unknown'
        top['META-INF/jarjar/metadata.json'] = json.dumps(value)
        with self.assertRaisesRegex(verify.VerificationError, 'forge_jarjar_metadata'): self.check(top)

    def test_test_fixtures_cannot_enter_production(self):
        for member in ('io/p2wlan/mc/game/fixture/GameClientChecks.class', 'META-INF/fixture.mods.toml',
                       'net/minecraftforge/launcher/p2wlan/P2wlanAssetsLauncher.class',
                       'net/minecraftforge/launcher/p2wlan/P2wlanAssetsLauncher$Helper.class',
                       'io/p2wlan/mc/forge/LocalNativeResourcesTests.class',
                       'io/p2wlan/mc/forge/LocalNativeResourcesTests$1.class',
                       'io/p2wlan/mc/forge/LauncherPolicyTests.class',
                       'io/p2wlan/mc/forge/LauncherPolicyTests$1.class'):
            top = self.fixture(); top[member] = bytecode(61)
            with self.assertRaisesRegex(verify.VerificationError, 'forge_test_fixture_in_artifact'): self.check(top)

    def test_bootstrap_jna_payloads_cannot_enter_top_level_archive(self):
        for member in ('com/sun/jna/Native.class', 'com/sun/jna/Memory.class',
                       'com/sun/jna/win32-x86-64/jnidispatch.dll'):
            top = self.fixture(); top[member] = bytecode(61)
            with self.subTest(member=member), self.assertRaisesRegex(verify.VerificationError, 'forge_duplicate_bootstrap_jna'):
                self.check(top)


if __name__ == '__main__':
    unittest.main()

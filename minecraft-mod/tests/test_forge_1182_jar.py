"""Forge 1.18.2 archive policy mutations; synthetic bytes, no gameplay claim."""
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import verify_forge_1182_jar as verify
import test_legacy_gradle as legacy_fixtures
import test_universal_jar as universal_fixtures
from test_mod_jar import bytecode, jar, jna_fixture_binding
import test_neoforge_jar as fixtures


class Forge1182JarTests(unittest.TestCase):
    def setUp(self):
        binding = jna_fixture_binding()
        binding.start(); self.addCleanup(binding.stop)

    def fixture(self):
        original = fixtures.NeoForgeJarTests.fixture(self)
        top = {name: value for name, value in original.items() if name.startswith('META-INF/jarjar/')}
        top |= {name: bytecode(61) for name in verify.CLASSES}
        top['META-INF/mods.toml'] = '''modLoader="javafml"
loaderVersion="[40,)"
[[mods]]
modId="p2wlan"
version="0.1.0"
[[dependencies.p2wlan]]
modId="forge"
mandatory=true
versionRange="[40.3.12]"
ordering="NONE"
side="BOTH"
[[dependencies.p2wlan]]
modId="minecraft"
mandatory=true
versionRange="[1.18.2]"
ordering="NONE"
side="BOTH"
'''
        top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\nMixinConfigs: ' + verify.SERVER_CONFIG + ',' + verify.CLIENT_CONFIG + '\r\n\r\n'
        top['pack.mcmeta'] = json.dumps({'pack': {'description': 'P2WLAN', 'pack_format': 8}})
        top[verify.SERVER_CONFIG] = json.dumps(verify.MIXIN_CONFIG)
        top[verify.CLIENT_CONFIG] = json.dumps(verify.CLIENT_MIXIN_CONFIG)
        for name, targets in ((verify.SERVER_REFMAP, verify.SERVER_TARGETS), (verify.CLIENT_REFMAP, verify.CLIENT_TARGETS)):
            top[name] = json.dumps({'mappings': targets, 'data': {'searge': targets}})
        for owner, field, original in verify.SHADOW_FIELDS:
            top['io/p2wlan/mc/game/' + owner + '.class'] = legacy_fixtures.LegacyGradleTests.field_fixture(field, original)
        top[verify.ACTIVATION_MIXIN] = universal_fixtures.bytecode(verify.ACTIVATION_MIXIN,
                marker=' '.join(token.decode() for token in sorted(verify.ACTIVATION_TOKENS)), fields=('activationHandler',))
        return top

    def check(self, top, development=True):
        return verify.verify(io.BytesIO(jar(top)), development)

    def test_full_leaf_requires_portable_owners_and_explicit_development_policy(self):
        top = self.fixture()
        self.assertEqual(self.check(top)[0]['verification-platform'], 'macos-aarch64')
        self.assertEqual((len(verify.CLASSES), len(verify.GAME_TYPES), len(verify.DEDICATED_CLASSES)), (60, 52, 13))
        with self.assertRaisesRegex(verify.VerificationError, 'mod_development_native_disabled'): self.check(top, False)
        top = self.fixture(); del top['META-INF/jarjar/metadata.json']
        with self.assertRaises(verify.VerificationError): self.check(top)

    def test_pins_manifest_and_pack_metadata_are_exact(self):
        for before, after in [('javafml', 'unknown'), ('[40,)', '[40,41)'), ('[40.3.12]', '[40,)'), ('[1.18.2]', '[1.18,)')]:
            top = self.fixture(); top['META-INF/mods.toml'] = top['META-INF/mods.toml'].replace(before, after)
            with self.subTest(before=before), self.assertRaisesRegex(verify.VerificationError, 'forge1182_mod_contract'): self.check(top)
        top = self.fixture(); top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\n'
        with self.assertRaisesRegex(verify.VerificationError, 'forge1182_mixin_manifest'): self.check(top)
        for pack in ({}, {'pack': {'description': 'P2WLAN', 'pack_format': 8.0}}, {'pack': {'description': 'P2WLAN', 'pack_format': 15}}):
            top = self.fixture(); top['pack.mcmeta'] = json.dumps(pack)
            with self.subTest(pack=pack), self.assertRaisesRegex(verify.VerificationError, 'forge1182_pack_contract'): self.check(top)

    def test_mixin_inventory_and_marker_are_exact(self):
        for name in (verify.SERVER_CONFIG, verify.CLIENT_CONFIG):
            for mutation in ({'required': 1}, {'injectors': {'defaultRequire': 1.0}}, {'minVersion': '0.8.4'}):
                top = self.fixture(); top[name] = json.dumps(json.loads(top[name]) | mutation)
                with self.subTest(name=name, mutation=mutation), self.assertRaisesRegex(verify.VerificationError, 'forge1182_mixin_contract'): self.check(top)
        top = self.fixture(); config = json.loads(top[verify.CLIENT_CONFIG]); config['client'].remove('ClientConnectionMixin')
        top[verify.CLIENT_CONFIG] = json.dumps(config)
        with self.assertRaisesRegex(verify.VerificationError, 'forge1182_mixin_contract'): self.check(top)

    def test_refmaps_require_actual_old_selectors_owners_and_descriptors(self):
        for name, targets in ((verify.SERVER_REFMAP, verify.SERVER_TARGETS), (verify.CLIENT_REFMAP, verify.CLIENT_TARGETS)):
            for owner, methods in targets.items():
                for selector in methods:
                    for value in (None, 'Lnet/minecraft/Unrelated;wrong()V'):
                        changed = deepcopy(targets)
                        if value is None: del changed[owner][selector]
                        else: changed[owner][selector] = value
                        top = self.fixture(); top[name] = json.dumps({'mappings': changed, 'data': {'searge': changed}})
                        with self.subTest(name=name, owner=owner, selector=selector), self.assertRaisesRegex(verify.VerificationError, 'forge1182_refmap_contract'):
                            self.check(top)
        top = self.fixture(); targets = deepcopy(verify.CLIENT_TARGETS)
        targets['io/p2wlan/mc/game/mixin/ClientConnectionMixin'] = {'connect': 'unverified()V'}
        top[verify.CLIENT_REFMAP] = json.dumps({'mappings': targets, 'data': {'searge': targets}})
        with self.assertRaisesRegex(verify.VerificationError, 'forge1182_refmap_contract'): self.check(top)

    def test_actual_shadow_declarations_are_required(self):
        for owner, field, original in verify.SHADOW_FIELDS:
            for indices in ((2,), (1, 2)):
                top = self.fixture(); top['io/p2wlan/mc/game/' + owner + '.class'] = legacy_fixtures.LegacyGradleTests.field_fixture(field, original, indices)
                with self.subTest(owner=owner, indices=indices), self.assertRaisesRegex(verify.VerificationError, 'forge1182_shadow_srg'): self.check(top)

    def test_client_activation_glue_cannot_be_a_marker_or_unmapped_getter(self):
        top = self.fixture(); top[verify.ACTIVATION_MIXIN] = bytecode(61)
        with self.assertRaises(verify.VerificationError): self.check(top)
        for token in verify.ACTIVATION_TOKENS:
            top = self.fixture(); top[verify.ACTIVATION_MIXIN] = top[verify.ACTIVATION_MIXIN].replace(token, b'x' * len(token))
            with self.subTest(token=token), self.assertRaisesRegex(verify.VerificationError, 'forge1182_client_activation_contract'):
                self.check(top)

    def test_unused_activation_constant_cannot_replace_the_forge_shadow_declaration(self):
        marker = ' '.join(token.decode() for token in sorted(verify.ACTIVATION_TOKENS))
        for fields in (('mappedActivationField',), ('activationHandler', 'extraOwner')):
            top = self.fixture(); top[verify.ACTIVATION_MIXIN] = universal_fixtures.bytecode(verify.ACTIVATION_MIXIN,
                    marker=marker, fields=fields)
            with self.subTest(fields=fields), self.assertRaisesRegex(verify.VerificationError, 'forge1182_client_activation_contract'):
                self.check(top)

    def test_game_and_delegate_inventory_bytecode_and_dedicated_separation(self):
        for member in verify.CLASSES:
            for mutation in ('missing', 'java21'):
                top = self.fixture()
                if mutation == 'missing': del top[member]
                else: top[member] = bytecode(65)
                with self.subTest(member=member, mutation=mutation), self.assertRaises(verify.VerificationError): self.check(top)
        for member in verify.DEDICATED_CLASSES:
            top = self.fixture(); top[member] += b'net/minecraft/client/Minecraft'
            with self.subTest(member=member), self.assertRaisesRegex(verify.VerificationError, 'mod_dedicated_client_reference'): self.check(top)
        for member in ('io/p2wlan/mc/game/fixture/GameFixtureApi.class', 'io/p2wlan/mc/game/server/mixin/ServerConfigurationMixin.class',
                       'io/p2wlan/mc/forge/legacy/ForgeServerEvents.class', 'META-INF/versions/17/io/p2wlan/mc/game/GameText.class'):
            top = self.fixture(); top[member] = bytecode(61)
            with self.subTest(member=member), self.assertRaisesRegex(verify.VerificationError, 'forge1182_class_inventory'): self.check(top)

    def test_nested_owner_identity_and_unlisted_resources_fail(self):
        top = self.fixture(); top['META-INF/jarjar/rogue.jar'] = jar({'unknown': b'x'})
        with self.assertRaisesRegex(verify.VerificationError, 'forge1182_unlisted_nested_resource'): self.check(top)
        top = self.fixture(); metadata = json.loads(top['META-INF/jarjar/metadata.json']); metadata['jars'].pop()
        top['META-INF/jarjar/metadata.json'] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, 'forge1182_nested_resources_missing'): self.check(top)
        top = self.fixture(); metadata = json.loads(top['META-INF/jarjar/metadata.json']); metadata['jars'][0]['identifier']['artifact'] = 'unknown'
        top['META-INF/jarjar/metadata.json'] = json.dumps(metadata)
        with self.assertRaisesRegex(verify.VerificationError, 'forge1182_jarjar_metadata'): self.check(top)


if __name__ == '__main__':
    unittest.main()
